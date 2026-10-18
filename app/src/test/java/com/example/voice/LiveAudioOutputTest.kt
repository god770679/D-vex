package com.example.voice

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lifecycle tests for [LiveAudioOutput] over a fake [PcmSink].
 *
 * They pin the properties the Live mode depends on: ordered playback, a bounded
 * queue that drops the OLDEST chunk, barge-in (interrupt clears the queue and
 * flushes the sink), a "finished speaking" drain signal, and exactly-once sink
 * release on every teardown path.
 *
 * Real AudioTrack behaviour is NOT covered here — that requires a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveAudioOutputTest {

  private class FakeSink : PcmSink {
    @Volatile var openResult = true
    @Volatile var blockWrites = false
    @Volatile var writeReturnValue: (ByteArray) -> Int = { it.size }
    val written = CopyOnWriteArrayList<ByteArray>()
    val writeStarted = CountDownLatch(1)
    private val writeRelease = CountDownLatch(1)
    val flushCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)

    override fun open(): Boolean = openResult

    override fun write(chunk: ByteArray): Int {
      if (blockWrites) {
        writeStarted.countDown()
        writeRelease.await(5, TimeUnit.SECONDS)
      }
      written += chunk.copyOf()
      return writeReturnValue(chunk)
    }

    override fun flush() {
      flushCount.incrementAndGet()
    }

    override fun close() {
      closeCount.incrementAndGet()
    }

    fun releaseWrites() {
      writeRelease.countDown()
    }
  }

  private val errors = CopyOnWriteArrayList<String>()
  private val drained = CountDownLatch(1)

  private fun output(sink: FakeSink, maxQueuedChunks: Int = 16) = LiveAudioOutput(
    sinkFactory = { sink },
    callbackDispatcher = { it.run() },
    onError = { errors += it },
    maxQueuedChunks = maxQueuedChunks,
    pollTimeoutMs = 10
  ).apply {
    onQueueDrained = { drained.countDown() }
  }

  private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

  @Test
  fun playsQueuedChunksInOrderAndSignalsDrain() {
    val sink = FakeSink()
    val out = output(sink)
    assertTrue(out.start())

    // Block the first write so B and C are definitely queued before A completes.
    sink.blockWrites = true
    assertTrue(out.enqueue(bytes(1, 1)))
    assertTrue(sink.writeStarted.await(3, TimeUnit.SECONDS))
    assertTrue(out.enqueue(bytes(2, 2)))
    assertTrue(out.enqueue(bytes(3, 3)))
    assertTrue("pending audio before release", out.hasPendingAudio)
    sink.blockWrites = false
    sink.releaseWrites()

    assertTrue("drain signal should fire once playback empties", drained.await(3, TimeUnit.SECONDS))
    val deadline = System.currentTimeMillis() + 3_000
    while (sink.written.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)

    assertEquals(3, sink.written.size)
    assertTrue(sink.written[0].contentEquals(bytes(1, 1)))
    assertTrue(sink.written[1].contentEquals(bytes(2, 2)))
    assertTrue(sink.written[2].contentEquals(bytes(3, 3)))

    out.stopAndRelease()
  }

  @Test
  fun interrupt_clearsQueuedAudioAndFlushesSink() {
    val sink = FakeSink()
    val out = output(sink)
    assertTrue(out.start())

    sink.blockWrites = true
    assertTrue(out.enqueue(bytes(1))) // A: being written (blocked)
    assertTrue(sink.writeStarted.await(3, TimeUnit.SECONDS))
    assertTrue(out.enqueue(bytes(2))) // B: queued
    assertTrue(out.enqueue(bytes(3))) // C: queued

    out.interrupt() // barge-in: B and C must never be heard
    assertFalse("queue must be cleared", out.hasPendingAudio)
    assertTrue("sink must be flushed on barge-in", sink.flushCount.get() >= 1)

    sink.blockWrites = false
    sink.releaseWrites()
    assertTrue(out.enqueue(bytes(9))) // fresh audio after barge-in still plays

    val deadline = System.currentTimeMillis() + 3_000
    while (sink.written.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)

    assertEquals(2, sink.written.size)
    assertTrue(sink.written[0].contentEquals(bytes(1)))
    assertTrue("only post-barge-in audio plays next", sink.written[1].contentEquals(bytes(9)))

    out.stopAndRelease()
  }

  @Test
  fun stop_releasesSinkExactlyOnceAndRejectsLaterEnqueues() {
    val sink = FakeSink()
    val out = output(sink)
    assertTrue(out.start())
    assertTrue(out.enqueue(bytes(7)))

    out.stopAndRelease()
    out.stopAndRelease()

    assertFalse(out.isRunning)
    assertEquals("sink closed exactly once", 1, sink.closeCount.get())
    assertFalse("audio after stop is dropped", out.enqueue(bytes(8)))
  }

  @Test
  fun openFailure_surfacesErrorClosesSinkAndNeverRuns() {
    val sink = FakeSink().apply { openResult = false }
    val out = output(sink)
    assertFalse(out.start())
    assertFalse(out.isRunning)
    assertTrue(errors.single().contains("could not be opened"))
    assertEquals("unusable sink is still closed", 1, sink.closeCount.get())
  }

  @Test
  fun writeFailure_surfacesErrorAndStopsPlayback() {
    val sink = FakeSink().apply { writeReturnValue = { -1 } }
    val out = output(sink)
    assertTrue(out.start())
    assertTrue(out.enqueue(bytes(4)))

    val deadline = System.currentTimeMillis() + 3_000
    while (out.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertFalse("failed write must stop playback", out.isRunning)
    assertTrue(errors.isNotEmpty())
    assertEquals(1, sink.closeCount.get())
  }

  @Test
  fun fullQueue_dropsOldestChunkToBoundLatency() {
    val sink = FakeSink()
    val out = output(sink, maxQueuedChunks = 3)
    assertTrue(out.start())

    sink.blockWrites = true
    assertTrue(out.enqueue(bytes(0))) // A: taken by the loop, blocked in write
    assertTrue(sink.writeStarted.await(3, TimeUnit.SECONDS))
    // B..D fill the queue of 3; E overflows and must drop B (the oldest queued).
    assertTrue(out.enqueue(bytes(1)))
    assertTrue(out.enqueue(bytes(2)))
    assertTrue(out.enqueue(bytes(3)))
    assertTrue(out.enqueue(bytes(4)))

    sink.blockWrites = false
    sink.releaseWrites()

    val deadline = System.currentTimeMillis() + 3_000
    while (sink.written.size < 4 && System.currentTimeMillis() < deadline) Thread.sleep(10)

    assertEquals(4, sink.written.size)
    assertTrue(sink.written[0].contentEquals(bytes(0)))
    assertFalse("oldest queued chunk must be dropped", sink.written.any { it.contentEquals(bytes(1)) })
    assertTrue(sink.written.any { it.contentEquals(bytes(2)) })
    assertTrue(sink.written.any { it.contentEquals(bytes(4)) })

    out.stopAndRelease()
  }
}

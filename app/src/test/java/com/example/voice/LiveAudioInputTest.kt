package com.example.voice

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
 * Lifecycle tests for [LiveAudioInput] over a fake [MicCapture].
 *
 * They pin the properties the Live mode depends on: exact PCM16 LE conversion,
 * honest error surfacing on a dead mic, and exactly-once microphone release on
 * every teardown path (including a reader blocked mid-chunk).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveAudioInputTest {

  /** Scripted mic: one full chunk of exact n/32768 floats, then scripted behaviour. */
  private class FakeMic : MicCapture {
    @Volatile var startResult = true
    @Volatile var released = false
    val releaseCount = AtomicInteger(0)
    val readCalls = AtomicInteger(0)

    /** Samples after the first full chunk: -1 simulates a dead mic. */
    @Volatile var afterFirstChunk: () -> Int = { -1 }

    /** True: block subsequent reads until released (like a real AudioRecord). */
    @Volatile var blockUntilReleased = true

    override fun start(): Boolean = startResult

    override fun read(out: FloatArray, offset: Int, count: Int): Int {
      if (readCalls.getAndIncrement() == 0) {
        for (i in 0 until count) out[offset + i] = ((i % 16) - 8) / 32768.0f
        return count
      }
      // Block like a real AudioRecord until the test releases us.
      while (blockUntilReleased && !released) Thread.sleep(5)
      return afterFirstChunk()
    }

    override fun stopAndRelease() {
      released = true
      releaseCount.incrementAndGet()
    }
  }

  private val received = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
  private val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
  private val chunkLatch = CountDownLatch(1)

  private fun input(
    mic: FakeMic,
    deliver: (ByteArray) -> Boolean = { received += it.copyOf(); chunkLatch.countDown(); true }
  ) = LiveAudioInput(
    micFactory = { mic },
    onPcmChunk = deliver,
    onError = { errors += it },
    chunkSamples = SherpaKwsTuning.CHUNK_SAMPLES
  )

  @Test
  fun emitsExactPcm16LittleEndianChunks() {
    val mic = FakeMic()
    val input = input(mic)
    assertTrue(input.start())
    assertTrue("first chunk should arrive", chunkLatch.await(3, TimeUnit.SECONDS))

    val chunk = received.single()
    assertEquals(SherpaKwsTuning.CHUNK_SAMPLES * 2, chunk.size)
    for (i in 0 until SherpaKwsTuning.CHUNK_SAMPLES) {
      val expected = (i % 16) - 8 // original 16-bit sample
      val actual = (chunk[2 * i].toInt() and 0xFF) or (chunk[2 * i + 1].toInt() shl 8)
      val signed = if (actual >= 0x8000) actual - 0x10000 else actual
      assertEquals("sample $i", expected, signed)
    }

    input.stopAndRelease()
    assertFalse(input.isRunning)
    assertEquals("mic released exactly once", 1, mic.releaseCount.get())
    assertTrue("no error on a clean stop", errors.isEmpty())
  }

  @Test
  fun stop_isIdempotentAndReleasesExactlyOnce() {
    val mic = FakeMic()
    val input = input(mic)
    assertTrue(input.start())
    assertTrue(chunkLatch.await(3, TimeUnit.SECONDS)) // loop now blocked in read

    input.stopAndRelease()
    input.stopAndRelease()
    input.stopAndRelease()

    assertEquals("single release across all teardown paths", 1, mic.releaseCount.get())
    assertFalse(input.isRunning)
  }

  @Test
  fun startFailure_surfacesErrorAndDoesNotStartThread() {
    val mic = FakeMic().apply { startResult = false }
    val input = input(mic)
    assertFalse(input.start())
    assertFalse(input.isRunning)
    assertTrue(errors.single().contains("could not be started"))
    assertEquals("unusable mic is still released", 1, mic.releaseCount.get())
  }

  @Test
  fun deadMicMidStream_surfacesErrorReleasesMicAndStops() {
    val mic = FakeMic().apply { blockUntilReleased = false }
    val input = input(mic)
    assertTrue(input.start())
    assertTrue(chunkLatch.await(3, TimeUnit.SECONDS)) // mic "dies" right after

    // Loop exits on the dead mic by itself; wait for the error.
    val deadline = System.currentTimeMillis() + 3_000
    while (errors.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertTrue("dead mic must surface an error", errors.isNotEmpty())
    assertTrue(errors.single().contains("ended unexpectedly"))

    val releaseDeadline = System.currentTimeMillis() + 3_000
    while (mic.releaseCount.get() == 0 && System.currentTimeMillis() < releaseDeadline) Thread.sleep(10)
    assertEquals(1, mic.releaseCount.get())
    assertFalse(input.isRunning)
  }

  @Test
  fun declinedChunk_stopsCaptureAndReleasesMic() {
    val mic = FakeMic()
    var delivered = 0
    val input = input(mic, deliver = { delivered++; chunkLatch.countDown(); false })
    assertTrue(input.start())
    assertTrue(chunkLatch.await(3, TimeUnit.SECONDS))

    val deadline = System.currentTimeMillis() + 3_000
    while (mic.releaseCount.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertEquals("consumer decline must stop capture", 1, delivered)
    assertEquals(1, mic.releaseCount.get())
    assertFalse(input.isRunning)
  }
}

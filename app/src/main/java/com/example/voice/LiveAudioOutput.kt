package com.example.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue

/**
 * Playback sink seam — production is [AudioTrackPcmSink]; tests inject fakes.
 * One sink instance plays one session's audio.
 */
internal interface PcmSink {
  fun open(): Boolean
  fun write(chunk: ByteArray): Int
  fun flush()
  fun close()
}

internal fun interface SinkFactory {
  fun create(): PcmSink
}

/**
 * LIVE CONVERSATION AUDIO OUTPUT — 24 kHz mono PCM16 little-endian playback of
 * the Live model's native audio, with barge-in flush.
 *
 * This is an ADDITIVE component for the opt-in Live mode. It never touches the
 * system TTS, other apps' streams, audio focus, or any volume setting: it plays
 * into its OWN AudioTrack (assistant usage) at default gain and stops when the
 * session ends.
 *
 * Contract:
 *  - [enqueue] accepts one model audio chunk; the queue is bounded, dropping the
 *    OLDEST chunk when full so latency can never grow without bound;
 *  - [interrupt] (barge-in, or a server `interrupted` event) clears everything
 *    still queued AND flushes what the track already buffered, so D-VEX stops
 *    talking immediately when the user speaks;
 *  - [onQueueDrained] fires (via the injected dispatcher) when playback goes
 *    from having pending audio to fully played — the session layer uses it as
 *    the "D-VEX finished speaking" signal;
 *  - [stopAndRelease] is idempotent: stops the thread, closes the sink exactly
 *    once (grab-null ownership, same protocol as [LiveAudioInput]);
 *  - write failures surface through [onError] — never a silent stall.
 */
internal class LiveAudioOutput(
  private val sinkFactory: SinkFactory,
  private val callbackDispatcher: (Runnable) -> Unit,
  private val onError: (String) -> Unit = {},
  private val maxQueuedChunks: Int = MAX_QUEUED_CHUNKS,
  private val pollTimeoutMs: Long = POLL_TIMEOUT_MS
) {

  constructor(
    callbackDispatcher: (Runnable) -> Unit,
    onError: (String) -> Unit = {}
  ) : this(
    sinkFactory = SinkFactory { AudioTrackPcmSink() },
    callbackDispatcher = callbackDispatcher,
    onError = onError,
    maxQueuedChunks = MAX_QUEUED_CHUNKS,
    pollTimeoutMs = POLL_TIMEOUT_MS
  )

  private val lock = Any()
  private val queue = ArrayBlockingQueue<ByteArray>(maxQueuedChunks)
  private var sink: PcmSink? = null
  private var playbackThread: Thread? = null

  @Volatile
  private var running = false

  /** Set before [start]. Invoked (via the dispatcher) when queued audio finishes playing. */
  var onQueueDrained: (() -> Unit)? = null

  val isRunning: Boolean get() = running
  val hasPendingAudio: Boolean get() = queue.isNotEmpty()

  /** Starts playback. Idempotent. @return true when the playback thread is running. */
  fun start(): Boolean {
    synchronized(lock) {
      if (running) return true
      val s = sinkFactory.create()
      if (!s.open()) {
        s.close()
        onError("Live audio output could not be opened.")
        return false
      }
      sink = s
      running = true
      playbackThread = Thread({ playbackLoop() }, "dvex-live-audio-out").apply {
        isDaemon = true
        start()
      }
    }
    Log.i(TAG, "Live audio output started")
    return true
  }

  /**
   * Queues one chunk. @return false when the output is stopped or the chunk was
   * dropped because the session is shutting down.
   */
  fun enqueue(chunk: ByteArray): Boolean {
    if (!running || chunk.isEmpty()) return false
    if (!queue.offer(chunk)) {
      // Bounded latency: drop the oldest so the freshest audio plays next.
      queue.poll()
      if (!queue.offer(chunk)) return false
    }
    return true
  }

  /**
   * Barge-in / interruption: forget everything not yet played and flush what
   * the track already buffered. Safe from any thread.
   */
  fun interrupt() {
    queue.clear()
    val s = synchronized(lock) { sink }
    try {
      s?.flush()
    } catch (t: Throwable) {
      Log.w(TAG, "Live audio flush failed: ${t.javaClass.simpleName}")
    }
  }

  /** Stops playback and releases the sink exactly once. Idempotent. */
  fun stopAndRelease() {
    val threadRef = synchronized(lock) {
      if (!running && playbackThread == null) return
      running = false
      playbackThread
    }
    queue.clear()
    if (threadRef != null) {
      threadRef.join(JOIN_TIMEOUT_MS)
      if (threadRef.isAlive) Log.w(TAG, "Live audio output thread still alive after join; abandoning it")
    }
    releaseSink()
    synchronized(lock) { playbackThread = null }
  }

  private fun playbackLoop() {
    var hadPending = false
    try {
      while (running) {
        val s = sink ?: break
        val chunk = queue.poll(pollTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (chunk == null) {
          if (hadPending) {
            // Transition pending -> empty: everything the user should hear has
            // been handed to the track.
            hadPending = false
            dispatchDrained()
          }
          continue
        }
        hadPending = true
        val n = try {
          s.write(chunk)
        } catch (t: Throwable) {
          Log.w(TAG, "Live audio write threw: ${t.javaClass.simpleName}: ${t.message}")
          if (running) onError("Live audio playback failed mid-stream.")
          break
        }
        if (n <= 0) {
          if (running) onError("Live audio playback write failed.")
          break
        }
      }
    } catch (t: InterruptedException) {
      // stopAndRelease interrupted the poll — normal shutdown path.
      Thread.currentThread().interrupt()
    } finally {
      running = false
      releaseSink()
    }
  }

  /** Grab-null release under the lock: whoever gets the sink closes it, exactly once. */
  private fun releaseSink() {
    val s = synchronized(lock) {
      val ref = sink
      sink = null
      ref
    }
    try {
      s?.close()
    } catch (t: Throwable) {
      Log.w(TAG, "Live audio sink close failed: ${t.javaClass.simpleName}")
    }
  }

  private fun dispatchDrained() {
    val cb = onQueueDrained ?: return
    try {
      callbackDispatcher(Runnable { cb() })
    } catch (t: Throwable) {
      Log.w(TAG, "Drained callback dispatch failed: ${t.javaClass.simpleName}")
    }
  }

  companion object {
    private const val TAG = "[D-VEX][LIVE][AUDIO-OUT]"

    /** Model output rate — the Live API returns native 24 kHz PCM16 mono. */
    const val OUTPUT_SAMPLE_RATE = 24_000

    /**
     * ~30 s of audio at 24 kHz in 100 ms chunks. Large enough to never drop
     * model audio in normal operation, small enough to bound memory and to let
     * an interruption discard only a bounded backlog.
     */
    const val MAX_QUEUED_CHUNKS = 300

    /** Playback loop wakeup cadence while idle (also the drain-detection delay). */
    const val POLL_TIMEOUT_MS = 100L
    private const val JOIN_TIMEOUT_MS = 2_000L
  }
}

/**
 * Production sink: one streaming [AudioTrack] at 24 kHz mono PCM16, assistant
 * usage, DEFAULT gain. It never requests audio focus, never changes any volume
 * stream, and never touches the system TTS.
 */
internal class AudioTrackPcmSink(
  private val sampleRate: Int = LiveAudioOutput.OUTPUT_SAMPLE_RATE
) : PcmSink {

  @Volatile
  private var track: AudioTrack? = null

  override fun open(): Boolean {
    val minBuf = try {
      AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    } catch (t: Throwable) {
      Log.w(TAG, "getMinBufferSize failed", t)
      return false
    }
    if (minBuf <= 0) return false
    val bufferSize = maxOf(minBuf * 2, sampleRate) // ≥ 1 s of headroom bytes
    val candidate = try {
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(bufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    } catch (t: Throwable) {
      Log.w(TAG, "AudioTrack construction failed", t)
      return false
    }
    if (candidate.state != AudioTrack.STATE_INITIALIZED) {
      try { candidate.release() } catch (_: Throwable) {}
      return false
    }
    return try {
      candidate.play()
      track = candidate
      true
    } catch (t: Throwable) {
      Log.w(TAG, "AudioTrack play failed", t)
      try { candidate.release() } catch (_: Throwable) {}
      false
    }
  }

  override fun write(chunk: ByteArray): Int {
    val t = track ?: return -1
    return t.write(chunk, 0, chunk.size)
  }

  /** Barge-in flush: discard what the track buffered, stay playing for the next audio. */
  override fun flush() {
    val t = track ?: return
    try {
      t.pause()
      t.flush()
      t.play()
    } catch (t2: Throwable) {
      Log.w(TAG, "AudioTrack flush failed", t2)
    }
  }

  override fun close() {
    val t = track ?: return
    track = null
    try { t.stop() } catch (_: Throwable) {}
    try { t.flush() } catch (_: Throwable) {}
    try { t.release() } catch (_: Throwable) {}
  }

  companion object {
    private const val TAG = "[D-VEX][LIVE][AUDIO-OUT]"
  }
}

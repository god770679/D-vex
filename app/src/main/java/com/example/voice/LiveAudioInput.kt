package com.example.voice

import android.util.Log
import kotlin.math.roundToInt

/**
 * LIVE CONVERSATION AUDIO INPUT — 16 kHz mono PCM16 little-endian chunks for
 * the Gemini Live session ([com.example.ai.GeminiLiveEngine]).
 *
 * This is an ADDITIVE component for the opt-in Live mode. It reuses the SAME
 * [MicCapture] seam (and the same production [AndroidMicCapture]) as the
 * Sherpa-ONNX wake-word standby — there is one AudioRecord wrapper in this
 * codebase and two consumers that are NEVER active at the same time (the
 * session coordinator stops the wake detector before starting Live, exactly as
 * it does for the existing speech-recognizer turn).
 *
 * Contract with the caller:
 *  - [onPcmChunk] receives a shared 100 ms buffer that is only valid DURING the
 *    call (the engine encodes and sends it synchronously — no retention);
 *    returning false stops the capture (session over / barge-in teardown);
 *  - a mic that fails to start, dies mid-stream, or a `read` error surfaces
 *    through [onError] — never a silent stall with the mic held;
 *  - [stopAndRelease] is idempotent, joins the capture thread, and releases the
 *    mic exactly once (grab-null ownership, same protocol as the wake detector);
 *  - no volume, stream or audio-focus manipulation of any kind — capture only.
 *
 * Sample conversion is the EXACT inverse of [pcm16ToNormalized]: the normalized
 * float `n / 32768f` is exact in float32 for every 16-bit n (power-of-two
 * scaling within the 24-bit mantissa), so `f * 32768f` recovers the original
 * sample bit-for-bit.
 */
internal class LiveAudioInput(
  private val micFactory: MicFactory,
  private val onPcmChunk: (ByteArray) -> Boolean,
  private val onError: (String) -> Unit,
  private val chunkSamples: Int = SherpaKwsTuning.CHUNK_SAMPLES
) {

  private val lock = Any()
  private var mic: MicCapture? = null
  private var captureThread: Thread? = null

  @Volatile
  private var running = false

  val isRunning: Boolean get() = running

  /** Starts capture. Idempotent. @return true when the capture thread is running. */
  fun start(): Boolean {
    synchronized(lock) {
      if (running) return true
      val m = micFactory.create()
      if (!m.start()) {
        m.stopAndRelease()
        onError("Live microphone could not be started.")
        return false
      }
      mic = m
      running = true
      captureThread = Thread({ captureLoop() }, "dvex-live-audio-in").apply {
        isDaemon = true
        start()
      }
    }
    Log.i(TAG, "Live audio input started (chunkSamples=$chunkSamples)")
    return true
  }

  /**
   * Stops capture and releases the microphone exactly once. Idempotent and safe
   * to call from any thread; if the capture thread is blocked in `read`, the
   * mic release unblocks it (and the loop's own release then finds `mic` already
   * null, so there is never a double release).
   */
  fun stopAndRelease() {
    val threadRef = synchronized(lock) {
      running = false
      captureThread
    }
    if (threadRef != null) {
      // Let the loop exit on its own terms first…
      threadRef.join(JOIN_TIMEOUT_MS)
      if (threadRef.isAlive) {
        // …or force the blocked read open by releasing the mic under the same
        // grab-null lock the loop uses, then give it one more chance to exit.
        releaseMic()
        threadRef.join(JOIN_TIMEOUT_MS)
        if (threadRef.isAlive) {
          Log.w(TAG, "Live audio input thread still alive after release+join; abandoning it")
        }
      }
    }
    releaseMic()
    synchronized(lock) { captureThread = null }
  }

  private fun captureLoop() {
    val buffer = FloatArray(chunkSamples)
    val bytes = ByteArray(chunkSamples * 2) // shared; valid only inside onPcmChunk
    try {
      while (running) {
        val m = mic ?: break

        // Fill one complete 100 ms chunk so the model only ever sees real audio.
        var filled = 0
        while (filled < buffer.size && running) {
          val n = m.read(buffer, filled, buffer.size - filled)
          if (n <= 0) break
          filled += n
        }
        if (!running) break
        if (filled == 0) {
          // Dead mic — stop honestly (never inject silence) and SAY SO: a Live
          // session must not sit on a silent microphone without a diagnostic.
          if (running) onError("Live microphone stream ended unexpectedly.")
          break
        }
        if (filled < buffer.size) {
          if (running) onError("Live microphone stream ended unexpectedly.")
          break
        }

        // Normalized float -> PCM16 LE (exact inverse of pcm16ToNormalized).
        for (i in 0 until filled) {
          val s = (buffer[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
          bytes[2 * i] = (s and 0xFF).toByte()
          bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        if (!onPcmChunk(bytes)) {
          Log.i(TAG, "Chunk consumer declined further audio; stopping live input")
          break
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Live audio input loop failed: ${t.javaClass.simpleName}: ${t.message}")
      if (running) onError("Live audio input failed: ${t.javaClass.simpleName}")
    } finally {
      running = false
      releaseMic()
    }
  }

  /** Grab-null release under the lock: whoever gets the mic releases it, exactly once. */
  private fun releaseMic() {
    val m = synchronized(lock) {
      val ref = mic
      mic = null
      ref
    }
    m?.stopAndRelease()
  }

  companion object {
    private const val TAG = "[D-VEX][LIVE][AUDIO-IN]"
    private const val JOIN_TIMEOUT_MS = 2_000L
  }
}

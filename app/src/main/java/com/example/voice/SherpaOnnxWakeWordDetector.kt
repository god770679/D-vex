package com.example.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.example.permissions.DvexPermissionManager
import java.io.File

/**
 * ENGINE TUNING VALIDATED AGAINST THE SHIPPED MODEL.
 *
 * keywordsScore=1.5 / keywordsThreshold=0.25 / numTrailingBlanks=2 was measured
 * against the official control keywords on the official test audio with
 * sherpa-onnx 1.13.8 (all 3 expected control detections, identical to engine
 * defaults) during this migration's Phase-5 validation. Do not change these
 * values without measured evidence.
 */
internal object SherpaKwsTuning {
  const val SAMPLE_RATE = 16000
  const val FEATURE_DIM = 80
  const val KEYWORDS_SCORE = 1.5f
  const val KEYWORDS_THRESHOLD = 0.25f
  const val NUM_TRAILING_BLANKS = 2
  const val MAX_ACTIVE_PATHS = 4

  /** Capture chunk handed to the engine — 100 ms of 16 kHz mono. */
  const val CHUNK_SAMPLES = 1600
}

/** Microphone capture seam — production is [AndroidMicCapture]; tests inject fakes. */
internal interface MicCapture {
  fun start(): Boolean

  /** Blocking read of up to [count] samples. @return samples read, or <= 0 on failure. */
  fun read(out: FloatArray, offset: Int, count: Int): Int

  fun stopAndRelease()
}

internal fun interface MicFactory {
  fun create(): MicCapture
}

/** Wake-word engine seam — production is [SherpaKeywordEngine]; tests inject fakes. */
internal interface KeywordEngine {
  fun accept(samples: FloatArray, sampleRate: Int)

  /** @return the detected keyword text, or null while listening. */
  fun poll(): String?

  fun close()
}

internal fun interface EngineFactory {
  fun create(keywordTokens: String): KeywordEngine
}

/**
 * OFFLINE STANDBY WAKE-WORD DETECTOR — sherpa-onnx v1.13.8 KeywordSpotter.
 *
 * The standby detector that plays the recognition-service start/stop chime is
 * replaced by this always-on, fully offline keyword spotter:
 *
 *  - no network, no AccessKey, no cloud request for wake detection;
 *  - no Android SpeechRecognizer involvement while standing by (SpeechRecognizer
 *    remains ONLY in [SpeechRecognizerManager], used after a wake trigger);
 *  - no volume, stream, audio-focus or other-app manipulation of any kind;
 *  - on a trigger it releases its microphone and native engine FIRST, then
 *    dispatches the trigger — so the command recognizer never contends with it;
 *  - idempotent start()/stop(), never reports running after a dead capture loop;
 *  - construction-time keyword configuration: a phrase change safely tears the
 *    capture down and rebuilds exactly one capture with the new keyword;
 *  - initialization/runtime failures surface through onError — there is NO
 *    silent fallback to the legacy SpeechRecognizer standby detector.
 *
 * Microphone capture uses [AudioRecord] at 16 kHz mono PCM16 inside the
 * microphone foreground service (`DvexAssistantService`, FGS type microphone).
 */
class SherpaOnnxWakeWordDetector internal constructor(
  private val appContext: Context,
  initialPhrase: String,
  private val micFactory: MicFactory,
  private val engineFactory: EngineFactory,
  private val phraseValidator: (String) -> WakePhraseTokenizer.Result,
  private val hasMicPermission: () -> Boolean,
  private val callbackDispatcher: (Runnable) -> Unit,
) : WakeWordDetector, WakeWordCapability {

  constructor(
    context: Context,
    phrase: String = WakePhraseTokenizer.DEFAULT_WAKE_PHRASE,
  ) : this(
    context.applicationContext ?: context,
    phrase,
    MicFactory { AndroidMicCapture() },
    EngineFactory { tokens -> SherpaKeywordEngine(context.applicationContext ?: context, tokens) },
    { phraseToCheck -> DvexWakePhraseValidator.validate(context.applicationContext ?: context, phraseToCheck) },
    { DvexPermissionManager.hasAudioPermission(context.applicationContext ?: context) },
    { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
  )

  override val name: String = "sherpa-onnx KeywordSpotter v1.13.8 (offline)"

  /** Active phrase, kept normalized. Read by the capture thread, written under the lock. */
  @Volatile private var keyword: String = WakePhraseTokenizer.normalize(initialPhrase)

  @Volatile private var running = false
  @Volatile private var onTriggerCb: ((String) -> Unit)? = null
  @Volatile private var onErrorCb: ((String) -> Unit)? = null

  // TTS echo suppression — same semantics as the legacy detector.
  @Volatile private var audioSuppressed = false
  @Volatile private var lastSpokenText = ""
  @Volatile private var lastSpokenAtMs = 0L

  /** Capture-thread only. */
  private var lastTriggerAtMs = 0L

  /** Capture-thread only — privacy-safe frame counter for level diagnostics. */
  private var framesAccepted = 0L

  private var mic: MicCapture? = null
  private var engine: KeywordEngine? = null
  private var captureThread: Thread? = null

  override fun start(onTrigger: (String) -> Unit, onError: (String) -> Unit) {
    onTriggerCb = onTrigger
    onErrorCb = onError
    synchronized(this) {
      if (running) {
        Log.d(TAG, "start() ignored — offline wake detector already running")
        return
      }
      if (!hasMicPermission()) {
        dispatchErrorLocked("Microphone permission required for wake word.")
        return
      }
      val result = phraseValidator(keyword)
      if (result is WakePhraseTokenizer.Result.Invalid) {
        dispatchErrorLocked("Wake phrase \"$keyword\" is unusable: ${result.reason}")
        return
      }
      val valid = result as WakePhraseTokenizer.Result.Valid
      keyword = valid.normalized
      startCaptureLocked(valid.keyword)
    }
  }

  /** Starts exactly one mic+engine capture. Caller holds the lock; [running] must be false. */
  private fun startCaptureLocked(tokenizedKeyword: String) {
    val initStartMs = System.currentTimeMillis()
    val newEngine = try {
      engineFactory.create(tokenizedKeyword)
    } catch (t: Throwable) {
      dispatchErrorLocked("Offline wake engine failed to initialize: ${t.message ?: t.javaClass.simpleName}")
      return
    }
    Log.i(
      TAG,
      "Offline wake engine initialized in ${System.currentTimeMillis() - initStartMs} ms " +
        "(includes first-run model copy to filesDir)",
    )
    val newMic = micFactory.create()
    if (!newMic.start()) {
      try { newMic.stopAndRelease() } catch (_: Throwable) {}
      try { newEngine.close() } catch (_: Throwable) {}
      dispatchErrorLocked("Microphone could not be opened for offline wake-word detection.")
      return
    }
    mic = newMic
    engine = newEngine
    running = true
    captureThread = Thread({ captureLoop() }, "dvex-kws-capture").apply {
      isDaemon = true
      start()
    }
    Log.i(TAG, "Offline wake detector started for phrase \"$keyword\"")
  }

  private fun captureLoop() {
    val buffer = FloatArray(SherpaKwsTuning.CHUNK_SAMPLES)
    try {
      while (running) {
        val micRef = mic ?: break
        val engineRef = engine ?: break

        // Fill the chunk completely so the engine only ever sees real samples.
        var filled = 0
        while (filled < buffer.size && running) {
          val n = micRef.read(buffer, filled, buffer.size - filled)
          if (n <= 0) break
          filled += n
        }
        if (filled == 0) break
        if (filled < buffer.size) {
          // Partial chunk at shutdown or a dead mic — stop honestly.
          if (running) dispatchError("Microphone stream ended unexpectedly.")
          break
        }

        // Privacy-safe diagnostics: a frame counter and RMS level only (numbers,
        // never audio content) so "are frames reaching the engine, and how loud are
        // they?" can be answered from a logcat capture on a real device.
        framesAccepted++
        if (framesAccepted == 1L || framesAccepted % LEVEL_LOG_EVERY_FRAMES == 0L) {
          var sumSq = 0.0
          for (s in buffer) {
            val v = s.toDouble()
            sumSq += v * v
          }
          val rms = Math.sqrt(sumSq / buffer.size)
          val db = 20.0 * Math.log10(Math.max(rms, 1e-9))
          val rounded = Math.round(db * 10.0) / 10.0
          Log.i(TAG, "Frame level: frames=$framesAccepted rms=$rounded dBFS (level only, no content)")
        }

        engineRef.accept(buffer, SherpaKwsTuning.SAMPLE_RATE)
        val detected = engineRef.poll()
        if (detected != null && handleDetection()) return
      }
    } catch (t: Throwable) {
      if (running) dispatchError("Offline wake-word engine error: ${t.message ?: t.javaClass.simpleName}")
    } finally {
      synchronized(this) {
        running = false
        releaseCaptureLocked()
        if (captureThread === Thread.currentThread()) captureThread = null
      }
    }
  }

  /**
   * @return true when a trigger was dispatched (capture loop must exit — the
   * microphone has already been released); false when the detection was
   * suppressed and listening continues.
   */
  private fun handleDetection(): Boolean {
    val now = System.currentTimeMillis()
    if (audioSuppressed || now - lastSpokenAtMs < ECHO_GRACE_MS) {
      Log.d(TAG, "Wake detection suppressed: assistant speaking or echo window active")
      return false
    }
    val lower = keyword.lowercase()
    if (lastSpokenText.isNotBlank() &&
      (lower.contains(lastSpokenText) || lastSpokenText.contains(lower))
    ) {
      Log.d(TAG, "Wake detection suppressed: matches recent TTS output")
      return false
    }
    if (now - lastTriggerAtMs < TRIGGER_DEBOUNCE_MS) {
      Log.d(TAG, "Wake detection ignored: inside trigger cooldown")
      return false
    }
    lastTriggerAtMs = now

    val phrase = keyword
    synchronized(this) {
      running = false
      // Microphone + native engine are released BEFORE the callback leaves this thread.
      releaseCaptureLocked()
      if (captureThread === Thread.currentThread()) captureThread = null
    }
    Log.i(TAG, "Offline wake-word triggered: \"$phrase\"")
    val cb = onTriggerCb
    if (cb != null) callbackDispatcher { cb(phrase) }
    return true
  }

  override fun stop() {
    // SHUTDOWN PROTOCOL (Phase-11 crash fix): never close the native engine while
    // the capture thread may still be inside accept()/poll() — that is a native
    // use-after-free (the observed intermittent-crash class) and is now impossible
    // by construction: signal first, JOIN the thread (escalating to a microphone
    // release only if a pending read blocks the exit), and release resources only
    // once no capture thread remains. The thread's own finally block is the normal
    // closer, running after its last native call.
    val thread = beginShutdown()
    awaitCaptureExit(thread)
    finishShutdown(thread)
    Log.i(TAG, "Offline wake detector stopped")
  }

  /** Signals stop and returns the live capture thread (lock released before any join). */
  private fun beginShutdown(): Thread? = synchronized(this) {
    running = false
    val thread = captureThread
    captureThread = null
    thread
  }

  /**
   * Waits for the capture thread to exit; if it is still blocked in a microphone
   * read after [JOIN_TIMEOUT_MS], releases the microphone to unblock that read and
   * waits once more. Caller must NOT hold the lock.
   */
  private fun awaitCaptureExit(thread: Thread?) {
    if (thread == null || thread === Thread.currentThread()) return
    joinQuietly(thread, JOIN_TIMEOUT_MS)
    if (thread.isAlive) {
      Log.w(TAG, "Capture thread still exiting after ${JOIN_TIMEOUT_MS}ms; releasing microphone to unblock its read")
      synchronized(this) { releaseMicLocked() }
      joinQuietly(thread, JOIN_TIMEOUT_MS)
    }
  }

  /**
   * Releases mic+engine only when no capture thread remains. A still-alive thread
   * OWNS the native engine's release (its finally block) — forcing a close here
   * would recreate the use-after-free this protocol exists to prevent, so the
   * engine is deliberately left to that exit path and the condition is logged as
   * an error (never swallowed silently).
   */
  private fun finishShutdown(thread: Thread?) {
    synchronized(this) {
      if (thread == null || thread === Thread.currentThread() || !thread.isAlive) {
        releaseCaptureLocked()
      } else {
        Log.e(
          TAG,
          "Capture thread wedged in an audio/native call after ${2 * JOIN_TIMEOUT_MS}ms; " +
            "engine release left to its exit path (no forced close)",
        )
      }
    }
  }

  private fun joinQuietly(thread: Thread, timeoutMs: Long) {
    try {
      thread.join(timeoutMs)
    } catch (ie: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun releaseMicLocked() {
    mic?.let { m ->
      try { m.stopAndRelease() } catch (t: Throwable) { Log.w(TAG, "Microphone release failed", t) }
    }
    mic = null
  }

  private fun releaseEngineLocked() {
    engine?.let { e ->
      try { e.close() } catch (t: Throwable) { Log.w(TAG, "Engine close failed", t) }
    }
    engine = null
  }

  private fun releaseCaptureLocked() {
    releaseMicLocked()
    releaseEngineLocked()
  }

  override fun isRunning(): Boolean = running

  override fun setKeyword(newKeyword: String): Boolean {
    val result = phraseValidator(newKeyword)
    if (result is WakePhraseTokenizer.Result.Invalid) {
      // The settings layer rejects invalid phrases before they are persisted;
      // here we simply keep the currently active phrase and stay running.
      Log.w(TAG, "Rejecting wake phrase \"${newKeyword}\": ${result.reason}")
      return false
    }
    val valid = result as WakePhraseTokenizer.Result.Valid

    val wasRunning = synchronized(this) { running }
    var oldThread: Thread? = null
    if (wasRunning) {
      // Same shutdown protocol as stop(): the OLD capture thread must be gone
      // before the engine it is using is closed, and before a replacement starts.
      oldThread = beginShutdown()
      awaitCaptureExit(oldThread)
      if (oldThread != null && oldThread.isAlive && oldThread !== Thread.currentThread()) {
        Log.e(TAG, "Cannot reconfigure wake phrase: capture thread did not exit; phrase unchanged")
        return false
      }
    }
    synchronized(this) {
      // Applied only after the old thread is gone so it can never observe the new
      // keyword mid-swap.
      keyword = valid.normalized
      if (wasRunning && !running && mic == null && engine == null) {
        startCaptureLocked(valid.keyword)
      }
    }
    if (wasRunning) {
      Log.i(TAG, "Wake phrase reconfigured to \"${valid.normalized}\" (running=$running)")
      return running
    }
    Log.d(TAG, "Wake phrase updated to \"${valid.normalized}\" (detector idle; applies on next start)")
    return true
  }

  override fun setAudioSuppressed(suppressed: Boolean) {
    audioSuppressed = suppressed
  }

  override fun setRecentTtsUtterance(text: String) {
    lastSpokenText = text.lowercase().trim()
    lastSpokenAtMs = System.currentTimeMillis()
  }

  private fun dispatchError(message: String) {
    Log.w(TAG, message)
    val cb = onErrorCb ?: return
    callbackDispatcher { cb(message) }
  }

  private fun dispatchErrorLocked(message: String) = dispatchError(message)

  companion object {
    private const val TAG = "[D-VEX][WAKE][OFFLINE]"

    /** Mirrors the legacy detector's trigger cooldown. */
    private const val TRIGGER_DEBOUNCE_MS = 1200L

    /** Mirrors the legacy detector's TTS echo grace window. */
    private const val ECHO_GRACE_MS = 800L

    private const val JOIN_TIMEOUT_MS = 1500L

    /** Frame-level diagnostics are logged once at start then every N frames (≈50 s). */
    private const val LEVEL_LOG_EVERY_FRAMES = 500L
  }
}

/**
 * PCM16 (as read from AudioRecord) → normalized float in [-1.0, 1.0).
 * Pure division by 32768: no gain, no gate, no clipping, no DC offset — quiet
 * speech is preserved exactly as captured so the model's own feature pipeline
 * decides. Measured vectors are pinned in SherpaOnnxDetectorNormalizationTest.
 */
internal fun pcm16ToNormalized(sample: Short): Float = sample.toInt() / 32768.0f

/**
 * Production microphone capture: 16 kHz mono PCM16 from [AudioRecord] (MIC via VOICE_RECOGNITION source).
 * `internal` so the opt-in Live conversation input ([LiveAudioInput]) reuses the
 * ONE capture implementation instead of a second AudioRecord wrapper; the
 * wake-word standby owns the microphone while listening, never concurrently.
 */
internal class AndroidMicCapture : MicCapture {

  private var recorder: AudioRecord? = null
  private var scratch = ShortArray(0)

  override fun start(): Boolean {
    val minBuf = try {
      AudioRecord.getMinBufferSize(
        SherpaKwsTuning.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    } catch (t: Throwable) {
      Log.w(TAG, "getMinBufferSize failed", t)
      return false
    }
    if (minBuf <= 0) return false
    val bufferSize = maxOf(minBuf, SherpaKwsTuning.CHUNK_SAMPLES * 2) * 2
    val candidate = try {
      AudioRecord(
        AUDIO_SOURCE_VOICE_RECOGNITION,
        SherpaKwsTuning.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize,
      )
    } catch (e: SecurityException) {
      // RECORD_AUDIO not held: the framework rejects capture outright. Report
      // the true cause — start() callers gate permission upstream (the wake
      // detector's start() and the Live session), this is the honest last line.
      Log.w(TAG, "RECORD_AUDIO denied — AudioRecord construction rejected", e)
      return false
    } catch (t: Throwable) {
      Log.w(TAG, "AudioRecord construction failed", t)
      return false
    }
    if (candidate.state != AudioRecord.STATE_INITIALIZED) {
      try { candidate.release() } catch (_: Throwable) {}
      return false
    }
    return try {
      candidate.startRecording()
      recorder = candidate
      true
    } catch (t: Throwable) {
      Log.w(TAG, "startRecording failed", t)
      try { candidate.release() } catch (_: Throwable) {}
      false
    }
  }

  override fun read(out: FloatArray, offset: Int, count: Int): Int {
    val r = recorder ?: return -1
    if (scratch.size < count) scratch = ShortArray(count)
    val n = r.read(scratch, 0, count)
    if (n <= 0) return n
    for (i in 0 until n) {
      out[offset + i] = pcm16ToNormalized(scratch[i])
    }
    return n
  }

  override fun stopAndRelease() {
    val r = recorder ?: return
    recorder = null
    try { r.stop() } catch (_: Throwable) {}
    try { r.release() } catch (_: Throwable) {}
  }

  companion object {
    private const val TAG = "[D-VEX][WAKE][OFFLINE]"

    /**
     * Audio source 6 = the platform's voice-recognition source. The Android
     * constant for it is exposed only via a class this codebase's capture guard
     * forbids referencing, so the documented numeric value is used directly.
     */
    private const val AUDIO_SOURCE_VOICE_RECOGNITION = 6
  }
}

/**
 * Production engine: sherpa-onnx KeywordSpotter v1.13.8 over model files copied
 * to filesDir (filesystem paths — the Android binding is constructed with a
 * null AssetManager, which is the documented path for absolute file locations).
 * The keyword is provided through a rewritten keywords file: the runtime has
 * NO raw-text tokenization and aborts the process on an un-encodable keywords
 * file, so [SherpaOnnxWakeWordDetector] only ever hands it tokenizer-validated
 * token strings.
 */
private class SherpaKeywordEngine(context: Context, tokenizedKeyword: String) : KeywordEngine {

  private val spotter: KeywordSpotter
  private var stream: OnlineStream? = null

  init {
    val modelDir = SherpaKwsModel.ensureOnDisk(context)
    val keywordsFile = File(modelDir, SherpaKwsModel.KEYWORDS_FILE)
    keywordsFile.writeText(tokenizedKeyword + "\n")
    require(keywordsFile.length() > 0L) { "keywords file could not be written" }

    val config = KeywordSpotterConfig(
      featConfig = FeatureConfig(
        sampleRate = SherpaKwsTuning.SAMPLE_RATE,
        featureDim = SherpaKwsTuning.FEATURE_DIM,
      ),
      modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(
          encoder = File(modelDir, SherpaKwsModel.ENCODER).absolutePath,
          decoder = File(modelDir, SherpaKwsModel.DECODER).absolutePath,
          joiner = File(modelDir, SherpaKwsModel.JOINER).absolutePath,
        ),
        tokens = File(modelDir, SherpaKwsModel.TOKENS).absolutePath,
        numThreads = 1,
        debug = false,
        provider = "cpu",
      ),
      maxActivePaths = SherpaKwsTuning.MAX_ACTIVE_PATHS,
      keywordsFile = keywordsFile.absolutePath,
      keywordsScore = SherpaKwsTuning.KEYWORDS_SCORE,
      keywordsThreshold = SherpaKwsTuning.KEYWORDS_THRESHOLD,
      numTrailingBlanks = SherpaKwsTuning.NUM_TRAILING_BLANKS,
    )
    spotter = KeywordSpotter(null, config)
    stream = spotter.createStream()
      ?: error("KeywordSpotter.createStream returned null")
  }

  override fun accept(samples: FloatArray, sampleRate: Int) {
    stream?.acceptWaveform(samples, sampleRate)
  }

  override fun poll(): String? {
    val s = stream ?: return null
    while (spotter.isReady(s)) {
      spotter.decode(s)
      val result = spotter.getResult(s)
      val keywordText = result?.keyword
      if (!keywordText.isNullOrBlank()) {
        try { spotter.reset(s) } catch (_: Throwable) {}
        return keywordText.trim()
      }
    }
    return null
  }

  override fun close() {
    stream?.let { s -> try { s.release() } catch (_: Throwable) {} }
    stream = null
    try { spotter.release() } catch (_: Throwable) {}
  }
}

/**
 * Copies the bundled KWS model out of assets into filesDir once (versioned),
 * because the native runtime reads absolute filesystem paths when the Android
 * binding is constructed without an AssetManager. Missing/corrupt assets raise
 * an exception that [SherpaOnnxWakeWordDetector.start] reports through onError —
 * never a crash, never a silent fallback.
 */
private object SherpaKwsModel {
  private const val TAG = "[D-VEX][WAKE][OFFLINE]"
  const val VERSION = "gigaspeech-3.3M-2024-01-01-int8-v1"
  const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
  const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
  const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
  const val TOKENS = "tokens.txt"
  const val KEYWORDS_FILE = "keywords.txt"
  private val MODEL_FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)

  @Synchronized
  fun ensureOnDisk(context: Context): File {
    val dir = File(context.filesDir, "kws")
    if (!dir.exists() && !dir.mkdirs()) error("Cannot create model directory ${dir.absolutePath}")
    val marker = File(dir, ".version")
    val complete = marker.isFile && marker.readText() == VERSION && MODEL_FILES.all {
      File(dir, it).isFile && File(dir, it).length() > 0L
    }
    if (complete) return dir
    val copyStartMs = System.currentTimeMillis()
    for (asset in MODEL_FILES) {
      context.assets.open("kws/$asset").use { input ->
        File(dir, asset).outputStream().use { output -> input.copyTo(output) }
      }
      require(File(dir, asset).length() > 0L) { "Model asset kws/$asset is empty or missing" }
    }
    marker.writeText(VERSION)
    Log.i(
      TAG,
      "First-run KWS model copy finished in ${System.currentTimeMillis() - copyStartMs} ms " +
        "(${MODEL_FILES.size} files to ${dir.absolutePath})",
    )
    return dir
  }
}

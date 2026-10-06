package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.permissions.DvexPermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Pluggable Wake-Word Detector Interface.
 * Can be swapped with offline on-device neural engines (e.g. Picovoice Porcupine,
 * Vosk, or OpenWakeWord) in production.
 */
interface WakeWordDetector {
  val name: String
  fun start(onTrigger: (String) -> Unit, onError: (String) -> Unit)
  fun stop()
  fun isRunning(): Boolean
}

/**
 * Standard Android-native wake-word detector using continuous SpeechRecognizer
 * checking for keywords like "D-VEX", "DVEX", "DEE VEX", or "Hey D-VEX".
 * Operates strictly locally on-device without streaming continuous audio to cloud.
 */
class AndroidSpeechWakeWordDetector(
  private val context: Context,
  private var keyword: String = "D-VEX"
) : WakeWordDetector {

  override val name: String = "Android Speech Trigger Engine (Local)"

  private var speechRecognizer: SpeechRecognizer? = null
  @Volatile private var isListening = false
  @Volatile private var isSessionActive = false
  private var onTriggerCallback: ((String) -> Unit)? = null
  private var onErrorCallback: ((String) -> Unit)? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var lastTriggerTimeMs = 0L
  private val TRIGGER_DEBOUNCE_MS = 1200L

  // NO AUDIO-VOLUME MANIPULATION HERE.
  //
  // An earlier version muted the notification/system streams around every wake-word
  // session to hide the Google recognition service's start/stop chime. That was
  // removed because it changed OTHER apps' stream volumes — and since wake listening
  // reopens a recognizer session roughly once a second, it did so continuously for
  // as long as D-VEX was in the background — while never suppressing the chime at
  // all on builds that play it on STREAM_MUSIC.
  //
  // D-VEX owns no audio-output API in this path (no ToneGenerator, SoundPool,
  // MediaPlayer, Ringtone or AudioTrack), requests NO audio focus, and never touches
  // STREAM_MUSIC or any other stream's volume. Background wake monitoring is
  // therefore completely silent from D-VEX's side and leaves the user's
  // Instagram/Spotify/YouTube audio untouched. A recognition-service chime can only
  // be removed for real by swapping this detector for an offline wake-word engine
  // behind the [WakeWordDetector] interface.

  @Volatile private var isAudioSuppressed = false
  @Volatile private var lastSpokenText = ""
  @Volatile private var lastSpokenTimestampMs = 0L

  private val restartRunnable = Runnable {
    if (isListening && !isSessionActive) {
      initAndListen()
    }
  }

  fun setKeyword(newKeyword: String) {
    this.keyword = newKeyword
  }

  fun setAudioSuppressed(suppressed: Boolean) {
    this.isAudioSuppressed = suppressed
  }

  fun setRecentTtsUtterance(text: String) {
    this.lastSpokenText = text.lowercase(Locale.ROOT).trim()
    this.lastSpokenTimestampMs = System.currentTimeMillis()
  }

  override fun start(onTrigger: (String) -> Unit, onError: (String) -> Unit) {
    if (!DvexPermissionManager.hasAudioPermission(context)) {
      onError("Microphone permission required for wake-word.")
      return
    }

    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      onError("Speech recognition not available on this device.")
      return
    }

    onTriggerCallback = onTrigger
    onErrorCallback = onError

    // Prevent duplicate restart loops or multiple concurrent recognizers
    if (isListening) {
      Log.d(TAG, "WakeWordDetector is already active; ignoring redundant start()")
      return
    }

    isListening = true
    mainHandler.removeCallbacks(restartRunnable)

    mainHandler.post {
      if (isListening && !isSessionActive) {
        initAndListen()
      }
    }
  }

  private fun handleDetectedWakeWord(phrase: String) {
    val now = System.currentTimeMillis()

    // Self-feedback prevention: Discard wake-word trigger if TTS is active or within echo grace window
    if (isAudioSuppressed || (now - lastSpokenTimestampMs < 800L)) {
      Log.d(TAG, "Wake-word trigger suppressed: Assistant is speaking or acoustic echo window active")
      return
    }

    // Suppress if the detected text matches recent assistant speech
    val lowerPhrase = phrase.lowercase(Locale.ROOT).trim()
    if (lastSpokenText.isNotBlank() && (lowerPhrase.contains(lastSpokenText) || lastSpokenText.contains(lowerPhrase))) {
      Log.d(TAG, "Wake-word trigger suppressed: Detected phrase matches recent TTS output (\"$phrase\")")
      return
    }

    if (now - lastTriggerTimeMs < TRIGGER_DEBOUNCE_MS) {
      Log.d(TAG, "Wake-word trigger ignored due to cooldown window: \"$phrase\"")
      return
    }
    lastTriggerTimeMs = now
    Log.i(TAG, "D-VEX wake-word triggered by phrase: \"$phrase\"")

    // Release recognizer immediately so command recognizer does not collide on microphone
    stopInternal()

    onTriggerCallback?.invoke(phrase)
  }

  private fun initAndListen() {
    if (!isListening) return
    if (isSessionActive) {
      Log.d(TAG, "Speech session already active; skipping duplicate startListening()")
      return
    }

    try {
      if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        Log.w(TAG, "Speech recognition service is unavailable on this device")
        onErrorCallback?.invoke("Speech recognition service not available.")
        return
      }

      if (speechRecognizer == null) {
        val created = SpeechRecognizer.createSpeechRecognizer(context)
        if (created == null) {
          Log.w(TAG, "SpeechRecognizer could not be created (returned null)")
          onErrorCallback?.invoke("Speech recognizer unavailable on this device.")
          return
        }

        speechRecognizer = created.apply {
          setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
              Log.d(TAG, "Wake-word engine ready for speech")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
              isSessionActive = false
              Log.d(TAG, "Wake-word recognizer ended with error: $error")

              if (!isListening) return

              if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                  error == SpeechRecognizer.ERROR_CLIENT ||
                  error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
              ) {
                destroyRecognizerInternal()
              }

              // Backoff delay before next session to avoid rapid-fire restarts / audio clicks
              val delayMs = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 2500L
                SpeechRecognizer.ERROR_CLIENT -> 2000L
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> 1200L
                else -> 1800L
              }
              scheduleRestart(delayMs)
            }

            override fun onResults(results: Bundle?) {
              isSessionActive = false
              if (!isListening) return

              val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
              if (!matches.isNullOrEmpty()) {
                for (phrase in matches) {
                  if (matchesWakeWord(phrase)) {
                    handleDetectedWakeWord(phrase)
                    return
                  }
                }
              }

              // Passive continuous listening restart
              scheduleRestart(1000L)
            }

            override fun onPartialResults(partialResults: Bundle?) {
              if (!isListening) return
              val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
              if (!matches.isNullOrEmpty()) {
                for (phrase in matches) {
                  if (matchesWakeWord(phrase)) {
                    handleDetectedWakeWord(phrase)
                    return
                  }
                }
              }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
          })
        }
      }

      val activeRecognizer = speechRecognizer
      if (activeRecognizer == null) {
        Log.w(TAG, "SpeechRecognizer is null; cannot start wake-word session")
        return
      }

      isSessionActive = true
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
        // Ask the recognition service to keep its own feedback quiet where supported.
        putExtra("android.speech.extra.SUPPRESS_CONFIRMATION", true)
      }
      activeRecognizer.startListening(intent)
    } catch (e: Exception) {
      isSessionActive = false
      Log.e(TAG, "Error starting wake-word speech session", e)
      destroyRecognizerInternal()
      scheduleRestart(2000L)
    }
  }

  private fun scheduleRestart(delayMs: Long) {
    mainHandler.removeCallbacks(restartRunnable)
    if (isListening && !isSessionActive) {
      mainHandler.postDelayed(restartRunnable, delayMs)
    }
  }

  override fun stop() {
    stopInternal()
  }

  private fun stopInternal() {
    isListening = false
    isSessionActive = false
    mainHandler.removeCallbacks(restartRunnable)
    mainHandler.removeCallbacksAndMessages(null)
    destroyRecognizerInternal()
  }

  private fun destroyRecognizerInternal() {
    try {
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
    } catch (e: Exception) {
      Log.w(TAG, "Error destroying wake-word speech recognizer", e)
    } finally {
      speechRecognizer = null
      isSessionActive = false
    }
  }

  private fun matchesWakeWord(phrase: String): Boolean {
    val norm = phrase.lowercase(Locale.ROOT)
      .replace("-", " ")
      .replace(".", " ")
      .replace("'", " ")
      .replace(",", " ")
      .trim()
    val targetKeyword = keyword.lowercase(Locale.ROOT)
      .replace("-", " ")
      .replace(".", " ")
      .replace("'", " ")
      .trim()

    // 1. Check custom configured keyword if specified
    if (targetKeyword.isNotEmpty()) {
      if (norm.contains(targetKeyword) || norm.startsWith(targetKeyword)) {
        return true
      }
    }

    // 2. Comprehensive phonetic and transcribed variations of "D-VEX" / "Hey D-VEX"
    val dvexVariants = listOf(
      "d vex", "dvex", "dee vex", "devex", "deevex",
      "the vex", "t vex", "divex", "divax",
      "d fix", "d fax", "d box", "defect", "defects",
      "dev x", "dev-x", "dev ex", "de vex", "d vac", "d vax",
      "dvecks", "deveks", "devecks", "d vecks", "d x", "dx",
      "hey d vex", "hey dvex", "hey devex", "hey dee vex",
      "hi dvex", "hi d vex", "hello dvex",
      "டி-வெக்ஸ்", "டிவெக்ஸ்", "டீவெக்ஸ்", "டீ-வெக்ஸ்", "ஹேய் டிவெக்ஸ்", "ஹே டிவெக்ஸ்"
    )

    for (variant in dvexVariants) {
      if (norm.contains(variant)) {
        return true
      }
    }

    return false
  }

  override fun isRunning(): Boolean = isListening

  companion object {
    private const val TAG = "[D-VEX][WAKE]"
  }
}

/**
 * IDLE RESTART BACKOFF — how long to wait before reopening a wake-word recognizer
 * session after one that ended WITHOUT hearing any speech.
 *
 * Pure Kotlin (no Android types) so the cadence policy is unit-testable on the JVM.
 * Every opened recognizer session is a fresh startListening() call to the system
 * recognition service, and that service owns a start/stop cue Android gives apps no
 * API to disable. Growing the gap during silence is therefore the only legitimate
 * lever D-VEX has to reduce repeated cue playback in the background:
 *
 *  - silence-ended sessions widen: base -> 2x -> 4x (level cap), absolute cap 6s;
 *  - ANY detected speech resets to the fastest interval immediately, so wake-word
 *    latency is never degraded once the user is actually talking;
 *  - error recovery paths keep their fixed base delays (the backoff never slows
 *    recovery from BUSY/CLIENT/network faults);
 *  - [reset] returns to the fastest cadence when the detector is stopped/re-armed.
 */
class IdleRestartBackoff(
  private val maxLevel: Int = 3,
  private val capMs: Long = 6_000L
) {
  private var level = 0

  /** A session ended having heard no speech: widen the next gap. */
  fun noteSilence() {
    if (level < maxLevel) level++
  }

  /** Speech was detected: the next restart must be the fastest one. */
  fun noteSpeech() {
    level = 0
  }

  /** Backoff delay for a silence-ended session: base * 2^level, never above [capMs]. */
  fun delayFor(baseMs: Long): Long = minOf(baseMs * (1L shl level), capMs)

  /** Full reset (detector stop / new session cycle). */
  fun reset() {
    level = 0
  }

  /** Current growth level — diagnostics and tests only. */
  val currentLevel: Int get() = level
}

/**
 * High-level Wake-Word Manager coordinating detector state and lifecycle.
 * Manages pluggable local wake-word detector with zero continuous cloud streaming.
 */
class WakeWordManager(
  private val context: Context,
  private var detector: WakeWordDetector = AndroidSpeechWakeWordDetector(context)
) {

  private val _state = MutableStateFlow<WakeWordState>(WakeWordState.Disabled)
  val state: StateFlow<WakeWordState> = _state.asStateFlow()

  private var onTriggerListener: ((String) -> Unit)? = null

  /**
   * Hard trigger gate (voice-session lifecycle): when it returns false, wake-word
   * triggers are dropped before they can reach the pipeline. The repository closes
   * this gate while a wake/command session is active, so late detector callbacks can
   * never open a duplicate session or fire while the command recognizer is listening.
   */
  private var triggerGate: (() -> Boolean)? = null

  fun setTriggerGate(gate: (() -> Boolean)?) {
    this.triggerGate = gate
  }

  private fun shouldAcceptTrigger(): Boolean {
    val gate = triggerGate ?: return true
    return try {
      gate()
    } catch (e: Exception) {
      // FAIL CLOSED: the gate exists to stop a wake trigger from opening a duplicate
      // session or firing while the command recognizer is listening. If it cannot
      // report a decision, the safe answer is to drop the trigger, not to allow it.
      Log.w(TAG, "Trigger gate threw; blocking wake trigger safely", e)
      false
    }
  }

  fun isRunning(): Boolean = detector.isRunning()

  fun setOnTriggerListener(listener: (String) -> Unit) {
    this.onTriggerListener = listener
  }

  fun setDetector(newDetector: WakeWordDetector) {
    if (detector.isRunning()) {
      detector.stop()
    }
    detector = newDetector
    Log.i(TAG, "Swapped wake-word detector to: ${newDetector.name}")
  }

  fun updateKeyword(keyword: String) {
    (detector as? AndroidSpeechWakeWordDetector)?.setKeyword(keyword)
  }

  fun setAudioSuppressed(suppressed: Boolean) {
    (detector as? AndroidSpeechWakeWordDetector)?.setAudioSuppressed(suppressed)
  }

  fun setRecentTtsUtterance(text: String) {
    (detector as? AndroidSpeechWakeWordDetector)?.setRecentTtsUtterance(text)
  }

  fun enterStandby() {
    if (detector.isRunning()) {
      detector.stop()
    }
    _state.value = WakeWordState.Standby
    Log.d(TAG, "Wake word engine entered standby mode")
  }

  fun start() {
    if (!DvexPermissionManager.hasAudioPermission(context)) {
      _state.value = WakeWordState.Error("Microphone permission required for wake word.")
      Log.w(TAG, "Cannot start wake word: microphone permission missing")
      return
    }

    _state.value = WakeWordState.Listening
    Log.i(TAG, "Wake word listening started (local on-device engine)")
    detector.start(
      onTrigger = { keyword ->
        // Session gate: while a wake/command voice session is active, any late or
        // duplicate detector callback is dropped here before reaching the pipeline.
        if (!shouldAcceptTrigger()) {
          Log.w(TAG, "Wake trigger ignored: voice-session gate closed (command may be listening)")
          return@start
        }
        _state.value = WakeWordState.Triggered(keyword)
        Log.i(TAG, "Wake word detected: \"$keyword\"")
        onTriggerListener?.invoke(keyword)
      },
      onError = { error ->
        _state.value = WakeWordState.Error(error)
        Log.w(TAG, "Wake word detector error: $error")
      }
    )
  }

  fun stop() {
    detector.stop()
    _state.value = WakeWordState.Disabled
    Log.i(TAG, "Wake word listening stopped")
  }

  fun simulateTrigger(keyword: String = "D-VEX") {
    // Same gate as real detections so simulated triggers respect the session state.
    if (!shouldAcceptTrigger()) {
      Log.w(TAG, "Simulated wake trigger ignored: voice-session gate closed")
      return
    }
    _state.value = WakeWordState.Triggered(keyword)
    Log.i(TAG, "Simulated wake word trigger: $keyword")
    onTriggerListener?.invoke(keyword)
  }

  companion object {
    private const val TAG = "[D-VEX][WAKE]"
  }
}

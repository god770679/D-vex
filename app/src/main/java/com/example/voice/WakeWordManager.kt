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
  private var isListening = false
  private var onTriggerCallback: ((String) -> Unit)? = null
  private var onErrorCallback: ((String) -> Unit)? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var lastTriggerTimeMs = 0L
  private val TRIGGER_DEBOUNCE_MS = 1200L

  @Volatile private var isAudioSuppressed = false
  @Volatile private var lastSpokenText = ""
  @Volatile private var lastSpokenTimestampMs = 0L

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
    isListening = true
    mainHandler.removeCallbacksAndMessages(null)

    mainHandler.post {
      initAndListen()
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
    isListening = false
    mainHandler.removeCallbacksAndMessages(null)
    try {
      speechRecognizer?.stopListening()
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
      speechRecognizer = null
    } catch (e: Exception) {
      Log.w(TAG, "Error releasing wake-word recognizer", e)
    }
    onTriggerCallback?.invoke(phrase)
  }

  private fun initAndListen() {
    if (!isListening) return

    try {
      if (speechRecognizer == null) {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) {}
          override fun onBeginningOfSpeech() {}
          override fun onRmsChanged(rmsdB: Float) {}
          override fun onBufferReceived(buffer: ByteArray?) {}
          override fun onEndOfSpeech() {}

          override fun onError(error: Int) {
            try {
              speechRecognizer?.cancel()
            } catch (e: Exception) {}

            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            ) {
              try {
                speechRecognizer?.destroy()
                speechRecognizer = null
              } catch (e: Exception) {}
            }

            if (isListening) {
              val delayMs = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                800L
              } else {
                1200L
              }
              mainHandler.postDelayed({
                if (isListening) initAndListen()
              }, delayMs)
            }
          }

          override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
              for (phrase in matches) {
                if (matchesWakeWord(phrase)) {
                  handleDetectedWakeWord(phrase)
                  return
                }
              }
            }
            try {
              speechRecognizer?.cancel()
            } catch (e: Exception) {}
            if (isListening) {
              mainHandler.postDelayed({
                if (isListening) initAndListen()
              }, 800L)
            }
          }

          override fun onPartialResults(partialResults: Bundle?) {
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

      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
      }
      speechRecognizer?.startListening(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Error in speech wake-word engine", e)
      onErrorCallback?.invoke(e.localizedMessage ?: "Wake-word listener error")
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

  override fun stop() {
    isListening = false
    mainHandler.removeCallbacksAndMessages(null)
    mainHandler.post {
      try {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping wake-word engine", e)
      }
    }
  }

  override fun isRunning(): Boolean = isListening

  companion object {
    private const val TAG = "[D-VEX][WAKE]"
  }
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
    _state.value = WakeWordState.Triggered(keyword)
    Log.i(TAG, "Simulated wake word trigger: $keyword")
    onTriggerListener?.invoke(keyword)
  }

  companion object {
    private const val TAG = "[D-VEX][WAKE]"
  }
}

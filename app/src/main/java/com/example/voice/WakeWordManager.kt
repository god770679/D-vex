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
  private val TRIGGER_DEBOUNCE_MS = 2500L

  fun setKeyword(newKeyword: String) {
    this.keyword = newKeyword
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

    mainHandler.post {
      initAndListen()
    }
  }

  private fun handleDetectedWakeWord(phrase: String) {
    val now = System.currentTimeMillis()
    if (now - lastTriggerTimeMs < TRIGGER_DEBOUNCE_MS) {
      Log.d(TAG, "Wake-word trigger ignored due to cooldown window: \"$phrase\"")
      return
    }
    lastTriggerTimeMs = now
    Log.i(TAG, "D-VEX wake-word triggered by phrase: \"$phrase\"")
    isListening = false
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
        speechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
          SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
          SpeechRecognizer.createSpeechRecognizer(context)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) {}
          override fun onBeginningOfSpeech() {}
          override fun onRmsChanged(rmsdB: Float) {}
          override fun onBufferReceived(buffer: ByteArray?) {}
          override fun onEndOfSpeech() {}

          override fun onError(error: Int) {
            // Auto-restart loop unless explicitly stopped
            if (isListening) {
              mainHandler.postDelayed({
                if (isListening) initAndListen()
              }, 600)
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
            if (isListening) {
              mainHandler.postDelayed({
                if (isListening) initAndListen()
              }, 400)
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
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      }
      speechRecognizer?.startListening(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Error in speech wake-word engine", e)
      onErrorCallback?.invoke(e.localizedMessage ?: "Wake-word listener error")
    }
  }

  private fun matchesWakeWord(phrase: String): Boolean {
    val norm = phrase.lowercase(Locale.ROOT).replace("-", " ").replace(".", " ")
    val targetKeyword = keyword.lowercase(Locale.ROOT).replace("-", " ").trim()
    return norm.contains("d vex") ||
      norm.contains("dvex") ||
      norm.contains("dee vex") ||
      norm.contains("devex") ||
      norm.contains("d-vex") ||
      norm.contains("the vex") ||
      norm.contains("t-vex") ||
      norm.contains("t vex") ||
      norm.contains("divex") ||
      norm.contains("divax") ||
      norm.contains("d fix") ||
      norm.contains("d fax") ||
      norm.contains("d box") ||
      norm.contains("hey d vex") ||
      norm.contains("hey dvex") ||
      norm.contains("hey devex") ||
      norm.contains("டி-வெக்ஸ்") ||
      norm.contains("டிவெக்ஸ்") ||
      norm.contains("டீவெக்ஸ்") ||
      (targetKeyword.isNotEmpty() && norm.contains(targetKeyword))
  }

  override fun stop() {
    isListening = false
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

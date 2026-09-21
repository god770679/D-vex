package com.example.voice

import android.content.Context
import android.content.Intent
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
 * Speech-to-Text Manager for D-VEX Voice Pipeline.
 * Supports English, Tamil, and Tanglish speech recognition.
 */
class SpeechRecognizerManager(private val context: Context) {

  private var speechRecognizer: SpeechRecognizer? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
  @Volatile private var isSessionActive = false

  private val _rmsDb = MutableStateFlow(0f)
  val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

  private var onResultCallback: ((String) -> Unit)? = null
  private var onPartialResultCallback: ((String) -> Unit)? = null
  private var onErrorCallback: ((String) -> Unit)? = null

  // Watchdog timeout runnable in case speech recognizer hangs or user remains silent
  private val timeoutRunnable = Runnable {
    if (_isListening.value || isSessionActive) {
      Log.w(TAG, "Speech recognition watchdog timed out")
      _isListening.value = false
      isSessionActive = false
      onErrorCallback?.invoke("EMPTY_SPEECH")
      stopListening()
    }
  }

  fun startListening(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    onPartialResult: ((String) -> Unit)? = null,
    preferredLanguage: String? = null
  ) {
    if (!DvexPermissionManager.hasAudioPermission(context)) {
      Log.w(TAG, "Cannot start listening: audio permission missing")
      onError("Microphone permission required for speech recognition.")
      return
    }

    this.onResultCallback = onResult
    this.onErrorCallback = onError
    this.onPartialResultCallback = onPartialResult

    mainHandler.post {
      try {
        if (isSessionActive) {
          Log.w(TAG, "Previous session still active; destroying before starting new session")
          destroyRecognizer()
        } else {
          destroyRecognizer()
        }

        Log.i(TAG, "Listening started")
        _isListening.value = true
        isSessionActive = true

        // 8-second safety watchdog
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, 8000)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
          setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
              Log.d(TAG, "Ready for speech input")
            }

            override fun onBeginningOfSpeech() {
              Log.d(TAG, "Speech detected")
              mainHandler.removeCallbacks(timeoutRunnable)
              // Reset timeout to 6 seconds after user began speaking
              mainHandler.postDelayed(timeoutRunnable, 6000)
            }

            override fun onRmsChanged(rmsdB: Float) {
              _rmsDb.value = (rmsdB.coerceAtLeast(0f) / 10f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
              Log.d(TAG, "End of speech detected")
              mainHandler.removeCallbacks(timeoutRunnable)
            }

            override fun onError(error: Int) {
              mainHandler.removeCallbacks(timeoutRunnable)
              _isListening.value = false
              isSessionActive = false
              val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient audio permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network connection error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timed out"
                SpeechRecognizer.ERROR_NO_MATCH -> "EMPTY_SPEECH"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "EMPTY_SPEECH"
                else -> "Recognition error ($error)"
              }
              Log.w(TAG, "SpeechRecognizer error: $errorMsg ($error)")
              onErrorCallback?.invoke(errorMsg)
            }

            override fun onResults(results: Bundle?) {
              mainHandler.removeCallbacks(timeoutRunnable)
              _isListening.value = false
              isSessionActive = false
              val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
              val recognizedText = matches?.firstOrNull()?.trim().orEmpty()
              if (recognizedText.isNotBlank()) {
                Log.i(TAG, "Final text: $recognizedText")
                onResultCallback?.invoke(recognizedText)
              } else {
                Log.i(TAG, "Empty or blank recognition result")
                onErrorCallback?.invoke("EMPTY_SPEECH")
              }
            }

            override fun onPartialResults(partialResults: Bundle?) {
              val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
              val partialText = matches?.firstOrNull()?.trim().orEmpty()
              if (partialText.isNotEmpty()) {
                Log.d(TAG, "Partial result: $partialText")
                onPartialResultCallback?.invoke(partialText)
              }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
          })
        }

        // Support English, Tamil, Tanglish (en-IN + ta-IN)
        val selectedLocale = when (preferredLanguage?.lowercase(Locale.ROOT)) {
          "tamil", "ta", "ta-in" -> Locale.forLanguageTag("ta-IN")
          "tanglish", "en-in" -> Locale.forLanguageTag("en-IN")
          else -> Locale.getDefault()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLocale.toLanguageTag())
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, selectedLocale.toLanguageTag())
          putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_PROMPT, "D-VEX is listening...")
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
          // Also provide Tamil and English India as fallback locales for Tanglish speech
          putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "ta-IN", "en-US"))
        }

        speechRecognizer?.startListening(intent)
      } catch (e: Exception) {
        mainHandler.removeCallbacks(timeoutRunnable)
        _isListening.value = false
        Log.e(TAG, "Failed to start speech recognition", e)
        onErrorCallback?.invoke(e.localizedMessage ?: "Failed to initialize microphone")
      }
    }
  }

  fun stopListening() {
    mainHandler.removeCallbacks(timeoutRunnable)
    _isListening.value = false
    mainHandler.post {
      try {
        speechRecognizer?.stopListening()
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping speech recognizer", e)
      }
    }
  }

  fun cancelListening() {
    mainHandler.removeCallbacks(timeoutRunnable)
    _isListening.value = false
    mainHandler.post {
      try {
        speechRecognizer?.cancel()
      } catch (e: Exception) {
        Log.e(TAG, "Error canceling speech recognizer", e)
      }
    }
  }

  fun destroy() {
    mainHandler.removeCallbacks(timeoutRunnable)
    _isListening.value = false
    mainHandler.post {
      destroyRecognizer()
    }
  }

  private fun destroyRecognizer() {
    try {
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying speech recognizer", e)
    } finally {
      speechRecognizer = null
      isSessionActive = false
      _isListening.value = false
    }
  }

  companion object {
    private const val TAG = "[D-VEX][STT]"
  }
}

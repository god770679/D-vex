package com.example.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Text-to-Speech Manager for D-VEX Voice Pipeline.
 * Prioritizes natural female conversational voice with language matching and safe utterance length.
 */
class TextToSpeechManager(private val context: Context) {

  private var tts: TextToSpeech? = null
  private var isInitialized = false
  private val mainHandler = Handler(Looper.getMainLooper())

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

  private var onSpeechDoneCallback: (() -> Unit)? = null

  init {
    initTts()
  }

  private fun initTts() {
    try {
      tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
          tts?.let { engine ->
            setupVoiceAndLanguage(engine, Locale.getDefault())
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                Log.d(TAG, "Utterance started: $utteranceId")
              }

              override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                Log.i(TAG, "Speech completed")
                mainHandler.post {
                  onSpeechDoneCallback?.invoke()
                  onSpeechDoneCallback = null
                }
              }

              override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                Log.w(TAG, "Utterance playback error: $utteranceId")
                mainHandler.post {
                  onSpeechDoneCallback?.invoke()
                  onSpeechDoneCallback = null
                }
              }
            })
            isInitialized = true
            Log.i(TAG, "D-VEX TextToSpeech initialized successfully")
          }
        } else {
          Log.w(TAG, "TTS initialization failed with status code: $status")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize TTS engine", e)
    }
  }

  private fun setupVoiceAndLanguage(engine: TextToSpeech, targetLocale: Locale) {
    try {
      val langResult = engine.setLanguage(targetLocale)
      if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.w(TAG, "Target locale $targetLocale not fully supported; using US English fallback")
        engine.setLanguage(Locale.US)
      }

      // Configure natural, articulate female pitch and tactical pace
      engine.setPitch(1.08f)
      engine.setSpeechRate(1.02f)

      // Find natural female voice if available in engine voices
      val availableVoices = engine.voices
      if (!availableVoices.isNullOrEmpty()) {
        val femaleVoice = availableVoices.firstOrNull { voice ->
          val name = voice.name.lowercase(Locale.ROOT)
          val matchesLocale = voice.locale.language == targetLocale.language || voice.locale.language == "en"
          matchesLocale && (name.contains("female") || name.contains("fem") || name.contains("f0") ||
              name.contains("en-us-x-sfg") || name.contains("en-in-x-dfg")) &&
              !voice.isNetworkConnectionRequired
        } ?: availableVoices.firstOrNull { voice ->
          val name = voice.name.lowercase(Locale.ROOT)
          (name.contains("female") || name.contains("fem"))
        }

        if (femaleVoice != null) {
          engine.voice = femaleVoice
          Log.i(TAG, "Selected natural female voice: ${femaleVoice.name}")
        }
      }
    } catch (e: Throwable) {
      Log.w(TAG, "Error configuring voice/language: ${e.message}")
    }
  }

  fun speak(text: String, languageCode: String? = null, onDone: (() -> Unit)? = null) {
    if (!isInitialized || tts == null) {
      Log.w(TAG, "TTS not ready; falling back immediately")
      onDone?.invoke()
      return
    }

    // Stop any in-progress speech before speaking new response
    stop()

    this.onSpeechDoneCallback = onDone

    try {
      tts?.let { engine ->
        if (languageCode != null) {
          val locale = when (languageCode.lowercase(Locale.ROOT)) {
            "tamil", "ta", "ta-in" -> Locale("ta", "IN")
            "tanglish", "en-in" -> Locale("en", "IN")
            else -> Locale.US
          }
          setupVoiceAndLanguage(engine, locale)
        }

        // Limit excessively long responses to concise conversational speech (up to ~250 chars)
        val textToSpeak = sanitizeTextForSpeech(text)

        val utteranceId = UUID.randomUUID().toString()
        _isSpeaking.value = true
        Log.i(TAG, "Speaking response: \"$textToSpeak\"")
        engine.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error speaking utterance", e)
      _isSpeaking.value = false
      onDone?.invoke()
    }
  }

  /**
   * Sanitizes markdown, symbols, and caps excessive text length to prevent long monologues.
   */
  private fun sanitizeTextForSpeech(input: String): String {
    var cleaned = input
      .replace(Regex("[*#_`~\\[\\]()<>{}=|]"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()

    // If text exceeds 220 chars, cut off cleanly at the nearest sentence boundary
    if (cleaned.length > 220) {
      val periodIndex = cleaned.indexOf('.', 120)
      if (periodIndex != -1 && periodIndex <= 240) {
        cleaned = cleaned.substring(0, periodIndex + 1)
      } else {
        cleaned = cleaned.take(210) + "..."
      }
    }
    return cleaned
  }

  fun stop() {
    try {
      tts?.stop()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping TTS", e)
    }
    _isSpeaking.value = false
  }

  fun destroy() {
    try {
      tts?.stop()
      tts?.shutdown()
      tts = null
      isInitialized = false
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying TTS", e)
    }
  }

  companion object {
    private const val TAG = "[D-VEX][TTS]"
  }
}

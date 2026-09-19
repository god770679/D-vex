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
 * Prioritizes natural, calm conversational voice synthesis.
 * Explicitly prefers Google's TTS engine (com.google.android.tts) for high-quality
 * Tamil (ta-IN) and Indian English (en-IN) neural voices.
 * Includes graceful fallback to Tanglish if native Tamil TTS is missing or of poor quality.
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

  /**
   * Initializes TextToSpeech. Prioritizes Google's TTS engine (com.google.android.tts)
   * as it offers the highest-quality, most natural Indian and Tamil neural voices.
   */
  private fun initTts() {
    try {
      val isGoogleTtsInstalled = isGoogleTtsEngineInstalled()
      val initCallback = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
          tts?.let { engine ->
            setupProgressListener(engine)
            setupVoiceAndLanguage(engine, Locale.getDefault())
            isInitialized = true
            Log.i(TAG, "D-VEX TextToSpeech initialized successfully (engine: ${engine.defaultEngine})")
          }
        } else {
          Log.w(TAG, "Primary TTS initialization failed with status $status; attempting default engine fallback")
          if (isGoogleTtsInstalled) {
            fallbackToDefaultTts()
          }
        }
      }

      tts = if (isGoogleTtsInstalled) {
        Log.i(TAG, "Selecting Google TTS engine for natural neural voice synthesis")
        TextToSpeech(context, initCallback, GOOGLE_TTS_PACKAGE)
      } else {
        Log.i(TAG, "Google TTS not installed; using system default TTS engine")
        TextToSpeech(context, initCallback)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize TTS engine, falling back to default", e)
      fallbackToDefaultTts()
    }
  }

  private fun fallbackToDefaultTts() {
    try {
      tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
          tts?.let { engine ->
            setupProgressListener(engine)
            setupVoiceAndLanguage(engine, Locale.getDefault())
            isInitialized = true
            Log.i(TAG, "Fallback default TTS engine initialized")
          }
        } else {
          Log.e(TAG, "Fallback TTS initialization failed: $status")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in fallback TTS initialization", e)
    }
  }

  private fun isGoogleTtsEngineInstalled(): Boolean {
    return try {
      context.packageManager.getPackageInfo(GOOGLE_TTS_PACKAGE, 0)
      true
    } catch (e: Exception) {
      false
    }
  }

  private fun setupProgressListener(engine: TextToSpeech) {
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
  }

  /**
   * Configures pitch, speech rate, and selects the most natural, human-like voice.
   * Adjusts intonation to sound calm, warm, and articulate rather than fast or robotic.
   */
  private fun setupVoiceAndLanguage(
    engine: TextToSpeech,
    targetLocale: Locale,
    isTamil: Boolean = false
  ) {
    try {
      if (isTamil) {
        val tamilLocales = listOf(Locale("ta", "IN"), Locale.forLanguageTag("ta-IN"), Locale("ta"))
        var langSupported = false
        for (loc in tamilLocales) {
          val langResult = engine.setLanguage(loc)
          if (langResult != TextToSpeech.LANG_MISSING_DATA && langResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            langSupported = true
            break
          }
        }

        if (langSupported) {
          // Calmer, measured intonation for Tamil:
          // Pitch: 0.98f (natural, warm pitch; prevents sharp robotic tone)
          // Speech rate: 0.93f (deliberate cadence allowing full phonetic articulation)
          engine.setPitch(0.98f)
          engine.setSpeechRate(0.93f)

          val bestTamilVoice = selectBestTamilVoice(engine)
          if (bestTamilVoice != null) {
            engine.voice = bestTamilVoice
            Log.i(TAG, "Selected natural Tamil voice: ${bestTamilVoice.name}")
          } else {
            Log.i(TAG, "Using engine default Tamil voice with optimized pitch (0.98) and speech rate (0.93)")
          }
        } else {
          Log.w(TAG, "Tamil locale not fully supported by active TTS engine; using Indian English fallback")
          engine.setLanguage(Locale.forLanguageTag("en-IN"))
          engine.setPitch(1.0f)
          engine.setSpeechRate(0.95f)
          val bestVoice = selectBestVoice(engine, Locale.forLanguageTag("en-IN"))
          if (bestVoice != null) {
            engine.voice = bestVoice
          }
        }
      } else {
        val langResult = engine.setLanguage(targetLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
          Log.w(TAG, "Target locale $targetLocale not supported; falling back to US English")
          engine.setLanguage(Locale.US)
        }

        // Calmer, pleasant, conversational pace for English
        engine.setPitch(1.0f)
        engine.setSpeechRate(0.96f)

        val bestVoice = selectBestVoice(engine, targetLocale)
        if (bestVoice != null) {
          engine.voice = bestVoice
          Log.i(TAG, "Selected natural voice: ${bestVoice.name}")
        }
      }
    } catch (e: Throwable) {
      Log.w(TAG, "Error configuring voice/language: ${e.message}")
    }
  }

  /**
   * Evaluates available voices and selects the highest-quality Tamil voice.
   * Prioritizes Google neural voices (ta-in-x-taf / tag) and offline high-definition voices.
   */
  private fun selectBestTamilVoice(engine: TextToSpeech): Voice? {
    val availableVoices = try {
      engine.voices
    } catch (e: Exception) {
      null
    } ?: return null

    val tamilVoices = availableVoices.filter { voice ->
      val lang = voice.locale.language.lowercase(Locale.ROOT)
      val tag = voice.locale.toLanguageTag().lowercase(Locale.ROOT)
      lang == "ta" || tag.startsWith("ta")
    }

    if (tamilVoices.isEmpty()) return null

    return tamilVoices.maxByOrNull { voice ->
      var score = 0
      val name = voice.name.lowercase(Locale.ROOT)

      // Quality rating
      score += voice.quality * 2

      // Prefer Google neural Tamil voices (e.g. ta-in-x-taf, ta-in-x-tag)
      if (name.contains("taf") || name.contains("tag") || name.contains("female") || name.contains("fem")) {
        score += 500
      }
      // Prefer installed/offline voices for lower latency and zero connection jitter
      if (!voice.isNetworkConnectionRequired || name.contains("local")) {
        score += 300
      }
      // Penalize missing or uninstalled voice data
      if (voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
        score -= 1000
      }
      score
    }
  }

  /**
   * Selects the highest quality natural conversational voice for non-Tamil locales.
   * When target is Indian English (en-IN), strictly prioritizes en-IN voices over US/UK.
   */
  private fun selectBestVoice(engine: TextToSpeech, targetLocale: Locale): Voice? {
    val availableVoices = try {
      engine.voices
    } catch (e: Exception) {
      null
    } ?: return null

    val targetLang = targetLocale.language.lowercase(Locale.ROOT)
    val targetCountry = targetLocale.country.lowercase(Locale.ROOT)
    val isIndianEnglish = targetCountry == "in" || targetLocale.toLanguageTag().lowercase(Locale.ROOT).contains("in")

    val matchingVoices = availableVoices.filter { voice ->
      voice.locale.language.lowercase(Locale.ROOT) == targetLang
    }
    if (matchingVoices.isEmpty()) return null

    return matchingVoices.maxByOrNull { voice ->
      var score = 0
      val name = voice.name.lowercase(Locale.ROOT)
      val country = voice.locale.country.lowercase(Locale.ROOT)
      val tag = voice.locale.toLanguageTag().lowercase(Locale.ROOT)

      score += voice.quality * 2

      if (isIndianEnglish) {
        if (country == "in" || tag.contains("in") || name.contains("en-in") || name.contains("ind")) {
          score += 2000
        } else {
          score -= 1000
        }
      } else {
        if (country == "us" || tag.contains("us") || name.contains("en-us")) {
          score += 500
        }
      }

      if (name.contains("female") || name.contains("fem") || name.contains("f0") ||
          name.contains("en-in-x-dfg") || name.contains("en-in-x-cxx") || name.contains("en-us-x-sfg")) {
        score += 500
      }
      if (!voice.isNetworkConnectionRequired || name.contains("local")) {
        score += 300
      }
      if (voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
        score -= 1000
      }
      score
    }
  }

  /**
   * Checks whether the current TTS engine has voice support for Tamil.
   */
  fun isTamilQualityAcceptable(): Boolean {
    val engine = tts ?: return false
    val localesToCheck = listOf(Locale("ta", "IN"), Locale.forLanguageTag("ta-IN"), Locale("ta"))
    val status = localesToCheck.maxOfOrNull { loc ->
      try {
        engine.isLanguageAvailable(loc)
      } catch (e: Exception) {
        TextToSpeech.LANG_NOT_SUPPORTED
      }
    } ?: TextToSpeech.LANG_NOT_SUPPORTED

    return status >= TextToSpeech.LANG_AVAILABLE
  }

  /**
   * Synthesizes and speaks text.
   * Auto-detects Tamil script and speaks in Tamil via Tamil TTS.
   * Supports Tanglish via Indian English voice.
   */
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
        val containsTamil = TamilTransliteration.containsTamilScript(text)
        val isExplicitTamil = languageCode != null && when (languageCode.lowercase(Locale.ROOT)) {
          "tamil", "ta", "ta-in" -> true
          else -> false
        }
        val isExplicitTanglish = languageCode != null && when (languageCode.lowercase(Locale.ROOT)) {
          "tanglish", "en-in" -> true
          else -> false
        }

        val treatAsTamil = containsTamil || isExplicitTamil

        if (treatAsTamil) {
          setupVoiceAndLanguage(engine, Locale.forLanguageTag("ta-IN"), isTamil = true)
          val textToSpeak = sanitizeTextForSpeech(text)
          speakUtterance(engine, textToSpeak)
        } else if (isExplicitTanglish) {
          // Explicit Tanglish spoken with Indian English voice for natural phonetics
          setupVoiceAndLanguage(engine, Locale.forLanguageTag("en-IN"), isTamil = false)
          val textToSpeak = sanitizeTextForSpeech(text)
          speakUtterance(engine, textToSpeak)
        } else {
          val locale = when (languageCode?.lowercase(Locale.ROOT)) {
            "tanglish", "en-in" -> Locale.forLanguageTag("en-IN")
            else -> Locale.US
          }
          setupVoiceAndLanguage(engine, locale, isTamil = false)
          val textToSpeak = sanitizeTextForSpeech(text)
          speakUtterance(engine, textToSpeak)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error speaking utterance", e)
      _isSpeaking.value = false
      onDone?.invoke()
    }
  }

  private fun speakUtterance(engine: TextToSpeech, textToSpeak: String) {
    val utteranceId = UUID.randomUUID().toString()
    _isSpeaking.value = true
    Log.i(TAG, "Speaking response: \"$textToSpeak\"")
    engine.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
  }

  /**
   * Sanitizes markdown, punctuation noise, and prevents run-on monologues
   * while preserving pauses (periods, commas) for calm, natural cadence.
   */
  private fun sanitizeTextForSpeech(input: String): String {
    var cleaned = input
      .replace(Regex("[*#_`~\\[\\]()<>{}=|]"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()

    // Ensure space after punctuation for natural breath pauses
    cleaned = cleaned
      .replace(",", ", ")
      .replace(".", ". ")
      .replace("?", "? ")
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
    private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
  }
}

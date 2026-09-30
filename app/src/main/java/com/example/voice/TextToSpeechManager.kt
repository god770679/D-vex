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
 *
 * LONG REPLIES: the full text is spoken. Long answers are split into natural chunks
 * ([TextToSpeechChunker]) and played back to back through the engine's own queue — the
 * old behaviour of cutting everything after ~220 characters and speaking only the first
 * part is gone. Display text is unaffected: the HUD always shows the full reply.
 *
 * INTERRUPTION (barge-in) is unchanged: every new reply, wake trigger or explicit stop
 * calls [stop], which cancels the whole chunk queue at once. No audio focus is requested, no
 * stream volume is touched, and no second audio source is introduced — one engine, one queue.
 */
class TextToSpeechManager(private val context: Context) {

  private var tts: TextToSpeech? = null
  private var isInitialized = false
  private val mainHandler = Handler(Looper.getMainLooper())

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

  private var onSpeechDoneCallback: (() -> Unit)? = null

  // Tracks the chunk utterances of the reply currently being spoken, so the completion
  // callback fires exactly ONCE — after the LAST chunk (see SpeechChunkTracker).
  private val chunkTracker = SpeechChunkTracker()

  // Set by the engine's onInit callback. Kept even when the callback arrives before the
  // engine instance could be assigned to [tts], which is what makes a synchronous onInit
  // safe instead of leaving D-VEX permanently silent.
  private var lastInitStatus: Int? = null

  /** True when the engine reported that it could not be used at all. */
  private var initFailed = false

  /** True once voice/language/progress setup has been applied to the live engine. */
  private var engineSetupApplied = false

  // Baseline prosody for the currently selected voice/language. Tone adaptation
  // adjusts these rather than replacing them, so the language-specific voice
  // character set by setupVoiceAndLanguage() is preserved.
  private var baseSpeechRate = 0.96f
  private var basePitch = 1.0f

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
        lastInitStatus = status
        if (status == TextToSpeech.SUCCESS) {
          // Null when the engine dispatched onInit from inside its own constructor, i.e.
          // before [tts] could be assigned; speak() applies the setup on first use then.
          tts?.let { engine -> prepareEngine(engine) }
        } else {
          Log.w(TAG, "Primary TTS initialization failed with status $status; attempting default engine fallback")
          if (isGoogleTtsInstalled) {
            fallbackToDefaultTts()
          } else {
            initFailed = true
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
        lastInitStatus = status
        if (status == TextToSpeech.SUCCESS) {
          tts?.let { engine ->
            prepareEngine(engine)
            Log.i(TAG, "Fallback default TTS engine initialized")
          }
        } else {
          initFailed = true
          Log.e(TAG, "Fallback TTS initialization failed: $status")
        }
      }
    } catch (e: Exception) {
      initFailed = true
      Log.e(TAG, "Error in fallback TTS initialization", e)
    }
  }

  /**
   * Applies voice/language/progress setup to the live engine exactly once.
   *
   * Normally the engine's onInit callback does this. Some engines dispatch onInit from
   * inside their own constructor, before the instance can be assigned, and a callback that
   * finds no instance used to leave D-VEX permanently silent. Re-applying the setup on
   * first use is idempotent and fixes both orderings.
   */
  private fun prepareEngine(engine: TextToSpeech) {
    if (engineSetupApplied) return
    setupProgressListener(engine)
    setupVoiceAndLanguage(engine, Locale.getDefault())
    engineSetupApplied = true
    isInitialized = true
    Log.i(TAG, "D-VEX TextToSpeech initialized successfully (engine: ${engineName(engine)})")
  }

  /** Engine name for diagnostics; never fatal when an engine cannot report one. */
  private fun engineName(engine: TextToSpeech): String = try {
    engine.defaultEngine ?: "system default"
  } catch (e: Exception) {
    "unknown"
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
        // Only the reply being tracked may claim the microphone is busy: a late onStart from
        // an utterance that was flushed by a barge-in must not report D-VEX as speaking again.
        if (chunkTracker.isTracked(utteranceId)) {
          _isSpeaking.value = true
          Log.d(TAG, "Chunk started: $utteranceId")
        } else {
          Log.d(TAG, "Stale utterance started after interruption; ignored: $utteranceId")
        }
      }

      override fun onDone(utteranceId: String?) {
        Log.i(TAG, "Speech chunk finished: $utteranceId")
        // A long reply is spoken as several chunks: only the LAST one ends the turn.
        if (chunkTracker.complete(utteranceId)) finishSpeechTurn()
      }

      override fun onStop(utteranceId: String?, interrupted: Boolean) {
        Log.i(TAG, "Utterance stopped (interrupted=$interrupted): $utteranceId")
        // A stopped chunk of the reply being spoken means the rest of it will not play
        // either, so the turn ends now rather than waiting forever. `onStop` defaults to
        // calling `onDone`, which would have reported a bogus completion per chunk.
        if (chunkTracker.fail(utteranceId)) finishSpeechTurn()
      }

      override fun onError(utteranceId: String?) {
        Log.w(TAG, "Utterance playback error: $utteranceId")
        // A failed chunk cannot be recovered by waiting; end the turn honestly.
        if (chunkTracker.fail(utteranceId)) finishSpeechTurn()
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
          basePitch = 0.98f
          baseSpeechRate = 0.93f
          engine.setPitch(basePitch)
          engine.setSpeechRate(baseSpeechRate)

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
          basePitch = 1.0f
          baseSpeechRate = 0.95f
          engine.setPitch(basePitch)
          engine.setSpeechRate(baseSpeechRate)
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
        basePitch = 1.0f
        baseSpeechRate = 0.96f
        engine.setPitch(basePitch)
        engine.setSpeechRate(baseSpeechRate)

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
  fun speak(
    text: String,
    languageCode: String? = null,
    /** Tone name (EstimatedTone.name) applied as a subtle prosody adjustment. */
    toneCode: String? = null,
    onDone: (() -> Unit)? = null
  ) {
    val engine = tts
    if (engine == null || initFailed) {
      Log.w(TAG, "TTS not ready; falling back immediately")
      onDone?.invoke()
      return
    }
    if (!isInitialized) {
      if (lastInitStatus == null) {
        // Initialization has not reported back yet: keep the existing behaviour and let
        // the caller continue instead of speaking into a half-connected engine.
        Log.w(TAG, "TTS still initializing; falling back immediately")
        onDone?.invoke()
        return
      }
      // Success already reported, but before the engine instance existed (see prepareEngine).
      prepareEngine(engine)
    }

    // Stop any in-progress speech before speaking new response
    stop()

    this.onSpeechDoneCallback = onDone

    try {
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

      // Tamil script -> Tamil voice. Explicit Tanglish (or mixed Tamil-English)
      // -> Indian English voice, which pronounces romanized Tamil naturally.
      // Everything else -> US English.
      val locale = when {
        treatAsTamil -> Locale.forLanguageTag("ta-IN")
        isExplicitTanglish -> Locale.forLanguageTag("en-IN")
        else -> Locale.US
      }
      setupVoiceAndLanguage(engine, locale, isTamil = treatAsTamil)
      // Applied AFTER the language baseline so tone modifies the chosen voice
      // instead of overriding its character.
      applyTone(engine, toneCode)
      // The WHOLE text is spoken: it is split into natural chunks rather than truncated.
      speakChunks(engine, TextToSpeechChunker.chunk(sanitizeTextForSpeech(text)))
    } catch (e: Exception) {
      Log.e(TAG, "Error speaking utterance", e)
      finishSpeechTurn()
    }
  }

  /**
   * Applies subtle tone-adaptive prosody.
   *
   * Android's TextToSpeech API exposes NO dependable SSML / prosody control —
   * SSML tags are read literally or silently dropped by installed engines — so the
   * only supported levers are setSpeechRate and setPitch, plus punctuation in the
   * text itself. This maps the estimated tone onto small, bounded multipliers of
   * the language baseline, which reads as a person naturally adjusting delivery
   * rather than a theatrical voice swap.
   *
   * Unknown or null tone leaves the baseline untouched.
   */
  private fun applyTone(engine: TextToSpeech, toneCode: String?) {
    val (rateFactor, pitchOffset) = when (toneCode?.uppercase(Locale.ROOT)) {
      "URGENT" -> 1.06f to 0.00f        // prompt, steady, no drama
      "EXCITED" -> 1.07f to 0.03f       // a touch quicker and brighter
      "HAPPY" -> 1.03f to 0.02f         // warm lift
      "CONFUSED" -> 0.92f to 0.00f      // slower and clearer
      "FRUSTRATED" -> 0.95f to -0.02f   // calm, grounded
      "SAD" -> 0.90f to -0.04f          // softer and slower
      else -> 1.00f to 0.00f
    }
    try {
      val rate = clamp(baseSpeechRate * rateFactor, RATE_MIN, RATE_MAX)
      val pitch = clamp(basePitch + pitchOffset, PITCH_MIN, PITCH_MAX)
      engine.setSpeechRate(rate)
      engine.setPitch(pitch)
      Log.i(
        TAG,
        "Tone prosody: tone=${toneCode ?: "NEUTRAL"} " +
          "rate=${String.format(Locale.ROOT, "%.2f", rate)} " +
          "pitch=${String.format(Locale.ROOT, "%.2f", pitch)}"
      )
    } catch (e: Throwable) {
      Log.w(TAG, "Could not apply tone prosody: ${e.message}")
    }
  }

  private fun clamp(value: Float, min: Float, max: Float): Float =
    if (value < min) min else if (value > max) max else value

  /**
   * Speaks the chunks of one reply sequentially and gaplessly.
   *
   * The FIRST chunk FLUSHES — a new reply always replaces whatever was still playing —
   * and the rest are ADDED to the engine's own queue, so Android plays them back to back
   * in order, with no overlap and no second audio stream. A barge-in is still one
   * `tts.stop()`: it cancels the whole queue at once.
   */
  private fun speakChunks(engine: TextToSpeech, chunks: List<String>) {
    if (chunks.isEmpty()) {
      Log.i(TAG, "Nothing speakable in this reply")
      finishSpeechTurn()
      return
    }

    val utteranceIds = chunks.map { UUID.randomUUID().toString() }
    chunkTracker.begin(utteranceIds)
    _isSpeaking.value = true

    chunks.forEachIndexed { index, chunk ->
      val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
      Log.i(
        TAG,
        "Speaking chunk ${index + 1}/${chunks.size} (${chunk.length} chars, " +
          "queue=${if (index == 0) "FLUSH" else "ADD"}): \"$chunk\""
      )
      engine.speak(chunk, queueMode, null, utteranceIds[index])
    }
  }

  /**
   * Ends one spoken reply: at most one callback, on the main thread, for the reply that is
   * actually finishing. The tracker is cleared first, so a late `onDone`/`onStop` from an
   * abandoned utterance cannot end the NEXT reply early.
   */
  private fun finishSpeechTurn() {
    chunkTracker.clear()
    _isSpeaking.value = false
    mainHandler.post {
      onSpeechDoneCallback?.invoke()
      onSpeechDoneCallback = null
    }
  }

  /**
   * Sanitizes markdown and speech-hostile punctuation while PRESERVING the structure the
   * chunker needs: paragraph breaks survive as blank lines, sentences and commas keep
   * their pause points.
   *
   * It never truncates and never drops words. The old 220-character cut — which silently
   * discarded the rest of every long answer — is gone: the full text is always spoken, in
   * chunks chosen by [TextToSpeechChunker].
   */
  private fun sanitizeTextForSpeech(input: String): String = input
    .replace("\r\n", "\n")
    .split(PARAGRAPH_BREAK)
    .map { paragraph -> sanitizeParagraph(paragraph) }
    .filter { it.isNotEmpty() }
    .joinToString("\n\n")

  private fun sanitizeParagraph(paragraph: String): String = paragraph
    .replace(MARKDOWN_NOISE, " ")
    // Natural breath pause after punctuation (a space that is already there is collapsed
    // by the run below, so nothing becomes double-spaced).
    .replace(PUNCTUATION_WITHOUT_SPACE, "$1 ")
    .replace(WHITESPACE_RUN, " ")
    .trim()

  fun stop() {
    try {
      tts?.stop()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping TTS", e)
    }
    // Interruption ends the turn: the cancelled utterance will never report completion for
    // the reply it belonged to, and a late callback is not replayed into the next one.
    chunkTracker.clear()
    onSpeechDoneCallback = null
    _isSpeaking.value = false
  }

  fun destroy() {
    try {
      tts?.stop()
      tts?.shutdown()
      tts = null
      isInitialized = false
      engineSetupApplied = false
      chunkTracker.clear()
      onSpeechDoneCallback = null
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying TTS", e)
    }
  }

  companion object {
    private const val TAG = "[D-VEX][TTS]"
    private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    /** Paragraph break: a blank line is a real pause, and the chunker splits on it first. */
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

    /** Markdown characters that are read literally by TTS engines. */
    private val MARKDOWN_NOISE = Regex("[*#_`~\\[\\]()<>{}=|]")

    /** Characters the previous sanitizer spaced out; behaviour preserved exactly. */
    private val PUNCTUATION_WITHOUT_SPACE = Regex("([,.?])(?=\\S)")

    private val WHITESPACE_RUN = Regex("\\s+")

    // Bounded so tone adaptation can never produce an unnatural or unintelligible voice.
    private const val RATE_MIN = 0.80f
    private const val RATE_MAX = 1.15f
    private const val PITCH_MIN = 0.90f
    private const val PITCH_MAX = 1.10f
  }
}

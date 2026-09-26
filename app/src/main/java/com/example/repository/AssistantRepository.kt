package com.example.brain

import android.util.Log
import com.example.ai.AiEngine
import java.util.Locale
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Natural conversational response layer for D-VEX Smart Brain.
 *
 * V2 — LLM-GENERATED REPLIES:
 * Instead of selecting a canned string from per-language templates, this layer
 * now builds a compact factual prompt describing the just-executed action, the
 * REAL tool result data, the user's verbatim utterance (so the model mirrors the
 * user's exact language/script/tone), and memory hints — then asks the LLM to
 * write a short natural reply.
 *
 * Hard guarantees preserved:
 * 1. NEVER FABRICATES DATA: the prompt passes only real tool numbers (weather,
 *    time, calculation results) and forbids the model from inventing facts.
 *    The model phrases; it does not compute.
 * 2. NEVER GOES SILENT: any LLM failure (timeout, blank, missing key, error)
 *    falls back to a simple honest hardcoded string.
 * 3. NEVER HANGS: a hard 8s timeout bounds the LLM call, and the engine itself
 *    is already timeout-bounded internally.
 * 4. NON-BLOCKING: generateResponse is suspend; callers (DvexSmartBrain.process)
 *    already run inside coroutines on Dispatchers.Main and simply await it.
 * 5. NO TOOL-LOGIC CHANGES: intent classification and DvexToolRouter behavior
 *    are untouched — intent decides WHAT happens; only HOW D-VEX words it.
 *
 * Personality: calm, smart, warm, natural, concise when appropriate, and conversational,
 * mirroring the user's own language — Tamil script, Tanglish, English, or a natural mix.
 */
class DvexResponseGenerator(
  private val aiEngine: AiEngine? = null
) {

  /**
   * Estimates conversational tone from the user's words and conversational cues.
   * Passed to the LLM as warmth/styling signal — no longer a branch selector for
   * pre-written lines.
   */
  fun estimateTone(userInput: String): EstimatedTone {
    val text = userInput.lowercase(Locale.ROOT)
    if (text.isBlank()) return EstimatedTone.NEUTRAL

    // Urgent cues
    if (text.contains("urgent") || text.contains("urgently") || text.contains("emergency") ||
        text.contains("immediately") || text.contains("right now") || text.contains("asap") ||
        text.contains("hurry") || text.contains("quick") || text.contains("quickly") ||
        text.contains("seekiram") || text.contains("seekirama") || text.contains("udane") ||
        text.contains("ippove") || text.contains("avacharam") || text.contains("vegam") ||
        text.contains("vegama") || text.contains("fast") || text.contains("speed") ||
        text.contains("உடனே") || text.contains("சீக்கிரம்") || text.contains("சீக்கிரமா") ||
        text.contains("அவசரம்")
    ) {
      return EstimatedTone.URGENT
    }

    // Frustrated cues
    if (text.contains("annoying") || text.contains("stupid") || text.contains("worst") ||
        text.contains("waste") || text.contains("hate") || text.contains("angry") ||
        text.contains("not working") || text.contains("useless") || text.contains("shut up") ||
        text.contains("kaduppa") || text.contains("kaduppu") || text.contains("kadupethatha") ||
        text.contains("erichal") || text.contains("erichala") || text.contains("worstu") ||
        text.contains("wasteu") || text.contains("vela seiyala") || text.contains("vela pakkala") ||
        text.contains("ennada idhu") || text.contains("thirumba thirumba") || text.contains("frustrated") ||
        text.contains("frustrating") || text.contains("irritat") || text.contains("tension") ||
        text.contains("கடுப்பு") || text.contains("எரிச்சல்") || text.contains("வேலை செய்யவில்லை") ||
        text.contains("வெறுப்பு") ||
        text.contains("மாட்டேங்குது") || text.contains("சரியா போகவே") || text.contains("சரியா போகல") ||
        text.contains("sariya pogala") || text.contains("sariya poagala") ||
        text.contains("pogave mattenguthu") || text.contains("pogave mattenkuthu") ||
        text.contains("ellame sariya") || text.contains("ellam sariya")
    ) {
      return EstimatedTone.FRUSTRATED
    }

    // Confused cues
    if (text.contains("what do you mean") || text.contains("confused") || text.contains("confusing") ||
        text.contains("don't understand") || text.contains("dont understand") || text.contains("how come") ||
        text.contains("puriyala") || text.contains("purila") || text.contains("puriyave illa") ||
        text.contains("enna solra") || text.contains("enna soldra") || text.contains("enna aachu") ||
        text.contains("theriyala") || text.contains("not clear") || text.contains("புரியவில்லை") ||
        text.contains("என்ன சொல்றீங்க") || text.contains("என்ன சொல்ற") || text.contains("விளங்கவில்லை")
    ) {
      return EstimatedTone.CONFUSED
    }

    // Excited cues
    if (text.contains("wow") || text.contains("amazing") || text.contains("let's go") ||
        text.contains("unbelievable") || text.contains("vera level") || text.contains("mass kaatita") ||
        text.contains("massu") || text.contains("mass") || text.contains("excited") ||
        text.contains("awesome") || text.contains("fire") || text.contains("superb") ||
        text.contains("brilliant") || text.contains("marana mass") || text.contains("sema mass") ||
        text.contains("வேற லெவல்") || text.contains("வேற மாறி")
    ) {
      return EstimatedTone.EXCITED
    }

    // Happy / Grateful cues
    if (text.contains("super") || text.contains("great") || text.contains("wonderful") ||
        text.contains("happy") || text.contains("glad") || text.contains("thank you so much") ||
        text.contains("love it") || text.contains("semma") || text.contains("kalakkita") ||
        text.contains("arputham") || text.contains("good job") || text.contains("well done") ||
        text.contains("romba nandri") || text.contains("மகிழ்ச்சி") || text.contains("சூப்பர்") ||
        text.contains("அற்புதம்") || text.contains("நன்றி")
    ) {
      return EstimatedTone.HAPPY
    }

    // Sad / Low cues
    if (text.contains("sad") || text.contains("depressed") || text.contains("feeling down") ||
        text.contains("feeling low") || text.contains("bad day") || text.contains("unhappy") ||
        text.contains("upset") || text.contains("heartbroken") || text.contains("crying") ||
        text.contains("sogam") || text.contains("sogama") || text.contains("kashtama") ||
        text.contains("vali") || text.contains("vali thaangala") || text.contains("kavalaya") ||
        text.contains("சோகம்") || text.contains("கஷ்டமா இருக்கு") || text.contains("மனசு சரியில்லை") ||
        text.contains("வருத்தம்") || text.contains("கவலை")
    ) {
      return EstimatedTone.SAD
    }

    return EstimatedTone.NEUTRAL
  }

  /**
   * Generates the final spoken/displayed reply.
   *
   * Flow:
   * 1. Repeat requests replay the last spoken response directly (no LLM).
   * 2. Low-confidence speech gets a short deterministic clarification (no LLM —
   *    these must be instant and never hallucinate an action).
   * 3. Everything else is phrased by the LLM from a factual prompt; on any LLM
   *    failure the tool's own honest result or a simple fallback is returned.
   *
   * The [memoryHint] is an optional pre-rendered string of remembered user facts
   * (preferred name, likes, style) from the existing memory store — passed into
   * the prompt so the LLM can personalize naturally.
   */
  suspend fun generateResponse(
    intent: DvexIntent,
    toolResult: DvexToolResult,
    language: DetectedLanguage,
    userInput: String = "",
    context: ConversationContext? = null,
    tone: EstimatedTone = estimateTone(userInput),
    memoryHint: String? = null
  ): String {
    val lowerInput = userInput.lowercase(Locale.ROOT).trim()

    // 1. Context Continuity: Repeat requests (kept as-is; no LLM needed)
    if (context != null && (lowerInput == "repeat that" || lowerInput == "what did you say" ||
            lowerInput == "enna sonna" || lowerInput == "திரும்ப சொல்லு" || lowerInput == "repeat")
    ) {
      val lastSaid = context.lastSpokenResponse
      if (!lastSaid.isNullOrBlank()) {
        return when (language) {
          DetectedLanguage.TAMIL -> "கடைசியாக நான் சொன்னது, Sir: $lastSaid"
          DetectedLanguage.TANGLISH -> "Kadasila sonnathu, Sir: $lastSaid"
          DetectedLanguage.ENGLISH -> "I said, Sir: $lastSaid"
        }
      }
    }

    // 2. Uncertain speech / garbled transcription: short, instant, deterministic
    // clarification. Never invents missing words or facts, so it stays LLM-free.
    if (intent is DvexIntent.LowConfidence) {
      val response = generateClarification(language, intent.candidateIntent)
      Log.i(TAG, "Generated clarification ($language, tone=$tone): \"$response\"")
      return response
    }

    // 3. LLM path: phrase a natural reply from the real action + real result data.
    val engine = aiEngine
    if (engine != null) {
      val prompt = buildResponsePrompt(intent, toolResult, language, userInput, tone, context, memoryHint)
      val llmReply = try {
        withTimeout(LLM_TIMEOUT_MS) { engine.generate(prompt) }
      } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "LLM response timed out after ${LLM_TIMEOUT_MS}ms; using fallback")
        null
      } catch (e: Exception) {
        Log.w(TAG, "LLM engine threw ${e.javaClass.simpleName}: ${e.message}; using fallback")
        null
      }
      if (!llmReply.isNullOrBlank()) {
        Log.i(TAG, "LLM response ($language, tone=$tone): \"$llmReply\"")
        return llmReply
      }
      Log.w(TAG, "LLM returned null/blank; falling back to deterministic response")
    }

    // 4. Fallback path (no engine, missing key, timeout, blank): a simple honest
    // deterministic reply — the tool's own spoken text when it carries the real
    // data, otherwise the static fallback. Never silent, never fabricated.
    return fallbackResponse(intent, toolResult).also {
      Log.i(TAG, "Fallback response ($language, tone=$tone): \"$it\"")
    }
  }

  // =========================================================================
  // --- Prompt construction ---
  // =========================================================================

  /**
   * Builds the response-generation prompt. Contains ONLY facts D-VEX actually
   * established: the executed action, the real tool result, the user's verbatim
   * utterance, tone, and optional memory. The model is explicitly forbidden from
   * inventing numbers, names, or outcomes.
   */
  fun buildResponsePrompt(
    intent: DvexIntent,
    toolResult: DvexToolResult,
    language: DetectedLanguage,
    userInput: String,
    tone: EstimatedTone,
    context: ConversationContext?,
    memoryHint: String?
  ): String {
    val sb = StringBuilder()
    sb.append(
      "You are D-VEX, a personal voice assistant on the user's phone. " +
        "Write the reply the assistant should say out loud next.\n\n"
    )
    sb.append("STRICT RULES:\n")
    sb.append("- Reply naturally and conversationally. Usually use 1-3 short sentences; use a little more detail only when the user needs it. No lists, no markdown, no emojis unless the user clearly uses that style.\n")
    sb.append(
      "- Mirror the user's own language, script, and style EXACTLY as they used it " +
        "(Tamil script, Tanglish romanized Tamil, English, or a natural mix). " +
        "Address the user respectfully as \"Sir\".\n"
    )
    sb.append(
      "- Use ONLY the facts given below. NEVER invent or change numbers, times, " +
        "weather values, names, or outcomes. If the action failed or was not " +
        "found, say so plainly without sugarcoating it into a success.\n"
    )
    sb.append("- Sound like a warm, smart human assistant, not a scripted bot.\n\n")

    sb.append("WHAT JUST HAPPENED: ${describeAction(intent, toolResult)}\n")
    sb.append("RESULT: ${toolResult.status.name} — ${toolResult.message}\n")
    if (toolResult.spokenText.isNotBlank() && toolResult.spokenText != toolResult.message) {
      sb.append("SPOKEN DATA: ${toolResult.spokenText}\n")
    }
    if (userInput.isNotBlank()) {
      sb.append("USER SAID (verbatim, mirror this language and tone): \"$userInput\"\n")
    }
    val toneWord = when (tone) {
      EstimatedTone.URGENT -> "urgent — be prompt and reassuring, act like it's already being handled"
      EstimatedTone.FRUSTRATED -> "frustrated — stay calm, acknowledge it, offer to fix it step by step"
      EstimatedTone.SAD -> "low/sad — be gentle and supportive"
      EstimatedTone.CONFUSED -> "confused — be extra clear and simple"
      EstimatedTone.EXCITED -> "excited — match the energy, keep it brief"
      EstimatedTone.HAPPY -> "happy/grateful — be warm, enjoy it with them"
      EstimatedTone.NEUTRAL -> "neutral"
    }
    sb.append("USER TONE SOUNDS: $toneWord\n")
    if (!memoryHint.isNullOrBlank()) {
      sb.append("REMEMBERED ABOUT THE USER: $memoryHint\n")
    }
    context?.lastSpokenResponse?.takeIf { it.isNotBlank() }?.let {
      sb.append("YOUR PREVIOUS REPLY (for continuity, don't repeat it): \"$it\"\n")
    }

    sb.append("\nReply now with ONLY the spoken reply text.")
    return sb.toString()
  }

  /** Short factual description of the executed action for the prompt. */
  private fun describeAction(intent: DvexIntent, toolResult: DvexToolResult): String {
    return when (intent) {
      is DvexIntent.OpenApp -> "The user asked to open the app \"${intent.appName}\"."
      is DvexIntent.CallContact -> "The user asked to call \"${intent.recipient}\"."
      is DvexIntent.SendMessage ->
        if (intent.isWhatsApp) {
          "The user asked to send a WhatsApp message to \"${intent.recipient}\"" +
            (intent.messageText?.let { " saying \"$it\"" } ?: "") + "."
        } else {
          "The user asked to send a message to \"${intent.recipient}\"" +
            (intent.messageText?.let { " saying \"$it\"" } ?: "") + "."
        }
      is DvexIntent.SendEmail -> "The user asked to send an email to \"${intent.recipient}\"."
      is DvexIntent.GetWeather ->
        "The user asked for the weather" +
          (intent.location?.let { " in $it" } ?: "") + (if (intent.isTomorrow) " for tomorrow" else "") + "."
      is DvexIntent.GetTime -> "The user asked for the current time."
      is DvexIntent.Calculate -> "The user asked to calculate \"${intent.expression}\"."
      is DvexIntent.SearchWeb -> "The user asked to search the web for \"${intent.query}\"."
      is DvexIntent.PlayYoutubeVideo -> "The user asked to play \"${intent.query}\" on YouTube."
      is DvexIntent.SetAlarm -> "The user asked to set an alarm."
      is DvexIntent.SetTimer -> "The user asked to set a timer."
      is DvexIntent.RememberFact -> "The user asked D-VEX to remember: \"${intent.fact}\"."
      is DvexIntent.RecallMemory -> "The user asked what D-VEX remembers."
      is DvexIntent.GetRecentApps -> "The user asked for recently used apps."
      is DvexIntent.WakeGreeting -> "The user woke D-VEX with a greeting."
      is DvexIntent.Conversation -> "The user said: \"${intent.statement}\"."
      is DvexIntent.GeneralQuestion -> "The user asked: \"${intent.question}\"."
      is DvexIntent.MultiStep -> "The user gave a two-step command (e.g. open an app and search inside it)."
      else -> "The user made a device-control request (\"${toolResult.toolName}\")."
    }
  }

  // =========================================================================
  // --- Deterministic fallback (LLM unavailable) ---
  // =========================================================================

  /**
   * Simple honest fallback used when the LLM path is unavailable or failed.
   * Prefers the tool's own spoken text when it carries the real result (weather
   * numbers, time, confirmation prompts) so real data still reaches the user;
   * otherwise a short static line. Never fabricates.
   */
  private fun fallbackResponse(intent: DvexIntent, toolResult: DvexToolResult): String {
    // Confirmation prompts and real data must pass through untouched.
    if (toolResult.status == DvexToolStatus.CONFIRMATION_REQUIRED && !toolResult.confirmationPrompt.isNullOrBlank()) {
      return toolResult.confirmationPrompt
    }
    if (toolResult.spokenText.isNotBlank() && toolResult.spokenText != toolResult.message) {
      return toolResult.spokenText
    }
    return when (toolResult.status) {
      DvexToolStatus.SUCCESS -> toolResult.spokenText.ifBlank { FALLBACK_OK }
      DvexToolStatus.CONFIRMATION_REQUIRED -> FALLBACK_CONFIRM
      else -> toolResult.spokenText.ifBlank { FALLBACK_TROUBLE }
    }
  }

  /**
   * Short, natural clarification request for uncertain speech / garbled transcription.
   * If a candidate meaning was inferred from context, offers it back instead of
   * inventing details; otherwise simply asks the user to repeat.
   */
  private fun generateClarification(language: DetectedLanguage, candidateIntent: DvexIntent?): String {
    val candidatePhrase = candidateIntent?.let { describeIntentForClarification(it) }
    return when (language) {
      DetectedLanguage.TAMIL ->
        if (candidatePhrase != null) "மன்னிக்கவும், தெளிவா இல்லை. \"$candidatePhrase\" சொன்னீங்களா?"
        else "மன்னிக்கவும், கொஞ்சம் தெளிவா சொல்லுங்க?"
      DetectedLanguage.TANGLISH ->
        if (candidatePhrase != null) "Sorry, konjam clear-a illa. \"$candidatePhrase\" nu nenachena sollunga?"
        else "Sorry, konjam clear-a sollunga?"
      DetectedLanguage.ENGLISH ->
        if (candidatePhrase != null) "Sorry, I didn't quite catch that. Did you mean \"$candidatePhrase\"?"
        else "Sorry, I didn't quite catch that. Could you say it again?"
    }
  }

  /** Renders a short spoken form of an inferred intent for clarification prompts. */
  private fun describeIntentForClarification(intent: DvexIntent): String? {
    return when (intent) {
      is DvexIntent.OpenApp -> "open ${intent.appName}"
      is DvexIntent.CallContact -> "call ${intent.recipient}"
      is DvexIntent.SendMessage -> "message ${intent.recipient}"
      is DvexIntent.SendEmail -> "email ${intent.recipient}"
      is DvexIntent.SearchWeb -> "search for ${intent.query}"
      is DvexIntent.GetWeather -> "check the weather"
      is DvexIntent.GetTime -> "check the time"
      is DvexIntent.Calculate -> "calculate ${intent.expression}"
      is DvexIntent.SetAlarm -> "set an alarm"
      is DvexIntent.SetTimer -> "set a timer"
      is DvexIntent.PlayYoutubeVideo -> "play that on YouTube"
      is DvexIntent.ToggleFlashlight -> "toggle the flashlight"
      else -> null
    }
  }

  companion object {
    private const val TAG = "[D-VEX][RESPONSE]"
    private const val LLM_TIMEOUT_MS = 8_000L

    const val FALLBACK_OK = "Done."
    const val FALLBACK_CONFIRM = "Shall I go ahead?"
    const val FALLBACK_TROUBLE = "Sorry, I'm having trouble responding right now."
  }
}

package com.example.brain

import android.util.Log
import java.util.Locale

/**
 * D-VEX natural conversational response layer.
 *
 * Responsibilities:
 * - Turn verified tool results into natural assistant speech.
 * - Preserve Tamil / Tanglish / English style.
 * - Preserve conversation continuity.
 * - Never invent action results.
 * - Keep deterministic fallback responses when an external AI model
 *   is not available.
 *
 * IMPORTANT:
 * This class does NOT execute device actions.
 * DvexToolRouter is responsible for execution and verification.
 */
class DvexResponseGenerator {

  /**
   * Estimates the user's conversational tone.
   *
   * This is only a style signal. It does not decide what action to execute.
   */
  fun estimateTone(userInput: String): EstimatedTone {
    val text = userInput.lowercase(Locale.ROOT).trim()

    if (text.isBlank()) {
      return EstimatedTone.NEUTRAL
    }

    // Urgent
    if (
      text.contains("urgent") ||
      text.contains("urgently") ||
      text.contains("emergency") ||
      text.contains("immediately") ||
      text.contains("right now") ||
      text.contains("asap") ||
      text.contains("hurry") ||
      text.contains("quick") ||
      text.contains("quickly") ||
      text.contains("seekiram") ||
      text.contains("seekirama") ||
      text.contains("udane") ||
      text.contains("ippove") ||
      text.contains("avacharam") ||
      text.contains("vegam") ||
      text.contains("vegama") ||
      text.contains("fast") ||
      text.contains("speed") ||
      text.contains("உடனே") ||
      text.contains("சீக்கிரம்") ||
      text.contains("சீக்கிரமா") ||
      text.contains("அவசரம்")
    ) {
      return EstimatedTone.URGENT
    }

    // Frustrated
    if (
      text.contains("annoying") ||
      text.contains("stupid") ||
      text.contains("worst") ||
      text.contains("waste") ||
      text.contains("hate") ||
      text.contains("angry") ||
      text.contains("not working") ||
      text.contains("useless") ||
      text.contains("shut up") ||
      text.contains("kaduppa") ||
      text.contains("kaduppu") ||
      text.contains("kadupethatha") ||
      text.contains("erichal") ||
      text.contains("erichala") ||
      text.contains("worstu") ||
      text.contains("wasteu") ||
      text.contains("vela seiyala") ||
      text.contains("vela pakkala") ||
      text.contains("ennada idhu") ||
      text.contains("thirumba thirumba") ||
      text.contains("frustrated") ||
      text.contains("frustrating") ||
      text.contains("irritat") ||
      text.contains("tension") ||
      text.contains("கடுப்பு") ||
      text.contains("எரிச்சல்") ||
      text.contains("வேலை செய்யவில்லை") ||
      text.contains("மாட்டேங்குது") ||
      text.contains("சரியா போகல") ||
      text.contains("sariya pogala") ||
      text.contains("sariya poagala") ||
      text.contains("pogave mattenguthu") ||
      text.contains("pogave mattenkuthu")
    ) {
      return EstimatedTone.FRUSTRATED
    }

    // Confused
    if (
      text.contains("what do you mean") ||
      text.contains("confused") ||
      text.contains("confusing") ||
      text.contains("don't understand") ||
      text.contains("dont understand") ||
      text.contains("how come") ||
      text.contains("puriyala") ||
      text.contains("purila") ||
      text.contains("puriyave illa") ||
      text.contains("enna solra") ||
      text.contains("enna soldra") ||
      text.contains("enna aachu") ||
      text.contains("theriyala") ||
      text.contains("not clear") ||
      text.contains("புரியவில்லை") ||
      text.contains("என்ன சொல்றீங்க") ||
      text.contains("என்ன சொல்ற") ||
      text.contains("விளங்கவில்லை")
    ) {
      return EstimatedTone.CONFUSED
    }

    // Excited
    if (
      text.contains("wow") ||
      text.contains("amazing") ||
      text.contains("let's go") ||
      text.contains("unbelievable") ||
      text.contains("vera level") ||
      text.contains("mass kaatita") ||
      text.contains("massu") ||
      text.contains("mass") ||
      text.contains("excited") ||
      text.contains("awesome") ||
      text.contains("fire") ||
      text.contains("superb") ||
      text.contains("brilliant") ||
      text.contains("marana mass") ||
      text.contains("sema mass") ||
      text.contains("வேற லெவல்") ||
      text.contains("வேற மாறி")
    ) {
      return EstimatedTone.EXCITED
    }

    // Happy
    if (
      text.contains("super") ||
      text.contains("great") ||
      text.contains("wonderful") ||
      text.contains("happy") ||
      text.contains("glad") ||
      text.contains("thank you") ||
      text.contains("love it") ||
      text.contains("semma") ||
      text.contains("kalakkita") ||
      text.contains("arputham") ||
      text.contains("good job") ||
      text.contains("well done") ||
      text.contains("romba nandri") ||
      text.contains("மகிழ்ச்சி") ||
      text.contains("சூப்பர்") ||
      text.contains("அற்புதம்") ||
      text.contains("நன்றி")
    ) {
      return EstimatedTone.HAPPY
    }

    // Sad
    if (
      text.contains("sad") ||
      text.contains("depressed") ||
      text.contains("feeling down") ||
      text.contains("feeling low") ||
      text.contains("bad day") ||
      text.contains("unhappy") ||
      text.contains("upset") ||
      text.contains("heartbroken") ||
      text.contains("crying") ||
      text.contains("sogam") ||
      text.contains("sogama") ||
      text.contains("kashtama") ||
      text.contains("vali") ||
      text.contains("vali thaangala") ||
      text.contains("kavalaya") ||
      text.contains("சோகம்") ||
      text.contains("கஷ்டமா இருக்கு") ||
      text.contains("மனசு சரியில்லை") ||
      text.contains("வருத்தம்") ||
      text.contains("கவலை")
    ) {
      return EstimatedTone.SAD
    }

    return EstimatedTone.NEUTRAL
  }

  /**
   * Generates the response that D-VEX speaks/displays.
   *
   * At this stage the response is deterministic and source-of-truth-safe.
   * The actual AI conversational layer can be connected here later without
   * changing the action execution architecture.
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

    val lowerInput = userInput
      .lowercase(Locale.ROOT)
      .trim()

    /*
     * ---------------------------------------------------------------
     * 1. Repeat / continuity
     * ---------------------------------------------------------------
     */
    if (
      context != null &&
      (
        lowerInput == "repeat" ||
        lowerInput == "repeat that" ||
        lowerInput == "what did you say" ||
        lowerInput == "enna sonna" ||
        lowerInput == "திரும்ப சொல்லு"
      )
    ) {
      val lastResponse = context.lastSpokenResponse

      if (!lastResponse.isNullOrBlank()) {
        return when (language) {
          DetectedLanguage.TAMIL ->
            "கடைசியாக நான் சொன்னது, Sir: $lastResponse"

          DetectedLanguage.TANGLISH ->
            "Kadasila naan sonnathu, Sir: $lastResponse"

          DetectedLanguage.ENGLISH ->
            "I said, Sir: $lastResponse"
        }
      }
    }

    /*
     * ---------------------------------------------------------------
     * 2. Low confidence
     * ---------------------------------------------------------------
     *
     * Do not let an uncertain ASR result trigger a fake conversational
     * answer.
     */
    if (intent is DvexIntent.LowConfidence) {
      return generateClarification(
        language = language,
        candidateIntent = intent.candidateIntent
      )
    }

    /*
     * ---------------------------------------------------------------
     * 3. Build a natural response from the REAL tool result.
     * ---------------------------------------------------------------
     */
    val response = generateNaturalFallback(
      intent = intent,
      toolResult = toolResult,
      language = language,
      userInput = userInput,
      context = context,
      tone = tone,
      memoryHint = memoryHint
    )

    Log.i(
      TAG,
      "Response generated | language=$language | tone=$tone | response=$response"
    )

    return response
  }

  /**
   * Builds the factual context that a future LLM can use.
   *
   * This function deliberately does not execute anything.
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

    val builder = StringBuilder()

    builder.append(
      """
      You are D-VEX, a personal AI assistant.

      Generate the next natural spoken response.

      RULES:
      - Sound natural and human, not robotic or scripted.
      - Keep the response concise unless more explanation is necessary.
      - Mirror the user's language: English, Tamil, Tanglish, or a natural mix.
      - Preserve the user's conversational style.
      - Be warm, intelligent and context-aware.
      - Do not invent facts.
      - Do not invent action results.
      - Do not claim an action succeeded unless the tool result says it succeeded.
      - Do not change numbers, names, times or other factual data.
      - Device actions have already been handled by D-VEX tools.
      - Your job is ONLY to phrase the response.
      - Do not output markdown.
      - Do not output labels such as "Response:".
      - Do not explain these instructions.

      LANGUAGE:
      $language

      USER TONE:
      $tone

      USER INPUT:
      ${userInput.ifBlank { "(none)" }}

      ACTION:
      ${describeAction(intent, toolResult)}

      TOOL STATUS:
      ${toolResult.status.name}

      TOOL RESULT:
      ${toolResult.message}

      SPOKEN RESULT:
      ${toolResult.spokenText}

      TOOL NAME:
      ${toolResult.toolName}
      """.trimIndent()
    )

    if (!memoryHint.isNullOrBlank()) {
      builder.append(
        "\n\nRELEVANT USER MEMORY:\n$memoryHint"
      )
    }

    context?.lastSpokenResponse
      ?.takeIf { it.isNotBlank() }
      ?.let {
        builder.append(
          "\n\nPREVIOUS D-VEX RESPONSE:\n$it"
        )
      }

    builder.append(
      "\n\nReturn ONLY the final spoken response."
    )

    return builder.toString()
  }

  /**
   * Describes what happened using only the known intent.
   */
  private fun describeAction(
    intent: DvexIntent,
    toolResult: DvexToolResult
  ): String {

    return when (intent) {

      is DvexIntent.OpenApp ->
        "User asked D-VEX to open ${intent.appName}."

      is DvexIntent.CloseApp ->
        "User asked D-VEX to close ${intent.appName}."

      DvexIntent.GoHome ->
        "User asked D-VEX to go to the home screen."

      DvexIntent.GoBack ->
        "User asked D-VEX to go back."

      DvexIntent.OpenRecents ->
        "User asked D-VEX to open recent apps."

      DvexIntent.OpenNotifications ->
        "User asked D-VEX to open notifications."

      DvexIntent.OpenSettings ->
        "User asked D-VEX to open settings."

      DvexIntent.OpenWifiSettings ->
        "User asked D-VEX to open Wi-Fi settings."

      DvexIntent.LockScreen ->
        "User asked D-VEX to lock the screen."

      is DvexIntent.Scroll ->
        "User asked D-VEX to scroll ${intent.direction.name.lowercase()}."

      is DvexIntent.CallContact ->
        "User asked D-VEX to call ${intent.recipient}."

      is DvexIntent.SendMessage ->
        if (intent.isWhatsApp) {
          "User asked D-VEX to send a WhatsApp message to ${intent.recipient}."
        } else {
          "User asked D-VEX to send a message to ${intent.recipient}."
        }

      is DvexIntent.SendEmail ->
        "User asked D-VEX to send an email to ${intent.recipient}."

      is DvexIntent.ToggleFlashlight ->
        "User asked D-VEX to change the flashlight state."

      is DvexIntent.SetAlarm ->
        "User asked D-VEX to set an alarm."

      is DvexIntent.SetTimer ->
        "User asked D-VEX to set a timer."

      is DvexIntent.SearchWeb ->
        "User asked D-VEX to search the web for ${intent.query}."

      is DvexIntent.GetWeather ->
        "User asked D-VEX for weather information."

      is DvexIntent.GetNews ->
        "User asked D-VEX for news."

      DvexIntent.GetTime ->
        "User asked D-VEX for the current time."

      is DvexIntent.Calculate ->
        "User asked D-VEX to calculate ${intent.expression}."

      DvexIntent.WakeGreeting ->
        "User greeted or woke D-VEX."

      is DvexIntent.AdjustVolume ->
        "User asked D-VEX to adjust the volume."

      is DvexIntent.MediaControl ->
        "User asked D-VEX to control media playback."

      is DvexIntent.RememberFact ->
        "User asked D-VEX to remember something."

      is DvexIntent.RecallMemory ->
        "User asked D-VEX to recall something from memory."

      is DvexIntent.MultiStep ->
        "User gave D-VEX a multi-step command."

      is DvexIntent.GeneralQuestion ->
        "User asked a general question."

      is DvexIntent.Conversation ->
        "User started a normal conversation."

      is DvexIntent.LowConfidence ->
        "D-VEX was not confident about what the user said."

      is DvexIntent.Unknown ->
        "D-VEX could not confidently classify the request."
    }
  }

  /**
   * Deterministic response layer.
   *
   * This is intentionally honest:
   * toolResult is the source of truth.
   */
  private fun generateNaturalFallback(
    intent: DvexIntent,
    toolResult: DvexToolResult,
    language: DetectedLanguage,
    userInput: String,
    context: ConversationContext?,
    tone: EstimatedTone,
    memoryHint: String?
  ): String {

    // Confirmation must remain explicit and must not be rewritten
    // into a fake success.
    if (
      toolResult.status == DvexToolStatus.CONFIRMATION_REQUIRED &&
      !toolResult.confirmationPrompt.isNullOrBlank()
    ) {
      return toolResult.confirmationPrompt
    }

    // If the tool already has verified spoken data, preserve it.
    if (toolResult.spokenText.isNotBlank()) {
      return toolResult.spokenText
    }

    /*
     * Conversation/general-question responses.
     *
     * These are intentionally short until the real conversational model
     * is connected.
     */
    if (intent is DvexIntent.Conversation) {
      return conversationFallback(
        language = language,
        userInput = userInput,
        tone = tone,
        context = context
      )
    }

    if (intent is DvexIntent.GeneralQuestion) {
      return when (language) {
        DetectedLanguage.TAMIL ->
          "சரி Sir. இதைப் பற்றி இன்னும் கொஞ்சம் தெளிவா சொல்லுங்க."

        DetectedLanguage.TANGLISH ->
          "Seri Sir. Idha pathi konjam detail-ah sollunga."

        DetectedLanguage.ENGLISH ->
          "Sure, Sir. Give me a little more detail and I'll help."
      }
    }

    /*
     * Successful tool action without its own spoken result.
     */
    if (toolResult.status == DvexToolStatus.SUCCESS) {
      return successFallback(
        intent = intent,
        language = language
      )
    }

    /*
     * Failed / unavailable / unknown.
     */
    return failureFallback(
      language = language,
      toolResult = toolResult
    )
  }

  /**
   * Natural-ish conversation fallback.
   *
   * The actual open-ended AI model will replace this later.
   */
  private fun conversationFallback(
    language: DetectedLanguage,
    userInput: String,
    tone: EstimatedTone,
    context: ConversationContext?
  ): String {

    return when (language) {

      DetectedLanguage.TAMIL -> {
        when (tone) {
          EstimatedTone.SAD ->
            "நான் இருக்கேன் Sir. என்னாச்சுன்னு சொல்லுங்க."

          EstimatedTone.FRUSTRATED ->
            "சரி Sir, புரியுது. என்ன பிரச்சனைன்னு சொல்லுங்க, பார்த்துக்கலாம்."

          EstimatedTone.EXCITED ->
            "ஹா, சரி Sir 😄 என்ன விஷயம்?"

          EstimatedTone.CONFUSED ->
            "பரவாயில்லை Sir. மெதுவா சொல்லுங்க, நான் புரிஞ்சுக்கிறேன்."

          else ->
            "சரி Sir. சொல்லுங்க, கேட்கிறேன்."
        }
      }

      DetectedLanguage.TANGLISH -> {
        when (tone) {
          EstimatedTone.SAD ->
            "Naan irukken Sir. Enna aachunu sollunga."

          EstimatedTone.FRUSTRATED ->
            "Seri Sir, puriyudhu. Enna problem-nu sollunga, paathukkalam."

          EstimatedTone.EXCITED ->
            "Haha, seri Sir 😄 Enna vishayam?"

          EstimatedTone.CONFUSED ->
            "Parava illa Sir. Konjam slow-ah sollunga, naan purinjukkaren."

          else ->
            "Seri Sir. Sollunga, kekkaren."
        }
      }

      DetectedLanguage.ENGLISH -> {
        when (tone) {
          EstimatedTone.SAD ->
            "I'm here, Sir. Tell me what's going on."

          EstimatedTone.FRUSTRATED ->
            "I understand, Sir. Tell me what went wrong and we'll work through it."

          EstimatedTone.EXCITED ->
            "Alright, Sir. What's going on?"

          EstimatedTone.CONFUSED ->
            "No problem, Sir. Take your time and tell me what you mean."

          else ->
            "Alright, Sir. I'm listening."
        }
      }
    }
  }

  private fun successFallback(
    intent: DvexIntent,
    language: DetectedLanguage
  ): String {

    return when (language) {

      DetectedLanguage.TAMIL -> when (intent) {
        is DvexIntent.OpenApp ->
          "${intent.appName} திறந்துவிட்டேன், Sir."

        is DvexIntent.CloseApp ->
          "${intent.appName} close பண்ணிட்டேன், Sir."

        else ->
          "சரி Sir, முடிச்சுட்டேன்."
      }

      DetectedLanguage.TANGLISH -> when (intent) {
        is DvexIntent.OpenApp ->
          "${intent.appName} open pannitten, Sir."

        is DvexIntent.CloseApp ->
          "${intent.appName} close pannitten, Sir."

        else ->
          "Seri Sir, pannitten."
      }

      DetectedLanguage.ENGLISH -> when (intent) {
        is DvexIntent.OpenApp ->
          "${intent.appName} is open, Sir."

        is DvexIntent.CloseApp ->
          "${intent.appName} is closed, Sir."

        else ->
          "Done, Sir."
      }
    }
  }

  private fun failureFallback(
    language: DetectedLanguage,
    toolResult: DvexToolResult
  ): String {

    val message = toolResult.message.trim()

    if (message.isNotBlank()) {
      return message
    }

    return when (language) {
      DetectedLanguage.TAMIL ->
        "மன்னிக்கவும் Sir, அந்த செயலை முடிக்க முடியவில்லை."

      DetectedLanguage.TANGLISH ->
        "Sorry Sir, andha action complete panna mudiyala."

      DetectedLanguage.ENGLISH ->
        "Sorry, Sir. I couldn't complete that."
    }
  }

  private fun generateClarification(
    language: DetectedLanguage,
    candidateIntent: DvexIntent?
  ): String {

    val candidate = candidateIntent?.let {
      describeIntentForClarification(it)
    }

    return when (language) {

      DetectedLanguage.TAMIL ->
        if (candidate != null) {
          "மன்னிக்கவும் Sir, கொஞ்சம் தெளிவா கேட்கவில்லை. \"$candidate\" சொன்னீங்களா?"
        } else {
          "மன்னிக்கவும் Sir, கொஞ்சம் தெளிவா சொல்லுங்க."
        }

      DetectedLanguage.TANGLISH ->
        if (candidate != null) {
          "Sorry Sir, konjam clear-ah kekkala. \"$candidate\" sonneengala?"
        } else {
          "Sorry Sir, konjam clear-ah sollunga."
        }

      DetectedLanguage.ENGLISH ->
        if (candidate != null) {
          "Sorry, Sir. I didn't quite catch that. Did you mean \"$candidate\"?"
        } else {
          "Sorry, Sir. I didn't quite catch that. Could you say it again?"
        }
    }
  }

  private fun describeIntentForClarification(
    intent: DvexIntent
  ): String? {

    return when (intent) {

      is DvexIntent.OpenApp ->
        "open ${intent.appName}"

      is DvexIntent.CloseApp ->
        "close ${intent.appName}"

      is DvexIntent.CallContact ->
        "call ${intent.recipient}"

      is DvexIntent.SendMessage ->
        "message ${intent.recipient}"

      is DvexIntent.SendEmail ->
        "email ${intent.recipient}"

      is DvexIntent.SearchWeb ->
        "search for ${intent.query}"

      is DvexIntent.GetWeather ->
        "check the weather"

      DvexIntent.GetTime ->
        "check the time"

      is DvexIntent.Calculate ->
        "calculate ${intent.expression}"

      is DvexIntent.SetAlarm ->
        "set an alarm"

      is DvexIntent.SetTimer ->
        "set a timer"

      is DvexIntent.ToggleFlashlight ->
        "toggle the flashlight"

      is DvexIntent.AdjustVolume ->
        "adjust the volume"

      is DvexIntent.MediaControl ->
        "control the media"

      else ->
        null
    }
  }

  companion object {
    private const val TAG = "[D-VEX][RESPONSE]"
  }
}
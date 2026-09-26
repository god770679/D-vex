package com.example.brain

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.Locale

/**
 * D-VEX natural conversational response layer.
 *
 * Responsibilities:
 * - Convert verified tool results into natural assistant speech.
 * - Use Gemini for natural conversational phrasing.
 * - Preserve English / Tamil / Tanglish.
 * - Preserve conversation continuity.
 * - Never invent action results.
 *
 * IMPORTANT:
 * This class does NOT execute device actions.
 *
 * DvexToolRouter:
 *     decides/executed/verifies actions
 *
 * DvexResponseGenerator:
 *     explains the verified result naturally
 *
 * DvexAiEngine:
 *     provides natural-language generation
 */
class DvexResponseGenerator(
    private val aiEngine: DvexAiEngine? = null
) {

    /**
     * Estimate user's conversational tone.
     *
     * This is only a style signal.
     * It does not decide which action to execute.
     */
    fun estimateTone(userInput: String): EstimatedTone {

        val text = userInput
            .lowercase(Locale.ROOT)
            .trim()

        if (text.isBlank()) {
            return EstimatedTone.NEUTRAL
        }

        // ------------------------------------------------------------
        // URGENT
        // ------------------------------------------------------------

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

        // ------------------------------------------------------------
        // FRUSTRATED
        // ------------------------------------------------------------

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

        // ------------------------------------------------------------
        // CONFUSED
        // ------------------------------------------------------------

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

        // ------------------------------------------------------------
        // EXCITED
        // ------------------------------------------------------------

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

        // ------------------------------------------------------------
        // HAPPY
        // ------------------------------------------------------------

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

        // ------------------------------------------------------------
        // SAD
        // ------------------------------------------------------------

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
     * Main response-generation entry point.
     *
     * Gemini is used only for phrasing.
     * Tool results remain the source of truth.
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

        // ------------------------------------------------------------
        // 1. Repeat previous response
        // ------------------------------------------------------------

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

        // ------------------------------------------------------------
        // 2. Low-confidence speech
        // ------------------------------------------------------------

        if (intent is DvexIntent.LowConfidence) {

            return generateClarification(
                language = language,
                candidateIntent = intent.candidateIntent
            )
        }

        // ------------------------------------------------------------
        // 3. Generate through Gemini
        // ------------------------------------------------------------

        val response = generateWithAi(
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
            "Response generated | language=$language | tone=$tone"
        )

        return response
    }

    /**
     * Gemini response generation.
     *
     * Important:
     * Confirmation prompts remain deterministic.
     * Gemini cannot convert a pending action into a success.
     */
    private suspend fun generateWithAi(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        language: DetectedLanguage,
        userInput: String,
        context: ConversationContext?,
        tone: EstimatedTone,
        memoryHint: String?
    ): String {

        // ------------------------------------------------------------
        // Confirmation must never be rewritten as success.
        // ------------------------------------------------------------

        if (
            toolResult.status == DvexToolStatus.CONFIRMATION_REQUIRED &&
            !toolResult.confirmationPrompt.isNullOrBlank()
        ) {
            return toolResult.confirmationPrompt
        }

        val engine = aiEngine

        if (engine != null) {

            try {

                val prompt = buildResponsePrompt(
                    intent = intent,
                    toolResult = toolResult,
                    language = language,
                    userInput = userInput,
                    tone = tone,
                    context = context,
                    memoryHint = memoryHint
                )

                val aiResponse = withTimeout(AI_TIMEOUT_MS) {
                    engine.generate(prompt)
                }

                if (!aiResponse.isNullOrBlank()) {

                    return cleanAiResponse(aiResponse)
                }

            } catch (e: TimeoutCancellationException) {

                Log.w(
                    TAG,
                    "Gemini response timed out; using deterministic fallback"
                )

            } catch (t: Throwable) {

                Log.e(
                    TAG,
                    "Gemini response failed; using deterministic fallback",
                    t
                )
            }
        }

        // ------------------------------------------------------------
        // Gemini unavailable → safe deterministic fallback.
        // ------------------------------------------------------------

        return generateNaturalFallback(
            intent = intent,
            toolResult = toolResult,
            language = language,
            userInput = userInput,
            context = context,
            tone = tone,
            memoryHint = memoryHint
        )
    }

    /**
     * Remove accidental formatting that the model may add.
     */
    private fun cleanAiResponse(
        response: String
    ): String {

        return response
            .trim()
            .removePrefix("Response:")
            .removePrefix("response:")
            .removePrefix("D-VEX:")
            .removePrefix("D-Vex:")
            .trim()
            .removeSurrounding("\"")
            .trim()
    }

    /**
     * Prompt supplied to Gemini.
     *
     * Gemini is explicitly restricted to response generation.
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

        val previousResponse =
            context?.lastSpokenResponse
                ?.takeIf { it.isNotBlank() }
                ?: "(none)"

        return """
            You are D-VEX, a personal AI assistant.

            Your job is to produce the next natural spoken response
            to the user.

            You are NOT the action executor.

            D-VEX tools have already handled any requested device action.
            The tool result below is the only source of truth about
            what actually happened.

            STRICT RULES:

            1. Sound like a real intelligent human assistant.
            2. Do not sound robotic, repetitive, or like a command-line tool.
            3. Do not use canned phrases unnecessarily.
            4. Match the user's language naturally:
               - English
               - Tamil
               - Tanglish
               - Natural English/Tamil mix
            5. Match the user's conversational style.
            6. Remember the immediate conversation context.
            7. Be warm, intelligent and natural.
            8. Address the user as "Sir" naturally, but do not force
               "Sir" into every sentence.
            9. Use short responses when a short response is enough.
            10. Give more explanation only when the user needs it.
            11. Never invent facts.
            12. Never invent action results.
            13. Never claim an action succeeded unless the tool result
                indicates success.
            14. Never claim an action failed unless the tool result
                indicates failure.
            15. Never change names, numbers, times, locations or values.
            16. Do not execute or suggest that you executed another
                device action.
            17. Do not expose internal architecture.
            18. Do not mention Gemini, AI model, prompt, tools,
                intent detection or implementation.
            19. Do not output markdown.
            20. Do not output "Response:" or other labels.
            21. Return ONLY the final spoken response.
            
            LANGUAGE:
            $language

            USER TONE:
            $tone

            USER INPUT:
            ${userInput.ifBlank { "(none)" }}

            ACTION UNDERSTANDING:
            ${describeAction(intent)}

            TOOL STATUS:
            ${toolResult.status.name}

            TOOL NAME:
            ${toolResult.toolName}

            VERIFIED TOOL MESSAGE:
            ${toolResult.message}

            VERIFIED SPOKEN RESULT:
            ${toolResult.spokenText}

            CONFIRMATION REQUIRED:
            ${toolResult.requiresConfirmation}

            CONFIRMATION PROMPT:
            ${toolResult.confirmationPrompt ?: "(none)"}

            PREVIOUS D-VEX RESPONSE:
            $previousResponse

            ${if (!memoryHint.isNullOrBlank()) {
                "RELEVANT USER MEMORY:\n$memoryHint"
            } else {
                "RELEVANT USER MEMORY:\n(none)"
            }}

            Now produce the natural final spoken response.
        """.trimIndent()
    }

    /**
     * Human-readable description for Gemini.
     *
     * This describes the intent only.
     * It does not execute anything.
     */
    private fun describeAction(
        intent: DvexIntent
    ): String {

        return when (intent) {

            is DvexIntent.OpenApp ->
                "The user asked to open ${intent.appName}."

            is DvexIntent.CloseApp ->
                "The user asked to close ${intent.appName}."

            DvexIntent.GoHome ->
                "The user asked to go to the home screen."

            DvexIntent.GoBack ->
                "The user asked to go back."

            DvexIntent.OpenRecents ->
                "The user asked to open recent apps."

            DvexIntent.OpenNotifications ->
                "The user asked to open notifications."

            DvexIntent.OpenSettings ->
                "The user asked to open settings."

            DvexIntent.OpenWifiSettings ->
                "The user asked to open Wi-Fi settings."

            DvexIntent.LockScreen ->
                "The user asked to lock the screen."

            is DvexIntent.Scroll ->
                "The user asked to scroll ${intent.direction.name.lowercase()}."

            is DvexIntent.CallContact ->
                "The user asked to call ${intent.recipient}."

            is DvexIntent.SendMessage ->
                if (intent.isWhatsApp) {
                    "The user asked to send a WhatsApp message to ${intent.recipient}."
                } else {
                    "The user asked to send a message to ${intent.recipient}."
                }

            is DvexIntent.SendEmail ->
                "The user asked to send an email to ${intent.recipient}."

            is DvexIntent.ToggleFlashlight ->
                "The user asked to change the flashlight state."

            is DvexIntent.SetAlarm ->
                "The user asked to set an alarm."

            is DvexIntent.SetTimer ->
                "The user asked to set a timer."

            is DvexIntent.SearchWeb ->
                "The user asked to search the web for: ${intent.query}"

            is DvexIntent.GetWeather ->
                "The user asked for weather information."

            is DvexIntent.GetNews ->
                "The user asked for news."

            DvexIntent.GetTime ->
                "The user asked for the current time."

            is DvexIntent.Calculate ->
                "The user asked to calculate: ${intent.expression}"

            DvexIntent.WakeGreeting ->
                "The user greeted or woke D-VEX."

            is DvexIntent.AdjustVolume ->
                "The user asked to adjust device volume."

            is DvexIntent.MediaControl ->
                "The user asked to control media playback."

            is DvexIntent.RememberFact ->
                "The user asked D-VEX to remember something."

            is DvexIntent.RecallMemory ->
                "The user asked D-VEX to recall something."

            is DvexIntent.MultiStep ->
                "The user gave D-VEX a multi-step command."

            is DvexIntent.GeneralQuestion ->
                "The user asked a general question."

            is DvexIntent.Conversation ->
                "The user is having a normal conversation."

            is DvexIntent.LowConfidence ->
                "D-VEX could not confidently understand the speech."

            is DvexIntent.Unknown ->
                "D-VEX could not confidently classify the request."
        }
    }

    /**
     * Deterministic fallback.
     *
     * This remains available when:
     * - Gemini is unavailable
     * - network fails
     * - request times out
     * - Firebase is not configured
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

        // Confirmation is always deterministic.
        if (
            toolResult.status == DvexToolStatus.CONFIRMATION_REQUIRED &&
            !toolResult.confirmationPrompt.isNullOrBlank()
        ) {
            return toolResult.confirmationPrompt
        }

        // Verified spoken result has highest priority.
        if (toolResult.spokenText.isNotBlank()) {
            return toolResult.spokenText
        }

        // Normal conversation.
        if (intent is DvexIntent.Conversation) {

            return conversationFallback(
                language = language,
                userInput = userInput,
                tone = tone,
                context = context
            )
        }

        // General question.
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

        // Successful action.
        if (toolResult.status == DvexToolStatus.SUCCESS) {

            return successFallback(
                intent = intent,
                language = language
            )
        }

        // Failure / unavailable.
        return failureFallback(
            language = language,
            toolResult = toolResult
        )
    }

    /**
     * Conversation fallback.
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

    /**
     * Basic successful-action fallback.
     */
    private fun successFallback(
        intent: DvexIntent,
        language: DetectedLanguage
    ): String {

        return when (language) {

            DetectedLanguage.TAMIL -> {

                when (intent) {

                    is DvexIntent.OpenApp ->
                        "${intent.appName} திறந்துவிட்டேன், Sir."

                    is DvexIntent.CloseApp ->
                        "${intent.appName} close பண்ணிட்டேன், Sir."

                    else ->
                        "சரி Sir, முடிச்சுட்டேன்."
                }
            }

            DetectedLanguage.TANGLISH -> {

                when (intent) {

                    is DvexIntent.OpenApp ->
                        "${intent.appName} open pannitten, Sir."

                    is DvexIntent.CloseApp ->
                        "${intent.appName} close pannitten, Sir."

                    else ->
                        "Seri Sir, pannitten."
                }
            }

            DetectedLanguage.ENGLISH -> {

                when (intent) {

                    is DvexIntent.OpenApp ->
                        "${intent.appName} is open, Sir."

                    is DvexIntent.CloseApp ->
                        "${intent.appName} is closed, Sir."

                    else ->
                        "Done, Sir."
                }
            }
        }
    }

    /**
     * Failure fallback.
     */
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

    /**
     * Low-confidence clarification.
     */
    private fun generateClarification(
        language: DetectedLanguage,
        candidateIntent: DvexIntent?
    ): String {

        val candidate = candidateIntent?.let {
            describeIntentForClarification(it)
        }

        return when (language) {

            DetectedLanguage.TAMIL -> {

                if (candidate != null) {
                    "மன்னிக்கவும் Sir, கொஞ்சம் தெளிவா கேட்கவில்லை. \"$candidate\" சொன்னீங்களா?"
                } else {
                    "மன்னிக்கவும் Sir, கொஞ்சம் தெளிவா சொல்லுங்க."
                }
            }

            DetectedLanguage.TANGLISH -> {

                if (candidate != null) {
                    "Sorry Sir, konjam clear-ah kekkala. \"$candidate\" sonneengala?"
                } else {
                    "Sorry Sir, konjam clear-ah sollunga."
                }
            }

            DetectedLanguage.ENGLISH -> {

                if (candidate != null) {
                    "Sorry, Sir. I didn't quite catch that. Did you mean \"$candidate\"?"
                } else {
                    "Sorry, Sir. I didn't quite catch that. Could you say it again?"
                }
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

        /**
         * Do not make voice interaction wait indefinitely.
         */
        private const val AI_TIMEOUT_MS = 8_000L
    }
}
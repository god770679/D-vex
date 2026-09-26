package com.example.brain

import android.content.Context
import android.util.Log
import com.example.control.AppLauncherRepository
import com.example.control.ContactResolver
import com.example.control.DeviceControlRepository
import java.util.Locale

data class BrainExecutionResult(
    val intent: DvexIntent,
    val toolResult: DvexToolResult,
    val spokenText: String,
    val displayText: String = spokenText,
    val language: DetectedLanguage = DetectedLanguage.ENGLISH,
    val toolName: String = toolResult.toolName,
    val isSensitiveAction: Boolean = toolResult.requiresConfirmation,
    val pendingActionId: String? = toolResult.pendingActionId
)

/**
 * D-VEX Smart Brain V1.
 *
 * Central cognitive unit converting natural user speech into:
 * intent understanding,
 * tool selection,
 * tool execution,
 * verification,
 * and natural-language response generation.
 */
class DvexSmartBrain(
    private val context: Context,
    private val appLauncher: AppLauncherRepository,
    private val deviceControl: DeviceControlRepository
) {

    private val intentDetector = IntentDetector()

    private val conversationContext = ConversationContext()

    private val toolRouter = DvexToolRouter(
        context,
        appLauncher,
        deviceControl
    )

    // AI is used ONLY for natural-language response generation.
    // It does not execute device actions.
    private val aiEngine = DvexAiEngine()

    private val responseGenerator = DvexResponseGenerator(
        aiEngine = aiEngine
    )

    private val contactResolver = ContactResolver(context)

    private var activePendingIntent: DvexIntent? = null
    private var activePendingId: String? = null

    @Volatile
    private var lastAsrConfidence: Float? = null

    suspend fun process(rawInput: String): BrainExecutionResult {

        Log.i(TAG_BRAIN, "Input: $rawInput")

        if (rawInput.isBlank()) {
            return BrainExecutionResult(
                intent = DvexIntent.Conversation(""),
                toolResult = DvexToolResult(
                    DvexToolStatus.SUCCESS,
                    "standby",
                    "",
                    ""
                ),
                spokenText = "",
                displayText = "",
                language = conversationContext.lastLanguage,
                toolName = "standby"
            )
        }

        val lower = rawInput
            .lowercase(Locale.ROOT)
            .trim()

        /*
         * ---------------------------------------------------------
         * PENDING CONFIRMATION
         * ---------------------------------------------------------
         */

        if (activePendingIntent != null) {

            if (isAffirmative(lower)) {

                val confirmedIntent = activePendingIntent!!

                activePendingIntent = null
                activePendingId = null

                val confirmationLang =
                    intentDetector
                        .detectIntent(
                            rawInput,
                            conversationContext
                        )
                        .language

                val effectiveLang =
                    if (confirmationLang != DetectedLanguage.ENGLISH) {
                        confirmationLang
                    } else {
                        conversationContext.lastLanguage
                    }

                val executionResult =
                    executeConfirmedAction(confirmedIntent)

                val tone =
                    responseGenerator.estimateTone(rawInput)

                val spoken =
                    responseGenerator.generateResponse(
                        intent = confirmedIntent,
                        toolResult = executionResult,
                        language = effectiveLang,
                        userInput = rawInput,
                        context = conversationContext,
                        tone = tone
                    )

                conversationContext.update(
                    input = rawInput,
                    intent = confirmedIntent,
                    toolResult = executionResult,
                    language = effectiveLang,
                    tone = tone,
                    spokenResponse = spoken
                )

                return BrainExecutionResult(
                    intent = confirmedIntent,
                    toolResult = executionResult,
                    spokenText = spoken,
                    displayText = spoken,
                    language = effectiveLang,
                    toolName = executionResult.toolName
                )
            }

            else if (isNegative(lower)) {

                activePendingIntent = null
                activePendingId = null

                val cancelLang =
                    intentDetector
                        .detectIntent(
                            rawInput,
                            conversationContext
                        )
                        .language

                val effectiveLang =
                    if (cancelLang != DetectedLanguage.ENGLISH) {
                        cancelLang
                    } else {
                        conversationContext.lastLanguage
                    }

                val cancelMessage =
                    when (effectiveLang) {

                        DetectedLanguage.TAMIL ->
                            "செயல் ரத்து செய்யப்பட்டது, Sir."

                        DetectedLanguage.TANGLISH ->
                            "Action cancel panniyachu, Sir."

                        else ->
                            "Action cancelled, Sir."
                    }

                val cancelResult =
                    DvexToolResult(
                        status = DvexToolStatus.SUCCESS,
                        toolName = "cancellation",
                        message = cancelMessage,
                        spokenText = cancelMessage
                    )

                conversationContext.update(
                    input = rawInput,
                    intent = DvexIntent.Conversation(rawInput),
                    toolResult = cancelResult,
                    language = effectiveLang,
                    tone = EstimatedTone.NEUTRAL,
                    spokenResponse = cancelMessage
                )

                return BrainExecutionResult(
                    intent = DvexIntent.Conversation(rawInput),
                    toolResult = cancelResult,
                    spokenText = cancelMessage,
                    displayText = cancelMessage,
                    language = effectiveLang,
                    toolName = "cancellation"
                )
            }

            /*
             * The user said something other than yes/no.
             * Clear the pending confirmation and process the new
             * input normally.
             */
            activePendingIntent = null
            activePendingId = null
        }

        /*
         * ---------------------------------------------------------
         * INTENT DETECTION
         * ---------------------------------------------------------
         */

        val detection =
            intentDetector.detectIntent(
                rawInput,
                conversationContext
            )

        var intent = detection.intent

        val confidence = detection.confidence
        val language = detection.language

        /*
         * ---------------------------------------------------------
         * ASR + INTENT CONFIDENCE
         * ---------------------------------------------------------
         */

        val asrConfidence = lastAsrConfidence

        if (
            asrConfidence != null &&
            asrConfidence < ASR_LOW_CONFIDENCE_THRESHOLD &&
            confidence < INTENT_LOW_CONFIDENCE_THRESHOLD &&
            intent is DvexIntent.Conversation
        ) {

            intent =
                DvexIntent.LowConfidence(
                    clarificationPrompt =
                        "Sorry, Sir, I didn't quite catch that. Could you say it again?",
                    candidateIntent = null
                )
        }

        /*
         * ---------------------------------------------------------
         * TONE
         * ---------------------------------------------------------
         */

        val tone =
            responseGenerator.estimateTone(rawInput)

        /*
         * ---------------------------------------------------------
         * TOOL EXECUTION
         *
         * Tools remain the authority for real-world actions.
         * Gemini/AI does NOT execute these actions.
         * ---------------------------------------------------------
         */

        val toolResult =
            toolRouter.execute(
                intent,
                conversationContext
            )

        /*
         * ---------------------------------------------------------
         * NATURAL RESPONSE GENERATION
         *
         * DvexResponseGenerator -> DvexAiEngine
         *
         * AI generates only the conversational response using
         * the verified tool result.
         * ---------------------------------------------------------
         */

        val spokenResponse =
            responseGenerator.generateResponse(
                intent = intent,
                toolResult = toolResult,
                language = language,
                userInput = rawInput,
                context = conversationContext,
                tone = tone
            )

        /*
         * ---------------------------------------------------------
         * UPDATE CONVERSATION CONTEXT
         * ---------------------------------------------------------
         */

        conversationContext.update(
            input = rawInput,
            intent = intent,
            toolResult = toolResult,
            language = language,
            tone = tone,
            spokenResponse = spokenResponse
        )

        /*
         * ---------------------------------------------------------
         * CONFIRMATION
         * ---------------------------------------------------------
         */

        if (toolResult.requiresConfirmation) {

            activePendingIntent = intent
            activePendingId = toolResult.pendingActionId
        }

        /*
         * ---------------------------------------------------------
         * FINAL RESULT
         * ---------------------------------------------------------
         */

        return BrainExecutionResult(
            intent = intent,
            toolResult = toolResult,
            spokenText = spokenResponse,
            displayText = spokenResponse,
            language = language,
            toolName = toolResult.toolName,
            isSensitiveAction = toolResult.requiresConfirmation,
            pendingActionId = toolResult.pendingActionId
        )
    }

    /**
     * Execute an action after the user has explicitly confirmed it.
     */
    private suspend fun executeConfirmedAction(
        intent: DvexIntent
    ): DvexToolResult {
        return toolRouter.executeConfirmed(intent)
    }

    /**
     * Cancel the currently pending confirmation.
     */
    fun cancelPendingConfirmation() {
        activePendingIntent = null
        activePendingId = null
    }

    /**
     * Returns true when D-VEX is waiting for confirmation.
     */
    fun hasPendingConfirmation(): Boolean {
        return activePendingIntent != null
    }

    /*
     * -------------------------------------------------------------
     * AFFIRMATIVE / NEGATIVE DETECTION
     * -------------------------------------------------------------
     */

    private fun isAffirmative(lower: String): Boolean {

        return lower in setOf(
            "yes",
            "yeah",
            "yep",
            "yup",
            "sure",
            "okay",
            "ok",
            "go ahead",
            "do it",
            "confirm",
            "confirmed",
            "proceed",
            "please do",
            "ஆம்",
            "ஆமாம்",
            "சரி",
            "செய்",
            "செய்யலாம்",
            "பண்ணு",
            "பண்ணலாம்",
            "ஆமா",
            "ok sir",
            "yes sir",
            "sure sir"
        )
    }

    private fun isNegative(lower: String): Boolean {

        return lower in setOf(
            "no",
            "nope",
            "nah",
            "cancel",
            "cancel it",
            "stop",
            "don't",
            "do not",
            "never mind",
            "never mind it",
            "no thanks",
            "cancel that",
            "வேண்டாம்",
            "ரத்து செய்",
            "ரத்து",
            "நிறுத்து",
            "பண்ணாதே",
            "வேணாம்",
            "வேண்டாம் sir",
            "cancel sir"
        )
    }

    /*
     * -------------------------------------------------------------
     * ASR CONFIDENCE
     * -------------------------------------------------------------
     */

    fun reportAsrConfidence(confidence: Float?) {
        lastAsrConfidence = confidence
    }

    /*
     * -------------------------------------------------------------
     * RESET
     * -------------------------------------------------------------
     */

    fun resetContext() {

        conversationContext.clear()

        cancelPendingConfirmation()

        lastAsrConfidence = null
    }

    companion object {

        private const val TAG_BRAIN =
            "[D-VEX][BRAIN]"

        private const val TAG_INTENT =
            "[D-VEX][INTENT]"

        private const val TAG_TOOL =
            "[D-VEX][TOOL]"

        private const val TAG_RESULT =
            "[D-VEX][RESULT]"

        private const val TAG_RESPONSE =
            "[D-VEX][RESPONSE]"

        private const val ASR_LOW_CONFIDENCE_THRESHOLD =
            0.4f

        private const val INTENT_LOW_CONFIDENCE_THRESHOLD =
            0.75f
    }
}
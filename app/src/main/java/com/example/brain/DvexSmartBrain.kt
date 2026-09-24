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
 * D-VEX Smart Brain
 *
 * Main pipeline:
 *
 * User speech
 *      ↓
 * Intent detection
 *      ↓
 * Short-term conversation context
 *      ↓
 * Persistent memory
 *      ↓
 * Tool selection / execution
 *      ↓
 * Tone + language aware response
 *      ↓
 * Conversation context update
 */
class DvexSmartBrain(
    private val context: Context,
    private val appLauncher: AppLauncherRepository,
    private val deviceControl: DeviceControlRepository
) {

    private val intentDetector = IntentDetector()

    private val conversationContext = ConversationContext()

    // Persistent memory survives app restart.
    private val memoryStore = DvexMemoryStore(context)

    private val toolRouter = DvexToolRouter(
        context,
        appLauncher,
        deviceControl
    )

    private val responseGenerator = DvexResponseGenerator()

    private val contactResolver = ContactResolver(context)

    // Pending sensitive action
    private var activePendingIntent: DvexIntent? = null
    private var activePendingId: String? = null

    // Latest speech recognition confidence
    @Volatile
    private var lastAsrConfidence: Float? = null

    /**
     * Main D-VEX processing pipeline.
     */
    suspend fun process(rawInput: String): BrainExecutionResult {

        Log.i(TAG_BRAIN, "Input: $rawInput")

        if (rawInput.isBlank()) {
            Log.i(
                TAG_BRAIN,
                "Empty input provided to brain; returning standby result"
            )

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

        // ================================================================
        // 1. Pending sensitive-action confirmation
        // ================================================================

        if (activePendingIntent != null) {

            if (isAffirmative(lower)) {

                Log.i(
                    TAG_BRAIN,
                    "User confirmed pending action: $activePendingIntent"
                )

                val confirmedIntent = activePendingIntent!!

                activePendingIntent = null
                activePendingId = null

                val confirmationDetection =
                    intentDetector.detectIntent(
                        rawInput,
                        conversationContext
                    )

                val confirmationLang =
                    confirmationDetection.language

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

                Log.i(TAG_RESPONSE, spoken)

                return BrainExecutionResult(
                    intent = confirmedIntent,
                    toolResult = executionResult,
                    spokenText = spoken,
                    displayText = spoken,
                    language = effectiveLang,
                    toolName = executionResult.toolName
                )
            }

            if (isNegative(lower)) {

                Log.i(
                    TAG_BRAIN,
                    "User cancelled pending action."
                )

                activePendingIntent = null
                activePendingId = null

                val cancelDetection =
                    intentDetector.detectIntent(
                        rawInput,
                        conversationContext
                    )

                val cancelLang =
                    cancelDetection.language

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

                        DetectedLanguage.ENGLISH ->
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

                Log.i(TAG_RESPONSE, cancelMessage)

                return BrainExecutionResult(
                    intent = DvexIntent.Conversation(rawInput),
                    toolResult = cancelResult,
                    spokenText = cancelMessage,
                    displayText = cancelMessage,
                    language = effectiveLang,
                    toolName = "cancellation"
                )
            }

            // User changed the subject.
            Log.i(
                TAG_BRAIN,
                "Pending action cleared because user changed subject."
            )

            activePendingIntent = null
            activePendingId = null
        }

        // ================================================================
        // 2. Intent understanding
        // ================================================================

        val detection =
            intentDetector.detectIntent(
                rawInput,
                conversationContext
            )

        var intent = detection.intent

        val confidence = detection.confidence
        val language = detection.language

        Log.i(
            TAG_INTENT,
            "$intent (confidence: $confidence, lang: $language)"
        )

        // ================================================================
        // 3. Persistent memory
        // ================================================================

        val memoryHint =
            buildMemoryHint()

        if (memoryHint.isNotBlank()) {
            Log.i(
                TAG_MEMORY,
                "Persistent memory available: $memoryHint"
            )
        }

        // ================================================================
        // 4. Low-confidence speech protection
        // ================================================================

        val asrConfidence = lastAsrConfidence

        if (
            asrConfidence != null &&
            asrConfidence < ASR_LOW_CONFIDENCE_THRESHOLD &&
            confidence < INTENT_LOW_CONFIDENCE_THRESHOLD &&
            intent is DvexIntent.Conversation
        ) {

            Log.i(
                TAG_BRAIN,
                "Low ASR confidence ($asrConfidence) with weak intent."
            )

            intent = DvexIntent.LowConfidence(
                clarificationPrompt =
                    "Sorry, Sir, I didn't quite catch that. Could you say it again?",
                candidateIntent = null
            )
        }

        // ================================================================
        // 5. Tone estimation
        // ================================================================

        val tone =
            responseGenerator.estimateTone(rawInput)

        Log.i(
            TAG_BRAIN,
            "Estimated tone: $tone"
        )

        // ================================================================
        // 6. Tool selection + execution
        // ================================================================

        val toolResult =
            toolRouter.execute(
                intent,
                conversationContext
            )

        Log.i(
            TAG_RESULT,
            "Tool: ${toolResult.toolName} -> Status: ${toolResult.status}"
        )

        // ================================================================
        // 7. Natural response generation
        // ================================================================

        /*
         * Memory is currently read and tracked here.
         *
         * The response generator still receives the original user input
         * so memory information cannot accidentally change intent detection.
         *
         * The next memory upgrade will allow D-VEX to actively use stored
         * personality/preferences while generating replies.
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

        Log.i(
            TAG_RESPONSE,
            spokenResponse
        )

        // ================================================================
        // 8. Update short-term conversation context
        // ================================================================

        conversationContext.update(
            input = rawInput,
            intent = intent,
            toolResult = toolResult,
            language = language,
            tone = tone,
            spokenResponse = spokenResponse
        )

        // ================================================================
        // 9. Sensitive action confirmation
        // ================================================================

        if (toolResult.requiresConfirmation) {

            activePendingIntent = intent
            activePendingId = toolResult.pendingActionId

            Log.i(
                TAG_BRAIN,
                "Armed pending confirmation for sensitive action: " +
                    "${intent::class.simpleName}"
            )
        }

        // ================================================================
        // 10. Final result
        // ================================================================

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

    // ====================================================================
    // Persistent memory
    // ====================================================================

    /**
     * Reads important persistent D-VEX memory.
     *
     * This does NOT modify the user's input.
     * It only provides information that can later be used by the
     * response/personality layer.
     */
    private fun buildMemoryHint(): String {

        val memories = mutableListOf<String>()

        val preferredName =
            memoryStore.recall("preferred_name")

        val replyStyle =
            memoryStore.recall("reply_style")

        val personality =
            memoryStore.recall("personality")

        val languagePreference =
            memoryStore.recall("language_preference")

        if (!preferredName.isNullOrBlank()) {
            memories.add(
                "User prefers to be called $preferredName."
            )
        }

        if (!replyStyle.isNullOrBlank()) {
            memories.add(
                "User prefers this reply style: $replyStyle"
            )
        }

        if (!personality.isNullOrBlank()) {
            memories.add(
                "Preferred D-VEX personality: $personality"
            )
        }

        if (!languagePreference.isNullOrBlank()) {
            memories.add(
                "Preferred language style: $languagePreference"
            )
        }

        return memories.joinToString(" ")
    }

    // ====================================================================
    // Sensitive action execution
    // ====================================================================

    private suspend fun executeConfirmedAction(
        intent: DvexIntent
    ): DvexToolResult {

        return toolRouter.executeConfirmed(intent)
    }

    // ====================================================================
    // Confirmation control
    // ====================================================================

    fun cancelPendingConfirmation() {

        activePendingIntent = null
        activePendingId = null
    }

    fun hasPendingConfirmation(): Boolean {

        return activePendingIntent != null
    }

    // ====================================================================
    // Confirmation detection
    // ====================================================================

    private fun isAffirmative(
        lower: String
    ): Boolean {

        val clean =
            lower
                .trimEnd('.', '?', '!', ',')
                .trim()

        return clean == "yes" ||
            clean == "confirm" ||
            clean == "call" ||
            clean == "send" ||
            clean == "sure" ||
            clean == "do it" ||
            clean == "yeah" ||
            clean == "yep" ||
            clean == "aama" ||
            clean == "seri" ||
            clean == "sari" ||
            clean == "pannu" ||
            clean == "pannunga" ||
            clean == "anupu" ||
            clean == "anuppu" ||
            clean == "ok" ||
            clean == "okay" ||
            clean == "yes sir" ||
            clean == "sure sir" ||
            clean == "okay sir" ||
            clean == "சரி" ||
            clean == "பண்ணு" ||
            clean == "அனுப்பு" ||
            clean == "ஆம்" ||
            clean == "ஆமாம்" ||
            clean.contains("yes") ||
            clean.contains("confirm") ||
            clean.contains("ஆமாம்") ||
            clean.contains("சரி") ||
            clean.contains("sure")
    }

    private fun isNegative(
        lower: String
    ): Boolean {

        val clean =
            lower
                .trimEnd('.', '?', '!', ',')
                .trim()

        return clean == "no" ||
            clean == "cancel" ||
            clean == "stop" ||
            clean == "don't" ||
            clean == "nevermind" ||
            clean == "vendaam" ||
            clean == "vendam" ||
            clean == "illai" ||
            clean == "வேண்டாம்" ||
            clean == "இல்லை" ||
            clean == "நிறுத்து" ||
            clean.contains("cancel") ||
            clean.contains("stop")
    }

    // ====================================================================
    // ASR confidence
    // ====================================================================

    fun reportAsrConfidence(
        confidence: Float?
    ) {

        lastAsrConfidence = confidence
    }

    // ====================================================================
    // RESET SHORT-TERM CONTEXT
    // ====================================================================

    /**
     * Clears temporary conversation state.
     *
     * IMPORTANT:
     * This does NOT delete persistent D-VEX memory.
     *
     * ConversationContext = short-term memory
     * DvexMemoryStore      = persistent memory
     */
    fun resetContext() {

        conversationContext.clear()

        cancelPendingConfirmation()

        lastAsrConfidence = null

        Log.i(
            TAG_BRAIN,
            "Short-term conversation context reset."
        )
    }

    // ====================================================================
    // Debug / memory helpers
    // ====================================================================

    /**
     * Returns the currently available persistent memory as a string.
     */
    fun getMemoryHint(): String {

        return buildMemoryHint()
    }

    /**
     * Completely clears persistent memory.
     *
     * Use only when the user explicitly asks D-VEX to forget everything.
     */
    fun clearPersistentMemory() {

        memoryStore.clearAll()

        Log.i(
            TAG_MEMORY,
            "Persistent memory cleared."
        )
    }

    companion object {

        private const val TAG_BRAIN =
            "[D-VEX][BRAIN]"

        private const val TAG_INTENT =
            "[D-VEX][INTENT]"

        private const val TAG_RESULT =
            "[D-VEX][RESULT]"

        private const val TAG_RESPONSE =
            "[D-VEX][RESPONSE]"

        private const val TAG_MEMORY =
            "[D-VEX][MEMORY]"

        /**
         * ASR scores below this are considered uncertain.
         */
        private const val ASR_LOW_CONFIDENCE_THRESHOLD =
            0.4f

        /**
         * Weakly parsed intents below this confidence
         * are not trusted when ASR is also weak.
         */
        private const val INTENT_LOW_CONFIDENCE_THRESHOLD =
            0.75f
    }
}
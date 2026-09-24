package com.example.brain

import android.content.Context
import android.util.Log
import com.example.repository.AppLauncherRepository
import com.example.repository.ContactResolver
import com.example.repository.DeviceControlRepository
import java.util.Locale

/**
 * D-VEX Smart Brain
 *
 * Handles:
 * - Intent detection
 * - Language detection
 * - Conversation context
 * - Tone estimation
 * - Memory
 * - Tool execution
 * - Sensitive action confirmation
 * - Natural response generation
 * - ASR confidence
 */
class DvexSmartBrain
    context: (Context)
 {

    companion object {
        private const val TAG = "DvexSmartBrain"

        private const val DEFAULT_ASR_CONFIDENCE = 1.0f
        private const val LOW_CONFIDENCE_THRESHOLD = 0.45f
    }

    private val appContext = context.applicationContext

    // =========================================================
    // CORE COMPONENTS
    // =========================================================

    private val intentDetector = IntentDetector()

    private val conversationContext = ConversationContext(
        maxHistorySize = 10
    )

    private val memoryStore = DvexMemoryStore(
        appContext
    )

    private val responseGenerator = DvexResponseGenerator()

    private val appLauncherRepository =
        AppLauncherRepository(appContext)

    private val deviceControlRepository =
        DeviceControlRepository(appContext)

    private val contactResolver =
        ContactResolver(appContext)

    private val toolRouter = DvexToolRouter(
        context = appContext,
        appLauncherRepository = appLauncherRepository,
        deviceControlRepository = deviceControlRepository,
        contactResolver = contactResolver
    )

    // =========================================================
    // STATE
    // =========================================================

    private var lastAsrConfidence: Float =
        DEFAULT_ASR_CONFIDENCE

    private var pendingConfirmationIntent: DvexIntent? =
        null

    private var pendingConfirmationActionId: String? =
        null

    // =========================================================
    // MAIN PROCESS
    // =========================================================

    fun process(rawInput: String): BrainExecutionResult {

        val userInput = rawInput.trim()

        // -----------------------------------------------------
        // EMPTY INPUT
        // -----------------------------------------------------

        if (userInput.isBlank()) {
            return createStandbyResult()
        }

        Log.d(
            TAG,
            "Processing: $userInput"
        )

        // -----------------------------------------------------
        // PENDING CONFIRMATION
        // -----------------------------------------------------

        if (hasPendingConfirmation()) {

            val confirmationResult =
                handleConfirmationInput(userInput)

            if (confirmationResult != null) {
                return confirmationResult
            }
        }

        // -----------------------------------------------------
        // INTENT DETECTION
        // -----------------------------------------------------

        val detection =
            intentDetector.detect(userInput)

        val detectedIntent =
            detection.intent

        val detectedLanguage =
            detection.language

        val intentConfidence =
            detection.confidence

        Log.d(
            TAG,
            "Intent=$detectedIntent " +
                    "Language=$detectedLanguage " +
                    "Confidence=$intentConfidence " +
                    "ASR=$lastAsrConfidence"
        )

        // -----------------------------------------------------
        // LOW CONFIDENCE
        // -----------------------------------------------------

        if (
            lastAsrConfidence < LOW_CONFIDENCE_THRESHOLD ||
            intentConfidence < LOW_CONFIDENCE_THRESHOLD
        ) {

            val lowConfidenceResult =
                DvexToolResult(
                    success = false,
                    spokenText = "",
                    displayText = "",
                    toolName = "low_confidence"
                )

            val tone =
                DvexResponseGenerator
                    .estimateTone(userInput)

            val spokenText =
                responseGenerator.generateResponse(
                    intent = DvexIntent.LowConfidence,
                    toolResult = lowConfidenceResult,
                    language = detectedLanguage,
                    userInput = userInput,
                    context = conversationContext,
                    tone = tone,
                    memoryHint = buildMemoryHint()
                )

            val finalText =
                spokenText.ifBlank {
                    when (detectedLanguage) {

                        DetectedLanguage.TAMIL ->
                            "சரியா கேட்கல. இன்னொரு தடவை சொல்லு."

                        DetectedLanguage.TANGLISH ->
                            "Correct-ah kekkala. Innum oru thadava sollu."

                        else ->
                            "I didn't catch that clearly. Please say it again."
                    }
                }

            conversationContext.update(
                userInput = userInput,
                intent = DvexIntent.LowConfidence,
                toolName = "low_confidence",
                language = detectedLanguage,
                tone = tone,
                spokenResponse = finalText
            )

            return BrainExecutionResult(
                intent = DvexIntent.LowConfidence,
                toolResult = lowConfidenceResult,
                spokenText = finalText,
                displayText = finalText,
                language = detectedLanguage,
                toolName = "low_confidence",
                isSensitiveAction = false,
                pendingActionId = null
            )
        }

        // -----------------------------------------------------
        // TONE
        // -----------------------------------------------------

        val tone =
            DvexResponseGenerator
                .estimateTone(userInput)

        // -----------------------------------------------------
        // CONTEXT
        // -----------------------------------------------------

        val contextualValue =
            conversationContext
                .resolveContextualFollowUp(userInput)

        if (contextualValue != null) {

            Log.d(
                TAG,
                "Contextual follow-up: $contextualValue"
            )
        }

        // -----------------------------------------------------
        // TOOL EXECUTION
        // -----------------------------------------------------

        val toolResult =
            try {

                toolRouter.execute(
                    detectedIntent
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Tool execution failed",
                    e
                )

                DvexToolResult(
                    success = false,
                    spokenText = "",
                    displayText = "",
                    toolName = "tool_error"
                )
            }

        // -----------------------------------------------------
        // MEMORY
        // -----------------------------------------------------

        val memoryHint =
            buildMemoryHint()

        // -----------------------------------------------------
        // RESPONSE GENERATION
        // -----------------------------------------------------

        val generatedText =
            responseGenerator.generateResponse(
                intent = detectedIntent,
                toolResult = toolResult,
                language = detectedLanguage,
                userInput = userInput,
                context = conversationContext,
                tone = tone,
                memoryHint = memoryHint
            )

        val finalSpokenText =
            generatedText.ifBlank {

                toolResult.spokenText.ifBlank {
                    toolResult.displayText
                }
            }

        val finalDisplayText =
            toolResult.displayText.ifBlank {
                finalSpokenText
            }

        // -----------------------------------------------------
        // UPDATE CONVERSATION CONTEXT
        // -----------------------------------------------------

        conversationContext.update(
            userInput = userInput,
            intent = detectedIntent,
            toolName = toolResult.toolName,
            language = detectedLanguage,
            tone = tone,
            spokenResponse = finalSpokenText
        )

        // -----------------------------------------------------
        // SENSITIVE ACTION
        // -----------------------------------------------------

        if (toolResult.requiresConfirmation) {

            pendingConfirmationIntent =
                detectedIntent

            pendingConfirmationActionId =
                toolResult.pendingActionId

            Log.d(
                TAG,
                "Confirmation required. " +
                        "ActionId=$pendingConfirmationActionId"
            )
        }

        // -----------------------------------------------------
        // FINAL RESULT
        // -----------------------------------------------------

        return BrainExecutionResult(
            intent = detectedIntent,
            toolResult = toolResult,
            spokenText = finalSpokenText,
            displayText = finalDisplayText,
            language = detectedLanguage,
            toolName = toolResult.toolName,
            isSensitiveAction =
                toolResult.requiresConfirmation,
            pendingActionId =
                toolResult.pendingActionId
        )
    }

    // =========================================================
    // CONFIRMATION
    // =========================================================

    private fun handleConfirmationInput(
        input: String
    ): BrainExecutionResult? {

        if (!hasPendingConfirmation()) {
            return null
        }

        val normalized =
            input
                .trim()
                .lowercase(Locale.getDefault())

        // -----------------------------------------------------
        // YES
        // -----------------------------------------------------

        val isYes =
            normalized == "yes" ||
            normalized == "y" ||
            normalized == "ok" ||
            normalized == "okay" ||
            normalized == "confirm" ||
            normalized == "confirmed" ||
            normalized == "do it" ||
            normalized == "go ahead" ||
            normalized == "ama" ||
            normalized == "aama" ||
            normalized == "seri" ||
            normalized == "sari" ||
            normalized == "ஆம்" ||
            normalized == "ஆமாம்"

        if (isYes) {

            val intent =
                pendingConfirmationIntent

            val actionId =
                pendingConfirmationActionId

            if (
                intent == null ||
                actionId.isNullOrBlank()
            ) {

                clearPendingConfirmation()

                return null
            }

            val result =
                try {

                    toolRouter.executeConfirmed(
                        intent = intent,
                        pendingActionId = actionId
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Confirmed action failed",
                        e
                    )

                    DvexToolResult(
                        success = false,
                        spokenText = "",
                        displayText = "",
                        toolName = "confirmation_error"
                    )
                }

            clearPendingConfirmation()

            val language =
                conversationContext.lastLanguage
                    ?: DetectedLanguage.ENGLISH

            val tone =
                DvexResponseGenerator
                    .estimateTone(input)

            val response =
                responseGenerator.generateResponse(
                    intent = intent,
                    toolResult = result,
                    language = language,
                    userInput = input,
                    context = conversationContext,
                    tone = tone,
                    memoryHint = buildMemoryHint()
                )

            val finalResponse =
                response.ifBlank {

                    result.spokenText.ifBlank {
                        result.displayText
                    }
                }

            conversationContext.update(
                userInput = input,
                intent = intent,
                toolName = result.toolName,
                language = language,
                tone = tone,
                spokenResponse = finalResponse
            )

            return BrainExecutionResult(
                intent = intent,
                toolResult = result,
                spokenText = finalResponse,
                displayText =
                    result.displayText.ifBlank {
                        finalResponse
                    },
                language = language,
                toolName = result.toolName,
                isSensitiveAction = false,
                pendingActionId = null
            )
        }

        // -----------------------------------------------------
        // NO
        // -----------------------------------------------------

        val isNo =
            normalized == "no" ||
            normalized == "n" ||
            normalized == "cancel" ||
            normalized == "stop" ||
            normalized == "don't" ||
            normalized == "dont" ||
            normalized == "nope" ||
            normalized == "vendam" ||
            normalized == "venam" ||
            normalized == "வேண்டாம்" ||
            normalized == "நிறுத்து"

        if (isNo) {

            clearPendingConfirmation()

            val language =
                conversationContext.lastLanguage
                    ?: DetectedLanguage.ENGLISH

            val cancelledResult =
                DvexToolResult(
                    success = false,
                    spokenText = "",
                    displayText = "",
                    toolName = "confirmation_cancelled"
                )

            val generatedResponse =
                responseGenerator.generateResponse(
                    intent = DvexIntent.Conversation,
                    toolResult = cancelledResult,
                    language = language,
                    userInput = input,
                    context = conversationContext,
                    tone =
                        DvexResponseGenerator
                            .estimateTone(input),
                    memoryHint = buildMemoryHint()
                )

            val finalResponse =
                generatedResponse.ifBlank {

                    when (language) {

                        DetectedLanguage.TAMIL ->
                            "சரி, cancel பண்ணிட்டேன்."

                        DetectedLanguage.TANGLISH ->
                            "Seri, cancel pannitten."

                        else ->
                            "Okay, cancelled."
                    }
                }

            conversationContext.update(
                userInput = input,
                intent = DvexIntent.Conversation,
                toolName = "confirmation_cancelled",
                language = language,
                tone =
                    DvexResponseGenerator
                        .estimateTone(input),
                spokenResponse = finalResponse
            )

            return BrainExecutionResult(
                intent = DvexIntent.Conversation,
                toolResult = cancelledResult,
                spokenText = finalResponse,
                displayText = finalResponse,
                language = language,
                toolName = "confirmation_cancelled",
                isSensitiveAction = false,
                pendingActionId = null
            )
        }

        // Not a confirmation response
        return null
    }

    // =========================================================
    // MEMORY
    // =========================================================

    private fun buildMemoryHint(): String {

        val parts =
            mutableListOf<String>()

        memoryStore
            .get("preferred_name")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts.add(
                    "preferred_name=$it"
                )
            }

        memoryStore
            .get("reply_style")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts.add(
                    "reply_style=$it"
                )
            }

        memoryStore
            .get("personality")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts.add(
                    "personality=$it"
                )
            }

        memoryStore
            .get("language_preference")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts.add(
                    "language_preference=$it"
                )
            }

        memoryStore
            .get("user_preference")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts.add(
                    "user_preference=$it"
                )
            }

        memoryStore
            .get("user_likes")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts.add(
                    "user_likes=$it"
                )
            }

        memoryStore
            .get("user_dislikes")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                parts.add(
                    "user_dislikes=$it"
                )
            }

        return parts.joinToString("|")
    }

    fun getMemoryHint(): String {
        return buildMemoryHint()
    }

    fun clearPersistentMemory() {

        memoryStore.clearAll()

        Log.d(
            TAG,
            "Persistent memory cleared"
        )
    }

    // =========================================================
    // ASR CONFIDENCE
    // =========================================================

    fun setAsrConfidence(
        confidence: Float
    ) {

        lastAsrConfidence =
            confidence.coerceIn(
                0.0f,
                1.0f
            )

        Log.d(
            TAG,
            "ASR confidence=$lastAsrConfidence"
        )
    }

    /**
     * AssistantRepository may send Float?.
     *
     * Null means no confidence value was available.
     * In that case we use the default confidence.
     */
    fun reportAsrConfidence(
        confidence: Float?
    ) {

        setAsrConfidence(
            confidence
                ?: DEFAULT_ASR_CONFIDENCE
        )
    }

    fun getAsrConfidence(): Float {
        return lastAsrConfidence
    }

    // =========================================================
    // PENDING CONFIRMATION
    // =========================================================

    fun hasPendingConfirmation(): Boolean {

        return pendingConfirmationIntent != null &&
                !pendingConfirmationActionId
                    .isNullOrBlank()
    }

    fun getPendingConfirmationActionId(): String? {
        return pendingConfirmationActionId
    }

    /**
     * Public method used by AssistantRepository.
     */
    fun cancelPendingConfirmation() {

        pendingConfirmationIntent = null
        pendingConfirmationActionId = null

        Log.d(
            TAG,
            "Pending confirmation cancelled"
        )
    }

    private fun clearPendingConfirmation() {

        pendingConfirmationIntent = null
        pendingConfirmationActionId = null
    }

    // =========================================================
    // CONTEXT
    // =========================================================

    fun getContextSummary(): String {
        return conversationContext
            .getContextSummary()
    }

    fun resetContext() {

        conversationContext.clear()

        clearPendingConfirmation()

        Log.d(
            TAG,
            "Conversation context reset"
        )
    }

    // =========================================================
    // STANDBY
    // =========================================================

    private fun createStandbyResult():
            BrainExecutionResult {

        val language =
            conversationContext.lastLanguage
                ?: DetectedLanguage.ENGLISH

        val text =
            when (language) {

                DetectedLanguage.TAMIL ->
                    "சொல்லு, நான் கேக்குறேன்."

                DetectedLanguage.TANGLISH ->
                    "Sollu, naan kekkuren."

                else ->
                    "I'm listening."
            }

        val result =
            DvexToolResult(
                success = true,
                spokenText = text,
                displayText = text,
                toolName = "standby"
            )

        return BrainExecutionResult(
            intent = DvexIntent.Conversation,
            toolResult = result,
            spokenText = text,
            displayText = text,
            language = language,
            toolName = "standby",
            isSensitiveAction = false,
            pendingActionId = null
        )
    }
}

/**
 * Result returned by D-VEX Smart Brain.
 */
data class BrainExecutionResult(
    val intent: DvexIntent,
    val toolResult: DvexToolResult,
    val spokenText: String,
    val displayText: String = spokenText,
    val language: DetectedLanguage =
        DetectedLanguage.ENGLISH,
    val toolName: String =
        toolResult.toolName,
    val isSensitiveAction: Boolean =
        toolResult.requiresConfirmation,
    val pendingActionId: String? =
        toolResult.pendingActionId
)
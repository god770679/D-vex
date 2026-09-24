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
class DvexSmartBrain(
    context: Context
) {

    companion object {
        private const val TAG = "DvexSmartBrain"

        private const val DEFAULT_ASR_CONFIDENCE = 1.0f
        private const val LOW_CONFIDENCE_THRESHOLD = 0.45f
    }

    private val appContext = context.applicationContext

    // ---------------------------------------------------------
    // CORE COMPONENTS
    // ---------------------------------------------------------

    private val intentDetector = IntentDetector()

    private val conversationContext = ConversationContext(
        maxHistorySize = 10
    )

    private val memoryStore = DvexMemoryStore(
        appContext
    )

    private val responseGenerator = DvexResponseGenerator()

    private val appLauncherRepository = AppLauncherRepository(
        appContext
    )

    private val deviceControlRepository = DeviceControlRepository(
        appContext
    )

    private val contactResolver = ContactResolver(
        appContext
    )

    private val toolRouter = DvexToolRouter(
        context = appContext,
        appLauncherRepository = appLauncherRepository,
        deviceControlRepository = deviceControlRepository,
        contactResolver = contactResolver
    )

    // ---------------------------------------------------------
    // STATE
    // ---------------------------------------------------------

    private var lastAsrConfidence: Float = DEFAULT_ASR_CONFIDENCE

    private var pendingConfirmationIntent: DvexIntent? = null

    private var pendingConfirmationActionId: String? = null

    // ---------------------------------------------------------
    // MAIN PROCESS
    // ---------------------------------------------------------

    fun process(rawInput: String): BrainExecutionResult {

        val userInput = rawInput.trim()

        // Empty input
        if (userInput.isBlank()) {
            return createStandbyResult()
        }

        Log.d(TAG, "Processing: $userInput")

        // -----------------------------------------------------
        // 1. CHECK PENDING CONFIRMATION
        // -----------------------------------------------------

        if (hasPendingConfirmation()) {

            val confirmationResult = handleConfirmationInput(userInput)

            if (confirmationResult != null) {
                return confirmationResult
            }
        }

        // -----------------------------------------------------
        // 2. DETECT INTENT + LANGUAGE
        // -----------------------------------------------------

        val detection = intentDetector.detect(userInput)

        val detectedIntent = detection.intent
        val detectedLanguage = detection.language

        val intentConfidence = detection.confidence

        Log.d(
            TAG,
            "Intent=$detectedIntent " +
                    "Language=$detectedLanguage " +
                    "Confidence=$intentConfidence " +
                    "ASR=$lastAsrConfidence"
        )

        // -----------------------------------------------------
        // 3. LOW CONFIDENCE
        // -----------------------------------------------------

        if (
            lastAsrConfidence < LOW_CONFIDENCE_THRESHOLD ||
            intentConfidence < LOW_CONFIDENCE_THRESHOLD
        ) {

            val spokenText = responseGenerator.generateResponse(
                intent = DvexIntent.LowConfidence,
                toolResult = DvexToolResult(
                    success = false,
                    spokenText = "",
                    displayText = "",
                    toolName = "low_confidence"
                ),
                language = detectedLanguage,
                userInput = userInput,
                context = conversationContext,
                tone = DvexResponseGenerator.estimateTone(userInput),
                memoryHint = buildMemoryHint()
            )

            conversationContext.update(
                userInput = userInput,
                intent = DvexIntent.LowConfidence,
                toolName = "low_confidence",
                language = detectedLanguage,
                tone = DvexResponseGenerator.estimateTone(userInput),
                spokenResponse = spokenText
            )

            return BrainExecutionResult(
                intent = DvexIntent.LowConfidence,
                toolResult = DvexToolResult(
                    success = false,
                    spokenText = spokenText,
                    displayText = spokenText,
                    toolName = "low_confidence"
                ),
                spokenText = spokenText,
                displayText = spokenText,
                language = detectedLanguage,
                toolName = "low_confidence",
                isSensitiveAction = false,
                pendingActionId = null
            )
        }

        // -----------------------------------------------------
        // 4. TONE
        // -----------------------------------------------------

        val tone = DvexResponseGenerator.estimateTone(userInput)

        // -----------------------------------------------------
        // 5. CONTEXT
        // -----------------------------------------------------

        val contextualValue =
            conversationContext.resolveContextualFollowUp(userInput)

        if (contextualValue != null) {
            Log.d(
                TAG,
                "Contextual follow-up detected: $contextualValue"
            )
        }

        // -----------------------------------------------------
        // 6. EXECUTE TOOL
        // -----------------------------------------------------

        val toolResult = try {

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
        // 7. GENERATE NATURAL RESPONSE
        // -----------------------------------------------------

        val memoryHint = buildMemoryHint()

        val spokenText = responseGenerator.generateResponse(
            intent = detectedIntent,
            toolResult = toolResult,
            language = detectedLanguage,
            userInput = userInput,
            context = conversationContext,
            tone = tone,
            memoryHint = memoryHint
        )

        val finalSpokenText =
            if (spokenText.isBlank()) {
                toolResult.spokenText.ifBlank {
                    toolResult.displayText
                }
            } else {
                spokenText
            }

        // -----------------------------------------------------
        // 8. UPDATE CONTEXT
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
        // 9. SENSITIVE ACTION
        // -----------------------------------------------------

        if (toolResult.requiresConfirmation) {

            pendingConfirmationIntent = detectedIntent

            pendingConfirmationActionId =
                toolResult.pendingActionId

            Log.d(
                TAG,
                "Confirmation required. " +
                        "ActionId=$pendingConfirmationActionId"
            )
        }

        // -----------------------------------------------------
        // 10. FINAL RESULT
        // -----------------------------------------------------

        return BrainExecutionResult(
            intent = detectedIntent,
            toolResult = toolResult,
            spokenText = finalSpokenText,
            displayText = toolResult.displayText.ifBlank {
                finalSpokenText
            },
            language = detectedLanguage,
            toolName = toolResult.toolName,
            isSensitiveAction = toolResult.requiresConfirmation,
            pendingActionId = toolResult.pendingActionId
        )
    }

    // ---------------------------------------------------------
    // CONFIRMATION HANDLING
    // ---------------------------------------------------------

    private fun handleConfirmationInput(
        input: String
    ): BrainExecutionResult? {

        if (!hasPendingConfirmation()) {
            return null
        }

        val normalized = input
            .trim()
            .lowercase(Locale.getDefault())

        // -----------------------------------------------------
        // YES
        // -----------------------------------------------------

        if (
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
        ) {

            val intent =
                pendingConfirmationIntent

            val actionId =
                pendingConfirmationActionId

            if (intent == null || actionId.isNullOrBlank()) {
                clearPendingConfirmation()
                return null
            }

            val result = try {

                toolRouter.executeConfirmed(
                    intent = intent,
                    pendingActionId = actionId
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Confirmed tool execution failed",
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
                DvexResponseGenerator.estimateTone(input)

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

            conversationContext.update(
                userInput = input,
                intent = intent,
                toolName = result.toolName,
                language = language,
                tone = tone,
                spokenResponse = response
            )

            return BrainExecutionResult(
                intent = intent,
                toolResult = result,
                spokenText = response.ifBlank {
                    result.spokenText.ifBlank {
                        result.displayText
                    }
                },
                displayText = result.displayText.ifBlank {
                    response
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

        if (
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
        ) {

            clearPendingConfirmation()

            val language =
                conversationContext.lastLanguage
                    ?: DetectedLanguage.ENGLISH

            val cancelledResult = DvexToolResult(
                success = false,
                spokenText = "",
                displayText = "",
                toolName = "confirmation_cancelled"
            )

            val response =
                responseGenerator.generateResponse(
                    intent = DvexIntent.Conversation,
                    toolResult = cancelledResult,
                    language = language,
                    userInput = input,
                    context = conversationContext,
                    tone = DvexResponseGenerator.estimateTone(input),
                    memoryHint = buildMemoryHint()
                )

            val finalResponse =
                response.ifBlank {
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
                tone = DvexResponseGenerator.estimateTone(input),
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

        // -----------------------------------------------------
        // UNKNOWN CONFIRMATION RESPONSE
        // -----------------------------------------------------

        return null
    }

    // ---------------------------------------------------------
    // MEMORY
    // ---------------------------------------------------------

    private fun buildMemoryHint(): String {

        val parts = mutableListOf<String>()

        memoryStore.get(
            "preferred_name"
        )?.takeIf { it.isNotBlank() }?.let {
            parts.add("preferred_name=$it")
        }

        memoryStore.get(
            "reply_style"
        )?.takeIf { it.isNotBlank() }?.let {
            parts.add("reply_style=$it")
        }

        memoryStore.get(
            "personality"
        )?.takeIf { it.isNotBlank() }?.let {
            parts.add("personality=$it")
        }

        memoryStore.get(
            "language_preference"
        )?.takeIf { it.isNotBlank() }?.let {
            parts.add("language_preference=$it")
        }

        memoryStore.get(
            "user_preference"
        )?.takeIf { it.isNotBlank() }?.let {
            parts.add("user_preference=$it")
        }

        memoryStore.get(
            "user_likes"
        )?.takeIf { it.isNotBlank() }?.let {
            parts.add("user_likes=$it")
        }

        memoryStore.get(
            "user_dislikes"
        )?.takeIf { it.isNotBlank() }?.let {
            parts.add("user_dislikes=$it")
        }

        return parts.joinToString("|")
    }

    // ---------------------------------------------------------
    // PUBLIC MEMORY HELPERS
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // ASR CONFIDENCE
    // ---------------------------------------------------------

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
     * Compatibility method used by AssistantRepository.
     */
    fun reportAsrConfidence(
        confidence: Float
    ) {
        setAsrConfidence(confidence)
    }

    fun getAsrConfidence(): Float {
        return lastAsrConfidence
    }

    // ---------------------------------------------------------
    // PENDING CONFIRMATION
    // ---------------------------------------------------------

    fun hasPendingConfirmation(): Boolean {
        return pendingConfirmationIntent != null &&
                !pendingConfirmationActionId.isNullOrBlank()
    }

    fun getPendingConfirmationActionId(): String? {
        return pendingConfirmationActionId
    }

    /**
     * Cancel currently pending sensitive action.
     *
     * Compatibility method used by AssistantRepository.
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

    // ---------------------------------------------------------
    // CONTEXT
    // ---------------------------------------------------------

    fun getContextSummary(): String {
        return conversationContext.getContextSummary()
    }

    fun resetContext() {

        conversationContext.clear()

        clearPendingConfirmation()

        Log.d(
            TAG,
            "Conversation context reset"
        )
    }

    // ---------------------------------------------------------
    // STANDBY
    // ---------------------------------------------------------

    private fun createStandbyResult(): BrainExecutionResult {

        val language =
            conversationContext.lastLanguage
                ?: DetectedLanguage.ENGLISH

        val text = when (language) {

            DetectedLanguage.TAMIL ->
                "சொல்லு, நான் கேக்குறேன்."

            DetectedLanguage.TANGLISH ->
                "Sollu, naan kekkuren."

            else ->
                "I'm listening."
        }

        val result = DvexToolResult(
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
    val language: DetectedLanguage = DetectedLanguage.ENGLISH,
    val toolName: String = toolResult.toolName,
    val isSensitiveAction: Boolean = toolResult.requiresConfirmation,
    val pendingActionId: String? = toolResult.pendingActionId
)
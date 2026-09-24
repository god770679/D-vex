package com.example.brain

import android.content.Context
import android.util.Log
import com.example.dvex.data.AppLauncherRepository
import com.example.dvex.data.ContactResolver
import com.example.dvex.data.DeviceControlRepository
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

class DvexSmartBrain(
    context: Context,
    private val appLauncher: AppLauncherRepository,
    private val deviceControl: DeviceControlRepository
) {

    companion object {
        private const val TAG = "DvexSmartBrain"

        private const val MIN_ASR_CONFIDENCE = 0.40f
        private const val MIN_INTENT_CONFIDENCE = 0.75f
    }

    private val appContext = context.applicationContext

    private val intentDetector = IntentDetector()

    private val conversationContext =
        ConversationContext()

    /*
     * Persistent memory.
     * This survives app restart.
     */
    private val memoryStore =
        DvexMemoryStore(appContext)

    private val toolRouter =
        DvexToolRouter(
            context = appContext,
            appLauncher = appLauncher,
            deviceControl = deviceControl
        )

    private val responseGenerator =
        DvexResponseGenerator()

    /*
     * Kept here for future contact-aware brain features.
     */
    private val contactResolver =
        ContactResolver(appContext)

    private var activePendingIntent: DvexIntent? = null

    private var activePendingId: String? = null

    private var lastAsrConfidence: Float = 1.0f

    // ============================================================
    // MAIN BRAIN
    // ============================================================

    fun process(
        rawInput: String
    ): BrainExecutionResult {

        val input = rawInput.trim()

        if (input.isBlank()) {

            return createStandbyResult()
        }

        Log.d(
            TAG,
            "Processing input: $input"
        )

        /*
         * Load persistent memory BEFORE processing.
         */
        val memoryHint =
            buildMemoryHint()

        Log.d(
            TAG,
            "Memory hint: $memoryHint"
        )

        // --------------------------------------------------------
        // 1. Pending confirmation
        // --------------------------------------------------------

        if (hasPendingAction()) {

            return handlePendingConfirmation(
                input = input,
                memoryHint = memoryHint
            )
        }

        // --------------------------------------------------------
        // 2. Detect language + intent
        // --------------------------------------------------------

        val detection =
            intentDetector.detect(input)

        val intent =
            detection.intent

        val language =
            detection.language

        val intentConfidence =
            detection.confidence

        Log.d(
            TAG,
            "Intent=$intent confidence=$intentConfidence language=$language"
        )

        // --------------------------------------------------------
        // 3. Low confidence protection
        // --------------------------------------------------------

        if (
            lastAsrConfidence < MIN_ASR_CONFIDENCE &&
            intentConfidence < MIN_INTENT_CONFIDENCE
        ) {

            val lowConfidenceIntent =
                DvexIntent.LowConfidence

            val toolResult =
                DvexToolResult(
                    success = false,
                    spokenText = "",
                    displayText = "",
                    toolName = "low_confidence"
                )

            val tone =
                responseGenerator.estimateTone(input)

            val response =
                responseGenerator.generateResponse(
                    intent = lowConfidenceIntent,
                    toolResult = toolResult,
                    language = language,
                    userInput = input,
                    context = conversationContext,
                    tone = tone,
                    memoryHint = memoryHint
                )

            conversationContext.update(
                userInput = input,
                intent = lowConfidenceIntent,
                toolName = "low_confidence",
                language = language,
                tone = tone,
                spokenResponse = response
            )

            return BrainExecutionResult(
                intent = lowConfidenceIntent,
                toolResult = toolResult,
                spokenText = response,
                displayText = response,
                language = language,
                toolName = "low_confidence"
            )
        }

        // --------------------------------------------------------
        // 4. Estimate emotional tone
        // --------------------------------------------------------

        val tone =
            responseGenerator.estimateTone(input)

        Log.d(
            TAG,
            "Detected tone=$tone"
        )

        // --------------------------------------------------------
        // 5. Context-aware follow-up
        // --------------------------------------------------------

        val contextualValue =
            conversationContext.resolveContextualFollowUp(
                input
            )

        if (!contextualValue.isNullOrBlank()) {

            Log.d(
                TAG,
                "Context resolved: $contextualValue"
            )
        }

        // --------------------------------------------------------
        // 6. Execute tool
        // --------------------------------------------------------

        val toolResult =
            executeIntent(
                intent = intent
            )

        Log.d(
            TAG,
            "Tool=${toolResult.toolName} success=${toolResult.success}"
        )

        // --------------------------------------------------------
        // 7. Generate natural response
        // --------------------------------------------------------

        val response =
            responseGenerator.generateResponse(
                intent = intent,
                toolResult = toolResult,
                language = language,
                userInput = input,
                context = conversationContext,
                tone = tone,
                memoryHint = memoryHint
            )

        // --------------------------------------------------------
        // 8. Update conversation context
        // --------------------------------------------------------

        conversationContext.update(
            userInput = input,
            intent = intent,
            toolName = toolResult.toolName,
            language = language,
            tone = tone,
            spokenResponse = response
        )

        // --------------------------------------------------------
        // 9. Sensitive action confirmation
        // --------------------------------------------------------

        if (
            toolResult.requiresConfirmation &&
            !toolResult.pendingActionId.isNullOrBlank()
        ) {

            activePendingIntent = intent

            activePendingId =
                toolResult.pendingActionId

            Log.d(
                TAG,
                "Sensitive action armed: $activePendingId"
            )
        }

        return BrainExecutionResult(
            intent = intent,
            toolResult = toolResult,
            spokenText = response,
            displayText = toolResult.displayText.ifBlank {
                response
            },
            language = language,
            toolName = toolResult.toolName,
            isSensitiveAction =
                toolResult.requiresConfirmation,
            pendingActionId =
                toolResult.pendingActionId
        )
    }

    // ============================================================
    // TOOL EXECUTION
    // ============================================================

    private fun executeIntent(
        intent: DvexIntent
    ): DvexToolResult {

        return try {

            toolRouter.execute(intent)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Tool execution failed",
                e
            )

            DvexToolResult(
                success = false,
                spokenText = "I couldn't complete that right now.",
                displayText = "I couldn't complete that right now.",
                toolName = "error"
            )
        }
    }

    // ============================================================
    // PENDING CONFIRMATION
    // ============================================================

    private fun hasPendingAction(): Boolean {
        return activePendingIntent != null &&
                !activePendingId.isNullOrBlank()
    }

    private fun handlePendingConfirmation(
        input: String,
        memoryHint: String
    ): BrainExecutionResult {

        val language =
            intentDetector.detect(input).language

        val tone =
            responseGenerator.estimateTone(input)

        if (isConfirmation(input)) {

            val pendingIntent =
                activePendingIntent

            val pendingId =
                activePendingId

            if (
                pendingIntent == null ||
                pendingId.isNullOrBlank()
            ) {
                clearPendingAction()

                return createErrorResult(
                    language = language
                )
            }

            val result = try {

                toolRouter.executeConfirmed(
                    intent = pendingIntent,
                    pendingActionId = pendingId
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Confirmed tool execution failed",
                    e
                )

                DvexToolResult(
                    success = false,
                    spokenText = "I couldn't complete that action.",
                    displayText = "I couldn't complete that action.",
                    toolName = "confirmation_error"
                )
            }

            clearPendingAction()

            val response =
                responseGenerator.generateResponse(
                    intent = pendingIntent,
                    toolResult = result,
                    language = language,
                    userInput = input,
                    context = conversationContext,
                    tone = tone,
                    memoryHint = memoryHint
                )

            conversationContext.update(
                userInput = input,
                intent = pendingIntent,
                toolName = result.toolName,
                language = language,
                tone = tone,
                spokenResponse = response
            )

            return BrainExecutionResult(
                intent = pendingIntent,
                toolResult = result,
                spokenText = response,
                displayText =
                    result.displayText.ifBlank {
                        response
                    },
                language = language,
                toolName = result.toolName,
                isSensitiveAction = false
            )
        }

        if (isCancellation(input)) {

            val pendingIntent =
                activePendingIntent

            clearPendingAction()

            val response =
                when (language) {

                    DetectedLanguage.TAMIL ->
                        "சரி, அந்த action-ஐ cancel பண்ணிட்டேன்."

                    DetectedLanguage.TANGLISH ->
                        "Seri da, andha action-a cancel panniten."

                    else ->
                        "Okay, I cancelled that."
                }

            val safeIntent =
                pendingIntent
                    ?: DvexIntent.Conversation

            val result =
                DvexToolResult(
                    success = true,
                    spokenText = response,
                    displayText = response,
                    toolName = "confirmation_cancelled"
                )

            conversationContext.update(
                userInput = input,
                intent = safeIntent,
                toolName = result.toolName,
                language = language,
                tone = tone,
                spokenResponse = response
            )

            return BrainExecutionResult(
                intent = safeIntent,
                toolResult = result,
                spokenText = response,
                displayText = response,
                language = language,
                toolName = result.toolName,
                isSensitiveAction = false
            )
        }

        /*
         * User said something unrelated while confirmation
         * is waiting.
         */
        val response =
            when (language) {

                DetectedLanguage.TAMIL ->
                    "முதலில் அந்த action-ஐ confirm பண்ணலாமா அல்லது cancel பண்ணலாமா?"

                DetectedLanguage.TANGLISH ->
                    "First andha action-a confirm pannalama illa cancel pannalama?"

                else ->
                    "First, should I confirm that action or cancel it?"
            }

        val result =
            DvexToolResult(
                success = false,
                spokenText = response,
                displayText = response,
                toolName = "awaiting_confirmation",
                requiresConfirmation = true,
                pendingActionId = activePendingId
            )

        return BrainExecutionResult(
            intent =
                activePendingIntent
                    ?: DvexIntent.Conversation,
            toolResult = result,
            spokenText = response,
            displayText = response,
            language = language,
            toolName = result.toolName,
            isSensitiveAction = true,
            pendingActionId = activePendingId
        )
    }

    private fun clearPendingAction() {

        activePendingIntent = null
        activePendingId = null
    }

    // ============================================================
    // CONFIRMATION DETECTION
    // ============================================================

    private fun isConfirmation(
        input: String
    ): Boolean {

        val text =
            input.lowercase(Locale.getDefault()).trim()

        val phrases = listOf(
            "yes",
            "yeah",
            "yep",
            "yup",
            "sure",
            "okay",
            "ok",
            "confirm",
            "confirmed",
            "do it",
            "go ahead",
            "proceed",
            "yes do it",
            "seri",
            "sari",
            "ama",
            "aama",
            "pannu",
            "pannunga",
            "சரி",
            "ஆமாம்",
            "ஆம்",
            "செய்",
            "செய்யலாம்"
        )

        return phrases.any {
            text == it ||
                    text.startsWith("$it ")
        }
    }

    private fun isCancellation(
        input: String
    ): Boolean {

        val text =
            input.lowercase(Locale.getDefault()).trim()

        val phrases = listOf(
            "no",
            "nope",
            "cancel",
            "stop",
            "don't",
            "dont",
            "never mind",
            "nevermind",
            "cancel it",
            "vendam",
            "vena",
            "venam",
            "vidu",
            "cancel pannidu",
            "வேண்டாம்",
            "ரத்து செய்",
            "நிறுத்து"
        )

        return phrases.any {
            text == it ||
                    text.startsWith("$it ")
        }
    }

    // ============================================================
    // MEMORY
    // ============================================================

    private fun buildMemoryHint(): String {

        val parts =
            mutableListOf<String>()

        getMemory(
            "preferred_name"
        )?.let {

            if (it.isNotBlank()) {
                parts.add(
                    "User prefers to be called: $it"
                )
            }
        }

        getMemory(
            "reply_style"
        )?.let {

            if (it.isNotBlank()) {
                parts.add(
                    "Preferred reply style: $it"
                )
            }
        }

        getMemory(
            "personality"
        )?.let {

            if (it.isNotBlank()) {
                parts.add(
                    "Preferred assistant personality: $it"
                )
            }
        }

        getMemory(
            "language_preference"
        )?.let {

            if (it.isNotBlank()) {
                parts.add(
                    "Preferred language style: $it"
                )
            }
        }

        return parts.joinToString("\n")
    }

    private fun getMemory(
        key: String
    ): String? {

        return try {

            memoryStore.get(key)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Memory read failed: $key",
                e
            )

            null
        }
    }

    fun getMemoryHint(): String {
        return buildMemoryHint()
    }

    fun clearPersistentMemory() {

        try {

            memoryStore.clearAll()

            Log.d(
                TAG,
                "Persistent memory cleared"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to clear persistent memory",
                e
            )
        }
    }

    // ============================================================
    // ASR
    // ============================================================

    fun setAsrConfidence(
        confidence: Float
    ) {

        lastAsrConfidence =
            confidence.coerceIn(
                0.0f,
                1.0f
            )
    }

    fun getAsrConfidence(): Float {
        return lastAsrConfidence
    }

    // ============================================================
    // CONTEXT
    // ============================================================

    fun getContextSummary(): String {
        return conversationContext.getContextSummary()
    }

    fun resetContext() {

        conversationContext.clear()

        clearPendingAction()

        Log.d(
            TAG,
            "Short-term context reset"
        )
    }

    // ============================================================
    // RESULT HELPERS
    // ============================================================

    private fun createStandbyResult():
            BrainExecutionResult {

        val result =
            DvexToolResult(
                success = true,
                spokenText = "",
                displayText = "",
                toolName = "standby"
            )

        return BrainExecutionResult(
            intent = DvexIntent.Conversation,
            toolResult = result,
            spokenText = "",
            displayText = "",
            language = DetectedLanguage.ENGLISH,
            toolName = "standby"
        )
    }

    private fun createErrorResult(
        language: DetectedLanguage
    ): BrainExecutionResult {

        val response =
            when (language) {

                DetectedLanguage.TAMIL ->
                    "மன்னிக்கவும், அந்த action-ஐ complete பண்ண முடியல."

                DetectedLanguage.TANGLISH ->
                    "Sorry da, andha action-a complete panna mudiyala."

                else ->
                    "Sorry, I couldn't complete that action."
            }

        val result =
            DvexToolResult(
                success = false,
                spokenText = response,
                displayText = response,
                toolName = "brain_error"
            )

        return BrainExecutionResult(
            intent = DvexIntent.Conversation,
            toolResult = result,
            spokenText = response,
            displayText = response,
            language = language,
            toolName = result.toolName
        )
    }
}
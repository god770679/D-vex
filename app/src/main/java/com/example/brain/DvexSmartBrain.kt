package com.example.brain

import android.content.Context
import android.util.Log
import com.example.dvex.data.AppLauncherRepository
import com.example.dvex.data.DeviceControlRepository
import com.example.dvex.contacts.ContactResolver

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

    private val intentDetector =
        IntentDetector()

    private val conversationContext =
        ConversationContext()

    private val memoryStore =
        DvexMemoryStore(context)

    private val toolRouter =
        DvexToolRouter(
            context = context,
            appLauncher = appLauncher,
            deviceControl = deviceControl
        )

    private val responseGenerator =
        DvexResponseGenerator()

    private val contactResolver =
        ContactResolver(context)

    private var activePendingIntent: DvexIntent? = null

    private var activePendingId: String? = null

    private var lastAsrConfidence: Float = 1f

    // ------------------------------------------------------------
    // Main processing
    // ------------------------------------------------------------

    fun process(
        rawInput: String
    ): BrainExecutionResult {

        val input =
            rawInput.trim()

        // --------------------------------------------------------
        // Empty input
        // --------------------------------------------------------

        if (input.isBlank()) {

            return BrainExecutionResult(
                intent = DvexIntent.Conversation,
                toolResult = DvexToolResult(
                    status = DvexToolStatus.SUCCESS,
                    spokenText = "I'm here.",
                    toolName = "standby"
                ),
                spokenText = "I'm here.",
                displayText = "I'm here.",
                language = DetectedLanguage.ENGLISH
            )
        }

        Log.d(
            TAG,
            "Processing input: $input"
        )

        // --------------------------------------------------------
        // Memory hint
        //
        // Read BEFORE processing so response generation knows
        // user's existing preferences.
        // --------------------------------------------------------

        val memoryHint =
            buildMemoryHint()

        Log.d(
            TAG,
            "Memory hint: $memoryHint"
        )

        // --------------------------------------------------------
        // Pending confirmation
        // --------------------------------------------------------

        if (activePendingIntent != null) {

            if (isAffirmative(input)) {

                return executePendingAction(
                    confirmed = true,
                    memoryHint = memoryHint
                )
            }

            if (isNegative(input)) {

                return cancelPendingAction(
                    memoryHint = memoryHint
                )
            }
        }

        // --------------------------------------------------------
        // Detect intent + language
        // --------------------------------------------------------

        val detection =
            intentDetector.detect(input)

        val intent =
            detection.intent

        val language =
            detection.language

        val confidence =
            detection.confidence

        Log.d(
            TAG,
            "Detected intent=$intent language=$language confidence=$confidence"
        )

        // --------------------------------------------------------
        // ASR confidence guard
        // --------------------------------------------------------

        if (
            lastAsrConfidence < ASR_CONFIDENCE_THRESHOLD &&
            confidence < INTENT_CONFIDENCE_THRESHOLD
        ) {

            val lowConfidenceIntent =
                DvexIntent.LowConfidence(
                    candidateIntent = intent
                )

            val result =
                DvexToolResult(
                    status = DvexToolStatus.SUCCESS,
                    spokenText = "",
                    toolName = "low_confidence"
                )

            val tone =
                responseGenerator.estimateTone(
                    input
                )

            val response =
                responseGenerator.generateResponse(
                    intent = lowConfidenceIntent,
                    toolResult = result,
                    language = language,
                    userInput = input,
                    context = conversationContext,
                    tone = tone,
                    memoryHint = memoryHint
                )

            conversationContext.update(
                userInput = input,
                intent = lowConfidenceIntent,
                toolName = result.toolName,
                tone = tone,
                language = language,
                spokenResponse = response
            )

            return BrainExecutionResult(
                intent = lowConfidenceIntent,
                toolResult = result,
                spokenText = response,
                displayText = response,
                language = language
            )
        }

        // --------------------------------------------------------
        // Contextual follow-up
        // --------------------------------------------------------

        val contextualValue =
            conversationContext
                .resolveContextualFollowUp(input)

        if (!contextualValue.isNullOrBlank()) {

            Log.d(
                TAG,
                "Context resolved: $contextualValue"
            )
        }

        // --------------------------------------------------------
        // Estimate tone
        // --------------------------------------------------------

        val tone =
            responseGenerator.estimateTone(
                input
            )

        Log.d(
            TAG,
            "Detected tone=$tone"
        )

        // --------------------------------------------------------
        // Execute tool
        // --------------------------------------------------------

        val toolResult =
            toolRouter.execute(
                intent = intent
            )

        Log.d(
            TAG,
            "Tool result=${toolResult.status} tool=${toolResult.toolName}"
        )

        // --------------------------------------------------------
        // Generate natural response
        //
        // IMPORTANT:
        // memoryHint is now passed to the response layer.
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
        // Update short-term context
        // --------------------------------------------------------

        conversationContext.update(
            userInput = input,
            intent = intent,
            toolName = toolResult.toolName,
            tone = tone,
            language = language,
            spokenResponse = response
        )

        // --------------------------------------------------------
        // Sensitive action
        // --------------------------------------------------------

        if (toolResult.requiresConfirmation) {

            activePendingIntent =
                intent

            activePendingId =
                toolResult.pendingActionId

            Log.d(
                TAG,
                "Sensitive action armed: $intent"
            )
        }

        // --------------------------------------------------------
        // Final result
        // --------------------------------------------------------

        return BrainExecutionResult(
            intent = intent,
            toolResult = toolResult,
            spokenText = response,
            displayText = response,
            language = language,
            toolName = toolResult.toolName,
            isSensitiveAction =
                toolResult.requiresConfirmation,
            pendingActionId =
                toolResult.pendingActionId
        )
    }

    // ============================================================
    // Pending confirmation
    // ============================================================

    private fun executePendingAction(
        confirmed: Boolean,
        memoryHint: String
    ): BrainExecutionResult {

        val pendingIntent =
            activePendingIntent
                ?: return cancelledResult(
                    memoryHint
                )

        val pendingId =
            activePendingId

        Log.d(
            TAG,
            "Executing confirmed action: $pendingIntent"
        )

        val toolResult =
            toolRouter.executeConfirmed(
                intent = pendingIntent,
                pendingActionId = pendingId
            )

        activePendingIntent = null
        activePendingId = null

        val language =
            conversationContext.lastLanguage

        val tone =
            EstimatedTone.NEUTRAL

        val response =
            responseGenerator.generateResponse(
                intent = pendingIntent,
                toolResult = toolResult,
                language = language,
                userInput = "yes",
                context = conversationContext,
                tone = tone,
                memoryHint = memoryHint
            )

        conversationContext.update(
            userInput = "yes",
            intent = pendingIntent,
            toolName = toolResult.toolName,
            tone = tone,
            language = language,
            spokenResponse = response
        )

        return BrainExecutionResult(
            intent = pendingIntent,
            toolResult = toolResult,
            spokenText = response,
            displayText = response,
            language = language,
            toolName = toolResult.toolName,
            isSensitiveAction = false,
            pendingActionId = null
        )
    }

    // ============================================================
    // Cancel pending action
    // ============================================================

    private fun cancelPendingAction(
        memoryHint: String
    ): BrainExecutionResult {

        val previousIntent =
            activePendingIntent

        activePendingIntent = null
        activePendingId = null

        val language =
            conversationContext.lastLanguage

        val intent =
            previousIntent
                ?: DvexIntent.Conversation

        val toolResult =
            DvexToolResult(
                status = DvexToolStatus.SUCCESS,
                spokenText = "",
                toolName = "cancel_action"
            )

        val response =
            responseGenerator.generateResponse(
                intent = intent,
                toolResult = toolResult,
                language = language,
                userInput = "no",
                context = conversationContext,
                tone = EstimatedTone.NEUTRAL,
                memoryHint = memoryHint
            )

        val naturalResponse =
            when (language) {

                DetectedLanguage.TAMIL ->
                    "சரி, செய்யவில்லை."

                DetectedLanguage.TANGLISH ->
                    "Seri, pannala."

                DetectedLanguage.ENGLISH ->
                    "Okay, I won't do it."
            }

        conversationContext.update(
            userInput = "no",
            intent = intent,
            toolName = toolResult.toolName,
            tone = EstimatedTone.NEUTRAL,
            language = language,
            spokenResponse = naturalResponse
        )

        return BrainExecutionResult(
            intent = intent,
            toolResult = toolResult,
            spokenText = naturalResponse,
            displayText = naturalResponse,
            language = language,
            toolName = toolResult.toolName,
            isSensitiveAction = false,
            pendingActionId = null
        )
    }

    // ============================================================
    // Cancelled fallback
    // ============================================================

    private fun cancelledResult(
        memoryHint: String
    ): BrainExecutionResult {

        val language =
            conversationContext.lastLanguage

        val text =
            when (language) {

                DetectedLanguage.TAMIL ->
                    "சரி."

                DetectedLanguage.TANGLISH ->
                    "Seri."

                DetectedLanguage.ENGLISH ->
                    "Okay."
            }

        val result =
            DvexToolResult(
                status = DvexToolStatus.SUCCESS,
                spokenText = text,
                toolName = "cancel"
            )

        return BrainExecutionResult(
            intent = DvexIntent.Conversation,
            toolResult = result,
            spokenText = text,
            displayText = text,
            language = language,
            toolName = result.toolName
        )
    }

    // ============================================================
    // ASR confidence
    // ============================================================

    fun setAsrConfidence(
        confidence: Float
    ) {

        lastAsrConfidence =
            confidence.coerceIn(
                0f,
                1f
            )
    }

    fun getAsrConfidence(): Float =
        lastAsrConfidence

    // ============================================================
    // Memory
    // ============================================================

    private fun buildMemoryHint(): String {

        val parts =
            mutableListOf<String>()

        memoryStore
            .recall("preferred_name")
            ?.takeIf { it.isNotBlank() }
            ?.let {

                parts.add(
                    "User prefers to be called $it."
                )
            }

        memoryStore
            .recall("reply_style")
            ?.takeIf { it.isNotBlank() }
            ?.let {

                parts.add(
                    "Preferred reply style: $it."
                )
            }

        memoryStore
            .recall("personality")
            ?.takeIf { it.isNotBlank() }
            ?.let {

                parts.add(
                    "Preferred assistant personality: $it."
                )
            }

        memoryStore
            .recall("language_preference")
            ?.takeIf { it.isNotBlank() }
            ?.let {

                parts.add(
                    "Preferred language style: $it."
                )
            }

        return if (parts.isEmpty()) {
            ""
        } else {
            parts.joinToString(" ")
        }
    }

    fun getMemoryHint(): String {

        return buildMemoryHint()
    }

    fun clearPersistentMemory() {

        memoryStore.clearAll()

        Log.i(
            TAG,
            "Persistent memory cleared"
        )
    }

    // ============================================================
    // Context
    // ============================================================

    fun resetContext() {

        conversationContext.clear()

        activePendingIntent = null
        activePendingId = null

        lastAsrConfidence = 1f

        Log.i(
            TAG,
            "Short-term conversation context reset"
        )
    }

    fun getContextSummary(): String {

        return conversationContext
            .getContextSummary()
    }

    // ============================================================
    // Confirmation detection
    // ============================================================

    private fun isAffirmative(
        input: String
    ): Boolean {

        val text =
            input
                .lowercase()
                .trim()

        return text == "yes" ||
            text == "yeah" ||
            text == "yep" ||
            text == "yup" ||
            text == "ok" ||
            text == "okay" ||
            text == "sure" ||
            text == "do it" ||
            text == "go ahead" ||
            text == "confirm" ||
            text == "haan" ||
            text == "ha" ||
            text == "aama" ||
            text == "ama" ||
            text == "seri" ||
            text == "sari" ||
            text == "pannu" ||
            text == "pannunga" ||
            text == "send pannunga" ||
            text == "call pannu" ||
            text == "call pannunga" ||
            text == "ஆம்" ||
            text == "ஆமாம்" ||
            text == "சரி"
    }

    private fun isNegative(
        input: String
    ): Boolean {

        val text =
            input
                .lowercase()
                .trim()

        return text == "no" ||
            text == "nope" ||
            text == "nah" ||
            text == "cancel" ||
            text == "stop" ||
            text == "don't" ||
            text == "dont" ||
            text == "not now" ||
            text == "vendam" ||
            text == "vena" ||
            text == "venam" ||
            text == "pannadha" ||
            text == "pannadhinga" ||
            text == "வேண்டாம்" ||
            text == "ரத்து"
    }

    companion object {

        private const val TAG =
            "[D-VEX][BRAIN]"

        private const val ASR_CONFIDENCE_THRESHOLD =
            0.40f

        private const val INTENT_CONFIDENCE_THRESHOLD =
            0.75f
    }
}
package com.example.brain

import android.content.Context
import android.util.Log
import com.example.repository.AppLauncherRepository
import com.example.repository.ContactResolver
import com.example.repository.DeviceControlRepository
import java.util.Locale

class DvexSmartBrain(
    context: Context,
    vararg legacyDependencies: Any?
) {

    companion object {
        private const val TAG = "DvexSmartBrain"

        private const val DEFAULT_ASR_CONFIDENCE = 1.0f
        private const val LOW_CONFIDENCE_THRESHOLD = 0.45f
    }

    private val appContext = context.applicationContext

    // =========================================================
    // CORE
    // =========================================================

    private val intentDetector = IntentDetector()

    private val conversationContext =
        ConversationContext(
            maxHistorySize = 10
        )

    private val memoryStore =
        DvexMemoryStore(appContext)

    private val responseGenerator =
        DvexResponseGenerator()

    private val appLauncherRepository =
        AppLauncherRepository(appContext)

    private val deviceControlRepository =
        DeviceControlRepository(appContext)

    private val contactResolver =
        ContactResolver(appContext)

    private val toolRouter =
        DvexToolRouter(
            context = appContext,
            appLauncherRepository = appLauncherRepository,
            deviceControlRepository = deviceControlRepository,
            contactResolver = contactResolver
        )

    // =========================================================
    // STATE
    // =========================================================

    private var lastAsrConfidence =
        DEFAULT_ASR_CONFIDENCE

    private var pendingConfirmationIntent:
            DvexIntent? = null

    private var pendingConfirmationActionId:
            String? = null

    // =========================================================
    // MAIN PROCESS
    // =========================================================

    fun process(
        rawInput: String
    ): BrainExecutionResult {

        val userInput = rawInput.trim()

        if (userInput.isBlank()) {
            return createStandbyResult()
        }

        Log.d(
            TAG,
            "Processing: $userInput"
        )

        // -----------------------------------------------------
        // CONFIRMATION
        // -----------------------------------------------------

        if (hasPendingConfirmation()) {

            val confirmation =
                handleConfirmationInput(userInput)

            if (confirmation != null) {
                return confirmation
            }
        }

        // -----------------------------------------------------
        // DETECTION
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
            "Intent=$detectedIntent"
        )

        Log.d(
            TAG,
            "Language=$detectedLanguage"
        )

        Log.d(
            TAG,
            "Intent confidence=$intentConfidence"
        )

        Log.d(
            TAG,
            "ASR confidence=$lastAsrConfidence"
        )

        // -----------------------------------------------------
        // LOW CONFIDENCE
        // -----------------------------------------------------

        if (
            lastAsrConfidence <
            LOW_CONFIDENCE_THRESHOLD ||
            intentConfidence <
            LOW_CONFIDENCE_THRESHOLD
        ) {

            val result =
                DvexToolResult(
                    success = false,
                    spokenText = "",
                    displayText = "",
                    toolName = "low_confidence"
                )

            val tone =
                DvexResponseGenerator
                    .estimateTone(userInput)

            val generated =
                responseGenerator.generateResponse(
                    intent =
                        DvexIntent.LowConfidence,
                    toolResult = result,
                    language =
                        detectedLanguage,
                    userInput = userInput,
                    context =
                        conversationContext,
                    tone = tone,
                    memoryHint =
                        buildMemoryHint()
                )

            val finalText =
                generated.ifBlank {

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
                intent =
                    DvexIntent.LowConfidence,
                toolName =
                    "low_confidence",
                language =
                    detectedLanguage,
                tone = tone,
                spokenResponse =
                    finalText
            )

            return BrainExecutionResult(
                intent =
                    DvexIntent.LowConfidence,
                toolResult = result,
                spokenText =
                    finalText,
                displayText =
                    finalText,
                language =
                    detectedLanguage,
                toolName =
                    "low_confidence",
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
                .resolveContextualFollowUp(
                    userInput
                )

        if (contextualValue != null) {

            Log.d(
                TAG,
                "Context=$contextualValue"
            )
        }

        // -----------------------------------------------------
        // TOOL
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
        // RESPONSE
        // -----------------------------------------------------

        val generated =
            responseGenerator.generateResponse(
                intent =
                    detectedIntent,
                toolResult =
                    toolResult,
                language =
                    detectedLanguage,
                userInput =
                    userInput,
                context =
                    conversationContext,
                tone =
                    tone,
                memoryHint =
                    buildMemoryHint()
            )

        val finalSpokenText =
            generated.ifBlank {

                toolResult.spokenText.ifBlank {
                    toolResult.displayText
                }
            }

        val finalDisplayText =
            toolResult.displayText.ifBlank {
                finalSpokenText
            }

        // -----------------------------------------------------
        // CONTEXT UPDATE
        // -----------------------------------------------------

        conversationContext.update(
            userInput = userInput,
            intent = detectedIntent,
            toolName =
                toolResult.toolName,
            language =
                detectedLanguage,
            tone = tone,
            spokenResponse =
                finalSpokenText
        )

        // -----------------------------------------------------
        // CONFIRMATION REQUIRED
        // -----------------------------------------------------

        if (toolResult.requiresConfirmation) {

            pendingConfirmationIntent =
                detectedIntent

            pendingConfirmationActionId =
                toolResult.pendingActionId

            Log.d(
                TAG,
                "Confirmation required"
            )
        }

        return BrainExecutionResult(
            intent =
                detectedIntent,
            toolResult =
                toolResult,
            spokenText =
                finalSpokenText,
            displayText =
                finalDisplayText,
            language =
                detectedLanguage,
            toolName =
                toolResult.toolName,
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
                .lowercase(
                    Locale.getDefault()
                )

        // -----------------------------------------------------
        // YES
        // -----------------------------------------------------

        val yes =
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

        if (yes) {

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
                        pendingActionId =
                            actionId
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
                        toolName =
                            "confirmation_error"
                    )
                }

            clearPendingConfirmation()

            val language =
                conversationContext.lastLanguage
                    ?: DetectedLanguage.ENGLISH

            val response =
                responseGenerator.generateResponse(
                    intent = intent,
                    toolResult = result,
                    language = language,
                    userInput = input,
                    context =
                        conversationContext,
                    tone =
                        DvexResponseGenerator
                            .estimateTone(input),
                    memoryHint =
                        buildMemoryHint()
                )

            val finalText =
                response.ifBlank {

                    result.spokenText.ifBlank {
                        result.displayText
                    }
                }

            conversationContext.update(
                userInput = input,
                intent = intent,
                toolName =
                    result.toolName,
                language =
                    language,
                tone =
                    DvexResponseGenerator
                        .estimateTone(input),
                spokenResponse =
                    finalText
            )

            return BrainExecutionResult(
                intent = intent,
                toolResult = result,
                spokenText = finalText,
                displayText =
                    result.displayText.ifBlank {
                        finalText
                    },
                language = language,
                toolName =
                    result.toolName,
                isSensitiveAction = false,
                pendingActionId = null
            )
        }

        // -----------------------------------------------------
        // NO
        // -----------------------------------------------------

        val no =
            normalized == "no" ||
            normalized == "n" ||
            normalized == "cancel" ||
            normalized == "stop" ||
            normalized == "dont" ||
            normalized == "don't" ||
            normalized == "nope" ||
            normalized == "vendam" ||
            normalized == "venam" ||
            normalized == "வேண்டாம்" ||
            normalized == "நிறுத்து"

        if (no) {

            clearPendingConfirmation()

            val language =
                conversationContext.lastLanguage
                    ?: DetectedLanguage.ENGLISH

            val result =
                DvexToolResult(
                    success = false,
                    spokenText = "",
                    displayText = "",
                    toolName =
                        "confirmation_cancelled"
                )

            val generated =
                responseGenerator.generateResponse(
                    intent =
                        DvexIntent.Conversation,
                    toolResult = result,
                    language = language,
                    userInput = input,
                    context =
                        conversationContext,
                    tone =
                        DvexResponseGenerator
                            .estimateTone(input),
                    memoryHint =
                        buildMemoryHint()
                )

            val finalText =
                generated.ifBlank {

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
                intent =
                    DvexIntent.Conversation,
                toolName =
                    "confirmation_cancelled",
                language =
                    language,
                tone =
                    DvexResponseGenerator
                        .estimateTone(input),
                spokenResponse =
                    finalText
            )

            return BrainExecutionResult(
                intent =
                    DvexIntent.Conversation,
                toolResult =
                    result,
                spokenText =
                    finalText,
                displayText =
                    finalText,
                language =
                    language,
                toolName =
                    "confirmation_cancelled",
                isSensitiveAction = false,
                pendingActionId = null
            )
        }

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
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parts.add(
                    "preferred_name=$it"
                )
            }

        memoryStore
            .get("reply_style")
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parts.add(
                    "reply_style=$it"
                )
            }

        memoryStore
            .get("personality")
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parts.add(
                    "personality=$it"
                )
            }

        memoryStore
            .get("language_preference")
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parts.add(
                    "language_preference=$it"
                )
            }

        memoryStore
            .get("user_preference")
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parts.add(
                    "user_preference=$it"
                )
            }

        memoryStore
            .get("user_likes")
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                parts.add(
                    "user_likes=$it"
                )
            }

        memoryStore
            .get("user_dislikes")
            ?.takeIf {
                it.isNotBlank()
            }
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
    // ASR
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
            "ASR=$lastAsrConfidence"
        )
    }

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
    // CONFIRMATION STATE
    // =========================================================

    fun hasPendingConfirmation(): Boolean {

        return pendingConfirmationIntent != null &&
                !pendingConfirmationActionId
                    .isNullOrBlank()
    }

    fun getPendingConfirmationActionId():
            String? {
        return pendingConfirmationActionId
    }

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
            "Context reset"
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
            intent =
                DvexIntent.Conversation,
            toolResult =
                result,
            spokenText =
                text,
            displayText =
                text,
            language =
                language,
            toolName =
                "standby",
            isSensitiveAction = false,
            pendingActionId = null
        )
    }
}

// =============================================================
// BRAIN RESULT
// =============================================================

data class BrainExecutionResult(

    val intent: DvexIntent,

    val toolResult: DvexToolResult,

    val spokenText: String,

    val displayText: String =
        spokenText,

    val language: DetectedLanguage =
        DetectedLanguage.ENGLISH,

    val toolName: String =
        toolResult.toolName,

    val isSensitiveAction: Boolean =
        toolResult.requiresConfirmation,

    val pendingActionId: String? =
        toolResult.pendingActionId
)
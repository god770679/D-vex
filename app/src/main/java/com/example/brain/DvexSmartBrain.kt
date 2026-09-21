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
 * Central cognitive unit converting natural user speech into intent understanding,
 * tool selection, execution, verification, and natural speech synthesis response.
 */
class DvexSmartBrain(
  private val context: Context,
  private val appLauncher: AppLauncherRepository,
  private val deviceControl: DeviceControlRepository
) {

  private val intentDetector = IntentDetector()
  private val conversationContext = ConversationContext()
  private val toolRouter = DvexToolRouter(context, appLauncher, deviceControl)
  private val responseGenerator = DvexResponseGenerator()
  private val contactResolver = ContactResolver(context)

  // Track pending sensitive confirmation within the brain
  private var activePendingIntent: DvexIntent? = null
  private var activePendingId: String? = null

  /**
   * Processes user input end-to-end through the brain pipeline.
   */
  suspend fun process(rawInput: String): BrainExecutionResult {
    Log.i(TAG_BRAIN, "Input: $rawInput")

    if (rawInput.isBlank()) {
      Log.i(TAG_BRAIN, "Empty input provided to brain; returning silent standby result")
      return BrainExecutionResult(
        intent = DvexIntent.Conversation(""),
        toolResult = DvexToolResult(DvexToolStatus.SUCCESS, "standby", "", ""),
        spokenText = "",
        displayText = "",
        language = conversationContext.lastLanguage,
        toolName = "standby"
      )
    }

    val lower = rawInput.lowercase(Locale.ROOT).trim()

    // 1. Check for confirmation responses if a sensitive action is currently pending
    if (activePendingIntent != null) {
      if (isAffirmative(lower)) {
        Log.i(TAG_BRAIN, "User confirmed pending action: $activePendingIntent")
        val confirmedIntent = activePendingIntent!!
        activePendingIntent = null
        activePendingId = null

        val confirmationLang = intentDetector.detectIntent(rawInput, conversationContext).language
        val effectiveLang = if (confirmationLang != DetectedLanguage.ENGLISH) {
          confirmationLang
        } else {
          conversationContext.lastLanguage
        }

        // Execute the confirmed sensitive action
        val executionResult = executeConfirmedAction(confirmedIntent)
        val tone = responseGenerator.estimateTone(rawInput)
        val spoken = responseGenerator.generateResponse(
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
      } else if (isNegative(lower)) {
        Log.i(TAG_BRAIN, "User cancelled pending action.")
        activePendingIntent = null
        activePendingId = null

        val cancelLang = intentDetector.detectIntent(rawInput, conversationContext).language
        val effectiveLang = if (cancelLang != DetectedLanguage.ENGLISH) {
          cancelLang
        } else {
          conversationContext.lastLanguage
        }

        val cancelMessage = when (effectiveLang) {
          DetectedLanguage.TAMIL -> "செயல் ரத்து செய்யப்பட்டது, Sir."
          DetectedLanguage.TANGLISH -> "Action cancel panniyachu, Sir."
          else -> "Action cancelled, Sir."
        }

        val cancelResult = DvexToolResult(
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
      // If user said something completely different, clear the pending confirmation and continue
      activePendingIntent = null
      activePendingId = null
    }

    // 2. Intent Understanding
    val detection = intentDetector.detectIntent(rawInput, conversationContext)
    val intent = detection.intent
    val confidence = detection.confidence
    val language = detection.language

    Log.i(TAG_INTENT, "$intent (confidence: $confidence, lang: $language)")

    // 2.5 Estimate Tone
    val tone = responseGenerator.estimateTone(rawInput)

    // 3. Tool Selection & Execution
    val toolResult = toolRouter.execute(intent, conversationContext)
    Log.i(TAG_RESULT, "Tool: ${toolResult.toolName} -> Status: ${toolResult.status}")

    // 4. Natural Response Generation (Internal Consideration: meaning, goal, context, tone, language)
    val spokenResponse = responseGenerator.generateResponse(
      intent = intent,
      toolResult = toolResult,
      language = language,
      userInput = rawInput,
      context = conversationContext,
      tone = tone
    )
    Log.i(TAG_RESPONSE, spokenResponse)

    // 5. Update Conversation Context
    conversationContext.update(
      input = rawInput,
      intent = intent,
      toolResult = toolResult,
      language = language,
      tone = tone,
      spokenResponse = spokenResponse
    )

    // 6. Check if confirmation is required (Sensitive actions: Call, SMS, Email)
    if (toolResult.requiresConfirmation) {
      activePendingIntent = intent
      activePendingId = toolResult.pendingActionId
      Log.i(TAG_BRAIN, "Armed pending confirmation for sensitive action: ${intent::class.simpleName}")
    }

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
   * Executes a sensitive action once explicit user confirmation has been granted.
   */
  private suspend fun executeConfirmedAction(intent: DvexIntent): DvexToolResult {
    return toolRouter.executeConfirmed(intent)
  }

  fun cancelPendingConfirmation() {
    activePendingIntent = null
    activePendingId = null
  }

  fun hasPendingConfirmation(): Boolean = activePendingIntent != null

  private fun isAffirmative(lower: String): Boolean {
    val clean = lower.trimEnd('.', '?', '!', ',').trim()
    return clean == "yes" || clean == "confirm" || clean == "call" ||
        clean == "send" || clean == "sure" || clean == "do it" ||
        clean == "yeah" || clean == "yep" || clean == "aama" || clean == "seri" ||
        clean == "sari" || clean == "pannu" || clean == "pannunga" || clean == "anupu" ||
        clean == "anuppu" || clean == "ok" || clean == "okay" || clean == "yes sir" ||
        clean == "sure sir" || clean == "okay sir" || clean == "சரி" || clean == "பண்ணு" ||
        clean == "அனுப்பு" || clean == "ஆம்" || clean == "ஆமாம்" ||
        clean.contains("yes") || clean.contains("confirm") || clean.contains("ஆமாம்") ||
        clean.contains("சரி") || clean.contains("sure")
  }

  private fun isNegative(lower: String): Boolean {
    val clean = lower.trimEnd('.', '?', '!', ',')
    return clean == "no" || clean == "cancel" || clean == "stop" ||
        clean == "don't" || clean == "nevermind" || clean == "vendaam" || clean == "vendam" ||
        clean == "illai" || clean == "வேண்டாம்" || clean == "இல்லை" || clean == "நிறுத்து" ||
        clean.contains("cancel") || clean.contains("stop")
  }

  fun resetContext() {
    conversationContext.clear()
    cancelPendingConfirmation()
  }

  companion object {
    private const val TAG_BRAIN = "[D-VEX][BRAIN]"
    private const val TAG_INTENT = "[D-VEX][INTENT]"
    private const val TAG_TOOL = "[D-VEX][TOOL]"
    private const val TAG_RESULT = "[D-VEX][RESULT]"
    private const val TAG_RESPONSE = "[D-VEX][RESPONSE]"
  }
}

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

    val lower = rawInput.lowercase(Locale.ROOT).trim()

    // 1. Check for confirmation responses if a sensitive action is currently pending
    if (activePendingIntent != null) {
      if (isAffirmative(lower)) {
        Log.i(TAG_BRAIN, "User confirmed pending action: $activePendingIntent")
        val confirmedIntent = activePendingIntent!!
        activePendingIntent = null
        activePendingId = null

        // Execute the confirmed sensitive action
        val executionResult = executeConfirmedAction(confirmedIntent)
        val spoken = responseGenerator.generateResponse(confirmedIntent, executionResult, DetectedLanguage.ENGLISH)
        Log.i(TAG_RESPONSE, spoken)
        return BrainExecutionResult(
          intent = confirmedIntent,
          toolResult = executionResult,
          spokenText = spoken,
          toolName = executionResult.toolName
        )
      } else if (isNegative(lower)) {
        Log.i(TAG_BRAIN, "User cancelled pending action.")
        activePendingIntent = null
        activePendingId = null

        val cancelResult = DvexToolResult(
          status = DvexToolStatus.SUCCESS,
          toolName = "cancellation",
          message = "Action cancelled, Sir.",
          spokenText = "Action cancelled, Sir."
        )
        Log.i(TAG_RESPONSE, "Action cancelled, Sir.")
        return BrainExecutionResult(
          intent = DvexIntent.Conversation(rawInput),
          toolResult = cancelResult,
          spokenText = "Action cancelled, Sir.",
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

    // 3. Tool Selection & Execution
    val toolResult = toolRouter.execute(intent, conversationContext)
    Log.i(TAG_RESULT, "Tool: ${toolResult.toolName} -> Status: ${toolResult.status}")

    // 4. Update Conversation Context
    conversationContext.update(rawInput, intent, toolResult, language)

    // 5. Check if confirmation is required (Sensitive actions: Call, SMS, Email)
    if (toolResult.requiresConfirmation) {
      activePendingIntent = intent
      activePendingId = toolResult.pendingActionId
      Log.i(TAG_BRAIN, "Armed pending confirmation for sensitive action: ${intent::class.simpleName}")
    }

    // 6. Natural Response Generation
    val spokenResponse = responseGenerator.generateResponse(intent, toolResult, language)
    Log.i(TAG_RESPONSE, spokenResponse)

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
  private fun executeConfirmedAction(intent: DvexIntent): DvexToolResult {
    return when (intent) {
      is DvexIntent.CallContact -> {
        Log.i(TAG_TOOL, "Executing confirmed phone call for: ${intent.recipient}")
        val contact = contactResolver.resolveContact(intent.recipient)
        val targetNumber = contact?.phoneNumber ?: intent.recipient
        val displayName = contact?.name ?: intent.recipient
        val res = deviceControl.makePhoneCall(targetNumber, displayName)
        DvexToolResult(
          status = if (res.isSuccessful) DvexToolStatus.SUCCESS else DvexToolStatus.FAILED,
          toolName = "phone_call",
          message = "Calling $displayName, Sir.",
          spokenText = "Calling $displayName, Sir."
        )
      }
      is DvexIntent.SendMessage -> {
        Log.i(TAG_TOOL, "Executing confirmed SMS for: ${intent.recipient}")
        val contact = contactResolver.resolveContact(intent.recipient)
        val targetNumber = contact?.phoneNumber ?: intent.recipient
        val displayName = contact?.name ?: intent.recipient
        val res = if (!intent.messageText.isNullOrBlank() && contact?.phoneNumber != null) {
          deviceControl.sendSms(targetNumber, intent.messageText, displayName)
        } else {
          deviceControl.openMessages(targetNumber, intent.messageText)
        }
        DvexToolResult(
          status = if (res.isSuccessful) DvexToolStatus.SUCCESS else DvexToolStatus.FAILED,
          toolName = "send_message",
          message = "Sending message to $displayName, Sir.",
          spokenText = "Sending message to $displayName, Sir."
        )
      }
      is DvexIntent.SendEmail -> {
        Log.i(TAG_TOOL, "Executing confirmed email for: ${intent.recipient}")
        val res = deviceControl.sendEmail(intent.recipient, intent.subject, intent.body)
        DvexToolResult(
          status = if (res.isSuccessful) DvexToolStatus.SUCCESS else DvexToolStatus.FAILED,
          toolName = "send_email",
          message = "Opening email to ${intent.recipient}, Sir.",
          spokenText = "Opening email to ${intent.recipient}, Sir."
        )
      }
      else -> {
        DvexToolResult(
          status = DvexToolStatus.FAILED,
          toolName = "unknown",
          message = "Unknown confirmation action, Sir.",
          spokenText = "I couldn't complete that action, Sir."
        )
      }
    }
  }

  fun cancelPendingConfirmation() {
    activePendingIntent = null
    activePendingId = null
  }

  private fun isAffirmative(lower: String): Boolean {
    val clean = lower.trimEnd('.', '?', '!', ',')
    return clean == "yes" || clean == "confirm" || clean == "call" ||
        clean == "send" || clean == "sure" || clean == "do it" ||
        clean == "yeah" || clean == "yep" || clean == "aama" || clean == "seri" ||
        clean == "sari" || clean == "pannu" || clean == "pannunga" || clean == "anupu" ||
        clean == "anuppu" || clean == "ok" || clean == "okay" || clean == "yes sir" ||
        clean == "sure sir" || clean == "okay sir" || clean == "சரி" || clean == "பண்ணு" ||
        clean == "அனுப்பு" || clean == "ஆம்" || clean.contains("yes") || clean.contains("confirm")
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

package com.example.brain

import android.content.Context
import android.util.Log
import com.example.agent.AgentState
import com.example.agent.DvexAgentEngine
import com.example.agent.DvexAgentStateController
import com.example.ai.AiEngine
import com.example.ai.GeminiEngine
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
  val pendingActionId: String? = toolResult.pendingActionId,
  /**
   * Tone estimated from the user's own words. Carried out of the brain so the
   * voice layer can adapt rate/pitch to the same tone the reply was written for,
   * instead of both layers guessing separately.
   */
  val tone: EstimatedTone = EstimatedTone.NEUTRAL
)

/**
 * D-VEX Smart Brain V1.
 * Central cognitive unit converting natural user speech into intent understanding,
 * tool selection, execution, verification, and natural speech synthesis response.
 */
class DvexSmartBrain(
  private val context: Context,
  private val appLauncher: AppLauncherRepository,
  private val deviceControl: DeviceControlRepository,
  /**
   * LLM used to phrase natural responses. Defaults to the Gemini engine reading
   * BuildConfig.GEMINI_API_KEY (populated from the project .env); pass a fake in
   * tests or null to force deterministic fallback replies.
   */
  private val aiEngine: AiEngine? = GeminiEngine(
    apiKeyProvider = {
      try {
        com.example.BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
      } catch (e: Exception) {
        null
      }
    }
  )
) {

  private val intentDetector = IntentDetector()
  private val conversationContext = ConversationContext()
  private val toolRouter = DvexToolRouter(context, appLauncher, deviceControl)
  /**
   * Agent layer: action-oriented planning + capability-checked execution. It owns
   * device/app/multi-step intents and delegates each action to the EXISTING
   * repositories; anything it does not plan falls through to [toolRouter] unchanged,
   * so conversation, information, memory and confirmation flows are untouched.
   */
  private val agentEngine = DvexAgentEngine(context, appLauncher, deviceControl, toolRouter)
  private val responseGenerator = DvexResponseGenerator(aiEngine)
  private val contactResolver = ContactResolver(context)

  /**
   * Memory hint for the response LLM, rendered from the EXISTING memory store
   * ("core_memories" in dvex_brain_prefs, written by DvexToolRouter's
   * remember-fact tool). No new memory system — just surfacing it to the prompt.
   */
  private val memoryHintLoader: () -> String? = {
    try {
      val memories = context.getSharedPreferences("dvex_brain_prefs", Context.MODE_PRIVATE)
        .getStringSet("core_memories", emptySet())
        .orEmpty()
        .filter { it.isNotBlank() }
      if (memories.isEmpty()) null else memories.take(5).joinToString("; ")
    } catch (e: Exception) {
      Log.w(TAG_RESPONSE, "Memory hint unavailable: ${e.message}")
      null
    }
  }

  // Track pending sensitive confirmation within the brain
  private var activePendingIntent: DvexIntent? = null
  private var activePendingId: String? = null

  // Latest speech-recognition confidence (null when unavailable or typed input)
  @Volatile private var lastAsrConfidence: Float? = null

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
          tone = tone,
          memoryHint = memoryHintLoader()
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
          toolName = executionResult.toolName,
          tone = tone
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
          DetectedLanguage.TAMIL -> "செயல் ரத்து செய்யப்பட்டது."
          DetectedLanguage.TANGLISH -> "Action cancel panniyachu."
          else -> "Cancelled."
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

    Log.i(TAG_INTENT, "[D-VEX][AGENT] input=\"$rawInput\" -> intent=$intent (confidence: $confidence, lang: $language)")
    Log.i(
      TAG_INTENT,
      "[D-VEX][AGENT] route=${if (intent is DvexIntent.Conversation || intent is DvexIntent.GeneralQuestion) "CONVERSATION (LLM-direct)" else "ACTION (tool -> verify -> LLM phrasing)"}"
    )

    // 2.1 Uncertain speech, two different situations:
    // - GARBLED transcription: IntentDetector already returned LowConfidence, which
    //   is handled deterministically downstream (never guess an action, never invent
    //   words). Unchanged.
    // - LOW ASR CONFIDENCE on a CONVERSATIONAL turn: the words are still real natural
    //   language (an unfamiliar question scores low confidence simply because it is
    //   not a known action). This used to be rewritten into a canned English
    //   clarification, so valid questions never reached the LLM. Now the turn goes to
    //   the LLM as-is with an explicit uncertainty hint, and the model decides whether
    //   to answer or to ask the user (naturally, in their own language) to repeat.
    //   Action intents are unaffected: they are still executed and verified by tools.
    val asrConfidence = lastAsrConfidence
    val unclearConversation = asrConfidence != null &&
      asrConfidence < ASR_LOW_CONFIDENCE_THRESHOLD &&
      confidence < INTENT_LOW_CONFIDENCE_THRESHOLD &&
      intent is DvexIntent.Conversation
    if (unclearConversation) {
      Log.i(TAG_BRAIN, "Low ASR confidence ($asrConfidence) on a conversational turn " +
        "(intent confidence $confidence); passing to the LLM with an unclear-input hint " +
        "instead of a canned clarification")
    }

    // 2.5 Estimate Tone
    val tone = responseGenerator.estimateTone(rawInput)

    // 3. Tool Selection & Execution
    // The agent layer goes first for device/app/multi-step requests; it returns null
    // for everything else, in which case the existing tool router handles the intent
    // exactly as before.
    val agentResult = agentEngine.executeIfApplicable(intent, rawInput)
    val toolResult = agentResult ?: toolRouter.execute(intent, conversationContext)
    Log.i(TAG_RESULT, "Tool: ${toolResult.toolName} -> Status: ${toolResult.status}")

    // 4. Natural Response Generation (Internal Consideration: meaning, goal, context, tone, language)
    val spokenResponse = responseGenerator.generateResponse(
      intent = intent,
      toolResult = toolResult,
      language = language,
      userInput = rawInput,
      context = conversationContext,
      tone = tone,
      memoryHint = memoryHintLoader(),
      unclearInput = unclearConversation
    )
    // END-TO-END TRACE: proves what value is actually spoken for this input.
    // If this line shows a fallback string, [D-VEX][AI] logs above it show why
    // (configured=false, HTTP error, timeout, empty...).
    Log.i(TAG_RESPONSE, "[D-VEX][AI] final spokenText=\"$spokenResponse\" (intent=$intent, tool=${toolResult.toolName}, status=${toolResult.status})")

    // The response has now been produced, so the agent pipeline is genuinely done.
    if (agentResult != null) {
      DvexAgentStateController.transition(AgentState.IDLE)
    }

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
      pendingActionId = toolResult.pendingActionId,
      tone = tone
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

  /** Reports the latest speech-recognition confidence for uncertain-speech handling. */
  fun reportAsrConfidence(confidence: Float?) {
    lastAsrConfidence = confidence
  }

  fun resetContext() {
    conversationContext.clear()
    cancelPendingConfirmation()
    lastAsrConfidence = null
  }

  companion object {
    private const val TAG_BRAIN = "[D-VEX][BRAIN]"
    private const val TAG_INTENT = "[D-VEX][INTENT]"
    private const val TAG_TOOL = "[D-VEX][TOOL]"
    private const val TAG_RESULT = "[D-VEX][RESULT]"
    private const val TAG_RESPONSE = "[D-VEX][RESPONSE]"

    /** ASR scores below this are considered uncertain transcriptions. */
    private const val ASR_LOW_CONFIDENCE_THRESHOLD = 0.4f
    /** Weakly-parsed intents (e.g. bare Conversation fallback) below this are not trusted. */
    private const val INTENT_LOW_CONFIDENCE_THRESHOLD = 0.75f
  }
}

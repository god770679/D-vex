package com.example.brain

import android.content.Context
import android.util.Log
import com.example.agent.AgentState
import com.example.agent.DvexAgentEngine
import com.example.agent.DvexAgentStateController
import com.example.agent.DvexMcpRegistry
import com.example.agent.DvexToolAdmission
import com.example.agent.DvexToolCall
import com.example.agent.DvexCapabilityManager
import com.example.agent.DvexCapabilityProbe
import com.example.agent.DvexToolProtocol
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
  ),
  /**
   * Capability authority for the agent engine. Defaults to the real device probe;
   * tests supply a stub so HIGH-risk confirmation behaviour can be exercised on a
   * device-less JVM. This does NOT change what anything is allowed to do: it is the
   * same [DvexCapabilityProbe] contract the engine has always consumed.
   */
  capabilityProbe: DvexCapabilityProbe = DvexCapabilityManager(context)
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
  private val agentEngine =
    DvexAgentEngine(context, appLauncher, deviceControl, toolRouter, capabilityManager = capabilityProbe)
  private val responseGenerator = DvexResponseGenerator(aiEngine)
  private val contactResolver = ContactResolver(context)

  /**
   * The canonical tools the model may SEE and SELECT from — derived from the actions
   * D-VEX can actually execute, so nothing is ever advertised that has no handler.
   * The registry is the only source of truth for admission: a tool name that is not
   * in here can never reach a handler, and capability/risk always come from the
   * descriptor, never from anything the model supplies.
   */
  private val mcpRegistry = DvexMcpRegistry.withDefaults()

  /**
   * Protocol-path pending confirmation: the admitted call and its one-step plan
   * that the model selected and D-VEX's policy held at the confirmation gate.
   * Never persisted (same lifetime as [activePendingIntent]); [activePendingId]
   * stays the single pending id for both paths.
   */
  private var activePendingToolCall: DvexToolCall? = null
  private var activePendingToolPlan: com.example.agent.DvexTaskPlan? = null

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
    // (deterministic intents AND model-selected HIGH-risk actions share this one gate).
    if (activePendingIntent != null || activePendingToolCall != null) {
      if (isAffirmative(lower)) {
        Log.i(TAG_BRAIN, "User confirmed pending action: $activePendingIntent / tool=${activePendingToolCall?.toolName}")
        // Snapshot then clear EVERYTHING before executing: a "yes" may confirm a
        // deterministic intent, a model-selected call, or both pending from earlier
        // turns. Clearing first guarantees exactly one execution per confirmation.
        val pendingIntent = activePendingIntent
        val pendingCall = activePendingToolCall
        activePendingIntent = null
        activePendingId = null
        activePendingToolCall = null
        activePendingToolPlan = null
        // A model-selected call is re-admitted here too, so the confirmed execution
        // reports the REAL action (and its real result) rather than the utterance.
        val confirmedIntent = pendingIntent
          ?: pendingCall?.let { call ->
            val reAdmitted = DvexToolProtocol.admit(call, mcpRegistry.tool(call.toolName))
            (reAdmitted as? DvexToolAdmission.Accepted)?.let { toolIntentFor(it) }
          }
          ?: DvexIntent.Conversation("")

        val confirmationLang = intentDetector.detectIntent(rawInput, conversationContext).language
        val effectiveLang = if (confirmationLang != DetectedLanguage.ENGLISH) {
          confirmationLang
        } else {
          conversationContext.lastLanguage
        }

        // Execute the confirmed sensitive action
        val executionResult = executeConfirmedAction(confirmedIntent, pendingCall)
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
        activePendingToolCall = null
        activePendingToolPlan = null

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
      // If user said something completely different, clear the pending confirmation
      // (both paths) and continue with the new request.
      activePendingIntent = null
      activePendingId = null
      activePendingToolCall = null
      activePendingToolPlan = null
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
    // INCREMENT 2 — model tool selection, and ONLY for the one situation the
    // deterministic layers cannot serve: an ACTION-shaped utterance that matched no
    // known pattern — the detector's pure fall-through Conversation at 0.70
    // confidence (recognized small talk scores 0.88, questions are GeneralQuestion
    // at >= 0.75, and every supported action has a deterministic plan/handler).
    // Those flows keep their existing execution and their byte-identical text-only
    // generate() turn; the model only ever ADDS the unparseable-action capability.
    // A low-confidence transcription (unclearConversation) never selects tools —
    // garbled words must not drive actions — and when the model declines (text
    // only, null) the existing conversation routing applies unchanged.
    //
    // A bare YES/NO is never offered to the model either: with nothing pending these
    // words are conversational acknowledgements (the same classification the
    // confirmation flow above already uses), not a request to act on the device.
    val toolResult = agentResult ?: when {
      intent is DvexIntent.Conversation &&
        confidence < INTENT_LOW_CONFIDENCE_THRESHOLD &&
        !unclearConversation &&
        !isAffirmative(lower) &&
        !isNegative(lower) &&
        intent.statement.isNotBlank() ->
        runModelToolTurn(rawInput) ?: toolRouter.execute(intent, conversationContext)
      else -> toolRouter.execute(intent, conversationContext)
    }
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

    // 6. Check if confirmation is required (Sensitive actions: Call, SMS, Email —
    // including a HIGH-risk call the MODEL selected: the same gate, one system).
    if (toolResult.requiresConfirmation) {
      if (activePendingIntent == null && activePendingToolCall != null) {
        Log.i(TAG_BRAIN, "Armed pending confirmation for a protocol-selected sensitive action: ${activePendingToolCall?.toolName}")
      } else {
        activePendingIntent = intent
        Log.i(TAG_BRAIN, "Armed pending confirmation for sensitive action: ${intent::class.simpleName}")
      }
      activePendingId = toolResult.pendingActionId
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
   *
   * A protocol-path pending ([activePendingToolCall]) re-enters the EXISTING router
   * execution path: the admitted call is re-admitted against the registry (so even
   * the "yes" cannot smuggle in a different tool or argument) and mapped onto the
   * same [DvexIntent] the deterministic confirmation flow has always executed. No
   * second execution system, no second confirmation system.
   */
  private suspend fun executeConfirmedAction(
    intent: DvexIntent,
    pendingToolCall: DvexToolCall?
  ): DvexToolResult {
    if (pendingToolCall != null) {
      // Re-admit the stored call so even the "yes" cannot smuggle in a different
      // tool or argument, then run the router's confirmed path — the exact
      // execution the deterministic HIGH-risk flow has always used (no second
      // confirmation, no re-entry into the agent engine's HIGH-risk gate).
      val tool = mcpRegistry.tool(pendingToolCall.toolName)
      val admission = DvexToolProtocol.admit(pendingToolCall, tool)
      val confirmedIntent = (admission as? DvexToolAdmission.Accepted)
        ?.let { toolIntentFor(it) }
        ?: intent
      return toolRouter.executeConfirmed(confirmedIntent)
    }
    return toolRouter.executeConfirmed(intent)
  }

  fun cancelPendingConfirmation() {
    activePendingIntent = null
    activePendingId = null
    activePendingToolCall = null
    activePendingToolPlan = null
  }

  fun hasPendingConfirmation(): Boolean =
    activePendingIntent != null || activePendingToolCall != null

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

  // =========================================================================
  // INCREMENT 2 — MODEL TOOL-SELECTED EXECUTION (protocol path)
  //
  // Exactly ONE tool-capable Gemini exchange per action turn, and tool results
  // are NEVER sent back to the model within the turn — the model can therefore
  // never drive a model/tool loop. All admission rules live in
  // [DvexToolProtocol]; all execution/safety/verification lives in the existing
  // [DvexAgentEngine]; all natural phrasing stays with [DvexResponseGenerator].
  // =========================================================================

  /**
   * One bounded model tool exchange for an ACTION-SHAPED, otherwise-unserved turn.
   * The caller guarantees the detector's pure fall-through (Conversation below the
   * low-confidence threshold, reliable transcription); recognized conversation,
   * GeneralQuestion and every deterministic flow never construct this call.
   *
   * Returns the canonical [DvexToolResult] of what actually happened — an executed
   * verified action, the honest refusal of a bad call, or null when the model did
   * not select any tool (plain text answer → the caller keeps the existing flow).
   */
  private suspend fun runModelToolTurn(prompt: String): DvexToolResult? {
    val engine = aiEngine ?: return null
    if (mcpRegistry.size == 0) return null

    val turn = engine.generateWithTools(prompt, mcpRegistry.tools())
    if (!turn.hasToolCall) {
      Log.i(TAG_BRAIN, "[D-VEX][TOOLTURN] model answered with text only (${turn.text?.length ?: 0} chars); no tool selected")
      return null
    }

    val calls = turn.toolCalls.take(MAX_TOOL_CALLS_PER_TURN)
    if (turn.toolCalls.size > MAX_TOOL_CALLS_PER_TURN) {
      Log.w(TAG_BRAIN, "[D-VEX][TOOLTURN] model selected ${turn.toolCalls.size} calls; executing only the first $MAX_TOOL_CALLS_PER_TURN (bounded)")
    }

    Log.i(TAG_BRAIN, "[D-VEX][TOOLTURN] model selected ${calls.size} tool call(s): ${calls.joinToString(", ") { it.toolName }} (selection only — D-VEX decides)")

    // REFUSE FIRST. A turn that carries any blocked call executes NOTHING: the model
    // asked for something D-VEX will not do, so no other call from the same turn is
    // allowed to act either. The first refusal becomes the turn's outcome, spoken
    // verbatim, and no handler is ever invoked for it.
    val admissions = calls.map { DvexToolProtocol.admit(it, mcpRegistry) }
    val refusal = admissions.filterIsInstance<DvexToolAdmission.Refused>().firstOrNull()
    if (refusal != null) {
      Log.w(TAG_BRAIN, "[D-VEX][TOOLTURN] refused '${refusal.result.toolName}': ${refusal.result.error} — no call from this turn executes")
      return refusedResult(refusal)
    }

    // Otherwise: exactly ONE admitted call runs per turn (the agent engine already
    // owns multi-step behaviour for the deterministic path; the model must never be
    // able to fan one utterance out into several device actions).
    val accepted = admissions.filterIsInstance<DvexToolAdmission.Accepted>()
    val admission = accepted.firstOrNull() ?: return null
    if (accepted.size > 1) {
      Log.i(TAG_BRAIN, "[D-VEX][TOOLTURN] ${accepted.size} admitted call(s); running only '${admission.tool.name}' — one action per turn")
    }

    val result = executeAdmittedCall(admission)
    if (result.requiresConfirmation) {
      // Hold the call at the gate; the user's next "yes" executes it through the
      // existing confirmation machinery (never the agent engine's gate again).
      activePendingToolCall = calls.first()
      activePendingToolPlan = DvexToolProtocol.planFor(admission)
    }
    return result
  }

  /**
    * Executes ONE admitted call through the EXISTING agent engine: capability
    * check → safety policy → confirmation gate → handler → verification. The plan's
    * risk/capability come from the registry descriptor, never from the model.
    */
  private suspend fun executeAdmittedCall(admission: DvexToolAdmission.Accepted): DvexToolResult {
    val plan = DvexToolProtocol.planFor(admission)
    Log.i(TAG_BRAIN, "[D-VEX][TOOLTURN] executing model-selected plan: ${plan.summary}")
    return agentEngine.runPlan(plan)
  }

  /** Converts a protocol refusal into the existing tool-result flow, verbatim. */
  private fun refusedResult(admission: DvexToolAdmission.Refused): DvexToolResult {
    val result = admission.result
    return DvexToolResult(
      status = when (result.status) {
        com.example.agent.DvexMcpStatus.VERIFIED_SUCCESS -> DvexToolStatus.SUCCESS
        com.example.agent.DvexMcpStatus.FAILED -> DvexToolStatus.FAILED
        com.example.agent.DvexMcpStatus.UNVERIFIED -> DvexToolStatus.UNVERIFIED
        com.example.agent.DvexMcpStatus.BLOCKED -> DvexToolStatus.UNSUPPORTED
      },
      toolName = result.toolName,
      message = result.message,
      spokenText = result.message
    )
  }

  /**
   * Maps an admitted call onto the [DvexIntent] the router's confirmed-execution
   * path already implements (executeConfirmed switches on DvexIntent). This is a
   * mapping only — it grants nothing: the intent flows into the same permissioned,
   * verified execution the deterministic confirmation flow has always used. The
   * reminder time must match HH:mm exactly, as the tool's declared field promises.
   */
  private fun toolIntentFor(admission: DvexToolAdmission.Accepted): DvexIntent? {
    val arguments = admission.arguments
    fun arg(name: String): String = arguments[name].orEmpty()
    return when (admission.tool.actionType) {
      com.example.agent.AgentActionType.SEND_MESSAGE -> DvexIntent.SendMessage(
        recipient = arg("recipient"),
        messageText = arg("message").ifBlank { null },
        isWhatsApp = arg("recipient").contains("whatsapp", ignoreCase = true)
      )
      com.example.agent.AgentActionType.CREATE_REMINDER ->
        "^(\\d{1,2}):(\\d{2})$".toRegex().find(arg("time"))?.let { match ->
          DvexIntent.SetAlarm(
            hour = match.groupValues[1].toIntOrNull(),
            minute = match.groupValues[2].toIntOrNull(),
            message = arg("label").ifBlank { null }
          )
        }
      else -> null
    }
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

    /** Maximum admitted tool calls per user turn — the model may never drive a loop. */
    private const val MAX_TOOL_CALLS_PER_TURN = 3
  }
}

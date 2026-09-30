package com.example.agent

/**
 * D-VEX TOOL PROTOCOL — the boundary between the AI model and D-VEX tools.
 *
 * WHAT THE MODEL MAY DO: understand the request, select one of the exposed tools by
 * name, supply arguments for it, and otherwise keep talking in plain language. That
 * is all.
 *
 * WHAT D-VEX OWNS (and the model can never bypass): looking the tool up, validating
 * its arguments, the safety policy, the permission check, the confirmation prompt,
 * execution, verification of the real effect, recovery when a step fails, and the
 * final user-facing result.
 *
 * The boundary therefore has three model-independent data types and one adapter seam:
 *
 *  - [DvexToolCall]      — the model's *request* ("use open_app with app_name=YouTube").
 *  - [DvexToolAdmission] — D-VEX's decision: `Accepted` (a real tool, valid arguments)
 *                          or `Refused` (with the canonical [DvexMcpResult] explaining it).
 *  - [AiToolAdapter]     — how one model dialect is encoded/decoded. The JSON dialect
 *                          lives ONLY in the implementation (see `ai/GeminiToolAdapter`);
 *                          nothing in this file knows any model's wire format.
 *
 * TYPICAL USE (the only supported flow):
 * ```
 * val admissions = DvexToolProtocol.exchange(adapter, rawModelResponse, mcpRegistry)
 * admissions.forEach { admission ->
 *   when (admission) {
 *     is DvexToolAdmission.Accepted -> engine.runPlan(DvexToolProtocol.planFor(admission))
 *     is DvexToolAdmission.Refused  -> speak(admission.result.message)  // no action was taken
 *   }
 * }
 * ```
 * `engine.runPlan` is [DvexAgentEngine]: it performs the permission check, the
 * [DvexActionPolicy] confirmation gate, the handler execution and the verification
 * gate, and it returns the honest final result. This file executes NOTHING.
 *
 * HARD RULES pinned by DvexToolProtocolTest:
 *  - a tool name D-VEX does not expose is refused, never guessed at;
 *  - missing or unparsable arguments are refused, never defaulted silently;
 *  - a refused call is [DvexMcpStatus.BLOCKED]/FAILED — never promoted to success;
 *  - risk and capability always come from the registry descriptor, so a model can
 *    never lower the risk of the action it selected.
 *
 * LIVE STATUS: receiving a `functionCall` from the live model is still PENDING (the
 * free-tier daily quota for `gemini-3.8-flash` is exhausted), and the conversation
 * pipeline does not route through this boundary yet. The types and the admissions
 * below are covered by unit tests; they are not evidence of live function calling.
 */

/** One tool the model asked for, in D-VEX's own vocabulary. Carries no authority. */
data class DvexToolCall(
  val toolName: String,
  val arguments: Map<String, String> = emptyMap()
)

/** What D-VEX decided about a model's tool call. */
sealed class DvexToolAdmission {

  /**
   * The tool exists and the arguments satisfy its declared schema. Nothing has been
   * executed: the caller must go through [DvexAgentEngine], which owns the permission
   * check, the safety policy, confirmation, execution and verification.
   */
  data class Accepted(
    val tool: DvexMcpTool,
    val arguments: Map<String, String>
  ) : DvexToolAdmission()

  /**
   * D-VEX will not act on the call. [result] is the canonical, honest outcome and is
   * the final result of the turn — there is nothing to execute and nothing to retry.
   */
  data class Refused(val result: DvexMcpResult) : DvexToolAdmission()
}

/**
 * One model transport's tool dialect. Implementations live in the AI layer and are the
 * ONLY place a model wire format is understood; the agent layer sees plain D-VEX types.
 */
interface AiToolAdapter {

  /** Stable name of the dialect this adapter speaks (for logs and diagnostics). */
  val dialect: String

  /**
   * The payload the transport must send so the model can see [tools], or null when the
   * dialect cannot express tool declarations. Opaque to the agent layer by design.
   */
  fun encodeDeclarations(tools: List<DvexMcpTool>): String?

  /**
   * The tool calls the model selected in [modelResponse], in order. Empty means the
   * model replied with text only — never "assume it worked".
   */
  fun decodeCalls(modelResponse: String): List<DvexToolCall>
}

object DvexToolProtocol {

  /** User-facing line when the model asked for a capability D-VEX does not have. */
  const val UNKNOWN_TOOL_REPLY = "I don't have a way to do that."

  /** User-facing line when a call arrived without the details D-VEX needs. */
  const val INCOMPLETE_CALL_REPLY = "I need a bit more detail before I can do that."

  /** User-facing line when the model supplied a detail D-VEX could not understand. */
  const val UNPARSABLE_CALL_REPLY = "I didn't understand one of those details."

  /**
   * Admits (or refuses) one model tool call. The registry is the ONLY source of truth for
   * what D-VEX can do, so an unregistered name can never reach execution.
   */
  fun admit(call: DvexToolCall, registry: DvexMcpRegistry): DvexToolAdmission =
    admit(call, registry.tool(call.toolName))

  /**
   * Admits a call against an already-resolved descriptor; a null [tool] means D-VEX does
   * not expose that tool. Kept separate from the registry lookup so the admission rules can
   * be exercised against any schema.
   */
  fun admit(call: DvexToolCall, tool: DvexMcpTool?): DvexToolAdmission {
    if (tool == null) {
      return DvexToolAdmission.Refused(
        DvexMcpResult(
          toolName = call.toolName.trim().ifEmpty { UNNAMED_TOOL },
          status = DvexMcpStatus.BLOCKED,
          verification = VerificationOutcome.UNVERIFIED,
          message = UNKNOWN_TOOL_REPLY,
          error = "unknown tool: ${call.toolName.trim().ifEmpty { "<blank>" }}"
        )
      )
    }

    // Normalize: only schema-declared fields survive, trimmed; blank optionals drop out so
    // handlers see "absent" exactly as they do on the deterministic path. Undeclared keys
    // the model invented are ignored rather than forwarded to a handler.
    val arguments = tool.inputFields
      .mapNotNull { field -> call.arguments[field.name]?.trim()?.takeIf { it.isNotEmpty() }?.let { field.name to it } }
      .toMap()

    val missing = tool.requiredFields.filter { arguments[it.name] == null }.map { it.name }
    if (missing.isNotEmpty()) {
      return DvexToolAdmission.Refused(
        DvexMcpResult(
          toolName = tool.name,
          status = DvexMcpStatus.BLOCKED,
          verification = VerificationOutcome.UNVERIFIED,
          message = INCOMPLETE_CALL_REPLY,
          error = "missing required argument(s): ${missing.joinToString(", ")}"
        )
      )
    }

    // Every surviving key is a declared field, so the schema can always be looked up.
    val unparsable = arguments
      .filter { (name, value) -> !isWellFormed(tool.inputFields.first { it.name == name }, value) }
      .keys
    if (unparsable.isNotEmpty()) {
      return DvexToolAdmission.Refused(
        DvexMcpResult(
          toolName = tool.name,
          status = DvexMcpStatus.BLOCKED,
          verification = VerificationOutcome.UNVERIFIED,
          message = UNPARSABLE_CALL_REPLY,
          error = "invalid argument(s): ${unparsable.sorted().joinToString(", ")}"
        )
      )
    }

    return DvexToolAdmission.Accepted(tool = tool, arguments = arguments)
  }

  /** True when [value] can be read as the field's declared type. */
  private fun isWellFormed(field: DvexMcpInputField, value: String): Boolean = when (field.type) {
    McpFieldType.STRING -> true
    McpFieldType.INTEGER -> value.toIntOrNull() != null
    McpFieldType.NUMBER -> value.toDoubleOrNull() != null
    McpFieldType.BOOLEAN -> value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true)
  }

  /**
   * Reads one model response through [adapter] and admits every call it selected.
   * An empty list means the model produced no tool call (text-only turn).
   */
  fun exchange(
    adapter: AiToolAdapter,
    modelResponse: String,
    registry: DvexMcpRegistry
  ): List<DvexToolAdmission> =
    adapter.decodeCalls(modelResponse).map { admit(it, registry) }

  /**
   * The executable action for an admitted call. Only the call's ARGUMENTS live here —
   * risk and capability are copied from the registry descriptor, so a crafted tool call
   * cannot talk its way past [DvexActionPolicy].
   *
   * Kept separate from [planFor] so a caller can inspect what would run before running it.
   */
  fun actionFor(admission: DvexToolAdmission.Accepted): AgentAction {
    val tool = admission.tool
    val arguments = admission.arguments
    fun argument(name: String): String = arguments[name].orEmpty()

    return when (tool.actionType) {
      AgentActionType.OPEN_APP -> action(tool, target = argument("app_name"))
      AgentActionType.SEARCH_IN_APP -> action(
        tool,
        target = argument("app_name"),
        query = argument("query")
      )
      AgentActionType.LAUNCH_URL -> action(tool, target = argument("url"))
      AgentActionType.GET_WEATHER ->
        // `place` is forwarded as the target; the weather path itself resolves the
        // user's location today, so nothing is invented when it is absent.
        action(tool, target = argument("place"))
      AgentActionType.SEND_MESSAGE -> action(
        tool,
        target = argument("recipient"),
        query = argument("message")
      )
      AgentActionType.CREATE_REMINDER -> action(
        tool,
        target = argument("time"),
        query = argument("label")
      )
      // The scroll handler reads the direction from `query` (uppercase, like the planner).
      AgentActionType.SCROLL -> action(tool, query = argument("direction").uppercase())
      AgentActionType.TAP_ELEMENT -> action(tool, query = argument("label"))
      AgentActionType.TYPE_TEXT -> action(
        tool,
        target = argument("field"),
        query = argument("text")
      )
      AgentActionType.CLEAR_TEXT -> action(tool, target = argument("field"))
      // Actions with no inputs: nothing but the descriptor decides what runs.
      AgentActionType.NAVIGATE_HOME,
      AgentActionType.NAVIGATE_BACK,
      AgentActionType.OPEN_RECENT_APPS,
      AgentActionType.OPEN_CAMERA,
      AgentActionType.OPEN_SETTINGS,
      AgentActionType.OPEN_NOTIFICATIONS,
      AgentActionType.OPEN_WIFI_SETTINGS,
      AgentActionType.OPEN_BLUETOOTH_SETTINGS,
      AgentActionType.OPEN_DISPLAY_SETTINGS,
      AgentActionType.OPEN_DATE_TIME_SETTINGS,
      AgentActionType.OPEN_LOCATION_SETTINGS,
      AgentActionType.OPEN_NOTIFICATION_SETTINGS,
      AgentActionType.OPEN_ACCESSIBILITY_SETTINGS,
      AgentActionType.SUBMIT_TEXT,
      AgentActionType.MEDIA_PLAY,
      AgentActionType.MEDIA_PAUSE,
      AgentActionType.MEDIA_NEXT,
      AgentActionType.MEDIA_PREVIOUS,
      AgentActionType.VOLUME_UP,
      AgentActionType.VOLUME_DOWN -> action(tool)
    }
  }

  /**
   * One-step plan for an admitted call, ready for [DvexAgentEngine.runPlan]. The summary
   * reuses the planner's own wording, so a confirmation prompt reads the same however
   * the action was triggered.
   */
  fun planFor(admission: DvexToolAdmission.Accepted): DvexTaskPlan {
    val action = actionFor(admission)
    return DvexTaskPlan(steps = listOf(action), summary = describeAction(action))
  }

  private fun action(
    tool: DvexMcpTool,
    target: String = "",
    query: String = ""
  ): AgentAction = AgentAction(
    type = tool.actionType,
    capability = tool.capability,
    target = target,
    query = query,
    // Never from the model: the descriptor's own classification is authoritative.
    risk = tool.risk,
    detail = query.ifBlank { target }
  )

  private const val UNNAMED_TOOL = "unknown_tool"
}

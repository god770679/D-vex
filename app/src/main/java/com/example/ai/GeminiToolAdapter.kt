package com.example.ai

import com.example.agent.AiToolAdapter
import com.example.agent.DvexMcpTool
import com.example.agent.DvexToolCall
import org.json.JSONObject

/**
 * One model turn: the text the model produced, plus any tool calls it selected.
 *
 * A turn may legitimately carry a tool call and no text — that is the model *asking*,
 * not D-VEX acting. Callers must treat an empty [toolCalls] as "no tool was selected",
 * never as "the action happened".
 */
data class AiModelTurn(
  val text: String?,
  val toolCalls: List<DvexToolCall> = emptyList()
) {
  val hasToolCall: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * GEMINI TOOL ADAPTER — the one place the Gemini function-calling JSON dialect is
 * translated into D-VEX's model-independent protocol types.
 *
 * It implements [AiToolAdapter] (owned by the agent layer) by delegating to
 * [GeminiToolCodec], so:
 *  - D-VEX tool descriptors go out as `tools[0].functionDeclarations[]`;
 *  - the model's `functionCall` parts come back as [DvexToolCall] (name + string args).
 *
 * Nothing here validates, permissions, executes or verifies anything: an adapter turns
 * bytes into a *request*. Admitting the request, running it and reporting the honest
 * result belong to `agent/DvexToolProtocol` and `DvexAgentEngine`.
 */
object GeminiToolAdapter : AiToolAdapter {

  /** Dialect id used in diagnostics. Matches the declaration shape the codec emits. */
  const val DIALECT = "gemini-function-declarations-v1"

  override val dialect: String = DIALECT

  override fun encodeDeclarations(tools: List<DvexMcpTool>): String = declarations(tools).toString()

  /** The `{"functionDeclarations": [...]}` object, for the REST `tools` array. */
  fun declarations(tools: List<DvexMcpTool>): JSONObject = GeminiToolCodec.toToolsObject(tools)

  override fun decodeCalls(modelResponse: String): List<DvexToolCall> =
    GeminiToolCodec.parseFunctionCalls(modelResponse).map { call ->
      DvexToolCall(toolName = call.name, arguments = GeminiToolCodec.argumentsMap(call))
    }
}

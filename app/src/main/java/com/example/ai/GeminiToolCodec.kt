package com.example.ai

import com.example.agent.DvexMcpTool
import com.example.agent.McpFieldType
import org.json.JSONArray
import org.json.JSONObject

/**
 * One function call the model asked for. Parsing only — nothing here runs it.
 * [arguments] is the model's raw argument object; validating, permissioning and
 * executing it is the deterministic agent's job.
 */
data class GeminiFunctionCall(
  val name: String,
  val arguments: JSONObject
)

/**
 * GEMINI TOOL CODEC
 *
 * Converts between the canonical D-VEX tool descriptors ([DvexMcpTool]) and the
 * Gemini REST `generateContent` function-calling JSON:
 *
 *  - D-VEX descriptor  -> `tools[0].functionDeclarations[]`
 *  - model response    -> [GeminiFunctionCall] (name + arguments)
 *
 * It is deliberately independent of device execution: selecting a tool is not
 * permission to perform it. Safety, confirmation, execution and verification stay
 * with the deterministic D-VEX agent.
 *
 * DIALECT: the declaration uses `parameters` with UPPERCASE OpenAPI type names
 * (`OBJECT`, `STRING`, `INTEGER`, `BOOLEAN`). That is the subset the live
 * `gemini-3.x` `generateContent` endpoint actually parses — verified against the
 * live API on 2026-09-29 by probing `gemini-3.8-flash`: an unknown key inside the
 * declaration returned HTTP 400 naming
 * `tools[0].function_declarations[0]`, and an invalid property type returned HTTP
 * 400 naming `...parameters.properties[0].value.type`, while a well-formed
 * declaration was accepted past validation (it was stopped only by the free-tier
 * daily quota, HTTP 429).
 *
 * LIVE STATUS: receiving an actual `functionCall` from the live model is still
 * PENDING (the free-tier daily quota for `gemini-3.8-flash` is exhausted). This
 * codec is NOT evidence that live function calling works; the parser is covered by
 * unit tests against the documented response shape.
 */
object GeminiToolCodec {

  /** OpenAPI-subset type names the REST endpoint accepts inside `parameters`. */
  private val GEMINI_TYPE_NAMES: Map<McpFieldType, String> = mapOf(
    McpFieldType.STRING to "STRING",
    McpFieldType.INTEGER to "INTEGER",
    McpFieldType.NUMBER to "NUMBER",
    McpFieldType.BOOLEAN to "BOOLEAN"
  )

  // =========================================================================
  // D-VEX tool descriptor -> Gemini function declaration
  // =========================================================================

  /**
   * The `{"functionDeclarations": [...]}` object to place under `tools`.
   * Only tools that are actually exposed by the registry should be passed in.
   */
  fun toToolsObject(tools: List<DvexMcpTool>): JSONObject =
    JSONObject().put(
      "functionDeclarations",
      JSONArray().apply { tools.forEach { put(toFunctionDeclaration(it)) } }
    )

  /** One Gemini function declaration for one D-VEX tool. */
  fun toFunctionDeclaration(tool: DvexMcpTool): JSONObject = JSONObject().apply {
    put("name", tool.name)
    put("description", tool.description)
    put("parameters", toParametersSchema(tool))
  }

  /**
   * The tool's input schema in the Gemini-accepted dialect. JSON escaping is left
   * to org.json, so descriptions with quotes/newlines cannot corrupt the payload.
   */
  fun toParametersSchema(tool: DvexMcpTool): JSONObject {
    val properties = JSONObject()
    tool.inputFields.forEach { field ->
      properties.put(
        field.name,
        JSONObject().apply {
          put("type", GEMINI_TYPE_NAMES[field.type] ?: "STRING")
          put("description", field.description)
        }
      )
    }
    return JSONObject().apply {
      put("type", "OBJECT")
      put("properties", properties)
      val required = tool.requiredFields.map { it.name }
      if (required.isNotEmpty()) put("required", JSONArray(required))
    }
  }

  /** Model-facing names of the supplied tools. */
  fun declarationNames(tools: List<DvexMcpTool>): Set<String> = tools.map { it.name }.toSet()

  // =========================================================================
  // Gemini response -> function call
  // =========================================================================

  /**
   * Extracts the first function call from a raw `generateContent` response body.
   * Returns null when the model replied with text only, or when the payload has no
   * parsable call — callers must treat null as "no tool was selected", never as
   * "assume it worked".
   *
   * Tolerates both the REST camelCase shape (`functionCall`) and the snake_case
   * variant (`function_call`), and both `args` / `arguments` payload keys, so a
   * wire-format change cannot silently drop a call.
   */
  fun parseFunctionCall(responseJson: String): GeminiFunctionCall? = try {
    val json = JSONObject(responseJson)
    val parts = json.optJSONArray("candidates")
      ?.optJSONObject(0)
      ?.optJSONObject("content")
      ?.optJSONArray("parts")
    parseFunctionCall(parts)
  } catch (e: Exception) {
    null
  }

  /** Extracts the first function call from a `parts` array; null when absent. */
  fun parseFunctionCall(parts: JSONArray?): GeminiFunctionCall? {
    if (parts == null) return null
    for (i in 0 until parts.length()) {
      val part = parts.optJSONObject(i) ?: continue
      val call = part.optJSONObject("functionCall") ?: part.optJSONObject("function_call")
      if (call != null) {
        val name = call.optString("name", "").trim()
        if (name.isNotEmpty()) {
          val args = call.optJSONObject("args") ?: call.optJSONObject("arguments") ?: JSONObject()
          return GeminiFunctionCall(name = name, arguments = args)
        }
      }
    }
    return null
  }

  /** Every function call in a response, in order (a plan may ask for several). */
  fun parseFunctionCalls(responseJson: String): List<GeminiFunctionCall> = try {
    val parts = JSONObject(responseJson)
      .optJSONArray("candidates")
      ?.optJSONObject(0)
      ?.optJSONObject("content")
      ?.optJSONArray("parts")
    val calls = mutableListOf<GeminiFunctionCall>()
    if (parts != null) {
      for (i in 0 until parts.length()) {
        val part = parts.optJSONObject(i) ?: continue
        val call = part.optJSONObject("functionCall") ?: part.optJSONObject("function_call") ?: continue
        val name = call.optString("name", "").trim()
        if (name.isEmpty()) continue
        calls += GeminiFunctionCall(
          name = name,
          arguments = call.optJSONObject("args") ?: call.optJSONObject("arguments") ?: JSONObject()
        )
      }
    }
    calls
  } catch (e: Exception) {
    emptyList()
  }

  /** Reads a string argument, or null when the model omitted/mistyped it. */
  fun stringArgument(call: GeminiFunctionCall, key: String): String? =
    call.arguments.optString(key, "").trim().takeIf { it.isNotEmpty() }

  /** Reads an integer argument, or null when absent / not a number. */
  fun intArgument(call: GeminiFunctionCall, key: String): Int? =
    if (call.arguments.has(key)) call.arguments.optInt(key) else null
}

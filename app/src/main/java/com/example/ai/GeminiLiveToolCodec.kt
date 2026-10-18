package com.example.ai

import android.util.Base64
import com.example.agent.DvexMcpTool
import com.example.agent.DvexToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * One function call the Live model asked for. Parsing only — nothing here runs it.
 *
 * [id] is the Live-API correlation id that MUST be echoed back in the
 * `toolResponse` message; [arguments] is the model's raw argument object.
 * Validating, permissioning, confirming, executing and verifying the call is the
 * deterministic agent's job (`agent/DvexToolProtocol`), exactly as in the REST
 * pipeline.
 */
data class LiveFunctionCall(
  val id: String,
  val name: String,
  val arguments: JSONObject
) {
  /** Same model-independent shape the REST pipeline hands to `DvexToolProtocol`. */
  fun toToolCall(): DvexToolCall =
    DvexToolCall(toolName = name, arguments = GeminiToolCodec.argumentsMap(GeminiFunctionCall(name, arguments)))
}

/** The client's answer to one [LiveFunctionCall], keyed by the call's [LiveFunctionCall.id]. */
data class LiveFunctionResponse(
  val id: String,
  val name: String,
  val response: JSONObject
)

/**
 * One meaningful thing a Live server message said. A single wire message can
 * produce several events (audio + turn completion often travel together), so
 * [GeminiLiveToolCodec.parseServerMessage] returns them in order.
 */
sealed class LiveServerEvent {
  /** The session is configured and ready — audio may now be sent. */
  object SetupComplete : LiveServerEvent()

  /** One chunk of the model's native AUDIO output: 24 kHz mono PCM16 little-endian. */
  class Audio(val bytes: ByteArray) : LiveServerEvent()

  /** Live transcription of what the user just said (when the server sends it). */
  data class UserTranscript(val text: String) : LiveServerEvent()

  /** Live transcription of what the model said. */
  data class ModelTranscript(val text: String) : LiveServerEvent()

  /** The model finished its turn (server VAD detected end of user speech and replied). */
  object TurnComplete : LiveServerEvent()

  /** The model was interrupted (barge-in): queued playback must be dropped. */
  object Interrupted : LiveServerEvent()

  /** The model selected one or more tools. Selection is not permission. */
  data class ToolCall(val calls: List<LiveFunctionCall>) : LiveServerEvent()

  /** The server warns the connection will close soon (normal end of the ~10 min session). */
  data class GoAway(val timeLeftMs: Long?) : LiveServerEvent()

  /** The server reported an error object on the socket. */
  data class ServerError(val code: Int, val message: String) : LiveServerEvent()
}

/**
 * GEMINI LIVE WIRE CODEC — raw WebSocket (`BidiGenerateContent`) JSON.
 *
 * Verifies against Google's official WebSocket guide
 * (ai.google.dev/gemini-api/docs/live-api/get-started-websocket, read 2026-10-09):
 *  - setup MUST be the first message: `{setup: {model: "models/<model>",
 *    generationConfig: {responseModalities: ["AUDIO"]}, systemInstruction: {parts: [{text}]}}}`
 *    (placement verified against the LIVE endpoint 2026-10-17: a setup-level
 *    `responseModalities` is rejected outright with close code 1007 —
 *    `Unknown name "responseModalities" at 'setup'`);
 *  - input audio: `{realtimeInput: {audio: {data: <base64>,
 *    mimeType: "audio/pcm;rate=16000"}}}` — raw 16 kHz PCM16 little-endian;
 *  - output audio: `serverContent.modelTurn.parts[].inlineData.data` (base64,
 *    24 kHz PCM16 little-endian native audio);
 *  - tools declare exactly like REST (`tools[0].functionDeclarations[]`, same
 *    dialect as [GeminiToolCodec]), requests arrive as `toolCall.functionCalls[]`
 *    with `{id, name, args}`, and the answer goes back as
 *    `{toolResponse: {functionResponses: [{id, name, response}]}}`;
 *  - model `gemini-3.8-live` is the model used in Google's own examples, and was
 *    confirmed servable for this project's API key on 2026-10-09
 *    (models.get → HTTP 200, supportedGenerationMethods contains
 *    bidiGenerateContent).
 *
 * Independence from execution is deliberate and total: this codec turns bytes
 * into [LiveServerEvent]s and back. Selecting a tool is not permission to
 * perform it — safety, confirmation, execution and verification stay with the
 * deterministic D-VEX agent, exactly as in the REST pipeline.
 */
object GeminiLiveToolCodec {

  /** MIME type the Live API requires for input audio. */
  const val INPUT_AUDIO_MIME_TYPE = "audio/pcm;rate=16000"

  // =========================================================================
  // Client -> server messages
  // =========================================================================

  /**
   * The mandatory first message of every Live session.
   *
   * [tools] uses the SAME declaration dialect as the REST engine
   * ([GeminiToolAdapter] / [GeminiToolCodec]), so D-VEX keeps exactly one
   * model-facing tool description in the codebase.
   */
  fun setupMessage(
    model: String,
    systemInstruction: String?,
    tools: List<DvexMcpTool>
  ): String {
    val setup = JSONObject().apply {
      // The wire wants the resource name: "models/<id>".
      put("model", if (model.startsWith("models/")) model else "models/$model")
      // responseModalities belongs UNDER generationConfig on the current endpoint.
      // Live-wire probe 2026-10-17: a setup-level "responseModalities" makes the
      // server close the socket (1007, Unknown name at 'setup'), so a session could
      // never reach setupComplete; generationConfig placement is accepted and the
      // session completes with audio + tool calls.
      put(
        "generationConfig",
        JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
      )
      val instruction = systemInstruction?.trim().orEmpty()
      if (instruction.isNotEmpty()) {
        put(
          "systemInstruction",
          JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction)))
        )
      }
      if (tools.isNotEmpty()) {
        put("tools", JSONArray().put(GeminiToolCodec.toToolsObject(tools)))
      }
    }
    return JSONObject().put("setup", setup).toString()
  }

  /**
   * One chunk of user audio: raw 16 kHz PCM16 little-endian, base64 (no wrap).
   * The key is never involved here — authentication rides the WebSocket URL.
   */
  fun audioMessage(pcm16le: ByteArray): String {
    val data = Base64.encodeToString(pcm16le, Base64.NO_WRAP)
    return JSONObject().put(
      "realtimeInput",
      JSONObject().put(
        "audio",
        JSONObject().apply {
          put("data", data)
          put("mimeType", INPUT_AUDIO_MIME_TYPE)
        }
      )
    ).toString()
  }

  /**
   * Text input (documented alternative to audio):
   * `{realtimeInput: {text: "..."}}` — used e.g. for a wake phrase that carried an
   * attached command ("D-VEX open YouTube").
   */
  fun textMessage(text: String): String =
    JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString()

  /** The answer to a batch of [LiveFunctionCall]s, echoing each call's correlation id. */
  fun toolResponseMessage(responses: List<LiveFunctionResponse>): String {
    val array = JSONArray()
    responses.forEach { r ->
      array.put(
        JSONObject().apply {
          put("id", r.id)
          put("name", r.name)
          put("response", r.response)
        }
      )
    }
    return JSONObject().put(
      "toolResponse",
      JSONObject().put("functionResponses", array)
    ).toString()
  }

  // =========================================================================
  // Server -> client messages
  // =========================================================================

  /**
   * Every event a server message carries, in order. Unparsable JSON and
   * messages with nothing meaningful yield an empty list — callers log, never
   * guess. Parsing is deliberately tolerant (camelCase and the snake_case
   * proto-JSON aliases both accepted), so a wire-format change cannot silently
   * drop a tool call or an interruption.
   */
  fun parseServerMessage(raw: String): List<LiveServerEvent> {
    val json = try {
      JSONObject(raw)
    } catch (e: Exception) {
      return emptyList()
    }
    val events = mutableListOf<LiveServerEvent>()

    if (json.has("setupComplete")) events += LiveServerEvent.SetupComplete

    json.optJSONObject("serverContent")?.let { sc ->
      sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
        for (i in 0 until parts.length()) {
          val part = parts.optJSONObject(i) ?: continue
          val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
          val b64 = inline?.optString("data", "")?.takeIf { it.isNotEmpty() } ?: continue
          val bytes = try {
            Base64.decode(b64, Base64.NO_WRAP)
          } catch (e: Exception) {
            continue
          }
          if (bytes.isNotEmpty()) events += LiveServerEvent.Audio(bytes)
        }
      }
      sc.optJSONObject("inputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }
        ?.let { events += LiveServerEvent.UserTranscript(it) }
      sc.optJSONObject("outputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }
        ?.let { events += LiveServerEvent.ModelTranscript(it) }
      if (sc.optBoolean("turnComplete", false) || sc.optBoolean("turn_complete", false)) {
        events += LiveServerEvent.TurnComplete
      }
      if (sc.optBoolean("interrupted", false)) {
        events += LiveServerEvent.Interrupted
      }
    }

    // Tool requests ride at the TOP level of the server message (not inside
    // serverContent) — exactly as the official guide shows.
    (json.optJSONObject("toolCall") ?: json.optJSONObject("tool_call"))?.let { tc ->
      val calls = parseFunctionCalls(tc)
      if (calls.isNotEmpty()) events += LiveServerEvent.ToolCall(calls)
    }

    (json.optJSONObject("goAway") ?: json.optJSONObject("go_away"))?.let { ga ->
      events += LiveServerEvent.GoAway(
        if (ga.has("timeLeftMs")) ga.optLong("timeLeftMs") else null
      )
    }

    json.optJSONObject("error")?.let { err ->
      events += LiveServerEvent.ServerError(err.optInt("code", 0), err.optString("message", ""))
    }

    return events
  }

  /** Every function call inside a `toolCall` object, in order. */
  private fun parseFunctionCalls(toolCall: JSONObject): List<LiveFunctionCall> {
    val array =
      toolCall.optJSONArray("functionCalls") ?: toolCall.optJSONArray("function_calls") ?: return emptyList()
    val calls = mutableListOf<LiveFunctionCall>()
    for (i in 0 until array.length()) {
      val fc = array.optJSONObject(i) ?: continue
      val name = fc.optString("name", "").trim()
      if (name.isEmpty()) continue
      val args = fc.optJSONObject("args") ?: fc.optJSONObject("arguments") ?: JSONObject()
      calls += LiveFunctionCall(id = fc.optString("id", ""), name = name, arguments = args)
    }
    return calls
  }
}

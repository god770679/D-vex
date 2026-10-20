package com.example.ai

import android.util.Base64
import com.example.agent.DvexMcpRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wire-pinning tests for the Gemini Live codec.
 *
 * Every JSON shape here is copied from Google's official WebSocket guide
 * (ai.google.dev/gemini-api/docs/live-api/get-started-websocket, read
 * 2026-10-09). If the Live wire format drifts, these tests say so BEFORE a
 * user ever hears silence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiLiveToolCodecTest {

  // =========================================================================
  // Client -> server
  // =========================================================================

  @Test
  fun setupMessage_pinsModelModalitiesAndSystemInstruction() {
    val json = JSONObject(
      GeminiLiveToolCodec.setupMessage("gemini-3.8-live", "You are D-VEX.", emptyList())
    )
    val setup = json.getJSONObject("setup")
    // The wire wants the resource name.
    assertEquals("models/gemini-3.8-live", setup.getString("model"))
    // responseModalities must live UNDER generationConfig: a setup-level copy is
    // rejected by the live endpoint (close 1007, observed 2026-10-17), so this pins
    // the wire-verified placement rather than the old shape.
    assertEquals(
      listOf("AUDIO"),
      jsonArrayToList(setup.getJSONObject("generationConfig").getJSONArray("responseModalities"))
    )
    assertEquals(
      "You are D-VEX.",
      setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
    )
    // No tools -> no tools key (a Live session must not advertise anything it cannot execute).
    assertFalse(setup.has("tools"))
  }

  @Test
  fun setupMessage_usesSameToolDialectAsRestPipeline() {
    val tools = DvexMcpRegistry.withDefaults().tools()
    assertTrue("registry should expose tools", tools.isNotEmpty())

    val setup = JSONObject(GeminiLiveToolCodec.setupMessage("gemini-3.8-live", null, tools))
      .getJSONObject("setup")

    val toolObject = setup.getJSONArray("tools").getJSONObject(0)
    val declarations = toolObject.getJSONArray("functionDeclarations")
    assertEquals(tools.size, declarations.length())

    val first = declarations.getJSONObject(0)
    val params = first.getJSONObject("parameters")
    // Same OpenAPI-subset dialect the REST endpoint was probed with: OBJECT + UPPERCASE types.
    assertEquals("OBJECT", params.getString("type"))
    assertTrue(params.has("properties"))

    // Blank system instruction -> key omitted (not an empty parts array).
    assertFalse(setup.has("systemInstruction"))
  }

  @Test
  fun audioMessage_roundTripsBase64WithRequiredMimeType() {
    val pcm = byteArrayOf(0x01, 0x02.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
    val json = JSONObject(GeminiLiveToolCodec.audioMessage(pcm))
    val audio = json.getJSONObject("realtimeInput").getJSONObject("audio")

    assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
    val decoded = Base64.decode(audio.getString("data"), Base64.NO_WRAP)
    assertTrue("base64 payload must round-trip", pcm.contentEquals(decoded))
  }

  @Test
  fun textMessage_wrapsTextAsRealtimeInput() {
    val json = JSONObject(GeminiLiveToolCodec.textMessage("open youtube"))
    val realtime = json.getJSONObject("realtimeInput")
    assertEquals("open youtube", realtime.getString("text"))
    assertFalse("text message must not carry audio", realtime.has("audio"))
  }

  @Test
  fun toolResponseMessage_echoesIdNameAndResponse() {
    val response = JSONObject().put("status", "denied").put("reason", "user declined")
    val json = JSONObject(
      GeminiLiveToolCodec.toolResponseMessage(
        listOf(LiveFunctionResponse(id = "call-1", name = "open_app", response = response))
      )
    )
    val fr = json.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
    assertEquals("call-1", fr.getString("id"))
    assertEquals("open_app", fr.getString("name"))
    assertEquals("denied", fr.getJSONObject("response").getString("status"))
  }

  // =========================================================================
  // Server -> client
  // =========================================================================

  @Test
  fun parse_setupComplete() {
    val events = GeminiLiveToolCodec.parseServerMessage("""{"setupComplete":{}}""")
    assertEquals(listOf(LiveServerEvent.SetupComplete), events)
  }

  @Test
  fun parse_modelAudioFromInlineData() {
    val a = byteArrayOf(1, 2, 3, 4)
    val b = byteArrayOf(9, 8)
    val b64a = Base64.encodeToString(a, Base64.NO_WRAP)
    val b64b = Base64.encodeToString(b, Base64.NO_WRAP)
    val events = GeminiLiveToolCodec.parseServerMessage(
      """
      {"serverContent":{"modelTurn":{"parts":[
        {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64a"}},
        {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64b"}}
      ]}}}
      """.trimIndent()
    )
    assertEquals(2, events.size)
    val bytes = events.map { (it as LiveServerEvent.Audio).bytes }
    assertTrue(bytes[0].contentEquals(a))
    assertTrue(bytes[1].contentEquals(b))
  }

  @Test
  fun parse_transcriptsTurnCompleteAndInterrupted() {
    val events = GeminiLiveToolCodec.parseServerMessage(
      """
      {"serverContent":{
        "inputTranscription":{"text":"open youtube"},
        "outputTranscription":{"text":"Opening YouTube."},
        "turnComplete":true,
        "interrupted":false
      }}
      """.trimIndent()
    )
    assertEquals(3, events.size)
    assertEquals(LiveServerEvent.UserTranscript("open youtube"), events[0])
    assertEquals(LiveServerEvent.ModelTranscript("Opening YouTube."), events[1])
    assertEquals(LiveServerEvent.TurnComplete, events[2])
  }

  @Test
  fun parse_interruptedAlone() {
    val events = GeminiLiveToolCodec.parseServerMessage("""{"serverContent":{"interrupted":true}}""")
    assertEquals(listOf(LiveServerEvent.Interrupted), events)
  }

  @Test
  fun parse_toolCallCarriesIdNameAndArgs() {
    val events = GeminiLiveToolCodec.parseServerMessage(
      """
      {"toolCall":{"functionCalls":[
        {"id":"c1","name":"open_app","args":{"app_name":"YouTube"}}
      ]}}
      """.trimIndent()
    )
    assertEquals(1, events.size)
    val call = (events[0] as LiveServerEvent.ToolCall).calls.single()
    assertEquals("c1", call.id)
    assertEquals("open_app", call.name)
    assertEquals("YouTube", call.arguments.getString("app_name"))

    // Mapping into the SAME tool-call shape the REST pipeline feeds to DvexToolProtocol.
    val toolCall = call.toToolCall()
    assertEquals("open_app", toolCall.toolName)
    assertEquals(mapOf("app_name" to "YouTube"), toolCall.arguments)
  }

  @Test
  fun parse_toolCallSkipsUnnamedCalls() {
    val events = GeminiLiveToolCodec.parseServerMessage(
      """{"toolCall":{"functionCalls":[{"id":"c1","args":{}},{"id":"c2","name":"open_app","args":{}}]}}"""
    )
    val calls = (events[0] as LiveServerEvent.ToolCall).calls
    assertEquals(listOf("open_app"), calls.map { it.name })
  }

  @Test
  fun parse_toleratesSnakeCaseProtoJson() {
    val events = GeminiLiveToolCodec.parseServerMessage(
      """{"tool_call":{"function_calls":[{"id":"c1","name":"open_app","arguments":{"app_name":"YouTube"}}]}}"""
    )
    assertEquals("open_app", (events[0] as LiveServerEvent.ToolCall).calls.single().name)
  }

  @Test
  fun parse_goAwayAndServerError() {
    val goAway = GeminiLiveToolCodec.parseServerMessage("""{"goAway":{"timeLeftMs":12000}}""")
    assertEquals(listOf(LiveServerEvent.GoAway(12000L)), goAway)

    val error = GeminiLiveToolCodec.parseServerMessage("""{"error":{"code":400,"message":"bad setup"}}""")
    assertEquals(listOf(LiveServerEvent.ServerError(400, "bad setup")), error)
  }

  @Test
  fun parse_malformedAndIrrelevantMessagesYieldNoEvents() {
    assertTrue(GeminiLiveToolCodec.parseServerMessage("not json at all").isEmpty())
    assertTrue(GeminiLiveToolCodec.parseServerMessage("{}").isEmpty())
    assertTrue(GeminiLiveToolCodec.parseServerMessage("""{"serverContent":{}}""").isEmpty())
  }

  private fun jsonArrayToList(array: JSONArray): List<String> =
    (0 until array.length()).map { array.getString(it) }
}

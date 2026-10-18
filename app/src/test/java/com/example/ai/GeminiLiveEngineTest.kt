package com.example.ai

import com.example.agent.DvexMcpRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Session-behaviour tests for [GeminiLiveEngine] over a fake socket.
 *
 * The whole point of the seams: no network, no device — every connection
 * lifecycle the Live mode must survive (setup, audio, tools, interruption,
 * transport failure, hang, double close) is exercised deterministically.
 *
 * These tests do NOT prove real-device audio latency; they prove the engine's
 * protocol, state machine, cleanup and credential hygiene.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiLiveEngineTest {

  /** Test key shaped like Google's newer `AQ.` format (used only for redaction tests). */
  private val testKey =
    "AQ.Ab8RN6LioS7k0I4y2P6XtUqW9vH3nB1mZ5cJ7dF2gK8pL4rT6yU0aS3dF5gH"

  private class FakeSocket : LiveSocket {
    val sent = CopyOnWriteArrayList<String>()
    val closedWith = CopyOnWriteArrayList<Pair<Int, String?>>()
    @Volatile var failSend = false
    @Volatile var listener: LiveSocketListener? = null

    override fun send(text: String): Boolean {
      if (failSend) return false
      sent += text
      return true
    }

    override fun close(code: Int, reason: String?): Boolean {
      closedWith += Pair(code, reason)
      return true
    }
  }

  private class FakeFactory : LiveSocketFactory {
    val urls = CopyOnWriteArrayList<String>()
    val socket = FakeSocket()

    override fun connect(url: String, listener: LiveSocketListener): LiveSocket {
      urls += url
      socket.listener = listener
      return socket
    }
  }

  private class RecordingListener : LiveSessionListener {
    val setupComplete = CopyOnWriteArrayList<Unit>()
    val audio = CopyOnWriteArrayList<ByteArray>()
    val userTranscripts = CopyOnWriteArrayList<String>()
    val modelTranscripts = CopyOnWriteArrayList<String>()
    val turnComplete = CopyOnWriteArrayList<Unit>()
    val interrupted = CopyOnWriteArrayList<Unit>()
    val toolCalls = CopyOnWriteArrayList<List<LiveFunctionCall>>()
    val goAways = CopyOnWriteArrayList<Long?>()
    val errors = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val closed = CopyOnWriteArrayList<String>()

    override fun onSetupComplete() { setupComplete += Unit }
    override fun onAudio(bytes: ByteArray) { audio += bytes }
    override fun onUserTranscript(text: String) { userTranscripts += text }
    override fun onModelTranscript(text: String) { modelTranscripts += text }
    override fun onTurnComplete() { turnComplete += Unit }
    override fun onInterrupted() { interrupted += Unit }
    override fun onToolCall(calls: List<LiveFunctionCall>) { toolCalls += calls }
    override fun onGoAway(timeLeftMs: Long?) { goAways += timeLeftMs }
    override fun onError(message: String, fatal: Boolean) { errors += Pair(message, fatal) }
    override fun onClosed(reason: String) { closed += reason }
  }

  private val factory = FakeFactory()
  private val listener = RecordingListener()

  private fun engine(apiKey: String? = testKey, setupTimeoutMs: Long = 5_000L) = GeminiLiveEngine(
    apiKeyProvider = { apiKey },
    socketFactory = factory,
    callbackDispatcher = { it.run() }, // synchronous in tests
    model = "gemini-3.8-live",
    wsUrlBase = GeminiLiveEngine.DEFAULT_WS_URL,
    setupTimeoutMs = setupTimeoutMs
  )

  private fun GeminiLiveEngine.connectAndOpen(): Boolean {
    val started = connect("You are D-VEX.", DvexMcpRegistry.withDefaults().tools(), listener)
    if (started) factory.socket.listener!!.onOpen()
    return started
  }

  // =========================================================================
  // Connection + setup
  // =========================================================================

  @Test
  fun missingKey_failsFastWithoutConnectionAttempt() {
    val e = engine(apiKey = null)
    assertFalse(e.connect("sys", emptyList(), listener))
    assertEquals(0, factory.urls.size)
    assertTrue(listener.errors.single().second) // fatal
    assertTrue("engine must not be open", !e.isOpen)
  }

  @Test
  fun connect_keyRidesUrlAndSetupIsTheFirstMessage() {
    val e = engine()
    assertTrue(e.connectAndOpen())

    // Auth: the key rides the URL as the ONLY query parameter.
    val url = factory.urls.single()
    assertTrue(url.startsWith(GeminiLiveEngine.DEFAULT_WS_URL))
    assertTrue(url.endsWith("?key=$testKey"))

    // Setup MUST be the first message on the wire.
    assertEquals(1, factory.socket.sent.size)
    val setup = JSONObject(factory.socket.sent[0]).getJSONObject("setup")
    assertEquals("models/gemini-3.8-live", setup.getString("model"))
    // Wire-verified placement (live endpoint 2026-10-17): under generationConfig.
    assertEquals(
      "AUDIO",
      setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0)
    )
    assertTrue("tools must be declared", setup.has("tools"))
    assertFalse("not ready before setupComplete", e.isOpen)

    // Server confirms -> session ready.
    factory.socket.listener!!.onMessage("""{"setupComplete":{}}""")
    assertTrue(e.isOpen)
    assertEquals(1, listener.setupComplete.size)
  }

  @Test
  fun doubleConnect_isRejected() {
    val e = engine()
    assertTrue(e.connectAndOpen())
    assertFalse(e.connect("sys", emptyList(), listener))
    assertEquals("second attempt must not dial", 1, factory.urls.size)
  }

  @Test
  fun setupTimeout_closesHungConnection() {
    val e = engine(setupTimeoutMs = 100L)
    assertTrue(e.connectAndOpen())
    Thread.sleep(500) // watchdog window
    val (message, fatal) = listener.errors.single()
    assertTrue("expected fatal setup-timeout error, got: $message", fatal)
    assertTrue(message.contains("setup not confirmed"))
    assertTrue("hung socket must be closed", factory.socket.closedWith.isNotEmpty())
    assertFalse(e.isOpen)
  }

  // =========================================================================
  // Audio
  // =========================================================================

  @Test
  fun sendAudio_beforeSetupComplete_isDropped() {
    val e = engine()
    assertTrue(e.connectAndOpen()) // open, but no setupComplete yet
    assertFalse(e.sendAudio(byteArrayOf(1, 2)))
    assertEquals("only setup may be on the wire", 1, factory.socket.sent.size)
  }

  @Test
  fun sendAudio_afterSetup_wrapsPcmAsRealtimeInput() {
    val e = engine()
    assertTrue(e.connectAndOpen())
    factory.socket.listener!!.onMessage("""{"setupComplete":{}}""")

    val pcm = byteArrayOf(0x10, 0x20, 0x30, 0x40)
    assertTrue(e.sendAudio(pcm))

    val audio = JSONObject(factory.socket.sent[1])
      .getJSONObject("realtimeInput")
      .getJSONObject("audio")
    assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
    val decoded = android.util.Base64.decode(audio.getString("data"), android.util.Base64.NO_WRAP)
    assertTrue(pcm.contentEquals(decoded))
  }

  @Test
  fun sendText_followsTheSameOpenSessionGuardsAsAudio() {
    val e = engine()
    assertTrue(e.connectAndOpen())
    // Before setupComplete: dropped.
    assertFalse(e.sendText("open youtube"))
    assertEquals(1, factory.socket.sent.size)

    factory.socket.listener!!.onMessage("""{"setupComplete":{}}""")
    assertTrue(e.sendText("open youtube"))
    val realtime = JSONObject(factory.socket.sent[1]).getJSONObject("realtimeInput")
    assertEquals("open youtube", realtime.getString("text"))
  }

  // =========================================================================
  // Server events
  // =========================================================================

  @Test
  fun transcriptsTurnCompleteAndInterrupted_reachListener() {
    val e = engine()
    assertTrue(e.connectAndOpen())
    factory.socket.listener!!.onMessage("""{"setupComplete":{}}""")

    factory.socket.listener!!.onMessage(
      """{"serverContent":{"inputTranscription":{"text":"hello"},"outputTranscription":{"text":"hi"},"turnComplete":true}}"""
    )
    factory.socket.listener!!.onMessage("""{"serverContent":{"interrupted":true}}""")

    assertEquals(listOf("hello"), listener.userTranscripts.toList())
    assertEquals(listOf("hi"), listener.modelTranscripts.toList())
    assertEquals(1, listener.turnComplete.size)
    assertEquals(1, listener.interrupted.size)
  }

  @Test
  fun toolCall_isSelectionOnly_andResponsesGoBackOnTheWire() {
    val e = engine()
    assertTrue(e.connectAndOpen())
    factory.socket.listener!!.onMessage("""{"setupComplete":{}}""")

    factory.socket.listener!!.onMessage(
      """{"toolCall":{"functionCalls":[{"id":"c1","name":"open_app","args":{"app_name":"YouTube"}}]}}"""
    )
    val calls = listener.toolCalls.single()
    assertEquals("open_app", calls.single().name)
    assertEquals("c1", calls.single().id)

    // The deterministic agent decides (here: denied) — the response carries the
    // REAL outcome, keyed by the call id.
    val denied = JSONObject().put("status", "denied")
    assertTrue(
      e.sendToolResponses(listOf(LiveFunctionResponse(id = "c1", name = "open_app", response = denied)))
    )
    val fr = JSONObject(factory.socket.sent.last())
      .getJSONObject("toolResponse")
      .getJSONArray("functionResponses")
      .getJSONObject(0)
    assertEquals("c1", fr.getString("id"))
    assertEquals("open_app", fr.getString("name"))
    assertEquals("denied", fr.getJSONObject("response").getString("status"))
  }

  // =========================================================================
  // Failure, cleanup, credential hygiene
  // =========================================================================

  @Test
  fun transportFailure_isFatalAndTearsDown() {
    val e = engine()
    assertTrue(e.connectAndOpen())
    factory.socket.listener!!.onMessage("""{"setupComplete":{}}""")

    factory.socket.failSend = true
    factory.socket.listener!!.onFailure(java.io.IOException("network gone"))

    val (_, fatal) = listener.errors.single()
    assertTrue(fatal)
    assertTrue("socket must be closed", factory.socket.closedWith.isNotEmpty())
    assertFalse("engine must not stay open", e.isOpen)

    // Audio after death is dropped, never queued into a dead socket.
    factory.socket.sent.clear()
    assertFalse(e.sendAudio(byteArrayOf(1)))
    assertEquals(0, factory.socket.sent.size)
  }

  @Test
  fun close_isIdempotentAndDispatchesOnClosedOnce() {
    val e = engine()
    assertTrue(e.connectAndOpen())
    factory.socket.listener!!.onMessage("""{"setupComplete":{}}""")

    e.close("session end")
    e.close("session end")
    e.close()

    assertEquals(1, factory.socket.closedWith.size)
    assertEquals(1, listener.closed.size)
    assertFalse(e.isOpen)
  }

  @Test
  fun failureTextAndLogs_neverCarryTheApiKey() {
    val e = engine()
    assertTrue(e.connectAndOpen())

    // Transport failure whose message smuggles the key AND a key-shaped query.
    factory.socket.listener!!.onFailure(
      java.io.IOException("failed connecting to wss://x?key=$testKey and AIzaSyAbcDefGhiJklMnoPqrStuVwXyZ012345678")
    )

    // The user-facing error is redacted…
    val (message, _) = listener.errors.single()
    assertFalse("error must not contain the key", message.contains(testKey))
    assertFalse("error must not contain Google key shapes", message.contains("AIza"))

    // …and so is every log line.
    val allLogs = ShadowLog.getLogs().joinToString("\n") { it.msg }
    assertFalse("logcat must never contain the key", allLogs.contains(testKey))
    assertFalse("logcat must never contain Google key shapes", allLogs.contains("AIzaSyAbc"))
    assertTrue("the connect line should still prove the key reached the build", allLogs.contains("connecting model="))
  }
}

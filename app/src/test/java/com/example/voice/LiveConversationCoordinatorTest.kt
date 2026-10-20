package com.example.voice

import android.util.Base64
import com.example.agent.DvexMcpRegistry
import com.example.agent.DvexToolCall
import com.example.ai.GeminiLiveEngine
import com.example.ai.LiveSocket
import com.example.ai.LiveSocketFactory
import com.example.ai.LiveSocketListener
import com.example.model.DvexSettings
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coordinator integration tests over FAKE dependencies: WebSocket (socket
 * seam), microphone ([MicCapture]), speaker ([PcmSink]) and the two agent
 * gateways (tool execution / confirmation answers). No network, no device.
 *
 * What they pin:
 *  - Live start/stop with exactly-once resource release (mic, sink, socket);
 *  - mic PCM reaches the wire as `realtimeInput.audio`; an attached wake command
 *    reaches the model as `realtimeInput.text`;
 *  - model audio plays through the sink; interruption flushes it;
 *  - tool calls are routed through the SAFETY GATEWAY (never executed here) and
 *    every correlation id gets the REAL outcome — unverified is never success;
 *  - a HIGH-risk call is held at the confirmation gate; the user's answer
 *    (voice or HUD) resolves it exactly once; duplicates never re-execute;
 *  - fatal engine errors end the session and release everything exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveConversationCoordinatorTest {

  private val testKey =
    "AQ.Ab8RN6LioS7k0I4y2P6XtUqW9vH3nB1mZ5cJ7dF2gK8pL4rT6yU0aS3dF5gH"

  // ------------------------------------------------------------------ fakes

  private class FakeSocket : LiveSocket {
    val sent = CopyOnWriteArrayList<String>()
    val closedWith = CopyOnWriteArrayList<Pair<Int, String?>>()
    @Volatile var listener: LiveSocketListener? = null
    @Volatile var failSend = false

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

  private class FakeSocketFactory : LiveSocketFactory {
    val socket = FakeSocket()
    override fun connect(url: String, listener: LiveSocketListener): LiveSocket {
      socket.listener = listener
      return socket
    }
  }

  private class FakeMic : MicCapture {
    @Volatile var startResult = true
    @Volatile var released = false
    val releaseCount = AtomicInteger(0)
    private val reads = AtomicInteger(0)

    override fun start(): Boolean = startResult

    override fun read(out: FloatArray, offset: Int, count: Int): Int {
      // First read delivers one full 100 ms chunk; later reads block like a real mic.
      if (reads.getAndIncrement() == 0) {
        for (i in 0 until count) out[offset + i] = (i % 8) / 32768.0f
        return count
      }
      while (!released) Thread.sleep(5)
      return -1
    }

    override fun stopAndRelease() {
      releaseCount.incrementAndGet()
      released = true
    }
  }

  private class FakeSink : PcmSink {
    val written = CopyOnWriteArrayList<ByteArray>()
    val flushCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    @Volatile var openResult = true

    override fun open(): Boolean = openResult
    override fun write(chunk: ByteArray): Int {
      written += chunk.copyOf()
      return chunk.size
    }
    override fun flush() { flushCount.incrementAndGet() }
    override fun close() { closeCount.incrementAndGet() }
  }

  private class RecordingCallbacks : LiveConversationCoordinator.Callbacks {
    val ready = CopyOnWriteArrayList<Unit>()
    val userTranscripts = CopyOnWriteArrayList<String>()
    val modelSpeech = CopyOnWriteArrayList<String>()
    val speakingEvents = CopyOnWriteArrayList<Boolean>()
    val toolExecutions = CopyOnWriteArrayList<String>()
    val confirmationRequests = CopyOnWriteArrayList<Pair<String, String>>()
    val confirmationsResolved = CopyOnWriteArrayList<Unit>()
    val errors = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val ended = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val speakingLatch = CountDownLatch(2) // true then false

    override fun onReady() { ready += Unit }
    override fun onUserTranscript(text: String) { userTranscripts += text }
    override fun onModelSpeech(text: String) { modelSpeech += text }
    override fun onSpeakingChanged(speaking: Boolean) {
      speakingEvents += speaking
      if (speaking) speakingLatch.countDown()
    }
    override fun onToolExecutionStarted(toolName: String) { toolExecutions += toolName }
    override fun onToolConfirmationRequested(toolName: String, prompt: String) {
      confirmationRequests += Pair(toolName, prompt)
    }
    override fun onToolConfirmationResolved() { confirmationsResolved += Unit }
    override fun onError(message: String, fatal: Boolean) { errors += Pair(message, fatal) }
    override fun onEnded(reason: String, fatal: Boolean) { ended += Pair(reason, fatal) }
  }

  private val factory = FakeSocketFactory()
  private val callbacks = RecordingCallbacks()
  private val mic = FakeMic()
  private val sink = FakeSink()

  /** Calls observed by the fake tool-execution gateway. */
  private val executedCalls = CopyOnWriteArrayList<DvexToolCall>()
  private val confirmationAnswers = CopyOnWriteArrayList<String>()

  private var executeToolCall: suspend (DvexToolCall) -> LiveToolOutcome =
    { LiveToolOutcome.Finished("success", "done") }
  private var answerConfirmation: suspend (String) -> LiveConfirmationResolution =
    { LiveConfirmationResolution("success", "ok") }

  private fun coordinator(debounceMs: Long = 20L): LiveConversationCoordinator {
    val engine = GeminiLiveEngine(
      apiKeyProvider = { testKey },
      socketFactory = factory,
      callbackDispatcher = { it.run() },
      model = "gemini-3.8-live",
      wsUrlBase = GeminiLiveEngine.DEFAULT_WS_URL,
      setupTimeoutMs = 30_000L
    )
    return LiveConversationCoordinator(
      engine = engine,
      micFactory = { mic },
      sinkFactory = { sink },
      systemInstruction = "You are D-VEX.",
      tools = DvexMcpRegistry.withDefaults().tools(),
      executeToolCall = { call -> executedCalls += call; executeToolCall(call) },
      answerConfirmation = { text -> confirmationAnswers += text; answerConfirmation(text) },
      callbacks = callbacks,
      dispatch = { it.run() },
      runAsync = { block -> CoroutineScope(Dispatchers.Unconfined).launch { block() } },
      confirmationDebounceMs = debounceMs
    )
  }

  /** Starts the session and drives the socket through open + setupComplete. */
  private fun startReady(c: LiveConversationCoordinator, initialText: String? = null) {
    assertTrue("session must start", c.start(initialText))
    assertTrue("session must be active", c.isActive)
    factory.socket.listener!!.onOpen()
    factory.socket.listener!!.onMessage("""{"setupComplete":{}}""")
  }

  private fun awaitAtLeast(collection: CopyOnWriteArrayList<*>, n: Int, timeoutMs: Long = 3_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (collection.size < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
    return collection.size >= n
  }

  // ------------------------------------------------------------ session flow

  @Test
  fun start_streamsMicPcmToWireAndReportsReady() {
    val c = coordinator()
    startReady(c)

    assertEquals(1, callbacks.ready.size)
    assertTrue("mic must be captured", !mic.released)
    // The 100 ms mic chunk must reach the wire as realtimeInput.audio (16 kHz PCM).
    val audioDeadline = System.currentTimeMillis() + 3_000
    var audioOnWire = false
    while (!audioOnWire && System.currentTimeMillis() < audioDeadline) {
      // NOTE: org.json escapes '/' as '\/', so match the parsed shape, not raw text.
      audioOnWire = factory.socket.sent.any { raw ->
        if (!raw.contains("realtimeInput")) return@any false
        val realtime = JSONObject(raw).getJSONObject("realtimeInput")
        realtime.has("audio") &&
          realtime.getJSONObject("audio").optString("mimeType") == "audio/pcm;rate=16000"
      }
      if (!audioOnWire) Thread.sleep(10)
    }
    assertTrue(
      "mic PCM must be on the wire; ended=${callbacks.ended} errors=${callbacks.errors} " +
        "ready=${callbacks.ready.size} micReleased=${mic.releaseCount.get()}",
      audioOnWire
    )

    c.end("test over")
    assertEquals("mic released exactly once", 1, mic.releaseCount.get())
    assertEquals("sink closed exactly once", 1, sink.closeCount.get())
  }

  @Test
  fun attachedWakeCommand_isSentAsTextInput() {
    val c = coordinator()
    startReady(c, initialText = "open youtube")

    val deadline = System.currentTimeMillis() + 3_000
    var found = false
    while (!found && System.currentTimeMillis() < deadline) {
      found = factory.socket.sent.any {
        it.contains("realtimeInput") && it.contains("open youtube")
      }
      if (!found) Thread.sleep(10)
    }
    assertTrue("attached command must reach the model as realtimeInput.text", found)
    c.end("done")
  }

  @Test
  fun modelAudio_playsThroughSinkAndSignalsSpeakingLifecycle() {
    val c = coordinator()
    startReady(c)

    val pcm = byteArrayOf(1, 2, 3, 4)
    val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
    factory.socket.listener!!.onMessage(
      """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]}}}"""
    )
    assertTrue("model audio must reach the sink", awaitAtLeast(sink.written, 1))
    assertTrue("first chunk must be the model audio", sink.written[0].contentEquals(pcm))
    assertTrue("speaking must have started", callbacks.speakingEvents.contains(true))

    factory.socket.listener!!.onMessage("""{"serverContent":{"turnComplete":true}}""")
    val turnDeadline = System.currentTimeMillis() + 3_000
    while (!callbacks.speakingEvents.contains(false) && System.currentTimeMillis() < turnDeadline) Thread.sleep(10)
    assertTrue("turn completion must end speaking", callbacks.speakingEvents.contains(false))
    c.end("done")
  }

  @Test
  fun interruption_flushesPlaybackWithoutTouchingAnythingGlobal() {
    val c = coordinator()
    startReady(c)

    val b64 = Base64.encodeToString(ByteArray(8) { it.toByte() }, Base64.NO_WRAP)
    factory.socket.listener!!.onMessage(
      """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"$b64"}}]}}}"""
    )
    assertTrue(awaitAtLeast(sink.written, 1))

    factory.socket.listener!!.onMessage("""{"serverContent":{"interrupted":true}}""")
    val flushDeadline = System.currentTimeMillis() + 3_000
    while (sink.flushCount.get() == 0 && System.currentTimeMillis() < flushDeadline) Thread.sleep(10)
    assertTrue("barge-in must flush the sink", sink.flushCount.get() >= 1)
    c.end("done")
  }

  // ------------------------------------------------------------ tool routing

  @Test
  fun toolCall_routesThroughSafetyGateway_andReportsRealOutcome() {
    // The gateway reports an UNVERIFIED dispatch — never promotable to success.
    executeToolCall = { LiveToolOutcome.Finished("unverified", "The app opened but D-VEX could not confirm the screen.") }
    val c = coordinator()
    startReady(c)

    factory.socket.listener!!.onMessage(
      """{"toolCall":{"functionCalls":[{"id":"c1","name":"open_app","args":{"app_name":"YouTube"}}]}}"""
    )

    assertTrue(awaitAtLeast(executedCalls, 1))
    assertEquals("open_app", executedCalls[0].toolName)
    assertEquals("YouTube", executedCalls[0].arguments["app_name"])
    assertTrue(awaitAtLeast(callbacks.toolExecutions, 1))

    val response = awaitToolResponse("c1")
    assertEquals("unverified", response.getString("status"))
    assertFalse("unverified must never read as success", response.getString("status") == "success")
    assertTrue(response.getString("message").contains("could not confirm"))
    c.end("done")
  }

  @Test
  fun refusedCall_isAnsweredHonestly_withoutExecution() {
    executeToolCall = { LiveToolOutcome.Finished("unsupported", "I don't have a way to do that.") }
    val c = coordinator()
    startReady(c)

    factory.socket.listener!!.onMessage(
      """{"toolCall":{"functionCalls":[{"id":"c9","name":"mystery_tool","args":{}}]}}"""
    )
    val response = awaitToolResponse("c9")
    assertEquals("unsupported", response.getString("status"))
    c.end("done")
  }

  @Test
  fun confirmationRequired_holdsCall_andVoiceAnswerResolvesExactlyOnce() {
    executeToolCall = { LiveToolOutcome.ConfirmationRequired("Shall I send the message?") }
    answerConfirmation = { LiveConfirmationResolution("success", "Message sent.") }
    val c = coordinator()
    startReady(c)

    factory.socket.listener!!.onMessage(
      """{"toolCall":{"functionCalls":[{"id":"c1","name":"send_message","args":{"recipient":"Mom"}}]}}"""
    )
    val held = awaitToolResponse("c1")
    assertEquals("confirmation_required", held.getString("status"))
    assertTrue(c.hasPendingConfirmation)
    assertTrue(awaitAtLeast(callbacks.confirmationRequests, 1))
    assertEquals("Shall I send the message?", callbacks.confirmationRequests[0].second)

    // User answers by voice (transcript chunks) — debounced, then resolved.
    factory.socket.listener!!.onMessage(
      """{"serverContent":{"inputTranscription":{"text":"yes"}}}"""
    )
    assertTrue(awaitAtLeast(confirmationAnswers, 1, 5_000))
    assertEquals("yes", confirmationAnswers[0])

    // The confirmed outcome reaches the model naming the ORIGINAL correlation id.
    assertTrue(awaitTextSent("D-VEX_TOOL_OUTCOME id=c1"))
    assertTrue("pending must clear", !c.hasPendingConfirmation)
    assertTrue(awaitAtLeast(callbacks.confirmationsResolved, 1))

    // A late duplicate transcript must NOT re-execute the resolved confirmation.
    factory.socket.listener!!.onMessage(
      """{"serverContent":{"inputTranscription":{"text":"yes please"}}}"""
    )
    Thread.sleep(150)
    assertEquals("confirmation answered exactly once", 1, confirmationAnswers.size)
    c.end("done")
  }

  @Test
  fun hudCancel_resolvesPendingThroughGateway() {
    executeToolCall = { LiveToolOutcome.ConfirmationRequired("Call Rohit?") }
    answerConfirmation = { LiveConfirmationResolution("cancelled", "Cancelled.") }
    val c = coordinator()
    startReady(c)

    factory.socket.listener!!.onMessage(
      """{"toolCall":{"functionCalls":[{"id":"c2","name":"make_call","args":{"contact":"Rohit"}}]}}"""
    )
    assertTrue(awaitAtLeast(callbacks.confirmationRequests, 1))

    c.resolveConfirmationFromHud(false)
    assertTrue(awaitAtLeast(confirmationAnswers, 1, 5_000))
    assertEquals("cancel", confirmationAnswers[0])
    assertTrue(awaitTextSent("status=cancelled"))
    c.end("done")
  }

  @Test
  fun secondToolCall_whileConfirmationPending_getsBusyAnswer_withoutExecution() {
    executeToolCall = { LiveToolOutcome.ConfirmationRequired("Send it?") }
    val c = coordinator()
    startReady(c)

    factory.socket.listener!!.onMessage(
      """{"toolCall":{"functionCalls":[{"id":"c1","name":"send_message","args":{"recipient":"Mom"}}]}}"""
    )
    assertTrue(awaitAtLeast(callbacks.confirmationRequests, 1))

    factory.socket.listener!!.onMessage(
      """{"toolCall":{"functionCalls":[{"id":"c2","name":"open_app","args":{"app_name":"Maps"}}]}}"""
    )
    val busy = awaitToolResponse("c2")
    assertEquals("busy", busy.getString("status"))
    assertEquals("only the held call may execute", 1, executedCalls.size)
    c.resolveConfirmationFromHud(false)
    c.end("done")
  }

  @Test
  fun duplicateToolCallId_neverReExecutes() {
    val c = coordinator()
    startReady(c)

    val msg = """{"toolCall":{"functionCalls":[{"id":"dup1","name":"open_app","args":{"app_name":"YouTube"}}]}}"""
    factory.socket.listener!!.onMessage(msg)
    assertTrue(awaitAtLeast(executedCalls, 1))
    val first = awaitToolResponse("dup1")
    assertEquals("success", first.getString("status"))

    factory.socket.listener!!.onMessage(msg)
    // Second answer for the same id arrives with duplicate status; gateway NOT re-invoked.
    val deadline = System.currentTimeMillis() + 3_000
    var duplicates = 0
    while (System.currentTimeMillis() < deadline) {
      duplicates = factory.socket.sent.count {
        it.contains("\"id\":\"dup1\"") && it.contains("\"status\":\"duplicate\"")
      }
      if (duplicates >= 1) break
      Thread.sleep(10)
    }
    assertTrue("duplicate must be answered as duplicate", duplicates >= 1)
    assertEquals("gateway executed exactly once", 1, executedCalls.size)
    c.end("done")
  }

  // ------------------------------------------------------------ failure/cleanup

  @Test
  fun fatalEngineError_endsSession_andReleasesEverythingExactlyOnce() {
    val c = coordinator()
    startReady(c)

    factory.socket.listener!!.onFailure(IOException("network gone"))

    assertTrue(awaitAtLeast(callbacks.ended, 1))
    assertTrue(callbacks.ended[0].second) // fatal
    assertEquals("mic released exactly once", 1, mic.releaseCount.get())
    assertEquals("sink closed exactly once", 1, sink.closeCount.get())
    assertTrue("socket closed", factory.socket.closedWith.isNotEmpty())
    assertFalse(c.isActive)

    // A later end() must not release anything a second time.
    c.end("late")
    assertEquals(1, mic.releaseCount.get())
    assertEquals(1, sink.closeCount.get())
  }

  @Test
  fun externalEnd_releasesEverything_withoutFiringOnEnded() {
    val c = coordinator()
    startReady(c)

    c.end("user left the conversation")

    assertTrue("external end must not dispatch onEnded", callbacks.ended.isEmpty())
    assertEquals(1, mic.releaseCount.get())
    assertEquals(1, sink.closeCount.get())
    assertTrue(factory.socket.closedWith.isNotEmpty())
    assertFalse(c.isActive)

    // Late server events after end() are ignored — nothing plays, nothing sends.
    val writes = sink.written.size
    factory.socket.listener!!.onMessage(
      """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"AAAA"}}]}}}"""
    )
    Thread.sleep(100)
    assertEquals("no audio after end", writes, sink.written.size)
  }

  @Test
  fun remoteClose_endsSessionGracefully_andIsIdempotent() {
    val c = coordinator()
    startReady(c)

    factory.socket.listener!!.onClosed(1000, "server window ended")
    assertTrue(awaitAtLeast(callbacks.ended, 1))
    assertFalse("remote close is not fatal", callbacks.ended[0].second)
    assertEquals(1, mic.releaseCount.get())
    assertEquals(1, sink.closeCount.get())

    c.end("again")
    assertEquals(1, mic.releaseCount.get())
  }

  // ------------------------------------------------------------- live policy

  @Test
  fun liveModePolicy_isOffByDefault_andRequiresPermission() {
    assertFalse("Live must be OFF by default", DvexSettings().liveConversationEnabled)
    assertFalse(
      LiveModePolicy.shouldStartLive(DvexSettings().copy(liveConversationEnabled = true), hasAudioPermission = false)
    )
    assertTrue(
      LiveModePolicy.shouldStartLive(DvexSettings().copy(liveConversationEnabled = true), hasAudioPermission = true)
    )
    assertFalse(
      LiveModePolicy.shouldStartLive(DvexSettings(), hasAudioPermission = true)
    )
  }

  // --------------------------------------------------------------- helpers

  /** Waits for the toolResponse with the given id and returns its response object. */
  private fun awaitToolResponse(id: String): JSONObject {
    val deadline = System.currentTimeMillis() + 3_000
    while (System.currentTimeMillis() < deadline) {
      factory.socket.sent.forEach { raw ->
        if (!raw.contains("toolResponse")) return@forEach
        val responses = JSONObject(raw).getJSONObject("toolResponse").getJSONArray("functionResponses")
        for (i in 0 until responses.length()) {
          val entry = responses.getJSONObject(i)
          if (entry.optString("id") == id) return entry.getJSONObject("response")
        }
      }
      Thread.sleep(10)
    }
    throw AssertionError("no toolResponse for id=$id in: ${factory.socket.sent}")
  }

  /** Waits until a realtimeInput.text message containing [needle] was sent. */
  private fun awaitTextSent(needle: String): Boolean {
    val deadline = System.currentTimeMillis() + 3_000
    while (System.currentTimeMillis() < deadline) {
      if (factory.socket.sent.any { it.contains("realtimeInput") && it.contains(needle) }) return true
      Thread.sleep(10)
    }
    throw AssertionError("no text message containing \"$needle\" in: ${factory.socket.sent}")
  }
}

package com.example.brain

import com.example.ai.AiAvailability
import com.example.ai.AiQuotaState
import com.example.ai.GeminiEngine
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * FREE-FIRST ARCHITECTURE — the behaviour D-VEX must keep when Gemini cannot answer.
 *
 * The situation these tests exist for is real and permanent enough to design around:
 * the Gemini free tier answers `429 RESOURCE_EXHAUSTED` once the daily
 * `generate_content_free_tier_requests` cap is spent, and no source change can grant
 * more quota. Before this architecture D-VEX re-sent the same doomed request on every
 * user message and spoke one blanket apology every time.
 *
 * These tests pin the contract that replaces that:
 *  1. Gemini is still the primary voice whenever it can answer.
 *  2. A spent quota costs ONE request, then the local brain answers.
 *  3. A command that executes locally still reports its real, verified result.
 *  4. A question that genuinely needs the cloud model stays HONEST — no invented answer.
 *  5. The gate reopens on its own after the cooldown.
 *  6. Nothing about the credential ever reaches a log line.
 *
 * No test here touches the network: a loopback server speaks the real Gemini wire
 * protocol, exactly as [com.example.ai.GeminiEngineTest] does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexFreeFirstFallbackTest {

  private lateinit var server: HttpServer
  private var baseUrl: String = ""

  private val requestCount = AtomicInteger(0)
  private var responseCode: Int = 200
  private var responseBody: String = "{}"

  /** Distinctive test key so "did the credential leak?" is a real assertion. */
  private val testKey = "AQ.Zz9FreeFirstTestKeyNotARealCredential000111222333"

  private var now: Long = 1_000_000L
  private lateinit var quotaState: AiQuotaState
  private lateinit var generator: DvexResponseGenerator

  /** The exact body the live free tier returns when the daily cap is spent. */
  private val quotaExhaustedBody = """
    {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
    "message":"You exceeded your current quota, please check your plan and billing details.
    * Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash",
    "details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure"},
               {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3.777996s"}]}}
  """.trimIndent()

  /** The string D-VEX used to say for EVERY failure, whatever the real cause. */
  private val oldBlanketFallback = "I can't reach my AI connection right now, so I can't reply properly."

  @Before
  fun setUp() {
    ShadowLog.clear()
    requestCount.set(0)
    now = 1_000_000L
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      requestCount.incrementAndGet()
      exchange.requestBody.readBytes()
      val bytes = responseBody.toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
      exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    baseUrl = "http://127.0.0.1:${server.address.port}"

    quotaState = AiQuotaState(
      clock = { now },
      quotaCooldownMs = 60_000L,
      temporaryCooldownMs = 5_000L
    )
    val engine = GeminiEngine(
      apiKeyProvider = { testKey },
      baseUrl = baseUrl,
      quotaState = quotaState
    )
    generator = DvexResponseGenerator(engine, quotaState)
  }

  @After
  fun tearDown() {
    server.stop(0)
  }

  private fun geminiReply(answer: String): String = """
    {"candidates":[{"content":{"parts":[{"text":"$answer","thoughtSignature":"sig=="}],"role":"model"},
    "finishReason":"STOP","index":0}],
    "usageMetadata":{"thoughtsTokenCount":0},"modelVersion":"gemini-3.8-flash"}
  """.trimIndent()

  private fun conversationResult() = DvexToolResult(
    DvexToolStatus.SUCCESS,
    "conversation",
    "Conversation: greeting",
    ""
  )

  /** One full production conversational turn through the response layer. */
  private suspend fun say(input: String): String = generator.generateResponse(
    intent = DvexIntent.Conversation(input),
    toolResult = conversationResult(),
    language = DetectedLanguage.ENGLISH,
    userInput = input,
    context = null,
    memoryHint = null
  )

  private fun loggedLines(): List<String> =
    ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" }

  // (1) Gemini available -> Gemini response -----------------------------------

  @Test
  fun whenGeminiIsAvailableItsReplyIsWhatDvexSays() = runBlocking {
    responseBody = geminiReply("Good to hear from you. What can I do for you?")

    val reply = say("Hi D-VEX")

    assertEquals("Good to hear from you. What can I do for you?", reply)
    assertEquals(1, requestCount.get())
    assertEquals(AiAvailability.AVAILABLE, quotaState.current)
  }

  // (2) quota exhausted -> local fallback -------------------------------------

  @Test
  fun anExhaustedQuotaFallsBackToTheLocalBrainInsteadOfTheBlanketApology() = runBlocking {
    responseCode = 429
    responseBody = quotaExhaustedBody

    val reply = say("Hi D-VEX")

    assertNotEquals(
      "D-VEX must stop saying it cannot reach a connection that answered perfectly well",
      oldBlanketFallback,
      reply
    )
    assertTrue("a local greeting is expected: $reply", reply.contains("D-VEX"))
    assertTrue("the reply must be short and spoken: $reply", reply.length < 90)
    assertEquals("a spent quota costs exactly one request", 1, requestCount.get())
    assertEquals(AiAvailability.QUOTA_EXHAUSTED, quotaState.current)
  }

  // (3) quota exhausted -> NO repeated Gemini calls ---------------------------

  @Test
  fun anExhaustedQuotaStopsFurtherGeminiCallsForTheRestOfTheWindow() = runBlocking {
    responseCode = 429
    responseBody = quotaExhaustedBody
    say("Hi D-VEX")
    assertEquals(1, requestCount.get())

    // Five more real user messages. None of them may reach the network.
    val replies = listOf(
      say("what's the weather like in chennai"),
      say("tell me a joke"),
      say("thanks"),
      say("goodbye"),
      say("can you help me")
    )

    assertEquals(
      "no further request may be sent while the quota window is closed",
      1,
      requestCount.get()
    )
    replies.forEach { reply ->
      assertNotEquals(oldBlanketFallback, reply)
      assertTrue("every reply must still be a real spoken line: $reply", reply.isNotBlank())
    }
    assertTrue(
      "the skip must be visible in diagnostics: ${loggedLines()}",
      loggedLines().any { it.contains("request SKIPPED") }
    )
  }

  // (4) local deterministic command still executes ----------------------------

  @Test
  fun aCommandThatRanLocallyReportsItsRealResultEvenWhileTheCloudIsDown() = runBlocking {
    responseCode = 429
    responseBody = quotaExhaustedBody
    say("Hi D-VEX") // spends the quota
    val callsBefore = requestCount.get()

    val alarmResult = DvexToolResult(
      DvexToolStatus.SUCCESS,
      "set_alarm",
      "Alarm set for 6:30 AM.",
      "Alarm set for 6:30 AM."
    )
    val reply = generator.generateResponse(
      intent = DvexIntent.SetAlarm(hour = 6, minute = 30, message = null),
      toolResult = alarmResult,
      language = DetectedLanguage.ENGLISH,
      userInput = "set an alarm for 6:30 in the morning",
      context = null,
      memoryHint = null
    )

    assertEquals(
      "a verified local result must never be replaced by outage chit-chat",
      "Alarm set for 6:30 AM.",
      reply
    )
    assertEquals("a local command needs no cloud call at all", callsBefore, requestCount.get())
  }

  @Test
  fun aConfirmationPromptStillReachesTheUserWhileTheCloudIsDown() = runBlocking {
    // Safety must never depend on an AI call succeeding: the confirmation gate is
    // control logic and is spoken from the tool layer.
    responseCode = 429
    responseBody = quotaExhaustedBody
    say("Hi D-VEX")

    val confirmation = DvexToolResult(
      status = DvexToolStatus.CONFIRMATION_REQUIRED,
      toolName = "call_contact",
      message = "Call Amma?",
      spokenText = "Call Amma?",
      confirmationPrompt = "Call Amma?"
    )
    val reply = generator.generateResponse(
      intent = DvexIntent.CallContact("Amma"),
      toolResult = confirmation,
      language = DetectedLanguage.ENGLISH,
      userInput = "call amma",
      context = null,
      memoryHint = null
    )

    assertEquals("Call Amma?", reply)
  }

  // (5) cloud-only question stays honest -------------------------------------

  @Test
  fun aQuestionThatNeedsTheCloudModelStaysHonestAndInventsNothing() = runBlocking {
    responseCode = 429
    responseBody = quotaExhaustedBody

    val reply = say("why does the moon look different every month")

    assertNotEquals(oldBlanketFallback, reply)
    assertTrue(
      "it must say the cloud assistant is unavailable: $reply",
      reply.contains("temporarily unavailable", ignoreCase = true)
    )
    assertTrue(
      "it must also say what still works, so D-VEX is not useless: $reply",
      reply.contains("still work", ignoreCase = true)
    )
    // No fabricated specifics: no invented cause, no invented dates, no fake source.
    listOf("because", "gravity", "orbit takes", "according to").forEach { fabrication ->
      assertFalse(
        "D-VEX must not invent an answer ($fabrication): $reply",
        reply.contains(fabrication, ignoreCase = true)
      )
    }
  }

  @Test
  fun theLocalBrainDoesNotClaimGeminiAnsweredWhenTheCloudIsDown() = runBlocking {
    responseCode = 429
    responseBody = quotaExhaustedBody
    say("Hi D-VEX")

    val logs = loggedLines()
    assertTrue(
      "the local line must be labelled as locally produced: $logs",
      logs.any { it.contains("not cloud-generated") }
    )
  }

  // (6) the gate can return to available --------------------------------------

  @Test
  fun theGateReopensAfterItsCooldownAndGeminiIsAskedAgain() = runBlocking {
    responseCode = 429
    responseBody = quotaExhaustedBody
    say("Hi D-VEX")
    assertEquals(AiAvailability.QUOTA_EXHAUSTED, quotaState.current)

    // Inside the cooldown the gate stays shut...
    now += 10_000L
    assertEquals(AiAvailability.QUOTA_EXHAUSTED, quotaState.current)
    say("still there?")
    assertEquals(1, requestCount.get())

    // ...and once it elapses, D-VEX probes the cloud exactly once more.
    now += 51_000L
    assertEquals(AiAvailability.AVAILABLE, quotaState.current)
    responseCode = 200
    responseBody = geminiReply("Back online. What do you need?")

    val reply = say("Hi D-VEX")

    assertEquals("Back online. What do you need?", reply)
    assertEquals("exactly one probe after the cooldown", 2, requestCount.get())
    assertEquals(AiAvailability.AVAILABLE, quotaState.current)
  }

  @Test
  fun aTransientUpstreamFaultClosesTheGateOnlyBriefly() = runBlocking {
    responseCode = 503
    responseBody = """{"error":{"code":503,"status":"UNAVAILABLE","message":"high demand"}}"""
    say("Hi D-VEX")
    assertEquals(AiAvailability.TEMPORARILY_UNAVAILABLE, quotaState.current)

    now += 5_001L
    assertEquals(AiAvailability.AVAILABLE, quotaState.current)
  }

  @Test
  fun theGateCanBeResetByHandWithoutWaiting() {
    quotaState.onQuotaExhausted(null)
    assertFalse(quotaState.isCloudUsable())

    quotaState.reset()

    assertTrue(quotaState.isCloudUsable())
    assertEquals(AiAvailability.AVAILABLE, quotaState.current)
  }

  @Test
  fun aRejectedRequestDoesNotSilenceTheCloudAiForever() {
    // A bad model id or a malformed body is a BUG, not an outage: the gate must stay
    // open so the next turn still tries, instead of silently disabling Gemini for
    // the rest of the process.
    quotaState.onTemporaryFailure("x")
    assertFalse(quotaState.isCloudUsable())
    // BAD_REQUEST / AUTH / MODEL_NOT_FOUND are deliberately never written to the gate.
    assertEquals(AiAvailability.TEMPORARILY_UNAVAILABLE, quotaState.current)
    quotaState.reset()
    assertTrue(quotaState.isCloudUsable())
  }

  // (7) the credential never leaks -------------------------------------------

  @Test
  fun noFailurePathEverLogsTheApiKey() = runBlocking {
    // Success, quota exhaustion and an auth failure in one go.
    responseBody = geminiReply("ok")
    say("Hi D-VEX")

    responseCode = 429
    responseBody = quotaExhaustedBody
    say("and now?")

    responseCode = 401
    responseBody = """{"error":{"code":401,"message":"API key not valid: $testKey"}}"""
    say("still there?")

    val logs = loggedLines()
    assertTrue("diagnostics must exist to assert against", logs.isNotEmpty())
    assertFalse("API KEY LEAKED", logs.any { it.contains(testKey) })
    assertFalse("legacy key shape leaked", logs.any { Regex("AIza[0-9A-Za-z_-]{10,}").containsMatchIn(it) })
    assertFalse("new key shape leaked", logs.any { Regex("AQ\\.[0-9A-Za-z_.-]{10,}").containsMatchIn(it) })
    assertTrue(
      "the reason for gating must still be diagnosable: $logs",
      logs.any { it.contains("QUOTA_EXHAUSTED") || it.contains("category=AUTH") }
    )
  }
}
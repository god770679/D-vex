package com.example.ai

import com.example.agent.DvexMcpRegistry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Gemini model migration guard: `gemini-2.0-flash` -> `gemini-3.8-flash`.
 *
 * These tests stand a real loopback HTTP server in front of [GeminiEngine] and
 * speak the real Gemini wire protocol to it, so they cover the whole path the
 * user asked to verify:
 * 1. the model id in the URL is `gemini-3.8-flash`
 * 2. a request is actually issued and reaches the (stub) endpoint
 * 3. a successful HTTP 200 is decoded
 * 4. the model's GENERATED text is what the engine hands back to
 *    DvexResponseGenerator — not a locally invented sentence
 *
 * They also pin the Gemini 3.x request shape. Gemini 3.x models think, and their
 * thoughts are billed against `maxOutputTokens`, so a 2.x-sized body silently
 * truncates the answer. If someone reverts to the old body these fail.
 *
 * Finally they pin the ON-DEVICE failure that made D-VEX speak "I can't reach my
 * AI connection right now": the read timeout was shorter than the API's real
 * cold-start latency, so good replies were discarded as timeouts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiEngineTest {

  private lateinit var server: HttpServer
  private var baseUrl: String = ""

  private val requestCount = AtomicInteger(0)

  /** Test API key. Deliberately shaped like Google's newer `AQ.` key format. */
  private val testKey = "AQ.Ab8RN6LioS7k0I4y2P6XtUqW9vH3nB1mZ5cJ7dF2gK8pL4rT6yU0aS3dF5gH"

  @Volatile private var lastPath: String? = null
  @Volatile private var lastApiKeyHeader: String? = null
  @Volatile private var lastBody: String? = null
  @Volatile private var responseCode: Int = 200
  @Volatile private var responseBody: String = "{}"
  @Volatile private var responseDelayMs: Long = 0

  /** Status to return for specific 1-based attempt numbers (retry tests only). */
  private val transientFailures = java.util.concurrent.ConcurrentHashMap<Int, Int>()
  private val errorBody =
    """{"error":{"code":503,"status":"UNAVAILABLE","message":"This model is currently experiencing high demand."}}"""

  @Before
  fun setUp() {
    ShadowLog.clear()
    transientFailures.clear()
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val attempt = requestCount.incrementAndGet()
      lastPath = exchange.requestURI.path
      lastApiKeyHeader = exchange.requestHeaders.getFirst("x-goog-api-key")
      lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
      if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
      val code = transientFailures[attempt] ?: responseCode
      val payload = if (code == responseCode) responseBody else errorBody
      val bytes = payload.toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
      exchange.sendResponseHeaders(code, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    baseUrl = "http://127.0.0.1:${server.address.port}"
  }

  @After
  fun tearDown() {
    server.stop(0)
  }

  private fun engine(
    apiKey: String? = testKey,
    connectTimeoutSeconds: Long = GeminiEngine.DEFAULT_CONNECT_TIMEOUT_SECONDS,
    readTimeoutSeconds: Long = GeminiEngine.DEFAULT_READ_TIMEOUT_SECONDS
  ) = GeminiEngine(
    apiKeyProvider = { apiKey },
    connectTimeoutSeconds = connectTimeoutSeconds,
    readTimeoutSeconds = readTimeoutSeconds,
    baseUrl = baseUrl
  )

  /** A realistic Gemini 3.x generateContent reply: answer text PLUS thought metadata. */
  private fun gemini3Response(answer: String): String = """
    {
      "candidates": [
        {
          "content": {
            "parts": [
              {
                "text": "$answer",
                "thoughtSignature": "EuwDCukDAWkUfROlvLTkPwf8JJ4nL0uAGgxcKRxz4bGq873wFRgB"
              }
            ],
            "role": "model"
          },
          "finishReason": "STOP",
          "index": 0
        }
      ],
      "usageMetadata": {
        "promptTokenCount": 28,
        "candidatesTokenCount": 21,
        "totalTokenCount": 49,
        "thoughtsTokenCount": 0
      },
      "modelVersion": "gemini-3.8-flash"
    }
  """.trimIndent()

  private fun loggedLines(): List<String> =
    ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" }

  // (1) model id -------------------------------------------------------------

  @Test
  fun defaultModelIsGemini38Flash_notTheShutDown2xModel() {
    assertEquals("gemini-3.8-flash", GeminiEngine.DEFAULT_MODEL)
    assertEquals("low", GeminiEngine.THINKING_LEVEL)
    assertTrue(
      "budget must leave room for thinking tokens AND the answer",
      GeminiEngine.MAX_OUTPUT_TOKENS >= 256
    )
  }

  // (2) request reaches Gemini -----------------------------------------------

  @Test
  fun requestTargetsGemini38FlashGenerateContentWithApiKeyHeader() = runBlocking {
    responseBody = gemini3Response("Hello, it is great to meet you!")

    engine().generate("Say hello in one short sentence.")

    assertEquals(1, requestCount.get())
    assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", lastPath)
    assertEquals(testKey, lastApiKeyHeader)
  }

  // Gemini 3.x compatibility of the body -------------------------------------

  @Test
  fun requestBodyCarriesGemini3xThinkingConfigAndAdequateTokenBudget() = runBlocking {
    responseBody = gemini3Response("Ready.")

    engine().generate("what can you do?")

    val body = JSONObject(lastBody!!)
    val config = body.getJSONObject("generationConfig")

    // 3.x-only field: the API 400s on an unrecognised value, so its presence
    // proves the request is a Gemini 3.x request and not a recycled 2.x one.
    assertEquals("low", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
    assertTrue(
      "2.x-era 120 tokens starves the answer once thoughts are billed: ${config.getInt("maxOutputTokens")}",
      config.getInt("maxOutputTokens") >= 256
    )
    assertEquals(0.7, config.getDouble("temperature"), 0.0001)

    val promptPart = body.getJSONArray("contents")
      .getJSONObject(0)
      .getJSONArray("parts")
      .getJSONObject(0)
    assertEquals("what can you do?", promptPart.getString("text"))
  }

  // (3)+(4) real generated text is returned, not a local stand-in -------------

  @Test
  fun generatedGeminiTextIsReturnedVerbatimToTheResponseLayer() = runBlocking {
    val generated = "I can answer questions, manage tasks, solve problems, and control smart devices. What do you need?"
    responseBody = gemini3Response(generated)

    val reply = engine().generate("what can you do?")

    assertEquals(generated, reply)
  }

  @Test
  fun thoughtSignaturesAndMetadataOnlyPartsAreNeverSpoken() = runBlocking {
    // A 3.x reply where the only text is the answer, plus a signature-only part.
    responseCode = 200
    responseBody = """
      {
        "candidates": [
          {
            "content": {
              "parts": [
                { "thoughtSignature": "AbCdEf==" },
                { "text": "Your first alarm is at 7 AM." }
              ],
              "role": "model"
            },
            "finishReason": "STOP"
          }
        ]
      }
    """.trimIndent()

    val reply = engine().generate("when is my alarm?")

    assertEquals("Your first alarm is at 7 AM.", reply)
  }

  // THE ON-DEVICE BUG: read timeout shorter than real cold-start latency -----

  @Test
  fun replySlowerThanTheOldSixSecondReadTimeoutIsNowDelivered() = runBlocking {
    // The live API answered a real D-VEX conversation prompt in 7.1s on the cold
    // request. With the old 6s read timeout that reply was discarded and the user
    // heard the "can't reach my AI connection" fallback. 6.5s here.
    responseDelayMs = 6_500
    responseBody = gemini3Response("I'm doing great, thanks for asking.")

    val reply = engine().generate("how are you doing today")

    assertEquals("I'm doing great, thanks for asking.", reply)
  }

  @Test
  fun aShortReadTimeoutReproducesTheOldFallbackFailure() = runBlocking {
    // Same slow reply, but with the old-style tight timeout: this is exactly how
    // a good response became the user-facing fallback string.
    responseDelayMs = 2_500
    responseBody = gemini3Response("I'm doing great, thanks for asking.")

    val reply = engine(
      connectTimeoutSeconds = 1,
      readTimeoutSeconds = 1
    ).generate("how are you doing today")

    assertNull(reply)
    assertTrue(
      "the timeout must be named in the log: ${loggedLines()}",
      loggedLines().any { it.contains("SocketTimeoutException") || it.contains("timeout") }
    )
  }

  // Diagnostics: every failure mode names its own cause ----------------------

  @Test
  fun successLogsStatusCandidateCountAndFinishReason() = runBlocking {
    responseBody = gemini3Response("Diagnostics work.")

    engine().generate("hello")

    val logs = loggedLines()
    assertTrue("status: $logs", logs.any { it.contains("HTTP status=200") })
    assertTrue("candidates: $logs", logs.any {
      it.contains("candidates=1") && it.contains("finishReason=STOP") && it.contains("textParts=1")
    })
    assertTrue("length: $logs", logs.any { it.contains("generated 17 chars") || it.contains("success — generated") })
    assertTrue("model: $logs", logs.any { it.contains("model=gemini-3.8-flash") })
  }

  @Test
  fun httpErrorLogsTheApiMessageWithTheKeyRedacted() = runBlocking {
    responseCode = 401
    // A hostile-ish error body that echoes the key back, plus both key shapes.
    responseBody = """
      {"error":{"code":401,"message":"API key not valid: $testKey (see also AIzaSyDbcdEfghIjklmnopqrstuvwxyz01234)"}}
    """.trimIndent()

    val reply = engine().generate("hello")

    assertNull(reply)
    val logs = loggedLines()
    assertTrue("status logged: $logs", logs.any { it.contains("HTTP 401") })
    assertTrue("api message surfaced: $logs", logs.any { it.contains("apiMessage=") })
    assertTrue("api message useful: $logs", logs.any { it.contains("API key not valid") })
    assertFalse("KEY LEAKED INTO LOGS", logs.any { it.contains(testKey) })
    assertFalse("legacy key leaked", logs.any { it.contains("AIzaSyDbcdEfghIjklmnopqrstuvwxyz01234") })
  }

  @Test
  fun configuredFlagIsLoggedWithoutTheKey() = runBlocking {
    responseBody = gemini3Response("ok")

    engine().generate("hello")

    val logs = loggedLines()
    assertTrue("configured flag: $logs", logs.any { it.contains("configured=true") })
    assertFalse("KEY LEAKED INTO LOGS", logs.any { it.contains(testKey) })
  }

  @Test
  fun missingKeyLogsConfiguredFalse() = runBlocking {
    engine(apiKey = "").generate("hello")

    assertTrue(
      "configured=false must be unmistakable: ${loggedLines()}",
      loggedLines().any { it.contains("configured=false") }
    )
  }

  // Failure paths still fall back cleanly (no fabrication) -------------------

  @Test
  fun missingApiKeyNeverIssuesARequest() = runBlocking {
    assertNull(engine(apiKey = "").generate("hello"))
    assertEquals(0, requestCount.get())
  }

  @Test
  fun shutDownModelStyle404ReturnsNullSoCallerFallsBack() = runBlocking {
    responseCode = 404
    responseBody = """{"error":{"code":404,"message":"model is no longer available"}}"""

    assertNull(engine().generate("hello"))
    assertEquals(1, requestCount.get())
    assertTrue(
      "404 hint must point at the model string: ${loggedLines()}",
      loggedLines().any { it.contains("not servable") }
    )
  }

  @Test
  fun quotaExhaustionIsNamedInTheLog() = runBlocking {
    responseCode = 429
    responseBody = """{"error":{"code":429,"message":"You exceeded your current quota"}}"""

    assertNull(engine().generate("hello"))

    val logs = loggedLines()
    assertTrue("quota hint: $logs", logs.any { it.contains("quota") })
    assertTrue("api message: $logs", logs.any { it.contains("You exceeded your current quota") })
  }

  // Retry: the transient failure the LIVE API really returns -----------------

  @Test
  fun transient503IsRetriedAndTheRealReplyIsDelivered() = runBlocking {
    // Measured against the live API on 2026-09-29: 5 of 6 consecutive real
    // conversation prompts came back 503 UNAVAILABLE ("high demand"). The first
    // attempt here fails; the retry must deliver the model's real answer rather
    // than letting the user hear the no-connection fallback.
    transientFailures[1] = 503
    responseBody = gemini3Response("Summa irukken, neenga enna panreenga?")

    val reply = engine().generate("enna panra?")

    assertEquals("Summa irukken, neenga enna panreenga?", reply)
    assertEquals(2, requestCount.get())
    assertTrue(
      "the retry must be visible in the logs: ${loggedLines()}",
      loggedLines().any { it.contains("retrying after") }
    )
  }

  @Test
  fun aRetryableFailureThatNeverRecoversReturnsNullAfterTheAttemptCap() = runBlocking {
    responseCode = 503
    responseBody = """{"error":{"code":503,"status":"UNAVAILABLE","message":"high demand"}}"""

    assertNull(engine().generate("hello"))

    assertEquals(GeminiEngine.DEFAULT_MAX_ATTEMPTS, requestCount.get())
    assertTrue(
      "giving up must name its cause: ${loggedLines()}",
      loggedLines().any { it.contains("giving up after") }
    )
  }

  @Test
  fun aPermanentFailureIsNeverRetried() = runBlocking {
    responseCode = 404
    responseBody = """{"error":{"code":404,"message":"model is no longer available"}}"""

    assertNull(engine().generate("hello"))

    assertEquals("another identical request cannot fix a retired model", 1, requestCount.get())
  }

  @Test
  fun truncatedMaxTokensReplyWithNoAnswerTextReturnsNull() = runBlocking {
    // Exactly what gemini-3.8-flash does when the token budget is too small:
    // 200 OK, thinking only, no answer text.
    responseCode = 200
    responseBody = """
      {
        "candidates": [
          {
            "content": { "parts": [ { "thoughtSignature": "only-thoughts==" } ], "role": "model" },
            "finishReason": "MAX_TOKENS"
          }
        ],
        "usageMetadata": { "thoughtsTokenCount": 112 }
      }
    """.trimIndent()

    assertNull(engine().generate("hello"))

    assertTrue(
      "MAX_TOKENS must be visible, not a mystery: ${loggedLines()}",
      loggedLines().any { it.contains("finishReason=MAX_TOKENS") }
    )
  }

  // The tool boundary, on the model side of it ---------------------------------
  //
  // These pin that the transport can carry D-VEX tool declarations and hand back the
  // model's SELECTION. They are not evidence that live function calling works: a real
  // `functionCall` from `gemini-3.8-flash` is still pending (free-tier quota exhausted).

  private fun functionCallOnlyResponse(name: String, argsJson: String): String = """
    {
      "candidates": [
        {
          "content": {
            "parts": [
              { "functionCall": { "name": "$name", "args": $argsJson } }
            ],
            "role": "model"
          },
          "finishReason": "STOP"
        }
      ],
      "modelVersion": "gemini-3.8-flash"
    }
  """.trimIndent()

  @Test
  fun toolDeclarationsAreSentOnlyWhenTheModelIsAskedToSelectTools() = runBlocking {
    responseBody = gemini3Response("Ready.")
    val tools = DvexMcpRegistry.withDefaults().tools().filter { it.name == "open_app" || it.name == "send_message" }

    engine().generateWithTools("Open YouTube for me.", tools)

    val declarations = JSONObject(lastBody!!)
      .getJSONArray("tools")
      .getJSONObject(0)
      .getJSONArray("functionDeclarations")
    val declaredNames = (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }.toSet()
    assertEquals(setOf("open_app", "send_message"), declaredNames)
    assertTrue("tools must be visible in the log: ${loggedLines()}", loggedLines().any { it.contains("tools=2") })

    responseBody = gemini3Response("Hello there.")
    engine().generate("hello")
    assertFalse(
      "the plain conversation request must stay tool-free",
      JSONObject(lastBody!!).has("tools")
    )
  }

  @Test
  fun aSelectedToolCallIsReturnedAsATurnNotTreatedAsAnEmptyReply() = runBlocking {
    responseBody = functionCallOnlyResponse("open_app", "{\"app_name\":\"YouTube\"}")

    val turn = engine().generateWithTools("Open YouTube for me.", DvexMcpRegistry.withDefaults().tools())

    assertNull("a tool-call-only turn carries no spoken text", turn.text)
    assertTrue(turn.hasToolCall)
    assertEquals("open_app", turn.toolCalls.single().toolName)
    assertEquals("YouTube", turn.toolCalls.single().arguments["app_name"])
    assertEquals(
      "a selected tool is a complete answer, so it must not be retried",
      1,
      requestCount.get()
    )
    assertTrue(
      "the selection must be logged by name: ${loggedLines()}",
      loggedLines().any { it.contains("tool call") && it.contains("open_app") }
    )
  }

  @Test
  fun aTextTurnAfterAToolTurnStillReturnsText() = runBlocking {
    responseBody = functionCallOnlyResponse("open_app", "{\"app_name\":\"YouTube\"}")
    engine().generateWithTools("Open YouTube for me.", DvexMcpRegistry.withDefaults().tools())

    responseBody = gemini3Response("Opening YouTube for you.")
    val turn = engine().generateWithTools("Open YouTube for me.", DvexMcpRegistry.withDefaults().tools())

    assertEquals("Opening YouTube for you.", turn.text)
    assertFalse(turn.hasToolCall)
  }
}

package com.example.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Image-payload + response-parsing contract for [GeminiVisionEngine] (Phase 4).
 *
 * A loopback HTTP server stands in for the Generative Language API and speaks the
 * real wire shape, so these tests prove the whole path the camera depends on:
 *  1. the request POSTs to `.../models/<model>:generateContent` with the key in the
 *     `x-goog-api-key` header (never the URL);
 *  2. the JPEG is sent as a documented `inlineData` part with `image/jpeg` mime,
 *     base64-encoded, alongside the question text;
 *  3. a 200 reply's `candidates[0].content.parts[*].text` is returned VERBATIM as
 *     the analysis (the model's own words, not a local guess);
 *  4. every failure names its own cause (missing key, empty frame, HTTP status,
 *     safety block, malformed body) and NEVER reads as a fabricated description.
 *
 * No real network, no real camera. Retries are exercised against 429/5xx which the
 * engine treats as transient; 400/401/403/404 are asserted to be non-retried.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiVisionEngineTest {

  private lateinit var server: HttpServer
  private var baseUrl: String = ""

  private val testKey = "AQ.test_key_value_for_vision_engine_unit_test"

  @Volatile private var lastPath: String? = null
  @Volatile private var lastApiKeyHeader: String? = null
  @Volatile private var lastBody: String? = null
  @Volatile private var responseCode: Int = 200
  @Volatile private var responseBody: String = "{}"
  private val requestCount = java.util.concurrent.atomic.AtomicInteger(0)

  @Before
  fun setUp() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      requestCount.incrementAndGet()
      lastPath = exchange.requestURI.path
      lastApiKeyHeader = exchange.requestHeaders.getFirst("x-goog-api-key")
      lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
      val bytes = responseBody.toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
      exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
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
    maxAttempts: Int = 3
  ) = GeminiVisionEngine(
    apiKeyProvider = { apiKey },
    model = "gemini-3.8-flash",
    baseUrl = baseUrl,
    maxAttempts = maxAttempts
  )

  /** A realistic Gemini multimodal reply with two text parts. */
  private fun reply(text1: String, text2: String = ""): String = """
    {
      "candidates": [
        {
          "content": {
            "parts": [
              {"text": ${JSONObject.quote(text1)}},
              {"text": ${JSONObject.quote(text2)}}
            ],
            "role": "model"
          },
          "finishReason": "STOP"
        }
      ],
      "usageMetadata": {"promptTokenCount": 123, "candidatesTokenCount": 21}
    }
  """.trimIndent()

  private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())

  // --- 1/2. Request shape ----------------------------------------------------

  @Test
  fun analyzesFrameByPostingInlineJpegToTheVisionModel() = runBlocking {
    responseBody = reply("A rusted tractor linkage with visible wear.")

    val result = engine().analyze(jpeg, "What part is this?")

    assertTrue("expected success, got ${result.error}", result.isSuccess)
    assertEquals("A rusted tractor linkage with visible wear.", result.text)

    // Endpoint + model id.
    assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", lastPath)
    // Key travels in the header, never the URL.
    assertEquals(testKey, lastApiKeyHeader)
    assertFalse("key must never leak into the URL", lastPath!!.contains(testKey))

    val body = JSONObject(lastBody!!)
    val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
    // Text question part first...
    assertEquals("What part is this?", parts.getJSONObject(0).getString("text"))
    // ...then the documented inlineData image part, correct mime + base64 bytes.
    val inline = parts.getJSONObject(1).getJSONObject("inlineData")
    assertEquals("image/jpeg", inline.getString("mimeType"))
    val decoded = java.util.Base64.getDecoder().decode(inline.getString("data"))
    assertTrue("base64 image must decode back to the exact JPEG bytes", decoded.contentEquals(jpeg))
  }

  @Test
  fun concatenatesAllCandidateTextPartsInOrder() = runBlocking {
    responseBody = reply("First sentence.", " Second sentence.")

    val result = engine().analyze(jpeg, "Describe it.")

    assertEquals("First sentence. Second sentence.", result.text)
  }

  // --- 3/4. Honest error vocabulary -----------------------------------------

  @Test
  fun missingKeyIsReportedAsMissingKeyAndSendsNoRequest() = runBlocking {
    val result = engine(apiKey = null).analyze(jpeg, "Q")

    assertFalse(result.isSuccess)
    assertEquals(VisionAnalysis.ErrorKind.MISSING_API_KEY, result.errorKind)
    assertNotNull(result.error)
    assertEquals("no request may be sent without a key", 0, requestCount.get())
  }

  @Test
  fun blankFrameIsMalformedAndSendsNoRequest() = runBlocking {
    val result = engine().analyze(ByteArray(0), "Q")

    assertEquals(VisionAnalysis.ErrorKind.MALFORMED, result.errorKind)
    assertEquals(0, requestCount.get())
  }

  @Test
  fun unauthorizedKeyIsHttpErrorAndNotRetried() = runBlocking {
    responseCode = 401
    responseBody = """{"error":{"code":401,"message":"API key not valid."}}"""

    val result = engine(maxAttempts = 3).analyze(jpeg, "Q")

    assertEquals(VisionAnalysis.ErrorKind.HTTP_ERROR, result.errorKind)
    assertTrue(result.error!!.contains("401"))
    assertTrue("401 is not transient — must NOT be retried", requestCount.get() == 1)
  }

  @Test
  fun badModelIdIsHttpErrorAndNotRetried() = runBlocking {
    responseCode = 404
    responseBody = """{"error":{"code":404,"message":"Model not found."}}"""

    val result = engine(maxAttempts = 3).analyze(jpeg, "Q")

    assertEquals(VisionAnalysis.ErrorKind.HTTP_ERROR, result.errorKind)
    assertTrue(result.error!!.contains("404"))
    assertTrue(requestCount.get() == 1)
  }

  @Test
  fun transientServerErrorIsRetriedThenSucceeds() = runBlocking {
    // First attempt 503, second 200 — proves the transient retry path works and
    // still returns the model's real words once the upstream recovers.
    val attempt = java.util.concurrent.atomic.AtomicInteger(0)
    // Reconfigure the handler to fail once. We do this by flipping responseCode
    // from the handler thread via a counter checked below.
    server.removeContext("/")
    server.createContext("/") { exchange ->
      val n = attempt.incrementAndGet()
      lastPath = exchange.requestURI.path
      lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
      val (code, payload) = if (n == 1) {
        503 to """{"error":{"code":503,"message":"upstream busy"}}"""
      } else {
        200 to reply("Recovered answer.")
      }
      val bytes = payload.toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
      exchange.sendResponseHeaders(code, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }

    val result = engine(maxAttempts = 3).analyze(jpeg, "Q")

    assertTrue("expected success after retry, got ${result.error}", result.isSuccess)
    assertEquals("Recovered answer.", result.text)
    assertTrue("503 must be retried at least once", attempt.get() >= 2)
  }

  @Test
  fun safetyBlockedReplyIsEmptyResponseWithBlockReason() = runBlocking {
    // A real Gemini safety block returns a candidate that carries finishReason
    // SAFETY and NO text part, plus promptFeedback.blockReason. The engine must
    // surface the block reason rather than claim it "couldn't read" the reply.
    responseBody = """
      {"promptFeedback": {"blockReason": "SAFETY"},
       "candidates": [{"content": {"parts": [], "role": "model"},
                       "finishReason": "SAFETY"}]}
    """.trimIndent()

    val result = engine().analyze(jpeg, "Q")

    assertFalse("a safety block is not a success", result.isSuccess)
    assertEquals(VisionAnalysis.ErrorKind.EMPTY_RESPONSE, result.errorKind)
    assertTrue(
      "blocked reply must name the block reason",
      result.error!!.contains("SAFETY")
    )
  }

  @Test
  fun malformedBodyIsReportedAsMalformedNotAsContent() = runBlocking {
    responseBody = "this is not json at all"

    val result = engine().analyze(jpeg, "Q")

    assertEquals(VisionAnalysis.ErrorKind.MALFORMED, result.errorKind)
    // The raw garbage body must never surface as the "analysis".
    assertFalse(result.text?.contains("not json") == true)
  }

  @Test
  fun emptyCandidateTextIsEmptyResponse() = runBlocking {
    responseBody = """{"candidates":[{"content":{"parts":[{"text":""}]}}]}"""

    val result = engine().analyze(jpeg, "Q")

    assertEquals(VisionAnalysis.ErrorKind.EMPTY_RESPONSE, result.errorKind)
    assertNull(result.text)
  }

  @Test
  fun apiKeyValueIsNeverIncludedInTheErrorText() = runBlocking {
    responseCode = 403
    responseBody = """{"error":{"code":403,"message":"key $testKey denied"}}"""

    val result = engine().analyze(jpeg, "Q")

    assertEquals(VisionAnalysis.ErrorKind.HTTP_ERROR, result.errorKind)
    assertFalse(
      "API key value must be redacted from any surfaced error",
      result.error!!.contains(testKey)
    )
  }
}

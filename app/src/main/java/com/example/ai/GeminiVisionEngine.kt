package com.example.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Outcome of one capture-and-analyze call. Honest by construction: [text] and
 * [error] are mutually exclusive — a non-null [text] is the model's own words
 * about the image, and a non-null [error] names why no analysis exists.
 */
data class VisionAnalysis(
  val text: String? = null,
  val error: String? = null,
  val errorKind: ErrorKind? = null
) {
  enum class ErrorKind {
    /** GEMINI_API_KEY missing/blank in this build. */
    MISSING_API_KEY,
    /** Connect/read/call timeout or IO failure. */
    NETWORK,
    /** Non-2xx from the API (status recorded in the message). */
    HTTP_ERROR,
    /** 2xx but the response carried no usable text (safety block, empty body). */
    EMPTY_RESPONSE,
    /** Response body was not the JSON shape we expect. */
    MALFORMED
  }

  val isSuccess: Boolean get() = text != null && error == null
}

/**
 * Gemini VISION engine for D-VEX camera analysis (Phase 4).
 *
 * Sends ONE captured JPEG + ONE question to the same `generateContent` endpoint
 * the conversation engine already uses ([GeminiEngine]), with an inline
 * `inlineData` part — the documented multimodal input shape of the Generative
 * Language API. The model ([GeminiEngine.DEFAULT_MODEL]) is a Gemini 3.x flash
 * model: natively multimodal (images are first-class input) and the id the live
 * endpoint currently serves (gemini-2.0-flash is retired). This is a REAL
 * vision-capable model call — never a fake, never a cached guess.
 *
 * Deliberately a separate class from [GeminiEngine]:
 * - Different request shape (image inline data vs text-only/tools), different
 *   timeout budget (short analysis), different failure vocabulary ([VisionAnalysis]).
 * - The conversation engine stays byte-for-byte unchanged (its request shape is
 *   pinned by GeminiEngineTest).
 *
 * Honesty contract:
 * - Returns exactly one of: analysis text, or an error naming its own cause.
 * - Never retries 400/401/403/404 (a bad key or bad model id cannot fix itself);
 *   retries transient 429/5xx twice with short backoff (the upstream API is
 *   intermittently busy — same evidence as [GeminiEngine]).
 * - The API key VALUE is never logged; error text is redacted first.
 *
 * Reliability contract (shared with [AiEngine]): hard connect/read/call
 * timeouts so a vision call can never hang a tool request.
 */
class GeminiVisionEngine(
  private val apiKeyProvider: () -> String?,
  connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
  readTimeoutSeconds: Long = DEFAULT_READ_TIMEOUT_SECONDS,
  private val model: String = GeminiEngine.DEFAULT_MODEL,
  private val baseUrl: String = GeminiEngine.DEFAULT_BASE_URL,
  private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {

  private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
    .callTimeout(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()

  /**
   * Analyzes one captured JPEG frame against [question] and returns the model's
   * plain-text answer, or an error that names why no answer exists. Always
   * suspends on [Dispatchers.IO]; bounded by the client timeouts and the retry
   * budget.
   *
   * @param jpegBytes frame bytes as captured by [com.example.vision.DvexVisionManager]
   * @param question the user's (or tool's) question about the frame
   */
  suspend fun analyze(jpegBytes: ByteArray, question: String): VisionAnalysis =
    withContext(Dispatchers.IO) {
      val apiKey = apiKeyProvider()
      if (apiKey.isNullOrBlank()) {
        Log.w(TAG, "[D-VEX][VISION-AI] configured=false — GEMINI_API_KEY missing/blank; " +
          "capture cannot be analyzed in this build")
        return@withContext VisionAnalysis(
          error = "No AI key configured in this build — frame analysis is unavailable.",
          errorKind = VisionAnalysis.ErrorKind.MISSING_API_KEY
        )
      }
      if (jpegBytes.isEmpty()) {
        return@withContext VisionAnalysis(
          error = "The captured frame was empty — nothing to analyze.",
          errorKind = VisionAnalysis.ErrorKind.MALFORMED
        )
      }

      val bodyJson = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().apply {
          put("role", "user")
          put("parts", JSONArray().apply {
            put(JSONObject().put("text", question))
            put(JSONObject().put("inlineData", JSONObject().apply {
              put("mimeType", "image/jpeg")
              put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            }))
          })
        }))
        put("generationConfig", JSONObject().apply {
          put("temperature", 0.2) // analysis should be factual, not creative
          put("maxOutputTokens", MAX_OUTPUT_TOKENS)
          put("thinkingConfig", JSONObject().put("thinkingLevel", THINKING_LEVEL))
        })
      }

      val url = "$baseUrl/v1beta/models/$model:generateContent"
      Log.i(TAG, "[D-VEX][VISION-AI] request started POST $model " +
        "(image=${jpegBytes.size} bytes, question=${question.length} chars, " +
        "connect=${client.connectTimeoutMillis}ms read=${client.readTimeoutMillis}ms " +
        "call=${client.callTimeoutMillis}ms, maxAttempts=$maxAttempts)")

      val request = Request.Builder()
        .url(url)
        .addHeader("x-goog-api-key", apiKey) // key in header only: never in the URL
        .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

      val deadlineMs = System.currentTimeMillis() + RETRY_TOTAL_BUDGET_MS
      var lastError: VisionAnalysis? = null
      for (attempt in 1..maxAttempts) {
        val outcome = executeOnce(request, apiKey)
        if (outcome.successText != null) {
          Log.i(TAG, "[D-VEX][VISION-AI] success — ${outcome.successText.length} chars (attempt $attempt)")
          return@withContext VisionAnalysis(text = outcome.successText)
        }
        lastError = outcome.failure
        if (!outcome.retryable) break
        val backoffMs = RETRY_BACKOFF_MS.getOrElse(attempt - 1) { RETRY_BACKOFF_MS.last() }
        val remainingMs = deadlineMs - System.currentTimeMillis()
        if (attempt == maxAttempts || remainingMs <= backoffMs) break
        Log.w(TAG, "[D-VEX][VISION-AI] retryable failure (${outcome.failure?.error}); " +
          "retrying after ${backoffMs}ms (attempt $attempt of $maxAttempts)")
        delay(backoffMs)
      }
      lastError ?: VisionAnalysis(
        error = "Frame analysis failed for an unknown reason.",
        errorKind = VisionAnalysis.ErrorKind.NETWORK
      )
    }

  /** One HTTP attempt: either answer text or an honest failure. */
  private fun executeOnce(request: Request, apiKey: String): SingleOutcome {
    val startedAt = System.currentTimeMillis()
    return try {
      client.newCall(request).execute().use { response ->
        val elapsedMs = System.currentTimeMillis() - startedAt
        if (!response.isSuccessful) {
          val apiMessage = response.body?.string().orEmpty()
          Log.w(TAG, "[D-VEX][VISION-AI] HTTP ${response.code} in ${elapsedMs}ms " +
            "apiMessage=${redact(apiMessage, apiKey).take(MAX_ERROR_LOG_CHARS)}")
          val hint = when (response.code) {
            400 -> "The vision request was rejected (bad image or bad model id)."
            401, 403 -> "The API key is not authorized for this model/project."
            404 -> "The vision model id is not servable in this project."
            429 -> "Vision quota/rate limit reached for this API key."
            in RETRYABLE_HTTP -> "The vision model is temporarily unavailable upstream."
            else -> "Vision request failed with HTTP ${response.code}."
          }
          return SingleOutcome(
            successText = null,
            failure = VisionAnalysis(
              error = "$hint (HTTP ${response.code})",
              errorKind = VisionAnalysis.ErrorKind.HTTP_ERROR
            ),
            // Only transient statuses can plausibly succeed on an identical retry.
            retryable = response.code == 429 || response.code in RETRYABLE_HTTP
          )
        }
        val raw = response.body?.string()
        if (raw.isNullOrBlank()) {
          return SingleOutcome(null, VisionAnalysis(
            error = "The vision model returned an empty response body.",
            errorKind = VisionAnalysis.ErrorKind.EMPTY_RESPONSE
          ), retryable = false)
        }
        val parsed = parseVisionReply(raw)
        if (parsed == null) {
          Log.w(TAG, "[D-VEX][VISION-AI] response parse failed (not JSON or missing candidates)")
          return SingleOutcome(null, VisionAnalysis(
            error = "The vision model returned an unreadable response.",
            errorKind = VisionAnalysis.ErrorKind.MALFORMED
          ), retryable = false)
        }
        if (parsed.isBlank()) {
          val blockReason = try {
            JSONObject(raw).optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
          } catch (e: Exception) { "" }
          Log.w(TAG, "[D-VEX][VISION-AI] 2xx but no answer text (blockReason=${blockReason.ifBlank { "none" }})")
          return SingleOutcome(null, VisionAnalysis(
            error = if (blockReason.isNotBlank()) {
              "The vision model declined this image ($blockReason)."
            } else {
              "The vision model returned no description for this frame."
            },
            errorKind = VisionAnalysis.ErrorKind.EMPTY_RESPONSE
          ), retryable = false)
        }
        SingleOutcome(successText = parsed, failure = null, retryable = false)
      }
    } catch (e: Exception) {
      Log.w(TAG, "[D-VEX][VISION-AI] exception=${e.javaClass.simpleName}: " +
        "${redact(e.message ?: "no message", apiKey)} (call=${client.callTimeoutMillis}ms)")
      SingleOutcome(null, VisionAnalysis(
        error = "Could not reach the vision model (${e.javaClass.simpleName}).",
        errorKind = VisionAnalysis.ErrorKind.NETWORK
      ), retryable = true)
    }
  }

  /** Concatenates candidates[0] answer text parts; null when shape is unexpected. */
  private fun parseVisionReply(raw: String): String? {
    return try {
      val json = JSONObject(raw)
      val candidates = json.optJSONArray("candidates") ?: return null
      val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        ?: return null
      val sb = StringBuilder()
      for (i in 0 until parts.length()) {
        val part = parts.optJSONObject(i) ?: continue
        if (!part.has("text")) continue
        sb.append(part.optString("text", ""))
      }
      sb.toString().trim()
    } catch (e: Exception) {
      null
    }
  }

  private data class SingleOutcome(
    val successText: String?,
    val failure: VisionAnalysis?,
    /** True when an identical retry could plausibly succeed (network + transient HTTP). */
    val retryable: Boolean
  )

  private fun redact(text: String, apiKey: String): String {
    var out = text
    if (apiKey.isNotBlank()) out = out.replace(apiKey, REDACTED)
    out = KEY_SHAPE_REGEX.replace(out, REDACTED)
    out = KEY_QUERY_REGEX.replace(out, "$1$REDACTED")
    return out
  }

  companion object {
    private const val TAG = "[D-VEX][VISION-AI]"

    /** Short factual answer — a vision analysis is not an essay. */
    private const val MAX_OUTPUT_TOKENS = 350

    /** Gemini 3.x thinking control; same constraint as [GeminiEngine]. */
    private const val THINKING_LEVEL = "low"

    private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 8L
    private const val DEFAULT_READ_TIMEOUT_SECONDS = 20L
    private const val DEFAULT_CALL_TIMEOUT_SECONDS = 30L
    private const val DEFAULT_MAX_ATTEMPTS = 3

    private val RETRYABLE_HTTP = setOf(500, 502, 503, 504)
    private val RETRY_BACKOFF_MS = longArrayOf(400L, 1_200L)

    /** Total reattempt budget for one vision call (well under tool timeouts). */
    private const val RETRY_TOTAL_BUDGET_MS = 20_000L

    private const val REDACTED = "***REDACTED***"
    private const val MAX_ERROR_LOG_CHARS = 300

    private val KEY_SHAPE_REGEX = Regex("""\b(AIza[0-9A-Za-z_\-]{10,}|AQ\.[0-9A-Za-z_.\-]{10,})""")
    private val KEY_QUERY_REGEX = Regex("""([?&]key=)[^&\s']+""")
  }
}

package com.example.ai

import android.util.Log
import com.example.agent.DvexMcpTool
import com.example.agent.DvexToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini LLM engine for D-VEX natural response generation.
 *
 * Talks to the Google Generative Language REST API over OkHttp (same stack the
 * project already uses in RealTimeWebService — no new dependency added).
 *
 * MODEL / API VERSION: [DEFAULT_MODEL] is a Gemini 3.x model. The 3.x series are
 * "thinking" models and are NOT drop-in compatible with a 2.x request body:
 * - Thoughts are billed against `maxOutputTokens`, so a 2.x-sized budget (120)
 *   leaves almost nothing for the spoken answer and the API answers 200 OK with
 *   finishReason=MAX_TOKENS and a truncated reply.
 * - Thinking is controlled by `generationConfig.thinkingConfig.thinkingLevel`,
 *   a 3.x-only field (the API rejects unknown values in it with HTTP 400).
 * Request shape is pinned by GeminiEngineTest so a silent revert to the 2.x body
 * cannot ship.
 *
 * TIMEOUTS: measured on the live API, a real D-VEX conversation prompt (the
 * generator's personality + rules + history, ~1.2k chars) took 7.1s on the cold
 * request and 2.2-3.9s warm. The previous read timeout of 6s therefore dropped
 * real, perfectly good replies on the floor with a SocketTimeoutException, and
 * the user heard "I can't reach my AI connection right now" for a request that
 * would have succeeded. The defaults below leave headroom for a cold TLS
 * handshake on mobile.
 *
 * Still the same single engine and the same `generateContent` endpoint D-VEX
 * always used — no second AI engine and no protocol rewrite.
 *
 * Reliability contract (see [AiEngine]):
 * - Hard connect/read/call timeouts so D-VEX never hangs waiting for the model.
 * - Retries TRANSIENT failures only (HTTP 429/5xx, timeouts, IO errors) with a
 *   short bounded backoff. The live API intermittently answers 503 UNAVAILABLE
 *   ("this model is currently experiencing high demand") for a completely valid
 *   request — measured 2026-09-29: 5 of 6 consecutive real conversation prompts
 *   came back 503 while the identical body succeeded moments later. Without a
 *   retry, one transient 503 became the user-visible "I can't reach my AI
 *   connection" fallback even though nothing was wrong with the request.
 * - Never retries permanent failures: missing key, HTTP 400/401/403/404, or a 2xx
 *   answer that carried no text.
 * - Returns null when every attempt failed: missing key, HTTP error, timeout,
 *   empty body, blank reply. The response layer owns the user-facing fallback
 *   string.
 * - Never fabricates data: prompting happens in DvexResponseGenerator, which
 *   passes only real tool results and forbids inventing numbers or facts.
 *
 * DIAGNOSTICS: every failure mode logs one line that names its own cause
 * (configured=false / HTTP status + API message / timeout / parse). The API key
 * VALUE is never logged; HTTP error bodies and exception text are redacted
 * before logging.
 */
class GeminiEngine(
  private val apiKeyProvider: () -> String?,
  connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
  readTimeoutSeconds: Long = DEFAULT_READ_TIMEOUT_SECONDS,
  private val model: String = DEFAULT_MODEL,
  private val baseUrl: String = DEFAULT_BASE_URL,
  private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) : AiEngine {

  private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
    // Whole-request ceiling. MUST stay below DvexResponseGenerator.LLM_TIMEOUT_MS
    // so this engine always gets to fail on its own terms and log the real cause
    // instead of being cancelled from outside.
    .callTimeout(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()

  /**
   * Text-only turn: the model's reply, or null when it could not be produced.
   * Unchanged behaviour — no tool declarations are sent, so the request body is
   * byte-for-byte what it has always been.
   */
  override suspend fun generate(prompt: String): String? = generateWithTools(prompt, emptyList()).text

  /**
   * THE MODEL-FACING HALF OF THE D-VEX TOOL BOUNDARY.
   *
   * Same single `generateContent` request, plus D-VEX's tool declarations when [tools]
   * is non-empty, so the model may *select* one of them. Selection is not permission:
   * the returned [AiModelTurn.toolCalls] are plain requests that must go through
   * `agent/DvexToolProtocol` for validation, permission, confirmation, execution and
   * verification. This method never runs a tool.
   *
   * A turn that carries a tool call and no text is a SUCCESS (the model asked instead
   * of talking), and it is never retried as an empty reply.
   *
   * LIVE STATUS: the declaration dialect itself was accepted by the live endpoint on
   * 2026-09-29, but a real `functionCall` has NOT been observed yet (the free-tier
   * daily quota for `gemini-3.8-flash` is exhausted). The conversation pipeline does
   * not call this yet, so nothing about the spoken experience depends on it.
   */
  override suspend fun generateWithTools(prompt: String, tools: List<DvexMcpTool>): AiModelTurn =
    withContext(Dispatchers.IO) {
    val apiKey = apiKeyProvider()
    if (apiKey.isNullOrBlank()) {
      // Single decisive diagnostic: proves at runtime whether the key actually
      // reached the APK. The key VALUE is never logged.
      Log.w(TAG, "[D-VEX][AI] configured=false — GEMINI_API_KEY missing/blank in this build; " +
        "every conversation will use the deterministic fallback. Rebuild with the key set.")
      return@withContext AiModelTurn(text = null)
    }

    Log.i(TAG, "[D-VEX][AI] configured=true model=$model " +
      "(key len=${apiKey.length}, value withheld)")

    val bodyJson = JSONObject().apply {
      put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
      put("generationConfig", JSONObject().apply {
        put("temperature", 0.7)
        // Gemini 3.x bills internal thinking against this same budget. At the
        // old 2.x value (120) a real request measured 112 thinking tokens and
        // only 4 for the answer -> 200 OK but finishReason=MAX_TOKENS and a
        // truncated "Hello, I hope". The budget must cover thoughts + a full
        // spoken sentence.
        put("maxOutputTokens", MAX_OUTPUT_TOKENS)
        // Gemini 3.x thinking control. "low" keeps the model's reasoning short:
        // it leaves the token budget to the answer and keeps the request inside
        // this engine's timeout. D-VEX replies are one or two spoken sentences,
        // so deep reasoning buys nothing here.
        put("thinkingConfig", JSONObject().put("thinkingLevel", THINKING_LEVEL))
      })
      if (tools.isNotEmpty()) {
        // Gemini REST shape: tools[] of Tool objects, each holding functionDeclarations.
        // Encoding stays in GeminiToolAdapter/GeminiToolCodec — this engine never
        // hand-rolls a declaration.
        put("tools", JSONArray().put(GeminiToolAdapter.declarations(tools)))
      }
    }

    val url = "$baseUrl/v1beta/models/$model:generateContent"
    Log.i(TAG, "[D-VEX][AI] request started POST $url " +
      "(prompt=${prompt.length} chars, connect=${client.connectTimeoutMillis}ms, " +
      "read=${client.readTimeoutMillis}ms, call=${client.callTimeoutMillis}ms, " +
      "thinkingLevel=$THINKING_LEVEL, maxOutputTokens=$MAX_OUTPUT_TOKENS, maxAttempts=$maxAttempts, " +
      "tools=${tools.size}, dialect=${if (tools.isEmpty()) "none" else GeminiToolAdapter.DIALECT})")

    val request = Request.Builder()
      .url(url)
      .addHeader("x-goog-api-key", apiKey)
      .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
      .build()

    // BOUNDED RETRY around one request. The upstream API is intermittently busy
    // (see the class comment); a retry turns a transient 503/429 into a real
    // answer instead of the fallback line. Permanent failures stop immediately, and
    // the loop is bounded by BOTH maxAttempts and RETRY_TOTAL_BUDGET_MS so this
    // engine always finishes before the response layer's outer timeout.
    val deadlineMs = System.currentTimeMillis() + RETRY_TOTAL_BUDGET_MS
    var answer: String? = null
    var selected: List<DvexToolCall> = emptyList()
    for (attempt in 1..maxAttempts) {
      val outcome = executeOnce(request, apiKey)
      // Either half of a turn counts as an answer: text, a selected tool call, or both.
      if (outcome.text != null || outcome.toolCalls.isNotEmpty()) {
        answer = outcome.text
        selected = outcome.toolCalls
        break
      }
      // Permanent (400/401/403/404, or a 2xx with no answer text): another
      // identical request cannot help, so do not spend the user's latency on it.
      if (!outcome.retryable) break

      val backoffMs = RETRY_BACKOFF_MS.getOrElse(attempt - 1) { RETRY_BACKOFF_MS.last() }
      val remainingMs = deadlineMs - System.currentTimeMillis()
      if (attempt == maxAttempts || remainingMs <= backoffMs) {
        Log.w(TAG, "[D-VEX][AI] giving up after $attempt attempt(s) — ${outcome.reason} " +
          "(model=$model, remaining=${remainingMs.coerceAtLeast(0)}ms of " +
          "${RETRY_TOTAL_BUDGET_MS}ms retry budget); response layer will use its fallback")
        break
      }
      Log.i(TAG, "[D-VEX][AI] retryable failure — ${outcome.reason}; retrying after ${backoffMs}ms " +
        "(attempt $attempt of $maxAttempts, model=$model)")
      delay(backoffMs)
    }
    AiModelTurn(text = answer, toolCalls = selected)
  }

  /**
   * Performs ONE HTTP attempt and reports whether a retry could plausibly help.
   *
   * Retryable: 429/5xx and any exception (timeouts, connection/IO failures).
   * Permanent: 400/401/403/404 (bad request / key / model id) and a 2xx reply that
   * carried no answer text — retrying those just burns the user's latency budget.
   */
  private fun executeOnce(request: Request, apiKey: String): AttemptOutcome {
    val startedAt = System.currentTimeMillis()
    return try {
      client.newCall(request).execute().use { response ->
        val elapsedMs = System.currentTimeMillis() - startedAt
        Log.i(TAG, "[D-VEX][AI] HTTP status=${response.code} in ${elapsedMs}ms (model=$model)")

        if (!response.isSuccessful) {
          // The API's own message is the fastest route to the real cause
          // ("quota exceeded" vs "API key not valid" vs "model not found").
          // Redacted before logging; the key is never printed.
          val apiMessage = response.body?.string().orEmpty()
          val hint = when (response.code) {
            400 -> " — request rejected (bad model id, bad field, or bad key)"
            401, 403 -> " — the API key is not authorized for this model/project"
            404 -> " — model id is not servable (retired or typo'd model string)"
            429 -> " — quota/rate limit for this API key (free-tier limits are low)"
            500, 502, 503, 504 -> " — Gemini temporarily unavailable upstream"
            else -> ""
          }
          Log.w(TAG, "[D-VEX][AI] exception=HTTP ${response.code} from generativelanguage.googleapis.com " +
            "(model=$model)$hint apiMessage=${redact(apiMessage, apiKey).take(MAX_ERROR_LOG_CHARS)}")
          return AttemptOutcome(
            text = null,
            retryable = response.code in RETRYABLE_STATUS_CODES,
            reason = "HTTP ${response.code}"
          )
        }

        val raw = response.body?.string() ?: run {
          Log.w(TAG, "[D-VEX][AI] empty — HTTP ok but response body missing")
          return AttemptOutcome(text = null, retryable = false, reason = "HTTP ok but no body")
        }
        val parsed = parseReply(raw)
        Log.i(TAG, "[D-VEX][AI] response parsed candidates=${parsed.candidates} parts=${parsed.parts} " +
          "textParts=${parsed.textParts} finishReason=${parsed.finishReason ?: "none"} " +
          "blockReason=${parsed.blockReason ?: "none"} " +
          "thoughtsTokenCount=${parsed.thoughtsTokenCount}")
        // Parsed through the codec, so the response dialect lives in ONE place. A
        // text-only answer yields an empty list and the turn stays exactly as before.
        val toolCalls = GeminiToolAdapter.decodeCalls(raw)
        if (parsed.text.isNullOrBlank() && toolCalls.isEmpty()) {
          Log.w(TAG, "[D-VEX][AI] empty — response had no candidate text " +
            "(model=$model, finishReason=${parsed.finishReason ?: "none"}, " +
            "blockReason=${parsed.blockReason ?: "none"}); thoughts/signatures are not answer text")
        } else if (toolCalls.isNotEmpty()) {
          Log.i(TAG, "[D-VEX][AI] model selected ${toolCalls.size} tool call(s): " +
            "${toolCalls.joinToString(", ") { it.toolName }} (selection only — D-VEX decides)")
        } else {
          Log.i(TAG, "[D-VEX][AI] success — generated ${parsed.text?.length ?: 0} chars")
        }
        AttemptOutcome(
          text = parsed.text,
          retryable = false,
          reason = if (toolCalls.isEmpty()) "no answer text" else "tool call selected",
          toolCalls = toolCalls
        )
      }
    } catch (e: Exception) {
      // Timeouts (SocketTimeoutException) and IO failures both land here.
      // The class name separates "network too slow" from "no connectivity" at a
      // glance, and both are worth one more attempt.
      Log.w(TAG, "[D-VEX][AI] exception=${e.javaClass.simpleName}: " +
        "${redact(e.message ?: "no message", apiKey)} (model=$model, " +
        "read=${client.readTimeoutMillis}ms call=${client.callTimeoutMillis}ms)")
      AttemptOutcome(text = null, retryable = true, reason = e.javaClass.simpleName)
    }
  }

  /**
   * One attempt's result: the answer text, any tool call the model selected, and whether
   * retrying could help.
   */
  private class AttemptOutcome(
    val text: String?,
    val retryable: Boolean,
    val reason: String,
    val toolCalls: List<DvexToolCall> = emptyList()
  )

  /** Diagnostics for one parsed reply. Never carries the key. */
  private data class ParsedReply(
    val text: String?,
    val candidates: Int,
    val parts: Int,
    val textParts: Int,
    val finishReason: String?,
    val blockReason: String?,
    val thoughtsTokenCount: Int
  )

  /**
   * Extracts the model's answer text from candidates[0].content.parts; null when
   * absent or blank.
   *
   * Gemini 3.x `generateContent` has no dedicated thought blocks: thought
   * signatures ride along as metadata on normal parts (`thoughtSignature`), and
   * a part may carry no `text` at all. Only real text parts are concatenated, so
   * signature-only parts can never leak into what D-VEX speaks.
   */
  private fun parseReply(raw: String): ParsedReply {
    return try {
      val json = JSONObject(raw)
      val candidates = json.optJSONArray("candidates")
      val first = candidates?.optJSONObject(0)
      val parts = first?.optJSONObject("content")?.optJSONArray("parts")
      val sb = StringBuilder()
      var textParts = 0
      if (parts != null) {
        for (i in 0 until parts.length()) {
          val part = parts.optJSONObject(i) ?: continue
          // Skip metadata-only parts (thought signatures, inline data, etc.).
          if (!part.has("text")) continue
          val text = part.optString("text", "")
          if (text.isNotEmpty()) {
            sb.append(text)
            textParts++
          }
        }
      }
      ParsedReply(
        text = sb.toString().trim().takeIf { it.isNotEmpty() },
        candidates = candidates?.length() ?: 0,
        parts = parts?.length() ?: 0,
        textParts = textParts,
        finishReason = first?.optString("finishReason")?.takeIf { it.isNotBlank() },
        blockReason = json.optJSONObject("promptFeedback")
          ?.optString("blockReason")?.takeIf { it.isNotBlank() },
        thoughtsTokenCount = json.optJSONObject("usageMetadata")
          ?.optInt("thoughtsTokenCount", 0) ?: 0
      )
    } catch (e: Exception) {
      Log.w(TAG, "Gemini reply parse failed: ${e.message}")
      ParsedReply(null, 0, 0, 0, null, null, 0)
    }
  }

  /**
   * Removes anything that could carry the credential from text about to be
   * logged: the exact key, Google key shapes (legacy `AIza…`, new `AQ.…`), and
   * `key=` query parameters.
   */
  private fun redact(text: String, apiKey: String): String {
    var out = text
    if (apiKey.isNotBlank()) out = out.replace(apiKey, REDACTED)
    out = KEY_SHAPE_REGEX.replace(out, REDACTED)
    out = KEY_QUERY_REGEX.replace(out, "$1$REDACTED")
    return out
  }

  companion object {
    private const val TAG = "[D-VEX][GEMINI]"

    /** Public Generative Language API root; overridable for tests only. */
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"

    /**
     * Gemini 3.x model. The previous value, `gemini-2.0-flash`, is SHUT DOWN:
     * the API answers `404 "This model models/gemini-2.0-flash is no longer
     * available. Please update your code to use models/gemini-3.8-flash"`.
     * Keep this a Gemini 3.x id so the thinking-config body below stays valid.
     */
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    /** Gemini 3.x thinking level; must be low/medium/high or the API 400s. */
    const val THINKING_LEVEL = "low"

    /**
     * Budget for thinking tokens + the spoken answer. 512 comfortably fits a
     * short spoken reply plus low-level thinking, and bounds worst-case latency.
     */
    const val MAX_OUTPUT_TOKENS = 512

    /**
     * Cold-start headroom. A live D-VEX conversation prompt measured 7.1s on the
     * first request (TLS handshake + model warm-up) and 2.2-3.9s warm, so the
     * old 4s/6s pair timed out on real replies on real devices — which is what
     * produced "I can't reach my AI connection right now" for a request that
     * would have succeeded.
     */
    const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 8L
    const val DEFAULT_READ_TIMEOUT_SECONDS = 15L

    /** Hard ceiling for one Gemini call; kept under the generator's outer cap. */
    const val DEFAULT_CALL_TIMEOUT_SECONDS = 20L

    /** Attempts per user turn: 1 initial request + (this - 1) retries. */
    const val DEFAULT_MAX_ATTEMPTS = 3

    /**
     * Transient upstream failures worth another attempt. Deliberately excludes
     * 400/401/403/404: another identical request cannot fix a bad field, an
     * unauthorized key, or a retired model id.
     */
    private val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)

    /** Backoff before retry N (index = attempt - 1); the last entry repeats. */
    private val RETRY_BACKOFF_MS = longArrayOf(400L, 1_200L)

    /**
     * Total reattempt budget. Stays below DvexResponseGenerator.LLM_TIMEOUT_MS
     * (25s) so this engine always gets to give up on its own terms and log the
     * real cause instead of being cancelled from outside.
     */
    const val RETRY_TOTAL_BUDGET_MS = 18_000L

    private const val REDACTED = "***REDACTED***"
    private const val MAX_ERROR_LOG_CHARS = 300

    private val KEY_SHAPE_REGEX = Regex("""\b(AIza[0-9A-Za-z_\-]{10,}|AQ\.[0-9A-Za-z_.\-]{10,})""")
    private val KEY_QUERY_REGEX = Regex("""([?&]key=)[^&\s"']+""")
  }
}

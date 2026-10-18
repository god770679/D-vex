package com.example.ai

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CONNECTION STATE MODEL (Phase 1 — reliable live connection).
 *
 * One explicit state for the D-VEX ↔ Gemini link, shared by the REST engine,
 * the Live engine and the HUD. Transitions are driven ONLY by real outcomes:
 *
 * - DISCONNECTED — initial state, or a Live socket that closed before it ever
 *   became ready. No verified contact has happened yet.
 * - CONNECTING   — a real connection attempt is in flight.
 * - RECONNECTING — a recoverable failure occurred and the bounded retry loop is
 *   waiting out its backoff before the next attempt (never a loop on its own:
 *   [GeminiEngine] bounds attempts AND total retry budget).
 * - CONNECTED    — a REAL success landed: an HTTP 2xx with an actual answer
 *   (text or a selected tool call), or a Live session whose `setup` completed.
 *   There is no other way into this state, so a false CONNECTED is impossible.
 * - ERROR        — a failure was classified and no further attempt will be made
 *   (permanent class, budget exhausted, or a fatal Live error).
 */
enum class ConnectionState {
  DISCONNECTED,
  CONNECTING,
  RECONNECTING,
  CONNECTED,
  ERROR
}

/**
 * WHAT KIND OF FAILURE IT WAS — separate handling for each class, as required
 * for accurate diagnostics:
 *
 * - CONFIG   (never retried): key missing/blank in this build, or a model id the
 *            service will not serve. Fixing it needs a rebuild/config change, so
 *            retrying inside the app can never help.
 * - AUTH     (never retried): HTTP 401/403 — the key is not authorized for this
 *            model/project. Another identical request cannot change that.
 * - NETWORK  (retried): DNS, connect, TLS or other I/O failure — usually
 *            transient on mobile.
 * - TIMEOUT  (retried): the service did not answer within the call budget.
 * - RATE_LIMIT (retried): HTTP 429 — free-tier quota/rate limit.
 * - SERVER   (retried): HTTP 5xx — Gemini temporarily unavailable upstream.
 * - PROTOCOL (never retried): HTTP 400/404-style request problems, an empty or
 *            unparsable reply — another identical request would fail the same way.
 * - UNKNOWN  (retried): anything not recognized; retried once because transient
 *            I/O problems are the common case, bounded like every other retry.
 *
 * [safeMessage] is the key-free, user-facing line for logs/UI fallbacks. It never
 * contains the API key, a URL, a stack trace, or internal identifiers — the raw
 * cause lives only in [ConnectionDiagnostic.detail], which is already redacted by
 * the engine and is for logs, never for speech.
 */
enum class ConnectionErrorKind(
  val retryable: Boolean,
  val safeMessage: String
) {
  CONFIG(
    retryable = false,
    safeMessage = "D-VEX's AI configuration in this build isn't usable (missing key or model id), so it can't reach the AI service."
  ),
  AUTH(
    retryable = false,
    safeMessage = "The AI key isn't authorized to use this model, so requests are being refused."
  ),
  NETWORK(
    retryable = true,
    safeMessage = "No usable network right now — D-VEX can't reach the AI service."
  ),
  TIMEOUT(
    retryable = true,
    safeMessage = "The AI service didn't answer in time."
  ),
  RATE_LIMIT(
    retryable = true,
    safeMessage = "The AI service is rate-limiting this key right now (free-tier quota is low)."
  ),
  SERVER(
    retryable = true,
    safeMessage = "The AI service is having trouble on its side right now."
  ),
  PROTOCOL(
    retryable = false,
    safeMessage = "The AI service replied with something D-VEX couldn't use."
  ),
  UNKNOWN(
    retryable = true,
    safeMessage = "The AI connection failed for an unknown reason."
  )
}

/**
 * ONE classified failure. [detail] is an internal, already-redacted cause string
 * for logs; [kind] drives retry decisions and every user-facing message.
 */
data class ConnectionDiagnostic(
  val kind: ConnectionErrorKind,
  /** Redacted internal cause (e.g. "HTTP 401", "UnknownHostException"). Never spoken. */
  val detail: String,
  /** HTTP status when the failure came from an HTTP response, else null. */
  val httpStatus: Int? = null,
  /** Engine attempt number that produced this failure (1-based). */
  val attempt: Int = 1,
  /** Wall-clock time of the failure; used to reject stale diagnostics. */
  val atMs: Long = System.currentTimeMillis()
) {
  /** Safe, key-free user-facing description of this failure. */
  val safeMessage: String get() = kind.safeMessage
}

/**
 * Pure classification helpers — the single place failure → kind mapping lives,
 * so REST and Live cannot disagree about what a given failure means.
 */
object ConnectionDiagnostics {

  fun config(detail: String, attempt: Int = 1) =
    ConnectionDiagnostic(ConnectionErrorKind.CONFIG, detail, attempt = attempt)

  fun fromHttpStatus(status: Int, detail: String, attempt: Int = 1): ConnectionDiagnostic =
    ConnectionDiagnostic(kindForHttpStatus(status), detail, httpStatus = status, attempt = attempt)

  fun kindForHttpStatus(status: Int): ConnectionErrorKind = when {
    status == 401 || status == 403 -> ConnectionErrorKind.AUTH
    status == 429 -> ConnectionErrorKind.RATE_LIMIT
    // 404 on generateContent = model id not servable (retired/typo'd) → config.
    status == 404 -> ConnectionErrorKind.CONFIG
    status == 400 -> ConnectionErrorKind.PROTOCOL
    status in 500..599 -> ConnectionErrorKind.SERVER
    else -> ConnectionErrorKind.UNKNOWN
  }

  /**
   * Exception → kind. Timeouts first (SocketTimeoutException is an IOException),
   * then every I/O/DNS/TLS failure as NETWORK (the common mobile case), anything
   * else UNKNOWN. Cancellation is never classified: callers must rethrow it.
   */
  fun fromThrowable(t: Throwable, detail: String, attempt: Int = 1): ConnectionDiagnostic =
    ConnectionDiagnostic(kindForThrowable(t), detail, attempt = attempt)

  fun kindForThrowable(t: Throwable): ConnectionErrorKind = when (t) {
    is SocketTimeoutException, is TimeoutException -> ConnectionErrorKind.TIMEOUT
    is UnknownHostException, is ConnectException, is SSLException, is IOException ->
      ConnectionErrorKind.NETWORK
    else -> ConnectionErrorKind.UNKNOWN
  }

  /** Live-protocol server error codes (`LiveServerEvent.ServerError`). */
  fun fromServerErrorCode(code: Int, detail: String): ConnectionDiagnostic =
    ConnectionDiagnostic(kindForServerErrorCode(code), detail, httpStatus = code.takeIf { it > 0 })

  fun kindForServerErrorCode(code: Int): ConnectionErrorKind = when {
    code == 401 || code == 403 -> ConnectionErrorKind.AUTH
    code == 429 -> ConnectionErrorKind.RATE_LIMIT
    code == 404 -> ConnectionErrorKind.CONFIG
    code == 400 -> ConnectionErrorKind.PROTOCOL
    code in 500..599 || code == 0 -> ConnectionErrorKind.SERVER
    else -> ConnectionErrorKind.UNKNOWN
  }
}

/**
 * SHARED CONNECTION MONITOR — the app's single source of truth for the AI link.
 *
 * Plain object (same pattern as `DvexAgentStateController`): thread-safe
 * StateFlows written by whichever engine is actually talking (OkHttp threads)
 * and collected by the ViewModel/HUD. Both engines report real outcomes only;
 * nothing here can fabricate a CONNECTED state.
 *
 * [lastError] is cleared by a real success so a stale cause from an older turn
 * can never be reported for a later, different failure ([freshError] additionally
 * bounds how old a cause may be before the response layer uses it in a message).
 */
object ConnectionMonitor {

  /**
   * How long a recorded failure stays relevant to the current turn. Long enough
   * to cover the response layer's outer timeout (25s) plus one bounded retry
   * cycle, short enough that a cause from minutes ago is never blamed for a
   * different, later failure.
   */
  const val ERROR_FRESH_MS = 60_000L

  private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
  val state: StateFlow<ConnectionState> = _state.asStateFlow()

  private val _lastError = MutableStateFlow<ConnectionDiagnostic?>(null)
  val lastError: StateFlow<ConnectionDiagnostic?> = _lastError.asStateFlow()

  /** A real connection attempt is in flight. Keeps any existing error cause. */
  fun connecting() {
    _state.value = ConnectionState.CONNECTING
  }

  /** A recoverable failure happened; the bounded backoff is running now. */
  fun retrying(diagnostic: ConnectionDiagnostic?) {
    if (diagnostic != null) _lastError.value = diagnostic
    _state.value = ConnectionState.RECONNECTING
  }

  /** A REAL success (2xx with an answer / Live setup complete). Clears the cause. */
  fun connected() {
    _lastError.value = null
    _state.value = ConnectionState.CONNECTED
  }

  /** Terminal classified failure — no further attempt will be made. */
  fun error(diagnostic: ConnectionDiagnostic) {
    _lastError.value = diagnostic
    _state.value = ConnectionState.ERROR
  }

  /**
   * No verified link (used only when a Live socket closes before it ever became
   * ready, and at startup). Never downgrades a state that was actually verified.
   */
  fun disconnected() {
    if (_state.value != ConnectionState.CONNECTED) {
      _state.value = ConnectionState.DISCONNECTED
    }
  }

  /**
   * The most recent failure, only if it is recent enough to describe the failure
   * the caller is handling right now. Stale causes are surfaced as null so the
   * caller falls back to its generic honest line instead of blaming an old cause.
   */
  fun freshError(nowMs: Long = System.currentTimeMillis()): ConnectionDiagnostic? {
    val diag = _lastError.value ?: return null
    return diag.takeIf { nowMs - it.atMs <= ERROR_FRESH_MS }
  }
}

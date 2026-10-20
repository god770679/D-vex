package com.example.ai

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.agent.DvexMcpTool
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Everything the UI/session layer needs to react to a Live session. Callbacks are
 * delivered on the engine's callback dispatcher (main thread by default). Any
 * method may be left unimplemented — they all have empty defaults.
 *
 * Contract:
 *  - [onSetupComplete] is the ONLY point at which audio may be sent; before it,
 *    [GeminiLiveEngine.sendAudio] drops silently.
 *  - [onToolCall] delivers SELECTION only. Running a tool requires the same
 *    validation/permission/confirmation/verification path as the REST pipeline
 *    (`agent/DvexToolProtocol`), and the result is sent back with
 *    [GeminiLiveEngine.sendToolResponses].
 *  - [onInterrupted] means the user barged in: drop queued playback.
 *  - [onError] with `fatal = true` ends the session; the caller falls back to
 *    the existing (unchanged) offline/REST pipeline.
 */
interface LiveSessionListener {
  fun onSetupComplete() {}
  fun onAudio(bytes: ByteArray) {}
  fun onUserTranscript(text: String) {}
  fun onModelTranscript(text: String) {}
  fun onTurnComplete() {}
  fun onInterrupted() {}
  fun onToolCall(calls: List<LiveFunctionCall>) {}
  fun onGoAway(timeLeftMs: Long?) {}
  fun onError(message: String, fatal: Boolean) {}
  fun onClosed(reason: String) {}
}

/** Send-side seam — production is OkHttp; tests inject fakes. Never logged. */
internal interface LiveSocket {
  fun send(text: String): Boolean
  fun close(code: Int, reason: String?): Boolean
}

/** Socket events the engine reacts to. */
internal interface LiveSocketListener {
  fun onOpen()
  fun onMessage(text: String)
  fun onFailure(t: Throwable)
  fun onClosed(code: Int, reason: String?)
}

internal fun interface LiveSocketFactory {
  fun connect(url: String, listener: LiveSocketListener): LiveSocket
}

/** Production transport: one OkHttp WebSocket with keepalive pings. */
internal class OkHttpLiveSocketFactory : LiveSocketFactory {

  // Dedicated client: long-lived socket, so read timeout is disabled and
  // keepalive pings detect dead peers instead of a silent stall.
  private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
    .build()

  override fun connect(url: String, listener: LiveSocketListener): LiveSocket {
    val request = Request.Builder().url(url).build()
    val ws = client.newWebSocket(
      request,
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

        override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
          listener.onFailure(t)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
          listener.onClosed(code, reason)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          webSocket.close(code, reason)
        }
      }
    )
    return object : LiveSocket {
      override fun send(text: String): Boolean = ws.send(text)
      override fun close(code: Int, reason: String?): Boolean = ws.close(code, reason.orEmpty())
    }
  }

  companion object {
    const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10L
    const val PING_INTERVAL_SECONDS = 20L
  }
}

/**
 * GEMINI LIVE ENGINE — one opt-in, real-time voice session over the raw Live
 * WebSocket. This is an ADDITIVE mode, never the default: D-VEX keeps its
 * existing working pipeline (Sherpa-ONNX wake word → speech recognizer →
 * GeminiEngine REST → deterministic agent → TTS) and falls back to it whenever
 * Live is off, unavailable, or dies.
 *
 * Protocol (verified against Google's official WebSocket guide, 2026-10-09):
 *  - endpoint `wss://…/BidiGenerateContent?key=<KEY>` — the API key rides the
 *    URL as query param, the ONLY auth scheme this transport supports;
 *  - first message is `setup` (see [GeminiLiveToolCodec.setupMessage]);
 *  - audio in: 16 kHz PCM16 LE, out: 24 kHz PCM16 LE;
 *  - `gemini-3.8-live` is the documented model and was confirmed servable for
 *    this project's key on 2026-10-09 (models.get → HTTP 200).
 *
 * Session lifecycle: ONE engine instance = ONE session. [connect] once; on any
 * fatal error or [close] the engine is done and must not be reused (the caller
 * creates a new one to start again). This keeps the state machine trivial to
 * reason about and to test.
 *
 * Safety properties:
 *  - the API key value is NEVER logged: no URL is ever logged, and every error
 *    string passes [redact] (key value + Google key shapes + `key=` query).
 *  - missing/blank key fails fast with a fatal error and NO connection attempt.
 *  - a `setup` timeout watchdog closes a connection that never becomes ready, so
 *    a hung socket cannot pin the microphone forever.
 *  - sendAudio/sendToolResponses before setup completion or after close are
 *    dropped, never queued into a dead socket.
 *  - close() is idempotent and always releases the socket; all listener
 *    callbacks go through the injected dispatcher (main thread in production).
 *
 * Tool calls: [LiveSessionListener.onToolCall] delivers plain selections. The
 * deterministic agent (`DvexToolProtocol`) must gate every call with the same
 * validation, permissions, confirmation and post-action verification as the
 * REST pipeline before [sendToolResponses] answers the model.
 */
class GeminiLiveEngine internal constructor(
  private val apiKeyProvider: () -> String?,
  private val socketFactory: LiveSocketFactory,
  private val callbackDispatcher: (Runnable) -> Unit,
  private val model: String = DEFAULT_MODEL,
  private val wsUrlBase: String = DEFAULT_WS_URL,
  private val setupTimeoutMs: Long = DEFAULT_SETUP_TIMEOUT_MS
) {

  /** Public constructor: OkHttp transport, main-thread callbacks. */
  constructor(
    apiKeyProvider: () -> String?,
    callbackDispatcher: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) }
  ) : this(
    apiKeyProvider = apiKeyProvider,
    socketFactory = OkHttpLiveSocketFactory(),
    callbackDispatcher = callbackDispatcher,
    model = DEFAULT_MODEL,
    wsUrlBase = DEFAULT_WS_URL,
    setupTimeoutMs = DEFAULT_SETUP_TIMEOUT_MS
  )

  private enum class State { IDLE, CONNECTING, OPEN, CLOSING, CLOSED }

  private val lock = Any()
  private var state = State.IDLE
  private var socket: LiveSocket? = null
  private var listener: LiveSessionListener? = null
  private var setupComplete = false
  private var watchdogJob: Job? = null

  /**
   * Watchdog scope: the setup-timeout check only (structured, cancelled with the
   * session — the project bans raw Executor/Timer schedulers in production code).
   */
  private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val isOpen: Boolean
    get() = synchronized(lock) { state == State.OPEN && setupComplete }

  /**
   * Opens the session. Sends `setup` as the FIRST message once the socket is
   * up. @return true when a connection attempt was started; a fatal error
   * arrives through [LiveSessionListener.onError] otherwise.
   */
  fun connect(systemInstruction: String?, tools: List<DvexMcpTool>, liveListener: LiveSessionListener): Boolean {
    val key = apiKeyProvider().orEmpty()
    synchronized(lock) {
      if (state != State.IDLE) {
        dispatch { liveListener.onError("Live session already started (state=$state); create a new engine.", fatal = false) }
        return false
      }
      if (key.isBlank()) {
        state = State.CLOSED
        Log.w(TAG, "[D-VEX][LIVE] configured=false — GEMINI_API_KEY missing/blank; " +
          "Live conversation cannot start. Caller must keep the existing pipeline.")
        // CONFIG class, never retryable — classified before any connection attempt.
        ConnectionMonitor.error(
          ConnectionDiagnostics.config("GEMINI_API_KEY missing/blank (Live mode)")
        )
        dispatch { liveListener.onError("GEMINI_API_KEY missing/blank in this build — Live mode unavailable.", fatal = true) }
        return false
      }
      state = State.CONNECTING
      listener = liveListener
    }
    ConnectionMonitor.connecting()

    Log.i(TAG, "[D-VEX][LIVE] connecting model=$model (key len=${key.length}, value withheld, " +
      "setupTimeout=${setupTimeoutMs}ms, tools=${tools.size})")

    // URL carries the key as query parameter (the only auth this transport
    // supports) — it is passed to OkHttp directly and NEVER logged.
    val url = "$wsUrlBase?key=$key"
    val socketListener = object : LiveSocketListener {
      override fun onOpen() = handleOpen(systemInstruction, tools)
      override fun onMessage(text: String) = handleMessage(text)
      override fun onFailure(t: Throwable) = handleTransportFailure(t)
      override fun onClosed(code: Int, reason: String?) = handleClosed(code, reason)
    }
    val started = try {
      val s = socketFactory.connect(url, socketListener)
      synchronized(lock) {
        if (state == State.CONNECTING) socket = s
      }
      true
    } catch (t: Throwable) {
      fail(
        "connection attempt failed: ${t.javaClass.simpleName}: ${redact(t.message ?: "no message", key)}",
        fatal = true,
        diagnostic = ConnectionDiagnostics.fromThrowable(
          t,
          detail = "${t.javaClass.simpleName}: ${redact(t.message ?: "no message", key)}"
        )
      )
      false
    }
    return started
  }

  /**
   * Sends one chunk of 16 kHz PCM16 LE mic audio. Dropped (returns false)
   * unless the session is fully open — audio is never queued into a socket that
   * may never become ready.
   */
  fun sendAudio(pcm16le: ByteArray): Boolean {
    val s = synchronized(lock) {
      if (state != State.OPEN || !setupComplete) return false
      socket ?: return false
    }
    val ok = try {
      s.send(GeminiLiveToolCodec.audioMessage(pcm16le))
    } catch (t: Throwable) {
      fail("audio send failed: ${t.javaClass.simpleName}", fatal = true, diagnostic = networkDiag("audio send"))
      return false
    }
    if (!ok) {
      fail("audio send rejected by socket (backpressure/closed)", fatal = true, diagnostic = networkDiag("audio send"))
      return false
    }
    return true
  }

  /**
   * Sends one text input (documented `realtimeInput.text` shape) — e.g. a wake
   * phrase that carried an attached command. Same guards as [sendAudio]: dropped
   * unless the session is fully open.
   */
  fun sendText(text: String): Boolean {
    if (text.isBlank()) return true
    val s = synchronized(lock) {
      if (state != State.OPEN || !setupComplete) return false
      socket ?: return false
    }
    val ok = try {
      s.send(GeminiLiveToolCodec.textMessage(text))
    } catch (t: Throwable) {
      fail("text send failed: ${t.javaClass.simpleName}", fatal = true, diagnostic = networkDiag("text send"))
      return false
    }
    if (!ok) {
      fail("text send rejected by socket", fatal = true, diagnostic = networkDiag("text send"))
      return false
    }
    Log.i(TAG, "[D-VEX][LIVE] sent text input (${text.length} chars)")
    return true
  }

  /**
   * Answers the model's tool requests. Each [LiveFunctionResponse.response]
   * MUST be the real outcome produced by the deterministic agent (including
   * user-denied or verification-failed outcomes) — never an invented result.
   */
  fun sendToolResponses(responses: List<LiveFunctionResponse>): Boolean {
    if (responses.isEmpty()) return true
    val (s, ready) = synchronized(lock) { Pair(socket, state == State.OPEN && setupComplete) }
    if (!ready || s == null) return false
    val ok = try {
      s.send(GeminiLiveToolCodec.toolResponseMessage(responses))
    } catch (t: Throwable) {
      fail("tool response send failed: ${t.javaClass.simpleName}", fatal = true, diagnostic = networkDiag("tool response send"))
      return false
    }
    if (!ok) {
      fail("tool response send rejected by socket", fatal = true, diagnostic = networkDiag("tool response send"))
      return false
    }
    Log.i(TAG, "[D-VEX][LIVE] sent ${responses.size} tool response(s): ${responses.joinToString(", ") { it.name }}")
    return true
  }

  /**
   * Closes the session. Idempotent; releases the socket and the watchdog
   * executor. [LiveSessionListener.onClosed] is dispatched once the transport
   * reports the close.
   */
  fun close(reason: String = "client close") {
    val s = synchronized(lock) {
      if (state == State.CLOSED || state == State.CLOSING) return
      state = State.CLOSING
      cancelWatchdog()
      socket
    }
    try {
      s?.close(1000, reason)
    } catch (t: Throwable) {
      Log.w(TAG, "[D-VEX][LIVE] close failed: ${t.javaClass.simpleName}")
    }
    // If the transport never confirms the close, still settle the state.
    handleClosed(1000, reason)
  }

  // =========================================================================
  // Socket events (OkHttp threads)
  // =========================================================================

  private fun handleOpen(systemInstruction: String?, tools: List<DvexMcpTool>) {
    val s = synchronized(lock) {
      if (state != State.CONNECTING) return
      socket
    } ?: return

    val setupJson = GeminiLiveToolCodec.setupMessage(model, systemInstruction, tools)
    val sent = try {
      s.send(setupJson)
    } catch (t: Throwable) {
      fail("setup send failed: ${t.javaClass.simpleName}", fatal = true, diagnostic = networkDiag("setup send"))
      return
    }
    if (!sent) {
      fail("setup send rejected by socket", fatal = true, diagnostic = networkDiag("setup send"))
      return
    }
    Log.i(TAG, "[D-VEX][LIVE] socket open; setup sent (model=$model, tools=${tools.size})")
    scheduleWatchdog()
  }

  private fun handleMessage(text: String) {
    val events = GeminiLiveToolCodec.parseServerMessage(text)
    if (events.isEmpty()) {
      // Unparsable or empty server frame: not fatal, just not useful.
      Log.w(TAG, "[D-VEX][LIVE] ignored non-event server message (${text.length} chars)")
      return
    }
    events.forEach { event ->
      when (event) {
        LiveServerEvent.SetupComplete -> {
          synchronized(lock) {
            state = State.OPEN
            setupComplete = true
          }
          cancelWatchdog()
          Log.i(TAG, "[D-VEX][LIVE] setup complete — session ready")
          // ONLY entry point to CONNECTED on the Live path: the server itself
          // confirmed the session with setupComplete.
          ConnectionMonitor.connected()
          dispatch { listener?.onSetupComplete() }
        }
        is LiveServerEvent.Audio -> dispatch { listener?.onAudio(event.bytes) }
        is LiveServerEvent.UserTranscript -> dispatch { listener?.onUserTranscript(event.text) }
        is LiveServerEvent.ModelTranscript -> dispatch { listener?.onModelTranscript(event.text) }
        LiveServerEvent.TurnComplete -> dispatch { listener?.onTurnComplete() }
        LiveServerEvent.Interrupted -> {
          Log.i(TAG, "[D-VEX][LIVE] model turn interrupted (barge-in)")
          dispatch { listener?.onInterrupted() }
        }
        is LiveServerEvent.ToolCall -> {
          // SELECTION only — the deterministic agent still gates execution.
          Log.i(TAG, "[D-VEX][LIVE] model selected ${event.calls.size} tool call(s): " +
            "${event.calls.joinToString(", ") { it.name }} (selection only — D-VEX decides)")
          dispatch { listener?.onToolCall(event.calls) }
        }
        is LiveServerEvent.GoAway -> {
          Log.w(TAG, "[D-VEX][LIVE] server goAway timeLeftMs=${event.timeLeftMs ?: "unknown"} " +
            "(normal end of the long-session window; caller should wind down and may reconnect later)")
          dispatch { listener?.onGoAway(event.timeLeftMs) }
        }
        is LiveServerEvent.ServerError -> {
          val detail = "server error ${event.code}: ${redact(event.message, apiKeyProvider().orEmpty())}"
          fail(
            "server error ${event.code}: ${redact(event.message, apiKeyProvider().orEmpty())}",
            fatal = true,
            diagnostic = ConnectionDiagnostics.fromServerErrorCode(event.code, detail)
          )
        }
      }
    }
  }

  private fun handleTransportFailure(t: Throwable) {
    val wasClosing = synchronized(lock) { state == State.CLOSING || state == State.CLOSED }
    val message = "${t.javaClass.simpleName}: ${redact(t.message ?: "no message", apiKeyProvider().orEmpty())}"
    if (wasClosing) {
      handleClosed(1000, "closed")
      return
    }
    fail(
      "connection failed — $message",
      fatal = true,
      diagnostic = ConnectionDiagnostics.fromThrowable(t, detail = message)
    )
  }

  private fun handleClosed(code: Int, reason: String?) {
    val l = synchronized(lock) {
      if (state == State.CLOSED) return
      val wasReady = setupComplete
      state = State.CLOSED
      setupComplete = false
      cancelWatchdog()
      watchdogScope.cancel()
      listener to wasReady
    }
    // A Live socket that closed BEFORE it was ever ready means no verified link
    // exists (a clean close after a ready session keeps the last verified truth).
    if (!l.second) ConnectionMonitor.disconnected()
    Log.i(TAG, "[D-VEX][LIVE] session closed (code=$code, reason=${reason ?: "none"})")
    dispatch { l.first?.onClosed(reason ?: "closed (code=$code)") }
  }

  // =========================================================================
  // Internals
  // =========================================================================

  /** Terminal failure: tear the socket down and report the cause once. */
  private fun fail(
    message: String,
    fatal: Boolean,
    diagnostic: ConnectionDiagnostic? = null
  ) {
    val (s, l) = synchronized(lock) {
      if (state == State.CLOSED) return
      val socketRef = socket
      socket = null
      if (fatal) state = State.CLOSED
      cancelWatchdog()
      Pair(socketRef, listener)
    }
    Log.w(TAG, "[D-VEX][LIVE] fatal=$fatal — $message")
    if (fatal) {
      try {
        s?.close(1011, "error")
      } catch (_: Throwable) {
      }
      synchronized(lock) {
        state = State.CLOSED
        setupComplete = false
        watchdogScope.cancel()
      }
      // Terminal classified failure (never CONNECTED): the classified diagnostic
      // when the call site knows the class, otherwise an honest UNKNOWN that
      // carries only the already-redacted message.
      ConnectionMonitor.error(
        diagnostic ?: ConnectionDiagnostic(ConnectionErrorKind.UNKNOWN, detail = message)
      )
    }
    dispatch { l?.onError(message, fatal) }
  }

  /** Transport-class diagnostic for socket send/connect failures (never a key). */
  private fun networkDiag(detail: String): ConnectionDiagnostic =
    ConnectionDiagnostic(ConnectionErrorKind.NETWORK, detail = detail)

  private fun scheduleWatchdog() {
    cancelWatchdog()
    watchdogJob = watchdogScope.launch {
      delay(setupTimeoutMs)
      val timedOut = synchronized(lock) {
        state == State.CONNECTING || (state == State.OPEN && !setupComplete)
      }
      if (timedOut) {
        fail(
          "setup not confirmed within ${setupTimeoutMs}ms — closing hung connection",
          fatal = true,
          diagnostic = ConnectionDiagnostic(
            ConnectionErrorKind.TIMEOUT,
            detail = "setup not confirmed within ${setupTimeoutMs}ms"
          )
        )
      }
    }
  }

  private fun cancelWatchdog() {
    watchdogJob?.cancel()
    watchdogJob = null
  }

  /** All listener callbacks travel through the injected dispatcher. */
  private fun dispatch(block: () -> Unit) {
    try {
      callbackDispatcher(Runnable { block() })
    } catch (t: Throwable) {
      Log.w(TAG, "[D-VEX][LIVE] callback dispatch failed: ${t.javaClass.simpleName}")
    }
  }

  /**
   * Removes anything that could carry the credential from text about to be
   * logged: the exact key, Google key shapes (legacy `AIza…`, new `AQ.…`) and
   * `key=` query parameters — same three shapes [GeminiEngine] redacts.
   */
  private fun redact(text: String, apiKey: String): String {
    var out = text
    if (apiKey.isNotBlank()) out = out.replace(apiKey, REDACTED)
    out = KEY_SHAPE_REGEX.replace(out, REDACTED)
    out = KEY_QUERY_REGEX.replace(out, "$1$REDACTED")
    return out
  }

  companion object {
    private const val TAG = "[D-VEX][GEMINI][LIVE]"

    /** Raw WebSocket endpoint for BidiGenerateContent (from Google's guide). */
    const val DEFAULT_WS_URL =
      "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /**
     * The Live model used in Google's own WebSocket examples; confirmed servable
     * for this project's key on 2026-10-09 (bidiGenerateContent listed).
     * (`gemini-2.0-flash-live-001` was shut down in Dec 2025.)
     */
    const val DEFAULT_MODEL = "gemini-3.8-live"

    /**
     * A healthy session confirms setup within a few seconds. If it does not,
     * the connection is hung — closing it beats holding the mic forever. Kept
     * generous for cold TLS + model warm-up on mobile.
     */
    const val DEFAULT_SETUP_TIMEOUT_MS = 15_000L

    private const val REDACTED = "***REDACTED***"
    private val KEY_SHAPE_REGEX = Regex("""\b(AIza[0-9A-Za-z_\-]{10,}|AQ\.[0-9A-Za-z_.\-]{10,})""")
    private val KEY_QUERY_REGEX = Regex("""([?&]key=)[^&\s"']+""")
  }
}

package com.example.voice

import android.util.Log
import com.example.agent.DvexMcpTool
import com.example.agent.DvexToolCall
import com.example.ai.GeminiLiveEngine
import com.example.ai.LiveFunctionCall
import com.example.ai.LiveFunctionResponse
import com.example.ai.LiveSessionListener
import com.example.model.DvexSettings
import kotlinx.coroutines.Job
import org.json.JSONObject

/**
 * Honest outcome of ONE model-selected tool call, in D-VEX's own status
 * vocabulary. Produced by the agent/tool safety boundary — never by this class.
 */
sealed class LiveToolOutcome {
  /**
   * Execution finished. [responseStatus] is the REAL status
   * (success | unverified | failed | blocked | unsupported | skipped):
   * an unverified or failed action is never promoted to success.
   */
  data class Finished(val responseStatus: String, val message: String) : LiveToolOutcome()

  /**
   * Held at D-VEX's confirmation gate: nothing executed yet. The call is
   * answered with `confirmation_required` so the model can ask the user, and
   * the execution resumes only through [LiveConfirmationResolution].
   */
  data class ConfirmationRequired(val prompt: String) : LiveToolOutcome()
}

/** The user's real answer to a pending confirmation (voice or HUD). */
data class LiveConfirmationResolution(val outcomeStatus: String, val message: String)

/**
 * LIVE MODE POLICY — the single decision point for whether a voice entry uses
 * the Live pipeline. Live needs BOTH the opt-in toggle AND the microphone
 * permission; otherwise the proven STT/REST/TTS pipeline serves the turn.
 */
internal object LiveModePolicy {
  fun shouldStartLive(settings: DvexSettings, hasAudioPermission: Boolean): Boolean =
    settings.liveConversationEnabled && hasAudioPermission
}

/**
 * LIVE CONVERSATION COORDINATOR — the bridge between [GeminiLiveEngine] and
 * D-VEX's real assistant. Owns one Live session end-to-end:
 *
 *  - mic in: [MicCapture] chunks (16 kHz PCM16 LE) → engine → model;
 *  - model audio out: engine chunks (24 kHz PCM16 LE) → [PcmSink], with
 *    barge-in flush on server interruption;
 *  - tool calls: converted to [DvexToolCall] and routed through the INJECTED
 *    safety gateway (the repository wires it to DvexSmartBrain's
 *    `processModelSelectedToolCall` — the same admission/policy/confirmation/
 *    verification boundary the REST pipeline uses). This coordinator executes
 *    NOTHING itself and always answers every correlation id with the REAL
 *    outcome (success / unverified / failed / blocked / denied);
 *  - confirmations: a HIGH-risk call is held at the gate; the model asks the
 *    user, and the answer (voice transcript, debounced, or HUD button) goes
 *    through the injected confirmation gateway — the same brain machinery the
 *    STT flow uses. An unrelated answer ABANDONS the pending action (nothing
 *    runs) so a follow-up the model also heard can never double-execute;
 *  - lifecycle: [end] is idempotent and always releases mic, sink and socket;
 *    fatal errors end the session through [Callbacks.onEnded] so the caller can
 *    restore the normal standby lifecycle.
 *
 * All listener callbacks are delivered through [dispatch] (main thread in
 * production); [runAsync] runs suspend work on the caller's session scope.
 */
internal class LiveConversationCoordinator(
  private val engine: GeminiLiveEngine,
  private val micFactory: MicFactory,
  private val sinkFactory: SinkFactory,
  private val systemInstruction: String,
  private val tools: List<DvexMcpTool>,
  /** Executes ONE admitted tool call through the agent safety boundary. */
  private val executeToolCall: suspend (DvexToolCall) -> LiveToolOutcome,
  /** Resolves a pending confirmation with the user's words ("yes"/"no"/other). */
  private val answerConfirmation: suspend (String) -> LiveConfirmationResolution,
  private val callbacks: Callbacks,
  private val dispatch: (Runnable) -> Unit,
  private val runAsync: (suspend () -> Unit) -> Job,
  private val confirmationDebounceMs: Long = CONFIRMATION_DEBOUNCE_MS
) : LiveSessionListener {

  /** What the surrounding assistant does about session events. */
  interface Callbacks {
    /** Session configured; mic capture starts and audio may now flow. */
    fun onReady()

    /** Live transcription of the user's speech (HUD display only). */
    fun onUserTranscript(text: String)

    /** Incremental model speech transcript (HUD response card). */
    fun onModelSpeech(text: String)

    /** Model audio started ([speaking] = true) / finished or was interrupted. */
    fun onSpeakingChanged(speaking: Boolean)

    /** A model-selected tool is entering the safety boundary. */
    fun onToolExecutionStarted(toolName: String)

    /** The confirmation gate held a HIGH-risk call; [prompt] asks the user. */
    fun onToolConfirmationRequested(toolName: String, prompt: String)

    /** The pending confirmation resolved (executed, denied or abandoned). */
    fun onToolConfirmationResolved()

    /** Non-fatal diagnostic (e.g. a transient engine warning). */
    fun onError(message: String, fatal: Boolean)

    /** Session over. The caller restores standby (wake re-arm) from here. */
    fun onEnded(reason: String, fatal: Boolean)
  }

  private val started = java.util.concurrent.atomic.AtomicBoolean(false)
  private val ended = java.util.concurrent.atomic.AtomicBoolean(false)

  @Volatile private var input: LiveAudioInput? = null
  @Volatile private var output: LiveAudioOutput? = null
  @Volatile private var lastSpeaking = false

  /** The call held at the confirmation gate (exactly one at a time). */
  @Volatile private var pendingCallId: String? = null
  @Volatile private var pendingCallName: String? = null

  /** Debounce buffer: user transcripts arrive as incremental chunks. */
  private val transcriptBuffer = StringBuilder()
  @Volatile private var confirmationDebounceJob: Job? = null

  /** Correlation ids already answered — a duplicate model call never re-executes. */
  private val answeredIds = LinkedHashSet<String>()

  val isActive: Boolean get() = started.get() && !ended.get()
  val hasPendingConfirmation: Boolean get() = pendingCallId != null

  /**
   * Opens the session. @false when the engine refused to dial (missing key /
   * connect failure) — the fatal error arrives through [Callbacks.onEnded] and
   * the caller falls back to the proven pipeline.
   */
  fun start(initialText: String? = null): Boolean {
    if (!started.compareAndSet(false, true)) return false
    initialText?.let { pendingInitialText = it.trim().takeIf { t -> t.isNotBlank() } }
    Log.i(TAG, "Starting Live conversation (tools=${tools.size})")
    return engine.connect(systemInstruction, tools, this)
  }

  /** Text the wake phrase carried (\"D-VEX open YouTube\"), sent once setup completes. */
  @Volatile private var pendingInitialText: String? = null

  /**
   * Ends the session and releases mic, sink and socket exactly once. Idempotent,
   * safe from any thread. Does NOT invoke [Callbacks.onEnded] — an external end
   * is caller-driven (the caller owns the standby restore).
   */
  fun end(reason: String) {
    if (!ended.compareAndSet(false, true)) return
    Log.i(TAG, "Live session ended by caller: $reason")
    cleanup(reason)
  }

  /** HUD confirm/cancel of the pending HIGH-risk call. */
  fun resolveConfirmationFromHud(affirmative: Boolean) {
    if (pendingCallId == null) return
    runAsync { resolvePendingInternal(if (affirmative) "yes" else "cancel") }
  }

  // =========================================================================
  // LiveSessionListener (delivered via the engine's dispatcher — main thread)
  // =========================================================================

  override fun onSetupComplete() {
    if (ended.get()) return
    val out = LiveAudioOutput(
      sinkFactory = sinkFactory,
      callbackDispatcher = dispatch,
      onError = { msg -> reportEnded("audio output failed: $msg", fatal = true) }
    ).apply {
      onQueueDrained = { dispatch { emitSpeaking(false) } }
    }
    if (!out.start()) {
      reportEnded("audio output could not be started", fatal = true)
      return
    }
    output = out
    val mic = LiveAudioInput(
      micFactory = micFactory,
      onPcmChunk = { pcm -> engine.sendAudio(pcm) },
      onError = { msg -> reportEnded("audio input failed: $msg", fatal = true) }
    )
    if (!mic.start()) {
      reportEnded("audio input could not be started", fatal = true)
      return
    }
    input = mic
    pendingInitialText?.let { text ->
      pendingInitialText = null
      engine.sendText(text)
    }
    callbacks.onReady()
  }

  override fun onAudio(bytes: ByteArray) {
    if (ended.get()) return
    output?.enqueue(bytes)
    emitSpeaking(true)
  }

  override fun onUserTranscript(text: String) {
    if (ended.get()) return
    callbacks.onUserTranscript(text)
    if (pendingCallId != null) {
      // Transcripts arrive as incremental chunks: debounce so the gate sees the
      // user's whole answer, never its first fragment.
      synchronized(transcriptBuffer) { transcriptBuffer.append(text) }
      confirmationDebounceJob?.cancel()
      confirmationDebounceJob = runAsync {
        kotlinx.coroutines.delay(confirmationDebounceMs)
        val answer = synchronized(transcriptBuffer) {
          val full = transcriptBuffer.toString()
          transcriptBuffer.clear()
          full
        }
        resolvePendingInternal(answer)
      }
    }
  }

  override fun onModelTranscript(text: String) {
    if (ended.get()) return
    callbacks.onModelSpeech(text)
  }

  override fun onTurnComplete() {
    emitSpeaking(false)
  }

  override fun onInterrupted() {
    // Barge-in: the user spoke over the model — drop queued playback immediately.
    output?.interrupt()
    emitSpeaking(false)
  }

  override fun onToolCall(calls: List<LiveFunctionCall>) {
    if (ended.get() || calls.isEmpty()) return
    runAsync { handleToolCalls(calls) }
  }

  override fun onError(message: String, fatal: Boolean) {
    if (fatal) {
      reportEnded(message, fatal = true)
    } else {
      callbacks.onError(message, fatal = false)
    }
  }

  override fun onClosed(reason: String) {
    reportEnded("Live session closed: $reason", fatal = false)
  }

  // =========================================================================
  // Tool routing (never executed here — the injected gateway owns safety)
  // =========================================================================

  private suspend fun handleToolCalls(calls: List<LiveFunctionCall>) {
    // One action at a time — the same per-turn policy the REST pipeline enforces.
    // Extra calls still get an honest answer so no correlation id is left hanging.
    val primary = calls.first()
    calls.drop(1).forEach { extra ->
      answerCall(
        extra,
        JSONObject().put("status", "skipped")
          .put("message", "One action at a time. Ask again after the current one finishes.")
      )
    }

    if (isDuplicate(primary.id)) {
      answerCall(
        primary,
        JSONObject().put("status", "duplicate").put("message", "This call was already handled.")
      )
      return
    }
    if (pendingCallId != null) {
      answerCall(
        primary,
        JSONObject().put("status", "busy")
          .put("message", "A confirmation is already waiting for the user.")
      )
      return
    }

    callbacks.onToolExecutionStarted(primary.name)
    when (val outcome = executeToolCall(primary.toToolCall())) {
      is LiveToolOutcome.Finished -> {
        answerCall(primary, resultJson(outcome.responseStatus, outcome.message))
      }
      is LiveToolOutcome.ConfirmationRequired -> {
        pendingCallId = primary.id
        pendingCallName = primary.name
        // Answer with the gate's OWN prompt so the model asks the user verbatim;
        // nothing has executed at this point.
        answerCall(primary, resultJson("confirmation_required", outcome.prompt))
        callbacks.onToolConfirmationRequested(primary.name, outcome.prompt)
      }
    }
  }

  /** Resolves the held call with the user's words; executes or abandons — never fakes. */
  private suspend fun resolvePendingInternal(answerText: String) {
    confirmationDebounceJob?.cancel()
    confirmationDebounceJob = null
    val id = pendingCallId ?: return
    val name = pendingCallName.orEmpty()
    val lower = answerText.lowercase().trim().trimEnd('.', '?', '!', ',')
    pendingCallId = null
    pendingCallName = null
    synchronized(transcriptBuffer) { transcriptBuffer.clear() }

    val resolution = answerConfirmation(answerText)
    callbacks.onToolConfirmationResolved()
    markAnswered(id)
    // The confirmed/denied outcome reaches the model as a text message that names
    // the original correlation id; the system instruction makes the model relay
    // the status honestly and never upgrade it.
    engine.sendText("D-VEX_TOOL_OUTCOME id=$id tool=$name status=${resolution.outcomeStatus}: ${resolution.message} " +
      "(User's answer was: \"$lower\".)")
  }

  private fun resultJson(status: String, message: String): JSONObject =
    JSONObject().put("status", status).put("message", message)

  /** Sends one toolResponse carrying the call's correlation id and the REAL outcome. */
  private fun answerCall(call: LiveFunctionCall, response: JSONObject) {
    markAnswered(call.id)
    val sent = engine.sendToolResponses(
      listOf(LiveFunctionResponse(id = call.id, name = call.name, response = response))
    )
    if (!sent) {
      Log.w(TAG, "Tool response for id=${call.id} could not be sent (session likely ending)")
    }
  }

  private fun isDuplicate(id: String): Boolean = synchronized(answeredIds) { id in answeredIds }

  private fun markAnswered(id: String) {
    synchronized(answeredIds) {
      answeredIds.add(id)
      while (answeredIds.size > MAX_ANSWERED_IDS) {
        val it = answeredIds.iterator()
        it.next()
        it.remove()
      }
    }
  }

  // =========================================================================
  // Internals
  // =========================================================================

  private fun emitSpeaking(speaking: Boolean) {
    if (lastSpeaking == speaking) return
    lastSpeaking = speaking
    callbacks.onSpeakingChanged(speaking)
  }

  /** Terminal end through a listener event: release everything, then notify once. */
  private fun reportEnded(reason: String, fatal: Boolean) {
    if (!ended.compareAndSet(false, true)) return
    Log.w(TAG, "Live session ended (fatal=$fatal): $reason")
    cleanup(reason)
    callbacks.onEnded(reason, fatal)
  }

  private fun cleanup(reason: String) {
    confirmationDebounceJob?.cancel()
    confirmationDebounceJob = null
    val mic = input
    input = null
    val out = output
    output = null
    // Release order matters: mic first (frees the recorder), then playback,
    // then the socket — by the time standby re-arms, D-VEX holds nothing.
    try {
      mic?.stopAndRelease()
    } catch (t: Throwable) {
      Log.w(TAG, "Mic release failed during end($reason): ${t.javaClass.simpleName}")
    }
    try {
      out?.stopAndRelease()
    } catch (t: Throwable) {
      Log.w(TAG, "Output release failed during end($reason): ${t.javaClass.simpleName}")
    }
    try {
      engine.close(reason)
    } catch (t: Throwable) {
      Log.w(TAG, "Engine close failed during end($reason): ${t.javaClass.simpleName}")
    }
  }

  companion object {
    private const val TAG = "[D-VEX][LIVE][COORD]"

    /**
     * Production microphone factory. The voice package owns the ONE
     * AudioRecord-backed capture site; the orchestrator never constructs audio
     * hardware itself (pinned by VoiceAudioHygieneTest).
     */
    fun productionMicFactory(): MicFactory = MicFactory { AndroidMicCapture() }

    /** Production speaker factory: the ONE AudioTrack playback site for Live audio. */
    fun productionSinkFactory(): SinkFactory = SinkFactory { AudioTrackPcmSink() }

    /** Quiet-gap after the last transcript chunk before the gate reads the answer. */
    const val CONFIRMATION_DEBOUNCE_MS = 700L

    /** Bounded bookkeeping of answered correlation ids. */
    const val MAX_ANSWERED_IDS = 64
  }
}

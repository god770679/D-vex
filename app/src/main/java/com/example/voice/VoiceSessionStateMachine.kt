package com.example.voice

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * D-VEX voice interaction lifecycle state machine (Bug: voice session lifecycle).
 *
 * Enforces the exact session protocol:
 *
 *   STANDBY
 *     └─ wake word detected ──────────► WAKE_TRIGGERED (one session created)
 *          └─ command listening starts ► COMMAND_LISTENING (wake detector is OFF)
 *               └─ onResults/onError/timeout ► PROCESSING_COMMAND
 *                    └─ response spoken ──► STANDBY (wake detection resumes)
 *
 * Guarantees (each locked by unit tests):
 * - A wake trigger is accepted exactly once per session; duplicates are ignored.
 * - Command listening starts exactly once per session; duplicate starts are ignored.
 * - Command-terminal callbacks (results/error/timeout) close the command session
 *   exactly once; duplicates are ignored.
 * - Wake-word detection resumes ONLY after the command session has fully ended
 *   (command closed + response finished) — never while command audio is active.
 * - "D-VEX" alone with no speech times out into an honest STANDBY with no action.
 *
 * Pure Kotlin (no Android imports) so the full protocol is unit-testable on the JVM.
 */
class VoiceSessionStateMachine(
  private val clock: () -> Long = System::currentTimeMillis
) {

  /** Explicit, observable voice session states. */
  enum class State {
    /** Idle/no voice activity. Not yet listening for the wake word. */
    IDLE,

    /** STANDBY: wake-word detector is active and armed. */
    STANDBY,

    /** Wake word accepted once; wake detection must be off; command listening starting. */
    WAKE_TRIGGERED,

    /** Command recognizer is listening exactly once for this session. */
    COMMAND_LISTENING,

    /** Recognizer finished (result/error/timeout); command is being processed once. */
    PROCESSING_COMMAND,

    /** Response spoken / flow finished; waiting for wake detection to resume. */
    RETURNING_TO_STANDBY
  }

  /** Reason a command session reached its terminal callback. */
  enum class CommandEndReason { RESULT, ERROR, TIMEOUT, SILENCE }

  data class Snapshot(
    val state: State,
    val sessionId: String?,
    val wakeAcceptedAtMs: Long,
    val commandStartedAtMs: Long,
    val commandEndedAtMs: Long,
    val wakeResumedAtMs: Long,
    val lastCommandEndReason: CommandEndReason?
  ) {
    val isSessionActive: Boolean
      get() = state != State.STANDBY && state != State.IDLE
  }

  private val state = AtomicReference(State.IDLE)
  private val sessionId = AtomicReference<String?>(null)

  @Volatile private var wakeAcceptedAtMs = 0L
  @Volatile private var commandStartedAtMs = 0L
  @Volatile private var commandEndedAtMs = 0L
  @Volatile private var wakeResumedAtMs = 0L
  @Volatile private var lastCommandEndReason: CommandEndReason? = null

  /** Milliseconds a WAKE_TRIGGERED session may sit before the command must start. */
  var wakeToCommandTimeoutMs: Long = 10_000L

  // ---------------------------------------------------------------------
  // Wake word side
  // ---------------------------------------------------------------------

  /**
   * STANDBY with wake detection armed. When called from STANDBY, the pending
   * session bookkeeping is cleared so the next wake creates a fresh session.
   */
  fun enterStandby() {
    state.set(State.STANDBY)
    sessionId.set(null)
    wakeAcceptedAtMs = 0L
    commandStartedAtMs = 0L
    commandEndedAtMs = 0L
    lastCommandEndReason = null
    wakeResumedAtMs = 0L
  }

  /**
   * A wake-word candidate arrived. Accepted exactly once per session: while a
   * session is active (or already mid-wake), every further wake is IGNORED.
   * Returns the fresh unique session id, or null when the trigger is a duplicate.
   */
  fun onWakeDetected(keyword: String): String? {
    val now = clock()
    // Lock the "wake accepted" window first so concurrent callbacks can't both pass.
    val fresh = sessionId.get() == null &&
      (state.get() == State.STANDBY || state.get() == State.IDLE || state.get() == State.RETURNING_TO_STANDBY)
    if (!fresh) return null

    if (!sessionId.compareAndSet(null, UUID.randomUUID().toString())) return null

    state.set(State.WAKE_TRIGGERED)
    wakeAcceptedAtMs = now
    commandStartedAtMs = 0L
    commandEndedAtMs = 0L
    lastCommandEndReason = null
    return sessionId.get()
  }

  /** True when a wake session exists but no command has started yet. */
  fun isAwaitingCommand(): Boolean = state.get() == State.WAKE_TRIGGERED

  /**
   * Command listening is about to start for the current session. Succeeds exactly
   * once per session; subsequent calls return null (duplicate protection).
   */
  fun onCommandListeningStarted(): String? {
    val now = clock()
    if (state.get() != State.WAKE_TRIGGERED) return null
    state.set(State.COMMAND_LISTENING)
    commandStartedAtMs = now
    return sessionId.get()
  }

  /**
   * STANDBY re-entry guard: while a voice session is active, standalone command
   * entry points (orb tap, HUD mic button) must not open overlapping sessions.
   */
  fun canStartCommandWithoutWake(): Boolean {
    return when (state.get()) {
      State.STANDBY, State.IDLE, State.RETURNING_TO_STANDBY -> true
      else -> false
    }
  }

  /**
   * Manual (non-wake) command entry: opens a session directly in COMMAND_LISTENING.
   * Returns the session id, or null when a session is already active.
   */
  fun onManualCommandRequested(): String? {
    val now = clock()
    if (!canStartCommandWithoutWake()) return null
    // A previous session may have just ended (RETURNING_TO_STANDBY) without wake
    // re-arm ever running (wake disabled) — clear its id so a NEW manual session
    // can always start. Only fully-ended sessions are recycled this way.
    if (state.get() == State.RETURNING_TO_STANDBY) {
      sessionId.set(null)
    }
    if (!sessionId.compareAndSet(null, UUID.randomUUID().toString())) return null
    state.set(State.COMMAND_LISTENING)
    wakeAcceptedAtMs = 0L
    commandStartedAtMs = now
    commandEndedAtMs = 0L
    lastCommandEndReason = null
    return sessionId.get()
  }

  // ---------------------------------------------------------------------
  // Command side
  // ---------------------------------------------------------------------

  /**
   * A command-terminal callback (onResults / onError / watchdog timeout).
   * Closes the command phase exactly once per session; duplicate callbacks for
   * the same session return false. A wake-only session (no command started,
   * e.g. "D-VEX" then silence) also lands here and ends honestly with no action.
   */
  fun onCommandFinished(reason: CommandEndReason): Boolean {
    val now = clock()
    val current = state.get()
    // PROCESSING_COMMAND deliberately does NOT close here: once a command was
    // accepted, the brain/response path owns the session and late duplicate
    // recognizer callbacks (onError after onResults, etc.) must be ignored.
    val closed = when (current) {
      State.COMMAND_LISTENING -> true
      // Wake-only session: user said just "D-VEX" and said nothing else, or the
      // command window elapsed. Close the session honestly — no action taken.
      State.WAKE_TRIGGERED -> true
      else -> false
    }
    if (!closed) return false

    state.set(State.PROCESSING_COMMAND)
    commandEndedAtMs = now
    lastCommandEndReason = reason
    return true
  }

  /**
   * Marks the recognized command as accepted for processing (once). Returns false
   * when no command is pending (e.g. silence already closed the session).
   */
  fun markCommandProcessing(): Boolean {
    return state.compareAndSet(State.COMMAND_LISTENING, State.PROCESSING_COMMAND)
  }

  /** True when PROCESSING_COMMAND is armed but the response has not finished. */
  fun isProcessingCommand(): Boolean = state.get() == State.PROCESSING_COMMAND

  /**
   * Bounded re-listen for a pending safety confirmation ("yes/no") within the SAME
   * session. Valid only from PROCESSING_COMMAND (the previous command callback already
   * closed its listen window). Re-opens exactly one listening window — this is not a
   * restart loop; the session can hold at most one confirmation re-listen at a time.
   * Returns the session id, or null when no confirmation window is pending.
   */
  fun onCommandRelistenRequested(): String? {
    if (state.get() != State.PROCESSING_COMMAND) return null
    state.set(State.COMMAND_LISTENING)
    commandStartedAtMs = clock()
    commandEndedAtMs = 0L
    return sessionId.get()
  }

  /**
   * Response finished: the session fully ends. Only after this call may wake-word
   * detection resume. Returns true exactly once per session.
   */
  fun onSessionComplete(): Boolean {
    if (state.get() != State.PROCESSING_COMMAND) return false
    state.set(State.RETURNING_TO_STANDBY)
    return true
  }

  /**
   * Wake-word detection may resume ONLY after [onSessionComplete] — i.e. the
   * command session is closed and the response finished. Returns true exactly
   * once per session; the machine then sits in RETURNING_TO_STANDBY until
   * [wakeResumed] confirms the detector is armed again.
   */
  fun canResumeWake(): Boolean {
    if (state.get() != State.RETURNING_TO_STANDBY) return false
    return wakeResumedAtMs == 0L
  }

  /** Records that the wake detector has actually been re-armed. */
  fun wakeResumed() {
    if (state.get() == State.RETURNING_TO_STANDBY) {
      wakeResumedAtMs = clock()
      // Back to STANDBY: allow the next wake to create a brand-new session.
      state.set(State.STANDBY)
      sessionId.set(null)
      wakeAcceptedAtMs = 0L
      commandStartedAtMs = 0L
      commandEndedAtMs = 0L
      lastCommandEndReason = null
      // Reset so the NEXT session can re-arm wake detection after it ends.
      wakeResumedAtMs = 0L
    }
  }

  /** True while a wake or command session is active (wake detector must be off). */
  fun isSessionActive(): Boolean {
    return when (state.get()) {
      State.WAKE_TRIGGERED, State.COMMAND_LISTENING, State.PROCESSING_COMMAND -> true
      else -> false
    }
  }

  /** True when a command listen window is currently open (COMMAND_LISTENING). */
  fun isListeningWindowOpen(): Boolean = state.get() == State.COMMAND_LISTENING

  /**
   * Single session-end authority: marks the response as finished so wake detection
   * may resume. Returns true exactly once per session (false on duplicates).
   */
  fun endSessionAfterResponse(): Boolean = onSessionComplete()

  /** True when the current session has exceeded its wake→command window. */
  fun isWakeToCommandTimedOut(nowMs: Long = clock()): Boolean {
    return state.get() == State.WAKE_TRIGGERED &&
      wakeAcceptedAtMs > 0 &&
      (nowMs - wakeAcceptedAtMs) > wakeToCommandTimeoutMs
  }

  /**
   * Authoritative force-close from ANY active state (watchdog recovery, stop
   * button, service shutdown). Returns true exactly once per active session.
   * Unlike [onCommandFinished], this may also close PROCESSING_COMMAND.
   */
  fun forceEndSession(): Boolean {
    val current = state.get()
    val wasActive = current == State.WAKE_TRIGGERED ||
      current == State.COMMAND_LISTENING ||
      current == State.PROCESSING_COMMAND
    if (wasActive) {
      state.set(State.RETURNING_TO_STANDBY)
      sessionId.set(null)
    }
    return wasActive
  }

  /** Force-close any active session (service shutdown, fatal error). */
  fun abortSession() {
    state.set(State.RETURNING_TO_STANDBY)
    sessionId.set(null)
  }

  /** Current immutable snapshot for observability and tests. */
  fun snapshot(): Snapshot = Snapshot(
    state = state.get(),
    sessionId = sessionId.get(),
    wakeAcceptedAtMs = wakeAcceptedAtMs,
    commandStartedAtMs = commandStartedAtMs,
    commandEndedAtMs = commandEndedAtMs,
    wakeResumedAtMs = wakeResumedAtMs,
    lastCommandEndReason = lastCommandEndReason
  )
}

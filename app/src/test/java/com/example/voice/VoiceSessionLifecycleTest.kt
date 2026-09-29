package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Voice interaction lifecycle protocol tests (wake -> command -> standby).
 *
 * Locks the exact session protocol:
 * - wake detected once (exactly one session created per wake)
 * - duplicate wake callbacks are ignored while a session is active
 * - command listening starts exactly once per session
 * - duplicate command-terminal callbacks (result/error/timeout) are ignored
 * - command completion ends the session and STANDBY is restored
 * - "D-VEX" then silence (wake-only session) closes honestly with no action
 * - wake detection may resume ONLY after the command session fully ends
 *
 * Pure JVM tests — the state machine has no Android dependencies.
 */
class VoiceSessionLifecycleTest {

  private lateinit var machine: VoiceSessionStateMachine
  private var fakeNowMs = 1_000_000L

  @Before
  fun setUp() {
    fakeNowMs = 1_000_000L
    machine = VoiceSessionStateMachine(clock = { fakeNowMs })
  }

  private fun advanceTime(ms: Long) {
    fakeNowMs += ms
  }

  // ---------------------------------------------------------------
  // 1. Wake detected once
  // ---------------------------------------------------------------

  @Test
  fun `wake detected once creates exactly one session`() {
    machine.enterStandby()

    val sessionId = machine.onWakeDetected("D-VEX")

    assertNotNull("First wake must create a session", sessionId)
    assertEquals(VoiceSessionStateMachine.State.WAKE_TRIGGERED, machine.snapshot().state)
    assertTrue(machine.isSessionActive())
    assertTrue(machine.isAwaitingCommand())
  }

  @Test
  fun `successive wakes after full session cycle each create fresh sessions`() {
    machine.enterStandby()
    val first = machine.onWakeDetected("D-VEX")
    machine.onCommandListeningStarted()
    machine.onCommandFinished(VoiceSessionStateMachine.CommandEndReason.RESULT)
    machine.endSessionAfterResponse()
    machine.wakeResumed()

    val second = machine.onWakeDetected("D-VEX")

    assertNotNull("A completed cycle must allow a new wake session", second)
    assertNotEquals("Session ids must be unique per wake", first, second)
  }

  // ---------------------------------------------------------------
  // 2. Duplicate wake ignored
  // ---------------------------------------------------------------

  @Test
  fun `duplicate wake ignored while session active`() {
    machine.enterStandby()
    val first = machine.onWakeDetected("D-VEX")

    val duplicate = machine.onWakeDetected("D-VEX")
    val duplicate2 = machine.onWakeDetected("hey d vex")

    assertNull("Duplicate wake must be ignored", duplicate)
    assertNull("Second duplicate wake must be ignored", duplicate2)
    assertEquals(
      "State must still be the original wake session",
      VoiceSessionStateMachine.State.WAKE_TRIGGERED,
      machine.snapshot().state
    )
    assertEquals("Session id must be unchanged", first, machine.snapshot().sessionId)
  }

  @Test
  fun `wake ignored while command listening`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    machine.onCommandListeningStarted()

    val lateWake = machine.onWakeDetected("D-VEX")

    assertNull("Wake must never be accepted while command recognizer is active", lateWake)
    assertEquals(VoiceSessionStateMachine.State.COMMAND_LISTENING, machine.snapshot().state)
  }

  // ---------------------------------------------------------------
  // 3. Command starts once
  // ---------------------------------------------------------------

  @Test
  fun `command listening starts exactly once per wake session`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")

    val first = machine.onCommandListeningStarted()
    val duplicate = machine.onCommandListeningStarted()

    assertNotNull("First command start must succeed", first)
    assertNull("Second command start must be refused (exactly-once)", duplicate)
    assertEquals(VoiceSessionStateMachine.State.COMMAND_LISTENING, machine.snapshot().state)
  }

  @Test
  fun `command cannot start without a wake session`() {
    machine.enterStandby()

    val result = machine.onCommandListeningStarted()

    assertNull("Command listening must not start without a wake session", result)
  }

  // ---------------------------------------------------------------
  // 4. Duplicate command callback ignored
  // ---------------------------------------------------------------

  @Test
  fun `duplicate command result callback ignored`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    machine.onCommandListeningStarted()

    val first = machine.onCommandFinished(VoiceSessionStateMachine.CommandEndReason.RESULT)
    val duplicate = machine.onCommandFinished(VoiceSessionStateMachine.CommandEndReason.RESULT)
    val duplicateError = machine.onCommandFinished(VoiceSessionStateMachine.CommandEndReason.ERROR)

    assertTrue("First terminal callback must close the command phase", first)
    assertFalse("Duplicate result must be ignored", duplicate)
    assertFalse("Late error after result must be ignored", duplicateError)
  }

  @Test
  fun `late duplicate error after command accepted is ignored`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    machine.onCommandListeningStarted()
    machine.markCommandProcessing()

    val lateError = machine.onCommandFinished(VoiceSessionStateMachine.CommandEndReason.ERROR)

    assertFalse(
      "Once the command is accepted for processing, late recognizer errors must be ignored",
      lateError
    )
  }

  // ---------------------------------------------------------------
  // 5. Command completes -> standby
  // ---------------------------------------------------------------

  @Test
  fun `command completion ends session and restores standby`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    machine.onCommandListeningStarted()
    machine.markCommandProcessing()

    val ended = machine.endSessionAfterResponse()

    assertTrue("Session must end after the response", ended)
    assertEquals(
      VoiceSessionStateMachine.State.RETURNING_TO_STANDBY,
      machine.snapshot().state
    )
    assertFalse("No active session remains", machine.isSessionActive())
    assertTrue("Wake detection may resume after session end", machine.canResumeWake())

    machine.wakeResumed()
    assertEquals(
      "Machine must sit in STANDBY after wake is re-armed",
      VoiceSessionStateMachine.State.STANDBY,
      machine.snapshot().state
    )
  }

  @Test
  fun `session end and wake resume are each exactly-once`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    machine.onCommandListeningStarted()
    machine.markCommandProcessing()

    assertTrue(machine.endSessionAfterResponse())
    assertFalse("Duplicate session-end must be ignored", machine.endSessionAfterResponse())

    machine.wakeResumed()
    // After STANDBY, canResumeWake resets for the next session.
    assertFalse(
      "canResumeWake must not re-trigger for the already-completed session",
      machine.canResumeWake() && machine.snapshot().state == VoiceSessionStateMachine.State.RETURNING_TO_STANDBY
    )
  }

  // ---------------------------------------------------------------
  // 6. No command after wake ("D-VEX" then silence) -> standby
  // ---------------------------------------------------------------

  @Test
  fun `wake-only session with no command closes honestly with no action`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    advanceTime(10_000)

    // User said nothing: the timeout path closes the session via the
    // WAKE_TRIGGERED -> closed transition (no command was ever accepted).
    val closed = machine.onCommandFinished(VoiceSessionStateMachine.CommandEndReason.TIMEOUT)

    assertTrue("Wake-only session must close on timeout", closed)
    assertEquals(
      VoiceSessionStateMachine.CommandEndReason.TIMEOUT,
      machine.snapshot().lastCommandEndReason
    )
    // endCommandSession(reason != RESULT) then ends the whole session:
    machine.endSessionAfterResponse()
    assertFalse("Session must not remain active after silence", machine.isSessionActive())
    assertTrue("Wake detection resumes after silent timeout", machine.canResumeWake())
    machine.wakeResumed()
    assertEquals(VoiceSessionStateMachine.State.STANDBY, machine.snapshot().state)
  }

  @Test
  fun `wake to command timeout is detected only after the window elapses`() {
    machine.wakeToCommandTimeoutMs = 10_000
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")

    advanceTime(9_000)
    assertFalse("9s into a 10s window is not yet a timeout", machine.isWakeToCommandTimedOut())

    advanceTime(1_500)
    assertTrue("Past the window with no command must be a timeout", machine.isWakeToCommandTimedOut())
  }

  @Test
  fun `wake-only session interrupted by silence error also returns to standby`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    machine.onCommandListeningStarted()

    // SpeechRecognizer delivered ERROR_SPEECH_TIMEOUT -> mapped to SILENCE.
    assertTrue(machine.onCommandFinished(VoiceSessionStateMachine.CommandEndReason.SILENCE))
    machine.endSessionAfterResponse()
    machine.wakeResumed()

    assertEquals(VoiceSessionStateMachine.State.STANDBY, machine.snapshot().state)
    assertNull("No command was processed", machine.snapshot().sessionId)
  }

  // ---------------------------------------------------------------
  // 7. Wake detector resumes only after command session ends
  // ---------------------------------------------------------------

  @Test
  fun `wake resume refused until session fully ends`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")

    // Still WAKE_TRIGGERED: no resume.
    assertFalse("Wake must not resume mid-wake-session", machine.canResumeWake())
    machine.onCommandListeningStarted()
    assertFalse("Wake must not resume while command listening", machine.canResumeWake())
    machine.markCommandProcessing()
    assertFalse("Wake must not resume while processing", machine.canResumeWake())

    machine.endSessionAfterResponse()
    assertTrue(
      "Wake resumes only after the response finished (session ended)",
      machine.canResumeWake()
    )
  }

  @Test
  fun `full protocol cycle keeps wake detector off for the whole command`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    assertTrue("Wake session active -> detector must be off", machine.isSessionActive())

    machine.onCommandListeningStarted()
    assertTrue("Command listening -> detector must be off", machine.isSessionActive())

    machine.markCommandProcessing()
    assertTrue("Processing -> detector must be off", machine.isSessionActive())

    machine.endSessionAfterResponse()
    assertFalse("Only after full completion is the session inactive", machine.isSessionActive())
    assertTrue(machine.canResumeWake())
  }

  // ---------------------------------------------------------------
  // Manual (non-wake) command entry: orb tap / HUD mic
  // ---------------------------------------------------------------

  @Test
  fun `manual command allowed when idle and creates a listening session`() {
    val sessionId = machine.onManualCommandRequested()

    assertNotNull("Manual entry from IDLE must be allowed", sessionId)
    assertEquals(VoiceSessionStateMachine.State.COMMAND_LISTENING, machine.snapshot().state)
  }

  @Test
  fun `manual command refused while wake session active`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")

    val result = machine.onManualCommandRequested()

    assertNull("Manual entry must not stack on an active wake session", result)
  }

  @Test
  fun `manual command allowed again after previous session ends without wake resume`() {
    machine.onManualCommandRequested()
    machine.onCommandFinished(VoiceSessionStateMachine.CommandEndReason.RESULT)
    machine.endSessionAfterResponse()
    // Wake disabled in settings: wakeResumed() never runs.
    val next = machine.onManualCommandRequested()

    assertNotNull("New manual session must be possible after the last ended", next)
  }

  // ---------------------------------------------------------------
  // Confirmation re-listen (bounded, same session)
  // ---------------------------------------------------------------

  @Test
  fun `confirmation relisten allowed once from processing state`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    machine.onCommandListeningStarted()
    machine.markCommandProcessing()

    val relisten = machine.onCommandRelistenRequested()

    assertNotNull("Confirmation re-listen must open within the same session", relisten)
    assertEquals(machine.snapshot().sessionId, relisten)
    assertEquals(VoiceSessionStateMachine.State.COMMAND_LISTENING, machine.snapshot().state)
  }

  @Test
  fun `confirmation relisten refused while already listening or idle`() {
    assertNull(machine.onCommandRelistenRequested())

    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    assertNull("Re-listen refused before any command window opened", machine.onCommandRelistenRequested())
  }

  // ---------------------------------------------------------------
  // Watchdog / authority force-close
  // ---------------------------------------------------------------

  @Test
  fun `forceEndSession closes any active state exactly once and allows wake resume`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")
    machine.onCommandListeningStarted()

    assertTrue(machine.forceEndSession())
    assertFalse("Second force-close is a no-op", machine.forceEndSession())
    assertFalse(machine.isSessionActive())
    assertTrue("Force-closed session must re-arm wake detection", machine.canResumeWake())
  }

  @Test
  fun `abortSession clears everything for shutdown`() {
    machine.enterStandby()
    machine.onWakeDetected("D-VEX")

    machine.abortSession()

    assertFalse(machine.isSessionActive())
    assertNull(machine.snapshot().sessionId)
  }
}

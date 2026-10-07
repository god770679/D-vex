package com.example.model

import com.example.agent.AgentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ORB DISPLAY STATE — honesty contract for the floating Orb's state projection.
 *
 * The Orb must reflect REAL state only. These tests pin:
 *  - the documented priority order (ERROR always wins, CONFIRM before anything active),
 *  - LISTENING derived exclusively from DvexAssistantState.Listening (AgentState.LISTENING
 *    is never transitioned in production and must NOT fabricate an Orb LISTENING state),
 *  - WAITING_FOR_PERMISSION rendering as an honest confirmation state ("CONFIRM?"),
 *  - no output state that is not represented by the actual inputs.
 */
class OrbDisplayStateTest {

  private fun map(agent: AgentState, voice: DvexAssistantState): OrbDisplayState =
    OrbDisplayStateMapper.map(agent, voice)

  // ---------------------------------------------------------------- IDLE

  @Test
  fun idle_agentWithIdleVoice_mapsToIdle() {
    assertEquals(OrbDisplayState.IDLE, map(AgentState.IDLE, DvexAssistantState.Idle))
    assertEquals(OrbDisplayState.IDLE, map(AgentState.IDLE, DvexAssistantState.Standby))
    assertEquals(OrbDisplayState.IDLE, map(AgentState.IDLE, DvexAssistantState.WakeWordListening))
    assertEquals("IDLE", OrbDisplayState.IDLE.label)
  }

  // ------------------------------------------------------------ LISTENING

  @Test
  fun voiceListening_mapsToListening() {
    assertEquals(OrbDisplayState.LISTENING, map(AgentState.IDLE, DvexAssistantState.Listening))
    assertEquals("LISTENING", OrbDisplayState.LISTENING.label)
  }

  @Test
  fun agentListeningAlone_mustNotFabricateListening() {
    // AgentState.LISTENING is never transitioned in production — with voice Idle
    // the Orb must stay honest and show IDLE, never LISTENING.
    assertEquals(OrbDisplayState.IDLE, map(AgentState.LISTENING, DvexAssistantState.Idle))
    assertEquals(OrbDisplayState.IDLE, map(AgentState.LISTENING, DvexAssistantState.Standby))
    assertEquals(OrbDisplayState.IDLE, map(AgentState.LISTENING, DvexAssistantState.WakeWordListening))
  }

  // ------------------------------------------------------------ THINKING

  @Test
  fun agentUnderstanding_mapsToThinking() {
    assertEquals(OrbDisplayState.THINKING, map(AgentState.UNDERSTANDING, DvexAssistantState.Idle))
    assertEquals(OrbDisplayState.THINKING, map(AgentState.UNDERSTANDING, DvexAssistantState.Processing))
    assertEquals("THINKING", OrbDisplayState.THINKING.label)
  }

  @Test
  fun voiceProcessing_withoutAgentEngine_mapsToThinking() {
    // Brain-only turns (local fallback / deterministic commands) never touch the
    // agent bus; voice Processing is the real THINKING signal for them.
    assertEquals(OrbDisplayState.THINKING, map(AgentState.IDLE, DvexAssistantState.Processing))
  }

  // ------------------------------------------------------------- PLANNING

  @Test
  fun agentPlanning_mapsToPlanning() {
    assertEquals(OrbDisplayState.PLANNING, map(AgentState.PLANNING, DvexAssistantState.Idle))
    assertEquals(OrbDisplayState.PLANNING, map(AgentState.PLANNING, DvexAssistantState.Processing))
    assertEquals("PLANNING", OrbDisplayState.PLANNING.label)
  }

  // ------------------------------------------------------------ EXECUTING

  @Test
  fun agentExecuting_mapsToExecuting() {
    assertEquals(OrbDisplayState.EXECUTING, map(AgentState.EXECUTING, DvexAssistantState.Processing))
    assertEquals(OrbDisplayState.EXECUTING, map(AgentState.EXECUTING, DvexAssistantState.Idle))
    assertEquals("EXECUTING", OrbDisplayState.EXECUTING.label)
  }

  @Test
  fun voiceExecutingAction_withoutAgentEngine_mapsToExecuting() {
    assertEquals(
      OrbDisplayState.EXECUTING,
      map(AgentState.IDLE, DvexAssistantState.ExecutingAction("open_app"))
    )
  }

  // ------------------------------------------------------------ VERIFYING

  @Test
  fun agentVerifying_mapsToVerifying() {
    assertEquals(OrbDisplayState.VERIFYING, map(AgentState.VERIFYING, DvexAssistantState.Processing))
    assertEquals(OrbDisplayState.VERIFYING, map(AgentState.VERIFYING, DvexAssistantState.ExecutingAction("open_app")))
    assertEquals("VERIFYING", OrbDisplayState.VERIFYING.label)
  }

  // ------------------------------------------------------------ SPEAKING

  @Test
  fun agentResponding_mapsToSpeaking() {
    assertEquals(OrbDisplayState.SPEAKING, map(AgentState.RESPONDING, DvexAssistantState.Processing))
    assertEquals("SPEAKING", OrbDisplayState.SPEAKING.label)
  }

  @Test
  fun voiceSpeaking_mapsToSpeaking() {
    assertEquals(OrbDisplayState.SPEAKING, map(AgentState.IDLE, DvexAssistantState.Speaking("done")))
  }

  // ---------------------------------------------------------------- ERROR

  @Test
  fun error_alwaysWins_overEveryAgentState() {
    AgentState.entries.forEach { agent ->
      assertEquals(
        "voice Error must beat agent state $agent",
        OrbDisplayState.ERROR,
        map(agent, DvexAssistantState.Error("boom"))
      )
    }
  }

  @Test
  fun agentError_alwaysWins_overEveryVoiceState() {
    listOf<DvexAssistantState>(
      DvexAssistantState.Idle,
      DvexAssistantState.Standby,
      DvexAssistantState.WakeWordListening,
      DvexAssistantState.Listening,
      DvexAssistantState.Processing,
      DvexAssistantState.ExecutingAction("open_app"),
      DvexAssistantState.Speaking("ok"),
      DvexAssistantState.Error("nested")
    ).forEach { voice ->
      assertEquals(
        "agent ERROR must beat voice state $voice",
        OrbDisplayState.ERROR,
        map(AgentState.ERROR, voice)
      )
    }
    assertEquals("ERROR", OrbDisplayState.ERROR.label)
  }

  @Test
  fun error_beatsWaitingForPermissionAndListening() {
    assertEquals(OrbDisplayState.ERROR, map(AgentState.ERROR, DvexAssistantState.Listening))
    assertEquals(OrbDisplayState.ERROR, map(AgentState.WAITING_FOR_PERMISSION, DvexAssistantState.Error("x")))
  }

  // -------------------------------------------------------------- CONFIRM

  @Test
  fun waitingForPermission_mapsToHonestConfirmState() {
    assertEquals(
      OrbDisplayState.CONFIRM,
      map(AgentState.WAITING_FOR_PERMISSION, DvexAssistantState.Processing)
    )
    assertEquals(
      OrbDisplayState.CONFIRM,
      map(AgentState.WAITING_FOR_PERMISSION, DvexAssistantState.Idle)
    )
    assertEquals("CONFIRM?", OrbDisplayState.CONFIRM.label)
  }

  @Test
  fun waitingForPermission_beatsListeningAndActiveVoiceStates() {
    assertEquals(OrbDisplayState.CONFIRM, map(AgentState.WAITING_FOR_PERMISSION, DvexAssistantState.Listening))
    assertEquals(OrbDisplayState.CONFIRM, map(AgentState.WAITING_FOR_PERMISSION, DvexAssistantState.ExecutingAction("a")))
  }

  // ----------------------------------------------------- PRIORITY / SANITY

  @Test
  fun voiceListening_beatsAgentPlanning() {
    // Documented priority: voice LISTENING sits above agent PLANNING.
    assertEquals(OrbDisplayState.LISTENING, map(AgentState.PLANNING, DvexAssistantState.Listening))
  }

  @Test
  fun agentPlanning_beatsVoiceProcessing() {
    assertEquals(OrbDisplayState.PLANNING, map(AgentState.PLANNING, DvexAssistantState.Processing))
  }

  @Test
  fun everyInputCombination_producesOnlyRealRepresentableStates() {
    val voices = listOf<DvexAssistantState>(
      DvexAssistantState.Idle,
      DvexAssistantState.Standby,
      DvexAssistantState.WakeWordListening,
      DvexAssistantState.Listening,
      DvexAssistantState.Processing,
      DvexAssistantState.ExecutingAction("open_app"),
      DvexAssistantState.Speaking("ok"),
      DvexAssistantState.Error("boom")
    )
    AgentState.entries.forEach { agent ->
      voices.forEach { voice ->
        val result = map(agent, voice)
        assertTrue(
          "$agent × $voice produced $result which is not a real OrbDisplayState",
          OrbDisplayState.entries.contains(result)
        )
        assertTrue("every state must expose a non-blank label", result.label.isNotBlank())
      }
    }
  }

  @Test
  fun noImpossibleState_outputsMatchDocumentedTable() {
    // Full documented mapping table — any behavior change must be deliberate.
    val expected = mapOf(
      // IDLE
      (AgentState.IDLE to DvexAssistantState.Idle) to OrbDisplayState.IDLE,
      (AgentState.IDLE to DvexAssistantState.Standby) to OrbDisplayState.IDLE,
      (AgentState.IDLE to DvexAssistantState.WakeWordListening) to OrbDisplayState.IDLE,
      // LISTENING only from voice
      (AgentState.IDLE to DvexAssistantState.Listening) to OrbDisplayState.LISTENING,
      (AgentState.LISTENING to DvexAssistantState.Listening) to OrbDisplayState.LISTENING,
      // THINKING
      (AgentState.UNDERSTANDING to DvexAssistantState.Processing) to OrbDisplayState.THINKING,
      (AgentState.IDLE to DvexAssistantState.Processing) to OrbDisplayState.THINKING,
      // PLANNING
      (AgentState.PLANNING to DvexAssistantState.Processing) to OrbDisplayState.PLANNING,
      // EXECUTING
      (AgentState.EXECUTING to DvexAssistantState.Processing) to OrbDisplayState.EXECUTING,
      (AgentState.IDLE to DvexAssistantState.ExecutingAction("open_app")) to OrbDisplayState.EXECUTING,
      // VERIFYING
      (AgentState.VERIFYING to DvexAssistantState.Processing) to OrbDisplayState.VERIFYING,
      // SPEAKING
      (AgentState.RESPONDING to DvexAssistantState.Idle) to OrbDisplayState.SPEAKING,
      (AgentState.IDLE to DvexAssistantState.Speaking("ok")) to OrbDisplayState.SPEAKING,
      // CONFIRM
      (AgentState.WAITING_FOR_PERMISSION to DvexAssistantState.Idle) to OrbDisplayState.CONFIRM,
      // ERROR
      (AgentState.ERROR to DvexAssistantState.Idle) to OrbDisplayState.ERROR,
      (AgentState.IDLE to DvexAssistantState.Error("boom")) to OrbDisplayState.ERROR
    )
    expected.forEach { (inputs, expectedState) ->
      assertEquals(
        "map(${inputs.first}, ${inputs.second})",
        expectedState,
        map(inputs.first, inputs.second)
      )
    }
  }
}

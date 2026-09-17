package com.example

import com.example.data.remote.ToolExecutionResult
import com.example.data.remote.ToolResultStatus
import com.example.model.AiCoreState
import com.example.model.DvexAssistantState
import com.example.model.DvexSettings
import com.example.model.VoiceState
import com.example.voice.WakeWordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DvexAssistantUnitTest {

  @Test
  fun testAssistantStateToAiCoreStateMapping() {
    assertEquals(AiCoreState.IDLE, DvexAssistantState.Idle.toAiCoreState())
    assertEquals(AiCoreState.IDLE, DvexAssistantState.Standby.toAiCoreState())
    assertEquals(AiCoreState.LISTENING, DvexAssistantState.Listening.toAiCoreState())
    assertEquals(AiCoreState.PROCESSING, DvexAssistantState.Processing.toAiCoreState())
    assertEquals(AiCoreState.EXECUTING, DvexAssistantState.ExecutingAction("launch_app").toAiCoreState())
    assertEquals(AiCoreState.RESPONDING, DvexAssistantState.Speaking("Standing by.").toAiCoreState())
    assertEquals(AiCoreState.ERROR, DvexAssistantState.Error("Mic timeout").toAiCoreState())
  }

  @Test
  fun testAssistantStateToVoiceStateMapping() {
    assertEquals(VoiceState.IDLE, DvexAssistantState.Idle.toVoiceState())
    assertEquals(VoiceState.STANDBY, DvexAssistantState.Standby.toVoiceState())
    assertEquals(VoiceState.LISTENING, DvexAssistantState.Listening.toVoiceState())
    assertEquals(VoiceState.PROCESSING, DvexAssistantState.Processing.toVoiceState())
    assertEquals(VoiceState.EXECUTING_ACTION, DvexAssistantState.ExecutingAction("open_youtube").toVoiceState())
    assertEquals(VoiceState.SPEAKING, DvexAssistantState.Speaking("Opening YouTube.").toVoiceState())
    assertEquals(VoiceState.ERROR, DvexAssistantState.Error("Network error").toVoiceState())
  }

  @Test
  fun testToolExecutionResultSafety() {
    val successResult = ToolExecutionResult(
      status = ToolResultStatus.SUCCESS,
      toolName = "camera",
      message = "Opening Camera."
    )
    assertTrue(successResult.isSuccessful)
    assertFalse(successResult.requiresConfirmation)

    val sensitiveResult = ToolExecutionResult(
      status = ToolResultStatus.CONFIRMATION_REQUIRED,
      toolName = "phone_call",
      message = "Safety confirmation required",
      requiresConfirmation = true,
      confirmationPrompt = "Confirm call to Command?"
    )
    assertFalse(sensitiveResult.isSuccessful)
    assertTrue(sensitiveResult.requiresConfirmation)
  }

  @Test
  fun testWakeWordStateTransitions() {
    val disabled: WakeWordState = WakeWordState.Disabled
    val standby: WakeWordState = WakeWordState.Standby
    val listening: WakeWordState = WakeWordState.Listening
    val triggered: WakeWordState = WakeWordState.Triggered("D-VEX", 0.98f)
    val error: WakeWordState = WakeWordState.Error("Mic busy")

    assertTrue(disabled is WakeWordState.Disabled)
    assertTrue(standby is WakeWordState.Standby)
    assertTrue(listening is WakeWordState.Listening)
    assertTrue(triggered is WakeWordState.Triggered)
    assertEquals("D-VEX", (triggered as WakeWordState.Triggered).phrase)
    assertTrue(error is WakeWordState.Error)
  }

  @Test
  fun testCompleteVoicePipelineLifecycleFlow() {
    // 1. Initial Standby
    var state: DvexAssistantState = DvexAssistantState.Standby
    assertEquals(AiCoreState.IDLE, state.toAiCoreState())
    assertEquals(VoiceState.STANDBY, state.toVoiceState())

    // 2. Wake phrase "D-VEX" triggered -> Listening
    state = DvexAssistantState.Listening
    assertEquals(AiCoreState.LISTENING, state.toAiCoreState())
    assertEquals(VoiceState.LISTENING, state.toVoiceState())

    // 3. User spoken command -> Processing
    state = DvexAssistantState.Processing
    assertEquals(AiCoreState.PROCESSING, state.toAiCoreState())
    assertEquals(VoiceState.PROCESSING, state.toVoiceState())

    // 4. Action routed -> ExecutingAction
    state = DvexAssistantState.ExecutingAction("open_youtube")
    assertEquals(AiCoreState.EXECUTING, state.toAiCoreState())
    assertEquals(VoiceState.EXECUTING_ACTION, state.toVoiceState())

    // 5. Action complete -> Speaking transmission
    state = DvexAssistantState.Speaking("Opening YouTube.")
    assertEquals(AiCoreState.RESPONDING, state.toAiCoreState())
    assertEquals(VoiceState.SPEAKING, state.toVoiceState())

    // 6. Return to Standby
    state = DvexAssistantState.Standby
    assertEquals(AiCoreState.IDLE, state.toAiCoreState())
    assertEquals(VoiceState.STANDBY, state.toVoiceState())
  }

  @Test
  fun testAssistantErrorStateRecovery() {
    val errorState = DvexAssistantState.Error("Microphone timeout")
    assertEquals(AiCoreState.ERROR, errorState.toAiCoreState())
    assertEquals(VoiceState.ERROR, errorState.toVoiceState())
    assertEquals("ALERT: Microphone timeout", errorState.label)
  }

  @Test
  fun testDvexSettingsDefaults() {
    val settings = DvexSettings()
    assertFalse(settings.alwaysReadyEnabled)
    assertFalse(settings.floatingOrbEnabled)
    assertFalse(settings.wakeWordEnabled)
    assertEquals("D-VEX", settings.wakeWordKeyword)
    assertEquals("D-VEX", settings.wakePhrase)
    assertTrue(settings.voiceResponseEnabled)
    assertFalse(settings.accessibilityControlEnabled)
  }
}

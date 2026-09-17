package com.example.model

/**
 * Unified assistant state shared across the main HUD, foreground service,
 * and floating overlay orb.
 *
 * Core Voice State Machine states:
 * - IDLE
 * - STANDBY
 * - LISTENING
 * - PROCESSING
 * - EXECUTING_ACTION
 * - SPEAKING
 * - ERROR
 */
sealed class DvexAssistantState {
  object Idle : DvexAssistantState()
  object Standby : DvexAssistantState()
  object WakeWordListening : DvexAssistantState() // Compatible with Standby
  object Listening : DvexAssistantState()
  object Processing : DvexAssistantState()
  data class ExecutingAction(val toolName: String) : DvexAssistantState()
  data class Speaking(val text: String) : DvexAssistantState()
  data class Error(val message: String) : DvexAssistantState()

  val label: String
    get() = when (this) {
      is Idle -> "IDLE"
      is Standby -> "STANDBY"
      is WakeWordListening -> "WAKE WORD ACTIVE"
      is Listening -> "LISTENING"
      is Processing -> "PROCESSING"
      is ExecutingAction -> "EXECUTING: $toolName"
      is Speaking -> "TRANSMITTING"
      is Error -> "ALERT: $message"
    }

  fun toAiCoreState(): AiCoreState = when (this) {
    is Idle -> AiCoreState.IDLE
    is Standby -> AiCoreState.IDLE
    is WakeWordListening -> AiCoreState.IDLE
    is Listening -> AiCoreState.LISTENING
    is Processing -> AiCoreState.PROCESSING
    is ExecutingAction -> AiCoreState.EXECUTING
    is Speaking -> AiCoreState.RESPONDING
    is Error -> AiCoreState.ERROR
  }

  fun toVoiceState(): VoiceState = when (this) {
    is Idle -> VoiceState.IDLE
    is Standby -> VoiceState.STANDBY
    is WakeWordListening -> VoiceState.STANDBY
    is Listening -> VoiceState.LISTENING
    is Processing -> VoiceState.PROCESSING
    is ExecutingAction -> VoiceState.EXECUTING_ACTION
    is Speaking -> VoiceState.SPEAKING
    is Error -> VoiceState.ERROR
  }
}

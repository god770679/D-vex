package com.example.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * D-VEX AGENT STATE MACHINE
 *
 * One observable pipeline state for the whole agent:
 * IDLE → LISTENING → UNDERSTANDING → PLANNING → (WAITING_FOR_PERMISSION) →
 * EXECUTING → VERIFYING → RESPONDING → IDLE, with ERROR as an honest dead-end.
 *
 * The UI (voice-wave box, action cards) renders this — it is never faked: the
 * engine only leaves a state when the corresponding work actually happened.
 */
enum class AgentState {
  IDLE,
  LISTENING,
  UNDERSTANDING,
  PLANNING,
  WAITING_FOR_PERMISSION,
  EXECUTING,
  VERIFYING,
  RESPONDING,
  ERROR
}

/** Single observable state holder shared by the engine and the HUD. */
object DvexAgentStateController {

  private val _state = MutableStateFlow(AgentState.IDLE)
  val state: StateFlow<AgentState> = _state.asStateFlow()

  val current: AgentState get() = _state.value

  fun transition(next: AgentState) {
    _state.value = next
  }

  fun reset() {
    _state.value = AgentState.IDLE
  }

  /** True while the agent is actually doing work (not idle/listening). */
  fun isBusy(): Boolean = _state.value != AgentState.IDLE && _state.value != AgentState.LISTENING
}

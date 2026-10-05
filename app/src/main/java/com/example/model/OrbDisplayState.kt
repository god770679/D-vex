package com.example.model

import com.example.agent.AgentState

/**
 * Display state surfaced by the EXISTING floating Orb bubble and its
 * foreground-service notification subtitle.
 *
 * This is NOT a new state machine. It is a pure, read-only projection of the
 * two already-existing state sources:
 *
 *  - [DvexAssistantState] — voice/orchestrator state owned by AssistantRepository
 *  - [AgentState]         — real agent pipeline state owned by DvexAgentStateController
 *
 * Every value emitted here is backed by an actual input transition — the Orb
 * never fabricates progress, completion, or activity that did not happen.
 */
enum class OrbDisplayState {
  /** No active work — Orb shows its existing idle behavior. */
  IDLE,

  /** Voice recognizer is actively capturing a command (from DvexAssistantState.Listening only). */
  LISTENING,

  /** The brain is understanding/analyzing an input. */
  THINKING,

  /** The agent engine is building a plan. */
  PLANNING,

  /** An admitted action is actually executing. */
  EXECUTING,

  /** Execution finished and the real verification step is running. */
  VERIFYING,

  /** A response is actually being spoken. */
  SPEAKING,

  /** An error was reported by the voice pipeline or the agent engine. */
  ERROR,

  /**
   * The agent is honestly WAITING_FOR_PERMISSION — work is paused until the
   * user confirms. Rendered as "CONFIRM?"; never shown as EXECUTING.
   */
  CONFIRM;

  /** Short status label used by the Orb bubble and notification subtitle. */
  val label: String
    get() = when (this) {
      IDLE -> "IDLE"
      LISTENING -> "LISTENING"
      THINKING -> "THINKING"
      PLANNING -> "PLANNING"
      EXECUTING -> "EXECUTING"
      VERIFYING -> "VERIFYING"
      SPEAKING -> "SPEAKING"
      ERROR -> "ERROR"
      CONFIRM -> "CONFIRM?"
    }
}

/**
 * Pure mapping with fixed priority (highest first):
 *
 *  1. ERROR — voice or agent error always wins
 *  2. WAITING_FOR_PERMISSION → CONFIRM (honest confirmation state)
 *  3. voice Listening → LISTENING
 *  4. agent PLANNING → PLANNING
 *  5. agent UNDERSTANDING → THINKING
 *  6. agent EXECUTING → EXECUTING
 *  7. agent VERIFYING → VERIFYING
 *  8. agent RESPONDING / voice Speaking → SPEAKING
 *  9. voice-only fallbacks for turns that never touch the agent bus:
 *     Processing → THINKING, ExecutingAction → EXECUTING
 *  10. otherwise → IDLE
 *
 * Agent-specific states always outrank the generic voice fallbacks, so a real
 * VERIFYING or EXECUTING in progress is never masked by a coarser voice label.
 *
 * Note: [AgentState.LISTENING] is currently never transitioned in production,
 * so LISTENING is deliberately derived ONLY from [DvexAssistantState.Listening].
 * An agent bus sitting in LISTENING alone can therefore never fabricate an
 * Orb LISTENING state.
 */
object OrbDisplayStateMapper {

  fun map(
    agentState: AgentState,
    assistantState: DvexAssistantState
  ): OrbDisplayState = when {
    // 1. ERROR always wins (either source is a real, reported failure).
    assistantState is DvexAssistantState.Error -> OrbDisplayState.ERROR
    agentState == AgentState.ERROR -> OrbDisplayState.ERROR

    // 2. Honest confirmation gate — work is genuinely paused for the user.
    agentState == AgentState.WAITING_FOR_PERMISSION -> OrbDisplayState.CONFIRM

    // 3. Voice is actively capturing a command.
    assistantState is DvexAssistantState.Listening -> OrbDisplayState.LISTENING

    // 4. Agent is planning.
    agentState == AgentState.PLANNING -> OrbDisplayState.PLANNING

    // 5. Agent is understanding the input.
    agentState == AgentState.UNDERSTANDING -> OrbDisplayState.THINKING

    // 6. Agent reports real execution in progress.
    agentState == AgentState.EXECUTING -> OrbDisplayState.EXECUTING

    // 7. Real post-execution verification is running.
    agentState == AgentState.VERIFYING -> OrbDisplayState.VERIFYING

    // 8. A response is actually being spoken (agent or voice signal).
    agentState == AgentState.RESPONDING -> OrbDisplayState.SPEAKING
    assistantState is DvexAssistantState.Speaking -> OrbDisplayState.SPEAKING

    // 9. Voice-only fallbacks for turns that never touch the agent engine
    //    (brain-only analysis, tool-router actions). Real signals, lower specificity.
    assistantState is DvexAssistantState.Processing -> OrbDisplayState.THINKING
    assistantState is DvexAssistantState.ExecutingAction -> OrbDisplayState.EXECUTING

    // 10. Nothing active in either source.
    else -> OrbDisplayState.IDLE
  }
}

package com.example.model

import com.example.agent.AgentState
import com.example.brain.DvexToolStatus

/**
 * Display state surfaced by the EXISTING floating Orb bubble and its
 * foreground-service notification subtitle.
 *
 * This is NOT a new state machine. It is a pure, read-only projection of the
 * two already-existing state sources plus the honest outcome of the last turn:
 *
 *  - [DvexAssistantState] — voice/orchestrator state owned by AssistantRepository
 *  - [AgentState]         — real agent pipeline state owned by DvexAgentStateController
 *  - [DvexToolStatus]?    — the last tool turn's real outcome (AssistantRepository
 *                           `lastToolOutcome`), null when no turn is settled yet
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
  CONFIRM,

  /** The last completed turn's effect was OBSERVED — settled success, nothing active now. */
  SUCCESS,

  /**
   * The last real outcome was PERMISSION_REQUIRED: the action is BLOCKED until
   * the user grants access. Rendered as "BLOCKED" — never as EXECUTING, never as IDLE.
   */
  BLOCKED,

  /**
   * The last real outcome was dispatched but its effect could NOT be confirmed.
   * Honest by design: shown as "UNVERIFIED", never promoted to SUCCESS.
   */
  UNVERIFIED;

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
      SUCCESS -> "SUCCESS"
      BLOCKED -> "BLOCKED"
      UNVERIFIED -> "UNVERIFIED"
    }
}

/**
 * Pure mapping with fixed priority (highest first):
 *
 *  1. BLOCKED — the last real outcome was PERMISSION_REQUIRED (the voice layer
 *     reports that case through an Error state such as "Permission required", so
 *     BLOCKED must outrank it to render the honest blocked condition; outcomes
 *     are cleared when a new turn starts, so a genuine failure is never masked)
 *  2. ERROR — voice or agent error always wins otherwise
 *  3. WAITING_FOR_PERMISSION or a pending CONFIRMATION_REQUIRED outcome → CONFIRM
 *  4. voice Listening → LISTENING
 *  5. agent PLANNING → PLANNING
 *  6. agent UNDERSTANDING → THINKING
 *  7. agent EXECUTING → EXECUTING
 *  8. agent VERIFYING → VERIFYING
 *  9. agent RESPONDING / voice Speaking → SPEAKING
 *  10. voice-only fallbacks for turns that never touch the agent bus:
 *      Processing → THINKING, ExecutingAction → EXECUTING
 *  11. settled outcome of the last turn, only when nothing is active:
 *      UNVERIFIED → UNVERIFIED, SUCCESS → SUCCESS (never promoted to each other)
 *  12. otherwise → IDLE
 *
 * Agent-specific states always outrank the generic voice fallbacks, so a real
 * VERIFYING or EXECUTING in progress is never masked by a coarser voice label —
 * and a settled outcome never masks anything that is actually happening now.
 *
 * Note: [AgentState.LISTENING] is currently never transitioned in production,
 * so LISTENING is deliberately derived ONLY from [DvexAssistantState.Listening].
 * An agent bus sitting in LISTENING alone can therefore never fabricate an
 * Orb LISTENING state.
 */
object OrbDisplayStateMapper {

  fun map(
    agentState: AgentState,
    assistantState: DvexAssistantState,
    toolOutcome: DvexToolStatus? = null,
  ): OrbDisplayState = when {
    // 1. BLOCKED — a missing permission is the one outcome that outranks ERROR,
    //    because the voice layer expresses it through an Error state.
    toolOutcome == DvexToolStatus.PERMISSION_REQUIRED -> OrbDisplayState.BLOCKED

    // 2. ERROR always wins otherwise (either source is a real, reported failure).
    assistantState is DvexAssistantState.Error -> OrbDisplayState.ERROR
    agentState == AgentState.ERROR -> OrbDisplayState.ERROR

    // 3. Honest confirmation gate — agent bus OR a pending REST-pipeline
    //    confirmation (the REST path never transitions the agent bus).
    agentState == AgentState.WAITING_FOR_PERMISSION -> OrbDisplayState.CONFIRM
    toolOutcome == DvexToolStatus.CONFIRMATION_REQUIRED -> OrbDisplayState.CONFIRM

    // 4. Voice is actively capturing a command.
    assistantState is DvexAssistantState.Listening -> OrbDisplayState.LISTENING

    // 5. Agent is planning.
    agentState == AgentState.PLANNING -> OrbDisplayState.PLANNING

    // 6. Agent is understanding the input.
    agentState == AgentState.UNDERSTANDING -> OrbDisplayState.THINKING

    // 7. Agent reports real execution in progress.
    agentState == AgentState.EXECUTING -> OrbDisplayState.EXECUTING

    // 8. Real post-execution verification is running.
    agentState == AgentState.VERIFYING -> OrbDisplayState.VERIFYING

    // 9. A response is actually being spoken (agent or voice signal).
    agentState == AgentState.RESPONDING -> OrbDisplayState.SPEAKING
    assistantState is DvexAssistantState.Speaking -> OrbDisplayState.SPEAKING

    // 10. Voice-only fallbacks for turns that never touch the agent engine
    //     (brain-only analysis, tool-router actions). Real signals, lower specificity.
    assistantState is DvexAssistantState.Processing -> OrbDisplayState.THINKING
    assistantState is DvexAssistantState.ExecutingAction -> OrbDisplayState.EXECUTING

    // 11. Settled honest outcome of the previous turn, shown only when nothing
    //     active is happening. UNVERIFIED is checked first so "we are not sure"
    //     can never be displayed as (or beside) a success.
    toolOutcome == DvexToolStatus.UNVERIFIED -> OrbDisplayState.UNVERIFIED
    toolOutcome == DvexToolStatus.SUCCESS -> OrbDisplayState.SUCCESS

    // 12. Nothing active in either source.
    else -> OrbDisplayState.IDLE
  }
}

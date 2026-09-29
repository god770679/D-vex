package com.example.agent

import com.example.brain.DvexToolResult
import com.example.brain.DvexToolStatus

/**
 * D-VEX MCP RESULT
 *
 * The ONE canonical outcome model for the agent/MCP pipeline. Every tool outcome is
 * expressed with exactly four states, so no caller ever has to guess what happened:
 *
 *  - [VERIFIED_SUCCESS] the effect was observed (or the platform API reported the
 *    change itself, which for a device control is the effect).
 *  - [FAILED] the action ran and did not succeed.
 *  - [BLOCKED] D-VEX is not allowed / not able to run it: missing permission,
 *    unsupported on this device, or waiting for the user's confirmation.
 *  - [UNVERIFIED] the action was dispatched but could NOT be confirmed.
 *
 * HARD RULE: an unconfirmed action is NEVER promoted to success. [UNVERIFIED] is a
 * first-class, reportable outcome — D-VEX tells the user what it actually knows.
 *
 * This is an adapter-level model: the legacy result types
 * (`ToolExecutionResult`, `DvexToolResult`, `AgentActionResult`) are kept so the
 * existing app keeps compiling and behaving, and are converted here rather than
 * replaced. Migration stays incremental.
 */
enum class DvexMcpStatus {
  VERIFIED_SUCCESS,
  FAILED,
  BLOCKED,
  UNVERIFIED
}

/**
 * Verified outcome of one tool call.
 *
 * @param toolName the stable tool name (see [DvexMcpTool.name]).
 * @param status the canonical outcome.
 * @param verification how much was actually confirmed (see [VerificationOutcome]).
 * @param message factual, human-readable result data — never internal labels,
 *   tool names, JSON or debug text.
 * @param error failure/blocked reason when one is known, else null.
 * @param requiredPermission the permission that must be granted, when blocked on one.
 * @param requiresConfirmation true when execution is waiting for user consent
 *   (a [DvexMcpStatus.BLOCKED] state that the user can unblock).
 */
data class DvexMcpResult(
  val toolName: String,
  val status: DvexMcpStatus,
  val verification: VerificationOutcome,
  val message: String,
  val error: String? = null,
  val requiredPermission: String? = null,
  val requiresConfirmation: Boolean = false
) {
  /** True only for a success that is not unverified. */
  val isVerifiedSuccess: Boolean get() = status == DvexMcpStatus.VERIFIED_SUCCESS

  companion object {

    /**
     * Converts a legacy router/agent tool result into the canonical outcome.
     *
     * The legacy [DvexToolResult] carries no verification evidence of its own, so a
     * SUCCESS here means "the deterministic layer reported success" — recorded as
     * [VerificationOutcome.DISPATCH_CONFIRMED], never as fully verified. Its new
     * [DvexToolStatus.UNVERIFIED] state stays UNVERIFIED.
     */
    fun fromToolResult(result: DvexToolResult): DvexMcpResult = when (result.status) {
      DvexToolStatus.SUCCESS -> DvexMcpResult(
        toolName = result.toolName,
        status = DvexMcpStatus.VERIFIED_SUCCESS,
        verification = VerificationOutcome.DISPATCH_CONFIRMED,
        message = result.message
      )

      DvexToolStatus.UNVERIFIED -> DvexMcpResult(
        toolName = result.toolName,
        status = DvexMcpStatus.UNVERIFIED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.message,
        error = "not confirmed"
      )

      DvexToolStatus.PERMISSION_REQUIRED -> DvexMcpResult(
        toolName = result.toolName,
        status = DvexMcpStatus.BLOCKED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.message,
        error = "permission required"
      )

      DvexToolStatus.UNSUPPORTED -> DvexMcpResult(
        toolName = result.toolName,
        status = DvexMcpStatus.BLOCKED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.message,
        error = "unsupported"
      )

      DvexToolStatus.CONFIRMATION_REQUIRED -> DvexMcpResult(
        toolName = result.toolName,
        status = DvexMcpStatus.BLOCKED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.confirmationPrompt ?: result.message,
        error = "awaiting confirmation",
        requiresConfirmation = true
      )

      DvexToolStatus.NOT_FOUND -> DvexMcpResult(
        toolName = result.toolName,
        status = DvexMcpStatus.FAILED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.message,
        error = "target not found"
      )

      DvexToolStatus.FAILED, DvexToolStatus.ERROR -> DvexMcpResult(
        toolName = result.toolName,
        status = DvexMcpStatus.FAILED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.message,
        error = result.status.name.lowercase()
      )
    }

    /**
     * Converts one agent action outcome. The verification state is carried through
     * verbatim: an action whose effect was never observed can only be UNVERIFIED.
     */
    fun fromActionResult(
      toolName: String,
      result: AgentActionResult,
      requiresConfirmation: Boolean = result.needsConfirmation
    ): DvexMcpResult = when (result.status) {
      AgentActionStatus.SUCCESS ->
        if (result.verification == VerificationOutcome.UNVERIFIED) {
          DvexMcpResult(
            toolName = toolName,
            status = DvexMcpStatus.UNVERIFIED,
            verification = VerificationOutcome.UNVERIFIED,
            message = result.message,
            error = "not confirmed"
          )
        } else {
          DvexMcpResult(
            toolName = toolName,
            status = DvexMcpStatus.VERIFIED_SUCCESS,
            verification = result.verification,
            message = result.message
          )
        }

      AgentActionStatus.REQUIRES_PERMISSION -> DvexMcpResult(
        toolName = toolName,
        status = DvexMcpStatus.BLOCKED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.message,
        error = "permission required",
        requiredPermission = result.requiredPermission
      )

      AgentActionStatus.REQUIRES_CONFIRMATION -> DvexMcpResult(
        toolName = toolName,
        status = DvexMcpStatus.BLOCKED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.message,
        error = "awaiting confirmation",
        requiresConfirmation = requiresConfirmation
      )

      AgentActionStatus.UNSUPPORTED -> DvexMcpResult(
        toolName = toolName,
        status = DvexMcpStatus.BLOCKED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.message,
        error = "unsupported"
      )

      AgentActionStatus.FAILURE -> DvexMcpResult(
        toolName = toolName,
        status = DvexMcpStatus.FAILED,
        verification = VerificationOutcome.UNVERIFIED,
        message = result.message,
        error = "action failed"
      )
    }
  }
}

package com.example.brain

/**
 * Structured tool execution result returned by DvexToolRouter.
 * Never treats a failed action as successful.
 */
enum class DvexToolStatus {
  SUCCESS,
  FAILED,
  PERMISSION_REQUIRED,
  NOT_FOUND,
  UNSUPPORTED,
  CONFIRMATION_REQUIRED,
  ERROR
}

data class DvexToolResult(
  val status: DvexToolStatus,
  val toolName: String,
  val message: String,
  val spokenText: String = message,
  val requiresConfirmation: Boolean = false,
  val confirmationPrompt: String? = null,
  val pendingActionId: String? = null,
  val multiStepExecutedFirst: Boolean = false,
  val multiStepPendingSecond: DvexIntent? = null
) {
  val isSuccessful: Boolean get() = status == DvexToolStatus.SUCCESS
}

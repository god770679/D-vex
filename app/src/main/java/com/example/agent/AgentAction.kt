package com.example.agent

/**
 * D-VEX ACTION MODEL
 *
 * One small, extensible abstraction every agent task is built from. Actions are pure
 * data; the [DvexActionRegistry] owns HOW they run. Adding a future capability means
 * adding a handler, never another branch in the brain.
 */
enum class AgentActionType {
  OPEN_APP,
  SEARCH_IN_APP,
  LAUNCH_URL,
  NAVIGATE_HOME,
  NAVIGATE_BACK,
  OPEN_RECENT_APPS,
  OPEN_CAMERA,
  OPEN_SETTINGS,
  GET_WEATHER,
  SEND_MESSAGE,
  CREATE_REMINDER,

  // --- Device control layer (extensions) ---
  OPEN_NOTIFICATIONS,
  OPEN_WIFI_SETTINGS,
  OPEN_BLUETOOTH_SETTINGS,
  OPEN_DISPLAY_SETTINGS,
  OPEN_DATE_TIME_SETTINGS,
  OPEN_LOCATION_SETTINGS,
  OPEN_NOTIFICATION_SETTINGS,
  OPEN_ACCESSIBILITY_SETTINGS,
  SCROLL,
  TAP_ELEMENT,
  TYPE_TEXT,
  CLEAR_TEXT,
  SUBMIT_TEXT,
  MEDIA_PLAY,
  MEDIA_PAUSE,
  MEDIA_NEXT,
  MEDIA_PREVIOUS,
  VOLUME_UP,
  VOLUME_DOWN
}

/** Risk drives whether an action may run without asking first. */
enum class ActionRisk { LOW, MEDIUM, HIGH }

enum class AgentActionStatus {
  SUCCESS,
  FAILURE,
  REQUIRES_PERMISSION,
  REQUIRES_CONFIRMATION,
  UNSUPPORTED
}

/**
 * HOW MUCH of an action was actually confirmed — a separate axis from the status,
 * because "the handler returned success" and "the effect was observed" are not the
 * same claim.
 *
 * - [VERIFIED] the effect was observed (foreground package read back, typed text
 *   read back, accessibility action returned true).
 * - [DISPATCH_CONFIRMED] there is no further observable signal on Android and the
 *   platform API itself reported the change (volume/media keys, global actions,
 *   an intent that was accepted). The action really happened; nothing more can be
 *   read back.
 * - [UNVERIFIED] verification was attempted and could NOT confirm the effect.
 *   This must never be reported to the user as success.
 */
enum class VerificationOutcome { VERIFIED, DISPATCH_CONFIRMED, UNVERIFIED }

data class AgentAction(
  val type: AgentActionType,
  val capability: DvexCapability,
  /** App name, or URL for LAUNCH_URL. */
  val target: String = "",
  /** Search text / reminder text / recipient text. */
  val query: String = "",
  val risk: ActionRisk = ActionRisk.LOW,
  /** Human-facing subject shown in the action card. */
  val detail: String = ""
)

/**
 * Honest outcome of one action. [message] is the factual basis the response layer
 * speaks from — it must never claim more than actually happened.
 */
data class AgentActionResult(
  val status: AgentActionStatus,
  val message: String,
  val requiredPermission: String? = null,
  /** True only when the effect was actually observed (not merely dispatched). */
  val verified: Boolean = false,
  /**
   * Explicit verification state. Defaults from [verified] so every existing handler
   * keeps its meaning: `verified = true` -> VERIFIED, otherwise UNVERIFIED. Handlers
   * whose effect has no observable signal declare [VerificationOutcome.DISPATCH_CONFIRMED]
   * explicitly instead of claiming verification they never performed.
   */
  val verification: VerificationOutcome =
    if (verified) VerificationOutcome.VERIFIED else VerificationOutcome.UNVERIFIED
) {
  val isSuccess: Boolean get() = status == AgentActionStatus.SUCCESS
  val needsConfirmation: Boolean get() = status == AgentActionStatus.REQUIRES_CONFIRMATION

  /** True only when the effect was observed or the platform API itself confirmed it. */
  val isConfirmed: Boolean get() = status == AgentActionStatus.SUCCESS &&
    verification != VerificationOutcome.UNVERIFIED
}

data class DvexTaskPlan(
  val steps: List<AgentAction>,
  /** Short user-facing description of what the whole task will do. */
  val summary: String
) {
  val isEmpty: Boolean get() = steps.isEmpty()
  val stepCount: Int get() = steps.size
}

/** Aggregated, honest outcome of a whole plan — partial success is never "success". */
data class DvexTaskResult(
  val plan: DvexTaskPlan,
  val steps: List<Pair<AgentAction, AgentActionResult>>,
  val failedAtStep: Int? = null,
  val failureReason: String = ""
) {
  val completedCount: Int get() = steps.count { it.second.isSuccess }
  val isComplete: Boolean get() = failedAtStep == null && steps.isNotEmpty() && steps.all { it.second.isSuccess }
  val firstResult: AgentActionResult? get() = steps.firstOrNull()?.second
}

/**
 * ACTION SAFETY POLICY
 *
 * Harmless navigation/open/search runs immediately. Irreversible or
 * outward-facing actions (sending messages) require explicit confirmation first.
 * Medium-risk work (reminders) is deliberately left directly executable and is
 * isolated here so a later phase can raise it to confirmation without touching
 * any handler.
 */
object DvexActionPolicy {
  fun requiresConfirmation(action: AgentAction): Boolean = action.risk == ActionRisk.HIGH
}

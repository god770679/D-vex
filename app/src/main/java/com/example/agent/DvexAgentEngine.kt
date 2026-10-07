package com.example.agent

import android.content.Context
import com.example.control.DvexContextProvider
import android.util.Log
import com.example.brain.DvexIntent
import com.example.brain.DvexToolResult
import com.example.brain.DvexToolRouter
import com.example.brain.DvexToolStatus
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.model.AgentActivityController
import com.example.model.AgentActivityStatus
import com.example.model.AgentCapability
import java.util.UUID

/**
 * D-VEX AGENT ENGINE
 *
 * Executes a [DvexTaskPlan] against the real device:
 *
 *   capability check → safety policy → handler execution → verification → honest result
 *
 * It publishes [AgentActivityController] updates (the small action cards) and
 * [DvexAgentStateController] transitions as real work happens — the UI therefore can
 * never show a fake "executing" or a fake success. A failed step stops the plan and is
 * reported as a partial failure.
 */
class DvexAgentEngine(
  private val context: Context,
  private val appLauncher: AppLauncherRepository,
  private val deviceControl: DeviceControlRepository,
  private val toolRouter: DvexToolRouter,
  /** Optional on-demand context provider (advisory only). Defaults to none — same as before. */
  private val dvexContextProvider: DvexContextProvider? = null,
  private val capabilityManager: DvexCapabilityProbe = DvexCapabilityManager(context),
  private val registry: DvexActionRegistry = DvexActionRegistry.withDefaults(),
  val taskContext: DvexTaskContext = DvexTaskContext()
) {

  private val planner = DvexTaskPlanner(taskContext)
  private val env = DvexAgentEnvironment(
    context = context,
    appLauncher = appLauncher,
    deviceControl = deviceControl,
    toolRouter = toolRouter,
    dvexContextProvider = dvexContextProvider
  )

  /** Plan for an intent, or null when the existing tool router should handle it. */
  fun planFor(intent: DvexIntent, rawInput: String = ""): DvexTaskPlan? =
    planner.plan(intent, rawInput)

  /** Drops the short-lived task context (never persisted, so nothing lingers). */
  fun resetSession() {
    taskContext.clear()
  }

  /**
   * Runs the agent path when the intent is agent-owned.
   * Returns null otherwise so the caller keeps the existing behaviour untouched.
   */
  suspend fun executeIfApplicable(intent: DvexIntent, rawInput: String): DvexToolResult? {
    val plan = planner.plan(intent, rawInput) ?: return null
    return runPlan(plan)
  }

  /** Runs a plan end to end and converts the honest task result into a tool result. */
  suspend fun runPlan(plan: DvexTaskPlan): DvexToolResult {
    DvexAgentStateController.transition(AgentState.UNDERSTANDING)
    DvexAgentStateController.transition(AgentState.PLANNING)
    val result = executePlan(plan)
    DvexAgentStateController.transition(
      if (result.isComplete) AgentState.RESPONDING else AgentState.ERROR
    )
    return result.toToolResult()
  }

  /** Executes every step in order, stopping honestly at the first non-success. */
  suspend fun executePlan(plan: DvexTaskPlan): DvexTaskResult {
    if (plan.isEmpty) {
      return DvexTaskResult(plan, emptyList(), null, "")
    }

    val results = mutableListOf<Pair<AgentAction, AgentActionResult>>()

    for ((index, action) in plan.steps.withIndex()) {
      // 1. Permission / capability check — never execute without the real grant.
      val capabilityState = capabilityManager.state(action.capability)
      if (capabilityState !is CapabilityState.Available) {
        // Surface the missing capability as an action card, never a silent failure.
        DvexAgentStateController.transition(AgentState.WAITING_FOR_PERMISSION)
        AgentActivityController.requireAccess(cardCapabilityFor(action), waitingForPermission = false)
        return stopWith(plan, results, index, action, capabilityResult(action, capabilityState))
      }

      // 2. Safety policy — irreversible / outward-facing actions wait for consent.
      if (DvexActionPolicy.requiresConfirmation(action)) {
        return stopWith(
          plan, results, index, action,
          AgentActionResult(
            status = AgentActionStatus.REQUIRES_CONFIRMATION,
            message = "Shall I ${plan.summary}?"
          )
        )
      }

      // 3. Handler lookup — an unregistered action is honestly unsupported.
      val handler = registry.handlerFor(action.type)
      if (handler == null) {
        return stopWith(
          plan, results, index, action,
          AgentActionResult(
            status = AgentActionStatus.UNSUPPORTED,
            message = "I don't know how to do that yet."
          )
        )
      }

      // 4. Real execution, with a live action card.
      DvexAgentStateController.transition(AgentState.EXECUTING)
      val cardId = AgentActivityController.begin(
        capability = cardCapabilityFor(action),
        status = startingStatusFor(action),
        detail = cardDetailFor(action)
      )

      val result = try {
        handler.execute(action, env)
      } catch (e: Exception) {
        Log.w(TAG, "Action ${action.type} failed", e)
        AgentActionResult(
          status = AgentActionStatus.FAILURE,
          message = "That action couldn't be completed."
        )
      }

      // 5. Verify + publish, then remember context for follow-ups.
      when (result.status) {
        AgentActionStatus.SUCCESS -> {
          DvexAgentStateController.transition(AgentState.VERIFYING)
          AgentActivityController.complete(cardId, detail = completedDetailFor(action))
          taskContext.remember(action)
          results += action to result
        }

        AgentActionStatus.REQUIRES_PERMISSION -> {
          DvexAgentStateController.transition(AgentState.WAITING_FOR_PERMISSION)
          AgentActivityController.requireAccess(
            cardCapabilityFor(action),
            waitingForPermission = true
          )
          return stopWith(plan, results, index, action, result)
        }

        AgentActionStatus.REQUIRES_CONFIRMATION -> {
          AgentActivityController.update(cardId, AgentActivityStatus.RUNNING, cardDetailFor(action))
          return stopWith(plan, results, index, action, result)
        }

        AgentActionStatus.UNSUPPORTED -> {
          AgentActivityController.fail(cardId, result.message)
          return stopWith(plan, results, index, action, result)
        }

        AgentActionStatus.FAILURE -> {
          AgentActivityController.fail(cardId, result.message)
          return stopWith(plan, results, index, action, result)
        }
      }
    }

    return DvexTaskResult(plan = plan, steps = results, failedAtStep = null, failureReason = "")
  }

  private fun stopWith(
    plan: DvexTaskPlan,
    results: MutableList<Pair<AgentAction, AgentActionResult>>,
    index: Int,
    action: AgentAction,
    result: AgentActionResult
  ): DvexTaskResult {
    results += action to result
    return DvexTaskResult(
      plan = plan,
      steps = results,
      failedAtStep = index,
      failureReason = result.message
    )
  }

  private fun capabilityResult(
    action: AgentAction,
    state: CapabilityState
  ): AgentActionResult = when (state) {
    is CapabilityState.PermissionRequired -> AgentActionResult(
      status = AgentActionStatus.REQUIRES_PERMISSION,
      message = state.message,
      requiredPermission = state.permission
    )
    is CapabilityState.Unsupported -> AgentActionResult(
      status = AgentActionStatus.UNSUPPORTED,
      message = state.reason
    )
    CapabilityState.Available -> AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = "Available."
    )
  }

  // --- action card helpers --------------------------------------------------

  private fun cardCapabilityFor(action: AgentAction): AgentCapability =
    when {
      action.target.contains("youtube", ignoreCase = true) -> AgentCapability.YOUTUBE
      action.target.contains("whatsapp", ignoreCase = true) -> AgentCapability.WHATSAPP
      action.target.contains("instagram", ignoreCase = true) -> AgentCapability.INSTAGRAM
      else -> when (action.capability) {
        DvexCapability.APP_LAUNCH -> AgentCapability.APP
        DvexCapability.IN_APP_SEARCH -> AgentCapability.APP
        DvexCapability.LAUNCH_URL -> AgentCapability.APP
        DvexCapability.NAVIGATION -> AgentCapability.DEVICE
        DvexCapability.SETTINGS -> AgentCapability.DEVICE
        DvexCapability.APP_USAGE -> AgentCapability.RECENT_APPS
        DvexCapability.UI_INTERACTION -> AgentCapability.DEVICE
        DvexCapability.CAMERA -> AgentCapability.CAMERA
        DvexCapability.MICROPHONE -> AgentCapability.MICROPHONE
        DvexCapability.LOCATION -> AgentCapability.LOCATION
        DvexCapability.WEATHER -> AgentCapability.WEATHER
        DvexCapability.NOTIFICATIONS -> AgentCapability.NOTIFICATIONS
        DvexCapability.MESSAGING -> AgentCapability.MESSAGES
        DvexCapability.MEDIA -> AgentCapability.DEVICE
      }
    }

  private fun startingStatusFor(action: AgentAction): AgentActivityStatus = when (action.type) {
    AgentActionType.OPEN_APP -> AgentActivityStatus.OPENING
    AgentActionType.SEARCH_IN_APP -> AgentActivityStatus.SEARCHING
    else -> AgentActivityStatus.RUNNING
  }

  private fun cardDetailFor(action: AgentAction): String = action.query

  private fun completedDetailFor(action: AgentAction): String = when (action.type) {
    AgentActionType.SEARCH_IN_APP -> "Search completed"
    AgentActionType.OPEN_APP -> "Opened"
    else -> "Completed"
  }

  companion object {
    private const val TAG = "DvexAgentEngine"
  }
}

/**
 * Converts the honest task result into the existing D-VEX tool result, so the
 * response generator, TTS and HUD continue to work without any change.
 *
 * VERIFICATION GATE: a step whose effect was never confirmed
 * ([VerificationOutcome.UNVERIFIED]) becomes [DvexToolStatus.UNVERIFIED] — it is
 * NEVER reported as SUCCESS, and it never contributes to the "done" summary. Only
 * observed effects ([VerificationOutcome.VERIFIED]) or platform-confirmed effects
 * ([VerificationOutcome.DISPATCH_CONFIRMED]) count as success.
 */
fun DvexTaskResult.toToolResult(): DvexToolResult {
  val lastStep = steps.lastOrNull()
  if (lastStep == null) {
    return DvexToolResult(
      status = DvexToolStatus.FAILED,
      toolName = "agent",
      message = "I couldn't plan that one.",
      spokenText = "I couldn't plan that one."
    )
  }

  val (lastAction, lastResult) = lastStep
  val toolName = toolNameFor(lastAction.type)
  // Only CONFIRMED successes may be spoken as done. An unverified step is excluded
  // so the summary can never claim something that was not observed.
  val done = steps
    .filter { it.second.isConfirmed }
    .joinToString(" and ") { pastTense(it.first) }

  return when (lastResult.status) {
    AgentActionStatus.SUCCESS -> {
      if (lastResult.verification == VerificationOutcome.UNVERIFIED) {
        // Dispatched, but the effect was never observed: report it as unverified
        // and keep the handler's own honest wording ("Opening X", not "Opened X").
        val text = lastResult.message.ifBlank { "I couldn't confirm that one." }
        val combined = if (done.isBlank()) {
          text
        } else {
          "${done.replaceFirstChar { it.uppercaseChar() }}, but ${text.replaceFirstChar { it.lowercase() }}"
        }
        DvexToolResult(
          status = DvexToolStatus.UNVERIFIED,
          toolName = toolName,
          message = combined,
          spokenText = combined
        )
      } else {
        // pastTense yields lowercase verb phrases ("opened YouTube"); capitalise for
        // the spoken sentence.
        val text = if (done.isBlank()) {
          "Done."
        } else {
          "Done. ${done.replaceFirstChar { it.uppercaseChar() }}."
        }
        DvexToolResult(
          status = DvexToolStatus.SUCCESS,
          toolName = toolName,
          message = text,
          spokenText = text
        )
      }
    }

    AgentActionStatus.REQUIRES_PERMISSION -> DvexToolResult(
      status = DvexToolStatus.PERMISSION_REQUIRED,
      toolName = toolName,
      message = lastResult.message,
      spokenText = lastResult.message
    )

    AgentActionStatus.REQUIRES_CONFIRMATION -> DvexToolResult(
      status = DvexToolStatus.CONFIRMATION_REQUIRED,
      toolName = toolName,
      message = lastResult.message,
      spokenText = lastResult.message,
      requiresConfirmation = true,
      confirmationPrompt = lastResult.message,
      pendingActionId = UUID.randomUUID().toString()
    )

    AgentActionStatus.UNSUPPORTED -> DvexToolResult(
      status = DvexToolStatus.UNSUPPORTED,
      toolName = toolName,
      message = lastResult.message,
      spokenText = lastResult.message
    )

    AgentActionStatus.FAILURE -> {
      val text = if (done.isBlank()) {
        lastResult.message
      } else {
        "${done.replaceFirstChar { it.uppercaseChar() }}, but ${lastResult.message.replaceFirstChar { it.lowercase() }}"
      }
      DvexToolResult(
        status = DvexToolStatus.FAILED,
        toolName = toolName,
        message = text,
        spokenText = text
      )
    }
  }
}

/**
 * The canonical action -> tool-name mapping. Unique per action type, because a
 * function call is routed back to an action by this name (see DvexMcpRegistry).
 */
internal fun toolNameFor(type: AgentActionType): String = when (type) {
  AgentActionType.OPEN_APP -> "open_app"
  AgentActionType.SEARCH_IN_APP -> "search"
  AgentActionType.LAUNCH_URL -> "launch_url"
  AgentActionType.NAVIGATE_HOME -> "home"
  AgentActionType.NAVIGATE_BACK -> "back"
  AgentActionType.OPEN_RECENT_APPS -> "recent_apps"
  AgentActionType.OPEN_CAMERA -> "camera"
  AgentActionType.OPEN_SETTINGS -> "open_settings"
  AgentActionType.GET_WEATHER -> "get_weather"
  AgentActionType.SEND_MESSAGE -> "send_message"
  AgentActionType.CREATE_REMINDER -> "set_alarm"
  AgentActionType.SCROLL -> "scroll"
  AgentActionType.TAP_ELEMENT -> "tap_element"
  AgentActionType.TYPE_TEXT -> "type_text"
  AgentActionType.CLEAR_TEXT -> "clear_text"
  AgentActionType.SUBMIT_TEXT -> "submit_text"
  AgentActionType.VOLUME_UP -> "volume_up"
  AgentActionType.VOLUME_DOWN -> "volume_down"
  AgentActionType.OPEN_NOTIFICATIONS -> "open_notifications"
  AgentActionType.OPEN_WIFI_SETTINGS -> "open_wifi_settings"
  AgentActionType.OPEN_BLUETOOTH_SETTINGS -> "open_bluetooth_settings"
  AgentActionType.OPEN_DISPLAY_SETTINGS -> "open_display_settings"
  AgentActionType.OPEN_DATE_TIME_SETTINGS -> "open_date_time_settings"
  AgentActionType.OPEN_LOCATION_SETTINGS -> "open_location_settings"
  AgentActionType.OPEN_NOTIFICATION_SETTINGS -> "open_notification_settings"
  AgentActionType.OPEN_ACCESSIBILITY_SETTINGS -> "open_accessibility_settings"
  AgentActionType.MEDIA_PLAY -> "media_play"
  AgentActionType.MEDIA_PAUSE -> "media_pause"
  AgentActionType.MEDIA_NEXT -> "media_next"
  AgentActionType.MEDIA_PREVIOUS -> "media_previous"
}

private fun pastTense(action: AgentAction): String = when (action.type) {
  AgentActionType.OPEN_APP -> "opened ${action.target}"
  AgentActionType.OPEN_CAMERA -> "opened the camera"
  AgentActionType.SEARCH_IN_APP -> "searched \"${action.query}\" in ${action.target}"
  AgentActionType.LAUNCH_URL -> "opened ${action.target}"
  AgentActionType.NAVIGATE_HOME -> "went home"
  AgentActionType.NAVIGATE_BACK -> "went back"
  AgentActionType.OPEN_RECENT_APPS -> "opened recent apps"
  AgentActionType.OPEN_SETTINGS -> "opened settings"
  AgentActionType.GET_WEATHER -> "got the weather"
  AgentActionType.CREATE_REMINDER -> "set the reminder for ${action.target}"
  AgentActionType.SEND_MESSAGE -> "prepared the message to ${action.target}"
  AgentActionType.SCROLL -> "scrolled ${action.query.lowercase()}"
  AgentActionType.TAP_ELEMENT -> "tapped \"${action.query}\""
  AgentActionType.TYPE_TEXT -> "typed \"${action.query}\""
  AgentActionType.CLEAR_TEXT -> "cleared the field"
  AgentActionType.SUBMIT_TEXT -> "submitted the search"
  AgentActionType.VOLUME_UP -> "turned the volume up"
  AgentActionType.VOLUME_DOWN -> "turned the volume down"
  AgentActionType.MEDIA_PLAY, AgentActionType.MEDIA_PAUSE -> "controlled playback"
  AgentActionType.MEDIA_NEXT -> "skipped to the next track"
  AgentActionType.MEDIA_PREVIOUS -> "went to the previous track"
  AgentActionType.OPEN_NOTIFICATIONS -> "opened notifications"
  else -> "opened ${action.target.ifBlank { "the requested screen" }}"
}

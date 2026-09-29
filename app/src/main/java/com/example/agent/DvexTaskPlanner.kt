package com.example.agent

import com.example.brain.DvexIntent
import java.util.Locale

/**
 * D-VEX TASK PLANNER
 *
 * Turns one detected intent (plus the short-lived task context) into an executable
 * [DvexTaskPlan]. Returns null when the intent is not agent-owned — the existing
 * tool router then handles it exactly as before, so nothing is duplicated.
 *
 * The planner only decides WHAT should happen; handlers and the capability layer
 * decide HOW, and the engine verifies the result.
 */
class DvexTaskPlanner(private val taskContext: DvexTaskContext) {

  fun plan(intent: DvexIntent, rawInput: String = ""): DvexTaskPlan? {
    val steps = buildSteps(intent, rawInput) ?: return null
    if (steps.isEmpty()) return null
    return DvexTaskPlan(steps = steps, summary = summaryOf(steps))
  }

  private fun buildSteps(intent: DvexIntent, rawInput: String): List<AgentAction>? = when (intent) {
    // --- App / device actions ---------------------------------------------
    is DvexIntent.OpenApp -> {
      if (isCamera(intent.appName)) {
        listOf(AgentAction(AgentActionType.OPEN_CAMERA, DvexCapability.CAMERA, target = "camera"))
      } else {
        listOf(
          AgentAction(
            type = AgentActionType.OPEN_APP,
            capability = DvexCapability.APP_LAUNCH,
            target = intent.appName
          )
        )
      }
    }

    is DvexIntent.OpenSettings ->
      listOf(AgentAction(AgentActionType.OPEN_SETTINGS, DvexCapability.SETTINGS))

    is DvexIntent.OpenRecents ->
      listOf(AgentAction(AgentActionType.OPEN_RECENT_APPS, DvexCapability.NAVIGATION))

    is DvexIntent.GetRecentApps ->
      // The real recent-apps LIST needs Usage access; the recents screen does not.
      listOf(AgentAction(AgentActionType.OPEN_RECENT_APPS, DvexCapability.APP_USAGE))

    is DvexIntent.OpenNotifications ->
      listOf(AgentAction(AgentActionType.OPEN_NOTIFICATIONS, DvexCapability.NAVIGATION))

    is DvexIntent.OpenWifiSettings ->
      listOf(AgentAction(AgentActionType.OPEN_WIFI_SETTINGS, DvexCapability.SETTINGS))

    is DvexIntent.OpenSystemSettings ->
      listOf(
        AgentAction(
          type = when (intent.kind) {
            com.example.brain.SystemSettingsKind.BLUETOOTH -> AgentActionType.OPEN_BLUETOOTH_SETTINGS
            com.example.brain.SystemSettingsKind.DISPLAY -> AgentActionType.OPEN_DISPLAY_SETTINGS
            com.example.brain.SystemSettingsKind.DATE_TIME -> AgentActionType.OPEN_DATE_TIME_SETTINGS
            com.example.brain.SystemSettingsKind.LOCATION -> AgentActionType.OPEN_LOCATION_SETTINGS
            com.example.brain.SystemSettingsKind.NOTIFICATION -> AgentActionType.OPEN_NOTIFICATION_SETTINGS
            com.example.brain.SystemSettingsKind.ACCESSIBILITY -> AgentActionType.OPEN_ACCESSIBILITY_SETTINGS
          },
          capability = DvexCapability.SETTINGS
        )
      )

    is DvexIntent.Scroll ->
      listOf(
        AgentAction(
          type = AgentActionType.SCROLL,
          capability = DvexCapability.UI_INTERACTION,
          query = intent.direction.name
        )
      )

    is DvexIntent.AdjustVolume ->
      listOf(
        AgentAction(
          type = if (intent.direction == com.example.brain.VolumeAction.UP) {
            AgentActionType.VOLUME_UP
          } else {
            AgentActionType.VOLUME_DOWN
          },
          capability = DvexCapability.MEDIA
        )
      )

    is DvexIntent.MediaControl ->
      listOf(
        AgentAction(
          type = when (intent.command) {
            com.example.brain.MediaAction.PLAY_PAUSE -> AgentActionType.MEDIA_PLAY
            com.example.brain.MediaAction.NEXT -> AgentActionType.MEDIA_NEXT
            com.example.brain.MediaAction.PREVIOUS -> AgentActionType.MEDIA_PREVIOUS
          },
          capability = DvexCapability.MEDIA
        )
      )

    is DvexIntent.GoHome ->
      listOf(AgentAction(AgentActionType.NAVIGATE_HOME, DvexCapability.NAVIGATION))

    is DvexIntent.GoBack ->
      listOf(AgentAction(AgentActionType.NAVIGATE_BACK, DvexCapability.NAVIGATION))

    // --- Search -----------------------------------------------------------
    is DvexIntent.PlayYoutubeVideo -> listOf(
      AgentAction(
        type = AgentActionType.SEARCH_IN_APP,
        capability = DvexCapability.IN_APP_SEARCH,
        target = "YouTube",
        query = intent.query
      )
    )

    is DvexIntent.SearchWeb -> {
      val continueApp = taskContext.currentAppForSearch()
      if (continueApp != null) {
        listOf(
          AgentAction(
            type = AgentActionType.SEARCH_IN_APP,
            capability = DvexCapability.IN_APP_SEARCH,
            target = continueApp,
            query = intent.query
          )
        )
      } else {
        null // No app context → keep the existing browser-search behaviour.
      }
    }

    // --- Multi-step task plans -------------------------------------------
    is DvexIntent.MultiStep -> buildMultiStep(intent)

    // --- Information ------------------------------------------------------
    is DvexIntent.GetWeather ->
      listOf(AgentAction(AgentActionType.GET_WEATHER, DvexCapability.WEATHER))

    is DvexIntent.SetAlarm -> {
      if (intent.hour == null || intent.minute == null) {
        null // No parseable time → let the existing "repeat with a time" path handle it.
      } else {
        listOf(
          AgentAction(
            type = AgentActionType.CREATE_REMINDER,
            capability = DvexCapability.SETTINGS,
            target = "%02d:%02d".format(Locale.ROOT, intent.hour, intent.minute),
            query = intent.message.orEmpty(),
            risk = ActionRisk.MEDIUM
          )
        )
      }
    }

    // --- High-risk, confirmed separately ---------------------------------
    is DvexIntent.SendMessage -> listOf(
      AgentAction(
        type = AgentActionType.SEND_MESSAGE,
        capability = DvexCapability.MESSAGING,
        target = intent.recipient,
        query = intent.messageText.orEmpty(),
        risk = ActionRisk.HIGH
      )
    )

    else -> null
  }

  private fun buildMultiStep(intent: DvexIntent.MultiStep): List<AgentAction>? {
    val open = intent.first as? DvexIntent.OpenApp ?: return null
    val query = when (val second = intent.second) {
      is DvexIntent.SearchWeb -> second.query
      is DvexIntent.PlayYoutubeVideo -> second.query
      else -> return null
    }
    if (query.isBlank()) return null

    val steps = mutableListOf<AgentAction>(
      if (isCamera(open.appName)) {
        AgentAction(AgentActionType.OPEN_CAMERA, DvexCapability.CAMERA, target = "camera")
      } else {
        AgentAction(
          type = AgentActionType.OPEN_APP,
          capability = DvexCapability.APP_LAUNCH,
          target = open.appName
        )
      },
      AgentAction(
        type = AgentActionType.SEARCH_IN_APP,
        capability = DvexCapability.IN_APP_SEARCH,
        target = open.appName,
        query = query
      )
    )
    return steps
  }

  private fun isCamera(appName: String): Boolean =
    appName.contains("camera", ignoreCase = true)

  private fun summaryOf(steps: List<AgentAction>): String = when {
    steps.size == 1 -> describe(steps.first())
    else -> steps.joinToString(", ") { describe(it) }
  }

  private fun describe(action: AgentAction): String = when (action.type) {
    AgentActionType.OPEN_APP -> "open ${action.target}"
    AgentActionType.OPEN_CAMERA -> "open the camera"
    AgentActionType.SEARCH_IN_APP -> "search \"${action.query}\" in ${action.target}"
    AgentActionType.LAUNCH_URL -> "open ${action.target}"
    AgentActionType.NAVIGATE_HOME -> "go home"
    AgentActionType.NAVIGATE_BACK -> "go back"
    AgentActionType.OPEN_RECENT_APPS -> "open recent apps"
    AgentActionType.OPEN_SETTINGS -> "open settings"
    AgentActionType.GET_WEATHER -> "get the weather"
    AgentActionType.CREATE_REMINDER -> "set a reminder for ${action.target}"
    AgentActionType.SEND_MESSAGE -> "message ${action.target}"
    AgentActionType.OPEN_NOTIFICATIONS -> "open notifications"
    AgentActionType.OPEN_WIFI_SETTINGS -> "open Wi-Fi settings"
    AgentActionType.OPEN_BLUETOOTH_SETTINGS -> "open Bluetooth settings"
    AgentActionType.OPEN_DISPLAY_SETTINGS -> "open display settings"
    AgentActionType.OPEN_DATE_TIME_SETTINGS -> "open date & time settings"
    AgentActionType.OPEN_LOCATION_SETTINGS -> "open location settings"
    AgentActionType.OPEN_NOTIFICATION_SETTINGS -> "open notification settings"
    AgentActionType.OPEN_ACCESSIBILITY_SETTINGS -> "open accessibility settings"
    AgentActionType.SCROLL -> if (action.query == "UP") "scroll up" else "scroll down"
    AgentActionType.TAP_ELEMENT -> "tap \"${action.query}\""
    AgentActionType.TYPE_TEXT -> "type \"${action.query}\""
    AgentActionType.CLEAR_TEXT -> "clear the text field"
    AgentActionType.SUBMIT_TEXT -> "submit the search"
    AgentActionType.MEDIA_PLAY -> "play or pause"
    AgentActionType.MEDIA_PAUSE -> "play or pause"
    AgentActionType.MEDIA_NEXT -> "skip to the next track"
    AgentActionType.MEDIA_PREVIOUS -> "go to the previous track"
    AgentActionType.VOLUME_UP -> "turn the volume up"
    AgentActionType.VOLUME_DOWN -> "turn the volume down"
  }
}

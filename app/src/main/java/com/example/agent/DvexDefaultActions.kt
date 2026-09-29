package com.example.agent

import com.example.brain.DvexIntent
import com.example.brain.MediaAction
import com.example.brain.ScrollDirection
import com.example.brain.SystemSettingsKind
import com.example.brain.VolumeAction
import com.example.control.DvexAccessibilityService
import java.util.Locale

/**
 * D-VEX DEFAULT ACTIONS
 *
 * Real, Android-supported actions only. Every handler delegates to the existing
 * repositories (launcher / device control / tool router) — nothing here invents a
 * device capability that Android does not actually grant D-VEX.
 */
object DvexDefaultActions {

  fun all(): List<AgentActionHandler> = listOf(
    OpenAppHandler(),
    SearchInAppHandler(),
    LaunchUrlHandler(),
    NavigateHomeHandler(),
    NavigateBackHandler(),
    OpenRecentAppsHandler(),
    OpenCameraHandler(),
    OpenSettingsHandler(),
    GetWeatherHandler(),
    CreateReminderHandler(),
    SendMessageHandler(),
    // --- Device control layer ---
    OpenNotificationsHandler(),
    OpenWifiSettingsHandler(),
    SystemSettingsHandler(
      AgentActionType.OPEN_BLUETOOTH_SETTINGS,
      SystemSettingsKind.BLUETOOTH
    ),
    SystemSettingsHandler(
      AgentActionType.OPEN_DISPLAY_SETTINGS,
      SystemSettingsKind.DISPLAY
    ),
    SystemSettingsHandler(
      AgentActionType.OPEN_DATE_TIME_SETTINGS,
      SystemSettingsKind.DATE_TIME
    ),
    SystemSettingsHandler(
      AgentActionType.OPEN_LOCATION_SETTINGS,
      SystemSettingsKind.LOCATION
    ),
    SystemSettingsHandler(
      AgentActionType.OPEN_NOTIFICATION_SETTINGS,
      SystemSettingsKind.NOTIFICATION
    ),
    SystemSettingsHandler(
      AgentActionType.OPEN_ACCESSIBILITY_SETTINGS,
      SystemSettingsKind.ACCESSIBILITY
    ),
    ScrollActionHandler(),
    VolumeActionHandler(AgentActionType.VOLUME_UP),
    VolumeActionHandler(AgentActionType.VOLUME_DOWN),
    MediaActionHandler(AgentActionType.MEDIA_NEXT),
    MediaActionHandler(AgentActionType.MEDIA_PREVIOUS),
    MediaPlayPauseHandler(AgentActionType.MEDIA_PLAY),
    MediaPlayPauseHandler(AgentActionType.MEDIA_PAUSE),
    TapElementHandler(),
    TypeTextHandler(),
    ClearTextHandler(),
    SubmitTextHandler()
  )
}

/** OPEN_APP — launches an installed app and confirms the foreground when possible. */
class OpenAppHandler : AgentActionHandler {
  override val actionType = AgentActionType.OPEN_APP

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
    val result = env.appLauncher.launchAppByName(action.target)
    if (result.status != com.example.data.remote.ToolResultStatus.SUCCESS) {
      return mapToolResult(result, "Opened ${action.target}.")
    }
    // Real verification (usage access) — otherwise say "opening", never "opened".
    val verified = resolvePackage(env, action.target)
      ?.let { env.appLauncher.verifyAppForeground(it) } == true
    return AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = if (verified) "Opened ${action.target}." else "Opening ${action.target}.",
      verified = verified
    )
  }

  private fun resolvePackage(env: DvexAgentEnvironment, target: String): String? {
    val query = target.lowercase(Locale.ROOT)
      .replace("open", "").replace("launch", "").replace("app", "").trim()
    if (query.isEmpty()) return null
    return env.appLauncher.getDiscoveredApps().firstOrNull { app ->
      app.label.lowercase(Locale.ROOT).contains(query) ||
        app.packageName.lowercase(Locale.ROOT).contains(query)
    }?.packageName
  }
}

/** SEARCH_IN_APP — real search mechanism per app (YouTube/Maps deep link, else browser). */
class SearchInAppHandler : AgentActionHandler {
  override val actionType = AgentActionType.SEARCH_IN_APP

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
    val target = action.target.lowercase(Locale.ROOT)

    // Preferred path when D-VEX may drive the UI: find the field, type, submit.
    // Any honest failure falls through to the app's real deep-link mechanism.
    if (DvexAccessibilityService.isEnabled(env.context)) {
      val typed = typeAndSubmit(action.query)
      if (typed != null) return typed
    }

    return if (target.contains("youtube")) {
      val res = env.deviceControl.openYouTube(action.query)
      if (res.toolName == "youtube_browser_fallback") {
        // Honest: the app itself was unavailable, the browser did the search.
        AgentActionResult(
          status = AgentActionStatus.SUCCESS,
          message = "YouTube isn't installed — I searched \"${action.query}\" in the browser instead.",
          verified = false
        )
      } else {
        mapToolResult(res, "Searched \"${action.query}\" on YouTube.")
      }
    } else if (target.contains("maps")) {
      mapToolResult(
        env.deviceControl.openMaps(action.query),
        "Searched \"${action.query}\" on Maps."
      )
    } else {
      mapToolResult(
        env.deviceControl.openBrowser(action.query),
        "Searched \"${action.query}\" in the browser."
      )
    }
  }

  /** Types into the app's search field and submits; null when it honestly could not. */
  private fun typeAndSubmit(query: String): AgentActionResult? {
    if (query.isBlank()) return null
    val field = DvexAccessibilityService.focusEditableField("search") ?: return null
    if (!DvexAccessibilityService.setTextInNode(field, query)) return null
    val typedText = field.text?.toString()
    if (!DvexAccessibilityService.submitVisibleSearchControl()) {
      return AgentActionResult(
        status = AgentActionStatus.FAILURE,
        message = "I typed \"$query\" but couldn't find a search button.",
        verified = typedText?.contains(query, ignoreCase = true) == true
      )
    }
    return AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = "Searched \"$query\".",
      verified = true
    )
  }
}

/** LAUNCH_URL — opens a web address in the real browser. */
class LaunchUrlHandler : AgentActionHandler {
  override val actionType = AgentActionType.LAUNCH_URL

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult =
    mapToolResult(
      env.deviceControl.openBrowser(action.target),
      "Opened ${action.target}."
    )
}

/** NAVIGATE_HOME */
class NavigateHomeHandler : AgentActionHandler {
  override val actionType = AgentActionType.NAVIGATE_HOME

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult =
    mapToolResult(env.deviceControl.navigateHome(), "Went to the home screen.")
}

/** NAVIGATE_BACK */
class NavigateBackHandler : AgentActionHandler {
  override val actionType = AgentActionType.NAVIGATE_BACK

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult =
    mapToolResult(env.deviceControl.navigateBack(), "Went back.")
}

/** OPEN_RECENT_APPS — opens the OS recents screen (honest system-level action). */
class OpenRecentAppsHandler : AgentActionHandler {
  override val actionType = AgentActionType.OPEN_RECENT_APPS

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult =
    mapToolResult(env.deviceControl.showRecents(), "Opened recent apps.")
}

/** OPEN_CAMERA */
class OpenCameraHandler : AgentActionHandler {
  override val actionType = AgentActionType.OPEN_CAMERA

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult =
    mapToolResult(env.deviceControl.openCamera(), "Opening the camera.")
}

/** OPEN_SETTINGS */
class OpenSettingsHandler : AgentActionHandler {
  override val actionType = AgentActionType.OPEN_SETTINGS

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult =
    mapToolResult(env.deviceControl.openSettings(), "Opening settings.")
}

/** GET_WEATHER — reuses the existing weather tool (location + live web service). */
class GetWeatherHandler : RouterBackedHandler() {
  override val actionType = AgentActionType.GET_WEATHER

  override fun intentFor(action: AgentAction): DvexIntent = DvexIntent.GetWeather()
}

/**
 * CREATE_REMINDER — real system alarm via the existing device control.
 * [AgentAction.target] carries "HH:mm"; [AgentAction.query] carries the label.
 */
class CreateReminderHandler : AgentActionHandler {
  override val actionType = AgentActionType.CREATE_REMINDER

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
    val parts = action.target.split(":")
    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()
    if (hour == null || minute == null) {
      return AgentActionResult(
        status = AgentActionStatus.FAILURE,
        message = "I didn't catch a clear time for the reminder."
      )
    }
    return mapToolResult(
      env.deviceControl.setAlarm(hour, minute, action.query.ifBlank { null }),
      "Reminder set for ${action.target}."
    )
  }
}

/**
 * SEND_MESSAGE — HIGH risk. Delegates to the existing messaging tool, which already
 * requires explicit confirmation before anything is actually sent.
 */
class SendMessageHandler : RouterBackedHandler() {
  override val actionType = AgentActionType.SEND_MESSAGE

  override fun intentFor(action: AgentAction): DvexIntent = DvexIntent.SendMessage(
    recipient = action.target,
    messageText = action.query.ifBlank { null },
    isWhatsApp = action.target.contains("whatsapp", ignoreCase = true)
  )
}

// ===========================================================================
// DEVICE CONTROL LAYER — real Android actions, each delegating to the existing
// repository / router / accessibility service. Nothing here fakes an effect.
// ===========================================================================

/** Shared foreground check used to VERIFY that a launch really happened. */
internal fun verifiedForeground(env: DvexAgentEnvironment, vararg candidates: String): Boolean {
  val foreground = env.appLauncher.foregroundPackageOrNull() ?: return false
  return candidates.any { foreground.contains(it, ignoreCase = true) }
}

/** OPEN_NOTIFICATIONS — the notification shade (accessibility-backed in the router). */
class OpenNotificationsHandler : RouterBackedHandler() {
  override val actionType = AgentActionType.OPEN_NOTIFICATIONS
  override fun intentFor(action: AgentAction): DvexIntent = DvexIntent.OpenNotifications
}

/** OPEN_WIFI_SETTINGS — reuses the existing Wi-Fi settings implementation. */
class OpenWifiSettingsHandler : RouterBackedHandler() {
  override val actionType = AgentActionType.OPEN_WIFI_SETTINGS
  override fun intentFor(action: AgentAction): DvexIntent = DvexIntent.OpenWifiSettings
}

/**
 * Any Android system settings screen. D-VEX only navigates to the platform screen —
 * it never mutates a sensitive setting itself.
 */
class SystemSettingsHandler(
  override val actionType: AgentActionType,
  private val kind: SystemSettingsKind
) : AgentActionHandler {
  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
    val result = env.deviceControl.openSystemSettings(kind)
    if (result.status != com.example.data.remote.ToolResultStatus.SUCCESS) {
      return mapToolResult(result, "Opening ${kind.displayName}.")
    }
    val verified = verifiedForeground(env, "com.android.settings")
    return AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = if (verified) "Opened ${kind.displayName}." else "Opening ${kind.displayName}.",
      verified = verified
    )
  }
}

/**
 * SCROLL — vertical only; horizontal scrolling is not exposed by the service, so it
 * is intentionally not offered. Direction travels on [AgentAction.query].
 */
class ScrollActionHandler : RouterBackedHandler() {
  override val actionType = AgentActionType.SCROLL

  override fun intentFor(action: AgentAction): DvexIntent = DvexIntent.Scroll(
    if (action.query.equals("UP", ignoreCase = true)) ScrollDirection.UP else ScrollDirection.DOWN
  )
}

/** VOLUME_UP / VOLUME_DOWN via the existing AudioManager path. */
class VolumeActionHandler(override val actionType: AgentActionType) : RouterBackedHandler() {
  override fun intentFor(action: AgentAction): DvexIntent = DvexIntent.AdjustVolume(
    when (action.type) {
      AgentActionType.VOLUME_UP -> VolumeAction.UP
      else -> VolumeAction.DOWN
    }
  )
}

/** MEDIA_NEXT / MEDIA_PREVIOUS via media key events (no permission needed). */
class MediaActionHandler(override val actionType: AgentActionType) : RouterBackedHandler() {
  override fun intentFor(action: AgentAction): DvexIntent = DvexIntent.MediaControl(
    when (action.type) {
      AgentActionType.MEDIA_NEXT -> MediaAction.NEXT
      else -> MediaAction.PREVIOUS
    }
  )
}

/** MEDIA_PLAY / MEDIA_PAUSE (both map to the real play-pause media key). */
class MediaPlayPauseHandler(override val actionType: AgentActionType) : RouterBackedHandler() {
  override fun intentFor(action: AgentAction): DvexIntent =
    DvexIntent.MediaControl(MediaAction.PLAY_PAUSE)
}

/** TAP_ELEMENT — clicks a visible node found by text/content description. */
class TapElementHandler : AgentActionHandler {
  override val actionType = AgentActionType.TAP_ELEMENT

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
    val label = action.query.ifBlank { action.target }
    if (label.isBlank()) {
      return AgentActionResult(AgentActionStatus.FAILURE, "I don't know what to tap.")
    }
    val node = DvexAccessibilityService.findNodesByText(label)
      .firstOrNull { it.isVisibleToUser }
      ?: return AgentActionResult(
        AgentActionStatus.FAILURE,
        "I couldn't see \"$label\" on the screen."
      )
    val clicked = DvexAccessibilityService.clickNode(node)
    return if (clicked) {
      AgentActionResult(AgentActionStatus.SUCCESS, "Tapped \"$label\".", verified = true)
    } else {
      AgentActionResult(AgentActionStatus.FAILURE, "I couldn't tap \"$label\".")
    }
  }
}

/** TYPE_TEXT — focuses a field and injects text, verifying the field content. */
class TypeTextHandler : AgentActionHandler {
  override val actionType = AgentActionType.TYPE_TEXT

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
    val text = action.query
    if (text.isBlank()) {
      return AgentActionResult(AgentActionStatus.FAILURE, "I don't know what to type.")
    }
    val field = DvexAccessibilityService.focusEditableField(action.target.ifBlank { null })
      ?: return AgentActionResult(
        AgentActionStatus.FAILURE,
        "I couldn't find a text field on the screen."
      )
    if (!DvexAccessibilityService.setTextInNode(field, text)) {
      return AgentActionResult(AgentActionStatus.FAILURE, "I couldn't type that.")
    }
    // Verification: read the field back when the platform exposes its content.
    val observed = field.text?.toString()
    val verified = observed != null && observed.contains(text, ignoreCase = true)
    return AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = if (verified) "Typed \"$text\"." else "I typed \"$text\", but I couldn't confirm it.",
      verified = verified
    )
  }
}

/** CLEAR_TEXT — empties a focused field and verifies it is empty. */
class ClearTextHandler : AgentActionHandler {
  override val actionType = AgentActionType.CLEAR_TEXT

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
    val field = DvexAccessibilityService.focusEditableField(action.target.ifBlank { null })
      ?: return AgentActionResult(
        AgentActionStatus.FAILURE,
        "I couldn't find a text field on the screen."
      )
    if (!DvexAccessibilityService.clearTextInNode(field)) {
      return AgentActionResult(AgentActionStatus.FAILURE, "I couldn't clear that field.")
    }
    val observed = field.text?.toString()
    val verified = observed == null || observed.isBlank()
    return AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = if (verified) "Cleared the field." else "I cleared the field, but I couldn't confirm it.",
      verified = verified
    )
  }
}

/** SUBMIT_TEXT — activates a visible search/submit control; honest when absent. */
class SubmitTextHandler : AgentActionHandler {
  override val actionType = AgentActionType.SUBMIT_TEXT

  override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
    val submitted = DvexAccessibilityService.submitVisibleSearchControl()
    return if (submitted) {
      AgentActionResult(AgentActionStatus.SUCCESS, "Submitted.", verified = true)
    } else {
      AgentActionResult(
        AgentActionStatus.FAILURE,
        "I couldn't find a search button to submit."
      )
    }
  }
}

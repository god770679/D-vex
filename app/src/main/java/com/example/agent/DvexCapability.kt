package com.example.agent

import android.content.Context
import com.example.control.DvexAccessibilityService
import com.example.permissions.DvexPermissionManager

/**
 * D-VEX CAPABILITY LAYER
 *
 * Declares what D-VEX can actually control on this device right now. Every action
 * resolves its capability through here before execution, so a missing permission or
 * an unsupported feature produces an honest explanation instead of a silent failure.
 */
enum class DvexCapability(
  val id: String,
  val displayName: String,
  val requiredPermission: String? = null
) {
  APP_LAUNCH("app_launch", "Installed apps"),
  IN_APP_SEARCH("in_app_search", "App search"),
  LAUNCH_URL("launch_url", "Browser"),
  NAVIGATION("navigation", "Device navigation"),
  SETTINGS("settings", "Device settings"),
  APP_USAGE("app_usage", "App usage", "usage_access"),
  UI_INTERACTION("ui_interaction", "App interaction", "accessibility"),
  CAMERA("camera", "Camera", "camera"),
  MICROPHONE("microphone", "Microphone", "microphone"),
  LOCATION("location", "Location", "location"),
  WEATHER("weather", "Weather", "location"),
  NOTIFICATIONS("notifications", "Notifications", "notification_listener"),
  MESSAGING("messaging", "Messaging", "accessibility_or_sms"),
  MEDIA("media", "Media")
}

/** Real availability of a capability on the current device. */
sealed class CapabilityState {
  object Available : CapabilityState()

  /** A runtime/special permission is missing — D-VEX must say so, never pretend. */
  data class PermissionRequired(
    val permission: String,
    val message: String
  ) : CapabilityState()

  /** The device genuinely cannot do this (no handler, no package). */
  data class Unsupported(val reason: String) : CapabilityState()
}

/**
 * Capability probe contract. The engine depends on this (not the concrete manager)
 * so alternative probes (tests, future remote capabilities) can be supplied.
 */
interface DvexCapabilityProbe {
  fun state(capability: DvexCapability): CapabilityState
  fun isAvailable(capability: DvexCapability): Boolean
}

/**
 * Probes capabilities against the real device state. No capability is ever assumed.
 */
class DvexCapabilityManager(private val context: Context) : DvexCapabilityProbe {

  override fun state(capability: DvexCapability): CapabilityState = when (capability) {
    DvexCapability.APP_LAUNCH,
    DvexCapability.IN_APP_SEARCH,
    DvexCapability.LAUNCH_URL,
    DvexCapability.NAVIGATION,
    DvexCapability.SETTINGS,
    DvexCapability.MEDIA -> CapabilityState.Available

    DvexCapability.APP_USAGE ->
      if (DvexPermissionManager.hasUsageAccess(context)) {
        CapabilityState.Available
      } else {
        CapabilityState.PermissionRequired(
          permission = "usage_access",
          message = "I need Usage access to read your recent apps."
        )
      }

    DvexCapability.UI_INTERACTION ->
      if (DvexPermissionManager.isAccessibilityServiceEnabled(context) && DvexAccessibilityService.isEnabled(context)) {
        CapabilityState.Available
      } else {
        CapabilityState.PermissionRequired(
          permission = "accessibility",
          message = "I need accessibility permission to interact with apps for you."
        )
      }

    DvexCapability.CAMERA ->
      if (DvexPermissionManager.hasCameraPermission(context)) {
        CapabilityState.Available
      } else {
        CapabilityState.PermissionRequired(
          permission = "camera",
          message = "I need camera permission before I can use the camera."
        )
      }

    DvexCapability.MICROPHONE ->
      if (DvexPermissionManager.hasAudioPermission(context)) {
        CapabilityState.Available
      } else {
        CapabilityState.PermissionRequired(
          permission = "microphone",
          message = "I need microphone permission to listen to commands."
        )
      }

    DvexCapability.LOCATION, DvexCapability.WEATHER ->
      if (DvexPermissionManager.hasLocationPermission(context)) {
        CapabilityState.Available
      } else {
        CapabilityState.PermissionRequired(
          permission = "location",
          message = "I need location permission to get the weather for your area."
        )
      }

    DvexCapability.NOTIFICATIONS ->
      if (DvexPermissionManager.isNotificationListenerEnabled(context)) {
        CapabilityState.Available
      } else {
        CapabilityState.PermissionRequired(
          permission = "notification_listener",
          message = "I need notification access to read your alerts."
        )
      }

    DvexCapability.MESSAGING ->
      if (DvexAccessibilityService.isEnabled(context) ||
        DvexPermissionManager.hasSmsPermission(context)
      ) {
        CapabilityState.Available
      } else {
        CapabilityState.PermissionRequired(
          permission = "accessibility_or_sms",
          message = "I need messaging permission to send that."
        )
      }
  }

  override fun isAvailable(capability: DvexCapability): Boolean = state(capability) is CapabilityState.Available
}

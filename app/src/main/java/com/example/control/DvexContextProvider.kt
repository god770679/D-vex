package com.example.control

import android.content.Context
import com.example.model.DvexContextSnapshot
import com.example.permissions.DvexPermissionManager

/**
 * D-VEX ON-DEMAND CONTEXT PROVIDER
 *
 * Supplies the CURRENT foreground application and accessibility state when an agent
 * or task actually needs it — and nothing more.
 *
 * Guiding rules:
 *  - NO continuous monitoring. This provider is never called by a loop, timer or
 *    background thread. It is invoked at the exact moment a task needs context.
 *  - NO screen content, microphone audio, camera frames or user-activity harvesting.
 *  - The result is HONEST: it reports exactly what Android reports, or null when it
 *    cannot read a fact (permission denied, API unavailable, no data yet).
 *  - Nothing is invented. A null foregroundPackage means "not known on this device".
 *
 * The provider reads two existing facts through existing infrastructure:
 *  - foregroundPackageOrNull() from AppLauncherRepository (UsageEvents foreground
 *    read, requires a user-granted usage-access permission)
 *  - DvexAccessibilityService.isServiceConnected (optional user-authorized feature)
 *
 * Both sources return a normal result immediately; there is no waiting, no retry
 * loop and no polling.
 */
class DvexContextProvider(private val context: Context) {

  /** Build a snapshot in one on-demand call. The result is advisory at best. */
  fun buildSnapshot(): DvexContextSnapshot {
    val foregroundPackage = AppLauncherRepository(context).foregroundPackageOrNull()

    // Optional feature: if the caller has not enabled accessibility control, we do
    // not guess. A null value means "not reported / not enabled" rather than a
    // negative claim about the user's consent.
    val accessibilityConnected =
      if (DvexAccessibilityService.isEnabled(context)) {
        DvexAccessibilityService.isServiceConnected.value
      } else {
        null
      }

    // Intentional: if we genuinely cannot read one of these facts, the snapshot
    // carries null instead of a fabricated app name or task description.
    return DvexContextSnapshot(
      foregroundPackage = foregroundPackage,
      accessibilityConnected = accessibilityConnected
    )
  }

  /**
   * Whether we can currently learn which app the user is working in.
   *
   * This is a read-only shortcut used by callers that want an early, cheap check
   * before doing work that depends on the foreground app. It does not start any
   * monitoring, loop or timer; it simply asks Android once.
   */
  fun canProvideForegroundPackage(): Boolean {
    return DvexPermissionManager.hasUsageAccess(context)
  }
}

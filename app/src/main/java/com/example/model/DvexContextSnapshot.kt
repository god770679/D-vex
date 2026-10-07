package com.example.model

/**
 * D-VEX CONTEXT SNAPSHOT
 *
 * ONE honest picture of the task the user is working on right now. Built only from
 * information Android already exposes — never from screen content, microphone audio,
 * camera frames, or user activity. Everything here is advisory and expires with the
 * turn that consumed it (the provider is on-demand only).
 *
 * The snapshot carries no authority: a null means the app could not tell. No
 * intent, package name or activity text is ever invented by D-VEX.
 */
data class DvexContextSnapshot(
  /** Real foreground application package, when Android reports it. Null otherwise. */
  val foregroundPackage: String?,
  /** Real accessibility service connection state, if the feature is enabled on device. */
  val accessibilityConnected: Boolean?
) {
  /** True when the snapshot carries a specific foreground package we can reason about. */
  val hasPackage: Boolean get() = foregroundPackage != null

  /** True when the snapshot is fully available (a foreground app is known). */
  val isFullyAvailable: Boolean get() = hasPackage && accessibilityConnected == true

  /** True when the snapshot is entirely unavailable and could not be read. */
  val isUnavailable: Boolean get() = foregroundPackage == null && accessibilityConnected == null

  override fun toString(): String {
    return "DvexContextSnapshot(foregroundPackage=$foregroundPackage, accessibilityConnected=$accessibilityConnected)"
  }
}

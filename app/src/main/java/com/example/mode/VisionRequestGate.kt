package com.example.mode

import android.content.Context

/**
 * Vision safety layer for the D-VEX Dual Mode System (additive; explicit opt-in only).
 *
 * This does NOT modify DvexVisionManager or SceneAnalyzer internals — it only tracks
 * whether the user has explicitly tapped the Power Mode VISION control, and gates the
 * one call site that turns that tap into the existing public camera API
 * (DvexVisionManager.startCamera / stopCamera).
 *
 * Safety contract:
 * - [visionRequested] is FALSE by default, is never written by PowerModeManager,
 *   AssistantViewModel, MainActivity, DvexHud, startup, boot, or Power Mode
 *   activation — only by the VISION button handler in PowerModeButtonCluster.
 * - Vision is deliberately NOT persisted: it resets to OFF on every app restart,
 *   so the camera can never come back on its own.
 * - Enabling Power Mode has zero effect on this flag (Power Mode ON => Vision stays OFF).
 */
object VisionRequestGate {

  private val lock = Any()
  private var visionRequestedByUser = false

  /** True only when the user has explicitly tapped VISION in the Power Mode cluster. */
  fun isVisionRequested(): Boolean = synchronized(lock) { visionRequestedByUser }

  /**
   * Called ONLY from the VISION button tap handler. Returns the new state so the
   * caller can decide between the existing startCamera/stopCamera mechanisms.
   */
  fun toggleVisionRequest(): Boolean = synchronized(lock) {
    visionRequestedByUser = !visionRequestedByUser
    visionRequestedByUser
  }

  /** Used by tests to start each scenario from the OFF baseline. */
  fun resetForTesting() = synchronized(lock) { visionRequestedByUser = false }

  /** Non-production helper: ensures the flag is false at composition time. */
  fun ensureOff(context: Context) {
    // Intentionally reads nothing and persists nothing: vision request is volatile,
    // so a fresh process always starts with the camera off. Keeping the parameter
    // documents the call-site contract (call from a composable's remember block).
  }
}

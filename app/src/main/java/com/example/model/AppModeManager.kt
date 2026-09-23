package com.example.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages operating modes for D-VEX:
 * 1. STANDARD MODE (default):
 *    - Existing features only
 *    - SEND_MESSAGE and CALL_CONTACT require voice/safety confirmation
 * 2. POWER MODE (opt-in):
 *    - Surfaces Orb toggle, Recent App, Notifications, Device Control, Vision toggle
 *    - Automatic execution of SEND_MESSAGE (skips confirmation flow)
 *    - CALL_CONTACT STILL requires confirmation (safety protection)
 *    - Vision/Camera NEVER auto-starts without explicit toggle
 */
object AppModeManager {
  private const val TAG = "[D-VEX][MODE]"
  private const val PREFS_NAME = "dvex_app_mode_prefs"
  private const val KEY_POWER_MODE = "is_power_mode"
  private const val KEY_VISION_ACTIVE = "is_vision_active"

  private val _isPowerMode = MutableStateFlow(false)
  val isPowerMode: StateFlow<Boolean> = _isPowerMode.asStateFlow()

  private val _isVisionActive = MutableStateFlow(false)
  val isVisionActive: StateFlow<Boolean> = _isVisionActive.asStateFlow()

  private var prefs: SharedPreferences? = null

  /**
   * Initializes preferences and restores saved mode.
   */
  fun init(context: Context) {
    if (prefs == null) {
      val appContext = context.applicationContext ?: context
      prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      _isPowerMode.value = prefs?.getBoolean(KEY_POWER_MODE, false) ?: false
      // Vision is strictly OFF by default or restored from explicit user toggle
      _isVisionActive.value = prefs?.getBoolean(KEY_VISION_ACTIVE, false) ?: false
      Log.i(TAG, "Initialized AppModeManager: PowerMode=${_isPowerMode.value}, VisionActive=${_isVisionActive.value}")
    }
  }

  /**
   * Enables or disables Power Mode.
   * State is persisted across app restarts.
   */
  fun setPowerMode(enabled: Boolean, context: Context? = null) {
    _isPowerMode.value = enabled
    val p = prefs ?: context?.let { (it.applicationContext ?: it).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    p?.edit()?.putBoolean(KEY_POWER_MODE, enabled)?.apply()
    Log.i(TAG, "Power Mode set to: $enabled")

    // Turning off Power Mode resets Vision Detection
    if (!enabled && _isVisionActive.value) {
      setVisionActive(false, context)
    }
  }

  /**
   * Toggles Power Mode on/off.
   */
  fun togglePowerMode(context: Context? = null) {
    setPowerMode(!_isPowerMode.value, context)
  }

  /**
   * Sets Vision detection camera feed state.
   * Requires explicit user action; never auto-started by entering Power Mode.
   */
  fun setVisionActive(active: Boolean, context: Context? = null) {
    _isVisionActive.value = active
    val p = prefs ?: context?.let { (it.applicationContext ?: it).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    p?.edit()?.putBoolean(KEY_VISION_ACTIVE, active)?.apply()
    Log.i(TAG, "Vision Detection set to: $active")
  }

  /**
   * Toggles Vision detection state.
   */
  fun toggleVision(context: Context? = null) {
    setVisionActive(!_isVisionActive.value, context)
  }

  /**
   * For unit tests to reset state cleanly.
   */
  fun resetForTesting(context: Context? = null) {
    prefs = null
    _isPowerMode.value = false
    _isVisionActive.value = false
    context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
  }
}

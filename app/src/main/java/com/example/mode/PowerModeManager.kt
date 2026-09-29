package com.example.mode

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * D-VEX Dual Mode System — Power Mode state (additive feature).
 *
 * Stores a single persisted boolean (`power_mode_enabled`) in SharedPreferences and
 * exposes it as an observable [StateFlow]. Default is OFF (Standard Mode); a value
 * persisted in an earlier run is honoured, so the mode survives app restart.
 *
 * Safety properties (by design):
 * - This class only holds state. It never starts the camera, never places calls,
 *   never sends messages, and never touches any execution component. Turning Power
 *   Mode ON has exactly one effect: the observable flag flips to true.
 * - Consumers (HUD toggle, SEND_MESSAGE gate in DvexToolRouter) read the flag
 *   explicitly; nothing here drives behaviour on its own.
 *
 * Deliberately simple: SharedPreferences + StateFlow, no DataStore, no Room.
 */
class PowerModeManager private constructor(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val lock = Any()

  private val _powerModeEnabled = MutableStateFlow(readPersistedValue())
  val powerModeEnabled: StateFlow<Boolean> = _powerModeEnabled.asStateFlow()

  /** Current Power Mode state (thread-safe read). */
  fun isPowerModeEnabled(): Boolean = _powerModeEnabled.value

  /**
   * Persists and publishes the new Power Mode state. Safe to call from any thread.
   */
  fun setEnabled(enabled: Boolean) {
    synchronized(lock) {
      prefs.edit().putBoolean(KEY_POWER_MODE_ENABLED, enabled).apply()
      _powerModeEnabled.value = enabled
    }
  }

  /** Convenience flip for the header toggle. */
  fun toggle() {
    setEnabled(!isPowerModeEnabled())
  }

  private fun readPersistedValue(): Boolean =
    prefs.getBoolean(KEY_POWER_MODE_ENABLED, DEFAULT_POWER_MODE_ENABLED)

  companion object {
    private const val PREFS_NAME = "dvex_power_mode_prefs"

    /** Persisted key, exactly as specified: `power_mode_enabled`. */
    const val KEY_POWER_MODE_ENABLED = "power_mode_enabled"

    /** Power Mode is opt-in: default OFF (Standard Mode). */
    const val DEFAULT_POWER_MODE_ENABLED = false

    @Volatile
    private var instance: PowerModeManager? = null

    fun getInstance(context: Context): PowerModeManager {
      return instance ?: synchronized(this) {
        instance ?: PowerModeManager(context.applicationContext).also { instance = it }
      }
    }
  }
}

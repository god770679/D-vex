package com.example.ai

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RUNTIME GEMINI API-KEY OVERRIDE.
 *
 * The build-time key ([BuildConfig.GEMINI_API_KEY], injected by CI from the
 * repository secret) is shared by every install of a release APK. When that
 * key's free-tier quota is spent (Google reports `429 RESOURCE_EXHAUSTED`,
 * `limit: 20` per day), every user of the APK sees the honest local fallback —
 * and until now there was no way to fix that from the phone.
 *
 * This store lets the user paste their OWN Gemini key in Settings. Resolution
 * order for [resolve]:
 *   1. the runtime key stored on this device (if present and not blank)
 *   2. the embedded build-time key
 *   3. null — [com.example.ai.GeminiEngine] then reports "no key" honestly and
 *      the response layer falls back without pretending a canned reply is Gemini.
 *
 * Security rules:
 *  - The stored key is written to app-private SharedPreferences only.
 *  - The key value is NEVER logged — only whether a custom key is present.
 *  - This class never prints the value, and listeners receive the key only so
 *    they can act on it; the one production listener (the quota gate) ignores
 *    the value entirely.
 */
object GeminiKeyStore {

  private const val TAG = "[D-VEX][AI-KEY]"
  private const val PREFS_NAME = "dvex_ai_key_prefs"
  private const val KEY_GEMINI_RUNTIME = "gemini_api_key_runtime"

  /** Notified when the effective key changes (value may be null = build key). */
  private val keyChangeListeners = CopyOnWriteArrayList<(String?) -> Unit>()

  /** The key Gemini engines should use right now: runtime override, else build-time. */
  fun resolve(context: Context): String? {
    return readRuntimeKey(context) ?: buildConfigKey()
  }

  /** True when the user has stored their own key on this device. */
  fun hasRuntimeKey(context: Context): Boolean = readRuntimeKey(context) != null

  /** The runtime override only; null when the user relies on the build-time key. */
  fun readRuntimeKey(context: Context): String? = try {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getString(KEY_GEMINI_RUNTIME, null)
      ?.trim()
      ?.takeIf { it.isNotBlank() }
  } catch (e: Exception) {
    Log.w(TAG, "Could not read runtime key (${e.javaClass.simpleName}); using build-time key")
    null
  }

  /**
   * Stores (or clears, when [rawKey] is blank/null) the user's own key and
   * notifies listeners so e.g. a quota cooldown recorded against the OLD key is
   * lifted immediately instead of blocking the new key for up to an hour.
   */
  fun setRuntimeKey(context: Context, rawKey: String?) {
    val normalized = rawKey?.trim().orEmpty()
    try {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_GEMINI_RUNTIME, normalized.ifBlank { null })
        .apply()
    } catch (e: Exception) {
      Log.w(TAG, "Could not persist runtime key (${e.javaClass.simpleName})")
      return
    }
    // Never the value — only whether a custom key is now active.
    Log.i(TAG, "Runtime Gemini key ${if (normalized.isBlank()) "cleared" else "saved"} (value not logged)")
    keyChangeListeners.forEach { listener ->
      try {
        listener(if (normalized.isBlank()) null else normalized)
      } catch (e: Exception) {
        Log.w(TAG, "Key-change listener failed: ${e.javaClass.simpleName}")
      }
    }
  }

  /** Subscribe to key changes; returns the listener so callers can unregister. */
  fun addOnKeyChangedListener(listener: (String?) -> Unit): (String?) -> Unit {
    keyChangeListeners.add(listener)
    return listener
  }

  fun removeOnKeyChangedListener(listener: (String?) -> Unit) {
    keyChangeListeners.remove(listener)
  }

  /** Visible for tests: current listener count (leak guard). */
  internal fun listenerCount(): Int = keyChangeListeners.size

  private fun buildConfigKey(): String? = try {
    BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
  } catch (e: Exception) {
    // BuildConfig may be absent in some unit-test sandboxes.
    null
  }
}

package com.example.control

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.permissions.DvexPermissionManager

/**
 * Real recently-used apps for D-VEX (Bug 5).
 *
 * Reads actual app usage from [UsageStatsManager] (requires the special
 * PACKAGE_USAGE_STATS "Usage access" permission granted by the user in Settings —
 * there is no runtime dialog for it). App display names are resolved via
 * [PackageManager]; packages that fail to resolve are skipped, never guessed.
 *
 * Honesty contract:
 * - [getRecentApps] returns real data only, or a typed [UsageAccessError] —
 *   it never fabricates app names or timestamps.
 * - A granted-but-empty usage history yields an honest empty list.
 * - Query window: the last [LOOKBACK_MS] (default 6 hours), newest first.
 */
class RecentAppsProvider(private val context: Context) {

  /** One real recently-used app, resolved to a human-readable label. */
  data class RecentApp(
    val packageName: String,
    val appName: String,
    val lastTimeUsed: Long
  )

  /** Exact reason real usage data is unavailable. */
  sealed class UsageAccessError(message: String) : Exception(message) {
    object PermissionNotGranted : UsageAccessError("usage_access_not_granted")
    object Unsupported : UsageAccessError("usage_stats_unsupported")
    data class Failed(val detail: String) : UsageAccessError("usage_stats_failed: $detail")
  }

  /**
   * Returns the user's most recently used apps (newest first) over the last
   * [lookbackMs]. Uses [UsageEvents] (event stream) so ordering reflects actual
   * ACTIVITY_RESUMED times, not aggregated day buckets.
   */
  fun getRecentApps(lookbackMs: Long = DEFAULT_LOOKBACK_MS): Result<List<RecentApp>> {
    if (!DvexPermissionManager.hasUsageAccess(context)) {
      return Result.failure(UsageAccessError.PermissionNotGranted)
    }

    val usageStatsManager =
      context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return Result.failure(UsageAccessError.Unsupported)

    return try {
      val now = System.currentTimeMillis()
      val start = now - lookbackMs

      // Package -> last ACTIVITY_RESUMED event time within the window.
      val lastResumedByPackage = mutableMapOf<String, Long>()
      val events = usageStatsManager.queryEvents(start, now)
      val event = UsageEvents.Event()
      while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
          !event.packageName.isNullOrBlank()
        ) {
          val existing = lastResumedByPackage[event.packageName]
          if (existing == null || event.timeStamp > existing) {
            lastResumedByPackage[event.packageName] = event.timeStamp
          }
        }
      }

      val apps = lastResumedByPackage.entries
        .mapNotNull { (pkg, time) ->
          resolveAppName(pkg)?.let { label -> RecentApp(pkg, label, time) }
        }
        .sortedByDescending { it.lastTimeUsed }

      Log.i(TAG, "Recent apps (real usage, ${lookbackMs / 3_600_000.0}h window): ${apps.size} entries")
      Result.success(apps)
    } catch (e: Exception) {
      Log.e(TAG, "Usage stats query failed", e)
      Result.failure(UsageAccessError.Failed(e.message ?: "query_error"))
    }
  }

  /** Resolves a package to its display label; null when unresolvable (never guessed). */
  private fun resolveAppName(packageName: String): String? {
    return try {
      val pm = context.packageManager
      val info = pm.getApplicationInfo(packageName, 0)
      pm.getApplicationLabel(info)?.toString()?.ifBlank { null }
    } catch (e: PackageManager.NameNotFoundException) {
      null // uninstalled or system-internal package: skip honestly
    } catch (e: Exception) {
      Log.w(TAG, "Label resolve failed for $packageName: ${e.message}")
      null
    }
  }

  companion object {
    private const val TAG = "[D-VEX][RECENT-APPS]"

    /** Default history window: 6 hours. */
    const val DEFAULT_LOOKBACK_MS = 6 * 60 * 60 * 1000L
  }
}

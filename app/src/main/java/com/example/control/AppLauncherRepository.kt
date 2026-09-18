package com.example.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.data.remote.ToolExecutionResult
import com.example.data.remote.ToolResultStatus
import java.util.Locale

data class DiscoveredApp(
  val label: String,
  val packageName: String,
  val activityName: String
)

class AppLauncherRepository(private val context: Context) {

  private var cachedApps: List<DiscoveredApp> = emptyList()
  private var lastScanTime: Long = 0

  init {
    refreshInstalledApps()
  }

  fun refreshInstalledApps(): List<DiscoveredApp> {
    return try {
      val pm = context.packageManager
      val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
      }
      val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
      } else {
        pm.queryIntentActivities(mainIntent, 0)
      }

      val apps = resolveInfos.mapNotNull { ri ->
        val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
        val act = ri.activityInfo?.name ?: return@mapNotNull null
        val label = ri.loadLabel(pm)?.toString()?.trim() ?: pkg
        DiscoveredApp(label = label, packageName = pkg, activityName = act)
      }.distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.ROOT) }

      cachedApps = apps
      lastScanTime = System.currentTimeMillis()
      Log.i(TAG, "Discovered ${apps.size} launchable Android applications.")
      apps
    } catch (e: Exception) {
      Log.e(TAG, "Error scanning installed applications", e)
      emptyList()
    }
  }

  fun getDiscoveredApps(): List<DiscoveredApp> {
    if (cachedApps.isEmpty() || System.currentTimeMillis() - lastScanTime > 60_000) {
      refreshInstalledApps()
    }
    return cachedApps
  }

  /**
   * Matches natural language string (e.g. "open youtube", "launch chrome", "whatsapp")
   * against discovered packages and launches the matched app.
   */
  fun launchAppByName(rawQuery: String): ToolExecutionResult {
    val cleanQuery = rawQuery
      .lowercase(Locale.ROOT)
      .replace("open", "")
      .replace("launch", "")
      .replace("start", "")
      .replace("app", "")
      .trim()

    if (cleanQuery.isEmpty()) {
      return ToolExecutionResult(
        status = ToolResultStatus.FAILED,
        toolName = "launch_app",
        message = "No application name specified."
      )
    }

    val apps = getDiscoveredApps()

    // 1. Direct label exact match
    var matchedApp = apps.find { it.label.lowercase(Locale.ROOT) == cleanQuery }

    // 2. Starts with / Substring match on label
    if (matchedApp == null) {
      matchedApp = apps.find { it.label.lowercase(Locale.ROOT).startsWith(cleanQuery) }
    }
    if (matchedApp == null) {
      matchedApp = apps.find { it.label.lowercase(Locale.ROOT).contains(cleanQuery) }
    }

    // 3. Match package name segments (e.g., "youtube", "chrome", "whatsapp")
    if (matchedApp == null) {
      matchedApp = apps.find { it.packageName.lowercase(Locale.ROOT).contains(cleanQuery) }
    }

    // 4. Common popular alias fallback
    if (matchedApp == null) {
      matchedApp = when (cleanQuery) {
        "browser", "internet", "web", "chrome", "google chrome" -> apps.find { it.packageName.contains("chrome") || it.packageName.contains("browser") }
        "gmail", "email", "mail", "google mail" -> apps.find { it.packageName.contains("gm") || it.packageName.contains("email") || it.packageName.contains("mail") }
        "whatsapp" -> apps.find { it.packageName.contains("whatsapp") }
        "youtube", "yt" -> apps.find { it.packageName.contains("youtube") }
        "maps", "navigation", "google maps" -> apps.find { it.packageName.contains("maps") }
        "camera" -> apps.find { it.packageName.contains("camera") }
        "settings" -> apps.find { it.packageName.contains("settings") }
        "gallery", "photos" -> apps.find { it.packageName.contains("gallery") || it.packageName.contains("photos") }
        "messages", "sms", "messaging", "text messages" -> apps.find { it.packageName.contains("mms") || it.packageName.contains("messaging") || it.packageName.contains("messages") }
        "dialer", "phone", "call" -> apps.find { it.packageName.contains("dialer") || it.packageName.contains("phone") }
        "clock", "alarm", "timer" -> apps.find { it.packageName.contains("deskclock") || it.packageName.contains("clock") }
        "music", "player", "spotify" -> apps.find { it.packageName.contains("music") || it.packageName.contains("spotify") || it.packageName.contains("audio") }
        else -> null
      }
    }

    if (matchedApp == null) {
      return ToolExecutionResult(
        status = ToolResultStatus.FAILED,
        toolName = "launch_app",
        message = "Application \"$cleanQuery\" not found on device."
      )
    }

    return launchPackage(matchedApp.packageName, matchedApp.label)
  }

  fun launchPackage(packageName: String, displayLabel: String = packageName): ToolExecutionResult {
    return try {
      val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
      if (launchIntent != null) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        ToolExecutionResult(
          status = ToolResultStatus.SUCCESS,
          toolName = "launch_app",
          message = "Opening $displayLabel."
        )
      } else {
        ToolExecutionResult(
          status = ToolResultStatus.FAILED,
          toolName = "launch_app",
          message = "Could not create launch intent for $displayLabel."
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to launch $packageName", e)
      ToolExecutionResult(
        status = ToolResultStatus.FAILED,
        toolName = "launch_app",
        message = "Launch failed: ${e.localizedMessage ?: "Unknown error"}"
      )
    }
  }

  companion object {
    private const val TAG = "AppLauncherRepo"
  }
}

package com.example.model

enum class AiCoreState(val label: String) {
  IDLE("SYSTEM IDLE"),
  LISTENING("VOICE ACTIVE"),
  THINKING("ANALYZING"),
  PROCESSING("PROCESSING"),
  RESPONDING("TRANSMITTING"),
  EXECUTING("EXECUTING"),
  ERROR("WARNING"),
  OFFLINE("SYSTEM OFFLINE")
}

enum class VoiceState(val label: String) {
  IDLE("IDLE"),
  STANDBY("STANDBY"),
  LISTENING("LISTENING..."),
  PROCESSING("PROCESSING..."),
  SPEAKING("SPEAKING..."),
  EXECUTING_ACTION("EXECUTING..."),
  ERROR("ERROR")
}

enum class NavItem(val title: String) {
  HOME("HOME"),
  CALL("CALL"),
  MSG("MSG"),
  EMAIL("EMAIL"),
  MAPS("MAPS"),
  APPS("APPS"),
  SETTINGS("SETTINGS"),
  
  // Right sidebar
  AI("AI"),
  TOOLS("TOOLS"),
  MEMORY("MEMORY"),
  SECURITY("SECURITY"),
  POWER("POWER")
}

/**
 * REAL device vitals. Every field is nullable on purpose: null = the source has
 * not delivered a reading yet (or is unreadable on this device), and the UI must
 * render an honest "--" for it — never a fabricated default. Populated ONLY by
 * AssistantViewModel from Android system APIs (ACTION_BATTERY_CHANGED broadcast,
 * ActivityManager, StatFs, /proc/stat, thermal zone, ConnectivityManager,
 * PowerManager thermal status).
 */
data class SystemVitals(
  val cpuUsagePercent: Int? = null,
  val cpuTempCelsius: Int? = null,
  val ramUsagePercent: Int? = null,
  val storageUsagePercent: Int? = null,
  val batteryPercent: Int? = null,
  /** Real charging state from EXTRA_STATUS; null until the broadcast arrives. */
  val isCharging: Boolean? = null,
  /** Real transport label (WIFI / CELLULAR / OFFLINE / …); null until sampled. */
  val networkLabel: String? = null,
  /** Real thermal status (PowerManager, API 29+); null where unsupported. */
  val thermalStatus: String? = null
) {
  /** True once ANY real reading has landed — drives LIVE vs honest-standby tags. */
  val hasAnyData: Boolean
    get() = cpuUsagePercent != null || cpuTempCelsius != null || ramUsagePercent != null ||
      storageUsagePercent != null || batteryPercent != null || isCharging != null ||
      networkLabel != null || thermalStatus != null
}

data class NotificationItem(
  val id: String,
  val title: String,
  val subtitle: String = "ACTIVE",
  val timestamp: String = "NOW"
)

data class ActivityItem(
  val id: String,
  val action: String,
  val timestamp: String,
  val isSuccess: Boolean = true
)

/**
 * Weather Telemetry panel state. No fabricated defaults: [hasData]=false renders
 * the panel's honest STANDBY state until a real fetch succeeds. Demo values
 * ("30°C / CHENNAI / PARTLY CLOUDY") are never shown as real data.
 */
data class WeatherInfo(
  val hasData: Boolean = false,
  val temperatureCelsius: Int = 0,
  val condition: String = "",
  val location: String = "",
  val highCelsius: Int = 0,
  val lowCelsius: Int = 0
)

/**
 * One real entry for the on-screen RECENT APPS overlay (UsageStatsManager data,
 * resolved via PackageManager — never fabricated).
 */
data class RecentAppEntry(
  val packageName: String,
  val appName: String,
  val lastUsedTimestampMs: Long,
  val lastUsedLabel: String
)

/** Camera permission state for the Vision panel (Phase 1). */
data class CameraPermissionState(
  val granted: Boolean = false,
  val requested: Boolean = false
)

/**
 * Geo Coordinates panel state. Fabricated demo defaults removed (Bug 4 regression
 * audit): [hasData]=false renders the panel's honest STANDBY state until a real
 * device fix succeeds — a real GPS fix never shows "CHENNAI" or fake satellite data.
 */
data class LocationInfo(
  val hasData: Boolean = false,
  val city: String = "",
  val latitude: String = "",
  val longitude: String = "",
  val altitudeMeters: Int? = null,
  val accuracyMeters: Int? = null
)

data class DvexUiState(
  val aiState: AiCoreState = AiCoreState.IDLE,
  val voiceState: VoiceState = VoiceState.IDLE,
  val selectedNavigation: NavItem = NavItem.HOME,
  val systemStatus: SystemVitals = SystemVitals(),
  /**
   * Memory-core gauge percentage. Null until the REAL ActivityManager reading
   * arrives — the previous hardcoded 76 was invented telemetry.
   */
  val memoryPercentage: Int? = null,
  /**
   * The LAST final D-VEX response (the exact string sent to TTS). Empty until the
   * brain has produced a real reply — never a hardcoded conversational line.
   */
  val responseText: String = "",
  /**
   * The user's latest input (typed or spoken), shown above the response in the
   * conversation card. Empty until the user says something.
   */
  val lastUserInput: String = "",
  val weather: WeatherInfo = WeatherInfo(),
  val location: LocationInfo = LocationInfo(),
  val cameraPermission: CameraPermissionState = CameraPermissionState(),
  /** Real usage-stats list shown by the on-screen RECENT APPS overlay; null = hidden. */
  val recentAppsOverlay: List<RecentAppEntry>? = null,
  /** True when the overlay is open but usage access is denied — honest permission state. */
  val recentAppsOverlayPermissionRequired: Boolean = false,
  // Bug 5: System Alerts is fed ONLY by DvexNotificationListenerService's real
  // captured feed (collected in AssistantViewModel). No hardcoded demo data —
  // an empty list renders the panel's honest empty/permission-required state.
  val notifications: List<NotificationItem> = emptyList(),
  val recentActivities: List<ActivityItem> = listOf(
    ActivityItem("1", "Meeting reminder set", "10:30 AM"),
    ActivityItem("2", "Message transmitted", "09:45 AM"),
    ActivityItem("3", "Weather telemetry synced", "09:00 AM"),
    ActivityItem("4", "Camera optical scan opened", "08:15 AM"),
    ActivityItem("5", "Tactical note stored in core", "07:50 AM")
  )
)

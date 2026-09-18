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

data class SystemVitals(
  val cpuUsagePercent: Int = 42,
  val ramUsagePercent: Int = 68,
  val storageUsagePercent: Int = 54,
  val batteryPercent: Int = 89,
  val cpuTempCelsius: Int = 38
)

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

data class WeatherInfo(
  val temperatureCelsius: Int = 30,
  val condition: String = "PARTLY CLOUDY",
  val location: String = "CHENNAI",
  val highCelsius: Int = 34,
  val lowCelsius: Int = 26
)

data class LocationInfo(
  val city: String = "CHENNAI",
  val latitude: String = "13.0827° N",
  val longitude: String = "80.2707° E",
  val altitudeMeters: Int = 16
)

data class DvexUiState(
  val aiState: AiCoreState = AiCoreState.IDLE,
  val voiceState: VoiceState = VoiceState.IDLE,
  val selectedNavigation: NavItem = NavItem.HOME,
  val systemStatus: SystemVitals = SystemVitals(),
  val memoryPercentage: Int = 76,
  val responseText: String = "Yes Sir, I'm listening...",
  val weather: WeatherInfo = WeatherInfo(),
  val location: LocationInfo = LocationInfo(),
  val notifications: List<NotificationItem> = listOf(
    NotificationItem("1", "New message", "Encrypted link", "2m ago"),
    NotificationItem("2", "D-VEX ready", "All sub-systems nominal", "5m ago"),
    NotificationItem("3", "System active", "Secure protocol 4", "12m ago"),
    NotificationItem("4", "Battery check", "Level 89% optimal", "20m ago")
  ),
  val recentActivities: List<ActivityItem> = listOf(
    ActivityItem("1", "Meeting reminder set", "10:30 AM"),
    ActivityItem("2", "Message transmitted", "09:45 AM"),
    ActivityItem("3", "Weather telemetry synced", "09:00 AM"),
    ActivityItem("4", "Camera optical scan opened", "08:15 AM"),
    ActivityItem("5", "Tactical note stored in core", "07:50 AM")
  )
)

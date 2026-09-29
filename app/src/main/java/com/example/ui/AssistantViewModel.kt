package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.control.LocationProvider
import com.example.control.RecentAppsProvider
import com.example.data.remote.PendingConfirmation
import android.util.Log
import com.example.data.remote.RealTimeWebService
import com.example.model.AiCoreState
import com.example.model.DvexAssistantState
import com.example.model.DvexSettings
import com.example.model.DvexUiState
import com.example.model.LocationInfo
import com.example.model.NotificationItem
import com.example.model.RecentAppEntry
import com.example.model.VoiceState
import com.example.model.WeatherInfo
import com.example.mode.PowerModeManager
import com.example.mode.VisionRequestGate
import com.example.permissions.DvexPermissionManager
import com.example.repository.AssistantRepository
import com.example.service.DvexAssistantService
import com.example.service.DvexNotificationListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

  private val assistantRepo = AssistantRepository.getInstance(application)

  private val _uiState = MutableStateFlow(DvexUiState())
  val uiState: StateFlow<DvexUiState> = _uiState.asStateFlow()

  /** App context for permission checks feeding the System Alerts panel. */
  private val appContext: Context = application.applicationContext

  /**
   * System Alerts data source state (Bug 5): real captured notifications when the
   * Notification Listener is enabled; otherwise an empty feed so the panel can show
   * its honest "notification access required" state — never placeholder data.
   */
  private val notificationListenerEnabled = MutableStateFlow(false)

  val assistantState: StateFlow<DvexAssistantState> = assistantRepo.assistantState
  val settings: StateFlow<DvexSettings> = assistantRepo.settings
  val pendingConfirmation: StateFlow<PendingConfirmation?> = assistantRepo.pendingConfirmation
  val latestTranscript: StateFlow<String> = assistantRepo.latestTranscript

  init {
    viewModelScope.launch {
      assistantRepo.assistantState.collectLatest { state ->
        val mappedAiState = state.toAiCoreState()
        val mappedVoiceState = state.toVoiceState()
        val response = assistantRepo.latestResponse.value
        val transcript = assistantRepo.latestTranscript.value

        // The card shows the REAL final response (the same string sent to TTS).
        // Listening / Processing / ExecutingAction / Error are live PHASES, not
        // conversational text: showing them here would display internal tool names
        // and state labels, so the card keeps the last real reply instead. The
        // live phase is already visible on the reactor, the voice box and the
        // agent-activity popups.
        val displayText = when (state) {
          is DvexAssistantState.Speaking -> state.text
          is DvexAssistantState.Standby, is DvexAssistantState.Idle -> response
          // Wake detection is a SILENT state transition: no "listening for ..."
          // status text is fabricated for the card. It keeps the last real reply,
          // which is empty until a real response exists.
          is DvexAssistantState.WakeWordListening -> response
          is DvexAssistantState.Listening,
          is DvexAssistantState.Processing,
          is DvexAssistantState.ExecutingAction,
          is DvexAssistantState.Error -> _uiState.value.responseText
        }

        _uiState.value = _uiState.value.copy(
          aiState = mappedAiState,
          voiceState = mappedVoiceState,
          responseText = displayText
        )
      }
    }

    viewModelScope.launch {
      assistantRepo.latestResponse.collectLatest { resp ->
        val state = assistantRepo.assistantState.value
        if (state is DvexAssistantState.Speaking || state is DvexAssistantState.Standby || state is DvexAssistantState.Idle || state is DvexAssistantState.WakeWordListening) {
          _uiState.value = _uiState.value.copy(responseText = resp)
        }
      }
    }

    viewModelScope.launch {
      assistantRepo.latestTranscript.collectLatest { tr ->
        if (tr.isBlank()) return@collectLatest
        // BOTH input methods land here: voice (ASR transcript) and typed tactical
        // commands (processCommand publishes the typed text as the transcript).
        // The card therefore shows what the user actually said or typed.
        _uiState.value = _uiState.value.copy(lastUserInput = tr)
      }
    }

    // --- Bug 5: real System Alerts from the Notification Listener ---
    viewModelScope.launch {
      DvexNotificationListenerService.notifications.collect { captured ->
        val enabled = notificationListenerEnabled.value
        val realItems = captured.map { n ->
          NotificationItem(
            id = n.key.ifBlank { n.packageName },
            title = n.title,
            subtitle = n.appName,
            timestamp = formatNotificationTime(n.timestamp)
          )
        }
        _uiState.value = _uiState.value.copy(
          notifications = if (enabled) realItems else emptyList()
        )
      }
    }

    // Re-check listener enablement periodically (user can toggle it in Settings at
    // any time; there is no broadcast when it changes).
    viewModelScope.launch {
      while (true) {
        notificationListenerEnabled.value =
          DvexPermissionManager.isNotificationListenerEnabled(appContext)
        kotlinx.coroutines.delay(5_000)
      }
    }

    // --- Live HUD telemetry: real GPS weather + real device coordinates ---
    // The voice pipeline already fetched real weather; the panels were never fed.
    // Refresh on launch and then every 10 minutes while the app is open.
    viewModelScope.launch {
      val realTimeWeb = RealTimeWebService()
      val locationProvider = LocationProvider(appContext)
      while (true) {
        if (locationProvider.hasPermission() && locationProvider.areProvidersEnabled()) {
          locationProvider.getLocationResult()
            .onSuccess { fix ->
              // Show the real fix on the Geo Coordinates panel immediately.
              _uiState.value = _uiState.value.copy(
                location = LocationInfo(
                  hasData = true,
                  city = fix.cityName ?: "",
                  latitude = formatCoordinate(fix.latitude, "N", "S"),
                  longitude = formatCoordinate(fix.longitude, "E", "W"),
                  altitudeMeters = fix.altitudeMeters?.toInt(),
                  accuracyMeters = fix.accuracyMeters?.toInt()
                )
              )
              realTimeWeb.fetchLiveWeatherAt(fix.latitude, fix.longitude, fix.cityName)
                ?.let { live ->
                  _uiState.value = _uiState.value.copy(
                    weather = WeatherInfo(
                      hasData = true,
                      temperatureCelsius = live.temperatureCelsius,
                      condition = live.condition,
                      location = live.location,
                      highCelsius = live.highCelsius,
                      lowCelsius = live.lowCelsius
                    )
                  )
                }
            }
            .onFailure {
              // Leave panels in their honest STANDBY states; never fabricate data.
              Log.w(TAG, "HUD telemetry refresh failed: $it")
            }
        }
        delay(TELEMETRY_REFRESH_MS)
      }
    }
  }

  /**
   * RECENT button (Power Mode): opens the on-screen recent-apps overlay backed by
   * the REAL UsageStatsManager data. Never fabricates apps: when usage access is
   * not granted the overlay renders the honest permission-required state.
   */
  fun requestRecentAppsPanel() {
    val provider = RecentAppsProvider(appContext)
    if (!DvexPermissionManager.hasUsageAccess(appContext)) {
      _uiState.value = _uiState.value.copy(
        recentAppsOverlay = emptyList(),
        recentAppsOverlayPermissionRequired = true
      )
      return
    }
    viewModelScope.launch(Dispatchers.Default) {
      val entries = provider.getRecentApps().fold(
        onSuccess = { apps ->
          apps.map { app ->
            RecentAppEntry(
              packageName = app.packageName,
              appName = app.appName,
              lastUsedTimestampMs = app.lastTimeUsed,
              lastUsedLabel = formatRelativeTime(app.lastTimeUsed)
            )
          }
        },
        onFailure = { emptyList() }
      )
      _uiState.value = _uiState.value.copy(
        recentAppsOverlay = entries,
        recentAppsOverlayPermissionRequired = false
      )
    }
  }

  /** Closes the on-screen recent-apps overlay. */
  fun dismissRecentAppsPanel() {
    _uiState.value = _uiState.value.copy(
      recentAppsOverlay = null,
      recentAppsOverlayPermissionRequired = false
    )
  }

  /** Coarse relative time for usage timestamps ("JUST NOW", "4m ago", "2h ago"). */
  private fun formatRelativeTime(timestampMs: Long): String {
    val diffMin = (System.currentTimeMillis() - timestampMs) / 60_000
    return when {
      diffMin < 1 -> "JUST NOW"
      diffMin < 60 -> "${diffMin}m AGO"
      diffMin < 1440 -> "${diffMin / 60}H AGO"
      else -> "${diffMin / 1440}D AGO"
    }
  }

  /** Formats a signed decimal coordinate as "13.0827° N"-style text. */
  private fun formatCoordinate(value: Double, positive: String, negative: String): String {
    val hemi = if (value >= 0) positive else negative
    return "%.4f° %s".format(kotlin.math.abs(value), hemi)
  }

  private companion object {
    private const val TAG = "DvexAssistantVM"

    /** HUD weather/coordinates refresh cadence. */
    private const val TELEMETRY_REFRESH_MS = 10 * 60 * 1000L
  }

  /** Coarse relative time for the System Alerts feed ("NOW", "4m", "2h"). */
  private fun formatNotificationTime(timestampMs: Long): String {
    val diffMin = (System.currentTimeMillis() - timestampMs) / 60_000
    return when {
      diffMin < 1 -> "NOW"
      diffMin < 60 -> "${diffMin}m"
      diffMin < 1440 -> "${diffMin / 60}h"
      else -> "${diffMin / 1440}d"
    }
  }

  fun updateUiState(newState: DvexUiState) {
    _uiState.value = newState
  }

  fun updateSettings(newSettings: DvexSettings) {
    assistantRepo.updateSettings(newSettings)
    val context = getApplication<Application>()
    if (newSettings.alwaysReadyEnabled) {
      DvexAssistantService.start(context)
    } else {
      DvexAssistantService.stop(context)
    }
    if (newSettings.floatingOrbEnabled && DvexPermissionManager.hasOverlayPermission(context)) {
      com.example.overlay.DvexFloatingOrbService.start(context)
    } else {
      com.example.overlay.DvexFloatingOrbService.stop(context)
    }
  }

  fun onCenterCoreTapped() {
    val currentState = assistantRepo.assistantState.value
    when (currentState) {
      is DvexAssistantState.Listening -> {
        assistantRepo.stopListening()
      }
      is DvexAssistantState.Speaking -> {
        // Interruption: Stop speaking immediately and listen for new command
        assistantRepo.startListeningForCommand()
      }
      else -> {
        assistantRepo.startListeningForCommand()
      }
    }
  }

  fun processVoiceCommand(command: String) {
    assistantRepo.processCommand(command)
  }

  fun confirmPendingAction() {
    assistantRepo.confirmPendingAction()
  }

  fun cancelPendingAction() {
    assistantRepo.cancelPendingAction()
  }

  fun startWakeWordListening() {
    assistantRepo.startWakeWordListening()
  }

  fun stopWakeWordListening() {
    assistantRepo.wakeWordManager.stop()
  }

  // ---------------------------------------------------------------------------
  // Power Mode / Vision (surviving backend: mode/PowerModeManager + mode/VisionRequestGate)
  //
  // These are pure state toggles. Power Mode flips one persisted boolean; Vision is
  // volatile, starts OFF on every process start, and is written ONLY here and by the
  // VISION button inside PowerModeButtonCluster. Neither ever starts a camera or
  // executes a device action on its own.
  // ---------------------------------------------------------------------------

  val isPowerMode: StateFlow<Boolean> =
    PowerModeManager.getInstance(getApplication()).powerModeEnabled

  fun togglePowerMode() {
    PowerModeManager.getInstance(getApplication()).toggle()
  }

  fun setPowerMode(enabled: Boolean) {
    PowerModeManager.getInstance(getApplication()).setEnabled(enabled)
  }

  /** Returns the new state so the caller can keep its UI in sync. */
  fun setVisionActive(active: Boolean): Boolean {
    if (VisionRequestGate.isVisionRequested() != active) {
      VisionRequestGate.toggleVisionRequest()
    }
    return VisionRequestGate.isVisionRequested()
  }

  fun toggleOrb() {
    val current = settings.value.floatingOrbEnabled
    updateSettings(settings.value.copy(floatingOrbEnabled = !current))
  }

  // ---------------------------------------------------------------------------
  // Device-control conveniences (ported from origin/main).
  //
  // These deliberately reuse the EXISTING DeviceControlRepository, exactly as the
  // remote helpers did. HUD-side user actions that are conversational in nature are
  // NOT wired here: they continue to go through processVoiceCommand() so the agent
  // remains the single execution authority.
  // ---------------------------------------------------------------------------

  fun showRecents() {
    assistantRepo.deviceControl.showRecents()
  }

  fun showNotifications() {
    assistantRepo.deviceControl.showNotifications()
  }

  fun triggerDeviceControl(action: String = "home") {
    when (action.lowercase()) {
      "back" -> assistantRepo.deviceControl.navigateBack()
      "recents" -> assistantRepo.deviceControl.showRecents()
      "notifications" -> assistantRepo.deviceControl.showNotifications()
      else -> assistantRepo.deviceControl.navigateHome()
    }
  }
}

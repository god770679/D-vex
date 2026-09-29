package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.model.DvexUiState
import com.example.mode.PowerModeManager
import com.example.mode.VisionRequestGate
import com.example.overlay.DvexFloatingOrbService
import com.example.permissions.DvexPermissionManager
import com.example.service.DvexAssistantService
import com.example.ui.AssistantViewModel
import com.example.ui.DvexHud
import com.example.ui.components.AlwaysReadySettingsDialog
import com.example.ui.components.DvexBootScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private val viewModel: AssistantViewModel by viewModels()
  private val showSettingsDialogState = mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    enterFullscreenHud()
    handleLaunchIntent(intent)

    setContent {
      MyApplicationTheme {
        val uiState by viewModel.uiState.collectAsState()
        val settings by viewModel.settings.collectAsState()
        val showSettingsDialog by showSettingsDialogState

        // DUAL MODE: POWER MODE is a real application state, not a label. When it
        // is enabled, D-VEX auto-engages the EXISTING always-ready capability
        // through the EXISTING settings plumbing (updateSettings ->
        // DvexAssistantService), and ONLY when the audio permission is already
        // granted — no permission is requested or bypassed here. STANDARD MODE
        // (default) leaves every explicit user setting untouched: nothing is
        // auto-enabled and nothing the user chose is ever auto-disabled.
        val powerModeEnabled by PowerModeManager
          .getInstance(this).powerModeEnabled.collectAsState()

        // Vision is deliberately NOT persisted: it starts OFF on every process start
        // so the camera can never come back on its own. Mirrors VisionRequestGate.
        var isVisionActive by remember { mutableStateOf(VisionRequestGate.isVisionRequested()) }

        LaunchedEffect(powerModeEnabled) {
          val current = viewModel.settings.value
          if (powerModeEnabled &&
            !current.alwaysReadyEnabled &&
            DvexPermissionManager.hasAudioPermission(this@MainActivity)
          ) {
            viewModel.updateSettings(current.copy(alwaysReadyEnabled = true))
          }
        }

        // Boot sequence: progressive D-VEX INITIALIZING console shown once at
        // launch, then the main HUD takes over (reactor positioning untouched).
        var isBooting by remember { mutableStateOf(true) }

        // Contextual runtime permission launcher for Microphone (Wake-Word & Voice Commands)
        val audioPermissionLauncher = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestPermission()
        ) { isGranted ->
          if (isGranted) {
            viewModel.updateSettings(settings.copy(wakeWordEnabled = true))
          }
        }

        // Contextual runtime permission launcher for Notifications (Android 13+ Foreground Service)
        val notifPermissionLauncher = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestPermission()
        ) { _ -> }

        // Runtime permission launcher for Communication (Call, SMS, Contacts)
        val commsPermissionLauncher = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestMultiplePermissions()
        ) { _ -> }

        // Runtime permission launcher for Camera (Vision system)
        val cameraPermissionLauncher = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestPermission()
        ) { isGranted ->
          viewModel.updateUiState(
            viewModel.uiState.value.copy(
              cameraPermission = viewModel.uiState.value.cameraPermission.copy(
                granted = isGranted,
                requested = true
              )
            )
          )
        }

        LaunchedEffect(Unit) {
          if (!DvexPermissionManager.hasCameraPermission(this@MainActivity)) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
          } else {
            viewModel.updateUiState(
              viewModel.uiState.value.copy(
                cameraPermission = viewModel.uiState.value.cameraPermission.copy(
                  granted = true,
                  requested = true
                )
              )
            )
          }
        }

        LaunchedEffect(Unit) {
          val needed = mutableListOf<String>()
          if (!DvexPermissionManager.hasContactsPermission(this@MainActivity)) {
            needed.add(Manifest.permission.READ_CONTACTS)
          }
          if (!DvexPermissionManager.hasCallPhonePermission(this@MainActivity)) {
            needed.add(Manifest.permission.CALL_PHONE)
          }
          if (!DvexPermissionManager.hasSmsPermission(this@MainActivity)) {
            needed.add(Manifest.permission.SEND_SMS)
          }
          // Real device location for weather (Bug 4): fine preferred, coarse accepted.
          if (!DvexPermissionManager.hasLocationPermission(this@MainActivity)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
          }
          if (needed.isNotEmpty()) {
            commsPermissionLauncher.launch(needed.toTypedArray())
          }
        }

        if (!isBooting) {
          DvexHud(
            uiState = uiState,
            onUiStateChange = { newState -> viewModel.updateUiState(newState) },
          onSendCommand = { command -> viewModel.processVoiceCommand(command) },
          onCenterCoreTapped = {
            if (!DvexPermissionManager.hasAudioPermission(this)) {
              audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
              viewModel.onCenterCoreTapped()
            }
          },
          onMicrophoneTapped = {
            if (!DvexPermissionManager.hasAudioPermission(this)) {
              audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
              viewModel.onCenterCoreTapped()
            }
          },
          onQuickActionSelected = { action ->
            when (action) {
              "Settings" -> showSettingsDialogState.value = true
              "Camera" -> viewModel.processVoiceCommand("camera")
              "Music" -> viewModel.processVoiceCommand("play music")
              "YouTube" -> viewModel.processVoiceCommand("open youtube")
              "Maps" -> viewModel.processVoiceCommand("open maps")
              "Call" -> viewModel.processVoiceCommand("call phone")
              "Messages" -> viewModel.processVoiceCommand("open messages")
              "More" -> showSettingsDialogState.value = true
              else -> viewModel.processVoiceCommand(action)
            }
          },
          onOpenSettingsRequested = {
            showSettingsDialogState.value = true
          },
          // DUAL MODE: Power Mode ORB button reuses the EXISTING settings plumbing
          // (updateSettings already starts/stops DvexFloatingOrbService based on
          // floatingOrbEnabled). The service itself is not modified.
          onOrbToggleRequested = { enable ->
            viewModel.updateSettings(settings.copy(floatingOrbEnabled = enable))
          },
          // Regression audit: RECENT button opens the on-screen overlay with REAL
          // usage-stats data (or the honest permission-required state).
          onRecentAppsRequested = { viewModel.requestRecentAppsPanel() },
          onDismissRecentApps = { viewModel.dismissRecentAppsPanel() },
          // VISION: explicit user opt-in only. This mirrors the existing cluster
          // behaviour (the gate is also flipped by the VISION button inside the panel)
          // so the HUD label and the gate can never disagree. Neither Power Mode
          // activation nor startup ever sets this.
          isVisionActive = isVisionActive,
          onVisionToggleRequested = { enabled ->
            viewModel.setVisionActive(enabled)
            isVisionActive = enabled
          },
          modifier = Modifier.fillMaxSize()
          )
        }

        if (isBooting) {
          DvexBootScreen(
            onBootComplete = { isBooting = false },
            modifier = Modifier.fillMaxSize()
          )
        }

        if (showSettingsDialog) {
          AlwaysReadySettingsDialog(
            settings = settings,
            onSettingsChanged = { newSettings -> viewModel.updateSettings(newSettings) },
            onRequestAudioPermission = {
              audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRequestNotificationPermission = {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
              }
            },
            onDismiss = { showSettingsDialogState.value = false }
          )
        }
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleLaunchIntent(intent)
  }

  /**
   * True fullscreen HUD mode: hides the system status bar entirely so the OS
   * clock/battery/cutout can never overlap D-VEX's own header. Transient bars
   * (swipe from edge) show briefly over the HUD and hide themselves again.
   * WindowInsetsControllerCompat works edge-to-edge on all supported API levels.
   */
  private fun enterFullscreenHud() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.hide(WindowInsetsCompat.Type.statusBars())
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    // Re-hide the status bar whenever focus returns (dialogs, permission
    // prompts, or another app can make the transient bars sticky).
    if (hasFocus) {
      enterFullscreenHud()
    }
  }

  private fun handleLaunchIntent(intent: android.content.Intent?) {
    if (intent == null) return
    if (intent.getBooleanExtra(DvexFloatingOrbService.EXTRA_OPEN_SETTINGS, false)) {
      showSettingsDialogState.value = true
    }
    if (intent.getBooleanExtra(DvexFloatingOrbService.EXTRA_START_LISTENING, false)) {
      if (DvexPermissionManager.hasAudioPermission(this)) {
        viewModel.onCenterCoreTapped()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    val currentSettings = viewModel.settings.value
    // Start microphone foreground service only from an allowed visible foreground state
    if (currentSettings.alwaysReadyEnabled && DvexPermissionManager.hasAudioPermission(this)) {
      DvexAssistantService.start(this)
    }
  }

  override fun onStart() {
    super.onStart()
    val currentSettings = viewModel.settings.value

    // Start passive wake-word listening in foreground if enabled and audio permission is granted
    if (currentSettings.wakeWordEnabled && DvexPermissionManager.hasAudioPermission(this)) {
      viewModel.startWakeWordListening()
    }

    // Ensure system-wide floating overlay is running if authorized
    if (currentSettings.floatingOrbEnabled && DvexPermissionManager.hasOverlayPermission(this)) {
      DvexFloatingOrbService.start(this)
    }
  }

  override fun onStop() {
    super.onStop()
    // When main D-VEX app is minimized or sent to background, ensure the floating orb overlay stays active
    val currentSettings = viewModel.settings.value
    if (currentSettings.floatingOrbEnabled && DvexPermissionManager.hasOverlayPermission(this)) {
      DvexFloatingOrbService.start(this)
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  androidx.compose.material3.Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  MyApplicationTheme { Greeting("Android") }
}



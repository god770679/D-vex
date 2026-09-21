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
import com.example.model.DvexUiState
import com.example.overlay.DvexFloatingOrbService
import com.example.permissions.DvexPermissionManager
import com.example.service.DvexAssistantService
import com.example.ui.AssistantViewModel
import com.example.ui.DvexHud
import com.example.ui.components.AlwaysReadySettingsDialog
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private val viewModel: AssistantViewModel by viewModels()
  private val showSettingsDialogState = mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleLaunchIntent(intent)

    setContent {
      MyApplicationTheme {
        val uiState by viewModel.uiState.collectAsState()
        val settings by viewModel.settings.collectAsState()
        val showSettingsDialog by showSettingsDialogState

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
          if (needed.isNotEmpty()) {
            commsPermissionLauncher.launch(needed.toTypedArray())
          }
        }

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
          modifier = Modifier.fillMaxSize()
        )

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



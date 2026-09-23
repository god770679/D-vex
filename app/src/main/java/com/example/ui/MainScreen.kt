package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.model.DvexUiState

/**
 * MainScreen Composable representing the primary tactical HUD with the command chat input box.
 */
@Composable
fun MainScreen(
  uiState: DvexUiState,
  onUiStateChange: (DvexUiState) -> Unit,
  modifier: Modifier = Modifier,
  onSendCommand: ((String) -> Unit)? = null,
  onCenterCoreTapped: (() -> Unit)? = null,
  onMicrophoneTapped: (() -> Unit)? = null,
  onQuickActionSelected: ((String) -> Unit)? = null,
  onOpenSettingsRequested: (() -> Unit)? = null,
  isPowerMode: Boolean = false,
  onTogglePowerMode: () -> Unit = {},
  isOrbActive: Boolean = false,
  isVisionActive: Boolean = false,
  onToggleOrb: () -> Unit = {},
  onRecentApp: () -> Unit = {},
  onNotificationAlert: () -> Unit = {},
  onDeviceControl: () -> Unit = {},
  onToggleVision: () -> Unit = {}
) {
  DvexHud(
    uiState = uiState,
    onUiStateChange = onUiStateChange,
    modifier = modifier,
    onSendCommand = onSendCommand,
    onCenterCoreTapped = onCenterCoreTapped,
    onMicrophoneTapped = onMicrophoneTapped,
    onQuickActionSelected = onQuickActionSelected,
    onOpenSettingsRequested = onOpenSettingsRequested,
    isPowerMode = isPowerMode,
    onTogglePowerMode = onTogglePowerMode,
    isOrbActive = isOrbActive,
    isVisionActive = isVisionActive,
    onToggleOrb = onToggleOrb,
    onRecentApp = onRecentApp,
    onNotificationAlert = onNotificationAlert,
    onDeviceControl = onDeviceControl,
    onToggleVision = onToggleVision
  )
}

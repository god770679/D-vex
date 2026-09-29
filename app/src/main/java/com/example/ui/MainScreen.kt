package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.model.DvexUiState

/**
 * MainScreen Composable representing the primary tactical HUD with the command chat input box.
 *
 * Thin pass-through to [DvexHud]. It deliberately exposes ONLY the parameters the
 * surviving HUD actually accepts: the remote 9-parameter API (isPowerMode /
 * onTogglePowerMode / isOrbActive / onToggleOrb / onRecentApp / onNotificationAlert /
 * onDeviceControl / onToggleVision) was never a merge conflict, but forwarding it
 * here would not compile against the current DvexHud. Power Mode, orb, recents and
 * device controls are reached through the existing panel-popup layer instead.
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
  isVisionActive: Boolean = false,
  onVisionToggleRequested: ((Boolean) -> Unit)? = null
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
    isVisionActive = isVisionActive,
    onVisionToggleRequested = onVisionToggleRequested
  )
}

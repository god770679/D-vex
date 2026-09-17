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
  onOpenSettingsRequested: (() -> Unit)? = null
) {
  DvexHud(
    uiState = uiState,
    onUiStateChange = onUiStateChange,
    modifier = modifier,
    onSendCommand = onSendCommand,
    onCenterCoreTapped = onCenterCoreTapped,
    onMicrophoneTapped = onMicrophoneTapped,
    onQuickActionSelected = onQuickActionSelected,
    onOpenSettingsRequested = onOpenSettingsRequested
  )
}

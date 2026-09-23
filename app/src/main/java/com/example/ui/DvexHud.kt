package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AiCoreState
import com.example.model.DvexUiState
import com.example.model.NavItem
import com.example.model.VoiceState
import com.example.ui.components.CameraVisionPanel
import com.example.ui.components.DvexAiCore
import com.example.ui.components.DvexLeftSidebar
import com.example.ui.components.DvexRightSidebar
import com.example.ui.components.DvexTopBar
import com.example.ui.components.LocationPanel
import com.example.ui.components.MemoryCorePanel
import com.example.ui.components.MicrophoneButton
import com.example.ui.components.NotificationPanel
import com.example.ui.components.PowerModeButtonCluster
import com.example.ui.components.QuickAccessPanel
import com.example.ui.components.RecentActivityPanel
import com.example.ui.components.ResponsePanel
import com.example.ui.components.SystemStatusPanel
import com.example.ui.components.TimePanel
import com.example.ui.components.VoiceModule
import com.example.ui.components.WeatherPanel
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary

private enum class MobileHudTab(val label: String) {
  ALL("ALL PANELS"),
  VITALS("VITALS"),
  ACCESS("QUICK"),
  LOGS("ALERTS")
}

/**
 * Main D-VEX Tactical HUD Screen.
 * Automatically adapts between wide desktop/tablet and compact phone form factors.
 */
@Composable
fun DvexHud(
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
  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(DvexBlack)
  ) {
    val isWidescreen = maxWidth >= 840.dp

    if (isWidescreen) {
      WidescreenTacticalHud(
        uiState = uiState,
        onUiStateChange = onUiStateChange,
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
    } else {
      CompactMobileTacticalHud(
        uiState = uiState,
        onUiStateChange = onUiStateChange,
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
  }
}

/**
 * Complete Full Multi-Column HUD Layout for Desktop / Tablet / Foldables
 */
@Composable
private fun WidescreenTacticalHud(
  uiState: DvexUiState,
  onUiStateChange: (DvexUiState) -> Unit,
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
  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.navigationBars)
  ) {
    // TOP BAR
    DvexTopBar(
      uiState = uiState,
      isCompact = false,
      isPowerMode = isPowerMode,
      onTogglePowerMode = onTogglePowerMode
    )

    // MAIN CONTENT ROW
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(horizontal = 6.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // 1. LEFT SIDEBAR
      DvexLeftSidebar(
        selectedItem = uiState.selectedNavigation,
        onItemSelected = { nav ->
          if (nav == NavItem.SETTINGS) {
            onOpenSettingsRequested?.invoke()
          }
          onUiStateChange(uiState.copy(selectedNavigation = nav))
        }
      )

      Spacer(modifier = Modifier.width(6.dp))

      // 2. LEFT PANELS (Scrollable Column)
      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        WeatherPanel(weather = uiState.weather)
        NotificationPanel(notifications = uiState.notifications)
        SystemStatusPanel(vitals = uiState.systemStatus)
        MemoryCorePanel(percentage = uiState.memoryPercentage)
        CameraVisionPanel(isVisionActive = isVisionActive)
      }

      Spacer(modifier = Modifier.width(8.dp))

      // 3. CENTER D-VEX AI CORE (Visual Focus)
      Box(
        modifier = Modifier
          .weight(1.35f)
          .fillMaxHeight(),
        contentAlignment = Alignment.Center
      ) {
        DvexAiCore(
          aiState = uiState.aiState,
          onStateChangeRequest = { newState ->
            if (onCenterCoreTapped != null) {
              onCenterCoreTapped()
            } else {
              val newVoiceState = when (newState) {
                AiCoreState.LISTENING -> VoiceState.LISTENING
                AiCoreState.PROCESSING -> VoiceState.PROCESSING
                AiCoreState.RESPONDING -> VoiceState.SPEAKING
                else -> VoiceState.IDLE
              }
              onUiStateChange(uiState.copy(aiState = newState, voiceState = newVoiceState))
            }
          }
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      // 4. RIGHT PANELS (Scrollable Column)
      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        TimePanel()
        LocationPanel(location = uiState.location)
        PowerModeButtonCluster(
          isPowerMode = isPowerMode,
          isOrbActive = isOrbActive,
          isVisionActive = isVisionActive,
          onToggleOrb = onToggleOrb,
          onRecentApp = onRecentApp,
          onNotificationAlert = onNotificationAlert,
          onDeviceControl = onDeviceControl,
          onToggleVision = onToggleVision
        )
        QuickAccessPanel(
          onActionSelected = { action ->
            if (action == "Settings" || action == "More") {
              onOpenSettingsRequested?.invoke()
            }
            if (onQuickActionSelected != null) {
              onQuickActionSelected(action)
            } else {
              onUiStateChange(
                uiState.copy(
                  responseText = "Quick access initiated for $action. Standby.",
                  aiState = AiCoreState.PROCESSING
                )
              )
            }
          }
        )
        RecentActivityPanel(activities = uiState.recentActivities)
      }

      Spacer(modifier = Modifier.width(6.dp))

      // 5. RIGHT SIDEBAR
      DvexRightSidebar(
        selectedItem = uiState.selectedNavigation,
        onItemSelected = { nav ->
          if (nav == NavItem.SETTINGS) {
            onOpenSettingsRequested?.invoke()
          }
          onUiStateChange(uiState.copy(selectedNavigation = nav))
        }
      )
    }

    // BOTTOM BAR: Voice, Chat Input & Responses
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(DvexSurfaceDark)
        .border(1.dp, DvexBorderMuted, CutCornerShape(topStart = 8.dp, topEnd = 8.dp))
        .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
      TacticalCommandInput(
        onSendCommand = { cmd -> onSendCommand?.invoke(cmd) },
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(modifier = Modifier.height(6.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        VoiceModule(
          voiceState = uiState.voiceState,
          modifier = Modifier.weight(1f)
        )

        MicrophoneButton(
          isListening = uiState.voiceState == VoiceState.LISTENING,
          onClick = {
            if (onMicrophoneTapped != null) {
              onMicrophoneTapped()
            } else {
              val nextVoice = if (uiState.voiceState == VoiceState.LISTENING) VoiceState.IDLE else VoiceState.LISTENING
              val nextAi = if (nextVoice == VoiceState.LISTENING) AiCoreState.LISTENING else AiCoreState.IDLE
              onUiStateChange(
                uiState.copy(
                  voiceState = nextVoice,
                  aiState = nextAi,
                  responseText = if (nextVoice == VoiceState.LISTENING) "Listening for command..." else "Standing by."
                )
              )
            }
          },
          modifier = Modifier.padding(horizontal = 16.dp)
        )

        ResponsePanel(
          responseText = uiState.responseText,
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

/**
 * Optimized Mobile Portrait Layout for Phones
 * Prioritizes: Central AI Core, Voice Controls, Response Panel, with Tactical Switcher for Panels
 */
@Composable
private fun CompactMobileTacticalHud(
  uiState: DvexUiState,
  onUiStateChange: (DvexUiState) -> Unit,
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
  var activeMobileTab by remember { mutableStateOf(MobileHudTab.ALL) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.navigationBars)
  ) {
    // TOP BAR: D-VEX + System status (Compact version)
    DvexTopBar(
      uiState = uiState,
      isCompact = true,
      isPowerMode = isPowerMode,
      onTogglePowerMode = onTogglePowerMode
    )

    // MAIN SCROLLABLE CONTENT BODY
    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(horizontal = 6.dp, vertical = 4.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // 1. ALWAYS-PROMINENT LARGE D-VEX AI CORE REACTOR
      DvexAiCore(
        aiState = uiState.aiState,
        onStateChangeRequest = { newState ->
          if (onCenterCoreTapped != null) {
            onCenterCoreTapped()
          } else {
            val newVoiceState = when (newState) {
              AiCoreState.LISTENING -> VoiceState.LISTENING
              AiCoreState.PROCESSING -> VoiceState.PROCESSING
              AiCoreState.RESPONDING -> VoiceState.SPEAKING
              else -> VoiceState.IDLE
            }
            onUiStateChange(uiState.copy(aiState = newState, voiceState = newVoiceState))
          }
        },
        modifier = Modifier.padding(vertical = 4.dp)
      )

      // 2. MOBILE TACTICAL TAB SELECTOR (2-Column & Category View)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(DvexSurfaceDark)
          .border(1.dp, DvexBorderMuted, CutCornerShape(4.dp))
          .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceAround
      ) {
        MobileHudTab.values().forEach { tab ->
          val isSelected = tab == activeMobileTab
          Box(
            modifier = Modifier
              .clip(CutCornerShape(3.dp))
              .background(if (isSelected) DvexNeonRed else DvexSurfaceCard)
              .border(1.dp, if (isSelected) DvexNeonRedBright else DvexBorderMuted, CutCornerShape(3.dp))
              .clickable { activeMobileTab = tab }
              .padding(horizontal = 6.dp, vertical = 3.dp)
          ) {
            Text(
              text = "[ ${tab.label} ]",
              fontFamily = FontFamily.Monospace,
              fontSize = 8.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              color = if (isSelected) Color.White else DvexTextSecondary
            )
          }
        }
      }

      // 3. COMPACT 2-COLUMN TACTICAL INFORMATION PANELS
      when (activeMobileTab) {
        MobileHudTab.ALL -> {
          // Row 1: Weather + Memory Core
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            WeatherPanel(weather = uiState.weather, modifier = Modifier.weight(1f))
            MemoryCorePanel(percentage = uiState.memoryPercentage, modifier = Modifier.weight(1f))
          }

          // Row 2: System Status + Camera Vision
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            SystemStatusPanel(vitals = uiState.systemStatus, modifier = Modifier.weight(1f))
            CameraVisionPanel(isVisionActive = isVisionActive, modifier = Modifier.weight(1f))
          }

          // Row 3: Time + Location
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            TimePanel(modifier = Modifier.weight(1f))
            LocationPanel(location = uiState.location, modifier = Modifier.weight(1f))
          }

          // Power Mode Core Matrix Panel
          PowerModeButtonCluster(
            isPowerMode = isPowerMode,
            isOrbActive = isOrbActive,
            isVisionActive = isVisionActive,
            onToggleOrb = onToggleOrb,
            onRecentApp = onRecentApp,
            onNotificationAlert = onNotificationAlert,
            onDeviceControl = onDeviceControl,
            onToggleVision = onToggleVision
          )

          // Quick Access Grid (8 compact tactical buttons)
          QuickAccessPanel(
            onActionSelected = { action ->
              if (action == "Settings" || action == "More") {
                onOpenSettingsRequested?.invoke()
              }
              if (onQuickActionSelected != null) {
                onQuickActionSelected(action)
              } else {
                onUiStateChange(
                  uiState.copy(
                    responseText = "Quick access: $action triggered in HUD.",
                    aiState = AiCoreState.PROCESSING
                  )
                )
              }
            }
          )

          // Row 4: Notifications + Recent Activity
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            NotificationPanel(notifications = uiState.notifications, modifier = Modifier.weight(1f))
            RecentActivityPanel(activities = uiState.recentActivities, modifier = Modifier.weight(1f))
          }
        }

        MobileHudTab.VITALS -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            SystemStatusPanel(vitals = uiState.systemStatus, modifier = Modifier.weight(1f))
            MemoryCorePanel(percentage = uiState.memoryPercentage, modifier = Modifier.weight(1f))
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            WeatherPanel(weather = uiState.weather, modifier = Modifier.weight(1f))
            CameraVisionPanel(isVisionActive = isVisionActive, modifier = Modifier.weight(1f))
          }
        }

        MobileHudTab.ACCESS -> {
          // Power Mode Core Matrix Panel
          PowerModeButtonCluster(
            isPowerMode = isPowerMode,
            isOrbActive = isOrbActive,
            isVisionActive = isVisionActive,
            onToggleOrb = onToggleOrb,
            onRecentApp = onRecentApp,
            onNotificationAlert = onNotificationAlert,
            onDeviceControl = onDeviceControl,
            onToggleVision = onToggleVision
          )

          QuickAccessPanel(
            onActionSelected = { action ->
              if (action == "Settings" || action == "More") {
                onOpenSettingsRequested?.invoke()
              }
              if (onQuickActionSelected != null) {
                onQuickActionSelected(action)
              } else {
                onUiStateChange(
                  uiState.copy(
                    responseText = "Quick access: $action triggered in HUD.",
                    aiState = AiCoreState.PROCESSING
                  )
                )
              }
            }
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            TimePanel(modifier = Modifier.weight(1f))
            LocationPanel(location = uiState.location, modifier = Modifier.weight(1f))
          }
        }

        MobileHudTab.LOGS -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            NotificationPanel(notifications = uiState.notifications, modifier = Modifier.weight(1f))
            RecentActivityPanel(activities = uiState.recentActivities, modifier = Modifier.weight(1f))
          }
        }
      }
    }

    // DOCKED BOTTOM CONTROLS
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(DvexSurfaceDark)
        .border(1.dp, DvexBorderMuted, CutCornerShape(topStart = 8.dp, topEnd = 8.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
      // Dynamic response bubble
      ResponsePanel(
        responseText = uiState.responseText,
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(modifier = Modifier.height(6.dp))

      // Tactical Chat Command Input Box ("Enter tactical command...")
      TacticalCommandInput(
        onSendCommand = { cmd -> onSendCommand?.invoke(cmd) },
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(modifier = Modifier.height(6.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        VoiceModule(
          voiceState = uiState.voiceState,
          modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(10.dp))

        MicrophoneButton(
          isListening = uiState.voiceState == VoiceState.LISTENING,
          onClick = {
            if (onMicrophoneTapped != null) {
              onMicrophoneTapped()
            } else {
              val nextVoice = if (uiState.voiceState == VoiceState.LISTENING) VoiceState.IDLE else VoiceState.LISTENING
              val nextAi = if (nextVoice == VoiceState.LISTENING) AiCoreState.LISTENING else AiCoreState.IDLE
              onUiStateChange(
                uiState.copy(
                  voiceState = nextVoice,
                  aiState = nextAi,
                  responseText = if (nextVoice == VoiceState.LISTENING) "Listening for command..." else "Standing by."
                )
              )
            }
          }
        )
      }
    }
  }
}

/**
 * Tactical Command Text Input with Send Button.
 * Provides a reliable text-based interface to dispatch commands directly to D-VEX.
 */
@Composable
fun TacticalCommandInput(
  onSendCommand: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  var commandText by remember { mutableStateOf("") }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  Row(
    modifier = modifier
      .background(DvexSurfaceDark, CutCornerShape(4.dp))
      .border(1.dp, DvexBorderMuted, CutCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    BasicTextField(
      value = commandText,
      onValueChange = { commandText = it },
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 6.dp, vertical = 6.dp),
      textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = DvexTextPrimary
      ),
      cursorBrush = SolidColor(DvexNeonRedBright),
      singleLine = true,
      keyboardOptions = KeyboardOptions(
        imeAction = ImeAction.Send,
        keyboardType = KeyboardType.Text
      ),
      keyboardActions = KeyboardActions(
        onSend = {
          if (commandText.isNotBlank()) {
            val cmd = commandText.trim()
            commandText = ""
            keyboardController?.hide()
            focusManager.clearFocus()
            onSendCommand(cmd)
          }
        }
      ),
      decorationBox = { innerTextField ->
        Box(contentAlignment = Alignment.CenterStart) {
          if (commandText.isEmpty()) {
            Text(
              text = "Enter tactical command...",
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = DvexTextMuted
            )
          }
          innerTextField()
        }
      }
    )

    Spacer(modifier = Modifier.width(6.dp))

    val isSendEnabled = commandText.isNotBlank()
    Box(
      modifier = Modifier
        .clip(CutCornerShape(3.dp))
        .background(if (isSendEnabled) DvexNeonRed else DvexSurfaceCard)
        .border(
          1.dp,
          if (isSendEnabled) DvexNeonRedBright else DvexBorderMuted,
          CutCornerShape(3.dp)
        )
        .clickable(enabled = isSendEnabled) {
          if (commandText.isNotBlank()) {
            val cmd = commandText.trim()
            commandText = ""
            keyboardController?.hide()
            focusManager.clearFocus()
            onSendCommand(cmd)
          }
        }
        .padding(horizontal = 10.dp, vertical = 6.dp),
      contentAlignment = Alignment.Center
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.Send,
          contentDescription = "Send tactical command",
          tint = if (isSendEnabled) Color.White else DvexTextMuted,
          modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = "SEND",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
          color = if (isSendEnabled) Color.White else DvexTextMuted
        )
      }
    }
  }
}


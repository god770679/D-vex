package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.agent.AgentState
import com.example.agent.DvexAgentStateController
import com.example.model.AgentActivityController
import com.example.model.AgentCapability
import com.example.model.AiCoreState
import com.example.model.DvexUiState
import com.example.model.NavItem
import com.example.model.VoiceState
import com.example.mode.PowerModeManager
import com.example.repository.AssistantRepository
import com.example.ui.components.AgentAccessPopupHost
import com.example.ui.components.DvexAiCore
import com.example.ui.components.DvexConversationCard
import com.example.ui.components.conversationCardTag
import com.example.ui.components.DvexLeftSidebar
import com.example.ui.components.DvexModeBadge
import com.example.ui.components.DvexPanel
import com.example.ui.components.DvexPanelLauncherButton
import com.example.ui.components.DvexPanelPopupContent
import com.example.ui.components.DvexPanelPopupHost
import com.example.ui.components.DvexRightSidebar
import com.example.ui.components.DvexTopBar
import com.example.ui.components.MicrophoneButton
import com.example.ui.components.RecentAppsOverlay
import com.example.ui.components.TacticalCommandDock
import com.example.ui.components.VoiceWaveBox
import com.example.ui.theme.DvexBlack

/**
 * The compact voice box mirrors the REAL agent phase. It never invents a
 * listening/executing state: when the agent pipeline is idle the existing voice
 * state is shown unchanged.
 */
private fun voiceStateForVoiceBox(voiceState: VoiceState, agentState: AgentState): VoiceState =
  when (agentState) {
    AgentState.UNDERSTANDING, AgentState.PLANNING,
    AgentState.EXECUTING, AgentState.VERIFYING -> VoiceState.EXECUTING_ACTION
    AgentState.WAITING_FOR_PERMISSION -> VoiceState.PROCESSING
    AgentState.RESPONDING -> VoiceState.SPEAKING
    AgentState.ERROR -> VoiceState.ERROR
    AgentState.LISTENING -> VoiceState.LISTENING
    AgentState.IDLE -> voiceState
  }

/**
 * Single source of truth for which panel popup is open (NONE = no popup).
 * Selecting a launcher button opens that panel; selecting another button swaps
 * the popup; tapping the active button again closes it. Both form factors share
 * this state via [rememberPanelState].
 */
private enum class DvexActivePanel { NONE, CAMERA, ACTIVITY, AI, TOOLS, SYSTEM, WEATHER, ALERTS, MEMORY, QUICK, TIME, MODE }

/**
 * Height reserved at the bottom of every scrollable content area for the floating
 * control band (input cluster on the left, response card on the right), so the two
 * overlays never sit on top of navigation or launcher controls.
 */
private val HUD_CONTROL_BAND_HEIGHT = 100.dp

/** Ordered launcher buttons: one organized rail — never scattered, never covering the reactor. */
private val hudPanelButtons = listOf(
  DvexPanel.CAMERA,
  DvexPanel.ACTIVITY,
  DvexPanel.AI,
  DvexPanel.TOOLS,
  DvexPanel.SYSTEM,
  DvexPanel.WEATHER,
  DvexPanel.ALERTS,
  DvexPanel.MEMORY,
  DvexPanel.QUICK,
  DvexPanel.TIME,
  DvexPanel.MODE
)

/** Shared popup-state machine for both form factors (rule set from the panel spec). */
@Composable
private fun rememberPanelState(): Pair<DvexActivePanel, (DvexActivePanel) -> Unit> {
  var activePanel by remember { mutableStateOf(DvexActivePanel.NONE) }
  val select: (DvexActivePanel) -> Unit = { requested ->
    activePanel = if (activePanel == requested) DvexActivePanel.NONE else requested
  }
  return activePanel to select
}

/**
 * Main D-VEX Tactical HUD Screen.
 * Automatically adapts between wide desktop/tablet and compact phone form factors.
 * Panel popups overlay the layout (they never reflow it), so the central reactor
 * stays mathematically centered in the full viewport in both form factors.
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
  onOrbToggleRequested: ((Boolean) -> Unit)? = null,
  onRecentAppsRequested: (() -> Unit)? = null,
  onDismissRecentApps: (() -> Unit)? = null,
  /**
   * Explicit vision-request state (Power Mode VISION control). Defaulted to false:
   * the camera is never requested by Power Mode activation, startup, or boot.
   */
  isVisionActive: Boolean = false,
  /** Optional host callback for the VISION control; never required for the panel to work. */
  onVisionToggleRequested: ((Boolean) -> Unit)? = null
) {
  val showRecentAppsOverlay =
    uiState.recentAppsOverlay != null || uiState.recentAppsOverlayPermissionRequired

  // Contextual agent-activity feed. Genuinely wired to existing UI-state signals —
  // no demo data. The future action layer publishes here via
  // AgentActivityController (begin → update → complete), exactly matching the
  // popup states defined for app/device actions.
  val agentActivities by AgentActivityController.activities.collectAsState()
  val agentState by DvexAgentStateController.state.collectAsState()

  LaunchedEffect(uiState.cameraPermission.requested, uiState.cameraPermission.granted) {
    if (uiState.cameraPermission.requested && !uiState.cameraPermission.granted) {
      AgentActivityController.requireAccess(AgentCapability.CAMERA)
    }
  }

  LaunchedEffect(uiState.recentAppsOverlayPermissionRequired) {
    if (uiState.recentAppsOverlayPermissionRequired) {
      AgentActivityController.requireAccess(AgentCapability.RECENT_APPS)
    }
  }

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
        onOrbToggleRequested = onOrbToggleRequested,
        onRecentAppsRequested = onRecentAppsRequested,
        onDismissRecentApps = onDismissRecentApps,
        isVisionActive = isVisionActive,
        onVisionToggleRequested = onVisionToggleRequested
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
        onOrbToggleRequested = onOrbToggleRequested,
        onRecentAppsRequested = onRecentAppsRequested,
        onDismissRecentApps = onDismissRecentApps,
        isVisionActive = isVisionActive,
        onVisionToggleRequested = onVisionToggleRequested
      )
    }

    // ---------------------------------------------------------------------
    // SHARED FLOATING CONTROL BAND (both form factors)
    //
    // BOTTOM-LEFT  : ONE input cluster — tactical text dock + voice button +
    //                live waveform. Typing and speaking are the same entry point.
    // BOTTOM-RIGHT : compact conversation card — latest user input + final
    //                D-VEX response (the exact string sent to TTS).
    //
    // Both are corner-anchored, adaptive and wrap-content: nothing spans the
    // screen, nothing is full-height, and the centred reactor stays fully visible.
    // Insets keep them clear of Android system UI.
    // ---------------------------------------------------------------------
    val isNarrowBand = maxWidth < 720.dp
    val inputWidth = if (isNarrowBand) maxWidth * 0.40f else 248.dp
    val conversationCardWidth = if (isNarrowBand) maxWidth * 0.48f else 330.dp
    val conversationCardMaxHeight = if (isNarrowBand) 120.dp else 208.dp

    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
      AgentAccessPopupHost(
        activities = agentActivities,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .windowInsetsPadding(WindowInsets.statusBars)
          .padding(top = 8.dp, end = 10.dp)
      )

      // BOTTOM-RIGHT first, so the input cluster (composed above) always wins the
      // overlap when the text field is deliberately expanded on a small screen.
      DvexConversationCard(
        userInput = uiState.lastUserInput,
        responseText = uiState.responseText,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 12.dp, bottom = 10.dp)
          .width(conversationCardWidth)
          .heightIn(max = conversationCardMaxHeight),
          // Real session state: LIVE only while a turn is genuinely in flight.
          headerTag = conversationCardTag(uiState.voiceState)
      )

      Row(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(start = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // TEXT INPUT: collapsed to one small button until needed, then opens into
        // the existing command field. Feeds AssistantRepository.processCommand.
        TacticalCommandDock(
          onSendCommand = { cmd -> onSendCommand?.invoke(cmd) },
          expandedWidth = inputWidth
        )

        // VOICE INPUT: the same brain, the same pipeline.
        MicrophoneButton(
          isListening = uiState.voiceState == VoiceState.LISTENING,
          onClick = {
            if (onMicrophoneTapped != null) {
              onMicrophoneTapped()
            } else {
              val nextVoice =
                if (uiState.voiceState == VoiceState.LISTENING) VoiceState.IDLE else VoiceState.LISTENING
              val nextAi = if (nextVoice == VoiceState.LISTENING) AiCoreState.LISTENING else AiCoreState.IDLE
              // Only the live phase changes here: the response card keeps showing
              // the last REAL reply instead of a hardcoded status sentence.
              onUiStateChange(
                uiState.copy(voiceState = nextVoice, aiState = nextAi)
              )
            }
          }
        )

        // Live waveform — appears only while the voice pipeline is actually doing
        // something, and belongs to the input cluster.
        VoiceWaveBox(
          voiceState = voiceStateForVoiceBox(uiState.voiceState, agentState),
          modifier = Modifier.align(Alignment.CenterVertically)
        )
      }
    }

    // DUAL MODE: on-screen RECENT APPS overlay — real usage-stats data (or the
    // honest permission state) rendered above the HUD; never placeholder apps.
    if (showRecentAppsOverlay) {
      RecentAppsOverlay(
        permissionRequired = uiState.recentAppsOverlayPermissionRequired,
        entries = uiState.recentAppsOverlay ?: emptyList(),
        onDismiss = { onDismissRecentApps?.invoke() }
      )
    }
  }
}

/**
 * Popup-layer wiring shared by both form factors: BACK closes the popup before
 * leaving the main HUD, and the selected panel's EXISTING composable is rendered
 * inside the single glass popup host.
 */
@Composable
private fun HudPanelPopupLayer(
  activePanel: DvexActivePanel,
  onSelect: (DvexActivePanel) -> Unit,
  uiState: DvexUiState,
  powerModeEnabled: Boolean,
  settings: com.example.model.DvexSettings,
  onSelectStandard: () -> Unit,
  onSelectPower: () -> Unit,
  onSendCommand: (String) -> Unit,
  onOrbToggleRequested: (Boolean) -> Unit,
  onQuickActionSelected: (String) -> Unit,
  onRecentAppsRequested: () -> Unit,
  isVisionActive: Boolean = false,
  onVisionToggleRequested: ((Boolean) -> Unit)? = null
) {
  // BACK press closes the popup first; only when no popup is open does BACK
  // propagate (default activity behaviour).
  BackHandler(enabled = activePanel != DvexActivePanel.NONE) {
    onSelect(DvexActivePanel.NONE)
  }

  DvexPanelPopupHost(
    activePanel = activePanel.toPopupPanel(),
    onDismiss = { onSelect(DvexActivePanel.NONE) }
  ) {
    DvexPanelPopupContent(
      activePanel = activePanel.toPopupPanel(),
      weatherData = uiState.weather,
      notifications = uiState.notifications,
      systemStatus = uiState.systemStatus,
      memoryPercentage = uiState.memoryPercentage,
      recentActivities = uiState.recentActivities,
      voiceState = uiState.voiceState,
      powerModeEnabled = powerModeEnabled,
      settings = settings,
      onSendCommand = onSendCommand,
      onOrbToggleRequested = onOrbToggleRequested,
      onQuickActionSelected = onQuickActionSelected,
      onRecentAppsRequested = onRecentAppsRequested,
      onSelectStandard = onSelectStandard,
      onSelectPower = onSelectPower,
      isVisionActive = isVisionActive,
      onVisionToggleRequested = onVisionToggleRequested
    )
  }
}

private fun DvexActivePanel.toPopupPanel(): DvexPanel = when (this) {
  DvexActivePanel.NONE -> DvexPanel.NONE
  DvexActivePanel.CAMERA -> DvexPanel.CAMERA
  DvexActivePanel.ACTIVITY -> DvexPanel.ACTIVITY
  DvexActivePanel.AI -> DvexPanel.AI
  DvexActivePanel.TOOLS -> DvexPanel.TOOLS
  DvexActivePanel.SYSTEM -> DvexPanel.SYSTEM
  DvexActivePanel.WEATHER -> DvexPanel.WEATHER
  DvexActivePanel.ALERTS -> DvexPanel.ALERTS
  DvexActivePanel.MEMORY -> DvexPanel.MEMORY
  DvexActivePanel.QUICK -> DvexPanel.QUICK
  DvexActivePanel.TIME -> DvexPanel.TIME
  DvexActivePanel.MODE -> DvexPanel.MODE
}

/**
 * Complete Full HUD Layout for Desktop / Tablet / Foldables.
 *
 * Panels no longer sit permanently on the HUD: the layout is two symmetric
 * launcher rails + the central reactor. Equal weights on both sides of the
 * reactor keep it mathematically centered in the full viewport — exactly where
 * it was before (the reactor component itself is untouched).
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
  onOrbToggleRequested: ((Boolean) -> Unit)? = null,
  onRecentAppsRequested: (() -> Unit)? = null,
  onDismissRecentApps: (() -> Unit)? = null,
  /**
   * Explicit vision-request state (Power Mode VISION control). Defaulted to false:
   * the camera is never requested by Power Mode activation, startup, or boot.
   */
  isVisionActive: Boolean = false,
  /** Optional host callback for the VISION control; never required for the panel to work. */
  onVisionToggleRequested: ((Boolean) -> Unit)? = null
) {
  val context = LocalContext.current
  // DUAL MODE: persisted mode state drives the header badge and the MODE popup.
  val powerModeEnabled by PowerModeManager.getInstance(context)
    .powerModeEnabled.collectAsState()
  val settings by AssistantRepository.getInstance(context)
    .settings.collectAsState()

  val (activePanel, selectPanel) = rememberPanelState()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.navigationBars)
  ) {
    // TOP BAR — mode chip opens the real D-VEX MODE selector popup.
    DvexTopBar(
      uiState = uiState,
      isCompact = false,
      onModeSelectorRequested = { selectPanel(DvexActivePanel.MODE) }
    )

    // MAIN CONTENT ROW
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(horizontal = 6.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // 1. LEFT SIDEBAR (existing nav) — its lower edge clears the input band.
      DvexLeftSidebar(
        selectedItem = uiState.selectedNavigation,
        bottomInset = HUD_CONTROL_BAND_HEIGHT,
        onItemSelected = { nav ->
          if (nav == NavItem.SETTINGS) {
            onOpenSettingsRequested?.invoke()
          }
          onUiStateChange(uiState.copy(selectedNavigation = nav))
        }
      )

      Spacer(modifier = Modifier.width(6.dp))

      // 2. LEFT PANEL LAUNCHER RAIL — the one organized button area (top half).
      // Panels open as popups; they are never permanently on the HUD. The rail
      // scrolls inside a viewport that stops above the floating input band, so no
      // launcher button ends up under the dock / voice button.
      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .padding(bottom = HUD_CONTROL_BAND_HEIGHT)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        hudPanelButtons.take(6).forEach { panel ->
          DvexPanelLauncherButton(
            panel = panel,
            isActive = activePanel == panel.toActivePanel(),
            onClick = { selectPanel(panel.toActivePanel()) }
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      // 3. CENTER D-VEX AI CORE (Visual Focus) — untouched component, centered.
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

      // 4. RIGHT PANEL LAUNCHER RAIL — same buttons, second half of the list.
      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .padding(bottom = HUD_CONTROL_BAND_HEIGHT)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        hudPanelButtons.drop(6).forEach { panel ->
          DvexPanelLauncherButton(
            panel = panel,
            isActive = activePanel == panel.toActivePanel(),
            onClick = { selectPanel(panel.toActivePanel()) }
          )
        }
      }

      Spacer(modifier = Modifier.width(6.dp))

      // 5. RIGHT SIDEBAR (existing nav) — its lower edge clears the response card.
      DvexRightSidebar(
        selectedItem = uiState.selectedNavigation,
        bottomInset = HUD_CONTROL_BAND_HEIGHT,
        onItemSelected = { nav ->
          when (nav) {
            // POWER was a dead-end panel; it now opens the real D-VEX MODE selector.
            NavItem.POWER -> selectPanel(DvexActivePanel.MODE)
            NavItem.AI -> selectPanel(DvexActivePanel.AI)
            NavItem.TOOLS -> selectPanel(DvexActivePanel.TOOLS)
            NavItem.MEMORY -> selectPanel(DvexActivePanel.MEMORY)
            NavItem.SETTINGS -> onOpenSettingsRequested?.invoke()
            else -> { /* action nav (CALL/MSG/...) stays routed as before */ }
          }
          onUiStateChange(uiState.copy(selectedNavigation = nav))
        }
      )
    }

    // The old full-width conversation bar is GONE: it was the panel covering the
    // reactor. The conversation now lives in the compact bottom-right card of the
    // shared floating band, and this content row is free to use the full height so
    // the reactor stays mathematically centred in the viewport.
  }

  // Single glass popup layer above everything (overlays — never reflows the HUD).
  // Mode selection persists through the EXISTING PowerModeManager (SharedPreferences);
  // MainActivity syncs real capability wiring from the same flow.
  val modeManager = PowerModeManager.getInstance(context)
  HudPanelPopupLayer(
    activePanel = activePanel,
    onSelect = selectPanel,
    uiState = uiState,
    powerModeEnabled = powerModeEnabled,
    settings = settings,
    onSelectStandard = {
      modeManager.setEnabled(false)
      selectPanel(DvexActivePanel.NONE)
    },
    onSelectPower = {
      modeManager.setEnabled(true)
      selectPanel(DvexActivePanel.NONE)
    },
    onSendCommand = { cmd -> onSendCommand?.invoke(cmd) },
    onOrbToggleRequested = { enable -> onOrbToggleRequested?.invoke(enable) },
    onQuickActionSelected = { action ->
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
    },
    onRecentAppsRequested = { onRecentAppsRequested?.invoke() },
    isVisionActive = isVisionActive,
    onVisionToggleRequested = onVisionToggleRequested
  )
}

private fun DvexPanel.toActivePanel(): DvexActivePanel = when (this) {
  DvexPanel.NONE -> DvexActivePanel.NONE
  DvexPanel.CAMERA -> DvexActivePanel.CAMERA
  DvexPanel.ACTIVITY -> DvexActivePanel.ACTIVITY
  DvexPanel.AI -> DvexActivePanel.AI
  DvexPanel.TOOLS -> DvexActivePanel.TOOLS
  DvexPanel.SYSTEM -> DvexActivePanel.SYSTEM
  DvexPanel.WEATHER -> DvexActivePanel.WEATHER
  DvexPanel.ALERTS -> DvexActivePanel.ALERTS
  DvexPanel.MEMORY -> DvexActivePanel.MEMORY
  DvexPanel.QUICK -> DvexActivePanel.QUICK
  DvexPanel.TIME -> DvexActivePanel.TIME
  DvexPanel.MODE -> DvexActivePanel.MODE
}

/**
 * Optimized Mobile Portrait/Landscape Layout for Phones.
 * The reactor and conversation are primary; every panel is behind the single
 * organized launcher rail (popups). The mode badge shows the live mode state.
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
  onOrbToggleRequested: ((Boolean) -> Unit)? = null,
  onRecentAppsRequested: (() -> Unit)? = null,
  onDismissRecentApps: (() -> Unit)? = null,
  /**
   * Explicit vision-request state (Power Mode VISION control). Defaulted to false:
   * the camera is never requested by Power Mode activation, startup, or boot.
   */
  isVisionActive: Boolean = false,
  /** Optional host callback for the VISION control; never required for the panel to work. */
  onVisionToggleRequested: ((Boolean) -> Unit)? = null
) {
  val context = LocalContext.current
  // DUAL MODE: persisted mode state drives the badge and the MODE popup.
  val powerModeEnabled by PowerModeManager.getInstance(context)
    .powerModeEnabled.collectAsState()
  val settings by AssistantRepository.getInstance(context)
    .settings.collectAsState()

  val (activePanel, selectPanel) = rememberPanelState()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.navigationBars)
  ) {
    // TOP BAR: D-VEX + System status (Compact version) — mode chip opens the
    // real D-VEX MODE selector popup.
    DvexTopBar(
      uiState = uiState,
      isCompact = true,
      onModeSelectorRequested = { selectPanel(DvexActivePanel.MODE) }
    )

    // MAIN CONTENT: reactor + conversation + launcher rail.
    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(horizontal = 6.dp, vertical = 4.dp)
        // Reserved space for the floating input band + response card so the
        // launcher grid never ends up underneath them (the phone card can grow
        // a little past the band, hence the extra clearance).
        .padding(bottom = HUD_CONTROL_BAND_HEIGHT + 24.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // 1. ALWAYS-PROMINENT LARGE D-VEX AI CORE REACTOR — untouched.
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

      // Active-mode indicator, always visible under the reactor.
      DvexModeBadge(
        powerModeEnabled = powerModeEnabled,
        onClick = { selectPanel(DvexActivePanel.MODE) }
      )

      // The conversation itself is the compact bottom-right card (shared floating
      // band) so it never pushes the reactor out of view on a phone screen.

      // 2. PANEL LAUNCHER GRID — the one organized button area (2 rows, wrap-style).
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        hudPanelButtons.chunked(4).forEach { rowPanels ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
          ) {
            rowPanels.forEach { panel ->
              DvexPanelLauncherButton(
                panel = panel,
                isActive = activePanel == panel.toActivePanel(),
                onClick = { selectPanel(panel.toActivePanel()) },
                modifier = Modifier.padding(horizontal = 3.dp)
              )
            }
          }
        }
      }
    }
  }

  // Single glass popup layer above everything (overlays — never reflows the HUD).
  val modeManager = PowerModeManager.getInstance(context)
  HudPanelPopupLayer(
    activePanel = activePanel,
    onSelect = selectPanel,
    uiState = uiState,
    powerModeEnabled = powerModeEnabled,
    settings = settings,
    onSelectStandard = {
      modeManager.setEnabled(false)
      selectPanel(DvexActivePanel.NONE)
    },
    onSelectPower = {
      modeManager.setEnabled(true)
      selectPanel(DvexActivePanel.NONE)
    },
    onSendCommand = { cmd -> onSendCommand?.invoke(cmd) },
    onOrbToggleRequested = { enable -> onOrbToggleRequested?.invoke(enable) },
    onQuickActionSelected = { action ->
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
    },
    onRecentAppsRequested = { onRecentAppsRequested?.invoke() },
    isVisionActive = isVisionActive,
    onVisionToggleRequested = onVisionToggleRequested
  )
}

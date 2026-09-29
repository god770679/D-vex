package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mode.PowerModeManager
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedDim
import com.example.ui.theme.DvexNeonRedGlow
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary

/**
 * D-VEX PANEL POPUP SYSTEM — single source of truth for which overlay is open.
 *
 * [NONE] means no popup. Every other value opens exactly ONE panel popup at a
 * time; the popup content REUSES the existing panel composables (CameraVisionPanel,
 * WeatherPanel, SystemStatusPanel, ...) — nothing is duplicated.
 *
 * [MODE] is the D-VEX MODE selector (Standard / Power), reachable from the header
 * chip and the right-sidebar POWER control. It is a real mode control, not a label.
 */
enum class DvexPanel(val label: String, val icon: ImageVector) {
  NONE("NONE", Icons.Filled.Close),
  CAMERA("CAMERA", Icons.Filled.CameraAlt),
  ACTIVITY("ACTIVITY", Icons.Filled.History),
  AI("AI", Icons.Filled.Psychology),
  TOOLS("TOOLS", Icons.Filled.Tune),
  SYSTEM("SYSTEM", Icons.Filled.Settings),
  WEATHER("WEATHER", Icons.Filled.WbCloudy),
  ALERTS("ALERTS", Icons.Filled.NotificationsActive),
  MEMORY("MEMORY", Icons.Filled.Memory),
  QUICK("QUICK", Icons.Filled.Widgets),
  TIME("TIME", Icons.Filled.Schedule),
  MODE("MODE", Icons.Filled.Bolt)
}

/**
 * One launcher button in the single organized panel-launcher column. Cut-corner
 * D-VEX style, active state highlighted, 48dp minimum touch target, content
 * description for accessibility.
 */
@Composable
fun DvexPanelLauncherButton(
  panel: DvexPanel,
  isActive: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
  val borderColor = if (isActive) DvexNeonRedBright else DvexBorderMuted
  val labelColor = if (isActive) DvexNeonRedBright else DvexTextSecondary

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .size(width = 56.dp, height = 44.dp)
        .clip(shape)
        .background(if (isActive) DvexSurfaceCard else DvexBlack.copy(alpha = 0.55f))
        .border(1.dp, borderColor, shape)
        .drawBehind {
          if (isActive) {
            drawRect(
              brush = Brush.radialGradient(
                colors = listOf(DvexNeonRedGlow, Color.Transparent),
                radius = size.maxDimension
              )
            )
          }
        }
        .clickable(onClick = onClick)
        .semantics {
          contentDescription = if (isActive) {
            "${panel.label} panel open, tap to close"
          } else {
            "Open ${panel.label} panel"
          }
        },
      contentAlignment = Alignment.Center
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
          imageVector = panel.icon,
          contentDescription = null,
          tint = labelColor,
          modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = panel.label,
          fontFamily = FontFamily.Monospace,
          fontSize = 7.sp,
          fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
          letterSpacing = 0.8.sp,
          color = labelColor
        )
      }
    }
  }
}

/**
 * D-VEX MODE SELECTOR — the real Standard/Power control.
 *
 * Replaces the old dead-end POWER nav panel: two explicit rows, the selected mode
 * highlighted, selection closes the selector and persists via the existing
 * PowerModeManager (SharedPreferences `power_mode_enabled`, default STANDARD).
 * Selecting a mode ONLY flips the persisted flag here; the app-level capability
 * wiring lives in MainActivity (existing settings plumbing), never in this UI.
 */
@Composable
fun DvexModeSelector(
  powerModeEnabled: Boolean,
  onSelectStandard: () -> Unit,
  onSelectPower: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(modifier = modifier.widthIn(max = 340.dp)) {
    TacticalPanel(
      title = "D-VEX MODE",
      headerTag = if (powerModeEnabled) "POWER ACTIVE" else "STANDARD ACTIVE",
      cornerCut = 10.dp
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        ModeOptionRow(
          title = "STANDARD MODE",
          subtitle = "Normal operation",
          selected = !powerModeEnabled,
          description = "Standard Mode, normal everyday assistant operation. Tap to select.",
          onClick = onSelectStandard
        )
        ModeOptionRow(
          title = "POWER MODE",
          subtitle = "Maximum available capability",
          selected = powerModeEnabled,
          description = "Power Mode, maximum available D-VEX capability within existing permissions. Tap to select.",
          onClick = onSelectPower
        )

        Text(
          text = "Power Mode enables already-implemented capabilities only. " +
            "Android permissions and action verification are never bypassed.",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          lineHeight = 11.sp,
          color = DvexTextMuted,
          modifier = Modifier.padding(top = 2.dp)
        )
      }
    }
  }
}

@Composable
private fun ModeOptionRow(
  title: String,
  subtitle: String,
  selected: Boolean,
  description: String,
  onClick: () -> Unit
) {
  val shape = CutCornerShape(6.dp)
  val borderColor = if (selected) DvexNeonRedBright else DvexBorderMuted

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(if (selected) DvexSurfaceCard else DvexBlack.copy(alpha = 0.6f))
      .border(1.dp, borderColor, shape)
      .drawBehind {
        if (selected) {
          drawRect(
            brush = Brush.radialGradient(
              colors = listOf(DvexNeonRedGlow, Color.Transparent),
              radius = size.maxDimension
            )
          )
        }
      }
      .clickable(onClick = onClick)
      .semantics { contentDescription = description }
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .size(10.dp)
        .clip(CircleShape)
        .background(if (selected) DvexNeonRedBright else Color.Transparent)
        .border(1.dp, if (selected) DvexNeonRedBright else DvexBorderMuted, CircleShape)
    )
    Spacer(modifier = Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = if (selected) DvexNeonRedBright else DvexTextPrimary
      )
      Text(
        text = subtitle,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        color = DvexTextMuted
      )
    }
    if (selected) {
      Text(
        text = "ACTIVE",
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = DvexNeonRed
      )
    }
  }
}

/**
 * Compact always-visible mode indicator (STANDARD / POWER) with active-state pip.
 * Tapping it opens the D-VEX MODE selector popup.
 */
@Composable
fun DvexModeBadge(
  powerModeEnabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val shape = CutCornerShape(4.dp)
  Row(
    modifier = modifier
      .clip(shape)
      .background(DvexBlack.copy(alpha = 0.6f))
      .border(1.dp, if (powerModeEnabled) DvexNeonRedBright else DvexBorderMuted, shape)
      .clickable(onClick = onClick)
      .semantics {
        contentDescription = if (powerModeEnabled) {
          "Power Mode active. Tap to change D-VEX mode."
        } else {
          "Standard Mode active. Tap to change D-VEX mode."
        }
      }
      .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .size(6.dp)
        .clip(CircleShape)
        .background(if (powerModeEnabled) DvexNeonRedBright else DvexTextMuted)
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = if (powerModeEnabled) "POWER MODE" else "STANDARD MODE",
      fontFamily = FontFamily.Monospace,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 1.2.sp,
      color = if (powerModeEnabled) DvexNeonRedBright else DvexTextSecondary
    )
  }
}

/**
 * The single popup host. Renders a dim scrim + one glass HUD card containing the
 * selected panel's EXISTING composable. Tapping the scrim, the close button, or
 * requesting [onDismiss] from a BACK press closes it. Landscape-safe: the card is
 * width-capped, height-capped and scrolls; it overlays instead of reflowing, so
 * the central reactor never moves.
 */
@Composable
fun DvexPanelPopupHost(
  activePanel: DvexPanel,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  AnimatedVisibility(
    visible = activePanel != DvexPanel.NONE,
    enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.96f, animationSpec = tween(160)),
    exit = fadeOut(tween(130)) + scaleOut(targetScale = 0.97f, animationSpec = tween(130)),
    modifier = modifier
  ) {
    // Height is adaptive: on short landscape phones the popup must stay well
    // clear of the header and the floating corner band instead of eating 82% of
    // the viewport height (which visually squeezed the centred reactor).
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val popupMaxHeight = if (maxHeight < 420.dp) maxHeight * 0.58f else maxHeight * 0.72f
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(DvexBlack.copy(alpha = 0.55f))
          .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
      ) {
        Column(
          modifier = Modifier
            .widthIn(max = 380.dp)
            .heightIn(max = popupMaxHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp)
          .clip(CutCornerShape(10.dp))
          .background(DvexSurfaceDark.copy(alpha = 0.97f))
          .border(1.dp, DvexBorderRed, CutCornerShape(10.dp))
          .drawBehind {
            drawRect(
              brush = Brush.radialGradient(
                colors = listOf(DvexNeonRedGlow.copy(alpha = 0.4f), Color.Transparent),
                radius = size.maxDimension,
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f)
              )
            )
          }
          .clickable(enabled = false) { } // block scrim taps inside the card
          .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Popup header: panel name + clear close button.
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .size(4.dp, 14.dp)
              .background(DvexNeonRed)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = activePanel.label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = DvexNeonRedBright,
            modifier = Modifier.weight(1f)
          )
          Box(
            modifier = Modifier
              .size(30.dp)
              .clip(CutCornerShape(4.dp))
              .background(DvexSurfaceCard)
              .border(1.dp, DvexBorderMuted, CutCornerShape(4.dp))
              .clickable(onClick = onDismiss)
              .semantics { contentDescription = "Close ${activePanel.label} panel" },
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Filled.Close,
              contentDescription = null,
              tint = DvexTextSecondary,
              modifier = Modifier.size(15.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // EXISTING panel components rendered inside the popup, scrollable so every
        // panel stays readable in landscape.
        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          content()
        }
        }
      }
    }
  }
}

/**
 * Content resolver: maps the active panel to its EXISTING panel composable.
 * Kept here so DvexHud only passes state; no panel implementation is duplicated.
 */
@Composable
fun DvexPanelPopupContent(
  activePanel: DvexPanel,
  weatherData: com.example.model.WeatherInfo,
  notifications: List<com.example.model.NotificationItem>,
  systemStatus: com.example.model.SystemVitals,
  memoryPercentage: Int,
  recentActivities: List<com.example.model.ActivityItem>,
  voiceState: com.example.model.VoiceState,
  powerModeEnabled: Boolean,
  settings: com.example.model.DvexSettings,
  onSendCommand: (String) -> Unit,
  onOrbToggleRequested: (Boolean) -> Unit,
  onQuickActionSelected: (String) -> Unit,
  onRecentAppsRequested: () -> Unit,
  onSelectStandard: () -> Unit,
  onSelectPower: () -> Unit,
  /**
   * Vision-request state from the Power Mode VISION control. Defaulted so existing
   * call sites keep compiling; only ever written by an explicit user tap
   * (VisionRequestGate), never by Power Mode activation or startup.
   */
  isVisionActive: Boolean = false,
  onVisionToggleRequested: ((Boolean) -> Unit)? = null
) {
  when (activePanel) {
    DvexPanel.CAMERA -> CameraVisionPanel(isVisionActive = isVisionActive)
    DvexPanel.ACTIVITY -> RecentActivityPanel(activities = recentActivities)
    DvexPanel.AI -> VoiceModule(voiceState = voiceState)
    DvexPanel.TOOLS -> PowerModeButtonCluster(
      powerModeEnabled = powerModeEnabled,
      settings = settings,
      onSendCommand = onSendCommand,
      onOrbToggleRequested = onOrbToggleRequested,
      onRecentAppsRequested = onRecentAppsRequested,
      onVisionToggleRequested = onVisionToggleRequested
    )
    DvexPanel.SYSTEM -> SystemStatusPanel(vitals = systemStatus)
    DvexPanel.WEATHER -> WeatherPanel(weather = weatherData)
    DvexPanel.ALERTS -> NotificationPanel(notifications = notifications)
    DvexPanel.MEMORY -> MemoryCorePanel(percentage = memoryPercentage)
    DvexPanel.QUICK -> QuickAccessPanel(onActionSelected = onQuickActionSelected)
    DvexPanel.TIME -> TimePanel()
    DvexPanel.MODE -> DvexModeSelector(
      powerModeEnabled = powerModeEnabled,
      onSelectStandard = onSelectStandard,
      onSelectPower = onSelectPower
    )
    DvexPanel.NONE -> { /* no popup */ }
  }
}

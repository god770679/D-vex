package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Tactical Power Mode Button Cluster.
 * Presents a futuristic honeycomb/pentagonal cluster of quick-action controls:
 * 1. Orb Toggle: controls DvexFloatingOrbService on/off
 * 2. Recent App: triggers recent apps view
 * 3. Notifications: triggers notification shade
 * 4. Device Control: navigation/system controls
 * 5. Vision Detection: camera toggle (explicit on/off)
 *
 * Dimmed and inactive when in Standard Mode; fully glowing and operational in Power Mode.
 */
@Composable
fun PowerModeButtonCluster(
  isPowerMode: Boolean,
  isOrbActive: Boolean,
  isVisionActive: Boolean,
  onToggleOrb: () -> Unit,
  onRecentApp: () -> Unit,
  onNotificationAlert: () -> Unit,
  onDeviceControl: () -> Unit,
  onToggleVision: () -> Unit,
  modifier: Modifier = Modifier
) {
  val clusterAlpha = if (isPowerMode) 1f else 0.4f

  TacticalPanel(
    title = "Power Core Matrix",
    headerTag = if (isPowerMode) "ONLINE // POWER" else "STANDBY // STD",
    modifier = modifier.testTag("power_mode_button_cluster")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(DvexSurfaceDark)
        .padding(horizontal = 8.dp, vertical = 8.dp)
        .alpha(clusterAlpha),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      if (!isPowerMode) {
        // Mode prompt banner when in Standard Mode
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(4.dp))
            .background(DvexBlack.copy(alpha = 0.6f))
            .border(1.dp, DvexBorderMuted, CutCornerShape(4.dp))
            .padding(vertical = 4.dp, horizontal = 8.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "[ POWER MODE OFFLINE — SWITCH TO PWR IN TOP BAR ]",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = DvexTextMuted,
            letterSpacing = 1.sp
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
      }

      // Honeycomb / Pentagonal Lattice:
      // Row 1: 3 Hexagonal Action Buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
      ) {
        HoneycombActionButton(
          label = "ORB",
          sublabel = if (isOrbActive) "ONLINE" else "OFF",
          icon = Icons.Filled.RadioButtonChecked,
          isActive = isOrbActive,
          isEnabled = isPowerMode,
          testTag = "power_mode_orb_btn",
          onClick = onToggleOrb
        )

        HoneycombActionButton(
          label = "RECENTS",
          sublabel = "APPS",
          icon = Icons.Filled.History,
          isActive = false,
          isEnabled = isPowerMode,
          testTag = "power_mode_recents_btn",
          onClick = onRecentApp
        )

        HoneycombActionButton(
          label = "ALERTS",
          sublabel = "SHADE",
          icon = Icons.Filled.NotificationsActive,
          isActive = false,
          isEnabled = isPowerMode,
          testTag = "power_mode_notif_btn",
          onClick = onNotificationAlert
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Row 2: 2 Centered Hexagonal Action Buttons (offset lattice)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
      ) {
        HoneycombActionButton(
          label = "CONTROL",
          sublabel = "SYSTEM",
          icon = Icons.Filled.PhoneAndroid,
          isActive = false,
          isEnabled = isPowerMode,
          testTag = "power_mode_device_btn",
          onClick = onDeviceControl
        )

        Spacer(modifier = Modifier.width(18.dp))

        HoneycombActionButton(
          label = "VISION",
          sublabel = if (isVisionActive) "CAM ON" else "CAM OFF",
          icon = Icons.Filled.Videocam,
          isActive = isVisionActive,
          isEnabled = isPowerMode,
          testTag = "power_mode_vision_btn",
          onClick = onToggleVision
        )
      }
    }
  }
}

/**
 * Individual tactical hexagon-styled action button.
 */
@Composable
private fun HoneycombActionButton(
  label: String,
  sublabel: String,
  icon: ImageVector,
  isActive: Boolean,
  isEnabled: Boolean,
  testTag: String,
  onClick: () -> Unit
) {
  val infiniteTransition = rememberInfiniteTransition(label = "btnPulse")
  val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 0.5f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(1000, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "btnPulseAlpha"
  )

  val borderColor by animateColorAsState(
    targetValue = when {
      !isEnabled -> DvexBorderMuted
      isActive -> DvexNeonRedBright
      else -> DvexBorderRed
    },
    label = "btnBorderColor"
  )

  val backgroundColor by animateColorAsState(
    targetValue = when {
      !isEnabled -> DvexBlack.copy(alpha = 0.5f)
      isActive -> DvexSurfaceCard
      else -> DvexBlack.copy(alpha = 0.8f)
    },
    label = "btnBgColor"
  )

  val iconTint = when {
    !isEnabled -> DvexTextMuted
    isActive -> DvexNeonRedBright
    else -> DvexTextPrimary
  }

  val shape = CutCornerShape(10.dp)

  Column(
    modifier = Modifier
      .defaultMinSize(minWidth = 76.dp, minHeight = 60.dp)
      .clip(shape)
      .background(backgroundColor)
      .border(1.dp, borderColor, shape)
      .clickable(
        enabled = isEnabled,
        role = Role.Button,
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
      )
      .padding(horizontal = 8.dp, vertical = 6.dp)
      .testTag(testTag),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = "$label button",
        tint = iconTint,
        modifier = Modifier.size(16.dp)
      )
      if (isActive && isEnabled) {
        Spacer(modifier = Modifier.width(4.dp))
        Box(
          modifier = Modifier
            .size(5.dp)
            .clip(CircleShape)
            .background(DvexNeonRedBright)
            .alpha(pulseAlpha)
        )
      }
    }

    Spacer(modifier = Modifier.height(3.dp))

    Text(
      text = label,
      fontFamily = FontFamily.Monospace,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      color = if (isEnabled) DvexTextPrimary else DvexTextMuted,
      textAlign = TextAlign.Center
    )

    Text(
      text = sublabel,
      fontFamily = FontFamily.Monospace,
      fontSize = 7.sp,
      fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
      color = if (isActive && isEnabled) DvexNeonRedBright else DvexTextMuted,
      textAlign = TextAlign.Center
    )
  }
}

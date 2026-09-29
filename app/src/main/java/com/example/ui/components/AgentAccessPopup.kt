package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AgentActivity
import com.example.model.AgentActivityController
import com.example.model.AgentCapability
import com.example.model.AgentActivityStatus
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexSuccess
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexWarning
import kotlinx.coroutines.delay

/**
 * D-VEX AGENT ACCESS POPUP
 *
 * A compact, contextual card that appears only while D-VEX is using (or needs) a
 * capability — camera, location, app access, an app action, etc. It never blocks the
 * conversation UI and disappears on its own once the action completes.
 *
 * Reusable by the agent layer: publish through [AgentActivityController] and the
 * [AgentAccessPopupHost] renders it.
 */
@Composable
fun AgentAccessPopup(
  activity: AgentActivity,
  modifier: Modifier = Modifier
) {
  val accent = accentFor(activity)
  val shape = CutCornerShape(6.dp)

  Column(modifier = modifier.widthIn(max = 236.dp)) {
    Row(
      modifier = Modifier
        .clip(shape)
        .background(DvexSurfaceCard)
        .border(1.dp, accent.copy(alpha = 0.75f), shape)
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(26.dp)
          .clip(CutCornerShape(4.dp))
          .background(accent.copy(alpha = 0.14f))
          .border(1.dp, accent.copy(alpha = 0.5f), CutCornerShape(4.dp)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = iconFor(activity),
          contentDescription = activity.capability.displayName,
          tint = accent,
          modifier = Modifier.size(15.dp)
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      Column {
        Text(
          text = activity.capability.displayName.uppercase(),
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
          color = accent
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
          text = activity.message,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium,
          color = DvexTextPrimary,
          maxLines = 2
        )
      }
    }
  }
}

/**
 * Renders the live agent activity feed (newest first) and auto-dismisses terminal
 * entries. Place it in a corner of the HUD — it only exists while actions are running.
 */
@Composable
fun AgentAccessPopupHost(
  activities: List<AgentActivity>,
  modifier: Modifier = Modifier,
  autoDismissMs: Long = 2200L
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(6.dp),
    horizontalAlignment = Alignment.End
  ) {
    activities.forEach { activity ->
      androidx.compose.runtime.key(activity.id) {
        PopupEntry {
          AgentAccessPopup(activity = activity)
        }
        if (activity.isTerminal && activity.autoDismiss) {
          LaunchedEffect(activity.id) {
            delay(autoDismissMs)
            AgentActivityController.dismiss(activity.id)
          }
        }
      }
    }
  }
}

/** One-shot appearance animation for a newly published popup. */
@Composable
private fun PopupEntry(content: @Composable () -> Unit) {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { shown = true }
  AnimatedVisibility(
    visible = shown,
    enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { -it / 3 }),
    exit = fadeOut(tween(140))
  ) {
    content()
  }
}

private fun accentFor(activity: AgentActivity): Color = when (activity.status) {
  AgentActivityStatus.COMPLETED -> DvexSuccess
  AgentActivityStatus.FAILED -> DvexWarning
  AgentActivityStatus.ACCESS_REQUIRED -> DvexWarning
  AgentActivityStatus.WAITING_PERMISSION -> DvexWarning
  else -> DvexNeonRed
}

private fun iconFor(activity: AgentActivity): ImageVector = when (activity.status) {
  AgentActivityStatus.COMPLETED -> Icons.Filled.CheckCircle
  AgentActivityStatus.FAILED -> Icons.Filled.Warning
  AgentActivityStatus.ACCESS_REQUIRED -> Icons.Filled.Warning
  AgentActivityStatus.WAITING_PERMISSION -> Icons.Filled.Warning
  AgentActivityStatus.SEARCHING -> Icons.Filled.Search
  else -> when (activity.capability) {
    AgentCapability.CAMERA -> Icons.Filled.CameraAlt
    AgentCapability.LOCATION -> Icons.Filled.LocationOn
    AgentCapability.WEATHER -> Icons.Filled.LocationOn
    AgentCapability.MICROPHONE -> Icons.Filled.Mic
    AgentCapability.NOTIFICATIONS -> Icons.Filled.Notifications
    AgentCapability.CONTACTS -> Icons.Filled.Person
    AgentCapability.MESSAGES -> Icons.Filled.Email
    AgentCapability.WHATSAPP -> Icons.Filled.Email
    else -> Icons.Filled.Apps
  }
}

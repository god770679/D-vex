package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.DvexSettings
import com.example.permissions.DvexPermissionManager
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedDim
import com.example.ui.theme.DvexSuccess
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary
import com.example.ui.theme.DvexWarning

@Composable
fun AlwaysReadySettingsDialog(
  settings: DvexSettings,
  onSettingsChanged: (DvexSettings) -> Unit,
  onRequestAudioPermission: () -> Unit,
  onRequestNotificationPermission: () -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val hasAudio = DvexPermissionManager.hasAudioPermission(context)
  val hasOverlay = DvexPermissionManager.hasOverlayPermission(context)
  val hasNotif = DvexPermissionManager.hasNotificationPermission(context)
  val hasAccessibility = DvexPermissionManager.isAccessibilityServiceEnabled(context)
  val hasBattery = DvexPermissionManager.hasBatteryOptimizationExemption(context)

  Dialog(onDismissRequest = onDismiss) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(CutCornerShape(12.dp))
        .background(DvexBlack)
        .border(1.5.dp, DvexNeonRed, CutCornerShape(12.dp))
        .padding(16.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(DvexNeonRed)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "ALWAYS-READY PROTOCOLS",
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp,
              color = DvexNeonRedBright,
              letterSpacing = 1.sp
            )
          }

          IconButton(onClick = onDismiss) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close",
              tint = DvexTextSecondary
            )
          }
        }

        HorizontalDivider(color = DvexBorderMuted, thickness = 1.dp)

        // Section 1: Always Ready Protocols
        Text(
          text = "ALWAYS READY PROTOCOLS",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          color = DvexNeonRedBright,
          letterSpacing = 1.sp
        )

        SettingToggleRow(
          title = "ALWAYS READY",
          subtitle = "Maintains D-VEX readiness in background via persistent service",
          checked = settings.alwaysReadyEnabled,
          onCheckedChange = { onSettingsChanged(settings.copy(alwaysReadyEnabled = it)) }
        )

        SettingToggleRow(
          title = "WAKE WORD",
          subtitle = "Continuous local keyword listening for reactor activation",
          checked = settings.wakeWordEnabled,
          onCheckedChange = {
            if (it && !hasAudio) {
              onRequestAudioPermission()
            } else {
              onSettingsChanged(settings.copy(wakeWordEnabled = it))
            }
          }
        )

        // Wake Phrase Selection
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(4.dp))
            .background(DvexSurfaceDark.copy(alpha = 0.5f))
            .padding(10.dp)
        ) {
          Text(
            text = "WAKE PHRASE",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = DvexTextPrimary
          )
          Text(
            text = "Select phrase that initiates listening protocol",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = DvexTextMuted
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf("D-VEX", "Hey D-VEX").forEach { phrase ->
              val isSelected = settings.wakePhrase == phrase
              Box(
                modifier = Modifier
                  .weight(1f)
                  .clip(CutCornerShape(4.dp))
                  .background(if (isSelected) DvexNeonRed.copy(alpha = 0.25f) else DvexSurfaceDark)
                  .border(1.dp, if (isSelected) DvexNeonRedBright else DvexBorderMuted, CutCornerShape(4.dp))
                  .clickable { onSettingsChanged(settings.copy(wakePhrase = phrase, wakeWordKeyword = phrase)) }
                  .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = phrase,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  fontSize = 12.sp,
                  color = if (isSelected) DvexNeonRedBright else DvexTextSecondary
                )
              }
            }
          }
        }

        SettingToggleRow(
          title = "VOICE CONFIRMATION",
          subtitle = "Play verbal response (\"Yes?\") upon wake-word trigger",
          checked = settings.voiceConfirmationEnabled,
          onCheckedChange = { onSettingsChanged(settings.copy(voiceConfirmationEnabled = it)) }
        )

        SettingToggleRow(
          title = "FLOATING ORB",
          subtitle = "Displays cybernetic energy reactor above other applications",
          checked = settings.floatingOrbEnabled,
          onCheckedChange = {
            if (it && !hasOverlay) {
              context.startActivity(DvexPermissionManager.createOverlayPermissionIntent(context))
            } else {
              onSettingsChanged(settings.copy(floatingOrbEnabled = it))
            }
          }
        )

        // Orb Size Selector
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(4.dp))
            .background(DvexSurfaceDark.copy(alpha = 0.5f))
            .padding(10.dp)
        ) {
          Text(
            text = "ORB SIZE",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = DvexTextPrimary
          )
          Text(
            text = "Current dimension: ${settings.orbSizeDp}dp",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = DvexTextMuted
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf(48 to "48dp", 56 to "56dp", 64 to "64dp").forEach { (size, label) ->
              val isSelected = settings.orbSizeDp == size
              Box(
                modifier = Modifier
                  .weight(1f)
                  .clip(CutCornerShape(4.dp))
                  .background(if (isSelected) DvexNeonRed.copy(alpha = 0.25f) else DvexSurfaceDark)
                  .border(1.dp, if (isSelected) DvexNeonRedBright else DvexBorderMuted, CutCornerShape(4.dp))
                  .clickable { onSettingsChanged(settings.copy(orbSizeDp = size)) }
                  .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = label,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  fontSize = 12.sp,
                  color = if (isSelected) DvexNeonRedBright else DvexTextSecondary
                )
              }
            }
          }
        }

        // Orb Position & Reset
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(4.dp))
            .background(DvexSurfaceDark.copy(alpha = 0.5f))
            .padding(10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = "ORB POSITION",
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              color = DvexTextPrimary
            )
            Text(
              text = "X: ${settings.orbPositionX}px, Y: ${settings.orbPositionY}px",
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = DvexTextMuted
            )
          }

          OutlinedButton(
            onClick = { onSettingsChanged(settings.copy(orbPositionX = 100, orbPositionY = 300)) },
            shape = CutCornerShape(2.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DvexNeonRedBright)
          ) {
            Text("RESET", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
          }
        }

        SettingToggleRow(
          title = "VOICE RESPONSE (TTS)",
          subtitle = "Transmits spoken answers using speech synthesis",
          checked = settings.voiceResponseEnabled,
          onCheckedChange = { onSettingsChanged(settings.copy(voiceResponseEnabled = it)) }
        )

        SettingToggleRow(
          title = "ACCESSIBILITY NAVIGATION",
          subtitle = "Enables hands-free Home, Back & Recents commands",
          checked = settings.accessibilityControlEnabled,
          onCheckedChange = {
            if (it && !hasAccessibility) {
              context.startActivity(DvexPermissionManager.createAccessibilitySettingsIntent())
            } else {
              onSettingsChanged(settings.copy(accessibilityControlEnabled = it))
            }
          }
        )

        HorizontalDivider(color = DvexBorderMuted, thickness = 1.dp)

        // Section 2: Permission Statuses & Direct Route Buttons
        val overallStatus = when {
          hasAudio && hasOverlay && hasNotif && hasBattery -> "READY"
          hasAudio -> "LIMITED"
          else -> "PERMISSION REQUIRED"
        }
        val statusBorderColor = when (overallStatus) {
          "READY" -> DvexSuccess
          "LIMITED" -> DvexWarning
          else -> DvexNeonRedBright
        }
        val statusDescription = when (overallStatus) {
          "READY" -> "All background authorizations granted. Full system readiness active."
          "LIMITED" -> "Microphone operational. Overlay or battery exemptions may limit background wake."
          else -> "Essential permissions missing. Assistant cannot listen or interact."
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(4.dp))
            .background(DvexSurfaceDark)
            .border(1.dp, statusBorderColor, CutCornerShape(4.dp))
            .padding(10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .size(10.dp)
              .clip(CircleShape)
              .background(statusBorderColor)
          )
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = "SYSTEM READINESS: $overallStatus",
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
              color = statusBorderColor,
              letterSpacing = 1.sp
            )
            Text(
              text = statusDescription,
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              color = DvexTextMuted
            )
          }
        }

        Text(
          text = "SECURITY & ACCESS PERMISSIONS",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          color = DvexNeonRedBright,
          letterSpacing = 1.sp
        )

        // 1. Microphone
        PermissionStatusRow(
          title = "Microphone (Voice & Wake-Word)",
          icon = Icons.Default.Mic,
          granted = hasAudio,
          onGrantClick = { onRequestAudioPermission() }
        )

        // 2. Overlay
        PermissionStatusRow(
          title = "Overlay (Draw Over Other Apps)",
          icon = Icons.Default.Layers,
          granted = hasOverlay,
          onGrantClick = { context.startActivity(DvexPermissionManager.createOverlayPermissionIntent(context)) }
        )

        // 3. Accessibility
        PermissionStatusRow(
          title = "Accessibility (App & System Navigation)",
          icon = Icons.Default.AccessibilityNew,
          granted = hasAccessibility,
          onGrantClick = { context.startActivity(DvexPermissionManager.createAccessibilitySettingsIntent()) }
        )

        // 4. Notifications
        PermissionStatusRow(
          title = "Notifications (Foreground Service)",
          icon = Icons.Default.Notifications,
          granted = hasNotif,
          onGrantClick = { onRequestNotificationPermission() }
        )

        // 5. Battery / Background Access
        PermissionStatusRow(
          title = "Battery / Background Access (Unrestricted)",
          icon = Icons.Default.BatteryChargingFull,
          granted = hasBattery,
          onGrantClick = { context.startActivity(DvexPermissionManager.createBatteryOptimizationIntent(context)) }
        )

        HorizontalDivider(color = DvexBorderMuted, thickness = 1.dp)

        // Section 3: Foreground Service Operational Status
        Text(
          text = "SERVICE RUNTIME STATUS",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          color = DvexNeonRedBright,
          letterSpacing = 1.sp
        )

        val isServiceActive = settings.alwaysReadyEnabled && hasAudio
        OperationalStatusRow(
          title = "D-VEX Foreground Service",
          subtitle = if (isServiceActive) "RUNNING (MICROPHONE MONITORING)" else "STOPPED / STANDBY",
          active = isServiceActive
        )

        val isWakeActive = settings.wakeWordEnabled && hasAudio
        OperationalStatusRow(
          title = "Wake-Word Detection (\"D-VEX\")",
          subtitle = if (isWakeActive) "ONLINE & LISTENING" else "OFFLINE",
          active = isWakeActive
        )

        val isOrbActive = settings.floatingOrbEnabled && hasOverlay
        OperationalStatusRow(
          title = "Floating Reactor Orb (56dp)",
          subtitle = if (isOrbActive) "STANDBY OVERLAY READY" else "OFFLINE (OVERLAY PERMISSION REQ)",
          active = isOrbActive
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Done button
        ElevatedButton(
          onClick = onDismiss,
          colors = ButtonDefaults.elevatedButtonColors(
            containerColor = DvexNeonRed,
            contentColor = Color.White
          ),
          shape = CutCornerShape(4.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "SAVE & CLOSE PROTOCOLS",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }
  }
}

@Composable
private fun SettingToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(CutCornerShape(4.dp))
      .background(DvexSurfaceDark.copy(alpha = 0.5f))
      .padding(10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = DvexTextPrimary
      )
      Text(
        text = subtitle,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = DvexTextMuted,
        lineHeight = 14.sp
      )
    }

    Spacer(modifier = Modifier.width(8.dp))

    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = DvexNeonRed,
        uncheckedThumbColor = DvexTextMuted,
        uncheckedTrackColor = DvexSurfaceDark
      )
    )
  }
}

@Composable
private fun PermissionStatusRow(
  title: String,
  icon: ImageVector,
  granted: Boolean,
  onGrantClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(CutCornerShape(4.dp))
      .background(DvexSurfaceDark.copy(alpha = 0.35f))
      .border(0.5.dp, if (granted) DvexSuccess.copy(alpha = 0.5f) else DvexWarning.copy(alpha = 0.5f), CutCornerShape(4.dp))
      .padding(8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (granted) DvexSuccess else DvexWarning,
        modifier = Modifier.size(18.dp)
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column {
        Text(
          text = title,
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          color = DvexTextPrimary
        )
        Text(
          text = if (granted) "STATUS: AUTHORIZED" else "STATUS: ACTION REQUIRED",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = if (granted) DvexSuccess else DvexWarning
        )
      }
    }

    if (!granted) {
      OutlinedButton(
        onClick = onGrantClick,
        shape = CutCornerShape(2.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DvexNeonRedBright)
      ) {
        Text("GRANT", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      }
    }
  }
}

@Composable
private fun OperationalStatusRow(
  title: String,
  subtitle: String,
  active: Boolean
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(CutCornerShape(4.dp))
      .background(DvexSurfaceDark.copy(alpha = 0.25f))
      .border(0.5.dp, if (active) DvexSuccess.copy(alpha = 0.3f) else DvexBorderMuted, CutCornerShape(4.dp))
      .padding(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .size(8.dp)
        .clip(CircleShape)
        .background(if (active) DvexSuccess else DvexTextMuted)
    )
    Spacer(modifier = Modifier.width(8.dp))
    Column {
      Text(
        text = title,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        color = DvexTextPrimary
      )
      Text(
        text = subtitle,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = if (active) DvexSuccess else DvexTextMuted
      )
    }
  }
}

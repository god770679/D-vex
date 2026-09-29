package com.example.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.mode.VisionRequestGate
import com.example.model.DvexSettings
import com.example.permissions.DvexPermissionManager
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedGlow
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextSecondary
import com.example.vision.DvexVisionManager

/**
 * DUAL MODE SYSTEM: Power Mode button cluster (NEW, additive panel).
 *
 * A separate tactical panel — it does NOT modify the internal layout architecture
 * of DvexHud — that surfaces EXISTING D-VEX functionality through quick hexagonal
 * controls in the black/red cyber style:
 *
 *  - ORB     : enable/disable DvexFloatingOrbService via the existing settings
 *              plumbing (the service internals are untouched).
 *  - RECENT  : opens the on-screen recent-apps overlay via [onRecentAppsRequested]
 *              (real UsageStatsManager data rendered by RecentAppsOverlay). When no
 *              handler is provided it falls back to routing the EXISTING voice
 *              pipeline (IntentDetector.GetRecentApps -> DvexToolRouter
 *              .executeGetRecentApps, the real Bug 5 usage-stats implementation).
 *  - NOTIFY  : routes "open notifications" through the EXISTING pipeline
 *              (DvexIntent.OpenNotifications -> DeviceControlRepository via the
 *              accessibility service; DvexNotificationListenerService continues to
 *              feed the existing System Alerts panel).
 *  - DEVICE  : expands to HOME / BACK / RECENTS / NOTIFS — the existing
 *              DeviceControlRepository navigation actions via the existing router.
 *  - VISION  : explicit camera toggle. OFF by default and NEVER auto-started:
 *              Power Mode activation does not touch it; only this button does
 *              (VisionRequestGate + the existing public DvexVisionManager
 *              startCamera/stopCamera APIs — manager internals are untouched).
 *
 * Behaviour:
 *  - Active when Power Mode is ON.
 *  - Dimmed and non-interactive when Power Mode is OFF (never interferes with
 *    the existing HUD panels).
 *  - All touch targets are at least 48dp and carry content descriptions.
 *
 * @param onSendCommand routes a command through the existing brain pipeline
 *   (AssistantViewModel.processVoiceCommand) — no duplicated execution logic.
 * @param onOrbToggleRequested flips the existing floatingOrbEnabled setting.
 * @param onVisionToggleRequested optional test/seam hook: when provided, invoked
 *   with the new vision state INSTEAD of the default camera wiring, so unit tests
 *   can verify the VISION tap reaches the start/stop mechanism without a camera.
 * @param onRecentAppsRequested optional hook that opens the on-screen recent-apps
 *   overlay (real usage data); falls back to the voice pipeline when absent.
 */
@Composable
fun PowerModeButtonCluster(
  powerModeEnabled: Boolean,
  settings: DvexSettings,
  onSendCommand: (String) -> Unit,
  onOrbToggleRequested: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  onVisionToggleRequested: ((Boolean) -> Unit)? = null,
  onRecentAppsRequested: (() -> Unit)? = null
) {
  // Explicit-opt-in vision state. Baseline is always OFF (the volatile gate resets
  // on process start); only the VISION button below ever flips it.
  var visionOn by remember { mutableStateOf(VisionRequestGate.isVisionRequested()) }
  var deviceControlsExpanded by remember { mutableStateOf(false) }

  val clusterEnabled = powerModeEnabled
  val clusterAlpha = if (clusterEnabled) 1f else 0.35f

  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val visionManager = remember { DvexVisionManager.getInstance(context) }

  var cameraPermissionGranted by remember {
    mutableStateOf(DvexPermissionManager.hasCameraPermission(context))
  }
  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    cameraPermissionGranted = isGranted
    if (!isGranted) {
      // Keep gate and state honest if the user denies the permission prompt.
      VisionRequestGate.resetForTesting()
      visionOn = false
    }
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .alpha(clusterAlpha)
      .semantics {
        contentDescription = if (clusterEnabled) {
          "Power Mode control cluster, active"
        } else {
          "Power Mode control cluster, disabled while Standard Mode is active"
        }
      }
  ) {
    TacticalPanel(
      title = "POWER GRID",
      headerTag = if (clusterEnabled) "ACTIVE" else "STANDBY"
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // Honeycomb row 1: ORB / RECENT / NOTIFY
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly
        ) {
          PowerHexButton(
            label = "ORB",
            icon = Icons.Filled.Adjust,
            active = clusterEnabled && settings.floatingOrbEnabled,
            enabled = clusterEnabled,
            testTag = "power_btn_orb",
            description = "Power Mode: toggle floating orb service",
            onClick = { onOrbToggleRequested(!settings.floatingOrbEnabled) }
          )
          PowerHexButton(
            label = "RECENT",
            icon = Icons.Filled.History,
            active = false,
            enabled = clusterEnabled,
            testTag = "power_btn_recent",
            description = "Power Mode: show recently used apps",
            onClick = {
              val handler = onRecentAppsRequested
              if (handler != null) {
                handler()
              } else {
                onSendCommand("recent apps")
              }
            }
          )
          PowerHexButton(
            label = "NOTIFY",
            icon = Icons.Filled.NotificationsActive,
            active = false,
            enabled = clusterEnabled,
            testTag = "power_btn_notify",
            description = "Power Mode: open notification shade",
            onClick = { onSendCommand("open notifications") }
          )
        }

        // Honeycomb row 2: DEVICE / VISION
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly
        ) {
          PowerHexButton(
            label = "DEVICE",
            icon = Icons.Filled.Widgets,
            active = clusterEnabled && deviceControlsExpanded,
            enabled = clusterEnabled,
            testTag = "power_btn_device",
            description = "Power Mode: expand device controls, home back recents notifications",
            onClick = { deviceControlsExpanded = !deviceControlsExpanded }
          )
          PowerHexButton(
            label = "VISION",
            icon = Icons.Filled.Videocam,
            active = clusterEnabled && visionOn,
            enabled = clusterEnabled,
            testTag = "power_btn_vision",
            description = if (visionOn) {
              "Power Mode: vision camera is on, tap to stop"
            } else {
              "Power Mode: vision camera is off, tap to start"
            },
            onClick = {
              val newState = VisionRequestGate.toggleVisionRequest()
              visionOn = newState
              if (onVisionToggleRequested != null) {
                onVisionToggleRequested(newState)
              }
            }
          )
        }

        // Expanded device controls: HOME / BACK / RECENTS / NOTIFS (existing actions).
        if (deviceControlsExpanded && clusterEnabled) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
          ) {
            PowerMiniButton(
              label = "HOME",
              testTag = "power_device_home",
              description = "Power Mode: go to home screen",
              onClick = { onSendCommand("go home") }
            )
            PowerMiniButton(
              label = "BACK",
              testTag = "power_device_back",
              description = "Power Mode: navigate back",
              onClick = { onSendCommand("go back") }
            )
            PowerMiniButton(
              label = "RECENTS",
              testTag = "power_device_recents",
              description = "Power Mode: open recent apps switcher",
              onClick = { onSendCommand("open recents") }
            )
            PowerMiniButton(
              label = "NOTIFS",
              testTag = "power_device_notifications",
              description = "Power Mode: open notification shade",
              onClick = { onSendCommand("open notifications") }
            )
          }
        }

        // Explicit vision preview: bound ONLY when the user tapped VISION.
        // Uses the existing public DvexVisionManager API — no internal changes.
        if (clusterEnabled && visionOn) {
          if (!cameraPermissionGranted) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(DvexBlack)
                .border(1.dp, DvexBorderRed, CutCornerShape(4.dp))
                .clickable { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center
            ) {
              Text(
                text = "CAMERA PERMISSION REQUIRED",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = DvexNeonRedBright
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "TAP TO GRANT OPTICAL ACCESS",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = DvexTextMuted
              )
            }
          } else {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(DvexBlack)
                .border(1.dp, DvexBorderRed, CutCornerShape(4.dp))
            ) {
              AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                  PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                  }
                },
                update = { previewView ->
                  visionManager.startCamera(lifecycleOwner, previewView)
                }
              )
              DisposableEffect(visionOn) {
                onDispose { visionManager.stopCamera() }
              }
              Text(
                text = "[ VISION ACTIVE // OPTICAL ]",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = DvexNeonRedBright,
                modifier = Modifier
                  .align(Alignment.TopStart)
                  .padding(6.dp)
              )
            }
          }
        }
      }
    }
  }
}

/**
 * One hexagonal cyber button in the cluster. Thin borders, subtle red glow when
 * active, minimum 48dp interactive size, accessibility description.
 */
@Composable
private fun PowerHexButton(
  label: String,
  icon: ImageVector,
  active: Boolean,
  enabled: Boolean,
  testTag: String,
  description: String,
  onClick: () -> Unit
) {
  val borderColor = when {
    active -> DvexNeonRedBright
    enabled -> DvexBorderRed
    else -> DvexBorderMuted
  }
  val contentColor = when {
    active -> DvexNeonRedBright
    enabled -> DvexTextSecondary
    else -> DvexTextMuted
  }
  val hexShape = CutCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp)

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      modifier = Modifier
        .size(64.dp)
        .testTag(testTag)
        .clip(hexShape)
        .background(if (active) DvexSurfaceCard else DvexBlack.copy(alpha = 0.7f))
        .border(1.dp, borderColor, hexShape)
        .drawBehind {
          if (active) {
            drawRect(
              brush = Brush.radialGradient(
                colors = listOf(DvexNeonRedGlow, Color.Transparent),
                radius = size.maxDimension
              )
            )
          }
        }
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = description }
        .padding(10.dp),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(22.dp)
      )
    }
    Spacer(modifier = Modifier.height(3.dp))
    Text(
      text = label,
      fontFamily = FontFamily.Monospace,
      fontSize = 8.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 1.sp,
      color = if (active) DvexNeonRedBright else contentColor
    )
  }
}

/**
 * Compact device-control chip (HOME / BACK / RECENTS / NOTIFS) — 48dp minimum.
 */
@Composable
private fun PowerMiniButton(
  label: String,
  testTag: String,
  description: String,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
      .testTag(testTag)
      .clip(CutCornerShape(3.dp))
      .background(DvexSurfaceCard)
      .border(1.dp, DvexBorderRed, CutCornerShape(3.dp))
      .clickable(onClick = onClick)
      .semantics { contentDescription = description }
      .padding(horizontal = 10.dp, vertical = 6.dp)
  ) {
    Text(
      text = label,
      fontFamily = FontFamily.Monospace,
      fontSize = 8.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 1.sp,
      color = DvexTextSecondary
    )
  }
}

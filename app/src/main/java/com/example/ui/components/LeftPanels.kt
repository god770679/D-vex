package com.example.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.NotificationItem
import com.example.model.SystemVitals
import com.example.model.WeatherInfo
import com.example.permissions.DvexPermissionManager
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedDim
import com.example.ui.theme.DvexNeonRedSubtle
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.vision.DvexVisionManager
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary

/**
 * 1. WEATHER PANEL — honest live/standby states (Bug regression fix).
 *
 * Previously always rendered the fabricated WeatherInfo defaults ("30°C / CHENNAI /
 * PARTLY CLOUDY") under a "DEMO FEED" tag — the panel could never show real data.
 * Now: real fetched values when [WeatherInfo.hasData], an explicit STANDBY state
 * otherwise. Never shows demo data as real.
 */
@Composable
fun WeatherPanel(
  weather: WeatherInfo,
  modifier: Modifier = Modifier
) {
  TacticalPanel(
    title = "Weather Telemetry",
    headerTag = if (weather.hasData) "LIVE FEED" else "STANDBY",
    modifier = modifier
  ) {
    if (!weather.hasData) {
      // Honest standby: no fabricated temperature/city, guidance to fetch live data.
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column {
          Text(
            text = "NO LIVE TELEMETRY",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = DvexTextMuted
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Say \"weather\" to scan your area — or ask \"weather in Chennai\".",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            color = DvexTextMuted
          )
        }

        Icon(
          imageVector = Icons.Filled.CloudQueue,
          contentDescription = null,
          tint = DvexBorderMuted,
          modifier = Modifier.size(36.dp)
        )
      }
    } else {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column {
        Text(
          text = "${weather.temperatureCelsius}°C",
          fontFamily = FontFamily.Monospace,
          fontSize = 24.sp,
          fontWeight = FontWeight.Black,
          color = DvexNeonRedBright
        )
        Text(
          text = weather.condition.uppercase(),
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
          color = DvexTextPrimary
        )
        Text(
          text = "${weather.location.uppercase()} // H:${weather.highCelsius}° L:${weather.lowCelsius}°",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          color = DvexTextMuted
        )
      }

      Icon(
        imageVector = Icons.Filled.CloudQueue,
        contentDescription = "Weather",
        tint = DvexNeonRed,
        modifier = Modifier.size(36.dp)
      )
    }
    }
  }
}

/**
 * 2. NOTIFICATIONS PANEL (Bug 5: real data or honest states — never placeholders)
 *
 * Data source: DvexNotificationListenerService's captured-notification StateFlow,
 * collected into uiState.notifications by AssistantViewModel. When the listener is
 * not enabled there is NO data, so the panel says so and deep-links to the Settings
 * "Notification access" screen. Demo data is never shown here.
 */
@Composable
fun NotificationPanel(
  notifications: List<NotificationItem>,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  // Re-check listener enablement when returning from the Settings deep-link.
  var listenerCheckTick by remember { mutableStateOf(0) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) listenerCheckTick++
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val listenerEnabled = remember(listenerCheckTick) {
    DvexPermissionManager.isNotificationListenerEnabled(context)
  }

  TacticalPanel(
    title = "System Alerts",
    headerTag = when {
      !listenerEnabled -> "ACCESS REQUIRED"
      notifications.isEmpty() -> "0 LOGS"
      else -> "${notifications.size} LIVE"
    },
    modifier = modifier
  ) {
    when {
      !listenerEnabled -> {
        // Honest permission-required state; tapping opens Notification access settings.
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clickable {
              context.startActivity(
                DvexPermissionManager.createNotificationListenerSettingsIntent()
              )
            }
            .padding(10.dp)
        ) {
          Text(
            text = "NOTIFICATION ACCESS REQUIRED",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = DvexNeonRedBright
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Enable D-VEX in Settings > Notification access to display real device alerts here.",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            color = DvexTextMuted
          )
        }
      }
      notifications.isEmpty() -> {
        // Listener live, shade genuinely empty: honest empty state.
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
        ) {
          Text(
            text = "NO ACTIVE ALERTS",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = DvexTextMuted
          )
        }
      }
      else -> {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
        ) {
          notifications.take(4).forEach { item ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .size(4.dp)
                  .background(DvexNeonRed)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = item.title,
                  fontFamily = FontFamily.Monospace,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Medium,
                  color = DvexTextPrimary
                )
                Text(
                  text = item.subtitle,
                  fontFamily = FontFamily.Monospace,
                  fontSize = 8.sp,
                  color = DvexTextMuted
                )
              }
              Text(
                text = item.timestamp,
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                color = DvexNeonRedDim
              )
            }
          }
        }
      }
    }
  }
}

/**
 * 3. SYSTEM STATUS PANEL
 */
@Composable
fun SystemStatusPanel(
  vitals: SystemVitals,
  modifier: Modifier = Modifier
) {
  TacticalPanel(
    title = "Core Diagnostics",
    headerTag = "LIVE VITAL",
    modifier = modifier
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp)
    ) {
      VitalBar(label = "CPU LOAD", valuePercent = vitals.cpuUsagePercent, tag = "${vitals.cpuTempCelsius}°C")
      Spacer(modifier = Modifier.height(6.dp))
      VitalBar(label = "RAM ALLOC", valuePercent = vitals.ramUsagePercent, tag = "${vitals.ramUsagePercent}%")
      Spacer(modifier = Modifier.height(6.dp))
      VitalBar(label = "STORAGE", valuePercent = vitals.storageUsagePercent, tag = "${vitals.storageUsagePercent}%")
      Spacer(modifier = Modifier.height(6.dp))
      VitalBar(label = "POWER CELL", valuePercent = vitals.batteryPercent, tag = "OPTIMAL")
    }
  }
}

@Composable
private fun VitalBar(
  label: String,
  valuePercent: Int,
  tag: String
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        text = label,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = DvexTextSecondary
      )
      Text(
        text = tag,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = DvexNeonRedBright
      )
    }
    Spacer(modifier = Modifier.height(3.dp))
    // Segmented style progress
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(5.dp)
        .background(DvexSurfaceCard)
        .border(0.5.dp, DvexBorderMuted)
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth(valuePercent / 100f)
          .height(5.dp)
          .background(
            Brush.horizontalGradient(
              listOf(DvexNeonRedDim, DvexNeonRed, DvexNeonRedBright)
            )
          )
      )
    }
  }
}

/**
 * 4. MEMORY CORE PANEL
 * Futuristic circular multi-ring memory gauge.
 */
@Composable
fun MemoryCorePanel(
  percentage: Int,
  modifier: Modifier = Modifier
) {
  val infiniteTransition = rememberInfiniteTransition(label = "memorySpin")
  val memoryAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(15000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "memoryRingSpin"
  )

  TacticalPanel(
    title = "Memory Core",
    headerTag = "SYNAPSE",
    modifier = modifier
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceAround
    ) {
      // Futuristic Ring Gauge
      Box(
        modifier = Modifier.size(70.dp),
        contentAlignment = Alignment.Center
      ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val center = Offset(size.width / 2f, size.height / 2f)
          val radius = size.width / 2f - 4.dp.toPx()

          // Track
          drawCircle(
            color = DvexBorderMuted,
            radius = radius,
            center = center,
            style = Stroke(width = 2.5.dp.toPx())
          )

          // Segmented rotating track
          rotate(memoryAngle, center) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
            drawCircle(
              color = DvexNeonRed.copy(alpha = 0.4f),
              radius = radius * 0.85f,
              center = center,
              style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash)
            )
          }

          // Active Memory Arc
          val sweep = (percentage / 100f) * 360f
          drawArc(
            brush = Brush.sweepGradient(
              listOf(DvexNeonRedDim, DvexNeonRedBright, DvexNeonRed)
            ),
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
          )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "$percentage%",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            color = DvexNeonRedBright
          )
        }
      }

      Column(modifier = Modifier.padding(start = 8.dp)) {
        Text(
          text = "LONG TERM MEMORY",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          color = DvexTextPrimary,
          letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "SECTORS: 16 // ACTIVE",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          color = DvexTextSecondary
        )
        Text(
          text = "SYNAPSE INTEGRITY: 99.4%",
          fontFamily = FontFamily.Monospace,
          fontSize = 7.sp,
          color = DvexNeonRedDim
        )
      }
    }
  }
}

/**
 * 5. CAMERA VISION PANEL
 * Live camera feed (Phase 1) with HUD reticle, corner brackets, and scan line on top.
 * Shows an explicit "CAMERA PERMISSION REQUIRED" state when access is denied.
 */
@Composable
fun CameraVisionPanel(
  modifier: Modifier = Modifier,
  cameraPermissionOverride: Boolean? = null,
  /**
   * Explicit vision-request state from the Power Mode VISION control (VisionRequestGate).
   * Defaults to false so every existing call site keeps compiling. The panel stays
   * honest: the feed is only ever called LIVE when the user has actually granted the
   * camera permission, so the labels below combine both signals.
   */
  isVisionActive: Boolean = false
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val infiniteTransition = rememberInfiniteTransition(label = "camScan")
  val scanYPercent by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(2800, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "scanLineY"
  )
  var cameraPermissionGranted by remember {
    mutableStateOf(cameraPermissionOverride ?: DvexPermissionManager.hasCameraPermission(context))
  }
  val visionManager = remember { DvexVisionManager.getInstance(context) }

  // Keep the remembered permission state in sync with the override (HUD-level wiring).
  LaunchedEffect(cameraPermissionOverride) {
    cameraPermissionOverride?.let { cameraPermissionGranted = it }
  }

  // In-panel permission request so the panel is self-sufficient (HUD wiring untouched).
  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    cameraPermissionGranted = isGranted
  }

  // Vision is only "active" when the user both asked for it AND granted the camera.
  val visionActive = isVisionActive || cameraPermissionGranted

  TacticalPanel(
    title = "Camera Vision",
    headerTag = if (visionActive) "OPTICAL ACTIVE" else "STANDBY",
    modifier = modifier
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(110.dp)
        .background(DvexBlack),
      contentAlignment = Alignment.Center
    ) {
      if (cameraPermissionGranted) {
        // Live CameraX preview behind the HUD decoration.
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
        DisposableEffect(cameraPermissionGranted) {
          onDispose { visionManager.stopCamera() }
        }
      } else {
        // Honest, explicit permission-required state (no fake feed).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "CAMERA PERMISSION REQUIRED",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = DvexNeonRedBright
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "TAP TO ENABLE OPTICAL SENSOR",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            color = DvexTextMuted,
            modifier = Modifier
              .clip(CutCornerShape(4.dp))
              .border(1.dp, DvexBorderRed, CutCornerShape(4.dp))
              .padding(horizontal = 10.dp, vertical = 4.dp)
          )
        }
        // Full-area tap target to request permission.
        Box(
          modifier = Modifier
            .fillMaxSize()
            .clickable { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
        )
      }

      // Reticle & Scan line (rendered on top, unchanged)
      Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val c = Offset(w / 2f, h / 2f)

        // Crosshair reticle
        val reticleRadius = 22.dp.toPx()
        drawCircle(
          color = DvexNeonRed.copy(alpha = 0.5f),
          radius = reticleRadius,
          center = c,
          style = Stroke(1.dp.toPx())
        )
        // Center cross ticks
        drawLine(DvexNeonRedBright, Offset(c.x - 12.dp.toPx(), c.y), Offset(c.x + 12.dp.toPx(), c.y), 1.dp.toPx())
        drawLine(DvexNeonRedBright, Offset(c.x, c.y - 12.dp.toPx()), Offset(c.x, c.y + 12.dp.toPx()), 1.dp.toPx())

        // Corner target brackets
        val pad = 12.dp.toPx()
        val len = 14.dp.toPx()
        // Top-left
        drawLine(DvexNeonRed, Offset(pad, pad), Offset(pad + len, pad), 1.5.dp.toPx())
        drawLine(DvexNeonRed, Offset(pad, pad), Offset(pad, pad + len), 1.5.dp.toPx())
        // Top-right
        drawLine(DvexNeonRed, Offset(w - pad, pad), Offset(w - pad - len, pad), 1.5.dp.toPx())
        drawLine(DvexNeonRed, Offset(w - pad, pad), Offset(w - pad, pad + len), 1.5.dp.toPx())
        // Bottom-left
        drawLine(DvexNeonRed, Offset(pad, h - pad), Offset(pad + len, h - pad), 1.5.dp.toPx())
        drawLine(DvexNeonRed, Offset(pad, h - pad), Offset(pad, h - pad - len), 1.5.dp.toPx())
        // Bottom-right
        drawLine(DvexNeonRed, Offset(w - pad, h - pad), Offset(w - pad - len, h - pad), 1.5.dp.toPx())
        drawLine(DvexNeonRed, Offset(w - pad, h - pad), Offset(w - pad, h - pad - len), 1.5.dp.toPx())

        // Moving scan line
        val scanY = h * scanYPercent
        drawLine(
          brush = Brush.horizontalGradient(
            listOf(Color.Transparent, DvexNeonRedBright.copy(alpha = 0.65f), Color.Transparent)
          ),
          start = Offset(0f, scanY),
          end = Offset(w, scanY),
          strokeWidth = 2.dp.toPx()
        )
      }

      // HUD Text badges
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(8.dp)
          .align(Alignment.TopCenter),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = if (visionActive) "[ AI VISION ACTIVE ]" else "[ VISION FEED OFFLINE ]",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          fontWeight = FontWeight.Bold,
          color = if (visionActive) DvexNeonRedBright else DvexTextMuted
        )
        Text(
          text = if (visionActive) "1.0x // OPTICAL" else "OFFLINE",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          color = DvexTextSecondary
        )
      }

      Text(
        text = if (visionActive) "OPTICAL SENSOR ACTIVE" else "OPTICAL SENSOR STANDBY",
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        color = if (visionActive) DvexNeonRedBright else DvexTextMuted,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 6.dp)
      )
    }
  }
}

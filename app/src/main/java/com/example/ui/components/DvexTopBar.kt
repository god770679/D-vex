package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mode.PowerModeManager
import com.example.model.DvexUiState
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedGlow
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DvexTopBar(
  uiState: DvexUiState,
  modifier: Modifier = Modifier,
  isCompact: Boolean = false,
  onModeSelectorRequested: (() -> Unit)? = null,
  /** Injectable clock (tests); production reads the authoritative device time. */
  timeSource: () -> Long = { System.currentTimeMillis() }
) {
  // LIVE DEVICE CLOCK (bug fix): the header previously rendered the hardcoded
  // "08:45 PM  //  22 AUG 2026" forever. It now re-reads the system clock every
  // second, so time, date, day and rollover are always the real local values in
  // the device's configured timezone and locale — never a stale startup value.
  var nowMs by remember { mutableStateOf(timeSource()) }
  LaunchedEffect(Unit) {
    while (true) {
      nowMs = timeSource()
      delay(1_000L)
    }
  }
  val timeText = remember(nowMs) {
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(nowMs)).uppercase(Locale.getDefault())
  }
  val dateText = remember(nowMs) {
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(nowMs)).uppercase(Locale.getDefault())
  }

  // Real telemetry labels: honest "--" until the system source delivers a reading,
  // never an invented network, battery or status string.
  val networkLabel = uiState.systemStatus.networkLabel ?: "--"
  val batteryLabel = uiState.systemStatus.batteryPercent?.let { "$it%" } ?: "--"
  val systemStatusLabel = uiState.systemStatus.thermalStatus ?: "--"
  val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
  val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 0.35f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(1200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "statusDotAlpha"
  )

  Column(
    modifier = modifier
      .fillMaxWidth()
      // Adaptive cap: on short landscape phones the header must not eat the
      // viewport height that the centred reactor needs (no content change).
      .heightIn(max = 56.dp)
      .background(DvexSurfaceDark)
      .border(1.dp, DvexBorderMuted, CutCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
      .padding(horizontal = 12.dp, vertical = 6.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // LEFT: Brand identity
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .border(1.dp, DvexBorderRed, CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
          .background(DvexBlack.copy(alpha = 0.6f))
          .padding(horizontal = 8.dp, vertical = 4.dp)
      ) {
        Box(
          modifier = Modifier
            .size(6.dp, 24.dp)
            .background(DvexNeonRed)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text(
            text = "D-VEX",
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
            color = DvexNeonRedBright
          )
          Text(
            text = "AI ASSISTANT",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = DvexTextSecondary
          )
        }
      }

      // DUAL MODE: Standard / Power indicator chip (additive control in the
      // existing header; no header redesign). Tapping opens the real D-VEX MODE
      // selector popup — it never silently flips the mode on its own.
      DvexPowerModeToggle(onClick = onModeSelectorRequested)

      // CENTER: Time, Date, System Online
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .border(1.dp, DvexBorderMuted, CutCornerShape(4.dp))
          .background(DvexBlack.copy(alpha = 0.5f))
          .padding(horizontal = 14.dp, vertical = 3.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(6.dp)
              .clip(CircleShape)
              .background(DvexNeonRed)
              .alpha(pulseAlpha)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "SYSTEM ONLINE",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp,
            color = DvexNeonRedBright
          )
        }
        Text(
          text = "$timeText  //  $dateText",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.Medium,
          color = DvexTextPrimary,
          letterSpacing = 1.2.sp
        )
      }

      // RIGHT: Battery, Network, System Status
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .border(1.dp, DvexBorderRed, CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp))
          .background(DvexBlack.copy(alpha = 0.6f))
          .padding(horizontal = 8.dp, vertical = 4.dp)
      ) {
        if (!isCompact) {
          // Network
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Filled.Wifi,
              contentDescription = "Network",
              tint = DvexNeonRed,
              modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column {
              Text(
                text = "NETWORK",
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                color = DvexTextMuted
              )
              Text(
                text = networkLabel,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = DvexTextSecondary
              )
            }
          }

          Spacer(modifier = Modifier.width(10.dp))
          Box(
            modifier = Modifier
              .width(1.dp)
              .height(18.dp)
              .background(DvexBorderMuted)
          )
          Spacer(modifier = Modifier.width(10.dp))

          // Battery
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Filled.BatteryChargingFull,
              contentDescription = "Battery",
              tint = DvexNeonRed,
              modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column {
              Text(
                text = "BATTERY",
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                color = DvexTextMuted
              )
              Text(
                text = batteryLabel,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = DvexNeonRedBright
              )
            }
          }

          Spacer(modifier = Modifier.width(10.dp))
          Box(
            modifier = Modifier
              .width(1.dp)
              .height(18.dp)
              .background(DvexBorderMuted)
          )
          Spacer(modifier = Modifier.width(10.dp))
        }

        // System Status
        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = "SYSTEM STATUS",
            fontFamily = FontFamily.Monospace,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            color = DvexTextMuted
          )
          Text(
            text = systemStatusLabel,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = DvexNeonRedBright,
            letterSpacing = 1.sp
          )
        }
      }
    }
  }
}

/**
 * DUAL MODE SYSTEM: Standard / Power header chip (additive).
 *
 * Compact chip in the EXISTING DvexTopBar row — the header layout itself is
 * untouched. Follows the existing D-VEX black/red cyber style: monospace type,
 * thin cut-corner borders, subtle red glow. Target is at least 48dp for touch.
 *
 * State source of truth: PowerModeManager's persisted StateFlow (default OFF =
 * Standard). Tapping invokes [onClick] (the HUD opens the D-VEX MODE selector);
 * this chip NEVER flips the mode silently.
 */
@Composable
fun DvexPowerModeToggle(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null
) {
  val context = LocalContext.current
  val powerModeManager = remember { PowerModeManager.getInstance(context) }
  val powerEnabled by powerModeManager.powerModeEnabled.collectAsState()

  val chipLabel = if (powerEnabled) "POWER" else "STANDARD"
  val stateColor = if (powerEnabled) DvexNeonRedBright else DvexTextSecondary
  val chipDescription = if (powerEnabled) {
    "Power Mode active. Tap to open the D-VEX mode selector."
  } else {
    "Standard Mode active. Tap to open the D-VEX mode selector."
  }

  Box(
    modifier = modifier
      .minimumInteractiveComponentSize()
      .clip(CutCornerShape(4.dp))
      .background(DvexBlack.copy(alpha = 0.6f))
      .border(1.dp, DvexBorderRed, CutCornerShape(4.dp))
      .drawBehind {
        if (powerEnabled) {
          drawRect(
            brush = Brush.radialGradient(
              colors = listOf(DvexNeonRedGlow, Color.Transparent),
              radius = size.maxDimension
            )
          )
        }
      }
      .clickable(enabled = onClick != null) { onClick?.invoke() }
      .semantics { contentDescription = chipDescription }
      .padding(horizontal = 8.dp, vertical = 6.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(5.dp)
          .clip(CircleShape)
          .background(if (powerEnabled) DvexNeonRedBright else DvexTextMuted)
          .alpha(if (powerEnabled) 1f else 0.6f)
      )
      Spacer(modifier = Modifier.width(5.dp))
      Text(
        text = chipLabel,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.2.sp,
        color = stateColor
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = "MODE",
        fontFamily = FontFamily.Monospace,
        fontSize = 7.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = DvexTextMuted
      )
    }
  }
}

package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ActivityItem
import com.example.model.LocationInfo
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedDim
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 1. CURRENT TIME PANEL — live device clock (Bug regression fix).
 *
 * Previously rendered hardcoded "08:45 PM" / "SATURDAY" / "22 AUG 2026" strings —
 * the panel looked permanently stuck. Now a one-second coroutine ticker re-reads
 * System.currentTimeMillis() every second so the displayed time is always live.
 */
@Composable
fun TimePanel(
  modifier: Modifier = Modifier,
  timeSource: () -> Long = { System.currentTimeMillis() }
) {
  var nowMs by remember { mutableStateOf(timeSource()) }

  // Live tick: refresh every second so the clock visibly advances.
  LaunchedEffect(Unit) {
    while (true) {
      nowMs = timeSource()
      delay(1_000L)
    }
  }

  val calendar = remember(nowMs) { Calendar.getInstance().apply { timeInMillis = nowMs } }
  val timeText = remember(nowMs) {
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(nowMs)).uppercase(Locale.getDefault())
  }
  val dayText = remember(nowMs) {
    SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(nowMs)).uppercase(Locale.getDefault())
  }
  val dateText = remember(nowMs) {
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(nowMs)).uppercase(Locale.getDefault())
  }
  val tzOffsetLabel = remember(nowMs) {
    val offsetMinutes = calendar.get(Calendar.ZONE_OFFSET) / 60_000
    val sign = if (offsetMinutes >= 0) "+" else "-"
    val absMin = abs(offsetMinutes)
    "UTC${sign}%02d:%02d".format(absMin / 60, absMin % 60)
  }

  TacticalPanel(
    title = "Current Time",
    headerTag = "CHRONO // $tzOffsetLabel // LIVE",
    modifier = modifier
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
      ) {
        Text(
          text = timeText,
          fontFamily = FontFamily.Monospace,
          fontSize = 24.sp,
          fontWeight = FontWeight.Black,
          color = DvexNeonRedBright,
          letterSpacing = 1.sp
        )
        Text(
          text = dayText,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          color = DvexTextSecondary
        )
      }
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "$dateText // EPOCH SYNC: ${nowMs / 1000L}",
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        color = DvexTextMuted,
        letterSpacing = 0.8.sp
      )
    }
  }
}

/**
 * 2. LOCATION PANEL — honest GPS state (Bug regression fix).
 *
 * Previously showed fabricated "CHENNAI / 13.0827° N / GPS LOCK: 9 SATS / +/- 2M"
 * regardless of the real fix. Now renders real device fix data when available and
 * an explicit STANDBY state when no fix exists — never fake coordinates.
 */
@Composable
fun LocationPanel(
  location: LocationInfo,
  modifier: Modifier = Modifier
) {
  TacticalPanel(
    title = "Geo Coordinates",
    headerTag = if (location.hasData) "GPS LOCK LIVE" else "NO GPS FIX",
    modifier = modifier
  ) {
    if (!location.hasData) {
      // Honest standby: no fabricated city/coords, guidance to trigger a fix.
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(10.dp)
      ) {
        Text(
          text = "NO DEVICE FIX",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          color = DvexTextMuted,
          letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Coordinates appear after a real GPS fix. Say \"weather\" to trigger a location scan.",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          color = DvexTextMuted
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
          text = (location.city.ifBlank { "UNKNOWN AREA" }).uppercase(),
          fontFamily = FontFamily.Monospace,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = DvexNeonRedBright,
          letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "${location.latitude} // ${location.longitude}",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          color = DvexTextPrimary,
          letterSpacing = 0.5.sp
        )
        Text(
          text = buildString {
            append("ALT: ")
            append(location.altitudeMeters?.let { "${it}M" } ?: "N/A")
            append(" // ACCURACY: ")
            append(location.accuracyMeters?.let { "+/- ${it}M" } ?: "N/A")
          },
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          color = DvexTextMuted
        )
      }

      Icon(
        imageVector = Icons.Filled.Navigation,
        contentDescription = "Coordinates",
        tint = DvexNeonRed,
        modifier = Modifier.size(28.dp)
      )
    }
    }
  }
}

/**
 * 3. QUICK ACCESS PANEL (Grid of Tactical Buttons)
 */
@Composable
fun QuickAccessPanel(
  onActionSelected: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  TacticalPanel(
    title = "Quick Access",
    headerTag = "GRID 2X4",
    modifier = modifier
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
    ) {
      // Row 1: Camera, Music, YouTube, Maps
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        TacticalButton(
          text = "Camera",
          icon = Icons.Filled.CameraAlt,
          onClick = { onActionSelected("Camera") },
          modifier = Modifier.weight(1f)
        )
        TacticalButton(
          text = "Music",
          icon = Icons.Filled.MusicNote,
          onClick = { onActionSelected("Music") },
          modifier = Modifier.weight(1f)
        )
        TacticalButton(
          text = "YouTube",
          icon = Icons.Filled.PlayArrow,
          onClick = { onActionSelected("YouTube") },
          modifier = Modifier.weight(1f)
        )
        TacticalButton(
          text = "Maps",
          icon = Icons.Filled.Map,
          onClick = { onActionSelected("Maps") },
          modifier = Modifier.weight(1f)
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Row 2: Call, Messages, Settings, More
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        TacticalButton(
          text = "Call",
          icon = Icons.Filled.Call,
          onClick = { onActionSelected("Call") },
          modifier = Modifier.weight(1f)
        )
        TacticalButton(
          text = "Messages",
          icon = Icons.Filled.Sms,
          onClick = { onActionSelected("Messages") },
          modifier = Modifier.weight(1f)
        )
        TacticalButton(
          text = "Settings",
          icon = Icons.Filled.Settings,
          onClick = { onActionSelected("Settings") },
          modifier = Modifier.weight(1f)
        )
        TacticalButton(
          text = "More",
          icon = Icons.Filled.MoreHoriz,
          onClick = { onActionSelected("More") },
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

/**
 * 4. RECENT ACTIVITY PANEL
 */
@Composable
fun RecentActivityPanel(
  activities: List<ActivityItem>,
  modifier: Modifier = Modifier
) {
  TacticalPanel(
    title = "Recent Activity",
    headerTag = "EVENT LOG",
    modifier = modifier
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
    ) {
      activities.take(5).forEach { item ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Status",
            tint = DvexNeonRed,
            modifier = Modifier.size(12.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = item.action,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = DvexTextPrimary,
            modifier = Modifier.weight(1f)
          )
          Text(
            text = item.timestamp,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            color = DvexTextMuted
          )
        }
      }
    }
  }
}

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

/**
 * 1. CURRENT TIME PANEL
 */
@Composable
fun TimePanel(
  modifier: Modifier = Modifier
) {
  TacticalPanel(
    title = "Current Time",
    headerTag = "CHRONO // UTC+5:30",
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
          text = "08:45 PM",
          fontFamily = FontFamily.Monospace,
          fontSize = 24.sp,
          fontWeight = FontWeight.Black,
          color = DvexNeonRedBright,
          letterSpacing = 1.sp
        )
        Text(
          text = "SATURDAY",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          color = DvexTextSecondary
        )
      }
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "22 AUG 2026 // EPOCH SYNC: 1787492100",
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        color = DvexTextMuted,
        letterSpacing = 0.8.sp
      )
    }
  }
}

/**
 * 2. LOCATION PANEL
 */
@Composable
fun LocationPanel(
  location: LocationInfo,
  modifier: Modifier = Modifier
) {
  TacticalPanel(
    title = "Geo Coordinates",
    headerTag = "GPS LOCK: 9 SATS",
    modifier = modifier
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column {
        Text(
          text = location.city.uppercase(),
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
          text = "ALT: ${location.altitudeMeters}M // ACCURACY: +/- 2M",
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

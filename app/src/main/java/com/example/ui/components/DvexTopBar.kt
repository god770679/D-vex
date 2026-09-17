package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun DvexTopBar(
  uiState: DvexUiState,
  modifier: Modifier = Modifier,
  isCompact: Boolean = false
) {
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
          text = "08:45 PM  //  22 AUG 2026",
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
                text = "5G SECURE",
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
                text = "${uiState.systemStatus.batteryPercent}%",
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
            text = "OPTIMAL",
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

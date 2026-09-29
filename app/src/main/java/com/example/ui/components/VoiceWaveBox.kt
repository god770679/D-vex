package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.VoiceState
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexSurfaceDark
import kotlin.math.abs
import kotlin.math.sin

/** True only while the voice pipeline is actually doing something. */
val VoiceState.isVoiceActive: Boolean
  get() = this == VoiceState.LISTENING ||
    this == VoiceState.PROCESSING ||
    this == VoiceState.SPEAKING ||
    this == VoiceState.EXECUTING_ACTION

/**
 * Shared compact animated voice waveform. Reused by the main HUD voice module and
 * the floating [VoiceWaveBox] so there is a single waveform implementation.
 */
@Composable
fun VoiceWaveform(
  modifier: Modifier = Modifier,
  barCount: Int = 16,
  barHeight: Dp = 18.dp,
  color: Color = DvexNeonRed
) {
  val infiniteTransition = rememberInfiniteTransition(label = "voiceWaveform")
  val phase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 6.28f,
    animationSpec = infiniteRepeatable(
      animation = tween(1200),
      repeatMode = RepeatMode.Restart
    ),
    label = "voiceWavePhase"
  )

  Canvas(
    modifier = modifier
      .fillMaxWidth()
      .height(barHeight)
  ) {
    val midY = size.height / 2f
    val step = size.width / barCount
    for (i in 0 until barCount) {
      val x = i * step + step / 2f
      val waveH = (size.height * 0.7f) * abs(sin(i * 0.4f + phase)) + 2.dp.toPx()
      drawLine(
        color = color,
        start = Offset(x, midY - waveH / 2f),
        end = Offset(x, midY + waveH / 2f),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Square
      )
    }
  }
}

/**
 * Compact floating voice-activity box.
 *
 * Deliberately small: it appears only while D-VEX is listening / processing /
 * speaking, stays out of the conversation's way, and disappears when idle.
 */
@Composable
fun VoiceWaveBox(
  voiceState: VoiceState,
  modifier: Modifier = Modifier
) {
  AnimatedVisibility(
    visible = voiceState.isVoiceActive,
    enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.9f),
    exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.9f),
    modifier = modifier
  ) {
    val shape = CutCornerShape(6.dp)
    Row(
      modifier = Modifier
        .clip(shape)
        .background(DvexSurfaceDark)
        .border(1.dp, DvexBorderRed, shape)
        .padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = Icons.Filled.Mic,
        contentDescription = "Voice activity",
        tint = DvexNeonRedBright,
        modifier = Modifier.size(14.dp)
      )
      Spacer(modifier = Modifier.width(8.dp))
      VoiceWaveform(
        modifier = Modifier.width(84.dp),
        barCount = 12,
        barHeight = 14.dp
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = voiceState.label,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = DvexNeonRedBright,
        maxLines = 1
      )
    }
  }
}

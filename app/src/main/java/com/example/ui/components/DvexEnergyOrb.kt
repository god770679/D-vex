package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DvexAssistantState
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedDim
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexWarning
import kotlin.math.cos
import kotlin.math.sin

/**
 * 56dp Circular Miniature D-VEX Energy Reactor Orb for Floating Overlay and HUD indicators.
 */
@Composable
fun DvexEnergyOrb(
  assistantState: DvexAssistantState,
  modifier: Modifier = Modifier,
  orbSize: Dp = 56.dp
) {
  val infiniteTransition = rememberInfiniteTransition(label = "energyOrbTransitions")

  // Rotation speed depending on state
  val rotationDuration = when (assistantState) {
    is DvexAssistantState.Idle -> 9000
    is DvexAssistantState.Standby -> 11000
    is DvexAssistantState.WakeWordListening -> 11000
    is DvexAssistantState.Listening -> 2200
    is DvexAssistantState.Processing -> 1100
    is DvexAssistantState.ExecutingAction -> 750
    is DvexAssistantState.Speaking -> 2500
    is DvexAssistantState.Error -> 900
  }

  // Counter rotation speed for second energy ring
  val counterRotationDuration = when (assistantState) {
    is DvexAssistantState.Idle -> 13000
    is DvexAssistantState.Standby -> 15000
    is DvexAssistantState.WakeWordListening -> 15000
    is DvexAssistantState.Listening -> 3200
    is DvexAssistantState.Processing -> 1600
    is DvexAssistantState.ExecutingAction -> 1000
    is DvexAssistantState.Speaking -> 3400
    is DvexAssistantState.Error -> 1200
  }

  val pulseDuration = when (assistantState) {
    is DvexAssistantState.Listening -> 550
    is DvexAssistantState.Speaking -> 450
    is DvexAssistantState.ExecutingAction -> 350
    is DvexAssistantState.Processing -> 600
    is DvexAssistantState.Error -> 250
    else -> 2200
  }

  val rotationAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(rotationDuration, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "orbRotation"
  )

  val counterRotationAngle by infiniteTransition.animateFloat(
    initialValue = 360f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(
      animation = tween(counterRotationDuration, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "orbCounterRotation"
  )

  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 0.92f,
    targetValue = if (assistantState is DvexAssistantState.Listening || assistantState is DvexAssistantState.Speaking) 1.18f else 1.05f,
    animationSpec = infiniteRepeatable(
      animation = tween(pulseDuration, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "orbPulse"
  )

  val glowAlpha by infiniteTransition.animateFloat(
    initialValue = 0.35f,
    targetValue = if (assistantState is DvexAssistantState.Listening || assistantState is DvexAssistantState.Speaking || assistantState is DvexAssistantState.ExecutingAction) 0.98f else 0.65f,
    animationSpec = infiniteRepeatable(
      animation = tween(pulseDuration, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "orbGlow"
  )

  Box(
    modifier = modifier
      .size(orbSize)
      .clip(CircleShape)
      .background(DvexBlack),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val center = Offset(size.width / 2f, size.height / 2f)
      val radius = (size.width / 2f) - 2.5.dp.toPx()

      val primaryColor = if (assistantState is DvexAssistantState.Error) DvexWarning else DvexNeonRed
      val brightColor = if (assistantState is DvexAssistantState.Error) Color(0xFFFF9999) else DvexNeonRedBright
      val coreColor = if (assistantState is DvexAssistantState.Error) Color(0xFFFFD5D5) else Color.White

      // 1. Radial Energy Glow Halo
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(
            brightColor.copy(alpha = glowAlpha * 0.45f),
            primaryColor.copy(alpha = glowAlpha * 0.20f),
            Color.Transparent
          ),
          center = center,
          radius = radius * 1.35f * pulseScale
        ),
        radius = radius * 1.35f * pulseScale,
        center = center
      )

      // 2. Dark Containment Chamber
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(DvexSurfaceDark, DvexBlack),
          center = center,
          radius = radius
        ),
        radius = radius,
        center = center
      )

      // 3. Ring Layer 1: Outer Segmented Rotating Track (Clockwise)
      rotate(rotationAngle, center) {
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f, 5f, 8f), 0f)
        drawCircle(
          color = primaryColor.copy(alpha = 0.85f),
          radius = radius * 0.90f,
          center = center,
          style = Stroke(width = 1.6.dp.toPx(), pathEffect = dashEffect)
        )

        // 4 Cardinal Energy Index Dots
        for (i in 0 until 4) {
          val rad = Math.toRadians(i * 90.0).toFloat()
          val px = center.x + (radius * 0.90f) * cos(rad)
          val py = center.y + (radius * 0.90f) * sin(rad)
          drawCircle(brightColor, radius = 1.6.dp.toPx(), center = Offset(px, py))
        }

        // Executing plasma arcs accelerating around outer ring
        if (assistantState is DvexAssistantState.ExecutingAction) {
          drawArc(
            color = brightColor,
            startAngle = 30f,
            sweepAngle = 75f,
            useCenter = false,
            topLeft = Offset(center.x - radius * 0.90f, center.y - radius * 0.90f),
            size = Size(radius * 1.80f, radius * 1.80f),
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
          )
        }
      }

      // 4. Ring Layer 2: Middle Counter-Rotating Thin Energy Ring (Counter-Clockwise)
      rotate(counterRotationAngle, center) {
        val midRadius = radius * 0.74f
        val dashEffect2 = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f)
        drawCircle(
          color = brightColor.copy(alpha = 0.70f),
          radius = midRadius,
          center = center,
          style = Stroke(width = 1.2.dp.toPx(), pathEffect = dashEffect2)
        )

        // Processing State: Sweeping scanning arc radar beam
        if (assistantState is DvexAssistantState.Processing) {
          drawArc(
            brush = Brush.sweepGradient(
              colors = listOf(Color.Transparent, brightColor),
              center = center
            ),
            startAngle = 0f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(center.x - midRadius, center.y - midRadius),
            size = Size(midRadius * 2f, midRadius * 2f),
            style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round)
          )
        }
      }

      // 5. Ring Layer 3: Inner Breathing Plasma Ring
      val innerRadius = radius * 0.52f * pulseScale
      drawCircle(
        color = brightColor.copy(alpha = glowAlpha),
        radius = innerRadius,
        center = center,
        style = Stroke(width = 1.4.dp.toPx())
      )

      // Speaking State: Concentric acoustic energy waves expanding
      if (assistantState is DvexAssistantState.Speaking) {
        drawCircle(
          color = brightColor.copy(alpha = 0.40f * (1.25f - pulseScale)),
          radius = innerRadius * 1.25f,
          center = center,
          style = Stroke(width = 1.0.dp.toPx())
        )
      }

      // 6. Central Glowing Reactor Core
      val coreRadius = if (assistantState is DvexAssistantState.Listening) {
        4.8.dp.toPx() * pulseScale
      } else if (assistantState is DvexAssistantState.Speaking) {
        4.2.dp.toPx() * pulseScale
      } else {
        3.2.dp.toPx()
      }

      // Reactor core aura
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(
            coreColor,
            brightColor,
            primaryColor.copy(alpha = 0.4f),
            Color.Transparent
          ),
          center = center,
          radius = coreRadius * 2.2f
        ),
        radius = coreRadius * 2.2f,
        center = center
      )

      // Reactor center nucleus
      drawCircle(
        color = coreColor,
        radius = coreRadius,
        center = center
      )
    }
  }
}

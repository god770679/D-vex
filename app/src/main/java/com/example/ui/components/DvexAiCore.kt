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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AiCoreState
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedDim
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary
import com.example.ui.theme.DvexWarning
import kotlin.math.cos
import kotlin.math.sin

/**
 * Large Central D-VEX AI CORE Energy Reactor.
 *
 * NO HUMAN FACE. NO ROBOT FACE.
 *
 * Continuously animated futuristic energy reactor built with multi-layer concentric rings:
 * 1. Outer ring: Slow, smooth continuous clockwise rotation.
 * 2. Second ring: Continuous counter-clockwise rotation with technical tick indices.
 * 3. Third ring: Segmented neon-red energy arcs with continuous rotation and dynamic acceleration.
 * 4. Inner ring: Bright neon-red energy pulse smoothly expanding and contracting.
 * 5. Center: Glowing red energy nucleus with breathing pulse and D-VEX AI CORE label.
 * 6. Orbiting energy sparks/particles & short electrical arcs around the circumference.
 * 7. Subtle 360-degree radar scanning sweep beam.
 * 8. Synchronized radial waveform / equalizer spectrum pulses on the outer boundary.
 *
 * Fully responsive to AI Core states: IDLE, LISTENING, PROCESSING, RESPONDING (SPEAKING),
 * EXECUTING, ERROR, and OFFLINE.
 */
@Composable
fun DvexAiCore(
  aiState: AiCoreState,
  modifier: Modifier = Modifier,
  onStateChangeRequest: ((AiCoreState) -> Unit)? = null
) {
  val infiniteTransition = rememberInfiniteTransition(label = "dvexCoreReactorTransitions")

  // State-based speed and timing parameters
  val outerDuration = when (aiState) {
    AiCoreState.IDLE -> 18000
    AiCoreState.LISTENING -> 8000
    AiCoreState.THINKING -> 6000
    AiCoreState.PROCESSING -> 4500
    AiCoreState.RESPONDING -> 7000
    AiCoreState.EXECUTING -> 3200
    AiCoreState.ERROR -> 2800
    AiCoreState.OFFLINE -> 40000
  }

  val secondDuration = when (aiState) {
    AiCoreState.IDLE -> 12000
    AiCoreState.LISTENING -> 6500
    AiCoreState.THINKING -> 5000
    AiCoreState.PROCESSING -> 3600
    AiCoreState.RESPONDING -> 5500
    AiCoreState.EXECUTING -> 2400
    AiCoreState.ERROR -> 2000
    AiCoreState.OFFLINE -> 30000
  }

  val thirdArcDuration = when (aiState) {
    AiCoreState.IDLE -> 5000
    AiCoreState.LISTENING -> 2800
    AiCoreState.THINKING -> 2200
    AiCoreState.PROCESSING -> 1600
    AiCoreState.RESPONDING -> 2400
    AiCoreState.EXECUTING -> 1100
    AiCoreState.ERROR -> 1000
    AiCoreState.OFFLINE -> 12000
  }

  val pulseDuration = when (aiState) {
    AiCoreState.IDLE -> 2400
    AiCoreState.LISTENING -> 900
    AiCoreState.THINKING -> 1100
    AiCoreState.PROCESSING -> 800
    AiCoreState.RESPONDING -> 650 // Synchronized with speech rhythm
    AiCoreState.EXECUTING -> 550
    AiCoreState.ERROR -> 380
    AiCoreState.OFFLINE -> 3600
  }

  val scanDuration = when (aiState) {
    AiCoreState.IDLE -> 4500
    AiCoreState.LISTENING -> 2200
    AiCoreState.THINKING -> 1800
    AiCoreState.PROCESSING -> 1300
    AiCoreState.RESPONDING -> 2800
    AiCoreState.EXECUTING -> 1000
    AiCoreState.ERROR -> 900
    AiCoreState.OFFLINE -> 8000
  }

  // 1. Outer Ring: Clockwise rotation
  val outerAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(outerDuration, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "outerRingAngle"
  )

  // 2. Second Ring: Counter-clockwise rotation
  val secondAngle by infiniteTransition.animateFloat(
    initialValue = 360f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(
      animation = tween(secondDuration, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "secondRingAngle"
  )

  // 3. Third Ring: Segmented Energy Arcs with acceleration curve
  val arcAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(thirdArcDuration, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "energyArcAngle"
  )

  // 4. Inner Ring: Smooth energy pulse expansion/contraction
  val innerPulseScale by infiniteTransition.animateFloat(
    initialValue = if (aiState == AiCoreState.OFFLINE) 0.94f else 0.92f,
    targetValue = if (aiState == AiCoreState.OFFLINE) 0.96f else 1.08f,
    animationSpec = infiniteRepeatable(
      animation = tween(pulseDuration, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "innerPulseScale"
  )

  // 5. Center Nucleus Breathing Pulse & Glow Intensity
  val centerGlow by infiniteTransition.animateFloat(
    initialValue = when (aiState) {
      AiCoreState.OFFLINE -> 0.15f
      AiCoreState.IDLE -> 0.38f
      AiCoreState.EXECUTING -> 0.70f
      else -> 0.50f
    },
    targetValue = when (aiState) {
      AiCoreState.OFFLINE -> 0.25f
      AiCoreState.IDLE -> 0.70f
      AiCoreState.EXECUTING -> 1.00f
      AiCoreState.RESPONDING -> 0.95f
      AiCoreState.LISTENING -> 0.90f
      else -> 0.85f
    },
    animationSpec = infiniteRepeatable(
      animation = tween(pulseDuration, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "centerGlowIntensity"
  )

  // 6. Scanning Radar Sweep Angle
  val scanSweepAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(scanDuration, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "scanSweepAngle"
  )

  // 7. Synchronized Waveform Spectrum Pulse Phase
  val eqPhase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 6.283185f,
    animationSpec = infiniteRepeatable(
      animation = tween(
        durationMillis = when (aiState) {
          AiCoreState.RESPONDING -> 650
          AiCoreState.LISTENING -> 850
          AiCoreState.EXECUTING -> 600
          AiCoreState.PROCESSING -> 1100
          else -> 2000
        },
        easing = LinearEasing
      ),
      repeatMode = RepeatMode.Restart
    ),
    label = "eqPhase"
  )

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    // Micro Status Header
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
      modifier = Modifier.padding(bottom = 2.dp)
    ) {
      Box(
        modifier = Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(if (aiState == AiCoreState.ERROR) DvexWarning else DvexNeonRed)
      )
      Spacer(modifier = Modifier.size(6.dp))
      Text(
        text = "D-VEX REACTOR // ${aiState.label}",
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        color = if (aiState == AiCoreState.OFFLINE) DvexTextMuted else DvexNeonRedBright
      )
    }

    // High-Impact Circular Reactor Visual Orb
    Box(
      modifier = Modifier
        .size(268.dp)
        .padding(2.dp),
      contentAlignment = Alignment.Center
    ) {
      Canvas(modifier = Modifier.size(260.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = (size.width / 2f) - 6.dp.toPx()

        val primaryColor = when (aiState) {
          AiCoreState.ERROR -> DvexWarning
          AiCoreState.OFFLINE -> DvexTextMuted
          else -> DvexNeonRed
        }
        val brightColor = when (aiState) {
          AiCoreState.ERROR -> Color(0xFFFF8888)
          AiCoreState.OFFLINE -> Color(0xFF7A7D8A)
          else -> DvexNeonRedBright
        }

        // ==========================================================
        // 1. WAVEFORM SPECTRUM PULSES AROUND OUTER CIRCUMFERENCE (EVENLY SPACED)
        // ==========================================================
        val eqMultiplier = when (aiState) {
          AiCoreState.RESPONDING -> 1.0f  // Synchronized with speaking
          AiCoreState.LISTENING -> 0.90f  // Strong audio listening
          AiCoreState.EXECUTING -> 0.85f  // High energetic ripple
          AiCoreState.PROCESSING -> 0.65f
          AiCoreState.THINKING -> 0.50f
          AiCoreState.ERROR -> 0.70f
          AiCoreState.OFFLINE -> 0.10f
          AiCoreState.IDLE -> 0.32f
        }
        drawRadialWaveform(
          center = center,
          radius = maxRadius * 0.94f,
          eqMultiplier = eqMultiplier,
          eqPhase = eqPhase,
          primaryColor = primaryColor,
          brightColor = brightColor
        )

        // ==========================================================
        // 2. OUTER RING: Continuous Clockwise Rotation (Clean concentric & ticks)
        // ==========================================================
        rotate(outerAngle, center) {
          drawOuterClockwiseRing(
            center = center,
            radius = maxRadius * 0.88f,
            primaryColor = primaryColor,
            brightColor = brightColor
          )
        }

        // ==========================================================
        // 3. SECOND RING: Counter-Clockwise Rotation (Evenly spaced technical ticks)
        // ==========================================================
        rotate(secondAngle, center) {
          drawSecondCounterRing(
            center = center,
            radius = maxRadius * 0.76f,
            primaryColor = primaryColor,
            brightColor = brightColor
          )
        }

        // ==========================================================
        // 4. SUBTLE RADAR SWEEP BEAM (Confined cleanly within ring bounds)
        // ==========================================================
        if (aiState != AiCoreState.OFFLINE) {
          rotate(scanSweepAngle, center) {
            val sweepInnerRadius = maxRadius * 0.48f
            val sweepOuterRadius = maxRadius * 0.88f
            val sweepDiameter = sweepOuterRadius * 2f

            drawArc(
              brush = Brush.sweepGradient(
                colors = listOf(
                  Color.Transparent,
                  primaryColor.copy(alpha = 0.02f),
                  primaryColor.copy(alpha = 0.12f),
                  brightColor.copy(alpha = 0.30f)
                ),
                center = center
              ),
              startAngle = 0f,
              sweepAngle = 45f,
              useCenter = true,
              size = Size(sweepDiameter, sweepDiameter),
              topLeft = Offset(center.x - sweepOuterRadius, center.y - sweepOuterRadius)
            )
            // Leading radar scan laser tick (starts from inner ring boundary to outer ring boundary, not cutting through center)
            val rad = Math.toRadians(45.0).toFloat()
            drawLine(
              color = brightColor.copy(alpha = 0.65f),
              start = Offset(center.x + sweepInnerRadius * cos(rad), center.y + sweepInnerRadius * sin(rad)),
              end = Offset(center.x + sweepOuterRadius * cos(rad), center.y + sweepOuterRadius * sin(rad)),
              strokeWidth = 1.dp.toPx()
            )
          }
        }

        // ==========================================================
        // 5. THIRD RING: Segmented Energy Arcs (Symmetrical quadrants)
        // ==========================================================
        rotate(arcAngle, center) {
          drawSegmentedEnergyArcs(
            center = center,
            radius = maxRadius * 0.64f,
            aiState = aiState,
            primaryColor = primaryColor,
            brightColor = brightColor
          )
        }

        // ==========================================================
        // 6. INNER RING: Bright Neon-Red Energy Pulse (Expands/Contracts cleanly)
        // ==========================================================
        val pulsedInnerRadius = maxRadius * 0.48f * innerPulseScale
        drawInnerPulsingRing(
          center = center,
          radius = pulsedInnerRadius,
          centerGlow = centerGlow,
          primaryColor = primaryColor,
          brightColor = brightColor
        )

        // ==========================================================
        // 7. CENTER NUCLEUS: Glowing Red Energy Breathing Center
        // ==========================================================
        val nucleusRadius = maxRadius * 0.36f * (0.95f + (innerPulseScale - 1f) * 0.4f)
        drawGlowingNucleus(
          center = center,
          radius = nucleusRadius,
          glowIntensity = centerGlow,
          primaryColor = primaryColor,
          brightColor = brightColor
        )
      }

      // ==========================================================
      // 5b. CENTER TYPOGRAPHY: D-VEX AI CORE LABEL
      // ==========================================================
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 10.dp)
      ) {
        Text(
          text = "CORE-V1",
          fontFamily = FontFamily.Monospace,
          fontSize = 7.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 2.sp,
          color = DvexTextSecondary
        )

        Spacer(modifier = Modifier.height(1.dp))

        // Large high-impact D-VEX branding
        Text(
          text = "D-VEX",
          fontFamily = FontFamily.Monospace,
          fontSize = 20.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 4.sp,
          color = if (aiState == AiCoreState.ERROR) DvexWarning else DvexNeonRedBright
        )

        // AI CORE designation
        Text(
          text = "AI CORE",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 2.5.sp,
          color = Color.White
        )

        Spacer(modifier = Modifier.height(1.dp))

        // State Micro Status
        Text(
          text = when (aiState) {
            AiCoreState.OFFLINE -> "OFFLINE"
            AiCoreState.ERROR -> "ALERT"
            AiCoreState.EXECUTING -> "EXEC//ACT"
            AiCoreState.RESPONDING -> "TX//AUDIO"
            AiCoreState.LISTENING -> "RX//VOICE"
            AiCoreState.PROCESSING -> "PROC//AI"
            AiCoreState.THINKING -> "COMPUTE"
            AiCoreState.IDLE -> "ONLINE"
          },
          fontFamily = FontFamily.Monospace,
          fontSize = 7.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.2.sp,
          color = if (aiState == AiCoreState.ERROR) DvexWarning else DvexNeonRed
        )
      }
    }

    // Waveform Under Core
    DvexWaveform(
      aiState = aiState,
      modifier = Modifier
        .fillMaxWidth(0.85f)
        .height(24.dp)
    )

    Spacer(modifier = Modifier.height(4.dp))

    // Interactive State Selector (Allows testing and observing all reactor modes)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp),
      horizontalArrangement = Arrangement.Center
    ) {
      AiCoreState.values().forEach { state ->
        val isSelected = state == aiState
        Box(
          modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(CutCornerShape(3.dp))
            .background(if (isSelected) DvexNeonRed else DvexSurfaceCard)
            .border(1.dp, if (isSelected) DvexNeonRedBright else DvexBorderMuted, CutCornerShape(3.dp))
            .clickable { onStateChangeRequest?.invoke(state) }
            .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
          Text(
            text = when (state) {
              AiCoreState.IDLE -> "IDLE"
              AiCoreState.LISTENING -> "LSTN"
              AiCoreState.THINKING -> "THNK"
              AiCoreState.PROCESSING -> "PROC"
              AiCoreState.RESPONDING -> "SPK"
              AiCoreState.EXECUTING -> "EXEC"
              AiCoreState.ERROR -> "WARN"
              AiCoreState.OFFLINE -> "OFF"
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 7.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color.White else DvexTextMuted
          )
        }
      }
    }
  }
}

// =========================================================================
// DRAW HELPER 1: Outer Clockwise Ring (Slow and Smooth)
// =========================================================================
private fun DrawScope.drawOuterClockwiseRing(
  center: Offset,
  radius: Float,
  primaryColor: Color,
  brightColor: Color
) {
  // Main guide track
  drawCircle(
    color = primaryColor.copy(alpha = 0.35f),
    radius = radius,
    center = center,
    style = Stroke(width = 1.dp.toPx())
  )

  // Segmented outer dashes (evenly spaced)
  val outerDash = PathEffect.dashPathEffect(floatArrayOf(32f, 16f, 12f, 16f), 0f)
  drawCircle(
    color = primaryColor.copy(alpha = 0.70f),
    radius = radius,
    center = center,
    style = Stroke(width = 1.5.dp.toPx(), pathEffect = outerDash)
  )

  // 4 Cardinal Tactical Nodes & Crosshair Markers (confined precisely to ring perimeter)
  val nodeTickLength = 4.dp.toPx()
  for (i in 0 until 4) {
    val angleRad = Math.toRadians((i * 90.0)).toFloat()
    val x1 = center.x + (radius - nodeTickLength) * cos(angleRad)
    val y1 = center.y + (radius - nodeTickLength) * sin(angleRad)
    val x2 = center.x + (radius + nodeTickLength) * cos(angleRad)
    val y2 = center.y + (radius + nodeTickLength) * sin(angleRad)

    drawLine(brightColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.5.dp.toPx())
    drawCircle(brightColor, radius = 2.dp.toPx(), center = Offset(x2, y2))
  }

  // 8 Intermediate tick notches (symmetrical, small, clean)
  val interTickLength = 2.5.dp.toPx()
  for (i in 0 until 8) {
    val angleRad = Math.toRadians((i * 45.0 + 22.5)).toFloat()
    val x1 = center.x + (radius - interTickLength) * cos(angleRad)
    val y1 = center.y + (radius - interTickLength) * sin(angleRad)
    val x2 = center.x + (radius + interTickLength) * cos(angleRad)
    val y2 = center.y + (radius + interTickLength) * sin(angleRad)
    drawLine(primaryColor.copy(alpha = 0.60f), Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.dp.toPx())
  }
}

// =========================================================================
// DRAW HELPER 2: Second Counter-Clockwise Ring (Evenly Spaced Technical Ticks)
// =========================================================================
private fun DrawScope.drawSecondCounterRing(
  center: Offset,
  radius: Float,
  primaryColor: Color,
  brightColor: Color
) {
  // Fine technical dash ring
  val techDash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
  drawCircle(
    color = brightColor.copy(alpha = 0.60f),
    radius = radius,
    center = center,
    style = Stroke(width = 1.2.dp.toPx(), pathEffect = techDash)
  )

  // 16 Sector index notches (strictly radial, inward-pointing only, no overlap)
  val tickLength = 3.dp.toPx()
  for (i in 0 until 16) {
    val angleRad = Math.toRadians((i * 22.5)).toFloat()
    val tx1 = center.x + (radius - tickLength) * cos(angleRad)
    val ty1 = center.y + (radius - tickLength) * sin(angleRad)
    val tx2 = center.x + radius * cos(angleRad)
    val ty2 = center.y + radius * sin(angleRad)
    drawLine(
      color = if (i % 4 == 0) brightColor else primaryColor.copy(alpha = 0.5f),
      start = Offset(tx1, ty1),
      end = Offset(tx2, ty2),
      strokeWidth = if (i % 4 == 0) 1.5.dp.toPx() else 1.dp.toPx()
    )
  }
}

// =========================================================================
// DRAW HELPER 3: Third Ring - Segmented Energy Arcs (Symmetrical Quadrants)
// =========================================================================
private fun DrawScope.drawSegmentedEnergyArcs(
  center: Offset,
  radius: Float,
  aiState: AiCoreState,
  primaryColor: Color,
  brightColor: Color
) {
  val arcRect = Size(radius * 2f, radius * 2f)
  val arcTopLeft = Offset(center.x - radius, center.y - radius)

  val arcWidth = if (aiState == AiCoreState.EXECUTING) 3.5.dp.toPx() else 2.5.dp.toPx()

  // Symmetrical 4-quadrant segmented HUD radar arcs (clean 60 deg each, with 30 deg gaps)
  val quadrantSweep = 60f
  for (quadrant in 0 until 4) {
    val startAngle = quadrant * 90f + 15f
    drawArc(
      color = if (quadrant % 2 == 0) brightColor.copy(alpha = 0.90f) else primaryColor.copy(alpha = 0.70f),
      startAngle = startAngle,
      sweepAngle = quadrantSweep,
      useCenter = false,
      topLeft = arcTopLeft,
      size = arcRect,
      style = Stroke(width = arcWidth, cap = StrokeCap.Round)
    )
  }
}

// =========================================================================
// DRAW HELPER 4: Inner Ring - Bright Neon-Red Energy Pulse
// =========================================================================
private fun DrawScope.drawInnerPulsingRing(
  center: Offset,
  radius: Float,
  centerGlow: Float,
  primaryColor: Color,
  brightColor: Color
) {
  // Pulsing glow halo perimeter
  drawCircle(
    brush = Brush.radialGradient(
      colors = listOf(
        brightColor.copy(alpha = centerGlow * 0.40f),
        primaryColor.copy(alpha = centerGlow * 0.15f),
        Color.Transparent
      ),
      center = center,
      radius = radius * 1.25f
    ),
    radius = radius * 1.25f,
    center = center
  )

  // Sharp bright perimeter ring
  drawCircle(
    color = brightColor.copy(alpha = 0.85f),
    radius = radius,
    center = center,
    style = Stroke(width = 2.dp.toPx())
  )

  // Inner dashed containment trace
  val innerDash = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
  drawCircle(
    color = primaryColor.copy(alpha = 0.65f),
    radius = radius * 0.92f,
    center = center,
    style = Stroke(width = 1.dp.toPx(), pathEffect = innerDash)
  )
}

// =========================================================================
// DRAW HELPER 5: Center Nucleus - Glowing Red Breathing Center
// =========================================================================
private fun DrawScope.drawGlowingNucleus(
  center: Offset,
  radius: Float,
  glowIntensity: Float,
  primaryColor: Color,
  brightColor: Color
) {
  // Deep intense radial energy glow
  drawCircle(
    brush = Brush.radialGradient(
      colors = listOf(
        brightColor.copy(alpha = glowIntensity * 0.90f),
        primaryColor.copy(alpha = glowIntensity * 0.60f),
        DvexNeonRedDim.copy(alpha = glowIntensity * 0.30f),
        Color.Transparent
      ),
      center = center,
      radius = radius * 1.5f
    ),
    radius = radius * 1.5f,
    center = center
  )

  // Inner dark containment core chamber
  drawCircle(
    brush = Brush.radialGradient(
      colors = listOf(
        DvexSurfaceDark,
        DvexBlack
      ),
      center = center,
      radius = radius
    ),
    radius = radius,
    center = center
  )

  // Core chamber rim
  drawCircle(
    color = brightColor.copy(alpha = 0.85f),
    radius = radius,
    center = center,
    style = Stroke(width = 1.8.dp.toPx())
  )
}

// =========================================================================
// DRAW HELPER 6: Radial Waveform / Equalizer Pulses around Circumference
// =========================================================================
private fun DrawScope.drawRadialWaveform(
  center: Offset,
  radius: Float,
  eqMultiplier: Float,
  eqPhase: Float,
  primaryColor: Color,
  brightColor: Color
) {
  val barCount = 36
  val stepAngle = 360.0 / barCount

  for (i in 0 until barCount) {
    val angleDeg = i * stepAngle
    val angleRad = Math.toRadians(angleDeg).toFloat()

    // Harmonic wave formula producing active, organic pulse modulation
    val waveHarmonic = kotlin.math.abs(sin(i * 0.45f + eqPhase) + 0.5f * sin(i * 0.9f - eqPhase))
    val barLength = (3.dp.toPx() + 10.dp.toPx() * waveHarmonic) * eqMultiplier

    val x1 = center.x + radius * cos(angleRad)
    val y1 = center.y + radius * sin(angleRad)
    val x2 = center.x + (radius + barLength) * cos(angleRad)
    val y2 = center.y + (radius + barLength) * sin(angleRad)

    val isPeak = waveHarmonic > 1.1f
    drawLine(
      color = if (isPeak) brightColor else primaryColor.copy(alpha = 0.75f),
      start = Offset(x1, y1),
      end = Offset(x2, y2),
      strokeWidth = 1.8.dp.toPx(),
      cap = StrokeCap.Square
    )
  }
}

/**
 * Animated real-time horizontal audio waveform below core.
 */
@Composable
fun DvexWaveform(
  aiState: AiCoreState,
  modifier: Modifier = Modifier
) {
  val infiniteTransition = rememberInfiniteTransition(label = "horizontalWaveformTransition")

  val wavePhase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 6.283185f,
    animationSpec = infiniteRepeatable(
      animation = tween(
        durationMillis = when (aiState) {
          AiCoreState.RESPONDING -> 650
          AiCoreState.LISTENING -> 850
          AiCoreState.EXECUTING -> 600
          AiCoreState.PROCESSING -> 1000
          else -> 2200
        },
        easing = LinearEasing
      ),
      repeatMode = RepeatMode.Restart
    ),
    label = "horizontalWavePhase"
  )

  Canvas(modifier = modifier) {
    val midY = size.height / 2f
    val barCount = 32
    val step = size.width / barCount
    val barWidth = step * 0.6f

    val baseMultiplier = when (aiState) {
      AiCoreState.RESPONDING -> 0.95f
      AiCoreState.LISTENING -> 0.90f
      AiCoreState.EXECUTING -> 0.85f
      AiCoreState.PROCESSING -> 0.65f
      AiCoreState.THINKING -> 0.50f
      AiCoreState.ERROR -> 0.75f
      AiCoreState.OFFLINE -> 0.12f
      AiCoreState.IDLE -> 0.35f
    }

    val waveColor = when (aiState) {
      AiCoreState.ERROR -> DvexWarning
      AiCoreState.OFFLINE -> DvexTextMuted
      else -> DvexNeonRed
    }

    // Baseline axis
    drawLine(
      color = waveColor.copy(alpha = 0.2f),
      start = Offset(0f, midY),
      end = Offset(size.width, midY),
      strokeWidth = 1.dp.toPx()
    )

    for (i in 0 until barCount) {
      val x = i * step + barWidth / 2f
      val sinVal = kotlin.math.abs(sin(i * 0.35f + wavePhase))
      val barHeight = (size.height * 0.8f) * sinVal * baseMultiplier + 2.dp.toPx()

      drawLine(
        brush = Brush.verticalGradient(
          listOf(
            DvexNeonRedBright,
            waveColor,
            DvexNeonRedDim
          ),
          startY = midY - barHeight / 2f,
          endY = midY + barHeight / 2f
        ),
        start = Offset(x, midY - barHeight / 2f),
        end = Offset(x, midY + barHeight / 2f),
        strokeWidth = barWidth,
        cap = StrokeCap.Square
      )
    }
  }
}

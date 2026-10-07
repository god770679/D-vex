package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.VoiceState
import com.example.ui.theme.DvexBlack
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexNeonRedDim
import com.example.ui.theme.DvexNeonRedGlow
import com.example.ui.theme.DvexNeonRedSubtle
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary
import com.example.ui.theme.DvexTextSecondary
import kotlin.math.sin

/**
 * Bottom Left: VOICE MODULE UI
 */
@Composable
fun VoiceModule(
  voiceState: VoiceState,
  modifier: Modifier = Modifier
) {
  TacticalPanel(
    title = "Voice Module",
    headerTag = "VOICE ENGINE",
    modifier = modifier
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.VolumeUp,
          contentDescription = "Audio input",
          tint = DvexNeonRed,
          modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = voiceState.label,
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          color = DvexNeonRedBright
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      // Mini soundwave — shared waveform implementation
      VoiceWaveform(
        modifier = Modifier.fillMaxWidth(),
        barCount = 16,
        barHeight = 18.dp
      )
    }
  }
}

/**
 * Bottom Center: Large Glowing Microphone Button
 */
@Composable
fun MicrophoneButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  isEnabled: Boolean = true,
  isListening: Boolean = false
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.18f,
    animationSpec = infiniteRepeatable(
      animation = tween(1000, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "micGlowPulse"
  )

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .size(68.dp),
      contentAlignment = Alignment.Center
    ) {
      // Outer Glowing Ring when active or pressed
      if (isListening || isPressed) {
        Box(
          modifier = Modifier
            .size(66.dp)
            .scale(pulseScale)
            .clip(CircleShape)
            .background(DvexNeonRedGlow)
        )
      }

      // Border circle
      Box(
        modifier = Modifier
          .size(56.dp)
          .clip(CircleShape)
          .background(
            if (isEnabled) DvexSurfaceDark else DvexBlack
          )
          .border(
            width = if (isPressed || isListening) 2.dp else 1.5.dp,
            color = if (!isEnabled) DvexTextMuted else if (isPressed || isListening) DvexNeonRedBright else DvexNeonRed,
            shape = CircleShape
          )
          .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = isEnabled,
            onClick = onClick
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Filled.Mic,
          contentDescription = "Tap to speak",
          tint = if (!isEnabled) DvexTextMuted else if (isPressed || isListening) Color.White else DvexNeonRedBright,
          modifier = Modifier.size(28.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(2.dp))

    Text(
      text = if (isListening) "LISTENING..." else "TAP TO SPEAK",
      fontFamily = FontFamily.Monospace,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 1.sp,
      color = if (isListening) DvexNeonRedBright else DvexTextPrimary
    )
  }
}

/**
 * LIVE badge for the conversation card — REAL pipeline state only (P5: the "LIVE"
 * label must represent the actual mode it claims, never a decorative always-on
 * tag). The card is LIVE exactly while a voice/response turn is genuinely in
 * flight; otherwise it is STANDBY. Pure function so the transition is unit-tested.
 */
fun conversationCardTag(voiceState: VoiceState): String = when (voiceState) {
  VoiceState.LISTENING,
  VoiceState.PROCESSING,
  VoiceState.SPEAKING,
  VoiceState.EXECUTING_ACTION -> "LIVE"
  VoiceState.IDLE,
  VoiceState.STANDBY,
  VoiceState.ERROR -> "STANDBY"
}

/**
 * BOTTOM-RIGHT compact conversation card.
 *
 * Shows the LAST real exchange: what the user said or typed, and the final D-VEX
 * response — the exact same string handed to the TTS layer, so display and voice
 * can never diverge. Deliberately small and wrap-content: the caller bounds its
 * width and height, so it never becomes a full-width panel and never covers the
 * centred reactor. Only real content is rendered — no debug, tool, intent or
 * implementation data.
 */
@Composable
fun DvexConversationCard(
  userInput: String,
  responseText: String,
  modifier: Modifier = Modifier,
  /** Real session tag from [conversationCardTag]; LIVE only while a turn runs. */
  headerTag: String = "STANDBY"
) {
  TacticalPanel(
    title = "D-VEX RESPONSE",
    headerTag = headerTag,
    modifier = modifier
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 42.dp)
        .padding(horizontal = 8.dp, vertical = 7.dp)
        .verticalScroll(rememberScrollState())
    ) {
      if (userInput.isNotBlank()) {
        Text(
          text = "YOU",
          fontFamily = FontFamily.Monospace,
          fontSize = 7.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
          color = DvexTextMuted
        )
        Text(
          text = userInput,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = DvexTextMuted,
          lineHeight = 13.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
      }

      Text(
        text = "D-VEX",
        fontFamily = FontFamily.Monospace,
        fontSize = 7.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = DvexNeonRed
      )
      if (responseText.isBlank()) {
        // Honest empty state: D-VEX has nothing to say until it has actually
        // said it. No canned "ready" / "standing by" line is ever fabricated.
        Text(
          text = "—",
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          color = DvexTextMuted
        )
      } else {
        Text(
          text = responseText,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = DvexNeonRedBright,
          letterSpacing = 0.3.sp,
          lineHeight = 15.sp
        )
      }
      Spacer(modifier = Modifier.height(5.dp))
      // Synthesis / audio state footer — real state only, never implementation
      // details. Matches the reference layout's compact status line.
      Text(
        text = "SYNTHESIS: READY // AUDIO OUT: ACTIVE",
        fontFamily = FontFamily.Monospace,
        fontSize = 7.sp,
        color = DvexTextMuted
      )
    }
  }
}

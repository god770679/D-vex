package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Tactical cut-corner panel with glowing red outline and tech brackets.
 */
@Composable
fun TacticalPanel(
  modifier: Modifier = Modifier,
  title: String? = null,
  headerTag: String? = null,
  cornerCut: Dp = 8.dp,
  borderColor: Color = DvexBorderRed,
  backgroundColor: Color = DvexSurfaceDark,
  content: @Composable BoxScope.() -> Unit
) {
  val shape = CutCornerShape(cornerCut)

  Column(modifier = modifier) {
    if (title != null) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Red tactical pip
        Box(
          modifier = Modifier
            .size(4.dp, 10.dp)
            .background(DvexNeonRed)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = title.uppercase(),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.2.sp,
          color = DvexNeonRed
        )
        Spacer(modifier = Modifier.weight(1f))
        if (headerTag != null) {
          Text(
            text = headerTag.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = DvexTextMuted,
            letterSpacing = 1.sp
          )
        }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(backgroundColor)
        .border(BorderStroke(1.dp, borderColor), shape)
        .drawBehind {
          // Draw tactical corner highlight brackets
          val strokeW = 1.5.dp.toPx()
          val bracketLen = 10.dp.toPx()
          val bracketColor = DvexNeonRedBright

          // Top Left
          drawLine(bracketColor, Offset(0f, 0f), Offset(bracketLen, 0f), strokeW)
          drawLine(bracketColor, Offset(0f, 0f), Offset(0f, bracketLen), strokeW)

          // Top Right
          drawLine(bracketColor, Offset(size.width - bracketLen, 0f), Offset(size.width, 0f), strokeW)
          drawLine(bracketColor, Offset(size.width, 0f), Offset(size.width, bracketLen), strokeW)

          // Bottom Left
          drawLine(bracketColor, Offset(0f, size.height), Offset(bracketLen, size.height), strokeW)
          drawLine(bracketColor, Offset(0f, size.height - bracketLen), Offset(0f, size.height), strokeW)

          // Bottom Right
          drawLine(bracketColor, Offset(size.width - bracketLen, size.height), Offset(size.width, size.height), strokeW)
          drawLine(bracketColor, Offset(size.width, size.height - bracketLen), Offset(size.width, size.height), strokeW)
        }
    ) {
      content()
    }
  }
}

/**
 * Reusable Tactical Button with neon red glow and pressed state
 */
@Composable
fun TacticalButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  isActive: Boolean = false,
  subLabel: String? = null
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  val bgBorderColor by animateColorAsState(
    targetValue = when {
      isPressed -> DvexNeonRedBright
      isActive -> DvexNeonRed
      else -> DvexBorderMuted
    },
    label = "buttonBorder"
  )

  val bgColor by animateColorAsState(
    targetValue = when {
      isPressed -> DvexNeonRedSubtle.copy(alpha = 0.45f)
      isActive -> DvexNeonRedSubtle.copy(alpha = 0.25f)
      else -> DvexSurfaceCard
    },
    label = "buttonBg"
  )

  val shape = CutCornerShape(6.dp)

  Box(
    modifier = modifier
      .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
      .clip(shape)
      .background(bgColor)
      .border(BorderStroke(if (isActive || isPressed) 1.5.dp else 1.dp, bgBorderColor), shape)
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
      )
      .padding(horizontal = 8.dp, vertical = 6.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = text,
          tint = if (isActive || isPressed) DvexNeonRedBright else DvexNeonRed,
          modifier = Modifier.size(18.dp)
        )
      }
      Text(
        text = text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp,
        color = if (isActive || isPressed) Color.White else DvexTextPrimary
      )
      if (subLabel != null) {
        Text(
          text = subLabel,
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          color = DvexTextMuted
        )
      }
    }
  }
}

package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexSurfaceCard
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextPrimary

/**
 * Compact bottom-left tactical command control — the TEXT half of D-VEX's single
 * input band (the voice button sits beside it and feeds the very same pipeline).
 *
 * Collapsed by default to a single small button, it expands on demand into the
 * existing text command input. It is intentionally small and unobtrusive: the
 * conversation stays the visual focus and this never spans the screen.
 *
 * The caller places it above the system navigation bar (the HUD applies the
 * navigation-bar insets) and supplies [expandedWidth] so the open input adapts to
 * the screen instead of using one hardcoded oversized dimension.
 */
@Composable
fun TacticalCommandDock(
  onSendCommand: (String) -> Unit,
  modifier: Modifier = Modifier,
  expandedWidth: Dp = 248.dp
) {
  var expanded by remember { mutableStateOf(false) }

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.Start
  ) {
    AnimatedVisibility(
      visible = expanded,
      enter = fadeIn(tween(160)) + expandVertically(expandFrom = Alignment.Bottom),
      exit = fadeOut(tween(120)) + shrinkVertically(shrinkTowards = Alignment.Bottom)
    ) {
      Column(
        modifier = Modifier
          .width(expandedWidth)
          .padding(bottom = 6.dp)
      ) {
        TacticalPanel(
          title = "Tactical Command",
          headerTag = "INPUT"
        ) {
          Column(modifier = Modifier.padding(6.dp)) {
            TacticalCommandInput(
              onSendCommand = { command ->
                onSendCommand(command)
                expanded = false
              },
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
      }
    }

    TacticalButton(
      text = if (expanded) "Hide" else "Command",
      icon = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
      isActive = expanded,
      onClick = { expanded = !expanded }
    )
  }
}

/**
 * Tactical Command Text Input with Send Button.
 * Provides a reliable text-based interface to dispatch commands directly to D-VEX.
 */
@Composable
fun TacticalCommandInput(
  onSendCommand: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  var commandText by remember { mutableStateOf("") }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  Row(
    modifier = modifier
      .background(DvexSurfaceDark, CutCornerShape(4.dp))
      .border(1.dp, DvexBorderMuted, CutCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    BasicTextField(
      value = commandText,
      onValueChange = { commandText = it },
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 6.dp, vertical = 6.dp),
      textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = DvexTextPrimary
      ),
      cursorBrush = SolidColor(DvexNeonRedBright),
      singleLine = true,
      keyboardOptions = KeyboardOptions(
        imeAction = ImeAction.Send,
        keyboardType = KeyboardType.Text
      ),
      keyboardActions = KeyboardActions(
        onSend = {
          if (commandText.isNotBlank()) {
            val cmd = commandText.trim()
            commandText = ""
            keyboardController?.hide()
            focusManager.clearFocus()
            onSendCommand(cmd)
          }
        }
      ),
      decorationBox = { innerTextField ->
        Box(contentAlignment = Alignment.CenterStart) {
          if (commandText.isEmpty()) {
            Text(
              text = "Enter tactical command...",
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = DvexTextMuted
            )
          }
          innerTextField()
        }
      }
    )

    Spacer(modifier = Modifier.width(6.dp))

    val isSendEnabled = commandText.isNotBlank()
    Box(
      modifier = Modifier
        .clip(CutCornerShape(3.dp))
        .background(if (isSendEnabled) DvexNeonRed else DvexSurfaceCard)
        .border(
          1.dp,
          if (isSendEnabled) DvexNeonRedBright else DvexBorderMuted,
          CutCornerShape(3.dp)
        )
        .clickable(enabled = isSendEnabled) {
          if (commandText.isNotBlank()) {
            val cmd = commandText.trim()
            commandText = ""
            keyboardController?.hide()
            focusManager.clearFocus()
            onSendCommand(cmd)
          }
        }
        .padding(horizontal = 10.dp, vertical = 6.dp),
      contentAlignment = Alignment.Center
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.Send,
          contentDescription = "Send tactical command",
          tint = if (isSendEnabled) Color.White else DvexTextMuted,
          modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = "SEND",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
          color = if (isSendEnabled) Color.White else DvexTextMuted
        )
      }
    }
  }
}

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DvexBorderMuted
import com.example.ui.theme.DvexNeonRed
import com.example.ui.theme.DvexNeonRedBright
import com.example.ui.theme.DvexSurfaceDark
import com.example.ui.theme.DvexTextMuted
import com.example.ui.theme.DvexTextSecondary
import com.example.ui.theme.DvexWarning
import kotlinx.coroutines.delay

/**
 * D-VEX boot / initialization sequence.
 *
 * Progressive "D-VEX INITIALIZING" console shown over the black HUD at launch.
 * Lines appear step by step (~4-7s total), then hand control back to the main
 * HUD via [onBootComplete].
 *
 * [initializationError] reflects REAL startup state: when non-null the sequence
 * stops and a controlled retry affordance is shown — the screen never pretends
 * the system is online when initialization failed.
 */
@Composable
fun DvexBootScreen(
  onBootComplete: () -> Unit,
  initializationError: String? = null,
  onRetry: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val bootLines = remember {
    listOf(
      "Initializing D-VEX core...",
      "Loading intelligence layer...",
      "Loading memory system...",
      "Initializing voice engine...",
      "Connecting AI services...",
      "Initializing autonomous agent...",
      "Synchronizing reactor...",
      "System diagnostics...",
      "D-VEX ONLINE"
    )
  }

  var visibleLines by remember { mutableStateOf(0) }
  var finished by remember { mutableStateOf(false) }

  // Restart cleanly after a retry.
  LaunchedEffect(initializationError, onRetry) {
    if (initializationError == null) {
      visibleLines = 0
      finished = false
    }
  }

  // Progressive line reveal (~4.5s total pacing; last line holds briefly).
  LaunchedEffect(initializationError) {
    if (initializationError != null) return@LaunchedEffect
    for (i in bootLines.indices) {
      visibleLines = i + 1
      delay(450)
    }
    delay(550)
    finished = true
    onBootComplete()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(DvexSurfaceDark),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Title — D-VEX INITIALIZING
      Text(
        text = "D-VEX INITIALIZING",
        fontFamily = FontFamily.Monospace,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp,
        color = DvexNeonRed
      )

      Spacer(modifier = Modifier.height(6.dp))

      // Thin reactor-style divider (decorative only; does not alter the main core)
      Box(
        modifier = Modifier
          .width(220.dp)
          .height(1.dp)
          .background(DvexBorderMuted)
      )

      Spacer(modifier = Modifier.height(22.dp))

      if (initializationError == null) {
        bootLines.take(visibleLines).forEachIndexed { index, line ->
          val isLast = index == bootLines.lastIndex
          BootLine(
            text = line,
            color = if (isLast) DvexNeonRedBright else DvexTextSecondary
          )
          Spacer(modifier = Modifier.height(7.dp))
        }

        if (!finished) {
          Spacer(modifier = Modifier.height(14.dp))
          Text(
            text = ">",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = DvexTextMuted
          )
        }
      } else {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = "INITIALIZATION FAULT",
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 2.sp,
          color = DvexWarning
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = initializationError,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          color = DvexTextMuted
        )
        if (onRetry != null) {
          Spacer(modifier = Modifier.height(20.dp))
          Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
              containerColor = DvexNeonRed,
              contentColor = Color.White
            )
          ) {
            Text(
              text = "RETRY",
              fontFamily = FontFamily.Monospace,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 2.sp
            )
          }
        }
      }
    }
  }
}

/** One progressive console line: "> <text>" in monospace, matching the HUD style. */
@Composable
private fun BootLine(text: String, color: Color) {
  Text(
    text = "> $text",
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    color = color
  )
}

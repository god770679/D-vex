package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DvexDarkColorScheme = darkColorScheme(
  primary = DvexNeonRed,
  onPrimary = DvexBlack,
  primaryContainer = DvexNeonRedDim,
  onPrimaryContainer = DvexNeonRedBright,
  secondary = DvexNeonRedBright,
  onSecondary = DvexBlack,
  secondaryContainer = DvexSurfaceElevated,
  onSecondaryContainer = DvexTextPrimary,
  tertiary = DvexNeonRedDim,
  onTertiary = DvexTextPrimary,
  background = DvexBlack,
  onBackground = DvexTextPrimary,
  surface = DvexSurfaceDark,
  onSurface = DvexTextPrimary,
  surfaceVariant = DvexSurfaceCard,
  onSurfaceVariant = DvexTextSecondary,
  outline = DvexBorderRed,
  outlineVariant = DvexBorderMuted
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false, // Keep false to preserve strict tactical D-VEX black & neon red branding
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = DvexDarkColorScheme,
    typography = Typography,
    content = content
  )
}


package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
  displayLarge = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp,
    lineHeight = 36.sp,
    letterSpacing = 2.sp,
    color = DvexTextPrimary
  ),
  titleLarge = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 24.sp,
    letterSpacing = 1.5.sp,
    color = DvexNeonRed
  ),
  titleMedium = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = 1.sp,
    color = DvexTextPrimary
  ),
  bodyLarge = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.5.sp,
    color = DvexTextPrimary
  ),
  bodyMedium = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.25.sp,
    color = DvexTextSecondary
  ),
  labelSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.sp,
    color = DvexTextMuted
  )
)

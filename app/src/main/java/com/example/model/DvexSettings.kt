package com.example.model

/**
 * Settings configuration for D-VEX Always Ready background features.
 */
data class DvexSettings(
  val alwaysReadyEnabled: Boolean = false,
  val floatingOrbEnabled: Boolean = false,
  val wakeWordEnabled: Boolean = false,
  val wakeWordKeyword: String = "D-VEX",
  val wakePhrase: String = "D-VEX", // "D-VEX" or "Hey D-VEX"
  val orbSizeDp: Int = 56, // 48, 56, 64
  val voiceConfirmationEnabled: Boolean = true, // Short "Yes?" voice prompt upon wake word
  val voiceResponseEnabled: Boolean = true,
  val accessibilityControlEnabled: Boolean = false,
  val orbPositionX: Int = 100,
  val orbPositionY: Int = 300
)


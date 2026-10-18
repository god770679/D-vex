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
  val voiceResponseEnabled: Boolean = true,
  /** Opt-in Live (BiDi) conversation pipeline; off by default — STT/REST/TTS serves the turn until enabled. */
  val liveConversationEnabled: Boolean = false,
  val accessibilityControlEnabled: Boolean = false,
  val orbPositionX: Int = 100,
  val orbPositionY: Int = 300
)


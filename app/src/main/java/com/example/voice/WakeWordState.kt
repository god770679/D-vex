package com.example.voice

/**
 * Wake Word Detection States for D-VEX Voice Pipeline.
 */
sealed class WakeWordState {
  object Disabled : WakeWordState()
  object Standby : WakeWordState()
  object Listening : WakeWordState()
  data class Triggered(val keyword: String = "D-VEX", val confidence: Float = 1.0f) : WakeWordState() {
    val phrase: String get() = keyword
  }
  data class Error(val message: String) : WakeWordState()

  // Backward-compatible aliases
  object Inactive : WakeWordState()
  object ListeningForTrigger : WakeWordState()
  data class TriggerDetected(val keyword: String, val confidence: Float = 1.0f) : WakeWordState()
}

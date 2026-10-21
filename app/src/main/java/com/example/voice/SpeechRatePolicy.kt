package com.example.voice

/**
 * Pure, engine-free policy for the speech RATE D-VEX requests from Android's
 * TextToSpeech engine.
 *
 * D-VEX targets a configured speed of [TARGET_SPEECH_RATE] (1.2x), but the
 * Android TTS contract makes no promise that a requested rate is honoured:
 * engines may reject the call or silently clamp the value into their own
 * supported range. The ONLY acceptance signal the public Android API exposes is
 * the status code returned by `setSpeechRate()` (TextToSpeech.SUCCESS /
 * TextToSpeech.ERROR) — the API has no `getSpeechRate()` getter — so that
 * status is what gets VERIFIED and logged. D-VEX never silently claims 1.2x:
 * it logs either the engine's acceptance or its rejection, and a rejected rate
 * falls back to the engine's own default instead of failing the utterance.
 *
 * Kept free of any Android type so the verification contract is unit-testable
 * on the JVM without an engine.
 */
object SpeechRatePolicy {

  /** The configured D-VEX speech speed. Requested from the engine, then verified. */
  const val TARGET_SPEECH_RATE = 1.2f

  /** `TextToSpeech.SUCCESS` (0) / `TextToSpeech.ERROR` (-1), API 21+ contract. */
  const val ENGINE_SUCCESS = 0

  /**
   * Outcome of one verified rate request. `callAccepted` is the engine's OWN
   * answer about the request; nothing is claimed beyond what the engine said.
   */
  data class Verification(
    val requested: Float,
    val engineStatus: Int
  ) {
    /** The engine accepted the `setSpeechRate()` call itself. */
    val callAccepted: Boolean
      get() = engineStatus == ENGINE_SUCCESS
  }

  /** Builds the verification record for one `setSpeechRate()` attempt. */
  fun verify(requested: Float, engineStatus: Int): Verification =
    Verification(requested, engineStatus)
}

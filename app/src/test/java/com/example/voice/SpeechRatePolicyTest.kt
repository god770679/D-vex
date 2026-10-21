package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speech-rate verification contract: D-VEX targets the configured 1.2x speed,
 * but only the ENGINE decides what it will actually speak. Android exposes no
 * rate getter, so acceptance is the `setSpeechRate()` status code. These tests
 * pin that contract so "1.2x" can never again be a claim the code makes without
 * reporting the engine's own answer.
 */
class SpeechRatePolicyTest {

  @Test
  fun configuredTargetIs12x() {
    assertEquals(1.2f, SpeechRatePolicy.TARGET_SPEECH_RATE, 0.0001f)
  }

  @Test
  fun engineSuccessStatusConfirmsAcceptance() {
    val v = SpeechRatePolicy.verify(requested = 1.2f, engineStatus = 0)
    assertTrue("status 0 (TextToSpeech.SUCCESS) means the engine accepted", v.callAccepted)
    assertEquals(1.2f, v.requested, 0.0001f)
  }

  @Test
  fun engineErrorStatusNeverCountsAsAcceptance() {
    val v = SpeechRatePolicy.verify(1.2f, -1)
    assertFalse("status -1 (TextToSpeech.ERROR) means rejected", v.callAccepted)
  }

  @Test
  fun unknownStatusCodesNeverCountAsAcceptance() {
    // Defensive: any non-SUCCESS code (e.g. a future engine quirk) must not be
    // silently treated as an accepted 1.2x.
    assertFalse(SpeechRatePolicy.verify(1.2f, 7).callAccepted)
  }

  @Test
  fun androidTtsStillExposesNoRateGetter() {
    // The verification design rests on this fact: if a getter ever appears, the
    // policy should be upgraded to read back the effective rate. Guarding the
    // assumption keeps the limitation explicit and documented.
    val getterExists = try {
      android.speech.tts.TextToSpeech::class.java.getMethod("getSpeechRate")
      true
    } catch (e: NoSuchMethodException) {
      false
    } catch (e: Throwable) {
      // Classloaders differ across runners; treat as absent rather than fail.
      false
    }
    if (getterExists) {
      throw AssertionError(
        "Android now exposes TextToSpeech.getSpeechRate() — upgrade SpeechRatePolicy " +
          "to read back the engine's effective rate instead of trusting the status code alone"
      )
    }
  }
}

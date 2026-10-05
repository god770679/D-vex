package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wake-word restart cadence policy (anti-chime-churn).
 *
 * The Google recognition service plays its own start/stop cue per opened session
 * and Android exposes NO API to disable it. The only legitimate lever D-VEX has is
 * how often a session is opened — this pins that policy:
 *
 *  - consecutive SILENCE-ended sessions widen the gap (1x -> 2x -> 4x, capped at 6s),
 *  - ANY detected speech resets to the fastest interval immediately (wake latency
 *    is never degraded once the user talks),
 *  - the cap is absolute: a quiet room never waits longer than 6s between sessions,
 *  - reset() restores the fastest cadence when the detector is stopped/re-armed.
 */
class IdleRestartBackoffTest {

  @Test
  fun silenceGrowsTheGapUpToTheCap() {
    val backoff = IdleRestartBackoff()

    // Fastest interval while speech is present / at start.
    assertEquals(1_000L, backoff.delayFor(1_000L))

    backoff.noteSilence()
    assertEquals(2_000L, backoff.delayFor(1_000L))

    backoff.noteSilence()
    assertEquals(4_000L, backoff.delayFor(1_000L))

    // Further silence never grows past the absolute cap.
    backoff.noteSilence()
    assertEquals(6_000L, backoff.delayFor(1_000L))
    backoff.noteSilence()
    backoff.noteSilence()
    assertEquals(6_000L, backoff.delayFor(1_000L))
    assertEquals(6_000L, backoff.delayFor(1_200L))
  }

  @Test
  fun anyDetectedSpeechResetsToTheFastestInterval() {
    val backoff = IdleRestartBackoff()
    backoff.noteSilence()
    backoff.noteSilence()
    backoff.noteSilence()
    assertTrue(backoff.currentLevel > 0)

    backoff.noteSpeech()

    assertEquals(0, backoff.currentLevel)
    assertEquals(1_000L, backoff.delayFor(1_000L))
  }

  @Test
  fun resetRestoresTheFastestCadenceOnDetectorRestart() {
    val backoff = IdleRestartBackoff()
    backoff.noteSilence()
    backoff.noteSilence()
    assertTrue(backoff.delayFor(1_200L) > 1_200L)

    backoff.reset()

    assertEquals(0, backoff.currentLevel)
    assertEquals(1_200L, backoff.delayFor(1_200L))
  }

  @Test
  fun growthIsExponentialButBoundedPerLevel() {
    val backoff = IdleRestartBackoff()
    backoff.noteSilence()
    assertEquals(1_200L * 2, backoff.delayFor(1_200L))
    backoff.noteSilence()
    assertEquals(1_200L * 4, backoff.delayFor(1_200L))
    backoff.noteSilence()
    // Level cap reached: 1200 * 8 = 9600 -> clamped to the 6000 absolute cap.
    assertEquals(6_000L, backoff.delayFor(1_200L))
  }
}

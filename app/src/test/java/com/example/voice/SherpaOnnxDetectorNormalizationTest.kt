package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MEASURED VECTORS for the microphone PCM → float normalization used by
 * [AndroidMicCapture] (Phase-11, goal B): quiet speech must be preserved exactly,
 * never gated, never amplified, never clipped.
 *
 * These are known input/output pairs of the exact production function
 * (`pcm16ToNormalized`), pinning the contract that the offline model receives the
 * captured waveform verbatim in [-1.0, 1.0) so its own feature pipeline decides
 * what to do with low-amplitude speech — no hard amplitude gate, no arbitrary gain
 * that would amplify bus noise as much as the voice.
 */
class SherpaOnnxDetectorNormalizationTest {

  @Test
  fun fullScaleVectorsAreExact() {
    assertEquals(0f, pcm16ToNormalized(0), 0f)
    assertEquals(-1.0f, pcm16ToNormalized(-32768), 0f)
    // +32767 is the largest positive PCM16 sample; it maps just under +1.0.
    assertEquals(32767f / 32768f, pcm16ToNormalized(32767), 0f)
  }

  @Test
  fun unitStepsAreLinearNoGainNoOffset() {
    assertEquals(1f / 32768f, pcm16ToNormalized(1), 0f)
    assertEquals(-1f / 32768f, pcm16ToNormalized(-1), 0f)
    // Symmetry: f(-x) == -f(x) for every in-range x (no DC offset introduced).
    for (x in intArrayOf(1, 2, 3, 32, 100, 1234, 16384, 32767)) {
      assertEquals(
        "asymmetry at $x",
        -pcm16ToNormalized(x.toShort()),
        pcm16ToNormalized((-x).toShort()),
        0f,
      )
    }
  }

  @Test
  fun quietSpeechSamplesSurviveUnchangedAndAreNeverZeroed() {
    // -60 dBFS tone: exactly the level a low-volume voice produces near the mic.
    val quiet = pcm16ToNormalized(32)
    assertEquals(32f / 32768f, quiet, 0f)
    assertTrue("a quiet sample must never be gated to zero", quiet > 0f)

    // Quiet conversational levels stay well above the float floor.
    assertTrue(pcm16ToNormalized(100) > 0f)
    assertTrue(pcm16ToNormalized(1000) > 0f)
    assertTrue(pcm16ToNormalized(-1000) < 0f)
  }

  @Test
  fun everyRepresentableSampleMapsInsideHalfOpenRange() {
    // Spot checks across the whole range plus the extremes: output must always
    // be in [-1.0, 1.0) so the model's feature pipeline never sees clipping.
    for (x in intArrayOf(
      -32768, -32767, -16384, -1000, -32, -1, 0, 1, 32, 1000, 16384, 32766, 32767,
    )) {
      val f = pcm16ToNormalized(x.toShort())
      assertTrue("out of range at $x: $f", f >= -1.0f && f < 1.0f)
    }
  }

  @Test
  fun gainOfOneIsPreservedAcrossTheEntireRange() {
    // Direct ratio check: output == input / 32768 for a dense sweep — proves the
    // transfer function is exactly unity gain (no hidden scaling curve).
    var checked = 0
    for (x in -32768..32767 step 31) { // 2118 sampled points across the range
      val expected = x.toFloat() / 32768f
      assertEquals("gain mismatch at $x", expected, pcm16ToNormalized(x.toShort()), 0f)
      checked++
    }
    assertTrue(checked > 2000)
  }
}

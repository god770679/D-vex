package com.example.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SceneAnalyzer]'s result-shaping rules (Phase 2).
 *
 * ML Kit's real detectors require Play-services-backed native inference that cannot
 * run on the JVM, so these tests exercise the exact pure logic the live analyzer
 * applies to detector output — threshold filtering, label ranking, face counting,
 * and the empty-frame honesty contract — plus [VisionResult] semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SceneAnalyzerTest {

  private lateinit var analyzer: SceneAnalyzer

  @Before
  fun setUp() {
    analyzer = SceneAnalyzer()
  }

  // --- Threshold / ranking rules ---

  @Test
  fun labelsBelowThresholdAreFilteredOut() {
    val result = analyzer.buildSceneAnalysis(
      detectedLabels = listOf(
        "Sky" to 0.9f,
        "Low confidence thing" to 0.59f, // just below 0.6 — must be dropped
        "Food" to 0.61f // just above — must be kept
      ),
      faceCount = 0,
      timestamp = 100L
    )

    assertEquals(listOf("Sky", "Food"), result.labels.map { it.text })
    assertEquals(2, result.labels.size)
  }

  @Test
  fun labelsSortedByConfidenceAndCappedAtMaxLabels() {
    val result = analyzer.buildSceneAnalysis(
      detectedLabels = listOf(
        "A" to 0.7f,
        "B" to 0.95f,
        "C" to 0.85f,
        "D" to 0.8f,
        "E" to 0.65f,
        "F" to 0.99f, // 6th above-threshold label — must be cut by take(5)
        "G" to 0.75f
      ),
      faceCount = 0,
      timestamp = 100L
    )

    assertEquals(SceneAnalyzer.MAX_LABELS, result.labels.size)
    // Top 5 by confidence, descending: G (0.75) outranks A (0.70).
    assertEquals(listOf("F", "B", "C", "D", "G"), result.labels.map { it.text })
  }

  @Test
  fun confidenceEqualsHighestKeptLabelConfidence() {
    val result = analyzer.buildSceneAnalysis(
      detectedLabels = listOf("Low" to 0.61f, "High" to 0.93f),
      faceCount = 0,
      timestamp = 100L
    )
    assertEquals(0.93f, result.confidence, 0.0001f)
  }

  // --- Face counting (count only, no identity) ---

  @Test
  fun faceCountReflectsNumberOfDetectedFaces() {
    val result = analyzer.buildSceneAnalysis(
      detectedLabels = emptyList(),
      faceCount = 3,
      timestamp = 100L
    )
    assertEquals(3, result.faceCount)
    assertEquals(0f, result.confidence, 0f) // no labels -> confidence stays 0
  }

  @Test
  fun faceCountIsZeroWhenNoFacesDetected() {
    val result = analyzer.buildSceneAnalysis(
      detectedLabels = listOf("Person" to 0.8f),
      faceCount = 0,
      timestamp = 100L
    )
    assertEquals(0, result.faceCount)
    // Honesty: a label saying "Person" is NOT a face count.
    assertEquals(1, result.labels.size)
  }

  // --- Empty-frame honesty contract ---

  @Test
  fun frameWithNoLabelsAndNoFacesYieldsValidEmptyResult() {
    val result = analyzer.buildSceneAnalysis(
      detectedLabels = emptyList(),
      faceCount = 0,
      timestamp = 123L
    )

    assertTrue(result.labels.isEmpty())
    assertEquals(0, result.faceCount)
    assertEquals(0f, result.confidence, 0f)
    assertEquals(123L, result.timestamp)
  }

  // --- VisionResult honesty semantics (what the StateFlow carries) ---

  @Test
  fun visionResultWithEmptyLabelsIsNotReliable() {
    val empty = VisionResult(frameCount = 1, analyzerReady = true, timestamp = 1L)
    assertFalse(empty.isReliable)

    val withLabels = VisionResult(
      frameCount = 2,
      labels = listOf(VisionResult.Label("Sky", 0.9f)),
      analyzerReady = true,
      timestamp = 2L
    )
    assertTrue(withLabels.isReliable)
  }

  @Test
  fun visionResultCarriesErrorHonestly() {
    val errored = VisionResult(
      frameCount = 3,
      labels = listOf(VisionResult.Label("Sky", 0.9f)), // labels must not mask an error
      error = "detector_down"
    )
    assertFalse(errored.isReliable)
    assertEquals("detector_down", errored.error)
  }

  @Test
  fun visionResultDefaultsAreHonestZeros() {
    val result = VisionResult()
    assertTrue(result.labels.isEmpty())
    assertEquals(0, result.faceCount)
    assertEquals(0f, result.confidence, 0f)
    assertEquals(0L, result.timestamp)
    assertNull(result.error)
  }
}

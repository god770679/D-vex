package com.example.voice

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REAL-DEVICE WAKE DIAGNOSTICS GUARD — source-level (same pattern as
 * VoiceAudioHygieneTest / LatencyInstrumentationGuardTest).
 *
 * The physical phone can only be diagnosed through logcat, so every marker a
 * device session needs is pinned here. From one armed session the logs must be
 * able to answer, in order:
 *
 *  1. INIT      — engine built (with model version + init duration) and the EXACT
 *                 keyword/threshold configuration it was built with;
 *  2. CAPTURE   — the microphone actually opened, with which parameters, and that
 *                 frames are being read (level-only, never content);
 *  3. INFERENCE — the native transducer is decoding (heartbeat), not just reading;
 *  4. DETECTION — whether the model ever fired (raw detection + gate outcomes) —
 *                 the line that separates "audio/inference dead" from "detections
 *                 suppressed";
 *  5. LIFECYCLE — why capture ended, plus the manager's start/stop/retry lines.
 *
 * Also pins the shipped KWS tuning constants (threshold 0.25 etc.) so a silent
 * change to the detection decision surface fails the build.
 */
class WakeDiagnosticsGuardTest {

  private fun source(relativePath: String): String {
    val candidates = listOf(File(".", relativePath), File("app", relativePath))
    val file = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "Could not locate $relativePath from ${File(".").absolutePath}; the guard must not pass silently",
      )
    return file.readText()
  }

  /** Strips line comments (and everything after them) so comment mentions don't count. */
  private fun codeOnly(text: String): String =
    text.lineSequence().joinToString("\n") { line -> line.substringBefore("//") }

  private val detector: String by lazy {
    codeOnly(source("src/main/java/com/example/voice/SherpaOnnxWakeWordDetector.kt"))
  }

  private val manager: String by lazy {
    codeOnly(source("src/main/java/com/example/voice/WakeWordManager.kt"))
  }

  private fun assertMarker(marker: String, inSource: String, why: String) {
    assertTrue("$why — missing marker \"$marker\"", inSource.contains(marker))
  }

  // 1. INIT ------------------------------------------------------------------

  @Test
  fun initResultAndExactConfigurationAreLoggable() {
    assertMarker(
      "Offline wake engine initialized in",
      detector,
      "init duration/version must be provable from logcat",
    )
    assertMarker(
      "KWS config: keyword=",
      detector,
      "the exact keyword handed to the native runtime and the KWS parameters must be provable",
    )
    assertMarker("keywords_threshold=", detector, "threshold decisions must name the threshold")
  }

  // 2. CAPTURE ---------------------------------------------------------------

  @Test
  fun microphoneStartAndAudioFlowAreLoggable() {
    assertMarker(
      "Microphone started: rate=",
      detector,
      "a successful mic open with its parameters must be provable (failures already log)",
    )
    assertMarker(
      "Frame level: frames=",
      detector,
      "frame-flow evidence (level only, no content) must exist",
    )
  }

  // 3. INFERENCE -------------------------------------------------------------

  @Test
  fun nativeInferenceHeartbeatIsLoggable() {
    assertMarker(
      "Inference heartbeat: decode_steps=",
      detector,
      "logcat must distinguish 'reading silence' from 'native transducer decoding'",
    )
  }

  // 4. DETECTION -------------------------------------------------------------

  @Test
  fun rawDetectionsAndGateOutcomesAreLoggable() {
    assertMarker(
      "Raw wake detection from engine:",
      detector,
      "logcat must separate 'model never fired' from 'fired but suppressed'",
    )
    assertMarker(
      "Wake detection suppressed:",
      detector,
      "suppression-gate outcomes must be visible",
    )
    assertMarker(
      "Offline wake-word triggered:",
      detector,
      "accepted triggers must be visible",
    )
  }

  // 5. LIFECYCLE -------------------------------------------------------------

  @Test
  fun captureExitAndManagerLifecycleAreLoggable() {
    assertMarker(
      "Capture loop exited (running=",
      detector,
      "why listening ended (and how many frames it processed) must be provable",
    )
    assertMarker("Wake word listening started", manager, "manager arm events must be visible")
    assertMarker("Wake word listening stopped", manager, "manager stop events must be visible")
    assertMarker("Wake init failed (attempt #", manager, "rate-limited init-failure events must be visible")
    assertMarker("Wake init retry attempt #", manager, "rate-limited retry events must be visible")
  }

  // TUNING PINNED ------------------------------------------------------------

  @Test
  fun shippedKwsTuningSurfaceIsPinned() {
    assertMarker("KEYWORDS_THRESHOLD = 0.25f", detector, "the 0.25 detection threshold must not drift")
    assertMarker("KEYWORDS_SCORE = 1.5f", detector, "the keywords_score must not drift silently")
    assertMarker("NUM_TRAILING_BLANKS = 2", detector, "num_trailing_blanks must not drift silently")
    assertMarker("SAMPLE_RATE = 16000", detector, "capture sample rate must not drift silently")
    assertMarker("CHUNK_SAMPLES = 1600", detector, "capture chunk must not drift silently")
  }
}

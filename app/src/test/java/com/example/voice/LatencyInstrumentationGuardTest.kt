package com.example.voice

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE-A LATENCY INSTRUMENTATION GUARD — source-level (same pattern as
 * VoiceAudioHygieneTest). The three measurable voice latencies (wake trigger ->
 * listening, wake trigger -> recognizer capturing, end of user turn -> first
 * audible response) are logged through fixed, greppable "LAT" markers so a real
 * device run can measure them from logcat. This guard pins those markers AND
 * that the instrumentation stays log-only: every LAT line is a single Log call,
 * anchored on monotonic uptime — no timing value may ever gate voice behavior.
 */
class LatencyInstrumentationGuardTest {

  private fun sourceFile(relativePath: String): File {
    val candidates = listOf(File(".", relativePath), File("app", relativePath))
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "Could not locate $relativePath from ${File(".").absolutePath}; the guard must not pass silently",
      )
  }

  /** Strips line comments (and everything after them) so comment mentions don't count. */
  private fun codeOnly(text: String): String =
    text.lineSequence().joinToString("\n") { line -> line.substringBefore("//") }

  private val repository: String
    get() = codeOnly(
      sourceFile("src/main/java/com/example/repository/AssistantRepository.kt").readText(),
    )

  private val recognizer: String
    get() = codeOnly(
      sourceFile("src/main/java/com/example/voice/SpeechRecognizerManager.kt").readText(),
    )

  @Test
  fun wakePathEmitsBothLatencyAnchors() {
    assertTrue(
      "wake trigger anchor missing (T1/T2 origin)",
      repository.contains("LAT wake_trigger ms="),
    )
    assertTrue(
      "wake -> listening latency (T1) marker missing",
      repository.contains("LAT wake_trigger_to_listening ms="),
    )
    assertTrue(
      "wake -> listen request latency (T2 part 1) marker missing",
      repository.contains("LAT listen_requested since_wake_ms="),
    )
  }

  @Test
  fun recognizerEmitsReadyForSpeechAnchor() {
    assertTrue(
      "listen request -> recognizer capturing (T2 part 2) marker missing",
      recognizer.contains("LAT ready_for_speech ms="),
    )
  }

  @Test
  fun turnPathEmitsEndOfTurnToFirstAudibleAnchors() {
    assertTrue(
      "end-of-turn anchor missing",
      repository.contains("LAT end_of_turn ms="),
    )
    assertTrue(
      "command-processing anchor missing",
      repository.contains("LAT command_processing since_turn_start_ms="),
    )
    assertTrue(
      "end-of-turn -> first audible response (T3) marker missing",
      repository.contains("LAT end_of_turn_to_speak ms="),
    )
  }

  @Test
  fun instrumentationIsLogOnlyOnUptimeClocks() {
    // Every LAT emission must be a single-line Log call (log-only, no behavior).
    val latLines = repository.lineSequence()
      .filter { it.contains("\"LAT ") }
      .toList()
    assertTrue("expected at least 5 LAT markers in the repository, got ${latLines.size}", latLines.size >= 5)
    latLines.forEach { line ->
      assertTrue("LAT line must be a Log call: $line", line.trimStart().startsWith("Log."))
    }
    val recognizerLatLines = recognizer.lineSequence()
      .filter { it.contains("\"LAT ") }
      .toList()
    assertTrue("expected 1 LAT marker in the recognizer, got ${recognizerLatLines.size}", recognizerLatLines.size == 1)
    recognizerLatLines.forEach { line ->
      assertTrue("LAT line must be a Log call: $line", line.trimStart().startsWith("Log."))
    }

    // Anchors must be monotonic uptime so wall-clock (NTP) jumps can't corrupt a delta.
    assertTrue(
      "repository latency anchors must use SystemClock.uptimeMillis()",
      repository.contains("SystemClock.uptimeMillis()"),
    )
    assertTrue(
      "recognizer latency anchors must use SystemClock.uptimeMillis()",
      recognizer.contains("SystemClock.uptimeMillis()"),
    )
  }
}

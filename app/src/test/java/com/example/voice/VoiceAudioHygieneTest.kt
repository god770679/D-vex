package com.example.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUDIO HYGIENE GUARD — the voice pipeline must never touch another app's audio.
 *
 * Reads the sources directly (same pattern as UserFacingPhraseGuardTest) because a
 * single stream-mute or audio-focus request here would silently break
 * Spotify/YouTube/Instagram playback for the user, and no behavioural test would
 * necessarily catch it. Comment text is stripped before matching so the
 * explanatory comments that NAME the forbidden APIs do not trip the guard.
 *
 * Also pins the recognizer topology: exactly one command SpeechRecognizerManager
 * and one WakeWordManager are constructed by the orchestrator — no duplicate
 * voice systems, no second recognizer factory hiding in the repository.
 */
class VoiceAudioHygieneTest {

  private val audioHygieneSources = listOf(
    "src/main/java/com/example/voice/SpeechRecognizerManager.kt",
    "src/main/java/com/example/voice/WakeWordManager.kt",
    "src/main/java/com/example/voice/TextToSpeechManager.kt",
    "src/main/java/com/example/repository/AssistantRepository.kt"
  )

  /** APIs that would mute another app's audio or steal its focus. */
  private val forbiddenTokens = listOf(
    "STREAM_MUSIC",
    "adjustStreamVolume",
    "setStreamMute",
    "setStreamVolume",
    "requestAudioFocus",
    "abandonAudioFocus",
    "AudioFocusRequest",
    "ToneGenerator",
    "SoundPool",
    "playSoundEffect",
    "AudioTrack",
    "RingtoneManager"
  )

  private fun sourceFile(relativePath: String): File {
    val candidates = listOf(File(".", relativePath), File("app", relativePath))
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "Could not locate $relativePath from ${File(".").absolutePath}; the guard must not pass silently"
      )
  }

  /** Strips line comments (and everything after them) so comment mentions don't count. */
  private fun codeOnly(text: String): String =
    text.lineSequence().joinToString("\n") { line -> line.substringBefore("//") }

  @Test
  fun voicePipelineNeverManipulatesStreamsOrAudioFocus() {
    audioHygieneSources.forEach { path ->
      val code = codeOnly(sourceFile(path).readText())
      forbiddenTokens.forEach { token ->
        assertTrue(
          "$path must not use $token (it would mute or steal another app's audio)",
          !code.contains(token)
        )
      }
    }
  }

  @Test
  fun orchestratorConstructsExactlyOneCommandRecognizerAndOneWakeManager() {
    val repo = codeOnly(
      sourceFile("src/main/java/com/example/repository/AssistantRepository.kt").readText()
    )
    assertEquals(
      "exactly ONE SpeechRecognizerManager in the orchestrator",
      1,
      Regex("""SpeechRecognizerManager\(""").findAll(repo).count()
    )
    assertEquals(
      "exactly ONE WakeWordManager in the orchestrator",
      1,
      Regex("""WakeWordManager\(""").findAll(repo).count()
    )
    assertEquals(
      "the repository must never create a recognizer itself",
      0,
      Regex("""createSpeechRecognizer""").findAll(repo).count()
    )
  }

  @Test
  fun eachVoiceRoleOwnsExactlyOneRecognizerFactory() {
    // One factory per ROLE (command vs wake) — never a restart loop of factories.
    val command = codeOnly(
      sourceFile("src/main/java/com/example/voice/SpeechRecognizerManager.kt").readText()
    )
    val wake = codeOnly(
      sourceFile("src/main/java/com/example/voice/WakeWordManager.kt").readText()
    )
    assertEquals(1, Regex("""SpeechRecognizer\.createSpeechRecognizer""").findAll(command).count())
    assertEquals(1, Regex("""SpeechRecognizer\.createSpeechRecognizer""").findAll(wake).count())
  }
}

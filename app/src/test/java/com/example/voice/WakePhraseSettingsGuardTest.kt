package com.example.voice

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guards for the wake-phrase configuration path (same pattern as
 * VoiceAudioHygieneTest): the storage contract must keep D-VEX as the default,
 * must persist through the EXISTING preference keys, must validate before
 * persisting, and must push changes to the active detector — and the
 * production standby detector must be the offline sherpa-onnx implementation.
 */
class WakePhraseSettingsGuardTest {

  private fun source(relativePath: String): String {
    val candidates = listOf(File(".", relativePath), File("app", relativePath))
    val file = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "Could not locate $relativePath from ${File(".").absolutePath}; the guard must not pass silently",
      )
    return file.readText()
  }

  private val repository: String by lazy {
    source("src/main/java/com/example/repository/AssistantRepository.kt")
  }

  private val wakeWordManager: String by lazy {
    source("src/main/java/com/example/voice/WakeWordManager.kt")
  }

  @Test
  fun defaultWakePhraseInStorageIsDvex() {
    assertTrue(
      "AssistantRepository must default the persisted wake phrase to D-VEX",
      repository.contains("\"wake_phrase\", \"D-VEX\""),
    )
    assertTrue(
      "AssistantRepository must default the persisted wake keyword to D-VEX",
      repository.contains("\"wake_word_keyword\", \"D-VEX\""),
    )
  }

  @Test
  fun wakePhraseIsPersistedThroughExistingPreferenceKeys() {
    assertTrue(
      "updateSettings must keep writing the existing wake_phrase key",
      repository.contains("putString(\"wake_phrase\""),
    )
    assertTrue(
      "loadSettings must keep reading the existing wake_phrase key",
      repository.contains("getString(\"wake_phrase\""),
    )
  }

  @Test
  fun settingsLayerValidatesBeforePersisting() {
    assertTrue(
      "updateSettings must run the offline tokenizer before persisting a wake phrase",
      repository.contains("DvexWakePhraseValidator.validate"),
    )
    assertTrue(
      "an invalid phrase must be ignored rather than stored",
      repository.contains("Ignoring invalid wake phrase"),
    )
  }

  @Test
  fun phraseChangesReachTheActiveDetector() {
    assertTrue(
      "updateSettings must push the (validated) phrase to the wake-word manager",
      repository.contains("wakeWordManager.updateKeyword("),
    )
    assertTrue(
      "WakeWordManager must dispatch keyword updates through the capability interface, not a concrete cast",
      wakeWordManager.contains("detector as? WakeWordCapability"),
    )
    assertTrue(
      "WakeWordManager must no longer cast to the legacy concrete detector for keyword updates",
      !wakeWordManager.contains("as? AndroidSpeechWakeWordDetector)"),
    )
  }

  @Test
  fun productionDefaultDetectorIsTheOfflineSherpaDetector() {
    assertTrue(
      "WakeWordManager's default detector must be the offline sherpa-onnx detector",
      wakeWordManager.contains("= SherpaOnnxWakeWordDetector(context)"),
    )
    assertTrue(
      "the legacy detector must not remain the default standby detector",
      !wakeWordManager.contains("= AndroidSpeechWakeWordDetector(context)"),
    )
    assertTrue(
      "the offline detector must be the standby implementation behind WakeWordDetector",
      wakeWordManager.contains(": WakeWordDetector, WakeWordCapability"),
    )
  }
}

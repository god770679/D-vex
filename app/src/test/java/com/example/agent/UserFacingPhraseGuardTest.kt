package com.example.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * INCREMENT 1 — authoring-level language guard.
 *
 * The agent / control layers author the text D-VEX falls back to when the LLM is
 * unavailable (device results, permission notices, confirmations). Those sentences
 * are spoken verbatim, so they must never contain internal phrasing or honorific
 * filler: "Sir", "Standing by", "Listening for command", routing labels, or a canned
 * "ready" acknowledgement.
 *
 * This reads the sources directly, because a string that is never executed in a unit
 * test can still reach the user's ear on a real device.
 *
 * DELIBERATELY EXCLUDED (and why):
 *  - `DvexResponseGenerator.kt` — legitimately contains "Sir" inside the model's
 *    prompt rules (banning honorifics) and in the honorific stripper itself.
 *  - `DvexToolRouter.kt` — one comment explaining the removed robotic behaviour.
 *  - `TamilTransliteration.kt` — transliteration data, not speech.
 *
 * `AlwaysReadySettingsDialog.kt` used to be excluded as static copy; its obsolete
 * "Play verbal response (\"Yes Sir\") upon wake-word trigger" toggle has since been
 * removed, so the file is now covered by this guard too.
 */
class UserFacingPhraseGuardTest {

  private val authoredSpeechSources = listOf(
    "src/main/java/com/example/agent/DvexDefaultActions.kt",
    "src/main/java/com/example/agent/DvexAgentEngine.kt",
    "src/main/java/com/example/agent/DvexCapability.kt",
    "src/main/java/com/example/control/DeviceControlRepository.kt",
    "src/main/java/com/example/control/AppControlAgent.kt",
    "src/main/java/com/example/data/remote/RealTimeWebService.kt",
    "src/main/java/com/example/ui/components/AlwaysReadySettingsDialog.kt"
  )

  private val cannedConversationalPhrases = listOf(
    "Yes D-VEX ready.",
    "Yes. சொல்லுங்க.",
    "Yes Sir",
    "Standing by",
    "Listening for command",
    "Conversation type:",
    "Conversation: greeting"
  )

  private fun sourceFile(relativePath: String): File {
    val candidates = listOf(File(".", relativePath), File("app", relativePath))
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "Could not locate $relativePath from ${File(".").absolutePath}; the guard must not pass silently"
      )
  }

  @Test
  fun authoredSpeechNeverContainsTheHonorific() {
    val honorific = Regex("\\bSir\\b", RegexOption.IGNORE_CASE)
    authoredSpeechSources.forEach { path ->
      sourceFile(path).readLines().forEachIndexed { index, line ->
        assertFalse(
          "$path:${index + 1} still contains honorific filler: ${line.trim()}",
          honorific.containsMatchIn(line)
        )
      }
    }
  }

  @Test
  fun authoredSpeechContainsNoCannedConversationalLines() {
    authoredSpeechSources.forEach { path ->
      val text = sourceFile(path).readText()
      cannedConversationalPhrases.forEach { phrase ->
        assertFalse("$path still contains the canned line \"$phrase\"", text.contains(phrase))
      }
    }
  }

  @Test
  fun verificationJargonNeverAppearsInAuthoredSpeech() {
    // "(unverified)" used to be appended to handler messages. Verification state
    // now travels as DvexToolStatus, so it must not be narrated to the user.
    authoredSpeechSources.forEach { path ->
      assertFalse(
        "$path narrates verification state",
        sourceFile(path).readText().contains("(unverified)")
      )
    }
  }
}

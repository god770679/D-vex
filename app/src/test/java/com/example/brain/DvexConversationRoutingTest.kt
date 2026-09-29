package com.example.brain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Conversation routing contract.
 *
 * The language layer (Gemini) owns every user-facing sentence. The deterministic
 * tool layer owns actions, permissions, confirmations and verified results. These
 * tests pin the boundary between the two so conversational hardcoding cannot creep
 * back into the tool layer:
 *
 * - input that matches no action is NOT a failure — it is routed to the LLM
 * - conversation / general-question results carry NO canned spoken text
 * - genuinely garbled speech still gets the deterministic, honorific-free
 *   clarification (that one is a "never guess" safety net, not conversation)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexConversationRoutingTest {

  private lateinit var appContext: Context
  private lateinit var router: DvexToolRouter

  @Before
  fun setUp() {
    appContext = ApplicationProvider.getApplicationContext()
    val appLauncher = AppLauncherRepository(appContext)
    router = DvexToolRouter(
      appContext,
      appLauncher,
      DeviceControlRepository(appContext, appLauncher)
    )
  }

  @Test
  fun unrecognizedInputIsRoutedToTheLlmNotACannedReply() = runBlocking {
    val result = router.execute(DvexIntent.Unknown("something unmatched"))

    assertEquals("conversation", result.toolName)
    assertEquals(DvexToolStatus.SUCCESS, result.status)
    assertEquals(
      "an unmatched utterance must not carry a canned reply",
      "",
      result.spokenText
    )
  }

  @Test
  fun conversationTurnCarriesNoCannedSpokenText() = runBlocking {
    val result = router.execute(DvexIntent.Conversation("how are you"))

    assertEquals("conversation", result.toolName)
    assertEquals("the LLM owns the reply, not the router", "", result.spokenText)
  }

  @Test
  fun generalQuestionCarriesNoCannedSpokenTextEvenWhenNoWebAnswerExists() = runBlocking {
    // A question the tool layer cannot answer factually must hand an EMPTY
    // spokenText to the generator, so the LLM answers instead of a template.
    val result = router.execute(
      DvexIntent.GeneralQuestion("explain how tides work"),
      ConversationContext()
    )

    assertEquals("general_qa", result.toolName)
    if (result.status == DvexToolStatus.SUCCESS && result.spokenText.isBlank()) {
      assertTrue(true)
    } else {
      // If a live web answer existed it is real verified data, never a template.
      assertTrue(
        "spoken text must be real web data, was: ${result.spokenText}",
        result.spokenText.isNotBlank() && result.message.startsWith("Web answer")
      )
    }
  }

  @Test
  fun garbledSpeechStillTakesTheDeterministicClarification() {
    val detection = IntentDetector().detectIntent("hnnkssss", ConversationContext())

    assertTrue(
      "genuinely garbled speech must still ask instead of guessing",
      detection.intent is DvexIntent.LowConfidence
    )
    val prompt = (detection.intent as DvexIntent.LowConfidence).clarificationPrompt
    assertTrue("clarification must not be blank", prompt.isNotBlank())
    assertFalse(
      "the hardcoded honorific must be gone from the clarification path: $prompt",
      prompt.contains("Sir")
    )
  }
}

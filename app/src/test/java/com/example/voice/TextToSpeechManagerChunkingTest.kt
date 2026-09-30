package com.example.voice

import android.os.Looper
import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * PART 2 END-TO-END GUARD: what actually reaches the TTS engine.
 *
 * Robolectric's TTS shadow records every utterance handed to the engine and reports each
 * one back through the `UtteranceProgressListener`, so this covers the real path the user
 * hears:
 *
 *  - a reply shorter than the old 220-character cap is spoken whole, unchanged;
 *  - a LONG reply is spoken in several chunks, in order, and the WHOLE text is reached —
 *    the old behaviour stopped after the first ~220 characters;
 *  - the completion callback fires exactly once, after the last chunk;
 *  - a barge-in ([TextToSpeechManager.stop]) cancels the queue and reports no completion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TextToSpeechManagerChunkingTest {

  private lateinit var manager: TextToSpeechManager
  private lateinit var shadow: ShadowTextToSpeech

  private val longReply =
    "Here is what I found about your request. The weather in Chennai is warm and humid today, " +
      "with a high of thirty-four degrees and a light breeze from the east. Tomorrow looks a " +
      "little cooler, so an umbrella is a good idea if you are heading out in the evening."

  @Before
  fun setUp() {
    ShadowTextToSpeech.reset()
    manager = TextToSpeechManager(RuntimeEnvironment.getApplication())
    val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
      ?: throw AssertionError("the manager did not create a TTS engine")
    shadow = shadowOf(engine)
    // A real engine reports readiness asynchronously through onInit; drive the same callback
    // here so the manager applies its voice/prosody/progress setup exactly as on device.
    shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
    settle()
  }

  private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

  /**
   * Drains the main looper twice: the engine reports each utterance through a posted
   * `onDone`, and the manager posts its completion callback from inside that callback.
   */
  private fun settle() {
    idleMainLooper()
    idleMainLooper()
  }

  @Test
  fun aShortReplyIsSpokenWholeAndUntruncated() {
    val reply = "Done. YouTube is open."

    manager.speak(reply)

    assertEquals(listOf(reply), shadow.spokenTextList)
    assertEquals("a fresh reply replaces whatever was playing", TextToSpeech.QUEUE_FLUSH, shadow.queueMode)
  }

  @Test
  fun aLongReplyIsSpokenInOrderAndItsWholeTailIsReached() {
    assertTrue("fixture must exceed the old truncation cap", longReply.length > 220)
    var completions = 0

    manager.speak(longReply) { completions++ }
    assertTrue("D-VEX is speaking", manager.isSpeaking.value)
    assertEquals("nothing has finished until the engine reports back", 0, completions)

    settle()

    val spoken = shadow.spokenTextList
    assertTrue("a long reply must become several utterances, got ${spoken.size}", spoken.size >= 2)
    spoken.forEach { chunk ->
      assertTrue(
        "engine chunk of ${chunk.length} chars exceeds ${TextToSpeechChunker.MAX_CHUNK_CHARS}: \"$chunk\"",
        chunk.length <= TextToSpeechChunker.MAX_CHUNK_CHARS
      )
    }
    assertEquals("the whole reply is spoken, in order and without overlap", longReply, spoken.joinToString(" "))
    assertEquals("the old truncation is gone: the reply must not stop early", longReply.takeLast(24), spoken.last().takeLast(24))
    assertEquals("the extra chunks are queued behind the first", TextToSpeech.QUEUE_ADD, shadow.queueMode)
    assertEquals("exactly one completion per reply", 1, completions)
    assertFalse("speaking has finished", manager.isSpeaking.value)
  }

  @Test
  fun interruptingStopsTheQueueAndReportsNoCompletion() {
    var completions = 0
    manager.speak(longReply) { completions++ }

    manager.stop()
    settle()

    assertTrue("the engine queue is cancelled", shadow.isStopped)
    assertFalse(manager.isSpeaking.value)
    assertEquals("an interrupted reply must not report that it finished speaking", 0, completions)
  }

  @Test
  fun speechNeverCarriesMarkdownNoise() {
    manager.speak("**Opening** YouTube [now] {please wait}")

    val spoken = shadow.spokenTextList.joinToString(" ")
    listOf("*", "{", "}", "[", "]").forEach { noise ->
      assertFalse("markdown noise \"$noise\" reached the engine: \"$spoken\"", spoken.contains(noise))
    }
    assertTrue("the actual words survive: \"$spoken\"", spoken.contains("Opening YouTube"))
    assertTrue("the actual words survive: \"$spoken\"", spoken.contains("please wait"))
  }
}

package com.example.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 2 REGRESSION GUARD: one spoken reply = one completion callback.
 *
 * A long reply is now several utterances, and the repository's callback is what re-arms the
 * wake word and ends the voice session. Firing it after the FIRST chunk would re-open the
 * microphone while D-VEX is still talking; never firing it would hang the session. This
 * pins both the queue-order rule (only the last chunk completes) and the interruption rule
 * (an abandoned reply reports nothing, and the NEXT reply cannot be ended by its stale
 * callbacks).
 */
class SpeechChunkTrackerTest {

  @Test
  fun onlyTheLastChunkOfAQueuedReplyEndsIt() {
    val tracker = SpeechChunkTracker()
    tracker.begin(listOf("chunk-1", "chunk-2", "chunk-3"))
    assertTrue(tracker.isActive)

    assertFalse("first chunk must not end the reply", tracker.complete("chunk-1"))
    assertFalse("middle chunk must not end the reply", tracker.complete("chunk-2"))
    assertTrue("last chunk ends the reply", tracker.complete("chunk-3"))
    assertFalse("the reply cannot be ended twice", tracker.complete("chunk-3"))
    assertFalse(tracker.isActive)
  }

  @Test
  fun outOfOrderCompletionIsCountedButOnlyTheLastOneEndsTheReply() {
    val tracker = SpeechChunkTracker()
    tracker.begin(listOf("a", "b"))

    assertFalse(tracker.complete("b"))
    assertTrue("after both chunks the reply is finished", tracker.complete("a"))
  }

  @Test
  fun aStaleUtteranceFromASupersededReplyNeverEndsTheCurrentOne() {
    val tracker = SpeechChunkTracker()
    // Reply two starts while the engine still reports the flushed utterance of reply one.
    tracker.begin(listOf("new-1", "new-2"))

    assertFalse("stale ids are ignored", tracker.complete("old-1"))
    assertFalse("stale ids are ignored", tracker.fail("old-2"))
    assertTrue("the current reply is still tracked", tracker.isActive)
    assertFalse(tracker.complete("new-1"))
    assertTrue(tracker.complete("new-2"))
  }

  @Test
  fun aStoppedOrFailedChunkEndsTheReplyInsteadOfHangingIt() {
    listOf("stopped", "errored").forEach { name ->
      val tracker = SpeechChunkTracker()
      tracker.begin(listOf("chunk-1", "chunk-2", "chunk-3"))
      assertTrue(
        "$name chunk belongs to this reply, so the turn must end",
        tracker.fail("chunk-2")
      )
      assertFalse("the rest of the reply is dropped with it", tracker.isActive)
    }
  }

  @Test
  fun afterAnInterruptionNothingReportsCompletion() {
    val tracker = SpeechChunkTracker()
    tracker.begin(listOf("chunk-1", "chunk-2"))
    tracker.clear()

    assertFalse(tracker.complete("chunk-1"))
    assertFalse(tracker.fail("chunk-2"))
    assertFalse("a cleared reply is over", tracker.isActive)
  }

  @Test
  fun anEngineThatDoesNotNameItsUtteranceStillFinishesTheReply() {
    val tracker = SpeechChunkTracker()
    tracker.begin(listOf("chunk-1", "chunk-2"))

    assertTrue("a nameless completion drains the reply rather than hanging it", tracker.complete(null))
    assertFalse(tracker.isActive)
  }

  @Test
  fun aStaleChunkStartIsNotPartOfTheCurrentReply() {
    val tracker = SpeechChunkTracker()
    tracker.begin(listOf("chunk-1"))

    assertTrue(tracker.isTracked("chunk-1"))
    assertFalse("a flushed utterance from an earlier reply is not speaking now", tracker.isTracked("old-1"))
    assertFalse(tracker.isTracked(null))
  }

  @Test
  fun nothingIsReportedWhenNoReplyIsTracked() {
    val tracker = SpeechChunkTracker()
    assertFalse(tracker.complete("anything"))
    assertFalse(tracker.fail("anything"))
    assertFalse(tracker.complete(null))
  }
}

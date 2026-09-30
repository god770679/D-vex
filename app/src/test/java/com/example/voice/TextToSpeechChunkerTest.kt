package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 2 REGRESSION GUARD: long replies are CHUNKED, never truncated.
 *
 * [TextToSpeechManager] used to cut everything after ~220 characters and speak only the
 * first part, so half of a real answer was never heard. These tests pin the replacement:
 * every chunk is at most [TextToSpeechChunker.MAX_CHUNK_CHARS] characters, the chunks are
 * produced in speaking order, and the WHOLE text survives — no dropped words, no
 * duplicated overlap, no word cut in half.
 *
 * Token-level integrity is asserted by comparing the words of the chunks with the words of
 * the input, and length integrity by re-joining the chunks with a single space and getting
 * the input back.
 */
class TextToSpeechChunkerTest {

  private val longEnglish =
    "Here is what I found about your request. The weather in Chennai is warm and humid today, " +
      "with a high of thirty-four degrees and a light breeze from the east. Tomorrow looks a " +
      "little cooler, so an umbrella is a good idea if you are heading out in the evening."

  private val longTamil = listOf(
    "இன்று சென்னையில் வானிலை வெப்பமாகவும் ஈரப்பதமாகவும் இருக்கும்.",
    "அதிகபட்ச வெப்பநிலை முப்பத்து நான்கு டிகிரி ஆக இருக்கும் என்று எதிர்பார்க்கிறார்கள்.",
    "நாளை சற்று குளிர்ச்சியாக இருக்கும், மாலையில் வெளியே சென்றால் குடை எடுத்துச் செல்வது நல்லது.",
    "மழை வாய்ப்பு அதிகமாக இருக்கிறது, எனவே வெளியே செல்லும் முன் வானிலை பார்த்துக் கொள்ளுங்கள்."
  ).joinToString(" ")

  private val longMixed =
    "Good question. சென்னையில் இன்று வானிலை வெப்பமாக இருக்கிறது and the humidity is high, " +
      "so it will feel hotter than the actual temperature. Tomorrow looks a little cooler, " +
      "மாலையில் குடை எடுத்துச் செல்வது நல்லது, and the breeze will pick up after sunset."

  /** Exactly 220 characters: the old truncation threshold must not split or cut it. */
  private val exactly220 = "abcde ".repeat(36) + "abcd"

  /** 221 characters: one character over — the tail must still be spoken. */
  private val justOver220 = "abcde ".repeat(36) + "abcde"

  private fun chunk(text: String) = TextToSpeechChunker.chunk(text)

  private fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

  /** Every chunk is a legal spoken piece: non-blank and inside the engine ceiling. */
  private fun assertSpeakable(chunks: List<String>, maxChars: Int = TextToSpeechChunker.MAX_CHUNK_CHARS) {
    assertTrue("expected at least one chunk", chunks.isNotEmpty())
    chunks.forEach { chunk ->
      assertTrue("blank chunk produced: \"$chunk\"", chunk.isNotBlank())
      assertEquals("chunk must not carry outer whitespace", chunk, chunk.trim())
      assertTrue(
        "chunk of ${chunk.length} chars exceeds $maxChars: \"$chunk\"",
        chunk.length <= maxChars
      )
    }
  }

  @Test
  fun shortReplyIsSpokenAsOneUtterance() {
    val reply = "Done. YouTube is open."
    assertEquals(listOf(reply), chunk(reply))
  }

  @Test
  fun exactlyTheOldLimitIsNotSplitOrCut() {
    assertEquals(220, exactly220.length)
    val chunks = chunk(exactly220)
    assertEquals(listOf(exactly220), chunks)
  }

  @Test
  fun oneCharacterOverTheOldLimitKeepsGoingInsteadOfStopping() {
    val chunks = chunk(justOver220)
    assertSpeakable(chunks)
    assertEquals(2, chunks.size)
    assertEquals(justOver220, chunks.joinToString(" "))
    assertTrue("the last chunk must carry the text that used to be dropped", chunks.last().isNotBlank())
  }

  @Test
  fun longEnglishReplyIsFullySpokenInOrder() {
    assertTrue("fixture must exceed the limit", longEnglish.length > 220)
    val chunks = chunk(longEnglish)

    assertSpeakable(chunks)
    assertTrue("a long reply must become several chunks", chunks.size >= 2)
    assertEquals("no words dropped, reordered or duplicated", words(longEnglish), chunks.flatMap { words(it) })
    assertEquals("no overlap, no gaps", longEnglish, chunks.joinToString(" "))
    assertEquals("the reply must not end early", longEnglish.takeLast(3), chunks.last().takeLast(3))
  }

  @Test
  fun longTamilReplyIsFullySpokenInOrder() {
    assertTrue("fixture must exceed the limit", longTamil.length > 220)
    val chunks = chunk(longTamil)

    assertSpeakable(chunks)
    assertTrue(chunks.size >= 2)
    assertEquals(longTamil, chunks.joinToString(" "))
  }

  @Test
  fun mixedTamilAndEnglishReplyIsFullySpokenInOrder() {
    assertTrue("fixture must exceed the limit", longMixed.length > 220)
    val chunks = chunk(longMixed)

    assertSpeakable(chunks)
    assertEquals(longMixed, chunks.joinToString(" "))
  }

  /** A sentence of predictable length, ending in punctuation like real speech does. */
  private fun sentence(prefix: String, words: Int): String =
    "$prefix ${(1..words).joinToString(" ") { "word$it" }}."

  @Test
  fun chunksBreakAtSentenceEndsBeforeFallingBackToWords() {
    // Sentences sized so that two fit in one chunk and the third cannot join them.
    val first = sentence("First sentence", 14)
    val second = sentence("Second sentence", 13)
    val third = sentence("Third sentence", 12)
    val reply = "$first $second $third"
    assertTrue("fixture must exceed the limit", reply.length > 220)
    assertTrue("the first two sentences must fit together", (first + " " + second).length <= 220)

    val chunks = chunk(reply)

    assertSpeakable(chunks)
    assertEquals(2, chunks.size)
    assertTrue(
      "first chunk should end on a sentence boundary: \"${chunks[0]}\"",
      chunks[0].endsWith(".")
    )
    assertEquals(third, chunks[1])
  }

  @Test
  fun punctuationHeavyTextIsNeverSplitInsideAToken() {
    // No space after the commas: those stay inside one token, exactly as an engine reads it.
    val reply = buildString {
      repeat(12) { append("Item number $it,with a comma and no space after it. ") }
    }.trim()
    assertTrue(reply.length > 220)

    val chunks = chunk(reply)

    assertSpeakable(chunks)
    assertEquals(words(reply), chunks.flatMap { words(it) })
    assertEquals(reply, chunks.joinToString(" "))
  }

  @Test
  fun paragraphsBecomeTheirOwnChunksAtTheParagraphBreak() {
    val paraOne = "First paragraph with enough words to be a natural breath of its own."
    val paraTwo = "Second paragraph that follows a real pause in the spoken answer."
    val reply = "$paraOne\n\n$paraTwo\n\n"

    // Each paragraph is inside the limit on its own, so the split happens at the pause
    // between them — not in the middle of either paragraph.
    assertTrue(paraOne.length <= TextToSpeechChunker.MAX_CHUNK_CHARS)
    assertEquals(listOf(paraOne, paraTwo), chunk(reply))
  }

  @Test
  fun aParagraphLongerThanTheLimitIsSplitInside() {
    val longParagraph = (1..8).joinToString(" ") { "Sentence number $it fills this paragraph with words." }
    assertTrue("fixture must exceed the limit", longParagraph.length > 220)

    val chunks = chunk(longParagraph)

    assertSpeakable(chunks)
    assertTrue("a long paragraph must still be split", chunks.size >= 2)
    assertEquals(longParagraph, chunks.joinToString(" "))
  }

  @Test
  fun aSingleWordLongerThanTheLimitStaysWhole() {
    // The one allowed exception: a word is never cut in half, even when it is enormous.
    val hugeWord = "a".repeat(TextToSpeechChunker.MAX_CHUNK_CHARS + 60)
    assertEquals(listOf(hugeWord), chunk(hugeWord))
  }

  @Test
  fun blankRepliesProduceNoChunks() {
    assertEquals(emptyList<String>(), chunk(""))
    assertEquals(emptyList<String>(), chunk("   "))
    assertEquals(emptyList<String>(), chunk("\n\n   \n\n"))
  }

  @Test
  fun chunksComeBackInSpeakingOrder() {
    val chunks = chunk(longEnglish)
    assertTrue(chunks.size >= 2)
    // The first chunk is the beginning of the reply and each following chunk continues it,
    // separated by exactly one space — nothing skipped, nothing repeated.
    assertTrue(longEnglish.startsWith(chunks.first()))
    var consumed = 0
    chunks.forEachIndexed { index, chunk ->
      val at = longEnglish.indexOf(chunk, consumed)
      assertTrue("chunk ${index + 1} must appear in order", at >= consumed)
      if (index > 0) assertEquals("chunks are separated by exactly one space", consumed + 1, at)
      consumed = at + chunk.length
    }
    assertEquals("the last chunk reaches the end of the reply", longEnglish.length, consumed)
  }
}

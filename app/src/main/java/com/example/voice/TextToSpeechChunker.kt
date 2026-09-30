package com.example.voice

/**
 * Natural chunking for spoken replies.
 *
 * D-VEX used to TRUNCATE anything longer than ~220 characters before speaking it, so
 * the second half of a real answer was simply never heard. The fix is not a bigger cap:
 * long replies are split into consecutive chunks that are spoken in order, and the whole
 * text is always spoken.
 *
 * Split order — always the coarsest natural boundary that is available:
 *  1. paragraph breaks (a blank line is a real pause in speech),
 *  2. sentence ends (`.`, `!`, `?`),
 *  3. clause separators (`,`, `;`, `:`, `—`),
 *  4. word boundaries (whitespace).
 *
 * HARD RULES (pinned by TextToSpeechChunkerTest):
 *  - a word is NEVER cut in half; the only piece allowed to exceed the limit is a single
 *    word that is itself longer than the limit;
 *  - chunks never overlap and never drop characters — joining consecutive chunks with a
 *    single space reproduces the input exactly;
 *  - chunk order is the speaking order (the caller plays them sequentially in order).
 *
 * This is pure text logic — no Android types, no engine, no audio focus, no queueing
 * policy. Playing the chunks is the manager's job ([TextToSpeechManager]).
 */
object TextToSpeechChunker {

  /**
   * Maximum length of one spoken chunk. 220 is the size the previous implementation
   * already used as its truncation threshold, so each individual utterance keeps the
   * cadence and engine behaviour the device is known to handle well; what changed is that
   * the remainder is now *spoken* rather than dropped.
   */
  const val MAX_CHUNK_CHARS = 220

  /** Paragraph break: a blank line, whatever indentation the text carried. */
  private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

  private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")

  private val CLAUSE_BREAK = Regex("(?<=[,;:\u2014])\\s+")

  private val WORD_BREAK = Regex("\\s+")

  /** Coarsest-to-finest, tried in order for text that is still too long. */
  private val SPLITTERS: List<(String) -> List<String>> = listOf(
    { text -> text.split(SENTENCE_BREAK) },
    { text -> text.split(CLAUSE_BREAK) },
    { text -> text.split(WORD_BREAK) }
  )

  /**
   * Chunks [text] into speakable pieces of at most [maxChars] characters, in order.
   * Blank input yields no chunks (nothing to say), never a chunk of whitespace.
   */
  fun chunk(text: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
    if (maxChars <= 0) return emptyList()
    val paragraphs = text
      .replace("\r\n", "\n")
      .split(PARAGRAPH_BREAK)
      .map { it.trim() }
      .filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    paragraphs.forEach { paragraph -> chunks += segment(paragraph, maxChars) }
    return chunks
  }

  /** One run of text, packed into pieces of at most [maxChars] characters. */
  private fun segment(text: String, maxChars: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    SPLITTERS.forEach { splitter ->
      val parts = splitter(text).map { it.trim() }.filter { it.isNotEmpty() }
      if (parts.size > 1) return pack(parts, maxChars)
    }
    // A single word longer than the limit: it stays whole — a cut word is worse than a
    // slightly long utterance.
    return listOf(text)
  }

  /** Greedily packs [units] into chunks, recursing only for a unit that is too long. */
  private fun pack(units: List<String>, maxChars: Int): List<String> {
    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
      if (current.isNotEmpty()) {
        chunks += current.toString()
        current.clear()
      }
    }

    units.forEach { unit ->
      when {
        unit.length > maxChars -> {
          flush()
          chunks += segment(unit, maxChars)
        }

        current.isEmpty() -> current.append(unit)

        current.length + 1 + unit.length <= maxChars -> current.append(' ').append(unit)

        else -> {
          flush()
          current.append(unit)
        }
      }
    }
    flush()
    return chunks
  }
}

/**
 * Tracks which chunk utterances of ONE spoken reply are still outstanding.
 *
 * A reply is now several utterances, but the caller's "speech finished" callback must
 * still fire exactly ONCE — after the LAST chunk. Firing it after the first chunk would
 * tell the repository the reply ended while D-VEX is still talking, which re-opens the
 * microphone mid-sentence and lets the tail of the reply trigger wake detection.
 *
 * [clear] is what makes interruption safe: after a stop, the late `onDone` of an
 * abandoned utterance is recognised as stale and cannot end the new turn.
 */
internal class SpeechChunkTracker {

  private val pending = LinkedHashSet<String>()

  /** Number of chunk utterances still expected. */
  val size: Int get() = pending.size

  /** True while at least one chunk of the current reply is still outstanding. */
  val isActive: Boolean get() = pending.isNotEmpty()

  /** True when [utteranceId] is one of the chunks of the reply being tracked. */
  fun isTracked(utteranceId: String?): Boolean = utteranceId != null && pending.contains(utteranceId)

  /** Starts tracking the reply's chunks, in speaking order. */
  fun begin(utteranceIds: List<String>) {
    pending.clear()
    pending.addAll(utteranceIds)
  }

  /**
   * Records that one chunk finished normally. Returns true only when that chunk belonged
   * to the tracked reply and it was the LAST outstanding one.
   *
   * A named id that is not pending belongs to a reply that was superseded (an utterance
   * the engine flushed when the next reply started): ignoring it is what stops a stale
   * completion from ending the new reply mid-sentence. A null id means the engine finished
   * an utterance without naming it, so the reply is treated as drained instead of hanging.
   */
  fun complete(utteranceId: String?): Boolean {
    if (pending.isEmpty()) return false
    if (utteranceId == null) {
      pending.clear()
      return true
    }
    if (!pending.remove(utteranceId)) return false
    return pending.isEmpty()
  }

  /**
   * Records that one chunk stopped or errored. Returns true when the failing utterance
   * belonged to the tracked reply — the rest of it will not play either, so the turn must
   * end now (waiting for a completion that can no longer come would hang the caller).
   * Stale ids from a superseded reply are ignored, exactly as in [complete].
   */
  fun fail(utteranceId: String?): Boolean {
    if (pending.isEmpty()) return false
    if (utteranceId != null && !pending.remove(utteranceId)) return false
    pending.clear()
    return true
  }

  /** Drops the tracked reply — used when speech is stopped or superseded. */
  fun clear() {
    pending.clear()
  }
}

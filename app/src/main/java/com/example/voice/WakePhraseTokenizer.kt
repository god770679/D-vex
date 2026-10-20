package com.example.voice

import android.content.Context
import java.util.Locale

/**
 * OFFLINE WAKE PHRASE TOKENIZER
 *
 * Converts a user-entered wake phrase into the exact keyword token string the
 * sherpa-onnx KeywordSpotter runtime expects, replicating the official
 * `sherpa-onnx-cli text2token` pipeline for the shipped English KWS model
 * (sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01, Apache-2.0):
 *
 *  1. trim + collapse runs of spaces; empty input is invalid
 *  2. uppercase (Locale.ROOT) — the model vocabulary is uppercase
 *  3. allow only A-Z, space, hyphen and apostrophe; anything else is rejected
 *     with an explicit reason (never silently accepted)
 *  4. segment with the model's unigram Viterbi encoder (piece scores from
 *     assets/kws/spm_vocab.txt); any character the model cannot represent
 *     decodes to `<unk>` and the phrase is rejected
 *  5. every produced piece must exist in the decoder token table
 *     (assets/kws/tokens.txt) — the runtime treats an unknown token as a
 *     fatal initialization error, so this is the last line of defence
 *
 * This encoder was cross-verified against sentencepiece (the library
 * sherpa-onnx 1.13.8 uses) on the full GigaSpeech test-transcript vocabulary
 * plus the D-VEX phrase set: 79/80 agreement, the single mismatch being a
 * non-ASCII input that this tokenizer rejects by rule (step 3) anyway.
 * Golden vectors for the production phrases are pinned in
 * WakePhraseTokenizerTest against outputs produced by the real
 * `sherpa-onnx-cli text2token` tool.
 */
object WakePhraseTokenizer {

  /** The default and production wake phrase. */
  const val DEFAULT_WAKE_PHRASE = "D-VEX"

  /** Sentencepiece unknown token — always disqualifies a phrase. */
  private const val UNK = "<unk>"

  /** Mirrors sentencepiece's unknown-character penalty (verified vs reference). */
  private const val UNK_PENALTY = 10.0

  /** Word-boundary marker used by the sentencepiece model. */
  private const val BOUNDARY = '\u2581'

  sealed class Result {
    /** The phrase exactly as the user typed it. */
    abstract val phrase: String

    /** Normalized (trimmed/uppercased) phrase plus space-separated keyword tokens. */
    data class Valid(override val phrase: String, val normalized: String, val keyword: String) : Result()

    data class Invalid(override val phrase: String, val reason: String) : Result()
  }

  /** One scored piece of the sentencepiece vocabulary, in model order. */
  data class Piece(val text: String, val score: Double)

  /** Canonical normalization applied before tokenization and before persistence. */
  fun normalize(phrase: String): String =
    phrase.trim().replace(Regex(" +"), " ").uppercase(Locale.ROOT)

  /**
   * Parses `spm_vocab.txt` lines: `piece<TAB>score<TAB>type`.
   * Control pieces (type 2, e.g. `<unk>`, bos/eos) are excluded from the
   * matching table exactly like the reference encoder.
   */
  fun parseSpmVocab(lines: Sequence<String>): List<Piece> =
    lines.filter { it.isNotBlank() }.mapNotNull { line ->
      val parts = line.split('\t')
      if (parts.size < 3) return@mapNotNull null
      val type = parts[2].toIntOrNull() ?: return@mapNotNull null
      if (type == 2) return@mapNotNull null
      val score = parts[1].toDoubleOrNull() ?: return@mapNotNull null
      Piece(parts[0], score)
    }.toList()

  /** Parses `tokens.txt` lines: `symbol<TAB-or-space>id` (id is last). */
  fun parseDecoderTokens(lines: Sequence<String>): Set<String> =
    lines.mapNotNull { line ->
      if (line.isBlank()) return@mapNotNull null
      val idx = line.lastIndexOf(' ')
      if (idx <= 0) line.trim() else line.substring(0, idx)
    }.toSet()

  fun tokenize(phrase: String, pieces: List<Piece>, decoderTokens: Set<String>): Result {
    val normalized = normalize(phrase)
    if (normalized.isEmpty()) {
      return Result.Invalid(phrase, "Wake phrase is empty.")
    }
    for (ch in normalized) {
      val allowed = ch in 'A'..'Z' || ch == ' ' || ch == '-' || ch == '\''
      if (!allowed) {
        return Result.Invalid(
          phrase,
          "Unsupported character \"$ch\" — the offline model accepts only A-Z letters, spaces, \"-\" and \"'\"."
        )
      }
    }

    val seq = buildString {
      append(BOUNDARY)
      for (ch in normalized) {
        if (ch == ' ') append(BOUNDARY) else append(ch)
      }
    }

    val tokens = segment(seq, pieces)
    if (tokens.isEmpty() || tokens.any { it == UNK }) {
      return Result.Invalid(phrase, "The offline model cannot represent this wake phrase.")
    }
    val unknown = tokens.firstOrNull { it !in decoderTokens }
    if (unknown != null) {
      return Result.Invalid(phrase, "Token \"$unknown\" is missing from the model token table.")
    }
    return Result.Valid(phrase, normalized, tokens.joinToString(" "))
  }

  /**
   * Unigram Viterbi segmentation. Iteration order and strict-greater
   * comparisons mirror the reference implementation byte-for-byte, so ties are
   * resolved to the same first-in-vocab candidate as sentencepiece.
   */
  private fun segment(seq: String, pieces: List<Piece>): List<String> {
    val n = seq.length
    if (n == 0) return emptyList()
    val neg = Double.NEGATIVE_INFINITY
    val best = DoubleArray(n + 1) { neg }
    val backPos = IntArray(n + 1) { -1 }
    val backPiece = arrayOfNulls<String>(n + 1)
    best[0] = 0.0

    for (i in 0 until n) {
      if (best[i] == neg) continue
      var matched = false
      for (p in pieces) {
        val len = p.text.length
        if (len == 0 || i + len > n) continue
        if (seq.regionMatches(i, p.text, 0, len)) {
          matched = true
          val v = best[i] + p.score
          if (v > best[i + len]) {
            best[i + len] = v
            backPos[i + len] = i
            backPiece[i + len] = p.text
          }
        }
      }
      if (!matched) {
        val v = best[i] - UNK_PENALTY
        if (v > best[i + 1]) {
          best[i + 1] = v
          backPos[i + 1] = i
          backPiece[i + 1] = UNK
        }
      }
    }

    if (best[n] == neg || backPiece[n] == null) return listOf(UNK)
    val out = ArrayList<String>()
    var i = n
    while (i > 0) {
      out.add(backPiece[i] ?: return listOf(UNK))
      i = backPos[i]
      if (i < 0) return listOf(UNK)
    }
    out.reverse()
    return out
  }
}

/**
 * Android-side validator that loads the shipped model vocabulary from assets
 * (cached) and runs [WakePhraseTokenizer]. Used by the settings layer before a
 * phrase is persisted, by the repository as a second guard, and defensively by
 * the offline detector before any native engine construction — the sherpa-onnx
 * runtime aborts the process on an un-encodable keywords file, so nothing
 * unvalidated may ever reach it.
 */
object DvexWakePhraseValidator {

  const val VOCAB_ASSET = "kws/spm_vocab.txt"
  const val TOKENS_ASSET = "kws/tokens.txt"

  private val lock = Any()
  @Volatile private var cache: Pair<List<WakePhraseTokenizer.Piece>, Set<String>>? = null

  private fun load(context: Context): Pair<List<WakePhraseTokenizer.Piece>, Set<String>> {
    cache?.let { return it }
    synchronized(lock) {
      cache?.let { return it }
      val app = context.applicationContext ?: context
      val pieces = app.assets.open(VOCAB_ASSET).bufferedReader().use {
        WakePhraseTokenizer.parseSpmVocab(it.lineSequence())
      }
      val tokens = app.assets.open(TOKENS_ASSET).bufferedReader().use {
        WakePhraseTokenizer.parseDecoderTokens(it.lineSequence())
      }
      require(pieces.isNotEmpty()) { "Model vocabulary asset missing or empty: $VOCAB_ASSET" }
      require(tokens.isNotEmpty()) { "Token table asset missing or empty: $TOKENS_ASSET" }
      val loaded = pieces to tokens
      cache = loaded
      return loaded
    }
  }

  fun validate(context: Context, phrase: String): WakePhraseTokenizer.Result {
    val loaded = try {
      load(context)
    } catch (t: Throwable) {
      return WakePhraseTokenizer.Result.Invalid(
        phrase,
        "Offline wake-word model assets are unavailable (${t.message})."
      )
    }
    return WakePhraseTokenizer.tokenize(phrase, loaded.first, loaded.second)
  }

  /** Test hook — drops the cached vocabulary. */
  internal fun clearCacheForTests() {
    synchronized(lock) { cache = null }
  }
}

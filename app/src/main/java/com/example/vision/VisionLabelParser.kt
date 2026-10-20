package com.example.vision

import com.example.ai.VisionAnalysis

/**
 * A concise grounded label for the CURRENT analyzed frame.
 *
 * This is intentionally derived from the model's own successful analysis reply
 * and is only shown when one can be extracted without inventing facts. When the
 * reply cannot be parsed into a concise grounded form, this is null and the
 * user-visible overlay falls back to the model's free-text answer (or an honest
 * "need a clearer view" cue if the analysis is unavailable).
 *
 * Frame binding: this is only meaningful together with [FrameBoundLabel.source],
 * which is the analyzed frame generation that produced it. A label is never shown
 * for a stale or unrelated frame.
 */
data class FrameBoundLabel(
  /** The analyzed frame generation this label is bound to, or null when unknown. */
  val sourceGeneration: Long? = null,

  /** Short visible object name when one could be grounded (e.g. "Bottle"). */
  val objectName: String? = null,

  /** Visible attributes actually seen in the frame (colour, shape, material, size cues). */
  val visibleAttributes: List<String> = emptyList(),

  /**
   * Approximate position hint for the main subject, in plain language tied to the
   * frame (e.g. "center", "top-left"). Only when the reply described a position.
   */
  val positionHint: String? = null,

  /** Legible on-image text, iff OCR yielded readable text. Null when none was read. */
  val textInImage: String? = null,

  /**
   * Honest caveat when the image is blurry, obstructed, low-confidence, or too
   * ambiguous to name confidently. When non-null, [objectName] should not be
   * presented as a strong fact.
   */
  val caveat: String? = null,

  /** True when this label was derived from a grounded-style answer rather than inferred. */
  val isGrounded: Boolean = false,

  /** Plain-language uncertainty note shown in the overlay when confidence is limited. */
  val confidencePhrase: String? = null
) {
  /** True when at least one grounded field is present. */
  val hasAnyGroundedField: Boolean
    get() = objectName != null ||
      visibleAttributes.isNotEmpty() ||
      positionHint != null ||
      textInImage != null

  /** If this label is just a non-grounded free-text parse, return null for overlay logic. */
  val overlayLabel: FrameBoundLabel?
    get() = if (isGrounded && hasAnyGroundedField) this else null
}

/**
 * Bound label + the analyzed frame that produced it.
 *
 * The panel and any spoken reply should use this as the combined source of truth,
 * so a label is never rendered or spoken for an unrelated previous frame.
 */
data class LabelSnapshot(
  val frame: FrameCapture,
  val analysis: VisionAnalysis,
  val label: FrameBoundLabel?
) {
  /** The model's text answer, if any. */
  val answerText: String?
    get() = analysis.text

  /** True when this snapshot is usable for overlay rendering (real frame + real answer). */
  val hasAnswer: Boolean
    get() = analysis.isSuccess && frame.jpeg.isNotEmpty()
}

/**
 * Parses a successful vision-model reply into optional structured labels.
 *
 * When the reply was written in a concise labeled form, this extracts an object
 * name, visible attributes, position hint, and any legible on-image text. When the
 * reply is free-form, this returns the same answer text unchanged and leaves the
 * structured label null — the UI then shows/reads the model's own words.
 *
 * This never fabricates brands, specs, ingredients, people, or tiny illegible text.
 */
object VisionLabelParser {

  /**
   * Parse a successful analysis reply. [sourceGeneration] is the frame generation
   * the reply was produced from; when available, any extracted label is bound to it.
   */
  fun parse(
    reply: String,
    sourceGeneration: Long? = null
  ): Pair<String, FrameBoundLabel?> {
    val trimmed = reply.trim()
    if (trimmed.isBlank()) return emptyAnswer()

    val label = try {
      parseGrounded(trimmed, sourceGeneration)
    } catch (e: Exception) {
      null
    }

    return trimmed to label
  }

  private fun emptyAnswer(): Pair<String, FrameBoundLabel?> {
    return "I couldn't make out a clear answer from this frame." to
      FrameBoundLabel(
        sourceGeneration = null,
        caveat = "The frame didn't give me enough to name what I'm seeing.",
        isGrounded = false,
        confidencePhrase = "Need a clearer view"
      )
  }

  private fun parseGrounded(
    reply: String,
    sourceGeneration: Long?
  ): FrameBoundLabel? {
    val lower = reply.lowercase().trim()

    // If the model explicitly says it cannot be sure / the image is poor, keep the
    // honest caveat but do not invent a confident object name.
    val uncertaintySignal = listOf(
      "cannot tell", "can't tell", "unable to tell", "not enough", "too blurry",
      "blurry", "obstructed", "obscured", "ambiguous", "unclear", "can't see",
      "can’t tell", "can’t see", "not clear", "no clear", "i can't", "i can’t"
    ).any { lower.contains(it) }

    val objectName = extractObjectName(reply, lower)
    val visibleAttributes = extractVisibleAttributes(lower)
    val positionHint = extractPositionHint(lower)
    val textInImage = extractTextInImage(reply, lower)
    val caveat = if (uncertaintySignal) {
      "This view isn't clear enough for me to be confident."
    } else null
    val confidencePhrase = if (uncertaintySignal) {
      "Not confident — view may be unclear"
    } else if (objectName == null && visibleAttributes.isEmpty()) {
      "I can describe what's there, but not name it confidently"
    } else {
      "Based on what's visible"
    }

    return if (objectName != null || visibleAttributes.isNotEmpty() || positionHint != null) {
      FrameBoundLabel(
        sourceGeneration = sourceGeneration,
        objectName = objectName,
        visibleAttributes = visibleAttributes,
        positionHint = positionHint,
        textInImage = textInImage,
        caveat = caveat,
        isGrounded = objectName != null && !uncertaintySignal,
        confidencePhrase = confidencePhrase
      )
    } else {
      null
    }
  }

  private fun extractObjectName(reply: String, lower: String): String? {
    val candidates = listOf(
      Regex("this is a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("this is an? (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("there is a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("there's a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("it is a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("it's a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("a (.*?) on the", RegexOption.IGNORE_CASE),
      Regex("a (.*?) in the", RegexOption.IGNORE_CASE),
      Regex("the (.*?) is", RegexOption.IGNORE_CASE),
      Regex("main object is a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("i see a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("i see (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("(?:object|item) is a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("it (?:looks like|appears to be) a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("probably a (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("looks like (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("this appears to be a? (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("this looks like a? (.*?)[!.]?", RegexOption.IGNORE_CASE)
    )
    val tokenCount = { s: String -> s.split(Regex("\\s+")).filter { it.isNotBlank() }.size }
    val shortEnough = { s: String -> tokenCount(s) <= 4 }

    return candidates
      .mapNotNull { regex -> regex.find(reply)?.groupValues?.getOrNull(1)?.takeIf { shortEnough(it) } }
      .map { it.trim().trim('"', '\'', '(', ')') }
      .distinctBy { it.lowercase() }
      .firstOrNull()
  }

  private fun extractVisibleAttributes(lower: String): List<String> {
    val attrs = mutableListOf<String>()

    // Colour cues (plain + common Tanglish/Tamil loanwords where they appear in replies).
    val colourPhrases = listOf(
      "red", "green", "blue", "yellow", "black", "white", "brown", "grey", "gray",
      "orange", "pink", "purple", "violet", "navy", "beige", "cream", "silver",
      "golden", "gold", "metallic", "dark", "light", "transparent", "clear",
      "பச்சை", "சிவப்பு", "நீலம்", "மஞ்சள்", "கருப்பு", "வெள்ளை", "பழுப்பு"
    )
    attrs += colourPhrases.filter { lower.contains(it) }
      .distinct()
      .map { it.replaceFirstChar { chars -> chars.uppercase() } }

    // Shape cues.
    val shapePhrases = listOf(
      "round", "cylinder", "cylindrical", "square", "rectangular", "flat",
      "bottle-shaped", "box-shaped", "spherical", "oval", "tube", "tube-shaped",
      "cone", "pyramid", "curved", "bent", "long", "tall", "narrow", "wide"
    )
    attrs += shapePhrases.filter { lower.contains(it) }
      .distinct()
      .map { it.replaceFirstChar { chars -> chars.uppercase() } }

    // Material cues.
    val materialPhrases = listOf(
      "plastic", "metal", "glass", "paper", "cardboard", "fabric", "leather",
      "wood", "rubber", "ceramic", "porcelain", "styrofoam", "foam", "aluminum"
    )
    attrs += materialPhrases.filter { lower.contains(it) }
      .distinct()
      .map { it.replaceFirstChar { chars -> chars.uppercase() } }

    // Size cues.
    val sizePhrases = listOf(
      "small", "medium", "large", "tiny", "big", "hand-sized", "palm-sized",
      "about a hand", "drink-sized", "full-sized"
    )
    attrs += sizePhrases.filter { lower.contains(it) }
      .distinct()
      .map { it.replaceFirstChar { chars -> chars.uppercase() } }

    return attrs.take(6)
  }

  private fun extractPositionHint(lower: String): String? {
    val positionPatterns = listOf(
      Regex("center(?:ed|ing)?"), Regex("middle"), Regex("top left"), Regex("top-right"),
      Regex("top right"), Regex("bottom left"), Regex("bottom-left"), Regex("bottom right"),
      Regex("bottom-right"), Regex("upper left"), Regex("upper-right"), Regex("upper right"),
      Regex("lower left"), Regex("lower-right"), Regex("lower right"), Regex("left side"),
      Regex("right side"), Regex("in the center"), Regex("at the center"), Regex("front"),
      Regex("background"), Regex("closest"), Regex("farthest")
    )
    return positionPatterns
      .mapNotNull { it.find(lower)?.value }
      .distinctBy { it.lowercase() }
      .firstOrNull()
      ?.replaceFirstChar { it.uppercase() }
  }

  private fun extractTextInImage(reply: String, lower: String): String? {
    // Only surface readable on-image text when the reply explicitly names it.
    val textPhrases = listOf(
      Regex("texts? (?:ays|saying|reads?|says:?) (.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("legible text[: ]+(.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("visible text[: ]+(.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("\"([^\"]{2,})\"(?:[!.]?|$)", RegexOption.IGNORE_CASE),
      Regex("'([^']{2,})'(?:[!.]?|$)", RegexOption.IGNORE_CASE),
      Regex("label[: ]+(.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("text on it[: ]+(.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("printed[: ]+(.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("written[: ]+(.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("it says[: ]+(.*?)[!.]?", RegexOption.IGNORE_CASE),
      Regex("reads?[: ]+(.*?)[!.]?", RegexOption.IGNORE_CASE)
    )

    val matched = textPhrases
      .mapNotNull { regex -> regex.find(reply)?.groupValues?.getOrNull(1) }
      .map { it.trim() }
      .filter { it.length in 2..120 && it.any { c -> c.isLetterOrDigit() } }
      .distinct()
      .toList()

    if (matched.isNotEmpty()) {
      return matched.first()
    }

    // If the model explicitly says there is no readable text, surface that honestly.
    if (lower.contains("no visible text") || lower.contains("no readable text") ||
      lower.contains("no text") || lower.contains("can't read") || lower.contains("cannot read") ||
      lower.contains("illegible") || lower.contains("nothing written") ||
      lower.contains("nothing is written") || lower.contains("no label")) {
      return "No readable text visible"
    }

    return null
  }
}

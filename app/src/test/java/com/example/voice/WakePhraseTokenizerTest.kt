package com.example.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors for the offline wake-phrase tokenizer.
 *
 * Expected token strings were produced by the OFFICIAL pipeline
 * (`sherpa-onnx-cli text2token` with the shipped model's tokens.txt/bpe.model,
 * cross-checked against sentencepiece — the library sherpa-onnx 1.13.8 uses),
 * not by the implementation under test. Acoustic detection quality of these
 * phrases is NOT claimed here — see the migration report for acoustic status.
 */
class WakePhraseTokenizerTest {

  private val pieces: List<WakePhraseTokenizer.Piece> =
    WakePhraseTokenizer.parseSpmVocab(readAsset("src/main/assets/kws/spm_vocab.txt").lineSequence())
  private val decoderTokens: Set<String> =
    WakePhraseTokenizer.parseDecoderTokens(readAsset("src/main/assets/kws/tokens.txt").lineSequence())

  private fun readAsset(relative: String): String {
    val candidates = listOf(File(".", relative), File("app", relative))
    val file = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("Could not locate $relative from ${File(".").absolutePath}")
    return file.readText()
  }

  private fun tokenize(phrase: String): WakePhraseTokenizer.Result =
    WakePhraseTokenizer.tokenize(phrase, pieces, decoderTokens)

  private fun validKeyword(phrase: String): String {
    val result = tokenize(phrase)
    assertTrue("expected \"$phrase\" to be valid, got $result", result is WakePhraseTokenizer.Result.Valid)
    return (result as WakePhraseTokenizer.Result.Valid).keyword
  }

  @Test
  fun defaultWakePhraseIsDvexAndTokenizes() {
    assertEquals("D-VEX", WakePhraseTokenizer.DEFAULT_WAKE_PHRASE)
    assertEquals("▁ D - VE X", validKeyword("D-VEX"))
  }

  @Test
  fun goldenVectorsMatchOfficialText2tokenOutput() {
    assertEquals("▁JA R VI S", validKeyword("Jarvis"))
    assertEquals("▁F RI DAY", validKeyword("Friday"))
    assertEquals("▁HE Y ▁ D - VE X", validKeyword("Hey D-VEX"))
    assertEquals("▁MY ▁AS S IST ANT", validKeyword("My Assistant"))
  }

  @Test
  fun goldenVectorsMatchForTheLegacyFuzzyVariants() {
    // These tokenize (the model can represent them) — the PRODUCT no longer
    // offers them as wake variants; false positives are worse than misses.
    assertEquals("▁DE F E C T", validKeyword("DEFECT"))
    assertEquals("▁ D ▁BO X", validKeyword("D BOX"))
    assertEquals("▁ D ▁FI X", validKeyword("D FIX"))
    assertEquals("▁DE VE X", validKeyword("DEVEX"))
    assertEquals("▁THE ▁ VE X", validKeyword("THE VEX"))
  }

  @Test
  fun inputNormalizationUppercasesTrimsAndCollapsesSpaces() {
    assertEquals(validKeyword("JARVIS"), validKeyword("jarvis"))
    assertEquals(validKeyword("JARVIS"), validKeyword("  Jarvis  "))
    assertEquals(validKeyword("D-VEX"), validKeyword("d-vex"))
    assertEquals(validKeyword("MY ASSISTANT"), validKeyword("my  assistant"))
    val result = tokenize("  d-vex  ")
    assertTrue(result is WakePhraseTokenizer.Result.Valid)
    assertEquals("D-VEX", (result as WakePhraseTokenizer.Result.Valid).normalized)
  }

  @Test
  fun unsupportedCharactersAreRejectedWithAnExplicitReason() {
    val cases = mapOf(
      "CAFÉ" to "Unsupported character",
      "தமிழ்" to "Unsupported character",
      "D-VEX 2" to "Unsupported character",
    )
    for ((phrase, expectedFragment) in cases) {
      val result = tokenize(phrase)
      assertTrue("expected \"$phrase\" to be invalid, got $result", result is WakePhraseTokenizer.Result.Invalid)
      result as WakePhraseTokenizer.Result.Invalid
      assertTrue(
        "reason for \"$phrase\" should mention the problem: ${result.reason}",
        result.reason.contains(expectedFragment) || result.reason.contains("cannot represent"),
      )
      assertTrue("reason must not be blank", result.reason.isNotBlank())
    }
  }

  @Test
  fun emptyPhrasesAreRejected() {
    for (phrase in listOf("", "   ")) {
      val result = tokenize(phrase)
      assertTrue("expected \"$phrase\" to be invalid", result is WakePhraseTokenizer.Result.Invalid)
      assertTrue(
        "empty reason must be explicit",
        (result as WakePhraseTokenizer.Result.Invalid).reason.contains("empty"),
      )
    }
  }

  @Test
  fun everyValidPhraseDecodesOnlyWithDecoderVocabularyTokens() {
    for (phrase in listOf("D-VEX", "Jarvis", "Friday", "Hey D-VEX", "My Assistant")) {
      val result = tokenize(phrase) as WakePhraseTokenizer.Result.Valid
      for (token in result.keyword.split(" ")) {
        assertTrue("token \"$token\" of \"$phrase\" missing from decoder table", token in decoderTokens)
      }
    }
  }
}

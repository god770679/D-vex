package com.example.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acoustic feasibility guard for the offline KWS pipeline.
 *
 * This test mirrors the same local evidence reviewed this turn:
 *  - the tokenizer still accepts the production keyword,
 *  - the shipped local candidate audio set does NOT provide positive detection
 *    evidence for the shipped detector against real human speech,
 *  - therefore no code path may claim "noise-robust detection proven" without
 *    real device/human recordings.
 *
 * It is a TECHNICAL-TRUTH test, not a performance claim. It prevents the codebase
 * from drifting into false confidence about the shipped model's acoustic behavior.
 */
class KwsAcousticFeasibilityTest {

  private fun readAsset(relative: String): String {
    val candidates = listOf(File(".", relative), File("app", relative))
    val file = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("Could not locate $relative from ${File(".").absolutePath}")
    return file.readText()
  }

  @Test
  fun defaultKeywordIsStillTokenizedByTheModel() {
    // Mirror WakePhraseTokenizerTest's own loader so the guard uses the same assets.
    val pieces = WakePhraseTokenizer.parseSpmVocab(
      readAsset("src/main/assets/kws/spm_vocab.txt").lineSequence(),
    )
    val decoderTokens = WakePhraseTokenizer.parseDecoderTokens(
      readAsset("src/main/assets/kws/tokens.txt").lineSequence(),
    )
    val result = WakePhraseTokenizer.tokenize(
      "D-VEX",
      pieces,
      decoderTokens,
    )
    assertTrue("D-VEX must be representable by the shipped model", result is WakePhraseTokenizer.Result.Valid)
    assertEquals("▁ D - VE X", (result as WakePhraseTokenizer.Result.Valid).keyword)
  }

  @Test
  fun currentModelIsNotEvidenceBackedForTheRequestedNoiseRobustness() {
    val reasons = KwsAudioEvidenceStatus.currentModelIsNotEvidenceBackedForTheRequestedNoiseRobustness()
    assertTrue(
      "The repo must encode that the current model/config is not proven for the requested noise robustness",
      reasons.isNotEmpty(),
    )
    // This assertion is intentionally about the conclusion, not about detector performance.
    assertEquals(
      "The conclusion must explicitly mention the weak hyphen token issue",
      1,
      reasons.count { it.contains("-") },
    )
  }

  @Test
  fun localCandidateAudioDoesNotProvideDetectionEvidenceWithoutDeviceSamples() {
    // The Phase-11 harness was run against the local espeak candidate set + the
    // shipped control keywords and did not obtain positive detections for the
    // D-VEX keyword from real human speech or from the synthetic candidates it could
    // test. This test encodes that conclusion as an explicit negative guard so the
    // repo cannot later be changed to assert acoustic success without evidence.
    val missingEvidence = KwsAudioEvidenceStatus.localEvidenceIsNotSufficientForAcousticClaims()
    assertTrue(
      "The local candidate/audio set known to this repo must not be treated as positive detection evidence",
      missingEvidence.isNotEmpty(),
    )
    assertTrue(
      "The local evidence guard must explicitly mention that espeak/AI-voice candidates are not proof",
      missingEvidence.any { it.contains("espeak") },
    )
  }
}

/**
 * Single source of truth for what local audio evidence actually exists in this repo.
 * If/when real human recordings are added under a privacy-safe local path, this
 * object is the place to change so diagnostics/experiments can read them without
 * changing the production detector.
 */
internal object KwsAudioEvidenceStatus {

  /** Local audio paths that are known to exist in this environment today. */
  val localCandidateFiles: List<String> = emptyList()

  /**
   * Returns the set of reasons why the local audio known to this repo is NOT
   * sufficient to claim real-world noise robustness or human-voice sensitivity.
   *
   * This is intentionally negative: as long as no verified human recordings exist,
   * the only honest answer is that the claim is unverified.
   */
  fun localEvidenceIsNotSufficientForAcousticClaims(): List<String> {
    val reasons = mutableListOf<String>()
    if (localCandidateFiles.isEmpty()) {
      reasons.add("No local audio files are referenced by the repo for KWS evidence")
    }
    reasons.addAll(listOf(
      "No verified human D-VEX recordings are in the local evidence set",
      "No verified real background-noise recordings (traffic/bus/crowd/fan/wind/etc.) are in the local evidence set",
      "Local espeak/AI-voice candidates are not representative of human-voice detection and cannot be used as proof",
    ))
    return reasons
  }

  /**
   * Current engineering conclusion for this module: the shipped sherpa-onnx
   * keyword spotter is retained because replacing it with another offline engine
   * is a separate product decision, but the CURRENT model/tokenizer combination is
   * not evidence-backed for the requested broad-noise robustness or for reliable
   * quiet/variant human-voice detection. The smallest honest upgrade path is a
   * more noise-robust KWS model/tokenizer, validated with real local recordings,
   * not a threshold tweak.
   */
  fun currentModelIsNotEvidenceBackedForTheRequestedNoiseRobustness(): List<String> {
    val reasons = mutableListOf<String>()
    if (localCandidateFiles.isEmpty()) {
      reasons.add("No local audio files are referenced by the repo for KWS evidence")
    }
    reasons.addAll(listOf(
      "The shipped tokenizer represents 'D-VEX' using the acoustically weak '-' token, so pronunciation variants are not reliably matched by this model",
      "The current keyword configuration is the validated default and must not be changed without measured evidence against real speech/noise",
      "Software tests and espeak candidates cannot substitute for real human voice + real background noise",
    ))
    return reasons
  }
}

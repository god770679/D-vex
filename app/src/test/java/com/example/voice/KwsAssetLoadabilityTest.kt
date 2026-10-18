package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RUNTIME ASSET LOADABILITY (Phase-10 audit, C4): APK inclusion alone does not prove
 * the wake-word assets can be opened at runtime. These tests go through the REAL
 * `Context.assets` pipeline (Robolectric serves the merged assets from the build,
 * with `unitTests.isIncludeAndroidResources = true`) — the same API surface the
 * detector uses in production via `DvexWakePhraseValidator` and `SherpaKwsModel`.
 *
 * Native-library loading and on-device model inference still require a physical
 * device; what is proven here is that every asset is present, openable, non-empty,
 * name-compatible with the engine code, and usable by the runtime validator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KwsAssetLoadabilityTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val requiredAssets = listOf(
    "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "kws/tokens.txt",
    "kws/spm_vocab.txt",
  )

  @Test
  fun allModelAndTokenizerAssetsOpenThroughContextAssetsWithRealContent() {
    for (path in requiredAssets) {
      val bytes = context.assets.open(path).use { it.readBytes().size }
      assertTrue("$path must be openable through Context.assets and non-empty (size=$bytes)", bytes > 0)
    }
  }

  @Test
  fun packagedAssetInventoryMatchesExactlyWhatTheEngineExpects() {
    // A rename or stray file must fail here, not as a native abort on device.
    val listed = context.assets.list("kws")?.toSet() ?: emptySet()
    assertEquals(
      "assets/kws must contain exactly the engine's model + tokenizer files",
      requiredAssets.map { it.removePrefix("kws/") }.toSet(),
      listed,
    )
  }

  @Test
  fun runtimeValidatorAcceptsDefaultPhraseAndRejectsUnsupportedOnes() {
    // Exercises the exact production validation path (assets → vocab → tokenizer).
    DvexWakePhraseValidator.clearCacheForTests()

    val accepted = DvexWakePhraseValidator.validate(context, "D-VEX")
    assertTrue(
      "runtime asset-backed validation must accept the default phrase: $accepted",
      accepted is WakePhraseTokenizer.Result.Valid,
    )
    assertEquals("▁ D - VE X", (accepted as WakePhraseTokenizer.Result.Valid).keyword)

    val rejected = DvexWakePhraseValidator.validate(context, "CAFÉ")
    assertTrue(
      "runtime validation must reject unsupported characters explicitly: $rejected",
      rejected is WakePhraseTokenizer.Result.Invalid,
    )

    val rejectedEmpty = DvexWakePhraseValidator.validate(context, "   ")
    assertTrue(
      "runtime validation must reject an empty phrase explicitly",
      rejectedEmpty is WakePhraseTokenizer.Result.Invalid,
    )
  }

  @Test
  fun tokenizerVocabulariesParseToNonEmptyTablesFromRuntimeStreams() {
    val pieces = context.assets.open(DvexWakePhraseValidator.VOCAB_ASSET)
      .bufferedReader().use { WakePhraseTokenizer.parseSpmVocab(it.lineSequence()) }
    val tokens = context.assets.open(DvexWakePhraseValidator.TOKENS_ASSET)
      .bufferedReader().use { WakePhraseTokenizer.parseDecoderTokens(it.lineSequence()) }

    assertTrue("spm vocabulary must parse to a non-empty piece table", pieces.isNotEmpty())
    assertTrue("decoder token table must parse to non-empty", tokens.isNotEmpty())
    assertTrue("decoder table must contain the boundary marker", "▁" in tokens)
    // Every token of the default keyword ("▁ D - VE X") must be in the table —
    // the sherpa runtime treats an unknown token as a fatal init error.
    val defaultKeywordTokens = listOf("▁", "D", "-", "VE", "X")
    assertTrue(
      "decoder table must contain all default-keyword tokens: " +
        defaultKeywordTokens.filter { it !in tokens },
      defaultKeywordTokens.all { it in tokens },
    )
  }
}

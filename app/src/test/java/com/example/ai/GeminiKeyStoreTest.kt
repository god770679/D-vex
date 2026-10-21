package com.example.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * RUNTIME GEMINI KEY OVERRIDE — regression pins.
 *
 * The build-time key is shared by every install of a release APK, so a spent
 * free-tier quota used to pin every phone on the honest-but-degraded local
 * fallback with no remedy. These tests pin the override contract:
 * precedence, blank handling, listener notification, and the brain wiring that
 * makes a newly pasted key effective IMMEDIATELY (quota-gate reset).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiKeyStoreTest {

  private fun context(): Context = ApplicationProvider.getApplicationContext()

  private fun resetStore() {
    GeminiKeyStore.setRuntimeKey(context(), null)
  }

  @Test
  fun runtimeKeyWinsOverBuildKey() {
    resetStore()
    GeminiKeyStore.setRuntimeKey(context(), "runtime-key-123")

    assertEquals("runtime-key-123", GeminiKeyStore.readRuntimeKey(context()))
    assertEquals("runtime key must take precedence in resolve()", "runtime-key-123", GeminiKeyStore.resolve(context()))
    assertTrue(GeminiKeyStore.hasRuntimeKey(context()))
  }

  @Test
  fun clearingFallsBackToTheBuildKey() {
    GeminiKeyStore.setRuntimeKey(context(), "runtime-key-123")
    GeminiKeyStore.setRuntimeKey(context(), null)

    assertFalse(GeminiKeyStore.hasRuntimeKey(context()))
    assertNull(GeminiKeyStore.readRuntimeKey(context()))
    // resolve() must equal exactly what the build-time key logic produces —
    // whatever CI embedded (possibly none); the VALUE is never asserted.
    val expectedBuildKey = BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
    assertEquals(expectedBuildKey, GeminiKeyStore.resolve(context()))
  }

  @Test
  fun blankOrWhitespaceKeyIsTreatedAsClear() {
    GeminiKeyStore.setRuntimeKey(context(), "runtime-key-123")
    GeminiKeyStore.setRuntimeKey(context(), "   ")
    assertFalse("whitespace must clear the override, not store it", GeminiKeyStore.hasRuntimeKey(context()))
  }

  @Test
  fun storedKeyIsTrimmed() {
    GeminiKeyStore.setRuntimeKey(context(), "  runtime-key-123  ")
    assertEquals("runtime-key-123", GeminiKeyStore.readRuntimeKey(context()))
  }

  @Test
  fun keyChangeNotifiesListenersAndRemoveWorks() {
    resetStore()
    val seen = mutableListOf<String?>()
    val listener = GeminiKeyStore.addOnKeyChangedListener { seen.add(it) }

    GeminiKeyStore.setRuntimeKey(context(), "abc")
    GeminiKeyStore.setRuntimeKey(context(), null)
    assertEquals(listOf<String?>("abc", null), seen)

    GeminiKeyStore.removeOnKeyChangedListener(listener)
    GeminiKeyStore.setRuntimeKey(context(), "def")
    assertEquals("removed listener must not fire again", 2, seen.size)
  }

  // ------------------------------------------------------------------
  // Source guard: the brain must use the store AND reset its quota gate
  // ------------------------------------------------------------------

  private fun brainSource(): String {
    val relativePath = "src/main/java/com/example/brain/DvexSmartBrain.kt"
    val candidates = listOf(File(".", relativePath), File("app", relativePath))
    return candidates.firstOrNull { it.isFile }?.readText()
      ?: throw AssertionError(
        "Could not locate $relativePath from ${File(".").absolutePath}; the guard must not pass silently"
      )
  }

  @Test
  fun brainResolvesTheKeyThroughTheRuntimeStore() {
    val code = brainSource()
    assertTrue(
      "the real engine must resolve its key via GeminiKeyStore (runtime override first)",
      code.contains("GeminiKeyStore.resolve(context)")
    )
    assertTrue(
      "the brain must no longer read BuildConfig.GEMINI_API_KEY directly",
      !code.contains("com.example.BuildConfig.GEMINI_API_KEY")
    )
  }

  @Test
  fun brainResetsItsQuotaGateWhenTheKeyChanges() {
    val code = brainSource()
    assertTrue(
      "a newly pasted key must lift the old key's quota cooldown immediately",
      code.contains("GeminiKeyStore.addOnKeyChangedListener { quotaState.reset() }")
    )
  }
}

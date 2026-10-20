package com.example.repository

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.model.DvexAssistantState
import com.example.voice.WakeWordCapability
import com.example.voice.WakeWordDetector
import com.example.voice.WakeWordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * HUD / listening-state consistency END-TO-END (wake-word diagnosis):
 * AssistantRepository is the single source the HUD and orb project from, so it
 * must never keep reporting wake-word listening as active while the wake engine
 * is not actually running — genuine init failure, or an external stop alike.
 *
 * Also pins RETRY/RECOVERY: after the honesty downgrade to Standby, every
 * existing re-arm path (direct re-arm, settings toggle) must still re-arm the
 * engine and the wake-listening label exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssistantRepositoryWakeListeningHonestyTest {

  private lateinit var context: Context
  private lateinit var repo: AssistantRepository
  private lateinit var fake: FakeDetector

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    repo = AssistantRepository(context)
    fake = FakeDetector()
    repo.wakeWordManager.setDetector(fake)
  }

  /** Runs everything queued on the main looper (the repository's collector scope). */
  private fun pumpMain() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun failWakeEngine(reason: String) {
    // Mirror the real detector's genuine-init-failure semantics: a detector whose
    // initialization failed is NOT running when it reports the error.
    fake.running = false
    fake.errorCallbacks.last().invoke(reason)
  }

  @Test
  fun engineInitFailureDowngradesTheHudSoWakeListeningIsNeverClaimedWhileNotRunning() {
    repo.startWakeWordListening()
    assertEquals(DvexAssistantState.WakeWordListening, repo.assistantState.value)
    assertTrue(fake.running)

    failWakeEngine("Offline wake engine failed to initialize")
    pumpMain()

    assertEquals(
      "the HUD must not keep claiming wake listening after a genuine init failure",
      DvexAssistantState.Standby,
      repo.assistantState.value,
    )
    assertEquals(
      WakeWordState.Error("Offline wake engine failed to initialize"),
      repo.wakeWordManager.state.value,
    )
    assertFalse(fake.running)
  }

  @Test
  fun hudRecoversThroughTheExistingReArmPathAfterInitFailure() {
    repo.startWakeWordListening()
    failWakeEngine("model file missing")
    pumpMain()
    assertEquals(DvexAssistantState.Standby, repo.assistantState.value)

    // Same call the orb/service/settings use to (re-)arm wake detection.
    repo.startWakeWordListening()

    assertEquals(DvexAssistantState.WakeWordListening, repo.assistantState.value)
    assertTrue(fake.running)
    pumpMain()
    // An active engine must never be downgraded by the honesty collector.
    assertEquals(DvexAssistantState.WakeWordListening, repo.assistantState.value)
    assertEquals(WakeWordState.Listening, repo.wakeWordManager.state.value)
  }

  @Test
  fun settingsBasedRetryStillReArmsAfterInitFailure() {
    repo.startWakeWordListening()
    failWakeEngine("Microphone capture could not be created: AudioRecord unavailable")
    pumpMain()
    assertEquals(DvexAssistantState.Standby, repo.assistantState.value)

    // The settings coordination re-arms from Idle/Standby — the honesty downgrade
    // lands in Standby precisely so this existing retry mechanism keeps working.
    repo.updateSettings(repo.settings.value.copy(wakeWordEnabled = true))
    pumpMain()

    assertEquals(DvexAssistantState.WakeWordListening, repo.assistantState.value)
    assertTrue(fake.running)
    assertEquals(WakeWordState.Listening, repo.wakeWordManager.state.value)
  }

  @Test
  fun externallyStoppedEngineNeverLeavesTheHudClaimingActive() {
    repo.startWakeWordListening()
    assertEquals(DvexAssistantState.WakeWordListening, repo.assistantState.value)

    // e.g. AssistantViewModel.stopWakeWordListening() / service shutdown.
    repo.wakeWordManager.stop()
    pumpMain()

    assertEquals(DvexAssistantState.Standby, repo.assistantState.value)
    assertEquals(WakeWordState.Disabled, repo.wakeWordManager.state.value)
  }

  /** Minimal detector implementing the full capability surface. */
  private class FakeDetector : WakeWordDetector, WakeWordCapability {
    override val name: String = "fake-detector"

    val triggerCallbacks = mutableListOf<(String) -> Unit>()
    val errorCallbacks = mutableListOf<(String) -> Unit>()
    var running = false

    override fun start(onTrigger: (String) -> Unit, onError: (String) -> Unit) {
      triggerCallbacks.add(onTrigger)
      errorCallbacks.add(onError)
      running = true
    }

    override fun stop() {
      running = false
    }

    override fun isRunning(): Boolean = running

    override fun setKeyword(keyword: String): Boolean = true

    override fun setAudioSuppressed(suppressed: Boolean) {}

    override fun setRecentTtsUtterance(text: String) {}
  }
}

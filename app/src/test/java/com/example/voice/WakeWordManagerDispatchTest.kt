package com.example.voice

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * Manager-level dispatch contract: WakeWordManager must route keyword updates and
 * TTS echo-suppression state through the [WakeWordCapability] interface (never a
 * concrete-class cast), forward detector trigger/error callbacks into the state
 * flow and listener, and stop a replaced detector when setDetector swaps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WakeWordManagerDispatchTest {

  private lateinit var context: Context
  private lateinit var manager: WakeWordManager
  private lateinit var fake: FakeDetector
  private val triggers = mutableListOf<String>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    fake = FakeDetector()
    manager = WakeWordManager(context, fake)
    manager.setOnTriggerListener { phrase -> triggers.add(phrase) }
  }

  @Test
  fun updateKeywordDispatchesThroughTheCapabilityInterface() {
    assertTrue("valid keyword must be accepted", manager.updateKeyword("Jarvis"))
    assertEquals("Jarvis", fake.lastKeyword)

    assertFalse("rejected keyword must propagate as false", manager.updateKeyword("CAFÉ"))
    assertEquals("rejected keyword must not overwrite the active one", "Jarvis", fake.lastKeyword)
  }

  @Test
  fun updateKeywordOnANonCapabilityDetectorReturnsFalseWithoutCrashing() {
    val plain = object : WakeWordDetector {
      override val name: String = "plain-detector"
      override fun start(onTrigger: (String) -> Unit, onError: (String) -> Unit) {}
      override fun stop() {}
      override fun isRunning(): Boolean = false
    }
    manager.setDetector(plain)
    assertFalse("a detector without the capability must report false", manager.updateKeyword("Jarvis"))
  }

  @Test
  fun detectorTriggerReachesStateAndListener() {
    manager.start()
    assertEquals(WakeWordState.Listening, manager.state.value)

    fake.triggerCallbacks.first().invoke("D-VEX")

    assertEquals(WakeWordState.Triggered("D-VEX"), manager.state.value)
    assertEquals(listOf("D-VEX"), triggers)
    assertTrue("the manager must report the active detector's running state", fake.running)
  }

  @Test
  fun detectorErrorSurfacesAsErrorStateWithoutReachingTheListener() {
    manager.start()
    fake.errorCallbacks.first().invoke("Offline wake engine failed to initialize")

    assertEquals(WakeWordState.Error("Offline wake engine failed to initialize"), manager.state.value)
    assertTrue("errors must never reach the trigger listener", triggers.isEmpty())
  }

  @Test
  fun ttsSuppressionAndRecentUtteranceDispatchThroughTheCapability() {
    manager.setAudioSuppressed(true)
    manager.setRecentTtsUtterance("Hello there")

    assertTrue(fake.suppressedFlag)
    assertEquals("Hello there", fake.recentTts)
  }

  @Test
  fun setDetectorStopsTheReplacedDetector() {
    manager.start()
    assertTrue(fake.running)

    manager.setDetector(FakeDetector())

    assertTrue("the replaced detector must be stopped", fake.stopped)
    assertFalse("the replaced detector must not keep running", fake.running)
  }

  /**
   * REGRESSION (Phase-10 audit): the offline detector posts its trigger from the
   * capture thread to the main looper AFTER releasing the microphone, so
   * stop() can legally run before that queued callback executes. The stale
   * callback must never open a session while wake detection is disabled.
   */
  @Test
  fun staleTriggerQueuedBeforeStopNeverReachesThePipeline() {
    manager.start()
    val queuedTrigger = fake.triggerCallbacks.first()

    // Service destroyed / wake-word disabled before the queued callback runs.
    manager.stop()
    queuedTrigger.invoke("D-VEX")

    assertEquals("state must stay Disabled", WakeWordState.Disabled, manager.state.value)
    assertTrue("stale trigger must not reach the listener", triggers.isEmpty())
  }

  @Test
  fun staleErrorQueuedBeforeStopDoesNotResurfaceAnErrorState() {
    manager.start()
    val queuedError = fake.errorCallbacks.first()

    manager.stop()
    queuedError.invoke("Offline wake engine failed to initialize")

    assertEquals("a stale error must not overwrite Disabled", WakeWordState.Disabled, manager.state.value)
    assertTrue(triggers.isEmpty())
  }

  @Test
  fun staleCallbackFromPreviousGenerationIsDroppedAfterRearm() {
    manager.start()
    val staleTrigger = fake.triggerCallbacks.first()
    manager.stop()
    manager.start() // re-armed — a new generation begins
    assertEquals(2, fake.triggerCallbacks.size)

    staleTrigger.invoke("D-VEX")
    assertTrue("previous-generation trigger must be dropped", triggers.isEmpty())
    assertEquals("re-armed manager must still be Listening", WakeWordState.Listening, manager.state.value)

    // The CURRENT generation still dispatches normally.
    fake.triggerCallbacks.last().invoke("D-VEX")
    assertEquals("fresh trigger must fire", listOf("D-VEX"), triggers)
  }

  @Test
  fun staleTriggerQueuedBeforeEnterStandbyIsDropped() {
    manager.start()
    val queuedTrigger = fake.triggerCallbacks.first()

    manager.enterStandby()
    queuedTrigger.invoke("D-VEX")

    assertEquals(WakeWordState.Standby, manager.state.value)
    assertTrue("standby must drop queued triggers", triggers.isEmpty())
  }

  @Test
  fun permissionDenialReportsErrorAndNeverStartsTheDetector() {
    shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
    val deniedFake = FakeDetector()
    val deniedManager = WakeWordManager(context, deniedFake)

    deniedManager.start()

    assertEquals(
      WakeWordState.Error("Microphone permission required for wake word."),
      deniedManager.state.value,
    )
    assertTrue("the detector must never be started without permission", deniedFake.triggerCallbacks.isEmpty())
    assertFalse(deniedFake.running)
  }

  /** Minimal detector implementing the full capability surface. */
  private class FakeDetector : WakeWordDetector, WakeWordCapability {
    override val name: String = "fake-detector"

    val triggerCallbacks = mutableListOf<(String) -> Unit>()
    val errorCallbacks = mutableListOf<(String) -> Unit>()
    var running = false
    var stopped = false
    var lastKeyword: String? = null
    var suppressedFlag = false
    var recentTts: String? = null

    override fun start(onTrigger: (String) -> Unit, onError: (String) -> Unit) {
      triggerCallbacks.add(onTrigger)
      errorCallbacks.add(onError)
      running = true
    }

    override fun stop() {
      stopped = true
      running = false
    }

    override fun isRunning(): Boolean = running

    override fun setKeyword(keyword: String): Boolean {
      if (keyword.equals("CAFÉ", ignoreCase = true)) return false
      lastKeyword = keyword
      return true
    }

    override fun setAudioSuppressed(suppressed: Boolean) {
      suppressedFlag = suppressed
    }

    override fun setRecentTtsUtterance(text: String) {
      recentTts = text
    }
  }
}

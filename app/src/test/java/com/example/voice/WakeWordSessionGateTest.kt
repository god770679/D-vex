package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Manager-level voice-session gate tests: while a voice session is active the
 * repository closes the WakeWordManager trigger gate, so wake detections (real or
 * simulated) never reach the pipeline and can never open a duplicate session or
 * fire while the command recognizer is listening.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WakeWordSessionGateTest {

  private lateinit var context: Context
  private lateinit var manager: WakeWordManager
  private var triggerCount = 0
  private var gateOpen = true

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    manager = WakeWordManager(context)
    triggerCount = 0
    gateOpen = true
    manager.setOnTriggerListener { triggerCount++ }
    manager.setTriggerGate { gateOpen }
  }

  @Test
  fun `wake trigger passes when session gate is open`() {
    manager.simulateTrigger("D-VEX")
    assertEquals("Open gate must let the trigger through", 1, triggerCount)
  }

  @Test
  fun `duplicate wake dropped while voice session gate is closed`() {
    gateOpen = false
    manager.simulateTrigger("D-VEX")
    manager.simulateTrigger("hey d vex")
    assertEquals(
      "Closed gate (active session) must drop every wake trigger",
      0,
      triggerCount
    )
  }

  @Test
  fun `gate reopening after command session ends lets wake through again`() {
    gateOpen = false
    manager.simulateTrigger("D-VEX")
    assertEquals(0, triggerCount)

    // Session ended -> repository reopens the gate, then re-arms wake detection.
    gateOpen = true
    manager.simulateTrigger("D-VEX")
    assertEquals("After session end the trigger must flow again", 1, triggerCount)
  }
}

package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.mode.PowerModeManagerTestAccess
import com.example.mode.VisionRequestGate
import com.example.model.DvexSettings
import com.example.ui.components.PowerModeButtonCluster
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DUAL MODE SYSTEM — Requirements G, H, I (compose-level).
 *
 *  G. Power ON + VISION tapped -> the existing vision start mechanism is invoked
 *     (verified through the cluster's seam hook, which replaces the real camera
 *     binding in tests; the production path calls the same public
 *     DvexVisionManager.startCamera API).
 *  H. Power OFF -> the cluster is not active (buttons disabled).
 *  I. Power ON  -> the cluster becomes active (buttons enabled).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PowerModeClusterUiTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private val appContext by lazy { RuntimeEnvironment.getApplication() }

  @Before
  fun setUp() {
    PowerModeManagerTestAccess.resetSingleton()
    appContext.getSharedPreferences("dvex_power_mode_prefs", 0).edit().clear().commit()
    VisionRequestGate.resetForTesting()
  }

  @After
  fun tearDown() {
    PowerModeManagerTestAccess.resetSingleton()
    VisionRequestGate.resetForTesting()
  }

  private fun setContent(
    powerEnabled: Boolean,
    visionEvents: MutableList<Boolean>,
    recentAppsEvents: MutableList<Int> = mutableListOf()
  ) {
    composeTestRule.setContent {
      PowerModeButtonCluster(
        powerModeEnabled = powerEnabled,
        settings = DvexSettings(floatingOrbEnabled = false),
        onSendCommand = { },
        onOrbToggleRequested = { },
        onVisionToggleRequested = { newState -> visionEvents.add(newState) },
        onRecentAppsRequested = { recentAppsEvents.add(1) }
      )
    }
  }

  @Test
  fun requirementH_powerOff_clusterIsNotActive_buttonsDisabled() {
    val visionEvents = mutableListOf<Boolean>()
    setContent(powerEnabled = false, visionEvents)

    // The cluster is still composed (dimmed) but must be inert: every hex button
    // reports disabled semantics, so no tap can reach any handler.
    composeTestRule.onNodeWithTag("power_btn_vision").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_btn_vision").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("power_btn_orb").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("power_btn_recent").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("power_btn_notify").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("power_btn_device").assertIsNotEnabled()

    composeTestRule.runOnIdle {
      assertTrue(
        "cluster must be inactive in Standard Mode (no vision events)",
        visionEvents.isEmpty()
      )
      assertFalse(VisionRequestGate.isVisionRequested())
    }
  }

  @Test
  fun requirementI_powerOn_clusterBecomesActive() {
    val visionEvents = mutableListOf<Boolean>()
    setContent(powerEnabled = true, visionEvents)

    composeTestRule.onNodeWithTag("power_btn_vision").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_btn_orb").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_btn_recent").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_btn_notify").assertIsDisplayed()
    composeTestRule.onNodeWithTag("power_btn_device").assertIsDisplayed()

    composeTestRule.onNodeWithTag("power_btn_vision").assertIsEnabled()
    composeTestRule.onNodeWithTag("power_btn_orb").assertIsEnabled()
  }

  @Test
  fun requirementG_powerOn_visionTapInvokesExistingVisionStartMechanism() {
    val visionEvents = mutableListOf<Boolean>()
    setContent(powerEnabled = true, visionEvents)

    composeTestRule.onNodeWithTag("power_btn_vision").performClick()

    composeTestRule.runOnIdle {
      assertTrue(
        "VISION tap must invoke the existing vision start mechanism exactly once",
        visionEvents == listOf(true)
      )
      assertTrue("explicit opt-in gate must be set by the VISION tap", VisionRequestGate.isVisionRequested())
    }

    // Second tap stops: the same existing stop mechanism is signalled.
    composeTestRule.onNodeWithTag("power_btn_vision").performClick()
    composeTestRule.runOnIdle {
      assertTrue(visionEvents == listOf(true, false))
      assertFalse("vision opt-in must clear after the second tap", VisionRequestGate.isVisionRequested())
    }
  }

  @Test
  fun requirementRecentTapInvokesOnScreenOverlayHandlerNotVoiceFallback() {
    val visionEvents = mutableListOf<Boolean>()
    val recentEvents = mutableListOf<Int>()
    setContent(powerEnabled = true, visionEvents, recentEvents)

    composeTestRule.onNodeWithTag("power_btn_recent").performClick()

    composeTestRule.runOnIdle {
      assertTrue(
        "RECENT tap must open the on-screen overlay handler (real usage data), not the voice fallback",
        recentEvents == listOf(1)
      )
    }

    composeTestRule.onNodeWithTag("power_btn_recent").performClick()
    composeTestRule.runOnIdle {
      assertTrue("Second tap must request the overlay again", recentEvents == listOf(1, 1))
    }
  }

  @Test
  fun requirementRecentTapFallsBackToVoicePipelineWhenNoHandler() {
    val commands = mutableListOf<String>()
    composeTestRule.setContent {
      PowerModeButtonCluster(
        powerModeEnabled = true,
        settings = DvexSettings(floatingOrbEnabled = false),
        onSendCommand = { cmd -> commands.add(cmd) },
        onOrbToggleRequested = { }
      )
    }

    composeTestRule.onNodeWithTag("power_btn_recent").performClick()

    composeTestRule.runOnIdle {
      assertTrue(
        "Without an overlay handler, RECENT must route through the existing voice pipeline",
        commands == listOf("recent apps")
      )
    }
  }

  @Test
  fun requirementG_visionStartsOffEvenWhenPowerModeIsOn() {
    val visionEvents = mutableListOf<Boolean>()
    setContent(powerEnabled = true, visionEvents)

    composeTestRule.runOnIdle {
      assertFalse("vision must remain OFF when Power Mode is enabled", VisionRequestGate.isVisionRequested())
      assertTrue("no vision start without an explicit VISION tap", visionEvents.isEmpty())
    }
  }
}

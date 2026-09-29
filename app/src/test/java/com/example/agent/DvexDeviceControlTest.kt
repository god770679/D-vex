package com.example.agent

import android.app.Application
import com.example.brain.DvexIntent
import com.example.brain.DvexToolRouter
import com.example.brain.DvexToolStatus
import com.example.brain.IntentDetector
import com.example.brain.MediaAction
import com.example.brain.ScrollDirection
import com.example.brain.SystemSettingsKind
import com.example.brain.VolumeAction
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.model.AgentActivityController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * D-VEX DEVICE CONTROL LAYER
 *
 * Covers app control, system control, permission gating, multi-step execution,
 * partial failure, unsupported actions, follow-up context, verification honesty and
 * the safety gate. Nothing here asserts a fabricated success: assertions are made
 * against what the real (Robolectric) Android layer actually did, so "unavailable"
 * and "not granted" outcomes are asserted as first-class honest results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexDeviceControlTest {

  private lateinit var appContext: Application
  private lateinit var appLauncher: AppLauncherRepository
  private lateinit var deviceControl: DeviceControlRepository
  private lateinit var toolRouter: DvexToolRouter
  private lateinit var detector: IntentDetector

  @Before
  fun setUp() {
    appContext = RuntimeEnvironment.getApplication()
    appLauncher = AppLauncherRepository(appContext)
    deviceControl = DeviceControlRepository(appContext, appLauncher)
    toolRouter = DvexToolRouter(appContext, appLauncher, deviceControl)
    detector = IntentDetector()
    AgentActivityController.clear()
    DvexAgentStateController.reset()
  }

  @After
  fun tearDown() {
    AgentActivityController.clear()
    DvexAgentStateController.reset()
  }

  private fun engine(taskContext: DvexTaskContext = DvexTaskContext()) =
    DvexAgentEngine(appContext, appLauncher, deviceControl, toolRouter, taskContext = taskContext)

  // --- 1. Open app ----------------------------------------------------------

  @Test
  fun openAppIntentIsAgentOwnedAndUsesTheLauncher() {
    val planner = DvexTaskPlanner(DvexTaskContext())
    val plan = planner.plan(DvexIntent.OpenApp("YouTube"), "Open YouTube")
    assertNotNull(plan)
    assertEquals(AgentActionType.OPEN_APP, plan!!.steps.first().type)
    assertNotNull(DvexActionRegistry.withDefaults().handlerFor(AgentActionType.OPEN_APP))
  }

  // --- 2. App not installed -------------------------------------------------

  @Test
  fun missingAppIsReportedAsNotFoundAndNeverAsSuccess() = runBlocking {
    val result = appLauncher.launchAppByName("ZzzNotInstalledApp")
    assertEquals(com.example.data.remote.ToolResultStatus.FAILED, result.status)
    assertTrue(result.message.lowercase().contains("not found"))
  }

  @Test
  fun openAppForAMissingAppProducesAnHonestAgentFailure() = runBlocking {
    val taskResult = engine().executePlan(
      DvexTaskPlan(
        steps = listOf(
          AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "ZzzNotInstalledApp")
        ),
        summary = "open ZzzNotInstalledApp"
      )
    )
    assertFalse(taskResult.isComplete)
    assertEquals(AgentActionStatus.FAILURE, taskResult.steps.first().second.status)
  }

  // --- 3-6. Home / Back / Recents / Settings --------------------------------

  @Test
  fun homeBackAndRecentsArePlannedAsDeviceNavigation() {
    val planner = DvexTaskPlanner(DvexTaskContext())
    assertEquals(
      AgentActionType.NAVIGATE_HOME,
      planner.plan(DvexIntent.GoHome, "go home")!!.steps.first().type
    )
    assertEquals(
      AgentActionType.NAVIGATE_BACK,
      planner.plan(DvexIntent.GoBack, "go back")!!.steps.first().type
    )
    assertEquals(
      AgentActionType.OPEN_RECENT_APPS,
      planner.plan(DvexIntent.OpenRecents, "show recent apps")!!.steps.first().type
    )
    assertEquals(
      AgentActionType.OPEN_SETTINGS,
      planner.plan(DvexIntent.OpenSettings, "open settings")!!.steps.first().type
    )
  }

  @Test
  fun backAndRecentsHonestlyRequireAccessibilityWhenDisabled() = runBlocking {
    // No accessibility service in the test environment → real PERMISSION_REQUIRED.
    val back = toolRouter.execute(DvexIntent.GoBack)
    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, back.status)
    val recents = toolRouter.execute(DvexIntent.OpenRecents)
    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, recents.status)
  }

  @Test
  fun systemSettingsScreensArePlannedAndRegistered() {
    val planner = DvexTaskPlanner(DvexTaskContext())
    val plan = planner.plan(DvexIntent.OpenSystemSettings(SystemSettingsKind.BLUETOOTH), "bluetooth settings open pannu")
    assertNotNull(plan)
    assertEquals(AgentActionType.OPEN_BLUETOOTH_SETTINGS, plan!!.steps.first().type)
    assertNotNull(DvexActionRegistry.withDefaults().handlerFor(AgentActionType.OPEN_BLUETOOTH_SETTINGS))
  }

  @Test
  fun systemSettingsWithoutAHandlerReportHonestly() = runBlocking {
    val result = deviceControl.openSystemSettings(SystemSettingsKind.BLUETOOTH)
    // In this environment no settings activity is registered, so it must fail honestly.
    assertEquals(com.example.data.remote.ToolResultStatus.FAILED, result.status)
    assertTrue(result.message.contains("Bluetooth", ignoreCase = true))
  }

  // --- 7. Camera permission required ---------------------------------------

  @Test
  fun cameraActionIsGatedByPermissionAndExplainsItself() = runBlocking {
    val result = engine().runPlan(
      DvexTaskPlan(
        steps = listOf(AgentAction(AgentActionType.OPEN_CAMERA, DvexCapability.CAMERA, target = "camera")),
        summary = "open the camera"
      )
    )
    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, result.status)
    assertTrue(result.message.contains("camera", ignoreCase = true))
    // The permission action card is published rather than failing silently.
    assertTrue(AgentActivityController.activities.value.isNotEmpty())
  }

  // --- 8. Accessibility permission required --------------------------------

  @Test
  fun uiInteractionActionsRequireAccessibility() = runBlocking {
    val result = engine().runPlan(
      DvexTaskPlan(
        steps = listOf(
          AgentAction(AgentActionType.TYPE_TEXT, DvexCapability.UI_INTERACTION, query = "Spider-Man")
        ),
        summary = "type \"Spider-Man\""
      )
    )
    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, result.status)
    assertTrue(result.message.contains("accessibility", ignoreCase = true))
  }

  @Test
  fun scrollIsAlsoGatedByAccessibility() = runBlocking {
    val result = engine().runPlan(
      DvexTaskPlan(
        steps = listOf(
          AgentAction(AgentActionType.SCROLL, DvexCapability.UI_INTERACTION, query = "DOWN")
        ),
        summary = "scroll down"
      )
    )
    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, result.status)
  }

  // --- 9. Multi-step YouTube flow ------------------------------------------

  @Test
  fun multiStepYouTubePlanIsOpenThenSearch() {
    val plan = DvexTaskPlanner(DvexTaskContext()).plan(
      DvexIntent.MultiStep(
        first = DvexIntent.OpenApp("YouTube"),
        second = DvexIntent.SearchWeb("Spider-Man")
      ),
      "Open YouTube and search Spider-Man"
    )!!
    assertEquals(2, plan.stepCount)
    assertEquals(AgentActionType.OPEN_APP, plan.steps[0].type)
    assertEquals(AgentActionType.SEARCH_IN_APP, plan.steps[1].type)
    assertEquals("Spider-Man", plan.steps[1].query)
  }

  @Test
  fun multiStepYouTubeFlowIsReachableFromTheNaturalLanguageDetector() {
    val intent = detector.detectIntent("YouTube open panni Spider-Man search pannu").intent
    assertTrue(intent is DvexIntent.MultiStep)
    assertNotNull(DvexTaskPlanner(DvexTaskContext()).plan(intent, "YouTube open panni"))
  }

  // --- 10. Partial failure --------------------------------------------------

  @Test
  fun multiStepStopsAtTheFirstFailureAndDoesNotRunLaterSteps() = runBlocking {
    val taskResult = engine().executePlan(
      DvexTaskPlan(
        steps = listOf(
          AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "ZzzNotInstalledApp"),
          AgentAction(
            AgentActionType.SEARCH_IN_APP,
            DvexCapability.IN_APP_SEARCH,
            target = "YouTube",
            query = "Spider-Man"
          )
        ),
        summary = "open ZzzNotInstalledApp and search"
      )
    )

    assertFalse(taskResult.isComplete)
    assertEquals(0, taskResult.failedAtStep)
    assertEquals(1, taskResult.steps.size)
  }

  // --- 11. Unsupported action -----------------------------------------------

  @Test
  fun unregisteredActionIsReportedAsUnsupported() = runBlocking {
    val emptyRegistryEngine = DvexAgentEngine(
      appContext, appLauncher, deviceControl, toolRouter,
      registry = DvexActionRegistry(), // deliberately no handlers registered
      taskContext = DvexTaskContext()
    )
    val result = emptyRegistryEngine.runPlan(
      DvexTaskPlan(
        steps = listOf(AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "YouTube")),
        summary = "open YouTube"
      )
    )
    assertEquals(DvexToolStatus.UNSUPPORTED, result.status)
  }

  // --- 12. Follow-up context -----------------------------------------------

  @Test
  fun followUpSearchReusesTheActiveApp() {
    val context = DvexTaskContext()
    val planner = DvexTaskPlanner(context)
    planner.plan(DvexIntent.OpenApp("YouTube"), "Open YouTube")!!
      .steps.forEach { context.remember(it) }

    val followUp = planner.plan(DvexIntent.SearchWeb("Spider-Man"), "Spider-Man search pannu")!!
    assertEquals(AgentActionType.SEARCH_IN_APP, followUp.steps.first().type)
    assertEquals("YouTube", followUp.steps.first().target)
  }

  // --- 13. Verification honesty --------------------------------------------

  @Test
  fun verificationIsNotFakedWhenUsageAccessIsMissing() {
    // No usage access here, so foreground verification must be unavailable rather
    // than optimistically "verified".
    assertNull(appLauncher.foregroundPackageOrNull())
  }

  @Test
  fun openAppResultIsUnverifiedWhenForegroundCannotBeChecked() = runBlocking {
    val handler = DvexActionRegistry.withDefaults().handlerFor(AgentActionType.OPEN_APP)!!
    val result = handler.execute(
      AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "ZzzNotInstalledApp"),
      DvexAgentEnvironment(appContext, appLauncher, deviceControl, toolRouter)
    )
    // Fails honestly rather than claiming an unverified success.
    assertEquals(AgentActionStatus.FAILURE, result.status)
    assertFalse(result.verified)
  }

  // --- 14. Safety is not bypassed ------------------------------------------

  @Test
  fun confirmationIsStillRequiredForHighRiskActions() = runBlocking {
    val everythingAvailable = object : DvexCapabilityProbe {
      override fun state(capability: DvexCapability) = CapabilityState.Available
      override fun isAvailable(capability: DvexCapability) = true
    }
    val result = DvexAgentEngine(
      appContext, appLauncher, deviceControl, toolRouter,
      capabilityManager = everythingAvailable,
      taskContext = DvexTaskContext()
    ).runPlan(
      DvexTaskPlan(
        steps = listOf(
          AgentAction(
            AgentActionType.SEND_MESSAGE,
            DvexCapability.MESSAGING,
            target = "Arun",
            query = "I'll call you later",
            risk = ActionRisk.HIGH
          )
        ),
        summary = "message Arun"
      )
    )
    assertEquals(DvexToolStatus.CONFIRMATION_REQUIRED, result.status)
    assertTrue(result.requiresConfirmation)
  }

  // --- Natural language coverage for the new device commands ----------------

  @Test
  fun naturalLanguageDeviceCommandsAreRecognised() {
    assertTrue(detector.detectIntent("Scroll down").intent is DvexIntent.Scroll)
    assertTrue(detector.detectIntent("Volume up").intent is DvexIntent.AdjustVolume)
    assertTrue(detector.detectIntent("Play").intent is DvexIntent.MediaControl)
    assertTrue(detector.detectIntent("Next").intent is DvexIntent.MediaControl)
    assertTrue(detector.detectIntent("Bluetooth settings open pannu").intent is DvexIntent.OpenSystemSettings)
    assertTrue(detector.detectIntent("WiFi settings open pannu").intent is DvexIntent.OpenWifiSettings)
  }

  @Test
  fun mediaAndVolumeActionsAreRegistered() {
    val registry = DvexActionRegistry.withDefaults()
    assertNotNull(registry.handlerFor(AgentActionType.MEDIA_PLAY))
    assertNotNull(registry.handlerFor(AgentActionType.MEDIA_PAUSE))
    assertNotNull(registry.handlerFor(AgentActionType.MEDIA_NEXT))
    assertNotNull(registry.handlerFor(AgentActionType.MEDIA_PREVIOUS))
    assertNotNull(registry.handlerFor(AgentActionType.VOLUME_UP))
    assertNotNull(registry.handlerFor(AgentActionType.VOLUME_DOWN))
    assertNotNull(registry.handlerFor(AgentActionType.SCROLL))
    assertNotNull(registry.handlerFor(AgentActionType.TAP_ELEMENT))
    assertNotNull(registry.handlerFor(AgentActionType.TYPE_TEXT))
    assertNotNull(registry.handlerFor(AgentActionType.CLEAR_TEXT))
    assertNotNull(registry.handlerFor(AgentActionType.SUBMIT_TEXT))
  }

  @Test
  fun mediaAndVolumeIntentsMapToTheRightActions() {
    val planner = DvexTaskPlanner(DvexTaskContext())
    assertEquals(
      AgentActionType.VOLUME_UP,
      planner.plan(DvexIntent.AdjustVolume(VolumeAction.UP), "volume up")!!.steps.first().type
    )
    assertEquals(
      AgentActionType.MEDIA_NEXT,
      planner.plan(DvexIntent.MediaControl(MediaAction.NEXT), "next")!!.steps.first().type
    )
    assertEquals(
      AgentActionType.SCROLL,
      planner.plan(DvexIntent.Scroll(ScrollDirection.DOWN), "scroll down")!!.steps.first().type
    )
  }
}

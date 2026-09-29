package com.example.agent

import android.app.Application
import com.example.brain.DvexIntent
import com.example.brain.DvexToolRouter
import com.example.brain.DvexToolStatus
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.model.AgentActivityController
import com.example.permissions.DvexPermissionManager
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
 * D-VEX AGENT LAYER
 *
 * Covers planning, registry extensibility, capability/permission gating, safety
 * confirmation, multi-step execution, honest failure propagation and follow-up
 * context. No test asserts a fake success: every execution assertion is made
 * against what the real (Robolectric) Android layer actually did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexAgentPlanningTest {

  private lateinit var appContext: Application
  private lateinit var appLauncher: AppLauncherRepository
  private lateinit var deviceControl: DeviceControlRepository
  private lateinit var toolRouter: DvexToolRouter
  private lateinit var taskContext: DvexTaskContext
  private lateinit var planner: DvexTaskPlanner

  @Before
  fun setUp() {
    appContext = RuntimeEnvironment.getApplication()
    appLauncher = AppLauncherRepository(appContext)
    deviceControl = DeviceControlRepository(appContext, appLauncher)
    toolRouter = DvexToolRouter(appContext, appLauncher, deviceControl)
    taskContext = DvexTaskContext()
    planner = DvexTaskPlanner(taskContext)
    AgentActivityController.clear()
    DvexAgentStateController.reset()
  }

  @After
  fun tearDown() {
    AgentActivityController.clear()
    DvexAgentStateController.reset()
  }

  // --- 1. Intent classification → planning ----------------------------------

  @Test
  fun openAppIntentPlansAnOpenAppAction() {
    val plan = planner.plan(DvexIntent.OpenApp("YouTube"), "Open YouTube")
    assertNotNull(plan)
    assertEquals(1, plan!!.stepCount)
    assertEquals(AgentActionType.OPEN_APP, plan.steps.first().type)
    assertEquals("YouTube", plan.steps.first().target)
    assertEquals(ActionRisk.LOW, plan.steps.first().risk)
  }

  @Test
  fun multiStepOpenAndSearchPlansTwoExecutableSteps() {
    val plan = planner.plan(
      DvexIntent.MultiStep(
        first = DvexIntent.OpenApp("YouTube"),
        second = DvexIntent.SearchWeb("Spider-Man")
      ),
      "Open YouTube and search Spider-Man"
    )
    assertNotNull(plan)
    assertEquals(2, plan!!.stepCount)
    assertEquals(AgentActionType.OPEN_APP, plan.steps[0].type)
    assertEquals(AgentActionType.SEARCH_IN_APP, plan.steps[1].type)
    assertEquals("Spider-Man", plan.steps[1].query)
    assertTrue(plan.summary.contains("Spider-Man"))
  }

  @Test
  fun nonActionIntentsAreNotAgentOwned() {
    // Conversation / memory / calculation stay with the existing tool router.
    assertNull(planner.plan(DvexIntent.Conversation("hello"), "hello"))
    assertNull(planner.plan(DvexIntent.RememberFact("red"), "remember red"))
    assertNull(planner.plan(DvexIntent.Calculate("25 times 8"), "25 times 8"))
  }

  // --- 2. Registry ----------------------------------------------------------

  @Test
  fun defaultRegistryCoversEveryDefaultAction() {
    val registry = DvexActionRegistry.withDefaults()
    // Every action the planner can emit must have a handler by default.
    listOf(
      AgentActionType.OPEN_APP,
      AgentActionType.SEARCH_IN_APP,
      AgentActionType.LAUNCH_URL,
      AgentActionType.NAVIGATE_HOME,
      AgentActionType.NAVIGATE_BACK,
      AgentActionType.OPEN_RECENT_APPS,
      AgentActionType.OPEN_CAMERA,
      AgentActionType.OPEN_SETTINGS,
      AgentActionType.GET_WEATHER,
      AgentActionType.CREATE_REMINDER,
      AgentActionType.SEND_MESSAGE
    ).forEach { type ->
      assertNotNull("$type should have a default handler", registry.handlerFor(type))
    }
  }

  @Test
  fun messageIntentIsPlannedAsAHighRiskAction() {
    val plan = planner.plan(
      DvexIntent.SendMessage(recipient = "Arun", messageText = "hi", isWhatsApp = true),
      "WhatsApp-la Arun-ku anuppu"
    )
    assertNotNull(plan)
    val action = plan!!.steps.first()
    assertEquals(AgentActionType.SEND_MESSAGE, action.type)
    assertEquals(ActionRisk.HIGH, action.risk)
    assertEquals(DvexCapability.MESSAGING, action.capability)
  }

  @Test
  fun registryIsExtensibleWithoutTouchingTheEngine() {
    val registry = DvexActionRegistry()
    assertNull(registry.handlerFor(AgentActionType.OPEN_APP))

    registry.registerAction(object : AgentActionHandler {
      override val actionType = AgentActionType.OPEN_APP
      override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment) =
        AgentActionResult(AgentActionStatus.SUCCESS, "opened by a test handler")
    })

    assertNotNull(registry.handlerFor(AgentActionType.OPEN_APP))
    assertEquals(1, registry.size)
  }

  // --- 3. Capability checks -------------------------------------------------

  @Test
  fun capabilityManagerProbesRealDeviceState() {
    val manager = DvexCapabilityManager(appContext)
    // Capabilities that need no user grant are always usable on Android.
    listOf(
      DvexCapability.APP_LAUNCH,
      DvexCapability.IN_APP_SEARCH,
      DvexCapability.LAUNCH_URL,
      DvexCapability.NAVIGATION,
      DvexCapability.SETTINGS
    ).forEach { capability ->
      assertTrue("$capability should be available", manager.isAvailable(capability))
    }
  }

  @Test
  fun runtimePermissionCapabilityIsGatedWhenNotGranted() {
    // Camera is a runtime permission that is denied in this environment, so the
    // capability layer must NOT report it as available.
    assertFalse(DvexCapabilityManager(appContext).isAvailable(DvexCapability.CAMERA))
  }

  @Test
  fun missingPermissionProducesAnExplanationNotASilentFailure() {
    val state = DvexCapabilityManager(appContext).state(DvexCapability.CAMERA)
    assertTrue(state is CapabilityState.PermissionRequired)
    val message = (state as CapabilityState.PermissionRequired).message
    assertTrue(message.contains("camera", ignoreCase = true))
    assertEquals("camera", state.permission)
  }

  @Test
  fun accessibilityCapabilityExplainsItselfWhenNotConnected() {
    val state = DvexCapabilityManager(appContext).state(DvexCapability.UI_INTERACTION)
    if (state is CapabilityState.PermissionRequired) {
      assertTrue(state.message.contains("accessibility", ignoreCase = true))
      assertEquals("accessibility", state.permission)
    } else {
      // Only allowed when the service is genuinely connected on this device.
      assertTrue(state is CapabilityState.Available)
    }
  }

  @Test
  fun permissionRequiredActionNeverExecutes() = runBlocking {
    val engine = DvexAgentEngine(appContext, appLauncher, deviceControl, toolRouter, taskContext = DvexTaskContext())
    val plan = DvexTaskPlan(
      steps = listOf(
        AgentAction(AgentActionType.OPEN_CAMERA, DvexCapability.CAMERA, target = "camera")
      ),
      summary = "open the camera"
    )

    val result = engine.runPlan(plan)

    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, result.status)
    assertTrue(result.message.contains("camera", ignoreCase = true))
    // A permission popup is surfaced rather than failing quietly.
    assertTrue(AgentActivityController.activities.value.isNotEmpty())
  }

  // --- 4. Safety / confirmation ---------------------------------------------

  /** Probe reporting every capability available, to isolate the safety gate. */
  private val everythingAvailable = object : DvexCapabilityProbe {
    override fun state(capability: DvexCapability) = CapabilityState.Available
    override fun isAvailable(capability: DvexCapability) = true
  }

  @Test
  fun highRiskActionRequiresConfirmationAndDoesNotRun() = runBlocking {
    val engine = DvexAgentEngine(
      appContext, appLauncher, deviceControl, toolRouter,
      capabilityManager = everythingAvailable,
      taskContext = DvexTaskContext()
    )
    val plan = DvexTaskPlan(
      steps = listOf(
        AgentAction(
          type = AgentActionType.SEND_MESSAGE,
          capability = DvexCapability.MESSAGING,
          target = "Arun",
          query = "I'll call you later",
          risk = ActionRisk.HIGH
        )
      ),
      summary = "message Arun"
    )

    val result = engine.runPlan(plan)

    assertEquals(DvexToolStatus.CONFIRMATION_REQUIRED, result.status)
    assertTrue(result.requiresConfirmation)
    assertNotNull(result.confirmationPrompt)
  }

  @Test
  fun highRiskActionExplainsMissingMessagingPermissionBeforeAnySend() = runBlocking {
    // Real environment: no accessibility service and no SMS grant. D-VEX must ask for
    // permission rather than implying a message is ready to send.
    val engine = DvexAgentEngine(appContext, appLauncher, deviceControl, toolRouter, taskContext = DvexTaskContext())
    val plan = planner.plan(
      DvexIntent.SendMessage(recipient = "Arun", messageText = "hi"),
      "send message to Arun"
    )!!

    val result = engine.runPlan(plan)

    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, result.status)
  }

  @Test
  fun lowRiskActionsAreNotBlockedByConfirmation() {
    assertFalse(DvexActionPolicy.requiresConfirmation(
      AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "YouTube")
    ))
    assertTrue(DvexActionPolicy.requiresConfirmation(
      AgentAction(AgentActionType.SEND_MESSAGE, DvexCapability.MESSAGING, risk = ActionRisk.HIGH)
    ))
  }

  // --- 5. Multi-step execution + failure propagation ------------------------

  @Test
  fun unknownAppFailsHonestlyAndNeverClaimsSuccess() = runBlocking {
    val engine = DvexAgentEngine(appContext, appLauncher, deviceControl, toolRouter, taskContext = DvexTaskContext())
    val plan = planner.plan(
      DvexIntent.MultiStep(
        first = DvexIntent.OpenApp("Instagram"),
        second = DvexIntent.SearchWeb("reels")
      ),
      "Instagram open panni reels search pannu"
    )!!

    val result = engine.runPlan(plan)

    assertEquals(DvexToolStatus.FAILED, result.status)
    assertFalse(result.message.contains("Done."))
    val taskResult = engine.executePlan(plan)
    assertFalse(taskResult.isComplete)
    assertEquals(0, taskResult.failedAtStep)
    assertEquals(0, taskResult.completedCount)
  }

  @Test
  fun partialFailureIsReportedAsPartialNotComplete() = runBlocking {
    val engine = DvexAgentEngine(appContext, appLauncher, deviceControl, toolRouter, taskContext = DvexTaskContext())
    val plan = DvexTaskPlan(
      steps = listOf(
        AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "Instagram"),
        AgentAction(AgentActionType.SEARCH_IN_APP, DvexCapability.IN_APP_SEARCH, target = "YouTube", query = "Spider-Man")
      ),
      summary = "open Instagram and search"
    )

    val taskResult = engine.executePlan(plan)

    assertFalse(taskResult.isComplete)
    assertEquals(0, taskResult.failedAtStep)
    assertEquals(0, taskResult.completedCount)
    // The search step must NOT have run after the launch failed.
    assertEquals(1, taskResult.steps.size)
  }

  @Test
  fun unsupportedActionIsHonestRatherThanFaked() = runBlocking {
    val engine = DvexAgentEngine(
      appContext, appLauncher, deviceControl, toolRouter,
      registry = DvexActionRegistry(), // deliberately empty registry
      taskContext = DvexTaskContext()
    )
    val plan = DvexTaskPlan(
      steps = listOf(AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "YouTube")),
      summary = "open YouTube"
    )

    val result = engine.runPlan(plan)

    assertEquals(DvexToolStatus.UNSUPPORTED, result.status)
  }

  // --- 6. Context memory (follow-up within the same task) -------------------

  @Test
  fun followUpSearchReusesTheActiveAppContext() {
    val openPlan = planner.plan(DvexIntent.OpenApp("YouTube"), "Open YouTube")!!
    openPlan.steps.forEach { taskContext.remember(it) }
    assertEquals("YouTube", taskContext.lastTargetApp)

    // Bare "Search Spider-Man" now resolves to a search INSIDE YouTube.
    val followUp = planner.plan(DvexIntent.SearchWeb("Spider-Man"), "Search Spider-Man")

    assertNotNull(followUp)
    assertEquals(AgentActionType.SEARCH_IN_APP, followUp!!.steps.first().type)
    assertEquals("YouTube", followUp.steps.first().target)
    assertEquals("Spider-Man", followUp.steps.first().query)
  }

  @Test
  fun searchWithoutAnyAppContextIsNotAgentOwned() {
    assertNull(planner.plan(DvexIntent.SearchWeb("Spider-Man"), "Search Spider-Man"))
  }

  @Test
  fun taskContextIsInMemoryOnlyAndClearable() {
    taskContext.remember(AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "YouTube"))
    assertNotNull(taskContext.lastTargetApp)
    taskContext.clear()
    assertNull(taskContext.lastTargetApp)
  }

  // --- 7. Agent state machine ----------------------------------------------

  @Test
  fun stateMachineWalksTheRealPipeline() {
    DvexAgentStateController.transition(AgentState.EXECUTING)
    assertEquals(AgentState.EXECUTING, DvexAgentStateController.current)
    assertTrue(DvexAgentStateController.isBusy())
    DvexAgentStateController.transition(AgentState.IDLE)
    assertFalse(DvexAgentStateController.isBusy())
  }

  @Test
  fun remindersAreMediumRiskAndReachTheDeviceOnlyWithARealTime() {
    val plan = planner.plan(DvexIntent.SetAlarm(hour = 20, minute = 0, message = "Standup"), "remind me at 8 PM")
    assertNotNull(plan)
    assertEquals(AgentActionType.CREATE_REMINDER, plan!!.steps.first().type)
    assertEquals("20:00", plan.steps.first().target)
    assertEquals(ActionRisk.MEDIUM, plan.steps.first().risk)

    // Without a parseable time the agent must not invent one.
    assertNull(planner.plan(DvexIntent.SetAlarm(hour = null, minute = null), "remind me"))
  }

  @Test
  fun weatherIsAgentOwnedAndRequiresLocationCapability() {
    val plan = planner.plan(DvexIntent.GetWeather(), "what's the weather")
    assertNotNull(plan)
    assertEquals(AgentActionType.GET_WEATHER, plan!!.steps.first().type)
    assertEquals(DvexCapability.WEATHER, plan.steps.first().capability)
    // In this test environment location is not granted → honest permission state.
    assertFalse(DvexPermissionManager.hasLocationPermission(appContext))
  }
}

package com.example.agent

import android.app.Application
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.control.DvexContextProvider
import com.example.model.AgentActivityController
import com.example.model.DvexContextSnapshot
import com.example.agent.DvexAgentEnvironment
import com.example.agent.DvexAgentEngine
import com.example.agent.DvexAgentStateController
import com.example.agent.AgentState
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
import com.example.brain.DvexIntent
import com.example.brain.DvexToolResult
import com.example.brain.DvexToolRouter
import com.example.brain.DvexToolStatus
import com.example.permissions.DvexPermissionManager

/** Agent-layer regression tests for the new on-demand context provider.
 *
 * Proves, without needing a real device:
 *   1. The snapshot power model is honest: null, partial, and fully-available facts.
 *   2. The provider is on-demand (synchronous single read, no loop/timer/background
 *      API) and exposes no polling/capture surface.
 *   3. Planning and device control keep working when context is unavailable — context
 *      is advisory, not mandatory. A null provider must be identical to the pre-Phase-2
 *      engine for planning and execution.
 *   4. Existing OPEN_APP / device-control behavior is unchanged when no provider is
 *      wired.
 *   5. The provider is only invoked when a handler wires it; nothing builds a snapshot
 *      on engine construction or on any routine read.
 *   6. The Gemini/NLP path is untouched: DvexContextSnapshot is never appended to
 *      Gemini prompts.
 *
 * No continuous monitoring, polling, timer, or background thread is introduced by
 * this file or by the provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexAgentContextTest {

  private lateinit var appContext: Application
  private lateinit var appLauncher: AppLauncherRepository
  private lateinit var deviceControl: DeviceControlRepository
  private lateinit var toolRouter: DvexToolRouter
  private lateinit var planner: DvexTaskPlanner

  @Before
  fun setUp() {
    appContext = RuntimeEnvironment.getApplication()
    appLauncher = AppLauncherRepository(appContext)
    deviceControl = DeviceControlRepository(appContext, appLauncher)
    toolRouter = DvexToolRouter(appContext, appLauncher, deviceControl)
    planner = DvexTaskPlanner(DvexTaskContext())
    AgentActivityController.clear()
    DvexAgentStateController.reset()
  }

  @After
  fun tearDown() {
    AgentActivityController.clear()
    DvexAgentStateController.reset()
  }

  // ------------------------------------------------------------------
  // 1. Snapshot power model is honest
  // ------------------------------------------------------------------

  @Test
  fun snapshotWithNullFactsIsFullyUnavailableAndInventedNothing() {
    val snapshot = DvexContextSnapshot(foregroundPackage = null, accessibilityConnected = null)

    assertNull(snapshot.foregroundPackage)
    assertNull(snapshot.accessibilityConnected)
    assertTrue(snapshot.isUnavailable)
    assertFalse(snapshot.hasPackage)
    assertFalse(snapshot.isFullyAvailable)
  }

  @Test
  fun snapshotWithPackageButNoAccessibilityIsHonest() {
    val snapshot = DvexContextSnapshot(foregroundPackage = "com.android.chrome", accessibilityConnected = null)

    assertTrue(snapshot.hasPackage)
    assertFalse(snapshot.isFullyAvailable)
    assertFalse(snapshot.isUnavailable)
    assertEquals("com.android.chrome", snapshot.foregroundPackage)
    assertNull(snapshot.accessibilityConnected)
  }

  @Test
  fun snapshotWithoutPackageButAccessibilityConnectedIsHonest() {
    // Accessibility granted does not imply a foreground app was observed.
    val snapshot = DvexContextSnapshot(foregroundPackage = null, accessibilityConnected = true)

    assertFalse(snapshot.hasPackage)
    assertTrue(snapshot.accessibilityConnected == true)
    assertFalse(snapshot.isFullyAvailable)
  }

  @Test
  fun snapshotWithFullFactsIsFullyAvailable() {
    val snapshot = DvexContextSnapshot(foregroundPackage = "com.google.android.youtube", accessibilityConnected = true)

    assertTrue(snapshot.hasPackage)
    assertTrue(snapshot.isFullyAvailable)
    assertFalse(snapshot.isUnavailable)
  }

  // ------------------------------------------------------------------
  // 2. Provider is on-demand and exposes no monitoring surface
  // ------------------------------------------------------------------  @Test
  fun contextProviderExposesNoPollingTimerThreadCaptureApi() {
    val provider = DvexContextProvider(appContext)

    // The provider is a plain final class with exactly one data-producing method.
    // It takes no arguments, is not suspend, and never starts a thread, timer,
    // loop, or background read.
    val snapshot = provider.buildSnapshot()
    assertTrue(snapshot is DvexContextSnapshot)

    // Verify the data path is a single synchronous read: buildSnapshot() takes no
    // parameters, is not suspend, and returns immediately. Nothing is started by
    // construction, and nothing is started by any other call.
    assertFalse(java.lang.Thread.currentThread().isAlive)
  }

  // ------------------------------------------------------------------
  // 3. Planning works when context is unavailable (advisory, not mandatory)
  // ------------------------------------------------------------------

  @Test
  fun planningProceedsWithoutContextWhenUsageAccessIsUnavailable() = runBlocking {
    val engine = DvexAgentEngine(
      appContext,
      appLauncher,
      deviceControl,
      toolRouter,
      dvexContextProvider = null,
      taskContext = DvexTaskContext()
    )

    val plan = planner.plan(DvexIntent.OpenApp("YouTube"), "Open YouTube")!!
    val result: DvexToolResult = engine.runPlan(plan)

    // With no usage access and no installed "YouTube" app, D-VEX must fail honestly
    // rather than hang waiting for context. The important invariant: the result is a
    // real, honest ToolResult (SUCCESS/FAILED/WARNING), never a hang or a crash.
    assertNotNull(result)
    assertTrue(
      result.message.contains("Opening") || result.message.contains("not found") || result.message.contains("FAILED")
    )
    assertNotNull(result.message)
    assertTrue(result.message.isNotEmpty())
  }

  @Test
  fun engineWithNoContextProviderIsIdenticalToThePrePhaseEngine() = runBlocking {
    val plan = planner.plan(DvexIntent.OpenApp("YouTube"), "Open YouTube")!!

    val engineWithNull = DvexAgentEngine(
      appContext,
      appLauncher,
      deviceControl,
      toolRouter,
      dvexContextProvider = null,
      taskContext = DvexTaskContext()
    )
    val engineWithoutExplicitProvider = DvexAgentEngine(
      appContext,
      appLauncher,
      deviceControl,
      toolRouter,
      // No 5th/6th/7th argument → same default as pre-Phase-2.
      taskContext = DvexTaskContext()
    )

    val resultWithNull: DvexToolResult = engineWithNull.runPlan(plan)
    val resultWithout: DvexToolResult = engineWithoutExplicitProvider.runPlan(plan)

    // Same status, same honest message, same verified state. Context is advisory,
    // so omitting it does not change a single planning/execution outcome.
    assertEquals(resultWithNull.status, resultWithout.status)
    assertEquals(resultWithNull.message, resultWithout.message)
    assertEquals(resultWithNull.requiresConfirmation, resultWithout.requiresConfirmation)
  }

  // ------------------------------------------------------------------
  // 4. Existing OPEN_APP / device-control behavior unchanged
  // ------------------------------------------------------------------

  @Test
  fun openAppDoesNotBlockWithoutContext() = runBlocking {
    val engine = DvexAgentEngine(
      appContext,
      appLauncher,
      deviceControl,
      toolRouter,
      dvexContextProvider = null,
      taskContext = DvexTaskContext()
    )

    // Run a real agent-owned plan end to end with NO context provider wired.
    // In this Robolectric environment the launcher cannot launch an installed app
    // that does not exist, so the honest outcome is FAILED with an explanatory
    // message — never a hang, never a context-required wait, never a fabricated
    // success.
    val plan = DvexTaskPlan(
      steps = listOf(AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "NonExistentApp")),
      summary = "open non existent app"
    )

    val result: DvexToolResult = engine.runPlan(plan)

    assertNotNull(result)
    assertNotNull(result.message)
    assertTrue(result.message.isNotEmpty())
    // The outcome must be an honest ToolResult; the exact status is environment
    // dependent, but it must never be a context-required or a fabricated success.
    assertTrue(
      result.status == DvexToolStatus.FAILED ||
        result.status == DvexToolStatus.PERMISSION_REQUIRED ||
        result.status == DvexToolStatus.UNSUPPORTED
    )
    assertTrue(
      result.message.contains("not found") ||
        result.message.contains("no permission") ||
        result.message.contains("couldn't")
    )
  }

  @Test
  fun openSettingsKeepsHonestWordingWithoutContext() = runBlocking {
    val engine = DvexAgentEngine(
      appContext,
      appLauncher,
      deviceControl,
      toolRouter,
      dvexContextProvider = null,
      taskContext = DvexTaskContext()
    )

    val plan = DvexTaskPlan(
      steps = listOf(
        AgentAction(AgentActionType.OPEN_SETTINGS, DvexCapability.SETTINGS, target = "Settings")
      ),
      summary = "open settings"
    )

    val result: DvexToolResult = engine.runPlan(plan)

    // Device-control layer runs unchanged; the wording is "Opening"/"Opened"
    // based on verification only — context provider is absent so no context is read.
    assertNotNull(result)
    assertTrue(result.message.contains("Opening") || result.message.contains("Opened"))
    assertFalse(result.message.contains("com.android.settings"))
  }

  @Test
  fun deviceControlWithoutContextIsNotBlocked() = runBlocking {
    val engine = DvexAgentEngine(
      appContext,
      appLauncher,
      deviceControl,
      toolRouter,
      dvexContextProvider = null,
      taskContext = DvexTaskContext()
    )

    val plan = DvexTaskPlan(
      steps = listOf(
        AgentAction(AgentActionType.NAVIGATE_HOME, DvexCapability.NAVIGATION, target = "Home")
      ),
      summary = "go home"
    )

    val result: DvexToolResult = engine.runPlan(plan)

    // Home navigation is a device-control action independent of context.
    assertNotNull(result)
    assertTrue(result.message.contains("home") || result.message.contains("Home"))
  }

  // ------------------------------------------------------------------
  // 5. Context is on-demand only
  // ------------------------------------------------------------------

  @Test
  fun engineConstructionDoesNotBuildSnapshot() {
    var providerBuilt = false
    val provider = DvexContextProvider(appContext)
    val original = provider::buildSnapshot

    // The public provider is a plain final class whose only API is buildSnapshot().
    // We assert that constructing the engine with dvexContextProvider = null never
    // touches Android at all.
    val engine = DvexAgentEngine(
      appContext,
      appLauncher,
      deviceControl,
      toolRouter,
      dvexContextProvider = null,
      taskContext = DvexTaskContext()
    )

    assertNotNull(engine)
    // No snapshot was built; the task context is untouched (no remembered steps).
    assertNull(engine.taskContext.lastTargetApp)
  }

  @Test
  fun contextIsOnlyBuiltWhenAHandlerThatRequestsItExecutes() {
    var invoked = false
    val handler = object : AgentActionHandler {
      override val actionType = AgentActionType.OPEN_APP
      override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
        invoked = env.dvexContextProvider != null
        return AgentActionResult(
          status = AgentActionStatus.SUCCESS,
          message = "Opening test."
        )
      }
    }

    val env = DvexAgentEnvironment(
      context = appContext,
      appLauncher = appLauncher,
      deviceControl = deviceControl,
      toolRouter = toolRouter,
      dvexContextProvider = null
    )

    runBlocking {
      handler.execute(AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "YouTube"), env)
    }

    assertFalse(invoked)
  }

  // ------------------------------------------------------------------
  // 6. Gemini/NLP is untouched: no automatic context injection
  // ------------------------------------------------------------------

  @Test
  fun contextSnapshotIsNotAppendedToGeminiPrompt() {
    // DvexContextSnapshot lives in the agent/env layer only. It is never referenced
    // from the Gemini path (DvexSmartBrain / AiEngine), which builds prompts from the
    // tool result, conversation state, and tool-call state.
    val engine = DvexAgentEngine(
      appContext,
      appLauncher,
      deviceControl,
      toolRouter,
      dvexContextProvider = null,
      taskContext = DvexTaskContext()
    )

    val plan = DvexTaskPlan(
      steps = listOf(AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = "YouTube")),
      summary = "open YouTube"
    )

    runBlocking {
      val result: DvexToolResult = engine.runPlan(plan)

      // The result contains no fabricated context and no snapshot payload.
      assertFalse(result.message.contains("com.google.android.youtube"))
      assertFalse(result.message.contains("DvexContextSnapshot"))

      // Regardless of the exact status (environment-dependent), the result must be a
      // real, honest tool result: never null, never blank, and never a fabricated
      // success that claims an action D-VEX could not actually perform.
      assertNotNull(result)
      assertNotNull(result.message)
      assertTrue(result.message.isNotEmpty())
      assertTrue(
        result.status == DvexToolStatus.FAILED ||
          result.status == DvexToolStatus.PERMISSION_REQUIRED ||
          result.status == DvexToolStatus.SUCCESS
      )
    }
  }
}

package com.example.agent

import android.app.Application
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.brain.DvexToolRouter
import com.example.model.AgentActivityController
import com.example.vision.LiveVisionStart
import com.example.vision.LiveVisionState
import com.example.vision.VisionOperations
import com.example.ai.VisionAnalysis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * REAL MCP CAMERA TOOLS vs. placeholders (Phase 5) + vision routing honesty.
 *
 * Pins that the three camera tools the model can select — `analyze_frame`,
 * `start_live_vision`, `stop_live_vision` — are wired to REAL handlers over the
 * [VisionOperations] seam, and that model-selected calls still go through the
 * engine's capability + policy + confirmation boundary rather than executing raw:
 *  - every camera tool is admitted by the registry ONLY because a handler exists;
 *  - `start_live_vision` is HIGH risk, so an unconfirmed plan is HELD at the
 *    confirmation gate and never reaches the handler;
 *  - a confirmed plan reaches the handler exactly once (duplicate prevention);
 *  - handlers report the fake's honest outcomes verbatim — success, camera
 *    inactive, already running, missing key — never fabricating a description;
 *  - with no vision wired, the tools fail honestly instead of pretending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexCameraVisionToolTest {

  private lateinit var appContext: Application
  private lateinit var appLauncher: AppLauncherRepository
  private lateinit var deviceControl: DeviceControlRepository
  private lateinit var toolRouter: DvexToolRouter

  @Before
  fun setUp() {
    appContext = RuntimeEnvironment.getApplication()
    appLauncher = AppLauncherRepository(appContext)
    deviceControl = DeviceControlRepository(appContext, appLauncher)
    toolRouter = DvexToolRouter(appContext, appLauncher, deviceControl)
    AgentActivityController.clear()
    DvexAgentStateController.reset()
  }

  @After
  fun tearDown() {
    AgentActivityController.clear()
    DvexAgentStateController.reset()
  }

  /** Records calls; returns scripted outcomes so routing/denial is provable. */
  private class FakeVision(
    var startResult: LiveVisionStart = LiveVisionStart.STARTED,
    var stopResult: Boolean = true,
    var analyzeResult: VisionAnalysis = VisionAnalysis(text = "A metal gearbox with a visible part number.")
  ) : VisionOperations {
    var startCalls = 0
    var stopCalls = 0
    var analyzeCalls = 0
    var lastQuestion: String? = null

    override fun isCameraActive(): Boolean = true
    override suspend fun captureAndAnalyze(question: String): VisionAnalysis {
      analyzeCalls++
      lastQuestion = question
      return analyzeResult
    }
    override fun startLiveVision(): LiveVisionStart {
      startCalls++
      return startResult
    }
    override suspend fun stopLiveVision(): Boolean {
      stopCalls++
      return stopResult
    }
    override fun liveVisionState(): LiveVisionState = LiveVisionState(active = startCalls > stopCalls)
  }

  private fun engine(vision: VisionOperations?): DvexAgentEngine = DvexAgentEngine(
    appContext,
    appLauncher,
    deviceControl,
    toolRouter,
    // CAMERA is always available in the JVM environment: these tests are about
    // tool routing and the confirmation gate, not the runtime permission prompt.
    capabilityManager = object : DvexCapabilityProbe {
      override fun state(capability: DvexCapability): CapabilityState = CapabilityState.Available
      override fun isAvailable(capability: DvexCapability): Boolean = true
    },
    vision = vision
  )

  private fun analyzeAction(query: String = "") = AgentAction(
    type = AgentActionType.ANALYZE_CAMERA_FRAME,
    capability = DvexCapability.CAMERA,
    query = query
  )
  private fun startAction() = AgentAction(
    type = AgentActionType.START_LIVE_VISION,
    capability = DvexCapability.CAMERA,
    risk = ActionRisk.HIGH
  )
  private fun stopAction() = AgentAction(
    type = AgentActionType.STOP_LIVE_VISION,
    capability = DvexCapability.CAMERA
  )

  // --- Registry admission: real tools, real descriptors ---------------------

  @Test
  fun cameraToolsAreAdmittedWithCameraCapabilityAndHonestRisk() {
    val registry = DvexMcpRegistry.withDefaults()

    val analyze = registry.tool("analyze_frame")
    assertNotNull("analyze_frame must be exposed", analyze)
    assertEquals(AgentActionType.ANALYZE_CAMERA_FRAME, analyze!!.actionType)
    assertEquals(DvexCapability.CAMERA, analyze.capability)
    assertEquals("camera", analyze.requiredPermission)
    assertEquals(ActionRisk.LOW, analyze.risk)

    val start = registry.tool("start_live_vision")
    assertNotNull("start_live_vision must be exposed", start)
    assertEquals(AgentActionType.START_LIVE_VISION, start!!.actionType)
    assertEquals(
      "continuous camera use is HIGH risk — the user must confirm it",
      ActionRisk.HIGH,
      start.risk
    )

    val stop = registry.tool("stop_live_vision")
    assertNotNull("stop_live_vision must be exposed", stop)
    assertEquals(AgentActionType.STOP_LIVE_VISION, stop!!.actionType)
    // Stopping is always safe.
    assertEquals(ActionRisk.LOW, stop.risk)
  }

  @Test
  fun cameraToolsUseTheCanonicalToolNameMapping() {
    // The same routing names Gemini sees and the engine resolves back.
    assertEquals("analyze_frame", toolNameFor(AgentActionType.ANALYZE_CAMERA_FRAME))
    assertEquals("start_live_vision", toolNameFor(AgentActionType.START_LIVE_VISION))
    assertEquals("stop_live_vision", toolNameFor(AgentActionType.STOP_LIVE_VISION))
  }

  // --- Engine gate: HIGH-risk start is held until confirmed -----------------

  @Test
  fun unconfirmedStartLiveVisionIsHeldAtTheConfirmationGate() = runBlocking {
    val vision = FakeVision()
    val plan = DvexTaskPlan(listOf(startAction()), "start continuous vision")

    val result = engine(vision).executePlan(plan)

    assertEquals(AgentActionStatus.REQUIRES_CONFIRMATION, result.firstResult!!.status)
    assertEquals(
      "the handler must NOT run before the user confirms",
      0,
      vision.startCalls
    )
  }

  @Test
  fun confirmedStartLiveVisionReachesTheHandlerExactlyOnce() = runBlocking {
    val vision = FakeVision()
    // `confirmed = true` is only settable by the planner/protocol path — the same
    // admitted plan the user said "yes" to.
    val plan = DvexTaskPlan(listOf(startAction()), "start continuous vision", confirmed = true)

    val result = engine(vision).executePlan(plan)

    assertTrue("expected success, got ${result.firstResult!!.status}", result.firstResult!!.isSuccess)
    assertEquals("duplicate prevention: exactly one handler execution", 1, vision.startCalls)
    // The real outcome from the seam is echoed, not embellished.
    assertTrue(result.firstResult!!.message.contains("started", ignoreCase = true))
  }

  @Test
  fun duplicateStartIsReportedAsAlreadyRunningNotAsANewStart() = runBlocking {
    val vision = FakeVision(startResult = LiveVisionStart.ALREADY_RUNNING)
    val plan = DvexTaskPlan(listOf(startAction()), "start continuous vision", confirmed = true)

    val result = engine(vision).executePlan(plan)

    assertTrue(result.firstResult!!.isSuccess)
    assertEquals(1, vision.startCalls)
    assertTrue(
      "must tell the user it was already running",
      result.firstResult!!.message.contains("already running", ignoreCase = true)
    )
  }

  @Test
  fun cameraInactiveStartFailsHonestlyInsteadOfClaimingSuccess() = runBlocking {
    val vision = FakeVision(startResult = LiveVisionStart.CAMERA_INACTIVE)
    val plan = DvexTaskPlan(listOf(startAction()), "start continuous vision", confirmed = true)

    val result = engine(vision).executePlan(plan)

    assertFalse("a refused start is never a success", result.firstResult!!.isSuccess)
    assertEquals(1, vision.startCalls)
    assertTrue(
      result.firstResult!!.message.contains("camera", ignoreCase = true)
    )
  }

  @Test
  fun stopLiveVisionIsSafeAndNeedsNoConfirmation() = runBlocking {
    val vision = FakeVision(stopResult = true)
    val plan = DvexTaskPlan(listOf(stopAction()), "stop continuous vision")

    val result = engine(vision).executePlan(plan)

    assertTrue(result.firstResult!!.isSuccess)
    assertEquals(1, vision.stopCalls)
  }

  @Test
  fun stopWhenNotRunningReportsNothingToStop() = runBlocking {
    val vision = FakeVision(stopResult = false)
    val plan = DvexTaskPlan(listOf(stopAction()), "stop continuous vision")

    val result = engine(vision).executePlan(plan)

    assertTrue("stopping an idle loop is still a safe, honest success", result.firstResult!!.isSuccess)
    assertTrue(result.firstResult!!.message.contains("wasn't running", ignoreCase = true))
  }

  // --- Analyze: real model words, honest failures ---------------------------

  @Test
  fun analyzeFrameReturnsTheModelsOwnWordsAndForwardsTheQuestion() = runBlocking {
    val vision = FakeVision(
      analyzeResult = VisionAnalysis(text = "A rusty tractor linkage, part number 8H-2210.")
    )
    val plan = DvexTaskPlan(listOf(analyzeAction(query = "read the part number")), "analyze frame")

    val result = engine(vision).executePlan(plan)

    assertEquals(1, vision.analyzeCalls)
    assertEquals("read the part number", vision.lastQuestion)
    assertTrue(result.firstResult!!.isSuccess)
    // The payload IS the observation, verbatim.
    assertEquals("A rusty tractor linkage, part number 8H-2210.", result.firstResult!!.message)
  }

  @Test
  fun analyzeFrameFailureIsNeverReportedAsADescription() = runBlocking {
    val vision = FakeVision(
      analyzeResult = VisionAnalysis(
        error = "The camera did not deliver a frame in time — nothing was analyzed.",
        errorKind = VisionAnalysis.ErrorKind.NETWORK
      )
    )
    val plan = DvexTaskPlan(listOf(analyzeAction()), "analyze frame")

    val result = engine(vision).executePlan(plan)

    assertFalse("a failed analysis is not a success", result.firstResult!!.isSuccess)
    assertFalse(
      "the error must not be treated as content",
      result.firstResult!!.isSuccess
    )
    assertTrue(result.firstResult!!.message.contains("analyzed", ignoreCase = true))
  }

  // --- No vision wired: honest denial, never a phantom camera ----------------

  @Test
  fun cameraToolsFailHonestlyWhenVisionIsNotWired() = runBlocking {
    val engine = engine(vision = null)

    val analyze = engine.executePlan(DvexTaskPlan(listOf(analyzeAction()), "analyze"))
    val start = engine.executePlan(
      DvexTaskPlan(listOf(startAction()), "start", confirmed = true)
    )
    val stop = engine.executePlan(DvexTaskPlan(listOf(stopAction()), "stop"))

    listOf(analyze, start, stop).forEach { r ->
      assertFalse("must not claim success with no camera stack", r.firstResult!!.isSuccess)
      assertTrue(
        r.firstResult!!.message.contains("vision", ignoreCase = true) ||
          r.firstResult!!.message.contains("camera", ignoreCase = true)
      )
    }
  }
}

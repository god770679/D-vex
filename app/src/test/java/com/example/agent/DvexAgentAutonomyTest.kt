package com.example.agent

import android.app.Application
import com.example.brain.DvexIntent
import com.example.brain.DvexToolRouter
import com.example.brain.DvexToolStatus
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.model.AgentActivityController
import com.example.model.DvexAssistantState
import com.example.model.OrbDisplayState
import com.example.model.OrbDisplayStateMapper
import java.io.File
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
 * PHASE 3 + PHASE 4 — general N-step planning and failure-aware autonomous execution.
 *
 * These tests lock the acceptance contract:
 *
 *  Phase 3 — ordered multi-step plans (2/3/4 steps) composed from the utterance,
 *  validated against the registry (registered actions only, arguments present,
 *  capabilities representable), executed by the EXISTING engine step by step with
 *  capability checks and the confirmation policy untouched, stopping honestly at
 *  the first genuine failure, and reported as STRUCTURED results only.
 *
 *  Phase 4 — recoverable failures are detected and retried at most ONCE per step,
 *  at most ONE re-plan per task over registered actions only, partial completion
 *  (completed / failed / not-executed) is preserved exactly, UNVERIFIED is never
 *  converted to SUCCESS, ambiguous follow-ups ask for clarification through the
 *  structured path, and the agent/orb state buses keep their real signals.
 *
 *  Across all of it: no authored user-facing conversational replies anywhere in
 *  the agent layer — [com.example.brain.DvexResponseGenerator] remains the sole
 *  conversational authority, speaking from the actual structured result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexAgentAutonomyTest {

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

  // ------------------------------------------------------------------
  // Harness: scripted handlers + probes (no device, no guessing)
  // ------------------------------------------------------------------

  /** A handler that records every invocation and replays a scripted outcome list. */
  private class ScriptedHandler(
    override val actionType: AgentActionType,
    private val outcomes: List<AgentActionResult>,
    private val log: MutableList<AgentActionType>? = null
  ) : AgentActionHandler {
    var invocations: Int = 0
      private set

    override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment): AgentActionResult {
      log?.add(actionType)
      val index = invocations++
      return outcomes[minOf(index, outcomes.lastIndex)]
    }
  }

  private fun verifiedOk(message: String = "step verified") = AgentActionResult(
    status = AgentActionStatus.SUCCESS,
    message = message,
    verification = VerificationOutcome.VERIFIED
  )

  private fun unverifiedOk(message: String = "step dispatched") = AgentActionResult(
    status = AgentActionStatus.SUCCESS,
    message = message,
    verification = VerificationOutcome.UNVERIFIED
  )

  private fun recoverableFailure(message: String) = AgentActionResult(
    status = AgentActionStatus.FAILURE,
    message = message,
    failureClass = FailureClass.RECOVERABLE
  )

  private fun terminalFailure(message: String) = AgentActionResult(
    status = AgentActionStatus.FAILURE,
    message = message,
    failureClass = FailureClass.TERMINAL
  )

  private object AlwaysAvailableProbe : DvexCapabilityProbe {
    override fun state(capability: DvexCapability): CapabilityState = CapabilityState.Available
    override fun isAvailable(capability: DvexCapability): Boolean = true
  }

  private class DeniedCapabilityProbe(
    private val denied: DvexCapability,
    private val denial: CapabilityState
  ) : DvexCapabilityProbe {
    override fun state(capability: DvexCapability): CapabilityState =
      if (capability == denied) denial else CapabilityState.Available

    override fun isAvailable(capability: DvexCapability): Boolean =
      state(capability) is CapabilityState.Available
  }

  private fun registryOf(vararg handlers: AgentActionHandler): DvexActionRegistry {
    val registry = DvexActionRegistry()
    handlers.forEach { registry.registerAction(it) }
    return registry
  }

  private fun engine(
    registry: DvexActionRegistry = DvexActionRegistry.withDefaults(),
    probe: DvexCapabilityProbe = AlwaysAvailableProbe,
    taskContext: DvexTaskContext = DvexTaskContext()
  ) = DvexAgentEngine(
    appContext,
    appLauncher,
    deviceControl,
    toolRouter,
    dvexContextProvider = null,
    capabilityManager = probe,
    registry = registry,
    taskContext = taskContext
  )

  private fun openStep(app: String = "YouTube") =
    AgentAction(AgentActionType.OPEN_APP, DvexCapability.APP_LAUNCH, target = app)

  private fun searchStep(target: String = "YouTube", query: String = "tractor videos") =
    AgentAction(AgentActionType.SEARCH_IN_APP, DvexCapability.IN_APP_SEARCH, target = target, query = query)

  private fun typeStep(text: String = "Spider-Man") =
    AgentAction(AgentActionType.TYPE_TEXT, DvexCapability.UI_INTERACTION, query = text)

  private fun scrollStep(direction: String = "DOWN") =
    AgentAction(AgentActionType.SCROLL, DvexCapability.UI_INTERACTION, query = direction)

  // ==================================================================
  // PHASE 3 — general N-step planning
  // ==================================================================

  // 1. Existing 2-step Open → Search behaviour preserved.
  @Test
  fun existingTwoStepOpenAndSearchStillPlans() {
    val planner = DvexTaskPlanner(DvexTaskContext())
    val outcome = planner.planOutcome(
      DvexIntent.MultiStep(
        first = DvexIntent.OpenApp("YouTube"),
        second = DvexIntent.SearchWeb("Spider-Man")
      ),
      "Open YouTube and search for Spider-Man"
    )
    assertTrue(outcome is PlanOutcome.Represented)
    val plan = (outcome as PlanOutcome.Represented).plan
    assertEquals(2, plan.stepCount)
    assertEquals(AgentActionType.OPEN_APP, plan.steps[0].type)
    assertEquals(AgentActionType.SEARCH_IN_APP, plan.steps[1].type)
    assertEquals("Spider-Man", plan.steps[1].query)
  }

  // 2. Valid 3-step plan composed from the utterance.
  @Test
  fun threeStepChainPlansInOrder() {
    val planner = DvexTaskPlanner(DvexTaskContext())
    val outcome = planner.planOutcome(
      DvexIntent.OpenApp("YouTube"),
      "Open YouTube, search tractor videos, scroll down"
    )
    assertTrue("expected a represented plan, got $outcome", outcome is PlanOutcome.Represented)
    val steps = (outcome as PlanOutcome.Represented).plan.steps
    assertEquals(3, steps.size)
    assertEquals(AgentActionType.OPEN_APP, steps[0].type)
    assertEquals("YouTube", steps[0].target)
    assertEquals(AgentActionType.SEARCH_IN_APP, steps[1].type)
    assertEquals("YouTube", steps[1].target)
    assertEquals("tractor videos", steps[1].query)
    assertEquals(AgentActionType.SCROLL, steps[2].type)
    assertEquals("DOWN", steps[2].query)
  }

  // 3. Valid 4-step plan (open → search → tap → scroll), the Phase-3 example.
  @Test
  fun fourStepChainPlansInOrder() {
    val planner = DvexTaskPlanner(DvexTaskContext())
    val outcome = planner.planOutcome(
      DvexIntent.OpenApp("Instagram"),
      "Open Instagram, search tractor reels, open the tractor reel, then scroll down"
    )
    assertTrue("expected a represented plan, got $outcome", outcome is PlanOutcome.Represented)
    val steps = (outcome as PlanOutcome.Represented).plan.steps
    assertEquals(4, steps.size)
    assertEquals(AgentActionType.OPEN_APP, steps[0].type)
    assertEquals("Instagram", steps[0].target)
    assertEquals(AgentActionType.SEARCH_IN_APP, steps[1].type)
    assertEquals("Instagram", steps[1].target)
    assertEquals("tractor reels", steps[1].query)
    assertEquals(AgentActionType.TAP_ELEMENT, steps[2].type)
    assertEquals("tractor reel", steps[2].query)
    assertEquals(AgentActionType.SCROLL, steps[3].type)
    assertEquals("DOWN", steps[3].query)
    // Every planned step must be represented in the default registry.
    val registry = DvexActionRegistry.withDefaults()
    steps.forEach { step ->
      assertNotNull("step ${step.type} must be registered", registry.handlerFor(step.type))
      assertTrue(step.capability in DvexCapability.entries)
    }
  }

  // 4. Step ordering is honoured during execution (no reordering, no skipping).
  @Test
  fun planStepsExecuteInDeclaredOrder() = runBlocking {
    val executionLog = mutableListOf<AgentActionType>()
    val registry = registryOf(
      ScriptedHandler(AgentActionType.OPEN_APP, listOf(verifiedOk("opened")), executionLog),
      ScriptedHandler(AgentActionType.SEARCH_IN_APP, listOf(verifiedOk("searched")), executionLog),
      ScriptedHandler(AgentActionType.TYPE_TEXT, listOf(verifiedOk("typed")), executionLog),
      ScriptedHandler(AgentActionType.SCROLL, listOf(verifiedOk("scrolled")), executionLog)
    )
    val plan = DvexTaskPlan(
      steps = listOf(openStep(), searchStep(), typeStep(), scrollStep()),
      summary = "open, search, type and scroll"
    )
    val result = engine(registry).executePlan(plan)
    assertEquals(
      listOf(
        AgentActionType.OPEN_APP,
        AgentActionType.SEARCH_IN_APP,
        AgentActionType.TYPE_TEXT,
        AgentActionType.SCROLL
      ),
      executionLog
    )
    assertTrue(result.isComplete)
    assertEquals(4, result.completedCount)
  }

  // 5. Unsupported chained step → structured UNSUPPORTED, never fake execution.
  @Test
  fun unsupportedChainedStepIsRejectedStructurally() = runBlocking {
    // The default registry minus SCROLL: the planner must refuse honestly.
    val withoutScroll = DvexActionRegistry().apply {
      DvexDefaultActions.all()
        .filter { it.actionType != AgentActionType.SCROLL }
        .forEach { registerAction(it) }
    }

    // Planner level: structured reason with the failing step index.
    val planner = DvexTaskPlanner(DvexTaskContext(), withoutScroll)
    val outcome = planner.planOutcome(DvexIntent.OpenApp("YouTube"), "Open YouTube, scroll down")
    assertTrue("expected Unsupported, got $outcome", outcome is PlanOutcome.Unsupported)
    assertTrue((outcome as PlanOutcome.Unsupported).reason.contains("SCROLL"))
    assertEquals(1, outcome.stepIndex)

    // Engine level: a structured tool result with NO authored speech of its own.
    val engineResult = engine(withoutScroll)
      .executeIfApplicable(DvexIntent.OpenApp("YouTube"), "Open YouTube, scroll down")
    assertNotNull(engineResult)
    assertEquals(DvexToolStatus.UNSUPPORTED, engineResult!!.status)
    assertEquals("agent", engineResult.toolName)
    assertFalse(engineResult.clarificationRequired)
    assertEquals("", engineResult.spokenText)
    assertTrue(engineResult.message.isNotBlank())
    assertFalse(Regex("\\bSir\\b", RegexOption.IGNORE_CASE).containsMatchIn(engineResult.message))
  }

  // 6. Capability validation: the capability gate still runs BEFORE execution.
  @Test
  fun capabilityCheckGatesExecutionBeforeAnyHandlerRuns() = runBlocking {
    val scrollHandler = ScriptedHandler(AgentActionType.SCROLL, listOf(verifiedOk()))
    val probe = DeniedCapabilityProbe(
      DvexCapability.UI_INTERACTION,
      CapabilityState.PermissionRequired("accessibility", "accessibility is required")
    )
    val result = engine(registryOf(scrollHandler), probe)
      .executePlan(DvexTaskPlan(listOf(scrollStep()), "scroll down"))

    assertEquals(0, scrollHandler.invocations)
    assertEquals(AgentActionStatus.REQUIRES_PERMISSION, result.steps.single().second.status)
    assertEquals("accessibility", result.permissionRequired)
    assertEquals(AgentState.WAITING_FOR_PERMISSION, DvexAgentStateController.current)
  }

  // 7. Confirmation policy preserved: HIGH-risk actions never auto-execute.
  @Test
  fun confirmationPolicyStillGatesHighRiskActions() = runBlocking {
    val messageHandler = ScriptedHandler(
      AgentActionType.SEND_MESSAGE,
      listOf(verifiedOk("message prepared"))
    )
    val highRisk = AgentAction(
      type = AgentActionType.SEND_MESSAGE,
      capability = DvexCapability.MESSAGING,
      target = "Arun",
      query = "hi",
      risk = ActionRisk.HIGH
    )
    assertTrue(DvexActionPolicy.requiresConfirmation(highRisk))

    val taskResult = engine(registryOf(messageHandler))
      .executePlan(DvexTaskPlan(listOf(highRisk), "message Arun"))
    assertEquals(0, messageHandler.invocations)
    assertEquals(AgentActionStatus.REQUIRES_CONFIRMATION, taskResult.steps.single().second.status)

    val toolResult = taskResult.toToolResult()
    assertEquals(DvexToolStatus.CONFIRMATION_REQUIRED, toolResult.status)
    assertTrue(toolResult.requiresConfirmation)
    assertNotNull(toolResult.confirmationPrompt)
  }

  // 8. Stop on first genuine (terminal) failure — no retry, no continuation.
  @Test
  fun genuineFailureStopsThePlanImmediately() = runBlocking {
    val openHandler = ScriptedHandler(AgentActionType.OPEN_APP, listOf(verifiedOk()))
    val searchHandler = ScriptedHandler(
      AgentActionType.SEARCH_IN_APP,
      listOf(terminalFailure("search backend refused"))
    )
    val scrollHandler = ScriptedHandler(AgentActionType.SCROLL, listOf(verifiedOk()))

    val plan = DvexTaskPlan(
      listOf(openStep(), searchStep(), scrollStep()),
      "open, search and scroll"
    )
    val result = engine(registryOf(openHandler, searchHandler, scrollHandler)).executePlan(plan)

    // Terminal failure: no retry, no re-plan, no false continuation.
    assertEquals(1, openHandler.invocations)
    assertEquals(1, searchHandler.invocations)
    assertEquals(0, scrollHandler.invocations)
    assertEquals(0, result.recoveryAttempts)
    assertFalse(result.replanned)
    assertEquals(1, result.failedAtStep)
    assertEquals(listOf(scrollStep()), result.notExecutedSteps)
    assertFalse(result.isComplete)
  }

  // 10. Structured execution result: every field the response layer needs.
  @Test
  fun executionResultIsFullyStructured() = runBlocking {
    val plan = DvexTaskPlan(
      listOf(openStep(), searchStep(), scrollStep()),
      "open, search and scroll"
    )
    val result = engine(
      registryOf(
        ScriptedHandler(AgentActionType.OPEN_APP, listOf(verifiedOk())),
        ScriptedHandler(AgentActionType.SEARCH_IN_APP, listOf(terminalFailure("search backend refused"))),
        ScriptedHandler(AgentActionType.SCROLL, listOf(verifiedOk()))
      )
    ).executePlan(plan)

    // completed: 1 — failed: 2 — not executed: 3 (exactly the acceptance example)
    assertEquals(listOf(openStep()), result.completedSteps)
    assertNotNull(result.failedStep)
    assertEquals(AgentActionType.SEARCH_IN_APP, result.failedStep!!.type)
    assertNotNull(result.failedStepResult)
    assertEquals(AgentActionStatus.FAILURE, result.failedStepResult!!.status)
    assertEquals(VerificationOutcome.UNVERIFIED, result.failedVerification)
    assertEquals(listOf(scrollStep()), result.notExecutedSteps)
    assertNull(result.permissionRequired)
    assertNull(result.unsupportedReason)
    assertEquals(1, result.completedCount)
    assertFalse(result.isComplete)
  }

  // ==================================================================
  // PHASE 4 — bounded recovery, one re-plan, honest partial results
  // ==================================================================

  // 11. Recoverable failure is detected and retried EXACTLY once.
  @Test
  fun recoverableFailureIsRetriedExactlyOnceAndCanSucceed() = runBlocking {
    val searchHandler = ScriptedHandler(
      AgentActionType.SEARCH_IN_APP,
      listOf(recoverableFailure("search field not visible yet"), verifiedOk("searched"))
    )
    val openHandler = ScriptedHandler(AgentActionType.OPEN_APP, listOf(verifiedOk()))
    val scrollHandler = ScriptedHandler(AgentActionType.SCROLL, listOf(verifiedOk()))

    val plan = DvexTaskPlan(listOf(openStep(), searchStep(), scrollStep()), "open, search, scroll")
    val result = engine(registryOf(openHandler, searchHandler, scrollHandler)).executePlan(plan)

    assertEquals(2, searchHandler.invocations) // initial attempt + ONE retry, never more
    assertEquals(1, result.recoveryAttempts)
    assertEquals(1, scrollHandler.invocations) // continuation only after success
    assertTrue(result.isComplete)
  }

  // 12. A failure that stays recoverable is NOT retried in a loop.
  @Test
  fun recoveryIsBoundedToASingleAttemptPerStep() = runBlocking {
    val searchHandler = ScriptedHandler(
      AgentActionType.SEARCH_IN_APP,
      listOf(recoverableFailure("search field never appeared"))
    )
    val openHandler = ScriptedHandler(AgentActionType.OPEN_APP, listOf(verifiedOk()))

    val plan = DvexTaskPlan(listOf(openStep(), searchStep()), "open and search")
    val result = engine(registryOf(openHandler, searchHandler)).executePlan(plan)

    assertEquals(2, searchHandler.invocations) // 1 attempt + 1 retry — never 3
    assertEquals(1, result.recoveryAttempts)
    assertEquals(1, result.failedAtStep)
    assertFalse(result.isComplete)
  }

  // 13 + 14 + 16. ONE re-plan per task, registered actions only, failure preserved,
  // later steps never executed and never reported as completed.
  @Test
  fun oneReplanPerTaskWithHonestPartialPreservation() = runBlocking {
    val openHandler = ScriptedHandler(AgentActionType.OPEN_APP, listOf(verifiedOk("opened")))
    val searchHandler = ScriptedHandler(
      AgentActionType.SEARCH_IN_APP,
      listOf(recoverableFailure("search surface changed"))
    )
    val typeHandler = ScriptedHandler(
      AgentActionType.TYPE_TEXT,
      listOf(recoverableFailure("field never appeared"))
    )
    val scrollHandler = ScriptedHandler(AgentActionType.SCROLL, listOf(verifiedOk("scrolled")))

    val plan = DvexTaskPlan(
      listOf(openStep(), searchStep(), typeStep(), scrollStep()),
      "open, search, type, scroll"
    )
    val registry = registryOf(openHandler, searchHandler, typeHandler, scrollHandler)
    val result = engine(registry).runTask(plan)

    // Recovery: exactly one retry for each recoverable step — no loops.
    assertEquals(2, searchHandler.invocations)
    assertEquals(2, typeHandler.invocations)

    // Re-plan: exactly ONE, over the remaining registered steps. The third
    // failure stops the task honestly — SCROLL never runs and is not re-planned again.
    assertTrue(result.replanned)
    assertEquals(0, scrollHandler.invocations)
    assertEquals(2, result.recoveryAttempts) // one per recoverable step, bounded

    // Partial preservation: completed = step 1, failed = step 2 (+ step 3 failed),
    // step 4 explicitly not executed.
    assertEquals(listOf(openStep()), result.completedSteps)
    assertEquals(AgentActionType.SEARCH_IN_APP, result.failedStep!!.type)
    assertEquals(AgentActionType.TYPE_TEXT, result.steps[2].first.type)
    assertEquals(AgentActionStatus.FAILURE, result.steps[2].second.status)
    assertEquals(listOf(scrollStep()), result.notExecutedSteps)
    assertFalse(result.isComplete)

    // The structured tool result never claims completion and keeps the real reason.
    val toolResult = result.toToolResult()
    assertEquals(DvexToolStatus.FAILED, toolResult.status)
    assertFalse(toolResult.spokenText.contains("Done."))
    assertTrue(toolResult.message.contains("search surface changed"))
    assertTrue(toolResult.message.contains("Opened YouTube"))
  }

  // 15. Re-plan uses only registered actions: safe sequences only, else none.
  @Test
  fun replanOnlyEverYieldsRegisteredActions() {
    val taskContext = DvexTaskContext()
    taskContext.remember(openStep("YouTube"))
    val defaultRegistry = DvexActionRegistry.withDefaults()
    val planner = DvexTaskPlanner(taskContext, defaultRegistry)

    // Blank search targets are refreshed from the verified task context.
    val original = DvexTaskPlan(
      listOf(
        openStep("YouTube"),
        searchStep(target = "", query = "tractor videos"),
        scrollStep()
      ),
      "open, search, scroll"
    )
    val revised = planner.replanAfterFailure(original, failedIndex = 0)
    assertNotNull(revised)
    assertEquals(2, revised!!.stepCount)
    assertEquals(AgentActionType.SEARCH_IN_APP, revised.steps[0].type)
    assertEquals("YouTube", revised.steps[0].target) // resolved, never invented
    revised.steps.forEach { step ->
      assertNotNull(
        "re-planned step ${step.type} must be registered",
        defaultRegistry.handlerFor(step.type)
      )
    }

    // When the remaining sequence cannot be validated, NO re-plan is produced —
    // the engine then stops honestly instead of guessing.
    val withoutScroll = DvexActionRegistry().apply {
      DvexDefaultActions.all()
        .filter { it.actionType != AgentActionType.SCROLL }
        .forEach { registerAction(it) }
    }
    val strictPlanner = DvexTaskPlanner(DvexTaskContext(), withoutScroll)
    val unsafe = DvexTaskPlan(
      listOf(openStep("YouTube"), searchStep(target = "YouTube"), scrollStep()),
      "open, search, scroll"
    )
    assertNull(strictPlanner.replanAfterFailure(unsafe, failedIndex = 0))
  }

  // 18. UNVERIFIED is never converted to SUCCESS (and never counts as done).
  @Test
  fun unverifiedSuccessIsNeverReportedAsSuccess() = runBlocking {
    val unverifiedHandler = ScriptedHandler(
      AgentActionType.SCROLL,
      listOf(unverifiedOk("scrolling"))
    )
    val toolResult = engine(registryOf(unverifiedHandler))
      .runPlan(DvexTaskPlan(listOf(scrollStep()), "scroll down"))
    assertEquals(DvexToolStatus.UNVERIFIED, toolResult.status)
    assertFalse(toolResult.isSuccessful)
    assertFalse(toolResult.spokenText.contains("Done."))

    val verifiedHandler = ScriptedHandler(
      AgentActionType.SCROLL,
      listOf(verifiedOk("scrolled"))
    )
    val verifiedResult = engine(registryOf(verifiedHandler))
      .runPlan(DvexTaskPlan(listOf(scrollStep()), "scroll down"))
    assertEquals(DvexToolStatus.SUCCESS, verifiedResult.status)

    // Data-level gate too: an unverified result can never be "confirmed".
    assertFalse(unverifiedOk().isConfirmed)
    assertTrue(verifiedOk().isConfirmed)
  }

  // 19. Foreground verification is honest: a missing app is never a success.
  @Test
  fun foregroundVerificationIsNeverAssumed() = runBlocking {
    // Default registry + real launcher: the app genuinely does not exist here.
    val result = engine().runPlan(
      DvexTaskPlan(listOf(openStep("ZzzNotInstalledApp")), "open missing app")
    )
    assertEquals(DvexToolStatus.FAILED, result.status)
    assertFalse(result.spokenText.contains("Done."))
    assertTrue(result.message.contains("not found", ignoreCase = true) || result.message.contains("couldn't", ignoreCase = true))
  }

  // 20. Context-aware follow-up: "search X" resolves to the remembered app.
  @Test
  fun contextAwareFollowUpResolvesTheSearchTarget() {
    val taskContext = DvexTaskContext()
    taskContext.remember(openStep("YouTube"))
    val planner = DvexTaskPlanner(taskContext)

    val outcome = planner.planOutcome(DvexIntent.SearchWeb("tractor videos"), "search tractor videos")
    assertTrue("expected a represented plan, got $outcome", outcome is PlanOutcome.Represented)
    val steps = (outcome as PlanOutcome.Represented).plan.steps
    assertEquals(1, steps.size)
    assertEquals(AgentActionType.SEARCH_IN_APP, steps[0].type)
    assertEquals("YouTube", steps[0].target)
  }

  // 21. Ambiguous context asks for clarification — never guesses.
  @Test
  fun ambiguousFollowUpRequestsClarificationStructurally() = runBlocking {
    val taskContext = DvexTaskContext()
    taskContext.remember(openStep("YouTube"))
    val planner = DvexTaskPlanner(taskContext)

    val outcome = planner.planOutcome(DvexIntent.OpenApp("first one"), "open the first one")
    assertTrue("expected clarification, got $outcome", outcome is PlanOutcome.Clarification)

    // Engine path: structured clarification with no authored sentence.
    val toolResult = engine(taskContext = taskContext)
      .executeIfApplicable(DvexIntent.OpenApp("first one"), "open the first one")
    assertNotNull(toolResult)
    assertTrue(toolResult!!.clarificationRequired)
    assertEquals(DvexToolStatus.UNSUPPORTED, toolResult.status)
    assertEquals("", toolResult.spokenText)
    assertTrue(toolResult.message.isNotBlank())
  }

  // 22. Agent state transitions remain accurate (and LISTENING is wired).
  @Test
  fun agentStateTransitionsRemainAccurate() = runBlocking {
    // LISTENING: the voice pipeline opens a command window.
    DvexAgentStateController.transition(AgentState.LISTENING)
    assertEquals(AgentState.LISTENING, DvexAgentStateController.current)
    assertFalse(DvexAgentStateController.isBusy())

    // A successful run ends in RESPONDING.
    val okRegistry = registryOf(ScriptedHandler(AgentActionType.SCROLL, listOf(verifiedOk())))
    engine(okRegistry).runPlan(DvexTaskPlan(listOf(scrollStep()), "scroll down"))
    assertEquals(AgentState.RESPONDING, DvexAgentStateController.current)

    // A failed run ends in ERROR — an honest dead-end, never a fake response.
    DvexAgentStateController.reset()
    val failRegistry = registryOf(
      ScriptedHandler(AgentActionType.SCROLL, listOf(terminalFailure("nothing to scroll")))
    )
    engine(failRegistry).runPlan(DvexTaskPlan(listOf(scrollStep()), "scroll down"))
    assertEquals(AgentState.ERROR, DvexAgentStateController.current)

    // The capability gate parks the agent in WAITING_FOR_PERMISSION.
    DvexAgentStateController.reset()
    val gated = engine(
      registryOf(ScriptedHandler(AgentActionType.SCROLL, listOf(verifiedOk()))),
      DeniedCapabilityProbe(
        DvexCapability.UI_INTERACTION,
        CapabilityState.PermissionRequired("accessibility", "accessibility is required")
      )
    )
    gated.executePlan(DvexTaskPlan(listOf(scrollStep()), "scroll down"))
    assertEquals(AgentState.WAITING_FOR_PERMISSION, DvexAgentStateController.current)
  }

  // 23. The orb agent-state bus remains intact (mapper contract unchanged).
  @Test
  fun orbAgentStateBusRemainsIntact() {
    val standby = DvexAssistantState.Standby
    assertEquals(OrbDisplayState.PLANNING, OrbDisplayStateMapper.map(AgentState.PLANNING, standby))
    assertEquals(OrbDisplayState.THINKING, OrbDisplayStateMapper.map(AgentState.UNDERSTANDING, standby))
    assertEquals(OrbDisplayState.EXECUTING, OrbDisplayStateMapper.map(AgentState.EXECUTING, standby))
    assertEquals(OrbDisplayState.VERIFYING, OrbDisplayStateMapper.map(AgentState.VERIFYING, standby))
    assertEquals(OrbDisplayState.SPEAKING, OrbDisplayStateMapper.map(AgentState.RESPONDING, standby))
    assertEquals(OrbDisplayState.ERROR, OrbDisplayStateMapper.map(AgentState.ERROR, standby))
    assertEquals(
      OrbDisplayState.CONFIRM,
      OrbDisplayStateMapper.map(AgentState.WAITING_FOR_PERMISSION, standby)
    )

    // LISTENING derives from the voice signal; the agent bus alone never fabricates it.
    assertEquals(
      OrbDisplayState.LISTENING,
      OrbDisplayStateMapper.map(AgentState.IDLE, DvexAssistantState.Listening)
    )
    assertEquals(OrbDisplayState.IDLE, OrbDisplayStateMapper.map(AgentState.LISTENING, standby))
  }

  // 24. The response generator receives the ACTUAL execution result — the agent
  // contributes structure and factual handler wording, never an authored reply.
  @Test
  fun responsePipelineReceivesActualExecutionResults() = runBlocking {
    val plan = DvexTaskPlan(listOf(openStep("YouTube"), searchStep()), "open and search")
    val result = engine(
      registryOf(
        ScriptedHandler(AgentActionType.OPEN_APP, listOf(verifiedOk("Opened YouTube."))),
        ScriptedHandler(AgentActionType.SEARCH_IN_APP, listOf(terminalFailure("search backend refused")))
      )
    ).runPlan(plan)

    assertEquals(DvexToolStatus.FAILED, result.status)
    // The real, observed facts — what happened and what did not — travel verbatim.
    assertTrue(result.message.contains("Opened YouTube"))
    assertTrue(result.message.contains("search backend refused"))
    assertEquals(result.message, result.spokenText)
    // And nothing canned or conversational was pre-authored by the agent.
    assertFalse(result.spokenText.contains("Done,"))
    assertFalse(Regex("\\bSir\\b", RegexOption.IGNORE_CASE).containsMatchIn(result.spokenText))
  }

  // ==================================================================
  // GUARD — no hardcoded user-facing conversational replies in the agent layer
  // ==================================================================

  private val agentLayerSources = listOf(
    "src/main/java/com/example/agent/DvexAgentEngine.kt",
    "src/main/java/com/example/agent/DvexTaskPlanner.kt",
    "src/main/java/com/example/agent/DvexDefaultActions.kt"
  )

  private fun sourceFile(relativePath: String): File {
    val candidates = listOf(File(".", relativePath), File("app", relativePath))
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "Could not locate $relativePath from ${File(".").absolutePath}; the guard must not pass silently"
      )
  }

  // 25. The agent layer authors NO canned conversational responses.
  @Test
  fun agentLayerContainsNoCannedConversationalReplies() {
    val forbiddenPhrases = listOf(
      "Done, Sir",
      "Yes Sir",
      "Done Sir",
      "Got it",
      "Task completed.",
      "Action successful.",
      "Standing by",
      "Listening for command",
      "I couldn't do that."
    )
    agentLayerSources.forEach { path ->
      val text = sourceFile(path).readText()
      forbiddenPhrases.forEach { phrase ->
        assertFalse("$path still contains the canned phrase \"$phrase\"", text.contains(phrase))
      }
      assertFalse(
        "$path contains honorific filler",
        Regex("\\bSir\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
      )
    }
  }

  // Planner-level: an unsupported intent shape stays NotAgentOwned (router keeps
  // its behaviour — no duplicate decision system was created).
  @Test
  fun conversationalInputIsNeverAgentOwned() {
    val planner = DvexTaskPlanner(DvexTaskContext())
    assertNull(planner.plan(DvexIntent.Conversation("hello"), "hello"))
    assertNull(planner.plan(DvexIntent.Calculate("25 times 8"), "25 times 8"))
    assertTrue(
      planner.planOutcome(DvexIntent.Conversation("hello"), "hello") is PlanOutcome.NotAgentOwned
    )
  }
}

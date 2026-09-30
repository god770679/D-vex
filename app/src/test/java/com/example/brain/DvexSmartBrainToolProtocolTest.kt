package com.example.brain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.DvexCapability
import com.example.agent.DvexCapabilityProbe
import com.example.agent.DvexToolCall
import com.example.agent.DvexToolProtocol
import com.example.ai.AiEngine
import com.example.ai.AiModelTurn
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * INCREMENT 2 — the brain's model tool-selection path, end to end (no network).
 *
 * The fakes here are UNIT TEST DOUBLES for the AiEngine seam. They are NOT live
 * Gemini responses and NOT evidence of live function calling: no real
 * `functionCall` from `gemini-3.8-flash` has been observed yet (HTTP 503 high
 * demand / free-tier quota). They pin only the D-VEX-side contract:
 *
 *  - recognized conversation stays on the text-only `generate()` path;
 *  - a deterministically recognized action can NEVER be hijacked by the model;
 *  - only the detector's genuine fall-through may reach tool selection;
 *  - a blocked call (unknown tool / missing / malformed argument) makes the whole
 *    turn a refusal: nothing else from that turn executes;
 *  - a HIGH-risk model-selected action waits at the EXISTING confirmation gate
 *    and a "yes" runs the EXISTING confirmed path exactly once;
 *  - a model/tool loop is structurally impossible (one exchange, one action,
 *    at most MAX_TOOL_CALLS_PER_TURN admissions);
 *  - garbled (low-ASR) speech never drives tool selection;
 *  - no internal protocol vocabulary reaches the user-facing layers.
 *
 * ASSERTION LAYERS (important): [BrainExecutionResult.toolResult] carries D-VEX's
 * canonical, honest outcome — including the fixed refusal lines. `spokenText` is
 * whatever the response LLM phrased from it, exactly as for every other action.
 * Assertions about what D-VEX decided therefore target `toolResult`; assertions
 * about what the user hears target `spokenText`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexSmartBrainToolProtocolTest {

  private lateinit var appContext: Context
  private lateinit var appLauncher: AppLauncherRepository
  private lateinit var deviceControl: DeviceControlRepository

  @Before
  fun setUp() {
    appContext = ApplicationProvider.getApplicationContext()
    appLauncher = AppLauncherRepository(appContext)
    deviceControl = DeviceControlRepository(appContext, appLauncher)
  }

  /**
   * A deliberately unrecognized ACTION-shaped utterance. `IntentProbeTmp`-style
   * probing confirmed the deterministic detector does NOT claim it: it lands on
   * the pure Conversation fall-through at 0.70 confidence, which is exactly the
   * one case Increment 2 allows to reach the model. (Phrases like "launch the
   * youtube application" are NOT usable — they are recognized deterministically
   * as PlayYoutubeVideo at 0.95 and never reach tool selection.)
   */
  private val unclaimedActionUtterance = "order a pizza from dominos right now"

  private val unclaimedMessagingUtterance = "ping dad about the flight details"

  /** Records which seam was used and answers with a scripted turn. */
  private class ScriptedEngine(
    var toolAnswer: AiModelTurn? = null,
    private val textAnswer: String = "Here is what I think."
  ) : AiEngine {
    val withToolsRequests = AtomicInteger(0)
    val textRequests = AtomicInteger(0)
    val lastTextPrompt = AtomicReference("")

    override suspend fun generate(prompt: String): String? {
      textRequests.incrementAndGet()
      lastTextPrompt.set(prompt)
      return textAnswer
    }

    override suspend fun generateWithTools(
      prompt: String,
      tools: List<com.example.agent.DvexMcpTool>
    ): AiModelTurn {
      withToolsRequests.incrementAndGet()
      return toolAnswer ?: AiModelTurn(text = textAnswer)
    }
  }

  /**
   * A brain whose capability probe is stubbed AVAILABLE. This is a TEST SEAM for
   * the agent engine's real permission probe only: production always uses
   * `DvexCapabilityManager`, and no capability CHECK is weakened or skipped —
   * the confirmation gate itself is still what holds a HIGH-risk action.
   */
  private fun brain(engine: AiEngine) = DvexSmartBrain(
    appContext, appLauncher, deviceControl, engine,
    capabilityProbe = object : DvexCapabilityProbe {
      override fun state(capability: DvexCapability) = com.example.agent.CapabilityState.Available
      override fun isAvailable(capability: DvexCapability) = true
    }
  )

  private fun openAppCall(app: String = "YouTube") =
    DvexToolCall(toolName = "open_app", arguments = mapOf("app_name" to app))

  private fun sendMessageCall(recipient: String = "Dad", message: String = "I will be late") =
    DvexToolCall(
      toolName = "send_message",
      arguments = mapOf("recipient" to recipient, "message" to message)
    )

  /** Precondition guard: the utterance must stay on the deterministic fall-through. */
  private fun assertUnclaimed(utterance: String) {
    val detection = IntentDetector().detectIntent(utterance, ConversationContext())
    assertTrue(
      "routing precondition changed: '$utterance' is now claimed deterministically as " +
        "${detection.intent::class.simpleName} (conf=${detection.confidence})",
      detection.intent is DvexIntent.Conversation && detection.confidence < 0.75f
    )
  }

  // 1. Normal conversation: text-only, tool selection never constructed ---------

  @Test
  fun recognizedConversationStaysOnTheTextOnlyPath() = runBlocking {
    val engine = ScriptedEngine(toolAnswer = AiModelTurn(text = null, toolCalls = listOf(openAppCall())))
    val res = brain(engine).process("how are you doing today")

    assertEquals("recognized small talk must never see a tool prompt", 0, engine.withToolsRequests.get())
    assertEquals("the normal conversation LLM call still happens", 1, engine.textRequests.get())
    assertTrue(res.intent is DvexIntent.Conversation)
  }

  // 2. Deterministic precedence: the model cannot hijack a known command ------

  @Test
  fun recognizedActionIsServedByTheDeterministicPathAndNeverOffersTools() = runBlocking {
    val engine = ScriptedEngine(toolAnswer = AiModelTurn(text = null, toolCalls = listOf(openAppCall("Camera"))))
    val res = brain(engine).process("open youtube")

    assertEquals("a recognized action must not be offered to the model at all", 0, engine.withToolsRequests.get())
    assertEquals("the existing deterministic executor owns it", "open_app", res.toolResult.toolName)
  }

  // 3. Genuinely unclaimed action-shaped request reaches the tool path ---------

  @Test
  fun unclaimedActionShapedRequestCanSelectAToolAndRunsThroughTheAgentEngine() = runBlocking {
    assertUnclaimed(unclaimedActionUtterance)
    val engine = ScriptedEngine(toolAnswer = AiModelTurn(text = null, toolCalls = listOf(openAppCall())))
    val res = brain(engine).process(unclaimedActionUtterance)

    assertEquals("exactly one tool-capable exchange", 1, engine.withToolsRequests.get())
    assertEquals(
      "the model's selection must be executed by the deterministic agent engine",
      "open_app", res.toolResult.toolName
    )
    assertEquals(
      "the single existing final response path must still phrase the result",
      1, engine.textRequests.get()
    )
  }

  // 4-7. Admission refusals: BLOCKED, honest, and NOTHING else from the turn ----

  @Test
  fun unknownToolIsRefusedWithTheCanonicalLine() = runBlocking {
    assertUnclaimed(unclaimedActionUtterance)
    val engine = ScriptedEngine(
      toolAnswer = AiModelTurn(text = null, toolCalls = listOf(DvexToolCall("not_a_real_tool", mapOf("x" to "y"))))
    )
    val res = brain(engine).process(unclaimedActionUtterance)

    assertEquals(1, engine.withToolsRequests.get())
    assertEquals(DvexToolStatus.UNSUPPORTED, res.toolResult.status)
    assertEquals(DvexToolProtocol.UNKNOWN_TOOL_REPLY, res.toolResult.spokenText)
    assertFalse("a refused call is never an execution", res.toolResult.isSuccessful)
  }

  @Test
  fun missingRequiredArgumentIsRefusedWithTheCanonicalLine() = runBlocking {
    val engine = ScriptedEngine(
      toolAnswer = AiModelTurn(text = null, toolCalls = listOf(DvexToolCall("open_app", emptyMap())))
    )
    val res = brain(engine).process(unclaimedActionUtterance)

    assertEquals(DvexToolProtocol.INCOMPLETE_CALL_REPLY, res.toolResult.spokenText)
    assertEquals("nothing may run when a required argument is missing", DvexToolStatus.UNSUPPORTED, res.toolResult.status)
  }

  @Test
  fun invalidArgumentTypeIsRefusedWithTheCanonicalLine() = runBlocking {
    // A schema that declares a non-string field, so type validation is reachable
    // through the real protocol rather than a hand-written bypass.
    val engine = ScriptedEngine(
      toolAnswer = AiModelTurn(text = null, toolCalls = listOf(DvexToolCall("open_app", mapOf("app_name" to "   "))))
    )
    val res = brain(engine).process(unclaimedActionUtterance)

    // A blank required value is dropped by admission, which is a refusal, not a guess.
    assertEquals(DvexToolProtocol.INCOMPLETE_CALL_REPLY, res.toolResult.spokenText)
  }

  @Test
  fun inventedArgumentFieldsAreDroppedAndTheDeclaredCallStillRuns() = runBlocking {
    val engine = ScriptedEngine(
      toolAnswer = AiModelTurn(
        text = null,
        toolCalls = listOf(
          DvexToolCall("open_app", mapOf("app_name" to "YouTube", "package_name" to "com.evil.app"))
        )
      )
    )
    val res = brain(engine).process(unclaimedActionUtterance)

    assertEquals("the declared field still resolves the call", "open_app", res.toolResult.toolName)
    assertTrue(
      "the invented field must never surface to the user",
      !res.spokenText.contains("package_name") && !res.spokenText.contains("com.evil")
    )
    assertTrue(
      "nor may it reach the LLM prompt",
      !engine.lastTextPrompt.get().contains("com.evil")
    )
  }

  // Refuse-first: a blocked call stops the ENTIRE turn (spec: never execute another
  // call from the same model turn).

  @Test
  fun aRefusedCallStopsEveryOtherCallInTheSameTurn() = runBlocking {
    val engine = ScriptedEngine(
      toolAnswer = AiModelTurn(
        text = null,
        toolCalls = listOf(
          DvexToolCall("delete_everything", emptyMap()),
          openAppCall("YouTube")
        )
      )
    )
    val res = brain(engine).process(unclaimedActionUtterance)

    assertEquals("the refusal is the turn's outcome", DvexToolStatus.UNSUPPORTED, res.toolResult.status)
    assertEquals(DvexToolProtocol.UNKNOWN_TOOL_REPLY, res.toolResult.spokenText)
    assertNotEquals(
      "the valid call from the SAME turn must not have executed",
      "open_app", res.toolResult.toolName
    )
  }

  // 8. HIGH-risk model selection stops at the existing confirmation gate -------

  @Test
  fun modelSelectedMessageStopsAtTheExistingConfirmationGate() = runBlocking {
    assertUnclaimed(unclaimedMessagingUtterance)
    val engine = ScriptedEngine(toolAnswer = AiModelTurn(text = null, toolCalls = listOf(sendMessageCall())))
    val b = brain(engine)

    val res = b.process(unclaimedMessagingUtterance)

    assertEquals(1, engine.withToolsRequests.get())
    assertEquals("the HIGH-risk action must be held", DvexToolStatus.CONFIRMATION_REQUIRED, res.toolResult.status)
    assertTrue("confirmation must be required", res.toolResult.requiresConfirmation)
    assertTrue("the pending confirmation state must be armed", b.hasPendingConfirmation())
    assertTrue("no SendMessage may have run yet", res.intent is DvexIntent.Conversation)
  }

  // 9. YES -> existing executeConfirmed path, exactly once, no re-gate ---------

  @Test
  fun confirmationYesRunsTheExistingConfirmedPathExactlyOnce() = runBlocking {
    val engine = ScriptedEngine(toolAnswer = AiModelTurn(text = null, toolCalls = listOf(sendMessageCall())))
    val b = brain(engine)

    b.process(unclaimedMessagingUtterance)
    val res = b.process("yes")

    assertTrue(
      "the confirmed execution must be the real SendMessage path",
      res.intent is DvexIntent.SendMessage
    )
    assertEquals("Dad", (res.intent as DvexIntent.SendMessage).recipient)
    assertEquals(
      "a confirmed action must not be re-held at the confirmation gate",
      false, res.toolResult.requiresConfirmation
    )
    assertFalse("pending state must be cleared", b.hasPendingConfirmation())
    assertEquals(
      "the confirmation must not re-enter the model tool path",
      1, engine.withToolsRequests.get()
    )
  }

  @Test
  fun aSecondYesCannotExecuteTheConfirmedActionAgain() = runBlocking {
    val engine = ScriptedEngine(toolAnswer = AiModelTurn(text = null, toolCalls = listOf(sendMessageCall())))
    val b = brain(engine)

    b.process(unclaimedMessagingUtterance)
    b.process("yes")
    val second = b.process("yes")

    assertTrue(
      "once confirmed and executed, the action must never re-arm",
      second.intent !is DvexIntent.SendMessage
    )
    assertEquals("still exactly one tool exchange in the whole flow", 1, engine.withToolsRequests.get())
  }

  // 10. NO -> cancellation, zero execution, all pending protocol state cleared --

  @Test
  fun confirmationNoCancelsWithZeroExecution() = runBlocking {
    val engine = ScriptedEngine(toolAnswer = AiModelTurn(text = null, toolCalls = listOf(sendMessageCall())))
    val b = brain(engine)

    b.process(unclaimedMessagingUtterance)
    val res = b.process("no")

    assertEquals("the existing cancellation line", "Cancelled.", res.spokenText)
    assertEquals("cancellation is the existing result path", "cancellation", res.toolResult.toolName)
    assertFalse("pending state must be cleared", b.hasPendingConfirmation())
    assertEquals("the model is not consulted again on cancel", 1, engine.withToolsRequests.get())

    // Nothing was armed: a later "yes" cannot resurrect the cancelled action.
    val later = b.process("yes")
    assertTrue("a cancelled action stays cancelled", later.intent !is DvexIntent.SendMessage)
    assertFalse(b.hasPendingConfirmation())
  }

  // 11. Bound: at most MAX_TOOL_CALLS_PER_TURN admissions, one action per turn --

  @Test
  fun toolCallsBeyondTheBoundAreNeverEvenAdmitted() = runBlocking {
    // Three blocked calls occupy the whole bound; the fourth call is the ONLY valid
    // one. If the bound did not exist, that fourth call would be admitted and run.
    val engine = ScriptedEngine(
      toolAnswer = AiModelTurn(
        text = null,
        toolCalls = listOf(
          DvexToolCall("no_such_tool_1", emptyMap()),
          DvexToolCall("no_such_tool_2", emptyMap()),
          DvexToolCall("no_such_tool_3", emptyMap()),
          openAppCall("YouTube")
        )
      )
    )
    val res = brain(engine).process(unclaimedActionUtterance)

    assertEquals("the turn is a refusal", DvexToolStatus.UNSUPPORTED, res.toolResult.status)
    assertNotEquals(
      "a call past the 3-call bound must never execute",
      "open_app", res.toolResult.toolName
    )
  }

  @Test
  fun onlyOneActionExecutesEvenWhenTheModelAsksForSeveral() = runBlocking {
    val engine = ScriptedEngine(
      toolAnswer = AiModelTurn(
        text = null,
        toolCalls = listOf(
          openAppCall("YouTube"),
          openAppCall("Maps"),
          openAppCall("Camera")
        )
      )
    )
    val res = brain(engine).process(unclaimedActionUtterance)

    assertEquals("one model exchange for the whole turn", 1, engine.withToolsRequests.get())
    assertEquals("exactly one action per turn", "open_app", res.toolResult.toolName)
    assertEquals(
      "the single existing final response path must still phrase the result",
      1, engine.textRequests.get()
    )
  }

  // 12. Garbled / low-ASR speech never drives tool selection -----------------

  @Test
  fun lowAsrConfidenceNeverInvokesToolSelection() = runBlocking {
    val engine = ScriptedEngine(toolAnswer = AiModelTurn(text = null, toolCalls = listOf(openAppCall())))
    val b = brain(engine)
    b.reportAsrConfidence(0.2f)

    val res = b.process("flurbnackle mumble morble")

    assertEquals("garbled words must not select tools", 0, engine.withToolsRequests.get())
    assertEquals("the existing conversation path still answers", 1, engine.textRequests.get())
    assertTrue(res.intent is DvexIntent.Conversation)
  }

  // 13. No internal protocol vocabulary in user-facing text ------------------

  @Test
  fun refusalsExposeNoProtocolVocabularyToTheUser() = runBlocking {
    val engine = ScriptedEngine(
      toolAnswer = AiModelTurn(text = null, toolCalls = listOf(DvexToolCall("mcp_invoke_schema", emptyMap())))
    )
    val res = brain(engine).process(unclaimedActionUtterance)

    val userFacing = "${res.toolResult.spokenText} ${res.spokenText} ${res.toolResult.message}".lowercase()
    for (banned in listOf("mcp", "functioncall", "function_call", "schema", "json", "registry", "protocol", "admission", "blocked")) {
      assertFalse("user-facing text must not contain '$banned'", userFacing.contains(banned))
    }
  }

  @Test
  fun theLlmIsToldTheHonestResultWithoutInternalVerbs() = runBlocking {
    val engine = ScriptedEngine(toolAnswer = AiModelTurn(text = null, toolCalls = listOf(DvexToolCall("no_such_tool", emptyMap()))))
    brain(engine).process(unclaimedActionUtterance)

    assertTrue("the refusal must still reach the phrasing layer", engine.textRequests.get() >= 1)
    assertTrue(
      "the LLM must be given D-VEX's canonical refusal line",
      engine.lastTextPrompt.get().contains(DvexToolProtocol.UNKNOWN_TOOL_REPLY)
    )
  }
}
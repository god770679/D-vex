package com.example.agent

import com.example.ai.GeminiToolAdapter
import com.example.brain.DvexToolResult
import com.example.brain.DvexToolStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PART 1 REGRESSION GUARD: the model/D-VEX tool boundary.
 *
 * The model may understand, select a tool and argue in text — nothing else. These tests pin
 * the whole boundary:
 *
 *  - a tool D-VEX exposes with valid arguments is ADMITTED (and only then can become an
 *    action); an unexposed tool, a missing argument or a mistyped value is REFUSED;
 *  - every refusal is an honest, non-success outcome (BLOCKED), never a silent default;
 *  - the action built from an admitted call keeps the descriptor's capability and risk, so
 *    the confirmation policy for a high-risk tool cannot be talked around;
 *  - the Gemini wire format is decoded into these model-independent types by the adapter
 *    and never leaks into the agent layer;
 *  - an unverified execution result stays UNVERIFIED — it is never promoted to success.
 *
 * Robolectric supplies the real `org.json` implementation used by the adapter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexToolProtocolTest {

  private val registry = DvexMcpRegistry.withDefaults()

  private fun call(name: String, vararg arguments: Pair<String, String>) =
    DvexToolCall(name, arguments.toMap())

  private fun admitted(admission: DvexToolAdmission): DvexToolAdmission.Accepted {
    assertTrue(
      "expected an accepted call, got ${(admission as? DvexToolAdmission.Refused)?.result}",
      admission is DvexToolAdmission.Accepted
    )
    return admission as DvexToolAdmission.Accepted
  }

  private fun refused(admission: DvexToolAdmission): DvexMcpResult {
    assertTrue(
      "expected a refused call, got $admission",
      admission is DvexToolAdmission.Refused
    )
    return (admission as DvexToolAdmission.Refused).result
  }

  // --- admission ------------------------------------------------------------

  @Test
  fun anExposedToolWithItsRequiredArgumentsIsAdmitted() {
    val admission = admitted(DvexToolProtocol.admit(call("open_app", "app_name" to "YouTube"), registry))

    assertEquals(AgentActionType.OPEN_APP, admission.tool.actionType)
    assertEquals(mapOf("app_name" to "YouTube"), admission.arguments)
  }

  @Test
  fun aToolDvexDoesNotExposeIsRefused() {
    val result = refused(DvexToolProtocol.admit(call("open_youtube", "app_name" to "YouTube"), registry))

    assertEquals(DvexMcpStatus.BLOCKED, result.status)
    assertFalse("a refusal is never a success", result.isVerifiedSuccess)
    assertEquals(VerificationOutcome.UNVERIFIED, result.verification)
    assertTrue("the refusal must name the tool internally: ${result.error}", result.error!!.contains("open_youtube"))
    assertFalse("the user-facing line must not leak internals", result.message.contains("open_youtube"))
  }

  @Test
  fun aBlankToolNameIsRefused() {
    val result = refused(DvexToolProtocol.admit(call("   "), registry))
    assertEquals(DvexMcpStatus.BLOCKED, result.status)
  }

  @Test
  fun aCallMissingARequiredArgumentIsRefused() {
    val result = refused(DvexToolProtocol.admit(call("open_app"), registry))

    assertEquals(DvexMcpStatus.BLOCKED, result.status)
    assertTrue("the missing field is named internally: ${result.error}", result.error!!.contains("app_name"))
    assertFalse(result.isVerifiedSuccess)
  }

  @Test
  fun aBlankRequiredArgumentIsTreatedAsMissing() {
    val result = refused(DvexToolProtocol.admit(call("open_app", "app_name" to "   "), registry))
    assertEquals(DvexMcpStatus.BLOCKED, result.status)
  }

  @Test
  fun onlyDeclaredFieldsSurviveAndInventedOnesAreIgnored() {
    val admission = admitted(
      DvexToolProtocol.admit(
        call("open_app", "app_name" to " YouTube ", "sudo" to "true", "url" to "https://example.com"),
        registry
      )
    )

    assertEquals("declared fields are trimmed, invented ones dropped", mapOf("app_name" to "YouTube"), admission.arguments)
  }

  @Test
  fun aMistypedValueForATypedFieldIsRefused() {
    val typed = DvexMcpTool(
      name = "set_alarm",
      description = "Set an alarm.",
      actionType = AgentActionType.CREATE_REMINDER,
      capability = DvexCapability.SETTINGS,
      risk = ActionRisk.MEDIUM,
      inputFields = listOf(
        DvexMcpInputField("hour", "Hour of the alarm.", McpFieldType.INTEGER, required = true),
        DvexMcpInputField("enabled", "Whether it repeats.", McpFieldType.BOOLEAN)
      )
    )

    val good = admitted(DvexToolProtocol.admit(call("set_alarm", "hour" to "7", "enabled" to "TRUE"), typed))
    assertEquals(mapOf("hour" to "7", "enabled" to "TRUE"), good.arguments)

    val badHour = refused(DvexToolProtocol.admit(call("set_alarm", "hour" to "seven"), typed))
    assertEquals(DvexMcpStatus.BLOCKED, badHour.status)
    assertTrue(badHour.error!!.contains("hour"))

    val badBoolean = refused(DvexToolProtocol.admit(call("set_alarm", "hour" to "7", "enabled" to "maybe"), typed))
    assertEquals(DvexMcpStatus.BLOCKED, badBoolean.status)
    assertTrue(badBoolean.error!!.contains("enabled"))
  }

  @Test
  fun anOptionalArgumentMayBeLeftOut() {
    val admission = admitted(DvexToolProtocol.admit(call("search", "query" to "weather in chennai"), registry))
    assertEquals(mapOf("query" to "weather in chennai"), admission.arguments)
  }

  // --- admitted call -> action ---------------------------------------------

  @Test
  fun anAdmittedCallMapsOntoTheExistingAction() {
    val action = DvexToolProtocol.actionFor(
      admitted(DvexToolProtocol.admit(call("open_app", "app_name" to "YouTube"), registry))
    )

    assertEquals(AgentActionType.OPEN_APP, action.type)
    assertEquals(DvexCapability.APP_LAUNCH, action.capability)
    assertEquals("YouTube", action.target)
    assertEquals(ActionRisk.LOW, action.risk)
  }

  @Test
  fun argumentsLandOnTheFieldsTheExistingHandlersRead() {
    val search = DvexToolProtocol.actionFor(
      admitted(DvexToolProtocol.admit(call("search", "query" to "lofi", "app_name" to "YouTube"), registry))
    )
    assertEquals("YouTube", search.target)
    assertEquals("lofi", search.query)

    val message = DvexToolProtocol.actionFor(
      admitted(DvexToolProtocol.admit(call("send_message", "recipient" to "Arun", "message" to "on my way"), registry))
    )
    assertEquals("Arun", message.target)
    assertEquals("on my way", message.query)

    // The scroll handler reads an uppercase direction from `query`.
    val scroll = DvexToolProtocol.actionFor(
      admitted(DvexToolProtocol.admit(call("scroll", "direction" to "up"), registry))
    )
    assertEquals("UP", scroll.query)
  }

  @Test
  fun theSafetyPolicyStillAppliesToAModelSelectedTool() {
    val action = DvexToolProtocol.actionFor(
      admitted(DvexToolProtocol.admit(call("send_message", "recipient" to "Arun", "message" to "hi"), registry))
    )

    // Risk comes from the descriptor, never from the model, so HIGH-risk work still waits
    // for the user's confirmation before anything is executed.
    assertEquals(ActionRisk.HIGH, action.risk)
    assertTrue("a model-selected message must still require confirmation", DvexActionPolicy.requiresConfirmation(action))
  }

  @Test
  fun anAdmittedCallBecomesASingleStepPlanWithSpeakableWording() {
    val plan = DvexToolProtocol.planFor(
      admitted(DvexToolProtocol.admit(call("send_message", "recipient" to "Arun", "message" to "hi"), registry))
    )

    assertEquals(1, plan.stepCount)
    assertEquals("Shall I message Arun?", "Shall I ${plan.summary}?")
    assertEquals(AgentActionType.SEND_MESSAGE, plan.steps.single().type)
  }

  // --- model response -> admissions (Gemini dialect) ------------------------

  @Test
  fun theGeminiAdapterDecodesASelectedToolIntoAModelIndependentCall() {
    val response = """
      {"candidates":[{"content":{"parts":[
        {"functionCall":{"name":"open_app","args":{"app_name":"YouTube"}}}
      ]}}]}
    """.trimIndent()

    val admissions = DvexToolProtocol.exchange(GeminiToolAdapter, response, registry)

    assertEquals(1, admissions.size)
    val admission = admitted(admissions.single())
    assertEquals(AgentActionType.OPEN_APP, admission.tool.actionType)
    assertEquals("YouTube", admission.arguments["app_name"])
  }

  @Test
  fun numbersFromTheModelReachTheAgentLayerAsStrings() {
    val response = """
      {"candidates":[{"content":{"parts":[
        {"functionCall":{"name":"set_alarm","args":{"time":"07:30","label":45}}}
      ]}}]}
    """.trimIndent()

    val admission = admitted(DvexToolProtocol.exchange(GeminiToolAdapter, response, registry).single())
    assertEquals("07:30", admission.arguments["time"])
    assertEquals("45", admission.arguments["label"])
  }

  @Test
  fun aTextOnlyTurnProducesNoAdmissions() {
    val response = """{"candidates":[{"content":{"parts":[{"text":"Sure, opening YouTube."}]}}]}"""
    assertTrue(DvexToolProtocol.exchange(GeminiToolAdapter, response, registry).isEmpty())
    assertTrue(DvexToolProtocol.exchange(GeminiToolAdapter, "not json", registry).isEmpty())
  }

  @Test
  fun aHallucinatedToolInAModelResponseIsRefusedNotGuessedAt() {
    val response = """
      {"candidates":[{"content":{"parts":[
        {"functionCall":{"name":"send_whatsapp","args":{"to":"Arun"}}}
      ]}}]}
    """.trimIndent()

    val result = refused(DvexToolProtocol.exchange(GeminiToolAdapter, response, registry).single())
    assertEquals(DvexMcpStatus.BLOCKED, result.status)
    assertTrue(result.error!!.contains("send_whatsapp"))
  }

  @Test
  fun theDeclarationsSentToTheModelDescribeExactlyTheExposedTools() {
    val declarations = JSONObject(GeminiToolAdapter.encodeDeclarations(registry.tools()))
    val names = declarations.getJSONArray("functionDeclarations")
    val declared = (0 until names.length()).map { names.getJSONObject(it).getString("name") }.toSet()

    assertEquals(registry.names(), declared)
    assertEquals(GeminiToolAdapter.DIALECT, GeminiToolAdapter.dialect)
    declared.forEach { name ->
      assertNotNull("every declared tool must be executable", registry.tool(name))
      assertTrue("illegal tool name $name", registry.tool(name)!!.hasValidName)
    }
  }

  // --- execution result -> canonical outcome --------------------------------

  @Test
  fun anUnconfirmedExecutionIsNeverReportedAsSuccess() {
    val dispatched = DvexMcpResult.fromToolResult(
      DvexToolResult(
        status = DvexToolStatus.UNVERIFIED,
        toolName = "open_app",
        message = "Opening YouTube.",
        spokenText = "Opening YouTube."
      )
    )

    assertEquals(DvexMcpStatus.UNVERIFIED, dispatched.status)
    assertFalse(dispatched.isVerifiedSuccess)
    assertEquals(VerificationOutcome.UNVERIFIED, dispatched.verification)
  }

  @Test
  fun aRefusalCarriesAnHonestSpokenLineAndNoSuccessState() {
    val result = refused(DvexToolProtocol.admit(call("launch_url"), registry))

    assertEquals(DvexMcpStatus.BLOCKED, result.status)
    assertFalse(result.isVerifiedSuccess)
    assertEquals(DvexToolProtocol.INCOMPLETE_CALL_REPLY, result.message)
    assertTrue("the missing field is named internally: ${result.error}", result.error!!.contains("url"))
  }
}

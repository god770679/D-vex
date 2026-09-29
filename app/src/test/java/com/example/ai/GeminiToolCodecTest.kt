package com.example.ai

import com.example.agent.AgentActionType
import com.example.agent.DvexMcpInputField
import com.example.agent.DvexMcpRegistry
import com.example.agent.DvexMcpTool
import com.example.agent.DvexCapability
import com.example.agent.McpFieldType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * INCREMENT 1 — Gemini tool codec.
 *
 * Covers the two conversion directions the agent/MCP layer needs: D-VEX descriptor
 * -> Gemini `functionDeclarations`, and live response shape -> parsed function call.
 *
 * SCOPE NOTE: these are shape/parse tests for the codec. They are NOT evidence that
 * live function calling works — receiving a real `functionCall` from
 * `gemini-3.8-flash` is still PENDING (free-tier daily quota exhausted), and no test
 * here substitutes for that verification.
 *
 * Robolectric supplies the real `org.json` implementation (a plain JVM unit test
 * would only see the Android stub jar).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiToolCodecTest {

  private val openApp = DvexMcpTool(
    name = "open_app",
    description = "Open an installed app.",
    actionType = AgentActionType.OPEN_APP,
    capability = DvexCapability.APP_LAUNCH,
    inputFields = listOf(
      DvexMcpInputField("app_name", "Name of the app.", McpFieldType.STRING, required = true)
    )
  )

  // --- descriptor -> declaration -------------------------------------------

  @Test
  fun declarationUsesTheUppercaseOpenApiDialectTheRestEndpointAccepts() {
    val declaration = GeminiToolCodec.toFunctionDeclaration(openApp)
    assertEquals("open_app", declaration.getString("name"))
    assertEquals("Open an installed app.", declaration.getString("description"))

    val parameters = declaration.getJSONObject("parameters")
    assertEquals("OBJECT", parameters.getString("type"))
    val appName = parameters.getJSONObject("properties").getJSONObject("app_name")
    assertEquals("STRING", appName.getString("type"))
    assertEquals("Name of the app.", appName.getString("description"))
    val required = parameters.getJSONArray("required")
    assertEquals(1, required.length())
    assertEquals("app_name", required.getString(0))
  }

  @Test
  fun toolWithoutInputsStillDeclaresAnEmptyObjectSchema() {
    val home = DvexMcpTool(
      name = "home",
      description = "Go home.",
      actionType = AgentActionType.NAVIGATE_HOME,
      capability = DvexCapability.NAVIGATION
    )
    val parameters = GeminiToolCodec.toParametersSchema(home)
    assertEquals("OBJECT", parameters.getString("type"))
    assertEquals(0, parameters.getJSONObject("properties").length())
    assertFalse(parameters.has("required"))
  }

  @Test
  fun toolObjectWrapsEveryDeclaration() {
    val tools = DvexMcpRegistry.withDefaults().tools()
    val toolsObject = GeminiToolCodec.toToolsObject(tools)
    val declarations = toolsObject.getJSONArray("functionDeclarations")
    assertEquals(tools.size, declarations.length())
    assertEquals(tools.map { it.name }.toSet(), GeminiToolCodec.declarationNames(tools))
  }

  @Test
  fun schemaSerializationEscapesQuotesAndNewlinesSafely() {
    val tricky = DvexMcpTool(
      name = "tricky_tool",
      description = "Says \"hello\" and\nbreaks lines.",
      actionType = AgentActionType.TYPE_TEXT,
      capability = DvexCapability.UI_INTERACTION,
      inputFields = listOf(
        DvexMcpInputField("text", "Text with a \"quote\" and a \\ backslash.", required = true)
      )
    )
    // Round-trips through JSON without corrupting the payload.
    val json = GeminiToolCodec.toToolsObject(listOf(tricky)).toString()
    val parsed = JSONObject(json)
    val declaration = parsed.getJSONArray("functionDeclarations").getJSONObject(0)
    assertEquals(tricky.description, declaration.getString("description"))
    assertEquals(
      "Text with a \"quote\" and a \\ backslash.",
      declaration.getJSONObject("parameters")
        .getJSONObject("properties").getJSONObject("text").getString("description")
    )
  }

  // --- response -> function call -------------------------------------------

  @Test
  fun parsesFunctionCallNameAndArgumentsFromTheResponseShape() {
    val response = """
      {
        "candidates": [
          {
            "content": {
              "parts": [
                { "functionCall": { "name": "open_app", "args": { "app_name": "YouTube" } } }
              ]
            }
          }
        ]
      }
    """.trimIndent()

    val call = GeminiToolCodec.parseFunctionCall(response)
    assertNotNull(call)
    assertEquals("open_app", call!!.name)
    assertEquals("YouTube", GeminiToolCodec.stringArgument(call, "app_name"))
  }

  @Test
  fun parsesSnakeCaseFunctionCallVariant() {
    val response =
      """{"candidates":[{"content":{"parts":[{"function_call":{"name":"set_alarm","arguments":{"time":"07:30","label":"gym"}}}]}}]}"""
    val call = GeminiToolCodec.parseFunctionCall(response)
    assertNotNull(call)
    assertEquals("set_alarm", call!!.name)
    assertEquals("07:30", GeminiToolCodec.stringArgument(call, "time"))
    assertEquals("gym", GeminiToolCodec.stringArgument(call, "label"))
  }

  @Test
  fun numericArgumentsAreReadAsIntegers() {
    val call = GeminiFunctionCall("set_alarm", JSONObject().put("hour", 7))
    assertEquals(7, GeminiToolCodec.intArgument(call, "hour"))
    assertNull(GeminiToolCodec.intArgument(call, "minute"))
  }

  @Test
  fun textOnlyResponsesYieldNoFunctionCall() {
    val response = """{"candidates":[{"content":{"parts":[{"text":"Sure, opening YouTube."}]}}]}"""
    assertNull(GeminiToolCodec.parseFunctionCall(response))
    assertTrue(GeminiToolCodec.parseFunctionCalls(response).isEmpty())
  }

  @Test
  fun malformedOrEmptyResponsesYieldNoFunctionCall() {
    assertNull(GeminiToolCodec.parseFunctionCall(""))
    assertNull(GeminiToolCodec.parseFunctionCall("not json at all"))
    assertNull(GeminiToolCodec.parseFunctionCall("""{"candidates":[]}"""))
    // A call with no name is not usable and must not be returned as a tool request.
    assertNull(
      GeminiToolCodec.parseFunctionCall(
        """{"candidates":[{"content":{"parts":[{"functionCall":{"args":{"x":1}}}]}}]}"""
      )
    )
  }

  @Test
  fun multipleCallsAreReturnedInOrder() {
    val response = """
      {"candidates":[{"content":{"parts":[
        {"functionCall":{"name":"open_app","args":{"app_name":"WhatsApp"}}},
        {"functionCall":{"name":"send_message","args":{"recipient":"Arun"}}}
      ]}}]}
    """.trimIndent()

    val calls = GeminiToolCodec.parseFunctionCalls(response)
    assertEquals(listOf("open_app", "send_message"), calls.map { it.name })
  }

  @Test
  fun parsedCallResolvesToARealDvexActionWithoutExecutingAnything() {
    val registry = DvexMcpRegistry.withDefaults()
    val response =
      """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"open_app","args":{"app_name":"YouTube"}}}]}}]}"""

    val call = GeminiToolCodec.parseFunctionCall(response)!!
    val tool = registry.tool(call.name)

    assertNotNull("a parsed call must map onto an existing action", tool)
    assertEquals(AgentActionType.OPEN_APP, tool!!.actionType)
    // Selection only. Nothing here performed the action.
    assertNull(registry.tool("open_youtube"))
  }

  @Test
  fun stringArgumentTreatsBlankAndMissingValuesAsAbsent() {
    val call = GeminiFunctionCall("open_app", JSONObject().put("app_name", "  "))
    assertNull(GeminiToolCodec.stringArgument(call, "app_name"))
    assertNull(GeminiToolCodec.stringArgument(call, "missing"))
  }
}

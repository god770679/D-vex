package com.example.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INCREMENT 1 — MCP tool registry.
 *
 * The registry must expose exactly the capabilities that really exist, must not
 * hand-maintain a second tool list, and must keep the tool name <-> action mapping
 * unambiguous (that mapping is what a function call is routed back through).
 */
class DvexMcpRegistryTest {

  @Test
  fun registryIsDerivedFromTheExistingActionRegistry() {
    val actions = DvexActionRegistry.withDefaults()
    val tools = DvexMcpRegistry.from(actions)

    // One exposed tool per registered handler — no more, no fewer. A mismatch means
    // either a duplicate/fallback tool name or a type without a descriptor.
    assertEquals(actions.size, tools.size)
    assertEquals(DvexDefaultActions.all().size, tools.size)

    actions.registeredTypes().forEach { type ->
      assertNotNull("$type must be exposed as a tool", tools.toolFor(type))
    }
  }

  @Test
  fun unregisteredActionsAreNeverExposed() {
    // Empty registry => no tools. Capabilities are advertised only when a handler
    // actually exists, so the AI layer can never request something D-VEX can't do.
    assertEquals(0, DvexMcpRegistry.from(DvexActionRegistry()).size)
    assertNull(DvexMcpRegistry.from(DvexActionRegistry()).tool("open_app"))
  }

  @Test
  fun addingAHandlerAddsAToolWithoutEditingTheRegistry() {
    val registry = DvexActionRegistry()
    registry.registerAction(object : AgentActionHandler {
      override val actionType = AgentActionType.OPEN_APP
      override suspend fun execute(action: AgentAction, env: DvexAgentEnvironment) =
        AgentActionResult(AgentActionStatus.SUCCESS, "opened by a test handler")
    })

    val tools = DvexMcpRegistry.from(registry)
    assertEquals(1, tools.size)
    assertEquals(AgentActionType.OPEN_APP, tools.tools().single().actionType)
  }

  @Test
  fun toolNamesAreUniqueValidAndMapToTheExistingAction() {
    val tools = DvexMcpRegistry.withDefaults()
    val all = tools.tools()

    assertEquals("names must be unique", all.size, all.map { it.name }.toSet().size)
    all.forEach { tool ->
      assertTrue("illegal tool name ${tool.name}", tool.hasValidName)
    }

    // The exact mapping a function call is routed through (Increment 0 check E).
    assertEquals(AgentActionType.OPEN_APP, tools.tool("open_app")?.actionType)
    assertEquals(AgentActionType.NAVIGATE_HOME, tools.tool("home")?.actionType)
    assertEquals(AgentActionType.SEND_MESSAGE, tools.tool("send_message")?.actionType)
    assertTrue(tools.declares("open_app"))
    assertFalse(tools.declares("open_youtube"))
  }

  @Test
  fun descriptorsCarryCapabilityRiskAndPermissionFromTheExistingLayers() {
    val tools = DvexMcpRegistry.withDefaults()

    val openApp = tools.tool("open_app")!!
    assertEquals(DvexCapability.APP_LAUNCH, openApp.capability)
    assertEquals(ActionRisk.LOW, openApp.risk)

    // High-risk messaging stays high-risk in the descriptor: tool selection never
    // bypasses the safety policy.
    val send = tools.tool("send_message")!!
    assertEquals(ActionRisk.HIGH, send.risk)
    assertEquals(DvexCapability.MESSAGING, send.capability)

    // A permission-gated capability surfaces its permission to the tool layer.
    assertEquals("camera", tools.tool("camera")!!.requiredPermission)
    assertNull(tools.tool("open_app")!!.requiredPermission)
  }

  @Test
  fun openAppDeclaresItsInputSchema() {
    val openApp = DvexMcpRegistry.withDefaults().tool("open_app")!!
    val appName = openApp.inputFields.single()
    assertEquals("app_name", appName.name)
    assertEquals(McpFieldType.STRING, appName.type)
    assertTrue(appName.required)
    assertEquals(listOf("app_name"), openApp.requiredFields.map { it.name })
  }

  @Test
  fun searchDeclaresARequiredQueryAndAnOptionalApp() {
    val search = DvexMcpRegistry.withDefaults().tool("search")!!
    assertEquals(listOf("query"), search.requiredFields.map { it.name })
    assertEquals(listOf("query", "app_name"), search.inputFields.map { it.name })
  }

  @Test
  fun everyDescriptorHasADescriptionAndNoInternalLabels() {
    DvexMcpRegistry.withDefaults().tools().forEach { tool ->
      assertTrue("${tool.name} needs a description", tool.description.isNotBlank())
      val lower = tool.description.lowercase()
      listOf("sir", "intent:", "tool_result", "json", "debug").forEach { banned ->
        assertFalse("${tool.name} description leaks \"$banned\"", lower.contains(banned))
      }
      tool.inputFields.forEach { field ->
        assertTrue("${tool.name}.${field.name} needs a description", field.description.isNotBlank())
      }
    }
  }
}

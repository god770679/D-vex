package com.example.agent

/**
 * D-VEX MCP TOOL DESCRIPTOR
 *
 * A single, canonical description of one capability D-VEX can perform, shaped the
 * way a tool-calling model (MCP client / Gemini function calling) needs it:
 * stable name, human-readable description, input schema, capability, risk and the
 * permission the capability requires.
 *
 * IMPORTANT: this is a DESCRIPTOR only. It never carries an implementation and it
 * never executes anything — [actionType] references the existing
 * [AgentActionType] / [DvexActionRegistry] / [DvexDefaultActions] system, which
 * remains the single source of truth for HOW an action runs. Gemini may later
 * *select* a tool by name; the deterministic D-VEX agent still performs the
 * permission check, safety policy, execution and verification.
 *
 * The descriptor is deliberately JSON-library-free: it holds plain data, and the
 * serialization dialect (Gemini `parameters` vs. plain JSON Schema) belongs to the
 * codec in the AI layer (see `ai/GeminiToolCodec`).
 */
enum class McpFieldType(val jsonSchemaName: String) {
  STRING("string"),
  INTEGER("integer"),
  NUMBER("number"),
  BOOLEAN("boolean")
}

/** One input field of a tool. [required] fields form the schema's `required` list. */
data class DvexMcpInputField(
  val name: String,
  val description: String,
  val type: McpFieldType = McpFieldType.STRING,
  val required: Boolean = false
)

/**
 * One D-VEX capability, described for the AI tool layer.
 *
 * @param name stable, model-safe identifier (lowercase, underscores) — unique
 *   across the registry, which is what makes function-call round-tripping
 *   unambiguous.
 * @param description what the capability does, in plain language, for the model.
 * @param actionType the EXISTING action this tool maps onto.
 * @param capability capability declaration used by the deterministic layer.
 * @param risk safety classification — HIGH never runs without confirmation.
 * @param requiredPermission permission the capability needs, if any (defaults to
 *   the capability's own declared permission).
 * @param inputFields the tool's input schema.
 */
data class DvexMcpTool(
  val name: String,
  val description: String,
  val actionType: AgentActionType,
  val capability: DvexCapability,
  val risk: ActionRisk = ActionRisk.LOW,
  val requiredPermission: String? = capability.requiredPermission,
  val inputFields: List<DvexMcpInputField> = emptyList()
) {

  /** Schema's `required` list, in declaration order. */
  val requiredFields: List<DvexMcpInputField> get() = inputFields.filter { it.required }

  /** True when [name] is a legal, model-safe tool identifier. */
  val hasValidName: Boolean get() = NAME_PATTERN.matches(name)

  companion object {
    /** Gemini function names must start with a letter and stay identifier-like. */
    val NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")
  }
}

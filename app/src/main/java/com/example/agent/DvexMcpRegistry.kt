package com.example.agent

/**
 * D-VEX MCP REGISTRY
 *
 * The single canonical list of tools D-VEX can actually perform, exposed to the AI
 * (tool-calling) layer.
 *
 * It is DERIVED, never hand-maintained: [from] walks the existing
 * [DvexActionRegistry] and describes each registered action with a
 * [DvexMcpTool]. If an action type has no registered handler, no tool is exposed —
 * so D-VEX can never advertise a capability it cannot execute. Adding a capability
 * still means "add one handler to [DvexDefaultActions]", not "edit a tool list".
 *
 * Only descriptions/metadata live here; no handler, execution path or duplicate
 * tool implementation exists in this class.
 */
class DvexMcpRegistry private constructor(
  private val toolsByName: Map<String, DvexMcpTool>
) {

  val size: Int get() = toolsByName.size

  /** Every exposed tool, in registration order. */
  fun tools(): List<DvexMcpTool> = toolsByName.values.toList()

  /** Tool by its stable model-facing name, or null when D-VEX does not expose it. */
  fun tool(name: String): DvexMcpTool? = toolsByName[name]

  /** Descriptor for an existing action type, or null when it is not exposed. */
  fun toolFor(type: AgentActionType): DvexMcpTool? =
    toolsByName.values.firstOrNull { it.actionType == type }

  /** All tool names — what a model may legally ask for. */
  fun names(): Set<String> = toolsByName.keys

  /** True when a model-provided function name maps onto a real, executable tool. */
  fun declares(name: String): Boolean = toolsByName.containsKey(name)

  companion object {

    /**
     * Tool names for action types with no observable verification signal are marked
     * [VerificationOutcome.DISPATCH_CONFIRMED] by their handlers, not here — this
     * constant only guards against exposing an unnamed fallback tool.
     */
    private const val UNNAMED_FALLBACK = "device_control"

    /** Registry for the default (full) D-VEX capability set. */
    fun withDefaults(): DvexMcpRegistry = from(DvexActionRegistry.withDefaults())

    /**
     * Builds the tool registry from the actions that are actually registered.
     * Types without a handler are skipped, so availability can never be faked.
     */
    fun from(registry: DvexActionRegistry): DvexMcpRegistry {
      val byName = LinkedHashMap<String, DvexMcpTool>()
      registry.registeredTypes().forEach { type ->
        val descriptor = descriptorFor(type)
        if (descriptor.name == UNNAMED_FALLBACK) return@forEach
        // A duplicate name would make function-call routing ambiguous; the first
        // registration wins and DvexMcpRegistryTest pins that names are unique.
        if (!byName.containsKey(descriptor.name)) byName[descriptor.name] = descriptor
      }
      return DvexMcpRegistry(byName)
    }

    /**
     * Descriptor for one action type. Capability, risk and inputs mirror exactly
     * what the planner/handlers already enforce — nothing new is granted here.
     *
     * Exhaustive by design: a new [AgentActionType] must decide its tool descriptor
     * (compile error until it does) instead of silently becoming invisible.
     */
    fun descriptorFor(type: AgentActionType): DvexMcpTool = when (type) {
      AgentActionType.OPEN_APP -> tool(
        type,
        "Open an installed application on the phone by its name.",
        DvexCapability.APP_LAUNCH,
        inputFields = listOf(required("app_name", "Name of the app to open, e.g. YouTube."))
      )

      AgentActionType.SEARCH_IN_APP -> tool(
        type,
        "Search inside an application (for example YouTube or Maps) using its own search.",
        DvexCapability.IN_APP_SEARCH,
        inputFields = listOf(
          required("query", "What to search for."),
          optional("app_name", "App to search in. Defaults to the app already in use.")
        )
      )

      AgentActionType.LAUNCH_URL -> tool(
        type,
        "Open a web address in the phone's browser.",
        DvexCapability.LAUNCH_URL,
        inputFields = listOf(required("url", "The http(s) address to open."))
      )

      AgentActionType.NAVIGATE_HOME -> tool(
        type,
        "Go to the device home screen.",
        DvexCapability.NAVIGATION
      )

      AgentActionType.NAVIGATE_BACK -> tool(
        type,
        "Go back one screen on the device.",
        DvexCapability.NAVIGATION
      )

      AgentActionType.OPEN_RECENT_APPS -> tool(
        type,
        "Open the recent applications screen.",
        DvexCapability.NAVIGATION
      )

      AgentActionType.OPEN_CAMERA -> tool(
        type,
        "Open the camera app.",
        DvexCapability.CAMERA
      )

      AgentActionType.OPEN_SETTINGS -> tool(
        type,
        "Open the device settings app.",
        DvexCapability.SETTINGS
      )

      AgentActionType.GET_WEATHER -> tool(
        type,
        "Get the current weather for a place.",
        DvexCapability.WEATHER,
        inputFields = listOf(optional("place", "City or area. Defaults to the user's location."))
      )

      AgentActionType.SEND_MESSAGE -> tool(
        type,
        "Prepare a message to a contact. Requires the user's confirmation before it is sent.",
        DvexCapability.MESSAGING,
        risk = ActionRisk.HIGH,
        inputFields = listOf(
          required("recipient", "Contact name to message."),
          required("message", "Text of the message.")
        )
      )

      AgentActionType.CREATE_REMINDER -> tool(
        type,
        "Set a system alarm / reminder for a specific time.",
        DvexCapability.SETTINGS,
        risk = ActionRisk.MEDIUM,
        inputFields = listOf(
          required("time", "Time in 24-hour HH:mm format."),
          optional("label", "What the reminder is for.")
        )
      )

      AgentActionType.OPEN_NOTIFICATIONS -> tool(
        type,
        "Open the notification shade.",
        DvexCapability.NAVIGATION
      )

      AgentActionType.OPEN_WIFI_SETTINGS -> tool(
        type,
        "Open the Wi-Fi / internet settings screen.",
        DvexCapability.SETTINGS
      )

      AgentActionType.OPEN_BLUETOOTH_SETTINGS -> tool(
        type,
        "Open the Bluetooth settings screen.",
        DvexCapability.SETTINGS
      )

      AgentActionType.OPEN_DISPLAY_SETTINGS -> tool(
        type,
        "Open the display settings screen.",
        DvexCapability.SETTINGS
      )

      AgentActionType.OPEN_DATE_TIME_SETTINGS -> tool(
        type,
        "Open the date and time settings screen.",
        DvexCapability.SETTINGS
      )

      AgentActionType.OPEN_LOCATION_SETTINGS -> tool(
        type,
        "Open the location settings screen.",
        DvexCapability.SETTINGS
      )

      AgentActionType.OPEN_NOTIFICATION_SETTINGS -> tool(
        type,
        "Open the notification settings screen.",
        DvexCapability.SETTINGS
      )

      AgentActionType.OPEN_ACCESSIBILITY_SETTINGS -> tool(
        type,
        "Open the accessibility settings screen.",
        DvexCapability.SETTINGS
      )

      AgentActionType.SCROLL -> tool(
        type,
        "Scroll the current screen up or down.",
        DvexCapability.UI_INTERACTION,
        inputFields = listOf(optional("direction", "Either \"up\" or \"down\". Defaults to down."))
      )

      AgentActionType.TAP_ELEMENT -> tool(
        type,
        "Tap a visible control on the current screen, found by its text.",
        DvexCapability.UI_INTERACTION,
        inputFields = listOf(required("label", "Visible text of the control to tap."))
      )

      AgentActionType.TYPE_TEXT -> tool(
        type,
        "Type text into a text field on the current screen.",
        DvexCapability.UI_INTERACTION,
        inputFields = listOf(
          required("text", "The text to type."),
          optional("field", "Text shown near the target field.")
        )
      )

      AgentActionType.CLEAR_TEXT -> tool(
        type,
        "Empty a text field on the current screen.",
        DvexCapability.UI_INTERACTION,
        inputFields = listOf(optional("field", "Text shown near the target field."))
      )

      AgentActionType.SUBMIT_TEXT -> tool(
        type,
        "Activate the search / submit control on the current screen.",
        DvexCapability.UI_INTERACTION
      )

      AgentActionType.MEDIA_PLAY -> tool(
        type,
        "Start or resume media playback.",
        DvexCapability.MEDIA
      )

      AgentActionType.MEDIA_PAUSE -> tool(
        type,
        "Pause media playback.",
        DvexCapability.MEDIA
      )

      AgentActionType.MEDIA_NEXT -> tool(
        type,
        "Skip to the next track.",
        DvexCapability.MEDIA
      )

      AgentActionType.MEDIA_PREVIOUS -> tool(
        type,
        "Go back to the previous track.",
        DvexCapability.MEDIA
      )

      AgentActionType.VOLUME_UP -> tool(
        type,
        "Turn the media volume up one step.",
        DvexCapability.MEDIA
      )

      AgentActionType.VOLUME_DOWN -> tool(
        type,
        "Turn the media volume down one step.",
        DvexCapability.MEDIA
      )
    }

    // --- helpers -------------------------------------------------------------

    private fun tool(
      type: AgentActionType,
      description: String,
      capability: DvexCapability,
      risk: ActionRisk = ActionRisk.LOW,
      inputFields: List<DvexMcpInputField> = emptyList()
    ): DvexMcpTool = DvexMcpTool(
      // The tool name comes from the existing engine mapping — one source of truth
      // for the action <-> tool-name relationship.
      name = toolNameFor(type),
      description = description,
      actionType = type,
      capability = capability,
      risk = risk,
      inputFields = inputFields
    )

    private fun required(name: String, description: String): DvexMcpInputField =
      DvexMcpInputField(name = name, description = description, required = true)

    private fun optional(name: String, description: String): DvexMcpInputField =
      DvexMcpInputField(name = name, description = description, required = false)
  }
}

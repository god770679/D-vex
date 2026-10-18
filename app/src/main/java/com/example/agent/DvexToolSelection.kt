package com.example.agent

import java.util.Locale

/**
 * D-VEX HYBRID DYNAMIC + MCP TOOL INJECTION — the shared dynamic tool-selection layer.
 *
 * WHY THIS EXISTS: both model transports used to be shown the ENTIRE canonical
 * registry on every request (REST: `DvexSmartBrain.runModelToolTurn` ->
 * `generateWithTools(prompt, mcpRegistry.tools())`; Live: `AssistantRepository`
 * -> `setupMessage(tools = DvexMcpRegistry.withDefaults().tools())`). This layer
 * narrows what is DECLARED to a model to the tools relevant to the current goal
 * and actually available on this device, while admission
 * ([DvexToolProtocol.admit] with a selection) enforces the SAME selection when a
 * call comes back — so a model can neither see nor successfully invoke a tool
 * outside the current selection.
 *
 * INVARIANTS (all enforced here and at admission time):
 *  - The canonical registry ([DvexMcpRegistry]) remains the ONLY source of truth:
 *    a selection can only narrow it, never add to it or re-describe a tool.
 *  - Only AVAILABLE capabilities are declared (permission probes run at selection
 *    time), and [DvexAgentEngine] still re-probes permission, capability and risk
 *    at execution time — selection is defense-in-depth, never a replacement.
 *  - Plan preservation: any [DvexTaskPlan] supplied by a caller has every one of
 *    its step types kept in the selection, so a goal-scoped narrowing can never
 *    strip tools a (multi-step) plan still needs.
 *  - No relevance signal (blank or unrecognised goal, no plan) => FULL_SET: the
 *    selector refuses to hide capabilities it cannot reason about, which keeps
 *    behaviour identical to the previous full-declaration path in that case.
 *  - Selections are REFRESHED per request where the transport protocol allows it:
 *    REST re-declares `tools` on every `generateContent` request (a new goal =>
 *    a new selection each turn), while the Gemini Live wire declares tools ONCE
 *    in the setup message and cannot update them mid-session (verified against
 *    Google's Live API docs/forum, 2026-10: "You cannot change system
 *    instructions or tool definitions mid-session over an active connection"),
 *    so a Live session holds ONE selection until a new session starts.
 *
 * This layer EXECUTES nothing, PERMISSIONS nothing and CONFIRMS nothing — it
 * only decides what may be declared. Unknown or unselected calls are refused by
 * [DvexToolProtocol], and every admitted call still flows through the one
 * central pipeline: registry admission -> capability/permission check ->
 * [DvexActionPolicy] confirmation gate -> handler -> verification.
 */
enum class ToolSelectionScope {
  /**
   * At least one relevance bucket matched the goal (or a plan was supplied):
   * only the always-on core set + the matched bucket's chain + plan types are
   * declared.
   */
  GOAL_SCOPED,

  /**
   * No relevance signal (blank goal, unrecognised goal, no plan): every
   * registered tool is declared — narrowed only by live availability.
   */
  FULL_SET
}

/**
 * One immutable snapshot of "what the model may see and ask for right now".
 *
 * [allows] is the enforcement predicate used by [DvexToolProtocol.admit]: a call
 * for a tool that is not in this selection is refused even when the tool exists
 * in the registry (e.g. the model invented a call it was never shown).
 */
class DvexToolSelection private constructor(
  private val registry: DvexMcpRegistry,
  private val allowedByName: Map<String, DvexMcpTool>,
  val goal: String?,
  val scope: ToolSelectionScope,
  /**
   * Capabilities whose tools were dropped because the permission is missing on
   * this device right now (reported honestly in admission errors/logs; the
   * engine re-validates the same permission again at execution time).
   */
  val unavailableCapabilities: Set<DvexCapability>
) {

  val size: Int get() = allowedByName.size

  /** The declared tool descriptors, in canonical registry order. */
  fun tools(): List<DvexMcpTool> = allowedByName.values.toList()

  /** Model-facing names in this selection. */
  fun names(): Set<String> = allowedByName.keys

  /** True when [name] was declared as part of this selection. */
  fun allows(name: String): Boolean = allowedByName.containsKey(name.trim())

  fun isEmpty(): Boolean = allowedByName.isEmpty()

  override fun toString(): String =
    "DvexToolSelection(scope=$scope, goal=${goal?.take(40) ?: "<none>"}, " +
      "tools=${allowedByName.size}/${registry.size}, unavailable=$unavailableCapabilities)"

  companion object {

    /**
     * Always-declared core of a GOAL_SCOPED selection: low-risk navigation and
     * launch tools that serve (and let the model unwind) almost any device
     * goal. Every one of them is still capability-checked and policy-checked at
     * execution time like any other tool.
     */
    private val CORE_TYPES: Set<AgentActionType> = setOf(
      AgentActionType.OPEN_APP,
      AgentActionType.LAUNCH_URL,
      AgentActionType.WEB_SEARCH,
      AgentActionType.NAVIGATE_HOME,
      AgentActionType.NAVIGATE_BACK,
      AgentActionType.OPEN_RECENT_APPS
    )

    /**
     * One relevance bucket: keyword evidence for a goal family plus the COMPLETE
     * chain of tools that family needs (so a multi-step goal such as "open
     * YouTube and search cats" keeps every follow-up step declared — buckets are
     * chain-complete by design).
     */
    private data class Bucket(
      val keywords: List<String>,
      val types: Set<AgentActionType>
    )

    private val BUCKETS: List<Bucket> = listOf(
      Bucket(
        keywords = listOf(
          "open", "launch", "app", "youtube", "switch to", "start the", "run ",
          "find the installed", "search my installed", "installed app named",
          "whatsapp open", "instagram open", "open calculator", "open settings app"
        ),
        types = setOf(
          AgentActionType.OPEN_APP,
          AgentActionType.OPEN_CAMERA,
          AgentActionType.OPEN_SETTINGS,
          AgentActionType.MEDIA_PLAY,
          AgentActionType.SEARCH_INSTALLED_APPS
        )
      ),
      Bucket(
        keywords = listOf(
          "search", "google", "look up", "lookup", "browse", "internet",
          "website", "web ", "url", "link", "http"
        ),
        types = setOf(
          AgentActionType.WEB_SEARCH,
          AgentActionType.LAUNCH_URL,
          AgentActionType.SEARCH_IN_APP,
          AgentActionType.OPEN_APP
        )
      ),
      Bucket(
        keywords = listOf(
          "message", "msg", "sms", "whatsapp", "send", "reply", "tell them",
          "text ", "shoot a"
        ),
        types = setOf(AgentActionType.SEND_MESSAGE)
      ),
      Bucket(
        keywords = listOf(
          "reminder", "alarm", "remind", "timer", "wake me", "schedule"
        ),
        types = setOf(AgentActionType.CREATE_REMINDER)
      ),
      Bucket(
        keywords = listOf(
          "camera", "vision", "capture", "describe", "recognize", "recognise",
          "photo", "picture", "what is", "what can you see", "visible", "read the",
          "look at", "see what"
        ),
        types = setOf(
          AgentActionType.ANALYZE_CAMERA_FRAME,
          AgentActionType.START_LIVE_VISION,
          AgentActionType.STOP_LIVE_VISION,
          AgentActionType.OPEN_CAMERA
        )
      ),
      Bucket(
        keywords = listOf(
          "play", "pause", "music", "song", "track", "next ", "previous",
          "volume", "louder", "quieter", "mute", "sound"
        ),
        types = setOf(
          AgentActionType.MEDIA_PLAY,
          AgentActionType.MEDIA_PAUSE,
          AgentActionType.MEDIA_NEXT,
          AgentActionType.MEDIA_PREVIOUS,
          AgentActionType.VOLUME_UP,
          AgentActionType.VOLUME_DOWN
        )
      ),
      Bucket(
        keywords = listOf("home", "back", "recents", "recent apps", "screen"),
        types = setOf(
          AgentActionType.NAVIGATE_HOME,
          AgentActionType.NAVIGATE_BACK,
          AgentActionType.OPEN_RECENT_APPS
        )
      ),
      Bucket(
        keywords = listOf(
          "settings", "wifi", "wi-fi", "bluetooth", "display", "brightness",
          "location", "accessibility", "dark mode", "date and time",
          "open bluetooth settings", "open wifi settings", "internet settings",
          "take me to internet", "take me to bluetooth"
        ),
        types = setOf(
          AgentActionType.OPEN_SETTINGS,
          AgentActionType.OPEN_WIFI_SETTINGS,
          AgentActionType.OPEN_BLUETOOTH_SETTINGS,
          AgentActionType.OPEN_DISPLAY_SETTINGS,
          AgentActionType.OPEN_DATE_TIME_SETTINGS,
          AgentActionType.OPEN_LOCATION_SETTINGS,
          AgentActionType.OPEN_NOTIFICATION_SETTINGS,
          AgentActionType.OPEN_ACCESSIBILITY_SETTINGS,
          AgentActionType.OPEN_APPS_SETTINGS,
          AgentActionType.OPEN_APP_SETTINGS,
          AgentActionType.CLOSE_APP
        )
      ),
      Bucket(
        keywords = listOf(
          "scroll", "swipe", "tap", "press", "click", "type", "enter text",
          "clear the", "submit", "fill in", "fill the", "close", "close the current app",
          "turn on flashlight", "torch on", "torch off", "flashlight on", "flashlight off",
          "is my flashlight on", "flashlight status", "torch status",
          "wifi status", "is wifi connected", "bluetooth status", "is bluetooth on",
          "battery percentage", "is my phone charging", "battery status", "battery health",
          "how much storage", "storage status", "free storage",
          "internet connected", "network status", "am i using wifi", "am i using mobile data",
          "is internet available"
        ),
        types = setOf(
          AgentActionType.SCROLL,
          AgentActionType.TAP_ELEMENT,
          AgentActionType.TYPE_TEXT,
          AgentActionType.CLEAR_TEXT,
          AgentActionType.SUBMIT_TEXT,
          AgentActionType.FLASHLIGHT_CONTROL,
          AgentActionType.FLASHLIGHT_STATUS,
          AgentActionType.WIFI_STATUS,
          AgentActionType.BLUETOOTH_STATUS,
          AgentActionType.BATTERY_STATUS,
          AgentActionType.NETWORK_STATUS,
          AgentActionType.STORAGE_STATUS,
          AgentActionType.CLOSE_APP
        )
      ),
      Bucket(
        keywords = listOf("weather", "temperature", "rain", "forecast", "humidity"),
        types = setOf(AgentActionType.GET_WEATHER)
      ),
      Bucket(
        keywords = listOf("notification", "notifications", "alerts", "shade"),
        types = setOf(
          AgentActionType.OPEN_NOTIFICATIONS,
          AgentActionType.OPEN_NOTIFICATION_SETTINGS
        )
      )
    )

    /**
     * Builds the selection for one goal.
     *
     * @param registry canonical source of truth; selection only narrows it.
     * @param goal the CURRENT user goal (a turn's utterance, or a Live session's
     *   attached command). Changes to the goal produce a new selection at the
     *   next refresh point (every REST turn; every new Live session).
     * @param plan when supplied, every step type of this (multi-step) plan is
     *   preserved in the selection regardless of keyword relevance.
     * @param isAvailable live permission/capability probe; unavailable tools are
     *   never declared. Execution re-probes anyway ([DvexAgentEngine]).
     */
    fun select(
      registry: DvexMcpRegistry,
      goal: String?,
      plan: DvexTaskPlan? = null,
      isAvailable: (DvexCapability) -> Boolean = { true }
    ): DvexToolSelection {
      val normalized = goal?.trim()?.lowercase(Locale.ROOT).orEmpty()
      val planTypes: Set<AgentActionType> =
        plan?.steps?.mapTo(LinkedHashSet()) { it.type } ?: emptySet()

      val matchedTypes: Set<AgentActionType> =
        if (normalized.isEmpty()) {
          emptySet()
        } else {
          BUCKETS
            .filter { bucket -> bucket.keywords.any { normalized.contains(it) } }
            .flatMap { it.types }
            .toSet()
        }

      val scope =
        if (matchedTypes.isEmpty() && planTypes.isEmpty()) {
          ToolSelectionScope.FULL_SET
        } else {
          ToolSelectionScope.GOAL_SCOPED
        }

      val selectedTypes: Set<AgentActionType> = when (scope) {
        // No relevance signal: every registered type (plan types are a subset of
        // the registry by construction, so they are already contained).
        ToolSelectionScope.FULL_SET -> registry.tools().mapTo(HashSet()) { it.actionType }
        ToolSelectionScope.GOAL_SCOPED -> CORE_TYPES + matchedTypes + planTypes
      }

      // Narrow from the canonical registry IN ORDER, dropping only what is
      // genuinely unavailable right now. Never invents, renames or re-describes.
      val unavailable = LinkedHashSet<DvexCapability>()
      val allowed = LinkedHashMap<String, DvexMcpTool>()
      registry.tools().forEach { tool ->
        if (tool.actionType !in selectedTypes) return@forEach
        if (isAvailable(tool.capability)) {
          allowed[tool.name] = tool
        } else {
          unavailable += tool.capability
        }
      }

      return DvexToolSelection(
        registry = registry,
        allowedByName = allowed,
        goal = goal?.trim()?.takeIf { it.isNotEmpty() },
        scope = scope,
        unavailableCapabilities = unavailable.toSet()
      )
    }
  }
}

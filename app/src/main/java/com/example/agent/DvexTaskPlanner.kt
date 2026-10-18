package com.example.agent

import com.example.brain.DvexIntent
import java.util.Locale

/**
 * D-VEX TASK PLANNER
 *
 * Turns one detected intent (plus the short-lived task context) into an executable
 * [DvexTaskPlan]. Returns null when the intent is not agent-owned — the existing
 * tool router then handles it exactly as before, so nothing is duplicated.
 *
 * The planner only decides WHAT should happen; handlers and the capability layer
 * decide HOW, and the engine verifies the result.
 */
/**
 * Structured outcome of planning one intent. Deliberately richer than a nullable
 * plan so the caller can tell WHY something will not run and act honestly:
 *  - [Represented] an executable plan exists.
 *  - [NotAgentOwned] the intent belongs to the existing tool router (conversation,
 *    calculation, …) — the router keeps its behaviour, no duplicate decision system.
 *  - [Unsupported] a step has no registered handler — report which step, never guess.
 *  - [Clarification] the target is ambiguous and D-VEX must ask, never assume.
 */
sealed class PlanOutcome {
  data class Represented(val plan: DvexTaskPlan) : PlanOutcome()
  data object NotAgentOwned : PlanOutcome()
  data class Unsupported(val reason: String, val stepIndex: Int) : PlanOutcome()
  data class Clarification(val prompt: String) : PlanOutcome()
}

class DvexTaskPlanner(
  private val taskContext: DvexTaskContext,
  /** Registry used to VALIDATE representability (a step must have a real handler). */
  private val registry: DvexActionRegistry = DvexActionRegistry.withDefaults()
) {

  fun plan(intent: DvexIntent, rawInput: String = ""): DvexTaskPlan? {
    val steps = buildSteps(intent, rawInput) ?: return null
    if (steps.isEmpty()) return null
    return DvexTaskPlan(steps = steps, summary = summaryOf(steps))
  }

  /**
   * The planning decision as a structured [PlanOutcome]: validates every step against
   * the registry (an unregistered step reports [PlanOutcome.Unsupported] with its index
   * instead of silently producing an unrunnable plan), and surfaces an ambiguous
   * follow-up as [PlanOutcome.Clarification] rather than guessing a target.
   */
  fun planOutcome(intent: DvexIntent, rawInput: String = ""): PlanOutcome {
    // Conversational / computed input is never agent-owned: the router handles it.
    val steps = buildSteps(intent, rawInput)
      ?: return PlanOutcome.NotAgentOwned
    if (steps.isEmpty()) return PlanOutcome.NotAgentOwned

    steps.forEachIndexed { index, step ->
      if (registry.handlerFor(step.type) == null) {
        return PlanOutcome.Unsupported("Unsupported step: ${step.type}", index)
      }
    }

    // Ambiguous app reference with no resolvable single target -> ask, never guess.
    if (steps.size == 1 && steps[0].type == AgentActionType.OPEN_APP && isAmbiguousReference(steps[0].target)) {
      return PlanOutcome.Clarification("Which app did you mean by \"${steps[0].target}\"?")
    }

    return PlanOutcome.Represented(DvexTaskPlan(steps = steps, summary = summaryOf(steps)))
  }

  /**
   * Re-plans the steps AFTER the failed one for the single bounded recovery pass.
   * Blank search targets are refreshed from the verified task context (never
   * invented). Returns null when the remaining sequence cannot be validated against
   * the registry, so the engine stops honestly instead of guessing.
   */
  fun replanAfterFailure(plan: DvexTaskPlan, failedIndex: Int): DvexTaskPlan? {
    if (failedIndex < 0 || failedIndex >= plan.steps.size) return null
    val remaining = plan.steps.drop(failedIndex + 1)
    if (remaining.isEmpty()) return null

    val refreshed = remaining.map { step ->
      if (step.type == AgentActionType.SEARCH_IN_APP && step.target.isBlank()) {
        step.copy(target = taskContext.lastTargetApp.orEmpty())
      } else {
        step
      }
    }

    // Every re-planned step must have a real handler — else NO re-plan is produced.
    if (refreshed.any { registry.handlerFor(it.type) == null }) return null
    // A search step with no resolvable target is not safely executable.
    if (refreshed.any { it.type == AgentActionType.SEARCH_IN_APP && it.target.isBlank() }) return null

    return DvexTaskPlan(steps = refreshed, summary = summaryOf(refreshed))
  }

  /** Vague pronouns that do not name a single app (ambiguity is surfaced, not guessed). */
  private fun isAmbiguousReference(target: String): Boolean {
    val t = target.trim().lowercase(Locale.ROOT).removePrefix("the ").trim()
    return t in setOf(
      "first one", "second one", "next one", "other one", "last one",
      "that one", "this one", "latter", "former", "it", "that", "this"
    )
  }

  private fun buildSteps(intent: DvexIntent, rawInput: String): List<AgentAction>? = when (intent) {
    // --- App / device actions ---------------------------------------------
    is DvexIntent.OpenApp -> {
      if (isCamera(intent.appName)) {
        listOf(AgentAction(AgentActionType.OPEN_CAMERA, DvexCapability.CAMERA, target = "camera"))
      } else {
        composeChain(
          base = listOf(
            AgentAction(
              type = AgentActionType.OPEN_APP,
              capability = DvexCapability.APP_LAUNCH,
              target = intent.appName
            )
          ),
          baseTarget = intent.appName,
          rawInput = rawInput
        )
      }
    }

    is DvexIntent.OpenSettings ->
      listOf(AgentAction(AgentActionType.OPEN_SETTINGS, DvexCapability.SETTINGS))

    is DvexIntent.OpenRecents ->
      listOf(AgentAction(AgentActionType.OPEN_RECENT_APPS, DvexCapability.NAVIGATION))

    is DvexIntent.GetRecentApps ->
      // The real recent-apps LIST needs Usage access; the recents screen does not.
      listOf(AgentAction(AgentActionType.OPEN_RECENT_APPS, DvexCapability.APP_USAGE))

    is DvexIntent.OpenNotifications ->
      listOf(AgentAction(AgentActionType.OPEN_NOTIFICATIONS, DvexCapability.NAVIGATION))

    is DvexIntent.OpenWifiSettings ->
      listOf(AgentAction(AgentActionType.OPEN_WIFI_SETTINGS, DvexCapability.SETTINGS))

    is DvexIntent.OpenSystemSettings ->
      listOf(
        AgentAction(
          type = when (intent.kind) {
            com.example.brain.SystemSettingsKind.BLUETOOTH -> AgentActionType.OPEN_BLUETOOTH_SETTINGS
            com.example.brain.SystemSettingsKind.DISPLAY -> AgentActionType.OPEN_DISPLAY_SETTINGS
            com.example.brain.SystemSettingsKind.DATE_TIME -> AgentActionType.OPEN_DATE_TIME_SETTINGS
            com.example.brain.SystemSettingsKind.LOCATION -> AgentActionType.OPEN_LOCATION_SETTINGS
            com.example.brain.SystemSettingsKind.NOTIFICATION -> AgentActionType.OPEN_NOTIFICATION_SETTINGS
            com.example.brain.SystemSettingsKind.ACCESSIBILITY -> AgentActionType.OPEN_ACCESSIBILITY_SETTINGS
          },
          capability = DvexCapability.SETTINGS
        )
      )

    is DvexIntent.Scroll ->
      listOf(
        AgentAction(
          type = AgentActionType.SCROLL,
          capability = DvexCapability.UI_INTERACTION,
          query = intent.direction.name
        )
      )

    is DvexIntent.AdjustVolume ->
      listOf(
        AgentAction(
          type = if (intent.direction == com.example.brain.VolumeAction.UP) {
            AgentActionType.VOLUME_UP
          } else {
            AgentActionType.VOLUME_DOWN
          },
          capability = DvexCapability.MEDIA
        )
      )

    is DvexIntent.MediaControl ->
      listOf(
        AgentAction(
          type = when (intent.command) {
            com.example.brain.MediaAction.PLAY_PAUSE -> AgentActionType.MEDIA_PLAY
            com.example.brain.MediaAction.NEXT -> AgentActionType.MEDIA_NEXT
            com.example.brain.MediaAction.PREVIOUS -> AgentActionType.MEDIA_PREVIOUS
          },
          capability = DvexCapability.MEDIA
        )
      )

    is DvexIntent.GoHome ->
      listOf(AgentAction(AgentActionType.NAVIGATE_HOME, DvexCapability.NAVIGATION))

    is DvexIntent.GoBack ->
      listOf(AgentAction(AgentActionType.NAVIGATE_BACK, DvexCapability.NAVIGATION))

    // --- Search -----------------------------------------------------------
    is DvexIntent.PlayYoutubeVideo -> listOf(
      AgentAction(
        type = AgentActionType.SEARCH_IN_APP,
        capability = DvexCapability.IN_APP_SEARCH,
        target = "YouTube",
        query = intent.query
      )
    )

    is DvexIntent.SearchWeb -> {
      val continueApp = taskContext.currentAppForSearch()
      if (continueApp != null) {
        listOf(
          AgentAction(
            type = AgentActionType.SEARCH_IN_APP,
            capability = DvexCapability.IN_APP_SEARCH,
            target = continueApp,
            query = intent.query
          )
        )
      } else {
        null // No app context → keep the existing browser-search behaviour.
      }
    }

    // --- Multi-step task plans -------------------------------------------
    is DvexIntent.MultiStep -> buildMultiStep(intent)

    // --- Information ------------------------------------------------------
    is DvexIntent.GetWeather ->
      listOf(AgentAction(AgentActionType.GET_WEATHER, DvexCapability.WEATHER))

    is DvexIntent.SetAlarm -> {
      if (intent.hour == null || intent.minute == null) {
        null // No parseable time → let the existing "repeat with a time" path handle it.
      } else {
        listOf(
          AgentAction(
            type = AgentActionType.CREATE_REMINDER,
            capability = DvexCapability.SETTINGS,
            target = "%02d:%02d".format(Locale.ROOT, intent.hour, intent.minute),
            query = intent.message.orEmpty(),
            risk = ActionRisk.MEDIUM
          )
        )
      }
    }

    // --- High-risk, confirmed separately ---------------------------------
    is DvexIntent.SendMessage -> listOf(
      AgentAction(
        type = AgentActionType.SEND_MESSAGE,
        capability = DvexCapability.MESSAGING,
        target = intent.recipient,
        query = intent.messageText.orEmpty(),
        risk = ActionRisk.HIGH
      )
    )

    else -> null
  }

  private fun buildMultiStep(intent: DvexIntent.MultiStep): List<AgentAction>? {
    val open = intent.first as? DvexIntent.OpenApp ?: return null
    val query = when (val second = intent.second) {
      is DvexIntent.SearchWeb -> second.query
      is DvexIntent.PlayYoutubeVideo -> second.query
      else -> return null
    }
    if (query.isBlank()) return null

    val steps = mutableListOf<AgentAction>(
      if (isCamera(open.appName)) {
        AgentAction(AgentActionType.OPEN_CAMERA, DvexCapability.CAMERA, target = "camera")
      } else {
        AgentAction(
          type = AgentActionType.OPEN_APP,
          capability = DvexCapability.APP_LAUNCH,
          target = open.appName
        )
      },
      AgentAction(
        type = AgentActionType.SEARCH_IN_APP,
        capability = DvexCapability.IN_APP_SEARCH,
        target = open.appName,
        query = query
      )
    )
    return steps
  }

  private fun isCamera(appName: String): Boolean =
    appName.contains("camera", ignoreCase = true)

  /**
   * Composes an ordered multi-step plan from the utterance for the general N-step
   * case: a base step (the app being opened) followed by each recognized trailing
   * clause. Clauses are split on commas / "then" and mapped to real actions:
   *   "search X" → SEARCH_IN_APP (in the opened app), "scroll down/up" → SCROLL,
   *   "type/enter X" → TYPE_TEXT, "open/tap X" → TAP_ELEMENT.
   *
   * An UNRECOGNIZED clause stops composition instead of guessing an action, so the
   * plan only ever contains steps that were clearly requested.
   */
  private fun composeChain(
    base: List<AgentAction>,
    baseTarget: String,
    rawInput: String
  ): List<AgentAction> {
    val clauses = rawInput
      .split(Regex("\\s*(?:,|\\bthen\\b)\\s*", RegexOption.IGNORE_CASE))
      .map { it.trim() }
      .filter { it.isNotEmpty() }
    if (clauses.size <= 1) return base

    val steps = base.toMutableList()
    // clause[0] is the base open already represented by [base].
    for (clause in clauses.drop(1)) {
      val lower = clause.lowercase(Locale.ROOT)
      when {
        lower.startsWith("search") -> {
          val query = clause.replace(Regex("(?i)^search\\s+"), "").trim()
          if (query.isNotEmpty()) {
            steps += AgentAction(
              type = AgentActionType.SEARCH_IN_APP,
              capability = DvexCapability.IN_APP_SEARCH,
              target = baseTarget,
              query = query
            )
          }
        }
        lower.startsWith("scroll") -> {
          val up = lower.contains("up")
          steps += AgentAction(
            type = AgentActionType.SCROLL,
            capability = DvexCapability.UI_INTERACTION,
            query = if (up) "UP" else "DOWN"
          )
        }
        lower.startsWith("type") || lower.startsWith("enter") || lower.startsWith("fill") -> {
          val text = clause.replace(Regex("(?i)^(type|enter|fill)\\s*(text\\s*)?"), "").trim()
          if (text.isNotEmpty()) {
            steps += AgentAction(
              type = AgentActionType.TYPE_TEXT,
              capability = DvexCapability.UI_INTERACTION,
              query = text
            )
          }
        }
        lower.startsWith("open") || lower.startsWith("tap") ||
          lower.startsWith("click") || lower.startsWith("press") -> {
          val label = clause
            .replace(Regex("(?i)^(open|tap|click|press)\\s+"), "")
            .replace(Regex("(?i)^the\\s+"), "")
            .trim()
          if (label.isNotEmpty()) {
            steps += AgentAction(
              type = AgentActionType.TAP_ELEMENT,
              capability = DvexCapability.UI_INTERACTION,
              query = label
            )
          }
        }
        // Unrecognized clause: stop rather than guess a step.
        else -> return steps
      }
    }
    return steps
  }

  private fun summaryOf(steps: List<AgentAction>): String = when {
    steps.size == 1 -> describeAction(steps.first())
    else -> steps.joinToString(", ") { describeAction(it) }
  }
}

/**
 * Short, speakable description of one action ("open YouTube", "message Arun").
 *
 * Shared by the planner (plan summaries) and [DvexToolProtocol] (a tool call admitted
 * from the model), so one action is described the same way no matter how it was
 * triggered — including inside the "Shall I …?" confirmation prompt.
 */
internal fun describeAction(action: AgentAction): String = when (action.type) {
    AgentActionType.OPEN_APP -> "open ${action.target}"
    AgentActionType.OPEN_CAMERA -> "open the camera"
    AgentActionType.SEARCH_IN_APP -> "search \"${action.query}\" in ${action.target}"
    AgentActionType.LAUNCH_URL -> "open ${action.target}"
    AgentActionType.NAVIGATE_HOME -> "go home"
    AgentActionType.NAVIGATE_BACK -> "go back"
    AgentActionType.OPEN_RECENT_APPS -> "open recent apps"
    AgentActionType.OPEN_SETTINGS -> "open settings"
    AgentActionType.GET_WEATHER -> "get the weather"
    AgentActionType.CREATE_REMINDER -> "set a reminder for ${action.target}"
    AgentActionType.SEND_MESSAGE -> "message ${action.target}"
    AgentActionType.OPEN_NOTIFICATIONS -> "open notifications"
    AgentActionType.OPEN_WIFI_SETTINGS -> "open Wi-Fi settings"
    AgentActionType.OPEN_BLUETOOTH_SETTINGS -> "open Bluetooth settings"
    AgentActionType.OPEN_DISPLAY_SETTINGS -> "open display settings"
    AgentActionType.OPEN_DATE_TIME_SETTINGS -> "open date & time settings"
    AgentActionType.OPEN_LOCATION_SETTINGS -> "open location settings"
    AgentActionType.OPEN_NOTIFICATION_SETTINGS -> "open notification settings"
    AgentActionType.OPEN_ACCESSIBILITY_SETTINGS -> "open accessibility settings"
    AgentActionType.SCROLL -> if (action.query == "UP") "scroll up" else "scroll down"
    AgentActionType.TAP_ELEMENT -> "tap \"${action.query}\""
    AgentActionType.TYPE_TEXT -> "type \"${action.query}\""
    AgentActionType.CLEAR_TEXT -> "clear the text field"
    AgentActionType.SUBMIT_TEXT -> "submit the search"
    AgentActionType.MEDIA_PLAY -> "play or pause"
    AgentActionType.MEDIA_PAUSE -> "play or pause"
    AgentActionType.MEDIA_NEXT -> "skip to the next track"
    AgentActionType.MEDIA_PREVIOUS -> "go to the previous track"
    AgentActionType.VOLUME_UP -> "turn the volume up"
    AgentActionType.VOLUME_DOWN -> "turn the volume down"
    AgentActionType.WEB_SEARCH -> if (action.query.isBlank()) "search the web" else "search the web for \"${action.query}\""
    AgentActionType.SEARCH_INSTALLED_APPS -> if (action.query.isBlank()) "find an installed app" else "find \"${action.query}\" among your apps"
    AgentActionType.ANALYZE_CAMERA_FRAME -> if (action.query.isBlank()) "analyze what the camera sees" else "analyze what the camera sees for \"${action.query}\""
    AgentActionType.START_LIVE_VISION -> "start continuous camera analysis"
    AgentActionType.STOP_LIVE_VISION -> "stop continuous camera analysis"
    AgentActionType.OPEN_APPS_SETTINGS -> "open your apps settings"
    AgentActionType.OPEN_APP_SETTINGS -> if (action.target.isBlank()) "open app settings" else "open ${action.target} settings"
    AgentActionType.CLOSE_APP -> if (action.target.isBlank()) "close the app" else "close ${action.target}"
    AgentActionType.FLASHLIGHT_CONTROL -> when (action.query.lowercase(Locale.ROOT)) {
      "on" -> "turn the flashlight on"
      "off" -> "turn the flashlight off"
      else -> "toggle the flashlight"
    }
    AgentActionType.FLASHLIGHT_STATUS -> "check the flashlight"
    AgentActionType.WIFI_STATUS -> "check the Wi-Fi status"
    AgentActionType.BLUETOOTH_STATUS -> "check the Bluetooth status"
    AgentActionType.BATTERY_STATUS -> "check the battery"
    AgentActionType.NETWORK_STATUS -> "check the network status"
    AgentActionType.STORAGE_STATUS -> "check storage"
  }

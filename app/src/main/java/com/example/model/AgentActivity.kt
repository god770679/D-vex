package com.example.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * D-VEX AGENT ACTIVITY MODEL
 *
 * Reusable, user-facing state for the contextual capability popups. The future
 * autonomous action layer publishes here (open app → search → verify) and the HUD
 * simply renders [AgentActivityController.activities].
 *
 * Hard rule: only short, human-facing text ever leaves this model. Tool names,
 * intents, JSON and planning detail must never be rendered to the user.
 */
enum class AgentCapability(val displayName: String) {
  YOUTUBE("YouTube"),
  WHATSAPP("WhatsApp"),
  INSTAGRAM("Instagram"),
  RECENT_APPS("Recent Apps"),
  CAMERA("Camera"),
  MICROPHONE("Microphone"),
  LOCATION("Location"),
  WEATHER("Weather"),
  NOTIFICATIONS("Notifications"),
  CONTACTS("Contacts"),
  CALENDAR("Calendar"),
  MESSAGES("Messages"),
  DEVICE("Device"),
  APP("App")
}

enum class AgentActivityStatus {
  ACCESS_REQUIRED,
  WAITING_PERMISSION,
  OPENING,
  SEARCHING,
  RUNNING,
  COMPLETED,
  FAILED
}

data class AgentActivity(
  val id: String,
  val capability: AgentCapability,
  val status: AgentActivityStatus,
  /** Optional user-facing subject, e.g. a search term ("Spider-Man"). */
  val detail: String = "",
  /** Terminal states with this flag may auto-dismiss from the popup host. */
  val autoDismiss: Boolean = true
) {
  /** Compact, human-facing status line. Never contains internal/tool data. */
  val message: String
    get() = when (status) {
      AgentActivityStatus.ACCESS_REQUIRED ->
        "${capability.displayName} access required"
      AgentActivityStatus.WAITING_PERMISSION ->
        "Waiting for permission…"
      AgentActivityStatus.OPENING ->
        if (detail.isBlank()) "Opening ${capability.displayName}…"
        else "Opening ${capability.displayName}: $detail…"
      AgentActivityStatus.SEARCHING ->
        if (detail.isBlank()) "Searching…" else "Searching \"$detail\"…"
      AgentActivityStatus.RUNNING ->
        if (detail.isBlank()) "Working on it…" else detail
      AgentActivityStatus.COMPLETED ->
        if (detail.isBlank()) "Completed" else detail
      AgentActivityStatus.FAILED ->
        if (detail.isBlank()) "Couldn't complete that" else detail
    }

  val isTerminal: Boolean
    get() = status == AgentActivityStatus.COMPLETED || status == AgentActivityStatus.FAILED
}

/**
 * Single source of truth for the contextual agent popups.
 *
 * The agent/action layer calls [begin] → [update] → [complete]/[fail]; permission
 * gaps are surfaced with [requireAccess]. The HUD only observes [activities].
 */
object AgentActivityController {

  private const val MAX_VISIBLE = 3

  private val _activities = MutableStateFlow<List<AgentActivity>>(emptyList())
  val activities: StateFlow<List<AgentActivity>> = _activities.asStateFlow()

  private var counter = 0L

  private fun nextId(): String {
    counter += 1
    return "agent-$counter"
  }

  private fun push(activity: AgentActivity) {
    _activities.value = (listOf(activity) + _activities.value)
      .take(MAX_VISIBLE)
  }

  /** Starts tracking a capability action; returns the id used for later updates. */
  @Synchronized
  fun begin(
    capability: AgentCapability,
    status: AgentActivityStatus = AgentActivityStatus.OPENING,
    detail: String = ""
  ): String {
    val id = nextId()
    push(AgentActivity(id = id, capability = capability, status = status, detail = detail))
    return id
  }

  /** Updates the status/detail of a tracked action (no-op when it already left the list). */
  @Synchronized
  fun update(id: String, status: AgentActivityStatus, detail: String = "") {
    _activities.value = _activities.value.map { current ->
      if (current.id == id) current.copy(status = status, detail = detail) else current
    }
  }

  fun complete(id: String, detail: String = "") =
    update(id, AgentActivityStatus.COMPLETED, detail)

  fun fail(id: String, detail: String = "") =
    update(id, AgentActivityStatus.FAILED, detail)

  /** Surfaces a missing capability as a compact popup instead of a stuck state. */
  @Synchronized
  fun requireAccess(capability: AgentCapability, waitingForPermission: Boolean = false) {
    // Never stack duplicate access prompts for the same capability.
    if (_activities.value.any {
        it.capability == capability &&
          (it.status == AgentActivityStatus.ACCESS_REQUIRED ||
            it.status == AgentActivityStatus.WAITING_PERMISSION)
      }
    ) {
      return
    }
    push(
      AgentActivity(
        id = nextId(),
        capability = capability,
        status = if (waitingForPermission) AgentActivityStatus.WAITING_PERMISSION
        else AgentActivityStatus.ACCESS_REQUIRED,
        autoDismiss = false
      )
    )
  }

  /** Marks the newest in-flight action complete (used when a task finishes). */
  @Synchronized
  fun completeInFlight(detail: String = "") {
    _activities.value = _activities.value.map { current ->
      if (current.status == AgentActivityStatus.COMPLETED ||
        current.status == AgentActivityStatus.FAILED ||
        current.status == AgentActivityStatus.ACCESS_REQUIRED ||
        current.status == AgentActivityStatus.WAITING_PERMISSION
      ) current
      else current.copy(status = AgentActivityStatus.COMPLETED, detail = detail)
    }
  }

  @Synchronized
  fun dismiss(id: String) {
    _activities.value = _activities.value.filterNot { it.id == id }
  }

  @Synchronized
  fun clear() {
    _activities.value = emptyList()
  }
}

package com.example.agent

/**
 * D-VEX TASK CONTEXT (short-lived, in-memory only)
 *
 * Keeps just enough context to resolve follow-ups inside the current task, e.g.
 * "Open YouTube." → "Search Spider-Man." meaning search *in YouTube*.
 *
 * Deliberately separate from long-term user memory: nothing here is persisted, and
 * [clear] drops it. No uncontrolled permanent memory is created.
 */
class DvexTaskContext {

  var lastTargetApp: String? = null
    private set

  var lastSearchQuery: String? = null
    private set

  var lastCapability: DvexCapability? = null
    private set

  /** Apps whose in-app search D-VEX can carry over to a follow-up turn. */
  private val searchableApps = setOf("youtube", "maps")

  fun remember(action: AgentAction) {
    if (action.type == AgentActionType.OPEN_APP && action.target.isNotBlank()) {
      lastTargetApp = action.target
      lastCapability = action.capability
    }
    if (action.type == AgentActionType.SEARCH_IN_APP) {
      if (action.target.isNotBlank()) lastTargetApp = action.target
      if (action.query.isNotBlank()) lastSearchQuery = action.query
      lastCapability = action.capability
    }
  }

  /** True when a bare "search …" follow-up can reuse the current app. */
  fun canContinueSearchIn(app: String?): Boolean {
    val current = lastTargetApp ?: return false
    if (app != null) return current.contains(app, ignoreCase = true)
    return searchableApps.any { current.contains(it, ignoreCase = true) }
  }

  fun currentAppForSearch(): String? {
    val current = lastTargetApp ?: return null
    return if (searchableApps.any { current.contains(it, ignoreCase = true) }) current else null
  }

  fun clear() {
    lastTargetApp = null
    lastSearchQuery = null
    lastCapability = null
  }
}

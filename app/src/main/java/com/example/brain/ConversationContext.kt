package com.example.brain

import java.util.LinkedList

data class ContextTurn(
  val userInput: String,
  val intent: DvexIntent,
  val toolName: String,
  val timestamp: Long = System.currentTimeMillis()
)

/**
 * Bounded short-term conversation context for D-VEX Smart Brain.
 * Tracks recent interactions, active apps, and follow-up references
 * without unbounded memory growth.
 */
class ConversationContext(private val maxHistorySize: Int = 6) {

  private val history = LinkedList<ContextTurn>()

  var lastActiveApp: String? = null
    private set

  var lastQuery: String? = null
    private set

  var lastIntent: DvexIntent? = null
    private set

  var lastLanguage: DetectedLanguage = DetectedLanguage.ENGLISH
    private set

  var lastWeatherLocation: String? = null
    private set

  var lastContactRecipient: String? = null
    private set

  fun update(
    input: String,
    intent: DvexIntent,
    toolResult: DvexToolResult,
    language: DetectedLanguage
  ) {
    lastIntent = intent
    lastLanguage = language

    when (intent) {
      is DvexIntent.OpenApp -> {
        lastActiveApp = intent.appName
      }
      is DvexIntent.SearchWeb -> {
        lastQuery = intent.query
      }
      is DvexIntent.GetWeather -> {
        if (!intent.location.isNullOrBlank()) {
          lastWeatherLocation = intent.location
        }
      }
      is DvexIntent.CallContact -> {
        lastContactRecipient = intent.recipient
      }
      is DvexIntent.SendMessage -> {
        lastContactRecipient = intent.recipient
      }
      is DvexIntent.MultiStep -> {
        if (intent.first is DvexIntent.OpenApp) {
          lastActiveApp = intent.first.appName
        }
      }
      else -> {}
    }

    synchronized(history) {
      if (history.size >= maxHistorySize) {
        history.removeFirst()
      }
      history.addLast(
        ContextTurn(
          userInput = input,
          intent = intent,
          toolName = toolResult.toolName
        )
      )
    }
  }

  /**
   * Resolves context-dependent follow-up inputs like "Tomorrow?" or "Search for tractor videos"
   * based on short-term conversation state.
   */
  fun resolveContextualFollowUp(cleanInput: String): DvexIntent? {
    val lower = cleanInput.lowercase().trim().trimEnd('?', '.', '!')

    // 1. Follow-up weather question (e.g. "Tomorrow?", "What about tomorrow?", "naalai", "naalaiku?")
    if (lower == "tomorrow" || lower == "what about tomorrow" || lower == "how about tomorrow" ||
        lower == "and tomorrow" || lower == "naalai" || lower == "naalaiku" || lower.contains("naalai")) {
      if (lastWeatherLocation != null || lastIntent is DvexIntent.GetWeather) {
        val city = lastWeatherLocation ?: "Chennai"
        return DvexIntent.GetWeather(location = city, isTomorrow = true)
      }
    }

    // 2. Follow-up contact question (e.g. "Call him", "Call her", "Avanukku call pannu")
    if (lastContactRecipient != null) {
      if (lower == "call him" || lower == "call her" || lower == "call" ||
          lower == "avanukku call pannu" || lower == "avalukku call pannu") {
        return DvexIntent.CallContact(lastContactRecipient!!)
      }
      if (lower.startsWith("message him ") || lower.startsWith("message her ") ||
          lower.startsWith("text him ") || lower.startsWith("text her ")) {
        val msg = lower.substringAfter("him ").substringAfter("her ").trim()
        return DvexIntent.SendMessage(recipient = lastContactRecipient!!, messageText = msg)
      }
    }

    // 3. Follow-up search inside the last active application
    val currentApp = lastActiveApp?.lowercase()
    if (currentApp != null && (lower.startsWith("search for ") || lower.startsWith("search ") || lower.startsWith("find "))) {
      val query = lower.replace("search for ", "")
        .replace("search ", "")
        .replace("find ", "")
        .trim()
      if (query.isNotEmpty()) {
        return when {
          currentApp.contains("youtube") || currentApp == "yt" -> {
            DvexIntent.MultiStep(
              first = DvexIntent.OpenApp("YouTube"),
              second = DvexIntent.SearchWeb(query)
            )
          }
          currentApp.contains("maps") -> {
            DvexIntent.SearchWeb("maps: $query")
          }
          else -> {
            DvexIntent.SearchWeb(query)
          }
        }
      }
    }

    return null
  }

  fun clear() {
    synchronized(history) {
      history.clear()
    }
    lastActiveApp = null
    lastQuery = null
    lastIntent = null
    lastLanguage = DetectedLanguage.ENGLISH
    lastWeatherLocation = null
    lastContactRecipient = null
  }
}

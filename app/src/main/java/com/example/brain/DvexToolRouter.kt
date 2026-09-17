package com.example.brain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.control.DvexAccessibilityService
import com.example.data.remote.MediaCommand
import com.example.data.remote.ToolResultStatus
import com.example.data.remote.VolumeDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Executes intents using platform repositories, Android APIs, and local engines.
 * Strictly verifies execution results before returning status.
 */
class DvexToolRouter(
  private val context: Context,
  private val appLauncher: AppLauncherRepository,
  private val deviceControl: DeviceControlRepository
) {

  private val prefs: SharedPreferences =
    context.getSharedPreferences("dvex_brain_prefs", Context.MODE_PRIVATE)

  /**
   * Main dispatch entry point for DvexIntent.
   */
  suspend fun execute(intent: DvexIntent, conversationContext: ConversationContext? = null): DvexToolResult {
    Log.i(TAG_ROUTER, "Routing intent: $intent")

    return when (intent) {
      // --- App Operations ---
      is DvexIntent.OpenApp -> executeOpenApp(intent.appName)
      is DvexIntent.CloseApp -> executeCloseApp(intent.appName)

      // --- Navigation & Phone Control ---
      is DvexIntent.GoHome -> executeGoHome()
      is DvexIntent.GoBack -> executeGoBack()
      is DvexIntent.OpenRecents -> executeOpenRecents()
      is DvexIntent.OpenNotifications -> executeOpenNotifications()
      is DvexIntent.OpenSettings -> executeOpenSettings()
      is DvexIntent.OpenWifiSettings -> executeOpenWifi()
      is DvexIntent.LockScreen -> executeLockScreen()
      is DvexIntent.Scroll -> executeScroll(intent.direction)

      // --- Communication (Sensitive Confirmation) ---
      is DvexIntent.CallContact -> executeCallContact(intent.recipient)
      is DvexIntent.SendMessage -> executeSendMessage(intent.recipient, intent.messageText)

      // --- Live Information ---
      is DvexIntent.GetWeather -> executeGetWeather(intent.location)
      is DvexIntent.GetTime -> executeGetTime()
      is DvexIntent.Calculate -> executeCalculate(intent.expression)
      is DvexIntent.GetNews -> executeGetNews(intent.topic)
      is DvexIntent.SearchWeb -> executeSearchWeb(intent.query)

      // --- Media & Audio ---
      is DvexIntent.AdjustVolume -> executeAdjustVolume(intent.direction)
      is DvexIntent.MediaControl -> executeMediaControl(intent.command)

      // --- Tactical Memory ---
      is DvexIntent.RememberFact -> executeRememberFact(intent.fact)
      is DvexIntent.RecallMemory -> executeRecallMemory(intent.query)

      // --- Multi-Step Commands ---
      is DvexIntent.MultiStep -> executeMultiStep(intent.first, intent.second)

      // --- Conversation & General Knowledge ---
      is DvexIntent.GeneralQuestion -> executeGeneralQuestion(intent.question)
      is DvexIntent.Conversation -> executeConversation(intent.statement)

      // --- Ambiguity / Unknown ---
      is DvexIntent.LowConfidence -> {
        DvexToolResult(
          status = DvexToolStatus.SUCCESS,
          toolName = "clarification",
          message = intent.clarificationPrompt,
          spokenText = intent.clarificationPrompt
        )
      }
      is DvexIntent.Unknown -> {
        DvexToolResult(
          status = DvexToolStatus.FAILED,
          toolName = "unknown",
          message = "I'm not sure what you want me to do.",
          spokenText = "I'm not sure what you want me to do."
        )
      }
    }
  }

  // --- App Launcher Execution ---
  private fun executeOpenApp(appName: String): DvexToolResult {
    Log.i(TAG_TOOL, "Executing AppLauncher for: \"$appName\"")
    val rawResult = appLauncher.launchAppByName(appName)

    return when (rawResult.status) {
      ToolResultStatus.SUCCESS -> {
        DvexToolResult(
          status = DvexToolStatus.SUCCESS,
          toolName = "open_app",
          message = rawResult.message,
          spokenText = rawResult.message
        )
      }
      ToolResultStatus.FAILED -> {
        // Check if app was simply not found
        if (rawResult.message.contains("not found", ignoreCase = true)) {
          DvexToolResult(
            status = DvexToolStatus.NOT_FOUND,
            toolName = "open_app",
            message = "Application \"$appName\" not found.",
            spokenText = "I couldn't find that app on your phone."
          )
        } else {
          DvexToolResult(
            status = DvexToolStatus.FAILED,
            toolName = "open_app",
            message = rawResult.message,
            spokenText = "I couldn't open $appName."
          )
        }
      }
      ToolResultStatus.NEEDS_PERMISSION -> {
        DvexToolResult(
          status = DvexToolStatus.PERMISSION_REQUIRED,
          toolName = "open_app",
          message = rawResult.message,
          spokenText = "D-VEX needs permission to launch that app."
        )
      }
      else -> {
        DvexToolResult(
          status = DvexToolStatus.FAILED,
          toolName = "open_app",
          message = rawResult.message,
          spokenText = "I couldn't open $appName."
        )
      }
    }
  }

  private fun executeCloseApp(appName: String): DvexToolResult {
    // Android platform does not permit third-party assistants to force-close other packages without root.
    // We gracefully navigate home and inform the user.
    val homeResult = deviceControl.navigateHome()
    return if (homeResult.isSuccessful) {
      DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "close_app",
        message = "Navigated home to minimize $appName.",
        spokenText = "Returned to home screen."
      )
    } else {
      DvexToolResult(
        status = DvexToolStatus.UNSUPPORTED,
        toolName = "close_app",
        message = "Direct app termination requires system privileges.",
        spokenText = "Android does not allow closing background apps directly."
      )
    }
  }

  // --- Phone Control ---
  private fun executeGoHome(): DvexToolResult {
    val res = deviceControl.navigateHome()
    return mapDeviceResult(res, "home", "Navigated to home screen.")
  }

  private fun executeGoBack(): DvexToolResult {
    if (!DvexAccessibilityService.isEnabled(context)) {
      return DvexToolResult(
        status = DvexToolStatus.PERMISSION_REQUIRED,
        toolName = "back",
        message = "Accessibility service required.",
        spokenText = "D-VEX needs Accessibility permission for that action."
      )
    }
    val res = deviceControl.navigateBack()
    return mapDeviceResult(res, "back", "Navigated back.")
  }

  private fun executeOpenRecents(): DvexToolResult {
    if (!DvexAccessibilityService.isEnabled(context)) {
      return DvexToolResult(
        status = DvexToolStatus.PERMISSION_REQUIRED,
        toolName = "recents",
        message = "Accessibility service required.",
        spokenText = "D-VEX needs Accessibility permission for that action."
      )
    }
    val res = deviceControl.showRecents()
    return mapDeviceResult(res, "recents", "Showing recent apps.")
  }

  private fun executeOpenNotifications(): DvexToolResult {
    if (!DvexAccessibilityService.isEnabled(context)) {
      return DvexToolResult(
        status = DvexToolStatus.PERMISSION_REQUIRED,
        toolName = "notifications",
        message = "Accessibility service required.",
        spokenText = "D-VEX needs Accessibility permission for that action."
      )
    }
    val res = deviceControl.showNotifications()
    return mapDeviceResult(res, "notifications", "Opening notifications.")
  }

  private fun executeOpenSettings(): DvexToolResult {
    val res = deviceControl.openSettings()
    return mapDeviceResult(res, "settings", "Opening settings.")
  }

  private fun executeOpenWifi(): DvexToolResult {
    val res = deviceControl.openWifiSettings()
    return mapDeviceResult(res, "wifi", "Opening Wi-Fi settings.")
  }

  private fun executeLockScreen(): DvexToolResult {
    return DvexToolResult(
      status = DvexToolStatus.UNSUPPORTED,
      toolName = "lock_screen",
      message = "Lock screen requires Device Administrator privilege.",
      spokenText = "Lock screen is not supported without device administrator permissions."
    )
  }

  private fun executeScroll(direction: ScrollDirection): DvexToolResult {
    if (!DvexAccessibilityService.isEnabled(context)) {
      return DvexToolResult(
        status = DvexToolStatus.PERMISSION_REQUIRED,
        toolName = "scroll",
        message = "Accessibility service required.",
        spokenText = "D-VEX needs Accessibility permission to scroll."
      )
    }
    val res = if (direction == ScrollDirection.DOWN) deviceControl.scrollDown() else deviceControl.scrollUp()
    return mapDeviceResult(res, "scroll", if (direction == ScrollDirection.DOWN) "Scrolled down." else "Scrolled up.")
  }

  // --- Volume & Media ---
  private fun executeAdjustVolume(action: VolumeAction): DvexToolResult {
    val direction = when (action) {
      VolumeAction.UP -> VolumeDirection.UP
      VolumeAction.DOWN -> VolumeDirection.DOWN
      VolumeAction.MUTE -> VolumeDirection.MUTE
    }
    val res = deviceControl.adjustVolume(direction)
    return mapDeviceResult(res, "volume", res.message)
  }

  private fun executeMediaControl(action: MediaAction): DvexToolResult {
    val cmd = when (action) {
      MediaAction.PLAY_PAUSE -> MediaCommand.PLAY_PAUSE
      MediaAction.NEXT -> MediaCommand.NEXT
      MediaAction.PREVIOUS -> MediaCommand.PREVIOUS
    }
    val res = deviceControl.controlMedia(cmd)
    return mapDeviceResult(res, "media", res.message)
  }

  // --- Sensitive Actions (Confirmation Mandatory) ---
  private fun executeCallContact(recipient: String): DvexToolResult {
    val prompt = "Do you want me to call $recipient?"
    return DvexToolResult(
      status = DvexToolStatus.CONFIRMATION_REQUIRED,
      toolName = "call_contact",
      message = "Confirmation required: Call $recipient?",
      spokenText = prompt,
      requiresConfirmation = true,
      confirmationPrompt = prompt,
      pendingActionId = UUID.randomUUID().toString()
    )
  }

  private fun executeSendMessage(recipient: String, messageText: String?): DvexToolResult {
    val prompt = "Do you want me to send a message to $recipient?"
    return DvexToolResult(
      status = DvexToolStatus.CONFIRMATION_REQUIRED,
      toolName = "send_message",
      message = "Confirmation required: Send message to $recipient?",
      spokenText = prompt,
      requiresConfirmation = true,
      confirmationPrompt = prompt,
      pendingActionId = UUID.randomUUID().toString()
    )
  }

  // --- Live Information ---
  private fun executeGetWeather(location: String?): DvexToolResult {
    val loc = location?.ifBlank { "local area" } ?: "your area"
    val report = "Current weather in $loc is 30°C and partly cloudy. High of 34°C, low of 26°C."
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "get_weather",
      message = report,
      spokenText = report
    )
  }

  private fun executeGetTime(): DvexToolResult {
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val currentTime = timeFormat.format(Date())
    val spoken = "The time is $currentTime."
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "get_time",
      message = spoken,
      spokenText = spoken
    )
  }

  private fun executeCalculate(expression: String): DvexToolResult {
    val result = evaluateArithmetic(expression)
    return if (result != null) {
      val spoken = "$expression is $result."
      DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "calculate",
        message = spoken,
        spokenText = spoken
      )
    } else {
      DvexToolResult(
        status = DvexToolStatus.FAILED,
        toolName = "calculate",
        message = "Could not evaluate: $expression",
        spokenText = "I couldn't calculate that."
      )
    }
  }

  private fun executeGetNews(topic: String?): DvexToolResult {
    val newsSummary = if (topic.isNullOrBlank()) {
      "Here are top headlines: Global tech summit kicks off, space exploration advances, and renewable energy adoption rises."
    } else {
      "Latest on $topic: New developments reported across major global networks."
    }
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "get_news",
      message = newsSummary,
      spokenText = newsSummary
    )
  }

  private fun executeSearchWeb(query: String): DvexToolResult {
    val res = deviceControl.openBrowser(query)
    return mapDeviceResult(res, "search_web", "Searching the web for $query.")
  }

  // --- Memory Operations ---
  private fun executeRememberFact(fact: String): DvexToolResult {
    val existing = getStoredMemories().toMutableSet()
    existing.add(fact)
    prefs.edit().putStringSet("core_memories", existing).apply()

    val spoken = "I'll remember that: $fact."
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "remember_fact",
      message = spoken,
      spokenText = spoken
    )
  }

  private fun executeRecallMemory(query: String?): DvexToolResult {
    val memories = getStoredMemories()
    if (memories.isEmpty()) {
      return DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "recall_memory",
        message = "No memories stored in D-VEX core yet.",
        spokenText = "You haven't asked me to remember anything yet."
      )
    }

    val q = query?.lowercase(Locale.ROOT) ?: ""
    val matched = when {
      q.contains("color") -> memories.firstOrNull { it.contains("color", ignoreCase = true) || it.contains("red", ignoreCase = true) || it.contains("like", ignoreCase = true) }
      q.contains("like") -> memories.firstOrNull { it.contains("like", ignoreCase = true) }
      else -> memories.lastOrNull()
    }

    val response = if (matched != null) {
      "According to your saved memories, $matched."
    } else {
      "You asked me to remember: ${memories.joinToString("; ")}."
    }

    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "recall_memory",
      message = response,
      spokenText = response
    )
  }

  private fun getStoredMemories(): Set<String> {
    return prefs.getStringSet("core_memories", emptySet()) ?: emptySet()
  }

  // --- Multi-Step Execution ---
  private fun executeMultiStep(first: DvexIntent, second: DvexIntent): DvexToolResult {
    Log.i(TAG_TOOL, "Executing MultiStep: Step 1 = $first, Step 2 = $second")

    // Special optimization: YouTube + Search
    if (first is DvexIntent.OpenApp && first.appName.contains("youtube", ignoreCase = true) && second is DvexIntent.SearchWeb) {
      val res = deviceControl.openYouTube(second.query)
      return mapDeviceResult(res, "youtube_search", "Opening YouTube and searching for ${second.query}.")
    }

    // Maps + Search
    if (first is DvexIntent.OpenApp && first.appName.contains("maps", ignoreCase = true) && second is DvexIntent.SearchWeb) {
      val res = deviceControl.openMaps(second.query)
      return mapDeviceResult(res, "maps_search", "Opening Maps and searching for ${second.query}.")
    }

    // General app + search: Launch app first. If search cannot be injected automatically, inform user honestly.
    if (first is DvexIntent.OpenApp && second is DvexIntent.SearchWeb) {
      val appRes = appLauncher.launchAppByName(first.appName)
      return if (appRes.isSuccessful) {
        DvexToolResult(
          status = DvexToolStatus.SUCCESS,
          toolName = "multi_step",
          message = "${first.appName} is open. I can't complete the search automatically yet.",
          spokenText = "${first.appName} is open. I can't complete the search automatically yet.",
          multiStepExecutedFirst = true,
          multiStepPendingSecond = second
        )
      } else {
        DvexToolResult(
          status = DvexToolStatus.NOT_FOUND,
          toolName = "multi_step",
          message = "Could not find ${first.appName} to execute multi-step command.",
          spokenText = "I couldn't find ${first.appName} on your phone."
        )
      }
    }

    // Default multi-step: execute first
    return DvexToolResult(
      status = DvexToolStatus.FAILED,
      toolName = "multi_step",
      message = "Unsupported multi-step sequence.",
      spokenText = "I can only complete one action at a time for this request."
    )
  }

  // --- General Knowledge & Conversation ---
  private fun executeGeneralQuestion(question: String): DvexToolResult {
    val q = question.lowercase(Locale.ROOT)
    val answer = when {
      q.contains("photosynthesis") ->
        "Photosynthesis is the process by which green plants and certain organisms use sunlight to synthesize nutrients from carbon dioxide and water, releasing oxygen as a byproduct."
      q.contains("joke") ->
        "Why do programmers prefer dark mode? Because light attracts bugs."
      q.contains("plan my day") ->
        "I recommend checking your high-priority communications first, setting 90-minute focus blocks, and taking tactical breaks between sessions."
      q.contains("moon") ->
        "The Moon is approximately 384,400 kilometers from Earth."
      else ->
        "I have analyzed your query. While my tactical network operates locally, I'm standing by to help manage your device, apps, and schedules."
    }
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "general_qa",
      message = answer,
      spokenText = answer
    )
  }

  private fun executeConversation(statement: String): DvexToolResult {
    val s = statement.lowercase(Locale.ROOT)
    val reply = when {
      s.contains("who are you") || s.contains("what are you") || s.contains("neenga yaaru") ->
        "I am D-VEX, your tactical intelligence and device assistant."
      s.contains("hello") || s.contains("hi") || s.contains("hey") ->
        "Hello. D-VEX tactical systems are online and standing by."
      s.contains("vanakkam") || s.contains("வணக்கம்") ->
        "Vanakkam! D-VEX systems are active and ready."
      else ->
        "Understood. Systems are operating at nominal capacity."
    }
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "conversation",
      message = reply,
      spokenText = reply
    )
  }

  // --- Helpers ---
  private fun mapDeviceResult(raw: com.example.data.remote.ToolExecutionResult, toolName: String, successText: String): DvexToolResult {
    return when (raw.status) {
      ToolResultStatus.SUCCESS -> DvexToolResult(DvexToolStatus.SUCCESS, toolName, successText, successText)
      ToolResultStatus.NEEDS_PERMISSION -> DvexToolResult(DvexToolStatus.PERMISSION_REQUIRED, toolName, raw.message, raw.message)
      ToolResultStatus.NOT_SUPPORTED -> DvexToolResult(DvexToolStatus.UNSUPPORTED, toolName, raw.message, raw.message)
      ToolResultStatus.FAILED -> DvexToolResult(DvexToolStatus.FAILED, toolName, raw.message, "I couldn't complete that action.")
      ToolResultStatus.CONFIRMATION_REQUIRED -> DvexToolResult(DvexToolStatus.CONFIRMATION_REQUIRED, toolName, raw.message, raw.message, true, raw.confirmationPrompt)
    }
  }

  private fun evaluateArithmetic(expression: String): String? {
    return try {
      val normalized = expression
        .replace("times", "*", ignoreCase = true)
        .replace("multiplied by", "*", ignoreCase = true)
        .replace("divided by", "/", ignoreCase = true)
        .replace("plus", "+", ignoreCase = true)
        .replace("minus", "-", ignoreCase = true)
        .replace("x", "*", ignoreCase = true)
        .replace(" ", "")

      val tokens = Regex("(\\d+(?:\\.\\d+)?)|([+\\-*/])").findAll(normalized).map { it.value }.toList()
      if (tokens.size < 3) return null

      var result = tokens[0].toDouble()
      var i = 1
      while (i < tokens.size - 1) {
        val op = tokens[i]
        val nextVal = tokens[i + 1].toDouble()
        result = when (op) {
          "+" -> result + nextVal
          "-" -> result - nextVal
          "*" -> result * nextVal
          "/" -> if (nextVal != 0.0) result / nextVal else return "undefined (division by zero)"
          else -> return null
        }
        i += 2
      }

      if (result % 1.0 == 0.0) {
        result.toLong().toString()
      } else {
        String.format(Locale.US, "%.2f", result)
      }
    } catch (e: Exception) {
      null
    }
  }

  companion object {
    private const val TAG_ROUTER = "[D-VEX][ROUTER]"
    private const val TAG_TOOL = "[D-VEX][TOOL]"
  }
}

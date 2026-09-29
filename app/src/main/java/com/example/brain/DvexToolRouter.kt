package com.example.brain

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.example.control.AppAction
import com.example.control.AppActionResult
import com.example.control.AppControlAgent
import com.example.control.AppLauncherRepository
import com.example.control.ContactResolver
import com.example.control.ContactSearchResult
import com.example.control.DeviceControlRepository
import com.example.control.DvexAccessibilityService
import com.example.control.LocationProvider
import com.example.control.RecentAppsProvider
import com.example.control.ResolvedContact
import com.example.data.remote.MediaCommand
import com.example.mode.PowerModeManager
import com.example.data.remote.RealTimeWebService
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
  private val contactResolver = ContactResolver(context)
  private val realTimeWeb = RealTimeWebService()
  private val appControlAgent = AppControlAgent(context, appLauncher, contactResolver)

  /**
   * Main dispatch entry point for DvexIntent.
   */
  suspend fun execute(intent: DvexIntent, conversationContext: ConversationContext? = null): DvexToolResult {
    Log.i(TAG_ROUTER, "Routing intent: $intent")

    return when (intent) {
      // --- Wake Greeting ---
      is DvexIntent.WakeGreeting -> executeWakeGreeting()

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

      // Device control layer: real system settings screens (the agent layer normally
      // owns this intent; the branch keeps the router exhaustive and single-sourced).
      is DvexIntent.OpenSystemSettings ->
        mapDeviceResult(
          deviceControl.openSystemSettings(intent.kind),
          "open_settings",
          "Opening ${intent.kind.displayName}."
        )

      // --- Hardware Tools ---
      is DvexIntent.ToggleFlashlight -> executeToggleFlashlight(intent.enable)
      is DvexIntent.SetAlarm -> executeSetAlarm(intent.hour, intent.minute, intent.message)
      is DvexIntent.SetTimer -> executeSetTimer(intent.seconds, intent.message)
      is DvexIntent.PlayYoutubeVideo -> executePlayYoutubeVideo(intent.query)

      // --- Communication (Sensitive Voice Confirmation) ---
      is DvexIntent.CallContact -> executeCallContact(intent.recipient)
      is DvexIntent.SendMessage -> executeSendMessage(intent.recipient, intent.messageText, intent.isWhatsApp)
      is DvexIntent.SendEmail -> executeSendEmail(intent.recipient, intent.subject, intent.body)

      // --- Live Information ---
      is DvexIntent.GetWeather -> executeGetWeather(intent.location, intent.isTomorrow)
      is DvexIntent.GetTime -> executeGetTime()
      is DvexIntent.Calculate -> executeCalculate(intent.expression)
      is DvexIntent.GetNews -> executeGetNews(intent.topic)
      is DvexIntent.SearchWeb -> executeSearchWeb(intent.query)
      is DvexIntent.GetRecentApps -> executeGetRecentApps()

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
      // Unrecognized input is NOT a failure and must not consume a canned reply.
      // Input that matched no deterministic action is still natural language, so it
      // is routed to the LLM conversation path, which answers it (or says plainly
      // that it can't) in the user's own language. Only a genuine runtime failure
      // reaches the emergency fallback, which lives in DvexResponseGenerator.
      is DvexIntent.Unknown -> DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "conversation",
        // Internal routing metadata only — never spoken or displayed.
        message = "Unrecognized input routed to the LLM conversation path.",
        spokenText = ""
      )
    }
  }

  // --- Wake Greeting ---
  private fun executeWakeGreeting(): DvexToolResult {
    val greeting = "Yes? I'm listening."
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "wake_greeting",
      message = greeting,
      spokenText = greeting
    )
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

  // --- Hardware Tools ---
  private fun executeToggleFlashlight(enable: Boolean?): DvexToolResult {
    val res = deviceControl.toggleFlashlight(enable)
    val text = if (res.status == ToolResultStatus.SUCCESS) {
      if (enable == false) "Flashlight turned off." else "Flashlight turned on."
    } else {
      "I couldn't control the flashlight."
    }
    return mapDeviceResult(res, "flashlight", text)
  }

  private fun executeSetAlarm(hour: Int?, minute: Int?, message: String?): DvexToolResult {
    // Phase A honesty contract: never guess a time. If no time was parsed from the
    // utterance, ask the user to repeat with a clear time instead of setting one.
    if (hour == null || minute == null) {
      val msg = "I couldn't tell the exact time for the alarm. Please repeat with a clear time, " +
        "for example 'set an alarm for 6:30 am'."
      Log.w(TAG_TOOL, "SetAlarm without a parseable time; asking user to repeat (no guess)")
      return DvexToolResult(
        status = DvexToolStatus.FAILED,
        toolName = "set_alarm",
        message = msg,
        spokenText = msg
      )
    }

    // EXTRA_SKIP_UI is deliberately false: the system Clock app's own UI stays
    // visible so the user sees and confirms the alarm — never silently auto-set.
    val res = deviceControl.setAlarm(hour, minute, message)
    val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
    return mapDeviceResult(res, "set_alarm", "Alarm set for $timeFormatted. Please confirm it in the Clock app.")
  }

  /**
   * Phase A: YouTube video search (system intents, no accessibility).
   * Opens YouTube's own search results for the query — it does NOT auto-play an
   * unverified "first result", since which video the user meant cannot be known.
   * Prefers the YouTube app package when installed; falls back to any browser.
   */
  private fun executePlayYoutubeVideo(query: String): DvexToolResult {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) {
      val msg = "What should I search on YouTube?"
      return DvexToolResult(
        status = DvexToolStatus.FAILED,
        toolName = "youtube_play",
        message = msg,
        spokenText = msg
      )
    }

    val searchUrl = "https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // Prefer the dedicated YouTube app package when it is installed.
    val ytPackage = "com.google.android.youtube"
    val ytInstalled = try {
      context.packageManager.getPackageInfo(ytPackage, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    } catch (_: Exception) {
      false
    }
    if (ytInstalled) {
      intent.setPackage(ytPackage)
    }

    val canResolve = try {
      intent.resolveActivity(context.packageManager) != null
    } catch (_: Exception) {
      false
    }
    if (!canResolve) {
      val msg = "No app or browser can open YouTube right now."
      Log.w(TAG_TOOL, "YouTube play: no resolver for ACTION_VIEW ($searchUrl)")
      return DvexToolResult(
        status = DvexToolStatus.UNSUPPORTED,
        toolName = "youtube_play",
        message = msg,
        spokenText = msg
      )
    }

    return try {
      context.startActivity(intent)
      Log.i(TAG_TOOL, "YouTube play: dispatched search results for \"$cleanQuery\" (app-preferred=$ytInstalled)")
      DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "youtube_play",
        message = "Opened YouTube search results for \"$cleanQuery\".",
        spokenText = "Opening YouTube search for $cleanQuery. Pick the video you want."
      )
    } catch (e: Exception) {
      Log.e(TAG_TOOL, "YouTube play dispatch failed", e)
      DvexToolResult(
        status = DvexToolStatus.FAILED,
        toolName = "youtube_play",
        message = "Couldn't open YouTube search.",
        spokenText = "Couldn't open YouTube search."
      )
    }
  }

  private fun executeSetTimer(seconds: Int, message: String?): DvexToolResult {
    val res = deviceControl.setTimer(seconds, message)
    val minutes = seconds / 60
    val label = if (minutes > 0) "$minutes minutes" else "$seconds seconds"
    return mapDeviceResult(res, "set_timer", "Timer set for $label.")
  }

  // --- Sensitive Actions (Voice Confirmation Mandatory) ---
  private fun executeCallContact(recipient: String): DvexToolResult {
    val searchResult = contactResolver.findContact(recipient)
    return when (searchResult) {
      is ContactSearchResult.PermissionDenied -> {
        val msg = "Contacts permission thevai."
        DvexToolResult(
          status = DvexToolStatus.PERMISSION_REQUIRED,
          toolName = "call_contact",
          message = msg,
          spokenText = msg
        )
      }
      is ContactSearchResult.NotFound -> {
        val msg = "${searchResult.cleanQuery} contact கிடைக்கல."
        DvexToolResult(
          status = DvexToolStatus.NOT_FOUND,
          toolName = "call_contact",
          message = msg,
          spokenText = msg
        )
      }
      is ContactSearchResult.Multiple -> {
        val names = searchResult.matches.map { it.name }.distinct()
        val msg = "${searchResult.cleanQuery}-la multiple contacts irukku: ${names.joinToString(", ")}. Which one should I call?"
        DvexToolResult(
          status = DvexToolStatus.SUCCESS,
          toolName = "call_contact",
          message = msg,
          spokenText = msg
        )
      }
      is ContactSearchResult.Single -> {
        val contact = searchResult.contact
        val prompt = "Okay. ${contact.name}-ku call pannattuma?"
        DvexToolResult(
          status = DvexToolStatus.CONFIRMATION_REQUIRED,
          toolName = "call_contact",
          message = "Confirmation required: Call ${contact.name} (${contact.phoneNumber})?",
          spokenText = prompt,
          requiresConfirmation = true,
          confirmationPrompt = prompt,
          pendingActionId = UUID.randomUUID().toString()
        )
      }
    }
  }

  private suspend fun executeSendMessage(
    recipient: String,
    messageText: String?,
    isWhatsApp: Boolean = false
  ): DvexToolResult {
    val searchResult = contactResolver.findContact(recipient)
    val contact = when (searchResult) {
      is ContactSearchResult.PermissionDenied -> {
        val msg = "Contacts permission thevai."
        return DvexToolResult(
          status = DvexToolStatus.PERMISSION_REQUIRED,
          toolName = if (isWhatsApp) "whatsapp" else "send_message",
          message = msg,
          spokenText = msg
        )
      }
      is ContactSearchResult.NotFound -> {
        val msg = "${searchResult.cleanQuery} contact கிடைக்கல."
        return DvexToolResult(
          status = DvexToolStatus.NOT_FOUND,
          toolName = if (isWhatsApp) "whatsapp" else "send_message",
          message = msg,
          spokenText = msg
        )
      }
      is ContactSearchResult.Multiple -> {
        val names = searchResult.matches.map { it.name }.distinct()
        val channel = if (isWhatsApp) "WhatsApp" else "message"
        val msg = "${searchResult.cleanQuery}-la multiple contacts irukku: ${names.joinToString(", ")}. Which one should I $channel?"
        return DvexToolResult(
          status = DvexToolStatus.SUCCESS,
          toolName = if (isWhatsApp) "whatsapp" else "send_message",
          message = msg,
          spokenText = msg
        )
      }
      is ContactSearchResult.Single -> searchResult.contact
    }

    val displayName = contact.name

    if (messageText.isNullOrBlank()) {
      val askPrompt = if (isWhatsApp) {
        "Okay. $displayName-ku WhatsApp-la enna message anuppanum?"
      } else {
        "Okay. $displayName-ku enna message anuppanum?"
      }
      return DvexToolResult(
        status = DvexToolStatus.CONFIRMATION_REQUIRED,
        toolName = if (isWhatsApp) "whatsapp" else "send_message",
        message = askPrompt,
        spokenText = askPrompt,
        requiresConfirmation = true,
        confirmationPrompt = askPrompt,
        pendingActionId = UUID.randomUUID().toString()
      )
    }

    // =========================================================================
    // DUAL MODE SYSTEM: SEND_MESSAGE Power Mode gate (single decision point).
    // - Power Mode OFF (Standard Mode): the existing confirmation flow below runs
    //   EXACTLY as before — behaviour is byte-for-byte unchanged.
    // - Power Mode ON: skip ONLY the confirmation prompt and execute the message
    //   through the EXISTING verified execution path (executeConfirmed), i.e. the
    //   same accessibility automation with verified result, or the same
    //   SmsManager/WhatsApp fallback. No second messaging implementation.
    // Contact resolution and message composition above are shared by both modes.
    // CALL_CONTACT is NOT touched here and NEVER bypasses confirmation in any mode.
    // =========================================================================
    if (PowerModeManager.getInstance(context).isPowerModeEnabled()) {
      Log.i(TAG_ROUTER, "Power Mode ON: auto-executing SEND_MESSAGE (confirmation bypassed) for: $displayName")
      return executeConfirmed(DvexIntent.SendMessage(recipient = displayName, messageText = messageText, isWhatsApp = isWhatsApp))
    }

    // If accessibility service is active, open conversation and enter the message now
    if (DvexAccessibilityService.isEnabled(context)) {
      val targetApp = if (isWhatsApp) "whatsapp" else "sms"
      val prepResult = appControlAgent.prepareMessageInApp(
        recipientQuery = displayName,
        messageText = messageText,
        targetAppOverride = targetApp
      )
      when (prepResult) {
        is AppActionResult.NeedsConfirmation -> {
          return DvexToolResult(
            status = DvexToolStatus.CONFIRMATION_REQUIRED,
            toolName = if (isWhatsApp) "whatsapp" else "send_message",
            message = "Confirmation required: Send message to $displayName: '$messageText'?",
            spokenText = prepResult.prompt,
            requiresConfirmation = true,
            confirmationPrompt = prepResult.prompt,
            pendingActionId = UUID.randomUUID().toString()
          )
        }
        is AppActionResult.PermissionRequired -> {
          return DvexToolResult(
            status = DvexToolStatus.PERMISSION_REQUIRED,
            toolName = if (isWhatsApp) "whatsapp" else "send_message",
            message = prepResult.message,
            spokenText = prepResult.message
          )
        }
        is AppActionResult.Failed -> {
          Log.w(TAG_ROUTER, "AppControlAgent prepare failed: ${prepResult.reason}, falling back to confirmation prompt")
        }
        else -> {}
      }
    }

    val confirmPrompt = if (isWhatsApp) {
      "Okay. $displayName-ku WhatsApp message anuppattuma?"
    } else {
      "Okay. $displayName-ku '$messageText' anuppattuma?"
    }

    return DvexToolResult(
      status = DvexToolStatus.CONFIRMATION_REQUIRED,
      toolName = if (isWhatsApp) "whatsapp" else "send_message",
      message = "Confirmation required: Send message to $displayName: '$messageText'?",
      spokenText = confirmPrompt,
      requiresConfirmation = true,
      confirmationPrompt = confirmPrompt,
      pendingActionId = UUID.randomUUID().toString()
    )
  }


  private fun executeSendEmail(recipient: String, subject: String?, body: String?): DvexToolResult {
    val clean = contactResolver.cleanContactQuery(recipient)
    val isDirectEmail = clean.contains("@") && clean.contains(".")
    val emailTarget: String
    val displayName: String

    if (isDirectEmail) {
      emailTarget = clean
      displayName = clean
    } else {
      val searchResult = contactResolver.findContact(clean)
      when (searchResult) {
        is ContactSearchResult.PermissionDenied -> {
          val msg = "Contacts permission thevai."
          return DvexToolResult(
            status = DvexToolStatus.PERMISSION_REQUIRED,
            toolName = "send_email",
            message = msg,
            spokenText = msg
          )
        }
        is ContactSearchResult.NotFound -> {
          val msg = "$clean contact கிடைக்கல."
          return DvexToolResult(
            status = DvexToolStatus.NOT_FOUND,
            toolName = "send_email",
            message = msg,
            spokenText = msg
          )
        }
        is ContactSearchResult.Multiple -> {
          val names = searchResult.matches.map { it.name }.distinct()
          val msg = "$clean-la multiple contacts irukku: ${names.joinToString(", ")}. Which one should I email?"
          return DvexToolResult(
            status = DvexToolStatus.SUCCESS,
            toolName = "send_email",
            message = msg,
            spokenText = msg
          )
        }
        is ContactSearchResult.Single -> {
          displayName = searchResult.contact.name
          val foundEmail = searchResult.contact.email ?: contactResolver.queryContactEmail(searchResult.contact.name)
          if (foundEmail.isNullOrBlank()) {
            val msg = "$displayName email address கிடைக்கல."
            return DvexToolResult(
              status = DvexToolStatus.NOT_FOUND,
              toolName = "send_email",
              message = msg,
              spokenText = msg
            )
          }
          emailTarget = foundEmail
        }
      }
    }

    if (body.isNullOrBlank() && subject.isNullOrBlank()) {
      val askPrompt = "Okay. What should the email to $displayName say?"
      return DvexToolResult(
        status = DvexToolStatus.CONFIRMATION_REQUIRED,
        toolName = "send_email",
        message = askPrompt,
        spokenText = askPrompt,
        requiresConfirmation = true,
        confirmationPrompt = askPrompt,
        pendingActionId = UUID.randomUUID().toString()
      )
    }

    val confirmPrompt = "Okay. $displayName-ku email anuppattuma?"
    return DvexToolResult(
      status = DvexToolStatus.CONFIRMATION_REQUIRED,
      toolName = "send_email",
      message = "Confirmation required: Send email to $displayName ($emailTarget)?",
      spokenText = confirmPrompt,
      requiresConfirmation = true,
      confirmationPrompt = confirmPrompt,
      pendingActionId = UUID.randomUUID().toString()
    )
  }

  suspend fun executeConfirmed(intent: DvexIntent): DvexToolResult {
    return when (intent) {
      is DvexIntent.CallContact -> {
        Log.i(TAG_ROUTER, "Executing confirmed phone call for: ${intent.recipient}")
        val search = contactResolver.findContact(intent.recipient)
        if (search !is ContactSearchResult.Single) {
          val clean = contactResolver.cleanContactQuery(intent.recipient)
          val msg = "$clean contact கிடைக்கல."
          return DvexToolResult(
            status = DvexToolStatus.NOT_FOUND,
            toolName = "call_contact",
            message = msg,
            spokenText = msg
          )
        }
        val contact = search.contact
        val res = deviceControl.makePhoneCall(contact.phoneNumber, contact.name)
        val status = when (res.status) {
          ToolResultStatus.SUCCESS -> DvexToolStatus.SUCCESS
          ToolResultStatus.NEEDS_PERMISSION -> DvexToolStatus.PERMISSION_REQUIRED
          else -> DvexToolStatus.FAILED
        }
        DvexToolResult(
          status = status,
          toolName = "call_contact",
          message = res.message,
          spokenText = res.message
        )
      }
      is DvexIntent.SendMessage -> {
        Log.i(TAG_ROUTER, "Executing confirmed message for: ${intent.recipient}, isWhatsApp=${intent.isWhatsApp}")
        val cleanRecipient = contactResolver.cleanContactQuery(intent.recipient)
        val isWhatsApp = intent.isWhatsApp || cleanRecipient.contains("whatsapp", ignoreCase = true)
        val contactQuery = cleanRecipient.replace("whatsapp", "", ignoreCase = true).trim()

        val search = contactResolver.findContact(contactQuery.ifBlank { cleanRecipient })
        if (search !is ContactSearchResult.Single) {
          val msg = "$contactQuery contact கிடைக்கல."
          return DvexToolResult(
            status = DvexToolStatus.NOT_FOUND,
            toolName = if (isWhatsApp) "whatsapp" else "send_message",
            message = msg,
            spokenText = msg
          )
        }
        val contact = search.contact
        val body = intent.messageText.orEmpty()

        // 1. First priority: UI Automation via Accessibility if service is enabled
        if (DvexAccessibilityService.isEnabled(context)) {
          val appResult = appControlAgent.executeSendAndVerify(contact.name, body)
          when (appResult) {
            is AppActionResult.Success -> {
              return DvexToolResult(
                status = DvexToolStatus.SUCCESS,
                toolName = if (isWhatsApp) "whatsapp" else "send_message",
                message = appResult.message,
                spokenText = appResult.message
              )
            }
            is AppActionResult.PermissionRequired -> {
              return DvexToolResult(
                status = DvexToolStatus.PERMISSION_REQUIRED,
                toolName = if (isWhatsApp) "whatsapp" else "send_message",
                message = appResult.message,
                spokenText = appResult.message
              )
            }
            is AppActionResult.Failed -> {
              Log.w(TAG_ROUTER, "AppControlAgent UI automation failed: ${appResult.reason}, checking direct fallback")
              // If not WhatsApp, fallback to direct SmsManager sending
              if (!isWhatsApp) {
                val res = deviceControl.sendSmsDirect(contact.phoneNumber, body, contact.name)
                val status = when (res.status) {
                  ToolResultStatus.SUCCESS -> DvexToolStatus.SUCCESS
                  ToolResultStatus.NEEDS_PERMISSION -> DvexToolStatus.PERMISSION_REQUIRED
                  else -> DvexToolStatus.FAILED
                }
                return DvexToolResult(
                  status = status,
                  toolName = "send_message",
                  message = res.message,
                  spokenText = res.message
                )
              }
              return DvexToolResult(
                status = DvexToolStatus.FAILED,
                toolName = "whatsapp",
                message = appResult.reason,
                spokenText = appResult.reason
              )
            }
            else -> {}
          }
        }

        // 2. Direct fallback if Accessibility service is not running
        if (isWhatsApp) {
          val res = deviceControl.sendWhatsApp(contact.phoneNumber, body, contact.name)
          DvexToolResult(
            status = if (res.status == ToolResultStatus.SUCCESS) DvexToolStatus.SUCCESS else DvexToolStatus.FAILED,
            toolName = "whatsapp",
            message = res.message,
            spokenText = res.message
          )
        } else {
          val res = deviceControl.sendSmsDirect(contact.phoneNumber, body, contact.name)
          val status = when (res.status) {
            ToolResultStatus.SUCCESS -> DvexToolStatus.SUCCESS
            ToolResultStatus.NEEDS_PERMISSION -> DvexToolStatus.PERMISSION_REQUIRED
            else -> DvexToolStatus.FAILED
          }
          DvexToolResult(
            status = status,
            toolName = "send_message",
            message = res.message,
            spokenText = res.message
          )
        }
      }

      is DvexIntent.SendEmail -> {
        Log.i(TAG_ROUTER, "Executing confirmed email for: ${intent.recipient}")
        val res = deviceControl.sendEmail(intent.recipient, intent.subject, intent.body)
        val status = if (res.status == ToolResultStatus.SUCCESS) DvexToolStatus.SUCCESS else DvexToolStatus.FAILED
        DvexToolResult(
          status = status,
          toolName = "send_email",
          message = res.message,
          spokenText = res.message
        )
      }
      else -> {
        DvexToolResult(
          status = DvexToolStatus.FAILED,
          toolName = "unknown",
          message = "Unknown confirmed action.",
          spokenText = "I couldn't complete that action."
        )
      }
    }
  }

  // --- Live Information ---
  /**
   * Weather with REAL device location (Bug 4 fix).
   * - City named in the utterance -> geocode that city (unchanged behavior).
   * - No city named -> real GPS/network fix via LocationProvider, reverse-geocoded.
   * - Permission denied -> PERMISSION_REQUIRED, suggests saying a city name.
   * - Fix failure/timeout -> honest ERROR, never a fallback city.
   */
  private suspend fun executeGetWeather(location: String?, isTomorrow: Boolean = false): DvexToolResult {
    val namedCity = location?.trim()?.takeIf { it.isNotBlank() }

    if (namedCity != null) {
      return fetchWeatherFor(namedCity, isTomorrow)
    }

    // No city spoken: resolve the real device location.
    val locationProvider = LocationProvider(context)
    if (!locationProvider.hasPermission()) {
      val msg = "I need location permission to check the weather where you are. " +
        "You can also tell me a city name instead."
      Log.w(TAG_ROUTER, "Weather without city: location permission denied")
      return DvexToolResult(
        status = DvexToolStatus.PERMISSION_REQUIRED,
        toolName = "get_weather",
        message = msg,
        spokenText = msg
      )
    }

    if (!locationProvider.areProvidersEnabled()) {
      val msg = "Location services are turned off. Please enable GPS, or tell me a city name."
      return DvexToolResult(
        status = DvexToolStatus.ERROR,
        toolName = "get_weather",
        message = msg,
        spokenText = msg
      )
    }

    val deviceLocation = locationProvider.getLocationResult().fold(
      onSuccess = { it },
      onFailure = { error ->
        val msg = when (error) {
          is LocationProvider.LocationError.Timeout ->
            "I couldn't get your location in time. Tell me a city name and I'll check right away."
          is LocationProvider.LocationError.ProvidersOff ->
            "Location services are turned off. Please enable GPS, or tell me a city name."
          is LocationProvider.LocationError.PermissionDenied ->
            "I need location permission to check the weather where you are. You can also tell me a city name instead."
          is LocationProvider.LocationError.Failed ->
            "I couldn't determine your location. Tell me a city name and I'll check the weather there."
          else ->
            "I couldn't determine your location. Tell me a city name and I'll check the weather there."
        }
        Log.w(TAG_ROUTER, "Weather without city: location failed ($error)")
        null
      }
    )

    if (deviceLocation == null) {
      return DvexToolResult(
        status = DvexToolStatus.ERROR,
        toolName = "get_weather",
        message = "Could not determine device location.",
        spokenText = "I couldn't determine your location. Tell me a city name and I'll check the weather there."
      )
    }

    val liveWeather = realTimeWeb.fetchRealWeatherAt(
      latitude = deviceLocation.latitude,
      longitude = deviceLocation.longitude,
      placeName = deviceLocation.cityName,
      isTomorrow = isTomorrow
    )
    return if (!liveWeather.isNullOrBlank()) {
      DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "get_weather",
        message = liveWeather,
        spokenText = liveWeather
      )
    } else {
      val msg = "I couldn't fetch live weather for your area right now. Please check your internet connection."
      DvexToolResult(
        status = DvexToolStatus.FAILED,
        toolName = "get_weather",
        message = msg,
        spokenText = msg
      )
    }
  }

  /**
   * Real recently-used apps via UsageStatsManager (Bug 5). Returns the actual
   * usage list when "Usage access" is granted, or an honest PERMISSION_REQUIRED
   * telling the user to enable it — never fake/demo app names.
   */
  private fun executeGetRecentApps(): DvexToolResult {
    val provider = RecentAppsProvider(context)
    val result = provider.getRecentApps()

    return result.fold(
      onSuccess = { apps ->
        if (apps.isEmpty()) {
          // Granted but genuinely no usage in the window: honest empty report.
          val msg = "No app usage recorded in the last few hours."
          DvexToolResult(
            status = DvexToolStatus.SUCCESS,
            toolName = "get_recent_apps",
            message = msg,
            spokenText = msg
          )
        } else {
          val top = apps.take(5)
          val listText = top.joinToString(separator = ", ") { it.appName }
          val whenText = formatRelativeTime(apps.first().lastTimeUsed)
          val spoken = "Your recently used apps are $listText. " +
            "${top.first().appName} was last used $whenText."
          DvexToolResult(
            status = DvexToolStatus.SUCCESS,
            toolName = "get_recent_apps",
            message = top.joinToString("\n") { "${it.appName} (${it.packageName}) — ${formatRelativeTime(it.lastTimeUsed)}" },
            spokenText = spoken
          )
        }
      },
      onFailure = { error ->
        when (error) {
          is RecentAppsProvider.UsageAccessError.PermissionNotGranted -> {
            val msg = "I need Usage access to see your recently used apps. " +
              "Enable D-VEX in Settings under Usage access, and ask me again."
            DvexToolResult(
              status = DvexToolStatus.PERMISSION_REQUIRED,
              toolName = "get_recent_apps",
              message = msg,
              spokenText = msg
            )
          }
          is RecentAppsProvider.UsageAccessError.Unsupported -> {
            val msg = "This device doesn't report app usage statistics."
            DvexToolResult(
              status = DvexToolStatus.UNSUPPORTED,
              toolName = "get_recent_apps",
              message = msg,
              spokenText = msg
            )
          }
          else -> {
            val msg = "I couldn't read your app usage right now."
            DvexToolResult(
              status = DvexToolStatus.ERROR,
              toolName = "get_recent_apps",
              message = msg,
              spokenText = msg
            )
          }
        }
      }
    )
  }

  /** Human-readable relative time for usage timestamps. */
  private fun formatRelativeTime(timeMs: Long): String {
    val diff = System.currentTimeMillis() - timeMs
    val minutes = diff / 60_000
    val hours = minutes / 60
    return when {
      minutes < 1 -> "just now"
      minutes < 60 -> "${minutes} minute${if (minutes == 1L) "" else "s"} ago"
      hours < 24 -> "${hours} hour${if (hours == 1L) "" else "s"} ago"
      else -> "${minutes / 1440} days ago"
    }
  }

  /** Weather for an explicitly named city (geocoded server-side by Open-Meteo). */
  private suspend fun fetchWeatherFor(city: String, isTomorrow: Boolean): DvexToolResult {
    val liveWeather = realTimeWeb.fetchRealWeather(city, isTomorrow)
    return if (!liveWeather.isNullOrBlank()) {
      DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "get_weather",
        message = liveWeather,
        spokenText = liveWeather
      )
    } else {
      val msg = "I couldn't fetch live weather for $city right now. Please check your internet connection."
      DvexToolResult(
        status = DvexToolStatus.FAILED,
        toolName = "get_weather",
        message = msg,
        spokenText = msg
      )
    }
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
      val spoken = "I couldn't calculate that."
      DvexToolResult(
        status = DvexToolStatus.FAILED,
        toolName = "calculate",
        message = "Could not evaluate: $expression",
        spokenText = spoken
      )
    }
  }

  private fun executeGetNews(topic: String?): DvexToolResult {
    val q = if (!topic.isNullOrBlank()) "$topic news" else "latest news"
    deviceControl.openBrowser(q)
    val spoken = "Opening latest news headlines for you."
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "get_news",
      message = spoken,
      spokenText = spoken
    )
  }

  private suspend fun executeSearchWeb(query: String): DvexToolResult {
    // Check for instant factual answer
    val webAnswer = realTimeWeb.fetchWebAnswer(query)
    if (!webAnswer.isNullOrBlank()) {
      val spoken = "$webAnswer."
      return DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "search_web",
        message = spoken,
        spokenText = spoken
      )
    }

    // Launch web search in browser
    val res = deviceControl.openBrowser(query)
    val spoken = "Here are the web search results for $query."
    return mapDeviceResult(res, "search_web", spoken)
  }

  // --- Memory Operations ---
  private fun executeRememberFact(fact: String): DvexToolResult {
    val existing = getStoredMemories().toMutableSet()
    existing.add(fact)
    prefs.edit().putStringSet("core_memories", existing).apply()

    // No banned conversational opener ("Got it"): this is a tool result that
    // Gemini rephrases, and it must read naturally even on the emergency path.
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
      val spoken = "You haven't asked me to remember anything yet."
      return DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "recall_memory",
        message = "No memories stored in D-VEX yet.",
        spokenText = spoken
      )
    }

    val q = query?.lowercase(Locale.ROOT) ?: ""
    val matched = when {
      q.contains("color") -> memories.firstOrNull { it.contains("color", ignoreCase = true) || it.contains("red", ignoreCase = true) || it.contains("like", ignoreCase = true) }
      q.contains("like") -> memories.firstOrNull { it.contains("like", ignoreCase = true) }
      else -> memories.lastOrNull()
    }

    val response = if (matched != null) {
      "From what you told me: $matched."
    } else {
      "Here is what I remember: ${memories.joinToString("; ")}."
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
  private suspend fun executeMultiStep(first: DvexIntent, second: DvexIntent): DvexToolResult {
    Log.i(TAG_TOOL, "Executing MultiStep: Step 1 = $first, Step 2 = $second")

    // Open target app + YouTube search: dispatch the real search-results deep link,
    // then confirm the app actually reached the foreground when usage access allows.
    val isYoutubeOpen = first is DvexIntent.OpenApp &&
      (first.appName.contains("youtube", ignoreCase = true) || first.appName.contains("you tube", ignoreCase = true))
    val youtubeQuery = when {
      isYoutubeOpen && second is DvexIntent.PlayYoutubeVideo -> second.query
      isYoutubeOpen && second is DvexIntent.SearchWeb -> second.query
      else -> null
    }
    if (!youtubeQuery.isNullOrBlank()) {
      val res = deviceControl.openYouTube(youtubeQuery)
      if (res.status != ToolResultStatus.SUCCESS) {
        return DvexToolResult(
          status = DvexToolStatus.FAILED,
          toolName = res.toolName,
          message = res.message,
          spokenText = res.message
        )
      }
      val confirmed = appLauncher.verifyAppForeground("com.google.android.youtube")
      val msg = if (confirmed) {
        "YouTube open panniten, \"$youtubeQuery\" search results kaatiten."
      } else {
        // Not verifiable on this device: state the dispatched action, ask the user to glance.
        "YouTube-la \"$youtubeQuery\" search results open pannen. Konjam check pannunga."
      }
      return DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "youtube_search",
        message = msg,
        spokenText = msg
      )
    }

    // Maps + Search
    if (first is DvexIntent.OpenApp && first.appName.contains("maps", ignoreCase = true) && second is DvexIntent.SearchWeb) {
      val res = deviceControl.openMaps(second.query)
      return mapDeviceResult(res, "maps_search", "Opening Maps and searching for ${second.query}.")
    }

    // General app + search: Launch app first.
    if (first is DvexIntent.OpenApp && second is DvexIntent.SearchWeb) {
      val appRes = appLauncher.launchAppByName(first.appName)
      return if (appRes.isSuccessful) {
        DvexToolResult(
          status = DvexToolStatus.SUCCESS,
          toolName = "multi_step",
          message = "Opening ${first.appName}.",
          spokenText = "Opening ${first.appName}.",
          multiStepExecutedFirst = true,
          multiStepPendingSecond = second
        )
      } else {
        DvexToolResult(
          status = DvexToolStatus.NOT_FOUND,
          toolName = "multi_step",
          message = "Could not find ${first.appName}.",
          spokenText = "I couldn't find ${first.appName} on your phone."
        )
      }
    }

    // Default multi-step: execute first
    return DvexToolResult(
      status = DvexToolStatus.FAILED,
      toolName = "multi_step",
      message = "I can only complete one action at a time for this request.",
      spokenText = "I can only complete one action at a time for this request."
    )
  }

  // --- General Knowledge & Conversation ---
  // These return FACTUAL/STRUCTURAL results only. The DvexResponseGenerator
  // (LLM) turns them into the final natural spoken response. Do NOT author
  // canned spokenText here for open-ended conversation — that bypasses the
  // response generator and produces robotic "Sir." replies.
  private suspend fun executeGeneralQuestion(question: String): DvexToolResult {
    val q = question.lowercase(Locale.ROOT)

    // Check online web answer first for accurate information
    val liveAns = realTimeWeb.fetchWebAnswer(question)
    if (!liveAns.isNullOrBlank()) {
      return DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "general_qa",
        message = "Web answer: $liveAns",
        spokenText = liveAns
      )
    }

    // Return a structural hint about what kind of question this is.
    // The response generator's LLM will phrase the actual reply naturally.
    val category = when {
      q.contains("photosynthesis") -> "science"
      q.contains("joke") -> "joke"
      q.contains("plan my day") -> "productivity"
      q.contains("moon") -> "space"
      else -> "general"
    }
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "general_qa",
      // Internal routing metadata only — NEVER spoken or displayed.
      message = "General question ($category): $question",
      spokenText = ""
    )
  }

  private fun executeConversation(statement: String): DvexToolResult {
    val s = statement.lowercase(Locale.ROOT)
    val isTamilScript = statement.any { it in '\u0B80'..'\u0BFF' }
    val isTanglish = s.contains("vanakkam") || s.contains("solla") || s.contains("sollu") ||
        s.contains("epdi") || s.contains("eppadi") || s.contains("irukku") || s.contains("irukken") ||
        s.contains("yaaru") || s.contains("nandri") || s.contains("enna") ||
        s.contains("mudiyum") || s.contains("seri") || s.contains("sari") || s.contains("pannu")

    // Return structural data about the conversation type — the LLM phrases the reply.
    // The response generator's prompt (buildResponsePrompt / describeAction) receives
    // the user's verbatim statement and will phrase a natural reply.
    //
    // CRITICAL: spokenText stays EMPTY here. Internal labels like
    // "Conversation: greeting" are routing metadata, never user-facing text —
    // DvexResponseGenerator owns every final spoken/displayed reply.
    val convType = when {
      s.contains("who are you") || s.contains("what are you") || s.contains("neenga yaaru") || s.contains("yaar nee") -> "identity"
      s.contains("vanakkam") || s.contains("வணக்கம்") -> "greeting"
      s.contains("how are you") || s.contains("epdi irukka") || s.contains("eppadi irukkenga") ||
          s.contains("eppadi irukinga") || s.contains("eppadi irukeenga") || s.contains("epdi irukinga") -> "how_are_you"
      s.contains("what can you do") || s.contains("what are you capable of") || s.contains("what do you do") ||
          s.contains("what can dvex do") || s.contains("capabilities") ||
          s.contains("enna panna mudiyum") || s.contains("enna seiya mudiyum") ||
          s.contains("என்ன செய்ய முடியும்") || s.contains("என்ன பண்ண முடியும்") ->
        "capabilities"
      s.contains("hello") || s.contains("hi ") || s.trim() == "hi" || s.contains("hey") ||
          s.contains("vanakkam") || s.contains("வணக்கம்") -> "greeting"
      // Very short casual openers with no other content ("heyy", "yo", "da") —
      // answered with a natural mirror-the-user line, not a formal greeting.
      s.trim().length <= 6 && !s.contains("?") -> "casual"
      s.contains("thank") || s.contains("nandri") -> "thanks"
      s.contains("bye") || s.contains("good night") || s.contains("see you") -> "goodbye"
      else -> "general"
    }
    return DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "conversation",
      message = "Conversation type: $convType" + (if (isTamilScript) " (Tamil script)" else "") + (if (isTanglish) " (Tanglish)" else ""),
      // Internal routing metadata only — NEVER spoken or displayed. The response
      // generator turns the user's own words into the natural reply.
      spokenText = ""
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

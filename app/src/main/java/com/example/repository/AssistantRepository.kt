package com.example.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.brain.DvexSmartBrain
import com.example.brain.DvexToolStatus
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.data.remote.MediaCommand
import com.example.data.remote.PendingConfirmation
import com.example.data.remote.ToolExecutionResult
import com.example.data.remote.ToolResultStatus
import com.example.data.remote.VolumeDirection
import com.example.model.DvexAssistantState
import com.example.model.DvexSettings
import com.example.permissions.DvexPermissionManager
import com.example.voice.SpeechRecognizerManager
import com.example.voice.TextToSpeechManager
import com.example.voice.WakeWordManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Central Orchestrator for the D-VEX Voice Pipeline V1.
 * Coordinates Wake Word -> Speech-to-Text -> AI / Tool Processing -> Text-to-Speech.
 */
class AssistantRepository private constructor(private val context: Context) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val prefs: SharedPreferences = context.getSharedPreferences("dvex_assistant_prefs", Context.MODE_PRIVATE)

  val appLauncher = AppLauncherRepository(context)
  val deviceControl = DeviceControlRepository(context, appLauncher)
  val wakeWordManager = WakeWordManager(context)
  val speechRecognizer = SpeechRecognizerManager(context)
  val ttsManager = TextToSpeechManager(context)
  val smartBrain = DvexSmartBrain(context, appLauncher, deviceControl)

  private val _assistantState = MutableStateFlow<DvexAssistantState>(DvexAssistantState.Standby)
  val assistantState: StateFlow<DvexAssistantState> = _assistantState.asStateFlow()

  private val _settings = MutableStateFlow(loadSettings())
  val settings: StateFlow<DvexSettings> = _settings.asStateFlow()

  private val _latestTranscript = MutableStateFlow("")
  val latestTranscript: StateFlow<String> = _latestTranscript.asStateFlow()

  private val _latestResponse = MutableStateFlow("Yes Sir, D-VEX ready.")
  val latestResponse: StateFlow<String> = _latestResponse.asStateFlow()

  private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
  val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

  // Watchdog job to ensure the UI is NEVER stuck in LISTENING or PROCESSING
  private var stateWatchdogJob: Job? = null

  init {
    wakeWordManager.setOnTriggerListener { phrase ->
      Log.i(TAG_WAKE, "Wake word detected: \"$phrase\"")
      handleWakeWordTriggered(phrase)
    }
  }

  private fun loadSettings(): DvexSettings {
    return DvexSettings(
      alwaysReadyEnabled = prefs.getBoolean("always_ready", true),
      floatingOrbEnabled = prefs.getBoolean("floating_orb", true),
      wakeWordEnabled = prefs.getBoolean("wake_word", false),
      wakeWordKeyword = prefs.getString("wake_word_keyword", "D-VEX") ?: "D-VEX",
      wakePhrase = prefs.getString("wake_phrase", "D-VEX") ?: "D-VEX",
      orbSizeDp = prefs.getInt("orb_size_dp", 56),
      voiceConfirmationEnabled = prefs.getBoolean("voice_confirmation", true),
      voiceResponseEnabled = prefs.getBoolean("voice_response", true),
      accessibilityControlEnabled = prefs.getBoolean("accessibility_control", false),
      orbPositionX = prefs.getInt("orb_x", 100),
      orbPositionY = prefs.getInt("orb_y", 300)
    )
  }

  fun updateSettings(newSettings: DvexSettings) {
    _settings.value = newSettings
    prefs.edit()
      .putBoolean("always_ready", newSettings.alwaysReadyEnabled)
      .putBoolean("floating_orb", newSettings.floatingOrbEnabled)
      .putBoolean("wake_word", newSettings.wakeWordEnabled)
      .putString("wake_word_keyword", newSettings.wakePhrase)
      .putString("wake_phrase", newSettings.wakePhrase)
      .putInt("orb_size_dp", newSettings.orbSizeDp)
      .putBoolean("voice_confirmation", newSettings.voiceConfirmationEnabled)
      .putBoolean("voice_response", newSettings.voiceResponseEnabled)
      .putBoolean("accessibility_control", newSettings.accessibilityControlEnabled)
      .putInt("orb_x", newSettings.orbPositionX)
      .putInt("orb_y", newSettings.orbPositionY)
      .apply()

    wakeWordManager.updateKeyword(newSettings.wakePhrase)

    // Coordinate wake-word listener
    if (newSettings.wakeWordEnabled && DvexPermissionManager.hasAudioPermission(context)) {
      if (_assistantState.value is DvexAssistantState.Idle || _assistantState.value is DvexAssistantState.Standby) {
        startWakeWordListening()
      }
    } else {
      wakeWordManager.stop()
      if (_assistantState.value is DvexAssistantState.WakeWordListening) {
        _assistantState.value = DvexAssistantState.Standby
      }
    }
  }

  fun saveOrbPosition(x: Int, y: Int) {
    prefs.edit().putInt("orb_x", x).putInt("orb_y", y).apply()
    _settings.value = _settings.value.copy(orbPositionX = x, orbPositionY = y)
  }

  private fun triggerTacticalHaptic() {
    try {
      val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(45)
      }
    } catch (e: Exception) {
      Log.d(TAG_VOICE, "Haptic unavailable: ${e.message}")
    }
  }

  fun startWakeWordListening() {
    if (!DvexPermissionManager.hasAudioPermission(context)) {
      Log.w(TAG_WAKE, "Audio permission missing; cannot start wake word")
      _assistantState.value = DvexAssistantState.Standby
      return
    }
    if (_settings.value.wakeWordEnabled) {
      _assistantState.value = DvexAssistantState.WakeWordListening
      wakeWordManager.start()
    } else {
      _assistantState.value = DvexAssistantState.Standby
    }
  }

  fun handleWakeWordTriggered(detectedPhrase: String = "D-VEX") {
    scope.launch {
      Log.i(TAG_WAKE, "Handling wake word activation triggered: \"$detectedPhrase\"")
      // Audio safety: Stop wake word detector before starting command recognition
      wakeWordManager.stop()
      ttsManager.stop()
      triggerTacticalHaptic()

      val confirmationText = "Yes, Sir. சொல்லுங்க."
      _latestResponse.value = confirmationText
      _assistantState.value = DvexAssistantState.Speaking(confirmationText)
      _latestTranscript.value = ""

      wakeWordManager.setAudioSuppressed(true)
      wakeWordManager.setRecentTtsUtterance(confirmationText)

      // Check if user already spoke an attached command in the same sentence (e.g. "D-VEX open youtube")
      val attachedCommand = extractAttachedCommand(detectedPhrase)
      if (attachedCommand.isNotBlank()) {
        Log.i(TAG_WAKE, "Detected attached voice command in wake trigger: \"$attachedCommand\"")
        var actionExecuted = false
        val executeAttached = {
          if (!actionExecuted) {
            actionExecuted = true
            scope.launch {
              delay(150)
              processCommand(attachedCommand)
            }
          }
        }
        val timeoutJob = scope.launch {
          delay(1600)
          executeAttached()
        }
        ttsManager.speak(confirmationText, "tamil") {
          timeoutJob.cancel()
          executeAttached()
        }
      } else {
        // Standard hands-free flow: Speak spoken confirmation, then immediately enter listening mode
        var commandListeningStarted = false
        val startCommandListening = {
          if (!commandListeningStarted) {
            commandListeningStarted = true
            scope.launch {
              delay(100)
              startListeningForCommand()
            }
          }
        }

        val timeoutJob = scope.launch {
          delay(1800)
          startCommandListening()
        }

        if (_settings.value.voiceConfirmationEnabled) {
          ttsManager.speak(confirmationText, "tamil") {
            timeoutJob.cancel()
            startCommandListening()
          }
        } else {
          timeoutJob.cancel()
          startCommandListening()
        }
      }
    }
  }

  private fun extractAttachedCommand(rawInput: String): String {
    val clean = rawInput.trim()
    val lower = clean.lowercase(Locale.ROOT)
    for (prefix in listOf(
      "hey d-vex", "hey d vex", "hey dvex", "hey devex",
      "d-vex", "d vex", "dvex", "dee vex", "devex", "t-vex", "the vex",
      "டி-வெக்ஸ்", "டிவெக்ஸ்", "டீவெக்ஸ்"
    )) {
      if (lower.startsWith(prefix)) {
        val rem = clean.substring(prefix.length).trim()
        return rem.trimStart(',', '.', ':', ';', ' ')
      }
    }
    return ""
  }

  fun startListeningForCommand(preferredLanguage: String? = null) {
    // Interruption handling: Stop any active speech immediately
    ttsManager.stop()

    // Safety: Stop wake-word engine so microphone is not contested
    wakeWordManager.stop()

    if (!DvexPermissionManager.hasAudioPermission(context)) {
      Log.w(TAG_STT, "Microphone permission missing")
      _assistantState.value = DvexAssistantState.Error("Microphone permission required")
      scope.launch {
        respondWith("Microphone permission is required to listen.")
      }
      return
    }

    triggerTacticalHaptic()
    _assistantState.value = DvexAssistantState.Listening
    _latestTranscript.value = ""

    // Arm 10-second watchdog so listening never hangs indefinitely
    armStateWatchdog(10000, "Listening timeout")

    speechRecognizer.startListening(
      onResult = { recognizedText ->
        cancelStateWatchdog()
        val norm = recognizedText.lowercase(Locale.ROOT).trim()
        // Prevent responding to self-echo from wake confirmation response
        if (norm == "yes sir" || norm.contains("சொல்லுங்க") || norm.contains("sollunga") || norm == "yes sir சொல்லுங்க" || norm == "yes, sir.") {
          Log.d(TAG_STT, "Ignored self-echo from confirmation response: $recognizedText")
          startListeningForCommand(preferredLanguage)
          return@startListening
        }
        _latestTranscript.value = recognizedText
        processCommand(recognizedText)
      },
      onError = { error ->
        cancelStateWatchdog()
        Log.w(TAG_STT, "SpeechRecognizer returned error: $error")
        if (error.contains("timed out", ignoreCase = true) ||
            error.contains("No speech", ignoreCase = true) ||
            error.contains("No match", ignoreCase = true)) {
          // Timeout with no command: return to passive wake-word listening automatically
          Log.i(TAG_STT, "No command detected before timeout; returning to passive wake-word listening")
          _assistantState.value = DvexAssistantState.Standby
          _latestResponse.value = "Standing by, Sir."
          scope.launch {
            delay(400)
            returnToRestState()
          }
        } else {
          _assistantState.value = DvexAssistantState.Error(error)
          scope.launch {
            delay(400)
            respondWith("Sorry, I didn't catch that, Sir.")
          }
        }
      },
      onPartialResult = { partialText ->
        _latestTranscript.value = partialText
      },
      preferredLanguage = preferredLanguage
    )
  }

  fun stopListening() {
    cancelStateWatchdog()
    speechRecognizer.stopListening()
    returnToRestState()
  }

  fun processCommand(command: String) {
    scope.launch {
      cancelStateWatchdog()
      _assistantState.value = DvexAssistantState.Processing
      _latestTranscript.value = command
      Log.i(TAG_AI, "Processing command via D-VEX Smart Brain: \"$command\"")

      // Arm 10-second processing watchdog
      armStateWatchdog(10000, "Processing timeout")

      val brainResult = smartBrain.process(command)
      cancelStateWatchdog()

      val toolResult = brainResult.toolResult
      when (toolResult.status) {
        DvexToolStatus.CONFIRMATION_REQUIRED -> {
          _assistantState.value = DvexAssistantState.Standby
          val pending = PendingConfirmation(
            id = toolResult.pendingActionId ?: UUID.randomUUID().toString(),
            actionTitle = toolResult.toolName,
            prompt = brainResult.spokenText,
            details = toolResult.message,
            onConfirm = {
              val confirmed = smartBrain.process("yes")
              ToolExecutionResult(
                status = if (confirmed.toolResult.isSuccessful) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
                toolName = confirmed.toolName,
                message = confirmed.spokenText
              )
            }
          )
          _pendingConfirmation.value = pending
          respondWith(brainResult.spokenText, brainResult.language)
        }
        DvexToolStatus.SUCCESS -> {
          Log.i(TAG_ACTION, "Smart Brain execution: ${toolResult.toolName} -> Success")
          _assistantState.value = DvexAssistantState.ExecutingAction(toolResult.toolName)
          delay(350)
          respondWith(brainResult.spokenText, brainResult.language)
        }
        DvexToolStatus.PERMISSION_REQUIRED -> {
          Log.w(TAG_ACTION, "Action needs permission: ${toolResult.message}")
          _assistantState.value = DvexAssistantState.Error("Permission required")
          respondWith(brainResult.spokenText, brainResult.language)
        }
        DvexToolStatus.NOT_FOUND -> {
          Log.w(TAG_ACTION, "Target not found: ${toolResult.message}")
          _assistantState.value = DvexAssistantState.Error("Not found")
          respondWith(brainResult.spokenText, brainResult.language)
        }
        DvexToolStatus.UNSUPPORTED -> {
          Log.w(TAG_ACTION, "Action not supported: ${toolResult.message}")
          _assistantState.value = DvexAssistantState.Error("Unsupported action")
          respondWith(brainResult.spokenText, brainResult.language)
        }
        DvexToolStatus.FAILED, DvexToolStatus.ERROR -> {
          Log.w(TAG_ACTION, "Execution failed: ${toolResult.message}")
          _assistantState.value = DvexAssistantState.Error("Execution failed")
          respondWith(brainResult.spokenText, brainResult.language)
        }
      }
    }
  }

  private suspend fun executeToolCalling(rawInput: String): ToolExecutionResult {
    // 0. Normalize text and strip wake phrase prefix if present
    var cleanText = rawInput.trim()
    val lower = cleanText.lowercase(Locale.ROOT)
    for (prefix in listOf("hey d-vex", "hey dvex", "d-vex", "dvex", "dee vex", "டி-வெக்ஸ்", "டிவெக்ஸ்", "டீவெக்ஸ்")) {
      if (lower.startsWith(prefix)) {
        cleanText = cleanText.substring(prefix.length).trim()
        break
      }
    }
    val rawText = cleanText.ifBlank { rawInput }
    val q = rawText.lowercase(Locale.ROOT).trim()

    // Voice Safety Confirmation (Allows hands-free confirmation when orb is floating)
    val pending = _pendingConfirmation.value
    if (pending != null) {
      if (q == "yes" || q == "confirm" || q == "proceed" || q.contains("confirm") || q == "aama" || q == "ok") {
        confirmPendingAction()
        return ToolExecutionResult(
          status = ToolResultStatus.SUCCESS,
          toolName = pending.actionTitle,
          message = "Action confirmed and executed."
        )
      } else if (q == "no" || q == "cancel" || q == "stop" || q.contains("cancel") || q == "vendaam") {
        cancelPendingAction()
        return ToolExecutionResult(
          status = ToolResultStatus.SUCCESS,
          toolName = "cancel",
          message = "Action cancelled."
        )
      }
    }

    // =========================================================================
    // 1. TACTICAL MEMORY PERSISTENCE ("Remember that my favorite color is red")
    // =========================================================================
    if (q.startsWith("remember that ") || q.startsWith("remember ") || q.contains("ninaivill veiy")) {
      val memoryToStore = rawText.replace("remember that", "", true)
        .replace("remember", "", true)
        .replace("ninaivill veiy", "", true).trim()

      if (memoryToStore.isNotEmpty()) {
        storeMemory(memoryToStore)
        Log.i(TAG_AI, "Intent: REMEMBER_FACT - \"$memoryToStore\"")
        Log.i(TAG_ACTION, "Persisting tactical memory to storage")
        return ToolExecutionResult(
          status = ToolResultStatus.SUCCESS,
          toolName = "memory_store",
          message = "Remembered: $memoryToStore."
        )
      }
    }

    if (q.contains("what did i tell you to remember") || q.contains("what do you remember") || q.contains("recall memory") || q == "memories") {
      val memories = getStoredMemories()
      Log.i(TAG_AI, "Intent: RECALL_MEMORIES (${memories.size} entries)")
      return if (memories.isEmpty()) {
        ToolExecutionResult(
          status = ToolResultStatus.SUCCESS,
          toolName = "memory_recall",
          message = "No memories stored in D-VEX core yet."
        )
      } else {
        ToolExecutionResult(
          status = ToolResultStatus.SUCCESS,
          toolName = "memory_recall",
          message = "Stored in core: ${memories.joinToString("; ")}."
        )
      }
    }

    if (q.contains("favorite color") || q.contains("favourite color")) {
      val memories = getStoredMemories()
      val colorMemory = memories.firstOrNull { it.lowercase(Locale.ROOT).contains("favorite color") || it.lowercase(Locale.ROOT).contains("color") }
      return if (colorMemory != null) {
        ToolExecutionResult(
          status = ToolResultStatus.SUCCESS,
          toolName = "memory_query",
          message = "According to stored memory: $colorMemory."
        )
      } else {
        ToolExecutionResult(
          status = ToolResultStatus.SUCCESS,
          toolName = "memory_query",
          message = "You haven't told me your favorite color yet."
        )
      }
    }

    // =========================================================================
    // 2. PHONE NAVIGATION (Home, Back, Recents, Notifications, Scrolling)
    // =========================================================================
    if (q == "go home" || q == "home" || q == "open home" || q == "veetuku po" || q == "home ku po" || q == "home po" || q.contains("ஹோம்")) {
      Log.i(TAG_AI, "Intent: GO_HOME")
      Log.i(TAG_ACTION, "Navigating to Android Home Screen")
      return deviceControl.navigateHome()
    }
    if (q == "go back" || q == "back" || q == "pinnaadi po" || q == "back po" || q.contains("பின்னாடி")) {
      Log.i(TAG_AI, "Intent: GO_BACK")
      Log.i(TAG_ACTION, "Navigating Back")
      return deviceControl.navigateBack()
    }
    if (q.contains("recent") || q == "open recents" || q == "show recent apps" || q.contains("recents kaattu") || q.contains("recent apps kaattu")) {
      Log.i(TAG_AI, "Intent: SHOW_RECENTS")
      Log.i(TAG_ACTION, "Opening Recent Apps")
      return deviceControl.showRecents()
    }
    if (q.contains("notification") || q == "open notifications" || q == "show notifications" || q.contains("notifications kaattu")) {
      Log.i(TAG_AI, "Intent: SHOW_NOTIFICATIONS")
      Log.i(TAG_ACTION, "Opening Notification Shade")
      return deviceControl.showNotifications()
    }
    if (q.contains("scroll down") || q.contains("keela scroll") || q.contains("scroll keela") || q == "keela po" || q.contains("கீழே ஸ்க்ரோல்")) {
      Log.i(TAG_AI, "Intent: SCROLL_DOWN")
      return deviceControl.scrollDown()
    }
    if (q.contains("scroll up") || q.contains("mela scroll") || q.contains("scroll mela") || q == "mela po" || q.contains("மேலே ஸ்க்ரோல்")) {
      Log.i(TAG_AI, "Intent: SCROLL_UP")
      return deviceControl.scrollUp()
    }

    // =========================================================================
    // 3. HARDWARE / SYSTEM CONTROLS & APPS
    // =========================================================================
    if (q == "open camera" || q.contains("camera") || q.contains("photo edu") || q.contains("கேமரா")) {
      Log.i(TAG_AI, "Intent: OPEN_CAMERA")
      Log.i(TAG_ACTION, "Launching Camera")
      return deviceControl.openCamera()
    }
    if (q == "open settings" || q == "settings" || q.contains("settings open") || q.contains("settings thora") || q.contains("செட்டிங்ஸ்")) {
      Log.i(TAG_AI, "Intent: OPEN_SETTINGS")
      Log.i(TAG_ACTION, "Opening System Settings")
      return deviceControl.openSettings()
    }
    if (q.contains("wifi") || q.contains("wi-fi") || q.contains("internet")) {
      Log.i(TAG_AI, "Intent: OPEN_WIFI")
      return deviceControl.openWifiSettings()
    }
    if (q.contains("maps") || q.contains("navigate to") || q.contains("வரைபடம்")) {
      val mapQuery = rawText.replace("open maps to", "", true)
        .replace("navigate to", "", true)
        .replace("open maps", "", true)
        .replace("maps open pannu", "", true).trim()
      Log.i(TAG_AI, "Intent: OPEN_MAPS")
      Log.i(TAG_ACTION, "Launching Google Maps")
      return deviceControl.openMaps(mapQuery.ifBlank { null })
    }
    if (q.contains("youtube") || q.contains("யூடியூப்")) {
      val ytQuery = rawText.replace("open youtube and search", "", true)
        .replace("search youtube for", "", true)
        .replace("open youtube", "", true)
        .replace("youtube open pannu", "", true)
        .replace("youtube thora", "", true).trim()
      Log.i(TAG_AI, "Intent: OPEN_YOUTUBE")
      Log.i(TAG_ACTION, "Launching YouTube")
      return deviceControl.openYouTube(ytQuery.ifBlank { null })
    }
    if (q.contains("browser") || q.contains("google search") || q.startsWith("search for")) {
      val searchQ = rawText.replace("search for", "", true)
        .replace("open browser and search", "", true)
        .replace("open browser", "", true).trim()
      Log.i(TAG_AI, "Intent: SEARCH_BROWSER")
      return deviceControl.openBrowser(searchQ.ifBlank { null })
    }
    if (q == "open phone" || q == "phone" || q == "dialer" || q == "open dialer") {
      Log.i(TAG_AI, "Intent: OPEN_DIALER")
      return deviceControl.openPhoneDialer()
    }
    if (q == "open messages" || q == "messages" || q == "sms" || q == "open sms") {
      Log.i(TAG_AI, "Intent: OPEN_MESSAGES")
      return deviceControl.openMessages()
    }

    // =========================================================================
    // 4. AUDIO & MEDIA CONTROLS
    // =========================================================================
    if (q.contains("open music") || q == "music" || q == "music player" || q == "start music" || q.contains("paatu podu") || q.contains("paatu play")) {
      Log.i(TAG_AI, "Intent: OPEN_MUSIC")
      return deviceControl.openMusicPlayer()
    }
    if (q.contains("volume up") || q.contains("increase volume") || q.contains("raise volume") || q.contains("sound ethu") || q.contains("volume ethu")) {
      Log.i(TAG_AI, "Intent: VOLUME_UP")
      return deviceControl.adjustVolume(VolumeDirection.UP)
    }
    if (q.contains("volume down") || q.contains("decrease volume") || q.contains("lower volume") || q.contains("sound kora") || q.contains("volume kora")) {
      Log.i(TAG_AI, "Intent: VOLUME_DOWN")
      return deviceControl.adjustVolume(VolumeDirection.DOWN)
    }
    if (q.contains("play music") || q.contains("pause music") || q == "pause" || q == "play") {
      val mediaResult = deviceControl.controlMedia(MediaCommand.PLAY_PAUSE)
      return if (q.contains("play music")) {
        deviceControl.openMusicPlayer()
      } else {
        mediaResult
      }
    }
    if (q.contains("next song") || q.contains("next track") || q == "next") {
      return deviceControl.controlMedia(MediaCommand.NEXT)
    }
    if (q.contains("previous song") || q.contains("previous track") || q == "previous") {
      return deviceControl.controlMedia(MediaCommand.PREVIOUS)
    }

    // =========================================================================
    // 5. SENSITIVE ACTIONS (Confirmation required)
    // =========================================================================
    if (q.startsWith("call ") || q.startsWith("dial ") || q.contains("call pannu") || q == "call" || q == "make a phone call") {
      val target = if (rawText.contains("call pannu")) {
        rawText.replace("call pannu", "", true).trim()
      } else if (rawText.contains(" ")) {
        rawText.substringAfter(" ").trim()
      } else {
        "selected contact"
      }
      val pending = PendingConfirmation(
        id = UUID.randomUUID().toString(),
        actionTitle = "Phone Call",
        prompt = "Confirm phone call to \"$target\"?",
        details = "D-VEX will open the phone dialer prepared to call $target.",
        onConfirm = { deviceControl.openPhoneDialer(target) }
      )
      _pendingConfirmation.value = pending
      return ToolExecutionResult(
        status = ToolResultStatus.CONFIRMATION_REQUIRED,
        toolName = "phone_call",
        message = "Safety confirmation required: Call $target?",
        requiresConfirmation = true,
        confirmationPrompt = "Confirm call to $target?"
      )
    }

    if (q.startsWith("send message") || q.startsWith("text ") || q.contains("message anupu") || q == "send message" || q == "send a message") {
      val target = if (q.contains("message anupu")) {
        rawText.replace("message anupu", "", true).trim()
      } else {
        rawText.substringAfter("to ", "").substringBefore("saying", "").trim()
      }
      val body = rawText.substringAfter("saying ", "").trim()
      val displayTarget = if (target.isNotEmpty()) target else "recipient"
      val pending = PendingConfirmation(
        id = UUID.randomUUID().toString(),
        actionTitle = "Send SMS",
        prompt = "Confirm opening message to \"$displayTarget\"?",
        details = if (body.isNotEmpty()) "Body: \"$body\"" else "Opening SMS draft.",
        onConfirm = { deviceControl.openMessages(target.ifBlank { null }, body.ifBlank { null }) }
      )
      _pendingConfirmation.value = pending
      return ToolExecutionResult(
        status = ToolResultStatus.CONFIRMATION_REQUIRED,
        toolName = "send_message",
        message = "Safety confirmation required: Open message composer for $displayTarget?",
        requiresConfirmation = true,
        confirmationPrompt = "Confirm message to $displayTarget?"
      )
    }

    // =========================================================================
    // 6. APP LAUNCHER (Generic installed app lookup)
    // =========================================================================
    var appToLaunch = ""
    if (q.startsWith("open ") || q.startsWith("launch ") || q.startsWith("start ")) {
      appToLaunch = rawText.substringAfter(" ").trim()
    } else if (q.endsWith(" open pannu") || q.endsWith(" open") || q.endsWith(" thora")) {
      appToLaunch = rawText.replace("open pannu", "", true)
        .replace("open", "", true)
        .replace("thora", "", true).trim()
    }

    if (appToLaunch.isNotEmpty()) {
      Log.i(TAG_AI, "Intent: LAUNCH_APP - \"$appToLaunch\"")
      val launchResult = appLauncher.launchAppByName(appToLaunch)
      if (launchResult.isSuccessful) {
        return launchResult
      } else {
        return ToolExecutionResult(
          status = ToolResultStatus.FAILED,
          toolName = "app_launcher",
          message = "Application \"$appToLaunch\" not found on device."
        )
      }
    }

    // Single app name spoken alone (e.g. "WhatsApp", "Chrome")
    val singleAppMatch = appLauncher.launchAppByName(rawText)
    if (singleAppMatch.isSuccessful) {
      return singleAppMatch
    }

    // =========================================================================
    // 7. GREETINGS, WEATHER & SYSTEM TELEMETRY
    // =========================================================================
    if (q.contains("weather") || q.contains("vaanavilai") || q.contains("climatic")) {
      Log.i(TAG_AI, "Intent: WEATHER_QUERY")
      return ToolExecutionResult(
        status = ToolResultStatus.SUCCESS,
        toolName = "weather_telemetry",
        message = "Current telemetry: 30°C, partly cloudy. High of 34°C, low of 26°C."
      )
    }
    if (q == "vanakkam" || q == "வணக்கம்" || q == "hello" || q == "hi" || q == "hey") {
      Log.i(TAG_AI, "Intent: GREETING")
      return ToolExecutionResult(
        status = ToolResultStatus.SUCCESS,
        toolName = "greeting",
        message = "வணக்கம், Sir! சொல்லுங்க, என்ன பண்ணனும்?"
      )
    }
    if (q.contains("epdi irukka") || q.contains("how are you")) {
      return ToolExecutionResult(
        status = ToolResultStatus.SUCCESS,
        toolName = "greeting",
        message = "நான் நல்லா இருக்கேன், Sir. சொல்லுங்க, என்ன வேணும்?"
      )
    }
    if (q.contains("who are you") || q.contains("what is d-vex") || q.contains("your name") || q.contains("yaar nee")) {
      return ToolExecutionResult(
        status = ToolResultStatus.SUCCESS,
        toolName = "identity",
        message = "I am D-VEX, your tactical AI assistant, Sir."
      )
    }
    if (q.contains("status") || q.contains("diagnostics") || q.contains("system")) {
      return ToolExecutionResult(
        status = ToolResultStatus.SUCCESS,
        toolName = "system_status",
        message = "All systems operational, Sir. Standing by."
      )
    }

    // Default conversational response
    Log.i(TAG_AI, "Intent: CONVERSATION - \"$rawText\"")
    return ToolExecutionResult(
      status = ToolResultStatus.SUCCESS,
      toolName = "conversation",
      message = "Got it, Sir. Standing by."
    )
  }

  // Memory Helper Methods
  private fun storeMemory(fact: String) {
    val existing = getStoredMemories().toMutableSet()
    existing.add(fact)
    prefs.edit().putStringSet("dvex_core_memories", existing).apply()
  }

  private fun getStoredMemories(): Set<String> {
    return prefs.getStringSet("dvex_core_memories", emptySet()) ?: emptySet()
  }

  fun confirmPendingAction() {
    val pending = _pendingConfirmation.value ?: return
    _pendingConfirmation.value = null
    scope.launch {
      _assistantState.value = DvexAssistantState.ExecutingAction(pending.actionTitle)
      val result = pending.onConfirm()
      respondWith(result.message)
    }
  }

  fun cancelPendingAction() {
    _pendingConfirmation.value = null
    smartBrain.cancelPendingConfirmation()
    respondWith("Action cancelled, Sir.")
  }

  private fun respondWith(text: String, language: com.example.brain.DetectedLanguage? = null) {
    _latestResponse.value = text
    _assistantState.value = DvexAssistantState.Speaking(text)
    Log.i(TAG_TTS, "Speaking response ($language): \"$text\"")

    // Register with WakeWordManager to prevent acoustic self-triggering on own TTS output
    wakeWordManager.setAudioSuppressed(true)
    wakeWordManager.setRecentTtsUtterance(text)

    if (_settings.value.voiceResponseEnabled) {
      val langCode = language?.name?.lowercase(Locale.ROOT)
      ttsManager.speak(text, langCode) {
        Log.i(TAG_VOICE, "TTS finished; checking confirmation or returning to rest")
        wakeWordManager.setAudioSuppressed(false)
        if (_pendingConfirmation.value != null) {
          scope.launch {
            delay(150)
            startListeningForCommand()
          }
        } else {
          returnToRestState()
        }
      }
    } else {
      wakeWordManager.setAudioSuppressed(false)
      scope.launch {
        delay(2200)
        if (_pendingConfirmation.value != null) {
          startListeningForCommand()
        } else {
          returnToRestState()
        }
      }
    }
  }

  fun returnToRestState() {
    cancelStateWatchdog()
    if (_settings.value.wakeWordEnabled && DvexPermissionManager.hasAudioPermission(context)) {
      Log.i(TAG_VOICE, "Returned to standby (passive wake-word listening re-armed)")
      _assistantState.value = DvexAssistantState.WakeWordListening
      scope.launch {
        delay(350)
        wakeWordManager.setAudioSuppressed(false)
        wakeWordManager.start()
      }
    } else {
      Log.i(TAG_VOICE, "Returned to standby")
      _assistantState.value = DvexAssistantState.Standby
      wakeWordManager.setAudioSuppressed(false)
    }
  }

  private fun armStateWatchdog(timeoutMs: Long, reason: String) {
    cancelStateWatchdog()
    stateWatchdogJob = scope.launch {
      delay(timeoutMs)
      Log.w(TAG_VOICE, "State watchdog tripped: $reason. Auto-recovering to Standby.")
      try {
        speechRecognizer.stopListening()
      } catch (e: Exception) {
        Log.d(TAG_VOICE, "Error stopping recognizer on watchdog", e)
      }
      returnToRestState()
    }
  }

  private fun cancelStateWatchdog() {
    stateWatchdogJob?.cancel()
    stateWatchdogJob = null
  }

  companion object {
    private const val TAG_WAKE = "[D-VEX][WAKE]"
    private const val TAG_STT = "[D-VEX][STT]"
    private const val TAG_AI = "[D-VEX][AI]"
    private const val TAG_TTS = "[D-VEX][TTS]"
    private const val TAG_VOICE = "[D-VEX][VOICE]"
    private const val TAG_ACTION = "[D-VEX][ACTION]"

    @Volatile
    private var INSTANCE: AssistantRepository? = null

    fun getInstance(context: Context): AssistantRepository {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: AssistantRepository(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}

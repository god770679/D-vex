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
import com.example.data.remote.PendingConfirmation
import com.example.data.remote.ToolExecutionResult
import com.example.data.remote.ToolResultStatus
import com.example.model.DvexAssistantState
import com.example.model.DvexSettings
import com.example.permissions.DvexPermissionManager
import com.example.voice.SpeechRecognizerManager
import com.example.voice.TextToSpeechManager
import com.example.voice.VoiceSessionStateMachine
import com.example.voice.VoiceSessionStateMachine.CommandEndReason
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

  // The response card starts NEUTRAL. No canned "ready"/"standing by" line is
  // ever published: this stays empty until a real response exists (Gemini's
  // generated reply, or a verified tool result).
  private val _latestResponse = MutableStateFlow("")
  val latestResponse: StateFlow<String> = _latestResponse.asStateFlow()

  private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
  val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

  /**
   * Voice session lifecycle (wake -> command -> standby) with duplicate protection.
   * Exactly one wake session and one command listen window may exist at a time;
   * wake-word detection resumes only after the command session fully ends.
   */
  private val voiceSession = VoiceSessionStateMachine()

  /** Timestamp of the last spoken wake-confirmation, used to bound echo suppression. */
  @Volatile private var lastConfirmationSpokenAtMs = 0L

  // Watchdog job to ensure the UI is NEVER stuck in LISTENING or PROCESSING
  private var stateWatchdogJob: Job? = null

  init {
    wakeWordManager.updateKeyword(_settings.value.wakePhrase)
    wakeWordManager.setOnTriggerListener { phrase ->
      Log.i(TAG_WAKE, "Wake word detected: \"$phrase\"")
      handleWakeWordTriggered(phrase)
    }
    // Hard gate: while a voice session is active the wake detector must never fire
    // (belt-and-braces alongside the detector being stopped for the session).
    wakeWordManager.setTriggerGate { !voiceSession.isSessionActive() }
  }

  private fun loadSettings(): DvexSettings {
    return DvexSettings(
      alwaysReadyEnabled = prefs.getBoolean("always_ready", true),
      floatingOrbEnabled = prefs.getBoolean("floating_orb", true),
      wakeWordEnabled = prefs.getBoolean("wake_word", true),
      wakeWordKeyword = prefs.getString("wake_word_keyword", "D-VEX") ?: "D-VEX",
      wakePhrase = prefs.getString("wake_phrase", "D-VEX") ?: "D-VEX",
      orbSizeDp = prefs.getInt("orb_size_dp", 56),
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
      // Never arm wake detection while a voice session is mid-flight.
      if (voiceSession.isSessionActive()) {
        Log.d(TAG_WAKE, "Voice session active; refusing to start wake detection mid-session")
        return
      }
      if (wakeWordManager.isRunning()) {
        Log.d(TAG_WAKE, "Wake-word manager already active; skipping duplicate start")
        return
      }
      voiceSession.enterStandby()
      _assistantState.value = DvexAssistantState.WakeWordListening
      wakeWordManager.start()
    } else {
      _assistantState.value = DvexAssistantState.Standby
    }
  }

  fun handleWakeWordTriggered(detectedPhrase: String = "D-VEX") {
    scope.launch {
      // DUPLICATE PROTECTION: accept the wake trigger exactly once per session.
      // Late echoes / double callbacks from the detector are dropped here and by
      // the detector-level trigger gate, so only ONE wake session is ever created.
      val sessionId = voiceSession.onWakeDetected(detectedPhrase)
      if (sessionId == null) {
        Log.i(TAG_WAKE, "Duplicate/late wake trigger ignored (session already active): \"$detectedPhrase\"")
        return@launch
      }
      Log.i(TAG_WAKE, "Wake session $sessionId created for: \"$detectedPhrase\"")

      // Audio safety: Stop wake word detector before starting command recognition
      wakeWordManager.stop()
      ttsManager.stop()
      triggerTacticalHaptic()

      // WAKE = STATE TRANSITION ONLY. No canned acknowledgement is spoken and no
      // canned text is published: the response card stays neutral until Gemini has
      // actually generated the reply to the user's real command. The echo-guard
      // timestamp is still armed so residual TTS audio is recognized as self-echo.
      _assistantState.value = DvexAssistantState.Listening
      _latestTranscript.value = ""
      lastConfirmationSpokenAtMs = System.currentTimeMillis()

      // Check if user already spoke an attached command in the same sentence (e.g. "D-VEX open youtube")
      val attachedCommand = extractAttachedCommand(detectedPhrase)
      if (attachedCommand.isNotBlank()) {
        Log.i(TAG_WAKE, "Detected attached voice command in wake trigger: \"$attachedCommand\"")
        // Attached commands skip the listen window; transition the session once so
        // processCommand's exactly-once gate accepts it. TTS callback OR timeout —
        // whichever lands first — executes it exactly once (idempotent latch below).
        voiceSession.onCommandListeningStarted()
        var actionExecuted = false
        val executeAttached = {
          if (!actionExecuted) {
            actionExecuted = true
            if (voiceSession.markCommandProcessing()) {
              scope.launch {
                delay(150)
                processCommand(attachedCommand)
              }
            }
          }
        }
        // No spoken acknowledgement to wait for: execute the attached command
        // immediately (it flows through the normal brain/Gemini pipeline).
        executeAttached()
      } else {
        // Standard hands-free flow: enter listening mode immediately.
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

        // No spoken acknowledgement: open the command-listening window straight
        // away so the user's actual speech is captured and routed through the brain.
        startCommandListening()
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
    // SESSION GATE: command listening may start exactly once per session.
    val sessionOpened = when {
      voiceSession.isAwaitingCommand() -> voiceSession.onCommandListeningStarted() != null
      // Confirmation re-listen or explicit TTS interruption (orb tap) within the
      // SAME session: one new window, gated by the previous session state.
      voiceSession.isProcessingCommand() -> voiceSession.onCommandRelistenRequested() != null
      voiceSession.isListeningWindowOpen() -> true
      // Manual entry (orb tap / HUD mic) only when no session is active.
      voiceSession.canStartCommandWithoutWake() -> voiceSession.onManualCommandRequested() != null
      else -> false
    }
    if (!sessionOpened) {
      Log.w(TAG_STT, "Command listening refused: voice session already active (${voiceSession.snapshot().state})")
      return
    }

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
        val clean = recognizedText.trim()
        if (clean.isBlank()) {
          Log.i(TAG_STT, "Empty recognized text; closing command session silently")
          endCommandSession(CommandEndReason.SILENCE)
          return@startListening
        }

        val norm = clean.lowercase(Locale.ROOT)
        // Self-echo of the wake confirmation: treated as "no command" ONLY within the
        // echo window right after the confirmation was spoken. A genuine "yes sir"
        // confirmation later in the session is processed normally.
        if (norm == "yes sir" || norm.contains("சொல்லுங்க") || norm.contains("sollunga") || norm == "yes sir சொல்லுங்க" || norm == "yes, sir.") {
          val sinceConfirmation = System.currentTimeMillis() - lastConfirmationSpokenAtMs
          if (sinceConfirmation < 3000) {
            Log.d(TAG_STT, "Self-echo of confirmation treated as no-command; closing session")
            endCommandSession(CommandEndReason.SILENCE)
            return@startListening
          }
          Log.d(TAG_STT, "Confirmation-like phrase outside echo window; processing normally")
        }

        // Exactly-once command processing: duplicate onResults for this session are ignored.
        if (!voiceSession.markCommandProcessing()) {
          Log.w(TAG_STT, "Duplicate command result ignored (session not in listening state)")
          return@startListening
        }
        _latestTranscript.value = clean
        processCommand(clean, speechRecognizer.lastResultConfidence)
      },
      onError = { error ->
        cancelStateWatchdog()
        Log.w(TAG_STT, "SpeechRecognizer returned error: $error")
        // Close the command session exactly once; duplicate error callbacks are dropped.
        val isSilenceOrEmpty = error == "EMPTY_SPEECH" ||
            error.contains("timed out", ignoreCase = true) ||
            error.contains("No speech", ignoreCase = true) ||
            error.contains("No match", ignoreCase = true) ||
            error.contains("No clear command", ignoreCase = true) ||
            error.contains("Client error", ignoreCase = true) ||
            error.contains("empty", ignoreCase = true)

        if (!endCommandSession(if (isSilenceOrEmpty) CommandEndReason.SILENCE else CommandEndReason.ERROR)) {
          Log.w(TAG_STT, "Duplicate command error callback ignored (session already closed)")
          return@startListening
        }

        if (isSilenceOrEmpty) {
          // Empty/no speech -> silently return to STANDBY without sending to brain or saying anything
          Log.i(TAG_STT, "Silence or empty speech; silently returning to STANDBY without error prompts")
          _assistantState.value = DvexAssistantState.Standby
          _latestResponse.value = ""
          _latestTranscript.value = ""
          scope.launch {
            delay(250)
            returnToRestState()
          }
        } else {
          _assistantState.value = DvexAssistantState.Standby
          _latestResponse.value = ""
          scope.launch {
            delay(300)
            returnToRestState()
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
    // User-initiated stop is an authority: force-close any active session so the
    // pipeline can never be wedged out of STANDBY, then fully release the recognizer.
    forceEndVoiceSession()
    speechRecognizer.stopListening()
    speechRecognizer.destroy()
    returnToRestState()
  }

  fun processCommand(command: String, speechConfidence: Float? = null) {
    val clean = command.trim()
    if (clean.isBlank()) {
      Log.i(TAG_AI, "Ignoring blank command; silently returning to Standby")
      _assistantState.value = DvexAssistantState.Standby
      _latestTranscript.value = ""
      endCommandSession(CommandEndReason.SILENCE)
      returnToRestState()
      return
    }

    // Exactly-once processing per session.
    // Voice path: the wake/session machinery transitions to PROCESSING_COMMAND
    // before calling this. Typed HUD path: no mic session exists, so open the
    // manual session here (only when the session is at rest — same entry the
    // orb tap uses) and mark it processing — identical exactly-once guarantees,
    // no duplicate command system.
    if (!voiceSession.isProcessingCommand() && !voiceSession.isListeningWindowOpen()) {
      if (voiceSession.onManualCommandRequested() == null) {
        Log.w(TAG_AI, "Command rejected: voice session busy (state=${voiceSession.snapshot().state})")
        return
      }
    }
    voiceSession.markCommandProcessing()
    scope.launch {
      cancelStateWatchdog()
      _assistantState.value = DvexAssistantState.Processing
      _latestTranscript.value = clean
      Log.i(TAG_AI, "Processing command via D-VEX Smart Brain: \"$clean\"")

      // Arm 10-second processing watchdog
      armStateWatchdog(10000, "Processing timeout")

      // Share ASR confidence so the brain can ask for clarification on uncertain speech
      smartBrain.reportAsrConfidence(speechConfidence)

      val brainResult = smartBrain.process(command)
      cancelStateWatchdog()

      val toolResult = brainResult.toolResult
      if (toolResult.status != DvexToolStatus.CONFIRMATION_REQUIRED) {
        _pendingConfirmation.value = null
      }

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
          respondWith(brainResult.spokenText, brainResult.language, brainResult.tone)
        }
        DvexToolStatus.SUCCESS -> {
          Log.i(TAG_ACTION, "Smart Brain execution: ${toolResult.toolName} -> Success")
          _assistantState.value = DvexAssistantState.ExecutingAction(toolResult.toolName)
          delay(350)
          respondWith(brainResult.spokenText, brainResult.language, brainResult.tone)
        }
        DvexToolStatus.UNVERIFIED -> {
          // Dispatched but not confirmed: never reported as success. The spoken text
          // is the honest wording the handler authored; the card shows the same text.
          Log.w(TAG_ACTION, "Action dispatched but unverified: ${toolResult.message}")
          _assistantState.value = DvexAssistantState.ExecutingAction(toolResult.toolName)
          respondWith(brainResult.spokenText, brainResult.language, brainResult.tone)
        }
        DvexToolStatus.PERMISSION_REQUIRED -> {
          Log.w(TAG_ACTION, "Action needs permission: ${toolResult.message}")
          _assistantState.value = DvexAssistantState.Error("Permission required")
          respondWith(brainResult.spokenText, brainResult.language, brainResult.tone)
        }
        DvexToolStatus.NOT_FOUND -> {
          Log.w(TAG_ACTION, "Target not found: ${toolResult.message}")
          _assistantState.value = DvexAssistantState.Error("Not found")
          respondWith(brainResult.spokenText, brainResult.language, brainResult.tone)
        }
        DvexToolStatus.UNSUPPORTED -> {
          Log.w(TAG_ACTION, "Action not supported: ${toolResult.message}")
          _assistantState.value = DvexAssistantState.Error("Unsupported action")
          respondWith(brainResult.spokenText, brainResult.language, brainResult.tone)
        }
        DvexToolStatus.FAILED, DvexToolStatus.ERROR -> {
          Log.w(TAG_ACTION, "Execution failed: ${toolResult.message}")
          _assistantState.value = DvexAssistantState.Error("Execution failed")
          respondWith(brainResult.spokenText, brainResult.language, brainResult.tone)
        }
      }
    }
  }

  // NOTE: the legacy keyword-template router (executeToolCalling) used to live here.
  // It hardcoded a competing conversational system (greeting / "how are you" /
  // identity / goodbye templates, "Got it. Standing by.", fabricated weather) that
  // answered before Gemini ever saw the request. D-VEX now has ONE response
  // authority: DvexSmartBrain -> DvexToolRouter (deterministic actions, safety and
  // confirmation) -> DvexResponseGenerator (Gemini owns every spoken sentence).

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
    respondWith("Action cancelled.")
  }

  private fun respondWith(
    text: String,
    language: com.example.brain.DetectedLanguage? = null,
    tone: com.example.brain.EstimatedTone? = null
  ) {
    _latestResponse.value = text
    _assistantState.value = DvexAssistantState.Speaking(text)
    // Single source of truth for what is spoken: the brain's final spokenText,
    // verbatim. The UI shows the same string, so display and voice never diverge.
    Log.i(TAG_TTS, "Speaking response (language=$language, tone=$tone): \"$text\"")

    // Register with WakeWordManager to prevent acoustic self-triggering on own TTS output
    wakeWordManager.setAudioSuppressed(true)
    wakeWordManager.setRecentTtsUtterance(text)

    if (_settings.value.voiceResponseEnabled) {
      val langCode = language?.name?.lowercase(Locale.ROOT)
      ttsManager.speak(text, langCode, tone?.name) {
        Log.i(TAG_VOICE, "TTS finished; checking confirmation or returning to rest")
        wakeWordManager.setAudioSuppressed(false)
        if (_pendingConfirmation.value != null) {
          // Bounded yes/no re-listen INSIDE the same session (one window at a time).
          val relistenId = voiceSession.onCommandRelistenRequested()
          if (relistenId != null) {
            scope.launch {
              delay(150)
              startListeningForCommand()
            }
          } else {
            Log.w(TAG_VOICE, "Confirmation re-listen refused by session state; ending session")
            voiceSession.endSessionAfterResponse()
            returnToRestState()
          }
        } else {
          // Session fully ends ONLY here — wake detection resumes afterwards.
          if (!voiceSession.endSessionAfterResponse()) {
            // Not in PROCESSING (e.g. cancelled from a re-listen window): force-close
            // so wake detection can always resume. Idempotent per session.
            voiceSession.forceEndSession()
          }
          returnToRestState()
        }
      }
    } else {
      wakeWordManager.setAudioSuppressed(false)
      scope.launch {
        delay(2200)
        if (_pendingConfirmation.value != null) {
          if (voiceSession.onCommandRelistenRequested() != null) {
            startListeningForCommand()
          } else {
            voiceSession.forceEndSession()
            returnToRestState()
          }
        } else {
          if (!voiceSession.endSessionAfterResponse()) {
            voiceSession.forceEndSession()
          }
          returnToRestState()
        }
      }
    }
  }

  /**
   * Voice-session-end authority: closes the command phase exactly once per session
   * (duplicate result/error/timeout callbacks return false) and, when no response
   * will follow (silence/timeout/stop), ends the session so wake detection may resume.
   */
  private fun endCommandSession(reason: CommandEndReason): Boolean {
    val closed = voiceSession.onCommandFinished(reason)
    if (closed && reason == CommandEndReason.RESULT) {
      // A real command was accepted: the session stays open until the brain's
      // response finishes (respondWith -> endSessionAfterResponse).
      return true
    }
    if (closed) {
      // Silence / error / timeout / stop: no action taken, end the whole session now.
      voiceSession.endSessionAfterResponse()
    }
    return closed
  }

  /**
   * Watchdog authority: force-closes a wedged session from any active state when
   * the pipeline is stuck (processing never produced a response, etc.).
   */
  private fun forceEndVoiceSession(): Boolean {
    return voiceSession.forceEndSession()
  }

  fun returnToRestState() {
    cancelStateWatchdog()
    _assistantState.value = DvexAssistantState.Standby
    if (_settings.value.wakeWordEnabled && DvexPermissionManager.hasAudioPermission(context)) {
      // Defer wake re-arm ONLY while a wake/command session is genuinely mid-flight;
      // once the session has ended (RETURNING_TO_STANDBY) or none exists, re-arm below.
      if (voiceSession.isSessionActive()) {
        Log.i(TAG_VOICE, "Standby UI state set; wake re-arm deferred (session still open)")
        return
      }
      Log.i(TAG_VOICE, "Returned to standby (passive wake-word listening re-arming)")
      scope.launch {
        delay(400)
        wakeWordManager.setAudioSuppressed(false)
        if (!wakeWordManager.isRunning() && !voiceSession.isSessionActive()) {
          wakeWordManager.start()
          voiceSession.wakeResumed()
        }
      }
    } else {
      Log.i(TAG_VOICE, "Returned to standby")
      wakeWordManager.setAudioSuppressed(false)
    }
  }

  private fun armStateWatchdog(timeoutMs: Long, reason: String) {
    cancelStateWatchdog()
    stateWatchdogJob = scope.launch {
      delay(timeoutMs)
      Log.w(TAG_VOICE, "State watchdog tripped: $reason. Auto-recovering to Standby.")
      try {
        // Watchdog is the authority: force-close the wedged session from any state
        // so a hung recognizer can never keep the pipeline out of STANDBY.
        forceEndVoiceSession()
        speechRecognizer.stopListening()
        speechRecognizer.destroy()
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

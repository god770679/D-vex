package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.permissions.DvexPermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Pluggable Wake-Word Detector Interface.
 *
 * Can be swapped with offline/on-device engines such as:
 * - Picovoice Porcupine
 * - Vosk
 * - OpenWakeWord
 *
 * in the future without changing the WakeWordManager API.
 */
interface WakeWordDetector {

    val name: String

    fun start(
        onTrigger: (String) -> Unit,
        onError: (String) -> Unit
    )

    fun stop()

    fun isRunning(): Boolean
}

/**
 * Standard Android-native wake-word detector.
 *
 * Uses Android SpeechRecognizer locally to detect:
 * - D-VEX
 * - DVEX
 * - Dee Vex
 * - Hey D-VEX
 * - common transcription variations
 *
 * It does not continuously stream audio through D-VEX cloud services.
 */
class AndroidSpeechWakeWordDetector(
    private val context: Context,
    private var keyword: String = "D-VEX"
) : WakeWordDetector {

    override val name: String =
        "Android Speech Trigger Engine (Local)"

    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var isListening = false

    @Volatile
    private var isSessionActive = false

    private var onTriggerCallback: ((String) -> Unit)? = null

    private var onErrorCallback: ((String) -> Unit)? = null

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var lastTriggerTimeMs = 0L

    private val TRIGGER_DEBOUNCE_MS = 1200L

    @Volatile
    private var isAudioSuppressed = false

    @Volatile
    private var lastSpokenText = ""

    @Volatile
    private var lastSpokenTimestampMs = 0L

    private val restartRunnable = Runnable {
        if (isListening && !isSessionActive) {
            initAndListen()
        }
    }

    fun setKeyword(newKeyword: String) {
        keyword = newKeyword
    }

    fun setAudioSuppressed(suppressed: Boolean) {
        isAudioSuppressed = suppressed
    }

    fun setRecentTtsUtterance(text: String) {
        lastSpokenText =
            text
                .lowercase(Locale.ROOT)
                .trim()

        lastSpokenTimestampMs =
            System.currentTimeMillis()
    }

    override fun start(
        onTrigger: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        if (!DvexPermissionManager.hasAudioPermission(context)) {
            onError(
                "Microphone permission required for wake-word."
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError(
                "Speech recognition not available on this device."
            )
            return
        }

        onTriggerCallback = onTrigger
        onErrorCallback = onError

        // Prevent duplicate restart loops or multiple recognizers.
        if (isListening) {
            Log.d(
                TAG,
                "WakeWordDetector is already active; ignoring redundant start()"
            )
            return
        }

        isListening = true

        mainHandler.removeCallbacks(
            restartRunnable
        )

        mainHandler.post {
            if (isListening && !isSessionActive) {
                initAndListen()
            }
        }
    }

    private fun handleDetectedWakeWord(
        phrase: String
    ) {

        val now =
            System.currentTimeMillis()

        /*
         * Self-feedback prevention.
         *
         * Do not react to D-VEX's own TTS output.
         */
        if (
            isAudioSuppressed ||
            (now - lastSpokenTimestampMs < 800L)
        ) {

            Log.d(
                TAG,
                "Wake-word trigger suppressed: Assistant is speaking or acoustic echo window active"
            )

            return
        }

        /*
         * Suppress if detected text matches recent assistant speech.
         */
        val lowerPhrase =
            phrase
                .lowercase(Locale.ROOT)
                .trim()

        if (
            lastSpokenText.isNotBlank() &&
            (
                lowerPhrase.contains(lastSpokenText) ||
                lastSpokenText.contains(lowerPhrase)
            )
        ) {

            Log.d(
                TAG,
                "Wake-word trigger suppressed: Detected phrase matches recent TTS output (\"$phrase\")"
            )

            return
        }

        /*
         * Debounce duplicate wake triggers.
         */
        if (
            now - lastTriggerTimeMs <
            TRIGGER_DEBOUNCE_MS
        ) {

            Log.d(
                TAG,
                "Wake-word trigger ignored due to cooldown window: \"$phrase\""
            )

            return
        }

        lastTriggerTimeMs = now

        Log.i(
            TAG,
            "D-VEX wake-word triggered by phrase: \"$phrase\""
        )

        /*
         * Release recognizer immediately so the command
         * recognizer does not collide on the microphone.
         */
        stopInternal()

        onTriggerCallback?.invoke(phrase)
    }

    private fun initAndListen() {

        if (!isListening) {
            return
        }

        if (isSessionActive) {

            Log.d(
                TAG,
                "Speech session already active; skipping duplicate startListening()"
            )

            return
        }

        try {

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {

                Log.w(
                    TAG,
                    "Speech recognition service is unavailable on this device"
                )

                onErrorCallback?.invoke(
                    "Speech recognition service not available."
                )

                return
            }

            if (speechRecognizer == null) {

                val created =
                    SpeechRecognizer.createSpeechRecognizer(
                        context
                    )

                if (created == null) {

                    Log.w(
                        TAG,
                        "SpeechRecognizer could not be created (returned null)"
                    )

                    onErrorCallback?.invoke(
                        "Speech recognizer unavailable on this device."
                    )

                    return
                }

                speechRecognizer =
                    created.apply {

                        setRecognitionListener(
                            object : RecognitionListener {

                                override fun onReadyForSpeech(
                                    params: Bundle?
                                ) {

                                    Log.d(
                                        TAG,
                                        "Wake-word engine ready for speech"
                                    )
                                }

                                override fun onBeginningOfSpeech() {}

                                override fun onRmsChanged(
                                    rmsdB: Float
                                ) {}

                                override fun onBufferReceived(
                                    buffer: ByteArray?
                                ) {}

                                override fun onEndOfSpeech() {}

                                override fun onError(
                                    error: Int
                                ) {

                                    isSessionActive = false

                                    Log.d(
                                        TAG,
                                        "Wake-word recognizer ended with error: $error"
                                    )

                                    if (!isListening) {
                                        return
                                    }

                                    if (
                                        error ==
                                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                        error ==
                                        SpeechRecognizer.ERROR_CLIENT ||
                                        error ==
                                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                                    ) {

                                        destroyRecognizerInternal()
                                    }

                                    val delayMs =
                                        when (error) {

                                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                                2500L

                                            SpeechRecognizer.ERROR_CLIENT ->
                                                2000L

                                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                                            SpeechRecognizer.ERROR_NO_MATCH ->
                                                1200L

                                            else ->
                                                1800L
                                        }

                                    scheduleRestart(
                                        delayMs
                                    )
                                }

                                override fun onResults(
                                    results: Bundle?
                                ) {

                                    isSessionActive = false

                                    if (!isListening) {
                                        return
                                    }

                                    val matches =
                                        results
                                            ?.getStringArrayList(
                                                SpeechRecognizer.RESULTS_RECOGNITION
                                            )

                                    if (!matches.isNullOrEmpty()) {

                                        for (phrase in matches) {

                                            if (
                                                matchesWakeWord(
                                                    phrase
                                                )
                                            ) {

                                                handleDetectedWakeWord(
                                                    phrase
                                                )

                                                return
                                            }
                                        }
                                    }

                                    /*
                                     * Passive continuous listening restart.
                                     */
                                    scheduleRestart(
                                        1000L
                                    )
                                }

                                override fun onPartialResults(
                                    partialResults: Bundle?
                                ) {

                                    if (!isListening) {
                                        return
                                    }

                                    val matches =
                                        partialResults
                                            ?.getStringArrayList(
                                                SpeechRecognizer.RESULTS_RECOGNITION
                                            )

                                    if (!matches.isNullOrEmpty()) {

                                        for (phrase in matches) {

                                            if (
                                                matchesWakeWord(
                                                    phrase
                                                )
                                            ) {

                                                handleDetectedWakeWord(
                                                    phrase
                                                )

                                                return
                                            }
                                        }
                                    }
                                }

                                override fun onEvent(
                                    eventType: Int,
                                    params: Bundle?
                                ) {}
                            }
                        )
                    }
            }

            val activeRecognizer =
                speechRecognizer

            if (activeRecognizer == null) {

                Log.w(
                    TAG,
                    "SpeechRecognizer is null; cannot start wake-word session"
                )

                return
            }

            isSessionActive = true

            val intent =
                Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        true
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        3
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        5000L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        5000L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        2000L
                    )
                }

            activeRecognizer.startListening(
                intent
            )

        } catch (e: Exception) {

            isSessionActive = false

            Log.e(
                TAG,
                "Error starting wake-word speech session",
                e
            )

            destroyRecognizerInternal()

            scheduleRestart(
                2000L
            )
        }
    }

    private fun scheduleRestart(
        delayMs: Long
    ) {

        mainHandler.removeCallbacks(
            restartRunnable
        )

        if (
            isListening &&
            !isSessionActive
        ) {

            mainHandler.postDelayed(
                restartRunnable,
                delayMs
            )
        }
    }

    override fun stop() {
        stopInternal()
    }

    private fun stopInternal() {

        isListening = false
        isSessionActive = false

        mainHandler.removeCallbacks(
            restartRunnable
        )

        mainHandler.removeCallbacksAndMessages(
            null
        )

        destroyRecognizerInternal()
    }

    private fun destroyRecognizerInternal() {

        try {

            speechRecognizer?.cancel()
            speechRecognizer?.destroy()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error destroying wake-word speech recognizer",
                e
            )

        } finally {

            speechRecognizer = null
            isSessionActive = false
        }
    }

    private fun matchesWakeWord(
        phrase: String
    ): Boolean {

        val norm =
            phrase
                .lowercase(Locale.ROOT)
                .replace("-", " ")
                .replace(".", " ")
                .replace("'", " ")
                .replace(",", " ")
                .trim()

        val targetKeyword =
            keyword
                .lowercase(Locale.ROOT)
                .replace("-", " ")
                .replace(".", " ")
                .replace("'", " ")
                .trim()

        /*
         * 1. Custom configured keyword.
         */
        if (targetKeyword.isNotEmpty()) {

            if (
                norm.contains(targetKeyword) ||
                norm.startsWith(targetKeyword)
            ) {

                return true
            }
        }

        /*
         * 2. D-VEX transcription variations.
         */
        val dvexVariants =
            listOf(

                "d vex",
                "dvex",
                "dee vex",
                "devex",
                "deevex",
                "the vex",
                "t vex",
                "divex",
                "divax",

                "d fix",
                "d fax",
                "d box",
                "defect",
                "defects",

                "dev x",
                "dev-x",
                "dev ex",
                "de vex",
                "d vac",
                "d vax",

                "dvecks",
                "deveks",
                "devecks",
                "d vecks",
                "d x",
                "dx",

                "hey d vex",
                "hey dvex",
                "hey devex",
                "hey dee vex",

                "hi dvex",
                "hi d vex",
                "hello dvex",

                "டி-வெக்ஸ்",
                "டிவெக்ஸ்",
                "டீவெக்ஸ்",
                "டீ-வெக்ஸ்",
                "ஹேய் டிவெக்ஸ்",
                "ஹே டிவெக்ஸ்"
            )

        for (variant in dvexVariants) {

            if (norm.contains(variant)) {
                return true
            }
        }

        return false
    }

    override fun isRunning(): Boolean {
        return isListening
    }

    companion object {

        private const val TAG =
            "[D-VEX][WAKE]"
    }
}


/**
 * High-level Wake-Word Manager.
 *
 * Coordinates:
 * - detector lifecycle
 * - wake-word state
 * - trigger callbacks
 * - audio suppression
 * - trigger gating
 */
class WakeWordManager(
    private val context: Context,
    private var detector: WakeWordDetector =
        AndroidSpeechWakeWordDetector(context)
) {

    private val _state =
        MutableStateFlow<WakeWordState>(
            WakeWordState.Disabled
        )

    val state: StateFlow<WakeWordState> =
        _state.asStateFlow()

    private var onTriggerListener:
        ((String) -> Unit)? = null

    /*
     * Trigger gate.
     *
     * AssistantRepository uses this to prevent a new wake
     * trigger while a VoiceSessionStateMachine session is active.
     *
     * Example:
     *
     * wakeWordManager.setTriggerGate {
     *     !voiceSession.isSessionActive()
     * }
     */
    @Volatile
    private var triggerGate:
        (() -> Boolean)? = null

    fun isRunning(): Boolean {
        return detector.isRunning()
    }

    fun setOnTriggerListener(
        listener: (String) -> Unit
    ) {

        onTriggerListener = listener
    }

    /**
     * Sets an external gate that must return true before
     * a detected wake-word is forwarded to the application.
     *
     * This is intentionally kept at the manager level so
     * AssistantRepository can protect the entire voice session
     * from duplicate wake callbacks.
     */
    fun setTriggerGate(
        gate: () -> Boolean
    ) {

        triggerGate = gate
    }

    fun setDetector(
        newDetector: WakeWordDetector
    ) {

        if (detector.isRunning()) {
            detector.stop()
        }

        detector = newDetector

        Log.i(
            TAG,
            "Swapped wake-word detector to: ${newDetector.name}"
        )
    }

    fun updateKeyword(
        keyword: String
    ) {

        (
            detector as?
                AndroidSpeechWakeWordDetector
            )?.setKeyword(keyword)
    }

    fun setAudioSuppressed(
        suppressed: Boolean
    ) {

        (
            detector as?
                AndroidSpeechWakeWordDetector
            )?.setAudioSuppressed(suppressed)
    }

    fun setRecentTtsUtterance(
        text: String
    ) {

        (
            detector as?
                AndroidSpeechWakeWordDetector
            )?.setRecentTtsUtterance(text)
    }

    fun enterStandby() {

        if (detector.isRunning()) {
            detector.stop()
        }

        _state.value =
            WakeWordState.Standby

        Log.d(
            TAG,
            "Wake word engine entered standby mode"
        )
    }

    fun start() {

        if (
            !DvexPermissionManager.hasAudioPermission(
                context
            )
        ) {

            _state.value =
                WakeWordState.Error(
                    "Microphone permission required for wake word."
                )

            Log.w(
                TAG,
                "Cannot start wake word: microphone permission missing"
            )

            return
        }

        _state.value =
            WakeWordState.Listening

        Log.i(
            TAG,
            "Wake word listening started (local on-device engine)"
        )

        detector.start(

            onTrigger = { keyword ->

                /*
                 * HARD TRIGGER GATE
                 *
                 * If VoiceSessionStateMachine says another
                 * voice session is active, silently drop this
                 * trigger instead of forwarding a duplicate.
                 */
                val allowed =
                    try {
                        triggerGate?.invoke() ?: true
                    } catch (e: Exception) {

                        Log.w(
                            TAG,
                            "Trigger gate failed; blocking wake trigger safely",
                            e
                        )

                        false
                    }

                if (!allowed) {

                    Log.i(
                        TAG,
                        "Wake trigger blocked by session gate: \"$keyword\""
                    )

                    return@start
                }

                _state.value =
                    WakeWordState.Triggered(
                        keyword
                    )

                Log.i(
                    TAG,
                    "Wake word detected: \"$keyword\""
                )

                onTriggerListener?.invoke(
                    keyword
                )
            },

            onError = { error ->

                _state.value =
                    WakeWordState.Error(
                        error
                    )

                Log.w(
                    TAG,
                    "Wake word detector error: $error"
                )
            }
        )
    }

    fun stop() {

        detector.stop()

        _state.value =
            WakeWordState.Disabled

        Log.i(
            TAG,
            "Wake word listening stopped"
        )
    }

    fun simulateTrigger(
        keyword: String = "D-VEX"
    ) {

        /*
         * Apply the same gate to simulated triggers so
         * testing cannot bypass session protection.
         */
        val allowed =
            try {
                triggerGate?.invoke() ?: true
            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Trigger gate failed during simulated trigger; blocking safely",
                    e
                )

                false
            }

        if (!allowed) {

            Log.i(
                TAG,
                "Simulated wake trigger blocked by session gate: \"$keyword\""
            )

            return
        }

        _state.value =
            WakeWordState.Triggered(
                keyword
            )

        Log.i(
            TAG,
            "Simulated wake word trigger: $keyword"
        )

        onTriggerListener?.invoke(
            keyword
        )
    }

    companion object {

        private const val TAG =
            "[D-VEX][WAKE]"
    }
}
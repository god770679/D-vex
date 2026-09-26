package com.example.voice

import java.util.UUID

/**
 * Controls the lifecycle of one D-VEX voice interaction.
 *
 * State flow:
 *
 * STANDBY
 *   -> WAKE_DETECTED
 *   -> AWAITING_COMMAND
 *   -> LISTENING_COMMAND
 *   -> PROCESSING_COMMAND
 *   -> RETURNING_TO_STANDBY
 *   -> STANDBY
 *
 * Manual/HUD command:
 *
 * STANDBY
 *   -> LISTENING_COMMAND
 *   -> PROCESSING_COMMAND
 *   -> RETURNING_TO_STANDBY
 *   -> STANDBY
 *
 * This class is deliberately independent from Android UI,
 * SpeechRecognizer and TextToSpeech.
 *
 * It only owns session/lifecycle state and duplicate protection.
 */
class VoiceSessionStateMachine {

    enum class State {
        STANDBY,
        WAKE_DETECTED,
        AWAITING_COMMAND,
        LISTENING_COMMAND,
        PROCESSING_COMMAND,
        RETURNING_TO_STANDBY
    }

    enum class CommandEndReason {
        RESULT,
        SILENCE,
        ERROR
    }

    data class Snapshot(
        val state: State,
        val sessionId: String?,
        val detectedPhrase: String?,
        val commandResultProcessed: Boolean
    )

    private var state: State = State.STANDBY

    private var sessionId: String? = null

    private var detectedPhrase: String? = null

    /**
     * Exactly-once guard for command result processing.
     */
    private var commandResultProcessed = false

    /**
     * Opens a new wake-word session.
     *
     * Returns the newly-created session ID.
     * Returns null when another session is already active.
     */
    @Synchronized
    fun onWakeDetected(
        phrase: String
    ): String? {

        if (isSessionActive()) {
            return null
        }

        val newSessionId = UUID.randomUUID().toString()

        sessionId = newSessionId
        detectedPhrase = phrase
        commandResultProcessed = false
        state = State.WAKE_DETECTED

        return newSessionId
    }

    /**
     * Moves a wake-detected session into the state where
     * D-VEX is waiting for the user's command.
     */
    @Synchronized
    fun onCommandListeningStarted(): String? {

        if (sessionId == null) {
            return null
        }

        if (
            state != State.WAKE_DETECTED &&
            state != State.PROCESSING_COMMAND
        ) {
            return null
        }

        /*
         * Re-listening after processing is allowed as long as
         * the session is still alive.
         */
        commandResultProcessed = false
        state = State.LISTENING_COMMAND

        return sessionId
    }

    /**
     * Marks the command as being processed.
     *
     * Returns true only when this transition is valid.
     * This prevents duplicate SpeechRecognizer callbacks.
     */
    @Synchronized
    fun markCommandProcessing(): Boolean {

        if (sessionId == null) {
            return false
        }

        if (commandResultProcessed) {
            return false
        }

        if (
            state != State.LISTENING_COMMAND &&
            state != State.AWAITING_COMMAND &&
            state != State.PROCESSING_COMMAND
        ) {
            return false
        }

        commandResultProcessed = true
        state = State.PROCESSING_COMMAND

        return true
    }

    /**
     * Marks the session as waiting for the command after
     * wake-word confirmation.
     */
    @Synchronized
    fun onAwaitingCommand(): Boolean {

        if (sessionId == null) {
            return false
        }

        if (state != State.WAKE_DETECTED) {
            return false
        }

        state = State.AWAITING_COMMAND
        commandResultProcessed = false

        return true
    }

    /**
     * Allows a second listening window within the same
     * active session.
     *
     * Used when D-VEX asks the user to repeat a command.
     */
    @Synchronized
    fun onCommandRelistenRequested(): String? {

        if (sessionId == null) {
            return null
        }

        if (
            state != State.PROCESSING_COMMAND &&
            state != State.RETURNING_TO_STANDBY
        ) {
            return null
        }

        commandResultProcessed = false
        state = State.LISTENING_COMMAND

        return sessionId
    }

    /**
     * Opens a command session without a wake-word trigger.
     *
     * Used by HUD/orb/manual command entry.
     */
    @Synchronized
    fun onManualCommandRequested(): String? {

        if (isSessionActive()) {
            return null
        }

        val newSessionId = UUID.randomUUID().toString()

        sessionId = newSessionId
        detectedPhrase = null
        commandResultProcessed = false
        state = State.LISTENING_COMMAND

        return newSessionId
    }

    /**
     * Returns true when any wake/command session is active.
     */
    @Synchronized
    fun isSessionActive(): Boolean {
        return when (state) {
            State.STANDBY -> false
            else -> sessionId != null
        }
    }

    /**
     * Returns true when the session is waiting for the
     * user's command after the wake-word confirmation.
     */
    @Synchronized
    fun isAwaitingCommand(): Boolean {
        return state == State.AWAITING_COMMAND
    }

    /**
     * Returns true while a command is being processed.
     */
    @Synchronized
    fun isProcessingCommand(): Boolean {
        return state == State.PROCESSING_COMMAND
    }

    /**
     * Returns true while a command listening window is open.
     */
    @Synchronized
    fun isListeningWindowOpen(): Boolean {
        return state == State.LISTENING_COMMAND
    }

    /**
     * Returns true when a manual command can be started.
     */
    @Synchronized
    fun canStartCommandWithoutWake(): Boolean {
        return state == State.STANDBY &&
            sessionId == null
    }

    /**
     * Enters standby without creating a command session.
     *
     * Used before passive wake-word listening starts.
     */
    @Synchronized
    fun enterStandby() {

        state = State.STANDBY
        sessionId = null
        detectedPhrase = null
        commandResultProcessed = false
    }

    /**
     * Called when command recognition finishes.
     *
     * RESULT:
     *   Keep the session alive until TTS/response completes.
     *
     * SILENCE / ERROR:
     *   Session can be closed immediately.
     */
    @Synchronized
    fun onCommandFinished(
        reason: CommandEndReason
    ): Boolean {

        if (sessionId == null) {
            return false
        }

        if (state != State.PROCESSING_COMMAND &&
            state != State.LISTENING_COMMAND
        ) {
            return false
        }

        when (reason) {

            CommandEndReason.RESULT -> {
                state = State.PROCESSING_COMMAND
                return true
            }

            CommandEndReason.SILENCE,
            CommandEndReason.ERROR -> {
                state = State.RETURNING_TO_STANDBY
                return true
            }
        }
    }

    /**
     * Ends the session after D-VEX has finished responding.
     */
    @Synchronized
    fun endSessionAfterResponse(): Boolean {

        if (sessionId == null) {
            return false
        }

        state = State.RETURNING_TO_STANDBY

        clearSession()

        return true
    }

    /**
     * Force-closes the session.
     *
     * Used by stopListening() and watchdog recovery.
     */
    @Synchronized
    fun forceEndSession(): Boolean {

        if (sessionId == null && state == State.STANDBY) {
            return false
        }

        state = State.RETURNING_TO_STANDBY

        clearSession()

        return true
    }

    /**
     * Called after the wake-word detector is re-armed.
     */
    @Synchronized
    fun wakeResumed() {

        clearSession()

        state = State.STANDBY
    }

    /**
     * Current immutable session snapshot.
     */
    @Synchronized
    fun snapshot(): Snapshot {

        return Snapshot(
            state = state,
            sessionId = sessionId,
            detectedPhrase = detectedPhrase,
            commandResultProcessed = commandResultProcessed
        )
    }

    /**
     * Clears all session-specific data.
     */
    private fun clearSession() {

        sessionId = null
        detectedPhrase = null
        commandResultProcessed = false
    }
}
package com.example.brain

import java.util.LinkedList
import java.util.Locale

/**
 * Coarse conversational tone.
 *
 * This is only an estimate used to adapt wording.
 * D-VEX should never present it as a certain fact about the user.
 */
enum class EstimatedTone {
    NEUTRAL,
    HAPPY,
    SAD,
    FRUSTRATED,
    URGENT,
    CONFUSED,
    EXCITED
}

/**
 * How the user is currently communicating.
 *
 * This is intentionally simple. It is a conversational signal,
 * not a personality diagnosis.
 */
enum class SpeakingStyle {
    CASUAL,
    FRIENDLY,
    SERIOUS,
    SHORT,
    DETAILED,
    PLAYFUL,
    UNKNOWN
}

data class ContextTurn(
    val userInput: String,
    val intent: DvexIntent,
    val toolName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tone: EstimatedTone = EstimatedTone.NEUTRAL,
    val language: DetectedLanguage = DetectedLanguage.ENGLISH,
    val style: SpeakingStyle = SpeakingStyle.UNKNOWN
)

/**
 * Short-term conversational memory.
 *
 * Keeps recent dialogue and useful references such as:
 * - current app
 * - last search
 * - last contact
 * - last weather location
 * - previous response
 * - current tone
 * - current speaking style
 *
 * This class is intentionally bounded. Long-term memory belongs in
 * DvexMemoryStore.
 */
class ConversationContext(
    private val maxHistorySize: Int = 12
) {

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

    var lastEstimatedTone: EstimatedTone = EstimatedTone.NEUTRAL
        private set

    var lastSpeakingStyle: SpeakingStyle = SpeakingStyle.UNKNOWN
        private set

    var lastSpokenResponse: String? = null
        private set

    /**
     * Number of recent turns currently available.
     */
    fun historySize(): Int {
        synchronized(history) {
            return history.size
        }
    }

    /**
     * Adds a completed conversation turn.
     */
    fun update(
        input: String,
        intent: DvexIntent,
        toolResult: DvexToolResult,
        language: DetectedLanguage,
        tone: EstimatedTone = EstimatedTone.NEUTRAL,
        spokenResponse: String? = null,
        style: SpeakingStyle = detectSpeakingStyle(input)
    ) {
        lastIntent = intent
        lastLanguage = language
        lastEstimatedTone = tone

        if (style != SpeakingStyle.UNKNOWN) {
            lastSpeakingStyle = style
        }

        if (!spokenResponse.isNullOrBlank()) {
            lastSpokenResponse = spokenResponse
        }

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

            else -> Unit
        }

        synchronized(history) {

            if (history.size >= maxHistorySize) {
                history.removeFirst()
            }

            history.addLast(
                ContextTurn(
                    userInput = input,
                    intent = intent,
                    toolName = toolResult.toolName,
                    tone = tone,
                    language = language,
                    style = style
                )
            )
        }
    }

    /**
     * Returns a snapshot so callers cannot modify internal history.
     */
    fun getRecentHistory(): List<ContextTurn> {
        synchronized(history) {
            return history.toList()
        }
    }

    /**
     * Returns the most recent user message.
     */
    fun getLastUserInput(): String? {
        synchronized(history) {
            return history.lastOrNull()?.userInput
        }
    }

    /**
     * Returns the last few user messages.
     */
    fun getRecentUserInputs(limit: Int = 5): List<String> {
        synchronized(history) {
            return history
                .takeLast(limit.coerceAtLeast(1))
                .map { it.userInput }
        }
    }

    /**
     * Detects whether the current message is likely continuing
     * the previous conversation.
     */
    fun looksLikeFollowUp(input: String): Boolean {
        val lower = input
            .lowercase(Locale.ROOT)
            .trim()

        if (lower.isBlank()) return false

        val shortFollowUp = lower.length <= 35

        return shortFollowUp && (
            lower.endsWith("?") ||
            lower == "tomorrow" ||
            lower == "today" ||
            lower == "why" ||
            lower == "how" ||
            lower == "what about it" ||
            lower == "what about that" ||
            lower == "and then" ||
            lower == "then" ||
            lower == "again" ||
            lower == "repeat" ||
            lower == "seri" ||
            lower == "sari" ||
            lower == "okay" ||
            lower == "ok" ||
            lower == "puriyala" ||
            lower == "enna" ||
            lower == "why da" ||
            lower == "aprom"
        )
    }

    /**
     * Resolves context-dependent follow-up inputs.
     */
    fun resolveContextualFollowUp(cleanInput: String): DvexIntent? {

        val lower = cleanInput
            .lowercase(Locale.ROOT)
            .trim()
            .trimEnd('?', '.', '!')

        // -------------------------------------------------------------
        // 0. Repeat last response
        // -------------------------------------------------------------

        if (
            lower == "repeat that" ||
            lower == "say that again" ||
            lower == "what did you say" ||
            lower == "repeat" ||
            lower == "say again" ||
            lower == "enna sonna" ||
            lower == "enna sonneenga" ||
            lower == "marubadiyum sollu" ||
            lower == "marupadiyum sollu" ||
            lower == "திரும்ப சொல்லு"
        ) {
            val lastMsg = lastSpokenResponse

            if (!lastMsg.isNullOrBlank()) {
                return DvexIntent.Conversation(lastMsg)
            }
        }

        // -------------------------------------------------------------
        // 1. Weather follow-up
        // -------------------------------------------------------------

        if (
            lower == "tomorrow" ||
            lower == "what about tomorrow" ||
            lower == "how about tomorrow" ||
            lower == "and tomorrow" ||
            lower == "naalai" ||
            lower == "naalaiku" ||
            lower.contains("naalai")
        ) {

            if (
                lastWeatherLocation != null ||
                lastIntent is DvexIntent.GetWeather
            ) {
                val city = lastWeatherLocation ?: "Chennai"

                return DvexIntent.GetWeather(
                    location = city,
                    isTomorrow = true
                )
            }
        }

        // -------------------------------------------------------------
        // 2. Contact follow-up
        // -------------------------------------------------------------

        if (lastContactRecipient != null) {

            if (
                lower == "call him" ||
                lower == "call her" ||
                lower == "call" ||
                lower == "avanukku call pannu" ||
                lower == "avalukku call pannu"
            ) {
                return DvexIntent.CallContact(
                    lastContactRecipient!!
                )
            }

            if (
                lower.startsWith("message him ") ||
                lower.startsWith("message her ") ||
                lower.startsWith("text him ") ||
                lower.startsWith("text her ")
            ) {

                val message = extractFollowUpMessage(lower)

                if (message.isNotBlank()) {
                    return DvexIntent.SendMessage(
                        recipient = lastContactRecipient!!,
                        messageText = message
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // 3. Search follow-up inside active app
        // -------------------------------------------------------------

        val currentApp = lastActiveApp?.lowercase(Locale.ROOT)

        if (
            currentApp != null &&
            (
                lower.startsWith("search for ") ||
                lower.startsWith("search ") ||
                lower.startsWith("find ")
            )
        ) {

            val query = lower
                .removePrefix("search for ")
                .removePrefix("search ")
                .removePrefix("find ")
                .trim()

            if (query.isNotEmpty()) {

                return when {

                    currentApp.contains("youtube") ||
                        currentApp == "yt" -> {

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

    /**
     * Detects the user's current communication style.
     *
     * This is deliberately lightweight and conservative.
     */
    private fun detectSpeakingStyle(input: String): SpeakingStyle {

        val text = input.trim()

        if (text.isBlank()) {
            return SpeakingStyle.UNKNOWN
        }

        val lower = text.lowercase(Locale.ROOT)

        // Explicit short-answer preference
        if (
            lower.contains("short ah sollu") ||
            lower.contains("short-a sollu") ||
            lower.contains("short ah") ||
            lower.contains("brief ah") ||
            lower.contains("brief-a") ||
            lower.contains("just answer") ||
            lower.contains("simple ah sollu")
        ) {
            return SpeakingStyle.SHORT
        }

        // Explicit detailed preference
        if (
            lower.contains("detail ah sollu") ||
            lower.contains("detailed ah") ||
            lower.contains("full ah explain") ||
            lower.contains("step by step") ||
            lower.contains("explain clearly")
        ) {
            return SpeakingStyle.DETAILED
        }

        // Serious wording
        if (
            lower.contains("important") ||
            lower.contains("serious") ||
            lower.contains("careful") ||
            lower.contains("don't joke") ||
            lower.contains("joke pannatha")
        ) {
            return SpeakingStyle.SERIOUS
        }

        // Playful / friendly style
        if (
            lower.contains("da") ||
            lower.contains("dei") ||
            lower.contains("bro") ||
            lower.contains("😂") ||
            lower.contains("🤣") ||
            lower.contains("lol") ||
            lower.contains("haha")
        ) {
            return SpeakingStyle.PLAYFUL
        }

        if (
            lower.contains("please") ||
            lower.contains("thanks") ||
            lower.contains("thank you") ||
            lower.contains("nandri")
        ) {
            return SpeakingStyle.FRIENDLY
        }

        return SpeakingStyle.CASUAL
    }

    private fun extractFollowUpMessage(lower: String): String {

        return when {
            lower.startsWith("message him ") ->
                lower.removePrefix("message him ").trim()

            lower.startsWith("message her ") ->
                lower.removePrefix("message her ").trim()

            lower.startsWith("text him ") ->
                lower.removePrefix("text him ").trim()

            lower.startsWith("text her ") ->
                lower.removePrefix("text her ").trim()

            else -> ""
        }
    }

    /**
     * Clears short-term conversation state.
     *
     * This does NOT delete persistent D-VEX memory.
     */
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
        lastEstimatedTone = EstimatedTone.NEUTRAL
        lastSpeakingStyle = SpeakingStyle.UNKNOWN
        lastSpokenResponse = null
    }
}
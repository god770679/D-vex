package com.example.brain

import java.util.Locale

enum class EstimatedTone {
    NEUTRAL,
    HAPPY,
    SAD,
    FRUSTRATED,
    URGENT,
    CONFUSED,
    EXCITED
}

data class ContextTurn(
    val userInput: String,
    val intent: DvexIntent,
    val toolName: String,
    val timestamp: Long,
    val tone: EstimatedTone,
    val language: DetectedLanguage
)

class ConversationContext(
    private val maxHistorySize: Int = 10
) {

    private val history = mutableListOf<ContextTurn>()

    var lastActiveApp: String? = null
        private set

    var lastQuery: String? = null
        private set

    var lastIntent: DvexIntent? = null
        private set

    var lastLanguage: DetectedLanguage? = null
        private set

    var lastWeatherLocation: String? = null
        private set

    var lastContactRecipient: String? = null
        private set

    var lastEstimatedTone: EstimatedTone =
        EstimatedTone.NEUTRAL
        private set

    var lastSpokenResponse: String? = null
        private set

    var lastUserInput: String? = null
        private set

    fun update(
        userInput: String,
        intent: DvexIntent,
        toolName: String,
        language: DetectedLanguage,
        tone: EstimatedTone,
        spokenResponse: String
    ) {

        lastUserInput = userInput
        lastIntent = intent
        lastLanguage = language
        lastEstimatedTone = tone
        lastSpokenResponse = spokenResponse

        val turn = ContextTurn(
            userInput = userInput,
            intent = intent,
            toolName = toolName,
            timestamp = System.currentTimeMillis(),
            tone = tone,
            language = language
        )

        history.add(turn)

        if (history.size > maxHistorySize) {
            history.removeAt(0)
        }

        updateIntentContext(intent, userInput)
    }

    private fun updateIntentContext(
        intent: DvexIntent,
        userInput: String
    ) {

        when (intent) {

            is DvexIntent.OpenApp -> {
                lastActiveApp = extractValue(
                    userInput,
                    listOf(
                        "open ",
                        "launch ",
                        "start ",
                        "open app "
                    )
                )
            }

            is DvexIntent.CloseApp -> {
                lastActiveApp = extractValue(
                    userInput,
                    listOf(
                        "close ",
                        "stop ",
                        "exit "
                    )
                )
            }

            is DvexIntent.SearchWeb -> {
                lastQuery = userInput
            }

            is DvexIntent.GetWeather -> {
                lastWeatherLocation =
                    extractWeatherLocation(intent, userInput)
            }

            is DvexIntent.SendMessage -> {
                lastContactRecipient =
                    extractContactName(userInput)
            }

            is DvexIntent.MakeCall -> {
                lastContactRecipient =
                    extractContactName(userInput)
            }

            else -> Unit
        }
    }

    private fun extractWeatherLocation(
        intent: DvexIntent,
        userInput: String
    ): String? {

        /*
         * First try to read a possible location
         * property from the intent.
         */
        try {

            val fields = intent::class.java.declaredFields

            for (field in fields) {

                field.isAccessible = true

                val name = field.name.lowercase(
                    Locale.getDefault()
                )

                if (
                    name.contains("location") ||
                    name.contains("city") ||
                    name.contains("place")
                ) {

                    val value =
                        field.get(intent)?.toString()

                    if (!value.isNullOrBlank()) {
                        return value.trim()
                    }
                }
            }

        } catch (_: Exception) {
            // Ignore reflection errors.
        }

        val text = userInput
            .trim()

        val markers = listOf(
            "weather in ",
            "weather at ",
            "weather for ",
            "climate in ",
            "climate at ",
            "temperature in ",
            "temperature at ",
            "weather ",
            "வானிலை "
        )

        for (marker in markers) {

            if (
                text.lowercase(Locale.getDefault())
                    .startsWith(marker)
            ) {

                val location = text
                    .substring(marker.length)
                    .trim()

                if (location.isNotBlank()) {
                    return location
                }
            }
        }

        return null
    }

    private fun extractContactName(
        input: String
    ): String? {

        val text = input.trim()

        val patterns = listOf(
            "call ",
            "call to ",
            "message ",
            "text ",
            "send message to ",
            "send a message to "
        )

        for (pattern in patterns) {

            if (
                text.lowercase(Locale.getDefault())
                    .startsWith(pattern)
            ) {

                val name = text
                    .substring(pattern.length)
                    .trim()

                if (name.isNotBlank()) {
                    return name
                }
            }
        }

        return null
    }

    private fun extractValue(
        input: String,
        prefixes: List<String>
    ): String? {

        val text = input.trim()

        for (prefix in prefixes) {

            if (
                text.lowercase(Locale.getDefault())
                    .startsWith(prefix)
            ) {

                val value = text
                    .substring(prefix.length)
                    .trim()

                if (value.isNotBlank()) {
                    return value
                }
            }
        }

        return null
    }

    fun getRecentHistory(): List<ContextTurn> {
        return history.toList()
    }

    fun getRecentUserInputs(): List<String> {
        return history.map { it.userInput }
    }

    fun getLastTurn(): ContextTurn? {
        return history.lastOrNull()
    }

    fun hasPreviousConversation(): Boolean {
        return history.isNotEmpty()
    }

    fun hasPreviousContact(): Boolean {
        return !lastContactRecipient.isNullOrBlank()
    }

    fun hasPreviousApp(): Boolean {
        return !lastActiveApp.isNullOrBlank()
    }

    fun hasPreviousSearch(): Boolean {
        return !lastQuery.isNullOrBlank()
    }

    fun hasPreviousWeather(): Boolean {
        return !lastWeatherLocation.isNullOrBlank()
    }

    fun resolveContextualFollowUp(
        userInput: String
    ): String? {

        val text = userInput
            .lowercase(Locale.getDefault())
            .trim()

        if (
            text == "again" ||
            text == "repeat" ||
            text.contains("say again") ||
            text.contains("thirumba sollu") ||
            text.contains("marubadi sollu")
        ) {
            return lastSpokenResponse
        }

        if (
            text.contains("same app") ||
            text.contains("that app") ||
            text.contains("andha app")
        ) {
            return lastActiveApp
        }

        if (
            text.contains("same person") ||
            text.contains("that person") ||
            text.contains("andha person")
        ) {
            return lastContactRecipient
        }

        if (
            text.contains("same search") ||
            text.contains("that search") ||
            text.contains("andha search")
        ) {
            return lastQuery
        }

        if (
            text.contains("there") ||
            text.contains("same place") ||
            text.contains("that place")
        ) {
            return lastWeatherLocation
        }

        return null
    }

    fun getContextSummary(): String {

        val parts = mutableListOf<String>()

        lastActiveApp?.let {
            parts.add("Last app: $it")
        }

        lastQuery?.let {
            parts.add("Last search: $it")
        }

        lastWeatherLocation?.let {
            parts.add("Last weather location: $it")
        }

        lastContactRecipient?.let {
            parts.add("Last contact: $it")
        }

        lastEstimatedTone.let {
            parts.add("Last tone: $it")
        }

        lastLanguage?.let {
            parts.add("Last language: $it")
        }

        return if (parts.isEmpty()) {
            "No previous context."
        } else {
            parts.joinToString(" | ")
        }
    }

    fun clear() {

        history.clear()

        lastActiveApp = null
        lastQuery = null
        lastIntent = null
        lastLanguage = null
        lastWeatherLocation = null
        lastContactRecipient = null
        lastEstimatedTone = EstimatedTone.NEUTRAL
        lastSpokenResponse = null
        lastUserInput = null
    }
}
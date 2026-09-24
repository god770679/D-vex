package com.example.brain

import java.util.LinkedList
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

    private val history =
        LinkedList<ContextTurn>()

    var lastActiveApp: String? = null
        private set

    var lastQuery: String? = null
        private set

    var lastIntent: DvexIntent? = null
        private set

    var lastLanguage: DetectedLanguage =
        DetectedLanguage.ENGLISH
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

    // ------------------------------------------------------------
    // Update context
    // ------------------------------------------------------------

    fun update(
        userInput: String,
        intent: DvexIntent,
        toolName: String,
        tone: EstimatedTone,
        language: DetectedLanguage,
        spokenResponse: String
    ) {

        val cleanInput =
            userInput
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        lastUserInput = cleanInput
        lastIntent = intent
        lastLanguage = language
        lastEstimatedTone = tone
        lastSpokenResponse = spokenResponse

        history.addLast(
            ContextTurn(
                userInput = cleanInput,
                intent = intent,
                toolName = toolName,
                timestamp = System.currentTimeMillis(),
                tone = tone,
                language = language
            )
        )

        while (history.size > maxHistorySize) {
            history.removeFirst()
        }

        updateIntentContext(intent)
    }

    // ------------------------------------------------------------
    // Intent-specific context
    // ------------------------------------------------------------

    private fun updateIntentContext(
        intent: DvexIntent
    ) {

        when (intent) {

            is DvexIntent.OpenApp -> {

                lastActiveApp =
                    intent.appName
            }

            is DvexIntent.SearchWeb -> {

                lastQuery =
                    intent.query
            }

            is DvexIntent.GetWeather -> {

                lastWeatherLocation =
                    extractWeatherLocation(intent)
            }

            is DvexIntent.CallContact -> {

                lastContactRecipient =
                    intent.recipient
            }

            is DvexIntent.SendMessage -> {

                lastContactRecipient =
                    intent.recipient
            }

            is DvexIntent.SendEmail -> {

                lastContactRecipient =
                    intent.recipient
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------
    // Weather location helper
    // ------------------------------------------------------------

    private fun extractWeatherLocation(
        intent: DvexIntent.GetWeather
    ): String? {

        return try {

            /*
             * This intentionally avoids depending on a specific
             * property name from GetWeather.
             *
             * If your GetWeather contains a location property,
             * reflection picks it up safely.
             */

            val possibleNames =
                listOf(
                    "location",
                    "city",
                    "place"
                )

            for (name in possibleNames) {

                try {

                    val field =
                        intent.javaClass
                            .declaredFields
                            .firstOrNull {
                                it.name.equals(
                                    name,
                                    ignoreCase = true
                                )
                            }

                    if (field != null) {

                        field.isAccessible = true

                        val value =
                            field.get(intent)
                                ?.toString()
                                ?.trim()

                        if (!value.isNullOrBlank()) {
                            return value
                        }
                    }

                } catch (_: Exception) {
                    // Continue checking.
                }
            }

            null

        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------
    // Recent history
    // ------------------------------------------------------------

    fun getRecentHistory(): List<ContextTurn> {

        return history.toList()
    }

    fun getRecentUserInputs(
        count: Int = 5
    ): List<String> {

        return history
            .takeLast(count)
            .map {
                it.userInput
            }
    }

    fun getLastTurn(): ContextTurn? {

        return history.lastOrNull()
    }

    // ------------------------------------------------------------
    // Contextual follow-up resolver
    // ------------------------------------------------------------

    fun resolveContextualFollowUp(
        cleanInput: String
    ): String? {

        val input =
            cleanInput
                .lowercase(Locale.ROOT)
                .trim()

        if (input.isBlank()) {
            return null
        }

        // --------------------------------------------------------
        // Repeat
        // --------------------------------------------------------

        if (
            input == "repeat" ||
            input == "repeat that" ||
            input == "say again" ||
            input == "again" ||
            input.contains("marubadi") ||
            input.contains("marupadi") ||
            input.contains("thirumba sollu") ||
            input.contains("திரும்ப சொல்லு")
        ) {

            return lastSpokenResponse
        }

        // --------------------------------------------------------
        // Previous app
        // --------------------------------------------------------

        if (
            lastActiveApp != null &&
            (
                input.contains("that app") ||
                input.contains("same app") ||
                input.contains("andha app") ||
                input.contains("antha app") ||
                input.contains("அந்த app")
            )
        ) {

            return lastActiveApp
        }

        // --------------------------------------------------------
        // Previous contact
        // --------------------------------------------------------

        if (
            lastContactRecipient != null &&
            (
                input.contains("him") ||
                input.contains("her") ||
                input.contains("them") ||
                input.contains("that person") ||
                input.contains("avan") ||
                input.contains("ava") ||
                input.contains("avanga") ||
                input.contains("andha person") ||
                input.contains("antha person")
            )
        ) {

            return lastContactRecipient
        }

        // --------------------------------------------------------
        // Previous search
        // --------------------------------------------------------

        if (
            lastQuery != null &&
            (
                input.contains("search that again") ||
                input.contains("search it again") ||
                input.contains("again search") ||
                input.contains("adha search") ||
                input.contains("atha search")
            )
        ) {

            return lastQuery
        }

        // --------------------------------------------------------
        // Weather follow-up
        // --------------------------------------------------------

        if (
            lastWeatherLocation != null &&
            (
                input.contains("tomorrow") ||
                input.contains("next day") ||
                input.contains("naala") ||
                input.contains("naalai") ||
                input.contains("நாளை")
            )
        ) {

            return lastWeatherLocation
        }

        return null
    }

    // ------------------------------------------------------------
    // Previous-context checks
    // ------------------------------------------------------------

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

    // ------------------------------------------------------------
    // Context summary
    // ------------------------------------------------------------

    fun getContextSummary(): String {

        val parts =
            mutableListOf<String>()

        lastActiveApp?.let {
            parts.add("lastApp=$it")
        }

        lastQuery?.let {
            parts.add("lastQuery=$it")
        }

        lastContactRecipient?.let {
            parts.add("lastContact=$it")
        }

        lastWeatherLocation?.let {
            parts.add("lastWeather=$it")
        }

        parts.add(
            "lastTone=$lastEstimatedTone"
        )

        parts.add(
            "lastLanguage=$lastLanguage"
        )

        return if (parts.isEmpty()) {
            "No previous context."
        } else {
            parts.joinToString(" | ")
        }
    }

    // ------------------------------------------------------------
    // Clear short-term context
    // ------------------------------------------------------------

    fun clear() {

        history.clear()

        lastActiveApp = null
        lastQuery = null
        lastIntent = null

        lastLanguage =
            DetectedLanguage.ENGLISH

        lastWeatherLocation = null
        lastContactRecipient = null

        lastEstimatedTone =
            EstimatedTone.NEUTRAL

        lastSpokenResponse = null
        lastUserInput = null
    }
}
package com.example.brain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent memory for D-VEX.
 *
 * Stores only information that is useful across app launches.
 *
 * Short-term conversation -> ConversationContext
 * Long-term user memory  -> DvexMemoryStore
 */
class DvexMemoryStore(
    context: Context
) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Explicit user facts.
     *
     * Example:
     * "My name is Alex"
     * "Remember that I prefer short answers"
     */
    fun rememberFact(fact: String) {

        val clean = fact.trim()

        if (clean.isBlank()) return

        val facts = getFacts().toMutableList()

        // Avoid exact duplicates.
        if (!facts.any { it.equals(clean, ignoreCase = true) }) {
            facts.add(clean)
        }

        saveFacts(facts.takeLast(MAX_FACTS))
    }

    fun getFacts(): List<String> {
        val raw = prefs.getString(KEY_FACTS, null)
            ?: return emptyList()

        return try {

            val json = JSONArray(raw)

            buildList {

                for (i in 0 until json.length()) {
                    val value = json.optString(i)

                    if (value.isNotBlank()) {
                        add(value)
                    }
                }
            }

        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearFacts() {
        prefs.edit()
            .remove(KEY_FACTS)
            .apply()
    }

    /**
     * Save the user's preferred response style.
     */
    fun setPreferredStyle(style: SpeakingStyle) {

        prefs.edit()
            .putString(KEY_STYLE, style.name)
            .apply()
    }

    fun getPreferredStyle(): SpeakingStyle {

        val value = prefs.getString(
            KEY_STYLE,
            SpeakingStyle.UNKNOWN.name
        ) ?: SpeakingStyle.UNKNOWN.name

        return try {
            SpeakingStyle.valueOf(value)
        } catch (_: Exception) {
            SpeakingStyle.UNKNOWN
        }
    }

    /**
     * Optional user name.
     */
    fun setUserName(name: String) {

        val clean = name.trim()

        if (clean.isBlank()) return

        prefs.edit()
            .putString(KEY_USER_NAME, clean)
            .apply()
    }

    fun getUserName(): String? {
        return prefs.getString(KEY_USER_NAME, null)
    }

    /**
     * Stores a lightweight memory summary.
     *
     * This is useful for future contextual personalization.
     */
    fun setConversationSummary(summary: String) {

        if (summary.isBlank()) return

        prefs.edit()
            .putString(KEY_SUMMARY, summary.take(MAX_SUMMARY_LENGTH))
            .apply()
    }

    fun getConversationSummary(): String? {
        return prefs.getString(KEY_SUMMARY, null)
    }

    private fun saveFacts(facts: List<String>) {

        val json = JSONArray()

        facts.forEach {
            json.put(it)
        }

        prefs.edit()
            .putString(KEY_FACTS, json.toString())
            .apply()
    }

    companion object {

        private const val PREFS_NAME = "dvex_long_term_memory"

        private const val KEY_FACTS = "facts"
        private const val KEY_STYLE = "preferred_style"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_SUMMARY = "conversation_summary"

        private const val MAX_FACTS = 50
        private const val MAX_SUMMARY_LENGTH = 2000
    }
}
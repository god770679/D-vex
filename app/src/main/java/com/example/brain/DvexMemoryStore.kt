package com.example.brain

import android.content.Context
import android.util.Log

class DvexMemoryStore(
    context: Context
) {

    companion object {
        private const val TAG = "DvexMemoryStore"
        private const val PREF_NAME = "dvex_memory"

        const val KEY_PREFERRED_NAME = "preferred_name"
        const val KEY_REPLY_STYLE = "reply_style"
        const val KEY_PERSONALITY = "personality"
        const val KEY_LANGUAGE_PREFERENCE = "language_preference"

        const val KEY_USER_PREFERENCE = "user_preference"
        const val KEY_USER_LIKES = "user_likes"
        const val KEY_USER_DISLIKES = "user_dislikes"
    }

    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        )

    // ============================================================
    // SAVE
    // ============================================================

    fun save(
        key: String,
        value: String
    ) {

        if (key.isBlank()) {
            return
        }

        if (value.isBlank()) {
            return
        }

        try {

            preferences.edit()
                .putString(
                    key,
                    value.trim()
                )
                .apply()

            Log.d(
                TAG,
                "Memory saved: $key"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to save memory: $key",
                e
            )
        }
    }

    // ============================================================
    // READ
    // ============================================================

    fun get(
        key: String
    ): String? {

        if (key.isBlank()) {
            return null
        }

        return try {

            preferences
                .getString(key, null)
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to read memory: $key",
                e
            )

            null
        }
    }

    // ============================================================
    // CHECK
    // ============================================================

    fun contains(
        key: String
    ): Boolean {

        return try {

            preferences.contains(key)

        } catch (e: Exception) {

            false
        }
    }

    // ============================================================
    // REMOVE ONE MEMORY
    // ============================================================

    fun remove(
        key: String
    ) {

        if (key.isBlank()) {
            return
        }

        try {

            preferences.edit()
                .remove(key)
                .apply()

            Log.d(
                TAG,
                "Memory removed: $key"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to remove memory: $key",
                e
            )
        }
    }

    // ============================================================
    // CLEAR ALL
    // ============================================================

    fun clearAll() {

        try {

            preferences.edit()
                .clear()
                .apply()

            Log.d(
                TAG,
                "All persistent memory cleared"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to clear all memory",
                e
            )
        }
    }

    // ============================================================
    // PREFERRED NAME
    // ============================================================

    fun savePreferredName(
        name: String
    ) {

        save(
            KEY_PREFERRED_NAME,
            name
        )
    }

    fun getPreferredName(): String? {

        return get(
            KEY_PREFERRED_NAME
        )
    }

    // ============================================================
    // REPLY STYLE
    // ============================================================

    fun saveReplyStyle(
        style: String
    ) {

        save(
            KEY_REPLY_STYLE,
            style
        )
    }

    fun getReplyStyle(): String? {

        return get(
            KEY_REPLY_STYLE
        )
    }

    // ============================================================
    // PERSONALITY
    // ============================================================

    fun savePersonality(
        personality: String
    ) {

        save(
            KEY_PERSONALITY,
            personality
        )
    }

    fun getPersonality(): String? {

        return get(
            KEY_PERSONALITY
        )
    }

    // ============================================================
    // LANGUAGE PREFERENCE
    // ============================================================

    fun saveLanguagePreference(
        language: String
    ) {

        save(
            KEY_LANGUAGE_PREFERENCE,
            language
        )
    }

    fun getLanguagePreference(): String? {

        return get(
            KEY_LANGUAGE_PREFERENCE
        )
    }

    // ============================================================
    // GENERAL USER PREFERENCE
    // ============================================================

    fun saveUserPreference(
        preference: String
    ) {

        save(
            KEY_USER_PREFERENCE,
            preference
        )
    }

    fun getUserPreference(): String? {

        return get(
            KEY_USER_PREFERENCE
        )
    }

    // ============================================================
    // LIKES
    // ============================================================

    fun saveUserLikes(
        likes: String
    ) {

        save(
            KEY_USER_LIKES,
            likes
        )
    }

    fun getUserLikes(): String? {

        return get(
            KEY_USER_LIKES
        )
    }

    // ============================================================
    // DISLIKES
    // ============================================================

    fun saveUserDislikes(
        dislikes: String
    ) {

        save(
            KEY_USER_DISLIKES,
            dislikes
        )
    }

    fun getUserDislikes(): String? {

        return get(
            KEY_USER_DISLIKES
        )
    }

    // ============================================================
    // MEMORY SNAPSHOT
    // ============================================================

    fun getAllMemory(): Map<String, String> {

        return try {

            preferences
                .all
                .mapNotNull { entry ->

                    val key = entry.key
                    val value = entry.value

                    if (
                        value is String &&
                        value.isNotBlank()
                    ) {
                        key to value
                    } else {
                        null
                    }
                }
                .toMap()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to read all memory",
                e
            )

            emptyMap()
        }
    }

    // ============================================================
    // MEMORY SUMMARY
    // ============================================================

    fun getMemorySummary(): String {

        val memory =
            getAllMemory()

        if (memory.isEmpty()) {
            return "No saved memory."
        }

        return memory.entries.joinToString(
            separator = "\n"
        ) {
            "${it.key}: ${it.value}"
        }
    }
}
package com.example.brain

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * D-VEX AI Engine
 *
 * Responsibility:
 * - Connect to Firebase AI Logic / Gemini.
 * - Generate natural-language responses.
 *
 * IMPORTANT:
 * This class does NOT execute device actions.
 * Device actions remain under D-VEX's deterministic tool/router layer.
 */
class DvexAiEngine {

    private val model by lazy {
        Firebase.ai(
            backend = GenerativeBackend.googleAI()
        ).generativeModel(
            "gemini-3.8-flash"
        )
    }

    /**
     * Sends a prompt to Gemini and returns only the generated text.
     *
     * Returns null when the request fails or Gemini returns no text.
     */
    suspend fun generate(prompt: String): String? {
        if (prompt.isBlank()) {
            return null
        }

        return try {
            withContext(Dispatchers.IO) {
                val response = model.generateContent(prompt)

                response.text
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Gemini generation failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "[D-VEX][AI]"
    }
}
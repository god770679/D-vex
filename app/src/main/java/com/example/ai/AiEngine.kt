package com.example.ai

/**
 * Minimal contract for the language model D-VEX uses to phrase natural responses.
 *
 * Implementations must be safe to call from any coroutine: the network call is
 * performed off the main thread by the implementation itself.
 *
 * Contract:
 * - Returns the model's text reply, or null when the engine cannot produce one
 *   (missing key, network failure, timeout, blank reply). Callers are expected
 *   to fall back to a safe hardcoded string — never hang, never go silent.
 * - Implementations must NOT fabricate facts; prompts passed in carry the real
 *   tool data and instruct the model to speak only from it.
 */
interface AiEngine {
  /** Generates a reply for [prompt], or null on any failure/timeout/blank. */
  suspend fun generate(prompt: String): String?
}

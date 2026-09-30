package com.example.ai

import com.example.agent.DvexMcpTool

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

  /**
   * MODEL-FACING HALF OF THE D-VEX TOOL BOUNDARY (model-independent seam).
   *
   * Asks the model to produce a turn for [prompt] while it can SEE the canonical
   * [tools] (from `DvexMcpRegistry`). The returned [AiModelTurn.toolCalls] are the
   * model's REQUESTS only: admission, permission, safety policy, confirmation,
   * execution and verification are D-VEX's job (agent/DvexToolProtocol and the
   * deterministic agent) — never the model's and never this interface's.
   *
   * The default keeps every existing implementation text-only: it simply delegates
   * to [generate] and reports that no tool was selected, so adding this seam could
   * not change any caller's behaviour. [GeminiEngine] overrides it with the real
   * function-declarations dialect; all Gemini JSON details stay inside the
   * ai-layer adapter/codec.
   */
  suspend fun generateWithTools(prompt: String, tools: List<DvexMcpTool>): AiModelTurn =
    AiModelTurn(text = generate(prompt))
}

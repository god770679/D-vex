package com.example.ai

/**
 * Whether D-VEX may spend a cloud AI request right now.
 *
 * Gemini's free tier answers `429 RESOURCE_EXHAUSTED` once the daily
 * `generate_content_free_tier_requests` cap is spent. That cap resets on Google's
 * schedule, not ours, and every request made while it is spent fails identically —
 * so without this state D-VEX re-sent the same doomed request on every single user
 * message and every reply collapsed into the same fallback line.
 */
enum class AiAvailability {
  /** The cloud AI may be called. */
  AVAILABLE,

  /**
   * The plan quota for this key is spent. Recoverable, but not by retrying:
   * Google's free tier reports `limit: 20` per day per project.
   */
  QUOTA_EXHAUSTED,

  /**
   * The cloud AI answered, but not usefully right now: upstream 5xx, rate-limit
   * window, timeout, DNS or TLS failure. Short cooldown, then probe again.
   */
  TEMPORARILY_UNAVAILABLE
}

/**
 * SMALL, SHARED QUOTA GATE. One instance is owned by [com.example.brain.DvexSmartBrain]
 * and handed to both the engine (which reports failures into it) and the response
 * layer (which asks it before deciding what to say).
 *
 * Design rules:
 * - It never invents a reset time. Google's error carries a `RetryInfo`, but for a
 *   daily cap that value describes the per-minute window, NOT when the daily quota
 *   returns — so it is used only to avoid retrying *sooner* than the API asked, and
 *   never as a claim about the quota coming back.
 * - The cooldown is therefore a LOCAL PROBE INTERVAL: a conservative pause after
 *   which D-VEX is allowed to try Gemini once more. It is deliberately not presented
 *   anywhere as "the quota resets at".
 * - [reset] exists so the gate is resettable by hand (and by tests) without waiting.
 *
 * Thread-safe: the engine writes it from an IO dispatcher while the response layer
 * reads it, so all transitions are guarded.
 */
class AiQuotaState(
  private val clock: () -> Long = System::currentTimeMillis,
  /** Conservative probe interval after the daily cap is hit. Not a claimed reset time. */
  private val quotaCooldownMs: Long = DEFAULT_QUOTA_COOLDOWN_MS,
  /** Short pause after a transient upstream/network fault. */
  private val temporaryCooldownMs: Long = DEFAULT_TEMPORARY_COOLDOWN_MS
) {

  @Volatile
  private var availability: AiAvailability = AiAvailability.AVAILABLE

  @Volatile
  private var probeAfterMs: Long = 0L

  @Volatile
  private var lastReason: String = ""

  /**
   * The current state, promoting an elapsed cooldown back to
   * [AiAvailability.AVAILABLE] as a side effect of reading it. D-VEX then makes
   * exactly one real Gemini request; if the quota is still spent, that request
   * puts the gate straight back to [AiAvailability.QUOTA_EXHAUSTED].
   */
  val current: AiAvailability
    get() {
      if (availability != AiAvailability.AVAILABLE && clock() >= probeAfterMs) {
        synchronized(this) {
          if (availability != AiAvailability.AVAILABLE && clock() >= probeAfterMs) {
            availability = AiAvailability.AVAILABLE
            lastReason = ""
            probeAfterMs = 0L
          }
        }
      }
      return availability
    }

  /** True only when a cloud request is allowed. The single gate every caller uses. */
  fun isCloudUsable(): Boolean = current == AiAvailability.AVAILABLE

  /** Why the gate last closed. Diagnostic only; never contains a credential. */
  fun reason(): String = lastReason

  /** A real reply came back: the cloud AI is healthy. */
  fun onCloudSuccess() = synchronized(this) {
    availability = AiAvailability.AVAILABLE
    probeAfterMs = 0L
    lastReason = ""
  }

  /**
   * The plan quota is spent. Records the pause and closes the gate: no further
   * Gemini request is made for the rest of this quota window.
   *
   * [serverRetryAfterMs] is the API's own `RetryInfo`. It can only ever *extend*
   * the local probe interval — a per-minute hint is never treated as the daily
   * cap's reset time.
   */
  fun onQuotaExhausted(serverRetryAfterMs: Long? = null, detail: String = "") = synchronized(this) {
    availability = AiAvailability.QUOTA_EXHAUSTED
    probeAfterMs = clock() + maxOf(quotaCooldownMs, serverRetryAfterMs ?: 0L)
    lastReason = if (detail.isBlank()) "quota exhausted" else "quota exhausted: $detail"
  }

  /** Upstream/network fault: brief pause, then one probe. */
  fun onTemporaryFailure(detail: String = "") = synchronized(this) {
    availability = AiAvailability.TEMPORARILY_UNAVAILABLE
    probeAfterMs = clock() + temporaryCooldownMs
    lastReason = if (detail.isBlank()) "temporarily unavailable" else detail
  }

  /** Manual/automatic clear, e.g. a settings toggle or a test. */
  fun reset() = synchronized(this) {
    availability = AiAvailability.AVAILABLE
    probeAfterMs = 0L
    lastReason = ""
  }

  /** Milliseconds until the next permitted probe; 0 when already usable. */
  fun remainingCooldownMs(): Long {
    val remaining = probeAfterMs - clock()
    return if (availability == AiAvailability.AVAILABLE || remaining <= 0L) 0L else remaining
  }

  companion object {
    /**
     * One hour. Long enough that a spent daily cap is not re-probed on every
     * message, short enough that D-VEX recovers on its own the day after.
     * It is a probe interval, NOT a statement about Google's reset schedule.
     */
    const val DEFAULT_QUOTA_COOLDOWN_MS = 60L * 60L * 1000L

    /** 30s: enough to ride out a 5xx burst or a dropped connection. */
    const val DEFAULT_TEMPORARY_COOLDOWN_MS = 30_000L
  }
}
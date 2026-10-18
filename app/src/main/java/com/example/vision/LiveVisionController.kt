package com.example.vision

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State of the opt-in continuous-vision loop. Pure data — the HUD, the panel and
 * tests all read the same flow. Every field reports what actually happened:
 * [lastInsight] is only ever set from a real analyzer response, [lastError] from
 * a real failure, and [stopReason] explains why the loop is no longer running.
 */
data class LiveVisionState(
  /** True only while the analysis loop is actually running. */
  val active: Boolean = false,
  /** Newest successful continuous-vision insight, or null when none has arrived. */
  val lastInsight: String? = null,
  val lastInsightAt: Long = 0L,
  /** Newest stop/failure reason (cleared on a successful start). */
  val lastError: String? = null,
  /** Frames successfully analyzed since process start. */
  val framesAnalyzed: Long = 0L,
  /** Why the loop stopped most recently ("user", "camera_stopped", ...). */
  val stopReason: String? = null
)

/** Why a start attempt did or did not begin the loop. */
enum class LiveVisionStart {
  /** The loop is now running. */
  STARTED,
  /** Already running — no second loop was created (duplicate call is harmless). */
  ALREADY_RUNNING,
  /** Honest refusal: the camera is not producing frames right now. */
  CAMERA_INACTIVE
}

/**
 * OPT-IN CONTINUOUS VISION (Phase 4).
 *
 * A deliberately small, bounded loop that repeatedly takes the newest captured
 * frame from [DvexVisionManager]'s single capture pipeline, asks a vision model
 * about it, and publishes each real answer through [state] / [onInsight].
 *
 * Bounds (all enforced in code, not by convention):
 *  - ONE loop at a time: [start] is idempotent; a duplicate call returns
 *    [LiveVisionStart.ALREADY_RUNNING] and never spawns a second job.
 *  - ONE frame in flight: the loop is sequential — no frame queue can grow.
 *  - CADENCE: at most one analysis per [intervalMs] (default 5s).
 *  - STALL: if no new frame arrives within [frameTimeoutMs] twice in a row
 *    (camera released, panel closed), the loop stops itself with
 *    "camera_not_producing_frames" — it never spins on stale data.
 *  - FAILURE: [maxConsecutiveFailures] analyzer failures in a row stop the loop
 *    with "analysis_kept_failing" — repeated failures never become a background
 *    hammer of the API.
 *  - CANCELLATION: [stop] cancels and JOINS the job, so after stop() returns the
 *    loop is provably not running and no further [onInsight] callbacks fire
 *    (cleanup contract pinned by tests).
 *
 * This uses only documented, supported Android/Kotlin primitives: CameraX
 * frames (already bound through the supported CameraX lifecycle API), a
 * coroutine SupervisorJob scope, and the same Generative Language REST API the
 * conversation engine uses. No deprecated preview APIs, no background camera
 * access, no foreground-service camera use.
 *
 * Honesty contract: an analyzer failure for one frame is simply that frame's
 * outcome — it is never reported as an insight, and never increments
 * [LiveVisionState.framesAnalyzed].
 *
 * @param cameraActive whether a camera pipeline is currently bound
 * @param awaitFrame suspends for the newest frame after [afterGeneration] or
 *   returns null on timeout (production: DvexVisionManager.awaitFrameNewerThan)
 * @param analyst analyzes one frame; returns the insight text, or null when this
 *   frame could not be analyzed (failure is per-frame, never fabricated)
 * @param onInsight receives every successful insight (wired to conversation context)
 */
class LiveVisionController(
  private val cameraActive: () -> Boolean,
  private val awaitFrame: suspend (afterGeneration: Long, timeoutMs: Long) -> FrameCapture?,
  private val analyst: suspend (FrameCapture) -> String?,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val intervalMs: Long = DEFAULT_INTERVAL_MS,
  private val frameTimeoutMs: Long = DEFAULT_FRAME_TIMEOUT_MS,
  private val maxMisses: Int = DEFAULT_MAX_MISSES,
  private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
  private val onInsight: (String) -> Unit = {},
  private val clock: () -> Long = System::currentTimeMillis
) {

  private val lock = Any()
  private var loopJob: Job? = null

  private val _state = MutableStateFlow(LiveVisionState())
  val state: StateFlow<LiveVisionState> = _state.asStateFlow()

  fun isActive(): Boolean = synchronized(lock) { loopJob?.isActive == true }

  /**
   * Starts the loop. Idempotent: a duplicate call never creates a second loop.
   * Refuses honestly when the camera is not bound (frames would never arrive).
   */
  fun start(): LiveVisionStart = synchronized(lock) {
    if (loopJob?.isActive == true) {
      Log.i(TAG, "start() ignored — continuous vision already active")
      return LiveVisionStart.ALREADY_RUNNING
    }
    if (!cameraActive()) {
      Log.w(TAG, "start() refused — camera is not producing frames right now")
      _state.value = _state.value.copy(
        active = false,
        lastError = "Camera is not running. Open the HUD camera panel first.",
        stopReason = "camera_inactive"
      )
      return LiveVisionStart.CAMERA_INACTIVE
    }
    _state.value = _state.value.copy(active = true, lastError = null, stopReason = null)
    loopJob = scope.launch { runLoop(coroutineContext[Job]) }
    Log.i(TAG, "Continuous vision started (interval=${intervalMs}ms, frameTimeout=${frameTimeoutMs}ms)")
    return LiveVisionStart.STARTED
  }

  /**
   * Stops the loop and JOINS it: when this returns, the loop is not running and
   * no further insights will be delivered. Idempotent — returns true only when a
   * loop was actually stopped.
   */
  suspend fun stop(reason: String = "user"): Boolean {
    val job = synchronized(lock) {
      val current = loopJob
      loopJob = null
      current
    }
    if (job == null || !job.isActive) {
      _state.value = _state.value.copy(active = false)
      return false
    }
    job.cancelAndJoin()
    _state.value = _state.value.copy(active = false, stopReason = reason)
    Log.i(TAG, "Continuous vision stopped (reason=$reason)")
    return true
  }

  /**
   * Runs on [scope]; every exit path publishes the honest terminal state.
   * [myJob] identifies THIS loop so a late `finally` can never clear a newer
   * loop that a concurrent start() may have installed.
   */
  private suspend fun runLoop(myJob: Job?) {
    val job = myJob ?: return
    var lastGeneration = -1L
    var misses = 0
    var failures = 0
    try {
      while (job.isActive) {
        val frame = awaitFrame(lastGeneration, frameTimeoutMs)
        if (frame == null) {
          misses++
          if (misses >= maxMisses) {
            stopFromLoop("camera_not_producing_frames",
              "The camera stopped producing frames — continuous vision ended.")
            return
          }
          continue
        }
        misses = 0
        lastGeneration = frame.generation

        val insight = try {
          analyst(frame)
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Frame analysis threw: ${e.javaClass.simpleName}")
          null
        }

        if (insight.isNullOrBlank()) {
          failures++
          if (failures >= maxConsecutiveFailures) {
            stopFromLoop("analysis_kept_failing",
              "Frame analysis kept failing — continuous vision ended.")
            return
          }
          continue
        }
        failures = 0

        _state.value = _state.value.copy(
          active = true,
          lastInsight = insight,
          lastInsightAt = clock(),
          framesAnalyzed = _state.value.framesAnalyzed + 1
        )
        try {
          onInsight(insight)
        } catch (e: Exception) {
          Log.w(TAG, "Insight sink threw; loop continues: ${e.javaClass.simpleName}")
        }

        // Cadence bound: the NEXT frame may only be requested after this delay.
        delay(intervalMs)
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      // Normal stop() path — state is published by stop().
      throw e
    } finally {
      // Clear OUR slot only: a newer loop installed by a concurrent start() wins.
      synchronized(lock) {
        if (myJob != null && loopJob === myJob) loopJob = null
      }
    }
  }

  /** Loop-initiated stop: publishes the terminal state once the job unwinds. */
  private fun stopFromLoop(reason: String, error: String) {
    Log.w(TAG, "Continuous vision self-stopped (reason=$reason)")
    _state.value = _state.value.copy(
      active = false,
      lastError = error,
      stopReason = reason
    )
    synchronized(lock) { loopJob = null }
  }


  companion object {
    private const val TAG = "[D-VEX][LIVE-VISION]"

    /** Default cadence: one analysis every 5s (bounded API + battery load). */
    const val DEFAULT_INTERVAL_MS = 5_000L

    /** Production frames arrive at ~2fps; 4s without one means the camera stalled. */
    const val DEFAULT_FRAME_TIMEOUT_MS = 4_000L

    /** Two consecutive frame timeouts stop the loop (camera gone). */
    const val DEFAULT_MAX_MISSES = 2

    /** Three consecutive analyzer failures stop the loop (API broken/quota). */
    const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 3
  }
}

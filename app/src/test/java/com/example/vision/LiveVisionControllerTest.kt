package com.example.vision

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Honesty + bounding contract for the opt-in continuous-vision loop (Phase 3/4).
 *
 * These tests pin the guarantees the rest of D-VEX relies on so repeated failures
 * or a dead camera can never turn into a background hammer of the API, and a
 * stopped loop can never keep analysing or keep firing callbacks:
 *  - ONE loop at a time (a duplicate start never spawns a second job).
 *  - Honest refusal when the camera is not producing frames.
 *  - A frame that fails to analyse is NEVER counted or reported as an insight.
 *  - A stalled camera self-stops with an explicit reason.
 *  - [LiveVisionController.stop] JOINS the job: after it returns, no further
 *    insights fire and the loop is provably not running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveVisionControllerTest {

  private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Before
  fun resetLog() {
    ShadowLog.clear()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun capture(generation: Long) = FrameCapture(
    generation = generation,
    jpeg = byteArrayOf(1, 2, 3),
    timestampMs = generation,
    rotationDegrees = 0
  )

  // --- Start / duplicate protection ----------------------------------------

  @Test
  fun startIsRefusedHonestlyWhenCameraIsNotBound() {
    val controller = LiveVisionController(
      cameraActive = { false },
      awaitFrame = { _, _ -> null },
      analyst = { "unused" },
      scope = scope
    )

    assertEquals(LiveVisionStart.CAMERA_INACTIVE, controller.start())
    val state = controller.state.value
    assertFalse("a refused start must not mark the loop active", state.active)
    assertTrue(
      "refusal must carry a human reason",
      state.lastError?.contains("camera", ignoreCase = true) == true
    )
    assertEquals("camera_inactive", state.stopReason)
  }

  @Test
  fun duplicateStartNeverSpawnsASecondLoop() {
    val concurrentAnalysts = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)
    val controller = LiveVisionController(
      cameraActive = { true },
      awaitFrame = { _, _ -> capture(1L) },
      analyst = {
        val now = concurrentAnalysts.incrementAndGet()
        maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
        delay(20)
        concurrentAnalysts.decrementAndGet()
        "insight"
      },
      scope = scope,
      intervalMs = 5
    )

    assertEquals(LiveVisionStart.STARTED, controller.start())
    assertEquals(LiveVisionStart.ALREADY_RUNNING, controller.start())
    // A third call while the first loop is still alive is still a no-op.
    assertEquals(LiveVisionStart.ALREADY_RUNNING, controller.start())

    runBlocking { withTimeout(2_000) { while (controller.state.value.framesAnalyzed < 2) delay(10) } }
    runBlocking { controller.stop("test") }

    assertTrue(
      "a duplicate start must never run two analysts at once (was ${maxConcurrent.get()})",
      maxConcurrent.get() <= 1
    )
  }

  // --- Honest per-frame accounting -----------------------------------------

  @Test
  fun failedAnalysisIsNeverCountedNorDelivered() = runBlocking {
    val delivered = AtomicInteger(0)
    val controller = LiveVisionController(
      cameraActive = { true },
      awaitFrame = { after, _ -> capture(after + 1) },
      analyst = { null }, // every frame fails to analyse
      scope = scope,
      intervalMs = 1,
      maxConsecutiveFailures = 3,
      onInsight = { delivered.incrementAndGet() }
    )

    controller.start()
    withTimeout(2_000) { while (controller.state.value.active) delay(10) }

    assertEquals("a failed frame must never increment the analysed count", 0L, controller.state.value.framesAnalyzed)
    assertEquals("a failed frame must never reach the insight sink", 0, delivered.get())
    assertEquals("analysis_kept_failing", controller.state.value.stopReason)
    assertFalse(controller.isActive())
  }

  @Test
  fun successfulInsightsAreCountedAndDeliveredInOrder() = runBlocking {
    val delivered = AtomicInteger(0)
    val controller = LiveVisionController(
      cameraActive = { true },
      awaitFrame = { after, _ -> capture(after + 1) },
      analyst = { frame -> "insight-${frame.generation}" },
      scope = scope,
      intervalMs = 1,
      maxConsecutiveFailures = 3,
      onInsight = { delivered.incrementAndGet() }
    )

    controller.start()
    withTimeout(2_000) { while (delivered.get() < 2) delay(10) }
    controller.stop("test")

    assertTrue(controller.state.value.framesAnalyzed >= 2)
    assertTrue(delivered.get() >= 2)
  }

  // --- Stall (dead camera) self-stop ---------------------------------------

  @Test
  fun stalledCameraSelfStopsWithExplicitReason() = runBlocking {
    val controller = LiveVisionController(
      cameraActive = { true },
      awaitFrame = { _, _ -> null }, // camera never delivers a frame
      analyst = { "unused" },
      scope = scope,
      frameTimeoutMs = 20,
      maxMisses = 2
    )

    controller.start()
    withTimeout(2_000) { while (controller.state.value.active) delay(10) }

    assertEquals("camera_not_producing_frames", controller.state.value.stopReason)
    assertFalse(controller.isActive())
    assertTrue(controller.state.value.lastError != null)
  }

  // --- Cancellation contract -----------------------------------------------

  @Test
  fun stopJoinsTheLoopAndNoFurtherInsightsFire() = runBlocking {
    val delivered = AtomicInteger(0)
    val controller = LiveVisionController(
      cameraActive = { true },
      awaitFrame = { after, _ -> capture(after + 1) },
      analyst = { "insight" },
      scope = scope,
      intervalMs = 5,
      onInsight = { delivered.incrementAndGet() }
    )

    controller.start()
    withTimeout(2_000) { while (delivered.get() < 1) delay(10) }

    val stopped = controller.stop("user")
    assertTrue("stop() must report that it stopped a running loop", stopped)
    val atStop = delivered.get()

    // The loop is provably not running: isActive is false immediately...
    assertFalse(controller.isActive())
    assertFalse(controller.state.value.active)
    // ...and waiting well past several cadences delivers NOTHING further.
    delay(100)
    assertEquals(
      "no insight may fire after stop() returned",
      atStop,
      delivered.get()
    )
    assertEquals("user", controller.state.value.stopReason)

    // A second stop is honest: nothing left to stop.
    assertFalse(controller.stop("user"))
  }

  @Test
  fun framesStopBeingRequestedOnceTheLoopIsStopped() = runBlocking {
    val requests = AtomicInteger(0)
    val controller = LiveVisionController(
      cameraActive = { true },
      awaitFrame = { after, _ ->
        requests.incrementAndGet()
        delay(5)
        capture(after + 1)
      },
      analyst = { "insight" },
      scope = scope,
      intervalMs = 5
    )

    controller.start()
    withTimeout(2_000) { while (requests.get() < 1) delay(10) }
    controller.stop("user")
    val atStop = requests.get()

    delay(80)
    assertEquals(
      "the stopped loop must never ask for another frame",
      atStop,
      requests.get()
    )
  }
}

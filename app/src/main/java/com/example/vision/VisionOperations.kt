package com.example.vision

import com.example.ai.GeminiVisionEngine
import com.example.ai.VisionAnalysis

/**
 * The camera-vision capability the agent's action handlers are allowed to use
 * (Phase 4). Handlers depend on this interface — never on CameraX or the vision
 * engine directly — so tests can supply a fake and prove routing, denial,
 * failure, duplicate-call and cleanup behaviour on a device-less JVM.
 *
 * Every method reports what actually happened: a capture that could not be
 * performed returns an honest [VisionAnalysis] error, never a fabricated
 * description.
 */
interface VisionOperations {

  /** True only while a camera pipeline is genuinely bound and producing frames. */
  fun isCameraActive(): Boolean

  /**
   * Captures a fresh frame from the single capture pipeline and analyzes it with
   * the vision model. The returned [VisionAnalysis] is either a real model
   * answer or an honest error — never a guess.
   */
  suspend fun captureAndAnalyze(question: String): VisionAnalysis

  /**
   * Parsed grounded label for a successful analysis, if the model's reply could be
   * read that way. When this is null, the answer should be shown/quoted as the
   * model's free-text reply (with an honest caveat if the image was unclear).
   *
   * This is bound to the frame generation it was produced from, so it is never
   * shown for a stale or unrelated previous frame.
   */
  suspend fun analyzeAndParseLabels(question: String): VisionParseResult =
    // Default for seams that only implement raw capture (fakes, simple wiring):
    // honest analysis with no structured label — never an invented one.
    VisionParseResult(captureAndAnalyze(question), null)

  /**
   * Starts the opt-in continuous-vision loop.
   * Returns [LiveVisionStart.ALREADY_RUNNING] for a duplicate call (no second
   * loop) and [LiveVisionStart.CAMERA_INACTIVE] when no camera is running.
   */
  fun startLiveVision(): LiveVisionStart

  /** Stops the loop; returns true only when a running loop was actually stopped. */
  suspend fun stopLiveVision(): Boolean

  /** Current continuous-vision state (reads the same flow the HUD observes). */
  fun liveVisionState(): LiveVisionState
}

/**
 * Outcome of one capture-and-analyze request with its optional frame-bound label.
 *
 * [analysis] is always the honest result (either the model's words or a named
 * error). [label] is non-null only when the reply could be read into a concise
 * grounded label — and it carries the frame generation it was produced from, so
 * it can never be shown for a stale or unrelated frame.
 */
data class VisionParseResult(
  val analysis: VisionAnalysis,
  val label: FrameBoundLabel? = null
)

/**
 * Production wiring: [DvexVisionManager]'s bounded capture pipeline + its owned
 * [LiveVisionController] + [GeminiVisionEngine] (the real vision-capable model).
 *
 * Frame freshness contract: [captureAndAnalyze] waits for the NEXT analyzed
 * frame (production cadence ~2 fps) rather than reusing an arbitrarily old one,
 * so the answer is always about a current picture. If no frame arrives within
 * [freshFrameTimeoutMs], it reports that honestly.
 */
class DeviceVisionOperations(
  private val manager: DvexVisionManager,
  private val engine: GeminiVisionEngine,
  private val freshFrameTimeoutMs: Long = DEFAULT_FRESH_FRAME_TIMEOUT_MS
) : VisionOperations {

  override fun isCameraActive(): Boolean = manager.isCameraActive()

  override suspend fun captureAndAnalyze(question: String): VisionAnalysis {
    return analyzeAndParseLabels(question).analysis
  }

  override suspend fun analyzeAndParseLabels(question: String): VisionParseResult {
    if (!manager.isCameraActive()) {
      val analysis = VisionAnalysis(
        error = "The camera preview is not running. Open the HUD camera panel, then try again.",
        errorKind = VisionAnalysis.ErrorKind.MALFORMED
      )
      // No camera: clear any previously shown analysis label (never a stale frame).
      manager.publishAnalysisLabel(null)
      return VisionParseResult(analysis, null)
    }
    val baseline = manager.latestFrame.value?.generation ?: -1L
    val frame = manager.awaitFrameNewerThan(baseline, freshFrameTimeoutMs)
      ?: return VisionParseResult(
        VisionAnalysis(
          error = "The camera did not deliver a frame in time — nothing was analyzed.",
          errorKind = VisionAnalysis.ErrorKind.NETWORK
        ),
        null
      )
    val analysis = engine.analyze(frame.jpeg, question)
    return if (analysis.isSuccess) {
      val (answerText, parsedLabel) = VisionLabelParser.parse(analysis.text!!, frame.generation)
      val label = if (parsedLabel != null && parsedLabel.hasAnyGroundedField) parsedLabel else null
      val parsedAnalysis = analysis.copy(text = answerText)
      // Publish the model-backed label bound to the frame it was produced from,
      // so the camera panel can show it; a free-form reply publishes no label.
      manager.publishAnalysisLabel(LabelSnapshot(frame, parsedAnalysis, label))
      VisionParseResult(parsedAnalysis, label)
    } else {
      // A failed analysis clears any previous label — never guess from an old frame.
      manager.publishAnalysisLabel(null)
      VisionParseResult(analysis, null)
    }
  }

  override fun startLiveVision(): LiveVisionStart = manager.liveVision.start()

  override suspend fun stopLiveVision(): Boolean = manager.liveVision.stop("user")

  override fun liveVisionState(): LiveVisionState = manager.liveVision.state.value

  companion object {
    /** Production frames arrive every ~500ms; 3s without one is an honest stall. */
    const val DEFAULT_FRESH_FRAME_TIMEOUT_MS = 3_000L

    /**
     * Default question for a bare capture request (the model supplies its own
     * when the user asked something specific).
     */
    const val DEFAULT_QUESTION = "Describe what is visible in this image in one or two sentences."
  }
}

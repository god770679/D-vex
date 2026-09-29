package com.example.vision

import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/**
 * On-device scene analyzer for D-VEX Vision V1 (Phase 2).
 *
 * Runs ML Kit ImageLabeling (default model) and FaceDetection (fast mode) on each
 * forwarded frame. Strictly on-device: no network calls, no cloud vision, and no
 * identity inference — faces are COUNTED only, never recognized or stored (Phase 4
 * owns consent-gated person memory).
 *
 * Honesty contract:
 * - [SceneAnalysis] carries exactly what ML Kit measured. If a detector returns
 *   nothing, fields stay empty/zero — no guessed placeholders.
 * - A detector failure degrades that one signal to empty; it never fabricates data.
 *
 * Frame lifecycle: [analyze] always closes the [ImageProxy] exactly once, after both
 * detectors settle (success, failure, or exception), so frames are neither leaked
 * nor double-closed.
 */
class SceneAnalyzer {

  /** Minimum label confidence to include in results. */
  private val minLabelConfidence = MIN_LABEL_CONFIDENCE

  /** Max labels reported per frame. */
  private val maxLabels = MAX_LABELS

  private val labeler by lazy {
    ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
  }

  private val faceDetector by lazy {
    FaceDetection.getClient(
      FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()
    )
  }

  /**
   * Analyzes a frame and reports the combined result via [onResult] exactly once.
   * The [ImageProxy] is closed here (never by the caller) after both detectors
   * finish — success, failure, or exception. Both detectors run asynchronously on
   * ML Kit's own executors and are joined with [Tasks.whenAllComplete]; the
   * analyzer thread is never blocked.
   */
  fun analyze(imageProxy: ImageProxy, onResult: (SceneAnalysis) -> Unit) {
    val frameTimestamp = System.currentTimeMillis()
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
      // Unsupported frame format: honest empty result, close, and return.
      Log.w(TAG, "Frame has no media image (format ${imageProxy.format}); skipping")
      onResult(SceneAnalysis(timestamp = frameTimestamp))
      imageProxy.close()
      return
    }

    val inputImage = InputImage.fromMediaImage(
      mediaImage,
      imageProxy.imageInfo.rotationDegrees
    )

    val labelsTask = labeler.process(inputImage)
    val facesTask = faceDetector.process(inputImage)

    Tasks.whenAllComplete(labelsTask, facesTask).addOnCompleteListener { _ ->
      try {
        // Safe to read results here: whenAllComplete fires only after both settle.
        // A failed detector degrades to empty — it never fabricates a result.
        val labels = if (labelsTask.isSuccessful) labelsTask.result else emptyList()
        val faces = if (facesTask.isSuccessful) facesTask.result else emptyList()
        if (!labelsTask.isSuccessful || !facesTask.isSuccessful) {
          Log.e(
            TAG,
            "Detector partial failure — labels ok=${labelsTask.isSuccessful}, faces ok=${facesTask.isSuccessful}: " +
              "${labelsTask.exception ?: facesTask.exception?.message}"
          )
        }

        val analysis = buildSceneAnalysis(
          detectedLabels = labels.map { it.text to it.confidence },
          faceCount = faces.size,
          timestamp = frameTimestamp
        )
        onResult(analysis)
      } catch (e: Exception) {
        Log.e(TAG, "Scene analysis failed", e)
        onResult(SceneAnalysis(timestamp = frameTimestamp))
      } finally {
        imageProxy.close() // exactly once, on every path
      }
    }
  }

  /** Releases ML Kit clients. Call when vision is stopped for good. */
  fun close() {
    try {
      labeler.close()
    } catch (e: Exception) {
      Log.w(TAG, "Labeler close failed", e)
    }
    try {
      faceDetector.close()
    } catch (e: Exception) {
      Log.w(TAG, "Face detector close failed", e)
    }
  }

  /**
   * Pure result-shaping logic shared by [analyze]: filters labels below the
   * confidence threshold, keeps the strongest [MAX_LABELS], counts faces, and
   * surfaces the highest kept label confidence. Takes plain data (not ML Kit
   * types) so unit tests exercise the exact rules the live analyzer applies.
   *
   * @param detectedLabels pairs of (label text, measured confidence 0..1)
   * @param faceCount number of faces the detector found in this frame
   */
  internal fun buildSceneAnalysis(
    detectedLabels: List<Pair<String, Float>>,
    faceCount: Int,
    timestamp: Long
  ): SceneAnalysis {
    val kept = detectedLabels
      .filter { it.second >= minLabelConfidence }
      .sortedByDescending { it.second }
      .take(maxLabels)
      .map { SceneAnalysis.Label(text = it.first, confidence = it.second) }

    return SceneAnalysis(
      labels = kept,
      faceCount = faceCount,
      confidence = kept.maxOfOrNull { it.confidence } ?: 0f,
      timestamp = timestamp
    )
  }

  /** Single-frame analysis payload. Honest by construction — no placeholders. */
  data class SceneAnalysis(
    val labels: List<Label> = emptyList(),
    val faceCount: Int = 0,
    /** Highest label confidence this frame, or 0f when nothing was detected. */
    val confidence: Float = 0f,
    val timestamp: Long = 0L
  ) {
    data class Label(val text: String, val confidence: Float)
  }

  companion object {
    private const val TAG = "[D-VEX][SCENE]"

    /** Threshold used to filter labels — tests must use the same value. */
    const val MIN_LABEL_CONFIDENCE = 0.6f

    /** Max labels kept per frame — tests must use the same value. */
    const val MAX_LABELS = 5
  }
}

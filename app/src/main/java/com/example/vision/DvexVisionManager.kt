package com.example.vision

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Result of a single analyzed camera frame.
 * Honest by design: only measured ML Kit output is reported. Empty labels and a
 * zero faceCount mean the frame genuinely contained nothing above threshold —
 * never a guessed placeholder. [confidence] is the highest label confidence in
 * this frame (0f when no labels were kept), and [timestamp] marks the analysis
 * time. [isReliable] is false whenever the result should be treated as unknown.
 */
data class VisionResult(
  val frameCount: Long = 0L,
  val labels: List<Label> = emptyList(),
  val faceCount: Int = 0,
  /** Highest label confidence in this frame, or 0f if none. */
  val confidence: Float = 0f,
  val analyzerReady: Boolean = false,
  val timestamp: Long = 0L,
  val error: String? = null
) {
  data class Label(val text: String, val confidence: Float)

  /** True only when the analyzer produced meaningful, above-threshold output. */
  val isReliable: Boolean
    get() = error == null && analyzerReady && labels.isNotEmpty()
}

/**
 * D-VEX Vision Manager (Phase 2).
 *
 * Binds CameraX [Preview] + [ImageAnalysis] to a [PreviewView] and exposes the
 * latest analysis as a [StateFlow]. Frames are throttled to ~2 fps and forwarded
 * to [SceneAnalyzer], which runs on-device ML Kit ImageLabeling + FaceDetection
 * and closes each frame exactly once. No person identification or face storage
 * happens here (Phase 4 owns consent-gated person memory).
 *
 * Lifecycle rules:
 * - Call [startCamera] from a Composable that owns [LifecycleOwner] (the HUD).
 * - Call [stopCamera] when the panel leaves composition or permission is revoked.
 *   ML Kit clients stay warm across stop/start cycles since the manager is a
 *   process-wide singleton.
 */
class DvexVisionManager private constructor(
  private val context: Context
) {

  private val _visionResult = MutableStateFlow(VisionResult())
  val visionResult: StateFlow<VisionResult> = _visionResult.asStateFlow()

  private var cameraProvider: ProcessCameraProvider? = null
  private var analysisExecutor: ExecutorService? = null
  private var isBound = false

  private val sceneAnalyzer = SceneAnalyzer()

  private val frameCounter = AtomicLong(0)
  private val lastAnalyzedAt = AtomicLong(0)

  /** Minimum interval between analyzed frames (~2 fps). */
  private val frameIntervalMs = 500L

  /** Starts the camera and binds Preview + ImageAnalysis to the given PreviewView. */
  @OptIn(ExperimentalGetImage::class)
  fun startCamera(
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView
  ) {
    if (isBound) return

    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
      try {
        val provider = future.get()
        cameraProvider = provider

        val preview = Preview.Builder().build().also {
          it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { it.setAnalyzer(analysisExecutor(), ::analyzeFrame) }

        // Unbind any previous use-cases before rebinding to avoid lifecycle crashes.
        provider.unbindAll()
        provider.bindToLifecycle(
          lifecycleOwner,
          CameraSelector.DEFAULT_BACK_CAMERA,
          preview,
          analysis
        )
        isBound = true
        Log.i(TAG, "Camera bound: Preview + ImageAnalysis (KEEP_ONLY_LATEST, ${1000 / frameIntervalMs} fps cap)")
      } catch (e: Exception) {
        Log.e(TAG, "Camera bind failed", e)
        _visionResult.value = VisionResult(error = e.message ?: "camera_bind_failed")
      }
    }, ContextCompat.getMainExecutor(context))
  }

  /** Stops the camera and releases bound use-cases. Safe to call multiple times. */
  fun stopCamera() {
    try {
      cameraProvider?.unbindAll()
    } catch (e: Exception) {
      Log.w(TAG, "unbindAll during stopCamera failed", e)
    }
    isBound = false
  }

  /**
   * Runs on the analyzer executor. Throttles to ~2 fps; forwarded frames are handed
   * to [SceneAnalyzer], which owns closing the [ImageProxy] exactly once. Throttled
   * frames are closed here immediately so the camera pipeline stays healthy.
   */
  private fun analyzeFrame(image: ImageProxy) {
    val now = System.currentTimeMillis()
    val count = frameCounter.incrementAndGet()
    if (now - lastAnalyzedAt.get() < frameIntervalMs) {
      image.close() // throttled: skip this frame entirely (KEEP_ONLY_LATEST discards the backlog)
      return
    }
    lastAnalyzedAt.set(now)

    try {
      // Phase 2: real on-device analysis. SceneAnalyzer closes the frame when both
      // detectors settle (success, failure, or exception) and reports exactly once.
      sceneAnalyzer.analyze(image) { analysis ->
        _visionResult.value = VisionResult(
          frameCount = count,
          labels = analysis.labels.map { VisionResult.Label(it.text, it.confidence) },
          faceCount = analysis.faceCount,
          confidence = analysis.confidence,
          analyzerReady = true,
          timestamp = analysis.timestamp
        )
        Log.i(
          TAG,
          "Frame #$count analyzed: labels=${analysis.labels.joinToString { it.text }} " +
            "faces=${analysis.faceCount} confidence=${analysis.confidence}"
        )
      }
    } catch (e: Exception) {
      // analyze() threw synchronously (before its complete-listener took ownership).
      Log.e(TAG, "Frame analysis error", e)
      _visionResult.value = VisionResult(frameCount = count, error = e.message ?: "analysis_error")
      try {
        image.close()
      } catch (closeError: Exception) {
        Log.w(TAG, "Frame close after analysis error failed", closeError)
      }
    }
  }

  private fun analysisExecutor(): ExecutorService {
    return analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
  }

  companion object {
    private const val TAG = "[D-VEX][VISION]"

    @Volatile
    private var instance: DvexVisionManager? = null

    fun getInstance(context: Context): DvexVisionManager {
      return instance ?: synchronized(this) {
        instance ?: DvexVisionManager(context.applicationContext).also { instance = it }
      }
    }
  }
}

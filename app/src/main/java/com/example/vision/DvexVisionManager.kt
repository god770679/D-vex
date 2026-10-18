package com.example.vision

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
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
 * One captured camera frame, JPEG-encoded and ready for the vision model.
 *
 * [generation] is a monotonically increasing capture counter so callers can ask for
 * a frame NEWER than one they already hold ([DvexVisionManager.awaitFrameNewerThan])
 * and never analyse a stale picture. [jpeg] holds the actual frame bytes handed to
 * the model, [rotationDegrees] the sensor rotation the caller must apply, and
 * [timestampMs] the capture time. This is pure data — no Android camera types — so
 * it is unit-testable on a device-less JVM.
 */
data class FrameCapture(
  val generation: Long,
  val jpeg: ByteArray,
  val timestampMs: Long,
  val rotationDegrees: Int
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is FrameCapture) return false
    return generation == other.generation &&
      timestampMs == other.timestampMs &&
      rotationDegrees == other.rotationDegrees &&
      jpeg.contentEquals(other.jpeg)
  }

  override fun hashCode(): Int {
    var result = generation.hashCode()
    result = 31 * result + jpeg.contentHashCode()
    result = 31 * result + timestampMs.hashCode()
    result = 31 * result + rotationDegrees
    return result
  }
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

  /** JPEG quality for captured frames handed to the vision model. */
  private val jpegQuality = 82

  /** Newest JPEG-encoded frame captured by the analysis pipeline (null before the first). */
  private val _latestFrame = MutableStateFlow<FrameCapture?>(null)
  val latestFrame: StateFlow<FrameCapture?> = _latestFrame.asStateFlow()

  /** The model-backed analysis label currently shown on the camera panel (null = none). */
  private val _analysisLabel = MutableStateFlow<LabelSnapshot?>(null)
  val analysisLabel: StateFlow<LabelSnapshot?> = _analysisLabel.asStateFlow()

  /**
   * Analyst for the opt-in continuous-vision loop: a frame -> its insight text, or
   * null when that frame could not be analyzed. Wired by the layer that owns the
   * vision model ([DeviceVisionOperations] via [setLiveVisionAnalyst]); while unset,
   * the loop honestly reports a per-frame failure and self-stops rather than
   * fabricating insights.
   */
  @Volatile
  private var liveVisionAnalyst: (suspend (FrameCapture) -> String?)? = null

  /**
   * The opt-in continuous-vision loop (Phase 4), owned by this manager so the HUD,
   * the agent and the panel all observe the SAME single loop. Frames come from
   * [awaitFrameNewerThan]; the analyst is whatever [setLiveVisionAnalyst] installed.
   */
  val liveVision: LiveVisionController = LiveVisionController(
    cameraActive = { isBound },
    awaitFrame = { after, timeout -> awaitFrameNewerThan(after, timeout) },
    analyst = { frame -> liveVisionAnalyst?.invoke(frame) }
  )

  /** Installs (or clears) the vision-model analyst used by [liveVision]. */
  fun setLiveVisionAnalyst(analyst: (suspend (FrameCapture) -> String?)?) {
    liveVisionAnalyst = analyst
  }

  /** True only while the CameraX pipeline is genuinely bound and producing frames. */
  fun isCameraActive(): Boolean = isBound

  /**
   * Publishes the model-backed analysis label for the camera panel, or clears it
   * with null (never a stale frame's label). Bound to the frame that produced it.
   */
  fun publishAnalysisLabel(snapshot: LabelSnapshot?) {
    _analysisLabel.value = snapshot
  }

  /**
   * Suspends for the newest captured frame whose generation is strictly greater than
   * [afterGeneration], or returns null after [timeoutMs] when no such frame arrives
   * (camera released / stalled). Never returns a stale frame at or below the caller's
   * baseline, so an analysis is always about a current picture.
   */
  suspend fun awaitFrameNewerThan(afterGeneration: Long, timeoutMs: Long): FrameCapture? {
    val current = _latestFrame.value
    if (current != null && current.generation > afterGeneration) return current
    return withTimeoutOrNull(timeoutMs) {
      _latestFrame.first { it != null && it.generation > afterGeneration }
    }
  }

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

    // Capture a JPEG of this frame for the vision model / continuous-vision loop
    // BEFORE handing the (still-open) ImageProxy to SceneAnalyzer, which closes it
    // when both detectors settle. FrameEncoder reads planes via duplicates, so the
    // buffers SceneAnalyzer consumes are left untouched.
    try {
      encodeFrameCapture(image, count, now)?.let { _latestFrame.value = it }
    } catch (e: Exception) {
      Log.w(TAG, "Frame JPEG encode failed (continuing with label analysis)", e)
    }

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

  /**
   * Encodes one YUV_420_888 frame to a JPEG [FrameCapture]. Pure dimension/plane
   * work is delegated to the unit-tested [FrameEncoder]; this is the thin Android
   * layer that turns NV21 into JPEG bytes. Returns null when the frame shape is not
   * a normal 3-plane YUV_420_888 (never fabricates an empty frame).
   */
  @OptIn(ExperimentalGetImage::class)
  private fun encodeFrameCapture(
    image: ImageProxy,
    generation: Long,
    timestampMs: Long
  ): FrameCapture? {
    val planes = image.planes
    if (planes.size < 3) return null
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null

    val nv21 = FrameEncoder.toNv21(
      width = width,
      height = height,
      y = FrameEncoder.PlaneView(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
      u = FrameEncoder.PlaneView(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
      v = FrameEncoder.PlaneView(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride)
    )
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    if (!yuv.compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)) return null
    val jpeg = out.toByteArray()
    if (jpeg.isEmpty()) return null
    return FrameCapture(
      generation = generation,
      jpeg = jpeg,
      timestampMs = timestampMs,
      rotationDegrees = image.imageInfo.rotationDegrees
    )
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

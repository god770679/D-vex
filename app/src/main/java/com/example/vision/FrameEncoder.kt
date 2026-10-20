package com.example.vision

import java.nio.ByteBuffer

/**
 * Frame encoding helpers for D-VEX camera vision.
 *
 * The NV21 assembly and dimension math here are PURE JVM code (no Android
 * classes), so the exact bytes the camera pipeline produces are unit-testable.
 * The JPEG/Bitmap step sits in [DvexVisionManager] as a thin Android layer.
 *
 * Honesty contract: only bytes actually present in the camera's planes are
 * copied. Row/pixel strides are honored exactly, so a mis-shaped input fails
 * loudly (IndexOutOfBounds) instead of silently producing a corrupted frame.
 */
object FrameEncoder {

  /**
   * One plane of a YUV_420_888 frame. [buffer] is the plane's ByteBuffer as
   * delivered by CameraX; [rowStride] is bytes between rows, [pixelStride] is
   * bytes between pixels (2 for the interleaved chroma planes on virtually all
   * devices).
   */
  data class PlaneView(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int
  )

  /**
   * Converts a YUV_420_888 frame to a tightly packed NV21 byte array
   * (Y plane of width*height, then interleaved V,U chroma of width/2 * height/2).
   *
   * This is the layout `android.graphics.YuvImage` accepts for JPEG compression.
   * Buffers are read through duplicates, so the caller's [ByteBuffer] positions
   * are left untouched (SceneAnalyzer still consumes the same buffers).
   */
  fun toNv21(width: Int, height: Int, y: PlaneView, u: PlaneView, v: PlaneView): ByteArray {
    require(width > 0 && height > 0) { "invalid frame size ${width}x$height" }
    val out = ByteArray(width * height + (width * height) / 2)
    val yBuf = y.buffer.duplicate()

    // --- Y plane: copy each row from rowStride offsets into packed rows. ---
    var pos = 0
    for (row in 0 until height) {
      val rowStart = row * y.rowStride
      for (col in 0 until width) {
        out[pos++] = yBuf.get(rowStart + col)
      }
    }

    // --- Chroma planes: NV21 is interleaved V,U at half resolution. ---
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val vBuf = v.buffer.duplicate()
    val uBuf = u.buffer.duplicate()
    for (row in 0 until chromaHeight) {
      val vRow = row * v.rowStride
      val uRow = row * u.rowStride
      for (col in 0 until chromaWidth) {
        out[pos++] = vBuf.get(vRow + col * v.pixelStride)
        out[pos++] = uBuf.get(uRow + col * u.pixelStride)
      }
    }
    return out
  }

  /**
   * Scales [width]x[height] so the longest side is at most [maxDim], preserving
   * aspect ratio. Returns the original size when it already fits (never up-scales
   * — up-scaling a small frame only inflates bytes, not detail).
   */
  fun fitWithin(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxDim > 0) { "invalid fitWithin($width,$height,$maxDim)" }
    val longest = maxOf(width, height)
    if (longest <= maxDim) return width to height
    val scale = maxDim.toDouble() / longest
    val w = maxOf(1, (width * scale).toInt())
    val h = maxOf(1, (height * scale).toInt())
    return w to h
  }
}

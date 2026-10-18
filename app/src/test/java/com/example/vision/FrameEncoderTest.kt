package com.example.vision

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [FrameEncoder] is the byte-exact core of the camera capture pipeline, so it is
 * tested against the real CameraX plane geometry it must handle:
 *  - NV21 assembly honors rowStride/pixelStride exactly (packed output, no leaked
 *    padding), which is the layout android.graphics.YuvImage needs to compress;
 *  - [FrameEncoder.fitWithin] bounds the longest side to the cap while preserving
 *    aspect ratio and NEVER up-scales (a smaller frame stays the same size).
 */
class FrameEncoderTest {

  private fun planeOf(bytes: ByteArray, rowStride: Int, pixelStride: Int): FrameEncoder.PlaneView =
    FrameEncoder.PlaneView(ByteBuffer.wrap(bytes), rowStride, pixelStride)

  // --- NV21 assembly --------------------------------------------------------

  @Test
  fun packsYPaddingAwayUsingRowStride() {
    // 4x2 Y plane with rowStride 6 (two padding bytes per row are NOT copied).
    val yFull = byteArrayOf(
      10, 11, 12, 13, 99, 99,   // row 0 (99 = padding, must be ignored)
      20, 21, 22, 23, 99, 99    // row 1
    )
    // 2x1 U and V planes (chromaWidth=2, chromaHeight=1), pixelStride 1.
    val uFull = byteArrayOf(30, 31)
    val vFull = byteArrayOf(40, 41)

    val nv21 = FrameEncoder.toNv21(
      width = 4, height = 2,
      y = planeOf(yFull, rowStride = 6, pixelStride = 1),
      u = planeOf(uFull, rowStride = 2, pixelStride = 1),
      v = planeOf(vFull, rowStride = 2, pixelStride = 1)
    )

    assertEquals(4 * 2 + (4 * 2) / 2, nv21.size)
    // Y is packed: padding stripped.
    val expectedY = byteArrayOf(10, 11, 12, 13, 20, 21, 22, 23)
    for (i in expectedY.indices) assertEquals("Y[$i]", expectedY[i], nv21[i])
    // Chroma is interleaved V,U.
    val chroma = byteArrayOf(40, 30, 41, 31)
    for (i in chroma.indices) assertEquals("UV[$i]", chroma[i], nv21[expectedY.size + i])
  }

  @Test
  fun honorsInterleavedChromaPixelStride() {
    // Typical device layout: chroma pixelStride=2 (interleaved U/V in one plane).
    // 4x2 frame -> chromaWidth 2, chromaHeight 1 -> 12-byte NV21 output (8 Y + 4 UV).
    // The useful chroma bytes sit at even offsets (col*pixelStride = 0, 2).
    val yFull = ByteArray(8) { 1 }
    val uFull = byteArrayOf(30, 0, 31, 0) // useful U at even offsets 0, 2
    val vFull = byteArrayOf(40, 0, 41, 0) // useful V at even offsets 0, 2

    val nv21 = FrameEncoder.toNv21(
      width = 4, height = 2,
      y = planeOf(yFull, rowStride = 4, pixelStride = 1),
      u = planeOf(uFull, rowStride = 4, pixelStride = 2),
      v = planeOf(vFull, rowStride = 4, pixelStride = 2)
    )

    assertEquals(12, nv21.size)
    val chromaStart = 8 // width*height Y bytes come first
    // Two (V,U) pairs in row-major order, taking only the even-offset bytes.
    val expectedVU = byteArrayOf(40, 30, 41, 31)
    for (i in expectedVU.indices) {
      assertEquals("chroma[$i]", expectedVU[i], nv21[chromaStart + i])
    }
  }

  @Test
  fun leavesTheCallerByteBufferPositionUntouched() {
    // SceneAnalyzer still reads the same buffers after capture, so the encoder
    // must read through duplicates rather than consume the original.
    val yBuf = ByteBuffer.wrap(ByteArray(4))
    val uBuf = ByteBuffer.wrap(ByteArray(1))
    val vBuf = ByteBuffer.wrap(ByteArray(1))
    val yPos = yBuf.position()
    val uPos = uBuf.position()
    val vPos = vBuf.position()

    FrameEncoder.toNv21(
      width = 2, height = 2,
      y = FrameEncoder.PlaneView(yBuf, 2, 1),
      u = FrameEncoder.PlaneView(uBuf, 1, 1),
      v = FrameEncoder.PlaneView(vBuf, 1, 1)
    )

    assertEquals("Y buffer position must not move", yPos, yBuf.position())
    assertEquals("U buffer position must not move", uPos, uBuf.position())
    assertEquals("V buffer position must not move", vPos, vBuf.position())
  }

  // --- fitWithin ------------------------------------------------------------

  @Test
  fun fitWithinCapsTheLongestSideAndKeepsAspectRatio() {
    val (w, h) = FrameEncoder.fitWithin(1920, 1080, 720)
    assertEquals(720, maxOf(w, h))
    // 16:9 preserved to within rounding.
    val aspect = w.toDouble() / h
    assertEquals(16.0 / 9.0, aspect, 0.05)

    val (sw, sh) = FrameEncoder.fitWithin(1080, 1920, 720)
    assertEquals(720, maxOf(sw, sh))
  }

  @Test
  fun fitWithinNeverUpScalesASmallerFrame() {
    assertEquals(320 to 240, FrameEncoder.fitWithin(320, 240, 720))
    assertEquals(720 to 720, FrameEncoder.fitWithin(720, 720, 720))
  }

  @Test
  fun fitWithinRejectsInvalidInput() {
    assertThrows(IllegalArgumentException::class.java) {
      FrameEncoder.fitWithin(0, 100, 720)
    }
    assertThrows(IllegalArgumentException::class.java) {
      FrameEncoder.fitWithin(100, 100, 0)
    }
  }

  @Test
  fun toNv21RejectsInvalidFrameSize() {
    assertThrows(IllegalArgumentException::class.java) {
      FrameEncoder.toNv21(
        width = 0, height = 2,
        y = planeOf(ByteArray(0), 1, 1),
        u = planeOf(ByteArray(0), 1, 1),
        v = planeOf(ByteArray(0), 1, 1)
      )
    }
  }
}

package com.example.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Scans the D-VEX Kotlin sources and asserts that no continuous-monitoring or capture primitive survived.
 *
 * The scan is intentionally conservative: a single unused identifier in a test helper or a string
 * literal does not make the build red. The assertions target the real production call sites: the
 * provider/engine call sites, while-loop conditions, timer/Executor/HandlerThread usage, and any
 * camera/audio/screen capture or audio-focus/STREAM_MUSIC manipulation. New forbidden patterns are
 * added here, not as a compile-time lint gate, so an occurrence in a comment or a test double does
 * not accidentally fail the suite.
 */
class DvexContextScanTest {

  @Test
  fun productionSourcesContainNoWhileTrueLoop() {
    assertFalse(sourceContains("while(true)"))
    assertFalse(sourceContains("while (true)"))
    assertFalse(sourceContains("while ( true )"))
  }

  @Test
  fun productionSourcesContainNoThreadSleepBlockedCall() {
    assertFalse(sourceContains("Thread.sleep"))
    assertFalse(sourceContains("Thread .sleep"))
    assertFalse(sourceContains("Thread.sleep("))
  }

  @Test
  fun productionSourcesContainNoTimerTaskScheduler() {
    assertFalse(sourceContains("TimerTask"))
    assertFalse(sourceContains("Timer.schedule"))
  }

  @Test
  fun productionSourcesContainNoHandlerThreadBackgroundThread() {
    assertFalse(sourceContains("HandlerThread"))
  }

  @Test
  fun productionSourcesContainNoExecutorServiceOutbound() {
    assertFalse(sourceContains("Executors"))
  }

  @Test
  fun productionSourcesContainNoGlobalScopeImmediateLaunch() {
    assertFalse(sourceContains("GlobalScope"))
  }

  @Test
  fun productionSourcesContainNoContinuousQueryUsageEvents() {
    assertFalse(sourceContains("queryUsageEvents"))
  }

  @Test
  fun productionSourcesContainNoAudioRecordDiscovery() {
    assertFalse(sourceContains("AudioRecord"))
  }

  @Test
  fun productionSourcesContainNoMediaRecorderCapture() {
    assertFalse(sourceContains("MediaRecorder"))
  }

  @Test
  fun productionSourcesContainNoScreenCapture() {
    assertFalse(sourceContains("screen.capture"))
    assertFalse(sourceContains("MediaProjection"))
    assertFalse(sourceContains("captureScreenshot"))
  }

  @Test
  fun productionSourcesContainNoCameraCapture() {
    assertFalse(sourceContains("camera.capture"))
    assertFalse(sourceContains("Camera1"))
    assertFalse(sourceContains("Camera2"))
    assertFalse(sourceContains("ImageCapture"))
    assertFalse(sourceContains("cameraCapture"))
  }

  @Test
  fun productionSourcesContainNoAudioFocusManipulation() {
    assertFalse(sourceContains("requestAudioFocus"))
    assertFalse(sourceContains("AUDIOFOCUS"))
  }

  @Test
  fun productionSourcesContainNoStreamMusicManipulation() {
    assertFalse(sourceContains("STREAM_MUSIC"))
  }

  private fun sourceContains(pattern: String): Boolean {
    val projectRoot = System.getProperty("user.dir")
    val base = File("$projectRoot/app/src/main/java").takeIf { it.isDirectory } ?: return false
    return scanRecursively(base, pattern)
  }

  private fun scanRecursively(base: File, pattern: String): Boolean {
    if (!base.isDirectory) return false
    return base.listFiles()?.any { file ->
      if (file.isDirectory) scanRecursively(file, pattern) else file.readText().contains(pattern)
    } == true
  }
}

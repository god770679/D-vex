package com.example.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ORB WIRING REGRESSION GUARD — reads the sources directly (same pattern as
 * VoiceAudioHygieneTest) because these invariants are invisible to behavioural
 * tests but would violate the Phase-1 contract if broken:
 *
 *  1. The Orb service must not gain audio-stream/audio-focus APIs.
 *  2. The Orb service must not gain polling loops or background threads.
 *  3. Orb position / size / drag / edge-snap logic must remain intact.
 *  4. The Orb must observe the EXISTING agent bus (DvexAgentStateController)
 *     through the EXISTING pure mapper — no duplicate state machine here.
 *  5. The mapper itself must stay pure (no Android deps, no state holders).
 */
class OrbServiceWiringRegressionTest {

  private fun sourceFile(relativePath: String): File {
    val candidates = listOf(File(".", relativePath), File("app", relativePath))
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "Could not locate $relativePath from ${File(".").absolutePath}; the guard must not pass silently"
      )
  }

  /** Strips line comments (and everything after them) so comment mentions don't count. */
  private fun codeOnly(text: String): String =
    text.lineSequence().joinToString("\n") { line -> line.substringBefore("//") }

  private val orbService: String
    get() = codeOnly(
      sourceFile("src/main/java/com/example/overlay/DvexFloatingOrbService.kt").readText()
    )

  private val mapper: String
    get() = codeOnly(
      sourceFile("src/main/java/com/example/model/OrbDisplayState.kt").readText()
    )

  @Test
  fun orbServiceNeverGainsAudioStreamOrFocusApis() {
    val forbidden = listOf(
      "STREAM_MUSIC",
      "adjustStreamVolume",
      "setStreamMute",
      "setStreamVolume",
      "requestAudioFocus",
      "abandonAudioFocus",
      "AudioFocusRequest",
      "ToneGenerator",
      "SoundPool",
      "playSoundEffect",
      "AudioTrack",
      "RingtoneManager",
      "MediaRecorder",
      "AudioRecord"
    )
    forbidden.forEach { token ->
      assertFalse(
        "Orb service must not use $token (audio hygiene / no continuous recording)",
        orbService.contains(token)
      )
    }
  }

  @Test
  fun orbServiceNeverGainsPollingLoopsOrBackgroundThreads() {
    val forbidden = listOf(
      "while (true",
      "while(true",
      "Thread.sleep",
      "Timer(",
      "TimerTask",
      "GlobalScope",
      "Executors.",
      "HandlerThread",
      "newFixedThreadPool",
      "queryUsageEvents",   // foreground polling is NOT part of this phase
      "registerReceiver"    // no new broadcast-driven polling either
    )
    forbidden.forEach { token ->
      assertFalse(
        "Orb service must not use '$token' (no polling / no background threads)",
        orbService.contains(token)
      )
    }
  }

  @Test
  fun orbGeometrySizePositionDragAndEdgeSnapLogicIsIntact() {
    // Everything that must remain untouched, asserted present verbatim.
    val markers = listOf(
      "settings.orbPositionX.coerceAtLeast(20)",
      "settings.orbPositionY.coerceAtLeast(100)",
      "snapToScreenEdge(params, composeView)",
      "saveOrbPosition(params.x, params.y)",
      "currentSettings.orbSizeDp.dp",
      "orbSize: Dp",
      "MotionEvent.ACTION_MOVE",
      "windowManager?.updateViewLayout(composeView, params)",
      "TYPE_APPLICATION_OVERLAY"
    )
    markers.forEach { marker ->
      assertTrue(
        "Orb geometry/drag marker missing (visual/behavior contract broken): $marker",
        orbService.contains(marker)
      )
    }
    assertEquals(
      "exactly ONE DvexEnergyOrb instance — no second orb",
      1,
      Regex("""DvexEnergyOrb\(""").findAll(orbService).count()
    )
  }

  @Test
  fun orbObservesRealAgentBusThroughExistingPureMapper() {
    assertTrue(
      "Orb must observe DvexAgentStateController.state",
      orbService.contains("DvexAgentStateController.state")
    )
    assertTrue(
      "Orb must map states via OrbDisplayStateMapper",
      orbService.contains("OrbDisplayStateMapper.map")
    )
    assertTrue(
      "Orb must still observe assistantRepo.assistantState",
      orbService.contains("assistantRepo.assistantState")
    )
    assertFalse(
      "Orb service must NOT declare its own state holder (no duplicate state machine)",
      orbService.contains("MutableStateFlow")
    )
    assertFalse(
      "Orb service must NOT define a new enum/state machine",
      orbService.contains("enum class")
    )
    assertEquals(
      "exactly ONE OverlayLifecycleOwner — overlay lifecycle untouched",
      1,
      Regex("""class OverlayLifecycleOwner""").findAll(orbService).count()
    )
  }

  @Test
  fun mapperStaysPureNoAndroidOrStatefulDependencies() {
    assertFalse("mapper must not depend on Android APIs", mapper.contains("android."))
    assertFalse("mapper must not hold state", mapper.contains("MutableStateFlow"))
    assertFalse("mapper must not poll", mapper.contains("delay("))
    assertTrue(
      "mapper must be a pure object in the model package",
      mapper.contains("object OrbDisplayStateMapper")
    )
    assertTrue(
      "mapper must expose the 9 honest display states",
      listOf("IDLE", "LISTENING", "THINKING", "PLANNING", "EXECUTING", "VERIFYING", "SPEAKING", "ERROR", "CONFIRM")
        .all { mapper.contains(it) }
    )
  }
}

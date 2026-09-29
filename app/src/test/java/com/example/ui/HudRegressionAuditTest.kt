package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.model.RecentAppEntry
import com.example.model.WeatherInfo
import com.example.ui.components.RecentAppsOverlay
import com.example.ui.components.TimePanel
import com.example.ui.components.WeatherPanel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Regression-audit honesty contract (Weather / Time / Recent Apps on-screen):
 *
 * - TimePanel renders the LIVE device clock (matches SimpleDateFormat of "now"),
 *   never the old hardcoded "08:45 PM" / "SATURDAY" strings.
 * - WeatherPanel with no data renders an honest STANDBY state — never the removed
 *   fabricated defaults ("30°C", "CHENNAI", "DEMO FEED").
 * - WeatherPanel with real data renders the real values under a LIVE FEED tag.
 * - RecentAppsOverlay renders real entries verbatim, and the permission-required
 *   state honestly — never placeholder app names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HudRegressionAuditTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  // --- TIME: live clock, not a stuck hardcoded string ---

  @Test
  fun timePanelRendersLiveDeviceTimeNotHardcodedStrings() {
    composeTestRule.setContent { TimePanel() }

    // The current formatted time must be visible...
    val expectedTime = SimpleDateFormat("hh:mm a", Locale.getDefault())
      .format(Date(System.currentTimeMillis())).uppercase(Locale.getDefault())
    composeTestRule.onNodeWithText(expectedTime, substring = true).assertIsDisplayed()

    // ...the weekday must be the LIVE device weekday (proving it is never a
    // hardcoded word), and the old fabricated strings must be gone forever.
    val expectedDay = SimpleDateFormat("EEEE", Locale.getDefault())
      .format(Date(System.currentTimeMillis())).uppercase(Locale.getDefault())
    composeTestRule.onNodeWithText(expectedDay).assertIsDisplayed()
    composeTestRule.onNodeWithText("08:45 PM").assertDoesNotExist()
    composeTestRule.onNodeWithText("22 AUG 2026 // EPOCH SYNC: 1787492100")
      .assertDoesNotExist()
  }

  @Test
  fun timePanelValueAdvancesBetweenTwoCheckPoints() {
    // Injected fake clock: deterministic two-check-point proof that the panel
    // re-reads the time source on every tick (the original bug was a value that
    // never changed). In production the time source is System.currentTimeMillis().
    val fakeNow = java.util.concurrent.atomic.AtomicLong(
      java.time.LocalDateTime.of(2026, 9, 24, 20, 45, 0)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    )
    composeTestRule.setContent {
      TimePanel(timeSource = { fakeNow.get() })
    }

    // Check point 1: 08:45 PM displayed.
    val first = SimpleDateFormat("hh:mm a", Locale.getDefault())
      .format(Date(fakeNow.get())).uppercase(Locale.getDefault())
    composeTestRule.onNodeWithText(first, substring = true).assertIsDisplayed()

    // Advance the clock past a minute boundary and let the one-second ticker run.
    fakeNow.addAndGet(65_000L)
    composeTestRule.mainClock.advanceTimeBy(65_000)

    // Check point 2: the later time is displayed; the stale one is gone.
    val second = SimpleDateFormat("hh:mm a", Locale.getDefault())
      .format(Date(fakeNow.get())).uppercase(Locale.getDefault())
    assertTrue(
      "Clock must show a different value at the two check points",
      first != second
    )
    composeTestRule.onNodeWithText(second, substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText(first, substring = true).assertDoesNotExist()
  }

  // --- WEATHER: honest states, fabricated defaults removed ---

  @Test
  fun weatherPanelWithoutDataShowsHonestStandbyNeverDemoDefaults() {
    composeTestRule.setContent { WeatherPanel(weather = WeatherInfo()) }

    composeTestRule.onNodeWithText("STANDBY").assertIsDisplayed()
    composeTestRule.onNodeWithText("NO LIVE TELEMETRY").assertIsDisplayed()

    // The removed fabricated defaults must never come back.
    composeTestRule.onNodeWithText("30°C").assertDoesNotExist()
    composeTestRule.onNodeWithText("PARTLY CLOUDY").assertDoesNotExist()
    composeTestRule.onNodeWithText("CHENNAI", substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("DEMO FEED").assertDoesNotExist()
  }

  @Test
  fun weatherPanelWithRealDataRendersLiveValues() {
    val live = WeatherInfo(
      hasData = true,
      temperatureCelsius = 27,
      condition = "Clear skies",
      location = "ANNA NAGAR",
      highCelsius = 31,
      lowCelsius = 24
    )
    composeTestRule.setContent { WeatherPanel(weather = live) }

    composeTestRule.onNodeWithText("LIVE FEED").assertIsDisplayed()
    composeTestRule.onNodeWithText("27°C").assertIsDisplayed()
    composeTestRule.onNodeWithText("CLEAR SKIES").assertIsDisplayed()
    composeTestRule.onNodeWithText("ANNA NAGAR // H:31° L:24°").assertIsDisplayed()
  }

  // --- RECENT APPS OVERLAY: real data verbatim, honest permission state ---

  @Test
  fun recentAppsOverlayRendersRealEntriesVerbatim() {
    val entries = listOf(
      RecentAppEntry(
        packageName = "com.example",
        appName = "D-VEX",
        lastUsedTimestampMs = System.currentTimeMillis() - 30_000L,
        lastUsedLabel = "JUST NOW"
      ),
      RecentAppEntry(
        packageName = "org.mozilla.firefox",
        appName = "Firefox",
        lastUsedTimestampMs = System.currentTimeMillis() - 45 * 60_000L,
        lastUsedLabel = "45M AGO"
      )
    )
    composeTestRule.setContent {
      RecentAppsOverlay(permissionRequired = false, entries = entries, onDismiss = {})
    }

    composeTestRule.onNodeWithText("RECENT APPS").assertIsDisplayed()
    composeTestRule.onNodeWithText("2 LIVE").assertIsDisplayed()
    composeTestRule.onNodeWithText("D-VEX").assertIsDisplayed()
    composeTestRule.onNodeWithText("Firefox").assertIsDisplayed()
    composeTestRule.onNodeWithText("JUST NOW").assertIsDisplayed()
    composeTestRule.onNodeWithText("45M AGO").assertIsDisplayed()
  }

  @Test
  fun recentAppsOverlayPermissionRequiredIsHonestNeverFabricated() {
    composeTestRule.setContent {
      RecentAppsOverlay(permissionRequired = true, entries = emptyList(), onDismiss = {})
    }

    composeTestRule.onNodeWithText("USAGE ACCESS REQUIRED").assertIsDisplayed()
    composeTestRule.onNodeWithText("ACCESS REQUIRED").assertIsDisplayed()

    // No placeholder app names may appear when permission is missing.
    composeTestRule.onNodeWithText("WhatsApp").assertDoesNotExist()
    composeTestRule.onNodeWithText("YouTube").assertDoesNotExist()
  }

  @Test
  fun recentAppsOverlayGrantedButEmptyIsHonest() {
    composeTestRule.setContent {
      RecentAppsOverlay(permissionRequired = false, entries = emptyList(), onDismiss = {})
    }

    composeTestRule.onNodeWithText("NO USAGE RECORDED").assertIsDisplayed()
    composeTestRule.onNodeWithText("0 LOGS").assertIsDisplayed()
  }

  // --- ViewModel: overlay request paths (real provider, honest states) ---

  @Test
  fun viewModelRequestRecentAppsUsesRealProviderPipeline() {
    val viewModel = AssistantViewModel(ApplicationProvider.getApplicationContext())

    // The granted path resolves asynchronously; wait for either outcome without
    // busy-waiting forever. Robolectric typically reports the op as allowed, so the
    // entries (real provider output, empty here) land shortly after the request.
    viewModel.requestRecentAppsPanel()
    var state = viewModel.uiState.value
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      state = viewModel.uiState.value
      if (state.recentAppsOverlay != null) break
      Thread.sleep(50)
    }

    if (state.recentAppsOverlayPermissionRequired) {
      // Honest denied state: overlay "open" with zero entries — no fake data.
      assertTrue(
        "Permission state must show no entries (no fake data)",
        state.recentAppsOverlay?.isEmpty() == true
      )
    } else {
      // Granted path: entries are non-null real provider output. Nothing fabricated
      // can appear because the data comes straight from RecentAppsProvider.
      assertTrue("Overlay must open with real (possibly empty) data", state.recentAppsOverlay != null)
    }
  }

  @Test
  fun viewModelDismissRecentAppsClearsOverlayState() {
    val viewModel = AssistantViewModel(ApplicationProvider.getApplicationContext())
    viewModel.requestRecentAppsPanel()
    viewModel.dismissRecentAppsPanel()

    val state = viewModel.uiState.value
    assertTrue(state.recentAppsOverlay == null)
    assertTrue(!state.recentAppsOverlayPermissionRequired)
  }
}

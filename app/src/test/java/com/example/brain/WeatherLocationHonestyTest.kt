package com.example.brain

import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.control.LocationProvider
import com.example.data.remote.RealTimeWebService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression tests for the Bug 4 fix: weather must NEVER fall back to a hardcoded
 * city (previously "Chennai"). Locks in the honesty contract:
 *
 * - A weather request without a spoken city produces a null-location intent that the
 *   router resolves with REAL device location.
 * - Follow-ups ("Tomorrow?") reuse the previously resolved city, or re-resolve device
 *   location when none was resolved — never a default.
 * - Permission denied -> PERMISSION_REQUIRED with a "say a city name" suggestion.
 * - Blank/null city input to the web service returns null (no network, no guessing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeatherLocationHonestyTest {

  private lateinit var detector: IntentDetector
  private lateinit var context: ConversationContext

  @Before
  fun setUp() {
    detector = IntentDetector()
    context = ConversationContext()
  }

  // --- Intent detection: no-city weather stays null, never a default city ---

  @Test
  fun weatherWithoutCityProducesNullLocationIntent() {
    val result = detector.detectIntent("D-VEX, what is the weather?", context)
    val intent = result.intent as? DvexIntent.GetWeather
    assertTrue("Should be GetWeather", intent != null)
    assertNull("No-city weather must carry null location (device location resolves it)", intent!!.location)
    assertFalse(intent.isTomorrow)
  }

  @Test
  fun weatherWithNamedCityStillExtractsCity() {
    val r1 = detector.detectIntent("D-VEX, what is the weather in trichy?", context)
    assertEquals("Trichy", (r1.intent as DvexIntent.GetWeather).location)

    val r2 = detector.detectIntent("D-VEX, weather in chennai", context)
    assertEquals("Chennai", (r2.intent as DvexIntent.GetWeather).location)
  }

  @Test
  fun weatherWithCityFromPrepositionPhraseExtractsCity() {
    val r = detector.detectIntent("D-VEX, what's the temperature for Coimbatore", context)
    assertEquals("Coimbatore", (r.intent as DvexIntent.GetWeather).location)
  }

  // --- Follow-ups: "Tomorrow?" reuses resolved city or re-resolves device location ---

  @Test
  fun tomorrowFollowUpReusesResolvedCity() {
    val first = detector.detectIntent("weather in trichy", context)
    recordWeatherIntent(first.intent as DvexIntent.GetWeather)

    val followUp = detector.detectIntent("tomorrow", context)
    val intent = followUp.intent as? DvexIntent.GetWeather
    assertTrue("Follow-up 'tomorrow' should be GetWeather", intent != null)
    assertEquals("Trichy", intent!!.location)
    assertTrue(intent.isTomorrow)
  }

  @Test
  fun tomorrowFollowUpAfterDeviceLocationWeatherUsesNullNotChennai() {
    // The key regression: device-location weather (null city), then "Tomorrow?"
    // must re-resolve device location — never fall back to "Chennai".
    val first = detector.detectIntent("what is the weather", context)
    recordWeatherIntent(first.intent as DvexIntent.GetWeather)
    assertNull((first.intent as DvexIntent.GetWeather).location)

    val followUp = detector.detectIntent("tomorrow", context)
    val intent = followUp.intent as? DvexIntent.GetWeather
    assertTrue("Follow-up 'tomorrow' should be GetWeather", intent != null)
    assertNull(
      "Follow-up must be null (device location), never the removed 'Chennai' default",
      intent!!.location
    )
    assertTrue(intent.isTomorrow)
  }

  @Test
  fun naalaiFollowUpReusesResolvedCity() {
    val first = detector.detectIntent("weather in madurai", context)
    recordWeatherIntent(first.intent as DvexIntent.GetWeather)

    val followUp = detector.detectIntent("naalaiku", context)
    val intent = followUp.intent as? DvexIntent.GetWeather
    assertTrue("Follow-up 'naalaiku' should be GetWeather", intent != null)
    assertEquals("Madurai", intent!!.location)
    assertTrue(intent.isTomorrow)
  }

  @Test
  fun contextNeverStoresBlankOrNullCity() {
    recordWeatherIntent(DvexIntent.GetWeather(location = null))
    assertNull(context.lastWeatherLocation)

    recordWeatherIntent(DvexIntent.GetWeather(location = "  "))
    assertNull("Blank city must not be stored as last-weather location", context.lastWeatherLocation)

    recordWeatherIntent(DvexIntent.GetWeather(location = "Salem"))
    assertEquals("Salem", context.lastWeatherLocation)
  }

  // --- Router: honest status when location permission is denied (no network touched) ---

  @Test
  fun routerReturnsPermissionRequiredWhenLocationDenied() = runTest {
    val appContext = RuntimeEnvironment.getApplication()
    val router = DvexToolRouter(
      appContext,
      AppLauncherRepository(appContext),
      DeviceControlRepository(appContext, AppLauncherRepository(appContext))
    )

    val result = router.execute(DvexIntent.GetWeather(location = null, isTomorrow = false))

    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, result.status)
    assertEquals("get_weather", result.toolName)
    assertTrue(
      "Spoken text must suggest saying a city name",
      result.spokenText.contains("city", ignoreCase = true)
    )
    assertTrue(
      "Spoken text must mention location permission",
      result.spokenText.contains("location permission", ignoreCase = true)
    )
  }

  @Test
  fun whatIsTheWeatherIsNotParsedAsACity() {
    // Regression for a real bug found by this suite: the preposition regex without
    // word boundaries extracted "Is" from "whAT IS the weather" as a city.
    val r = detector.detectIntent("D-VEX, what is the weather?", context)
    assertNull(
      "'what is the weather' must yield null location (device location), not garbage 'Is'",
      (r.intent as DvexIntent.GetWeather).location
    )
  }

  @Test
  fun tomorrowTodayHereAreNotParsedAsCities() {
    val r1 = detector.detectIntent("D-VEX, weather for tomorrow", context)
    val i1 = r1.intent as DvexIntent.GetWeather
    assertNull("'tomorrow' must not be geocoded as a city", i1.location)
    assertTrue(i1.isTomorrow)

    val r2 = detector.detectIntent("D-VEX, weather in the morning", context)
    val i2 = r2.intent as DvexIntent.GetWeather
    assertNull("'the' after 'in' must not be geocoded as a city", i2.location)

    val r3 = detector.detectIntent("D-VEX, weather at now", context)
    assertNull("'now' must not be geocoded as a city", (r3.intent as DvexIntent.GetWeather).location)
  }

  // --- LocationProvider: honest typed failure without permission ---

  @Test
  fun locationProviderReportsPermissionDeniedWithoutPermission() = runTest {
    val appContext = RuntimeEnvironment.getApplication()
    val provider = LocationProvider(appContext)

    // Robolectric grants no runtime permissions by default.
    assertFalse(provider.hasPermission())

    val result = provider.getLocationResult()
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is LocationProvider.LocationError.PermissionDenied)
  }

  // --- RealTimeWebService: blank/null location is rejected before any network call ---

  @Test
  fun webServiceRejectsNullLocationWithoutGuessing() = runTest {
    val service = RealTimeWebService()
    assertNull("null city must be rejected, never defaulted", service.fetchRealWeather(null))
  }

  @Test
  fun webServiceRejectsBlankLocationWithoutGuessing() = runTest {
    val service = RealTimeWebService()
    assertNull("blank city must be rejected, never defaulted", service.fetchRealWeather("   "))
  }

  /** Mirrors the pipeline: detect + feed through ConversationContext.update(). */
  private fun recordWeatherIntent(intent: DvexIntent) {
    context.update(
      input = "weather",
      intent = intent,
      toolResult = DvexToolResult(
        status = DvexToolStatus.SUCCESS,
        toolName = "get_weather",
        message = "test"
      ),
      language = DetectedLanguage.ENGLISH
    )
  }
}

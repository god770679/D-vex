package com.example.brain

import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.AlarmClock
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * PHASE A: Alarm Set + YouTube Play (system intents, no accessibility).
 *
 *  a. Parseable time phrases produce correctly-parsed SetAlarm intents
 *     (English/Tanglish, am/pm, half-past forms).
 *  b. Unparseable time phrases produce a null-time SetAlarm intent that the
 *     router turns into FAILED — never a guessed default time.
 *  c. YouTube search phrases produce the correctly-encoded search query.
 *  d. Router builds ACTION_SET_ALARM / ACTION_VIEW intents with the expected
 *     extras/data — verified via Robolectric's shadow started-activity queue;
 *     no activity is actually launched.
 *
 * Honesty constraints locked here:
 *  - EXTRA_SKIP_UI stays false (system Clock UI must remain visible).
 *  - Opening search results is the completion — no auto-play of a "first result".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhaseAAlarmAndYoutubeTest {

  private lateinit var detector: IntentDetector
  private lateinit var context: ConversationContext
  private lateinit var appContext: Application
  private lateinit var router: DvexToolRouter

  @Before
  fun setUp() {
    detector = IntentDetector()
    context = ConversationContext()
    appContext = RuntimeEnvironment.getApplication()
    router = DvexToolRouter(
      appContext,
      AppLauncherRepository(appContext),
      DeviceControlRepository(appContext, AppLauncherRepository(appContext))
    )
  }

  private fun startedIntents(): List<Intent> {
    val shadow = Shadows.shadowOf(appContext)
    val drained = mutableListOf<Intent>()
    while (true) {
      val next = shadow.nextStartedActivity ?: break
      drained.add(next)
    }
    return drained
  }

  /** Registers a resolver so the router's ACTION_VIEW dispatch can succeed. */
  private fun registerBrowserResolverFor(url: String) {
    val resolveInfo = ResolveInfo().apply {
      activityInfo = android.content.pm.ActivityInfo().apply {
        packageName = "com.example.testbrowser"
        name = "com.example.testbrowser.BrowserActivity"
      }
    }
    Shadows.shadowOf(appContext.packageManager)
      .addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), resolveInfo)
  }

  // --- a. Parseable time phrases -> correctly-parsed SetAlarm intents ---

  @Test
  fun requirementA_englishSetAlarmParsesHourAndMinute() {
    val r = detector.detectIntent("set an alarm for 6:30 am", context)
    val intent = r.intent as DvexIntent.SetAlarm
    assertEquals(6, intent.hour)
    assertEquals(30, intent.minute)
  }

  @Test
  fun requirementA_tanglishManiKuParsesHour() {
    val r = detector.detectIntent("alarm set pannu 7 mani ku", context)
    val intent = r.intent as DvexIntent.SetAlarm
    assertEquals(7, intent.hour)
    assertEquals(0, intent.minute)
  }

  @Test
  fun requirementA_pmIsConvertedTo24Hour() {
    val r = detector.detectIntent("wake me up at 7:30 pm", context)
    val intent = r.intent as DvexIntent.SetAlarm
    assertEquals(19, intent.hour)
    assertEquals(30, intent.minute)
  }

  @Test
  fun requirementA_halfPastSixParsesAsSixThirty() {
    val r = detector.detectIntent("wake me up at half past 6", context)
    val intent = r.intent as DvexIntent.SetAlarm
    assertEquals(6, intent.hour)
    assertEquals(30, intent.minute)
  }

  // --- b. Unparseable time -> FAILED, never a guessed alarm ---

  @Test
  fun requirementB_noTimePhraseYieldsNullTimeIntent() {
    val r = detector.detectIntent("set an alarm", context)
    val intent = r.intent as DvexIntent.SetAlarm
    assertTrue(
      "unparseable time must carry null hour (never a guessed default)",
      intent.hour == null
    )
  }

  @Test
  fun requirementB_routerReturnsFailedWhenTimeMissing_notAGuessedAlarm() = kotlinx.coroutines.test.runTest {
    val result = router.execute(DvexIntent.SetAlarm(hour = null, minute = null, message = "D-VEX Alarm"))

    assertEquals(DvexToolStatus.FAILED, result.status)
    assertEquals("set_alarm", result.toolName)
    assertTrue(
      "failure message must ask the user to repeat with a clear time",
      result.spokenText.contains("time", ignoreCase = true)
    )
    // Honesty: no intent may be dispatched when there is no parsed time.
    assertTrue(
      "no ACTION_SET_ALARM intent may be started without a parsed time",
      startedIntents().none { it.action == AlarmClock.ACTION_SET_ALARM }
    )
  }

  // --- c. YouTube search phrases -> correctly-encoded query ---

  @Test
  fun requirementC_playOnYouTubeExtractsQuery() {
    val r = detector.detectIntent("play lofi beats on youtube", context)
    val intent = r.intent as DvexIntent.PlayYoutubeVideo
    assertEquals("lofi beats", intent.query)
  }

  @Test
  fun requirementC_youtubeLaPoduExtractsTanglishQuery() {
    val r = detector.detectIntent("youtube la tractor video podu", context)
    val intent = r.intent as DvexIntent.PlayYoutubeVideo
    assertEquals("tractor", intent.query)
  }

  @Test
  fun requirementC_videoPoduImpliesYouTube() {
    val r = detector.detectIntent("lion videos podu", context)
    val intent = r.intent as DvexIntent.PlayYoutubeVideo
    assertEquals("lion", intent.query)
  }

  @Test
  fun requirementC_queryWithSpacesIsCorrectlyEncodedForUrl() {
    val r = detector.detectIntent("play tamil melody songs on youtube", context)
    val intent = r.intent as DvexIntent.PlayYoutubeVideo
    assertEquals("tamil melody songs", intent.query)
    val encoded = Uri.encode(intent.query)
    assertTrue("encoded query must contain no raw spaces", !encoded.contains(" "))
  }

  // --- d. Router intent construction (Robolectric shadow inspection) ---

  @Test
  fun requirementD_setAlarmBuildsActionSetAlarmWithExtrasAndSkipUiFalse() = kotlinx.coroutines.test.runTest {
    val result = router.execute(DvexIntent.SetAlarm(hour = 7, minute = 30, message = "D-VEX Alarm"))
    assertEquals(DvexToolStatus.SUCCESS, result.status)

    val alarmIntent = startedIntents().lastOrNull { it.action == AlarmClock.ACTION_SET_ALARM }
    assertNotNull("an ACTION_SET_ALARM intent must have been started", alarmIntent)
    assertEquals(7, alarmIntent!!.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
    assertEquals(30, alarmIntent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
    assertEquals("D-VEX Alarm", alarmIntent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
    // CRITICAL: the system Clock UI must stay visible (no silent auto-set).
    assertEquals(
      "EXTRA_SKIP_UI must stay false so the user confirms in the Clock app",
      false,
      alarmIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true)
    )
  }

  @Test
  fun requirementD_playYoutubeBuildsViewIntentWithEncodedSearchData() = kotlinx.coroutines.test.runTest {
    val query = "tamil melody songs"
    val searchUrl = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
    registerBrowserResolverFor(searchUrl)

    val result = router.execute(DvexIntent.PlayYoutubeVideo(query = query))
    assertEquals(
      "with a resolver registered, dispatch must succeed",
      DvexToolStatus.SUCCESS,
      result.status
    )

    val ytIntent = startedIntents().lastOrNull { it.action == Intent.ACTION_VIEW }
    assertNotNull("an ACTION_VIEW intent must have been started", ytIntent)
    val data = ytIntent!!.data
    assertNotNull(data)
    assertEquals("https", data!!.scheme)
    assertEquals("www.youtube.com", data.host)
    assertEquals("/results", data.path)
    assertEquals(
      "search_query parameter must be the correctly-encoded query",
      query,
      data.getQueryParameter("search_query")
    )
    // Honesty: search results were opened — the response must not claim playback.
    assertTrue(
      "response must ask the user to pick/search, not claim playback",
      result.spokenText.contains("pick", ignoreCase = true) ||
        result.spokenText.contains("search", ignoreCase = true)
    )
    assertTrue(
      "response must NOT claim the video is already playing",
      !result.spokenText.contains("playing your video", ignoreCase = true)
    )
  }
}

package com.example.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-VEX AGENT PHASE: Action Planner (IntentDetector) coverage.
 *
 * Locks the goal "YouTube open panni Spider-Man search pannu" → executable two-step
 * plan across English, Tanglish and Tamil-script phrasing, while guaranteeing that
 * plain conversation is never misread as an action plan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexAgentActionPlanningTest {

  private lateinit var detector: IntentDetector

  @Before
  fun setUp() {
    detector = IntentDetector()
  }

  private fun plan(input: String): DvexIntent =
    detector.detectIntent(input, ConversationContext()).intent

  // --- 1. Intent recognition -------------------------------------------------

  @Test
  fun tanglishOpenAppIsRecognized() {
    val intent = plan("YouTube open pannu")
    assertTrue(intent is DvexIntent.OpenApp)
    assertEquals("YouTube", (intent as DvexIntent.OpenApp).appName)
  }

  @Test
  fun tanglishOpenPanniVariantIsRecognized() {
    val intent = plan("YouTube open panni")
    assertTrue(intent is DvexIntent.OpenApp)
    assertEquals("YouTube", (intent as DvexIntent.OpenApp).appName)
  }

  // --- 4. English command ----------------------------------------------------

  @Test
  fun englishOpenAndSearchPlansTwoSteps() {
    val intent = plan("Open YouTube and search for Spider-Man")
    assertTrue(intent is DvexIntent.MultiStep)
    val multi = intent as DvexIntent.MultiStep
    assertEquals("YouTube", (multi.first as DvexIntent.OpenApp).appName)
    assertEquals("Spider-Man", (multi.second as DvexIntent.SearchWeb).query)
  }

  @Test
  fun conversationalEnglishPlansTwoSteps() {
    val intent = plan("D-VEX, can you open YouTube and find Spider-Man?")
    assertTrue(intent is DvexIntent.MultiStep)
    val multi = intent as DvexIntent.MultiStep
    assertEquals("YouTube", (multi.first as DvexIntent.OpenApp).appName)
    assertEquals("Spider-Man", (multi.second as DvexIntent.SearchWeb).query)
  }

  // --- 2 / 5 / 6. Tanglish + Tamil multi-step planning -----------------------

  @Test
  fun tanglishPanniChainPlansTwoSteps() {
    val intent = plan("YouTube open panni Spider-Man search pannu")
    assertTrue(intent is DvexIntent.MultiStep)
    val multi = intent as DvexIntent.MultiStep
    assertEquals("YouTube", (multi.first as DvexIntent.OpenApp).appName)
    assertEquals("Spider-Man", (multi.second as DvexIntent.SearchWeb).query)
  }

  @Test
  fun tanglishSentenceSeparatedChainPlansTwoSteps() {
    val intent = plan("YouTube open pannu. Spider-Man search pannu.")
    assertTrue(intent is DvexIntent.MultiStep)
    val multi = intent as DvexIntent.MultiStep
    assertEquals("YouTube", (multi.first as DvexIntent.OpenApp).appName)
    assertEquals("Spider-Man", (multi.second as DvexIntent.SearchWeb).query)
  }

  @Test
  fun tanglishCommaSeparatedChainPlansTwoSteps() {
    val intent = plan("YouTube open pannu, Spider-Man search pannu")
    assertTrue(intent is DvexIntent.MultiStep)
    val multi = intent as DvexIntent.MultiStep
    assertEquals("Spider-Man", (multi.second as DvexIntent.SearchWeb).query)
  }

  @Test
  fun tamilScriptPanniChainPlansTwoSteps() {
    val intent = plan("YouTube open பண்ணி Spider-Man search பண்ணு")
    assertTrue(intent is DvexIntent.MultiStep)
    val multi = intent as DvexIntent.MultiStep
    assertEquals("YouTube", (multi.first as DvexIntent.OpenApp).appName)
    assertEquals("Spider-Man", (multi.second as DvexIntent.SearchWeb).query)
  }

  // --- 3. YouTube action generation ----------------------------------------

  @Test
  fun tanglishYoutubePlayProducesSearchQuery() {
    val intent = plan("youtube la spider man podu")
    assertTrue(intent is DvexIntent.PlayYoutubeVideo)
    assertTrue(
      (intent as DvexIntent.PlayYoutubeVideo).query.equals("spider man", ignoreCase = true)
    )
  }

  @Test
  fun youtubeLaSearchPannuProducesYoutubeSearch() {
    val intent = plan("youtube la Spider-Man search pannu")
    assertTrue(intent is DvexIntent.PlayYoutubeVideo)
    assertTrue(
      (intent as DvexIntent.PlayYoutubeVideo).query.equals("Spider-Man", ignoreCase = true)
    )
  }

  @Test
  fun shortCommandYoutubeThenQuerySearchesYoutube() {
    val intent = plan("YouTube Spider-Man")
    assertTrue(intent is DvexIntent.PlayYoutubeVideo)
    assertTrue(
      (intent as DvexIntent.PlayYoutubeVideo).query.equals("Spider-Man", ignoreCase = true)
    )
  }

  // --- 7. Unknown app handling (planning stays honest, never invents an app) --

  @Test
  fun unknownAppChainStillPlansAndNamesTheRealTarget() {
    val intent = plan("Instagram open panni reels search pannu")
    assertTrue(intent is DvexIntent.MultiStep)
    val multi = intent as DvexIntent.MultiStep
    assertEquals("Instagram", (multi.first as DvexIntent.OpenApp).appName)
    assertEquals("reels", (multi.second as DvexIntent.SearchWeb).query)
  }

  // --- Guards: never turn conversation into an action plan -------------------

  @Test
  fun conversationIsNotMisplannedAsAction() {
    assertTrue(plan("How are you doing today?") !is DvexIntent.MultiStep)
  }

  @Test
  fun standaloneTrailingSearchBecomesWebSearch() {
    val intent = plan("Spider-Man search pannu")
    assertTrue(intent is DvexIntent.SearchWeb)
    assertEquals("Spider-Man", (intent as DvexIntent.SearchWeb).query)
  }
}

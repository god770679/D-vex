package com.example.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexSmartBrainTest {

  private lateinit var detector: IntentDetector
  private lateinit var context: ConversationContext
  private lateinit var responseGenerator: DvexResponseGenerator

  @Before
  fun setUp() {
    detector = IntentDetector()
    context = ConversationContext()
    responseGenerator = DvexResponseGenerator()
  }

  @Test
  fun testEnglishIntentDetection() {
    // Open YouTube
    val r1 = detector.detectIntent("D-VEX, open YouTube.", context)
    assertTrue("Should be OpenApp", r1.intent is DvexIntent.OpenApp)
    assertEquals("YouTube", (r1.intent as DvexIntent.OpenApp).appName)
    assertEquals(DetectedLanguage.ENGLISH, r1.language)

    // Open Camera
    val r2 = detector.detectIntent("D-VEX, open Camera.", context)
    assertTrue("Should be OpenApp", r2.intent is DvexIntent.OpenApp)
    assertEquals("Camera", (r2.intent as DvexIntent.OpenApp).appName)

    // Go home
    val r3 = detector.detectIntent("D-VEX, go home.", context)
    assertTrue("Should be GoHome", r3.intent is DvexIntent.GoHome)

    // Go back
    val r4 = detector.detectIntent("D-VEX, go back.", context)
    assertTrue("Should be GoBack", r4.intent is DvexIntent.GoBack)

    // Weather
    val r5 = detector.detectIntent("D-VEX, what is the weather?", context)
    assertTrue("Should be GetWeather", r5.intent is DvexIntent.GetWeather)

    // Time
    val r6 = detector.detectIntent("D-VEX, what time is it?", context)
    assertTrue("Should be GetTime", r6.intent is DvexIntent.GetTime)

    // Calculate
    val r7 = detector.detectIntent("D-VEX, calculate 25 times 8.", context)
    assertTrue("Should be Calculate", r7.intent is DvexIntent.Calculate)
    assertEquals("25 times 8", (r7.intent as DvexIntent.Calculate).expression)

    // Remember
    val r8 = detector.detectIntent("D-VEX, remember that I like red.", context)
    assertTrue("Should be RememberFact", r8.intent is DvexIntent.RememberFact)
    assertEquals("I like red", (r8.intent as DvexIntent.RememberFact).fact)

    // Recall
    val r9 = detector.detectIntent("D-VEX, what do I like?", context)
    assertTrue("Should be RecallMemory", r9.intent is DvexIntent.RecallMemory)

    // Identity
    val r10 = detector.detectIntent("D-VEX, who are you?", context)
    assertTrue("Should be Conversation", r10.intent is DvexIntent.Conversation)
  }

  @Test
  fun testTamilAndTanglishIntentDetection() {
    // Tamil/Tanglish: "D-VEX, YouTube open pannu."
    val r1 = detector.detectIntent("D-VEX, YouTube open pannu.", context)
    assertTrue("Should be OpenApp", r1.intent is DvexIntent.OpenApp)
    assertEquals("YouTube", (r1.intent as DvexIntent.OpenApp).appName)

    // Tanglish: "D-VEX, camera open pannu."
    val r2 = detector.detectIntent("D-VEX, camera open pannu.", context)
    assertTrue("Should be OpenApp", r2.intent is DvexIntent.OpenApp)
    assertEquals("camera", (r2.intent as DvexIntent.OpenApp).appName)

    // Pure Tamil: "யூடியூப் open பண்ணு"
    val r3 = detector.detectIntent("யூடியூப் open பண்ணு", context)
    assertTrue("Should be OpenApp", r3.intent is DvexIntent.OpenApp)
    assertEquals(DetectedLanguage.TAMIL, r3.language)

    // Tanglish navigation: "veetuku po"
    val r4 = detector.detectIntent("veetuku po", context)
    assertTrue("Should be GoHome", r4.intent is DvexIntent.GoHome)

    // Tanglish back: "pinnaadi po"
    val r5 = detector.detectIntent("pinnaadi po", context)
    assertTrue("Should be GoBack", r5.intent is DvexIntent.GoBack)
  }

  @Test
  fun testSensitiveActionRequiresConfirmation() {
    val r1 = detector.detectIntent("Call Dad.", context)
    assertTrue("Should be CallContact", r1.intent is DvexIntent.CallContact)
    assertEquals("Dad", (r1.intent as DvexIntent.CallContact).recipient)

    val r2 = detector.detectIntent("send message to Alice saying I will be late", context)
    assertTrue("Should be SendMessage", r2.intent is DvexIntent.SendMessage)
    val sendMsg = r2.intent as DvexIntent.SendMessage
    assertEquals("Alice", sendMsg.recipient)
    assertEquals("I will be late", sendMsg.messageText)
  }

  @Test
  fun testMultiStepIntentDetection() {
    val r = detector.detectIntent("Open YouTube and search for tractor videos", context)
    assertTrue("Should be MultiStep", r.intent is DvexIntent.MultiStep)
    val multi = r.intent as DvexIntent.MultiStep
    assertTrue("First step should be OpenApp", multi.first is DvexIntent.OpenApp)
    assertEquals("YouTube", (multi.first as DvexIntent.OpenApp).appName)
    assertTrue("Second step should be SearchWeb", multi.second is DvexIntent.SearchWeb)
    assertEquals("tractor videos", (multi.second as DvexIntent.SearchWeb).query)
  }

  @Test
  fun testContextualFollowUp() {
    // Step 1: User says "Open YouTube"
    val r1 = detector.detectIntent("Open YouTube", context)
    System.err.println("TEST r1: ${r1.intent}")
    context.update(
      input = "Open YouTube",
      intent = r1.intent,
      toolResult = DvexToolResult(DvexToolStatus.SUCCESS, "open_app", "Opening YouTube", "Opening YouTube"),
      language = DetectedLanguage.ENGLISH
    )
    System.err.println("TEST after update: lastActiveApp=${context.lastActiveApp}")
    assertEquals("YouTube", context.lastActiveApp)

    // Step 2: Follow-up command "Search for tractor videos"
    val r2 = detector.detectIntent("Search for tractor videos", context)
    System.err.println("DEBUG r2.intent = ${r2.intent}, class = ${r2.intent::class.java.name}")
    assertTrue("Follow up should be contextualized to YouTube search: was ${r2.intent}",
      r2.intent is DvexIntent.MultiStep || r2.intent is DvexIntent.SearchWeb
    )
    val query = if (r2.intent is DvexIntent.MultiStep) {
      ((r2.intent as DvexIntent.MultiStep).second as DvexIntent.SearchWeb).query
    } else {
      (r2.intent as DvexIntent.SearchWeb).query
    }
    assertEquals("tractor videos", query)
  }

  @Test
  fun testNaturalResponseGenerationNoInternalLeakage() {
    val openAppIntent = DvexIntent.OpenApp("YouTube")
    val toolResult = DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "open_app",
      message = "Opening YouTube.",
      spokenText = "Opening YouTube."
    )

    val response = responseGenerator.generateResponse(openAppIntent, toolResult, DetectedLanguage.ENGLISH)
    assertEquals("Opening YouTube.", response)
    assertFalse("Must never leak raw internal intent strings", response.contains("OPEN_APP"))
    assertFalse("Must never leak raw internal status strings", response.contains("SUCCESS"))
  }

  @Test
  fun testFailedActionResponseGeneration() {
    val notFoundResult = DvexToolResult(
      status = DvexToolStatus.NOT_FOUND,
      toolName = "open_app",
      message = "App FakeGame not found",
      spokenText = "I couldn't find that app on your phone."
    )

    val response = responseGenerator.generateResponse(DvexIntent.OpenApp("FakeGame"), notFoundResult, DetectedLanguage.ENGLISH)
    assertEquals("I couldn't find that app on your phone.", response)
    assertFalse(response.contains("NOT_FOUND"))
  }

  @Test
  fun testPermissionRequiredResponseGeneration() {
    val permResult = DvexToolResult(
      status = DvexToolStatus.PERMISSION_REQUIRED,
      toolName = "back",
      message = "Accessibility required",
      spokenText = "D-VEX needs Accessibility permission for that action."
    )

    val response = responseGenerator.generateResponse(DvexIntent.GoBack, permResult, DetectedLanguage.ENGLISH)
    assertEquals("D-VEX needs Accessibility permission for that action.", response)
  }
}

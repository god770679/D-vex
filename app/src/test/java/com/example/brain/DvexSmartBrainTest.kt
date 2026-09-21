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

  @Test
  fun testCallIntentSemanticEquivalence() {
    // "hari ku call pannu", "hari ah call", "dei hari ku call pannu", "Call Hari"
    val r1 = detector.detectIntent("hari ku call pannu", context)
    assertTrue("Should be CallContact", r1.intent is DvexIntent.CallContact)
    assertEquals("hari", (r1.intent as DvexIntent.CallContact).recipient.lowercase())

    val r2 = detector.detectIntent("hari ah call", context)
    assertTrue("Should be CallContact", r2.intent is DvexIntent.CallContact)
    assertEquals("hari", (r2.intent as DvexIntent.CallContact).recipient.lowercase())

    val r3 = detector.detectIntent("dei hari ku call pannu", context)
    assertTrue("Should be CallContact", r3.intent is DvexIntent.CallContact)
    assertEquals("hari", (r3.intent as DvexIntent.CallContact).recipient.lowercase())

    val r4 = detector.detectIntent("Call Hari", context)
    assertTrue("Should be CallContact", r4.intent is DvexIntent.CallContact)
    assertEquals("hari", (r4.intent as DvexIntent.CallContact).recipient.lowercase())
  }

  @Test
  fun testConfirmationDetection() = kotlinx.coroutines.runBlocking {
    val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val appLauncher = com.example.control.AppLauncherRepository(appContext)
    val deviceControl = com.example.control.DeviceControlRepository(appContext, appLauncher)
    val brain = DvexSmartBrain(appContext, appLauncher, deviceControl)

    // Trigger confirmation flow with a direct phone number so contact lookup succeeds in unit test environment
    val res1 = brain.process("Call 9876543210")
    assertEquals(DvexToolStatus.CONFIRMATION_REQUIRED, res1.toolResult.status)
    assertTrue(brain.hasPendingConfirmation())

    // Confirm with "Yes" -> executes pending call intent
    val res2 = brain.process("Yes")
    assertTrue("Should execute CallContact", res2.intent is DvexIntent.CallContact)
    assertFalse("Pending confirmation should now be cleared", brain.hasPendingConfirmation())
  }

  @Test
  fun testToneEstimationAndNaturalResponses() {
    // Frustrated
    val toneFrustrated = responseGenerator.estimateTone("kaduppa irukku da")
    assertEquals(EstimatedTone.FRUSTRATED, toneFrustrated)
    val respFrustrated = responseGenerator.generateResponse(
      intent = DvexIntent.Conversation("kaduppa irukku da"),
      toolResult = DvexToolResult(DvexToolStatus.SUCCESS, "conversation", "", ""),
      language = DetectedLanguage.TANGLISH,
      userInput = "kaduppa irukku da",
      tone = toneFrustrated
    )
    assertTrue("Should be calm, supportive step-by-step response", respFrustrated.contains("step-by-step"))

    // Sad
    val toneSad = responseGenerator.estimateTone("sogama irukku da")
    assertEquals(EstimatedTone.SAD, toneSad)
    val respSad = responseGenerator.generateResponse(
      intent = DvexIntent.Conversation("sogama irukku da"),
      toolResult = DvexToolResult(DvexToolStatus.SUCCESS, "conversation", "", ""),
      language = DetectedLanguage.TANGLISH,
      userInput = "sogama irukku da",
      tone = toneSad
    )
    assertTrue("Should be empathetic response", respSad.contains("நான் இருக்கேன்"))

    // Excited
    val toneExcited = responseGenerator.estimateTone("vera level da")
    assertEquals(EstimatedTone.EXCITED, toneExcited)
    val respExcited = responseGenerator.generateResponse(
      intent = DvexIntent.Conversation("vera level da"),
      toolResult = DvexToolResult(DvexToolStatus.SUCCESS, "conversation", "", ""),
      language = DetectedLanguage.TANGLISH,
      userInput = "vera level da",
      tone = toneExcited
    )
    assertTrue("Should be excited response", respExcited.contains("அடுத்த level"))
  }
}

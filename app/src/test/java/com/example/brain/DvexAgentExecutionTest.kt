package com.example.brain

import android.app.Application
import com.example.control.AppActionResult
import com.example.control.AppControlAgent
import com.example.control.AppLauncherRepository
import com.example.control.ContactResolver
import com.example.control.DeviceControlRepository
import com.example.data.remote.ToolResultStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * D-VEX AGENT PHASE: Android Action Executor + verification honesty.
 *
 * Locks the safety contract:
 *  - unknown apps are never reported as launched,
 *  - the YouTube action never fabricates display-level success without verification,
 *  - messaging automation without accessibility reports a permission requirement,
 *  - high-risk actions are never auto-executed (never SUCCESS) without confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexAgentExecutionTest {

  private lateinit var appContext: Application
  private lateinit var appLauncher: AppLauncherRepository
  private lateinit var deviceControl: DeviceControlRepository
  private lateinit var router: DvexToolRouter
  private lateinit var detector: IntentDetector

  @Before
  fun setUp() {
    appContext = RuntimeEnvironment.getApplication()
    appLauncher = AppLauncherRepository(appContext)
    deviceControl = DeviceControlRepository(appContext, appLauncher)
    router = DvexToolRouter(appContext, appLauncher, deviceControl)
    detector = IntentDetector()
  }

  // --- 7. Unknown app handling ----------------------------------------------

  @Test
  fun unknownAppLaunchFailsHonestly() {
    val result = appLauncher.launchAppByName("Instagram")
    assertNotEquals(ToolResultStatus.SUCCESS, result.status)
    assertTrue(result.message.lowercase().contains("not found"))
  }

  @Test
  fun unknownAppIntentNeverReportsSuccess() = runBlocking {
    val result = router.execute(DvexIntent.OpenApp("Instagram"))
    assertNotEquals(DvexToolStatus.SUCCESS, result.status)
    assertTrue(result.spokenText.isNotBlank())
  }

  // --- 9. Action failure recovery / honesty ---------------------------------

  @Test
  fun youtubeActionWithoutInstalledAppNeverFabricatesVerifiedWording() {
    assertFalse(appLauncher.isPackageInstalled("com.google.android.youtube"))
    val result = deviceControl.openYouTube("Spider-Man")
    assertTrue(result.message.contains("YouTube"))
    // "kaatiten" = "I showed it" — only allowed after real foreground verification.
    assertFalse(result.message.contains("kaatiten"))
  }

  @Test
  fun targetTanglishCommandRunsThroughTheRealYoutubeExecutor() = runBlocking {
    val intent = detector
      .detectIntent("YouTube open panni Spider-Man search pannu", ConversationContext()).intent
    assertTrue(intent is DvexIntent.MultiStep)

    val result = router.execute(intent)
    if (result.status == DvexToolStatus.SUCCESS) {
      // A success may only come from the real YouTube executor — never a stub.
      assertTrue(
        result.toolName == "youtube_search" || result.toolName == "youtube_browser_fallback"
      )
      assertFalse(result.spokenText.contains("kaatiten"))
    } else {
      // Honest failure must still explain itself.
      assertTrue(result.spokenText.isNotBlank())
    }
  }

  // --- 8. Permission failure handling ---------------------------------------

  @Test
  fun messagingAutomationWithoutAccessibilityRequiresPermission() = runBlocking {
    val agent = AppControlAgent(appContext, appLauncher, ContactResolver(appContext))
    val result = agent.prepareMessageInApp("Arun", "I'll call you later", targetAppOverride = "sms")
    assertTrue(result is AppActionResult.PermissionRequired)
  }

  // --- 10. Confirmation-required (high-risk) actions -------------------------

  @Test
  fun messageCommandNeverClaimsSuccessWithoutConfirmation() = runBlocking {
    val result = router.execute(
      DvexIntent.SendMessage(
        recipient = "Arun",
        messageText = "I'll call you later",
        isWhatsApp = true
      )
    )
    assertNotEquals(DvexToolStatus.SUCCESS, result.status)
  }
}

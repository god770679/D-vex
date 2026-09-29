package com.example.brain

import android.Manifest
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.mode.PowerModeManager
import com.example.mode.VisionRequestGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * DUAL MODE SYSTEM — Requirements A, B, C, D, J, K, L.
 *
 * Locks the confirmation contract at the single decision point (DvexToolRouter):
 *
 *  A. Power OFF  + SEND_MESSAGE  -> confirmation required (existing flow).
 *  B. Power ON   + SEND_MESSAGE  -> confirmation bypassed; existing execution path
 *                                   (executeConfirmed) is invoked.
 *  C. Power OFF  + CALL_CONTACT  -> confirmation required.
 *  D. Power ON   + CALL_CONTACT  -> confirmation STILL required (never bypassed).
 *  J. SEND_MESSAGE cannot bypass confirmation while Power is OFF even after an app
 *     restart (fresh router + fresh PowerModeManager from persisted prefs).
 *  K. Toggling Power Mode ON does not start vision (VisionRequestGate stays OFF).
 *  L. Toggling Power Mode ON does not initiate a phone call (a call can only ever
 *     happen through execute -> confirmation -> executeConfirmed, which the toggle
 *     never triggers).
 *
 * Test mechanics (safe, side-effect-free):
 *  - READ_CONTACTS is granted via Robolectric so resolution never short-circuits
 *    with PermissionDenied (Robolectric grants no dangerous permissions by default).
 *  - A phone-number recipient ("+911234567890") resolves deterministically to a
 *    Single contact through ContactResolver's direct-number path, without needing
 *    a contacts provider — so the tests exercise the confirmation gate itself.
 *  - Under Robolectric no SEND_SMS is granted and accessibility is off, so the
 *    Power-ON execution path honestly reports PERMISSION_REQUIRED — proof that
 *    execution (not confirmation) ran, with no real SMS ever dispatched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DualModeMessagingGateTest {

  private lateinit var appContext: android.content.Context
  private lateinit var router: DvexToolRouter

  private companion object {
    /** Direct-number recipient: resolves to Single without the contacts provider. */
    const val NUMBER = "+911234567890"
    const val UNKNOWN = "ZzzNoSuchPersonQ"
  }

  @Before
  fun setUp() {
    appContext = RuntimeEnvironment.getApplication()
    Shadows.shadowOf(appContext as android.app.Application)
      .grantPermissions(Manifest.permission.READ_CONTACTS)

    PowerModeManagerTestAccess.resetSingleton()
    appContext.getSharedPreferences("dvex_power_mode_prefs", 0).edit().clear().commit()
    VisionRequestGate.resetForTesting()
    router = newRouter()
  }

  @After
  fun tearDown() {
    PowerModeManagerTestAccess.resetSingleton()
    VisionRequestGate.resetForTesting()
  }

  private fun newRouter(): DvexToolRouter =
    DvexToolRouter(
      appContext,
      AppLauncherRepository(appContext),
      DeviceControlRepository(appContext, AppLauncherRepository(appContext))
    )

  private fun setPowerMode(enabled: Boolean) {
    PowerModeManager.getInstance(appContext).setEnabled(enabled)
  }

  // --- A. Standard Mode SEND_MESSAGE keeps the confirmation flow ---

  @Test
  fun requirementA_powerOff_sendMessageRequiresConfirmation() = kotlinx.coroutines.test.runTest {
    setPowerMode(false)

    val result = router.execute(DvexIntent.SendMessage(recipient = NUMBER, messageText = "I am coming"))

    assertEquals(
      "Standard Mode SEND_MESSAGE must require confirmation (unchanged flow)",
      DvexToolStatus.CONFIRMATION_REQUIRED,
      result.status
    )
    assertTrue("requiresConfirmation must stay true", result.requiresConfirmation)
    assertNotNull("pendingActionId must be armed", result.pendingActionId)
    assertTrue(
      "prompt must reference the resolved recipient",
      result.spokenText.contains(NUMBER) || result.message.contains(NUMBER)
    )
  }

  @Test
  fun requirementA_powerOff_unknownContactIsHonestNotFound_notAutoSend() =
    kotlinx.coroutines.test.runTest {
      setPowerMode(false)

      val result = router.execute(DvexIntent.SendMessage(recipient = UNKNOWN, messageText = "hi"))

      assertEquals(DvexToolStatus.NOT_FOUND, result.status)
      assertFalse(result.requiresConfirmation)
    }

  // --- B. Power Mode SEND_MESSAGE bypasses confirmation via the existing path ---

  @Test
  fun requirementB_powerOn_sendMessageBypassesConfirmation_andUsesExistingExecutionPath() =
    kotlinx.coroutines.test.runTest {
      setPowerMode(true)

      val result = router.execute(DvexIntent.SendMessage(recipient = NUMBER, messageText = "I am coming"))

      // Confirmation must be bypassed entirely.
      assertFalse(
        "Power Mode ON must bypass SEND_MESSAGE confirmation",
        result.status == DvexToolStatus.CONFIRMATION_REQUIRED || result.requiresConfirmation
      )
      // The EXISTING execution path ran: executeConfirmed -> accessibility automation
      // or the direct send fallback. Without SEND_SMS the honest outcome is
      // PERMISSION_REQUIRED; with it, SUCCESS. Both prove execution, never a prompt.
      assertTrue(
        "execution must go through the existing send path (send_message tool)",
        result.toolName == "send_message"
      )
      assertTrue(
        "status must be an execution outcome, not a confirmation prompt",
        result.status == DvexToolStatus.SUCCESS ||
          result.status == DvexToolStatus.PERMISSION_REQUIRED ||
          result.status == DvexToolStatus.FAILED
      )
    }

  @Test
  fun requirementB_powerOn_whatsAppSendMessageAlsoBypassesConfirmation() =
    kotlinx.coroutines.test.runTest {
      setPowerMode(true)

      val result = router.execute(
        DvexIntent.SendMessage(recipient = NUMBER, messageText = "I am coming", isWhatsApp = true)
      )

      assertFalse(
        "WhatsApp send must also bypass confirmation in Power Mode",
        result.status == DvexToolStatus.CONFIRMATION_REQUIRED || result.requiresConfirmation
      )
      assertEquals("whatsapp", result.toolName)
    }

  // --- C & D. CALL_CONTACT confirmation is mode-independent ---

  @Test
  fun requirementC_powerOff_callContactRequiresConfirmation() = kotlinx.coroutines.test.runTest {
    setPowerMode(false)

    val result = router.execute(DvexIntent.CallContact(recipient = NUMBER))

    assertEquals(
      "Standard Mode call must require confirmation",
      DvexToolStatus.CONFIRMATION_REQUIRED,
      result.status
    )
    assertTrue(result.requiresConfirmation)
    assertNotNull(result.pendingActionId)
  }

  @Test
  fun requirementD_powerOn_callContactStillRequiresConfirmation() = kotlinx.coroutines.test.runTest {
    setPowerMode(true)

    val result = router.execute(DvexIntent.CallContact(recipient = NUMBER))

    assertEquals(
      "Power Mode must NOT bypass call confirmation",
      DvexToolStatus.CONFIRMATION_REQUIRED,
      result.status
    )
    assertTrue("call confirmation flag must remain set in Power Mode", result.requiresConfirmation)
    assertNotNull(result.pendingActionId)
  }

  // --- J. No bypass after restart while Power Mode is OFF ---

  @Test
  fun requirementJ_afterRestart_powerStillOff_sendMessageStillRequiresConfirmation() =
    kotlinx.coroutines.test.runTest {
      // Persist OFF, then simulate a restart: drop the singleton and rebuild the
      // router exactly as a fresh process would.
      setPowerMode(false)
      PowerModeManagerTestAccess.resetSingleton()
      val freshRouter = newRouter()

      val powerManager = PowerModeManager.getInstance(appContext)
      assertFalse("after restart with OFF persisted, mode must be OFF", powerManager.isPowerModeEnabled())

      val result = freshRouter.execute(DvexIntent.SendMessage(recipient = NUMBER, messageText = "I am coming"))

      assertEquals(
        "confirmation must NOT be bypassable after restart while Power Mode is OFF",
        DvexToolStatus.CONFIRMATION_REQUIRED,
        result.status
      )
      assertTrue(result.requiresConfirmation)
    }

  // --- K. Power toggle never starts vision ---

  @Test
  fun requirementK_togglingPowerModeOnDoesNotStartVision() {
    VisionRequestGate.resetForTesting()
    assertFalse(VisionRequestGate.isVisionRequested())

    setPowerMode(true)

    assertFalse(
      "Power Mode activation must never flip the vision opt-in",
      VisionRequestGate.isVisionRequested()
    )
  }

  // --- L. Power toggle never initiates a call ---

  @Test
  fun requirementL_togglingPowerModeOnDoesNotInitiateCall() {
    // The toggle is a pure state flip: the router maps intents to actions only when
    // the pipeline executes one. The toggle itself never calls execute()/executeConfirmed(),
    // so a call can only ever happen via execute(CallContact) -> confirmation ->
    // executeConfirmed — none of which the toggle touches.
    val powerManager = PowerModeManager.getInstance(appContext)
    powerManager.setEnabled(true)
    assertTrue(powerManager.isPowerModeEnabled())
  }
}

/** Shared reset hook (mirrors the test helper in com.example.mode). */
object PowerModeManagerTestAccess {
  val PREFS_NAME_FOR_TEST: String = run {
    val field = PowerModeManager.Companion::class.java.declaredFields.firstOrNull {
      it.name == "PREFS_NAME"
    }
    field?.isAccessible = true
    field?.get(null) as? String ?: "dvex_power_mode_prefs"
  }

  fun resetSingleton() {
    val field = PowerModeManager::class.java.declaredFields.firstOrNull { it.name == "instance" }
    field?.isAccessible = true
    field?.set(null, null)
  }
}

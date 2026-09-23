package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.brain.DvexIntent
import com.example.brain.DvexToolRouter
import com.example.brain.DvexToolStatus
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.model.AppModeManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexDualModeUnitTest {

  private lateinit var context: Application
  private lateinit var appLauncher: AppLauncherRepository
  private lateinit var deviceControl: DeviceControlRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    appLauncher = AppLauncherRepository(context)
    deviceControl = DeviceControlRepository(context, appLauncher)
    AppModeManager.init(context)
    // Always start each test in a clean Standard Mode state
    AppModeManager.setPowerMode(false, context)
    AppModeManager.setVisionActive(false, context)
  }

  @Test
  fun testDefaultModeIsStandardMode() {
    assertFalse("Default state must be Standard Mode (Power Mode OFF)", AppModeManager.isPowerMode.value)
    assertFalse("Default vision state must be OFF", AppModeManager.isVisionActive.value)
  }

  @Test
  fun testToggleAndSetPowerMode() {
    AppModeManager.setPowerMode(true, context)
    assertTrue("Power mode should now be enabled", AppModeManager.isPowerMode.value)

    AppModeManager.togglePowerMode(context)
    assertFalse("Power mode should now be disabled after toggle", AppModeManager.isPowerMode.value)

    AppModeManager.togglePowerMode(context)
    assertTrue("Power mode should now be re-enabled after toggle", AppModeManager.isPowerMode.value)
  }

  @Test
  fun testVisionSafetyRequirements() {
    // 1. Enabling Power Mode must NEVER auto-enable Vision/Camera
    AppModeManager.setPowerMode(true, context)
    assertFalse("Enabling Power Mode must never auto-enable camera/vision", AppModeManager.isVisionActive.value)

    // 2. Vision requires explicit activation
    AppModeManager.setVisionActive(true, context)
    assertTrue("Vision active after explicit request", AppModeManager.isVisionActive.value)

    // 3. Turning off Power Mode must deactivate Vision feed for safety
    AppModeManager.setPowerMode(false, context)
    assertFalse("Disabling Power Mode must turn off vision", AppModeManager.isVisionActive.value)
  }

  @Test
  fun testSendMessageRequiresConfirmationInStandardMode() = runBlocking {
    AppModeManager.setPowerMode(false, context)
    assertFalse(AppModeManager.isPowerMode.value)

    val router = DvexToolRouter(context, appLauncher, deviceControl)
    val result = router.execute(
      DvexIntent.SendMessage(
        recipient = "+919876543210",
        messageText = "Hello from D-VEX Standard",
        isWhatsApp = false
      )
    )

    assertEquals("In Standard Mode, SEND_MESSAGE must require confirmation", DvexToolStatus.CONFIRMATION_REQUIRED, result.status)
    assertTrue("Result must indicate requiresConfirmation = true", result.requiresConfirmation)
  }

  @Test
  fun testSendMessageExecutesDirectlyInPowerMode() = runBlocking {
    AppModeManager.setPowerMode(true, context)
    assertTrue(AppModeManager.isPowerMode.value)

    val router = DvexToolRouter(context, appLauncher, deviceControl)
    val result = router.execute(
      DvexIntent.SendMessage(
        recipient = "+919876543210",
        messageText = "Instant message in Power Mode",
        isWhatsApp = false
      )
    )

    assertNotEquals("In Power Mode, SEND_MESSAGE must NOT require confirmation", DvexToolStatus.CONFIRMATION_REQUIRED, result.status)
    assertFalse("Result must have requiresConfirmation = false", result.requiresConfirmation)
  }

  @Test
  fun testCallContactStillRequiresConfirmationInStandardMode() = runBlocking {
    AppModeManager.setPowerMode(false, context)

    val router = DvexToolRouter(context, appLauncher, deviceControl)
    val result = router.execute(
      DvexIntent.CallContact(recipient = "+919876543210")
    )

    assertEquals("In Standard Mode, CALL_CONTACT must require confirmation", DvexToolStatus.CONFIRMATION_REQUIRED, result.status)
    assertTrue("Result must have requiresConfirmation = true", result.requiresConfirmation)
  }

  @Test
  fun testCallContactStillRequiresConfirmationInPowerMode() = runBlocking {
    // CALL_CONTACT safety flow must be strictly preserved even in Power Mode!
    AppModeManager.setPowerMode(true, context)

    val router = DvexToolRouter(context, appLauncher, deviceControl)
    val result = router.execute(
      DvexIntent.CallContact(recipient = "+919876543210")
    )

    assertEquals("In Power Mode, CALL_CONTACT must STILL require confirmation", DvexToolStatus.CONFIRMATION_REQUIRED, result.status)
    assertTrue("Result must have requiresConfirmation = true", result.requiresConfirmation)
  }
}

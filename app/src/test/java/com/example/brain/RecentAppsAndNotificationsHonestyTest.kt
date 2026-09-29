package com.example.brain

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.example.control.AppLauncherRepository
import com.example.control.DeviceControlRepository
import com.example.control.RecentAppsProvider
import com.example.permissions.DvexPermissionManager
import com.example.service.DvexNotificationListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager

/**
 * Bug 5 honesty contract (recent apps + notifications):
 *
 * - No usage access -> [DvexIntent.GetRecentApps] returns PERMISSION_REQUIRED with
 *   Settings guidance, never fabricated app names.
 * - Usage access granted but no history -> SUCCESS with an honest empty message,
 *   never placeholder/demo apps.
 * - Real usage events -> only actual [UsageStatsManager] entries, resolved to real
 *   installed-app labels, newest first.
 * - "Recent apps"/"recents"/"notifications" utterances map to the right intents.
 * - Notification listener enablement is checked via the OS component list, not faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecentAppsAndNotificationsHonestyTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // Note: Robolectric's ShadowAppOpsManager reports MODE_ALLOWED for ops that were
    // never set (unlike a real device, where the default is denied). Tests therefore
    // set the op mode EXPLICITLY to simulate granted vs denied states deterministically.
  }

  // --- Recent apps: permission-denied path must be honest ---

  @Test
  fun providerReturnsPermissionNotGrantedWhenUsageAccessDenied() {
    // Op explicitly denied (non-ALLOWED mode) -> provider must fail with
    // PermissionNotGranted and never fall through to fabricated data.
    setUsageAccessMode(AppOpsManager.MODE_IGNORED)
    val provider = RecentAppsProvider(context)

    val result = provider.getRecentApps()

    assertTrue("Expected failure when usage access is denied", result.isFailure)
    assertTrue(
      "Failure must be PermissionNotGranted, got: ${result.exceptionOrNull()}",
      result.exceptionOrNull() is RecentAppsProvider.UsageAccessError.PermissionNotGranted
    )
  }

  @Test
  fun routerReturnsPermissionRequiredForRecentAppsWhenUsageAccessDenied() = kotlinx.coroutines.runBlocking {
    setUsageAccessMode(AppOpsManager.MODE_IGNORED)
    val router = DvexToolRouter(
      context,
      AppLauncherRepository(context),
      DeviceControlRepository(context, AppLauncherRepository(context))
    )

    val result = router.execute(DvexIntent.GetRecentApps)

    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, result.status)
    assertEquals("get_recent_apps", result.toolName)
    assertTrue(
      "Spoken text must point the user to Settings usage access",
      result.spokenText.contains("Usage access", ignoreCase = true) ||
        result.spokenText.contains("Settings", ignoreCase = true)
    )
    // The honesty core: no invented app names.
    assertFalse(
      "Denied state must not contain any app-like fake data",
      result.spokenText.contains("WhatsApp") || result.spokenText.contains("YouTube")
    )
  }

  // --- Recent apps: granted-but-empty path must be honest ---

  @Test
  fun providerReturnsHonestEmptyListWhenGrantedButNoUsage() {
    grantUsageAccess()

    val provider = RecentAppsProvider(context)
    val result = provider.getRecentApps()

    assertTrue("Granted-but-empty must succeed, got error: ${result.exceptionOrNull()}", result.isSuccess)
    assertTrue("Empty history must yield an empty list", result.getOrDefault(emptyList()).isEmpty())
  }

  @Test
  fun routerReturnsSuccessWithHonestEmptyMessageWhenNoUsageRecorded() = kotlinx.coroutines.runBlocking {
    grantUsageAccess()
    val router = DvexToolRouter(
      context,
      AppLauncherRepository(context),
      DeviceControlRepository(context, AppLauncherRepository(context))
    )

    val result = router.execute(DvexIntent.GetRecentApps)

    assertEquals(DvexToolStatus.SUCCESS, result.status)
    assertTrue(
      "Empty state should say no usage recorded, got: ${result.spokenText}",
      result.spokenText.contains("No app usage", ignoreCase = true)
    )
  }

  // --- Recent apps: real usage data flows through, newest first ---

  @Test
  fun providerReturnsRealUsageEventsNewestFirst() {
    grantUsageAccess()
    val now = System.currentTimeMillis()
    val usageManager =
      context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    // Seed real usage events for a package that exists in Robolectric's package
    // manager (the app itself), plus one unresolvable package that must be skipped.
    val appPackage = context.packageName
    shadowOf(usageManager).addEvent(
      ShadowUsageStatsManager.EventBuilder.buildEvent()
        .setPackage(appPackage)
        .setEventType(UsageEvents.Event.ACTIVITY_RESUMED)
        .setTimeStamp(now - 60_000L)
        .build()
    )
    shadowOf(usageManager).addEvent(
      ShadowUsageStatsManager.EventBuilder.buildEvent()
        .setPackage(appPackage)
        .setEventType(UsageEvents.Event.ACTIVITY_RESUMED)
        .setTimeStamp(now - 10_000L)
        .build()
    )
    shadowOf(usageManager).addEvent(
      ShadowUsageStatsManager.EventBuilder.buildEvent()
        .setPackage("com.fake.uninstalled")
        .setEventType(UsageEvents.Event.ACTIVITY_RESUMED)
        .setTimeStamp(now - 30_000L)
        .build()
    )

    val provider = RecentAppsProvider(context)
    val result = provider.getRecentApps()

    assertTrue("Expected success, got: ${result.exceptionOrNull()}", result.isSuccess)
    val apps = result.getOrDefault(emptyList())
    assertEquals("Only resolvable packages survive", 1, apps.size)
    assertEquals(appPackage, apps[0].packageName)
    assertEquals("Newest event wins", now - 10_000L, apps[0].lastTimeUsed)
    assertNotNull("Label must be resolved via PackageManager", apps[0].appName)
    assertTrue("Label must be non-blank", apps[0].appName.isNotBlank())
    // The unresolvable package must never appear (never guessed).
    assertTrue(apps.none { it.packageName == "com.fake.uninstalled" })
  }

  // --- Intent detection: real utterances route to the real tools ---

  @Test
  fun recentAppsUtterancesRouteToGetRecentApps() {
    val detector = IntentDetector()
    val ctx = com.example.brain.ConversationContext()

    assertEquals(
      DvexIntent.GetRecentApps,
      detector.detectIntent("D-VEX, what are my recent apps", ctx).intent
    )
    assertEquals(
      DvexIntent.GetRecentApps,
      detector.detectIntent("recent apps kaatu", ctx).intent
    )
    assertEquals(
      DvexIntent.GetRecentApps,
      detector.detectIntent("enna apps use pannen", ctx).intent
    )
  }

  @Test
  fun recentsNavigationUtteranceDoesNotTriggerGetRecentApps() {
    val detector = IntentDetector()
    val ctx = com.example.brain.ConversationContext()

    // OS recents navigation must stay distinct from usage-history listing.
    assertEquals(
      DvexIntent.OpenRecents,
      detector.detectIntent("recents", ctx).intent
    )
  }

  @Test
  fun notificationsUtteranceRoutesToOpenNotifications() {
    val detector = IntentDetector()
    val ctx = com.example.brain.ConversationContext()

    assertEquals(
      DvexIntent.OpenNotifications,
      detector.detectIntent("show notifications", ctx).intent
    )
    assertEquals(
      DvexIntent.OpenNotifications,
      detector.detectIntent("notifications kaattu", ctx).intent
    )
  }

  // --- Notification listener enablement is checked honestly ---

  @Test
  fun notificationListenerCheckIsFalseWhenListenerNotEnabled() {
    // Robolectric has no enabled listeners by default -> must report false.
    assertFalse(DvexPermissionManager.isNotificationListenerEnabled(context))
  }

  @Test
  fun notificationListenerCheckDetectsEnabledComponent() {
    enableNotificationListener()
    assertTrue(
      "Listener must be reported enabled after OS registration",
      DvexPermissionManager.isNotificationListenerEnabled(context)
    )
  }

  @Test
  fun notificationsStateFlowStartsEmptyNoPlaceholderData() {
    // Fresh service state: empty list, no fabricated "New message" entries.
    val captured = DvexNotificationListenerService.notifications.value
    assertTrue("Fresh state must be empty, never placeholder data", captured.isEmpty())
    assertFalse(DvexNotificationListenerService.isListenerConnected())
  }

  // --- Helpers ---

  private fun setUsageAccessMode(mode: Int) {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    shadowOf(appOps).setMode(
      AppOpsManager.OPSTR_GET_USAGE_STATS,
      Process.myUid(),
      context.packageName,
      mode
    )
  }

  private fun grantUsageAccess() = setUsageAccessMode(AppOpsManager.MODE_ALLOWED)

  private fun enableNotificationListener() {
    // Robolectric's shadowed ContentResolver accepts Settings.Secure writes, which
    // the real OS forbids. "enabled_notification_listeners" is the OS setting that
    // NotificationManagerCompat.getEnabledListenerPackages() parses (its constant
    // is hidden from the SDK stubs, hence the literal).
    android.provider.Settings.Secure.putString(
      context.contentResolver,
      "enabled_notification_listeners",
      "${context.packageName}/${DvexNotificationListenerService::class.java.name}"
    )
  }
}

package com.example.permissions

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.control.DvexAccessibilityService

/**
 * Manages runtime, system overlay, and accessibility permissions contextually.
 * Strictly adheres to Android security best practices.
 */
object DvexPermissionManager {

  fun hasAudioPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun hasCameraPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
  }

  /** True when fine OR coarse location is granted (coarse is enough for a city-level weather fix). */
  fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  fun hasCallPhonePermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun hasContactsPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun hasSmsPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.SEND_SMS
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun hasAllCommunicationPermissions(context: Context): Boolean {
    return hasCallPhonePermission(context) && hasContactsPermission(context) && hasSmsPermission(context)
  }

  /**
   * True when the user granted the special "Usage access" permission
   * (PACKAGE_USAGE_STATS). There is no runtime dialog for it — the user must
   * enable it manually via [createUsageAccessSettingsIntent].
   */
  fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
      ?: return false
    val mode = appOps.checkOpNoThrow(
      AppOpsManager.OPSTR_GET_USAGE_STATS,
      android.os.Process.myUid(),
      context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
  }

  /** Settings deep-link: "Usage access" list where the user enables PACKAGE_USAGE_STATS. */
  fun createUsageAccessSettingsIntent(): Intent {
    return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  /**
   * True when D-VEX's [com.example.service.DvexNotificationListenerService] is an
   * enabled listener component. Enabled here means the OS-component check; the
   * listener also reports live connection state via its companion.
   */
  fun isNotificationListenerEnabled(context: Context): Boolean {
    // Public androidx API over the OS "enabled_notification_listeners" secure setting.
    // (Settings.Secure.ENABLED_NOTIFICATION_LISTENERS itself is a hidden constant.)
    return NotificationManagerCompat.getEnabledListenerPackages(context)
      .contains(context.packageName)
  }

  /** Settings deep-link: "Notification access" list where the user enables the listener. */
  fun createNotificationListenerSettingsIntent(): Intent {
    return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  fun hasOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Settings.canDrawOverlays(context)
    } else {
      true
    }
  }

  fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    val expectedId = "${context.packageName}/${DvexAccessibilityService::class.java.canonicalName}"
    return enabledServices.any { it.id.equals(expectedId, ignoreCase = true) || it.resolveInfo.serviceInfo.packageName == context.packageName }
  }

  fun createOverlayPermissionIntent(context: Context): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
      ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    } else {
      createAppSettingsIntent(context)
    }
  }

  fun createAccessibilitySettingsIntent(): Intent {
    return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  fun createNotificationSettingsIntent(context: Context): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    } else {
      createAppSettingsIntent(context)
    }
  }

  fun createAppSettingsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:${context.packageName}")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  fun hasBatteryOptimizationExemption(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
      pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    } else {
      true
    }
  }

  fun createBatteryOptimizationIntent(context: Context): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    } else {
      createAppSettingsIntent(context)
    }
  }
}

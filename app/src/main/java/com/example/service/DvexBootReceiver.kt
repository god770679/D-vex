package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.overlay.DvexFloatingOrbService
import com.example.permissions.DvexPermissionManager
import com.example.repository.AssistantRepository

/**
 * Restores D-VEX Always-Ready services after device restart or app update,
 * strictly subject to valid user permissions and configuration.
 */
class DvexBootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    Log.i(TAG, "Received broadcast intent: $action")

    if (action != Intent.ACTION_BOOT_COMPLETED &&
      action != Intent.ACTION_MY_PACKAGE_REPLACED &&
      action != "android.intent.action.QUICKBOOT_POWERON"
    ) {
      return
    }

    try {
      val repository = AssistantRepository.getInstance(context)
      val settings = repository.settings.value

      if (!settings.alwaysReadyEnabled) {
        Log.i(TAG, "Always-Ready protocol is not enabled. Skipping service restoration.")
        return
      }

      // Per Android while-in-use FGS restrictions, microphone foreground service is NOT started from background boot/update.
      // It will start only from an allowed visible/user-initiated foreground state when the app is opened.

      // Check overlay permission before restoring floating orb
      if (settings.floatingOrbEnabled && DvexPermissionManager.hasOverlayPermission(context)) {
        Log.i(TAG, "Overlay permission valid. Restoring DvexFloatingOrbService on boot.")
        DvexFloatingOrbService.start(context)
      } else {
        Log.w(TAG, "Overlay permission missing or orb disabled. Skipping floating orb restoration.")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error restoring D-VEX services during boot receiver execution", e)
    }
  }

  companion object {
    private const val TAG = "DvexBootReceiver"
  }
}

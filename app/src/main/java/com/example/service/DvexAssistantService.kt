package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.DvexAssistantState
import com.example.overlay.DvexFloatingOrbService
import com.example.repository.AssistantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service maintaining D-VEX Always-Ready capability, wake-word coordination,
 * and floating orb lifecycle outside the main app.
 */
class DvexAssistantService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var stateObserverJob: Job? = null
  private lateinit var assistantRepo: AssistantRepository

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    assistantRepo = AssistantRepository.getInstance(this)
    createNotificationChannel()
    Log.i(TAG, "DvexAssistantService created.")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_SERVICE) {
      Log.i(TAG, "Stop action requested for DvexAssistantService.")
      stopSelf()
      return START_NOT_STICKY
    }

    val foregroundStarted = startAsForeground()
    if (!foregroundStarted) {
      Log.w(TAG, "Microphone FGS could not be legally started. Stopping service gracefully.")
      return START_NOT_STICKY
    }

    // Coordinate Floating Orb
    val settings = assistantRepo.settings.value
    if (settings.floatingOrbEnabled) {
      DvexFloatingOrbService.start(this)
    }

    // Coordinate Wake Word
    if (settings.wakeWordEnabled) {
      assistantRepo.startWakeWordListening()
    }

    // Observe state to update notification dynamically
    stateObserverJob?.cancel()
    stateObserverJob = serviceScope.launch {
      assistantRepo.assistantState.collectLatest { state ->
        updateNotification(state)
      }
    }

    return START_STICKY
  }

  private fun startAsForeground(): Boolean {
    val notification = buildNotification(assistantRepo.assistantState.value)
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Must specify FOREGROUND_SERVICE_TYPE_MICROPHONE when declared in manifest
        startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
      true
    } catch (e: Exception) {
      // Handles ForegroundServiceStartNotAllowedException, SecurityException, and MissingForegroundServiceTypeException
      Log.e(TAG, "Microphone foreground service start failed or restricted: ${e.message}", e)
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
          @Suppress("DEPRECATION")
          stopForeground(true)
        }
      } catch (_: Exception) {}
      stopSelf()
      false
    }
  }

  private fun buildNotification(state: DvexAssistantState): Notification {
    val openIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val openPendingIntent = PendingIntent.getActivity(
      this, 0, openIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val stopIntent = Intent(this, DvexAssistantService::class.java).apply {
      action = ACTION_STOP_SERVICE
    }
    val stopPendingIntent = PendingIntent.getService(
      this, 1, stopIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val statusSubtitle = when (state) {
      is DvexAssistantState.Idle -> "Ready • Standby"
      is DvexAssistantState.Standby -> "Ready • Standby"
      is DvexAssistantState.WakeWordListening -> "Listening for \"D-VEX\"..."
      is DvexAssistantState.Listening -> "Listening..."
      is DvexAssistantState.Processing -> "Got it, Sir..."
      is DvexAssistantState.ExecutingAction -> "Working on it, Sir..."
      is DvexAssistantState.Speaking -> "Speaking..."
      is DvexAssistantState.Error -> state.message
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("D-VEX // TACTICAL ASSISTANT")
      .setContentText(statusSubtitle)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentIntent(openPendingIntent)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .addAction(0, "Open HUD", openPendingIntent)
      .addAction(0, "Deactivate", stopPendingIntent)
      .build()
  }

  private fun updateNotification(state: DvexAssistantState) {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "D-VEX Always-Ready Assistant",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Displays the background readiness and state of the D-VEX AI Assistant."
        setShowBadge(false)
      }
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    stateObserverJob?.cancel()
    serviceScope.cancel()

    // Stop orb and wake-word on service shutdown
    DvexFloatingOrbService.stop(this)
    assistantRepo.wakeWordManager.stop()

    Log.i(TAG, "DvexAssistantService destroyed cleanly.")
  }

  companion object {
    private const val TAG = "DvexAssistantService"
    const val CHANNEL_ID = "dvex_assistant_fg_channel"
    const val NOTIFICATION_ID = 2026
    const val ACTION_STOP_SERVICE = "com.example.dvex.ACTION_STOP_SERVICE"

    fun start(context: Context) {
      try {
        val intent = Intent(context, DvexAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: Exception) {
        // Safely catches ForegroundServiceStartNotAllowedException, SecurityException, etc.
        Log.w(TAG, "Cannot start DvexAssistantService from current state: ${e.message}")
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, DvexAssistantService::class.java).apply {
        action = ACTION_STOP_SERVICE
      }
      context.startService(intent)
    }
  }
}

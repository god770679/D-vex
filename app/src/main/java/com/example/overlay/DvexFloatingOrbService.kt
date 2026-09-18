package com.example.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.R
import com.example.model.DvexAssistantState
import com.example.permissions.DvexPermissionManager
import com.example.repository.AssistantRepository
import com.example.ui.components.DvexEnergyOrb
import com.example.ui.theme.DvexBorderRed
import com.example.ui.theme.DvexNeonRedBright
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * System-wide Floating Overlay Service that manages the interactive 56dp D-VEX circular energy orb
 * with a live status speech bubble, running as a persistent Foreground Service with microphone permissions.
 */
open class DvexFloatingOrbService : Service() {

  private var windowManager: WindowManager? = null
  private var overlayView: View? = null
  private var lifecycleOwner: OverlayLifecycleOwner? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var stateObserverJob: Job? = null
  private lateinit var assistantRepo: AssistantRepository

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    assistantRepo = AssistantRepository.getInstance(this)

    if (!DvexPermissionManager.hasOverlayPermission(this)) {
      Log.w(TAG, "Draw over other apps permission missing; stopping floating orb service.")
      stopSelf()
      return
    }

    createNotificationChannel()
    startAsForeground()

    // Coordinate passive wake-word listening outside the app if enabled and audio permission is granted
    if (assistantRepo.settings.value.wakeWordEnabled && DvexPermissionManager.hasAudioPermission(this)) {
      assistantRepo.startWakeWordListening()
    }

    // Observe assistant state changes to update the ongoing system notification
    stateObserverJob?.cancel()
    stateObserverJob = serviceScope.launch {
      assistantRepo.assistantState.collectLatest { state ->
        updateNotification(state)
      }
    }

    if (overlayView != null) {
      Log.d(TAG, "Floating orb overlay already displayed.")
      return
    }

    try {
      showFloatingOrb()
    } catch (e: Exception) {
      Log.e(TAG, "Error displaying floating orb overlay", e)
      stopSelf()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_ORB) {
      Log.i(TAG, "Stop action requested for DvexFloatingOrbService.")
      stopSelf()
      return START_NOT_STICKY
    }

    startAsForeground()
    return START_STICKY
  }

  private fun startAsForeground() {
    val notification = buildNotification(assistantRepo.assistantState.value)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
          0
        }
        startForeground(NOTIFICATION_ID_ORB, notification, fgType)
      } else {
        startForeground(NOTIFICATION_ID_ORB, notification)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Foreground start failed for orb service, attempting fallback", e)
      try {
        startForeground(NOTIFICATION_ID_ORB, notification)
      } catch (ex: Exception) {
        Log.e(TAG, "Secondary startForeground failed", ex)
      }
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

    val stopIntent = Intent(this, DvexFloatingOrbService::class.java).apply {
      action = ACTION_STOP_ORB
    }
    val stopPendingIntent = PendingIntent.getService(
      this, 2, stopIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val statusSubtitle = when (state) {
      is DvexAssistantState.Idle -> "Tactical Orb Active • Standby"
      is DvexAssistantState.Standby -> "Tactical Orb Active • Standby"
      is DvexAssistantState.WakeWordListening -> "Listening for \"D-VEX\"..."
      is DvexAssistantState.Listening -> "Microphone Active • Listening to Command"
      is DvexAssistantState.Processing -> "Analyzing Tactical Command..."
      is DvexAssistantState.ExecutingAction -> "Executing: ${state.toolName}"
      is DvexAssistantState.Speaking -> "Transmitting Response"
      is DvexAssistantState.Error -> "Alert: ${state.message}"
    }

    return NotificationCompat.Builder(this, CHANNEL_ID_ORB)
      .setContentTitle("D-VEX // TACTICAL OVERLAY")
      .setContentText(statusSubtitle)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentIntent(openPendingIntent)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .addAction(0, "Open HUD", openPendingIntent)
      .addAction(0, "Close Orb", stopPendingIntent)
      .build()
  }

  private fun updateNotification(state: DvexAssistantState) {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(NOTIFICATION_ID_ORB, buildNotification(state))
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID_ORB,
        "D-VEX Floating Overlay",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Maintains the system-wide floating overlay orb and voice interaction outside the application."
        setShowBadge(false)
      }
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun showFloatingOrb() {
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val settings = assistantRepo.settings.value

    val wmType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_PHONE
    }

    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      wmType,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = settings.orbPositionX.coerceAtLeast(20)
      y = settings.orbPositionY.coerceAtLeast(100)
    }

    val owner = OverlayLifecycleOwner()
    owner.onCreate()
    owner.onStart()
    lifecycleOwner = owner

    val composeView = ComposeView(this).apply {
      setViewTreeLifecycleOwner(owner)
      setViewTreeSavedStateRegistryOwner(owner)
      setViewTreeViewModelStoreOwner(owner)

      setContent {
        val assistantState by assistantRepo.assistantState.collectAsState()
        val latestResponse by assistantRepo.latestResponse.collectAsState()
        val currentSettings by assistantRepo.settings.collectAsState()

        OrbFloatingContainer(
          assistantState = assistantState,
          latestResponse = latestResponse,
          orbSize = currentSettings.orbSizeDp.dp
        )
      }
    }

    // Interactive Touch & Drag Listener with Edge Snapping, Single-Tap Voice Listen, and Long-Press Full HUD
    var initialX = 0
    var initialY = 0
    var initialTouchX = 0f
    var initialTouchY = 0f
    var isLongPress = false
    val longPressRunnable = Runnable {
      isLongPress = true
      triggerTacticalHaptic()
      openMainHud(startListening = false)
    }

    composeView.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialX = params.x
          initialY = params.y
          initialTouchX = event.rawX
          initialTouchY = event.rawY
          isLongPress = false
          mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - initialTouchX).toInt()
          val dy = (event.rawY - initialTouchY).toInt()
          if (abs(dx) > 12 || abs(dy) > 12) {
            mainHandler.removeCallbacks(longPressRunnable)
          }
          params.x = initialX + dx
          params.y = initialY + dy
          try {
            windowManager?.updateViewLayout(composeView, params)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to update overlay position", e)
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          mainHandler.removeCallbacks(longPressRunnable)
          val totalDx = abs(event.rawX - initialTouchX)
          val totalDy = abs(event.rawY - initialTouchY)

          if (totalDx < 15 && totalDy < 15 && !isLongPress) {
            // Single tap: Capture voice command or toggle listening anywhere on device
            triggerTacticalHaptic()
            Log.i(TAG, "Single-tap detected on floating orb outside app")
            if (!DvexPermissionManager.hasAudioPermission(this@DvexFloatingOrbService)) {
              Log.w(TAG, "Audio permission missing; opening settings")
              openSettings()
            } else {
              val currentState = assistantRepo.assistantState.value
              if (currentState is DvexAssistantState.Listening) {
                Log.i(TAG, "Stopping active listening from orb tap")
                assistantRepo.stopListening()
              } else {
                Log.i(TAG, "Triggering persistent voice command capture from system-wide orb")
                assistantRepo.startListeningForCommand()
              }
            }
          } else {
            // Snap to nearest screen edge after dragging
            snapToScreenEdge(params, composeView)
            assistantRepo.saveOrbPosition(params.x, params.y)
          }
          true
        }
        MotionEvent.ACTION_CANCEL -> {
          mainHandler.removeCallbacks(longPressRunnable)
          true
        }
        else -> false
      }
    }

    overlayView = composeView
    windowManager?.addView(composeView, params)
    Log.i(TAG, "Floating D-VEX orb overlay view attached to WindowManager with foreground persistence.")
  }

  private fun snapToScreenEdge(params: WindowManager.LayoutParams, view: View) {
    val displayMetrics = resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels
    val halfScreen = screenWidth / 2

    params.x = if (params.x + 28.dpToPx() < halfScreen) {
      12.dpToPx() // Left edge
    } else {
      screenWidth - 68.dpToPx() // Right edge
    }
    try {
      windowManager?.updateViewLayout(view, params)
    } catch (e: Exception) {
      Log.e(TAG, "Edge snapping update failed", e)
    }
  }

  private fun openMainHud(startListening: Boolean = true) {
    val intent = Intent(this, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      putExtra(EXTRA_START_LISTENING, startListening)
    }
    startActivity(intent)
  }

  private fun openSettings() {
    val intent = Intent(this, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      putExtra(EXTRA_OPEN_SETTINGS, true)
    }
    startActivity(intent)
  }

  override fun onDestroy() {
    super.onDestroy()
    stateObserverJob?.cancel()
    serviceScope.cancel()
    try {
      overlayView?.let { windowManager?.removeView(it) }
      overlayView = null
    } catch (e: Exception) {
      Log.e(TAG, "Error removing overlay view", e)
    }
    lifecycleOwner?.onDestroy()
    lifecycleOwner = null
    Log.i(TAG, "DvexFloatingOrbService destroyed cleanly.")
  }

  private fun triggerTacticalHaptic() {
    try {
      val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(45)
      }
    } catch (e: Exception) {
      Log.d(TAG, "Haptic unavailable: ${e.message}")
    }
  }

  private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

  companion object {
    private const val TAG = "[D-VEX][ORB]"
    const val CHANNEL_ID_ORB = "dvex_floating_orb_fg_channel"
    const val NOTIFICATION_ID_ORB = 2027
    const val ACTION_STOP_ORB = "com.example.dvex.ACTION_STOP_ORB"
    const val EXTRA_START_LISTENING = "EXTRA_START_LISTENING"
    const val EXTRA_OPEN_SETTINGS = "EXTRA_OPEN_SETTINGS"

    fun start(context: Context) {
      if (DvexPermissionManager.hasOverlayPermission(context)) {
        val intent = Intent(context, DvexFloatingOrbService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          try {
            context.startForegroundService(intent)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to startForegroundService for orb, falling back", e)
            context.startService(intent)
          }
        } else {
          context.startService(intent)
        }
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, DvexFloatingOrbService::class.java).apply {
        action = ACTION_STOP_ORB
      }
      context.startService(intent)
    }
  }
}

/**
 * Renders the floating energy orb paired with the live status speech bubble.
 */
@Composable
private fun OrbFloatingContainer(
  assistantState: DvexAssistantState,
  latestResponse: String,
  orbSize: Dp
) {
  var isBubbleVisible by remember { mutableStateOf(false) }
  var currentBubbleText by remember { mutableStateOf("💬 Standby") }

  LaunchedEffect(assistantState, latestResponse) {
    val text = when (assistantState) {
      is DvexAssistantState.Listening -> "💬 Listening..."
      is DvexAssistantState.Processing -> "💬 Processing..."
      is DvexAssistantState.ExecutingAction -> {
        if (latestResponse.isNotBlank() && latestResponse.startsWith("Opening", ignoreCase = true)) {
          "💬 $latestResponse"
        } else if (assistantState.toolName.isNotBlank()) {
          val cleanName = assistantState.toolName.replace("_", " ")
          "💬 ${cleanName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }}..."
        } else {
          "💬 Executing..."
        }
      }
      is DvexAssistantState.Speaking -> {
        if (latestResponse.isNotBlank() && latestResponse.length <= 26) {
          "💬 $latestResponse"
        } else {
          "💬 Speaking..."
        }
      }
      is DvexAssistantState.Error -> "💬 Alert: ${assistantState.message.take(18)}"
      is DvexAssistantState.WakeWordListening -> "💬 Standby"
      is DvexAssistantState.Standby, is DvexAssistantState.Idle -> "💬 Idle"
    }
    currentBubbleText = text

    when (assistantState) {
      is DvexAssistantState.Listening,
      is DvexAssistantState.Processing,
      is DvexAssistantState.ExecutingAction,
      is DvexAssistantState.Speaking,
      is DvexAssistantState.Error -> {
        isBubbleVisible = true
      }
      is DvexAssistantState.Standby,
      is DvexAssistantState.Idle,
      is DvexAssistantState.WakeWordListening -> {
        // Show status briefly (e.g. "💬 Idle"), then fade out after 3.5 seconds
        isBubbleVisible = true
        delay(3500)
        isBubbleVisible = false
      }
    }
  }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.wrapContentSize()
  ) {
    // Small floating status bubble (speech bubble style with 💬 icon)
    AnimatedVisibility(
      visible = isBubbleVisible,
      enter = fadeIn(animationSpec = tween(250)) + scaleIn(initialScale = 0.8f),
      exit = fadeOut(animationSpec = tween(500)) + scaleOut(targetScale = 0.8f)
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 2.dp)
      ) {
        Box(
          modifier = Modifier
            .clip(CutCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 1.dp))
            .background(Color(0xE60A0A0E))
            .border(
              width = 1.dp,
              color = DvexBorderRed,
              shape = CutCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 1.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
          Text(
            text = currentBubbleText,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = DvexNeonRedBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        // Small downward triangle pointer pointing towards the orb
        Canvas(modifier = Modifier.size(width = 6.dp, height = 3.dp)) {
          val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2f, size.height)
            close()
          }
          drawPath(path, color = DvexBorderRed)
        }
      }
    }

    // 56dp Circular Cybernetic Energy Reactor Orb
    DvexEnergyOrb(
      assistantState = assistantState,
      orbSize = orbSize
    )
  }
}

/**
 * Custom LifecycleOwner facilitating Jetpack Compose rendering inside WindowManager overlay.
 */
private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
  private val lifecycleRegistry = LifecycleRegistry(this)
  private val savedStateRegistryController = SavedStateRegistryController.create(this)
  private val store = ViewModelStore()

  override val lifecycle: Lifecycle get() = lifecycleRegistry
  override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
  override val viewModelStore: ViewModelStore get() = store

  fun onCreate() {
    savedStateRegistryController.performRestore(null)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
  }

  fun onStart() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
  }

  fun onDestroy() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    store.clear()
  }
}

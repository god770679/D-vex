package com.example.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
import com.example.model.DvexAssistantState
import com.example.permissions.DvexPermissionManager
import com.example.repository.AssistantRepository
import com.example.ui.components.DvexEnergyOrb
import kotlin.math.abs

/**
 * Floating Overlay Service that manages the interactive 56dp D-VEX circular energy orb
 * rendered over other applications using WindowManager.
 */
class DvexFloatingOrbService : Service() {

  private var windowManager: WindowManager? = null
  private var overlayView: View? = null
  private var lifecycleOwner: OverlayLifecycleOwner? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    if (!DvexPermissionManager.hasOverlayPermission(this)) {
      Log.w(TAG, "Draw over other apps permission missing; stopping floating orb service.")
      stopSelf()
      return
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

  private fun showFloatingOrb() {
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val assistantRepo = AssistantRepository.getInstance(this)
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
        val currentSettings by assistantRepo.settings.collectAsState()

        // ONLY a circular cybernetic energy reactor - transparent outside
        DvexEnergyOrb(
          assistantState = assistantState,
          orbSize = currentSettings.orbSizeDp.dp
        )
      }
    }

    // Interactive Touch & Drag Listener with Edge Snapping, Single-Tap Listen, and Long-Press Full HUD
    var initialX = 0
    var initialY = 0
    var initialTouchX = 0f
    var initialTouchY = 0f
    var isLongPress = false
    val longPressRunnable = Runnable {
      isLongPress = true
      triggerTacticalHaptic()
      // Long press: Open the full D-VEX tactical HUD
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
            // Single tap: Start voice listening immediately without opening full HUD
            triggerTacticalHaptic()
            Log.i(TAG, "Single-tap detected on floating orb")
            if (!DvexPermissionManager.hasAudioPermission(this@DvexFloatingOrbService)) {
              Log.w(TAG, "Audio permission missing; opening settings")
              openSettings()
            } else {
              val currentState = assistantRepo.assistantState.value
              if (currentState is DvexAssistantState.Listening) {
                Log.i(TAG, "Stopping active listening")
                assistantRepo.stopListening()
              } else {
                Log.i(TAG, "Triggering voice command capture from orb")
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
    Log.i(TAG, "Floating D-VEX orb overlay view attached to WindowManager.")
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
    const val EXTRA_START_LISTENING = "EXTRA_START_LISTENING"
    const val EXTRA_OPEN_SETTINGS = "EXTRA_OPEN_SETTINGS"

    fun start(context: Context) {
      if (DvexPermissionManager.hasOverlayPermission(context)) {
        val intent = Intent(context, DvexFloatingOrbService::class.java)
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, DvexFloatingOrbService::class.java)
      context.stopService(intent)
    }
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

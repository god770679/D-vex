package com.example.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Optional User-Authorized Accessibility Service for standard Android global navigation
 * (Home, Back, Recents, Notifications).
 *
 * SAFETY GUARANTEE:
 * - NO background screen content harvesting or keylogging.
 * - NO credential or password extraction.
 * - NO unauthorized bypass of system security prompts.
 * - Exclusively used to execute user voice commands (e.g. "Go home", "Go back").
 */
class DvexAccessibilityService : AccessibilityService() {

  override fun onServiceConnected() {
    super.onServiceConnected()
    instanceRef = WeakReference(this)
    _isServiceConnected.value = true
    Log.i(TAG, "D-VEX Accessibility Service connected with user authorization.")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // Intentionally no-op: We do NOT track or harvest user events or keystrokes
  }

  override fun onInterrupt() {
    Log.i(TAG, "D-VEX Accessibility Service interrupted.")
  }

  override fun onDestroy() {
    super.onDestroy()
    if (instanceRef?.get() == this) {
      instanceRef = null
    }
    _isServiceConnected.value = false
    Log.i(TAG, "D-VEX Accessibility Service destroyed.")
  }

  companion object {
    private const val TAG = "DvexAccessibility"
    private var instanceRef: WeakReference<DvexAccessibilityService>? = null

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    fun isEnabled(context: Context): Boolean {
      return instanceRef?.get() != null
    }

    fun performBack(): Boolean {
      val service = instanceRef?.get() ?: return false
      return service.performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performHome(): Boolean {
      val service = instanceRef?.get() ?: return false
      return service.performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performRecents(): Boolean {
      val service = instanceRef?.get() ?: return false
      return service.performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun performNotifications(): Boolean {
      val service = instanceRef?.get() ?: return false
      return service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun performScrollDown(): Boolean {
      val service = instanceRef?.get() ?: return false
      // 1. Try finding scrollable node in active window
      try {
        val root = service.rootInActiveWindow
        if (root != null) {
          val scrollableNode = findScrollableNode(root)
          if (scrollableNode != null) {
            val scrolled = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (scrolled) return true
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Node scroll down attempt threw exception: ${e.message}")
      }

      // 2. Fallback to swipe gesture if Android 24+
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        return dispatchSwipeGesture(service, swipeUp = true)
      }
      return false
    }

    fun performScrollUp(): Boolean {
      val service = instanceRef?.get() ?: return false
      try {
        val root = service.rootInActiveWindow
        if (root != null) {
          val scrollableNode = findScrollableNode(root)
          if (scrollableNode != null) {
            val scrolled = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (scrolled) return true
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Node scroll up attempt threw exception: ${e.message}")
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        return dispatchSwipeGesture(service, swipeUp = false)
      }
      return false
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
      if (node.isScrollable) return node
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        val found = findScrollableNode(child)
        if (found != null) return found
      }
      return null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchSwipeGesture(service: AccessibilityService, swipeUp: Boolean): Boolean {
      return try {
        val displayMetrics = service.resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val startX = width / 2f
        val endX = width / 2f
        val startY = if (swipeUp) height * 0.70f else height * 0.30f
        val endY = if (swipeUp) height * 0.30f else height * 0.70f

        val path = Path().apply {
          moveTo(startX, startY)
          lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
      } catch (e: Exception) {
        Log.e(TAG, "Error dispatching swipe gesture", e)
        false
      }
    }

    /**
     * Gets root AccessibilityNodeInfo of the active window.
     */
    fun getRootNode(): AccessibilityNodeInfo? {
      val service = instanceRef?.get() ?: return null
      return service.rootInActiveWindow
    }

    /**
     * Collects all nodes in active window hierarchy matching the predicate.
     */
    fun findNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
      val root = getRootNode() ?: return emptyList()
      val results = mutableListOf<AccessibilityNodeInfo>()
      traverseTree(root, predicate, results)
      return results
    }

    private fun traverseTree(
      node: AccessibilityNodeInfo,
      predicate: (AccessibilityNodeInfo) -> Boolean,
      results: MutableList<AccessibilityNodeInfo>
    ) {
      if (predicate(node)) {
        results.add(node)
      }
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        traverseTree(child, predicate, results)
      }
    }

    /**
     * Finds nodes matching specific text or content description (case-insensitive substring).
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
      val lower = text.lowercase(Locale.ROOT)
      return findNodes { node ->
        val nodeText = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        nodeText.contains(lower) || desc.contains(lower)
      }
    }

    /**
     * Performs a click on the node or nearest clickable parent.
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
      var current: AccessibilityNodeInfo? = node
      while (current != null) {
        if (current.isClickable) {
          return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        current = current.parent
      }
      return false
    }

    /**
     * Injects text into an editable node via AccessibilityNodeInfo.ACTION_SET_TEXT.
     */
    fun setTextInNode(node: AccessibilityNodeInfo, text: String): Boolean {
      val arguments = android.os.Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
      }
      val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
      if (success) {
        Log.i(TAG, "Successfully injected text into node: length=${text.length}")
      } else {
        Log.w(TAG, "Failed to inject text via ACTION_SET_TEXT")
      }
      return success
    }
  }
}


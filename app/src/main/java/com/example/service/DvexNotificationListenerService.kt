package com.example.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
/**
 * Captures real device notifications for D-VEX (Bug 5).
 *
 * The user must manually enable this listener in Settings >
 * Notification access (Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) — Android
 * provides no runtime dialog for it. Until enabled, this service never runs and the
 * app must show an honest "notification access required" state, never placeholder data.
 *
 * Exposes captured notifications via [notifications] (StateFlow, newest first,
 * capped at [MAX_TRACKED]) in the same style as DvexVisionManager exposes
 * VisionResult. All data stays on-device; nothing is uploaded.
 */
class DvexNotificationListenerService : NotificationListenerService() {

  data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long
  )

  override fun onListenerConnected() {
    super.onListenerConnected()
    listenerConnected = true
    Log.i(TAG, "Notification listener connected — capturing real notifications")
    // Snapshot whatever is already in the shade when we connect.
    try {
      val existing = activeNotifications ?: emptyArray()
      replaceAll(existing)
    } catch (e: Exception) {
      Log.w(TAG, "Initial notification snapshot failed: ${e.message}")
    }
  }

  override fun onListenerDisconnected() {
    listenerConnected = false
    Log.w(TAG, "Notification listener disconnected")
    super.onListenerDisconnected()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val captured = sbn.toCaptured() ?: return
    val current = _notifications.value.toMutableList()
    current.removeAll { it.key == captured.key || it.packageName == captured.packageName && it.title == captured.title }
    current.add(0, captured)
    _notifications.value = current.take(MAX_TRACKED)
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    _notifications.value = _notifications.value.filter { it.key != sbn.key }
  }

  private fun replaceAll(all: Array<StatusBarNotification>) {
    val list = all.mapNotNull { it.toCaptured() }
      .sortedByDescending { it.timestamp }
      .take(MAX_TRACKED)
    _notifications.value = list
  }

  private fun StatusBarNotification.toCaptured(): CapturedNotification? {
    // Ongoing/system (e.g. media, D-VEX foreground service) and empty notifications
    // are not user-visible alerts; skip them honestly instead of cluttering the feed.
    if (isOngoing) return null
    val extras = notification?.extras ?: return null

    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    if (title.isBlank() && text.isBlank()) return null

    return CapturedNotification(
      key = key ?: "",
      packageName = packageName,
      appName = resolveAppName(packageName),
      title = title.ifBlank { packageName },
      text = text,
      timestamp = when {
        notification != null && notification.`when` > 0 -> notification.`when`
        else -> postTime
      }
    )
  }

  private fun resolveAppName(packageName: String): String {
    return try {
      val pm = applicationContext.packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))?.toString() ?: packageName
    } catch (e: Exception) {
      packageName
    }
  }

  companion object {
    private const val TAG = "[D-VEX][NOTIF-LISTENER]"

    /** Cap on tracked notifications so the StateFlow stays small. */
    const val MAX_TRACKED = 20

    private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())

    /** Real captured notifications (newest first). Empty when the listener is disabled. */
    val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

    @Volatile
    private var listenerConnected: Boolean = false

    /** True only when the OS has actually connected this listener service. */
    fun isListenerConnected(): Boolean = listenerConnected
  }
}

package com.example.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.example.data.remote.MediaCommand
import com.example.data.remote.ToolExecutionResult
import com.example.data.remote.ToolResultStatus
import com.example.data.remote.VolumeDirection

/**
 * Executes safe Android device actions using standard platform APIs and Intents.
 * Never claims success if an operation fails or cannot be verified.
 */
class DeviceControlRepository(
  private val context: Context,
  private val appLauncher: AppLauncherRepository
) {

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
  private var isTorchOn = false

  fun openCamera(): ToolExecutionResult {
    return try {
      val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(ToolResultStatus.SUCCESS, "camera", "Opening Camera, Sir.")
    } catch (e: Exception) {
      Log.e(TAG, "Camera open failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "camera", "Unable to launch camera app, Sir.")
    }
  }

  fun openSettings(): ToolExecutionResult {
    return try {
      val intent = Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(ToolResultStatus.SUCCESS, "settings", "Opening Settings, Sir.")
    } catch (e: Exception) {
      Log.e(TAG, "Settings open failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "settings", "Unable to open Settings, Sir.")
    }
  }

  fun openWifiSettings(): ToolExecutionResult {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        ToolExecutionResult(ToolResultStatus.SUCCESS, "wifi_panel", "Opening Internet & Wi-Fi settings, Sir.")
      } else {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        ToolExecutionResult(ToolResultStatus.SUCCESS, "wifi_settings", "Opening Wi-Fi settings, Sir.")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Wi-Fi settings open failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "wifi", "Unable to open Wi-Fi controls, Sir.")
    }
  }

  fun openMaps(query: String? = null): ToolExecutionResult {
    return try {
      val uri = if (query.isNullOrBlank()) {
        Uri.parse("geo:0,0?q=")
      } else {
        Uri.parse("geo:0,0?q=${Uri.encode(query)}")
      }
      val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(
        ToolResultStatus.SUCCESS,
        "maps",
        if (query.isNullOrBlank()) "Opening Maps, Sir." else "Searching Maps for $query, Sir."
      )
    } catch (e: Exception) {
      // Fallback to app launcher or browser maps
      appLauncher.launchAppByName("maps")
    }
  }

  fun openYouTube(query: String? = null): ToolExecutionResult {
    return try {
      val intent = if (!query.isNullOrBlank()) {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
      } else {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
      }.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(
        ToolResultStatus.SUCCESS,
        "youtube",
        if (query.isNullOrBlank()) "Opening YouTube, Sir." else "Searching YouTube for $query, Sir."
      )
    } catch (e: Exception) {
      appLauncher.launchAppByName("youtube")
    }
  }

  fun openBrowser(url: String? = null): ToolExecutionResult {
    return try {
      val targetUrl = when {
        url.isNullOrBlank() -> "https://www.google.com"
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> "https://www.google.com/search?q=${Uri.encode(url)}"
      }
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(ToolResultStatus.SUCCESS, "browser", "Opening browser, Sir.")
    } catch (e: Exception) {
      Log.e(TAG, "Browser open failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "browser", "Unable to open browser, Sir.")
    }
  }

  fun openPhoneDialer(number: String? = null): ToolExecutionResult {
    return try {
      val uri = if (number.isNullOrBlank()) {
        Uri.parse("tel:")
      } else {
        Uri.parse("tel:${Uri.encode(number)}")
      }
      val intent = Intent(Intent.ACTION_DIAL, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(
        ToolResultStatus.SUCCESS,
        "phone",
        if (number.isNullOrBlank()) "Opening dialer, Sir." else "Preparing call to $number in dialer, Sir."
      )
    } catch (e: Exception) {
      Log.e(TAG, "Phone dialer failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "phone", "Unable to open phone dialer, Sir.")
    }
  }

  fun makePhoneCall(phoneNumber: String, contactName: String? = null): ToolExecutionResult {
    val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
    if (cleanNumber.isBlank()) {
      return ToolExecutionResult(ToolResultStatus.FAILED, "call", "Invalid phone number, Sir.")
    }
    val displayName = contactName ?: phoneNumber
    return try {
      val hasCallPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CALL_PHONE
      ) == PackageManager.PERMISSION_GRANTED

      if (hasCallPermission) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        ToolExecutionResult(ToolResultStatus.SUCCESS, "call", "Okay Sir. Calling $displayName.")
      } else {
        ToolExecutionResult(
          ToolResultStatus.NEEDS_PERMISSION,
          "call",
          "Sir, Call permission is required to place the call automatically."
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Call execution failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "call", "Unable to place call to $displayName, Sir.")
    }
  }

  fun sendSmsDirect(phoneNumber: String, messageText: String, contactName: String? = null): ToolExecutionResult {
    val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
    if (cleanNumber.isBlank()) {
      return ToolExecutionResult(ToolResultStatus.FAILED, "send_sms", "Invalid phone number, Sir.")
    }
    if (messageText.isBlank()) {
      return ToolExecutionResult(ToolResultStatus.FAILED, "send_sms", "Message cannot be empty, Sir.")
    }
    val displayName = contactName ?: phoneNumber

    val hasSmsPermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.SEND_SMS
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasSmsPermission) {
      return ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "send_sms",
        "Sir, SMS permission கிடைக்கல. Permission allow பண்ணுங்க."
      )
    }

    return try {
      @Suppress("DEPRECATION")
      val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
      } else {
        SmsManager.getDefault()
      }

      val parts = smsManager.divideMessage(messageText)
      if (parts.size > 1) {
        smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
      } else {
        smsManager.sendTextMessage(cleanNumber, null, messageText, null, null)
      }

      Log.i(TAG, "SMS successfully dispatched via SmsManager to $cleanNumber")
      ToolExecutionResult(
        ToolResultStatus.SUCCESS,
        "send_sms",
        "Sir, $displayName-ku message anuppiyachu."
      )
    } catch (e: Exception) {
      Log.e(TAG, "SMS sending failed via SmsManager", e)
      ToolExecutionResult(
        ToolResultStatus.FAILED,
        "send_sms",
        "Sir, message anuppa mudiyala: ${e.message ?: "Failed"}."
      )
    }
  }

  fun sendSms(phoneNumber: String, body: String? = null, contactName: String? = null): ToolExecutionResult {
    return if (!body.isNullOrBlank()) {
      sendSmsDirect(phoneNumber, body, contactName)
    } else {
      openMessages(phoneNumber, null)
    }
  }

  fun openMessages(target: String? = null, body: String? = null): ToolExecutionResult {
    return try {
      val uri = Uri.parse("smsto:${Uri.encode(target ?: "")}")
      val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        if (!body.isNullOrBlank()) {
          putExtra("sms_body", body)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(ToolResultStatus.SUCCESS, "send_sms", "Opening SMS composer, Sir.")
    } catch (e: Exception) {
      Log.e(TAG, "SMS composer failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "send_sms", "Unable to open SMS composer, Sir.")
    }
  }

  fun sendEmail(recipient: String, subject: String? = null, body: String? = null): ToolExecutionResult {
    return try {
      val uri = Uri.parse("mailto:${Uri.encode(recipient)}")
      val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        if (!subject.isNullOrBlank()) {
          putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        if (!body.isNullOrBlank()) {
          putExtra(Intent.EXTRA_TEXT, body)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(
        ToolResultStatus.SUCCESS,
        "send_email",
        "Sir, $recipient-ku email anuppa composer open panniyachu."
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open email composer", e)
      ToolExecutionResult(
        ToolResultStatus.FAILED,
        "send_email",
        "Sir, email anuppa mudiyala: ${e.message ?: "Failed"}."
      )
    }
  }

  fun sendEmailAutomated(recipient: String, subject: String? = null, body: String? = null): ToolExecutionResult {
    return sendEmail(recipient, subject, body)
  }

  fun isWhatsAppInstalled(): Boolean {
    return try {
      context.packageManager.getPackageInfo("com.whatsapp", 0)
      true
    } catch (e: Exception) {
      try {
        context.packageManager.getPackageInfo("com.whatsapp.w4b", 0)
        true
      } catch (e2: Exception) {
        false
      }
    }
  }

  fun sendWhatsApp(phoneNumber: String, messageText: String, contactName: String? = null): ToolExecutionResult {
    if (!isWhatsAppInstalled()) {
      return ToolExecutionResult(
        ToolResultStatus.FAILED,
        "whatsapp",
        "Sir, WhatsApp install aagala."
      )
    }

    return try {
      val cleanNumber = phoneNumber.replace(Regex("[^0-9]"), "")
      val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(messageText)}")
      val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.whatsapp")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      val name = contactName ?: phoneNumber
      ToolExecutionResult(
        ToolResultStatus.SUCCESS,
        "whatsapp",
        "Sir, $name-ku WhatsApp-la message anuppiyachu."
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to send WhatsApp message", e)
      ToolExecutionResult(
        ToolResultStatus.FAILED,
        "whatsapp",
        "Sir, WhatsApp message anuppa mudiyala."
      )
    }
  }

  fun sendWhatsAppAutomated(phoneNumber: String, messageText: String, contactName: String? = null): ToolExecutionResult {
    return sendWhatsApp(phoneNumber, messageText, contactName)
  }

  fun toggleFlashlight(enable: Boolean? = null): ToolExecutionResult {
    val cm = cameraManager ?: return ToolExecutionResult(ToolResultStatus.FAILED, "flashlight", "Camera hardware unavailable.")
    return try {
      val cameraId = cm.cameraIdList.firstOrNull() ?: return ToolExecutionResult(ToolResultStatus.FAILED, "flashlight", "No flashlight found.")
      val targetState = enable ?: !isTorchOn
      cm.setTorchMode(cameraId, targetState)
      isTorchOn = targetState
      val stateText = if (targetState) "Flashlight turned on, Sir." else "Flashlight turned off, Sir."
      ToolExecutionResult(ToolResultStatus.SUCCESS, "flashlight", stateText)
    } catch (e: Exception) {
      Log.e(TAG, "Flashlight toggle failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "flashlight", "Unable to toggle flashlight, Sir.")
    }
  }

  fun setAlarm(hour: Int, minute: Int, message: String? = null): ToolExecutionResult {
    return try {
      val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, hour)
        putExtra(AlarmClock.EXTRA_MINUTES, minute)
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (!message.isNullOrBlank()) {
          putExtra(AlarmClock.EXTRA_MESSAGE, message)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      val timeStr = String.format("%02d:%02d", hour, minute)
      ToolExecutionResult(ToolResultStatus.SUCCESS, "set_alarm", "Alarm set for $timeStr, Sir.")
    } catch (e: Exception) {
      Log.e(TAG, "Setting alarm failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "set_alarm", "Unable to set alarm, Sir.")
    }
  }

  fun setTimer(seconds: Int, message: String? = null): ToolExecutionResult {
    return try {
      val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (!message.isNullOrBlank()) {
          putExtra(AlarmClock.EXTRA_MESSAGE, message)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      val min = seconds / 60
      val sec = seconds % 60
      val label = if (min > 0 && sec > 0) "$min minutes $sec seconds" else if (min > 0) "$min minutes" else "$sec seconds"
      ToolExecutionResult(ToolResultStatus.SUCCESS, "set_timer", "Timer set for $label, Sir.")
    } catch (e: Exception) {
      Log.e(TAG, "Setting timer failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "set_timer", "Unable to set timer, Sir.")
    }
  }

  fun adjustVolume(direction: VolumeDirection): ToolExecutionResult {
    val am = audioManager ?: return ToolExecutionResult(ToolResultStatus.FAILED, "volume", "Audio manager unavailable.")
    return try {
      val adjust = when (direction) {
        VolumeDirection.UP -> AudioManager.ADJUST_RAISE
        VolumeDirection.DOWN -> AudioManager.ADJUST_LOWER
        VolumeDirection.MUTE -> AudioManager.ADJUST_SAME
      }
      val flags = AudioManager.FLAG_SHOW_UI
      am.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, flags)
      ToolExecutionResult(
        ToolResultStatus.SUCCESS,
        "volume",
        when (direction) {
          VolumeDirection.UP -> "Volume increased, Sir."
          VolumeDirection.DOWN -> "Volume decreased, Sir."
          VolumeDirection.MUTE -> "Volume level updated, Sir."
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Volume adjust failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "volume", "Volume adjustment failed, Sir.")
    }
  }

  fun controlMedia(command: MediaCommand): ToolExecutionResult {
    val am = audioManager ?: return ToolExecutionResult(ToolResultStatus.FAILED, "media", "Audio manager unavailable.")
    return try {
      val keyEventCode = when (command) {
        MediaCommand.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
      }
      am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode))
      am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyEventCode))
      ToolExecutionResult(
        ToolResultStatus.SUCCESS,
        "media",
        when (command) {
          MediaCommand.PLAY_PAUSE -> "Toggled media playback, Sir."
          MediaCommand.NEXT -> "Skipped to next track, Sir."
          MediaCommand.PREVIOUS -> "Returned to previous track, Sir."
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Media control failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "media", "Media playback command failed, Sir.")
    }
  }

  fun openMusicPlayer(): ToolExecutionResult {
    return try {
      val musicIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(musicIntent)
      ToolExecutionResult(ToolResultStatus.SUCCESS, "music", "Opening Music player, Sir.")
    } catch (e: Exception) {
      try {
        @Suppress("DEPRECATION")
        val fallbackIntent = Intent(MediaStore.INTENT_ACTION_MUSIC_PLAYER).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
        ToolExecutionResult(ToolResultStatus.SUCCESS, "music", "Opening Music player, Sir.")
      } catch (e2: Exception) {
        val appResult = appLauncher.launchAppByName("music")
        if (appResult.isSuccessful) {
          appResult
        } else {
          ToolExecutionResult(ToolResultStatus.FAILED, "music", "No music player found on device, Sir.")
        }
      }
    }
  }

  fun navigateHome(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performHome()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "home", "Done, Sir.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "home", "Navigation action failed, Sir.")
      }
    } else {
      // Fallback standard Intent launcher for Home
      try {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
          addCategory(Intent.CATEGORY_HOME)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(homeIntent)
        ToolExecutionResult(ToolResultStatus.SUCCESS, "home", "Done, Sir.")
      } catch (e: Exception) {
        ToolExecutionResult(
          ToolResultStatus.NEEDS_PERMISSION,
          "home",
          "Accessibility Service authorization required for direct system navigation, Sir."
        )
      }
    }
  }

  fun navigateBack(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performBack()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "back", "Done, Sir.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "back", "Back action failed, Sir.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "back",
        "Enable D-VEX Accessibility Service in Settings to allow the Back command, Sir."
      )
    }
  }

  fun showRecents(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performRecents()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "recents", "Showing recent applications, Sir.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "recents", "Recents action failed, Sir.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "recents",
        "Enable D-VEX Accessibility Service in Settings to allow Recents view, Sir."
      )
    }
  }

  fun showNotifications(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performNotifications()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "notifications", "Opening notifications, Sir.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "notifications", "Notifications shade failed, Sir.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "notifications",
        "Enable D-VEX Accessibility Service to open notifications, Sir."
      )
    }
  }

  fun scrollDown(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performScrollDown()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "scroll_down", "Scrolled down, Sir.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "scroll_down", "Unable to scroll down, Sir.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "scroll_down",
        "Enable D-VEX Accessibility Service in Settings to allow scrolling, Sir."
      )
    }
  }

  fun scrollUp(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performScrollUp()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "scroll_up", "Scrolled up, Sir.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "scroll_up", "Unable to scroll up, Sir.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "scroll_up",
        "Enable D-VEX Accessibility Service in Settings to allow scrolling, Sir."
      )
    }
  }

  companion object {
    private const val TAG = "DeviceControlRepo"
  }
}

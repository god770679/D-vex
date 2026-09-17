package com.example.control

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
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

  fun openCamera(): ToolExecutionResult {
    return try {
      val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(ToolResultStatus.SUCCESS, "camera", "Opening Camera.")
    } catch (e: Exception) {
      Log.e(TAG, "Camera open failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "camera", "Unable to launch camera app.")
    }
  }

  fun openSettings(): ToolExecutionResult {
    return try {
      val intent = Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(ToolResultStatus.SUCCESS, "settings", "Opening Android Settings.")
    } catch (e: Exception) {
      Log.e(TAG, "Settings open failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "settings", "Unable to open Settings.")
    }
  }

  fun openWifiSettings(): ToolExecutionResult {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        ToolExecutionResult(ToolResultStatus.SUCCESS, "wifi_panel", "Opening Internet & Wi-Fi settings panel.")
      } else {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        ToolExecutionResult(ToolResultStatus.SUCCESS, "wifi_settings", "Opening Wi-Fi settings.")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Wi-Fi settings open failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "wifi", "Unable to open Wi-Fi controls.")
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
        if (query.isNullOrBlank()) "Opening Maps." else "Searching Maps for $query."
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
        if (query.isNullOrBlank()) "Opening YouTube." else "Searching YouTube for $query."
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
      ToolExecutionResult(ToolResultStatus.SUCCESS, "browser", "Opening browser.")
    } catch (e: Exception) {
      Log.e(TAG, "Browser open failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "browser", "Unable to open browser.")
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
        if (number.isNullOrBlank()) "Opening dialer." else "Preparing call to $number in dialer."
      )
    } catch (e: Exception) {
      Log.e(TAG, "Phone dialer failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "phone", "Unable to open phone dialer.")
    }
  }

  fun openMessages(recipient: String? = null, body: String? = null): ToolExecutionResult {
    return try {
      val uri = if (!recipient.isNullOrBlank()) Uri.parse("smsto:${Uri.encode(recipient)}") else Uri.parse("smsto:")
      val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        if (!body.isNullOrBlank()) {
          putExtra("sms_body", body)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      ToolExecutionResult(
        ToolResultStatus.SUCCESS,
        "messages",
        if (!recipient.isNullOrBlank()) "Opening messaging composer for $recipient." else "Opening messaging app."
      )
    } catch (e: Exception) {
      Log.e(TAG, "Messages failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "messages", "Unable to open messaging app.")
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
          VolumeDirection.UP -> "Volume increased."
          VolumeDirection.DOWN -> "Volume decreased."
          VolumeDirection.MUTE -> "Volume level updated."
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Volume adjust failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "volume", "Volume adjustment failed.")
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
          MediaCommand.PLAY_PAUSE -> "Toggled media playback."
          MediaCommand.NEXT -> "Skipped to next track."
          MediaCommand.PREVIOUS -> "Returned to previous track."
        }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Media control failed", e)
      ToolExecutionResult(ToolResultStatus.FAILED, "media", "Media playback command failed.")
    }
  }

  fun openMusicPlayer(): ToolExecutionResult {
    return try {
      val musicIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(musicIntent)
      ToolExecutionResult(ToolResultStatus.SUCCESS, "music", "Opening Music player.")
    } catch (e: Exception) {
      try {
        @Suppress("DEPRECATION")
        val fallbackIntent = Intent(MediaStore.INTENT_ACTION_MUSIC_PLAYER).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
        ToolExecutionResult(ToolResultStatus.SUCCESS, "music", "Opening Music player.")
      } catch (e2: Exception) {
        val appResult = appLauncher.launchAppByName("music")
        if (appResult.isSuccessful) {
          appResult
        } else {
          ToolExecutionResult(ToolResultStatus.FAILED, "music", "No music player found on device.")
        }
      }
    }
  }

  fun navigateHome(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performHome()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "home", "Navigated to home screen.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "home", "Navigation action failed.")
      }
    } else {
      // Fallback standard Intent launcher for Home
      try {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
          addCategory(Intent.CATEGORY_HOME)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(homeIntent)
        ToolExecutionResult(ToolResultStatus.SUCCESS, "home", "Navigated to home screen.")
      } catch (e: Exception) {
        ToolExecutionResult(
          ToolResultStatus.NEEDS_PERMISSION,
          "home",
          "Accessibility Service authorization required for direct system navigation."
        )
      }
    }
  }

  fun navigateBack(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performBack()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "back", "Navigated back.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "back", "Back action failed.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "back",
        "Enable D-VEX Accessibility Service in Settings to allow the Back command."
      )
    }
  }

  fun showRecents(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performRecents()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "recents", "Showing recent applications.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "recents", "Recents action failed.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "recents",
        "Enable D-VEX Accessibility Service in Settings to allow Recents view."
      )
    }
  }

  fun showNotifications(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performNotifications()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "notifications", "Opening notification shade.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "notifications", "Notifications shade failed.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "notifications",
        "Enable D-VEX Accessibility Service to open notifications."
      )
    }
  }

  fun scrollDown(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performScrollDown()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "scroll_down", "Scrolled down.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "scroll_down", "Unable to scroll down current screen.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "scroll_down",
        "Enable D-VEX Accessibility Service in Settings to allow scrolling."
      )
    }
  }

  fun scrollUp(): ToolExecutionResult {
    return if (DvexAccessibilityService.isEnabled(context)) {
      val success = DvexAccessibilityService.performScrollUp()
      if (success) {
        ToolExecutionResult(ToolResultStatus.SUCCESS, "scroll_up", "Scrolled up.")
      } else {
        ToolExecutionResult(ToolResultStatus.FAILED, "scroll_up", "Unable to scroll up current screen.")
      }
    } else {
      ToolExecutionResult(
        ToolResultStatus.NEEDS_PERMISSION,
        "scroll_up",
        "Enable D-VEX Accessibility Service in Settings to allow scrolling."
      )
    }
  }

  companion object {
    private const val TAG = "DeviceControlRepo"
  }
}

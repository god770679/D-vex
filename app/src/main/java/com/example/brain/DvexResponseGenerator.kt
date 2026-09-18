package com.example.brain

import android.util.Log

/**
 * Natural conversational response layer for D-VEX Smart Brain.
 * Formulates concise, natural, tactical responses in English, Tamil, and Tanglish.
 * Never outputs robotic intent names or internal database errors.
 */
class DvexResponseGenerator {

  /**
   * Generates the final spoken and displayed text based on intent, tool result, and language.
   */
  fun generateResponse(
    intent: DvexIntent,
    toolResult: DvexToolResult,
    language: DetectedLanguage
  ): String {
    val response = when (language) {
      DetectedLanguage.TAMIL -> generateTamilResponse(intent, toolResult)
      DetectedLanguage.TANGLISH -> generateTanglishResponse(intent, toolResult)
      DetectedLanguage.ENGLISH -> generateEnglishResponse(intent, toolResult)
    }

    Log.i(TAG, "Generated response ($language): \"$response\"")
    return response
  }

  // --- English Responses ---
  private fun generateEnglishResponse(intent: DvexIntent, toolResult: DvexToolResult): String {
    return when (toolResult.status) {
      DvexToolStatus.NOT_FOUND -> {
        if (intent is DvexIntent.OpenApp) {
          "I couldn't find ${intent.appName} on your phone, Sir."
        } else {
          "I couldn't find that, Sir."
        }
      }
      DvexToolStatus.PERMISSION_REQUIRED -> {
        toolResult.spokenText.ifBlank {
          "D-VEX needs Accessibility permission for that action, Sir."
        }
      }
      DvexToolStatus.CONFIRMATION_REQUIRED -> {
        toolResult.confirmationPrompt ?: "Do you want me to proceed with that action, Sir?"
      }
      DvexToolStatus.UNSUPPORTED -> {
        toolResult.spokenText.ifBlank { "That action is not supported on this device, Sir." }
      }
      DvexToolStatus.FAILED -> {
        toolResult.spokenText.ifBlank { "I couldn't complete that, Sir." }
      }
      DvexToolStatus.ERROR -> {
        "A system error occurred, Sir. Please try again."
      }
      DvexToolStatus.SUCCESS -> {
        when (intent) {
          is DvexIntent.WakeGreeting -> "Yes, Sir. சொல்லுங்க."
          is DvexIntent.OpenApp -> "Sure, Sir. Opening ${intent.appName}."
          is DvexIntent.GoHome -> "Done, Sir."
          is DvexIntent.GoBack -> "Done, Sir."
          is DvexIntent.OpenRecents -> "Showing recent apps, Sir."
          is DvexIntent.OpenNotifications -> "Opening notifications, Sir."
          is DvexIntent.OpenSettings -> "Opening settings, Sir."
          is DvexIntent.OpenWifiSettings -> "Opening Wi-Fi settings, Sir."
          is DvexIntent.ToggleFlashlight -> toolResult.spokenText
          is DvexIntent.SetAlarm -> toolResult.spokenText
          is DvexIntent.SetTimer -> toolResult.spokenText
          is DvexIntent.AdjustVolume -> toolResult.spokenText
          is DvexIntent.MediaControl -> toolResult.spokenText
          is DvexIntent.GetTime -> toolResult.spokenText
          is DvexIntent.GetWeather -> toolResult.spokenText
          is DvexIntent.Calculate -> toolResult.spokenText
          is DvexIntent.RememberFact -> toolResult.spokenText
          is DvexIntent.RecallMemory -> toolResult.spokenText
          is DvexIntent.MultiStep -> toolResult.spokenText
          is DvexIntent.GeneralQuestion -> toolResult.spokenText
          is DvexIntent.Conversation -> toolResult.spokenText
          is DvexIntent.SearchWeb -> toolResult.spokenText
          else -> toolResult.spokenText.ifBlank { "Got it, Sir." }
        }
      }
    }
  }

  // --- Tanglish Responses ---
  private fun generateTanglishResponse(intent: DvexIntent, toolResult: DvexToolResult): String {
    return when (toolResult.status) {
      DvexToolStatus.NOT_FOUND -> {
        if (intent is DvexIntent.OpenApp) {
          "Intha app unga phone-la illa, Sir."
        } else {
          "Athu kedaikala, Sir."
        }
      }
      DvexToolStatus.PERMISSION_REQUIRED -> {
        "Intha action-ku Accessibility permission thevai, Sir."
      }
      DvexToolStatus.CONFIRMATION_REQUIRED -> {
        toolResult.confirmationPrompt ?: "Intha action-ah confirm panlaama, Sir?"
      }
      DvexToolStatus.FAILED -> {
        toolResult.spokenText.ifBlank { "Athai seiya mudiyala, Sir." }
      }
      DvexToolStatus.SUCCESS -> {
        when (intent) {
          is DvexIntent.WakeGreeting -> "Yes, Sir. Sollunga."
          is DvexIntent.OpenApp -> "Sure, Sir. ${intent.appName} open panren."
          is DvexIntent.GoHome -> "Home-ku poyachu, Sir."
          is DvexIntent.GoBack -> "Pinnaadi vandhachu, Sir."
          is DvexIntent.OpenNotifications -> "Notifications open panren, Sir."
          is DvexIntent.OpenSettings -> "Settings open panren, Sir."
          is DvexIntent.ToggleFlashlight -> toolResult.spokenText
          is DvexIntent.SetAlarm -> toolResult.spokenText
          is DvexIntent.SetTimer -> toolResult.spokenText
          is DvexIntent.GetTime -> toolResult.spokenText
          is DvexIntent.GetWeather -> toolResult.spokenText
          is DvexIntent.Calculate -> toolResult.spokenText
          is DvexIntent.RememberFact -> "Ninaivil vaithukkonden, Sir."
          is DvexIntent.RecallMemory -> toolResult.spokenText
          is DvexIntent.MultiStep -> toolResult.spokenText
          else -> toolResult.spokenText.ifBlank { "Sure, Sir. Ippove panren." }
        }
      }
      else -> toolResult.spokenText
    }
  }

  // --- Tamil Responses ---
  private fun generateTamilResponse(intent: DvexIntent, toolResult: DvexToolResult): String {
    return when (toolResult.status) {
      DvexToolStatus.NOT_FOUND -> {
        "அந்த ஆப் உங்கள் போனில் இல்லை, Sir."
      }
      DvexToolStatus.PERMISSION_REQUIRED -> {
        "இதற்கு Accessibility அனுமதி தேவை, Sir."
      }
      DvexToolStatus.CONFIRMATION_REQUIRED -> {
        toolResult.confirmationPrompt ?: "இதை உறுதிப்படுத்தவா, Sir?"
      }
      DvexToolStatus.FAILED -> {
        toolResult.spokenText.ifBlank { "இதை முடிக்க முடியவில்லை, Sir." }
      }
      DvexToolStatus.SUCCESS -> {
        when (intent) {
          is DvexIntent.WakeGreeting -> "Yes, Sir. சொல்லுங்க."
          is DvexIntent.OpenApp -> "Sure, Sir. ${intent.appName} திறக்கிறேன்."
          is DvexIntent.GoHome -> "Home-க்கு போயாச்சு, Sir."
          is DvexIntent.GoBack -> "பின் சென்றாச்சு, Sir."
          is DvexIntent.RememberFact -> "நினைவில் வைத்துக்கொண்டேன், Sir."
          is DvexIntent.ToggleFlashlight -> toolResult.spokenText
          is DvexIntent.SetAlarm -> toolResult.spokenText
          is DvexIntent.SetTimer -> toolResult.spokenText
          else -> toolResult.spokenText.ifBlank { "Sure, Sir. இப்பவே பண்றேன்." }
        }
      }
      else -> toolResult.spokenText
    }
  }

  companion object {
    private const val TAG = "[D-VEX][RESPONSE]"
  }
}

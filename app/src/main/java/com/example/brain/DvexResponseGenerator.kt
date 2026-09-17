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
          "I couldn't find that app on your phone."
        } else {
          "I couldn't find that."
        }
      }
      DvexToolStatus.PERMISSION_REQUIRED -> {
        toolResult.spokenText.ifBlank {
          "D-VEX needs Accessibility permission for that action."
        }
      }
      DvexToolStatus.CONFIRMATION_REQUIRED -> {
        toolResult.confirmationPrompt ?: "Do you want me to proceed with that action?"
      }
      DvexToolStatus.UNSUPPORTED -> {
        toolResult.spokenText.ifBlank { "That action is not supported on this device." }
      }
      DvexToolStatus.FAILED -> {
        toolResult.spokenText.ifBlank { "I couldn't complete that." }
      }
      DvexToolStatus.ERROR -> {
        "A system error occurred. Please try again."
      }
      DvexToolStatus.SUCCESS -> {
        when (intent) {
          is DvexIntent.OpenApp -> {
            "Opening ${intent.appName}."
          }
          is DvexIntent.GoHome -> "Done."
          is DvexIntent.GoBack -> "Done."
          is DvexIntent.OpenRecents -> "Showing recent apps."
          is DvexIntent.OpenNotifications -> "Opening notifications."
          is DvexIntent.OpenSettings -> "Opening settings."
          is DvexIntent.OpenWifiSettings -> "Opening Wi-Fi settings."
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
          is DvexIntent.SearchWeb -> "Searching the web for ${intent.query}."
          else -> toolResult.spokenText.ifBlank { "Done." }
        }
      }
    }
  }

  // --- Tanglish Responses ---
  private fun generateTanglishResponse(intent: DvexIntent, toolResult: DvexToolResult): String {
    return when (toolResult.status) {
      DvexToolStatus.NOT_FOUND -> {
        if (intent is DvexIntent.OpenApp) {
          "Intha app unga phone-la illa."
        } else {
          "Athu kedaikala."
        }
      }
      DvexToolStatus.PERMISSION_REQUIRED -> {
        "Intha action-ku Accessibility permission thevai."
      }
      DvexToolStatus.CONFIRMATION_REQUIRED -> {
        toolResult.confirmationPrompt ?: "Intha action-ah confirm panlaama?"
      }
      DvexToolStatus.FAILED -> {
        "Athai seiya mudiyala."
      }
      DvexToolStatus.SUCCESS -> {
        when (intent) {
          is DvexIntent.OpenApp -> "Opening ${intent.appName}."
          is DvexIntent.GoHome -> "Home-ku poyachu."
          is DvexIntent.GoBack -> "Pinnaadi poyachu."
          is DvexIntent.OpenNotifications -> "Notifications open panren."
          is DvexIntent.OpenSettings -> "Settings open panren."
          is DvexIntent.GetTime -> toolResult.spokenText
          is DvexIntent.GetWeather -> toolResult.spokenText
          is DvexIntent.Calculate -> toolResult.spokenText
          is DvexIntent.RememberFact -> "Ninaivil vaithukkonden."
          is DvexIntent.RecallMemory -> toolResult.spokenText
          is DvexIntent.MultiStep -> toolResult.spokenText
          else -> toolResult.spokenText
        }
      }
      else -> toolResult.spokenText
    }
  }

  // --- Tamil Responses ---
  private fun generateTamilResponse(intent: DvexIntent, toolResult: DvexToolResult): String {
    return when (toolResult.status) {
      DvexToolStatus.NOT_FOUND -> {
        "அந்த ஆப் உங்கள் போனில் இல்லை."
      }
      DvexToolStatus.PERMISSION_REQUIRED -> {
        "இதற்கு Accessibility அனுமதி தேவை."
      }
      DvexToolStatus.CONFIRMATION_REQUIRED -> {
        toolResult.confirmationPrompt ?: "இதை உறுதிப்படுத்தவா?"
      }
      DvexToolStatus.FAILED -> {
        "இதை முடிக்க முடியவில்லை."
      }
      DvexToolStatus.SUCCESS -> {
        when (intent) {
          is DvexIntent.OpenApp -> "${intent.appName} திறக்கப்படுகிறது."
          is DvexIntent.GoHome -> "முகப்புத் திரைக்கு மாற்றப்பட்டது."
          is DvexIntent.GoBack -> "பின் சென்றது."
          is DvexIntent.RememberFact -> "நினைவில் வைத்துக்கொண்டேன்."
          else -> toolResult.spokenText
        }
      }
      else -> toolResult.spokenText
    }
  }

  companion object {
    private const val TAG = "[D-VEX][RESPONSE]"
  }
}

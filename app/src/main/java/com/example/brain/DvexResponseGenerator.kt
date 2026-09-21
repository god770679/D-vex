package com.example.brain

import android.util.Log
import java.util.Locale

/**
 * Natural conversational response layer for D-VEX Smart Brain.
 * Formulates calm, smart, concise, confident, friendly, and respectful responses
 * in English, Tamil, and Tanglish. Naturally addresses the user as "Sir".
 * Never outputs robotic intent names, internal database errors, or parser jargon.
 */
class DvexResponseGenerator {

  /**
   * Estimates situational/conversational tone from user input.
   * Modulates response style without claiming certainty about the user's actual emotion.
   */
  fun estimateTone(userInput: String): EstimatedTone {
    val text = userInput.lowercase(Locale.ROOT)
    if (text.isBlank()) return EstimatedTone.NEUTRAL

    // Urgent cues
    if (text.contains("urgent") || text.contains("urgently") || text.contains("emergency") ||
        text.contains("immediately") || text.contains("right now") || text.contains("asap") ||
        text.contains("hurry") || text.contains("quick") || text.contains("quickly") ||
        text.contains("seekiram") || text.contains("seekirama") || text.contains("udane") ||
        text.contains("ippove") || text.contains("avacharam") || text.contains("vegam") ||
        text.contains("vegama") || text.contains("உடனே") || text.contains("சீக்கிரம்") ||
        text.contains("அவசரம்")
    ) {
      return EstimatedTone.URGENT
    }

    // Frustrated cues
    if (text.contains("annoying") || text.contains("stupid") || text.contains("worst") ||
        text.contains("waste") || text.contains("hate") || text.contains("angry") ||
        text.contains("not working") || text.contains("useless") || text.contains("shut up") ||
        text.contains("kaduppa") || text.contains("kadupethatha") || text.contains("erichal") ||
        text.contains("worstu") || text.contains("wasteu") || text.contains("vela seiyala") ||
        text.contains("vela pakkala") || text.contains("ennada idhu") || text.contains("thirumba thirumba") ||
        text.contains("கடுப்பு") || text.contains("எரிச்சல்") || text.contains("வேலை செய்யவில்லை")
    ) {
      return EstimatedTone.FRUSTRATED
    }

    // Confused cues
    if (text.contains("what do you mean") || text.contains("confused") || text.contains("don't understand") ||
        text.contains("dont understand") || text.contains("how come") || text.contains("puriyala") ||
        text.contains("puriyave illa") || text.contains("enna solra") || text.contains("enna soldra") ||
        text.contains("enna aachu") || text.contains("theriyala") || text.contains("புரியவில்லை") ||
        text.contains("என்ன சொல்றீங்க")
    ) {
      return EstimatedTone.CONFUSED
    }

    // Excited cues
    if (text.contains("wow") || text.contains("amazing") || text.contains("let's go") ||
        text.contains("unbelievable") || text.contains("vera level") || text.contains("mass kaatita") ||
        text.contains("massu") || text.contains("வேற லெவல்")
    ) {
      return EstimatedTone.EXCITED
    }

    // Happy / Grateful cues
    if (text.contains("super") || text.contains("awesome") || text.contains("great") ||
        text.contains("wonderful") || text.contains("happy") || text.contains("glad") ||
        text.contains("thank you so much") || text.contains("love it") || text.contains("semma") ||
        text.contains("kalakkita") || text.contains("arputham") || text.contains("மகிழ்ச்சி") ||
        text.contains("சூப்பர்") || text.contains("அற்புதம்")
    ) {
      return EstimatedTone.HAPPY
    }

    // Sad / Low cues
    if (text.contains("sad") || text.contains("depressed") || text.contains("feeling down") ||
        text.contains("bad day") || text.contains("unhappy") || text.contains("sogam") ||
        text.contains("sogama") || text.contains("kashtama") || text.contains("vali") ||
        text.contains("சோகம்") || text.contains("கஷ்டமா இருக்கு")
    ) {
      return EstimatedTone.SAD
    }

    return EstimatedTone.NEUTRAL
  }

  /**
   * Generates the final spoken and displayed text based on intent, tool result, language,
   * user input, short-term conversation context, and estimated conversational tone.
   */
  fun generateResponse(
    intent: DvexIntent,
    toolResult: DvexToolResult,
    language: DetectedLanguage,
    userInput: String = "",
    context: ConversationContext? = null,
    tone: EstimatedTone = estimateTone(userInput)
  ): String {
    // Internal cognitive synthesis:
    // 1. Consider user's goal (action vs question vs conversational interaction)
    // 2. Consider context (recent history, active apps, follow-up continuity)
    // 3. Match user's natural language (Tamil -> Tamil, Tanglish -> Tanglish, English -> English)
    // 4. Modulate style according to estimated tone (Urgent, Frustrated, Confused, Happy, etc.)
    // 5. Produce a calm, confident, smart, single cohesive response.
    val response = when (language) {
      DetectedLanguage.TAMIL -> generateTamilResponse(intent, toolResult, tone, context)
      DetectedLanguage.TANGLISH -> generateTanglishResponse(intent, toolResult, tone, context)
      DetectedLanguage.ENGLISH -> generateEnglishResponse(intent, toolResult, tone, context)
    }

    Log.i(TAG, "Generated response ($language, tone=$tone): \"$response\"")
    return response
  }

  // =========================================================================
  // --- English Responses ---
  // =========================================================================
  private fun generateEnglishResponse(
    intent: DvexIntent,
    toolResult: DvexToolResult,
    tone: EstimatedTone,
    context: ConversationContext?
  ): String {
    return when (toolResult.status) {
      DvexToolStatus.NOT_FOUND -> {
        if (toolResult.spokenText.isNotBlank()) {
          toolResult.spokenText
        } else when (intent) {
          is DvexIntent.OpenApp -> "I couldn't find ${intent.appName} on your phone, Sir."
          is DvexIntent.CallContact -> "I couldn't find ${intent.recipient} in your contacts, Sir."
          is DvexIntent.SendMessage -> "I couldn't find ${intent.recipient} in your contacts, Sir."
          is DvexIntent.SendEmail -> "I couldn't find an email address for ${intent.recipient}, Sir."
          else -> "I couldn't find that, Sir."
        }
      }
      DvexToolStatus.PERMISSION_REQUIRED -> {
        toolResult.spokenText.ifBlank {
          "D-VEX needs permission for that action, Sir."
        }
      }
      DvexToolStatus.CONFIRMATION_REQUIRED -> {
        toolResult.confirmationPrompt ?: when (intent) {
          is DvexIntent.CallContact -> when (tone) {
            EstimatedTone.URGENT -> "Confirm call to ${intent.recipient}, Sir?"
            else -> "Should I call ${intent.recipient}, Sir?"
          }
          is DvexIntent.SendMessage -> if (intent.isWhatsApp) "Should I send this WhatsApp message to ${intent.recipient}, Sir?" else "Should I send this message to ${intent.recipient}, Sir?"
          is DvexIntent.SendEmail -> "Should I send this email to ${intent.recipient}, Sir?"
          else -> "Do you want me to proceed with that action, Sir?"
        }
      }
      DvexToolStatus.UNSUPPORTED -> {
        toolResult.spokenText.ifBlank { "That action is not supported on this device, Sir." }
      }
      DvexToolStatus.FAILED -> {
        when (tone) {
          EstimatedTone.FRUSTRATED -> "I apologize, Sir. I couldn't complete that just now. Let me know if you want me to retry."
          else -> toolResult.spokenText.ifBlank { "I couldn't complete that, Sir." }
        }
      }
      DvexToolStatus.ERROR -> {
        "A system error occurred, Sir. Please try again."
      }
      DvexToolStatus.SUCCESS -> {
        when (intent) {
          is DvexIntent.WakeGreeting -> when (tone) {
            EstimatedTone.URGENT -> "Yes, Sir. Standing by."
            else -> "Yes, Sir. How can I help you?"
          }
          is DvexIntent.CallContact -> when (tone) {
            EstimatedTone.URGENT -> "Calling ${intent.recipient} right away, Sir."
            else -> toolResult.spokenText.ifBlank { "Calling ${intent.recipient} now, Sir." }
          }
          is DvexIntent.SendMessage -> toolResult.spokenText.ifBlank { "Message sent to ${intent.recipient}, Sir." }
          is DvexIntent.SendEmail -> toolResult.spokenText.ifBlank { "Email sent to ${intent.recipient}, Sir." }
          is DvexIntent.OpenApp -> when (tone) {
            EstimatedTone.URGENT -> "Opening ${intent.appName} immediately, Sir."
            else -> toolResult.spokenText.ifBlank { "Sure, Sir. Opening ${intent.appName}." }
          }
          is DvexIntent.GoHome -> "Done, Sir."
          is DvexIntent.GoBack -> "Done, Sir."
          is DvexIntent.OpenRecents -> "Showing recent apps, Sir."
          is DvexIntent.OpenNotifications -> "Opening notifications, Sir."
          is DvexIntent.OpenSettings -> "Opening settings, Sir."
          is DvexIntent.OpenWifiSettings -> "Opening Wi-Fi settings, Sir."
          is DvexIntent.ToggleFlashlight -> toolResult.spokenText.ifBlank { "Flashlight updated, Sir." }
          is DvexIntent.SetAlarm -> toolResult.spokenText.ifBlank { "Alarm set, Sir." }
          is DvexIntent.SetTimer -> toolResult.spokenText.ifBlank { "Timer started, Sir." }
          is DvexIntent.AdjustVolume -> toolResult.spokenText.ifBlank { "Volume adjusted, Sir." }
          is DvexIntent.MediaControl -> toolResult.spokenText.ifBlank { "Media updated, Sir." }
          is DvexIntent.GetTime -> toolResult.spokenText
          is DvexIntent.GetWeather -> toolResult.spokenText
          is DvexIntent.Calculate -> toolResult.spokenText
          is DvexIntent.RememberFact -> "Got it, Sir. I will remember that."
          is DvexIntent.RecallMemory -> toolResult.spokenText
          is DvexIntent.MultiStep -> toolResult.spokenText
          is DvexIntent.GeneralQuestion -> when (tone) {
            EstimatedTone.CONFUSED -> "Here is the explanation, Sir: ${toolResult.spokenText}"
            else -> toolResult.spokenText
          }
          is DvexIntent.Conversation -> when (tone) {
            EstimatedTone.FRUSTRATED -> "Understood, Sir. Let me know how I can make things easier."
            EstimatedTone.CONFUSED -> "No problem, Sir. Let me clarify whenever you need."
            EstimatedTone.HAPPY, EstimatedTone.EXCITED -> "Glad to hear that, Sir! Always at your service."
            EstimatedTone.SAD -> "I'm right here with you, Sir. Take it easy."
            else -> toolResult.spokenText
          }
          is DvexIntent.SearchWeb -> toolResult.spokenText
          else -> toolResult.spokenText.ifBlank { "Got it, Sir." }
        }
      }
    }
  }

  // =========================================================================
  // --- Tanglish Responses ---
  // =========================================================================
  private fun generateTanglishResponse(
    intent: DvexIntent,
    toolResult: DvexToolResult,
    tone: EstimatedTone,
    context: ConversationContext?
  ): String {
    return when (toolResult.status) {
      DvexToolStatus.NOT_FOUND -> {
        when (intent) {
          is DvexIntent.OpenApp -> "${intent.appName} unga phone-la illa, Sir."
          is DvexIntent.CallContact -> "Sir, ${intent.recipient} contact kedaikala."
          is DvexIntent.SendMessage -> "Sir, ${intent.recipient} contact kedaikala."
          is DvexIntent.SendEmail -> "Sir, ${intent.recipient} email kedaikala."
          else -> toolResult.spokenText.ifBlank { "Athu kedaikala, Sir." }
        }
      }
      DvexToolStatus.PERMISSION_REQUIRED -> {
        toolResult.spokenText.ifBlank { "Intha action-ku permission thevai, Sir." }
      }
      DvexToolStatus.CONFIRMATION_REQUIRED -> {
        toolResult.confirmationPrompt ?: when (intent) {
          is DvexIntent.CallContact -> when (tone) {
            EstimatedTone.URGENT -> "Udane ${intent.recipient}-ku call pannattuma, Sir?"
            else -> "${intent.recipient}-ku call pannattuma, Sir?"
          }
          is DvexIntent.SendMessage -> if (intent.isWhatsApp) "${intent.recipient}-ku WhatsApp anuppattuma, Sir?" else "${intent.recipient}-ku message anuppattuma, Sir?"
          is DvexIntent.SendEmail -> "${intent.recipient}-ku email anuppattuma, Sir?"
          else -> "Intha action-ah confirm panlaama, Sir?"
        }
      }
      DvexToolStatus.FAILED -> {
        when (tone) {
          EstimatedTone.FRUSTRATED -> "Mannichidunga Sir, ippo mudiyala. Oru nimisham irunga, solve panren."
          else -> toolResult.spokenText.ifBlank { "Athai seiya mudiyala, Sir." }
        }
      }
      DvexToolStatus.SUCCESS -> {
        when (intent) {
          is DvexIntent.WakeGreeting -> "Yes, Sir. சொல்லுங்க."
          is DvexIntent.CallContact -> when (tone) {
            EstimatedTone.URGENT -> "Ippove ${intent.recipient}-ku call panren, Sir."
            else -> "Sure, Sir. ${intent.recipient}-ku call panren."
          }
          is DvexIntent.SendMessage -> "Sir, ${intent.recipient}-ku message anuppiyachu."
          is DvexIntent.SendEmail -> "Sir, ${intent.recipient}-ku email anuppiyachu."
          is DvexIntent.OpenApp -> when (tone) {
            EstimatedTone.URGENT -> "Udane ${intent.appName} open panren, Sir."
            else -> "Sure, Sir. ${intent.appName} open panren."
          }
          is DvexIntent.GoHome -> "Home-ku poyachu, Sir."
          is DvexIntent.GoBack -> "Pinnaadi vandhachu, Sir."
          is DvexIntent.OpenRecents -> "Recent apps kaatren, Sir."
          is DvexIntent.OpenNotifications -> "Notifications open panren, Sir."
          is DvexIntent.OpenSettings -> "Settings open panren, Sir."
          is DvexIntent.OpenWifiSettings -> "Wi-Fi settings open panren, Sir."
          is DvexIntent.ToggleFlashlight -> {
            val s = toolResult.spokenText.lowercase(Locale.ROOT)
            if (s.contains("off")) "Flashlight off panniyachu, Sir." else "Flashlight on panniyachu, Sir."
          }
          is DvexIntent.SetAlarm -> "Alarm set panniyachu, Sir."
          is DvexIntent.SetTimer -> "Timer set panniyachu, Sir."
          is DvexIntent.AdjustVolume -> "Volume adjust panniyachu, Sir."
          is DvexIntent.MediaControl -> "Media update panniyachu, Sir."
          is DvexIntent.GetTime -> {
            val raw = toolResult.spokenText.replace("The time is ", "").replace(", Sir.", "").trim()
            "Ippo time $raw, Sir."
          }
          is DvexIntent.GetWeather -> {
            "Weather update, Sir: ${toolResult.spokenText}"
          }
          is DvexIntent.Calculate -> "Answer: ${toolResult.spokenText}"
          is DvexIntent.RememberFact -> "Got it, Sir. Ninaivil vaithukkonden."
          is DvexIntent.RecallMemory -> toolResult.spokenText
          is DvexIntent.Conversation -> when (tone) {
            EstimatedTone.FRUSTRATED -> "Kavalapadatheenga Sir, ippove theerthu vaikkiren."
            EstimatedTone.CONFUSED -> "Puriyala-na kavalapadatheenga Sir, thelivaa solren."
            EstimatedTone.HAPPY, EstimatedTone.EXCITED -> "Super Sir! எப்பவும் உங்களுக்காக."
            EstimatedTone.SAD -> "Unga kooda naan irukken, Sir. Relax pannunga."
            else -> toolResult.spokenText
          }
          is DvexIntent.GeneralQuestion -> toolResult.spokenText
          is DvexIntent.MultiStep -> toolResult.spokenText
          else -> toolResult.spokenText.ifBlank { "Sure, Sir. Ippove panren." }
        }
      }
      else -> toolResult.spokenText.ifBlank { "Done, Sir." }
    }
  }

  // =========================================================================
  // --- Tamil Responses ---
  // =========================================================================
  private fun generateTamilResponse(
    intent: DvexIntent,
    toolResult: DvexToolResult,
    tone: EstimatedTone,
    context: ConversationContext?
  ): String {
    return when (toolResult.status) {
      DvexToolStatus.NOT_FOUND -> {
        when (intent) {
          is DvexIntent.OpenApp -> "${intent.appName} உங்கள் போனில் இல்லை, Sir."
          is DvexIntent.CallContact -> "Sir, ${intent.recipient} தொடர்பு விவரம் கிடைக்கவில்லை."
          is DvexIntent.SendMessage -> "Sir, ${intent.recipient} தொடர்பு விவரம் கிடைக்கவில்லை."
          is DvexIntent.SendEmail -> "Sir, ${intent.recipient} மின்னஞ்சல் முகவரி கிடைக்கவில்லை."
          else -> toolResult.spokenText.ifBlank { "அது கிடைக்கவில்லை, Sir." }
        }
      }
      DvexToolStatus.PERMISSION_REQUIRED -> {
        toolResult.spokenText.ifBlank { "இதற்கு அனுமதி தேவை, Sir." }
      }
      DvexToolStatus.CONFIRMATION_REQUIRED -> {
        toolResult.confirmationPrompt ?: when (intent) {
          is DvexIntent.CallContact -> when (tone) {
            EstimatedTone.URGENT -> "உடனே ${intent.recipient}-க்கு கால் செய்யட்டுமா, Sir?"
            else -> "${intent.recipient}-க்கு கால் செய்யட்டுமா, Sir?"
          }
          is DvexIntent.SendMessage -> if (intent.isWhatsApp) "${intent.recipient}-க்கு WhatsApp செய்தி அனுப்பட்டுமா, Sir?" else "${intent.recipient}-க்கு செய்தி அனுப்பட்டுமா, Sir?"
          is DvexIntent.SendEmail -> "${intent.recipient}-க்கு மின்னஞ்சல் அனுப்பட்டுமா, Sir?"
          else -> "இதை உறுதிப்படுத்தவா, Sir?"
        }
      }
      DvexToolStatus.FAILED -> {
        when (tone) {
          EstimatedTone.FRUSTRATED -> "மன்னிக்கவும் Sir, இதை முடிக்க முடியவில்லை. உடனே சரி செய்கிறேன்."
          else -> toolResult.spokenText.ifBlank { "இதை முடிக்க முடியவில்லை, Sir." }
        }
      }
      DvexToolStatus.SUCCESS -> {
        when (intent) {
          is DvexIntent.WakeGreeting -> "வணக்கம், Sir. சொல்லுங்க."
          is DvexIntent.CallContact -> when (tone) {
            EstimatedTone.URGENT -> "உடனே ${intent.recipient}-க்கு கால் செய்கிறேன், Sir."
            else -> "Sure, Sir. ${intent.recipient}-க்கு கால் செய்கிறேன்."
          }
          is DvexIntent.SendMessage -> "Sir, ${intent.recipient}-க்கு செய்தி அனுப்பியாச்சு."
          is DvexIntent.SendEmail -> "Sir, ${intent.recipient}-க்கு மின்னஞ்சல் அனுப்பியாச்சு."
          is DvexIntent.OpenApp -> when (tone) {
            EstimatedTone.URGENT -> "உடனே ${intent.appName} திறக்கிறேன், Sir."
            else -> "Sure, Sir. ${intent.appName} திறக்கிறேன்."
          }
          is DvexIntent.GoHome -> "Home-க்கு போயாச்சு, Sir."
          is DvexIntent.GoBack -> "பின் சென்றாச்சு, Sir."
          is DvexIntent.OpenRecents -> "சமீபத்திய ஆப்ஸ்கள், Sir."
          is DvexIntent.OpenNotifications -> "அறிவிப்புகள் திறக்கிறேன், Sir."
          is DvexIntent.OpenSettings -> "Settings திறக்கிறேன், Sir."
          is DvexIntent.OpenWifiSettings -> "Wi-Fi settings திறக்கிறேன், Sir."
          is DvexIntent.ToggleFlashlight -> {
            val s = toolResult.spokenText.lowercase(Locale.ROOT)
            if (s.contains("off")) "டார்ச் ஆஃப் செய்யப்பட்டது, Sir." else "டார்ச் ஆன் செய்யப்பட்டது, Sir."
          }
          is DvexIntent.SetAlarm -> "அலாரம் செட் பண்ணியாச்சு, Sir."
          is DvexIntent.SetTimer -> "டைமர் செட் பண்ணியாச்சு, Sir."
          is DvexIntent.AdjustVolume -> "வால்யூம் மாற்றப்பட்டது, Sir."
          is DvexIntent.MediaControl -> "மீடியா இயக்கப்பட்டது, Sir."
          is DvexIntent.GetTime -> {
            val raw = toolResult.spokenText.replace("The time is ", "").replace(", Sir.", "").trim()
            "இப்போ நேரம் $raw, Sir."
          }
          is DvexIntent.GetWeather -> {
            "வானிலை விவரம், Sir: ${toolResult.spokenText}"
          }
          is DvexIntent.Calculate -> "விடை: ${toolResult.spokenText}"
          is DvexIntent.RememberFact -> "நினைவில் வைத்துக்கொண்டேன், Sir."
          is DvexIntent.RecallMemory -> toolResult.spokenText
          is DvexIntent.Conversation -> when (tone) {
            EstimatedTone.FRUSTRATED -> "கவலைப்படாதீங்க Sir, உடனே சரி செய்கிறேன்."
            EstimatedTone.CONFUSED -> "விளக்குகிறேன், Sir. கவலைப்படாதீங்க."
            EstimatedTone.HAPPY, EstimatedTone.EXCITED -> "மிக்க மகிழ்ச்சி, Sir! எப்போதும் உங்கள் சேவையில்."
            EstimatedTone.SAD -> "உங்களுடன் நான் இருக்கிறேன், Sir. அமைதியாக இருங்கள்."
            else -> toolResult.spokenText
          }
          is DvexIntent.GeneralQuestion -> toolResult.spokenText
          is DvexIntent.MultiStep -> toolResult.spokenText
          else -> toolResult.spokenText.ifBlank { "Sure, Sir. இப்பவே பண்றேன்." }
        }
      }
      else -> toolResult.spokenText.ifBlank { "முடிந்தது, Sir." }
    }
  }

  companion object {
    private const val TAG = "[D-VEX][RESPONSE]"
  }
}

package com.example.brain

import android.util.Log
import java.util.Locale

/**
 * Natural conversational response layer for D-VEX Smart Brain.
 * Formulates calm, smart, concise, confident, friendly, and respectful responses
 * inspired by a cinematic AI assistant. Naturally addresses the user as "Sir".
 *
 * Requirements:
 * 1. LANGUAGE MATCHING:
 *    Tamil input -> Tamil/Tanglish response.
 *    English input -> English response.
 *    Mixed -> natural Tanglish.
 * 2. UNDERSTAND MEANING, NOT EXACT WORDS:
 *    Natural phrasing over rigid command strings.
 * 3. CONVERSATION CONTEXT:
 *    Maintains continuity across dialogue turns.
 * 4. NATURAL PERSONALITY:
 *    Calm, smart, concise, confident, friendly, respectful.
 *    Never uses robotic phrases like "I have analyzed your query" or "Your request has been processed".
 *    Prefers natural responses like "Got it, Sir.", "Sure, Sir.", "Okay, Sir. சொல்லுங்க.",
 *    "Understood, Sir. இதை பண்ணுறேன்."
 * 5. TONE / EMOTION ESTIMATION:
 *    NEUTRAL, HAPPY, SAD, FRUSTRATED, URGENT, CONFUSED, EXCITED.
 *    Does not claim certainty about emotions, uses estimate to modulate appropriateness.
 */
class DvexResponseGenerator {

  /**
   * Estimates conversational tone from the user's words and conversational cues.
   * Modulates response style without claiming certainty about user's feelings.
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
        text.contains("vegama") || text.contains("fast") || text.contains("speed") ||
        text.contains("உடனே") || text.contains("சீக்கிரம்") || text.contains("சீக்கிரமா") ||
        text.contains("அவசரம்")
    ) {
      return EstimatedTone.URGENT
    }

    // Frustrated cues
    if (text.contains("annoying") || text.contains("stupid") || text.contains("worst") ||
        text.contains("waste") || text.contains("hate") || text.contains("angry") ||
        text.contains("not working") || text.contains("useless") || text.contains("shut up") ||
        text.contains("kaduppa") || text.contains("kaduppu") || text.contains("kadupethatha") ||
        text.contains("erichal") || text.contains("erichala") || text.contains("worstu") ||
        text.contains("wasteu") || text.contains("vela seiyala") || text.contains("vela pakkala") ||
        text.contains("ennada idhu") || text.contains("thirumba thirumba") || text.contains("frustrated") ||
        text.contains("frustrating") || text.contains("irritat") || text.contains("tension") ||
        text.contains("கடுப்பு") || text.contains("எரிச்சல்") || text.contains("வேலை செய்யவில்லை") ||
        text.contains("வெறுப்பு")
    ) {
      return EstimatedTone.FRUSTRATED
    }

    // Confused cues
    if (text.contains("what do you mean") || text.contains("confused") || text.contains("confusing") ||
        text.contains("don't understand") || text.contains("dont understand") || text.contains("how come") ||
        text.contains("puriyala") || text.contains("purila") || text.contains("puriyave illa") ||
        text.contains("enna solra") || text.contains("enna soldra") || text.contains("enna aachu") ||
        text.contains("theriyala") || text.contains("not clear") || text.contains("புரியவில்லை") ||
        text.contains("என்ன சொல்றீங்க") || text.contains("என்ன சொல்ற") || text.contains("விளங்கவில்லை")
    ) {
      return EstimatedTone.CONFUSED
    }

    // Excited cues
    if (text.contains("wow") || text.contains("amazing") || text.contains("let's go") ||
        text.contains("unbelievable") || text.contains("vera level") || text.contains("mass kaatita") ||
        text.contains("massu") || text.contains("mass") || text.contains("excited") ||
        text.contains("awesome") || text.contains("fire") || text.contains("superb") ||
        text.contains("brilliant") || text.contains("marana mass") || text.contains("sema mass") ||
        text.contains("வேற லெவல்") || text.contains("வேற மாறி")
    ) {
      return EstimatedTone.EXCITED
    }

    // Happy / Grateful cues
    if (text.contains("super") || text.contains("great") || text.contains("wonderful") ||
        text.contains("happy") || text.contains("glad") || text.contains("thank you so much") ||
        text.contains("love it") || text.contains("semma") || text.contains("kalakkita") ||
        text.contains("arputham") || text.contains("good job") || text.contains("well done") ||
        text.contains("romba nandri") || text.contains("மகிழ்ச்சி") || text.contains("சூப்பர்") ||
        text.contains("அற்புதம்") || text.contains("நன்றி")
    ) {
      return EstimatedTone.HAPPY
    }

    // Sad / Low cues
    if (text.contains("sad") || text.contains("depressed") || text.contains("feeling down") ||
        text.contains("feeling low") || text.contains("bad day") || text.contains("unhappy") ||
        text.contains("upset") || text.contains("heartbroken") || text.contains("crying") ||
        text.contains("sogam") || text.contains("sogama") || text.contains("kashtama") ||
        text.contains("vali") || text.contains("vali thaangala") || text.contains("kavalaya") ||
        text.contains("சோகம்") || text.contains("கஷ்டமா இருக்கு") || text.contains("மனசு சரியில்லை") ||
        text.contains("வருத்தம்") || text.contains("கவலை")
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
    val lowerInput = userInput.lowercase(Locale.ROOT).trim()

    // 1. Context Continuity: Repeat requests
    if (context != null && (lowerInput == "repeat that" || lowerInput == "what did you say" ||
            lowerInput == "enna sonna" || lowerInput == "திரும்ப சொல்லு" || lowerInput == "repeat")
    ) {
      val lastSaid = context.lastSpokenResponse
      if (!lastSaid.isNullOrBlank()) {
        return when (language) {
          DetectedLanguage.TAMIL -> "கடைசியாக நான் சொன்னது, Sir: $lastSaid"
          DetectedLanguage.TANGLISH -> "Kadasila sonnathu, Sir: $lastSaid"
          DetectedLanguage.ENGLISH -> "I said, Sir: $lastSaid"
        }
      }
    }

    // 2. Context Continuity: Short conversational acknowledgments after an action
    if (context?.lastIntent != null && intent is DvexIntent.Conversation &&
        (lowerInput == "ok" || lowerInput == "okay" || lowerInput == "seri" || lowerInput == "sari" ||
         lowerInput == "super" || lowerInput == "nice" || lowerInput == "thanks" || lowerInput == "nandri" ||
         lowerInput == "got it")
    ) {
      return when (language) {
        DetectedLanguage.TAMIL -> "எப்போதும் உங்கள் சேவையில், Sir."
        DetectedLanguage.TANGLISH -> "எப்பவும் உங்களுக்காக, Sir. Standing by."
        DetectedLanguage.ENGLISH -> "Always at your service, Sir."
      }
    }

    // 3. Language Synthesis
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
          EstimatedTone.FRUSTRATED -> "Understood, Sir. Let's take it step by step and resolve this."
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
            EstimatedTone.FRUSTRATED -> "Understood, Sir. Let's take it step by step and resolve this."
            EstimatedTone.CONFUSED -> "No worries, Sir. Let me break that down clearly. What can I clarify?"
            EstimatedTone.EXCITED -> "Nice, Sir! 🔥 Let's take it to the next level."
            EstimatedTone.HAPPY -> "Glad to hear that, Sir! Always at your service."
            EstimatedTone.SAD -> "I'm right here with you, Sir. Take your time, what happened?"
            EstimatedTone.URGENT -> "Right away, Sir. What do you need me to do?"
            else -> toolResult.spokenText.ifBlank { "Got it, Sir. Standing by." }
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
            else -> "Okay Sir. ${intent.recipient}-ku call pannattuma?"
          }
          is DvexIntent.SendMessage -> if (intent.isWhatsApp) "Okay Sir. ${intent.recipient}-ku WhatsApp anuppattuma?" else "Okay Sir. ${intent.recipient}-ku message anuppattuma?"
          is DvexIntent.SendEmail -> "Okay Sir. ${intent.recipient}-ku email anuppattuma?"
          else -> "Okay Sir. Intha action-ah confirm panlaama?"
        }
      }
      DvexToolStatus.FAILED -> {
        when (tone) {
          EstimatedTone.FRUSTRATED -> "Okay, Sir. புரியுது. இதை step-by-step சரி பண்ணலாம்."
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
          is DvexIntent.SendMessage -> "Done, Sir. ${intent.recipient}-ku message anuppiyachu."
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
            EstimatedTone.FRUSTRATED -> "Okay, Sir. புரியுது. இதை step-by-step சரி பண்ணலாம்."
            EstimatedTone.SAD -> "Hey Sir, புரியுது. நான் இருக்கேன். என்ன நடந்துச்சு சொல்லுங்க."
            EstimatedTone.EXCITED -> "Nice, Sir! 🔥 இதை அடுத்த level-க்கு கொண்டு போகலாம்."
            EstimatedTone.CONFUSED -> "No worries Sir, thelivaa solren. சொல்லுங்க, என்ன சந்தேகம்?"
            EstimatedTone.URGENT -> "Ippove panren, Sir. என்ன பண்ணனும் சொல்லுங்க."
            EstimatedTone.HAPPY -> "Super Sir! எப்பவும் உங்களுக்காக."
            else -> toolResult.spokenText.ifBlank { "Okay, Sir. சொல்லுங்க." }
          }
          is DvexIntent.GeneralQuestion -> toolResult.spokenText
          is DvexIntent.MultiStep -> toolResult.spokenText
          else -> toolResult.spokenText.ifBlank { "Understood, Sir. இதை பண்ணுறேன்." }
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
            else -> "Okay Sir. ${intent.recipient}-க்கு கால் செய்யட்டுமா?"
          }
          is DvexIntent.SendMessage -> if (intent.isWhatsApp) "${intent.recipient}-க்கு WhatsApp செய்தி அனுப்பட்டுமா, Sir?" else "${intent.recipient}-க்கு செய்தி அனுப்பட்டுமா, Sir?"
          is DvexIntent.SendEmail -> "${intent.recipient}-க்கு மின்னஞ்சல் அனுப்பட்டுமா, Sir?"
          else -> "இதை உறுதிப்படுத்தவா, Sir?"
        }
      }
      DvexToolStatus.FAILED -> {
        when (tone) {
          EstimatedTone.FRUSTRATED -> "Okay, Sir. புரியுது. இதை step-by-step சரி பண்ணலாம்."
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
            EstimatedTone.FRUSTRATED -> "Okay, Sir. புரியுது. இதை step-by-step சரி பண்ணலாம்."
            EstimatedTone.SAD -> "Hey Sir, புரியுது. நான் இருக்கேன். என்ன நடந்துச்சு சொல்லுங்க."
            EstimatedTone.EXCITED -> "Nice, Sir! 🔥 இதை அடுத்த level-க்கு கொண்டு போகலாம்."
            EstimatedTone.CONFUSED -> "கவலைப்படாதீங்க Sir, தெளிவாக சொல்கிறேன். என்ன சந்தேகம் சொல்லுங்கள்?"
            EstimatedTone.URGENT -> "உடனே பண்றேன், Sir. என்ன செய்ய வேண்டும் சொல்லுங்கள்."
            EstimatedTone.HAPPY -> "மிக்க மகிழ்ச்சி, Sir! எப்போதும் உங்கள் சேவையில்."
            else -> toolResult.spokenText.ifBlank { "சரி, Sir. சொல்லுங்க." }
          }
          is DvexIntent.GeneralQuestion -> toolResult.spokenText
          is DvexIntent.MultiStep -> toolResult.spokenText
          else -> toolResult.spokenText.ifBlank { "Understood, Sir. இதை செய்கிறேன்." }
        }
      }
      else -> toolResult.spokenText.ifBlank { "முடிந்தது, Sir." }
    }
  }

  companion object {
    private const val TAG = "[D-VEX][RESPONSE]"
  }
}

package com.example.brain

import android.util.Log
import java.util.Locale

/**
 * D-VEX Natural Personality Response Engine
 *
 * Goal:
 * - Natural human-like conversation
 * - Lovely bestie personality
 * - Smart + calm + confident
 * - Tamil / Tanglish / English matching
 * - Emotion-aware responses
 * - Short replies for short inputs
 * - Better continuity with ConversationContext
 * - Avoid repetitive robotic responses
 * - Never pretend to know something it doesn't know
 *
 * Personality:
 *   Calm        -> never panics
 *   Caring      -> notices emotional cues
 *   Smart       -> understands meaning instead of exact phrases
 *   Friendly    -> feels like a close bestie
 *   Respectful  -> keeps "Sir" naturally, not every sentence
 *   Cinematic   -> subtle JARVIS-style confidence
 */
class DvexResponseGenerator {

    // ---------------------------------------------------------------------
    // Tone estimation
    // ---------------------------------------------------------------------

    fun estimateTone(userInput: String): EstimatedTone {

        val text = normalize(userInput)

        if (text.isBlank()) {
            return EstimatedTone.NEUTRAL
        }

        // IMPORTANT:
        // Check urgent/frustrated/confused before happy/excited because
        // some users mix words such as "super urgent" or "mass but not working".

        if (containsAny(
                text,
                "urgent",
                "urgently",
                "emergency",
                "immediately",
                "right now",
                "asap",
                "hurry",
                "quick",
                "quickly",
                "fast",
                "seekiram",
                "seekirama",
                "udane",
                "ippove",
                "avacharam",
                "avasaram",
                "vegam",
                "vegama",
                "உடனே",
                "சீக்கிரம்",
                "சீக்கிரமா",
                "அவசரம்"
            )
        ) {
            return EstimatedTone.URGENT
        }

        if (containsAny(
                text,
                "angry",
                "annoying",
                "annoyed",
                "stupid",
                "worst",
                "waste",
                "hate",
                "not working",
                "doesn't work",
                "doesnt work",
                "useless",
                "shut up",
                "frustrated",
                "frustrating",
                "irritating",
                "irritated",
                "tension",
                "kaduppu",
                "kaduppa",
                "kadupethatha",
                "erichal",
                "erichala",
                "worstu",
                "wasteu",
                "vela seiyala",
                "vela pakkala",
                "ennada idhu",
                "thirumba thirumba",
                "pogala",
                "pogave mattenguthu",
                "mattenguthu",
                "சரியா போகல",
                "மாட்டேங்குது",
                "கடுப்பு",
                "எரிச்சல்"
            )
        ) {
            return EstimatedTone.FRUSTRATED
        }

        if (containsAny(
                text,
                "what do you mean",
                "confused",
                "confusing",
                "don't understand",
                "dont understand",
                "how come",
                "not clear",
                "i don't get it",
                "i dont get it",
                "puriyala",
                "purila",
                "puriyave illa",
                "enna solra",
                "enna soldra",
                "enna aachu",
                "theriyala",
                "புரியவில்லை",
                "புரியல",
                "என்ன சொல்ற",
                "விளங்கவில்லை"
            )
        ) {
            return EstimatedTone.CONFUSED
        }

        if (containsAny(
                text,
                "sad",
                "feeling down",
                "feeling low",
                "bad day",
                "unhappy",
                "upset",
                "heartbroken",
                "crying",
                "alone",
                "lonely",
                "sogam",
                "sogama",
                "kashtama",
                "kashtam",
                "vali thaangala",
                "kavalaya",
                "kavalai",
                "manasu sari illa",
                "manasu sariyilla",
                "சோகம்",
                "கஷ்டமா இருக்கு",
                "மனசு சரியில்லை",
                "வருத்தம்",
                "கவலை",
                "தனியா"
            )
        ) {
            return EstimatedTone.SAD
        }

        if (containsAny(
                text,
                "wow",
                "amazing",
                "lets go",
                "let's go",
                "unbelievable",
                "vera level",
                "mass kaatita",
                "massu",
                "mass",
                "excited",
                "awesome",
                "fire",
                "superb",
                "brilliant",
                "marana mass",
                "sema mass",
                "வேற லெவல்",
                "வேற மாறி"
            )
        ) {
            return EstimatedTone.EXCITED
        }

        if (containsAny(
                text,
                "super",
                "great",
                "wonderful",
                "happy",
                "glad",
                "thank you",
                "thanks",
                "love it",
                "semma",
                "kalakkita",
                "arputham",
                "good job",
                "well done",
                "romba nandri",
                "மகிழ்ச்சி",
                "சூப்பர்",
                "அற்புதம்",
                "நன்றி"
            )
        ) {
            return EstimatedTone.HAPPY
        }

        return EstimatedTone.NEUTRAL
    }

    // ---------------------------------------------------------------------
    // Main response generator
    // ---------------------------------------------------------------------

    fun generateResponse(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        language: DetectedLanguage,
        userInput: String = "",
        context: ConversationContext? = null,
        tone: EstimatedTone = estimateTone(userInput)
    ): String {

        val normalizedInput = normalize(userInput)

        // ---------------------------------------------------------------
        // Repeat request
        // ---------------------------------------------------------------

        if (isRepeatRequest(normalizedInput)) {

            val previous = context?.lastSpokenResponse

            if (!previous.isNullOrBlank()) {

                return when (language) {

                    DetectedLanguage.TAMIL ->
                        "கடைசியாக நான் சொன்னது: $previous"

                    DetectedLanguage.TANGLISH ->
                        "Naan last-aa sonnathu: $previous"

                    DetectedLanguage.ENGLISH ->
                        "I said: $previous"
                }
            }
        }

        // ---------------------------------------------------------------
        // Low-confidence speech
        // ---------------------------------------------------------------

        if (intent is DvexIntent.LowConfidence) {

            val clarification =
                generateClarification(
                    language = language,
                    candidateIntent = intent.candidateIntent
                )

            Log.i(
                TAG,
                "Clarification response: $clarification"
            )

            return clarification
        }

        // ---------------------------------------------------------------
        // Context-aware acknowledgement
        // ---------------------------------------------------------------

        if (
            intent is DvexIntent.Conversation &&
            isSimpleAcknowledgement(normalizedInput)
        ) {

            return generateAcknowledgement(
                language,
                tone
            )
        }

        // ---------------------------------------------------------------
        // Generate response
        // ---------------------------------------------------------------

        val response =
            when (language) {

                DetectedLanguage.ENGLISH ->
                    generateEnglishResponse(
                        intent,
                        toolResult,
                        tone,
                        context,
                        normalizedInput
                    )

                DetectedLanguage.TANGLISH ->
                    generateTanglishResponse(
                        intent,
                        toolResult,
                        tone,
                        context,
                        normalizedInput
                    )

                DetectedLanguage.TAMIL ->
                    generateTamilResponse(
                        intent,
                        toolResult,
                        tone,
                        context,
                        normalizedInput
                    )
            }

        Log.i(
            TAG,
            "Generated response | language=$language | tone=$tone | response=$response"
        )

        return response
    }

    // =====================================================================
    // ENGLISH
    // =====================================================================

    private fun generateEnglishResponse(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        tone: EstimatedTone,
        context: ConversationContext?,
        userInput: String
    ): String {

        return when (toolResult.status) {

            // -------------------------------------------------------------
            // NOT FOUND
            // -------------------------------------------------------------

            DvexToolStatus.NOT_FOUND -> {

                if (toolResult.spokenText.isNotBlank()) {

                    toolResult.spokenText

                } else {

                    when (intent) {

                        is DvexIntent.OpenApp ->
                            "I couldn't find ${intent.appName} on your phone."

                        is DvexIntent.CallContact ->
                            "I couldn't find ${intent.recipient} in your contacts."

                        is DvexIntent.SendMessage ->
                            "I couldn't find ${intent.recipient} in your contacts."

                        is DvexIntent.SendEmail ->
                            "I couldn't find an email address for ${intent.recipient}."

                        else ->
                            "I couldn't find that."
                    }
                }
            }

            // -------------------------------------------------------------
            // PERMISSION
            // -------------------------------------------------------------

            DvexToolStatus.PERMISSION_REQUIRED -> {

                toolResult.spokenText.ifBlank {
                    "I need permission for that, Sir."
                }
            }

            // -------------------------------------------------------------
            // CONFIRMATION
            // -------------------------------------------------------------

            DvexToolStatus.CONFIRMATION_REQUIRED -> {

                toolResult.confirmationPrompt
                    ?: when (intent) {

                        is DvexIntent.CallContact -> {

                            if (tone == EstimatedTone.URGENT) {
                                "I can call ${intent.recipient} right now. Confirm?"
                            } else {
                                "Should I call ${intent.recipient}?"
                            }
                        }

                        is DvexIntent.SendMessage -> {

                            if (intent.isWhatsApp) {
                                "Should I send this WhatsApp message to ${intent.recipient}?"
                            } else {
                                "Should I send this message to ${intent.recipient}?"
                            }
                        }

                        is DvexIntent.SendEmail ->
                            "Should I send this email to ${intent.recipient}?"

                        else ->
                            "Want me to go ahead with that?"
                    }
            }

            // -------------------------------------------------------------
            // UNSUPPORTED
            // -------------------------------------------------------------

            DvexToolStatus.UNSUPPORTED -> {

                toolResult.spokenText.ifBlank {
                    "I can't do that on this device yet."
                }
            }

            // -------------------------------------------------------------
            // FAILED
            // -------------------------------------------------------------

            DvexToolStatus.FAILED -> {

                if (tone == EstimatedTone.FRUSTRATED) {

                    "Yeah, I know that's annoying. Let's fix it together, one step at a time."

                } else {

                    toolResult.spokenText.ifBlank {
                        "That didn't work. Let's try another way."
                    }
                }
            }

            // -------------------------------------------------------------
            // ERROR
            // -------------------------------------------------------------

            DvexToolStatus.ERROR -> {

                "Something went wrong on my side. Give me another try."
            }

            // -------------------------------------------------------------
            // SUCCESS
            // -------------------------------------------------------------

            DvexToolStatus.SUCCESS -> {

                when (intent) {

                    // -----------------------------------------------------
                    // Greeting
                    // -----------------------------------------------------

                    is DvexIntent.WakeGreeting -> {

                        when (tone) {

                            EstimatedTone.URGENT ->
                                "I'm here. Tell me."

                            else ->
                                "Hey, I'm here. What's up?"
                        }
                    }

                    // -----------------------------------------------------
                    // Calls
                    // -----------------------------------------------------

                    is DvexIntent.CallContact -> {

                        if (tone == EstimatedTone.URGENT) {

                            "Calling ${intent.recipient} now."

                        } else {

                            toolResult.spokenText.ifBlank {
                                "Sure. Calling ${intent.recipient}."
                            }
                        }
                    }

                    // -----------------------------------------------------
                    // Messages
                    // -----------------------------------------------------

                    is DvexIntent.SendMessage -> {

                        toolResult.spokenText.ifBlank {
                            "Done. Message sent to ${intent.recipient}."
                        }
                    }

                    // -----------------------------------------------------
                    // Email
                    // -----------------------------------------------------

                    is DvexIntent.SendEmail -> {

                        toolResult.spokenText.ifBlank {
                            "Done. Email sent to ${intent.recipient}."
                        }
                    }

                    // -----------------------------------------------------
                    // Open app
                    // -----------------------------------------------------

                    is DvexIntent.OpenApp -> {

                        if (tone == EstimatedTone.URGENT) {

                            "Opening ${intent.appName} now."

                        } else {

                            toolResult.spokenText.ifBlank {
                                "Sure. Opening ${intent.appName}."
                            }
                        }
                    }

                    // -----------------------------------------------------
                    // Navigation
                    // -----------------------------------------------------

                    is DvexIntent.GoHome ->
                        "Done."

                    is DvexIntent.GoBack ->
                        "Done. We're back."

                    is DvexIntent.OpenRecents ->
                        "Here are your recent apps."

                    is DvexIntent.OpenNotifications ->
                        "Opening your notifications."

                    is DvexIntent.OpenSettings ->
                        "Opening settings."

                    is DvexIntent.OpenWifiSettings ->
                        "Opening Wi-Fi settings."

                    // -----------------------------------------------------
                    // Device
                    // -----------------------------------------------------

                    is DvexIntent.ToggleFlashlight -> {

                        toolResult.spokenText.ifBlank {
                            "Flashlight updated."
                        }
                    }

                    is DvexIntent.SetAlarm -> {

                        toolResult.spokenText.ifBlank {
                            "Alarm set."
                        }
                    }

                    is DvexIntent.SetTimer -> {

                        toolResult.spokenText.ifBlank {
                            "Timer started."
                        }
                    }

                    is DvexIntent.AdjustVolume -> {

                        toolResult.spokenText.ifBlank {
                            "Volume adjusted."
                        }
                    }

                    is DvexIntent.MediaControl -> {

                        toolResult.spokenText.ifBlank {
                            "Done."
                        }
                    }

                    // -----------------------------------------------------
                    // Information
                    // -----------------------------------------------------

                    is DvexIntent.GetTime ->
                        toolResult.spokenText

                    is DvexIntent.GetWeather ->
                        toolResult.spokenText

                    is DvexIntent.Calculate ->
                        toolResult.spokenText

                    // -----------------------------------------------------
                    // Memory
                    // -----------------------------------------------------

                    is DvexIntent.RememberFact ->
                        "Got it. I'll remember that."

                    is DvexIntent.RecallMemory ->
                        toolResult.spokenText

                    // -----------------------------------------------------
                    // Multi-step
                    // -----------------------------------------------------

                    is DvexIntent.MultiStep ->
                        toolResult.spokenText

                    // -----------------------------------------------------
                    // Web
                    // -----------------------------------------------------

                    is DvexIntent.SearchWeb ->
                        toolResult.spokenText

                    // -----------------------------------------------------
                    // General question
                    // -----------------------------------------------------

                    is DvexIntent.GeneralQuestion -> {

                        if (tone == EstimatedTone.CONFUSED) {

                            "Yeah, let me make that simpler: ${toolResult.spokenText}"

                        } else {

                            toolResult.spokenText
                        }
                    }

                    // -----------------------------------------------------
                    // Conversation
                    // -----------------------------------------------------

                    is DvexIntent.Conversation -> {

                        generateEnglishConversation(
                            toolResult.spokenText,
                            tone,
                            userInput,
                            context
                        )
                    }

                    else -> {

                        toolResult.spokenText.ifBlank {
                            "Got it."
                        }
                    }
                }
            }
        }
    }

    private fun generateEnglishConversation(
        existingText: String,
        tone: EstimatedTone,
        userInput: String,
        context: ConversationContext?
    ): String {

        return when (tone) {

            EstimatedTone.SAD -> {

                when {

                    containsAny(
                        userInput,
                        "alone",
                        "lonely",
                        "nobody",
                        "no one"
                    ) ->
                        "Hey... you're not alone right now. I'm here. Tell me what's going on."

                    else ->
                        "Hey... I get you. You don't have to pretend you're okay with me. Tell me what happened."
                }
            }

            EstimatedTone.FRUSTRATED -> {

                "Yeah, I get why that's frustrating. Don't worry — we'll sort it out together. Tell me what went wrong."
            }

            EstimatedTone.CONFUSED -> {

                "No worries. Let's make it simple. Tell me which part is confusing you."
            }

            EstimatedTone.EXCITED -> {

                "Haha, I like that energy. Let's do it."
            }

            EstimatedTone.HAPPY -> {

                "That's nice to hear. I'm glad you're feeling good."
            }

            EstimatedTone.URGENT -> {

                "I'm with you. Tell me what you need right now."
            }

            EstimatedTone.NEUTRAL -> {

                if (isHelpSeeking(userInput)) {

                    "Of course. Tell me what's going on."

                } else {

                    existingText.ifBlank {
                        "I'm here. Tell me."
                    }
                }
            }
        }
    }

    // =====================================================================
    // TANGLISH
    // =====================================================================

    private fun generateTanglishResponse(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        tone: EstimatedTone,
        context: ConversationContext?,
        userInput: String
    ): String {

        return when (toolResult.status) {

            DvexToolStatus.NOT_FOUND -> {

                when (intent) {

                    is DvexIntent.OpenApp ->
                        "${intent.appName} unga phone-la illa."

                    is DvexIntent.CallContact ->
                        "${intent.recipient} contact kedaikala."

                    is DvexIntent.SendMessage ->
                        "${intent.recipient} contact kedaikala."

                    is DvexIntent.SendEmail ->
                        "${intent.recipient}-ku email address kedaikala."

                    else ->
                        toolResult.spokenText.ifBlank {
                            "Athu kedaikala."
                        }
                }
            }

            DvexToolStatus.PERMISSION_REQUIRED -> {

                toolResult.spokenText.ifBlank {
                    "Intha action-ku permission venum."
                }
            }

            DvexToolStatus.CONFIRMATION_REQUIRED -> {

                toolResult.confirmationPrompt
                    ?: when (intent) {

                        is DvexIntent.CallContact -> {

                            if (tone == EstimatedTone.URGENT) {
                                "${intent.recipient}-ku ippove call pannattuma?"
                            } else {
                                "${intent.recipient}-ku call pannattuma?"
                            }
                        }

                        is DvexIntent.SendMessage -> {

                            if (intent.isWhatsApp) {
                                "${intent.recipient}-ku WhatsApp message anuppattuma?"
                            } else {
                                "${intent.recipient}-ku message anuppattuma?"
                            }
                        }

                        is DvexIntent.SendEmail ->
                            "${intent.recipient}-ku email anuppattuma?"

                        else ->
                            "Okay. Idha proceed pannattuma?"
                    }
            }

            DvexToolStatus.UNSUPPORTED -> {

                toolResult.spokenText.ifBlank {
                    "Intha action indha phone-la support aagala."
                }
            }

            DvexToolStatus.FAILED -> {

                if (tone == EstimatedTone.FRUSTRATED) {

                    "Aama, konjam kadupa irukkum. Puriyudhu. Namma rendu perum serndhu step-by-step fix pannalaam."

                } else {

                    toolResult.spokenText.ifBlank {
                        "Athu work aagala. Vera way try pannalaam."
                    }
                }
            }

            DvexToolStatus.ERROR -> {

                "En side-la konjam problem vandhuduchu. Oru thadava marubadi try pannalaam."
            }

            DvexToolStatus.SUCCESS -> {

                when (intent) {

                    is DvexIntent.WakeGreeting -> {

                        when (tone) {

                            EstimatedTone.URGENT ->
                                "Yes, naan inga irukken. Sollu."

                            else ->
                                "Hey, naan inga irukken. Sollu."
                        }
                    }

                    is DvexIntent.CallContact -> {

                        if (tone == EstimatedTone.URGENT) {

                            "${intent.recipient}-ku ippove call panren."

                        } else {

                            toolResult.spokenText.ifBlank {
                                "Sure. ${intent.recipient}-ku call panren."
                            }
                        }
                    }

                    is DvexIntent.SendMessage -> {

                        toolResult.spokenText.ifBlank {
                            "Done. ${intent.recipient}-ku message anuppiyachu."
                        }
                    }

                    is DvexIntent.SendEmail -> {

                        toolResult.spokenText.ifBlank {
                            "${intent.recipient}-ku email anuppiyachu."
                        }
                    }

                    is DvexIntent.OpenApp -> {

                        if (tone == EstimatedTone.URGENT) {

                            "${intent.appName} ippove open panren."

                        } else {

                            toolResult.spokenText.ifBlank {
                                "Sure. ${intent.appName} open panren."
                            }
                        }
                    }

                    is DvexIntent.GoHome ->
                        "Home-ku poiyachu."

                    is DvexIntent.GoBack ->
                        "Back vandhachu."

                    is DvexIntent.OpenRecents ->
                        "Recent apps kaatren."

                    is DvexIntent.OpenNotifications ->
                        "Notifications open panren."

                    is DvexIntent.OpenSettings ->
                        "Settings open panren."

                    is DvexIntent.OpenWifiSettings ->
                        "Wi-Fi settings open panren."

                    is DvexIntent.ToggleFlashlight -> {

                        val result =
                            toolResult.spokenText.lowercase(Locale.ROOT)

                        if (result.contains("off")) {
                            "Flashlight off panniyachu."
                        } else {
                            "Flashlight on panniyachu."
                        }
                    }

                    is DvexIntent.SetAlarm ->
                        "Alarm set panniyachu."

                    is DvexIntent.SetTimer ->
                        "Timer start panniyachu."

                    is DvexIntent.AdjustVolume ->
                        "Volume adjust panniyachu."

                    is DvexIntent.MediaControl ->
                        "Done."

                    is DvexIntent.GetTime -> {

                        val raw =
                            toolResult.spokenText
                                .replace(
                                    "The time is ",
                                    "",
                                    ignoreCase = true
                                )
                                .replace(
                                    ", Sir.",
                                    "",
                                    ignoreCase = true
                                )
                                .trim()

                        "Ippo time $raw."
                    }

                    is DvexIntent.GetWeather ->
                        "Weather update: ${toolResult.spokenText}"

                    is DvexIntent.Calculate ->
                        toolResult.spokenText

                    is DvexIntent.RememberFact ->
                        "Got it. Ninaivil vachukkiten."

                    is DvexIntent.RecallMemory ->
                        toolResult.spokenText

                    is DvexIntent.MultiStep ->
                        toolResult.spokenText

                    is DvexIntent.SearchWeb ->
                        toolResult.spokenText

                    is DvexIntent.GeneralQuestion -> {

                        toolResult.spokenText
                    }

                    is DvexIntent.Conversation -> {

                        generateTanglishConversation(
                            toolResult.spokenText,
                            tone,
                            userInput,
                            context
                        )
                    }

                    else ->
                        toolResult.spokenText.ifBlank {
                            "Okay. Sollu."
                        }
                }
            }
        }
    }

    private fun generateTanglishConversation(
        existingText: String,
        tone: EstimatedTone,
        userInput: String,
        context: ConversationContext?
    ): String {

        return when (tone) {

            EstimatedTone.SAD -> {

                when {

                    containsAny(
                        userInput,
                        "alone",
                        "lonely",
                        "yaarum illa",
                        "yarum illa"
                    ) ->
                        "Hey... nee thaniya illa. Naan inga irukken. Enna aachu nu sollu."

                    else ->
                        "Hey... puriyudhu. En kitta nee okay-nu nadikka thevai illa. Enna aachu nu sollu."
                }
            }

            EstimatedTone.FRUSTRATED -> {

                "Aama, idhu konjam kadupa irukkum. Puriyudhu. Namma serndhu fix pannalaam. Enna problem nu sollu."
            }

            EstimatedTone.CONFUSED -> {

                "No worries. Namma simple-aa paakalaam. Entha part puriyala nu sollu."
            }

            EstimatedTone.EXCITED -> {

                "Haha, indha energy super. Va, pannalaam."
            }

            EstimatedTone.HAPPY -> {

                "Adha kekka nalla irukku. Nee happy-aa irukkaradhu enakkum nice-aa irukku."
            }

            EstimatedTone.URGENT -> {

                "Naan inga irukken. Ippo enna venum nu sollu."
            }

            EstimatedTone.NEUTRAL -> {

                if (isHelpSeeking(userInput)) {

                    "Of course. Enna help venum nu sollu."

                } else {

                    existingText.ifBlank {
                        "Hmm, sollu. Naan kekkaren."
                    }
                }
            }
        }
    }

    // =====================================================================
    // TAMIL
    // =====================================================================

    private fun generateTamilResponse(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        tone: EstimatedTone,
        context: ConversationContext?,
        userInput: String
    ): String {

        return when (toolResult.status) {

            DvexToolStatus.NOT_FOUND -> {

                when (intent) {

                    is DvexIntent.OpenApp ->
                        "${intent.appName} உங்கள் போனில் இல்லை."

                    is DvexIntent.CallContact ->
                        "${intent.recipient} தொடர்பு விவரம் கிடைக்கவில்லை."

                    is DvexIntent.SendMessage ->
                        "${intent.recipient} தொடர்பு விவரம் கிடைக்கவில்லை."

                    is DvexIntent.SendEmail ->
                        "${intent.recipient}-க்கு மின்னஞ்சல் முகவரி கிடைக்கவில்லை."

                    else ->
                        toolResult.spokenText.ifBlank {
                            "அது கிடைக்கவில்லை."
                        }
                }
            }

            DvexToolStatus.PERMISSION_REQUIRED -> {

                toolResult.spokenText.ifBlank {
                    "இந்த செயலுக்கு அனுமதி தேவை."
                }
            }

            DvexToolStatus.CONFIRMATION_REQUIRED -> {

                toolResult.confirmationPrompt
                    ?: when (intent) {

                        is DvexIntent.CallContact -> {

                            if (tone == EstimatedTone.URGENT) {
                                "${intent.recipient}-க்கு இப்போதே கால் செய்யட்டுமா?"
                            } else {
                                "${intent.recipient}-க்கு கால் செய்யட்டுமா?"
                            }
                        }

                        is DvexIntent.SendMessage -> {

                            if (intent.isWhatsApp) {
                                "${intent.recipient}-க்கு WhatsApp செய்தி அனுப்பட்டுமா?"
                            } else {
                                "${intent.recipient}-க்கு செய்தி அனுப்பட்டுமா?"
                            }
                        }

                        is DvexIntent.SendEmail ->
                            "${intent.recipient}-க்கு மின்னஞ்சல் அனுப்பட்டுமா?"

                        else ->
                            "சரி. இதை தொடரட்டுமா?"
                    }
            }

            DvexToolStatus.UNSUPPORTED -> {

                toolResult.spokenText.ifBlank {
                    "இந்த போனில் இந்த செயல் இன்னும் support ஆகவில்லை."
                }
            }

            DvexToolStatus.FAILED -> {

                if (tone == EstimatedTone.FRUSTRATED) {

                    "புரியுது. இது கொஞ்சம் எரிச்சலா இருக்கும். கவலைப்படாதீங்க — நாம ஒவ்வொரு step-ஆ பார்த்து சரி பண்ணலாம்."

                } else {

                    toolResult.spokenText.ifBlank {
                        "அது வேலை செய்யவில்லை. வேறு வழியில் முயற்சி செய்யலாம்."
                    }
                }
            }

            DvexToolStatus.ERROR -> {

                "என் பக்கத்தில் ஒரு சிறிய பிரச்சனை வந்திருக்கிறது. மீண்டும் முயற்சி செய்யலாம்."
            }

            DvexToolStatus.SUCCESS -> {

                when (intent) {

                    is DvexIntent.WakeGreeting -> {

                        when (tone) {

                            EstimatedTone.URGENT ->
                                "நான் இங்கே இருக்கிறேன். சொல்லுங்கள்."

                            else ->
                                "ஹேய், நான் இங்கே இருக்கிறேன். சொல்லுங்கள்."
                        }
                    }

                    is DvexIntent.CallContact -> {

                        if (tone == EstimatedTone.URGENT) {

                            "${intent.recipient}-க்கு இப்போதே கால் செய்கிறேன்."

                        } else {

                            toolResult.spokenText.ifBlank {
                                "சரி. ${intent.recipient}-க்கு கால் செய்கிறேன்."
                            }
                        }
                    }

                    is DvexIntent.SendMessage -> {

                        toolResult.spokenText.ifBlank {
                            "Done. ${intent.recipient}-க்கு செய்தி அனுப்பியாச்சு."
                        }
                    }

                    is DvexIntent.SendEmail -> {

                        toolResult.spokenText.ifBlank {
                            "${intent.recipient}-க்கு மின்னஞ்சல் அனுப்பியாச்சு."
                        }
                    }

                    is DvexIntent.OpenApp -> {

                        if (tone == EstimatedTone.URGENT) {

                            "${intent.appName} இப்போதே திறக்கிறேன்."

                        } else {

                            toolResult.spokenText.ifBlank {
                                "சரி. ${intent.appName} திறக்கிறேன்."
                            }
                        }
                    }

                    is DvexIntent.GoHome ->
                        "Home-க்கு போயாச்சு."

                    is DvexIntent.GoBack ->
                        "பின்னாடி வந்தாச்சு."

                    is DvexIntent.OpenRecents ->
                        "Recent apps காட்டுகிறேன்."

                    is DvexIntent.OpenNotifications ->
                        "Notifications திறக்கிறேன்."

                    is DvexIntent.OpenSettings ->
                        "Settings திறக்கிறேன்."

                    is DvexIntent.OpenWifiSettings ->
                        "Wi-Fi settings திறக்கிறேன்."

                    is DvexIntent.ToggleFlashlight -> {

                        val result =
                            toolResult.spokenText.lowercase(Locale.ROOT)

                        if (result.contains("off")) {
                            "Flashlight off பண்ணியாச்சு."
                        } else {
                            "Flashlight on பண்ணியாச்சு."
                        }
                    }

                    is DvexIntent.SetAlarm ->
                        "Alarm set பண்ணியாச்சு."

                    is DvexIntent.SetTimer ->
                        "Timer start பண்ணியாச்சு."

                    is DvexIntent.AdjustVolume ->
                        "Volume adjust பண்ணியாச்சு."

                    is DvexIntent.MediaControl ->
                        "Done."

                    is DvexIntent.GetTime ->
                        toolResult.spokenText

                    is DvexIntent.GetWeather ->
                        "வானிலை விவரம்: ${toolResult.spokenText}"

                    is DvexIntent.Calculate ->
                        toolResult.spokenText

                    is DvexIntent.RememberFact ->
                        "நினைவில் வைத்துக்கொண்டேன்."

                    is DvexIntent.RecallMemory ->
                        toolResult.spokenText

                    is DvexIntent.MultiStep ->
                        toolResult.spokenText

                    is DvexIntent.SearchWeb ->
                        toolResult.spokenText

                    is DvexIntent.GeneralQuestion ->
                        toolResult.spokenText

                    is DvexIntent.Conversation -> {

                        generateTamilConversation(
                            toolResult.spokenText,
                            tone,
                            userInput,
                            context
                        )
                    }

                    else ->
                        toolResult.spokenText.ifBlank {
                            "சரி. சொல்லுங்கள்."
                        }
                }
            }
        }
    }

    private fun generateTamilConversation(
        existingText: String,
        tone: EstimatedTone,
        userInput: String,
        context: ConversationContext?
    ): String {

        return when (tone) {

            EstimatedTone.SAD -> {

                when {

                    containsAny(
                        userInput,
                        "தனியா",
                        "தனியாக",
                        "யாரும் இல்லை"
                    ) ->
                        "ஹேய்... நீங்கள் தனியாக இல்லை. நான் இங்கே இருக்கிறேன். என்ன நடந்தது என்று சொல்லுங்கள்."

                    else ->
                        "ஹேய்... புரிகிறது. என்னிடம் நீங்கள் okay-ஆ இருக்கிற மாதிரி நடிக்க வேண்டியதில்லை. என்ன நடந்தது சொல்லுங்கள்."
                }
            }

            EstimatedTone.FRUSTRATED -> {

                "புரிகிறது. இது கொஞ்சம் எரிச்சலாக இருக்கும். கவலைப்படாதீங்க — நாம சேர்ந்து சரி பண்ணலாம். என்ன பிரச்சனை சொல்லுங்கள்."
            }

            EstimatedTone.CONFUSED -> {

                "பரவாயில்லை. அதை ரொம்ப simple-ஆ பார்ப்போம். எந்த part புரியவில்லை என்று சொல்லுங்கள்."
            }

            EstimatedTone.EXCITED -> {

                "ஹா ஹா, அந்த energy நல்லா இருக்கு. வாங்க, பண்ணலாம்."
            }

            EstimatedTone.HAPPY -> {

                "அதை கேட்கவே நல்லா இருக்கு. நீங்கள் happy-ஆ இருக்கிறது எனக்கும் சந்தோஷம்."
            }

            EstimatedTone.URGENT -> {

                "நான் இங்கே இருக்கிறேன். இப்போது என்ன வேண்டும் சொல்லுங்கள்."
            }

            EstimatedTone.NEUTRAL -> {

                if (isHelpSeeking(userInput)) {

                    "கண்டிப்பா. என்ன உதவி வேண்டும் சொல்லுங்கள்."

                } else {

                    existingText.ifBlank {
                        "ம்... சொல்லுங்கள். நான் கேட்கிறேன்."
                    }
                }
            }
        }
    }

    // =====================================================================
    // ACKNOWLEDGEMENT
    // =====================================================================

    private fun generateAcknowledgement(
        language: DetectedLanguage,
        tone: EstimatedTone
    ): String {

        return when (language) {

            DetectedLanguage.ENGLISH -> {

                when (tone) {

                    EstimatedTone.HAPPY ->
                        "Nice. I'm with you."

                    EstimatedTone.EXCITED ->
                        "Let's go."

                    else ->
                        "Yeah. I'm with you."
                }
            }

            DetectedLanguage.TANGLISH -> {

                when (tone) {

                    EstimatedTone.HAPPY ->
                        "Nice. Naan un kooda irukken."

                    EstimatedTone.EXCITED ->
                        "Va, let's go."

                    else ->
                        "Yeah. Naan irukken."
                }
            }

            DetectedLanguage.TAMIL -> {

                when (tone) {

                    EstimatedTone.HAPPY ->
                        "நல்லா இருக்கு. நான் உங்களோடு இருக்கிறேன்."

                    EstimatedTone.EXCITED ->
                        "வாங்க, போகலாம்."

                    else ->
                        "சரி. நான் இருக்கிறேன்."
                }
            }
        }
    }

    // =====================================================================
    // CLARIFICATION
    // =====================================================================

    private fun generateClarification(
        language: DetectedLanguage,
        candidateIntent: DvexIntent?
    ): String {

        val phrase =
            candidateIntent?.let {
                describeIntentForClarification(it)
            }

        return when (language) {

            DetectedLanguage.ENGLISH -> {

                if (phrase != null) {

                    "Sorry, I didn't catch that clearly. Did you mean \"$phrase\"?"

                } else {

                    "Sorry, I didn't catch that clearly. Say it once more?"
                }
            }

            DetectedLanguage.TANGLISH -> {

                if (phrase != null) {

                    "Sorry, konjam clear-aa kekkala. \"$phrase\"-aa sonninga?"

                } else {

                    "Sorry, konjam clear-aa sollu. Marubadi sollu?"
                }
            }

            DetectedLanguage.TAMIL -> {

                if (phrase != null) {

                    "மன்னிக்கவும், தெளிவாக கேட்கவில்லை. \"$phrase\" என்று சொன்னீர்களா?"

                } else {

                    "மன்னிக்கவும், தெளிவாக கேட்கவில்லை. இன்னொரு முறை சொல்லுங்கள்?"
                }
            }
        }
    }

    private fun describeIntentForClarification(
        intent: DvexIntent
    ): String? {

        return when (intent) {

            is DvexIntent.OpenApp ->
                "open ${intent.appName}"

            is DvexIntent.CallContact ->
                "call ${intent.recipient}"

            is DvexIntent.SendMessage ->
                "message ${intent.recipient}"

            is DvexIntent.SendEmail ->
                "email ${intent.recipient}"

            is DvexIntent.SearchWeb ->
                "search for ${intent.query}"

            is DvexIntent.GetWeather ->
                "check the weather"

            is DvexIntent.GetTime ->
                "check the time"

            is DvexIntent.Calculate ->
                "calculate ${intent.expression}"

            is DvexIntent.SetAlarm ->
                "set an alarm"

            is DvexIntent.SetTimer ->
                "set a timer"

            is DvexIntent.ToggleFlashlight ->
                "toggle the flashlight"

            else ->
                null
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun normalize(value: String): String {

        return value
            .lowercase(Locale.ROOT)
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun containsAny(
        text: String,
        vararg values: String
    ): Boolean {

        return values.any {
            text.contains(it.lowercase(Locale.ROOT))
        }
    }

    private fun isRepeatRequest(
        input: String
    ): Boolean {

        return input == "repeat" ||
            input == "repeat that" ||
            input == "say again" ||
            input == "say that again" ||
            input == "what did you say" ||
            input == "enna sonna" ||
            input == "enna sonneenga" ||
            input == "marubadiyum sollu" ||
            input == "marupadiyum sollu" ||
            input == "திரும்ப சொல்லு"
    }

    private fun isSimpleAcknowledgement(
        input: String
    ): Boolean {

        return input == "ok" ||
            input == "okay" ||
            input == "seri" ||
            input == "sari" ||
            input == "super" ||
            input == "nice" ||
            input == "thanks" ||
            input == "thank you" ||
            input == "nandri" ||
            input == "got it" ||
            input == "correct" ||
            input == "right"
    }

    private fun isHelpSeeking(
        input: String
    ): Boolean {

        if (input.length > 60) {
            return false
        }

        return input == "help" ||
            input == "help me" ||
            input.contains("need help") ||
            input.contains("help venum") ||
            input.contains("oru help") ||
            input.contains("udhavi venum") ||
            input.contains("உதவி வேணும்") ||
            input.contains("உதவி வேண்டும்") ||
            input.contains("உதவி செய்யுங்கள்")
    }

    companion object {

        private const val TAG =
            "[D-VEX][RESPONSE]"
    }
}
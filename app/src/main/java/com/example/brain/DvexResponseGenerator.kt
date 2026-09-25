package com.example.brain

import android.util.Log
import java.util.Locale

class DvexResponseGenerator {

    companion object {
        private const val TAG = "DvexResponseGenerator"
    }

    private data class MemoryProfile(
        val preferredName: String? = null,
        val replyStyle: String? = null,
        val personality: String? = null,
        val languagePreference: String? = null
    )

    fun estimateTone(userInput: String): EstimatedTone {
        val text = userInput.lowercase(Locale.getDefault()).trim()

        if (text.isBlank()) return EstimatedTone.NEUTRAL

        val urgentWords = listOf(
            "urgent",
            "emergency",
            "help me now",
            "quick",
            "quickly",
            "immediately",
            "asap",
            "அவசரம்",
            "அவசரமா",
            "உடனே",
            "seekiram",
            "seekkiram"
        )

        val frustratedWords = listOf(
            "annoying",
            "frustrated",
            "frustrating",
            "irritated",
            "angry",
            "hate this",
            "not working",
            "why isn't",
            "why isnt",
            "kadupa",
            "kadupu",
            "erichal",
            "erichala",
            "kovam",
            "பிடிக்கல",
            "எரிச்சல்",
            "கோபம்"
        )

        val sadWords = listOf(
            "sad",
            "upset",
            "depressed",
            "lonely",
            "alone",
            "crying",
            "cry",
            "hurt",
            "bad day",
            "miss",
            "feeling low",
            "feel low",
            "sogama",
            "sogam",
            "kastama",
            "kastam",
            "azhuga",
            "அழுகை",
            "சோகம்",
            "கஷ்டம்",
            "தனியா"
        )

        val confusedWords = listOf(
            "confused",
            "don't understand",
            "dont understand",
            "not understand",
            "what do you mean",
            "how",
            "why",
            "puriyala",
            "puriyavae illa",
            "puriyalae",
            "எப்படி",
            "ஏன்",
            "புரியல"
        )

        val excitedWords = listOf(
            "wow",
            "awesome",
            "amazing",
            "excited",
            "yay",
            "super",
            "semma",
            "vera level",
            "mass",
            "செம்ம",
            "அருமை"
        )

        val happyWords = listOf(
            "happy",
            "great",
            "good",
            "nice",
            "love it",
            "thanks",
            "thank you",
            "nandri",
            "sandhosham",
            "சந்தோஷம்",
            "நன்றி"
        )

        return when {
            containsAny(text, urgentWords) -> EstimatedTone.URGENT
            containsAny(text, frustratedWords) -> EstimatedTone.FRUSTRATED
            containsAny(text, sadWords) -> EstimatedTone.SAD
            containsAny(text, confusedWords) -> EstimatedTone.CONFUSED
            containsAny(text, excitedWords) -> EstimatedTone.EXCITED
            containsAny(text, happyWords) -> EstimatedTone.HAPPY
            else -> EstimatedTone.NEUTRAL
        }
    }

    fun generateResponse(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        language: DetectedLanguage,
        userInput: String = "",
        context: ConversationContext? = null,
        tone: EstimatedTone = estimateTone(userInput),
        memoryHint: String = ""
    ): String {

        val memory = parseMemory(memoryHint)

        Log.d(
            TAG,
            "Generating response | language=$language tone=$tone memory=$memory"
        )

        // Repeat request
        if (isRepeatRequest(userInput) && context != null) {
            val previous = context.lastSpokenResponse

            if (!previous.isNullOrBlank()) {
                return personalizeResponse(
                    previous,
                    memory,
                    language,
                    tone,
                    userInput
                )
            }
        }

        val baseResponse = when (language) {

            DetectedLanguage.TAMIL -> {
                generateTamilResponse(
                    intent = intent,
                    toolResult = toolResult,
                    userInput = userInput,
                    tone = tone,
                    context = context
                )
            }

            DetectedLanguage.TANGLISH -> {
                generateTanglishResponse(
                    intent = intent,
                    toolResult = toolResult,
                    userInput = userInput,
                    tone = tone,
                    context = context
                )
            }

            else -> {
                generateEnglishResponse(
                    intent = intent,
                    toolResult = toolResult,
                    userInput = userInput,
                    tone = tone,
                    context = context
                )
            }
        }

        return personalizeResponse(
            response = baseResponse,
            memory = memory,
            language = language,
            tone = tone,
            userInput = userInput
        )
    }

    // ============================================================
    // MEMORY
    // ============================================================

    private fun parseMemory(memoryHint: String): MemoryProfile {

        if (memoryHint.isBlank()) {
            return MemoryProfile()
        }

        return MemoryProfile(
            preferredName = extractMemoryValue(
                memoryHint,
                "User prefers to be called"
            ),
            replyStyle = extractMemoryValue(
                memoryHint,
                "Preferred reply style"
            ),
            personality = extractMemoryValue(
                memoryHint,
                "Preferred assistant personality"
            ),
            languagePreference = extractMemoryValue(
                memoryHint,
                "Preferred language style"
            )
        )
    }

    private fun extractMemoryValue(
        memoryHint: String,
        prefix: String
    ): String? {

        val line = memoryHint
            .lines()
            .firstOrNull {
                it.trim()
                    .startsWith(prefix, ignoreCase = true)
            }
            ?: return null

        val value = line
            .substringAfter(":", "")
            .trim()

        if (value.isNotBlank()) {
            return value
        }

        // Handles:
        // "User prefers to be called Alex."
        val afterPrefix = line
            .substringAfter(prefix, "")
            .trim()
            .trim('.')

        return afterPrefix.ifBlank { null }
    }

    private fun personalizeResponse(
        response: String,
        memory: MemoryProfile,
        language: DetectedLanguage,
        tone: EstimatedTone,
        userInput: String
    ): String {

        if (response.isBlank()) return response

        var result = response.trim()

        /*
         * Do NOT add the user's name to every message.
         * That sounds robotic.
         */
        val shouldUseName =
            memory.preferredName != null &&
                    (
                            tone == EstimatedTone.SAD ||
                                    tone == EstimatedTone.FRUSTRATED ||
                                    tone == EstimatedTone.HAPPY ||
                                    tone == EstimatedTone.EXCITED ||
                                    isGreeting(userInput)
                            )

        if (shouldUseName) {

            val name = memory.preferredName!!.trim()

            if (
                name.isNotBlank() &&
                !containsName(result, name)
            ) {
                result = addNaturalName(
                    result,
                    name,
                    language,
                    tone
                )
            }
        }

        /*
         * Reply style
         */
        val style = memory.replyStyle
            ?.lowercase(Locale.getDefault())
            ?: ""

        if (style.contains("short") ||
            style.contains("concise") ||
            style.contains("brief")
        ) {
            result = makeConcise(result)
        }

        /*
         * Personality
         */
        val personality = memory.personality
            ?.lowercase(Locale.getDefault())
            ?: ""

        if (
            personality.contains("bestie") ||
            personality.contains("friendly") ||
            personality.contains("lovely") ||
            personality.contains("warm")
        ) {
            result = makeWarm(result, language, tone)
        }

        /*
         * Language preference is treated only as a style hint.
         * DetectedLanguage remains the primary source.
         */
        return result.trim()
    }

    private fun addNaturalName(
        response: String,
        name: String,
        language: DetectedLanguage,
        tone: EstimatedTone
    ): String {

        return when (language) {

            DetectedLanguage.TAMIL -> {
                when (tone) {
                    EstimatedTone.SAD ->
                        "$name, $response"

                    EstimatedTone.FRUSTRATED ->
                        "$name, $response"

                    else ->
                        "$name, $response"
                }
            }

            DetectedLanguage.TANGLISH -> {
                "$name, $response"
            }

            else -> {
                "$name, $response"
            }
        }
    }

    private fun makeConcise(response: String): String {

        val cleaned = response
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.length <= 140) {
            return cleaned
        }

        val sentences = cleaned.split(
            Regex("(?<=[.!?])\\s+")
        )

        if (sentences.isNotEmpty()) {
            return sentences
                .take(2)
                .joinToString(" ")
                .trim()
        }

        return cleaned.take(160).trim()
    }

    private fun makeWarm(
        response: String,
        language: DetectedLanguage,
        tone: EstimatedTone
    ): String {

        // Don't artificially add warmth to factual/tool responses.
        if (
            tone == EstimatedTone.URGENT ||
            tone == EstimatedTone.CONFUSED
        ) {
            return response
        }

        if (
            response.contains("❤️") ||
            response.contains("😊") ||
            response.contains("🙂") ||
            response.contains("💙")
        ) {
            return response
        }

        if (
            tone == EstimatedTone.SAD ||
            tone == EstimatedTone.FRUSTRATED
        ) {
            return when (language) {

                DetectedLanguage.TAMIL ->
                    "$response 💙"

                DetectedLanguage.TANGLISH ->
                    "$response 💙"

                else ->
                    "$response 💙"
            }
        }

        return response
    }

    // ============================================================
    // ENGLISH
    // ============================================================

    private fun generateEnglishResponse(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        userInput: String,
        tone: EstimatedTone,
        context: ConversationContext?
    ): String {

        if (intent is DvexIntent.LowConfidence) {
            return generateClarification(userInput)
        }

        if (isSimpleAcknowledgement(userInput)) {
            return when (tone) {
                EstimatedTone.HAPPY,
                EstimatedTone.EXCITED ->
                    "Anytime 😊"

                else ->
                    "Of course."
            }
        }

        return when (tone) {

            EstimatedTone.SAD -> {
                generateSadEnglish(intent)
            }

            EstimatedTone.FRUSTRATED -> {
                generateFrustratedEnglish(intent)
            }

            EstimatedTone.CONFUSED -> {
                generateConfusedEnglish(intent)
            }

            EstimatedTone.EXCITED -> {
                generateExcitedEnglish(intent)
            }

            EstimatedTone.HAPPY -> {
                generateHappyEnglish(intent)
            }

            EstimatedTone.URGENT -> {
                generateUrgentEnglish(
                    intent,
                    toolResult
                )
            }

            else -> {
                generateNeutralEnglish(
                    intent,
                    toolResult,
                    context
                )
            }
        }
    }

    private fun generateNeutralEnglish(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        context: ConversationContext?
    ): String {

        if (toolResult.spokenText.isNotBlank()) {
            return toolResult.spokenText
        }

        return when (intent) {

            is DvexIntent.WakeGreeting ->
                "Hey! I'm here. What are we doing?"

            is DvexIntent.GetTime ->
                "It's ${toolResult.spokenText}."

            is DvexIntent.GetWeather ->
                toolResult.spokenText.ifBlank {
                    "I couldn't get the weather right now."
                }

            is DvexIntent.OpenApp ->
                toolResult.spokenText.ifBlank {
                    "Done."

                }

            is DvexIntent.CloseApp ->
                toolResult.spokenText.ifBlank {
                    "Done."
                }

            is DvexIntent.SearchWeb ->
                toolResult.spokenText.ifBlank {
                    "I've got it."
                }

            is DvexIntent.MediaControl ->
                toolResult.spokenText.ifBlank {
                    "Sure, playing it."
                }

            is DvexIntent.SendMessage ->
                toolResult.spokenText.ifBlank {
                    "Message handled."
                }

            is DvexIntent.CallContact ->
                toolResult.spokenText.ifBlank {
                    "Call handled."
                }

            DvexIntent.GoHome, DvexIntent.GoBack, DvexIntent.OpenRecents,
            DvexIntent.OpenNotifications, DvexIntent.OpenSettings,
            DvexIntent.OpenWifiSettings, DvexIntent.LockScreen ->
                toolResult.spokenText.ifBlank {
                    "Done."
                }

            is DvexIntent.SetAlarm, is DvexIntent.SetTimer ->
                toolResult.spokenText.ifBlank {
                    "Reminder set."
                }

            is DvexIntent.Conversation ->
                "I'm right here."

            else ->
                toolResult.spokenText.ifBlank {
                    "Done."
                }
        }
    }

    private fun generateSadEnglish(
        intent: DvexIntent
    ): String {

        if (intent !is DvexIntent.Conversation) {
            return "I'm with you. Let's handle this one step at a time."
        }

        return listOf(
            "Hey... it's okay. You don't have to handle everything at once. I'm here with you.",
            "I got you. Take a breath first. We'll figure it out together.",
            "It's okay to have a rough moment. You can talk to me about it.",
            "Hey, don't carry everything alone. I'm right here."
        ).random()
    }

    private fun generateFrustratedEnglish(
        intent: DvexIntent
    ): String {

        if (intent !is DvexIntent.Conversation) {
            return "Yeah, I get why that's frustrating. Let's fix it."
        }

        return listOf(
            "Yeah, I get you. That sounds frustrating. Let's sort it out.",
            "I hear you. Don't worry, we'll break it down and fix it.",
            "Okay, okay. Let's not fight the problem. We'll solve it step by step."
        ).random()
    }

    private fun generateConfusedEnglish(
        intent: DvexIntent
    ): String {

        return when (intent) {

            is DvexIntent.Conversation ->
                "No worries. Tell me which part is confusing and I'll explain it simply."

            else ->
                "Got you. I can explain that more simply."
        }
    }

    private fun generateExcitedEnglish(
        intent: DvexIntent
    ): String {

        return when (intent) {

            is DvexIntent.Conversation ->
                "Haha, I like that energy 😄 What happened?"

            else ->
                "Nice! Let's go 😄"
        }
    }

    private fun generateHappyEnglish(
        intent: DvexIntent
    ): String {

        return when (intent) {

            is DvexIntent.Conversation ->
                "That's nice 😊 I'm glad to hear that."

            else ->
                "Nice 😊 Done."
        }
    }

    private fun generateUrgentEnglish(
        intent: DvexIntent,
        toolResult: DvexToolResult
    ): String {

        if (toolResult.spokenText.isNotBlank()) {
            return toolResult.spokenText
        }

        return when (intent) {
            is DvexIntent.Conversation ->
                "I'm here. Tell me what you need right now."

            else ->
                "Got it. Handling it now."
        }
    }

    // ============================================================
    // TANGLISH
    // ============================================================

    private fun generateTanglishResponse(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        userInput: String,
        tone: EstimatedTone,
        context: ConversationContext?
    ): String {

        if (intent is DvexIntent.LowConfidence) {
            return "Konjam clear-ah sollu, naan correct-ah understand panni help panren."
        }

        if (isSimpleAcknowledgement(userInput)) {
            return when (tone) {
                EstimatedTone.HAPPY,
                EstimatedTone.EXCITED ->
                    "Anytime da 😊"

                else ->
                    "Sure da."
            }
        }

        if (toolResult.spokenText.isNotBlank()) {

            val toolText = toolResult.spokenText.trim()

            if (
                intent !is DvexIntent.Conversation &&
                toolText.length > 2
            ) {
                return toolText
            }
        }

        return when (tone) {

            EstimatedTone.SAD -> {
                listOf(
                    "Hey... parava illa da. Ellamey orae time-la solve panna vendam. Naan iruken.",
                    "Sari da... first konjam calm aagu. En kitta pesu, together handle pannalam.",
                    "Nee thaniya carry panna vendam da. Naan inga iruken."
                ).random()
            }

            EstimatedTone.FRUSTRATED -> {
                listOf(
                    "Puriyudhu da, semma frustrating-ah irukkum. Va, step by step fix pannalam.",
                    "Okay da, tension aagadha. Problem-a break panni solve pannalam.",
                    "Aama, kadupa irukkum. Namma idha kandippa sort pannalam."
                ).random()
            }

            EstimatedTone.CONFUSED -> {
                "Parava illa da. Enna part puriyala nu sollu, romba simple-ah explain panren."
            }

            EstimatedTone.EXCITED -> {
                "Haha semma energy da 😄 Sollu, enna matter?"
            }

            EstimatedTone.HAPPY -> {
                "Nice da 😊 Unakku happy-ah irukku nu kekkave nalla irukku."
            }

            EstimatedTone.URGENT -> {
                "Okay da, got it. Ippo enna venum nu straight-ah sollu."
            }

            else -> {
                generateNeutralTanglish(
                    intent,
                    toolResult,
                    context
                )
            }
        }
    }

    private fun generateNeutralTanglish(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        context: ConversationContext?
    ): String {

        if (toolResult.spokenText.isNotBlank()) {
            return toolResult.spokenText
        }

        return when (intent) {

            is DvexIntent.WakeGreeting ->
                "Hey da 😄 Sollu, enna panlam?"

            is DvexIntent.OpenApp ->
                "Sure da, open panren."

            is DvexIntent.CloseApp ->
                "Done da."

            is DvexIntent.SearchWeb ->
                "Sure da, search panren."

            is DvexIntent.MediaControl ->
                "Sure da, play panren."

            is DvexIntent.SendMessage ->
                "Okay da, message handle panren."

            is DvexIntent.CallContact ->
                "Sure da, call handle panren."

            DvexIntent.GoHome, DvexIntent.GoBack, DvexIntent.OpenRecents,
            DvexIntent.OpenNotifications, DvexIntent.OpenSettings,
            DvexIntent.OpenWifiSettings, DvexIntent.LockScreen ->
                "Done da."

            is DvexIntent.SetAlarm, is DvexIntent.SetTimer ->
                "Okay da, reminder set panren."

            is DvexIntent.GetWeather ->
                "Weather check panren da."

            is DvexIntent.GetTime ->
                toolResult.spokenText.ifBlank {
                    "Ippo time check panren da."
                }

            is DvexIntent.Conversation ->
                "Hmm, sollu da. Naan kekkuren."

            else ->
                "Done da."
        }
    }

    // ============================================================
    // TAMIL
    // ============================================================

    private fun generateTamilResponse(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        userInput: String,
        tone: EstimatedTone,
        context: ConversationContext?
    ): String {

        if (intent is DvexIntent.LowConfidence) {
            return "கொஞ்சம் தெளிவாக சொல்லுங்க, சரியாக புரிந்து கொண்டு உதவுறேன்."
        }

        if (isSimpleAcknowledgement(userInput)) {
            return "எப்போதும் இருக்கேன் 😊"
        }

        if (toolResult.spokenText.isNotBlank()) {
            return toolResult.spokenText
        }

        return when (tone) {

            EstimatedTone.SAD -> {
                listOf(
                    "பரவாயில்லை... எல்லாத்தையும் ஒரே நேரத்தில் சமாளிக்க வேண்டியதில்லை. நான் இருக்கேன்.",
                    "கொஞ்சம் அமைதியாக இருங்க. என்ன நடந்தது என்று என்னிடம் சொல்லலாம்.",
                    "நீங்க இதை தனியாக சமாளிக்க வேண்டியதில்லை. நான் உங்களுடன் இருக்கேன்."
                ).random()
            }

            EstimatedTone.FRUSTRATED -> {
                listOf(
                    "புரிகிறது. அது எரிச்சலாகத்தான் இருக்கும். வாங்க, ஒவ்வொன்றாக சரி பண்ணலாம்.",
                    "கவலைப்படாதீங்க. பிரச்சனையை சின்ன சின்ன பகுதிகளாக பிரித்து சரி பண்ணலாம்.",
                    "சரி, முதலில் அமைதியாக இருப்போம். அடுத்து என்ன செய்யலாம் என்று பார்க்கலாம்."
                ).random()
            }

            EstimatedTone.CONFUSED -> {
                "பரவாயில்லை. எந்த பகுதி புரியவில்லை என்று சொல்லுங்க, எளிமையாக விளக்குறேன்."
            }

            EstimatedTone.EXCITED -> {
                "ஹாஹா 😄 அந்த energy நல்லா இருக்கு! என்ன விஷயம்?"
            }

            EstimatedTone.HAPPY -> {
                "அருமை 😊 நீங்கள் சந்தோஷமாக இருக்கிறீங்கன்னு கேட்கவே நல்லா இருக்கு."
            }

            EstimatedTone.URGENT -> {
                "சரி. இப்போ உங்களுக்கு என்ன தேவைன்னு நேராக சொல்லுங்க."
            }

            else -> {
                generateNeutralTamil(
                    intent,
                    toolResult,
                    context
                )
            }
        }
    }

    private fun generateNeutralTamil(
        intent: DvexIntent,
        toolResult: DvexToolResult,
        context: ConversationContext?
    ): String {

        if (toolResult.spokenText.isNotBlank()) {
            return toolResult.spokenText
        }

        return when (intent) {

            is DvexIntent.WakeGreeting ->
                "வணக்கம் 😊 சொல்லுங்க, என்ன செய்யலாம்?"

            is DvexIntent.OpenApp ->
                "சரி, திறக்கிறேன்."

            is DvexIntent.CloseApp ->
                "சரி, முடிந்தது."

            is DvexIntent.SearchWeb ->
                "சரி, தேடுகிறேன்."

            is DvexIntent.MediaControl ->
                "சரி, பாடலை இயக்குகிறேன்."

            is DvexIntent.SendMessage ->
                "சரி, மெசேஜை அனுப்புகிறேன்."

            is DvexIntent.CallContact ->
                "சரி, அழைப்பை செய்கிறேன்."

            DvexIntent.GoHome, DvexIntent.GoBack, DvexIntent.OpenRecents,
            DvexIntent.OpenNotifications, DvexIntent.OpenSettings,
            DvexIntent.OpenWifiSettings, DvexIntent.LockScreen ->
                "சரி, முடிந்தது."

            is DvexIntent.SetAlarm, is DvexIntent.SetTimer ->
                "சரி, நினைவூட்டலை அமைக்கிறேன்."

            is DvexIntent.GetWeather ->
                "வானிலை தகவலை பார்க்கிறேன்."

            is DvexIntent.GetTime ->
                toolResult.spokenText.ifBlank {
                    "நேரத்தை பார்க்கிறேன்."
                }

            is DvexIntent.Conversation ->
                "சொல்லுங்க, நான் கேட்கிறேன்."

            else ->
                "சரி, முடிந்தது."
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private fun isRepeatRequest(input: String): Boolean {

        val text = input
            .lowercase(Locale.getDefault())
            .trim()

        val phrases = listOf(
            "repeat",
            "say again",
            "again",
            "what did you say",
            "once more",
            "thirumba sollu",
            "thirumba sollunga",
            "marubadi sollu",
            "marubadi sollunga",
            "திரும்ப சொல்லு",
            "மறுபடியும் சொல்லு"
        )

        return containsAny(text, phrases)
    }

    private fun isSimpleAcknowledgement(input: String): Boolean {

        val text = input
            .lowercase(Locale.getDefault())
            .trim()

        val phrases = listOf(
            "thanks",
            "thank you",
            "thankyou",
            "ok",
            "okay",
            "cool",
            "nice",
            "great",
            "good",
            "super",
            "nandri",
            "seri",
            "sari",
            "correct",
            "semma",
            "சரி",
            "நன்றி",
            "அருமை"
        )

        return phrases.any {
            text == it || text.contains(it)
        }
    }

    private fun isGreeting(input: String): Boolean {

        val text = input
            .lowercase(Locale.getDefault())
            .trim()

        val greetings = listOf(
            "hi",
            "hello",
            "hey",
            "hey dvex",
            "hi dvex",
            "hello dvex",
            "good morning",
            "good afternoon",
            "good evening",
            "vanakkam",
            "வணக்கம்"
        )

        return greetings.any {
            text == it || text.startsWith("$it ")
        }
    }

    private fun containsName(
        response: String,
        name: String
    ): Boolean {

        return response
            .lowercase(Locale.getDefault())
            .contains(
                name.lowercase(Locale.getDefault())
            )
    }

    private fun containsAny(
        text: String,
        words: List<String>
    ): Boolean {

        return words.any { word ->
            text.contains(word)
        }
    }

    private fun generateClarification(
        userInput: String
    ): String {

        if (userInput.isBlank()) {
            return "Sorry, I didn't catch that. Say it again?"
        }

        return when {
            userInput.length < 5 ->
                "I didn't quite catch that. Can you say a little more?"

            else ->
                "I got part of that, but I'm not fully sure. Can you rephrase it?"
        }
    }

    private fun describeIntentForClarification(
        intent: DvexIntent
    ): String {

        return when (intent) {

            is DvexIntent.OpenApp ->
                "opening an app"

            is DvexIntent.CloseApp ->
                "closing an app"

            is DvexIntent.SearchWeb ->
                "searching the web"

            is DvexIntent.MediaControl ->
                "playing music"

            is DvexIntent.SendMessage ->
                "sending a message"

            is DvexIntent.CallContact ->
                "making a call"

            is DvexIntent.GetWeather ->
                "checking the weather"

            is DvexIntent.SetAlarm, is DvexIntent.SetTimer ->
                "setting a reminder"

            DvexIntent.GoHome, DvexIntent.GoBack, DvexIntent.OpenRecents,
            DvexIntent.OpenNotifications, DvexIntent.OpenSettings,
            DvexIntent.OpenWifiSettings, DvexIntent.LockScreen ->
                "controlling the device"

            else ->
                "doing that"
        }
    }
}
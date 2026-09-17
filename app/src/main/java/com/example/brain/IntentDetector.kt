package com.example.brain

import android.util.Log
import java.util.Locale

/**
 * Robust, natural language intent parser for D-VEX Smart Brain.
 * Parses natural variations in English, Tamil, and Tanglish into strongly-typed DvexIntents.
 */
class IntentDetector {

  data class DetectionResult(
    val intent: DvexIntent,
    val confidence: Float,
    val language: DetectedLanguage,
    val cleanedText: String
  )

  /**
   * Main parsing entry point.
   */
  fun detectIntent(rawInput: String, context: ConversationContext? = null): DetectionResult {
    val language = detectLanguage(rawInput)
    val cleanText = sanitizeWakePhraseAndFillers(rawInput)
    val lower = cleanText.lowercase(Locale.ROOT).trim()

    Log.d(TAG, "Input: \"$rawInput\" -> Cleaned: \"$cleanText\" (Lang: $language)")

    // Check for empty or noise input
    if (lower.isBlank()) {
      return DetectionResult(DvexIntent.Unknown(rawInput), 0.0f, language, cleanText)
    }

    // 0. Contextual follow-up resolution (e.g., "Search for tractor videos" after opening YouTube)
    if (context != null) {
      val contextualIntent = context.resolveContextualFollowUp(cleanText)
      if (contextualIntent != null) {
        return DetectionResult(contextualIntent, 0.95f, language, cleanText)
      }
    }

    // 1. Multi-Step Commands (e.g., "Open YouTube and search for tractor videos")
    val multiStep = parseMultiStepCommand(cleanText, lower)
    if (multiStep != null) {
      return DetectionResult(multiStep, 0.92f, language, cleanText)
    }

    // 2. Sensitive Actions (Call Contact / Send Message)
    val sensitiveAction = parseSensitiveAction(cleanText, lower)
    if (sensitiveAction != null) {
      return DetectionResult(sensitiveAction, 0.95f, language, cleanText)
    }

    // 3. Tactical Memory (Remember Fact / Recall Memory)
    val memoryIntent = parseMemoryCommand(cleanText, lower)
    if (memoryIntent != null) {
      return DetectionResult(memoryIntent, 0.95f, language, cleanText)
    }

    // 4. Live Information & Utility (Weather, Time, Calculator, News, Web Search)
    val liveInfo = parseLiveInformation(cleanText, lower)
    if (liveInfo != null) {
      return DetectionResult(liveInfo, 0.95f, language, cleanText)
    }

    // 5. Phone & System Control (Home, Back, Recents, Notifications, Settings, Volume, Media)
    val systemControl = parseSystemControl(cleanText, lower)
    if (systemControl != null) {
      return DetectionResult(systemControl, 0.98f, language, cleanText)
    }

    // 6. Application Launching / Closing
    val appIntent = parseAppIntent(cleanText, lower)
    if (appIntent != null) {
      return DetectionResult(appIntent, 0.92f, language, cleanText)
    }

    // 7. General Knowledge / Questions & Conversational Queries
    val conversationIntent = parseConversationAndKnowledge(cleanText, lower)
    if (conversationIntent != null) {
      return DetectionResult(conversationIntent, 0.88f, language, cleanText)
    }

    // Fallback: Default to Conversation / General Question
    return DetectionResult(DvexIntent.GeneralQuestion(cleanText), 0.70f, language, cleanText)
  }

  // --- Multi-Step Commands ---
  private fun parseMultiStepCommand(rawText: String, lower: String): DvexIntent? {
    // "Open YouTube and search for tractor videos"
    // "Launch Chrome and search for android news"
    val andSplit = when {
      lower.contains(" and search for ") -> " and search for "
      lower.contains(" and search ") -> " and search "
      lower.contains(" and find ") -> " and find "
      else -> null
    }

    if (andSplit != null) {
      val splitIndex = lower.indexOf(andSplit)
      if (splitIndex >= 0) {
        val firstPart = rawText.substring(0, splitIndex).trim()
        val searchQuery = rawText.substring(splitIndex + andSplit.length).trim()

        val appTarget = extractAppName(firstPart)
        if (appTarget.isNotEmpty() && searchQuery.isNotEmpty()) {
          return DvexIntent.MultiStep(
            first = DvexIntent.OpenApp(appTarget),
            second = DvexIntent.SearchWeb(searchQuery)
          )
        }
      }
    }
    return null
  }

  // --- Sensitive Actions ---
  private fun parseSensitiveAction(rawText: String, lower: String): DvexIntent? {
    // Call commands
    val isCall = lower.startsWith("call ") ||
        lower.startsWith("dial ") ||
        lower.contains("call pannu") ||
        lower.contains("call pannunga") ||
        lower == "call" ||
        lower.startsWith("make a call to ") ||
        lower.startsWith("phone ")

    if (isCall) {
      var recipient = rawText
        .replace(Regex("^(can you please |can you |please )?call( to)?", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?dial", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?make a call to", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?phone", RegexOption.IGNORE_CASE), "")
        .replace("call pannu", "", ignoreCase = true)
        .replace("call pannunga", "", ignoreCase = true)
        .replace("ku call", "", ignoreCase = true)
        .trim()
      while (recipient.endsWith(".") || recipient.endsWith("?") || recipient.endsWith("!")) {
        recipient = recipient.substring(0, recipient.length - 1).trim()
      }
      return DvexIntent.CallContact(recipient.ifBlank { "contact" })
    }

    // Message commands
    val isMessage = lower.startsWith("send message to ") ||
        lower.startsWith("send message ") ||
        lower.startsWith("message ") ||
        lower.startsWith("send a text to ") ||
        lower.startsWith("text ") ||
        lower.contains("message anupu") ||
        lower.contains("sms anupu")

    if (isMessage) {
      var target = rawText
        .replace(Regex("^(can you please |can you |please )?send message to", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?send message", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?message", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?send a text to", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?text", RegexOption.IGNORE_CASE), "")
        .replace("message anupu", "", ignoreCase = true)
        .replace("sms anupu", "", ignoreCase = true)
        .trim()

      var body: String? = null
      if (target.contains(" saying ", ignoreCase = true)) {
        val parts = target.split(Regex(" saying ", RegexOption.IGNORE_CASE), limit = 2)
        target = parts[0].trim()
        body = parts[1].trim()
      }

      val cleanTarget = target.trimEnd('.', '?', '!', ' ')
      val cleanBody = body?.trimEnd('.', '?', '!', ' ')
      return DvexIntent.SendMessage(cleanTarget.ifBlank { "recipient" }, cleanBody)
    }

    return null
  }

  // --- Memory Commands ---
  private fun parseMemoryCommand(rawText: String, lower: String): DvexIntent? {
    // Remember fact
    if (lower.startsWith("remember that ") ||
        lower.startsWith("remember ") ||
        lower.startsWith("please remember that ") ||
        lower.startsWith("can you remember that ") ||
        lower.contains("ninaivill veiy") ||
        lower.contains("ninaivukol")
    ) {
      val fact = rawText
        .replace(Regex("^(can you please |can you |please )?remember that", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?remember", RegexOption.IGNORE_CASE), "")
        .replace("ninaivill veiy", "", ignoreCase = true)
        .replace("ninaivukol", "", ignoreCase = true)
        .trim()
      if (fact.isNotEmpty()) {
        return DvexIntent.RememberFact(fact)
      }
    }

    // Recall memory
    if (lower.contains("what did i tell you to remember") ||
        lower.contains("what do you remember") ||
        lower.contains("what color do i like") ||
        lower.contains("what do i like") ||
        lower.contains("what is my favorite") ||
        lower.contains("what's my favorite") ||
        lower.contains("recall memory") ||
        lower == "memories" ||
        lower.contains("enaku enna pidikkum")
    ) {
      return DvexIntent.RecallMemory(query = lower)
    }

    return null
  }

  // --- Live Information & Utility ---
  private fun parseLiveInformation(rawText: String, lower: String): DvexIntent? {
    // Weather
    if (lower.contains("weather") ||
        lower.contains("temperature") ||
        lower.contains("vaanavilai") ||
        lower.contains("climatic") ||
        lower.contains("mazhai") ||
        lower.contains("weather epdi irukku")
    ) {
      return DvexIntent.GetWeather()
    }

    // Time
    if (lower.contains("what time is it") ||
        lower.contains("what is the time") ||
        lower.contains("what's the time") ||
        lower.contains("tell me the time") ||
        lower.contains("current time") ||
        lower == "time" ||
        lower.contains("time enna") ||
        lower.contains("mani enna") ||
        lower.contains("நேரம் என்ன")
    ) {
      return DvexIntent.GetTime
    }

    // Calculate
    if (lower.startsWith("calculate ") ||
        lower.startsWith("what is ") && containsMathKeywords(lower) ||
        lower.startsWith("solve ") ||
        containsArithmeticExpression(lower)
    ) {
      val expr = rawText
        .replace(Regex("^(can you please |can you |please )?calculate", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^what is", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^solve", RegexOption.IGNORE_CASE), "")
        .trim()
      if (expr.isNotEmpty()) {
        return DvexIntent.Calculate(expr)
      }
    }

    // News
    if (lower.contains("news") ||
        lower.contains("headlines") ||
        lower.contains("seithigal") ||
        lower.contains("செய்திகள்")
    ) {
      return DvexIntent.GetNews()
    }

    // Web Search
    if (lower.startsWith("search the web for ") ||
        lower.startsWith("search for ") ||
        lower.startsWith("google ") ||
        lower.startsWith("search google for ") ||
        lower.startsWith("search ")
    ) {
      val query = rawText
        .replace(Regex("^(can you please |can you |please )?search the web for", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?search google for", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?search for", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?google", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(can you please |can you |please )?search", RegexOption.IGNORE_CASE), "")
        .trim()
      if (query.isNotEmpty()) {
        return DvexIntent.SearchWeb(query)
      }
    }

    return null
  }

  // --- Phone & System Control ---
  private fun parseSystemControl(rawText: String, lower: String): DvexIntent? {
    // Home
    if (lower == "go home" || lower == "home" || lower == "open home" ||
        lower == "take me home" || lower == "veetuku po" || lower == "home ku po" ||
        lower == "home po" || lower.contains("ஹோம்")
    ) {
      return DvexIntent.GoHome
    }

    // Back
    if (lower == "go back" || lower == "back" || lower == "previous screen" ||
        lower == "pinnaadi po" || lower == "back po" || lower.contains("பின்னாடி")
    ) {
      return DvexIntent.GoBack
    }

    // Recents
    if (lower.contains("recent") || lower == "open recents" ||
        lower == "show recent apps" || lower.contains("recents kaattu")
    ) {
      return DvexIntent.OpenRecents
    }

    // Notifications
    if (lower.contains("notification") || lower == "open notifications" ||
        lower == "show notifications" || lower.contains("notifications kaattu")
    ) {
      return DvexIntent.OpenNotifications
    }

    // Settings
    if (lower == "open settings" || lower == "settings" ||
        lower.contains("settings open") || lower.contains("settings thora") ||
        lower.contains("செட்டிங்ஸ்")
    ) {
      return DvexIntent.OpenSettings
    }

    // Wi-Fi
    if (lower.contains("wifi") || lower.contains("wi-fi") || lower == "open wifi") {
      return DvexIntent.OpenWifiSettings
    }

    // Scroll
    if (lower.contains("scroll down") || lower.contains("keela scroll") || lower == "keela po") {
      return DvexIntent.Scroll(ScrollDirection.DOWN)
    }
    if (lower.contains("scroll up") || lower.contains("mela scroll") || lower == "mela po") {
      return DvexIntent.Scroll(ScrollDirection.UP)
    }

    // Volume
    if (lower.contains("volume up") || lower.contains("increase volume") || lower.contains("sound ethu")) {
      return DvexIntent.AdjustVolume(VolumeAction.UP)
    }
    if (lower.contains("volume down") || lower.contains("decrease volume") || lower.contains("sound kora")) {
      return DvexIntent.AdjustVolume(VolumeAction.DOWN)
    }

    // Media
    if (lower == "pause" || lower == "play" || lower == "pause music" || lower == "play music") {
      return DvexIntent.MediaControl(MediaAction.PLAY_PAUSE)
    }
    if (lower.contains("next song") || lower.contains("next track") || lower == "next") {
      return DvexIntent.MediaControl(MediaAction.NEXT)
    }
    if (lower.contains("previous song") || lower.contains("previous track")) {
      return DvexIntent.MediaControl(MediaAction.PREVIOUS)
    }

    return null
  }

  // --- App Intents ---
  private fun parseAppIntent(rawText: String, lower: String): DvexIntent? {
    // Natural opening variations:
    // "Open YouTube", "Can you open YouTube?", "Launch YouTube for me", "Take me to YouTube", "YouTube open pannu"
    val appName = extractAppName(rawText)
    if (appName.isNotEmpty()) {
      return DvexIntent.OpenApp(appName = appName, rawQuery = rawText)
    }
    return null
  }

  private fun extractAppName(input: String): String {
    var clean = input.trim()
    while (clean.endsWith(".") || clean.endsWith("?") || clean.endsWith("!")) {
      clean = clean.substring(0, clean.length - 1).trim()
    }
    val lower = clean.lowercase(Locale.ROOT).trim()

    // Prefix patterns
    val prefixes = listOf(
      "can you please open ",
      "can you open ",
      "please open ",
      "open ",
      "can you please launch ",
      "can you launch ",
      "please launch ",
      "launch ",
      "take me to ",
      "start ",
      "goto "
    )

    for (p in prefixes) {
      if (lower.startsWith(p)) {
        var candidate = clean.substring(p.length).trim()
        candidate = candidate.replace(Regex("( for me| please)$", RegexOption.IGNORE_CASE), "").trim()
        while (candidate.endsWith(".") || candidate.endsWith("?") || candidate.endsWith("!")) {
          candidate = candidate.substring(0, candidate.length - 1).trim()
        }
        if (candidate.isNotEmpty()) return candidate
      }
    }

    // Suffix patterns (e.g. "YouTube open pannu", "Camera open", "WhatsApp thora", "யூடியூப் open பண்ணு")
    val suffixes = listOf(
      " open pannu",
      " open pannunga",
      " open pannu dvex",
      " ah open pannu",
      " thora",
      " open பண்ணு",
      " open பண்ணுங்க",
      " ஓபன் பண்ணு",
      " open",
      " launch"
    )

    for (s in suffixes) {
      if (lower.endsWith(s)) {
        var candidate = clean.substring(0, clean.length - s.length).trim()
        while (candidate.endsWith(".") || candidate.endsWith("?") || candidate.endsWith("!")) {
          candidate = candidate.substring(0, candidate.length - 1).trim()
        }
        if (candidate.isNotEmpty()) return candidate
      }
    }

    // Standalone known app keywords (e.g. user just says "Camera", "YouTube", "Chrome", "WhatsApp", "Maps")
    val standaloneApps = listOf(
      "youtube", "yt", "camera", "chrome", "maps", "google maps",
      "whatsapp", "settings", "music", "spotify", "dialer", "messages", "gallery", "clock"
    )
    if (standaloneApps.contains(lower)) {
      return clean.trim()
    }

    return ""
  }

  // --- General Conversation & Knowledge ---
  private fun parseConversationAndKnowledge(rawText: String, lower: String): DvexIntent? {
    if (lower == "who are you" || lower == "what are you" || lower == "what is your name" ||
        lower.contains("tell me about yourself") || lower.contains("neenga yaaru") || lower.contains("who made you")
    ) {
      return DvexIntent.Conversation(rawText)
    }

    if (lower == "hello" || lower == "hi" || lower == "hey" || lower == "vanakkam" || lower == "வணக்கம்") {
      return DvexIntent.Conversation(rawText)
    }

    if (lower.startsWith("explain ") || lower.startsWith("tell me a joke") ||
        lower.startsWith("how to ") || lower.startsWith("why is ") || lower.startsWith("what is ")
    ) {
      return DvexIntent.GeneralQuestion(rawText)
    }

    return null
  }

  // --- Helper Math Checks ---
  private fun containsMathKeywords(lower: String): Boolean {
    return lower.contains("plus") || lower.contains("minus") ||
        lower.contains("times") || lower.contains("multiplied by") ||
        lower.contains("divided by") || lower.contains(" x ") ||
        lower.contains(" + ") || lower.contains(" - ") ||
        lower.contains(" * ") || lower.contains(" / ")
  }

  private fun containsArithmeticExpression(lower: String): Boolean {
    return Regex("\\d+\\s*(plus|minus|times|divided by|\\+|\\-|\\*|\\/|x)\\s*\\d+").containsMatchIn(lower)
  }

  // --- Language Detection ---
  private fun detectLanguage(text: String): DetectedLanguage {
    // Check Tamil script unicode range: 0B80 - 0BFF
    for (ch in text) {
      if (ch in '\u0B80'..'\u0BFF') {
        return DetectedLanguage.TAMIL
      }
    }

    // Check Tanglish phonetic cues
    val lower = text.lowercase(Locale.ROOT)
    val tanglishKeywords = listOf(
      "pannu", "pannunga", "pora", "po", "kaattu", "enna",
      "veiy", "vanakkam", "thora", "solla", "epdi", "irukku", "poidu", "anupu"
    )
    val words = lower.split(Regex("\\s+"))
    if (words.any { it in tanglishKeywords }) {
      return DetectedLanguage.TANGLISH
    }

    return DetectedLanguage.ENGLISH
  }

  // --- Sanitize Wake Phrase and Noise ---
  private fun sanitizeWakePhraseAndFillers(rawInput: String): String {
    var cleaned = rawInput.trim()
    val prefixes = listOf(
      "hey d-vex", "hey dvex", "d-vex", "dvex", "dee vex",
      "டி-வெக்ஸ்", "டிவெக்ஸ்", "டீவெக்ஸ்"
    )
    val lower = cleaned.lowercase(Locale.ROOT)
    for (p in prefixes) {
      if (lower.startsWith(p)) {
        cleaned = cleaned.substring(p.length).trim()
        while (cleaned.startsWith(",") || cleaned.startsWith(".") || cleaned.startsWith(":") || cleaned.startsWith("-")) {
          cleaned = cleaned.substring(1).trim()
        }
        break
      }
    }
    while (cleaned.endsWith(".") || cleaned.endsWith("?") || cleaned.endsWith("!")) {
      cleaned = cleaned.substring(0, cleaned.length - 1).trim()
    }
    return cleaned
  }

  companion object {
    private const val TAG = "[D-VEX][INTENT]"
  }
}

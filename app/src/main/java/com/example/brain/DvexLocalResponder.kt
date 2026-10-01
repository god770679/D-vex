package com.example.brain

import java.util.Locale

/**
 * D-VEX'S OWN OFFLINE VOICE — the local half of the FREE-FIRST architecture.
 *
 * This is NOT a second AI and it is NOT a replacement for Gemini. It is the small
 * deterministic layer D-VEX already has everywhere else (intent detection, tool
 * routing, verification), extended to the one thing it never owned: what D-VEX says
 * when there is no cloud model to say it.
 *
 * It exists because the honest options were both bad: say "I can't reach my AI
 * connection" every single message, or invent an answer. This does neither. For a
 * greeting, a thank-you or a farewell there is nothing to know — D-VEX simply
 * answers. For a question that genuinely needs the cloud model, it says plainly
 * that the cloud assistant is temporarily unavailable.
 *
 * HARD RULE — [AiAvailability] honesty: this class never claims Gemini answered,
 * never quotes or paraphrases a model, and never invents a fact, number, name or
 * outcome. Every line it returns is something D-VEX can say truthfully with no
 * network at all. The user's language, script and register are mirrored exactly as
 * the cloud path does, so a phone that loses its quota mid-conversation keeps
 * talking in the same voice.
 */
class DvexLocalResponder {

  /**
   * The line D-VEX speaks for [userInput] while the cloud AI is unavailable.
   *
   * [toolResult] is consulted so a command that already executed locally never gets
   * overwritten by chit-chat: the caller only routes genuinely conversational turns
   * here, and anything with a real result keeps its own spoken text.
   */
  fun reply(userInput: String, language: DetectedLanguage): String {
    val lower = userInput.lowercase(Locale.ROOT).trim()
    return when {
      isFarewell(lower) -> farewell(language)
      isThanks(lower) -> thanks(language)
      isGreeting(lower) -> greeting(language)
      isIdentity(lower) -> identity(language)
      isCapabilities(lower) -> capabilities(language)
      isAffirmationOnly(lower) -> affirmation(language)
      else -> cloudUnavailable(language)
    }
  }

  // =========================================================================
  // Classification. Deliberately narrow and literal: anything it does not
  // positively recognise becomes the honest unavailable line, so an
  // unrecognised question can never be answered from imagination.
  // =========================================================================

  private fun isGreeting(l: String): Boolean =
    GREETING_TOKENS.any { l == it || l == "$it d vex" || l == "hi $it" } ||
      l.matches(GREETING_PATTERN)

  private fun isThanks(l: String): Boolean =
    l.contains("thank") || l.contains("thanks") || l.contains("thx")

  private fun isFarewell(l: String): Boolean =
    l.contains("bye") || l.contains("goodbye") || l.contains("good night") ||
      l.contains("see you") || l.contains("ciao")

  private fun isIdentity(l: String): Boolean =
    (l.contains("who are you") || l.contains("what are you") || l == "your name") &&
      !l.contains("do")

  private fun isCapabilities(l: String): Boolean =
    l.contains("what can you do") || l.contains("what do you do") ||
      l.contains("help me") || l == "help" || l.contains("your capabilities")

  private fun isAffirmationOnly(l: String): Boolean =
    l in setOf("ok", "okay", "sure", "cool", "nice", "great", "awesome", "good")

  // =========================================================================
  // What D-VEX says. Every string is truthful with no network.
  // =========================================================================

  private fun greeting(language: DetectedLanguage): String = when (language) {
    DetectedLanguage.TAMIL -> "வணக்கம்! நான் D-VEX. உங்களுக்கு எதில் உதவி வேண்டும்?"
    DetectedLanguage.TANGLISH -> "Vanakkam! Naan D-VEX. Enakku edhil help venum?"
    DetectedLanguage.ENGLISH -> "Hey, I'm D-VEX. What do you need?"
  }

  private fun thanks(language: DetectedLanguage): String = when (language) {
    DetectedLanguage.TAMIL -> "பொருந்தியது. வேற ஏதாவது வேண்டுமா?"
    DetectedLanguage.TANGLISH -> "Nandri. Veru edhavu venuma?"
    DetectedLanguage.ENGLISH -> "Anytime. Need anything else?"
  }

  private fun farewell(language: DetectedLanguage): String = when (language) {
    DetectedLanguage.TAMIL -> "சரி, நான் இங்கே இருக்கிறேன். அழைத்தால் சொல்லுங்கள்."
    DetectedLanguage.TANGLISH -> "Seri, naan inge irukken. Azhuthal sollunga."
    DetectedLanguage.ENGLISH -> "Sure, I'll be right here. Just call for me."
  }

  private fun affirmation(language: DetectedLanguage): String = when (language) {
    DetectedLanguage.TAMIL -> "சரி, சொல்லுங்கள்."
    DetectedLanguage.TANGLISH -> "Seri, sollunga."
    DetectedLanguage.ENGLISH -> "Go ahead."
  }

  /**
   * Identity. States what D-VEX is without borrowing the cloud model's persona:
   * it is the assistant that runs on the phone and drives the device.
   */
  private fun identity(language: DetectedLanguage): String = when (language) {
    DetectedLanguage.TAMIL ->
      "நான் D-VEX, உங்கள் தொலைபேசியில் இயங்கும் உதவியாளர். நேரம், நினைவு, அலாரம் மற்றும் பயன்பாடுகளை நான் கையாள முடியும்."
    DetectedLanguage.TANGLISH ->
      "Naan D-VEX, ungal phone la operate aagum helper. Time, reminders, alarms and apps naan handle pannunga."
    DetectedLanguage.ENGLISH ->
      "I'm D-VEX, the assistant running on your phone. I handle the time, reminders, alarms, and opening apps."
  }

  /**
   * Capabilities. Only lists what D-VEX can genuinely do OFFLINE, because this is
   * the line a user hears when the cloud model is unreachable — promising a search
   * or a general answer here would be a promise D-VEX cannot keep right now.
   */
  private fun capabilities(language: DetectedLanguage): String = when (language) {
    DetectedLanguage.TAMIL ->
      "நான் நேரம் சொல்லலாம், நினைவூட்டல் மற்றும் அலாரம் வைக்கலாம், உங்கள் நினைவிற்கான விஷயங்களை மட்டும் வைத்திருக்கலாம், பயன்பாடுகளைத் திறக்கலாம், மற்றும் கைபேசி கட்டுப்பாட்டை செய்யலாம்."
    DetectedLanguage.TANGLISH ->
      "Naan time sollunga, reminders and alarms veichuvom, ungal feedha la irukura varaikkaraama naan vechirukkuvom, apps thaakuvom, and phone control panrenum."
    DetectedLanguage.ENGLISH ->
      "I can tell you the time, set and read back reminders and alarms, remember things you tell me, open apps, and control parts of your phone."
  }

  /**
   * The honest line for anything that genuinely needs the cloud model.
   *
   * It names the real situation (the cloud assistant is temporarily unavailable),
   * says what still works, and makes no pretence that an answer was generated. It
   * deliberately does NOT promise a specific time — D-VEX does not know when
   * Google's daily quota resets, and inventing one would be a lie.
   */
  private fun cloudUnavailable(language: DetectedLanguage): String = when (language) {
    DetectedLanguage.TAMIL ->
      "என் மேக உதவியாளர் தற்காலிகமாக இல்லை, அதனால் இந்த கேள்விக்கு சரியான பதில் தர முடியவில்லை. நேரம், நினைவூட்டல், அலாரம் போன்றவற்றை நான் இப்போதும் சரியாகச் செய்ய முடியும்."
    DetectedLanguage.TANGLISH ->
      "Enna cloud assistant temporary ah illa, apparam inna question ku correct reply solla mudiyala. Time, reminder, alarm elaichavalum ippo rendu nagai save aagudhu."
    DetectedLanguage.ENGLISH ->
      "My cloud assistant is temporarily unavailable, so I can't give you a proper answer to that one. Time, reminders and alarms still work fine, though."
  }

  private companion object {
    private val GREETING_TOKENS = setOf(
      "hi", "hey", "hello", "helo", "howdy", "yo", "sup", "namaste", "ayubowan", "vanakkam", "hola"
    )

    /** "hi dvex", "hey there", "good morning" and similar openers. */
    private val GREETING_PATTERN = Regex(
      "^(hi|hey|hello|helo|good\\s+(morning|afternoon|evening|night)|howdy)\\b.*"
    )
  }
}
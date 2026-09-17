package com.example.brain

/**
 * Strongly typed intent model for D-VEX Smart Brain V1.
 * Represents all recognized user objectives across phone control, live info,
 * memory, multi-step actions, and general conversation.
 */
sealed class DvexIntent {

  // --- App & Navigation Intents ---
  data class OpenApp(val appName: String, val rawQuery: String = appName) : DvexIntent()
  data class CloseApp(val appName: String) : DvexIntent()
  object GoHome : DvexIntent()
  object GoBack : DvexIntent()
  object OpenRecents : DvexIntent()
  object OpenNotifications : DvexIntent()
  object OpenSettings : DvexIntent()
  object OpenWifiSettings : DvexIntent()
  object LockScreen : DvexIntent()
  data class Scroll(val direction: ScrollDirection) : DvexIntent()

  // --- Communication (Sensitive Actions) ---
  data class CallContact(val recipient: String) : DvexIntent()
  data class SendMessage(val recipient: String, val messageText: String? = null) : DvexIntent()
  data class SendEmail(val recipient: String, val subject: String? = null, val body: String? = null) : DvexIntent()

  // --- Device Hardware Controls ---
  data class ToggleFlashlight(val enable: Boolean? = null) : DvexIntent()
  data class SetAlarm(val hour: Int, val minute: Int, val message: String? = null) : DvexIntent()
  data class SetTimer(val seconds: Int, val message: String? = null) : DvexIntent()

  // --- Live Information & Utility ---
  data class SearchWeb(val query: String) : DvexIntent()
  data class GetWeather(val location: String? = null, val isTomorrow: Boolean = false) : DvexIntent()
  data class GetNews(val topic: String? = null) : DvexIntent()
  object GetTime : DvexIntent()
  data class Calculate(val expression: String) : DvexIntent()

  // --- Wake / Greeting ---
  object WakeGreeting : DvexIntent()

  // --- Media & Volume ---
  data class AdjustVolume(val direction: VolumeAction) : DvexIntent()
  data class MediaControl(val command: MediaAction) : DvexIntent()

  // --- Memory Integration ---
  data class RememberFact(val fact: String) : DvexIntent()
  data class RecallMemory(val query: String? = null) : DvexIntent()

  // --- Multi-Step Commands (e.g. "Open YouTube and search for tractor videos") ---
  data class MultiStep(val first: DvexIntent, val second: DvexIntent) : DvexIntent()

  // --- General Conversation & Knowledge ---
  data class GeneralQuestion(val question: String) : DvexIntent()
  data class Conversation(val statement: String) : DvexIntent()

  // --- Safety & Ambiguity Handling ---
  data class LowConfidence(val clarificationPrompt: String, val candidateIntent: DvexIntent? = null) : DvexIntent()
  data class Unknown(val rawInput: String) : DvexIntent()
}

enum class ScrollDirection { UP, DOWN }
enum class VolumeAction { UP, DOWN, MUTE }
enum class MediaAction { PLAY_PAUSE, NEXT, PREVIOUS }

enum class DetectedLanguage {
  ENGLISH,
  TAMIL,
  TANGLISH
}

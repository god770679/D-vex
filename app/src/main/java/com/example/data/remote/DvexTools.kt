package com.example.data.remote

enum class ToolResultStatus {
  SUCCESS,
  FAILED,
  NEEDS_PERMISSION,
  NOT_SUPPORTED,
  CONFIRMATION_REQUIRED
}

data class ToolExecutionResult(
  val status: ToolResultStatus,
  val toolName: String,
  val message: String,
  val requiresConfirmation: Boolean = false,
  val confirmationPrompt: String? = null,
  val pendingActionId: String? = null
) {
  val isSuccessful: Boolean get() = status == ToolResultStatus.SUCCESS
}

data class PendingConfirmation(
  val id: String,
  val actionTitle: String,
  val prompt: String,
  val details: String,
  val onConfirm: suspend () -> ToolExecutionResult
)

sealed class DvexIntentAction {
  data class LaunchApp(val appName: String) : DvexIntentAction()
  object OpenCamera : DvexIntentAction()
  object OpenSettings : DvexIntentAction()
  data class OpenMaps(val query: String? = null) : DvexIntentAction()
  data class OpenBrowser(val url: String? = null) : DvexIntentAction()
  data class OpenYouTube(val query: String? = null) : DvexIntentAction()
  data class DialPhone(val number: String? = null) : DvexIntentAction()
  data class PrepareMessage(val recipient: String? = null, val body: String? = null) : DvexIntentAction()
  object NavigateHome : DvexIntentAction()
  object NavigateBack : DvexIntentAction()
  object ShowRecents : DvexIntentAction()
  object ShowNotifications : DvexIntentAction()
  data class AdjustVolume(val direction: VolumeDirection) : DvexIntentAction()
  data class MediaControl(val command: MediaCommand) : DvexIntentAction()
  object OpenWifiSettings : DvexIntentAction()
  data class QueryInformation(val topic: String) : DvexIntentAction()
  data class ChatResponse(val text: String) : DvexIntentAction()
}

enum class VolumeDirection { UP, DOWN, MUTE }
enum class MediaCommand { PLAY_PAUSE, NEXT, PREVIOUS }

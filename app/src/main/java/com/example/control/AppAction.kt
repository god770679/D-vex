package com.example.control

/**
 * Reusable action definition representing UI automation steps for a target app.
 * Allows extending D-VEX to any app without modifying core brain code.
 */
data class AppAction(
  val appName: String,
  val targetPackage: String,
  val actionName: String,
  val description: String
)

sealed class AppActionResult {
  data class Success(val message: String) : AppActionResult()
  data class Failed(val reason: String) : AppActionResult()
  data class NeedsConfirmation(
    val prompt: String,
    val contactName: String,
    val messageText: String
  ) : AppActionResult()
  data class PermissionRequired(val message: String) : AppActionResult()
  data class Ambiguous(val prompt: String, val options: List<String>) : AppActionResult()
}

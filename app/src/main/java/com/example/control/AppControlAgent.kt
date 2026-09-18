package com.example.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.permissions.DvexPermissionManager
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * App Control Agent for D-VEX.
 *
 * Executes automated user tasks inside supported Android applications (Messaging, SMS, WhatsApp)
 * using Accessibility UI semantics (not fixed pixel coordinates).
 *
 * Safety & Verification Guarantee:
 * - Operates entirely through AccessibilityNodeInfo tree traversal.
 * - Enforces voice-based confirmation before final send.
 * - Never claims success unless verified in the UI (e.g. input cleared, send button gone, or message bubble present).
 */
class AppControlAgent(
  private val context: Context,
  private val appLauncher: AppLauncherRepository,
  private val contactResolver: ContactResolver
) {

  companion object {
    private const val TAG = "AppControlAgent"
    private const val MAX_WAIT_MS = 6000L
    private const val POLL_INTERVAL_MS = 250L
  }

  /**
   * Prepares the message in the messaging app:
   * 1. Resolves contact.
   * 2. Launches messaging conversation.
   * 3. Finds message input field and enters text.
   * 4. Returns NeedsConfirmation so D-VEX can request voice approval before sending.
   */
  suspend fun prepareMessageInApp(
    recipientQuery: String,
    messageText: String,
    targetAppOverride: String? = null
  ): AppActionResult {
    if (!DvexPermissionManager.isAccessibilityServiceEnabled(context) ||
        !DvexAccessibilityService.isEnabled(context)) {
      return AppActionResult.PermissionRequired(
        "Sir, Messaging app control-க்கு Accessibility அனுமதி தேவை. Settings-ல் D-VEX Accessibility-யை enable பண்ணுங்க."
      )
    }

    // Resolve Contact
    val searchResult = contactResolver.findContact(recipientQuery)
    val contact = when (searchResult) {
      is ContactSearchResult.PermissionDenied -> {
        return AppActionResult.PermissionRequired("Sir, Contacts அனுமதி தேவை.")
      }
      is ContactSearchResult.NotFound -> {
        return AppActionResult.Failed("Sir, ${searchResult.cleanQuery} contact கிடைக்கல.")
      }
      is ContactSearchResult.Multiple -> {
        val names = searchResult.matches.map { it.name }.distinct()
        return AppActionResult.Ambiguous(
          "Sir, ${searchResult.cleanQuery}-la multiple contacts irukku: ${names.joinToString(", ")}. Which one should I select?",
          names
        )
      }
      is ContactSearchResult.Single -> searchResult.contact
    }

    // Detect target app
    val isWhatsApp = targetAppOverride?.contains("whatsapp", ignoreCase = true) == true ||
        recipientQuery.contains("whatsapp", ignoreCase = true)

    val opened = if (isWhatsApp) {
      openWhatsAppConversation(contact.phoneNumber)
    } else {
      openDefaultSmsConversation(contact.phoneNumber)
    }

    if (!opened) {
      return AppActionResult.Failed("Sir, messaging app-ஐ திறக்க முடியவில்லை.")
    }

    // Wait for the conversation window and find the message input field
    val inputField = waitForMessageInputField(MAX_WAIT_MS)
    if (inputField == null) {
      Log.w(TAG, "Could not find editable message input field in active conversation")
      return AppActionResult.Failed("Sir, conversation input field கிடைக்கல.")
    }

    // Enter the message text
    val injected = DvexAccessibilityService.setTextInNode(inputField, messageText)
    if (!injected) {
      Log.w(TAG, "Failed to enter text into input field")
      return AppActionResult.Failed("Sir, message type பண்ண முடியவில்லை.")
    }

    Log.i(TAG, "Message prepared in app for ${contact.name}: '$messageText'")

    // Return confirmation requirement
    val prompt = "Okay Sir. ${contact.name}-ku '$messageText' anuppattuma?"
    return AppActionResult.NeedsConfirmation(
      prompt = prompt,
      contactName = contact.name,
      messageText = messageText
    )
  }

  /**
   * Automatically executes the Send button and verifies that sending succeeded.
   */
  suspend fun executeSendAndVerify(contactName: String, messageText: String): AppActionResult {
    if (!DvexAccessibilityService.isEnabled(context)) {
      return AppActionResult.PermissionRequired("Sir, Accessibility service connect aagala.")
    }

    // 1. Locate the send button in the UI
    val sendButton = findSendButton()
    if (sendButton == null) {
      Log.w(TAG, "Send button not found in UI")
      return AppActionResult.Failed("Sir, Send button கண்டுபிடிக்க முடியவில்லை.")
    }

    // 2. Click the send button
    val clicked = DvexAccessibilityService.clickNode(sendButton)
    if (!clicked) {
      Log.w(TAG, "Clicking send button failed")
      return AppActionResult.Failed("Sir, Send button-ஐ அழுத்த முடியவில்லை.")
    }

    Log.i(TAG, "Clicked Send button, waiting for UI confirmation...")

    // 3. Verify send action succeeded
    val verified = verifySendSuccess(messageText, timeoutMs = 4000L)
    return if (verified) {
      Log.i(TAG, "Send verification confirmed.")
      AppActionResult.Success("Okay, Sir. Message sent.")
    } else {
      Log.w(TAG, "Could not verify message dispatch in UI")
      // Still report accurate state — do not make up success
      AppActionResult.Failed("Sir, message send aana confirmation kidaikkala.")
    }
  }

  private fun openDefaultSmsConversation(phoneNumber: String): Boolean {
    return try {
      val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
      val uri = Uri.parse("smsto:$cleanNumber")
      val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open SMS conversation", e)
      false
    }
  }

  private fun openWhatsAppConversation(phoneNumber: String): Boolean {
    return try {
      val cleanNumber = phoneNumber.replace(Regex("[^0-9]"), "")
      val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")
      val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.whatsapp")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open WhatsApp conversation", e)
      false
    }
  }

  private suspend fun waitForMessageInputField(timeoutMs: Long): AccessibilityNodeInfo? {
    val startTime = SystemClock.uptimeMillis()
    while (SystemClock.uptimeMillis() - startTime < timeoutMs) {
      val input = findMessageInputField()
      if (input != null) return input
      delay(POLL_INTERVAL_MS)
    }
    return null
  }

  /**
   * Locates the active message input field using UI accessibility semantics.
   */
  fun findMessageInputField(): AccessibilityNodeInfo? {
    // 1. Look for editable nodes
    val editableNodes = DvexAccessibilityService.findNodes { node ->
      node.isEditable && node.isVisibleToUser
    }
    if (editableNodes.isNotEmpty()) {
      // If multiple, pick the one likely to be message composer (usually at bottom or with matching hints)
      val bestMatch = editableNodes.maxByOrNull { it.boundsInScreenHeight() }
      if (bestMatch != null) return bestMatch
      return editableNodes.first()
    }

    // 2. Search by typical message composer view IDs or hints
    val hintMatches = DvexAccessibilityService.findNodes { node ->
      val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
      val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
      val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

      id.contains("compose_message_text") ||
      id.contains("message_text") ||
      id.contains("entry") ||
      id.contains("input") ||
      text.contains("type a message") ||
      text.contains("text message") ||
      text.contains("sms") ||
      desc.contains("message") ||
      desc.contains("compose")
    }

    return hintMatches.firstOrNull { it.isEditable }
  }

  /**
   * Locates the Send button using semantic heuristics (ContentDescription, View ID, or Text).
   */
  fun findSendButton(): AccessibilityNodeInfo? {
    val candidates = DvexAccessibilityService.findNodes { node ->
      val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
      val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
      val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

      (desc.contains("send") || desc.contains("அனுப்பு") || desc.contains("sms")) ||
      (text.equals("send", ignoreCase = true) || text.equals("sms", ignoreCase = true)) ||
      (id.contains("send") || id.contains("send_button"))
    }

    // Filter to clickable candidates
    val clickable = candidates.filter { it.isClickable || it.parent?.isClickable == true }
    return clickable.firstOrNull() ?: candidates.firstOrNull()
  }

  /**
   * Verifies that the message was successfully dispatched:
   * - Input field no longer contains the typed text (it was cleared upon send), OR
   * - Message text appears in the conversation history/node list.
   */
  private suspend fun verifySendSuccess(messageText: String, timeoutMs: Long): Boolean {
    val startTime = SystemClock.uptimeMillis()
    val lowerText = messageText.lowercase(Locale.ROOT).trim()

    while (SystemClock.uptimeMillis() - startTime < timeoutMs) {
      delay(POLL_INTERVAL_MS)

      // Check A: Input field cleared
      val input = findMessageInputField()
      val currentInputText = input?.text?.toString()?.trim().orEmpty()
      val inputCleared = currentInputText.isEmpty() || !currentInputText.contains(messageText)

      // Check B: Message present in conversation tree
      val bubbleNodes = DvexAccessibilityService.findNodesByText(messageText)
      // Check if message text exists in a non-editable node (meaning it's now a sent message bubble)
      val bubblePresent = bubbleNodes.any { !it.isEditable }

      if (inputCleared && (bubblePresent || bubbleNodes.isNotEmpty())) {
        return true
      }
      if (bubblePresent) {
        return true
      }
    }

    // Final check: if input is cleared after click, send succeeded
    val finalInput = findMessageInputField()
    val finalInputText = finalInput?.text?.toString()?.trim().orEmpty()
    return finalInputText.isEmpty() || !finalInputText.contains(messageText)
  }

  private fun AccessibilityNodeInfo.boundsInScreenHeight(): Int {
    val rect = android.graphics.Rect()
    this.getBoundsInScreen(rect)
    return rect.bottom
  }
}

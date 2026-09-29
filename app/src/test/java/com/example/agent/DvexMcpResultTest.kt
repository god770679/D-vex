package com.example.agent

import com.example.brain.DvexToolResult
import com.example.brain.DvexToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INCREMENT 1 — canonical MCP result.
 *
 * Four statuses, one rule: an action that was not confirmed is UNVERIFIED, and it is
 * never reported as success.
 */
class DvexMcpResultTest {

  private fun toolResult(status: DvexToolStatus, message: String = "text") = DvexToolResult(
    status = status,
    toolName = "open_app",
    message = message,
    spokenText = message
  )

  // --- legacy DvexToolResult -> canonical result ---------------------------

  @Test
  fun routerSuccessBecomesVerifiedSuccessAtDispatchLevel() {
    val result = DvexMcpResult.fromToolResult(toolResult(DvexToolStatus.SUCCESS, "Opening YouTube."))
    assertEquals(DvexMcpStatus.VERIFIED_SUCCESS, result.status)
    assertEquals(VerificationOutcome.DISPATCH_CONFIRMED, result.verification)
    assertTrue(result.isVerifiedSuccess)
    assertNull(result.error)
  }

  @Test
  fun unverifiedToolStatusStaysUnverifiedAndIsNeverSuccess() {
    val result = DvexMcpResult.fromToolResult(toolResult(DvexToolStatus.UNVERIFIED, "Opening YouTube."))
    assertEquals(DvexMcpStatus.UNVERIFIED, result.status)
    assertEquals(VerificationOutcome.UNVERIFIED, result.verification)
    assertFalse(result.isVerifiedSuccess)
    assertTrue(result.error!!.isNotBlank())
  }

  @Test
  fun permissionAndUnsupportedAreBlocked() {
    val permission = DvexMcpResult.fromToolResult(toolResult(DvexToolStatus.PERMISSION_REQUIRED))
    assertEquals(DvexMcpStatus.BLOCKED, permission.status)
    assertFalse(permission.isVerifiedSuccess)

    val unsupported = DvexMcpResult.fromToolResult(toolResult(DvexToolStatus.UNSUPPORTED))
    assertEquals(DvexMcpStatus.BLOCKED, unsupported.status)
  }

  @Test
  fun confirmationRequiredIsBlockedButUserUnblockable() {
    val result = DvexMcpResult.fromToolResult(
      DvexToolResult(
        status = DvexToolStatus.CONFIRMATION_REQUIRED,
        toolName = "send_message",
        message = "details",
        spokenText = "details",
        requiresConfirmation = true,
        confirmationPrompt = "Send it to Arun?"
      )
    )
    assertEquals(DvexMcpStatus.BLOCKED, result.status)
    assertTrue(result.requiresConfirmation)
    assertEquals("Send it to Arun?", result.message)
  }

  @Test
  fun failuresAndNotFoundAreFailed() {
    listOf(DvexToolStatus.FAILED, DvexToolStatus.ERROR, DvexToolStatus.NOT_FOUND).forEach { status ->
      assertEquals(
        "status $status",
        DvexMcpStatus.FAILED,
        DvexMcpResult.fromToolResult(toolResult(status)).status
      )
    }
  }

  // --- AgentActionResult -> canonical result -------------------------------

  @Test
  fun observedEffectIsVerifiedSuccess() {
    val result = DvexMcpResult.fromActionResult(
      "open_app",
      AgentActionResult(AgentActionStatus.SUCCESS, "Opened YouTube.", verified = true)
    )
    assertEquals(DvexMcpStatus.VERIFIED_SUCCESS, result.status)
    assertEquals(VerificationOutcome.VERIFIED, result.verification)
  }

  @Test
  fun platformConfirmedEffectIsSuccessWithoutClaimingVerification() {
    val result = DvexMcpResult.fromActionResult(
      "volume_up",
      AgentActionResult(
        AgentActionStatus.SUCCESS,
        "Turned the volume up.",
        verification = VerificationOutcome.DISPATCH_CONFIRMED
      )
    )
    assertEquals(DvexMcpStatus.VERIFIED_SUCCESS, result.status)
    assertEquals(VerificationOutcome.DISPATCH_CONFIRMED, result.verification)
  }

  @Test
  fun unobservedEffectNeverBecomesSuccess() {
    // The core honesty rule: a handler that returned SUCCESS but could not confirm
    // the effect is UNVERIFIED — regardless of the status enum it used.
    val dispatchedButUnconfirmed = AgentActionResult(
      status = AgentActionStatus.SUCCESS,
      message = "Opening YouTube.",
      verified = false
    )
    val result = DvexMcpResult.fromActionResult("open_app", dispatchedButUnconfirmed)

    assertEquals(VerificationOutcome.UNVERIFIED, dispatchedButUnconfirmed.verification)
    assertEquals(DvexMcpStatus.UNVERIFIED, result.status)
    assertFalse(result.isVerifiedSuccess)
  }

  @Test
  fun permissionBlockCarriesTheRequiredPermission() {
    val result = DvexMcpResult.fromActionResult(
      "camera",
      AgentActionResult(
        status = AgentActionStatus.REQUIRES_PERMISSION,
        message = "I need camera permission.",
        requiredPermission = "camera"
      )
    )
    assertEquals(DvexMcpStatus.BLOCKED, result.status)
    assertEquals("camera", result.requiredPermission)
  }

  @Test
  fun confirmationBlockIsMarkedAndFailureIsFailed() {
    val confirmation = DvexMcpResult.fromActionResult(
      "send_message",
      AgentActionResult(AgentActionStatus.REQUIRES_CONFIRMATION, "Shall I message Arun?")
    )
    assertEquals(DvexMcpStatus.BLOCKED, confirmation.status)
    assertTrue(confirmation.requiresConfirmation)

    val failure = DvexMcpResult.fromActionResult(
      "open_app",
      AgentActionResult(AgentActionStatus.FAILURE, "That action couldn't be completed.")
    )
    assertEquals(DvexMcpStatus.FAILED, failure.status)
  }

  @Test
  fun aSuccessIsNeverReportedWithUnverifiedVerification() {
    // Invariant across every mapping: VERIFIED_SUCCESS implies real confirmation.
    val results = listOf(
      DvexMcpResult.fromToolResult(toolResult(DvexToolStatus.SUCCESS)),
      DvexMcpResult.fromToolResult(toolResult(DvexToolStatus.UNVERIFIED)),
      DvexMcpResult.fromActionResult("t", AgentActionResult(AgentActionStatus.SUCCESS, "m", verified = true)),
      DvexMcpResult.fromActionResult("t", AgentActionResult(AgentActionStatus.SUCCESS, "m")),
      DvexMcpResult.fromActionResult("t", AgentActionResult(AgentActionStatus.FAILURE, "m"))
    )
    results.filter { it.status == DvexMcpStatus.VERIFIED_SUCCESS }.forEach {
      assertTrue("${it.toolName} claimed success without confirmation", it.verification != VerificationOutcome.UNVERIFIED)
    }
  }

  @Test
  fun canonicalMessagesCarryNoHonorificsOrInternalLabels() {
    val messages = listOf(
      DvexMcpResult.fromToolResult(toolResult(DvexToolStatus.SUCCESS, "Opened YouTube.")),
      DvexMcpResult.fromToolResult(toolResult(DvexToolStatus.UNVERIFIED, "Opening YouTube.")),
      DvexMcpResult.fromActionResult("open_app", AgentActionResult(AgentActionStatus.SUCCESS, "Opened YouTube."))
    ).map { it.message }

    messages.forEach { message ->
      assertFalse("honorific leaked: $message", Regex("\\bSir\\b", RegexOption.IGNORE_CASE).containsMatchIn(message))
      assertFalse("internal label leaked: $message", message.contains("tool_result", ignoreCase = true))
    }
  }
}

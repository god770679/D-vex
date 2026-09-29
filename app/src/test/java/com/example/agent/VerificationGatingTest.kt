package com.example.agent

import com.example.brain.DvexToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INCREMENT 1 — the verification gate in `DvexTaskResult.toToolResult()`.
 *
 * An action whose effect was never observed must come out as UNVERIFIED. It must not
 * become SUCCESS, and it must not be summarised as "done" — the user is told what
 * D-VEX actually knows.
 */
class VerificationGatingTest {

  private fun action(type: AgentActionType = AgentActionType.OPEN_APP, target: String = "YouTube") =
    AgentAction(type, DvexCapability.APP_LAUNCH, target = target)

  private fun taskResult(vararg steps: Pair<AgentAction, AgentActionResult>) = DvexTaskResult(
    plan = DvexTaskPlan(steps = steps.map { it.first }, summary = "test plan"),
    steps = steps.toList()
  )

  private val verifiedOpen = AgentActionResult(
    status = AgentActionStatus.SUCCESS,
    message = "Opened YouTube.",
    verified = true
  )

  private val unverifiedOpen = AgentActionResult(
    status = AgentActionStatus.SUCCESS,
    message = "Opening YouTube."
  )

  private val dispatchConfirmed = AgentActionResult(
    status = AgentActionStatus.SUCCESS,
    message = "Turned the volume up.",
    verification = VerificationOutcome.DISPATCH_CONFIRMED
  )

  @Test
  fun observedEffectIsReportedAsSuccess() {
    val result = taskResult(action() to verifiedOpen).toToolResult()
    assertEquals(DvexToolStatus.SUCCESS, result.status)
    assertEquals("Done. Opened YouTube.", result.spokenText)
    assertEquals(result.message, result.spokenText)
  }

  @Test
  fun platformConfirmedEffectIsReportedAsSuccess() {
    val result = taskResult(
      action(AgentActionType.VOLUME_UP, "") to dispatchConfirmed
    ).toToolResult()
    assertEquals(DvexToolStatus.SUCCESS, result.status)
    assertTrue(result.spokenText.startsWith("Done."))
  }

  @Test
  fun unobservedEffectIsNeverSuccess() {
    val result = taskResult(action() to unverifiedOpen).toToolResult()

    assertEquals(DvexToolStatus.UNVERIFIED, result.status)
    assertNotEquals(DvexToolStatus.SUCCESS, result.status)
    assertFalse("unverified output must not claim success", result.isSuccessful)
    // The honest handler wording survives, and nothing claims completion.
    assertEquals("Opening YouTube.", result.spokenText)
    assertFalse(result.spokenText.contains("Done."))
  }

  @Test
  fun everyActionTypeIsGatedTheSameWay() {
    // No action type can slip an unobserved effect through as success.
    AgentActionType.entries.forEach { type ->
      val result = taskResult(action(type, "target") to unverifiedOpen).toToolResult()
      assertNotEquals("$type reported an unverified effect as success", DvexToolStatus.SUCCESS, result.status)
      assertEquals(DvexToolStatus.UNVERIFIED, result.status)
    }
  }

  @Test
  fun unverifiedStepIsExcludedFromTheDoneSummary() {
    // Step 1 really happened; step 2 was dispatched but its effect was never seen.
    val result = taskResult(
      action(AgentActionType.OPEN_APP, "YouTube") to AgentActionResult(
        AgentActionStatus.SUCCESS,
        "Opened YouTube.",
        verified = true
      ),
      action(AgentActionType.OPEN_APP, "Instagram") to AgentActionResult(
        AgentActionStatus.SUCCESS,
        "Opening Instagram."
      )
    ).toToolResult()

    assertEquals(DvexToolStatus.UNVERIFIED, result.status)
    // The confirmed step is stated; the unconfirmed one keeps its honest
    // in-progress wording and is never summarised as done.
    assertTrue(
      "confirmed step must be summarised: ${result.spokenText}",
      result.spokenText.contains("Opened YouTube")
    )
    assertTrue(
      "unconfirmed step must keep its in-progress wording: ${result.spokenText}",
      result.spokenText.contains("opening Instagram", ignoreCase = true)
    )
    assertFalse("must not claim completion: ${result.spokenText}", result.spokenText.contains("Done."))
  }

  @Test
  fun failureAfterAConfirmedStepKeepsTheHonestPartialSummary() {
    val result = taskResult(
      action(AgentActionType.OPEN_APP, "YouTube") to dispatchConfirmed,
      action(AgentActionType.SEARCH_IN_APP, "YouTube") to AgentActionResult(
        AgentActionStatus.FAILURE,
        "I couldn't find a search button to submit."
      )
    ).toToolResult()

    assertEquals(DvexToolStatus.FAILED, result.status)
    assertFalse(result.spokenText.contains("Done."))
    assertTrue(result.spokenText.contains("couldn't find a search button"))
  }

  @Test
  fun permissionConfirmationAndUnsupportedStatesArePreserved() {
    val permission = taskResult(
      action() to AgentActionResult(AgentActionStatus.REQUIRES_PERMISSION, "I need camera permission.")
    ).toToolResult()
    assertEquals(DvexToolStatus.PERMISSION_REQUIRED, permission.status)

    val confirmation = taskResult(
      action() to AgentActionResult(AgentActionStatus.REQUIRES_CONFIRMATION, "Shall I message Arun?")
    ).toToolResult()
    assertEquals(DvexToolStatus.CONFIRMATION_REQUIRED, confirmation.status)
    assertTrue(confirmation.requiresConfirmation)
    assertEquals("Shall I message Arun?", confirmation.confirmationPrompt)

    val unsupported = taskResult(
      action() to AgentActionResult(AgentActionStatus.UNSUPPORTED, "That is not supported.")
    ).toToolResult()
    assertEquals(DvexToolStatus.UNSUPPORTED, unsupported.status)
  }

  @Test
  fun emptyPlanIsAFailureWithoutInternalWording() {
    val result = DvexTaskResult(DvexTaskPlan(emptyList(), ""), emptyList()).toToolResult()
    assertEquals(DvexToolStatus.FAILED, result.status)
    assertFalse(result.spokenText.contains("Sir", ignoreCase = true))
  }

  @Test
  fun agentMessagesContainNoHonorificsOrVerificationJargon() {
    val results = listOf(
      taskResult(action() to verifiedOpen).toToolResult(),
      taskResult(action() to unverifiedOpen).toToolResult(),
      taskResult(action() to dispatchConfirmed).toToolResult(),
      taskResult(action() to AgentActionResult(AgentActionStatus.FAILURE, "That action couldn't be completed.")).toToolResult(),
      DvexTaskResult(DvexTaskPlan(emptyList(), ""), emptyList()).toToolResult()
    )
    results.forEach { result ->
      val text = result.message + " " + result.spokenText
      assertFalse("honorific leaked: $text", Regex("\\bSir\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
      assertFalse("internal status leaked: $text", text.contains("UNVERIFIED", ignoreCase = true))
      assertFalse("internal label leaked: $text", text.contains("(unverified)"))
    }
  }
}

package com.example.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D-VEX AGENT UI: contextual capability popup state.
 *
 * Locks the popup contract the agent layer will publish into: user-facing copy only,
 * honest access/permission states, bounded feed, and auto-dismissible completion.
 */
class AgentActivityControllerTest {

  @Before
  fun setUp() {
    AgentActivityController.clear()
  }

  @After
  fun tearDown() {
    AgentActivityController.clear()
  }

  @Test
  fun beginTracksActionWithUserFacingOpeningState() {
    AgentActivityController.begin(AgentCapability.YOUTUBE, AgentActivityStatus.OPENING)
    val activity = AgentActivityController.activities.value.first()
    assertEquals(AgentCapability.YOUTUBE, activity.capability)
    assertEquals("Opening YouTube…", activity.message)
    assertFalse(activity.isTerminal)
  }

  @Test
  fun searchingAndCompletedStatesMatchTheSpecifiedCopy() {
    val id = AgentActivityController.begin(AgentCapability.YOUTUBE, AgentActivityStatus.OPENING)
    AgentActivityController.update(id, AgentActivityStatus.SEARCHING, "Spider-Man")
    assertEquals("Searching \"Spider-Man\"…", AgentActivityController.activities.value.first().message)

    AgentActivityController.complete(id)
    val done = AgentActivityController.activities.value.first()
    assertEquals("Completed", done.message)
    assertTrue(done.isTerminal)
    assertTrue(done.autoDismiss)
  }

  @Test
  fun accessRequiredIsSurfacedAndNeverAutoDismissed() {
    AgentActivityController.requireAccess(AgentCapability.CAMERA)
    val activity = AgentActivityController.activities.value.first()
    assertEquals("Camera access required", activity.message)
    assertFalse(activity.autoDismiss)
  }

  @Test
  fun waitingForPermissionCopy() {
    AgentActivityController.requireAccess(AgentCapability.LOCATION, waitingForPermission = true)
    assertEquals("Waiting for permission…", AgentActivityController.activities.value.first().message)
  }

  @Test
  fun duplicateAccessPromptsAreNotStacked() {
    AgentActivityController.requireAccess(AgentCapability.CAMERA)
    AgentActivityController.requireAccess(AgentCapability.CAMERA)
    assertEquals(
      1,
      AgentActivityController.activities.value.count { it.capability == AgentCapability.CAMERA }
    )
  }

  @Test
  fun failureIsReportedHonestlyAndIsTerminal() {
    val id = AgentActivityController.begin(AgentCapability.WEATHER, AgentActivityStatus.RUNNING, "Getting weather…")
    assertEquals("Getting weather…", AgentActivityController.activities.value.first().message)
    AgentActivityController.fail(id, "Couldn't get the weather")
    val failed = AgentActivityController.activities.value.first()
    assertEquals("Couldn't get the weather", failed.message)
    assertTrue(failed.isTerminal)
  }

  @Test
  fun feedIsBoundedSoPopupsNeverFloodTheHud() {
    repeat(6) { AgentActivityController.begin(AgentCapability.DEVICE, AgentActivityStatus.RUNNING) }
    assertEquals(3, AgentActivityController.activities.value.size)
  }

  @Test
  fun completeInFlightMarksRunningActionDoneButLeavesAccessPromptsAlone() {
    AgentActivityController.begin(AgentCapability.DEVICE, AgentActivityStatus.RUNNING)
    AgentActivityController.requireAccess(AgentCapability.CAMERA)

    AgentActivityController.completeInFlight()

    val values = AgentActivityController.activities.value
    assertTrue(
      values.any {
        it.capability == AgentCapability.CAMERA && it.status == AgentActivityStatus.ACCESS_REQUIRED
      }
    )
    assertTrue(
      values.any {
        it.capability == AgentCapability.DEVICE && it.status == AgentActivityStatus.COMPLETED
      }
    )
  }

  @Test
  fun popupCopyNeverLeaksInternalToolOrPlanningData() {
    AgentActivityController.begin(AgentCapability.YOUTUBE, AgentActivityStatus.SEARCHING, "Spider-Man")
    val samples = AgentActivityController.activities.value.map { it.message } + listOf(
      "Opening YouTube…",
      "Opening YouTube: Spider-Man…",
      "Camera access required",
      "Waiting for permission…",
      "Working on it…",
      "Completed",
      "Couldn't complete that"
    )
    samples.forEach { text ->
      assertFalse(text.contains("{"))
      assertFalse(text.contains("tool"))
      assertFalse(text.contains("intent"))
      assertFalse(text.contains("Dvex"))
    }
  }
}

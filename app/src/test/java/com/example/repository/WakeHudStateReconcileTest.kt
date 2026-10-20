package com.example.repository

import com.example.model.DvexAssistantState
import com.example.voice.WakeWordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HUD / listening-state consistency rule (wake-word diagnosis): the repository
 * and HUD may only report wake-word listening as active while the wake engine is
 * genuinely running (Listening/Triggered). Every other engine state must
 * downgrade the label to Standby — never to a state that would strand the
 * existing re-arm/recovery paths (which all re-arm FROM Standby/Idle).
 */
class WakeHudStateReconcileTest {

  private val activeEngineStates = listOf<WakeWordState>(
    WakeWordState.Listening,
    WakeWordState.Triggered("D-VEX"),
  )

  private val inactiveEngineStates = listOf<WakeWordState>(
    WakeWordState.Disabled,
    WakeWordState.Standby,
    WakeWordState.Error("Offline wake engine failed to initialize"),
    WakeWordState.Error("Microphone permission required for wake word."),
    // Legacy backward-compat aliases: never a running engine either.
    WakeWordState.Inactive,
    WakeWordState.ListeningForTrigger,
    WakeWordState.TriggerDetected("D-VEX"),
  )

  @Test
  fun activeEngineKeepsTheWakeListeningLabel() {
    activeEngineStates.forEach { engine ->
      assertNull(
        "engine=$engine must keep the wake-listening label",
        reconcileWakeHudState(engine, DvexAssistantState.WakeWordListening),
      )
    }
  }

  @Test
  fun engineNotRunningNeverLeavesTheHudClaimingWakeListening() {
    inactiveEngineStates.forEach { engine ->
      assertEquals(
        "engine=$engine must downgrade the wake-listening label",
        DvexAssistantState.Standby,
        reconcileWakeHudState(engine, DvexAssistantState.WakeWordListening),
      )
    }
  }

  @Test
  fun otherHudStatesAreNeverTouchedByAnyEngineState() {
    val otherHudStates = listOf<DvexAssistantState>(
      DvexAssistantState.Idle,
      DvexAssistantState.Standby,
      DvexAssistantState.Listening,
      DvexAssistantState.Processing,
      DvexAssistantState.ExecutingAction("open_app"),
      DvexAssistantState.Speaking("hello"),
      DvexAssistantState.Error("Microphone permission required"),
    )
    (activeEngineStates + inactiveEngineStates).forEach { engine ->
      otherHudStates.forEach { hud ->
        assertNull(
          "engine=$engine hud=$hud must never be rewritten",
          reconcileWakeHudState(engine, hud),
        )
      }
    }
  }
}

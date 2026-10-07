package com.example.ui

import com.example.model.VoiceState
import com.example.ui.components.conversationCardTag
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIVE badge state machine for the conversation card (P5: the "LIVE" label must
 * represent the real mode it claims — never a decorative always-on tag).
 *
 *  - LIVE exactly while a voice/response turn is genuinely in flight;
 *  - STANDBY otherwise, including error/idle states;
 *  - deterministic and total over the existing VoiceState enum — no fake live mode.
 */
class ConversationCardTagTest {

  @Test
  fun isLiveOnlyWhileATurnIsGenuinelyInFlight() {
    assertEquals("LIVE", conversationCardTag(VoiceState.LISTENING))
    assertEquals("LIVE", conversationCardTag(VoiceState.PROCESSING))
    assertEquals("LIVE", conversationCardTag(VoiceState.SPEAKING))
    assertEquals("LIVE", conversationCardTag(VoiceState.EXECUTING_ACTION))
  }

  @Test
  fun isStandbyWhenNoTurnIsActive() {
    assertEquals("STANDBY", conversationCardTag(VoiceState.IDLE))
    assertEquals("STANDBY", conversationCardTag(VoiceState.STANDBY))
    assertEquals("STANDBY", conversationCardTag(VoiceState.ERROR))
  }

  @Test
  fun tagIsDeterministicAcrossRepeatedReads() {
    // Same state, same tag: the badge never flickers between values for one state.
    VoiceState.entries.forEach { state ->
      assertEquals(conversationCardTag(state), conversationCardTag(state))
    }
  }
}

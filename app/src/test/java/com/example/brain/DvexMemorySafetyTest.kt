package com.example.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secret-guard contract for the memory tiers (see looksLikeSensitiveFact in
 * DvexMemoryStore.kt).
 *
 * The router's remember-fact tool, the recall read path and the response-prompt
 * memory hint all filter through this one function, so these cases pin the two
 * properties that matter:
 *  - credentials never reach SharedPreferences or a model prompt, and
 *  - ordinary facts the user actually asks D-VEX to remember are never rejected.
 *
 * Pure JVM (no Android/Robolectric dependency), so it runs in any test task.
 */
class DvexMemorySafetyTest {

  @Test
  fun credentialShapedFactsAreRefused() {
    val secrets = listOf(
      "my password is tiger123",
      "the passcode is 482913",
      "otp 458291",
      "one-time code 918273",
      "card number 4111 1111 1111 1111",
      "123-45-6789",
      "api key AIzaSyExampleKeyValue1234567890",
      "atm pin 7134",
      "recovery code ABCD1234",
      "cvv 412"
    )
    for (secret in secrets) {
      assertTrue("must be treated as a secret: $secret", looksLikeSensitiveFact(secret))
    }
  }

  @Test
  fun ordinaryFactsAreStillMemorisable() {
    val facts = listOf(
      "I like red",
      "my favorite color is blue",
      "call Amma every Sunday morning",
      "I work in Coimbatore",
      "battery drains fast on weekends",
      "my name is Hari",
      "naalu manikku wake up pannu"
    )
    for (fact in facts) {
      assertFalse("must stay memorisable: $fact", looksLikeSensitiveFact(fact))
    }
  }

  @Test
  fun blankInputIsNeverASecret() {
    assertFalse(looksLikeSensitiveFact(""))
    assertFalse(looksLikeSensitiveFact("   "))
  }
}

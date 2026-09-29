package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * SpeechRecognizerManager session guard tests (recognizer lifecycle contract):
 *
 * - A second startListening() while a session is active is REFUSED — the recognizer
 *   is never destroyed-and-restarted and startListening() is never called repeatedly
 *   before onResults()/onError().
 * - onResults() and onError() are session terminals: the recognizer is destroyed and
 *   no stale session can linger.
 * - Only ONE SpeechRecognizer instance exists for the whole pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeechRecognizerSessionGuardTest {

  private lateinit var context: Context
  private lateinit var manager: SpeechRecognizerManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // startListening bails early without RECORD_AUDIO; grant it for the session tests.
    shadowOf(context as android.app.Application).grantPermissions(
      android.Manifest.permission.RECORD_AUDIO
    )
    manager = SpeechRecognizerManager(context)
  }

  @After
  fun tearDown() {
    manager.destroy()
  }

  private fun startSession(
    onResult: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    preferredLanguage: String? = null
  ) {
    manager.startListening(
      onResult = onResult,
      onError = onError,
      preferredLanguage = preferredLanguage
    )
    // All start work is posted to the main handler — flush it.
    shadowOf(android.os.Looper.getMainLooper()).idle()
  }

  private fun fireResults(text: String) {
    val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    assertNotNull("A recognizer must have been created", recognizer)
    val bundle = Bundle().apply {
      putStringArrayList(
        android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
        arrayListOf(text)
      )
    }
    shadowOf(recognizer).triggerOnResults(bundle)
    shadowOf(android.os.Looper.getMainLooper()).idle()
  }

  private fun fireError(error: Int) {
    val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    assertNotNull(recognizer)
    shadowOf(recognizer).triggerOnError(error)
    shadowOf(android.os.Looper.getMainLooper()).idle()
  }

  @Test
  fun `first session creates exactly one recognizer and starts listening`() {
    startSession()

    val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    assertNotNull(recognizer)
    val intent = shadowOf(recognizer).lastRecognizerIntent
    assertNotNull("startListening must have been called", intent)
    assertEquals(
      "Recognizer action must be RECOGNIZE_SPEECH",
      android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
      intent.action
    )
  }

  @Test
  fun `second startListening while session active is refused without new session`() {
    var errorCallbackCount = 0
    startSession()

    val before = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    val intentBefore = shadowOf(before).lastRecognizerIntent

    // Duplicate start while the first session is open: must be refused.
    manager.startListening(onResult = {}, onError = { errorCallbackCount++ })
    shadowOf(android.os.Looper.getMainLooper()).idle()

    val after = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    assertEquals(
      "Refusal must not create a second recognizer session",
      before,
      after
    )
    assertEquals(
      "Recognizer intent must be untouched by the refused start",
      intentBefore,
      shadowOf(after).lastRecognizerIntent
    )
    assertEquals("Duplicate start must surface SESSION_ACTIVE via onError", 1, errorCallbackCount)
    assertTrue(manager.isListening.value)
  }

  @Test
  fun `onResults destroys the recognizer session terminal`() {
    startSession(onResult = { /* terminal verified below */ })

    val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    fireResults("open youtube")

    assertTrue(
      "onResults is a session terminal: recognizer must be destroyed",
      shadowOf(recognizer).isDestroyed
    )
    assertFalse(manager.isListening.value)
  }

  @Test
  fun `onError destroys the recognizer session terminal`() {
    startSession(onError = { /* terminal verified below */ })

    val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    fireError(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

    assertTrue(
      "onError is a session terminal: recognizer must be destroyed",
      shadowOf(recognizer).isDestroyed
    )
    assertFalse(manager.isListening.value)
  }

  @Test
  fun `new session can start after previous session ended`() {
    startSession()
    fireResults("first command")

    startSession(onResult = { })

    val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    val intent = shadowOf(recognizer).lastRecognizerIntent
    assertNotNull("A fresh session must start listening again", intent)
  }

  @Test
  fun `watchdog timeout destroys recognizer and reports EMPTY_SPEECH`() {
    var error: String? = null
    startSession(onError = { error = it })

    val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    // 8-second watchdog (posted at start) plus margin.
    shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(9_000))

    assertEquals("Watchdog must report EMPTY_SPEECH", "EMPTY_SPEECH", error)
    assertTrue(
      "Watchdog is a session terminal: recognizer must be destroyed",
      shadowOf(recognizer).isDestroyed
    )
    assertFalse(manager.isListening.value)
  }

  @Test
  fun `session start posts intent with correct language extras`() {
    startSession(preferredLanguage = "tamil")

    val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    val intent: Intent? = shadowOf(recognizer).lastRecognizerIntent
    assertNotNull(intent)
    assertEquals("ta-IN", intent!!.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE))

    // Language preference is always provided alongside the primary locale.
    val extras = intent.extras!!
    assertTrue(extras.containsKey(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
  }
}

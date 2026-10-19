package com.example.voice

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * WAKE-INIT STATE HONESTY (wake-word diagnosis step — observability/state only,
 * NO detection tuning: threshold, tokenizer, model assets, capture and pipeline
 * are all untouched by the behaviour these tests pin).
 *
 * Covers:
 *  - genuine init failures inside the detector (validator/mic exceptions) are
 *    reported through onError and never crash or leave a phantom "running";
 *  - a detector that throws synchronously out of start() leaves the MANAGER in
 *    an accurate Error state (never Listening-with-nothing-running);
 *  - retry: start() carries no precondition on Error, so a valid transition back
 *    to Listening (and to a delivered trigger) always exists after a failure;
 *  - failure/retry logs are rate-limited and carry the reason + attempt numbers,
 *    and contain state/reason text only (never audio or transcripts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WakeInitFailureHonestyTest {

  private lateinit var context: Context
  private lateinit var fake: FakeDetector
  private lateinit var manager: WakeWordManager
  private val triggers = mutableListOf<String>()

  private var engineCreations = 0
  private var micCreations = 0
  private var lastEngine: FakeEngine? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    ShadowLog.clear()
    fake = FakeDetector()
    manager = WakeWordManager(context, fake)
    manager.setOnTriggerListener { phrase -> triggers.add(phrase) }
    engineCreations = 0
    micCreations = 0
    lastEngine = null
  }

  private fun wakeLogs(): List<String> =
    ShadowLog.getLogsForTag("[D-VEX][WAKE]").map { it.msg }

  private fun failureLogs(): List<String> =
    wakeLogs().filter { it.contains("Wake init failed") }

  private fun retryLogs(): List<String> =
    wakeLogs().filter { it.contains("Wake init retry attempt") }

  // --- detector level: genuine init failures must reach onError, never crash ---

  @Test
  fun validatorExceptionDuringInitDispatchesErrorInsteadOfCrashing() {
    val detector = newDetector(
      micFactory = MicFactory { micCreations++; FakeMic() },
      phraseValidator = { throw IllegalStateException("vocab asset corrupt") },
    )
    val errors = mutableListOf<String>()

    detector.start(onTrigger = { }, onError = { errors.add(it) })

    assertEquals("exactly one error dispatch", 1, errors.size)
    assertTrue(
      "error must carry the failure reason: $errors",
      errors.first().contains("vocab asset corrupt"),
    )
    assertFalse("detector must not report running after a failed init", detector.isRunning())
    assertEquals("no engine may be constructed when validation fails", 0, engineCreations)
    assertEquals("no microphone may be opened when validation fails", 0, micCreations)
    detector.stop()
  }

  @Test
  fun microphoneCreationExceptionDuringInitClosesTheEngineAndDispatchesError() {
    val detector = newDetector(
      micFactory = MicFactory { micCreations++; throw IllegalStateException("AudioRecord unavailable") },
      phraseValidator = { validResult("D-VEX") },
    )
    val errors = mutableListOf<String>()

    detector.start(onTrigger = { }, onError = { errors.add(it) })

    assertEquals("exactly one error dispatch", 1, errors.size)
    assertTrue(
      "error must describe the microphone failure: $errors",
      errors.first().contains("Microphone capture could not be created"),
    )
    assertTrue(
      "error must carry the failure reason: $errors",
      errors.first().contains("AudioRecord unavailable"),
    )
    assertFalse("detector must not report running after a failed init", detector.isRunning())
    assertEquals("exactly one engine was constructed", 1, engineCreations)
    assertTrue("the engine must be closed when the microphone cannot be created", lastEngine!!.closed)
    detector.stop()
  }

  // --- manager level: honest state + retry + rate-limited observability ---

  @Test
  fun synchronousDetectorStartCrashSurfacesAsErrorStateNotSilence() {
    fake.throwOnStart = IllegalStateException("native sherpa library missing")

    manager.start() // must never crash the caller

    val state = manager.state.value
    assertTrue("state must be Error after a synchronous init crash, was $state", state is WakeWordState.Error)
    assertTrue(
      "error must carry the failure reason",
      (state as WakeWordState.Error).message.contains("native sherpa library missing"),
    )
    assertFalse("the detector must not report running", fake.running)
    assertTrue(
      "the failure reason must be logged",
      failureLogs().any { it.contains("native sherpa library missing") },
    )
  }

  @Test
  fun initFailureThenRearmTransitionsBackToListeningAndStillDeliversTriggers() {
    manager.start()
    fake.errorCallbacks.last().invoke("Offline wake engine failed to initialize")
    assertEquals(
      WakeWordState.Error("Offline wake engine failed to initialize"),
      manager.state.value,
    )

    // RETRY: start() must carry no precondition on Error — the valid transition
    // back to the active/READY state always exists after a genuine failure.
    manager.start()
    assertEquals("re-arm after failure must return to Listening", WakeWordState.Listening, manager.state.value)

    // ...and the re-armed manager must still deliver detections.
    fake.triggerCallbacks.last().invoke("D-VEX")
    assertEquals(WakeWordState.Triggered("D-VEX"), manager.state.value)
    assertEquals(listOf("D-VEX"), triggers)
  }

  @Test
  fun initFailureAndRetryLogsAreRateLimitedAndCarryReasonAndAttempts() {
    var nowMs = 1_000_000L
    val rateLimitedManager = WakeWordManager(context, fake, 5_000L) { nowMs }

    rateLimitedManager.start()
    fake.errorCallbacks[0].invoke("boom A")          // first failure in window -> logged
    rateLimitedManager.start()                      // retry from Error -> logged
    fake.errorCallbacks[1].invoke("boom B")          // same window -> suppressed
    nowMs += 5_000
    fake.errorCallbacks[1].invoke("boom C")          // new window -> logged (attempt gap visible)
    assertEquals(WakeWordState.Error("boom C"), rateLimitedManager.state.value)
    rateLimitedManager.start()                      // retry after the window -> logged

    val failures = failureLogs()
    assertEquals(
      "three failures must yield two log lines (one rate-limited): $failures",
      2,
      failures.size,
    )
    assertTrue("first failure must carry attempt + reason: ${failures[0]}",
      failures[0].contains("attempt #1") && failures[0].contains("boom A"))
    assertTrue("later failure must carry attempt + reason: ${failures[1]}",
      failures[1].contains("attempt #2") && failures[1].contains("boom C"))
    assertTrue("a rate-limited reason must not be logged", failures.none { it.contains("boom B") })

    val retries = retryLogs()
    assertEquals("one retry logged in-window + one after the window: $retries", 2, retries.size)
    assertTrue("retry must carry the previous failure reason: ${retries[0]}", retries[0].contains("boom A"))
    assertTrue("retry must carry the previous failure reason: ${retries[1]}", retries[1].contains("boom C"))

    assertEquals("retry from Error must still re-arm", WakeWordState.Listening, rateLimitedManager.state.value)
  }

  // --- helpers ---------------------------------------------------------------

  private fun validResult(phrase: String): WakePhraseTokenizer.Result.Valid =
    WakePhraseTokenizer.Result.Valid(
      phrase = phrase,
      normalized = WakePhraseTokenizer.normalize(phrase),
      keyword = "▁ D - VE X",
    )

  private fun newDetector(
    micFactory: MicFactory,
    phraseValidator: (String) -> WakePhraseTokenizer.Result,
  ): SherpaOnnxWakeWordDetector =
    SherpaOnnxWakeWordDetector(
      appContext = context,
      initialPhrase = "D-VEX",
      micFactory = micFactory,
      engineFactory = EngineFactory { tokens ->
        engineCreations++
        FakeEngine().also { lastEngine = it; it.keywordTokens = tokens }
      },
      phraseValidator = phraseValidator,
      hasMicPermission = { true },
      callbackDispatcher = { runnable -> runnable.run() },
    )

  /** Minimal detector implementing the full capability surface, optionally throwing out of start(). */
  private class FakeDetector : WakeWordDetector, WakeWordCapability {
    override val name: String = "fake-detector"

    val triggerCallbacks = mutableListOf<(String) -> Unit>()
    val errorCallbacks = mutableListOf<(String) -> Unit>()
    var running = false
    var throwOnStart: Throwable? = null
    var lastKeyword: String? = null

    override fun start(onTrigger: (String) -> Unit, onError: (String) -> Unit) {
      throwOnStart?.let { throw it }
      triggerCallbacks.add(onTrigger)
      errorCallbacks.add(onError)
      running = true
    }

    override fun stop() {
      running = false
    }

    override fun isRunning(): Boolean = running

    override fun setKeyword(keyword: String): Boolean {
      lastKeyword = keyword
      return true
    }

    override fun setAudioSuppressed(suppressed: Boolean) {}

    override fun setRecentTtsUtterance(text: String) {}
  }

  private class FakeMic : MicCapture {
    override fun start(): Boolean = true
    override fun read(out: FloatArray, offset: Int, count: Int): Int = -1
    override fun stopAndRelease() {}
  }

  private class FakeEngine : KeywordEngine {
    @Volatile var closed = false
    var keywordTokens: String = ""

    override fun accept(samples: FloatArray, sampleRate: Int) {}
    override fun poll(): String? = null
    override fun close() {
      closed = true
    }
  }
}

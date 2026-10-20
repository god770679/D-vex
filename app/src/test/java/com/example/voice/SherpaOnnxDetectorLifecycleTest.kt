package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lifecycle contract of [SherpaOnnxWakeWordDetector] with the microphone and
 * engine seams injected (the production AudioRecord/sherpa implementations
 * require a physical device; the state machine around them is pure JVM logic).
 *
 * Covers: idempotent start (no duplicate microphone capture), stop releasing
 * every resource and terminating the capture loop, microphone release BEFORE
 * the wake callback, initialization failures reported through onError without
 * any fallback capture, safe keyword reconfiguration while running, and TTS
 * echo suppression dropping detections without dying.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SherpaOnnxDetectorLifecycleTest {

  private lateinit var context: Context
  private val pieces: List<WakePhraseTokenizer.Piece> by lazy { loadPieces() }
  private val decoderTokens: Set<String> by lazy { loadDecoder() }

  private var micCreations = 0
  private var engineCreations = 0
  private var lastMic: FakeMic? = null
  private var lastEngine: FakeEngine? = null
  private var engineShouldFail = false
  private val triggered = mutableListOf<String>()
  private val errors = mutableListOf<String>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    micCreations = 0
    engineCreations = 0
    lastMic = null
    lastEngine = null
    engineShouldFail = false
    triggered.clear()
    errors.clear()
  }

  private fun readAsset(relative: String): String {
    val candidates = listOf(File(".", relative), File("app", relative))
    val file = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("Could not locate $relative from ${File(".").absolutePath}")
    return file.readText()
  }

  private fun loadPieces(): List<WakePhraseTokenizer.Piece> =
    WakePhraseTokenizer.parseSpmVocab(readAsset("src/main/assets/kws/spm_vocab.txt").lineSequence())

  private fun loadDecoder(): Set<String> =
    WakePhraseTokenizer.parseDecoderTokens(readAsset("src/main/assets/kws/tokens.txt").lineSequence())

  private fun newDetector(phrase: String = "D-VEX"): SherpaOnnxWakeWordDetector =
    SherpaOnnxWakeWordDetector(
      appContext = context,
      initialPhrase = phrase,
      micFactory = MicFactory {
        micCreations++
        FakeMic().also { lastMic = it }
      },
      engineFactory = EngineFactory { tokens ->
        if (engineShouldFail) throw IllegalStateException("model file missing")
        engineCreations++
        FakeEngine().also { lastEngine = it; it.keywordTokens = tokens }
      },
      phraseValidator = { phraseToCheck -> WakePhraseTokenizer.tokenize(phraseToCheck, pieces, decoderTokens) },
      hasMicPermission = { true },
      callbackDispatcher = { runnable -> runnable.run() },
    )

  private fun start(detector: SherpaOnnxWakeWordDetector) {
    detector.start(
      onTrigger = { phrase -> synchronized(triggered) { triggered.add(phrase) } },
      onError = { message -> synchronized(errors) { errors.add(message) } },
    )
  }

  private fun await(timeoutMs: Long = 4000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(10)
    }
    assertTrue("condition not met within ${timeoutMs}ms", condition())
  }

  @Test
  fun repeatedStartCreatesExactlyOneMicrophoneCapture() {
    val detector = newDetector()
    start(detector)
    await { detector.isRunning() }
    start(detector) // idempotent
    Thread.sleep(100)
    assertEquals("exactly one engine construction", 1, engineCreations)
    assertEquals("exactly one microphone capture", 1, micCreations)
    assertEquals("exactly one start() on the microphone", 1, lastMic?.startCalls)
    detector.stop()
  }

  @Test
  fun stopReleasesResourcesAndTerminatesTheCaptureLoop() {
    val detector = newDetector()
    start(detector)
    await { detector.isRunning() }
    val mic = lastMic!!
    val engine = lastEngine!!
    await { mic.readCalls > 0 }

    detector.stop()

    assertTrue("microphone released on stop", mic.released)
    assertTrue("native engine closed on stop", engine.closed)
    assertFalse("detector must not report running after stop", detector.isRunning())
    val readsAtStop = mic.readCalls
    Thread.sleep(150)
    assertEquals("no reads after stop — capture loop is dead", readsAtStop, mic.readCalls)
  }

  @Test
  fun microphoneIsReleasedBeforeTheWakeCallbackFires() {
    // The engine is born with a detection queued, so the very first processed
    // chunk triggers — no race between the test and the capture loop.
    val detector = SherpaOnnxWakeWordDetector(
      appContext = context,
      initialPhrase = "D-VEX",
      micFactory = MicFactory { micCreations++; FakeMic().also { lastMic = it } },
      engineFactory = EngineFactory { tokens ->
        engineCreations++
        FakeEngine().also { lastEngine = it; it.keywordTokens = tokens; it.detections += "▁ D - VE X" }
      },
      phraseValidator = { phraseToCheck -> WakePhraseTokenizer.tokenize(phraseToCheck, pieces, decoderTokens) },
      hasMicPermission = { true },
      callbackDispatcher = { runnable -> runnable.run() },
    )
    detector.start(
      onTrigger = { _ ->
        // Record microphone state at the exact moment the pipeline is notified.
        synchronized(triggered) { triggered.add(if (lastMic?.released == true) "released" else "held") }
      },
      onError = { message -> synchronized(errors) { errors.add(message) } },
    )
    await(5000) { synchronized(triggered) { triggered.isNotEmpty() } }

    val observed = synchronized(triggered) { triggered.toList() }
    assertEquals(
      "the microphone must be released before the wake trigger reaches the pipeline",
      listOf("released"),
      observed,
    )
    assertTrue("engine closed after trigger", lastEngine!!.closed)
    assertFalse("detector stops itself after a trigger", detector.isRunning())
    assertEquals("no errors during normal trigger", emptyList<String>(), errors)
    detector.stop()
  }

  @Test
  fun engineInitializationFailureReportsErrorAndNeverFallsBack() {
    engineShouldFail = true
    val detector = newDetector()
    start(detector)
    await { synchronized(errors) { errors.isNotEmpty() } }
    val reported = synchronized(errors) { errors.toList() }
    assertTrue("error must describe the engine failure: $reported", reported.first().contains("engine failed"))
    assertFalse("detector must not report running after init failure", detector.isRunning())
    assertEquals("no microphone may be opened when the engine fails", 0, micCreations)
    assertEquals("no silent fallback detector may engage", 0, synchronized(triggered) { triggered.size })
    detector.stop()
  }

  @Test
  fun microphoneFailureClosesTheEngineAndReportsError() {
    val detector = SherpaOnnxWakeWordDetector(
      appContext = context,
      initialPhrase = "D-VEX",
      micFactory = MicFactory { micCreations++; FailingMic() },
      engineFactory = EngineFactory { tokens ->
        engineCreations++
        FakeEngine().also { lastEngine = it; it.keywordTokens = tokens }
      },
      phraseValidator = { phraseToCheck -> WakePhraseTokenizer.tokenize(phraseToCheck, pieces, decoderTokens) },
      hasMicPermission = { true },
      callbackDispatcher = { runnable -> runnable.run() },
    )
    start(detector)
    await { synchronized(errors) { errors.isNotEmpty() } }
    val reported = synchronized(errors) { errors.toList() }
    assertTrue("error must describe the microphone failure: $reported", reported.first().contains("Microphone"))
    assertFalse(detector.isRunning())
    assertEquals("exactly one engine was constructed", 1, engineCreations)
    assertTrue("engine must be closed when the mic cannot open", lastEngine!!.closed)
    detector.stop()
  }

  @Test
  fun invalidKeywordWhileRunningIsRejectedWithoutDisturbingTheCapture() {
    val detector = newDetector()
    start(detector)
    await { detector.isRunning() }
    Thread.sleep(80)

    val accepted = detector.setKeyword("CAFÉ")
    Thread.sleep(120)

    assertFalse("unsupported phrase must be rejected", accepted)
    assertEquals("no engine rebuild on rejection", 1, engineCreations)
    assertEquals("no microphone rebuild on rejection", 1, micCreations)
    assertTrue("capture must survive a rejected phrase", detector.isRunning())
    assertEquals("no error dispatch for a rejected phrase", emptyList<String>(), errors)
    detector.stop()
  }

  @Test
  fun validKeywordWhileRunningRebuildsExactlyOneCapture() {
    val detector = newDetector()
    start(detector)
    await { detector.isRunning() }
    val firstMic = lastMic!!
    val firstEngine = lastEngine!!

    val accepted = detector.setKeyword("Jarvis")
    await { engineCreations == 2 && micCreations == 2 }

    assertTrue("valid phrase must be accepted", accepted)
    assertEquals("exactly one engine rebuild", 2, engineCreations)
    assertEquals("exactly one microphone rebuild", 2, micCreations)
    assertTrue("old microphone released during reconfigure", firstMic.released)
    assertTrue("old engine closed during reconfigure", firstEngine.closed)
    assertTrue("detector must keep running after reconfigure", detector.isRunning())
    // The rebuilt engine must receive the tokenized NEW phrase, not the old one.
    val expected = WakePhraseTokenizer.tokenize("Jarvis", pieces, decoderTokens)
    assertTrue("'Jarvis' must tokenize successfully", expected is WakePhraseTokenizer.Result.Valid)
    assertEquals((expected as WakePhraseTokenizer.Result.Valid).keyword, lastEngine!!.keywordTokens)
    detector.stop()
  }

  @Test
  fun keywordAppliedWhileIdleTakesEffectOnNextStart() {
    val detector = newDetector()
    assertTrue(detector.setKeyword("Friday"))
    start(detector)
    await { detector.isRunning() }
    assertEquals("▁F RI DAY", lastEngine!!.keywordTokens)
    detector.stop()
  }

  @Test
  fun echoSuppressionDropsDetectionButKeepsListening() {
    val detector = newDetector()
    detector.setAudioSuppressed(true)
    start(detector)
    await { detector.isRunning() }
    lastEngine?.detections?.add("▁ D - VE X")

    Thread.sleep(250)
    assertEquals("suppressed detection must not trigger", emptyList<String>(), synchronized(triggered) { triggered.toList() })
    assertTrue("detector keeps listening while suppressed", detector.isRunning())
    detector.stop()
  }

  @Test
  fun stopIsIdempotent() {
    val detector = newDetector()
    start(detector)
    await { detector.isRunning() }
    detector.stop()
    detector.stop()
    assertFalse(detector.isRunning())
    assertTrue(lastMic!!.released)
    assertTrue(lastEngine!!.closed)
  }

  /**
   * REGRESSION (Phase-11 proven crash cause): stop() used to close the native
   * engine BEFORE joining the capture thread, so a concurrent accept()/poll()
   * could touch released native state — a use-after-free and the intermittent
   * native-crash class. stop() must wait for the thread; close happens only
   * after its last native call.
   */
  @Test
  fun stopNeverClosesTheEngineWhileCaptureThreadIsInsideAccept() {
    val detector = newBlockingDetector()
    start(detector)
    await { detector.isRunning() }
    val blockingEngine = lastEngine!!
    assertTrue("capture thread must be inside accept()", blockingEngine.acceptStarted.await(3, TimeUnit.SECONDS))

    val stopThread = Thread({ detector.stop() }, "test-stop").apply { start() }
    Thread.sleep(400) // stop() must be parked in join() while accept() executes

    assertFalse("engine must NOT be closed while accept() is executing", blockingEngine.closed)
    assertTrue("stop() must be waiting for the capture thread", stopThread.isAlive)

    blockingEngine.acceptRelease.countDown()
    stopThread.join(8_000)
    assertFalse("stop() must complete once the capture thread exits", stopThread.isAlive)
    assertTrue("engine closed after thread exit", blockingEngine.closed)
    assertFalse("engine must never be closed during accept()", blockingEngine.closedDuringAccept)
    assertTrue("microphone released", lastMic!!.released)
    assertFalse("detector stopped", detector.isRunning())
  }

  /** Same protocol for runtime reconfiguration (setKeyword used to have the same race). */
  @Test
  fun reconfigureWaitsForCaptureThreadBeforeClosingTheOldEngine() {
    val detector = newBlockingDetector()
    start(detector)
    await { detector.isRunning() }
    val oldEngine = lastEngine!!
    assertTrue(oldEngine.acceptStarted.await(3, TimeUnit.SECONDS))

    var accepted: Boolean? = null
    val reconfigThread = Thread({ accepted = detector.setKeyword("Jarvis") }, "test-reconfig").apply { start() }
    Thread.sleep(400)

    assertFalse("old engine must NOT be closed while accept() is executing", oldEngine.closed)
    assertTrue("setKeyword() must be waiting for the capture thread", reconfigThread.isAlive)

    oldEngine.acceptRelease.countDown()
    reconfigThread.join(8_000)
    assertFalse("setKeyword() must complete", reconfigThread.isAlive)
    assertEquals("valid phrase accepted after safe swap", true, accepted)
    assertEquals("exactly one engine rebuild", 2, engineCreations)
    assertEquals("exactly one microphone rebuild", 2, micCreations)
    assertTrue("old engine closed after thread exit", oldEngine.closed)
    assertTrue("detector keeps running with the new phrase", detector.isRunning())
    detector.stop()
  }

  /**
   * B-verification: quiet speech must never be discarded or amplified — a
   * -60 dBFS tone (amplitude 32/32768) must reach the engine at exactly its
   * captured level (no hard gate, no arbitrary gain).
   */
  @Test
  fun lowAmplitudeFramesReachTheEngineWithoutGateOrGain() {
    val detector = SherpaOnnxWakeWordDetector(
      appContext = context,
      initialPhrase = "D-VEX",
      micFactory = MicFactory {
        micCreations++
        FakeMic().also { it.amplitude = 32 } // 32/32768 ≈ 0.001 → ≈ -60 dBFS
      },
      engineFactory = EngineFactory { tokens ->
        engineCreations++
        FakeEngine().also { lastEngine = it; it.keywordTokens = tokens }
      },
      phraseValidator = { phraseToCheck -> WakePhraseTokenizer.tokenize(phraseToCheck, pieces, decoderTokens) },
      hasMicPermission = { true },
      callbackDispatcher = { runnable -> runnable.run() },
    )
    start(detector)
    await { detector.isRunning() }
    await { (lastEngine?.accepts ?: 0) > 0 }

    val engine = lastEngine!!
    assertTrue("frames must actually reach the engine", engine.accepts > 0)
    assertTrue("quiet frames must not be zeroed by a gate (max=${engine.lastMaxAbs})", engine.lastMaxAbs > 0f)
    val captured = 32f / 32768f
    assertTrue(
      "no gain may be applied (max=${engine.lastMaxAbs}, expected=$captured)",
      engine.lastMaxAbs <= captured + 1e-6f,
    )
    detector.stop()
  }

  /** Detector wired to an engine whose first accept() blocks until released. */
  private fun newBlockingDetector(): SherpaOnnxWakeWordDetector =
    SherpaOnnxWakeWordDetector(
      appContext = context,
      initialPhrase = "D-VEX",
      micFactory = MicFactory { micCreations++; FakeMic().also { lastMic = it } },
      engineFactory = EngineFactory { tokens ->
        engineCreations++
        FakeEngine().also {
          lastEngine = it
          it.keywordTokens = tokens
          it.blockAccept = engineCreations == 1 // only the FIRST engine blocks
        }
      },
      phraseValidator = { phraseToCheck -> WakePhraseTokenizer.tokenize(phraseToCheck, pieces, decoderTokens) },
      hasMicPermission = { true },
      callbackDispatcher = { runnable -> runnable.run() },
    )

  // --- fakes -----------------------------------------------------------------

  private class FakeMic : MicCapture {
    @Volatile var released = false
    @Volatile var startCalls = 0
    @Volatile var readCalls = 0

    /** When non-zero, reads emit a constant DC level at this PCM amplitude. */
    @Volatile var amplitude: Short = 0

    override fun start(): Boolean {
      startCalls++
      return true
    }

    override fun read(out: FloatArray, offset: Int, count: Int): Int {
      if (released) return -1
      readCalls++
      try {
        Thread.sleep(4)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return -1
      }
      if (released) return -1
      val level = amplitude / 32768.0f
      for (i in 0 until count) out[offset + i] = level
      return count
    }

    override fun stopAndRelease() {
      released = true
    }
  }

  private class FailingMic : MicCapture {
    override fun start(): Boolean = false
    override fun read(out: FloatArray, offset: Int, count: Int): Int = -1
    override fun stopAndRelease() {}
  }

  private class FakeEngine : KeywordEngine {
    @Volatile var closed = false
    @Volatile var accepts = 0
    var keywordTokens: String = ""
    val detections: MutableList<String> = mutableListOf()

    /** When true, the first accept() blocks until [acceptRelease] (race harness). */
    @Volatile var blockAccept = false
    val acceptStarted = CountDownLatch(1)
    val acceptRelease = CountDownLatch(1)

    /** Set if close() ever ran while accept() was on the stack — must stay false. */
    @Volatile var closedDuringAccept = false

    /** Peak absolute sample level observed by the engine (amplitude evidence). */
    @Volatile var lastMaxAbs = 0f

    override fun accept(samples: FloatArray, sampleRate: Int) {
      accepts++
      for (s in samples) {
        val a = if (s >= 0) s else -s
        if (a > lastMaxAbs) lastMaxAbs = a
      }
      if (blockAccept) {
        acceptStarted.countDown()
        try {
          acceptRelease.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
        }
        if (closed) closedDuringAccept = true
      }
    }

    override fun poll(): String? = synchronized(detections) {
      if (detections.isEmpty()) null else detections.removeFirst()
    }

    override fun close() {
      closed = true
    }
  }
}

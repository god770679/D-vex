package com.example.brain

import com.example.ai.AiEngine
import com.example.ai.GeminiEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the V2 LLM-driven response layer.
 *
 * Covers:
 *  - Prompt construction: carries real tool result data, the user's verbatim
 *    utterance, and the memory hint; forbids fabrication.
 *  - LLM reply passthrough when the engine succeeds.
 *  - Deterministic fallback when the engine throws, times out, or is blank.
 *  - Repeat-request handling replays the last response WITHOUT calling the LLM.
 *  - Anti-fabrication contract: fallbacks pass through the tool's real data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DvexResponseGeneratorLlmTest {

  /** Records the prompt it was given and returns a scripted reply. */
  private class FakeEngine(
    private val reply: String? = "Sure thing, Sir.",
    private val throwOnCall: Boolean = false,
    private val delayMs: Long = 0
  ) : AiEngine {
    var prompts: MutableList<String> = mutableListOf()

    override suspend fun generate(prompt: String): String? {
      prompts.add(prompt)
      if (throwOnCall) throw java.io.IOException("engine exploded")
      if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
      return reply
    }
  }

  private fun successResult(
    toolName: String = "send_whatsapp",
    message: String = "Message sent to Amma.",
    spokenText: String = message
  ) = DvexToolResult(DvexToolStatus.SUCCESS, toolName, message, spokenText)

  // ---------------------------------------------------------------------
  // Prompt construction
  // ---------------------------------------------------------------------

  @Test
  fun promptContainsRealToolResultMessage() = runTest {
    val engine = FakeEngine()
    val gen = DvexResponseGenerator(engine)
    val result = successResult(message = "Message sent to Amma.")

    gen.generateResponse(
      intent = DvexIntent.SendMessage("Amma", "I am coming", isWhatsApp = true),
      toolResult = result,
      language = DetectedLanguage.TANGLISH,
      userInput = "Amma ku WhatsApp la 'I am coming' anupu",
      tone = EstimatedTone.NEUTRAL
    )

    val prompt = engine.prompts.single()
    assertTrue("prompt must carry the real tool message", prompt.contains("Message sent to Amma."))
    assertTrue("prompt must carry the verbatim user utterance", prompt.contains("Amma ku WhatsApp la"))
    assertTrue("prompt must carry the anti-fabrication rule", prompt.contains("NEVER invent"))
  }

  @Test
  fun promptContainsMemoryHintAndRealWeatherData() = runTest {
    val engine = FakeEngine()
    val gen = DvexResponseGenerator(engine)
    val weather = DvexToolResult(
      status = DvexToolStatus.SUCCESS,
      toolName = "get_weather",
      message = "Chennai: 31°C, light rain, high 33 low 27",
      spokenText = "Chennai: 31°C, light rain, high 33 low 27"
    )

    gen.generateResponse(
      intent = DvexIntent.GetWeather(location = "Chennai"),
      toolResult = weather,
      language = DetectedLanguage.ENGLISH,
      userInput = "weather epdi irukku",
      tone = EstimatedTone.NEUTRAL,
      memoryHint = "likes red; name is Hari"
    )

    val prompt = engine.prompts.single()
    assertTrue("prompt must carry REAL weather numbers", prompt.contains("31°C"))
    assertTrue("prompt must carry memory hint", prompt.contains("likes red"))
    assertTrue("prompt must forbid invented numbers", prompt.contains("NEVER invent or change numbers"))
  }

  @Test
  fun promptMirrorsTamilScriptUserInput() = runTest {
    val engine = FakeEngine()
    val gen = DvexResponseGenerator(engine)
    gen.generateResponse(
      intent = DvexIntent.GetTime,
      toolResult = successResult(toolName = "get_time", message = "The time is 10:15 PM"),
      language = DetectedLanguage.TAMIL,
      userInput = "இப்போ எத்தணி ஆச்சு",
      tone = EstimatedTone.NEUTRAL
    )
    val prompt = engine.prompts.single()
    assertTrue(
      "Tamil utterance must be embedded verbatim so the model mirrors the script",
      prompt.contains("இப்போ எத்தணி ஆச்சு")
    )
  }

  // ---------------------------------------------------------------------
  // LLM passthrough
  // ---------------------------------------------------------------------

  @Test
  fun llmReplyIsReturnedVerbatimWhenEngineSucceeds() = runTest {
    val engine = FakeEngine(reply = "Anuppiyachu, Sir. Two minutes la reach panren nu sollu.")
    val gen = DvexResponseGenerator(engine)

    val response = gen.generateResponse(
      intent = DvexIntent.SendMessage("Amma", "I am coming", isWhatsApp = true),
      toolResult = successResult(),
      language = DetectedLanguage.TANGLISH,
      userInput = "Amma ku message anupu",
      tone = EstimatedTone.NEUTRAL
    )

    assertEquals("Anuppiyachu, Sir. Two minutes la reach panren nu sollu.", response)
  }

  // ---------------------------------------------------------------------
  // Fallback behavior
  // ---------------------------------------------------------------------

  @Test
  fun engineThrowingFallsBackToSafeReply() = runTest {
    val engine = FakeEngine(throwOnCall = true)
    val gen = DvexResponseGenerator(engine)
    val response = gen.generateResponse(
      intent = DvexIntent.OpenApp("YouTube"),
      toolResult = DvexToolResult(DvexToolStatus.SUCCESS, "open_app", "Opening YouTube.", "Opening YouTube."),
      language = DetectedLanguage.ENGLISH,
      userInput = "open YouTube"
    )
    assertEquals("Opening YouTube.", response)
  }

  @Test
  fun engineBlankReplyFallsBackToSafeReply() = runTest {
    val engine = FakeEngine(reply = "   ")
    val gen = DvexResponseGenerator(engine)
    val response = gen.generateResponse(
      intent = DvexIntent.GoHome,
      toolResult = DvexToolResult(DvexToolStatus.SUCCESS, "go_home", "", ""),
      language = DetectedLanguage.ENGLISH,
      userInput = "go home"
    )
    assertEquals(DvexResponseGenerator.FALLBACK_OK, response)
  }

  @Test
  fun engineTimeoutFallsBackWithinHardDeadline() = runTest {
    // The engine hangs well past the generator's hard cap. D-VEX must still bail
    // out at that cap (never hang forever) and speak the honest fallback.
    // The cap is now 25s, not 8s: a cold Gemini request really did take 7.1s, so
    // 8s cancelled healthy replies. The no-hang guarantee is unchanged.
    val cap = DvexResponseGenerator.LLM_TIMEOUT_MS
    val engine = FakeEngine(reply = "late", delayMs = cap + 5_000)
    val gen = DvexResponseGenerator(engine)
    val start = testScheduler.currentTime
    val response = gen.generateResponse(
      intent = DvexIntent.GoHome,
      toolResult = DvexToolResult(DvexToolStatus.SUCCESS, "go_home", "", ""),
      language = DetectedLanguage.ENGLISH,
      userInput = "go home"
    )
    val elapsed = testScheduler.currentTime - start
    assertEquals(DvexResponseGenerator.FALLBACK_OK, response)
    assertTrue(
      "must bail out at the ${cap}ms cap, not wait ${cap + 5_000}ms (took ${elapsed}ms)",
      elapsed < cap + 5_000
    )
  }

  @Test
  fun noEngineUsesDeterministicFallback() = runTest {
    val gen = DvexResponseGenerator(aiEngine = null)
    val response = gen.generateResponse(
      intent = DvexIntent.GoHome,
      toolResult = DvexToolResult(DvexToolStatus.SUCCESS, "go_home", "", ""),
      language = DetectedLanguage.ENGLISH
    )
    assertEquals(DvexResponseGenerator.FALLBACK_OK, response)
  }

  @Test
  fun failedToolStatusStillProducesHonestFallback() = runTest {
    val engine = FakeEngine(reply = null)
    val gen = DvexResponseGenerator(engine)
    val response = gen.generateResponse(
      intent = DvexIntent.OpenApp("FakeGame"),
      toolResult = DvexToolResult(
        DvexToolStatus.NOT_FOUND, "open_app",
        message = "App not found", spokenText = "I couldn't find that app on your phone."
      ),
      language = DetectedLanguage.ENGLISH,
      userInput = "open FakeGame"
    )
    assertEquals("I couldn't find that app on your phone.", response)
  }

  // ---------------------------------------------------------------------
  // Repeat requests: no LLM involvement
  // ---------------------------------------------------------------------

  @Test
  fun repeatRequestReplaysLastResponseWithoutCallingLlm() = runTest {
    val engine = FakeEngine()
    val gen = DvexResponseGenerator(engine)
    val context = ConversationContext()
    context.update(
      input = "weather",
      intent = DvexIntent.GetWeather(),
      toolResult = successResult(toolName = "get_weather", message = "Chennai: 31°C"),
      language = DetectedLanguage.ENGLISH,
      spokenResponse = "It's 31 degrees in Chennai, Sir."
    )

    val response = gen.generateResponse(
      intent = DvexIntent.Conversation("repeat that"),
      toolResult = successResult(toolName = "conversation"),
      language = DetectedLanguage.ENGLISH,
      userInput = "repeat that",
      context = context
    )

    // Honorific filler removed (language-consistent, no repeated "Sir").
    assertEquals("I said: It's 31 degrees in Chennai, Sir.", response)
    assertTrue("repeat path must not invoke the LLM", engine.prompts.isEmpty())
  }

  // ---------------------------------------------------------------------
  // Conversation prompt: language consistency + human spoken delivery
  // ---------------------------------------------------------------------

  private fun conversationPromptFor(
    userInput: String,
    language: DetectedLanguage,
    unclearInput: Boolean = false
  ): String {
    val engine = FakeEngine(reply = "ok")
    val gen = DvexResponseGenerator(engine)
    kotlinx.coroutines.runBlocking {
      gen.generateResponse(
        intent = DvexIntent.Conversation(userInput),
        toolResult = successResult(toolName = "conversation", message = "", spokenText = ""),
        language = language,
        userInput = userInput,
        context = ConversationContext(),
        unclearInput = unclearInput
      )
    }
    return engine.prompts.single()
  }

  @Test
  fun conversationPromptStatesTheDetectedLanguageExplicitly() {
    // Previously the language was never spelled out, so short neutral inputs could
    // drift into another language. The directive must name the target language.
    val tamil = conversationPromptFor("என்ன பண்ற?", DetectedLanguage.TAMIL)
    assertTrue(
      "Tamil turn must demand a Tamil reply: $tamil",
      tamil.contains("USER'S CURRENT LANGUAGE: Tamil") && tamil.contains("Reply in Tamil")
    )

    val tanglish = conversationPromptFor("Innaiku enna plan pannalam?", DetectedLanguage.TANGLISH)
    assertTrue(
      "Tanglish turn must demand Tanglish: $tanglish",
      tanglish.contains("USER'S CURRENT LANGUAGE: Tanglish")
    )

    val english = conversationPromptFor("How are you?", DetectedLanguage.ENGLISH)
    assertTrue(
      "English turn must demand English: $english",
      english.contains("USER'S CURRENT LANGUAGE: English")
    )
  }

  @Test
  fun conversationPromptKeepsTanglishMixedInsteadOfFormalTamil() {
    val prompt = conversationPromptFor("enna panra", DetectedLanguage.TANGLISH)
    assertTrue(
      "Mixed style must be protected: $prompt",
      prompt.contains("never translate") || prompt.contains("must stay mixed")
    )
    assertTrue(
      "English technical words must survive inside Tamil/Tanglish: $prompt",
      prompt.contains("Keep app names")
    )
  }

  @Test
  fun conversationPromptBansCannedOpenersAndHonorifics() {
    val prompt = conversationPromptFor("hello there", DetectedLanguage.ENGLISH)
    assertTrue("must ban honorable-title filler: $prompt", prompt.contains("Sir"))
    assertTrue("must ban canned openers: $prompt", prompt.contains("Banned openers"))
    assertTrue("must ban repeated structures: $prompt", prompt.contains("Vary your sentence structure"))
  }

  @Test
  fun conversationPromptCarriesSpokenDeliveryRuleForTts() {
    val prompt = conversationPromptFor("tell me something", DetectedLanguage.ENGLISH)
    assertTrue("speak-ability rules: $prompt", prompt.contains("SPOKEN DELIVERY"))
    assertTrue("no list reading: $prompt", prompt.contains("No bullet points"))
    assertTrue("no fake emotion sounds: $prompt", prompt.contains("filler sounds"))
    assertTrue("no device claims: $prompt", prompt.contains("BOUNDARIES"))
  }

  @Test
  fun conversationPromptAdaptsLengthToTheQuestion() {
    val prompt = conversationPromptFor("hi", DetectedLanguage.ENGLISH)
    assertTrue(
      "length must follow the question: $prompt",
      prompt.contains("Length follows the question")
    )
  }

  @Test
  fun unclearInputTellsTheModelTheTranscriptMayBeGarbled() {
    val prompt = conversationPromptFor(
      userInput = "some rambling unclear words",
      language = DetectedLanguage.ENGLISH,
      unclearInput = true
    )
    assertTrue(
      "the model must know the transcript is unreliable: $prompt",
      prompt.contains("may be garbled")
    )
    assertTrue(
      "and must be allowed to ask for a repeat in the user's language: $prompt",
      prompt.contains("ask them to repeat")
    )
  }

  @Test
  fun unclearInputFlagIsAbsentFromAClearTurn() {
    val prompt = conversationPromptFor("How are you?", DetectedLanguage.ENGLISH)
    assertFalse("a clear turn must not be flagged unclear: $prompt", prompt.contains("may be garbled"))
  }

  @Test
  fun conversationTurnReturnsTheLlmTextVerbatimNotACannedReply() = runTest {
    val engine = FakeEngine(reply = "உன்னோட பேசிட்டு இருக்கேன். என்ன செய்யணும்?")
    val gen = DvexResponseGenerator(engine)

    val response = gen.generateResponse(
      intent = DvexIntent.Conversation("என்ன பண்ற?"),
      toolResult = successResult(toolName = "conversation", message = "", spokenText = ""),
      language = DetectedLanguage.TAMIL,
      userInput = "என்ன பண்ற?",
      context = ConversationContext()
    )

    assertEquals("உன்னோட பேசிட்டு இருக்கேன். என்ன செய்யணும்?", response)
  }

  @Test
  fun generalQuestionWithNoToolDataStillReachesTheLlm() = runTest {
    val engine = FakeEngine(reply = "Photosynthesis is how plants turn light into food.")
    val gen = DvexResponseGenerator(engine)

    val response = gen.generateResponse(
      intent = DvexIntent.GeneralQuestion("what is photosynthesis"),
      toolResult = successResult(toolName = "general_qa", message = "", spokenText = ""),
      language = DetectedLanguage.ENGLISH,
      userInput = "what is photosynthesis",
      context = ConversationContext()
    )

    assertEquals("Photosynthesis is how plants turn light into food.", response)
    assertEquals(1, engine.prompts.size)
  }

  // ---------------------------------------------------------------------
  // GeminiEngine unit behavior (parsing + key handling)
  // ---------------------------------------------------------------------

  @Test
  fun geminiEngineWithoutKeyReturnsNullWithoutNetwork() = runTest {
    val engine = GeminiEngine(apiKeyProvider = { null })
    val response = engine.generate("test prompt")
    assertEquals(null, response)
  }

  @Test
  fun geminiEngineBlankKeyReturnsNull() = runTest {
    val engine = GeminiEngine(apiKeyProvider = { "  " })
    val response = engine.generate("test prompt")
    assertEquals(null, response)
  }

  // ---------------------------------------------------------------------
  // P3 — conversational quality: natural target shapes, robotic lines banned
  // ---------------------------------------------------------------------

  @Test
  fun conversationPromptDefinesNaturalTargetShapesWithoutCannedReplies() {
    val prompt = conversationPromptFor("what is the time", DetectedLanguage.ENGLISH)

    // Natural shapes are taught as RULES, not literal example sentences: D-VEX
    // must skip throwaway acknowledgements, lead with the real answer, and vary
    // its wording so no two replies share the same shape.
    assertTrue(
      "no throwaway opener — lead with the answer: $prompt",
      prompt.contains("Lead with the actual answer instead")
    )
    assertTrue(
      "varied natural shapes required: $prompt",
      prompt.contains("Vary your sentence structure")
    )

    // Canned/robotic openers are banned by name — the exact filler lines a
    // command-parser assistant would produce. The old fixed banned-output list
    // ("Standing by", "Command executed successfully") was replaced in the
    // prompt redesign by this explicit opener/filler ban.
    assertTrue("bans sure-opener: $prompt", prompt.contains("\"Sure\""))
    assertTrue("bans ready-speak: $prompt", prompt.contains("\"I am ready\""))
    assertTrue(
      "bans assistant-canned-speak: $prompt",
      prompt.contains("\"How can I assist you\"")
    )
    assertTrue("bans understood-speak: $prompt", prompt.contains("\"Understood\""))
    assertTrue("bans certainly-speak: $prompt", prompt.contains("\"Certainly\""))
  }
}

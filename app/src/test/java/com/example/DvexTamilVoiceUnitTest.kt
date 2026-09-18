package com.example

import com.example.voice.TamilTransliteration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DvexTamilVoiceUnitTest {

  @Test
  fun testContainsTamilScriptDetection() {
    assertTrue(TamilTransliteration.containsTamilScript("வணக்கம்"))
    assertTrue(TamilTransliteration.containsTamilScript("YouTube திறக்கப்படுகிறது."))
    assertTrue(TamilTransliteration.containsTamilScript("டி-வெக்ஸ்"))
    assertFalse(TamilTransliteration.containsTamilScript("Hello D-VEX"))
    assertFalse(TamilTransliteration.containsTamilScript("Opening YouTube."))
    assertFalse(TamilTransliteration.containsTamilScript("Vanakkam naan D-VEX"))
  }

  @Test
  fun testTamilToTanglishPhraseTransliteration() {
    val input1 = "அந்த ஆப் உங்கள் போனில் இல்லை."
    val output1 = TamilTransliteration.toTanglish(input1)
    assertEquals("Andha app unga phone-la illa.", output1)

    val input2 = "YouTube திறக்கப்படுகிறது."
    val output2 = TamilTransliteration.toTanglish(input2)
    assertTrue(output2.contains("thirakkappadugiradhu"))

    val input3 = "நினைவில் வைத்துக்கொண்டேன்."
    val output3 = TamilTransliteration.toTanglish(input3)
    assertEquals("Ninaivil vaithukkonden.", output3)

    val input4 = "வணக்கம்"
    val output4 = TamilTransliteration.toTanglish(input4)
    assertEquals("Vanakkam", output4)

    val input5 = "இதற்கு Accessibility அனுமதி தேவை."
    val output5 = TamilTransliteration.toTanglish(input5)
    assertEquals("Idharku Accessibility permission thevai.", output5)
  }

  @Test
  fun testNonTamilTextRemainsUnchanged() {
    val englishText = "Opening Camera."
    assertEquals(englishText, TamilTransliteration.toTanglish(englishText))

    val tanglishText = "Intha app unga phone-la illa."
    assertEquals(tanglishText, TamilTransliteration.toTanglish(tanglishText))
  }
}

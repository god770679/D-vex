package com.example.voice

import java.util.Locale

/**
 * High-quality transliteration utility that converts Tamil script to natural Tanglish
 * (Tamil words in Latin script). Used as a graceful fallback when Tamil TTS voice data
 * is absent, corrupted, or low quality on the device.
 */
object TamilTransliteration {

  private val PHRASE_DICTIONARY = mapOf(
    "Yes, Sir. சொல்லுங்க." to "Yes, Sir. Sollunga.",
    "Yes, Sir. சொல்லுங்க" to "Yes, Sir. Sollunga.",
    "சொல்லுங்க." to "Sollunga.",
    "சொல்லுங்க" to "Sollunga",
    "அந்த ஆப் உங்கள் போனில் இல்லை, Sir." to "Intha app unga phone-la illa, Sir.",
    "அந்த ஆப் உங்கள் போனில் இல்லை." to "Andha app unga phone-la illa.",
    "இதற்கு Accessibility அனுமதி தேவை, Sir." to "Intha action-ku Accessibility permission thevai, Sir.",
    "இதற்கு Accessibility அனுமதி தேவை." to "Idharku Accessibility permission thevai.",
    "இதை உறுதிப்படுத்தவா, Sir?" to "Intha action-ah confirm panlaama, Sir?",
    "இதை உறுதிப்படுத்தவா?" to "Idhai urudhipaduthavaa?",
    "இதை முடிக்க முடியவில்லை, Sir." to "Athai mudikka mudiyala, Sir.",
    "இதை முடிக்க முடியவில்லை." to "Idhai mudikka mudiyavillai.",
    "Home திரைக்கு வந்தாச்சு, Sir." to "Home-ku vandhachu, Sir.",
    "Home-க்கு போயாச்சு, Sir." to "Home-ku poyachu, Sir.",
    "முகப்புத் திரைக்கு மாற்றப்பட்டது." to "Home screen-ku maatrapattadhu.",
    "பின் சென்றாச்சு, Sir." to "Pinnaadi vandhachu, Sir.",
    "பின் சென்றது." to "Pinnaadi sendradhu.",
    "நினைவில் வைத்துக்கொண்டேன், Sir." to "Ninaivil vachukitten, Sir.",
    "நினைவில் வைத்துக்கொண்டேன்." to "Ninaivil vaithukkonden.",
    "வணக்கம், Sir! சொல்லுங்க, உங்களுக்கு என்ன வேண்டும்?" to "Vanakkam, Sir! Sollunga, ungalukku enna venum?",
    "வணக்கம்" to "Vanakkam",
    "வணக்கம், நான் டி-வெக்ஸ்" to "Vanakkam, naan D-VEX",
    "நான் D-VEX, உங்கள் தனிப்பட்ட AI உதவியாளர், Sir." to "Naan D-VEX, ungaloda personal AI assistant, Sir.",
    "நான் நலமாக இருக்கிறேன், Sir! நீங்கள் எப்படி இருக்கிறீர்கள்?" to "Naan nalla irukken, Sir! Neenga epdi irukkeenga?",
    "மிக்க மகிழ்ச்சி, Sir!" to "Romba nandri, Sir!",
    "சரி, Sir. அப்புறம் பார்க்கலாம்." to "Seri, Sir. Approm paakalam.",
    "Sure, Sir. இப்பவே பண்றேன்." to "Sure, Sir. Ippove panren.",
    "இப்பவே பண்றேன்" to "Ippove panren",
    "இப்போது நேரம்" to "Ippo mani",
    "திறக்கிறேன்." to "open panren.",
    "திறக்கிறேன்" to "open panren",
    "ஆம்" to "Aam",
    "இல்லை" to "Illai",
    "சரி" to "Sari",
    "திறக்கப்படுகிறது." to "thirakkappadugiradhu.",
    "திறக்கப்படுகிறது" to "thirakkappadugiradhu"
  )

  private val INDEPENDENT_VOWELS = mapOf(
    '\u0B85' to "a",
    '\u0B86' to "aa",
    '\u0B87' to "i",
    '\u0B88' to "ee",
    '\u0B89' to "u",
    '\u0B8A' to "oo",
    '\u0B8E' to "e",
    '\u0B8F' to "ae",
    '\u0B90' to "ai",
    '\u0B92' to "o",
    '\u0B93' to "oa",
    '\u0B94' to "au",
    '\u0B83' to "ak"
  )

  private val CONSONANTS = mapOf(
    '\u0B95' to "k",
    '\u0B99' to "ng",
    '\u0B9A' to "s",
    '\u0B9E' to "ny",
    '\u0B9F' to "t",
    '\u0BA3' to "n",
    '\u0BA4' to "th",
    '\u0BA8' to "n",
    '\u0BA9' to "n",
    '\u0BAA' to "p",
    '\u0BAE' to "m",
    '\u0BAF' to "y",
    '\u0BB0' to "r",
    '\u0BB1' to "r",
    '\u0BB2' to "l",
    '\u0BB3' to "l",
    '\u0BB4' to "zh",
    '\u0BB5' to "v",
    '\u0BB6' to "sh",
    '\u0BB7' to "sh",
    '\u0BB8' to "s",
    '\u0BB9' to "h"
  )

  private val VOWEL_SIGNS = mapOf(
    '\u0BBE' to "aa",
    '\u0BBF' to "i",
    '\u0BC0' to "ee",
    '\u0BC1' to "u",
    '\u0BC2' to "oo",
    '\u0BC6' to "e",
    '\u0BC7' to "ae",
    '\u0BC8' to "ai",
    '\u0BCA' to "o",
    '\u0BCB' to "oa",
    '\u0BCC' to "au"
  )

  private const val VIRAMA = '\u0BCD' // Pulli (halant)

  /**
   * Checks if the given text contains any Tamil script Unicode code points.
   */
  fun containsTamilScript(text: String): Boolean {
    return text.any { it.code in 0x0B80..0x0BFF }
  }

  /**
   * Converts Tamil text into natural, readable Tanglish suitable for Indian English TTS playback.
   */
  fun toTanglish(input: String): String {
    if (input.isBlank() || !containsTamilScript(input)) {
      return input
    }

    val trimmed = input.trim()
    PHRASE_DICTIONARY[trimmed]?.let { return it }

    // Check if it ends with " திறக்கப்படுகிறது."
    for ((phrase, translit) in PHRASE_DICTIONARY) {
      if (trimmed.contains(phrase)) {
        return trimmed.replace(phrase, translit)
      }
    }

    val sb = StringBuilder()
    var i = 0
    val len = input.length

    while (i < len) {
      val ch = input[i]

      when {
        INDEPENDENT_VOWELS.containsKey(ch) -> {
          sb.append(INDEPENDENT_VOWELS[ch])
          i++
        }
        CONSONANTS.containsKey(ch) -> {
          val consonantBase = CONSONANTS[ch] ?: ""
          if (i + 1 < len) {
            val next = input[i + 1]
            if (next == VIRAMA) {
              // Pure consonant without vowel
              sb.append(consonantBase)
              i += 2
            } else if (VOWEL_SIGNS.containsKey(next)) {
              // Consonant + Vowel sign
              sb.append(consonantBase).append(VOWEL_SIGNS[next])
              i += 2
            } else {
              // Inherent 'a' vowel
              sb.append(consonantBase).append("a")
              i++
            }
          } else {
            // End of string consonant has inherent 'a'
            sb.append(consonantBase).append("a")
            i++
          }
        }
        ch.code in 0x0B80..0x0BFF -> {
          // Other Tamil diacritics / markers
          i++
        }
        else -> {
          sb.append(ch)
          i++
        }
      }
    }

    return sb.toString().replace(Regex("\\s+"), " ").trim()
  }
}

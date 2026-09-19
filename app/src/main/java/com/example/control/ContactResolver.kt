package com.example.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.voice.TamilTransliteration
import java.util.Locale

data class ResolvedContact(
  val name: String,
  val phoneNumber: String,
  val email: String? = null
)

sealed class ContactSearchResult {
  data class Single(val contact: ResolvedContact) : ContactSearchResult()
  data class Multiple(val cleanQuery: String, val matches: List<ResolvedContact>) : ContactSearchResult()
  data class NotFound(val cleanQuery: String) : ContactSearchResult()
  object PermissionDenied : ContactSearchResult()
}

/**
 * Resolves contact names, phone numbers, and emails via Android ContactsContract.
 * Strictly avoids guessing or inventing unknown phone numbers.
 */
class ContactResolver(private val context: Context) {

  /**
   * Cleans colloquial suffixes like "-ku", "ku", "vukku", "வுக்கு", etc.
   */
  fun cleanContactQuery(raw: String): String {
    var q = raw.trim()
    q = q.replace(Regex("^(to|for|call|dial|phone|make a call to|message|text|email|mail)\\s+", RegexOption.IGNORE_CASE), "").trim()

    // Strip common Tamil/Tanglish command affixes
    val commandAffixes = listOf(
      "call pannunga", "call pannu", "call seiy", "call podu", "phone pannu", "phone podu",
      "message anuppu", "message anupu", "msg pannu", "msg anuppu", "msg anupu", "msg podu",
      "whatsapp anuppu", "whatsapp anupu", "email anuppu",
      "கால் பண்ணுங்க", "கால் பண்ணு", "போன் பண்ணு", "கால் செய்", "போன் போடு", "அழைக்கவும்", "அழை",
      "செய்தி அனுப்பு", "மெசேஜ் அனுப்பு", "வாட்ஸ்அப் அனுப்பு", "மின்னஞ்சல் அனுப்பு",
      "பேச வேண்டும்", "பேசணும்"
    )
    for (affix in commandAffixes) {
      q = q.replace(affix, "", ignoreCase = true).trim()
    }

    val suffixes = listOf(
      "-ku", " ku", "-kku", " kku",
      "-vukku", "vukku", "-ukku", "ukku",
      "-kitta", " kitta", "-oda", " oda",
      "-va", " va", "-voda", " voda",
      "-a", " a",
      "வுக்கு", "க்கு", "டம்", "கிட்ட", "ரிடம்", "வை", "ஐ"
    )
    for (suffix in suffixes) {
      if (q.endsWith(suffix, ignoreCase = true) && q.length > suffix.length) {
        q = q.substring(0, q.length - suffix.length).trim()
        break
      }
    }
    return q.trimEnd('.', '?', '!', ',', '-', ' ').trim()
  }

  /**
   * Main rich contact resolution.
   * Returns Single, Multiple, NotFound, or PermissionDenied.
   */
  fun findContact(rawQuery: String): ContactSearchResult {
    val clean = cleanContactQuery(rawQuery)
    if (clean.isBlank()) return ContactSearchResult.NotFound(rawQuery)

    // 1. Direct phone number check
    val digitsOnly = clean.replace(Regex("[^0-9+]"), "")
    if (digitsOnly.length >= 7 && (clean.matches(Regex("[+0-9\\-\\s()]+")) || clean.all { it.isDigit() || it == '+' || it == '-' || it == ' ' })) {
      return ContactSearchResult.Single(ResolvedContact(name = clean, phoneNumber = digitsOnly))
    }

    // 2. Check permission
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "READ_CONTACTS permission not granted.")
      return ContactSearchResult.PermissionDenied
    }

    // 3. Query Contacts Provider with original clean query
    val initialResult = queryDeviceContacts(clean)
    if (initialResult !is ContactSearchResult.NotFound) {
      return initialResult
    }

    // 4. Tamil and English kinship aliases fallback (e.g. "Amma" -> "Mom", "Mother", or "Mom" -> "Amma")
    val kinshipAliases = mapOf(
      "அம்மா" to listOf("amma", "mom", "mother", "mummy", "maa"),
      "amma" to listOf("mom", "mother", "mummy", "maa", "அம்மா"),
      "mom" to listOf("amma", "mother", "mummy", "maa", "அம்மா"),
      "mother" to listOf("amma", "mom", "mummy", "அம்மா"),
      "அப்பா" to listOf("appa", "dad", "father", "daddy", "paa"),
      "appa" to listOf("dad", "father", "daddy", "paa", "அப்பா"),
      "dad" to listOf("appa", "father", "daddy", "paa", "அப்பா"),
      "father" to listOf("appa", "dad", "daddy", "அப்பா"),
      "அண்ணா" to listOf("anna", "annan", "brother", "bro"),
      "anna" to listOf("brother", "bro", "annan", "அண்ணா"),
      "அண்ணன்" to listOf("annan", "anna", "brother", "bro"),
      "தம்பி" to listOf("thambi", "brother", "bro"),
      "thambi" to listOf("brother", "bro", "தம்பி"),
      "அக்கா" to listOf("akka", "sister", "sis"),
      "akka" to listOf("sister", "sis", "அக்கா"),
      "தங்கை" to listOf("thangai", "sister", "sis"),
      "thangai" to listOf("sister", "sis", "தங்கை"),
      "மனைவி" to listOf("wife", "wifey"),
      "wife" to listOf("மனைவி", "wifey"),
      "கணவர்" to listOf("husband", "hubby"),
      "husband" to listOf("கணவர்", "hubby")
    )
    val lowerClean = clean.lowercase(Locale.ROOT)
    val aliases = kinshipAliases[clean] ?: kinshipAliases[lowerClean]
    if (aliases != null) {
      for (alias in aliases) {
        val aliasResult = queryDeviceContacts(alias)
        if (aliasResult !is ContactSearchResult.NotFound) {
          return aliasResult
        }
      }
    }

    // 5. Transliteration fallback (e.g. Tamil script to Tanglish phonetic query)
    val transliterated = TamilTransliteration.toTanglish(clean).trim()
    if (transliterated.isNotBlank() && !transliterated.equals(clean, ignoreCase = true)) {
      val transResult = queryDeviceContacts(transliterated)
      if (transResult !is ContactSearchResult.NotFound) {
        return transResult
      }
    }

    return ContactSearchResult.NotFound(clean)
  }

  fun resolveContact(query: String): ResolvedContact? {
    return when (val result = findContact(query)) {
      is ContactSearchResult.Single -> result.contact
      else -> null
    }
  }

  private fun queryDeviceContacts(cleanQuery: String): ContactSearchResult {
    val contentResolver = context.contentResolver
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER
    )

    // Step A: First try exact match (case-insensitive)
    val exactMatches = mutableListOf<ResolvedContact>()
    var exactCursor: Cursor? = null
    try {
      val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? COLLATE NOCASE"
      val selectionArgs = arrayOf(cleanQuery)
      exactCursor = contentResolver.query(uri, projection, selection, selectionArgs, null)

      if (exactCursor != null) {
        val nameIndex = exactCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = exactCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (exactCursor.moveToNext()) {
          val name = if (nameIndex >= 0) exactCursor.getString(nameIndex) else cleanQuery
          val number = if (numberIndex >= 0) exactCursor.getString(numberIndex) else null
          if (!number.isNullOrBlank()) {
            val contact = ResolvedContact(name = name, phoneNumber = number)
            if (exactMatches.none { it.name.equals(name, ignoreCase = true) && it.phoneNumber == number }) {
              exactMatches.add(contact)
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error querying exact contact", e)
    } finally {
      exactCursor?.close()
    }

    val distinctExactNames = exactMatches.map { it.name }.distinct()
    if (distinctExactNames.size == 1) {
      val first = exactMatches.first()
      val email = queryContactEmail(first.name)
      return ContactSearchResult.Single(first.copy(email = email))
    } else if (distinctExactNames.size > 1) {
      return ContactSearchResult.Multiple(cleanQuery, exactMatches)
    }

    // Step B: Prefix or Substring LIKE search
    val partialMatches = mutableListOf<ResolvedContact>()
    var partialCursor: Cursor? = null
    try {
      val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
      val selectionArgs = arrayOf("%$cleanQuery%")
      partialCursor = contentResolver.query(uri, projection, selection, selectionArgs, null)

      if (partialCursor != null) {
        val nameIndex = partialCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = partialCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (partialCursor.moveToNext()) {
          val name = if (nameIndex >= 0) partialCursor.getString(nameIndex) else cleanQuery
          val number = if (numberIndex >= 0) partialCursor.getString(numberIndex) else null
          if (!number.isNullOrBlank()) {
            val contact = ResolvedContact(name = name, phoneNumber = number)
            if (partialMatches.none { it.name.equals(name, ignoreCase = true) && it.phoneNumber == number }) {
              partialMatches.add(contact)
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error querying partial contact", e)
    } finally {
      partialCursor?.close()
    }

    val distinctPartialNames = partialMatches.map { it.name }.distinct()
    return when {
      distinctPartialNames.isEmpty() -> {
        ContactSearchResult.NotFound(cleanQuery)
      }
      distinctPartialNames.size == 1 -> {
        val first = partialMatches.first()
        val email = queryContactEmail(first.name)
        ContactSearchResult.Single(first.copy(email = email))
      }
      else -> {
        ContactSearchResult.Multiple(cleanQuery, partialMatches)
      }
    }
  }

  fun queryContactEmail(contactName: String): String? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      return null
    }
    val contentResolver = context.contentResolver
    val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Email.ADDRESS
    )
    var cursor: Cursor? = null
    try {
      val selection = "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?"
      val selectionArgs = arrayOf("%$contactName%")
      cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
      if (cursor != null && cursor.moveToFirst()) {
        val addressIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
        if (addressIndex >= 0) {
          val email = cursor.getString(addressIndex)
          if (!email.isNullOrBlank()) return email
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error querying contact email", e)
    } finally {
      cursor?.close()
    }
    return null
  }

  companion object {
    private const val TAG = "[D-VEX][CONTACTS]"
  }
}

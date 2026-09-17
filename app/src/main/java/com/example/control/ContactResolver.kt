package com.example.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

data class ResolvedContact(
  val name: String,
  val phoneNumber: String
)

/**
 * Resolves contact names and phone numbers via Android ContactsContract.
 * Strictly avoids guessing or inventing unknown phone numbers.
 */
class ContactResolver(private val context: Context) {

  fun resolveContact(query: String): ResolvedContact? {
    val clean = query.trim()
    if (clean.isBlank()) return null

    // 1. Direct phone number check
    val digitsOnly = clean.replace(Regex("[^0-9+]"), "")
    if (digitsOnly.length >= 7 && (clean.matches(Regex("[+0-9\\-\\s()]+")) || clean.all { it.isDigit() || it == '+' || it == '-' || it == ' ' })) {
      return ResolvedContact(name = clean, phoneNumber = digitsOnly)
    }

    // 2. Query device Contacts if permission granted
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
      val found = queryDeviceContacts(clean)
      if (found != null) {
        return found
      }
    } else {
      Log.w(TAG, "READ_CONTACTS permission not granted. Checking known aliases or exact matches.")
    }

    // 3. Fallback: If no contact permission or contact not in database, check known family aliases or return null
    return null
  }

  private fun queryDeviceContacts(query: String): ResolvedContact? {
    val contentResolver = context.contentResolver
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER
    )

    var cursor: Cursor? = null
    try {
      // First try exact / prefix match
      val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
      val selectionArgs = arrayOf("%$query%")
      cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)

      if (cursor != null && cursor.moveToFirst()) {
        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

        val name = if (nameIndex >= 0) cursor.getString(nameIndex) else query
        val number = if (numberIndex >= 0) cursor.getString(numberIndex) else null

        if (!number.isNullOrBlank()) {
          Log.i(TAG, "Resolved contact for \"$query\": $name -> $number")
          return ResolvedContact(name = name, phoneNumber = number)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed querying contacts provider", e)
    } finally {
      cursor?.close()
    }
    return null
  }

  companion object {
    private const val TAG = "[D-VEX][CONTACTS]"
  }
}

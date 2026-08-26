package com.relay.gateway.contacts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import com.relay.core.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlinx.serialization.encodeToString

/**
 * Reads the SIM device's contacts so the client can render real names and
 * avatars in the pinned "stories" row and message threads.
 *
 * Photos are downscaled hard before they cross the wire: an unbounded contact
 * photo can be 2 MB, and we are sending these inside AES-GCM envelopes over a
 * mobile link. 128 px JPEG at quality 70 is plenty for a 56 dp squircle at 4x
 * density and lands around 6 KB.
 */
class ContactsMirror(private val context: Context) {

    suspend fun load(limit: Int = MAX_CONTACTS): List<Contact> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED,
        )

        val results = LinkedHashMap<String, Contact>(limit)

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL",
                null,
                // Starred first, then most recently contacted — exactly the order
                // the client's pinned row wants.
                "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val normIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                val starIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
                val lastIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED)

                while (cursor.moveToNext() && results.size < limit) {
                    val number = cursor.getString(normIdx)
                        ?: cursor.getString(numIdx)
                        ?: continue
                    val key = number.filter { it.isDigit() || it == '+' }
                    if (key.isEmpty() || results.containsKey(key)) continue

                    results[key] = Contact(
                        id = cursor.getString(idIdx).orEmpty(),
                        name = cursor.getString(nameIdx).orEmpty().ifEmpty { number },
                        number = number,
                        photoB64 = cursor.getString(photoIdx)?.let(::encodeThumbnail).orEmpty(),
                        pinned = cursor.getInt(starIdx) == 1,
                        lastSeenTs = cursor.getLong(lastIdx),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CONTACTS not granted", e)
        } catch (t: Throwable) {
            Log.e(TAG, "contact query failed", t)
        }

        Log.i(TAG, "mirrored ${results.size} contacts")
        results.values.toList()
    }

    /** Decode → downscale → JPEG → base64. Returns "" for anything unreadable. */
    private fun encodeThumbnail(uriString: String): String = runCatching {
        val uri = Uri.parse(uriString)

        // Two-pass decode: bounds first so we never allocate the full bitmap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0) return ""

        var sample = 1
        while (bounds.outWidth / sample > TARGET_PX * 2) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return ""

        val scaled = if (decoded.width > TARGET_PX) {
            val ratio = TARGET_PX.toFloat() / decoded.width
            Bitmap.createScaledBitmap(
                decoded,
                TARGET_PX,
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        val out = ByteArrayOutputStream(8 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()

        if (out.size() > MAX_PHOTO_BYTES) return ""
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrElse { "" }

    private companion object {
        const val TAG = "ContactsMirror"
        const val MAX_CONTACTS = 500
        const val TARGET_PX = 128
        const val JPEG_QUALITY = 70
        const val MAX_PHOTO_BYTES = 24 * 1024
    }
}

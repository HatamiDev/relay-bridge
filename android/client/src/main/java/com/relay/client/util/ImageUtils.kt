package com.relay.client.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Base64 → Bitmap with an LRU cache.
 *
 * Contact photos arrive inside encrypted envelopes as base64 JPEG. Decoding one
 * on every recomposition of a LazyRow item would drop frames on scroll, so the
 * decoded bitmaps are cached by content hash and bounded to a fraction of the
 * app's heap.
 */
private val bitmapCache = object : LruCache<Int, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt().coerceAtLeast(2048),
) {
    override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
}

fun decodeBase64Bitmap(base64: String?): Bitmap? {
    if (base64.isNullOrEmpty()) return null
    val key = base64.hashCode()
    bitmapCache.get(key)?.let { return it }

    return runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bitmapCache.put(key, it) }
    }.getOrNull()
}

fun decodeBase64Image(base64: String?): ImageBitmap? =
    decodeBase64Bitmap(base64)?.asImageBitmap()

/**
 * Two-letter initials for avatar fallbacks, or `""` when there is no name.
 *
 * Only letters count. The old version took the first character of each word
 * whatever it was, so an unsaved contact — which on an SMS relay is most of
 * them — got the first character of its phone number. Every one of those
 * numbers starts with the country prefix, so the list rendered a column of
 * identical "+" circles that told the user nothing and read as an
 * "add contact" button rather than as a person.
 *
 * Returning empty rather than "?" lets the caller draw a person glyph, which
 * is honest about there being no name, where "?" suggests something went
 * wrong.
 */
fun String.initials(): String = trim()
    .split(' ', '-', '_', '.')
    .filter { it.isNotBlank() }
    .mapNotNull { word -> word.firstOrNull { it.isLetter() } }
    .take(2)
    .joinToString("") { it.uppercase() }

/** Mask all but the last four digits — used in privacy-sensitive logs. */
fun String.maskNumber(): String =
    if (length <= 4) "****" else "•".repeat(length - 4) + takeLast(4)

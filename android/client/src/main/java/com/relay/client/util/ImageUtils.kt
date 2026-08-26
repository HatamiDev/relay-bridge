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

/** Two-letter initials for avatar fallbacks. */
fun String.initials(): String = trim()
    .split(' ', '-', '_')
    .filter { it.isNotBlank() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifEmpty { "?" }

/** Mask all but the last four digits — used in privacy-sensitive logs. */
fun String.maskNumber(): String =
    if (length <= 4) "****" else "•".repeat(length - 4) + takeLast(4)

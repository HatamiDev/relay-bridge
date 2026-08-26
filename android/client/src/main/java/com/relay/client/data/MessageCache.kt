package com.relay.client.data

import android.content.Context
import android.util.Log
import com.relay.core.crypto.CryptoBox
import com.relay.core.crypto.SecureStore
import com.relay.core.model.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Encrypted on-disk message cache.
 *
 * Message bodies are the most sensitive thing this app holds, so the cache is
 * sealed with the same AES-256-GCM machinery as the wire protocol — using a key
 * derived from the pairing root key. A stolen device with an unlocked bootloader
 * yields ciphertext, not a plaintext SMS archive.
 *
 * Writes are debounced by the caller and go through a temp-file rename so a
 * crash mid-write cannot corrupt the store.
 */
class MessageCache(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val tempFile = File(context.filesDir, "$FILE_NAME.tmp")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val secureStore = SecureStore(context)

    suspend fun save(messages: List<SmsMessage>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val box = box() ?: return@runCatching
                // Cap the archive so a chatty year does not grow unbounded.
                val trimmed = messages.sortedByDescending { it.ts }.take(MAX_MESSAGES)
                val plaintext = json.encodeToString(trimmed)
                val envelope = box.sealAtRest(CACHE_EVENT, plaintext)

                tempFile.writeText(json.encodeToString(envelope))
                if (!tempFile.renameTo(file)) {
                    // renameTo can fail across some Samsung FS layers; fall back.
                    file.writeText(tempFile.readText())
                    tempFile.delete()
                }
            }.onFailure { Log.e(TAG, "cache save failed", it) }
        }
    }

    suspend fun load(): List<SmsMessage> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                if (!file.exists()) return@runCatching emptyList()
                val box = box() ?: return@runCatching emptyList()
                val envelope = json.decodeFromString<com.relay.core.model.Envelope>(file.readText())
                json.decodeFromString<List<SmsMessage>>(box.openAtRest(envelope))
            }.getOrElse {
                // A failed open means the pairing changed or the file was
                // tampered with. Either way the cache is worthless — drop it.
                Log.w(TAG, "cache unreadable, discarding: ${it.message}")
                file.delete()
                emptyList()
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            tempFile.delete()
        }
    }

    /**
     * Reuses the pairing key schedule so no extra key material has to exist or
     * be managed. `sealAtRest`/`openAtRest` use one key in both directions.
     * A receiver has exactly one peer — the gateway — so its box is the only
     * one that makes sense for the local cache.
     */
    private fun box(): CryptoBox? = secureStore.gatewayPeer()?.deviceId?.let { secureStore.cryptoBox(it) }

    private companion object {
        const val TAG = "MessageCache"
        const val FILE_NAME = "messages.enc"
        const val CACHE_EVENT = "cache:messages"
        const val MAX_MESSAGES = 5_000
    }
}

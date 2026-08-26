package com.relay.core.crypto

import android.util.Base64
import com.relay.core.model.DeviceRole
import com.relay.core.model.Envelope
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Agent 5 — end-to-end encryption.
 *
 * One box per *pair* of devices. A gateway serving three receivers holds three
 * boxes; a receiver holds exactly one. Each box owns its own key schedule
 * derived from that pair's root key:
 *
 * ```
 *  PRK   = HKDF-Extract(salt = roomId, ikm = rootKey)
 *  K_g2r = HKDF-Expand(PRK, "relay/v2/gateway->receiver", 32)
 *  K_r2g = HKDF-Expand(PRK, "relay/v2/receiver->gateway", 32)
 *  K_sas = HKDF-Expand(PRK, "relay/v2/sas",                4)
 * ```
 *
 * Because sibling receivers derive different root keys, one receiver can never
 * decrypt traffic addressed to another even though the relay server carries
 * both streams.
 *
 * Thread-safe.
 */
class CryptoBox private constructor(
    private val sendKey: SecretKeySpec,
    private val receiveKey: SecretKeySpec,
    /** Device this box talks to. Written into every envelope's `dst`. */
    val peerDeviceId: String,
    /** 6-digit short authentication string — compare it on both screens. */
    val sasCode: String,
) {

    private val random = SecureRandom()
    private val sequence = AtomicLong(System.currentTimeMillis() / 1000L)
    private val replayWindow = ReplayWindow(WINDOW_SIZE)

    // ── Sealing ──────────────────────────────────────────────────────────────

    /**
     * Encrypt [plaintextJson] for [peerDeviceId].
     * A fresh 96-bit IV is drawn per call; IVs are never reused under a key.
     */
    fun seal(event: String, plaintextJson: String): Envelope {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val sq = sequence.incrementAndGet()
        val ts = System.currentTimeMillis()

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(ENVELOPE_VERSION, event, peerDeviceId, sq, ts))
        }
        val ct = cipher.doFinal(plaintextJson.toByteArray(Charsets.UTF_8))

        return Envelope(ENVELOPE_VERSION, event, peerDeviceId, sq, ts, iv.b64(), ct.b64())
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    /**
     * Decrypt an envelope received from [peerDeviceId].
     *
     * @param expectedDst our own deviceId. Checked against the AAD so a hostile
     *        relay cannot redirect an envelope meant for a sibling device.
     * @throws SecurityException on tag failure, replay, stale timestamp,
     *         version mismatch or misdirection. Callers must drop the message —
     *         never surface partial data to the UI.
     */
    fun open(envelope: Envelope, expectedDst: String): String {
        if (envelope.v != ENVELOPE_VERSION) {
            throw SecurityException("Unsupported envelope version ${envelope.v}")
        }
        if (envelope.dst != expectedDst) {
            throw SecurityException("Envelope addressed to ${envelope.dst}, not us")
        }

        val skew = kotlin.math.abs(System.currentTimeMillis() - envelope.ts)
        if (skew > MAX_CLOCK_SKEW_MS) {
            throw SecurityException("Envelope outside freshness window (${skew}ms)")
        }
        if (!replayWindow.accept(envelope.sq)) {
            throw SecurityException("Replayed or out-of-window sequence ${envelope.sq}")
        }

        val iv = envelope.iv.unB64()
        if (iv.size != IV_BYTES) throw SecurityException("Bad IV length ${iv.size}")

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(envelope.v, envelope.ev, envelope.dst, envelope.sq, envelope.ts))
        }

        return try {
            String(cipher.doFinal(envelope.ct.unB64()), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            throw SecurityException("Authentication tag verification failed", e)
        }
    }

    // ── At-rest encryption ───────────────────────────────────────────────────

    /**
     * Encrypt data for local storage (message cache, drafts).
     *
     * Uses the send key in both directions — there is only one party, the
     * device itself — and skips the freshness and replay windows, because a
     * cache entry is legitimately hours or weeks old. Confidentiality and
     * integrity are unchanged.
     */
    fun sealAtRest(label: String, plaintextJson: String): Envelope {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val ts = System.currentTimeMillis()

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(ENVELOPE_VERSION, label, LOCAL_DST, 0L, ts))
        }
        val ct = cipher.doFinal(plaintextJson.toByteArray(Charsets.UTF_8))

        return Envelope(ENVELOPE_VERSION, label, LOCAL_DST, 0L, ts, iv.b64(), ct.b64())
    }

    /** @throws SecurityException when the file was tampered with or re-keyed. */
    fun openAtRest(envelope: Envelope): String {
        if (envelope.v != ENVELOPE_VERSION) {
            throw SecurityException("Unsupported envelope version ${envelope.v}")
        }
        val iv = envelope.iv.unB64()
        if (iv.size != IV_BYTES) throw SecurityException("Bad IV length ${iv.size}")

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(envelope.v, envelope.ev, envelope.dst, envelope.sq, envelope.ts))
        }
        return try {
            String(cipher.doFinal(envelope.ct.unB64()), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            throw SecurityException("At-rest authentication failed", e)
        }
    }

    fun reset() = replayWindow.reset()

    private fun aad(v: Int, ev: String, dst: String, sq: Long, ts: Long): ByteArray =
        "$v|$ev|$dst|$sq|$ts".toByteArray(Charsets.UTF_8)

    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32
        private const val ENVELOPE_VERSION = 2
        private const val MAX_CLOCK_SKEW_MS = 120_000L
        private const val WINDOW_SIZE = 1024
        private const val LOCAL_DST = "@local"

        private const val INFO_G2R = "relay/v2/gateway->receiver"
        private const val INFO_R2G = "relay/v2/receiver->gateway"
        private const val INFO_SAS = "relay/v2/sas"

        const val ROOT_KEY_BYTES = KEY_BYTES

        /** Generate a fresh root key for the QR pairing path. */
        fun generateRootKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

        /**
         * Build a session box for one paired peer.
         *
         * @param rootKey  32 bytes, from the QR or from [KeyAgreement]
         * @param roomId   HKDF salt — binds the keys to this pairing
         * @param ownRole  selects the send/receive key orientation
         * @param peerDeviceId written into every outgoing envelope's `dst`
         */
        fun create(
            rootKey: ByteArray,
            roomId: String,
            ownRole: DeviceRole,
            peerDeviceId: String,
        ): CryptoBox {
            require(rootKey.size == KEY_BYTES) {
                "Root key must be $KEY_BYTES bytes, was ${rootKey.size}"
            }

            val prk = hkdfExtract(salt = roomId.toByteArray(Charsets.UTF_8), ikm = rootKey)
            val g2r = SecretKeySpec(hkdfExpand(prk, INFO_G2R, KEY_BYTES), "AES")
            val r2g = SecretKeySpec(hkdfExpand(prk, INFO_R2G, KEY_BYTES), "AES")
            val sas = formatSas(hkdfExpand(prk, INFO_SAS, 4))
            prk.fill(0)

            return if (ownRole == DeviceRole.GATEWAY) {
                CryptoBox(g2r, r2g, peerDeviceId, sas)
            } else {
                CryptoBox(r2g, g2r, peerDeviceId, sas)
            }
        }

        /** 6-digit decimal SAS, zero-padded. */
        private fun formatSas(bytes: ByteArray): String {
            var v = 0L
            for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
            return (v % 1_000_000L).toString().padStart(6, '0')
        }

        // ── HKDF (RFC 5869) over HMAC-SHA256 ─────────────────────────────────

        private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            return mac.doFinal(ikm)
        }

        private fun hkdfExpand(prk: ByteArray, info: String, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            val infoBytes = info.toByteArray(Charsets.UTF_8)
            val output = ByteArray(length)
            var block = ByteArray(0)
            var generated = 0
            var counter = 1

            while (generated < length) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(block)
                mac.update(infoBytes)
                mac.update(counter.toByte())
                block = mac.doFinal()

                val take = minOf(block.size, length - generated)
                System.arraycopy(block, 0, output, generated, take)
                generated += take
                counter++
            }
            return output
        }

        // ── Base64url ────────────────────────────────────────────────────────

        private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        fun ByteArray.b64(): String = Base64.encodeToString(this, B64_FLAGS)
        fun String.unB64(): ByteArray = Base64.decode(this, B64_FLAGS)

        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }

        /** Displayable fingerprint of a root key, for manual verification. */
        fun keyFingerprint(rootKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(rootKey)
                .take(8)
                .joinToString(":") { "%02X".format(it) }
    }
}

/**
 * Sliding-window replay guard (RFC 6479 style bitmap).
 * Accepts each sequence number exactly once, tolerating reordering within
 * [size] positions of the highest seen.
 */
private class ReplayWindow(private val size: Int) {
    private var highest = -1L
    private val seen = java.util.BitSet(size)
    private val lock = Any()

    fun accept(sq: Long): Boolean = synchronized(lock) {
        if (sq < 0) return false

        if (highest < 0) {
            highest = sq
            seen.clear(); seen.set(0)
            return true
        }
        if (sq > highest) {
            shiftLeft((sq - highest).coerceAtMost(size.toLong()).toInt())
            highest = sq
            seen.set(0)
            return true
        }
        val delta = (highest - sq).toInt()
        if (delta >= size) return false
        if (seen.get(delta)) return false
        seen.set(delta)
        return true
    }

    fun reset() = synchronized(lock) {
        highest = -1L
        seen.clear()
    }

    private fun shiftLeft(bits: Int) {
        if (bits >= size) { seen.clear(); return }
        for (i in size - 1 downTo bits) seen.set(i, seen.get(i - bits))
        for (i in 0 until bits) seen.clear(i)
    }
}

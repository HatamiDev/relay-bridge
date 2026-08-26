package com.relay.core.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement as JceKeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Agent 5 — pairing key agreement.
 *
 * ## Why this exists
 *
 * The original design put a 256-bit root key straight into the QR code, which
 * is ideal: the server never has a chance to see or substitute it. But the user
 * needs to be able to *type* a short code on a second phone, and a 8-character
 * code carries ~40 bits — nowhere near a key.
 *
 * So the typed-code path does an **ephemeral ECDH over P-256** through the
 * server, and mixes the pairing code into the HKDF salt:
 *
 * ```
 *   gateway:  (Eg_priv, Eg_pub) = P256.generate()
 *   receiver: (Er_priv, Er_pub) = P256.generate()
 *   shared    = ECDH(own_priv, peer_pub)                  // 32 bytes
 *   rootKey   = HKDF(salt = roomId ‖ pairCode, ikm = shared, info = "relay/v2/root")
 * ```
 *
 * ## What this does and does not protect against
 *
 * * A **passive** server, or anyone sniffing the network, learns nothing: they
 *   see two public keys and cannot compute the shared secret.
 * * An **active** server can substitute both public keys and sit in the middle.
 *   ECDH alone cannot stop that — nothing can, without an authenticated
 *   channel. That is precisely what the 6-digit SAS is for: it is derived from
 *   the resulting root key, so a MITM produces two different codes and the
 *   mismatch is visible on the two screens.
 *
 * **Therefore: comparing the SAS is not optional on the typed-code path.** The
 * QR path carries the key directly and has no MITM window at all, which is why
 * the UI recommends it.
 *
 * P-256 rather than X25519 because `KeyPairGenerator.getInstance("XDH")` only
 * exists from API 33, and this app supports API 29. P-256 ECDH is available
 * through plain JCE on every supported device with no extra dependency.
 */
object KeyAgreement {

    private const val CURVE = "secp256r1"
    private const val KEY_ALGORITHM = "EC"
    private const val AGREEMENT_ALGORITHM = "ECDH"
    private const val ROOT_INFO = "relay/v2/root"
    private const val B64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /** An ephemeral pairing keypair. Discard [keyPair] as soon as pairing ends. */
    class Ephemeral internal constructor(private val keyPair: KeyPair) {

        /** base64url X.509 SubjectPublicKeyInfo — safe to send through the server. */
        val publicKeyB64: String =
            Base64.encodeToString(keyPair.public.encoded, B64)

        /**
         * Complete the exchange.
         *
         * @param peerPublicKeyB64 the other device's public key, as relayed
         * @param roomId           binds the key to this specific room
         * @param pairCode         binds it to this specific pairing attempt
         * @return the 32-byte root key. **Caller must zero it after use.**
         * @throws SecurityException if the peer key is malformed or off-curve
         */
        fun deriveRootKey(
            peerPublicKeyB64: String,
            roomId: String,
            pairCode: String,
        ): ByteArray {
            val peerPublic = decodePublicKey(peerPublicKeyB64)

            val shared = try {
                JceKeyAgreement.getInstance(AGREEMENT_ALGORITHM).run {
                    init(keyPair.private)
                    // doPhase validates that the peer point is on the curve; a
                    // malformed or small-subgroup point throws here rather than
                    // silently producing a predictable secret.
                    doPhase(peerPublic, true)
                    generateSecret()
                }
            } catch (e: java.security.GeneralSecurityException) {
                throw SecurityException("ECDH failed — peer public key rejected", e)
            }

            // Mixing pairCode into the salt means an attacker who captured the
            // exchange but never saw the code cannot derive the key offline.
            val salt = "$roomId|$pairCode".toByteArray(Charsets.UTF_8)
            val rootKey = hkdf(salt = salt, ikm = shared, info = ROOT_INFO, length = 32)

            shared.fill(0)
            return rootKey
        }
    }

    fun generateEphemeral(): Ephemeral {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(ECGenParameterSpec(CURVE), SecureRandom())
        return Ephemeral(generator.generateKeyPair())
    }

    private fun decodePublicKey(b64: String): PublicKey {
        val bytes = try {
            Base64.decode(b64, B64)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("Peer public key is not valid base64url", e)
        }
        return try {
            KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: java.security.GeneralSecurityException) {
            throw SecurityException("Peer public key is not a valid P-256 point", e)
        }
    }

    // ── HKDF-SHA256 (RFC 5869) ───────────────────────────────────────────────

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")

        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

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

        prk.fill(0)
        return output
    }
}

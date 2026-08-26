package com.relay.core.net

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.relay.core.crypto.KeyAgreement
import com.relay.core.crypto.SecureStore
import com.relay.core.model.DeviceRole
import com.relay.core.model.PairedPeer
import kotlinx.coroutines.delay

/**
 * Drives the pairing handshake for both roles, so the two UIs stay thin and the
 * crypto lives in exactly one place.
 *
 * Sender (gateway):
 * ```
 *   startAsGateway()  → shows a code
 *   pollForJoins()    → derives a key per receiver, returns SAS to display
 *   confirmReceiver() → after the user says the two codes match
 * ```
 *
 * Receiver:
 * ```
 *   joinWithCode("K7M4-QW2X") → derives the key, returns the SAS to display
 * ```
 *
 * The 6-digit SAS is not decoration. On the typed-code path the relay server
 * carries both public keys and could substitute them; the only thing that
 * detects it is the two codes disagreeing. See [KeyAgreement] for the reasoning.
 */
class PairingCoordinator(
    context: Context,
    private val store: SecureStore,
    private val serverUrl: String,
) {

    private val appContext = context.applicationContext
    private val api = PairingApi(serverUrl)

    /** Ephemeral keypair for the code currently outstanding. Rotated per code. */
    private var ephemeral: KeyAgreement.Ephemeral? = null
    private var activeCode: String = ""

    data class GatewaySession(
        val pairCode: String,
        val roomId: String,
        val deviceId: String,
        val expiresAt: Long,
        val ttlSeconds: Int,
        val qrPayload: String,
    )

    data class DerivedPeer(
        val deviceId: String,
        val model: String,
        val label: String,
        /** Compare this with the number on the other screen. */
        val sas: String,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Gateway
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create the room and mint the first code.
     * Persists role, server, room and token so a crash mid-pairing is recoverable.
     */
    suspend fun startAsGateway(bootstrapSecret: String): Result<GatewaySession> {
        val keys = KeyAgreement.generateEphemeral()
        ephemeral = keys

        val deviceId = ensureDeviceId("gw")

        return api.create(
            bootstrapSecret = bootstrapSecret,
            deviceId = deviceId,
            model = Build.MODEL,
            label = store.deviceLabel.ifEmpty { Build.MODEL },
            fcmToken = store.fcmToken,
            publicKeyB64 = keys.publicKeyB64,
        ).map { created ->
            activeCode = created.pairCode

            store.role = DeviceRole.GATEWAY
            store.serverUrl = serverUrl
            store.roomId = created.roomId
            store.deviceId = created.deviceId
            store.authToken = created.token

            GatewaySession(
                pairCode = created.pairCode,
                roomId = created.roomId,
                deviceId = created.deviceId,
                expiresAt = created.expiresAt,
                ttlSeconds = created.ttlSeconds,
                qrPayload = PairingPayload(
                    serverUrl = serverUrl,
                    pairCode = created.pairCode,
                    roomId = created.roomId,
                    gatewayDeviceId = created.deviceId,
                    gatewayPubKey = keys.publicKeyB64,
                ).toUri(),
            )
        }
    }

    /**
     * Mint a fresh code for the same room so another receiver can be added later
     * without disturbing the ones already paired. Rotates the ephemeral key —
     * an old code must not be able to derive a new receiver's session.
     */
    suspend fun issueAnotherCode(): Result<GatewaySession> {
        val keys = KeyAgreement.generateEphemeral()
        ephemeral = keys

        return api.newCode(store.authToken, keys.publicKeyB64).map { created ->
            activeCode = created.pairCode
            GatewaySession(
                pairCode = created.pairCode,
                roomId = store.roomId,
                deviceId = store.deviceId,
                expiresAt = created.expiresAt,
                ttlSeconds = created.ttlSeconds,
                qrPayload = PairingPayload(
                    serverUrl = serverUrl,
                    pairCode = created.pairCode,
                    roomId = store.roomId,
                    gatewayDeviceId = store.deviceId,
                    gatewayPubKey = keys.publicKeyB64,
                ).toUri(),
            )
        }
    }

    /**
     * One poll for receivers that redeemed the code.
     *
     * For each, completes the ECDH, stores the derived root key against that
     * receiver's deviceId, and returns the SAS for the user to compare. A
     * receiver whose key fails to derive (malformed or off-curve point) is
     * skipped and logged rather than silently paired with a broken key.
     */
    suspend fun pollForJoins(): Result<List<DerivedPeer>> {
        val keys = ephemeral ?: return Result.failure(IllegalStateException("no active code"))

        return api.pending(store.authToken).map { response ->
            response.pending.mapNotNull { join ->
                // Already derived on an earlier poll — do not re-derive, that
                // would reset the peer's stored key while it is in use.
                store.peer(join.deviceId)?.let { existing ->
                    return@mapNotNull DerivedPeer(
                        existing.deviceId, existing.model, existing.label, existing.sas,
                    )
                }

                val code = join.pairCode.ifEmpty { activeCode }
                val rootKey = try {
                    keys.deriveRootKey(join.pubKey, store.roomId, code)
                } catch (e: SecurityException) {
                    Log.e(TAG, "key agreement failed for ${join.deviceId}: ${e.message}")
                    return@mapNotNull null
                }

                val sas = com.relay.core.crypto.CryptoBox
                    .create(rootKey.copyOf(), store.roomId, DeviceRole.GATEWAY, join.deviceId)
                    .sasCode

                store.storeRootKey(join.deviceId, rootKey)
                store.upsertPeer(
                    PairedPeer(
                        deviceId = join.deviceId,
                        role = DeviceRole.RECEIVER.wire,
                        model = join.model,
                        label = join.label.ifEmpty { join.model },
                        sas = sas,
                        pairedAt = join.joinedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    ),
                )

                DerivedPeer(join.deviceId, join.model, join.label, sas)
            }
        }
    }

    /** Poll until at least one receiver appears or the code expires. */
    suspend fun awaitJoins(
        ttlSeconds: Int,
        onUpdate: (List<DerivedPeer>) -> Unit,
    ) {
        var remaining = ttlSeconds
        while (remaining > 0) {
            delay(POLL_INTERVAL_MS)
            remaining -= (POLL_INTERVAL_MS / 1000).toInt()

            val derived = pollForJoins().getOrNull().orEmpty()
            if (derived.isNotEmpty()) onUpdate(derived)
        }
    }

    /** The user compared the two numbers and they matched. */
    suspend fun confirmReceiver(deviceId: String): Result<Unit> =
        api.confirm(store.authToken, deviceId).map { }

    /** Stop accepting new receivers on the outstanding code. */
    suspend fun revokeCode(): Result<Unit> = api.revokeCode(store.authToken).map {
        activeCode = ""
        ephemeral = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Receiver
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Redeem a typed code (or one scanned from the QR).
     *
     * @param expectedGatewayPubKey when the code came from a QR, the key that
     *        was photographed. Supplying it closes the man-in-the-middle window
     *        entirely: if the server hands back a different key, we refuse.
     * @return the SAS to show the user
     */
    suspend fun joinWithCode(
        pairCode: String,
        expectedGatewayPubKey: String? = null,
    ): Result<DerivedPeer> {
        val keys = KeyAgreement.generateEphemeral()
        val deviceId = ensureDeviceId("rx")

        return api.join(
            pairCode = pairCode,
            deviceId = deviceId,
            model = Build.MODEL,
            label = store.deviceLabel.ifEmpty { Build.MODEL },
            fcmToken = store.fcmToken,
            publicKeyB64 = keys.publicKeyB64,
        ).mapCatching { joined ->
            // QR path: the key we photographed is authoritative. A mismatch
            // means the relay substituted it, which is the exact attack the QR
            // exists to prevent — so fail loudly rather than fall back.
            if (expectedGatewayPubKey != null && expectedGatewayPubKey != joined.gatewayPubKey) {
                throw SecurityException(
                    "Server returned a different gateway key than the QR contained",
                )
            }

            val rootKey = keys.deriveRootKey(
                peerPublicKeyB64 = joined.gatewayPubKey,
                roomId = joined.roomId,
                pairCode = normalizeCode(pairCode),
            )

            val sas = com.relay.core.crypto.CryptoBox
                .create(
                    rootKey.copyOf(),
                    joined.roomId,
                    DeviceRole.RECEIVER,
                    joined.gatewayDeviceId,
                )
                .sasCode

            store.role = DeviceRole.RECEIVER
            store.serverUrl = serverUrl
            store.roomId = joined.roomId
            store.deviceId = joined.deviceId
            store.authToken = joined.token
            store.storeRootKey(joined.gatewayDeviceId, rootKey)
            store.upsertPeer(
                PairedPeer(
                    deviceId = joined.gatewayDeviceId,
                    role = DeviceRole.GATEWAY.wire,
                    model = joined.gatewayModel,
                    label = joined.gatewayLabel.ifEmpty { joined.gatewayModel },
                    sas = sas,
                    pairedAt = System.currentTimeMillis(),
                ),
            )

            DerivedPeer(
                deviceId = joined.gatewayDeviceId,
                model = joined.gatewayModel,
                label = joined.gatewayLabel,
                sas = sas,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A stable per-install identifier.
     *
     * ANDROID_ID is per-app-signing-key and survives reboots but not a factory
     * reset — exactly the lifetime a pairing should have. It is hashed into a
     * prefixed form so the raw value never leaves the device.
     */
    private fun ensureDeviceId(prefix: String): String = store.deviceId.ifEmpty {
        @Suppress("HardwareIds")
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val suffix = (androidId + Build.FINGERPRINT).hashCode().toUInt().toString(36)
        "$prefix-$suffix-${System.currentTimeMillis().toString(36).takeLast(6)}"
            .take(64)
            .also { store.deviceId = it }
    }

    private fun normalizeCode(raw: String): String = raw
        .uppercase()
        .replace(Regex("[\\s-]"), "")
        .replace('O', '0')
        .replace('I', '1')
        .replace('L', '1')
        .replace('U', 'V')

    private companion object {
        const val TAG = "PairingCoordinator"
        const val POLL_INTERVAL_MS = 1_500L
    }
}

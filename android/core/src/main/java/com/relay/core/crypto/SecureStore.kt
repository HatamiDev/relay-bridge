package com.relay.core.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.relay.core.model.DeviceRole
import com.relay.core.model.PairedPeer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistence for role, pairing state and per-peer root keys.
 *
 * Root keys are the crown jewels: whoever holds one can read every SMS and
 * decrypt every call setup for that pair. They are double-wrapped —
 * `EncryptedSharedPreferences` (Keystore-backed master key, StrongBox where
 * available) *plus* an inner AES-GCM envelope under a second, hardware-bound
 * key. A compromise of the preferences file alone still yields ciphertext.
 *
 * A gateway stores one root key per receiver; a receiver stores exactly one,
 * for its gateway.
 */
class SecureStore(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setRequestStrongBoxBacked(true)
                }
            }
            .build()

        runCatching {
            build(masterKey)
        }.getOrElse {
            // StrongBox provisioning fails on some firmware; retry in software.
            build(
                MasterKey.Builder(appContext, MASTER_KEY_ALIAS_FALLBACK)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
            )
        }
    }

    private fun build(masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    // ── Role ─────────────────────────────────────────────────────────────────

    /** Which half of the bridge this install is. Chosen once at first launch. */
    var role: DeviceRole
        get() = DeviceRole.fromWire(prefs.getString(KEY_ROLE, null))
        set(value) = prefs.edit().putString(KEY_ROLE, value.wire).apply()

    val isGateway: Boolean get() = role == DeviceRole.GATEWAY
    val isReceiver: Boolean get() = role == DeviceRole.RECEIVER
    val hasRole: Boolean get() = role != DeviceRole.UNSET

    // ── Identity and session ─────────────────────────────────────────────────

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var roomId: String
        get() = prefs.getString(KEY_ROOM_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ROOM_ID, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceLabel: String
        get() = prefs.getString(KEY_DEVICE_LABEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_LABEL, value).apply()

    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var fcmToken: String
        get() = prefs.getString(KEY_FCM_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    /** Paired at least once, with a usable token and at least one peer key. */
    val isPaired: Boolean
        get() = hasRole && roomId.isNotEmpty() && authToken.isNotEmpty() && peers().isNotEmpty()

    // ── Peers ────────────────────────────────────────────────────────────────

    /** Everyone this device is paired with, newest first. */
    fun peers(): List<PairedPeer> = runCatching {
        val raw = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        json.decodeFromString<List<PairedPeer>>(raw)
    }.getOrDefault(emptyList())

    fun peer(deviceId: String): PairedPeer? = peers().firstOrNull { it.deviceId == deviceId }

    /** The gateway we are paired with. Only meaningful in the RECEIVER role. */
    fun gatewayPeer(): PairedPeer? =
        peers().firstOrNull { DeviceRole.fromWire(it.role) == DeviceRole.GATEWAY }

    /** Add or replace a peer record, keeping the list stable and de-duplicated. */
    fun upsertPeer(peer: PairedPeer) {
        val updated = peers().filterNot { it.deviceId == peer.deviceId } + peer
        savePeers(updated.sortedByDescending { it.pairedAt })
    }

    /**
     * Record that the SAS was verified by eye.
     *
     * Kept on disk rather than in UI state so the row does not offer to confirm
     * a peer again after the activity is recreated, which reads as the button
     * having done nothing.
     */
    fun markConfirmed(deviceId: String) {
        val peer = peer(deviceId) ?: return
        upsertPeer(peer.copy(confirmed = true))
    }

    fun removePeer(deviceId: String) {
        savePeers(peers().filterNot { it.deviceId == deviceId })
        prefs.edit()
            .remove(wrappedKeyFor(deviceId))
            .remove(ivKeyFor(deviceId))
            .apply()
    }

    private fun savePeers(list: List<PairedPeer>) {
        // Explicit serializer rather than the reified `encodeToString(value)`.
        // The reified form is an extension in the `kotlinx.serialization`
        // package, so without that import the call silently resolves to the
        // two-argument overload and fails to compile with a confusing
        // "expected SerializationStrategy" message. Naming the serializer makes
        // it unambiguous and survives anyone tidying imports later.
        prefs.edit()
            .putString(KEY_PEERS, json.encodeToString(ListSerializer(PairedPeer.serializer()), list))
            .apply()
    }

    // ── Per-peer root keys ───────────────────────────────────────────────────

    /**
     * Persist a 32-byte root key for one peer.
     * The caller's array is zeroed on return — it must not be reused.
     */
    fun storeRootKey(peerDeviceId: String, rootKey: ByteArray) {
        require(rootKey.size == CryptoBox.ROOT_KEY_BYTES) { "root key must be 32 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEnvelopeKey())
        val wrapped = cipher.doFinal(rootKey)
        prefs.edit()
            .putString(wrappedKeyFor(peerDeviceId), wrapped.toB64())
            .putString(ivKeyFor(peerDeviceId), cipher.iv.toB64())
            .apply()
        rootKey.fill(0)
    }

    /** @return the 32-byte root key, or null. **Caller must zero it.** */
    fun loadRootKey(peerDeviceId: String): ByteArray? {
        val wrapped = prefs.getString(wrappedKeyFor(peerDeviceId), null)?.fromB64() ?: return null
        val iv = prefs.getString(ivKeyFor(peerDeviceId), null)?.fromB64() ?: return null
        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, getOrCreateEnvelopeKey(), GCMParameterSpec(128, iv))
                doFinal(wrapped)
            }
        }.getOrNull()
    }

    /** Build the crypto box for one peer, or null when we have no key for it. */
    fun cryptoBox(peerDeviceId: String): CryptoBox? {
        val root = loadRootKey(peerDeviceId) ?: return null
        return try {
            CryptoBox.create(root, roomId, role, peerDeviceId)
        } catch (e: IllegalArgumentException) {
            null
        } finally {
            root.fill(0)
        }
    }

    /** Every peer we hold a usable key for, keyed by peer deviceId. */
    fun allCryptoBoxes(): Map<String, CryptoBox> =
        peers().mapNotNull { peer -> cryptoBox(peer.deviceId)?.let { peer.deviceId to it } }.toMap()

    // ── Teardown ─────────────────────────────────────────────────────────────

    /** Remove every secret, including the Keystore envelope key. Keeps the role. */
    fun unpair() {
        val keptRole = role
        prefs.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(ENVELOPE_KEY_ALIAS)
        }
        role = keptRole
    }

    /** Full reset, including the role choice. */
    fun wipe() {
        prefs.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(ENVELOPE_KEY_ALIAS)
        }
    }

    // ── Keystore envelope key ────────────────────────────────────────────────

    private fun getOrCreateEnvelopeKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ENVELOPE_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ENVELOPE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT user-authentication-bound: the gateway must
                // relay SMS while the screen is locked.
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrappedKeyFor(peerId: String) = "root_wrapped_$peerId"
    private fun ivKeyFor(peerId: String) = "root_iv_$peerId"

    private fun ByteArray.toB64() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromB64() = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val PREFS_NAME = "relay_secure_prefs"
        const val MASTER_KEY_ALIAS = "relay_master_key"
        const val MASTER_KEY_ALIAS_FALLBACK = "relay_master_key_sw"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ENVELOPE_KEY_ALIAS = "relay_root_envelope_key"

        const val KEY_ROLE = "role"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_ROOM_ID = "room_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_LABEL = "device_label"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_PEERS = "peers"
    }
}

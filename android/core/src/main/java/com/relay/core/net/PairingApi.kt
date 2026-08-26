package com.relay.core.net

import android.net.Uri
import android.util.Base64
import com.relay.core.model.IceServerDto
import com.relay.core.model.PeerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP half of pairing.
 *
 * ```
 *  GATEWAY                        SERVER                       RECEIVER
 *     │ POST /pair/create {pubKey}   │                             │
 *     │─────────────────────────────►│                             │
 *     │◄──── {roomId, pairCode} ─────│                             │
 *     │                              │                             │
 *     │   ── user reads the code aloud / scans the QR ──────────►   │
 *     │                              │                             │
 *     │                              │ POST /pair/join {code,pubKey}│
 *     │                              │◄────────────────────────────│
 *     │                              │──► {token, gatewayPubKey}   │
 *     │ POST /pair/pending           │                             │
 *     │─────────────────────────────►│                             │
 *     │◄── [{deviceId, pubKey}] ─────│                             │
 *     │ (derive key, compare SAS)    │       (derive key, SAS)     │
 * ```
 *
 * The server relays two public keys and never sees the derived root key. It
 * *could* substitute them — which is exactly what the 6-digit SAS comparison
 * detects. See `KeyAgreement` for the full reasoning.
 */
class PairingApi(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ── Gateway side ─────────────────────────────────────────────────────────

    /** Create the room and get a code to read out. Also mints the gateway token. */
    suspend fun create(
        bootstrapSecret: String,
        deviceId: String,
        model: String,
        label: String,
        fcmToken: String,
        publicKeyB64: String,
    ): Result<PairCreate> = post(
        path = "/pair/create",
        body = JSONObject()
            .put("deviceId", deviceId)
            .put("model", model)
            .put("label", label)
            .put("fcmToken", fcmToken)
            .put("pubKey", publicKeyB64),
        bootstrapSecret = bootstrapSecret,
    ).mapCatching { json.decodeFromString<PairCreate>(it) }

    /** Receivers that redeemed the code and are waiting to be confirmed. */
    suspend fun pending(authToken: String): Result<PendingJoins> =
        get("/pair/pending", authToken).mapCatching { json.decodeFromString<PendingJoins>(it) }

    /** Accept a receiver after the SAS has been compared. */
    suspend fun confirm(authToken: String, receiverDeviceId: String): Result<String> = post(
        path = "/pair/confirm",
        body = JSONObject().put("deviceId", receiverDeviceId),
        authToken = authToken,
    )

    /** Invalidate the outstanding code without unpairing anyone. */
    suspend fun revokeCode(authToken: String): Result<String> =
        post("/pair/revoke-code", JSONObject(), authToken = authToken)

    /** Mint a brand-new code for the same room, so more receivers can join. */
    suspend fun newCode(authToken: String, publicKeyB64: String): Result<PairCreate> = post(
        path = "/pair/new-code",
        body = JSONObject().put("pubKey", publicKeyB64),
        authToken = authToken,
    ).mapCatching { json.decodeFromString<PairCreate>(it) }

    // ── Receiver side ────────────────────────────────────────────────────────

    /** Redeem a code. Returns our token plus the gateway's ephemeral public key. */
    suspend fun join(
        pairCode: String,
        deviceId: String,
        model: String,
        label: String,
        fcmToken: String,
        publicKeyB64: String,
    ): Result<PairJoin> = post(
        path = "/pair/join",
        body = JSONObject()
            .put("pairCode", pairCode)
            .put("deviceId", deviceId)
            .put("model", model)
            .put("label", label)
            .put("fcmToken", fcmToken)
            .put("pubKey", publicKeyB64),
    ).mapCatching { json.decodeFromString<PairJoin>(it) }

    // ── Shared ───────────────────────────────────────────────────────────────

    suspend fun ice(authToken: String): Result<IceBundle> =
        get("/ice", authToken).mapCatching { json.decodeFromString<IceBundle>(it) }

    suspend fun session(authToken: String): Result<SessionInfo> =
        get("/session", authToken).mapCatching { json.decodeFromString<SessionInfo>(it) }

    suspend fun registerFcmToken(authToken: String, token: String): Result<String> =
        post("/fcm/token", JSONObject().put("token", token), authToken = authToken)

    /** Remove one peer from the room. Gateways may remove receivers. */
    suspend fun removePeer(authToken: String, deviceId: String): Result<String> =
        post("/session/remove", JSONObject().put("deviceId", deviceId), authToken = authToken)

    /** Destroy the whole room. */
    suspend fun unpair(authToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/session")
                .header("Authorization", "Bearer $authToken")
                .delete()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private suspend fun post(
        path: String,
        body: JSONObject,
        bootstrapSecret: String? = null,
        authToken: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}$path")
                .post(body.toString().toRequestBody(JSON_MEDIA))
            bootstrapSecret?.let { builder.header("x-bootstrap-secret", it) }
            authToken?.let { builder.header("Authorization", "Bearer $it") }

            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    error("HTTP ${response.code}${error?.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()}")
                }
                text
            }
        }
    }

    private suspend fun get(path: String, authToken: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}$path")
                    .header("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            }
        }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

// ── DTOs ─────────────────────────────────────────────────────────────────────

@Serializable
data class PairCreate(
    val pairCode: String,
    val roomId: String,
    val deviceId: String,
    val token: String,
    val expiresAt: Long,
    val ttlSeconds: Int,
    val iceServers: List<IceServerDto> = emptyList(),
)

@Serializable
data class PairJoin(
    val token: String,
    val roomId: String,
    val deviceId: String,
    val gatewayDeviceId: String,
    val gatewayPubKey: String,
    val gatewayModel: String = "",
    val gatewayLabel: String = "",
    val expiresAt: Long = 0L,
    val iceServers: List<IceServerDto> = emptyList(),
)

@Serializable
data class PendingJoin(
    val deviceId: String,
    val pubKey: String,
    val model: String = "",
    val label: String = "",
    val joinedAt: Long = 0L,
    val pairCode: String = "",
)

@Serializable
data class PendingJoins(val pending: List<PendingJoin> = emptyList())

@Serializable
data class IceBundle(
    val iceServers: List<IceServerDto> = emptyList(),
    val expiresAt: Long = 0L,
    val ttlSeconds: Int = 3600,
)

@Serializable
data class SessionInfo(
    val roomId: String = "",
    val role: String = "",
    val peers: List<PeerInfo> = emptyList(),
    val queuedForMe: Int = 0,
    val serverTime: Long = 0L,
)

/**
 * Optional QR fast path.
 *
 * `relay://pair?h=<serverUrl>&c=<pairCode>&r=<roomId>&g=<gatewayDeviceId>&k=<gatewayPubKey>`
 *
 * Carrying the gateway's ephemeral public key optically removes the
 * man-in-the-middle window that the typed-code path has to close with a manual
 * SAS comparison — the receiver can verify the key it got over HTTP matches the
 * one it photographed.
 */
data class PairingPayload(
    val serverUrl: String,
    val pairCode: String,
    val roomId: String,
    val gatewayDeviceId: String,
    val gatewayPubKey: String,
) {
    fun toUri(): String = Uri.Builder()
        .scheme("relay").authority("pair")
        .appendQueryParameter("h", serverUrl)
        .appendQueryParameter("c", pairCode)
        .appendQueryParameter("r", roomId)
        .appendQueryParameter("g", gatewayDeviceId)
        .appendQueryParameter("k", gatewayPubKey)
        .build()
        .toString()

    companion object {
        /** @return null when the scanned string is not a Relay pairing URI. */
        fun parse(raw: String): PairingPayload? = runCatching {
            val uri = Uri.parse(raw.trim())
            if (uri.scheme != "relay" || uri.authority != "pair") return null

            val host = uri.getQueryParameter("h").orEmpty()
            val code = uri.getQueryParameter("c").orEmpty()
            val room = uri.getQueryParameter("r").orEmpty()
            val gateway = uri.getQueryParameter("g").orEmpty()
            val key = uri.getQueryParameter("k").orEmpty()
            if (host.isEmpty() || code.isEmpty() || key.isEmpty()) return null

            // Reject anything that is not a decodable key up front.
            Base64.decode(key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

            PairingPayload(host, code, room, gateway, key)
        }.getOrNull()
    }
}

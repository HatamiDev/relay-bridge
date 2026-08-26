package com.relay.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire protocol — see docs/01-ARCHITECTURE.md.
 *
 * v2 changes (single-APK, multi-receiver):
 *  • One app, two roles: GATEWAY (holds the SIM) and RECEIVER (does not).
 *  • One gateway may serve MANY receivers. Each receiver negotiates its own
 *    root key with the gateway, so every envelope is addressed to exactly one
 *    device via [Envelope.dst] and encrypted under that pair's key.
 *  • Pairing is by short typed code + ECDH, with a 6-digit SAS to detect an
 *    active man-in-the-middle. QR remains the fast path.
 */

// ─────────────────────────────────────────────────────────────────────────────

enum class DeviceRole {
    /** Holds the SIM. Intercepts SMS and cellular calls, relays them out. */
    GATEWAY,

    /** Holds no SIM. Receives mirrored SMS and rings for relayed calls. */
    RECEIVER,

    /** Not yet chosen — the app shows the role picker. */
    UNSET;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): DeviceRole = when (value?.lowercase()) {
            "gateway" -> GATEWAY
            "receiver", "client" -> RECEIVER
            else -> UNSET
        }
    }
}

object Ev {
    // SMS
    const val SMS_INBOUND = "sms:inbound"
    const val SMS_OUTBOUND = "sms:outbound"
    const val SMS_STATUS = "sms:status"
    const val SMS_SYNC = "sms:sync"
    const val SMS_SYNC_RESULT = "sms:sync:result"

    // Contacts
    const val CONTACTS_SYNC = "contacts:sync"
    const val CONTACTS_RESULT = "contacts:result"

    // Call control
    const val CALL_INCOMING = "call:incoming"
    const val CALL_PLACE = "call:place"
    const val CALL_ANSWER = "call:answer"
    const val CALL_REJECT = "call:reject"
    const val CALL_HANGUP = "call:hangup"
    const val CALL_DTMF = "call:dtmf"
    const val CALL_MUTE = "call:mute"
    const val CALL_STATE = "call:state"

    // WebRTC signaling
    const val RTC_OFFER = "rtc:offer"
    const val RTC_ANSWER = "rtc:answer"
    const val RTC_ICE = "rtc:ice"
    const val RTC_RENEGOTIATE = "rtc:renegotiate"
    const val RTC_STATS = "rtc:stats"

    // Server-handled — never encrypted, never carries user content
    const val SESSION_READY = "session:ready"
    const val PEER_PRESENCE = "peer:presence"
    const val PEER_JOINED = "peer:joined"
    const val PEER_LEFT = "peer:left"
    const val PRESENCE = "presence"
    const val ICE_REFRESH = "ice:refresh"
    const val ICE_SERVERS = "ice:servers"
    const val FCM_TOKEN = "fcm:token"
    const val QUEUE_FLUSHED = "queue:flushed"
}

/**
 * Encrypted transport wrapper.
 *
 * [dst] is the deviceId of the single intended recipient. With several
 * receivers in one room the gateway emits one envelope per receiver, each
 * sealed under that pair's own key — so a receiver cannot read traffic meant
 * for its sibling even though both share the same relay server.
 *
 * `ct` is base64url(ciphertext ‖ 16-byte GCM tag).
 */
@Serializable
data class Envelope(
    val v: Int = 2,
    val ev: String,
    val dst: String,
    val sq: Long,
    val ts: Long,
    val iv: String,
    val ct: String,
) {
    companion object
}

// ─────────────────────────────────────────────────────────────────────────────
// Pairing
// ─────────────────────────────────────────────────────────────────────────────

/** A device this one is paired with, as shown in the "Paired devices" list. */
@Serializable
data class PairedPeer(
    val deviceId: String,
    val role: String,
    val model: String = "",
    val label: String = "",
    /** 6-digit short authentication string — must match on both screens. */
    val sas: String = "",
    val online: Boolean = false,
    val pairedAt: Long = 0L,
    val lastSeen: Long = 0L,
)

// ─────────────────────────────────────────────────────────────────────────────
// Payloads
// ─────────────────────────────────────────────────────────────────────────────

enum class SmsState { QUEUED, SENDING, SENT, DELIVERED, FAILED }

enum class CallState { IDLE, RINGING, DIALING, CONNECTING, ACTIVE, HELD, ENDED }

@Serializable
data class SmsMessage(
    val id: String,
    val address: String,
    val body: String,
    val ts: Long,
    val inbound: Boolean,
    val threadId: String = address,
    val simSlot: Int = 0,
    val state: SmsState = if (inbound) SmsState.DELIVERED else SmsState.QUEUED,
)

@Serializable
data class SmsOutboundRequest(
    val id: String,
    val to: String,
    val body: String,
    val ts: Long = System.currentTimeMillis(),
    val simSlot: Int = 0,
)

@Serializable
data class SmsStatusUpdate(
    val id: String,
    val state: SmsState,
    val errorCode: Int? = null,
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
data class SmsSyncRequest(val sinceTs: Long = 0L, val limit: Int = 200)

@Serializable
data class SmsSyncResult(val messages: List<SmsMessage>, val hasMore: Boolean)

@Serializable
data class Contact(
    val id: String,
    val name: String,
    val number: String,
    @SerialName("photo") val photoB64: String = "",
    val pinned: Boolean = false,
    val lastSeenTs: Long = 0L,
)

@Serializable
data class ContactsResult(val contacts: List<Contact>)

@Serializable
data class CallIncoming(
    val callId: String,
    val from: String,
    val displayName: String = "",
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
data class CallPlace(val callId: String, val to: String)

@Serializable
data class CallRef(val callId: String, val reason: String = "")

@Serializable
data class CallDtmf(val callId: String, val tone: String)

@Serializable
data class CallMute(val callId: String, val muted: Boolean)

@Serializable
data class CallStateUpdate(
    val callId: String,
    val state: CallState,
    val cause: String = "",
    /** Which capture strategy the gateway actually managed to open. */
    val audioMode: String = "",
    val ts: Long = System.currentTimeMillis(),
)

// ── WebRTC ───────────────────────────────────────────────────────────────────

@Serializable
data class RtcSdp(val callId: String, val type: String, val sdp: String)

@Serializable
data class RtcIce(
    val callId: String,
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String,
)

@Serializable
data class RtcStats(
    val callId: String,
    val rttMs: Int,
    val jitterMs: Int,
    val lossPct: Double,
    val bitrateKbps: Int,
    val codec: String,
    val audioLevel: Double = 0.0,
)

// ── Unencrypted control ──────────────────────────────────────────────────────

@Serializable
data class IceServerDto(
    val urls: List<String>,
    val username: String = "",
    val credential: String = "",
)

@Serializable
data class PeerInfo(
    val deviceId: String,
    val role: String,
    val model: String = "",
    val online: Boolean = false,
    val lastSeen: Long = 0L,
)

@Serializable
data class SessionReady(
    val role: String,
    val deviceId: String,
    val peers: List<PeerInfo> = emptyList(),
    val iceServers: List<IceServerDto> = emptyList(),
    val expiresAt: Long = 0L,
    val serverTime: Long = 0L,
)

@Serializable
data class PeerPresence(
    val deviceId: String = "",
    val role: String,
    val online: Boolean,
    val model: String = "",
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val signalDbm: Int = 0,
    val simState: String = "",
    val appVersion: String = "",
    val ts: Long = 0L,
)

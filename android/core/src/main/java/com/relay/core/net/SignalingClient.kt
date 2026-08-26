package com.relay.core.net

import android.util.Log
import com.relay.core.crypto.CryptoBox
import com.relay.core.model.DeviceRole
import com.relay.core.model.Envelope
import com.relay.core.model.Ev
import com.relay.core.model.IceServerDto
import com.relay.core.model.PeerInfo
import com.relay.core.model.PeerPresence
import com.relay.core.model.SessionReady
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport layer.
 *
 * Holds one authenticated Socket.IO connection and a [CryptoBox] **per peer**.
 * A gateway serving three receivers has three boxes here and fans an outbound
 * message out as three separately-sealed envelopes; a receiver has one.
 *
 * Envelopes that fail authentication, replay or addressing checks are dropped
 * and counted — never passed upward.
 */
class SignalingClient(
    private val serverUrl: String,
    private val authToken: String,
    private val role: DeviceRole,
    private val ownDeviceId: String,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var socket: Socket? = null
    private val connecting = AtomicBoolean(false)

    /** peerDeviceId → box. Mutated as peers pair and unpair. */
    private val boxes = ConcurrentHashMap<String, CryptoBox>()

    // ── Observable state ─────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val presence: StateFlow<Map<String, PeerPresence>> = _presence.asStateFlow()

    private val _iceServers = MutableStateFlow<List<IceServerDto>>(emptyList())
    val iceServers: StateFlow<List<IceServerDto>> = _iceServers.asStateFlow()

    private val _events = MutableSharedFlow<RelayEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<RelayEvent> = _events.asSharedFlow()

    @Volatile var rejectedEnvelopes: Long = 0L
        private set

    /** True when at least one paired peer currently has a live socket. */
    val anyPeerOnline: Boolean get() = _peers.value.any { it.online }

    // ── Peer keys ────────────────────────────────────────────────────────────

    fun setBoxes(newBoxes: Map<String, CryptoBox>) {
        boxes.clear()
        boxes.putAll(newBoxes)
        Log.i(TAG, "loaded ${boxes.size} peer key(s)")
    }

    fun addBox(peerDeviceId: String, box: CryptoBox) {
        boxes[peerDeviceId] = box
    }

    fun removeBox(peerDeviceId: String) {
        boxes.remove(peerDeviceId)
    }

    fun knownPeerIds(): Set<String> = boxes.keys.toSet()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun connect() {
        if (!connecting.compareAndSet(false, true)) return
        if (socket?.connected() == true) { connecting.set(false); return }

        _connectionState.value = ConnectionState.CONNECTING

        val options = IO.Options.builder()
            .setPath("/socket.io")
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(true)
            .setReconnectionAttempts(Int.MAX_VALUE)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(30_000)
            .setRandomizationFactor(0.5)
            .setTimeout(20_000)
            .setForceNew(true)
            .setAuth(mapOf("token" to authToken, "role" to role.wire))
            .build()

        val s = IO.socket(URI.create("${serverUrl.trimEnd('/')}/relay"), options)
        socket = s

        wireLifecycle(s)
        wireEncryptedEvents(s)
        wirePlainEvents(s)

        s.connect()
        connecting.set(false)
    }

    fun disconnect() {
        socket?.let { it.off(); it.disconnect(); it.close() }
        socket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _peers.value = _peers.value.map { it.copy(online = false) }
    }

    val isConnected: Boolean get() = socket?.connected() == true

    // ── Sending ──────────────────────────────────────────────────────────────

    /**
     * Seal and emit to one specific peer.
     * @return false when the socket is down or we hold no key for that peer
     */
    fun sendTo(peerDeviceId: String, event: String, plaintextJson: String): Boolean {
        val s = socket ?: return false
        if (!s.connected()) return false
        val box = boxes[peerDeviceId] ?: run {
            Log.w(TAG, "no key for $peerDeviceId; dropping $event")
            return false
        }
        return try {
            s.emit(event, box.seal(event, plaintextJson).toJson())
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendTo($peerDeviceId, $event) failed", e)
            false
        }
    }

    /**
     * Seal and emit to **every** paired peer, one envelope each.
     *
     * This is the gateway's normal path: sibling receivers hold different keys,
     * so a single broadcast envelope is impossible by construction.
     *
     * @return how many peers it reached
     */
    fun broadcast(event: String, plaintextJson: String): Int {
        var delivered = 0
        for (peerId in boxes.keys) {
            if (sendTo(peerId, event, plaintextJson)) delivered++
        }
        return delivered
    }

    /** Receiver convenience: send to the single gateway we are paired with. */
    fun sendToGateway(event: String, plaintextJson: String): Boolean {
        val gatewayId = boxes.keys.firstOrNull() ?: return false
        return sendTo(gatewayId, event, plaintextJson)
    }

    // ── Unencrypted control (carries no user content) ────────────────────────

    fun sendPresence(
        batteryPct: Int,
        charging: Boolean,
        signalDbm: Int,
        simState: String,
        appVersion: String,
        model: String,
    ) {
        socket?.takeIf { it.connected() }?.emit(
            Ev.PRESENCE,
            JSONObject()
                .put("batteryPct", batteryPct)
                .put("charging", charging)
                .put("signalDbm", signalDbm)
                .put("simState", simState)
                .put("appVersion", appVersion)
                .put("model", model),
        )
    }

    fun sendRtcStats(payloadJson: String) {
        socket?.takeIf { it.connected() }?.emit(Ev.RTC_STATS, JSONObject(payloadJson))
    }

    fun registerFcmToken(token: String) {
        socket?.takeIf { it.connected() }?.emit(Ev.FCM_TOKEN, JSONObject().put("token", token))
    }

    fun refreshIceServers() {
        socket?.takeIf { it.connected() }?.emit(Ev.ICE_REFRESH, JSONObject())
    }

    // ── Wiring ───────────────────────────────────────────────────────────────

    private fun wireLifecycle(s: Socket) {
        s.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "socket connected")
            _connectionState.value = ConnectionState.CONNECTED
        }
        s.on(Socket.EVENT_DISCONNECT) { args ->
            Log.w(TAG, "socket disconnected: ${args.firstOrNull()}")
            _connectionState.value = ConnectionState.DISCONNECTED
            _peers.value = _peers.value.map { it.copy(online = false) }
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val message = args.firstOrNull()?.toString().orEmpty()
            Log.w(TAG, "connect error: $message")
            _connectionState.value =
                if (message.contains("unauthorized") || message.contains("device_not_enrolled")) {
                    ConnectionState.UNAUTHORIZED
                } else {
                    ConnectionState.RECONNECTING
                }
        }
    }

    private fun wirePlainEvents(s: Socket) {
        s.on(Ev.SESSION_READY) { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            runCatching {
                val ready = json.decodeFromString<SessionReady>(obj.toString())
                _peers.value = ready.peers
                if (ready.iceServers.isNotEmpty()) _iceServers.value = ready.iceServers
                _events.tryEmit(RelayEvent.SessionReady(ready))
            }.onFailure { Log.e(TAG, "session:ready parse failed", it) }
        }

        s.on(Ev.ICE_SERVERS) { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            runCatching {
                val array = obj.optJSONArray("iceServers") ?: return@on
                val servers = json.decodeFromString<List<IceServerDto>>(array.toString())
                if (servers.isNotEmpty()) _iceServers.value = servers
            }.onFailure { Log.e(TAG, "ice:servers parse failed", it) }
        }

        s.on(Ev.PEER_PRESENCE) { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            runCatching {
                val p = json.decodeFromString<PeerPresence>(obj.toString())
                _presence.value = _presence.value + (p.deviceId to p)
                _peers.value = _peers.value.map {
                    if (it.deviceId == p.deviceId) it.copy(online = p.online) else it
                }
                _events.tryEmit(RelayEvent.Presence(p))
            }.onFailure { Log.e(TAG, "peer:presence parse failed", it) }
        }

        s.on(Ev.PEER_JOINED) { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            runCatching {
                val peer = json.decodeFromString<PeerInfo>(obj.toString())
                _peers.value = _peers.value.filterNot { it.deviceId == peer.deviceId } + peer
                _events.tryEmit(RelayEvent.PeerJoined(peer))
            }
        }

        s.on(Ev.PEER_LEFT) { args ->
            val deviceId = (args.firstOrNull() as? JSONObject)?.optString("deviceId").orEmpty()
            if (deviceId.isEmpty()) return@on
            _peers.value = _peers.value.filterNot { it.deviceId == deviceId }
            _events.tryEmit(RelayEvent.PeerLeft(deviceId))
        }

        s.on(Ev.QUEUE_FLUSHED) { args ->
            val count = (args.firstOrNull() as? JSONObject)?.optInt("count") ?: 0
            Log.i(TAG, "offline queue flushed: $count")
            _events.tryEmit(RelayEvent.QueueFlushed(count))
        }
    }

    private fun wireEncryptedEvents(s: Socket) {
        for (event in ENCRYPTED_EVENTS) {
            s.on(event) { args ->
                val obj = args.firstOrNull() as? JSONObject ?: return@on
                val src = obj.optString("src")        // stamped by the server
                val envelope = runCatching { Envelope.fromJson(obj) }.getOrNull()

                if (envelope == null) {
                    rejectedEnvelopes++
                    Log.w(TAG, "malformed envelope on $event")
                    return@on
                }

                // Pick the key by sender. A receiver has exactly one box, so
                // fall back to it when the server omitted `src`.
                val box = boxes[src] ?: boxes.values.singleOrNull()
                if (box == null) {
                    rejectedEnvelopes++
                    Log.w(TAG, "no key for sender '$src' on $event")
                    return@on
                }

                val plaintext = try {
                    box.open(envelope, expectedDst = ownDeviceId)
                } catch (e: SecurityException) {
                    rejectedEnvelopes++
                    Log.e(TAG, "REJECTED $event from '$src': ${e.message}")
                    return@on
                }

                _events.tryEmit(
                    RelayEvent.Encrypted(
                        event = event,
                        json = plaintext,
                        fromDeviceId = src.ifEmpty { box.peerDeviceId },
                    ),
                )
            }
        }
    }

    private fun Envelope.toJson(): JSONObject = JSONObject()
        .put("v", v).put("ev", ev).put("dst", dst)
        .put("sq", sq).put("ts", ts).put("iv", iv).put("ct", ct)

    companion object {
        private const val TAG = "SignalingClient"

        private val ENCRYPTED_EVENTS = listOf(
            Ev.SMS_INBOUND, Ev.SMS_OUTBOUND, Ev.SMS_STATUS,
            Ev.SMS_SYNC, Ev.SMS_SYNC_RESULT,
            Ev.CONTACTS_SYNC, Ev.CONTACTS_RESULT,
            Ev.CALL_INCOMING, Ev.CALL_PLACE, Ev.CALL_ANSWER, Ev.CALL_REJECT,
            Ev.CALL_HANGUP, Ev.CALL_DTMF, Ev.CALL_MUTE, Ev.CALL_STATE,
            Ev.RTC_OFFER, Ev.RTC_ANSWER, Ev.RTC_ICE, Ev.RTC_RENEGOTIATE,
        )
    }
}

/** Parse an envelope from a Socket.IO payload. Throws on any missing field. */
internal fun Envelope.Companion.fromJson(obj: JSONObject): Envelope = Envelope(
    v = obj.getInt("v"),
    ev = obj.getString("ev"),
    dst = obj.getString("dst"),
    sq = obj.getLong("sq"),
    ts = obj.getLong("ts"),
    iv = obj.getString("iv"),
    ct = obj.getString("ct"),
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, UNAUTHORIZED }

sealed interface RelayEvent {
    /** A decrypted application payload. [json] is the plaintext body. */
    data class Encrypted(
        val event: String,
        val json: String,
        val fromDeviceId: String,
    ) : RelayEvent

    data class SessionReady(val session: com.relay.core.model.SessionReady) : RelayEvent
    data class Presence(val presence: PeerPresence) : RelayEvent
    data class PeerJoined(val peer: PeerInfo) : RelayEvent
    data class PeerLeft(val deviceId: String) : RelayEvent
    data class QueueFlushed(val count: Int) : RelayEvent
}

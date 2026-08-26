package com.relay.client.data

import android.content.Context
import android.util.Log
import com.relay.core.crypto.SecureStore
import com.relay.core.model.CallDtmf
import com.relay.core.model.CallIncoming
import com.relay.core.model.CallMute
import com.relay.core.model.CallPlace
import com.relay.core.model.CallRef
import com.relay.core.model.CallState
import com.relay.core.model.CallStateUpdate
import com.relay.core.model.Contact
import com.relay.core.model.ContactsResult
import com.relay.core.model.DeviceRole
import com.relay.core.model.Ev
import com.relay.core.model.IceServerDto
import com.relay.core.model.PeerPresence
import com.relay.core.model.RtcIce
import com.relay.core.model.RtcSdp
import com.relay.core.model.SmsMessage
import com.relay.core.model.SmsOutboundRequest
import com.relay.core.model.SmsState
import com.relay.core.model.SmsStatusUpdate
import com.relay.core.model.SmsSyncRequest
import com.relay.core.model.SmsSyncResult
import com.relay.core.net.ConnectionState
import com.relay.core.net.RelayEvent
import com.relay.core.net.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Agent 4 — the client's single source of truth.
 *
 * Owns the socket, holds the in-memory message and contact stores, and exposes
 * everything the UI needs as StateFlows. Deliberately a singleton: a second
 * socket for the same device would be evicted by the server, and two competing
 * message stores would produce ghost duplicates in the thread list.
 *
 * Messages are kept in memory plus a small on-disk cache. There is no local
 * database by design — the gateway's SMS provider is authoritative, and
 * `sms:sync` rebuilds history on demand. That removes an entire class of
 * "my two devices disagree" bugs.
 */
class RelayRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val secureStore = SecureStore(appContext)
    private val cache = MessageCache(appContext)

    private var signaling: SignalingClient? = null

    // ── Observable state ─────────────────────────────────────────────────────

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _gatewayPresence = MutableStateFlow<PeerPresence?>(null)
    val gatewayPresence: StateFlow<PeerPresence?> = _gatewayPresence.asStateFlow()

    private val _threads = MutableStateFlow<List<Conversation>>(emptyList())
    val threads: StateFlow<List<Conversation>> = _threads.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _iceServers = MutableStateFlow<List<IceServerDto>>(emptyList())
    val iceServers: StateFlow<List<IceServerDto>> = _iceServers.asStateFlow()

    private val _callState = MutableStateFlow(ActiveCall())
    val callState: StateFlow<ActiveCall> = _callState.asStateFlow()

    /** One-shot UI signals: ring, stop ringing, apply remote SDP/ICE. */
    private val _signals = MutableSharedFlow<Signal>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val signals: SharedFlow<Signal> = _signals.asSharedFlow()

    /** Per-thread message store, newest last. */
    private val messages = LinkedHashMap<String, MutableList<SmsMessage>>()

    init {
        scope.launch {
            cache.load().forEach { message -> insertMessage(message, persist = false) }
            rebuildThreads()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun connect() {
        if (!secureStore.isPaired) {
            Log.w(TAG, "not paired")
            return
        }
        if (signaling?.isConnected == true) return

        val boxes = secureStore.allCryptoBoxes()
        if (boxes.isEmpty()) {
            Log.e(TAG, "no usable root key for the gateway")
            return
        }

        val client = SignalingClient(
            serverUrl = secureStore.serverUrl,
            authToken = secureStore.authToken,
            role = DeviceRole.RECEIVER,
            ownDeviceId = secureStore.deviceId,
        )
        client.setBoxes(boxes)
        signaling = client

        scope.launch { client.events.collect(::onEvent) }
        scope.launch { client.connectionState.collect { _connection.value = it } }
        // A receiver has exactly one peer — the gateway — so pick its entry
        // out of the presence map by role rather than by a known deviceId.
        scope.launch {
            client.presence.collect { byDevice ->
                byDevice.values.firstOrNull { DeviceRole.fromWire(it.role) == DeviceRole.GATEWAY }
                    ?.let { _gatewayPresence.value = it }
            }
        }
        scope.launch { client.iceServers.collect { if (it.isNotEmpty()) _iceServers.value = it } }

        client.connect()
        secureStore.fcmToken.takeIf { it.isNotEmpty() }?.let(client::registerFcmToken)
    }

    fun disconnect() {
        signaling?.disconnect()
        signaling = null
    }

    val isConnected: Boolean get() = signaling?.isConnected == true

    /** Envelopes rejected by the AEAD/replay guard — surfaced in Settings. */
    val rejectedEnvelopes: Long get() = signaling?.rejectedEnvelopes ?: 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Inbound
    // ─────────────────────────────────────────────────────────────────────────

    private fun onEvent(event: RelayEvent) {
        when (event) {
            is RelayEvent.Encrypted -> handlePayload(event.event, event.json)
            is RelayEvent.SessionReady -> {
                if (event.session.iceServers.isNotEmpty()) {
                    _iceServers.value = event.session.iceServers
                }
                // First connection of the session: backfill anything we missed.
                requestSync()
                requestContacts()
            }
            is RelayEvent.Presence -> {
                // Also updated by the client.presence map collector in connect();
                // this branch exists so the sealed `when` stays exhaustive.
                if (DeviceRole.fromWire(event.presence.role) == DeviceRole.GATEWAY) {
                    _gatewayPresence.value = event.presence
                }
            }
            is RelayEvent.PeerJoined -> Log.i(TAG, "peer joined: ${event.peer.deviceId}")
            is RelayEvent.PeerLeft -> Log.i(TAG, "peer left: ${event.deviceId}")
            is RelayEvent.QueueFlushed -> Log.i(TAG, "server queue flushed: ${event.count}")
        }
    }

    private fun handlePayload(event: String, payload: String) {
        runCatching {
            when (event) {
                Ev.SMS_INBOUND -> {
                    val message = json.decodeFromString<SmsMessage>(payload)
                    insertMessage(message)
                    rebuildThreads()
                    scope.launch { _signals.emit(Signal.NewMessage(message)) }
                }

                Ev.SMS_STATUS -> {
                    val update = json.decodeFromString<SmsStatusUpdate>(payload)
                    updateMessageState(update)
                    rebuildThreads()
                }

                Ev.SMS_SYNC_RESULT -> {
                    val result = json.decodeFromString<SmsSyncResult>(payload)
                    result.messages.forEach { insertMessage(it) }
                    rebuildThreads()
                    Log.i(TAG, "synced ${result.messages.size} messages (more=${result.hasMore})")
                    if (result.hasMore) {
                        val newest = result.messages.maxOfOrNull { it.ts } ?: 0L
                        requestSync(sinceTs = newest)
                    }
                }

                Ev.CONTACTS_RESULT -> {
                    _contacts.value = json.decodeFromString<ContactsResult>(payload).contacts
                    rebuildThreads()   // names may now resolve
                }

                Ev.CALL_INCOMING -> {
                    val incoming = json.decodeFromString<CallIncoming>(payload)
                    _callState.value = ActiveCall(
                        callId = incoming.callId,
                        peerNumber = incoming.from,
                        peerName = displayNameFor(incoming.from).ifEmpty { incoming.displayName },
                        state = CallState.RINGING,
                        inbound = true,
                        startedAt = incoming.ts,
                    )
                    scope.launch { _signals.emit(Signal.IncomingCall(incoming)) }
                }

                Ev.CALL_STATE -> {
                    val update = json.decodeFromString<CallStateUpdate>(payload)
                    if (update.callId != _callState.value.callId && update.state != CallState.ENDED) return
                    _callState.value = _callState.value.copy(
                        state = update.state,
                        audioMode = update.audioMode,
                        cause = update.cause,
                        connectedAt = if (update.state == CallState.ACTIVE &&
                            _callState.value.connectedAt == 0L
                        ) {
                            System.currentTimeMillis()
                        } else {
                            _callState.value.connectedAt
                        },
                    )
                    if (update.state == CallState.ENDED) {
                        val finished = _callState.value
                        if (finished.peerNumber.isNotEmpty()) {
                            CallLogStore.recordEnded(
                                number = finished.peerNumber,
                                inbound = finished.inbound,
                                startedAt = finished.startedAt,
                                connectedAt = finished.connectedAt,
                                audioMode = finished.audioMode,
                            )
                        }
                        scope.launch { _signals.emit(Signal.CallEnded(update.cause)) }
                        _callState.value = ActiveCall()
                    }
                }

                Ev.RTC_OFFER -> {
                    val sdp = json.decodeFromString<RtcSdp>(payload)
                    scope.launch { _signals.emit(Signal.RemoteOffer(sdp.callId, sdp.sdp)) }
                }

                Ev.RTC_ICE -> {
                    val ice = json.decodeFromString<RtcIce>(payload)
                    scope.launch { _signals.emit(Signal.RemoteIce(ice)) }
                }

                else -> Log.d(TAG, "unhandled event $event")
            }
        }.onFailure { Log.e(TAG, "failed handling $event", it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Outbound
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Optimistically insert the message as QUEUED, then send. The gateway's
     * `sms:status` stream drives it to SENT/DELIVERED/FAILED.
     */
    fun sendSms(to: String, body: String): SmsMessage {
        val message = SmsMessage(
            id = UUID.randomUUID().toString(),
            address = to,
            body = body,
            ts = System.currentTimeMillis(),
            inbound = false,
            threadId = normalize(to),
            state = SmsState.QUEUED,
        )
        insertMessage(message)
        rebuildThreads()

        emit(Ev.SMS_OUTBOUND, SmsOutboundRequest(id = message.id, to = to, body = body))
        return message
    }

    fun requestSync(sinceTs: Long = newestTimestamp(), limit: Int = 200) {
        emit(Ev.SMS_SYNC, SmsSyncRequest(sinceTs, limit))
    }

    fun requestContacts() = emit(Ev.CONTACTS_SYNC, EmptyPayload())

    // ── Call control ─────────────────────────────────────────────────────────

    fun placeCall(number: String): String {
        val callId = UUID.randomUUID().toString()
        _callState.value = ActiveCall(
            callId = callId,
            peerNumber = number,
            peerName = displayNameFor(number),
            state = CallState.DIALING,
            inbound = false,
            startedAt = System.currentTimeMillis(),
        )
        emit(Ev.CALL_PLACE, CallPlace(callId, number))
        return callId
    }

    fun answerCall(callId: String) = emit(Ev.CALL_ANSWER, CallRef(callId))
    fun rejectCall(callId: String) = emit(Ev.CALL_REJECT, CallRef(callId, "declined"))
    fun hangUp(callId: String) = emit(Ev.CALL_HANGUP, CallRef(callId, "local_hangup"))
    fun sendDtmf(callId: String, tone: String) = emit(Ev.CALL_DTMF, CallDtmf(callId, tone))
    fun setFarEndMuted(callId: String, muted: Boolean) =
        emit(Ev.CALL_MUTE, CallMute(callId, muted))

    fun sendAnswerSdp(callId: String, sdp: String) =
        emit(Ev.RTC_ANSWER, RtcSdp(callId, "answer", sdp))

    fun sendIce(ice: RtcIce) = emit(Ev.RTC_ICE, ice)

    /** Ask the gateway (the offerer) to perform an ICE restart. */
    fun requestRenegotiate(callId: String) = emit(Ev.RTC_RENEGOTIATE, CallRef(callId, "ice_failed"))

    fun reportStats(statsJson: String) = signaling?.sendRtcStats(statsJson)

    fun refreshIceServers() = signaling?.refreshIceServers()

    fun registerFcmToken(token: String) {
        secureStore.fcmToken = token
        signaling?.registerFcmToken(token)
    }

    private inline fun <reified T> emit(event: String, payload: T) {
        val body = json.encodeToString(payload)
        if (signaling?.sendToGateway(event, body) != true) {
            Log.w(TAG, "send failed for $event; reconnecting")
            connect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Store
    // ─────────────────────────────────────────────────────────────────────────

    @Synchronized
    private fun insertMessage(message: SmsMessage, persist: Boolean = true) {
        val bucket = messages.getOrPut(message.threadId) { mutableListOf() }
        val existing = bucket.indexOfFirst { it.id == message.id }
        if (existing >= 0) bucket[existing] = message else bucket.add(message)
        bucket.sortBy { it.ts }
        while (bucket.size > MAX_PER_THREAD) bucket.removeAt(0)
        if (persist) scope.launch { cache.save(allMessages()) }
    }

    @Synchronized
    private fun updateMessageState(update: SmsStatusUpdate) {
        for (bucket in messages.values) {
            val index = bucket.indexOfFirst { it.id == update.id }
            if (index >= 0) {
                bucket[index] = bucket[index].copy(state = update.state)
                scope.launch { cache.save(allMessages()) }
                return
            }
        }
    }

    @Synchronized
    fun messagesFor(threadId: String): List<SmsMessage> =
        messages[threadId]?.toList() ?: emptyList()

    @Synchronized
    private fun allMessages(): List<SmsMessage> = messages.values.flatten()

    @Synchronized
    private fun newestTimestamp(): Long =
        messages.values.flatten().maxOfOrNull { it.ts } ?: 0L

    @Synchronized
    private fun rebuildThreads() {
        val contactIndex = _contacts.value.associateBy { normalize(it.number) }
        _threads.value = messages.entries
            .mapNotNull { (threadId, bucket) ->
                val last = bucket.lastOrNull() ?: return@mapNotNull null
                val contact = contactIndex[threadId]
                Conversation(
                    threadId = threadId,
                    address = last.address,
                    displayName = contact?.name ?: last.address,
                    photoB64 = contact?.photoB64.orEmpty(),
                    lastMessage = last.body,
                    lastTimestamp = last.ts,
                    lastState = last.state,
                    lastInbound = last.inbound,
                    unread = bucket.count { it.inbound && it.ts > lastReadFor(threadId) },
                    pinned = contact?.pinned == true,
                )
            }
            .sortedByDescending { it.lastTimestamp }
    }

    // ── Read tracking ────────────────────────────────────────────────────────

    private val readMarks = mutableMapOf<String, Long>()

    @Synchronized
    private fun lastReadFor(threadId: String): Long = readMarks[threadId] ?: 0L

    @Synchronized
    fun markRead(threadId: String) {
        readMarks[threadId] = System.currentTimeMillis()
        rebuildThreads()
    }

    fun displayNameFor(number: String): String {
        val key = normalize(number)
        return _contacts.value.firstOrNull { normalize(it.number) == key }?.name.orEmpty()
    }

    fun totalUnread(): Int = _threads.value.sumOf { it.unread }

    private fun normalize(address: String) =
        address.filter { it.isDigit() || it == '+' }.ifEmpty { address }

    // ─────────────────────────────────────────────────────────────────────────

    @kotlinx.serialization.Serializable
    private class EmptyPayload

    companion object {
        private const val TAG = "RelayRepository"
        private const val MAX_PER_THREAD = 500

        @Volatile private var singleton: RelayRepository? = null

        fun get(context: Context): RelayRepository =
            singleton ?: synchronized(this) {
                singleton ?: RelayRepository(context).also { singleton = it }
            }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI models
// ─────────────────────────────────────────────────────────────────────────────

data class Conversation(
    val threadId: String,
    val address: String,
    val displayName: String,
    val photoB64: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val lastState: SmsState,
    val lastInbound: Boolean,
    val unread: Int,
    val pinned: Boolean,
)

data class ActiveCall(
    val callId: String = "",
    val peerNumber: String = "",
    val peerName: String = "",
    val state: CallState = CallState.IDLE,
    val inbound: Boolean = false,
    val startedAt: Long = 0L,
    val connectedAt: Long = 0L,
    val audioMode: String = "",
    val cause: String = "",
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
) {
    val isActive: Boolean get() = state != CallState.IDLE && state != CallState.ENDED
    val elapsedMs: Long
        get() = if (connectedAt > 0) System.currentTimeMillis() - connectedAt else 0L
}

/** One-shot events the UI reacts to but does not render as state. */
sealed interface Signal {
    data class NewMessage(val message: SmsMessage) : Signal
    data class IncomingCall(val call: CallIncoming) : Signal
    data class RemoteOffer(val callId: String, val sdp: String) : Signal
    data class RemoteIce(val ice: RtcIce) : Signal
    data class CallEnded(val cause: String) : Signal
}

package com.relay.client.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * In-memory log of calls that traversed this bridge.
 *
 * Deliberately not persisted: a call log is the single most sensitive artefact
 * on a phone after the messages themselves, and the value of surviving a reboot
 * does not justify writing it to disk. If you want persistence, route it through
 * [MessageCache]'s at-rest encryption rather than a plain file.
 */
@Serializable
data class CallLog(
    val id: String = UUID.randomUUID().toString(),
    val number: String,
    val inbound: Boolean,
    val missed: Boolean,
    val startedAt: Long,
    val durationMs: Long,
    /** Which capture strategy the gateway used, e.g. "Speakerphone loopback". */
    val audioMode: String = "",
)

object CallLogStore {

    private const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<CallLog>>(emptyList())
    val entries: StateFlow<List<CallLog>> = _entries.asStateFlow()

    @Synchronized
    fun record(entry: CallLog) {
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
    }

    /** Convenience for the common "call just ended" path. */
    fun recordEnded(
        number: String,
        inbound: Boolean,
        startedAt: Long,
        connectedAt: Long,
        audioMode: String,
    ) = record(
        CallLog(
            number = number,
            inbound = inbound,
            missed = inbound && connectedAt == 0L,
            startedAt = startedAt,
            durationMs = if (connectedAt > 0) System.currentTimeMillis() - connectedAt else 0L,
            audioMode = audioMode,
        ),
    )

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }
}

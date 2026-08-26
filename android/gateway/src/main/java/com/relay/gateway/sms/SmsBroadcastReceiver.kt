package com.relay.gateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage as AndroidSmsMessage
import android.util.Log
import com.relay.core.model.SmsMessage
import com.relay.core.model.SmsState
import com.relay.gateway.service.RelayForegroundService
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Agent 3 — inbound cellular SMS interception.
 *
 * Registered with `android:priority="999"` and guarded by `BROADCAST_SMS` so
 * only the platform can deliver to it.
 *
 * Two deliberate design decisions:
 *
 * 1. **We never call `abortBroadcast()`.** Aborting would stop the stock
 *    Messages app from persisting the message, meaning the SIM device would
 *    silently lose its own SMS history. A relay should mirror, not hijack.
 *
 * 2. **Multipart reassembly happens here.** A long SMS arrives as several PDUs
 *    in one broadcast; concatenating `displayMessageBody` across them is the
 *    only correct way to recover the original text. Doing it later loses the
 *    ordering guarantee.
 *
 * The receiver does no network I/O — it hands the reassembled message to the
 * foreground service, which owns the encrypted socket. `goAsync()` is used to
 * keep the process alive across that handoff.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {

    private val json = Json { encodeDefaults = true }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pendingResult = goAsync()
        try {
            val parts = extractMessages(intent)
            if (parts.isEmpty()) {
                Log.w(TAG, "SMS_RECEIVED with no decodable PDUs")
                return
            }

            // All parts of a concatenated message share the originating address
            // and arrive in order within a single broadcast.
            val head = parts.first()
            val address = head.displayOriginatingAddress ?: head.originatingAddress ?: "unknown"
            val body = parts.joinToString(separator = "") { it.displayMessageBody ?: it.messageBody ?: "" }
            val timestamp = head.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

            val message = SmsMessage(
                id = UUID.randomUUID().toString(),
                address = address,
                body = body,
                ts = timestamp,
                inbound = true,
                threadId = normalizeThread(address),
                simSlot = extractSubscriptionSlot(intent),
                state = SmsState.DELIVERED,
            )

            Log.i(TAG, "inbound SMS: ${parts.size} part(s), ${body.length} chars")

            // Ensure the relay is running, then hand it the message. The service
            // encrypts and emits; if the socket is down it queues locally and the
            // server-side FCM wake covers the client.
            RelayForegroundService.start(context)
            RelayForegroundService.deliverInboundSms(context, json.encodeToString(message))
        } catch (t: Throwable) {
            Log.e(TAG, "failed to process inbound SMS", t)
        } finally {
            pendingResult.finish()
        }
    }

    /** Decode the PDU array. `getMessagesFromIntent` handles 3GPP and 3GPP2. */
    private fun extractMessages(intent: Intent): List<AndroidSmsMessage> =
        runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        }.getOrElse {
            Log.e(TAG, "PDU decode failed", it)
            emptyList()
        }

    /**
     * Which SIM slot received this message on a dual-SIM Note 10+.
     * The extra key is not part of the public SDK and differs across OEMs, so we
     * probe the known variants and default to slot 0.
     */
    private fun extractSubscriptionSlot(intent: Intent): Int {
        val subId = when {
            intent.hasExtra("subscription") -> intent.getIntExtra("subscription", -1)
            intent.hasExtra("android.telephony.extra.SUBSCRIPTION_INDEX") ->
                intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
            else -> -1
        }
        if (subId < 0) return 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return runCatching {
            android.telephony.SubscriptionManager.getSlotIndex(subId).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    /** Strip formatting so "+1 (555) 010-9999" and "+15550109999" share a thread. */
    private fun normalizeThread(address: String): String =
        address.filter { it.isDigit() || it == '+' }.ifEmpty { address }

    private companion object {
        const val TAG = "SmsBroadcastReceiver"
    }
}

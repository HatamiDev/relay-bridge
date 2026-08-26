package com.relay.gateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.relay.core.model.SmsState
import com.relay.core.model.SmsStatusUpdate
import com.relay.gateway.service.RelayForegroundService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Sink for `sendMultipartTextMessage` PendingIntents.
 *
 * A multipart message reports per part, and the parts complete out of order.
 * The client should only ever see one transition per message, so we aggregate:
 *
 *  • any part failing ⇒ the whole message is FAILED (report immediately)
 *  • all parts SENT   ⇒ SENT
 *  • all parts DELIVERED ⇒ DELIVERED
 *
 * State is process-local and intentionally so: if the process dies mid-flight
 * the client simply never sees DELIVERED, which is the correct, honest outcome
 * rather than a fabricated one.
 */
class SmsResultReceiver : BroadcastReceiver() {

    private val json = Json { encodeDefaults = true }

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1).coerceAtLeast(1)
        val resultCode = resultCode

        when (intent.action) {
            ACTION_SENT -> handleSent(context, messageId, partCount, resultCode)
            ACTION_DELIVERED -> handleDelivered(context, messageId, partCount, resultCode)
            else -> Log.w(TAG, "unexpected action ${intent.action}")
        }
    }

    private fun handleSent(context: Context, id: String, partCount: Int, code: Int) {
        if (code != Activity.RESULT_OK) {
            tracker.remove(id)
            Log.w(TAG, "part of $id failed to send: ${describe(code)}")
            report(context, SmsStatusUpdate(id, SmsState.FAILED, code))
            return
        }
        val progress = tracker.getOrPut(id) { Progress(partCount) }
        if (progress.markSent() && !progress.sentReported) {
            progress.sentReported = true
            Log.i(TAG, "$id fully handed to the network")
            report(context, SmsStatusUpdate(id, SmsState.SENT))
        }
    }

    private fun handleDelivered(context: Context, id: String, partCount: Int, code: Int) {
        // Delivery reports use a different code space: RESULT_OK means the SMSC
        // confirmed delivery; anything else means the network gave up.
        if (code != Activity.RESULT_OK) {
            tracker.remove(id)
            Log.w(TAG, "$id delivery failed: $code")
            report(context, SmsStatusUpdate(id, SmsState.FAILED, code))
            return
        }
        val progress = tracker.getOrPut(id) { Progress(partCount) }
        if (progress.markDelivered()) {
            tracker.remove(id)
            Log.i(TAG, "$id delivered")
            report(context, SmsStatusUpdate(id, SmsState.DELIVERED))
        }
    }

    private fun report(context: Context, update: SmsStatusUpdate) {
        RelayForegroundService.deliverSmsStatus(context, json.encodeToString(update))
    }

    private fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
        SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "LIMIT_EXCEEDED"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "SHORT_CODE_NOT_ALLOWED"
        else -> "CODE_$code"
    }

    /** Per-message part accounting. */
    private class Progress(val total: Int) {
        private var sent = 0
        private var delivered = 0
        @Volatile var sentReported = false

        @Synchronized fun markSent(): Boolean = ++sent >= total
        @Synchronized fun markDelivered(): Boolean = ++delivered >= total
    }

    companion object {
        private const val TAG = "SmsResultReceiver"
        private val tracker = ConcurrentHashMap<String, Progress>()

        const val ACTION_SENT = "com.relay.gateway.SMS_SENT"
        const val ACTION_DELIVERED = "com.relay.gateway.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"
    }
}

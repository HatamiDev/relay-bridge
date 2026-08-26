package com.relay.gateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.getSystemService
import com.relay.core.model.SmsOutboundRequest
import com.relay.core.model.SmsState
import com.relay.core.model.SmsStatusUpdate
import com.relay.gateway.service.RelayForegroundService
import kotlinx.serialization.json.Json

/**
 * Agent 3 — outbound SMS execution.
 *
 * `SmsManager.sendMultipartTextMessage` is the only correct entry point: it
 * splits on GSM-7/UCS-2 boundaries and adds the concatenation UDH so the
 * recipient's phone reassembles a single message. Manually chunking by 160
 * characters produces broken text the moment an emoji or an accented character
 * appears.
 *
 * Delivery reporting is genuinely asynchronous — the network answers minutes
 * later — so each part gets its own PendingIntent routed to [SmsResultReceiver].
 */
class SmsSender(private val context: Context) {

    private val json = Json { encodeDefaults = true }

    fun send(request: SmsOutboundRequest) {
        val manager = smsManagerFor(request.simSlot)
        if (manager == null) {
            report(request.id, SmsState.FAILED, ERROR_NO_SMS_MANAGER)
            return
        }

        val destination = request.to.trim()
        if (destination.isEmpty()) {
            report(request.id, SmsState.FAILED, ERROR_BAD_DESTINATION)
            return
        }

        try {
            // divideMessage applies the correct encoding-aware split.
            val parts = manager.divideMessage(request.body)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveredIntents = ArrayList<PendingIntent>(parts.size)

            for (index in parts.indices) {
                sentIntents += resultIntent(
                    action = SmsResultReceiver.ACTION_SENT,
                    messageId = request.id,
                    partIndex = index,
                    partCount = parts.size,
                )
                deliveredIntents += resultIntent(
                    action = SmsResultReceiver.ACTION_DELIVERED,
                    messageId = request.id,
                    partIndex = index,
                    partCount = parts.size,
                )
            }

            manager.sendMultipartTextMessage(
                destination,
                null,          // scAddress: use the SIM's default SMSC
                parts,
                sentIntents,
                deliveredIntents,
            )

            Log.i(TAG, "sent ${parts.size} part(s) for ${request.id}")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "invalid SMS parameters for ${request.id}", e)
            report(request.id, SmsState.FAILED, ERROR_BAD_DESTINATION)
        } catch (e: SecurityException) {
            Log.e(TAG, "SEND_SMS permission missing", e)
            report(request.id, SmsState.FAILED, ERROR_PERMISSION)
        } catch (t: Throwable) {
            Log.e(TAG, "send failed for ${request.id}", t)
            report(request.id, SmsState.FAILED, ERROR_UNKNOWN)
        }
    }

    /**
     * Resolve the SmsManager bound to a specific SIM slot on a dual-SIM device.
     * Falls back to the system default subscription.
     */
    private fun smsManagerFor(slot: Int): SmsManager? = runCatching {
        val subscriptionId = subscriptionIdForSlot(slot)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService<SmsManager>() ?: return@runCatching null
            if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                base.createForSubscriptionId(subscriptionId)
            } else {
                base
            }
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }
    }.getOrNull()

    private fun subscriptionIdForSlot(slot: Int): Int = runCatching {
        val sm = context.getSystemService<SubscriptionManager>()
            ?: return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        @Suppress("MissingPermission")
        val info = sm.getActiveSubscriptionInfoForSimSlotIndex(slot)
        info?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    private fun resultIntent(
        action: String,
        messageId: String,
        partIndex: Int,
        partCount: Int,
    ): PendingIntent {
        val intent = Intent(action)
            .setClass(context, SmsResultReceiver::class.java)
            .putExtra(SmsResultReceiver.EXTRA_MESSAGE_ID, messageId)
            .putExtra(SmsResultReceiver.EXTRA_PART_INDEX, partIndex)
            .putExtra(SmsResultReceiver.EXTRA_PART_COUNT, partCount)

        // The request code must be unique per part, or PendingIntent will recycle
        // one instance and every part will report the first part's result.
        val requestCode = (messageId.hashCode() * 31 + partIndex) and 0x7FFFFFFF

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun report(id: String, state: SmsState, errorCode: Int) {
        RelayForegroundService.deliverSmsStatus(
            context,
            json.encodeToString(SmsStatusUpdate(id, state, errorCode)),
        )
    }

    companion object {
        private const val TAG = "SmsSender"
        const val ERROR_NO_SMS_MANAGER = -101
        const val ERROR_BAD_DESTINATION = -102
        const val ERROR_PERMISSION = -103
        const val ERROR_UNKNOWN = -199
    }
}

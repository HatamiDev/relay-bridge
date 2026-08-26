package com.relay.gateway.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.relay.core.model.SmsMessage
import com.relay.core.model.SmsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backfill reader over the system SMS provider.
 *
 * The client needs history the first time it pairs (and after a long outage).
 * Rather than maintaining a parallel database on the gateway, we read the
 * platform's own store — it is already the source of truth and stays correct
 * even when messages arrive while our app is not running.
 *
 * Requires `READ_SMS`.
 */
class SmsInbox(private val context: Context) {

    data class Page(val messages: List<SmsMessage>, val hasMore: Boolean)

    /**
     * @param sinceTs only messages strictly newer than this epoch-ms
     * @param limit   hard cap; one extra row is fetched to compute [Page.hasMore]
     */
    suspend fun query(sinceTs: Long, limit: Int): Page = withContext(Dispatchers.IO) {
        val capped = limit.coerceIn(1, MAX_PAGE)
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.STATUS,
        )

        val messages = ArrayList<SmsMessage>(capped)
        var hasMore = false

        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} > ?",
                arrayOf(sinceTs.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT ${capped + 1}",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val statusIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)

                while (cursor.moveToNext()) {
                    if (messages.size == capped) { hasMore = true; break }

                    val type = cursor.getInt(typeIdx)
                    val inbound = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                    val address = cursor.getString(addrIdx).orEmpty()

                    messages += SmsMessage(
                        id = "sys-${cursor.getLong(idIdx)}",
                        address = address,
                        body = cursor.getString(bodyIdx).orEmpty(),
                        ts = cursor.getLong(dateIdx),
                        inbound = inbound,
                        threadId = address.filter { it.isDigit() || it == '+' }.ifEmpty { address },
                        state = mapState(type, cursor.getInt(statusIdx)),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_SMS not granted", e)
        } catch (t: Throwable) {
            Log.e(TAG, "inbox query failed", t)
        }

        // Chronological order is friendlier for the client's incremental merge.
        Page(messages.sortedBy { it.ts }, hasMore)
    }

    private fun mapState(type: Int, status: Int): SmsState = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> SmsState.DELIVERED
        Telephony.Sms.MESSAGE_TYPE_FAILED -> SmsState.FAILED
        Telephony.Sms.MESSAGE_TYPE_QUEUED,
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> SmsState.QUEUED
        Telephony.Sms.MESSAGE_TYPE_SENT -> when (status) {
            Telephony.Sms.STATUS_COMPLETE -> SmsState.DELIVERED
            Telephony.Sms.STATUS_FAILED -> SmsState.FAILED
            else -> SmsState.SENT
        }
        else -> SmsState.SENT
    }

    private companion object {
        const val TAG = "SmsInbox"
        const val MAX_PAGE = 500
    }
}

package com.relay.client.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.relay.client.data.RelayRepository
import com.relay.core.util.SystemHealth

/**
 * Handles notification-action taps that must not open the UI.
 *
 * Declining from the lock screen should hang up and dismiss without ever
 * launching an Activity — starting one would defeat the point of the action.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        if (callId.isEmpty()) return

        val repository = RelayRepository.get(context)
        when (intent.action) {
            ACTION_DECLINE -> repository.rejectCall(callId)
            ACTION_HANGUP -> repository.hangUp(callId)
        }
        context.getSystemService<NotificationManager>()
            ?.cancel(SystemHealth.NOTIFICATION_CALL_ID)
    }

    companion object {
        const val ACTION_DECLINE = "com.relay.client.DECLINE_CALL"
        const val ACTION_HANGUP = "com.relay.client.HANGUP_CALL"
        const val EXTRA_CALL_ID = "call_id"
    }
}

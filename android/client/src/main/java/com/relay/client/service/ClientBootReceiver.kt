package com.relay.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.relay.client.data.RelayRepository

/** Brings the socket back after a reboot or an app update. */
class ClientBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val paired = runCatching {
                    RelayRepository.get(context).secureStore.isPaired
                }.getOrDefault(false)
                if (paired) ClientRelayService.start(context)
            }
        }
    }
}

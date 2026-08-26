package com.relay.gateway.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.relay.gateway.GatewayRuntime

/**
 * Restarts the relay after a reboot, an app update, or an OEM task-killer.
 *
 * `LOCKED_BOOT_COMPLETED` is handled too (the receiver is `directBootAware`), so
 * the socket comes up before the user unlocks the device for the first time.
 * The service itself only reaches its encrypted preferences after unlock — that
 * is a deliberate trade: relaying resumes as soon as credentials are readable,
 * and until then the server's offline queue holds anything that arrives.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            RelayForegroundService.ACTION_RESURRECT -> {
                val paired = runCatching { GatewayRuntime.secureStore.isPaired }
                    .getOrDefault(false)
                Log.i(TAG, "${intent.action} — paired=$paired")
                if (paired) RelayForegroundService.start(context)
            }
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}

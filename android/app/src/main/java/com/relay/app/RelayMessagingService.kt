package com.relay.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.relay.client.data.RelayRepository
import com.relay.client.service.ClientRelayService
import com.relay.core.model.DeviceRole
import com.relay.core.net.PairingApi
import com.relay.gateway.service.RelayForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One FCM service for the whole APK, dispatching by stored role.
 *
 * Android binds exactly one `FirebaseMessagingService` per application, so the
 * gateway and receiver halves cannot each have their own — this is the seam
 * where the merged app has to make a runtime decision.
 *
 * The push is **data-only** and carries no user content: just
 * `{type, event, room, urgent}`. The real payload is fetched from the server's
 * offline queue over the encrypted socket once we reconnect, so Google's
 * infrastructure never sees a message body or a phone number.
 */
class RelayMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "wake") {
            Log.d(TAG, "ignoring non-wake push")
            return
        }

        val event = message.data["event"].orEmpty()
        val urgent = message.data["urgent"] == "true"
        val role = RelayApp.instance.secureStore.role
        Log.i(TAG, "wake: event=$event urgent=$urgent role=$role")

        // Starting a foreground service is legal here: a high-priority data
        // message grants a temporary background-start exemption.
        when (role) {
            DeviceRole.GATEWAY -> RelayForegroundService.start(this)
            DeviceRole.RECEIVER -> {
                ClientRelayService.start(this)
                // The service may already be running on a socket that died
                // during Doze, so nudge the repository directly too.
                runCatching { RelayRepository.get(this).connect() }
            }
            DeviceRole.UNSET -> Log.w(TAG, "wake push but no role chosen yet")
        }
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated")
        val store = RelayApp.instance.secureStore
        store.fcmToken = token
        if (!store.isPaired) return

        // Register over HTTP rather than the socket: a token rotation often
        // happens while the socket is down, which is exactly when it matters.
        scope.launch {
            PairingApi(store.serverUrl)
                .registerFcmToken(store.authToken, token)
                .onFailure { Log.w(TAG, "token registration failed: ${it.message}") }
        }
        runCatching {
            if (store.role == DeviceRole.RECEIVER) {
                RelayRepository.get(this).registerFcmToken(token)
            }
        }
    }

    private companion object { const val TAG = "RelayFcm" }
}

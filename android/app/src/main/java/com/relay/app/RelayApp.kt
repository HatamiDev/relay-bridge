package com.relay.app

import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.relay.client.data.RelayRepository
import com.relay.client.service.ClientRelayService
import com.relay.core.crypto.SecureStore
import com.relay.core.model.DeviceRole
import com.relay.core.util.SystemHealth
import com.relay.gateway.GatewayRuntime
import com.relay.gateway.service.RelayForegroundService

/**
 * The single Application for both halves of the bridge.
 *
 * Boot order matters: notification channels first (a wake push can arrive
 * before any Activity exists), then the role-specific runtime, then the FCM
 * token. Nothing role-specific starts until a role has actually been chosen.
 */
class RelayApp : Application() {

    val secureStore: SecureStore by lazy { SecureStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        SystemHealth.createChannels(this)
        GatewayRuntime.attach(this)

        val role = secureStore.role
        Log.i(TAG, "starting in role=$role paired=${secureStore.isPaired}")

        // Resolve the FCM token early — it is the only thing that can wake this
        // process after a long Doze window.
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                runCatching {
                    secureStore.fcmToken = token
                    if (role == DeviceRole.RECEIVER) {
                        RelayRepository.get(this).registerFcmToken(token)
                    }
                }
            }
            .addOnFailureListener { Log.w(TAG, "FCM token unavailable: ${it.message}") }

        if (!secureStore.isPaired) return

        when (role) {
            DeviceRole.GATEWAY -> RelayForegroundService.start(this)
            DeviceRole.RECEIVER -> ClientRelayService.start(this)
            DeviceRole.UNSET -> Unit
        }
    }

    companion object {
        private const val TAG = "RelayApp"

        @Volatile
        lateinit var instance: RelayApp
            private set
    }
}

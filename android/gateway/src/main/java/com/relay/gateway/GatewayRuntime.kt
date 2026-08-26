package com.relay.gateway

import android.content.Context
import com.relay.core.crypto.SecureStore

/**
 * Process-wide handles for the gateway half.
 *
 * This used to be an `Application` subclass. It cannot be one any more: the
 * merged APK has exactly one Application (`com.relay.app.RelayApp`), and both
 * halves of the bridge now live inside it. So the gateway keeps a plain object
 * that RelayApp attaches at startup.
 *
 * [attach] is idempotent and safe to call from any process entry point —
 * a broadcast receiver waking a dead process reaches Application.onCreate first
 * either way, but calling it again costs nothing.
 */
object GatewayRuntime {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var store: SecureStore? = null

    fun attach(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
    }

    val context: Context
        get() = appContext
            ?: error("GatewayRuntime.attach() was never called — check RelayApp.onCreate")

    /**
     * Shared instance. `EncryptedSharedPreferences` is expensive to open
     * (Keystore round-trip), so every gateway component reuses this one.
     */
    val secureStore: SecureStore
        get() = store ?: synchronized(this) {
            store ?: SecureStore(context).also { store = it }
        }

    /** True once the role picker has set this device to GATEWAY. */
    val isGatewayRole: Boolean
        get() = runCatching { secureStore.isGateway }.getOrDefault(false)
}

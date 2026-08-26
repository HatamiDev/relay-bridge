package com.relay.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.relay.client.ReceiverActivity
import com.relay.core.model.DeviceRole
import com.relay.gateway.ui.GatewayActivity

/**
 * The only launcher entry point.
 *
 * Reads the stored role and forwards immediately — it never draws a frame, so
 * there is no flash of an empty screen between tapping the icon and landing on
 * the right surface.
 *
 * Declared `noHistory` in the manifest, so pressing Back from the destination
 * exits the app instead of bouncing through this router.
 */
class RouterActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = RelayApp.instance.secureStore

        val destination = when (store.role) {
            DeviceRole.GATEWAY -> Intent(this, GatewayActivity::class.java)
            DeviceRole.RECEIVER -> Intent(this, ReceiverActivity::class.java)
            DeviceRole.UNSET -> Intent(this, RoleSelectActivity::class.java)
        }

        // Carry through a thread deep link from a message notification.
        intent?.getStringExtra(EXTRA_THREAD_ID)?.let {
            destination.putExtra(EXTRA_THREAD_ID, it)
        }

        startActivity(destination.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
    }
}

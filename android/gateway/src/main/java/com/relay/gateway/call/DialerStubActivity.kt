package com.relay.gateway.call

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Minimal DIAL handler.
 *
 * `RoleManager.ROLE_DIALER` is only offered to apps that can handle `ACTION_DIAL`.
 * We need the role purely so Telecom will bind [RelayInCallService]; we have no
 * interest in replacing the dialer UI. So this activity immediately forwards the
 * intent to Samsung's own dialer and finishes without ever drawing a frame
 * (`Theme.NoDisplay`).
 *
 * If no other dialer can be found — which would mean we really are the only one
 * installed — we fall back to the generic chooser rather than swallowing the
 * user's tap.
 */
class DialerStubActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forwarded = Intent(intent).apply {
            component = null
            `package` = null
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        }

        val handlers = packageManager
            .queryIntentActivities(forwarded, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()

        val target = SAMSUNG_DIALERS.firstOrNull(handlers::contains) ?: handlers.firstOrNull()

        try {
            if (target != null) {
                forwarded.`package` = target
                startActivity(forwarded)
            } else {
                startActivity(Intent.createChooser(forwarded, "Dial"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "unable to forward DIAL intent", t)
        }

        finish()
    }

    private companion object {
        const val TAG = "DialerStubActivity"
        val SAMSUNG_DIALERS = listOf(
            "com.samsung.android.dialer",
            "com.samsung.android.contacts",
            "com.google.android.dialer",
            "com.android.dialer",
        )
    }
}

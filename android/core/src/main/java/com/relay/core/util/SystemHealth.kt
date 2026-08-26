package com.relay.core.util

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService

/**
 * Agent 6 — OS survival helpers.
 *
 * Samsung layers three independent kill mechanisms on top of stock Android:
 *   1. AOSP Doze / App Standby buckets
 *   2. Samsung "Put unused apps to sleep" / "Deep sleeping apps" (SmartManager)
 *   3. Adaptive Battery + the 3-day sleep heuristic
 *
 * Only #1 is programmatically waivable. #2 and #3 require the user to visit
 * Settings, which is why [SamsungBatterySettings] exposes deep links rather than
 * pretending an API exists. See docs/03-QA-OPTIMIZATION.md.
 */
object SystemHealth {

    // ── Doze exemption ───────────────────────────────────────────────────────

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent that shows the system dialog requesting a Doze exemption.
     * Requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** Fallback: the full battery-optimization list, for OEMs that block the dialog. */
    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    // ── Telemetry for the presence heartbeat ─────────────────────────────────

    fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService<BatteryManager>() ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Human-readable SIM state for the client's "gateway health" card. */
    fun simState(context: Context): String {
        val tm = context.getSystemService<TelephonyManager>() ?: return "UNKNOWN"
        return when (tm.simState) {
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
            TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "DISABLED"
            else -> "UNKNOWN"
        }
    }

    // ── Notifications ────────────────────────────────────────────────────────

    /**
     * Register the app's channels.
     * IMPORTANCE_LOW for the persistent relay notification (silent, no badge),
     * IMPORTANCE_HIGH for incoming calls so the full-screen intent fires.
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Relay service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the SMS and call bridge connected."
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Relayed cellular calls."
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 400, 500, 400)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(false)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Relayed SMS."
                setShowBadge(true)
            },
        )
    }

    const val CHANNEL_SERVICE = "relay_service"
    const val CHANNEL_CALLS = "relay_calls"
    const val CHANNEL_MESSAGES = "relay_messages"

    const val NOTIFICATION_SERVICE_ID = 1001
    const val NOTIFICATION_CALL_ID = 1002
    const val NOTIFICATION_MESSAGE_BASE_ID = 2000
}

/**
 * Deep links into Samsung's own power-management screens.
 *
 * These Activities are not part of the public SDK, so every launch is wrapped in
 * a resolve check and falls back to generic AOSP settings. Never assume they
 * exist — One UI renames them between major versions.
 */
object SamsungBatterySettings {

    private val CANDIDATES = listOf(
        // One UI 5/6/7 — Battery → Background usage limits
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        // One UI 4 and earlier
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        // Device Care root
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity",
    )

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /**
     * @return an intent that opens Samsung Device Care, or generic app settings
     *         when the OEM activity is unavailable on this firmware.
     */
    fun openPowerSettingsIntent(context: Context): Intent {
        if (isSamsung()) {
            for ((pkg, cls) in CANDIDATES) {
                val intent = Intent().setClassName(pkg, cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) return intent
            }
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Step-by-step instructions shown in-app; there is no API for these toggles. */
    val manualSteps: List<String> = listOf(
        "Settings → Battery → Background usage limits → Never sleeping apps → add this app",
        "Settings → Battery → Background usage limits → remove this app from Deep sleeping apps",
        "Settings → Battery → More battery settings → turn OFF \"Adaptive battery\" (or exempt this app)",
        "Settings → Apps → this app → Battery → Unrestricted",
        "Settings → Device care → Auto optimisation → turn OFF \"Close apps that aren't in use\"",
        "Settings → Apps → ⋮ → Special access → Modify system settings / Appear on top → allow",
    )
}

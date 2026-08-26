package com.relay.client.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import com.relay.client.ReceiverActivity
import com.relay.client.R
import com.relay.client.data.RelayRepository
import com.relay.client.data.Signal
import com.relay.client.ui.call.CallActivity
import com.relay.client.util.decodeBase64Bitmap
import com.relay.core.model.CallIncoming
import com.relay.core.model.SmsMessage
import com.relay.core.net.ConnectionState
import com.relay.core.util.SystemHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Agent 4 — the client's connection keeper.
 *
 * Holds the socket while the UI is closed, raises message notifications, and —
 * critically — fires the full-screen intent that makes a relayed call ring like
 * a real one even on the lock screen.
 */
class ClientRelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: RelayRepository

    override fun onCreate() {
        super.onCreate()
        repository = RelayRepository.get(this)

        startForeground(
            SystemHealth.NOTIFICATION_SERVICE_ID,
            buildServiceNotification("Connecting…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        repository.connect()
        observeSignals()
        observeConnection()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!repository.isConnected) repository.connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun observeSignals() = scope.launch {
        repository.signals.collect { signal ->
            when (signal) {
                is Signal.NewMessage -> notifyMessage(signal.message)
                is Signal.IncomingCall -> ringForCall(signal.call)
                is Signal.CallEnded -> cancelCallNotification()
                else -> Unit
            }
        }
    }

    private fun observeConnection() = scope.launch {
        repository.connection.collect { state ->
            val gateway = repository.gatewayPresence.value
            val text = when (state) {
                ConnectionState.CONNECTED ->
                    if (gateway?.online == true) {
                        "Gateway online" + (gateway.batteryPct.takeIf { it >= 0 }
                            ?.let { " · $it%" } ?: "")
                    } else {
                        "Waiting for gateway"
                    }
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.RECONNECTING -> "Reconnecting…"
                ConnectionState.UNAUTHORIZED -> "Pairing invalid — re-pair"
                ConnectionState.DISCONNECTED -> "Offline"
            }
            updateServiceNotification(text)
        }
    }

    private fun startHeartbeat() = scope.launch {
        var ticks = 0
        while (isActive) {
            if (!repository.isConnected) repository.connect()
            if (ticks % 60 == 0) repository.refreshIceServers()
            ticks++
            delay(30_000)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notifications
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full-screen incoming call.
     *
     * `setFullScreenIntent(..., true)` plus `CATEGORY_CALL` plus a `CallStyle`
     * is what tells Android to bypass the shade and launch [CallActivity]
     * directly — the same treatment the system dialer receives. Without the
     * CallStyle, One UI collapses it into an ordinary heads-up banner.
     */
    private fun ringForCall(call: CallIncoming) {
        val displayName = repository.displayNameFor(call.from).ifEmpty {
            call.displayName.ifEmpty { call.from }
        }

        val fullScreen = PendingIntent.getActivity(
            this,
            REQ_FULL_SCREEN,
            CallActivity.incomingIntent(this, call.callId, call.from, displayName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answer = PendingIntent.getActivity(
            this,
            REQ_ANSWER,
            CallActivity.answerIntent(this, call.callId, call.from, displayName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            this,
            REQ_DECLINE,
            Intent(this, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_DECLINE)
                .putExtra(CallActionReceiver.EXTRA_CALL_ID, call.callId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val caller = Person.Builder()
            .setName(displayName)
            .setImportant(true)
            .apply {
                repository.contacts.value
                    .firstOrNull { it.number.filter(Char::isDigit) == call.from.filter(Char::isDigit) }
                    ?.photoB64
                    ?.let(::decodeBase64Bitmap)
                    ?.let { setIcon(IconCompat.createWithBitmap(it)) }
            }
            .build()

        val notification = NotificationCompat.Builder(this, SystemHealth.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_call_relay)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setContentTitle(displayName)
            .setContentText("Relayed cellular call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        getSystemService<NotificationManager>()
            ?.notify(SystemHealth.NOTIFICATION_CALL_ID, notification)

        // Also try to launch straight into the call UI. On Android 10+ this only
        // succeeds when the app is visible or the device is unlocked-and-idle;
        // the full-screen intent above is the reliable path otherwise.
        runCatching {
            startActivity(
                CallActivity.incomingIntent(this, call.callId, call.from, displayName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun cancelCallNotification() {
        getSystemService<NotificationManager>()?.cancel(SystemHealth.NOTIFICATION_CALL_ID)
    }

    private fun notifyMessage(message: SmsMessage) {
        if (!message.inbound) return

        val name = repository.displayNameFor(message.address).ifEmpty { message.address }
        val open = PendingIntent.getActivity(
            this,
            message.threadId.hashCode(),
            ReceiverActivity.threadIntent(this, message.threadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val sender = Person.Builder().setName(name).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .addMessage(message.body, message.ts, sender)

        val notification = NotificationCompat.Builder(this, SystemHealth.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message_relay)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService<NotificationManager>()?.notify(
            SystemHealth.NOTIFICATION_MESSAGE_BASE_ID + (message.threadId.hashCode() and 0xFFF),
            notification,
        )
    }

    private fun buildServiceNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SystemHealth.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_message_relay)
            .setContentTitle("Relay")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        getSystemService<NotificationManager>()
            ?.notify(SystemHealth.NOTIFICATION_SERVICE_ID, buildServiceNotification(text))
    }

    companion object {
        private const val TAG = "ClientRelayService"
        private const val REQ_FULL_SCREEN = 4001
        private const val REQ_ANSWER = 4002
        private const val REQ_DECLINE = 4003

        const val ACTION_STOP = "com.relay.client.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ClientRelayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "service start refused: ${it.message}") }
        }
    }
}

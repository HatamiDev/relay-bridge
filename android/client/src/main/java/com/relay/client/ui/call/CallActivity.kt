package com.relay.client.ui.call

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.relay.client.call.ClientCallSession
import com.relay.client.data.RelayRepository
import com.relay.client.data.Signal
import com.relay.client.ui.theme.RelayGlassTheme
import kotlinx.coroutines.launch

/**
 * Full-screen call surface.
 *
 * Launched three ways:
 *  • by the full-screen intent on an incoming call (works over the lock screen)
 *  • by the notification's Answer action
 *  • directly, when the user places an outbound call from a thread
 *
 * `showWhenLocked` + `turnScreenOn` are set in the manifest; the legacy window
 * flags below are added for One UI builds that still honour them preferentially.
 */
class CallActivity : ComponentActivity() {

    private lateinit var repository: RelayRepository
    private lateinit var session: ClientCallSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyLockScreenFlags()

        repository = RelayRepository.get(this)
        session = ClientCallSession(this, repository)

        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val autoAnswer = intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)

        // Build the PeerConnection before the offer arrives so answering is
        // instant rather than incurring ICE gathering after the user taps.
        session.prepare(callId)

        if (autoAnswer) repository.answerCall(callId)

        // Close the screen as soon as the gateway reports the call ended.
        lifecycleScope.launch {
            repository.signals.collect { signal ->
                if (signal is Signal.CallEnded) finish()
            }
        }

        setContent {
            RelayGlassTheme {
                val vm = remember { CallScreenState(repository, session) }
                CallScreen(
                    state = vm,
                    callId = callId,
                    fallbackNumber = number,
                    fallbackName = name,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }

    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
        const val EXTRA_AUTO_ANSWER = "auto_answer"

        fun incomingIntent(context: Context, callId: String, number: String, name: String) =
            base(context, callId, number, name)

        fun answerIntent(context: Context, callId: String, number: String, name: String) =
            base(context, callId, number, name).putExtra(EXTRA_AUTO_ANSWER, true)

        fun outgoingIntent(context: Context, callId: String, number: String, name: String) =
            base(context, callId, number, name)

        private fun base(context: Context, callId: String, number: String, name: String) =
            Intent(context, CallActivity::class.java)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_NAME, name)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                )
    }
}

/** Thin holder so the composable can reach both the repo and the media session. */
class CallScreenState(
    val repository: RelayRepository,
    val session: ClientCallSession,
)

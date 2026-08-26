package com.relay.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.relay.client.data.RelayRepository
import com.relay.client.service.ClientRelayService
import com.relay.client.ui.components.AuroraBackground
import com.relay.client.ui.components.DockTab
import com.relay.client.ui.components.FloatingDock
import com.relay.client.ui.screens.CallLogScreen
import com.relay.client.ui.screens.ContactsScreen
import com.relay.client.ui.screens.MessagesScreen
import com.relay.client.ui.screens.PairingScreen
import com.relay.client.ui.screens.SettingsScreen
import com.relay.client.ui.screens.ThreadScreen
import com.relay.client.ui.theme.AuroraVariant
import com.relay.client.ui.theme.Glass
import com.relay.client.ui.theme.RelayGlassTheme

/**
 * Agent 4 — the single-Activity shell.
 *
 * Navigation is intentionally hand-rolled state rather than a nav library: there
 * are five destinations, one of which is a detail view, and the dock's sliding
 * highlight needs to own the transition timing. A nav graph would add a
 * dependency and take that control away for no benefit at this size.
 */
class ReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialThread = intent?.getStringExtra(EXTRA_THREAD_ID)

        setContent {
            RelayGlassTheme {
                Surface(color = Glass.colors.canvas) {
                    RelayApp(initialThreadId = initialThread)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"

        fun threadIntent(context: Context, threadId: String): Intent =
            Intent(context, ReceiverActivity::class.java)
                .putExtra(EXTRA_THREAD_ID, threadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@Composable
private fun RelayApp(initialThreadId: String?) {
    val context = LocalContext.current
    val repository = remember { RelayRepository.get(context) }

    var paired by rememberSaveable { mutableStateOf(repository.secureStore.isPaired) }
    var tab by rememberSaveable { mutableStateOf(DockTab.Home) }
    var openThreadId by rememberSaveable { mutableStateOf(initialThreadId) }

    val threads by repository.threads.collectAsState()
    val unread = remember(threads) { threads.sumOf { it.unread } }

    // Runtime permissions: microphone for calls, notifications for ringing.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* results are advisory; the UI degrades rather than blocks */ }

    LaunchedEffect(Unit) {
        permissions.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray(),
        )
    }

    LaunchedEffect(paired) {
        if (paired) {
            ClientRelayService.start(context)
            repository.connect()
        }
    }

    // The shell owns the canvas for every tab: Feed runs dark at the status bar
    // down to luminous teal under the dock, which is what the floating dock's
    // light frost is designed to sit on. ThreadScreen supplies its own Chat
    // canvas when it takes over.
    AuroraBackground(Modifier.fillMaxSize(), variant = AuroraVariant.Feed) {

        if (!paired) {
            PairingScreen(
                repository = repository,
                onPaired = { paired = true },
            )
            return@AuroraBackground
        }

        // ── Detail view takes over the whole surface ──────────────────────────
        AnimatedContent(
            targetState = openThreadId,
            transitionSpec = {
                if (targetState != null) {
                    (slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(220)))
                        .togetherWith(fadeOut(tween(140)))
                } else {
                    fadeIn(tween(200)).togetherWith(fadeOut(tween(140)))
                }
            },
            label = "threadTransition",
        ) { threadId ->
            if (threadId != null) {
                ThreadScreen(
                    repository = repository,
                    threadId = threadId,
                    onBack = { openThreadId = null },
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    // The dock's vocabulary is generic (Home / Search /
                    // Activity / Profile); the destinations behind it are this
                    // app's, and the pairing is deliberate rather than literal:
                    // conversations are home, the call log is what you go
                    // looking through, contacts are who is active, settings are
                    // yours.
                    when (tab) {
                        DockTab.Home -> MessagesScreen(
                            repository = repository,
                            onOpenThread = { openThreadId = it },
                            // No drawer in this shell, so the menu affordance
                            // goes where a drawer would have led anyway.
                            onOpenMenu = { tab = DockTab.Profile },
                        )
                        DockTab.Search -> CallLogScreen(repository)
                        DockTab.Activity -> ContactsScreen(
                            repository = repository,
                            onOpenThread = { openThreadId = it },
                        )
                        DockTab.Profile -> SettingsScreen(
                            repository = repository,
                            onUnpaired = { paired = false },
                        )
                    }

                    FloatingDock(
                        selected = tab,
                        onSelect = { tab = it },
                        badges = mapOf(DockTab.Home to unread),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Transparent placeholder used while a screen is still resolving. */
@Composable
internal fun EmptySurface() {
    Box(Modifier.fillMaxSize().padding(0.dp)) {
        Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize()) {}
    }
}

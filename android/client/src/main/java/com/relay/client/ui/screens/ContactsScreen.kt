package com.relay.client.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.data.RelayRepository
import com.relay.client.ui.call.CallActivity
import com.relay.client.ui.components.CircleIconButton
import com.relay.client.ui.components.DockSpacer
import com.relay.client.ui.components.GlassSurface
import com.relay.client.ui.components.SquircleShape
import com.relay.client.ui.theme.Glass
import com.relay.client.util.decodeBase64Image
import com.relay.client.util.initials
import com.relay.core.model.Contact

/**
 * Agent 4 — the mirrored contact book.
 *
 * Contacts live on the gateway; this screen renders the encrypted mirror. The
 * search field filters on both name and number with the digits normalised, so
 * typing "5550109" finds "+1 (555) 010-9999".
 */
@Composable
fun ContactsScreen(
    repository: RelayRepository,
    onOpenThread: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val context = LocalContext.current

    val contacts by repository.contacts.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { if (contacts.isEmpty()) repository.requestContacts() }

    val filtered = remember(contacts, query) {
        if (query.isBlank()) {
            contacts
        } else {
            val needle = query.trim().lowercase()
            val digits = needle.filter(Char::isDigit)
            contacts.filter { contact ->
                contact.name.lowercase().contains(needle) ||
                    (digits.isNotEmpty() && contact.number.filter(Char::isDigit).contains(digits))
            }
        }.sortedWith(compareByDescending<Contact> { it.pinned }.thenBy { it.name.lowercase() })
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 56.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = dimens.screenPadding)) {
                Text(
                    "Contacts",
                    color = colors.textPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp,
                )
                Spacer(Modifier.height(12.dp))
                SearchField(query) { query = it }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                GlassSurface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimens.screenPadding, vertical = 30.dp),
                ) {
                    Text(
                        if (contacts.isEmpty()) {
                            "Waiting for the gateway to mirror its contacts…"
                        } else {
                            "No contacts match \"$query\"."
                        },
                        color = colors.textTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        } else {
            items(filtered, key = { it.id.ifEmpty { it.number } }) { contact ->
                ContactRow(
                    contact = contact,
                    modifier = Modifier.padding(horizontal = dimens.screenPadding),
                    onMessage = { onOpenThread(contact.number.normalizedKey()) },
                    onCall = {
                        val callId = repository.placeCall(contact.number)
                        context.startActivity(
                            CallActivity.outgoingIntent(
                                context, callId, contact.number, contact.name,
                            ),
                        )
                    },
                )
            }
        }

        // The dock floats over the list, so the last row needs somewhere to go.
        item(key = "dock-gap") { DockSpacer() }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val colors = Glass.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text("Search name or number", color = colors.textTertiary, fontSize = 14.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    onMessage: () -> Unit,
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val photo = remember(contact.photoB64) { decodeBase64Image(contact.photoB64) }
    val shape = remember { SquircleShape(16.dp) }

    GlassSurface(modifier.fillMaxWidth().clickable(onClick = onMessage)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(shape).background(colors.canvasRaised),
                contentAlignment = Alignment.Center,
            ) {
                if (photo != null) {
                    Image(
                        bitmap = photo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().clip(shape),
                    )
                } else {
                    Text(
                        contact.name.initials(),
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        contact.name,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (contact.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = "Starred",
                            tint = colors.warning,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Text(contact.number, color = colors.textTertiary, fontSize = 12.sp)
            }

            // Each action names the contact: a screen reader moving down the
            // list would otherwise hear "Message, Call" twenty times over with
            // nothing to say which row it is on.
            CircleIconButton(
                icon = Icons.Rounded.Message,
                contentDescription = "Message ${contact.name}",
                tint = colors.accent,
                background = colors.accent.copy(alpha = 0.15f),
                onClick = onMessage,
            )
            Spacer(Modifier.width(8.dp))
            CircleIconButton(
                icon = Icons.Rounded.Call,
                contentDescription = "Call ${contact.name}",
                tint = colors.success,
                background = colors.success.copy(alpha = 0.15f),
                onClick = onCall,
            )
        }
    }
}

private fun String.normalizedKey() = filter { it.isDigit() || it == '+' }.ifEmpty { this }

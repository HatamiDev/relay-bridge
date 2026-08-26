package com.relay.client.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
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
import com.relay.client.ui.theme.Glass
import com.relay.client.util.decodeBase64Image
import com.relay.client.util.initials
import com.relay.core.model.Contact

/**
 * The mirrored address book.
 *
 * Contacts live on the gateway — this handset has no SIM and no contacts of its
 * own — so this screen renders whatever the gateway has sent. Search filters on
 * name and on digits with the formatting stripped, so typing `9121234` finds
 * `+98 912 123 4567`.
 *
 * Laid out to the kit's list geometry, the same as Chats and Calls: 64dp bar,
 * 72dp rows, 48dp circular avatar, 16dp gutters, no dividers.
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

    // Asked for once per visit while empty. The gateway also pushes the book
    // unprompted on every fresh session, so this only covers the case where the
    // receiver opened the tab before that arrived.
    LaunchedEffect(Unit) { if (contacts.isEmpty()) repository.requestContacts() }

    val filtered = remember(contacts, query) {
        val needle = query.trim().lowercase()
        val digits = needle.filter(Char::isDigit)
        contacts
            .filter { contact ->
                needle.isEmpty() ||
                    contact.name.lowercase().contains(needle) ||
                    (digits.isNotEmpty() && contact.number.filter(Char::isDigit).contains(digits))
            }
            .sortedWith(compareByDescending<Contact> { it.pinned }.thenBy { it.name.lowercase() })
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {

        // ── App bar ──────────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.canvasRaised)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Contacts",
                    color = colors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (contacts.isNotEmpty()) {
                    Text(
                        "${contacts.size}",
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Re-sync contacts from the sender",
                    tint = colors.textPrimary,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { repository.requestContacts() },
                )
            }

            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SearchField(query, onValueChange = { query = it }, onClear = { query = "" })
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.glassBorder))
        }

        // ── List ─────────────────────────────────────────────────────────────
        if (filtered.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (contacts.isEmpty()) "No contacts yet" else "No matches",
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (contacts.isEmpty()) {
                        "The sender mirrors its address book once it is connected " +
                            "and has been granted contact access. Tap the refresh " +
                            "icon to ask again."
                    } else {
                        "Nothing matches \"$query\"."
                    },
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 19.6.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = dimens.dockHeight + 48.dp),
            ) {
                // Keyed by number, not by contact id: one address-book entry
                // with a mobile and a landline shares a single id, and duplicate
                // keys make LazyColumn throw.
                items(filtered, key = { it.number }) { contact ->
                    ContactRow(
                        contact = contact,
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
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = Glass.colors
    val shape = RoundedCornerShape(Glass.dimens.pillRadius)

    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(colors.glassLight)
            .border(1.dp, colors.glassBorder, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Search name or number", color = colors.textSecondary, fontSize = 14.sp)
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
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear the search",
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp).clickable(onClick = onClear),
            )
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

    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onMessage)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContactAvatar(contact)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contact.name,
                    color = colors.textPrimary,
                    fontSize = 16.sp,
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
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                contact.number,
                color = colors.textSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Each action names the contact: a screen reader moving down the list
        // would otherwise hear "Message, Call" twenty times over with nothing
        // to say which row it is on.
        Icon(
            Icons.Rounded.Message,
            contentDescription = "Message ${contact.name}",
            tint = colors.textPrimary,
            modifier = Modifier.size(24.dp).clickable(onClick = onMessage),
        )
        Icon(
            Icons.Rounded.Call,
            contentDescription = "Call ${contact.name}",
            tint = colors.textPrimary,
            modifier = Modifier.size(24.dp).clickable(onClick = onCall),
        )
    }
}

@Composable
private fun ContactAvatar(contact: Contact) {
    val colors = Glass.colors
    val photo = remember(contact.photoB64) { decodeBase64Image(contact.photoB64) }

    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.auroraTeal),   // Extended Primary 500 · #3E3180
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape),
            )
        } else {
            val letters = contact.name.initials()
            if (letters.isNotEmpty()) {
                Text(
                    letters,
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = colors.textPrimary.copy(alpha = 0.72f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

private fun String.normalizedKey() = filter { it.isDigit() || it == '+' }.ifEmpty { this }

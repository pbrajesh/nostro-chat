package app.mobilechat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import app.mobilechat.core.DirectMessage
import app.mobilechat.core.NostrKeys
import app.mobilechat.core.Retention
import app.mobilechat.core.Usernames
import app.mobilechat.core.hexToBytes
import app.mobilechat.data.Messenger
import app.mobilechat.data.RelayStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilechat.data.Contact
import app.mobilechat.data.Identity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    identity: Identity,
    messenger: Messenger,
    contacts: List<Contact>,
    onSend: (peerHex: String, text: String) -> Unit,
    retention: Retention,
    onRetentionChange: (Retention) -> Unit,
    onAddContact: (key: String, name: String) -> String?,
    onRemoveContact: (Contact) -> Unit,
    onLock: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var openChat by rememberSaveable { mutableStateOf<String?>(null) }
    val conversations by messenger.conversations.collectAsStateWithLifecycle()
    val pending by messenger.pending.collectAsStateWithLifecycle()
    val status by messenger.status.collectAsStateWithLifecycle()

    val peer = openChat
    if (peer != null) {
        BackHandler { openChat = null }
        val contact = contacts.firstOrNull { it.publicKeyHex == peer }
        ChatScreen(
            peerName = contact?.name ?: Usernames.forPublicKey(peer.hexToBytes()),
            isContact = contact != null,
            messages = conversations[peer].orEmpty(),
            pending = pending,
            onSend = { onSend(peer, it) },
            onAddContact = { onAddContact(peer, "") },
            onRemoveContact = { contact?.let(onRemoveContact); openChat = null },
            onBack = { openChat = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf("Chats", "My profile", "Settings")[tab]) },
                actions = { TextButton(onClick = onLock) { Text("Lock") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = {}, label = { Text("Chats") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = {}, label = { Text("Profile") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = {}, label = { Text("Settings") })
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add contact")
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> ChatsTab(contacts, conversations, status, onOpen = { openChat = it })
                1 -> ProfileTab(identity)
                else -> SettingsTab(retention, onRetentionChange)
            }
        }
    }

    if (adding) {
        AddContactDialog(onAdd = onAddContact, onDismiss = { adding = false })
    }
}

@Composable
private fun SettingsTab(retention: Retention, onChange: (Retention) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text(
            "Delete messages after",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Retention.entries.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == retention, onClick = { onChange(option) }, role = Role.RadioButton)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == retention, onClick = null)
                Text(option.label, modifier = Modifier.padding(start = 16.dp))
            }
        }
        Text(
            "Messages are saved in an encrypted database on this phone that only your PIN " +
                "can unlock. Old messages are deleted from the phone when you unlock the app " +
                "and while it's open. Relays may keep their encrypted copies, which only you " +
                "and the other person can read.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ProfileTab(identity: Identity) {
    val context = LocalContext.current
    val npub = identity.npub
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(identity.username, style = MaterialTheme.typography.headlineMedium)
        // White card behind the QR so it scans in dark mode too.
        Box(
            Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            QrCode(NostrKeys.nostrUri(identity.publicKey), Modifier.size(260.dp))
        }
        Text("Scan to add me", style = MaterialTheme.typography.bodyMedium)
        Text(
            npub,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { copy(context, npub) }) { Text("Copy npub") }
            Button(onClick = { share(context, npub) }) { Text("Share") }
        }
    }
}

private data class ChatRow(val peerHex: String, val name: String, val isContact: Boolean, val last: DirectMessage?)

@Composable
private fun ChatsTab(
    contacts: List<Contact>,
    conversations: Map<String, List<DirectMessage>>,
    status: RelayStatus,
    onOpen: (peerHex: String) -> Unit,
) {
    // Contacts plus anyone who has messaged us, most recent conversation first.
    val rows = remember(contacts, conversations) {
        val names = contacts.associate { it.publicKeyHex to it.name }
        (names.keys + conversations.keys).map { peer ->
            ChatRow(
                peerHex = peer,
                name = names[peer] ?: Usernames.forPublicKey(peer.hexToBytes()),
                isContact = peer in names,
                last = conversations[peer]?.lastOrNull(),
            )
        }.sortedWith(compareByDescending<ChatRow> { it.last?.createdAt ?: 0 }.thenBy { it.name })
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            if (status.connected == 0) "Connecting to relays…"
            else "Connected to ${status.connected} of ${status.total} relays",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No chats yet. Tap + to scan a friend's QR code or paste their npub.",
                    textAlign = TextAlign.Center,
                )
            }
        } else LazyColumn {
            items(rows, key = { it.peerHex }) { row ->
                ListItem(
                    headlineContent = { Text(row.name) },
                    supportingContent = {
                        Text(
                            row.last?.let { (if (it.fromMe) "You: " else "") + it.text }
                                ?: if (row.isContact) "Say hello" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        if (!row.isContact) Text("New", color = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onOpen(row.peerHex) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AddContactDialog(onAdd: (key: String, name: String) -> String?, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { key = it.trim(); error = null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan a Nostr QR code")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR code") }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; error = null },
                    label = { Text("npub") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = onAdd(key, name)
                    if (error == null) onDismiss()
                },
                enabled = key.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("npub", text))
    Toast.makeText(context, "npub copied", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, npub: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "nostr:$npub")
    context.startActivity(Intent.createChooser(send, "Share your npub"))
}

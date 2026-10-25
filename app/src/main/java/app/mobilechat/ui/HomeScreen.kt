package app.mobilechat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import app.mobilechat.core.NostrKeys
import app.mobilechat.data.Contact
import app.mobilechat.data.Identity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    identity: Identity,
    contacts: List<Contact>,
    onAddContact: (key: String, name: String) -> String?,
    onRemoveContact: (Contact) -> Unit,
    onLock: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tab == 0) "My profile" else "Contacts") },
                actions = { TextButton(onClick = onLock) { Text("Lock") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = {}, label = { Text("Profile") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = {}, label = { Text("Contacts") })
            }
        },
        floatingActionButton = {
            if (tab == 1) {
                FloatingActionButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add contact")
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (tab == 0) ProfileTab(identity) else ContactsTab(contacts, onRemoveContact)
        }
    }

    if (adding) {
        AddContactDialog(onAdd = onAddContact, onDismiss = { adding = false })
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

@Composable
private fun ContactsTab(contacts: List<Contact>, onRemove: (Contact) -> Unit) {
    if (contacts.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No contacts yet. Tap + to scan a friend's QR code or paste their npub.",
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    var confirmRemove by remember { mutableStateOf<Contact?>(null) }
    LazyColumn {
        items(contacts, key = { it.publicKeyHex }) { contact ->
            ListItem(
                headlineContent = { Text(contact.name) },
                supportingContent = {
                    Text(contact.npub, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { TextButton(onClick = { confirmRemove = contact }) { Text("Remove") } },
            )
            HorizontalDivider()
        }
    }
    confirmRemove?.let { contact ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${contact.name}?") },
            confirmButton = { TextButton(onClick = { onRemove(contact); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
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

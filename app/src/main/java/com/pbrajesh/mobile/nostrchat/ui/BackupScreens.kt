package com.pbrajesh.mobile.nostrchat.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pbrajesh.mobile.nostrchat.core.Backup
import com.pbrajesh.mobile.nostrchat.core.Pin
import java.time.LocalDate

/** First setup on a new phone: open a backup file with its password and choose a PIN for this phone. */
@Composable
fun RestoreScreen(
    busy: Boolean,
    error: String?,
    onRestore: (password: String, pin: String, confirm: String) -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val canSubmit = !busy && password.isNotEmpty() && pin.length == Pin.LENGTH && confirm.length == Pin.LENGTH

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Restore from backup", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Enter the backup password you chose when you made the backup. Then choose a " +
                    "PIN for this phone; it can be the same as your old one or different.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SecretField("Backup password", password) { password = it; onEdit() }
            SecretField("New PIN", pin) { pin = pinInput(it); onEdit() }
            SecretField("Confirm PIN", confirm) { confirm = pinInput(it); onEdit() }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            if (busy) {
                CircularProgressIndicator()
            } else {
                Button(onClick = { onRestore(password, pin, confirm) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore")
                }
                TextButton(onClick = onCancel) { Text("Back") }
            }
        }
    }
}

/**
 * Asks for the PIN and a backup password, encrypts the backup, then lets the user pick
 * where to save the file (Downloads, a USB drive, Google Drive, …).
 */
@Composable
fun ExportBackupDialog(
    onPrepare: (pin: String, password: String, onResult: (String?) -> Unit) -> Unit,
    onSave: (file: Uri?, onResult: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        onSave(uri) { result ->
            busy = false
            if (result == null) {
                Toast.makeText(context, "Backup saved", Toast.LENGTH_LONG).show()
                onDismiss()
            } else {
                error = result
            }
        }
    }

    val passwordError = when {
        password.isNotEmpty() && password.length < Backup.MIN_PASSWORD_LENGTH ->
            "At least ${Backup.MIN_PASSWORD_LENGTH} characters"
        confirm.isNotEmpty() && confirm != password -> "The passwords don't match"
        else -> null
    }
    val canSubmit = !busy && pin.length == Pin.LENGTH &&
        password.length >= Backup.MIN_PASSWORD_LENGTH && password == confirm

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Back up to a file") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The file holds your private key, messages and contacts, encrypted with a " +
                        "backup password. Anyone with the file and the password can read your " +
                        "messages and act as you, so pick a strong password and don't keep it " +
                        "next to the file. There's no way to recover a forgotten password.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SecretField("Your PIN", pin) { pin = pinInput(it); error = null }
                SecretField("Backup password", password) { password = it; error = null }
                SecretField("Confirm backup password", confirm, passwordError) {
                    confirm = it; error = null
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    error = null
                    onPrepare(pin, password) { result ->
                        if (result == null) {
                            saver.launch("nostrchat-backup-${LocalDate.now()}.${Backup.FILE_EXTENSION}")
                        } else {
                            busy = false
                            error = result
                        }
                    }
                },
                enabled = canSubmit,
            ) { Text("Create backup") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

private fun pinInput(text: String) = text.filter { it.isLetterOrDigit() && it.code < 128 }.take(Pin.LENGTH)

@Composable
private fun SecretField(
    label: String,
    value: String,
    errorText: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

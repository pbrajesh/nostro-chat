package com.pbrajesh.mobile.nostrchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pbrajesh.mobile.nostrchat.core.Pin

/** First-run PIN setup when [creating], otherwise the unlock prompt. */
@Composable
fun PinScreen(
    creating: Boolean,
    busy: Boolean,
    error: String?,
    onSubmit: (pin: String, confirm: String) -> Unit,
    onEdit: () -> Unit,
    /** Shown at first setup: restore an identity from a backup file instead of creating one. */
    onRestore: (() -> Unit)? = null,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val canSubmit = !busy && pin.length == Pin.LENGTH && (!creating || confirm.length == Pin.LENGTH)
    val submit = { if (canSubmit) onSubmit(pin, confirm) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (creating) "Choose a PIN" else "Enter your PIN",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                if (creating) {
                    "Your PIN is 4 letters or digits, and it's case-sensitive. It encrypts the " +
                        "private key this app is about to create. There's no way to recover it, " +
                        "so pick something you'll remember."
                } else {
                    "Your private key is encrypted. Enter your PIN to unlock it."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            PinField("PIN", pin, imeAction = if (creating) ImeAction.Next else ImeAction.Done, onDone = submit) {
                pin = it; onEdit()
            }
            if (creating) {
                PinField("Confirm PIN", confirm, imeAction = ImeAction.Done, onDone = submit) {
                    confirm = it; onEdit()
                }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            if (busy) {
                CircularProgressIndicator()
            } else {
                Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                    Text(if (creating) "Create my keys" else "Unlock")
                }
                if (onRestore != null) {
                    TextButton(onClick = onRestore) { Text("Restore from a backup file") }
                }
            }
        }
    }
}

@Composable
private fun PinField(
    label: String,
    value: String,
    imeAction: ImeAction,
    onDone: () -> Unit,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onChange(new.filter { it.isLetterOrDigit() && it.code < 128 }.take(Pin.LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

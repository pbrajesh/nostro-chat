package com.pbrajesh.mobile.nostrchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pbrajesh.mobile.nostrchat.ui.HomeScreen
import com.pbrajesh.mobile.nostrchat.ui.PinScreen
import com.pbrajesh.mobile.nostrchat.ui.RestoreScreen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val retention by viewModel.retention.collectAsStateWithLifecycle()
                val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(viewModel::startRestore)
                }
                when (val screen = state.screen) {
                    Screen.Setup -> PinScreen(
                        creating = true,
                        busy = state.busy,
                        error = state.error,
                        onSubmit = viewModel::createIdentity,
                        onEdit = viewModel::clearError,
                        // Any type: file managers and cloud drives rarely know the .ncbak extension.
                        onRestore = { backupPicker.launch(arrayOf("*/*")) },
                    )
                    is Screen.Restore -> RestoreScreen(
                        busy = state.busy,
                        error = state.error,
                        onRestore = viewModel::restore,
                        onEdit = viewModel::clearError,
                        onCancel = viewModel::cancelRestore,
                    )
                    Screen.Unlock -> PinScreen(
                        creating = false,
                        busy = state.busy,
                        error = state.error,
                        onSubmit = { pin, _ -> viewModel.unlock(pin) },
                        onEdit = viewModel::clearError,
                    )
                    is Screen.Home -> HomeScreen(
                        identity = screen.identity,
                        messenger = screen.messenger,
                        contacts = state.contacts,
                        onSend = viewModel::sendMessage,
                        retention = retention,
                        onRetentionChange = viewModel::setRetention,
                        onAddContact = viewModel::addContact,
                        onRemoveContact = viewModel::removeContact,
                        onPrepareBackup = viewModel::prepareBackup,
                        onSaveBackup = viewModel::saveBackup,
                        onLock = viewModel::lock,
                    )
                }
            }
        }
    }
}

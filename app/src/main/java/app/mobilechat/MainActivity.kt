package app.mobilechat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilechat.ui.HomeScreen
import app.mobilechat.ui.PinScreen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                when (val screen = state.screen) {
                    Screen.Setup -> PinScreen(
                        creating = true,
                        busy = state.busy,
                        error = state.error,
                        onSubmit = viewModel::createIdentity,
                        onEdit = viewModel::clearError,
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
                        onAddContact = viewModel::addContact,
                        onRemoveContact = viewModel::removeContact,
                        onLock = viewModel::lock,
                    )
                }
            }
        }
    }
}

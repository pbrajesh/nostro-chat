package com.pbrajesh.mobile.nostrchat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pbrajesh.mobile.nostrchat.core.NostrKeys
import com.pbrajesh.mobile.nostrchat.core.LocalKeys
import com.pbrajesh.mobile.nostrchat.core.Pin
import com.pbrajesh.mobile.nostrchat.core.Retention
import com.pbrajesh.mobile.nostrchat.data.Contact
import com.pbrajesh.mobile.nostrchat.data.ContactStore
import com.pbrajesh.mobile.nostrchat.data.Identity
import com.pbrajesh.mobile.nostrchat.data.KeyVault
import com.pbrajesh.mobile.nostrchat.data.MessageStore
import com.pbrajesh.mobile.nostrchat.data.Messenger
import com.pbrajesh.mobile.nostrchat.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.pbrajesh.mobile.nostrchat.data.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Setup : Screen
    data object Unlock : Screen
    data class Home(val identity: Identity, val messenger: Messenger) : Screen
}

data class UiState(
    val screen: Screen,
    val busy: Boolean = false,
    val error: String? = null,
    val contacts: List<Contact> = emptyList(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = KeyVault(app)
    private val contactStore = ContactStore(app)
    private val settings = Settings(app)
    private var pruneJob: Job? = null

    private val _retention = MutableStateFlow(settings.retention)
    val retention: StateFlow<Retention> = _retention.asStateFlow()

    private val _state = MutableStateFlow(
        UiState(screen = if (vault.hasIdentity()) Screen.Unlock else Screen.Setup)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun createIdentity(pin: String, confirm: String) {
        when {
            !Pin.isValid(pin) -> return fail("PIN must be exactly 4 letters or digits.")
            pin != confirm -> return fail("The two PINs don't match.")
        }
        runBusy {
            enterHome(withContext(Dispatchers.Default) { vault.create(pin.toCharArray()) })
        }
    }

    fun unlock(pin: String) {
        if (!Pin.isValid(pin)) return fail("Enter your 4-character PIN.")
        runBusy {
            when (val result = withContext(Dispatchers.Default) { vault.unlock(pin.toCharArray()) }) {
                is UnlockResult.Success -> enterHome(result.identity)
                is UnlockResult.WrongPin -> fail(
                    "Wrong PIN. ${result.attemptsBeforeLockout} more tries before a wait."
                )
                is UnlockResult.LockedOut -> {
                    val seconds = ((result.untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                    fail("Too many wrong PINs. Try again in $seconds seconds.")
                }
            }
        }
    }

    fun lock() {
        pruneJob?.cancel()
        (_state.value.screen as? Screen.Home)?.let {
            it.messenger.stop()
            it.identity.privateKey.fill(0)
        }
        _state.value = UiState(screen = Screen.Unlock)
    }

    fun sendMessage(peerHex: String, text: String) {
        val home = _state.value.screen as? Screen.Home ?: return
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.Default) { home.messenger.send(peerHex, text.trim()) }
    }

    fun setRetention(value: Retention) {
        settings.retention = value
        _retention.value = value
        val home = _state.value.screen as? Screen.Home ?: return
        viewModelScope.launch(Dispatchers.IO) { home.messenger.prune() }
    }

    override fun onCleared() {
        (_state.value.screen as? Screen.Home)?.messenger?.stop()
    }

    /** Returns an error message, or null when the contact was added. */
    fun addContact(keyText: String, name: String): String? {
        val key = NostrKeys.parsePublicKey(keyText) ?: return "That isn't a valid npub."
        val me = (_state.value.screen as? Screen.Home)?.identity
        if (me != null && key.contentEquals(me.publicKey)) return "That's your own key."
        contactStore.add(key, name)
        _state.update { it.copy(contacts = contactStore.all()) }
        return null
    }

    fun removeContact(contact: Contact) {
        contactStore.remove(contact.publicKeyHex)
        _state.update { it.copy(contacts = contactStore.all()) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun enterHome(identity: Identity) {
        val app = getApplication<Application>()
        val messenger = withContext(Dispatchers.IO) {
            val store = MessageStore.open(app, LocalKeys.databaseKey(identity.privateKey))
            Messenger(identity, store, retention = { settings.retention }).also { it.start() }
        }
        _state.value = UiState(screen = Screen.Home(identity, messenger), contacts = contactStore.all())
        // Old messages also expire while the app stays open.
        pruneJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10 * 60 * 1000L)
                messenger.prune()
            }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(busy = false, error = message) }

    private fun runBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                fail("Something went wrong: ${e.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

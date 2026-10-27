package com.pbrajesh.mobile.nostrchat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pbrajesh.mobile.nostrchat.core.Backup
import com.pbrajesh.mobile.nostrchat.core.BackupContents
import com.pbrajesh.mobile.nostrchat.core.InvalidBackupException
import com.pbrajesh.mobile.nostrchat.core.NostrKeys
import com.pbrajesh.mobile.nostrchat.core.WrongBackupPasswordException
import com.pbrajesh.mobile.nostrchat.core.LocalKeys
import com.pbrajesh.mobile.nostrchat.core.Pin
import com.pbrajesh.mobile.nostrchat.core.Retention
import com.pbrajesh.mobile.nostrchat.core.toHex
import com.pbrajesh.mobile.nostrchat.data.Contact
import com.pbrajesh.mobile.nostrchat.data.ContactStore
import com.pbrajesh.mobile.nostrchat.data.Identity
import com.pbrajesh.mobile.nostrchat.data.KeyVault
import com.pbrajesh.mobile.nostrchat.data.MessageStore
import com.pbrajesh.mobile.nostrchat.data.RelayDirectory
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
    /** Restoring from a backup file the user picked at first setup. */
    data class Restore(val file: Uri) : Screen
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
    private val relayDirectory = RelayDirectory(app)
    private var pruneJob: Job? = null
    /** An encrypted backup waiting for the user to pick where to save it. */
    private var pendingBackup: ByteArray? = null

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

    fun startRestore(file: Uri) {
        _state.value = UiState(screen = Screen.Restore(file))
    }

    fun cancelRestore() {
        _state.value = UiState(screen = Screen.Setup)
    }

    fun restore(password: String, pin: String, confirm: String) {
        val screen = _state.value.screen as? Screen.Restore ?: return
        when {
            password.isEmpty() -> return fail("Enter the backup password.")
            !Pin.isValid(pin) -> return fail("PIN must be exactly 4 letters or digits.")
            pin != confirm -> return fail("The two PINs don't match.")
        }
        runBusy {
            val app = getApplication<Application>()
            val contents = try {
                withContext(Dispatchers.IO) {
                    val bytes = app.contentResolver.openInputStream(screen.file)?.use { it.readBytes() }
                        ?: throw InvalidBackupException("Couldn't open that file.")
                    withContext(Dispatchers.Default) { Backup.decrypt(bytes, password.toCharArray()) }
                }
            } catch (e: WrongBackupPasswordException) {
                return@runBusy fail("Wrong backup password.")
            } catch (e: InvalidBackupException) {
                return@runBusy fail(e.message)
            }
            // Messages, contacts and settings first: the identity is only saved once they
            // are in place, so a failure here leaves the phone at first setup.
            withContext(Dispatchers.IO) {
                app.deleteDatabase("messages.db")
                MessageStore.open(app, LocalKeys.databaseKey(contents.privateKey)).use { it.insertAll(contents.messages) }
            }
            contactStore.replaceAll(contents.contacts.map { (pub, name) -> Contact(pub, name) })
            contents.retention?.let {
                settings.retention = Retention.fromName(it)
                _retention.value = settings.retention
            }
            val identity = withContext(Dispatchers.Default) { vault.restore(contents.privateKey, pin.toCharArray()) }
            contents.privateKey.fill(0)
            enterHome(identity)
        }
    }

    /**
     * Checks the PIN and encrypts a backup with [password]. [onResult] gets null when the
     * backup is ready to save with [saveBackup], or an error message.
     */
    fun prepareBackup(pin: String, password: String, onResult: (String?) -> Unit) {
        val home = _state.value.screen as? Screen.Home ?: return
        viewModelScope.launch {
            val error = try {
                when (val check = withContext(Dispatchers.Default) { vault.verifyPin(pin.toCharArray()) }) {
                    is UnlockResult.WrongPin -> "Wrong PIN. ${check.attemptsBeforeLockout} more tries before a wait."
                    is UnlockResult.LockedOut -> "Too many wrong PINs. Try again later."
                    is UnlockResult.Success -> {
                        val contents = BackupContents(
                            privateKey = home.identity.privateKey,
                            contacts = contactStore.all().map { it.publicKeyHex to it.name },
                            messages = withContext(Dispatchers.IO) { home.messenger.allMessages() },
                            retention = settings.retention.name,
                        )
                        pendingBackup = withContext(Dispatchers.Default) { Backup.encrypt(contents, password.toCharArray()) }
                        null
                    }
                }
            } catch (e: Exception) {
                "Couldn't create the backup: ${e.message}"
            }
            onResult(error)
        }
    }

    /** Writes the prepared backup to [file]. [onResult] gets null on success, or an error message. */
    fun saveBackup(file: Uri?, onResult: (String?) -> Unit) {
        val bytes = pendingBackup ?: return
        pendingBackup = null
        if (file == null) return onResult("Backup not saved.")
        viewModelScope.launch {
            val error = try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(file, "wt")!!.use { it.write(bytes) }
                }
                null
            } catch (e: Exception) {
                "Couldn't save the backup: ${e.message}"
            }
            onResult(error)
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
        val profile = NostrKeys.parseProfile(keyText) ?: return "That isn't a valid npub."
        val key = profile.publicKey
        val me = (_state.value.screen as? Screen.Home)?.identity
        if (me != null && key.contentEquals(me.publicKey)) return "That's your own key."
        contactStore.add(key, name)
        // Relay hints from an nprofile, until their own inbox list (kind 10050) arrives.
        if (profile.relays.isNotEmpty()) relayDirectory.saveInbox(key.toHex(), profile.relays, createdAt = 0)
        _state.update { it.copy(contacts = contactStore.all()) }
        (_state.value.screen as? Screen.Home)?.messenger?.refreshContacts()
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
            Messenger(
                identity = identity,
                store = store,
                retention = { settings.retention },
                directory = relayDirectory,
                contactKeys = { contactStore.all().map { it.publicKeyHex } },
                parentScope = viewModelScope,
            ).also { it.start() }
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

    private fun fail(message: String?) = _state.update { it.copy(busy = false, error = message) }

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

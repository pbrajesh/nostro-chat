package com.pbrajesh.mobile.nostrchat.data

import com.pbrajesh.mobile.nostrchat.core.DirectMessage
import com.pbrajesh.mobile.nostrchat.core.Event
import com.pbrajesh.mobile.nostrchat.core.Nip17
import com.pbrajesh.mobile.nostrchat.core.Nip66
import com.pbrajesh.mobile.nostrchat.core.RelayLists
import com.pbrajesh.mobile.nostrchat.core.RelayPool
import com.pbrajesh.mobile.nostrchat.core.Retention
import com.pbrajesh.mobile.nostrchat.core.hexToBytes
import com.pbrajesh.mobile.nostrchat.core.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class RelayStatus(val connected: Int, val total: Int)

/**
 * Sends and receives NIP-17 direct messages.
 *
 * Every message from a relay is written to the encrypted [store] first, and
 * [conversations] is always read back from the store, so the screens only ever show
 * what is in the database. Messages older than the [retention] cutoff are deleted and,
 * if relays send them again, ignored.
 *
 * Relays: the built-in defaults are our inbox (published as kind 10050). On top of
 * those, the app connects to a few fast public relays found through NIP-66 monitor
 * reports, and sends each message to the recipient's own inbox relays when known.
 */
class Messenger(
    private val identity: Identity,
    private val store: MessageStore,
    private val retention: () -> Retention,
    private val directory: RelayDirectory,
    private val contactKeys: () -> List<String>,
    parentScope: CoroutineScope,
) {
    // Our own child scope, so stop() cancels the reader and discovery jobs.
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)

    private val myHex = identity.publicKey.toHex()
    private val inboxRelays = RelayPool.DEFAULT_RELAYS

    private val _conversations = MutableStateFlow<Map<String, List<DirectMessage>>>(emptyMap())
    /** Peer public key (hex) -> messages, oldest first. Read from the database only. */
    val conversations: StateFlow<Map<String, List<DirectMessage>>> = _conversations.asStateFlow()

    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of messages we sent that no relay has accepted yet. */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private val _status = MutableStateFlow(RelayStatus(0, inboxRelays.size))
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    /** Bumped after every database write; the reader below reloads, at most every 150 ms. */
    private val dbVersion = MutableStateFlow(0L)
    private val writes = AtomicLong()

    private val seenWraps = ConcurrentHashMap.newKeySet<String>()
    private val wrapToMessage = ConcurrentHashMap<String, String>()
    private val discoveryReports = ConcurrentHashMap.newKeySet<Nip66.RelayReport>()

    private val pool = RelayPool(
        urls = inboxRelays + directory.discovered(),
        listener = object : RelayPool.Listener {
            override fun onEvent(relay: String, subscriptionId: String, event: Event) {
                when (event.kind) {
                    Nip17.KIND_GIFT_WRAP -> if (seenWraps.add(event.id)) {
                        Nip17.unwrap(event, identity.privateKey)?.let(::save)
                    }
                    Nip17.KIND_DM_RELAYS -> if (event.pubkey != myHex && event.verify()) {
                        directory.saveInbox(event.pubkey, RelayLists.inboxRelays(event), event.createdAt)
                    }
                    Nip66.KIND_RELAY_DISCOVERY -> Nip66.parse(event)?.let { discoveryReports += it }
                }
            }

            override fun onPublished(relay: String, eventId: String, accepted: Boolean, message: String) {
                if (accepted) wrapToMessage[eventId]?.let { id -> _pending.update { it - id } }
            }

            override fun onConnectionsChanged(connected: Int, total: Int) {
                _status.value = RelayStatus(connected, total)
            }
        },
        authSigner = { relay, challenge -> RelayPool.authEvent(identity.privateKey, relay, challenge, now()) },
    )

    fun start() {
        scope.launch {
            dbVersion.collectLatest {
                delay(150)
                reload()
            }
        }
        prune()
        pool.start()
        pool.subscribe("dms", """{"kinds":[${Nip17.KIND_GIFT_WRAP}],"#p":["$myHex"],"limit":1000}""")
        refreshContacts()
        publishProfile()
        if (now() - directory.discoveredAt() > DISCOVERY_INTERVAL) discoverRelays()
    }

    fun stop() {
        job.cancel()
        pool.stop()
        store.close()
    }

    /** Re-reads contacts' inbox relay lists, e.g. after a contact is added. */
    fun refreshContacts() {
        val keys = contactKeys()
        if (keys.isEmpty()) return
        val authors = keys.joinToString(",") { Event.quote(it) }
        pool.subscribe("contact-inboxes", """{"kinds":[${Nip17.KIND_DM_RELAYS}],"authors":[$authors]}""")
    }

    /** Every stored message, for a backup file. */
    fun allMessages(): List<DirectMessage> = store.all()

    /** Applies the retention setting to the database. */
    fun prune() {
        val cutoff = retention().cutoff(now()) ?: return
        runCatching { store.deleteOlderThan(cutoff) }
        changed()
    }

    fun send(peerHex: String, text: String) {
        val (toPeer, toMe) = Nip17.wrapMessage(identity.privateKey, peerHex.hexToBytes(), text, now())
        val message = Nip17.unwrap(toMe, identity.privateKey) ?: return
        seenWraps += toMe.id
        wrapToMessage[toPeer.id] = message.id
        _pending.update { it + message.id }
        save(message)
        // Their inbox relays (if they published any) plus ours, which they may read too.
        pool.publish(toPeer, to = directory.inboxOf(peerHex) + inboxRelays)
        pool.publish(toMe, to = inboxRelays)
    }

    /**
     * Collects NIP-66 monitor reports for a while, then connects to the fastest
     * relays found. The choice is saved and reused on the next launches.
     */
    private fun discoverRelays() {
        val started = now()
        pool.addRelays(Nip66.BOOTSTRAP_RELAYS)
        pool.subscribe("discovery", Nip66.filter(started))
        scope.launch {
            delay(DISCOVERY_WAIT_MS)
            pool.unsubscribe("discovery")
            val picked = Nip66.pick(discoveryReports, now(), exclude = inboxRelays.toSet())
            if (picked.isNotEmpty()) {
                directory.saveDiscovered(picked, started)
                pool.addRelays(picked)
            }
        }
    }

    /**
     * Publishes our username (kind 0) and the relays we read DMs from (kind 10050),
     * so other Nostr apps show our name and know where to deliver messages.
     */
    private fun publishProfile() {
        val name = Event.quote(identity.username)
        pool.publish(Event.signed(identity.privateKey, 0, emptyList(), "{\"name\":$name,\"display_name\":$name}", now()))
        pool.publish(Event.signed(identity.privateKey, Nip17.KIND_DM_RELAYS, inboxRelays.map { listOf("relay", it) }, "", now()))
    }

    private fun save(message: DirectMessage) {
        val cutoff = retention().cutoff(now())
        if (cutoff != null && message.createdAt < cutoff) return
        // The store may already be closed if an event lands while the app is locking.
        val inserted = runCatching { store.insert(message) }.getOrDefault(false)
        if (inserted) changed()
    }

    private fun changed() {
        dbVersion.value = writes.incrementAndGet()
    }

    private fun reload() {
        runCatching { store.all() }.onSuccess { all -> _conversations.value = all.groupBy { it.peer } }
    }

    private fun now() = System.currentTimeMillis() / 1000

    private companion object {
        const val DISCOVERY_INTERVAL = 24 * 60 * 60L
        const val DISCOVERY_WAIT_MS = 15_000L
    }
}

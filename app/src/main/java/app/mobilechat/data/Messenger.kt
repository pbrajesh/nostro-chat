package app.mobilechat.data

import app.mobilechat.core.DirectMessage
import app.mobilechat.core.Event
import app.mobilechat.core.Nip17
import app.mobilechat.core.RelayPool
import app.mobilechat.core.Retention
import app.mobilechat.core.hexToBytes
import app.mobilechat.core.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class RelayStatus(val connected: Int, val total: Int)

/**
 * Sends and receives NIP-17 direct messages through public relays and keeps them in
 * the encrypted [store]. Messages older than the [retention] cutoff are deleted and,
 * if relays send them again, ignored.
 */
class Messenger(
    private val identity: Identity,
    private val store: MessageStore,
    private val retention: () -> Retention,
    relays: List<String> = RelayPool.DEFAULT_RELAYS,
) {
    private val myHex = identity.publicKey.toHex()

    private val _conversations = MutableStateFlow<Map<String, List<DirectMessage>>>(emptyMap())
    /** Peer public key (hex) -> messages, oldest first. */
    val conversations: StateFlow<Map<String, List<DirectMessage>>> = _conversations.asStateFlow()

    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of messages we sent that no relay has accepted yet. */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private val _status = MutableStateFlow(RelayStatus(0, relays.size))
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private val seenWraps = ConcurrentHashMap.newKeySet<String>()
    private val wrapToMessage = ConcurrentHashMap<String, String>()

    private val pool = RelayPool(
        urls = relays,
        listener = object : RelayPool.Listener {
            override fun onEvent(relay: String, subscriptionId: String, event: Event) {
                if (event.kind != Nip17.KIND_GIFT_WRAP || !seenWraps.add(event.id)) return
                Nip17.unwrap(event, identity.privateKey)?.let(::add)
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
        prune()
        _conversations.value = store.all().groupBy { it.peer }
        pool.start()
        pool.subscribe("dms", """{"kinds":[${Nip17.KIND_GIFT_WRAP}],"#p":["$myHex"],"limit":1000}""")
        publishProfile(relays = RelayPool.DEFAULT_RELAYS)
    }

    fun stop() {
        pool.stop()
        store.close()
    }

    /** Applies the retention setting to the database and the open conversations. */
    fun prune() {
        val cutoff = retention().cutoff(now()) ?: return
        runCatching { store.deleteOlderThan(cutoff) }
        _conversations.update { all ->
            all.mapValues { (_, messages) -> messages.filter { it.createdAt >= cutoff } }
                .filterValues { it.isNotEmpty() }
        }
    }

    fun send(peerHex: String, text: String) {
        val (toPeer, toMe) = Nip17.wrapMessage(identity.privateKey, peerHex.hexToBytes(), text, now())
        val message = Nip17.unwrap(toMe, identity.privateKey) ?: return
        seenWraps += toMe.id
        wrapToMessage[toPeer.id] = message.id
        _pending.update { it + message.id }
        add(message)
        pool.publish(toPeer)
        pool.publish(toMe)
    }

    /**
     * Publishes our username (kind 0) and the relays we read DMs from (kind 10050),
     * so other Nostr apps show our name and know where to deliver messages.
     */
    private fun publishProfile(relays: List<String>) {
        val name = Event.quote(identity.username)
        pool.publish(Event.signed(identity.privateKey, 0, emptyList(), "{\"name\":$name,\"display_name\":$name}", now()))
        pool.publish(Event.signed(identity.privateKey, Nip17.KIND_DM_RELAYS, relays.map { listOf("relay", it) }, "", now()))
    }

    private fun add(message: DirectMessage) {
        val cutoff = retention().cutoff(now())
        if (cutoff != null && message.createdAt < cutoff) return
        // The store may already be closed if an event lands while the app is locking.
        runCatching { store.insert(message) }
        _conversations.update { all ->
            val existing = all[message.peer].orEmpty()
            if (existing.any { it.id == message.id }) all
            else all + (message.peer to (existing + message).sortedBy { it.createdAt })
        }
    }

    private fun now() = System.currentTimeMillis() / 1000
}

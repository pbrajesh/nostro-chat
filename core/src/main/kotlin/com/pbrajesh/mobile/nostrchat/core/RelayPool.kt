package com.pbrajesh.mobile.nostrchat.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps WebSocket connections to a set of Nostr relays (NIP-01), resends
 * subscriptions after reconnects, and answers NIP-42 AUTH challenges.
 */
class RelayPool(
    private val urls: List<String>,
    private val listener: Listener,
    /** Signs a NIP-42 auth event (kind 22242) for the given relay and challenge. */
    private val authSigner: (relay: String, challenge: String) -> Event,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build(),
) {
    interface Listener {
        fun onEvent(relay: String, subscriptionId: String, event: Event)
        fun onPublished(relay: String, eventId: String, accepted: Boolean, message: String) {}
        fun onConnectionsChanged(connected: Int, total: Int) {}
    }

    private val subscriptions = ConcurrentHashMap<String, String>() // id -> filter JSON
    private val relays = urls.associateWith { Relay(it) }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay-pool").apply { isDaemon = true } }
    @Volatile private var running = false

    fun start() {
        running = true
        relays.values.forEach { it.connect() }
    }

    fun stop() {
        running = false
        relays.values.forEach { it.close() }
        scheduler.shutdownNow()
    }

    /** [filterJson] is one NIP-01 filter object. */
    fun subscribe(id: String, filterJson: String) {
        subscriptions[id] = filterJson
        relays.values.forEach { it.sendReq(id, filterJson) }
    }

    fun publish(event: Event) {
        relays.values.forEach { it.publish(event) }
    }

    fun connectedCount() = relays.values.count { it.connected }

    private fun notifyConnections() = listener.onConnectionsChanged(connectedCount(), relays.size)

    private inner class Relay(val url: String) : WebSocketListener() {
        @Volatile var connected = false
        private var socket: WebSocket? = null
        private var backoffSeconds = 2L
        private val outbox = ArrayDeque<String>() // events waiting for a connection
        private val authEventIds = ConcurrentHashMap.newKeySet<String>()

        fun connect() {
            if (!running) return
            socket = client.newWebSocket(Request.Builder().url(url).build(), this)
        }

        fun close() {
            socket?.close(1000, null)
            socket = null
            connected = false
        }

        fun sendReq(id: String, filter: String) {
            if (connected) socket?.send("[\"REQ\",${Event.quote(id)},$filter]")
        }

        fun publish(event: Event) {
            val message = "[\"EVENT\",${event.toJson()}]"
            synchronized(outbox) {
                if (connected && socket?.send(message) == true) return
                outbox.addLast(message)
                while (outbox.size > 200) outbox.removeFirst()
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            backoffSeconds = 2
            subscriptions.forEach { (id, filter) -> sendReq(id, filter) }
            synchronized(outbox) {
                while (outbox.isNotEmpty()) webSocket.send(outbox.removeFirst())
            }
            notifyConnections()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONArray(text) }.getOrNull() ?: return
            when (msg.optString(0)) {
                "EVENT" -> runCatching { Event.fromJson(msg.getJSONObject(2)) }.getOrNull()
                    ?.let { listener.onEvent(url, msg.getString(1), it) }
                "OK" -> {
                    val id = msg.optString(1)
                    if (authEventIds.remove(id)) {
                        // Authenticated: relays that refused our REQ before will now answer it.
                        if (msg.optBoolean(2)) subscriptions.forEach { (sid, filter) -> sendReq(sid, filter) }
                    } else {
                        listener.onPublished(url, id, msg.optBoolean(2), msg.optString(3))
                    }
                }
                "AUTH" -> {
                    val auth = authSigner(url, msg.optString(1))
                    authEventIds += auth.id
                    webSocket.send("[\"AUTH\",${auth.toJson()}]")
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()

        private fun dropped() {
            val wasConnected = connected
            connected = false
            socket = null
            if (wasConnected) notifyConnections()
            if (!running) return
            val delay = backoffSeconds
            backoffSeconds = (backoffSeconds * 2).coerceAtMost(300)
            runCatching { scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS) }
        }
    }

    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.0xchat.com",
        )

        fun authEvent(privateKey: ByteArray, relay: String, challenge: String, now: Long): Event =
            Event.signed(privateKey, 22242, listOf(listOf("relay", relay), listOf("challenge", challenge)), "", now)
    }
}

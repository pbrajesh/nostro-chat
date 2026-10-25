package app.mobilechat.core

import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sends a real NIP-17 message between two fresh keys over the default public relays.
 * Needs internet, so it only runs when RELAY_SMOKE_TEST=1 (CI sets it in its own step).
 */
class LiveRelayTest {
    @Test fun sendAndReceiveOverRealRelays() {
        assumeTrue(System.getenv("RELAY_SMOKE_TEST") == "1")
        val alice = NostrKeys.generatePrivateKey()
        val bob = NostrKeys.generatePrivateKey()
        val bobPub = NostrKeys.publicKey(bob)
        val got = CountDownLatch(1)
        val oks = mutableListOf<String>()
        fun pool(key: ByteArray, onEvent: (String, Event) -> Unit) = RelayPool(
            RelayPool.DEFAULT_RELAYS,
            object : RelayPool.Listener {
                override fun onEvent(relay: String, subscriptionId: String, event: Event) = onEvent(relay, event)
                override fun onPublished(relay: String, eventId: String, accepted: Boolean, message: String) {
                    synchronized(oks) { oks += "$relay $accepted $message" }
                }
                override fun onConnectionsChanged(connected: Int, total: Int) = println("conn $connected/$total")
            },
            authSigner = { r, c -> RelayPool.authEvent(key, r, c, System.currentTimeMillis() / 1000) },
        )
        val bobPool = pool(bob) { relay, e ->
            val dm = Nip17.unwrap(e, bob)
            println("bob got from $relay: ${dm?.text}")
            if (dm?.text == "hello from test") got.countDown()
        }
        bobPool.start()
        bobPool.subscribe("dms", """{"kinds":[1059],"#p":["${bobPub.toHex()}"]}""")
        val alicePool = pool(alice) { _, _ -> }
        alicePool.start()
        Thread.sleep(3000)
        Nip17.wrapMessage(alice, bobPub, "hello from test", System.currentTimeMillis() / 1000).forEach(alicePool::publish)
        val ok = got.await(30, TimeUnit.SECONDS)
        Thread.sleep(2000)
        synchronized(oks) { oks.forEach(::println) }
        alicePool.stop(); bobPool.stop()
        assertTrue(ok)
    }
}

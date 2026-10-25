package com.pbrajesh.mobile.nostrchat.core

import org.junit.Assume.assumeTrue
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Asks real relays for NIP-66 monitor reports and prints what would be picked.
 * Only runs when RELAY_SMOKE_TEST=1.
 */
class LiveDiscoveryTest {
    @Test fun discoversRelaysFromMonitors() {
        assumeTrue(System.getenv("RELAY_SMOKE_TEST") == "1")
        val key = NostrKeys.generatePrivateKey()
        val now = System.currentTimeMillis() / 1000
        val reports = ConcurrentHashMap<String, MutableList<Nip66.RelayReport>>()
        val pool = RelayPool(
            RelayPool.DEFAULT_RELAYS + Nip66.BOOTSTRAP_RELAYS,
            object : RelayPool.Listener {
                override fun onEvent(relay: String, subscriptionId: String, event: Event) {
                    Nip66.parse(event)?.let { reports.getOrPut(relay) { mutableListOf() }.add(it) }
                }
            },
            authSigner = { r, c -> RelayPool.authEvent(key, r, c, now) },
        )
        pool.start()
        pool.subscribe("discovery", Nip66.filter(now))
        Thread.sleep(15_000)
        pool.stop()
        reports.forEach { (relay, list) -> println("reports from $relay: ${list.size}") }
        val picked = Nip66.pick(reports.values.flatten(), now, exclude = RelayPool.DEFAULT_RELAYS.toSet())
        val all = reports.values.flatten()
        println("monitors: ${all.map { it.monitor }.distinct().size}, relays reported: ${all.map { it.url }.distinct().size}")
        println("picked: $picked")
        assertTrue(picked.isNotEmpty())
    }
}

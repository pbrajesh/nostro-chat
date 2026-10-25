package com.pbrajesh.mobile.nostrchat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RelayListsTest {
    private val key = NostrKeys.generatePrivateKey()
    private val now = 1_700_000_000L

    private fun report(url: String, vararg tags: List<String>, at: Long = now) =
        Event.signed(key, Nip66.KIND_RELAY_DISCOVERY, listOf(listOf("d", url)) + tags, "", at)

    @Test fun normalizesRelayUrls() {
        assertEquals("wss://relay.example.com", RelayLists.normalize(" WSS://Relay.Example.com/ "))
        assertEquals("wss://example.com/nostr", RelayLists.normalize("wss://EXAMPLE.com/nostr/"))
        assertNull(RelayLists.normalize("ws://insecure.example.com"))
        assertNull(RelayLists.normalize("https://example.com"))
        assertNull(RelayLists.normalize("wss://"))
    }

    @Test fun readsInboxRelayList() {
        val tags = listOf("wss://a.example", "wss://A.example/", "ws://bad", "wss://b.example", "wss://c.example",
            "wss://d.example", "wss://e.example", "wss://f.example").map { listOf("relay", it) }
        val event = Event.signed(key, Nip17.KIND_DM_RELAYS, tags, "", now)
        assertEquals(
            listOf("wss://a.example", "wss://b.example", "wss://c.example", "wss://d.example", "wss://e.example"),
            RelayLists.inboxRelays(event),
        )
        assertEquals(emptyList(), RelayLists.inboxRelays(event.copy(kind = 1)))
    }

    @Test fun parsesMonitorReports() {
        val ok = assertNotNull(Nip66.parse(report("wss://fast.example/", listOf("rtt-open", "120"), listOf("n", "clearnet"))))
        assertEquals("wss://fast.example", ok.url)
        assertEquals(120, ok.rttOpenMs)
        assertNotNull(Nip66.parse(report("wss://free.example", listOf("R", "!payment"), listOf("R", "!auth"))))
        assertNull(Nip66.parse(report("wss://paid.example", listOf("R", "payment"))))
        assertNull(Nip66.parse(report("wss://private.example", listOf("R", "auth"))))
        assertNull(Nip66.parse(report("wss://hidden.example", listOf("n", "tor"))))
        assertNull(Nip66.parse(report("ws://insecure.example")))
    }

    @Test fun picksFastestFreshRelays() {
        val reports = listOf(
            Nip66.RelayReport("wss://slow.example", 900, now),
            Nip66.RelayReport("wss://fast.example", 50, now),
            Nip66.RelayReport("wss://fast.example", 2000, now - 100), // older report of the same relay
            Nip66.RelayReport("wss://unknown.example", null, now),
            Nip66.RelayReport("wss://stale.example", 10, now - Nip66.MAX_AGE_SECONDS - 1),
            Nip66.RelayReport("wss://default.example", 5, now),
        )
        assertEquals(
            listOf("wss://fast.example", "wss://slow.example", "wss://unknown.example"),
            Nip66.pick(reports, now, exclude = setOf("wss://default.example"), count = 3),
        )
    }
}

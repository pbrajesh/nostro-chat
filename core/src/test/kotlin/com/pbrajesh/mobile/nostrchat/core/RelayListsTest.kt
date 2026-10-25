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

    @Test fun picksRelaysMostMonitorsVouchFor() {
        fun r(url: String, rtt: Int?, monitor: String, at: Long = now) = Nip66.RelayReport(url, rtt, at, monitor)
        val reports = listOf(
            r("wss://popular.example", 300, "m1"), r("wss://popular.example", 200, "m2"), r("wss://popular.example", 250, "m3"),
            r("wss://popular.example", 5000, "m1", at = now - 100), // older report from m1 is ignored
            r("wss://tiny.example", 5, "m1"),
            r("wss://pair-fast.example", 100, "m1"), r("wss://pair-fast.example", 120, "m2"),
            r("wss://pair-slow.example", 900, "m1"), r("wss://pair-slow.example", null, "m2"),
            r("wss://stale.example", 1, "m1", at = now - Nip66.MAX_AGE_SECONDS - 1),
            r("wss://stale.example", 1, "m2", at = now - Nip66.MAX_AGE_SECONDS - 1),
            r("wss://stale.example", 1, "m3", at = now - Nip66.MAX_AGE_SECONDS - 1),
            r("wss://default.example", 1, "m1"), r("wss://default.example", 1, "m2"), r("wss://default.example", 1, "m3"),
        )
        assertEquals(
            listOf("wss://popular.example", "wss://pair-fast.example", "wss://pair-slow.example"),
            Nip66.pick(reports, now, exclude = setOf("wss://default.example"), count = 3),
        )
    }
}

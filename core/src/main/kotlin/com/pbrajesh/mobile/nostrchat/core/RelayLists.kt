package com.pbrajesh.mobile.nostrchat.core

/** Relay URL handling and the relay lists other people publish. */
object RelayLists {
    /** Most relays to take from someone's inbox list (kind 10050). */
    const val MAX_INBOX_RELAYS = 5

    /** Lowercases the scheme and host and drops a trailing slash. Only `wss://` is accepted. */
    fun normalize(url: String): String? {
        val trimmed = url.trim().trimEnd('/')
        if (!trimmed.startsWith("wss://", ignoreCase = true)) return null
        val rest = trimmed.substring(6)
        if (rest.isEmpty() || rest.any { it.isWhitespace() }) return null
        val slash = rest.indexOf('/')
        val host = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash)
        if (host.isEmpty()) return null
        return "wss://" + host.lowercase() + path
    }

    /** The relays a person reads private messages from (NIP-17, kind 10050). */
    fun inboxRelays(event: Event): List<String> =
        if (event.kind != Nip17.KIND_DM_RELAYS) emptyList()
        else event.tagValues("relay").mapNotNull(::normalize).distinct().take(MAX_INBOX_RELAYS)
}

/**
 * NIP-66 relay discovery. Relay monitors publish a kind 30166 event per relay they
 * check, with the relay URL in the `d` tag and measurements such as `rtt-open`.
 */
object Nip66 {
    const val KIND_RELAY_DISCOVERY = 30166

    /** How many discovered relays the app connects to. */
    const val PICK_COUNT = 6

    /** Relays known to carry monitor reports, used in addition to the defaults when looking. */
    val BOOTSTRAP_RELAYS = listOf(
        "wss://relay.nostr.watch",
        "wss://monitorlizard.nostr1.com",
        "wss://relaypag.es",
    )

    /** Monitor reports older than this are ignored. */
    const val MAX_AGE_SECONDS = 24 * 60 * 60L

    data class RelayReport(val url: String, val rttOpenMs: Int?, val seenAt: Long)

    fun filter(now: Long): String =
        """{"kinds":[$KIND_RELAY_DISCOVERY],"since":${now - MAX_AGE_SECONDS},"limit":500}"""

    /**
     * Reads one monitor report. Relays that need payment or authentication to use,
     * or that aren't on the clear internet (Tor, I2P), are skipped.
     */
    fun parse(event: Event): RelayReport? {
        if (event.kind != KIND_RELAY_DISCOVERY) return null
        val url = event.tagValues("d").firstOrNull()?.let(RelayLists::normalize) ?: return null
        val requirements = event.tagValues("R").map { it.lowercase() }
        if ("payment" in requirements || "auth" in requirements) return null
        val network = event.tagValues("n").firstOrNull()?.lowercase()
        if (network != null && network != "clearnet") return null
        if (url.endsWith(".onion") || url.endsWith(".i2p")) return null
        val rtt = event.tagValues("rtt-open").firstOrNull()?.toIntOrNull()
        return RelayReport(url, rtt, event.createdAt)
    }

    /**
     * Picks the [count] relays with the fastest recent connection times, skipping any
     * already in [exclude]. Each relay is judged by its most recent report.
     */
    fun pick(reports: Collection<RelayReport>, now: Long, exclude: Set<String>, count: Int = PICK_COUNT): List<String> =
        reports
            .filter { it.seenAt >= now - MAX_AGE_SECONDS && it.url !in exclude }
            .groupBy { it.url }
            .map { (_, all) -> all.maxBy { it.seenAt } }
            .sortedWith(compareBy(nullsLast()) { it.rttOpenMs })
            .take(count)
            .map { it.url }
}

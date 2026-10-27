package com.pbrajesh.mobile.nostrchat.core

import fr.acinq.secp256k1.Secp256k1
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** A public key and the relays where its owner reads messages (NIP-19 `nprofile`). */
class NostrProfile(val publicKey: ByteArray, val relays: List<String>)

/** secp256k1 key handling and NIP-19 / NIP-21 encodings. */
object NostrKeys {
    private val random = SecureRandom()

    /** A fresh 32-byte private key that is valid on secp256k1. */
    fun generatePrivateKey(): ByteArray {
        while (true) {
            val candidate = ByteArray(32).also(random::nextBytes)
            if (Secp256k1.secKeyVerify(candidate)) return candidate
        }
    }

    /** The 32-byte x-only public key Nostr uses (BIP-340). */
    fun publicKey(privateKey: ByteArray): ByteArray {
        val compressed = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(privateKey))
        return compressed.copyOfRange(1, 33)
    }

    fun npub(publicKey: ByteArray): String = Bech32.encode("npub", publicKey)

    /** `nprofile1...`: the public key (TLV type 0) followed by relay hints (TLV type 1). */
    fun nprofile(publicKey: ByteArray, relays: List<String>): String {
        val tlv = ByteArrayOutputStream()
        tlv.write(0); tlv.write(publicKey.size); tlv.write(publicKey)
        relays.forEach {
            val url = it.toByteArray(Charsets.UTF_8)
            require(url.size <= 255) { "Relay URL too long" }
            tlv.write(1); tlv.write(url.size); tlv.write(url)
        }
        return Bech32.encode("nprofile", tlv.toByteArray())
    }

    /**
     * The NIP-21 URI that goes into the QR code: `nostr:nprofile1...` with relay hints,
     * or `nostr:npub1...` when there are none.
     */
    fun nostrUri(publicKey: ByteArray, relays: List<String> = emptyList()): String =
        "nostr:" + if (relays.isEmpty()) npub(publicKey) else nprofile(publicKey, relays)

    /**
     * Parses what a user might paste or scan: an npub, an nprofile, a `nostr:` URI, or
     * 64-char hex. Returns null unless the key is a valid secp256k1 point. Relay hints are
     * normalized; ones that aren't `wss://` URLs are dropped.
     */
    fun parseProfile(input: String): NostrProfile? {
        val text = input.trim().removePrefix("nostr:").removePrefix("NOSTR:")
        val profile = runCatching {
            if (text.length == 64) {
                NostrProfile(text.hexToBytes(), emptyList())
            } else {
                val (hrp, data) = Bech32.decode(text)
                when (hrp) {
                    "npub" -> NostrProfile(data, emptyList())
                    "nprofile" -> parseProfileTlv(data)
                    else -> return null
                }
            }
        }.getOrNull() ?: return null
        if (profile.publicKey.size != 32) return null
        val onCurve = runCatching { Secp256k1.pubkeyParse(byteArrayOf(0x02) + profile.publicKey) }.isSuccess
        return if (onCurve) profile else null
    }

    /** Just the 32-byte public key from anything [parseProfile] accepts. */
    fun parsePublicKey(input: String): ByteArray? = parseProfile(input)?.publicKey

    private fun parseProfileTlv(data: ByteArray): NostrProfile? {
        var key: ByteArray? = null
        val relays = mutableListOf<String>()
        var i = 0
        while (i < data.size) {
            require(i + 2 <= data.size) { "Truncated TLV" }
            val type = data[i].toInt() and 0xff
            val length = data[i + 1].toInt() and 0xff
            require(i + 2 + length <= data.size) { "Truncated TLV" }
            val value = data.copyOfRange(i + 2, i + 2 + length)
            when (type) {
                0 -> if (key == null) key = value
                1 -> RelayLists.normalize(String(value, Charsets.UTF_8))?.let(relays::add)
                // Other types are ignored, as NIP-19 asks.
            }
            i += 2 + length
        }
        return key?.let { NostrProfile(it, relays.distinct().take(RelayLists.MAX_INBOX_RELAYS)) }
    }
}

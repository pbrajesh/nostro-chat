package com.pbrajesh.mobile.nostrchat.core

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

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

    /** `nostr:npub1...`, the NIP-21 URI that goes into the QR code. */
    fun nostrUri(publicKey: ByteArray): String = "nostr:" + npub(publicKey)

    /**
     * Parses what a user might paste or scan: an npub, a `nostr:` URI, or 64-char hex.
     * Returns the 32-byte public key, or null if it is not a valid secp256k1 point.
     */
    fun parsePublicKey(input: String): ByteArray? {
        val text = input.trim().removePrefix("nostr:").removePrefix("NOSTR:")
        val key = runCatching {
            if (text.length == 64) {
                text.hexToBytes()
            } else {
                val (hrp, data) = Bech32.decode(text)
                if (hrp != "npub") return null
                data
            }
        }.getOrNull() ?: return null
        if (key.size != 32) return null
        val onCurve = runCatching { Secp256k1.pubkeyParse(byteArrayOf(0x02) + key) }.isSuccess
        return if (onCurve) key else null
    }
}

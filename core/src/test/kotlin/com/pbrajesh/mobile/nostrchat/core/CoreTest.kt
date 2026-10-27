package com.pbrajesh.mobile.nostrchat.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    // Test vector from NIP-19.
    private val vectorHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val vectorNpub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    @Test fun npubMatchesNip19Vector() {
        assertEquals(vectorNpub, NostrKeys.npub(vectorHex.hexToBytes()))
    }

    @Test fun parsesNpubUriAndHex() {
        val key = vectorHex.hexToBytes()
        assertContentEquals(key, NostrKeys.parsePublicKey(vectorNpub))
        assertContentEquals(key, NostrKeys.parsePublicKey("nostr:$vectorNpub"))
        assertContentEquals(key, NostrKeys.parsePublicKey("  $vectorHex "))
    }

    @Test fun rejectsBadKeys() {
        assertNull(NostrKeys.parsePublicKey(vectorNpub.dropLast(1) + "q")) // bad checksum
        assertNull(NostrKeys.parsePublicKey("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"))
        assertNull(NostrKeys.parsePublicKey("hello"))
    }

    @Test fun derivesPublicKeyFromNip06Vector() {
        // Private/public pair from NIP-06.
        val priv = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a".hexToBytes()
        assertEquals(
            "17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917",
            NostrKeys.publicKey(priv).toHex(),
        )
    }

    @Test fun generatedKeysRoundTrip() {
        val priv = NostrKeys.generatePrivateKey()
        val pub = NostrKeys.publicKey(priv)
        assertEquals(32, pub.size)
        assertContentEquals(pub, NostrKeys.parsePublicKey(NostrKeys.nostrUri(pub)))
    }

    // Test vector from NIP-19.
    private val vectorNprofile = "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"

    @Test fun nprofileMatchesNip19Vector() {
        val relays = listOf("wss://r.x.com", "wss://djbas.sadkb.com")
        assertEquals(vectorNprofile, NostrKeys.nprofile(vectorHex.hexToBytes(), relays))
        val parsed = NostrKeys.parseProfile("nostr:$vectorNprofile")!!
        assertContentEquals(vectorHex.hexToBytes(), parsed.publicKey)
        assertEquals(relays, parsed.relays)
        assertContentEquals(vectorHex.hexToBytes(), NostrKeys.parsePublicKey(vectorNprofile))
    }

    @Test fun qrUriCarriesRelays() {
        val pub = NostrKeys.publicKey(NostrKeys.generatePrivateKey())
        val uri = NostrKeys.nostrUri(pub, RelayPool.DEFAULT_RELAYS)
        assertTrue(uri.startsWith("nostr:nprofile1"))
        val parsed = NostrKeys.parseProfile(uri)!!
        assertContentEquals(pub, parsed.publicKey)
        assertEquals(RelayPool.DEFAULT_RELAYS, parsed.relays)
        assertEquals(0, NostrKeys.parseProfile(NostrKeys.npub(pub))!!.relays.size)
    }

    @Test fun nprofileDropsBadRelaysAndRejectsMissingKey() {
        val pub = vectorHex.hexToBytes()
        val parsed = NostrKeys.parseProfile(NostrKeys.nprofile(pub, listOf("http://plain.example", "WSS://Relay.Example/")))!!
        assertEquals(listOf("wss://relay.example"), parsed.relays)
        // Only a relay TLV, no public key.
        val noKey = Bech32.encode("nprofile", byteArrayOf(1, 3) + "wss".toByteArray())
        assertNull(NostrKeys.parseProfile(noKey))
        // Truncated key TLV.
        assertNull(NostrKeys.parseProfile(Bech32.encode("nprofile", byteArrayOf(0, 32) + pub.copyOf(10))))
    }

    @Test fun pinValidation() {
        assertTrue(Pin.isValid("a1B2"))
        assertFalse(Pin.isValid("abc"))
        assertFalse(Pin.isValid("abcde"))
        assertFalse(Pin.isValid("ab-1"))
        assertFalse(Pin.isValid("äb12"))
    }

    @Test fun sealAndOpenWithPin() {
        val secret = NostrKeys.generatePrivateKey()
        // Small scrypt cost keeps the test fast; the format records the parameters.
        val sealed = PinCrypto.seal(secret, "Ab12".toCharArray(), logN = 10)
        val restored = SealedSecret.fromBytes(sealed.toBytes())
        assertContentEquals(secret, PinCrypto.open(restored, "Ab12".toCharArray()))
        assertFailsWith<WrongPinException> { PinCrypto.open(restored, "ab12".toCharArray()) }
    }

    @Test fun usernameIsStableAndWellFormed() {
        val pub = vectorHex.hexToBytes()
        val name = Usernames.forPublicKey(pub)
        assertEquals(name, Usernames.forPublicKey(pub))
        assertTrue(Regex("[a-z]+_[a-z]+").matches(name), name)
    }
}

class LocalStorageTest {
    @Test fun retentionCutoffs() {
        val now = 1_700_000_000L
        assertEquals(now - 86_400, Retention.ONE_DAY.cutoff(now))
        assertEquals(now - 864_000, Retention.TEN_DAYS.cutoff(now))
        assertNull(Retention.NEVER.cutoff(now))
        assertEquals(Retention.NEVER, Retention.fromName(null))
        assertEquals(Retention.TEN_DAYS, Retention.fromName("TEN_DAYS"))
    }

    @Test fun databaseKeyIsStablePerIdentity() {
        val a = NostrKeys.generatePrivateKey()
        val b = NostrKeys.generatePrivateKey()
        assertEquals(32, LocalKeys.databaseKey(a).size)
        assertEquals(LocalKeys.databaseKey(a).toHex(), LocalKeys.databaseKey(a.copyOf()).toHex())
        assertTrue(LocalKeys.databaseKey(a).toHex() != LocalKeys.databaseKey(b).toHex())
    }
}

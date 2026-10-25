package app.mobilechat.core

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

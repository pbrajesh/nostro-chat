package com.pbrajesh.mobile.nostrchat.core

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagingTest {
    // Official NIP-44 test vectors (github.com/paulmillr/nip44, MIT).
    private val vectors = JSONObject(javaClass.getResource("/nip44.vectors.json")!!.readText()).getJSONObject("v2")

    @Test fun nip44ConversationKeys() {
        val list = vectors.getJSONObject("valid").getJSONArray("get_conversation_key")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val key = Nip44.conversationKey(v.getString("sec1").hexToBytes(), v.getString("pub2").hexToBytes())
            assertEquals(v.getString("conversation_key"), key.toHex(), "vector $i")
        }
    }

    @Test fun nip44PaddedLengths() {
        val list = vectors.getJSONObject("valid").getJSONArray("calc_padded_len")
        for (i in 0 until list.length()) {
            val pair = list.getJSONArray(i)
            assertEquals(pair.getInt(1), Nip44.calcPaddedLength(pair.getInt(0)))
        }
    }

    @Test fun nip44EncryptDecrypt() {
        val list = vectors.getJSONObject("valid").getJSONArray("encrypt_decrypt")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val sec1 = v.getString("sec1").hexToBytes()
            val pub2 = NostrKeys.publicKey(v.getString("sec2").hexToBytes())
            val key = Nip44.conversationKey(sec1, pub2)
            assertEquals(v.getString("conversation_key"), key.toHex())
            val payload = Nip44.encrypt(v.getString("plaintext"), key, v.getString("nonce").hexToBytes())
            assertEquals(v.getString("payload"), payload, "vector $i")
            assertEquals(v.getString("plaintext"), Nip44.decrypt(payload, key))
        }
    }

    @Test fun nip44RejectsInvalidPayloads() {
        val list = vectors.getJSONObject("invalid").getJSONArray("decrypt")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            assertFailsWith<Exception>("vector $i: ${v.optString("note")}") {
                Nip44.decrypt(v.getString("payload"), v.getString("conversation_key").hexToBytes())
            }
        }
    }

    @Test fun eventIdAndSignature() {
        val key = NostrKeys.generatePrivateKey()
        val event = Event.signed(key, 1, listOf(listOf("t", "a\"b")), "line1\nline2 \\ ünïcode", 1_700_000_000)
        assertTrue(event.verify())
        val parsed = Event.fromJson(event.toJson())
        assertEquals(event, parsed)
        assertTrue(parsed.verify())
        assertFalse(event.copy(content = "tampered").verify())
    }

    @Test fun directMessageRoundTrip() {
        val alice = NostrKeys.generatePrivateKey()
        val bob = NostrKeys.generatePrivateKey()
        val bobPub = NostrKeys.publicKey(bob)
        val now = 1_700_000_000L
        val (toBob, toAlice) = Nip17.wrapMessage(alice, bobPub, "hi bob 👋", now)

        assertEquals(Nip17.KIND_GIFT_WRAP, toBob.kind)
        assertTrue(toBob.verify())
        assertEquals(bobPub.toHex(), toBob.tagValues("p").single())
        assertTrue(toBob.createdAt <= now)

        val atBob = assertNotNull(Nip17.unwrap(toBob, bob))
        assertEquals("hi bob 👋", atBob.text)
        assertEquals(NostrKeys.publicKey(alice).toHex(), atBob.peer)
        assertFalse(atBob.fromMe)
        assertEquals(now, atBob.createdAt)

        val atAlice = assertNotNull(Nip17.unwrap(toAlice, alice))
        assertTrue(atAlice.fromMe)
        assertEquals(bobPub.toHex(), atAlice.peer)
        assertEquals(atBob.id, atAlice.id)

        // Someone else can't open it.
        assertNull(Nip17.unwrap(toBob, NostrKeys.generatePrivateKey()))
    }
}

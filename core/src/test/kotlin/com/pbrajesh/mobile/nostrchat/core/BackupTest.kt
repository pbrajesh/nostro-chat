package com.pbrajesh.mobile.nostrchat.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupTest {
    private val password = "correct horse".toCharArray()

    private fun sample(): BackupContents {
        val priv = NostrKeys.generatePrivateKey()
        val peer = NostrKeys.publicKey(NostrKeys.generatePrivateKey()).toHex()
        return BackupContents(
            privateKey = priv,
            contacts = listOf(peer to "Ana"),
            messages = listOf(
                DirectMessage("a1", peer, fromMe = true, text = "hi", createdAt = 1_700_000_000),
                DirectMessage("b2", peer, fromMe = false, text = "héllo \"quoted\"\n😀", createdAt = 1_700_000_100),
            ),
            retention = Retention.TEN_DAYS.name,
        )
    }

    // Small scrypt cost so the tests run fast; the format stores the parameters.
    private fun encrypt(c: BackupContents, pw: CharArray = password) = Backup.encrypt(c, pw, logN = 10)

    @Test fun roundTrips() {
        val original = sample()
        val restored = Backup.decrypt(encrypt(original), password)
        assertContentEquals(original.privateKey, restored.privateKey)
        assertEquals(original.contacts, restored.contacts)
        assertEquals(original.messages, restored.messages)
        assertEquals("TEN_DAYS", restored.retention)
    }

    @Test fun emptyHistoryAndNoRetention() {
        val c = BackupContents(NostrKeys.generatePrivateKey(), emptyList(), emptyList(), null)
        val restored = Backup.decrypt(encrypt(c), password)
        assertEquals(0, restored.messages.size)
        assertNull(restored.retention)
    }

    @Test fun wrongPasswordIsRejected() {
        val file = encrypt(sample())
        assertFailsWith<WrongBackupPasswordException> { Backup.decrypt(file, "wrong password".toCharArray()) }
    }

    @Test fun tamperingIsDetected() {
        val file = encrypt(sample())
        val body = file.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        assertFailsWith<WrongBackupPasswordException> { Backup.decrypt(body, password) }
        // The header (including scrypt parameters) is authenticated too.
        val header = file.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFailsWith<WrongBackupPasswordException> { Backup.decrypt(header, password) }
    }

    @Test fun rejectsOtherFiles() {
        assertFailsWith<InvalidBackupException> { Backup.decrypt("hello world, not a backup at all".toByteArray(), password) }
        val future = encrypt(sample()).also { it[4] = 99 }
        assertFailsWith<InvalidBackupException> { Backup.decrypt(future, password) }
    }

    @Test fun shortPasswordIsRefused() {
        assertFailsWith<IllegalArgumentException> { encrypt(sample(), "short".toCharArray()) }
    }

    @Test fun eachExportUsesFreshSaltAndIv() {
        val c = sample()
        assertEquals(false, encrypt(c).contentEquals(encrypt(c)))
    }
}

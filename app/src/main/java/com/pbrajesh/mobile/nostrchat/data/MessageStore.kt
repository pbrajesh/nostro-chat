package com.pbrajesh.mobile.nostrchat.data

import android.content.ContentValues
import android.content.Context
import com.pbrajesh.mobile.nostrchat.core.DirectMessage
import com.pbrajesh.mobile.nostrchat.core.toHex
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.Closeable

/**
 * Messages in a SQLCipher database (AES-256, whole file encrypted). The key comes from
 * [com.pbrajesh.mobile.nostrchat.core.LocalKeys.databaseKey], so it can only be opened after the PIN
 * unlocks the private key.
 */
class MessageStore private constructor(private val db: SQLiteDatabase) : Closeable {

    fun all(): List<DirectMessage> =
        db.rawQuery("SELECT id, peer, from_me, text, created_at FROM messages ORDER BY created_at", emptyArray<String>()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(DirectMessage(c.getString(0), c.getString(1), c.getInt(2) == 1, c.getString(3), c.getLong(4)))
                }
            }
        }

    /** Returns false if the message was already stored. */
    fun insert(message: DirectMessage): Boolean {
        val values = ContentValues().apply {
            put("id", message.id)
            put("peer", message.peer)
            put("from_me", if (message.fromMe) 1 else 0)
            put("text", message.text)
            put("created_at", message.createdAt)
        }
        return db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Inserts many messages in one transaction, e.g. when restoring a backup. */
    fun insertAll(messages: List<DirectMessage>) {
        db.beginTransaction()
        try {
            messages.forEach(::insert)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes messages created before [cutoff] (Unix seconds). */
    fun deleteOlderThan(cutoff: Long): Int = db.delete("messages", "created_at < ?", arrayOf(cutoff.toString()))

    override fun close() = db.close()

    companion object {
        private var libraryLoaded = false

        @Synchronized
        fun open(context: Context, key: ByteArray): MessageStore {
            if (!libraryLoaded) {
                System.loadLibrary("sqlcipher")
                libraryLoaded = true
            }
            val file = context.getDatabasePath("messages.db").apply { parentFile?.mkdirs() }
            // x'…' passes the 256-bit key to SQLCipher as-is, skipping its own (slow) PBKDF2.
            val rawKey = "x'${key.toHex()}'".toByteArray(Charsets.US_ASCII)
            val db = SQLiteDatabase.openOrCreateDatabase(file, rawKey, null, null, null)
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    peer TEXT NOT NULL,
                    from_me INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )"""
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS messages_created ON messages(created_at)")
            return MessageStore(db)
        }
    }
}

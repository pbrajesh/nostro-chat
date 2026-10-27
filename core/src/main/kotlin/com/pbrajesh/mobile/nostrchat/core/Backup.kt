package com.pbrajesh.mobile.nostrchat.core

import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongBackupPasswordException : Exception("Wrong backup password")
class InvalidBackupException(message: String) : Exception(message)

/** Everything needed to carry an identity and its history to another phone. */
class BackupContents(
    val privateKey: ByteArray,
    /** Public key hex to display name. */
    val contacts: List<Pair<String, String>>,
    val messages: List<DirectMessage>,
    /** A [Retention] name, or null to keep the new phone's default. */
    val retention: String?,
)

/**
 * A single backup file, encrypted with a password the user chooses (separate from the
 * PIN). Unlike the on-phone vault it isn't tied to the Android Keystore, so it can be
 * copied to a new phone and opened there.
 *
 * Layout: "NCBK" | version | log2(N) | r | p | salt(16) | iv(12) | AES-256-GCM(gzip(JSON))
 * The header is authenticated as associated data.
 */
object Backup {
    const val VERSION = 1
    const val MIN_PASSWORD_LENGTH = 8
    const val FILE_EXTENSION = "ncbak"

    // N = 2^16, r = 8 (64 MiB, as in NIP-49's suggested minimum). The password is
    // the only thing protecting a file that may sit in cloud storage or email.
    const val DEFAULT_LOG_N = 16
    const val DEFAULT_R = 8
    const val DEFAULT_P = 1

    private val MAGIC = "NCBK".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val HEADER_BYTES = 4 + 4 + SALT_BYTES + IV_BYTES
    private val random = SecureRandom()

    fun encrypt(
        contents: BackupContents,
        password: CharArray,
        logN: Int = DEFAULT_LOG_N,
        r: Int = DEFAULT_R,
        p: Int = DEFAULT_P,
    ): ByteArray {
        require(password.size >= MIN_PASSWORD_LENGTH) { "Backup password is too short" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(VERSION.toByte()).put(logN.toByte()).put(r.toByte()).put(p.toByte())
            .put(salt).put(iv)
            .array()
        val plain = gzip(toJson(contents).toByteArray(Charsets.UTF_8))
        val key = deriveKey(password, salt, logN, r, p)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            key.fill(0)
            plain.fill(0)
        }
    }

    /**
     * @throws WrongBackupPasswordException when the password doesn't match (or the file was altered).
     * @throws InvalidBackupException when the file isn't a backup from this app.
     */
    fun decrypt(file: ByteArray, password: CharArray): BackupContents {
        if (file.size <= HEADER_BYTES || !file.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw InvalidBackupException("This isn't a Nostr Chat backup file.")
        }
        val buf = ByteBuffer.wrap(file, 4, HEADER_BYTES - 4)
        if (buf.get().toInt() != VERSION) {
            throw InvalidBackupException("This backup was made by a newer version of the app.")
        }
        val logN = buf.get().toInt()
        val r = buf.get().toInt()
        val p = buf.get().toInt()
        if (logN !in 10..20 || r !in 1..16 || p !in 1..4) throw InvalidBackupException("The backup file is damaged.")
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val iv = ByteArray(IV_BYTES).also { buf.get(it) }

        val key = deriveKey(password, salt, logN, r, p)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(file, 0, HEADER_BYTES)
            cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
        } catch (e: AEADBadTagException) {
            throw WrongBackupPasswordException()
        } finally {
            key.fill(0)
        }
        val json = gunzip(plain)
        plain.fill(0)
        try {
            return fromJson(String(json, Charsets.UTF_8))
        } catch (e: Exception) {
            throw InvalidBackupException("The backup file is damaged.")
        } finally {
            json.fill(0)
        }
    }

    private fun toJson(c: BackupContents): String {
        val contacts = JSONArray()
        c.contacts.forEach { (pub, name) -> contacts.put(JSONObject().put("pubkey", pub).put("name", name)) }
        val messages = JSONArray()
        c.messages.forEach {
            messages.put(
                JSONObject()
                    .put("id", it.id).put("peer", it.peer).put("from_me", it.fromMe)
                    .put("text", it.text).put("created_at", it.createdAt)
            )
        }
        return JSONObject()
            .put("private_key", c.privateKey.toHex())
            .put("contacts", contacts)
            .put("messages", messages)
            .apply { c.retention?.let { put("retention", it) } }
            .toString()
    }

    private fun fromJson(text: String): BackupContents {
        val o = JSONObject(text)
        val priv = o.getString("private_key").hexToBytes()
        require(priv.size == 32)
        val contacts = o.getJSONArray("contacts").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).run { getString("pubkey") to getString("name") } }
        }
        val messages = o.getJSONArray("messages").let { a ->
            (0 until a.length()).map {
                a.getJSONObject(it).run {
                    DirectMessage(getString("id"), getString("peer"), getBoolean("from_me"), getString("text"), getLong("created_at"))
                }
            }
        }
        return BackupContents(priv, contacts, messages, o.optString("retention").ifEmpty { null })
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, logN: Int, r: Int, p: Int): ByteArray {
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            return SCrypt.generate(bytes, salt, 1 shl logN, r, p, 32)
        } finally {
            bytes.fill(0)
        }
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    private fun gunzip(data: ByteArray): ByteArray =
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        } catch (e: Exception) {
            throw InvalidBackupException("The backup file is damaged.")
        }
}

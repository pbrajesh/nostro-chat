package com.pbrajesh.mobile.nostrchat.core

import fr.acinq.secp256k1.Secp256k1
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** A NIP-01 event. [sig] is empty for unsigned rumors (NIP-59). */
data class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toJson(): String = buildString {
        append("{\"id\":").append(quote(id))
        append(",\"pubkey\":").append(quote(pubkey))
        append(",\"created_at\":").append(createdAt)
        append(",\"kind\":").append(kind)
        append(",\"tags\":").append(tagsJson(tags))
        append(",\"content\":").append(quote(content))
        if (sig.isNotEmpty()) append(",\"sig\":").append(quote(sig))
        append('}')
    }

    fun tagValues(name: String): List<String> = tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

    /** True when the id matches the content and the signature is valid. */
    fun verify(): Boolean = runCatching {
        id == computeId(pubkey, createdAt, kind, tags, content) &&
            Secp256k1.verifySchnorr(sig.hexToBytes(), id.hexToBytes(), pubkey.hexToBytes())
    }.getOrDefault(false)

    companion object {
        private val random = SecureRandom()

        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val serialized = "[0,${quote(pubkey)},$createdAt,$kind,${tagsJson(tags)},${quote(content)}]"
            return MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8)).toHex()
        }

        fun unsigned(publicKey: ByteArray, kind: Int, tags: List<List<String>>, content: String, createdAt: Long): Event {
            val pubkey = publicKey.toHex()
            return Event(computeId(pubkey, createdAt, kind, tags, content), pubkey, createdAt, kind, tags, content, "")
        }

        fun signed(privateKey: ByteArray, kind: Int, tags: List<List<String>>, content: String, createdAt: Long): Event {
            val rumor = unsigned(NostrKeys.publicKey(privateKey), kind, tags, content, createdAt)
            val aux = ByteArray(32).also(random::nextBytes)
            return rumor.copy(sig = Secp256k1.signSchnorr(rumor.id.hexToBytes(), privateKey, aux).toHex())
        }

        fun fromJson(json: String): Event = fromJson(JSONObject(json))

        fun fromJson(o: JSONObject): Event {
            val tagsArray = o.getJSONArray("tags")
            val tags = (0 until tagsArray.length()).map { i ->
                val tag = tagsArray.getJSONArray(i)
                (0 until tag.length()).map { tag.getString(it) }
            }
            return Event(
                id = o.getString("id"),
                pubkey = o.getString("pubkey"),
                createdAt = o.getLong("created_at"),
                kind = o.getInt("kind"),
                tags = tags,
                content = o.getString("content"),
                sig = o.optString("sig", ""),
            )
        }

        private fun tagsJson(tags: List<List<String>>) =
            tags.joinToString(",", "[", "]") { tag -> tag.joinToString(",", "[", "]") { quote(it) } }

        /** JSON string escaping exactly as NIP-01 requires for the id hash. */
        fun quote(s: String): String = buildString(s.length + 2) {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}

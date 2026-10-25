package app.mobilechat.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** How long messages are kept on the phone. */
enum class Retention(val days: Int?, val label: String) {
    ONE_DAY(1, "1 day"),
    TEN_DAYS(10, "10 days"),
    NEVER(null, "Never");

    /** Messages created before this Unix time (seconds) should be deleted, or null to keep all. */
    fun cutoff(nowSeconds: Long): Long? = days?.let { nowSeconds - it * 24L * 60 * 60 }

    companion object {
        fun fromName(name: String?): Retention = entries.firstOrNull { it.name == name } ?: NEVER
    }
}

object LocalKeys {
    /**
     * The key for the local message database. It is derived from the Nostr private key,
     * which only exists in memory after the PIN unlocks it, so the database can't be
     * opened without the PIN.
     */
    fun databaseKey(privateKey: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(privateKey, "HmacSHA256"))
            doFinal("mobilechat-message-db-v1".toByteArray())
        }
}

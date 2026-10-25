package com.pbrajesh.mobile.nostrchat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers relays between launches: the ones found through NIP-66, and each
 * contact's inbox relays (kind 10050). All of it is public information.
 */
class RelayDirectory(context: Context) {
    private val prefs = context.getSharedPreferences("relays", Context.MODE_PRIVATE)

    fun discovered(): List<String> = JSONArray(prefs.getString(KEY_DISCOVERED, "[]")).strings()

    /** Unix seconds of the last discovery, or 0 if it never ran. */
    fun discoveredAt(): Long = prefs.getLong(KEY_DISCOVERED_AT, 0)

    fun saveDiscovered(urls: List<String>, at: Long) {
        prefs.edit()
            .putString(KEY_DISCOVERED, JSONArray(urls).toString())
            .putLong(KEY_DISCOVERED_AT, at)
            .apply()
    }

    fun inboxOf(publicKeyHex: String): List<String> =
        inboxes().optJSONObject(publicKeyHex)?.optJSONArray("relays")?.strings().orEmpty()

    /** Saves a contact's inbox list unless we already have a newer one. */
    @Synchronized
    fun saveInbox(publicKeyHex: String, relays: List<String>, createdAt: Long) {
        val all = inboxes()
        val existing = all.optJSONObject(publicKeyHex)
        if (existing != null && existing.optLong("at") >= createdAt) return
        all.put(publicKeyHex, JSONObject().put("at", createdAt).put("relays", JSONArray(relays)))
        prefs.edit().putString(KEY_INBOXES, all.toString()).apply()
    }

    private fun inboxes() = JSONObject(prefs.getString(KEY_INBOXES, "{}"))

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private companion object {
        const val KEY_DISCOVERED = "discovered"
        const val KEY_DISCOVERED_AT = "discovered_at"
        const val KEY_INBOXES = "contact_inboxes"
    }
}

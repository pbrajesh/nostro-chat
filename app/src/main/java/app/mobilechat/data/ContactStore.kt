package app.mobilechat.data

import android.content.Context
import app.mobilechat.core.NostrKeys
import app.mobilechat.core.Usernames
import app.mobilechat.core.hexToBytes
import app.mobilechat.core.toHex
import org.json.JSONArray
import org.json.JSONObject

data class Contact(val publicKeyHex: String, val name: String) {
    val npub: String get() = NostrKeys.npub(publicKeyHex.hexToBytes())
}

/** Contacts are public keys only, so they are stored unencrypted. */
class ContactStore(context: Context) {
    private val prefs = context.getSharedPreferences("contacts", Context.MODE_PRIVATE)

    fun all(): List<Contact> {
        val json = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until json.length()).map {
            val o = json.getJSONObject(it)
            Contact(o.getString("pubkey"), o.getString("name"))
        }
    }

    /** Adds or renames. A blank name falls back to the key's friendly username. */
    fun add(publicKey: ByteArray, name: String): Contact {
        val contact = Contact(publicKey.toHex(), name.trim().ifEmpty { Usernames.forPublicKey(publicKey) })
        save(all().filterNot { it.publicKeyHex == contact.publicKeyHex } + contact)
        return contact
    }

    fun remove(publicKeyHex: String) = save(all().filterNot { it.publicKeyHex == publicKeyHex })

    private fun save(contacts: List<Contact>) {
        val json = JSONArray()
        contacts.forEach { json.put(JSONObject().put("pubkey", it.publicKeyHex).put("name", it.name)) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "contacts"
    }
}

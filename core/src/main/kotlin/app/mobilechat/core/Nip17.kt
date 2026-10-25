package app.mobilechat.core

import java.security.SecureRandom

/** A decrypted direct message. [peer] is the other person in the conversation. */
data class DirectMessage(
    val id: String,
    val peer: String,
    val fromMe: Boolean,
    val text: String,
    val createdAt: Long,
)

/**
 * NIP-17 private direct messages, wrapped per NIP-59:
 * rumor (kind 14, unsigned) -> seal (kind 13, signed by sender, NIP-44)
 * -> gift wrap (kind 1059, signed by a one-time key, NIP-44).
 */
object Nip17 {
    const val KIND_DM = 14
    const val KIND_SEAL = 13
    const val KIND_GIFT_WRAP = 1059
    const val KIND_DM_RELAYS = 10050

    /** Seal and wrap timestamps are pushed up to two days into the past to hide timing. */
    const val MAX_TIME_TWEAK = 2 * 24 * 60 * 60L

    private val random = SecureRandom()

    /**
     * Returns two gift wraps: one for the recipient and one for ourselves, so our own
     * sent messages come back from relays too.
     */
    fun wrapMessage(senderKey: ByteArray, recipient: ByteArray, text: String, now: Long): List<Event> {
        val rumor = Event.unsigned(
            NostrKeys.publicKey(senderKey), KIND_DM, listOf(listOf("p", recipient.toHex())), text, now,
        )
        val me = NostrKeys.publicKey(senderKey)
        return listOf(recipient, me).map { giftWrap(rumor, senderKey, it, now) }
    }

    fun giftWrap(rumor: Event, senderKey: ByteArray, receiver: ByteArray, now: Long): Event {
        val seal = Event.signed(
            senderKey, KIND_SEAL, emptyList(),
            Nip44.encrypt(rumor.toJson(), Nip44.conversationKey(senderKey, receiver)),
            randomPast(now),
        )
        val oneTimeKey = NostrKeys.generatePrivateKey()
        return Event.signed(
            oneTimeKey, KIND_GIFT_WRAP, listOf(listOf("p", receiver.toHex())),
            Nip44.encrypt(seal.toJson(), Nip44.conversationKey(oneTimeKey, receiver)),
            randomPast(now),
        )
    }

    /** Opens a gift wrap addressed to us, or returns null if it isn't a valid DM. */
    fun unwrap(wrap: Event, myKey: ByteArray): DirectMessage? = runCatching {
        if (wrap.kind != KIND_GIFT_WRAP) return null
        val seal = Event.fromJson(Nip44.decrypt(wrap.content, Nip44.conversationKey(myKey, wrap.pubkey.hexToBytes())))
        if (seal.kind != KIND_SEAL || !seal.verify()) return null
        val rumor = Event.fromJson(Nip44.decrypt(seal.content, Nip44.conversationKey(myKey, seal.pubkey.hexToBytes())))
        // The seal's signer must be the rumor's author, or anyone could forge the sender.
        if (rumor.kind != KIND_DM || rumor.pubkey != seal.pubkey) return null
        if (rumor.id != Event.computeId(rumor.pubkey, rumor.createdAt, rumor.kind, rumor.tags, rumor.content)) return null
        val me = NostrKeys.publicKey(myKey).toHex()
        val fromMe = rumor.pubkey == me
        val peer = if (fromMe) rumor.tagValues("p").firstOrNull() ?: return null else rumor.pubkey
        DirectMessage(rumor.id, peer, fromMe, rumor.content, rumor.createdAt)
    }.getOrNull()

    private fun randomPast(now: Long) = now - (random.nextInt(MAX_TIME_TWEAK.toInt()))
}

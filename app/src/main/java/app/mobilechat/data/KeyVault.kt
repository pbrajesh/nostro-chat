package app.mobilechat.data

import android.content.Context
import android.util.Base64
import app.mobilechat.core.NostrKeys
import app.mobilechat.core.PinCrypto
import app.mobilechat.core.SealedSecret
import app.mobilechat.core.Usernames
import app.mobilechat.core.WrongPinException
import app.mobilechat.core.hexToBytes
import app.mobilechat.core.toHex

/** The unlocked identity. The private key only exists in memory after a correct PIN. */
class Identity(val privateKey: ByteArray, val publicKey: ByteArray) {
    val npub: String get() = NostrKeys.npub(publicKey)
    val username: String get() = Usernames.forPublicKey(publicKey)
}

sealed interface UnlockResult {
    data class Success(val identity: Identity) : UnlockResult
    data class WrongPin(val attemptsBeforeLockout: Int) : UnlockResult
    data class LockedOut(val untilMillis: Long) : UnlockResult
}

/**
 * Stores the private key as: Keystore-AES-GCM( scrypt(PIN)-AES-GCM( privateKey ) ).
 * The public key is stored in the clear so the profile can be shown before unlock.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun hasIdentity(): Boolean = prefs.contains(KEY_BLOB)

    fun storedPublicKey(): ByteArray? = prefs.getString(KEY_PUB, null)?.hexToBytes()

    /** Slow (scrypt); call off the main thread. */
    fun create(pin: CharArray): Identity {
        val priv = NostrKeys.generatePrivateKey()
        val pub = NostrKeys.publicKey(priv)
        val sealed = PinCrypto.seal(priv, pin).toBytes()
        val wrapped = KeystoreWrapper.wrap(sealed)
        prefs.edit()
            .putString(KEY_BLOB, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(KEY_PUB, pub.toHex())
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCK_UNTIL, 0)
            .commit()
        return Identity(priv, pub)
    }

    /** Slow (scrypt); call off the main thread. */
    fun unlock(pin: CharArray, now: Long = System.currentTimeMillis()): UnlockResult {
        val lockedUntil = prefs.getLong(KEY_LOCK_UNTIL, 0)
        if (now < lockedUntil) return UnlockResult.LockedOut(lockedUntil)

        val wrapped = Base64.decode(prefs.getString(KEY_BLOB, null)!!, Base64.NO_WRAP)
        val sealed = SealedSecret.fromBytes(KeystoreWrapper.unwrap(wrapped))
        return try {
            val priv = PinCrypto.open(sealed, pin)
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0).commit()
            UnlockResult.Success(Identity(priv, NostrKeys.publicKey(priv)))
        } catch (e: WrongPinException) {
            val fails = prefs.getInt(KEY_FAILS, 0) + 1
            val editor = prefs.edit().putInt(KEY_FAILS, fails)
            val result = if (fails >= FREE_ATTEMPTS) {
                // 30 s after the 5th miss, doubling each time, capped at one hour.
                val shift = (fails - FREE_ATTEMPTS).coerceAtMost(7)
                val delay = (30_000L shl shift).coerceAtMost(3_600_000L)
                editor.putLong(KEY_LOCK_UNTIL, now + delay)
                UnlockResult.LockedOut(now + delay)
            } else {
                UnlockResult.WrongPin(FREE_ATTEMPTS - fails)
            }
            editor.commit()
            result
        }
    }

    private companion object {
        const val KEY_BLOB = "sealed_private_key"
        const val KEY_PUB = "public_key"
        const val KEY_FAILS = "failed_attempts"
        const val KEY_LOCK_UNTIL = "locked_until"
        const val FREE_ATTEMPTS = 5
    }
}

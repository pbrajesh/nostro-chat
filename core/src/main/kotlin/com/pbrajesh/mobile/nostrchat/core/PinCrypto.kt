package com.pbrajesh.mobile.nostrchat.core

import org.bouncycastle.crypto.generators.SCrypt
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongPinException : Exception("Wrong PIN")

/**
 * Encrypts a secret under a key stretched from the PIN with scrypt, then AES-256-GCM.
 *
 * Serialized layout: version | log2(N) | r | p | salt(16) | iv(12) | ciphertext+tag
 */
class SealedSecret(
    val logN: Int,
    val r: Int,
    val p: Int,
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    fun toBytes(): ByteArray =
        ByteBuffer.allocate(4 + salt.size + iv.size + ciphertext.size)
            .put(VERSION.toByte()).put(logN.toByte()).put(r.toByte()).put(p.toByte())
            .put(salt).put(iv).put(ciphertext)
            .array()

    companion object {
        const val VERSION = 1

        fun fromBytes(bytes: ByteArray): SealedSecret {
            require(bytes.size > 4 + PinCrypto.SALT_BYTES + PinCrypto.IV_BYTES) { "Truncated vault" }
            val buf = ByteBuffer.wrap(bytes)
            require(buf.get().toInt() == VERSION) { "Unknown vault version" }
            val logN = buf.get().toInt()
            val r = buf.get().toInt()
            val p = buf.get().toInt()
            val salt = ByteArray(PinCrypto.SALT_BYTES).also { buf.get(it) }
            val iv = ByteArray(PinCrypto.IV_BYTES).also { buf.get(it) }
            val ct = ByteArray(buf.remaining()).also { buf.get(it) }
            return SealedSecret(logN, r, p, salt, iv, ct)
        }
    }
}

object PinCrypto {
    const val SALT_BYTES = 16
    const val IV_BYTES = 12

    // N = 2^15, r = 8: 32 MiB and a few hundred ms on a phone per attempt.
    const val DEFAULT_LOG_N = 15
    const val DEFAULT_R = 8
    const val DEFAULT_P = 1

    private val AAD = "mobilechat-identity-v1".toByteArray()
    private val random = SecureRandom()

    fun seal(
        secret: ByteArray,
        pin: CharArray,
        logN: Int = DEFAULT_LOG_N,
        r: Int = DEFAULT_R,
        p: Int = DEFAULT_P,
    ): SealedSecret {
        require(Pin.isValid(String(pin))) { "PIN must be 4 letters or digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(pin, salt, logN, r, p)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD)
            return SealedSecret(logN, r, p, salt, iv, cipher.doFinal(secret))
        } finally {
            key.fill(0)
        }
    }

    /** @throws WrongPinException when the PIN does not match. */
    fun open(sealed: SealedSecret, pin: CharArray): ByteArray {
        val key = deriveKey(pin, sealed.salt, sealed.logN, sealed.r, sealed.p)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.iv))
            cipher.updateAAD(AAD)
            return cipher.doFinal(sealed.ciphertext)
        } catch (e: AEADBadTagException) {
            throw WrongPinException()
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(pin: CharArray, salt: ByteArray, logN: Int, r: Int, p: Int): ByteArray {
        val pinBytes = String(pin).toByteArray(Charsets.UTF_8)
        try {
            return SCrypt.generate(pinBytes, salt, 1 shl logN, r, p, 32)
        } finally {
            pinBytes.fill(0)
        }
    }
}

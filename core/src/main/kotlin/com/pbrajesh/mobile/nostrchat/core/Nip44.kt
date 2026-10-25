package com.pbrajesh.mobile.nostrchat.core

import fr.acinq.secp256k1.Secp256k1
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** NIP-44 version 2 payload encryption. */
object Nip44 {
    private const val VERSION: Byte = 2
    private val SALT = "nip44-v2".toByteArray()
    private val random = SecureRandom()

    fun conversationKey(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val point = Secp256k1.pubKeyTweakMul(Secp256k1.pubkeyParse(byteArrayOf(0x02) + publicKey), privateKey)
        val sharedX = Secp256k1.pubKeyCompress(point).copyOfRange(1, 33)
        return hmac(SALT, sharedX)
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = randomNonce()): String {
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val ciphertext = chacha(chachaKey, chachaNonce, pad(plaintext))
        val mac = hmac(hmacKey, nonce + ciphertext)
        return Base64.getEncoder().encodeToString(byteArrayOf(VERSION) + nonce + ciphertext + mac)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(payload.isNotEmpty() && payload[0] != '#') { "Unknown encryption version" }
        require(payload.length in 132..87472) { "Invalid payload size" }
        val data = Base64.getDecoder().decode(payload)
        require(data.size in 99..65603) { "Invalid data size" }
        require(data[0] == VERSION) { "Unknown encryption version ${data[0]}" }
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        require(MessageDigest.isEqual(mac, hmac(hmacKey, nonce + ciphertext))) { "Invalid MAC" }
        return unpad(chacha(chachaKey, chachaNonce, ciphertext))
    }

    fun calcPaddedLength(length: Int): Int {
        if (length <= 32) return 32
        val nextPower = Integer.highestOneBit(length - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((length - 1) / chunk + 1)
    }

    private fun randomNonce() = ByteArray(32).also(random::nextBytes)

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(conversationKey.size == 32 && nonce.size == 32) { "Bad key or nonce size" }
        val keys = hkdfExpand(conversationKey, nonce, 76)
        return Triple(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), keys.copyOfRange(44, 76))
    }

    private fun pad(plaintext: String): ByteArray {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..65535) { "Message must be 1 to 65535 bytes" }
        val out = ByteArray(2 + calcPaddedLength(bytes.size))
        out[0] = (bytes.size ushr 8).toByte()
        out[1] = bytes.size.toByte()
        bytes.copyInto(out, 2)
        return out
    }

    private fun unpad(padded: ByteArray): String {
        val length = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(length > 0 && padded.size == 2 + calcPaddedLength(length)) { "Invalid padding" }
        return String(padded, 2, length, Charsets.UTF_8)
    }

    private fun chacha(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        return ByteArray(input.size).also { engine.processBytes(input, 0, input.size, it, 0) }
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter++.toByte()))
            val n = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, n)
            offset += n
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

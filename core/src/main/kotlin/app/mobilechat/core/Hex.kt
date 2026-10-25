package app.mobilechat.core

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd hex length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        require(hi >= 0 && lo >= 0) { "Invalid hex" }
        ((hi shl 4) or lo).toByte()
    }
}

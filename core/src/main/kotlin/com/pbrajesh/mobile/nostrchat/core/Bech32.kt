package com.pbrajesh.mobile.nostrchat.core

/** BIP-173 bech32, as used by NIP-19 (npub, nsec). */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, pad = true)
        val checksum = createChecksum(hrp, values)
        return buildString {
            append(hrp).append('1')
            (values + checksum).forEach { append(CHARSET[it]) }
        }
    }

    /** Returns the human-readable part and the 8-bit payload. Throws on any malformed input. */
    fun decode(input: String): Pair<String, ByteArray> {
        require(input.length in 8..1000) { "Bad bech32 length" }
        require(input == input.lowercase() || input == input.uppercase()) { "Mixed case" }
        val str = input.lowercase()
        val sep = str.lastIndexOf('1')
        require(sep >= 1 && sep + 7 <= str.length) { "Missing separator" }
        val hrp = str.substring(0, sep)
        val values = str.substring(sep + 1).map {
            val v = CHARSET.indexOf(it)
            require(v >= 0) { "Invalid character '$it'" }
            v
        }.toIntArray()
        require(polymod(hrpExpand(hrp) + values) == 1) { "Bad checksum" }
        val payload = convertBits(values.copyOfRange(0, values.size - 6).toByteArrayOf5(), 5, 8, pad = false)
        return hrp to ByteArray(payload.size) { payload[it].toByte() }
    }

    private fun IntArray.toByteArrayOf5() = ByteArray(size) { this[it].toByte() }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        hrp.map { it.code ushr 5 }.toIntArray() + intArrayOf(0) + hrp.map { it.code and 31 }.toIntArray()

    private fun createChecksum(hrp: String, values: IntArray): IntArray {
        val mod = polymod(hrpExpand(hrp) + values + IntArray(6)) xor 1
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (b in data) {
            val value = b.toInt() and 0xff
            require(value ushr from == 0) { "Invalid data value" }
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out += (acc ushr bits) and maxv
            }
        }
        if (pad) {
            if (bits > 0) out += (acc shl (to - bits)) and maxv
        } else {
            require(bits < from && ((acc shl (to - bits)) and maxv) == 0) { "Invalid padding" }
        }
        return out.toIntArray()
    }
}

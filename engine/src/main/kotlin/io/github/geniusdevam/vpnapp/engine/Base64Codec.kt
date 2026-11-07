package io.github.geniusdevam.vpnapp.engine

/**
 * RFC 2045 ("MIME") base64 decoding that behaves exactly like `java.util.Base64.getMimeDecoder().decode(String)`, which only exists
 * on Android 8.0+. The engine decodes untrusted directory data, so this must not be more permissive than the JDK: it accepts and
 * rejects the same inputs and returns the same bytes (proven by an equivalence fuzz test against the JDK in Base64CodecTest).
 *
 * Rules reproduced from the JDK: characters outside the base64 alphabet are skipped; `=` ends the data and must form a valid
 * ending unit (`xx==` or `xxx=`, where the second `=` of `xx==` must follow immediately); a dangling single sextet is an error; after
 * the padding only non-alphabet characters may follow. Characters above U+00FF count as non-alphabet (the JDK maps them to '?').
 */
object Base64Codec {
    private val TABLE = IntArray(256) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
        t['='.code] = -2
    }

    private fun value(c: Char): Int = if (c.code < 256) TABLE[c.code] else -1

    fun decodeMime(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4 + 3)
        var bits = 0
        var shiftto = 18
        var sp = 0
        val sl = s.length
        while (sp < sl) {
            val b = value(s[sp++])
            if (b < 0) {
                if (b == -2) {
                    if (shiftto == 6 && (sp == sl || s[sp++] != '=') || shiftto == 18) {
                        throw IllegalArgumentException("Input byte array has wrong 4-byte ending unit")
                    }
                    break
                }
                continue // MIME: skip anything outside the alphabet
            }
            bits = bits or (b shl shiftto)
            shiftto -= 6
            if (shiftto < 0) {
                out.write(bits shr 16); out.write(bits shr 8); out.write(bits)
                shiftto = 18; bits = 0
            }
        }
        when (shiftto) {
            6 -> out.write(bits shr 16)
            0 -> { out.write(bits shr 16); out.write(bits shr 8) }
            12 -> throw IllegalArgumentException("Last unit does not have enough valid bits")
        }
        while (sp < sl) {
            if (value(s[sp++]) < 0) continue
            throw IllegalArgumentException("Input byte array has incorrect ending byte at $sp")
        }
        return out.toByteArray()
    }
}

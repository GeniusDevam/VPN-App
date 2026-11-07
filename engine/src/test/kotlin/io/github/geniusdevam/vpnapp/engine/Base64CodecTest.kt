package io.github.geniusdevam.vpnapp.engine

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Base64Codec replaces java.util.Base64's MIME decoder (Android 8.0+ only). It decodes untrusted directory data, so it must accept
 * and reject exactly what the JDK does and return identical bytes. Proven here against the JDK itself, on structured and random input.
 */
class Base64CodecTest {
    private val jdk = Base64.getMimeDecoder()

    /** Both decoders must agree: same bytes, or both reject. */
    private fun same(s: String) {
        val a = runCatching { jdk.decode(s) }
        val b = runCatching { Base64Codec.decodeMime(s) }
        assertEquals(a.isSuccess, b.isSuccess, "accept/reject differs for ${s.take(60).replace("\n", "\\n")}: jdk=${a.exceptionOrNull()} ours=${b.exceptionOrNull()}")
        if (a.isSuccess) assertContentEquals(a.getOrThrow(), b.getOrThrow(), "bytes differ for ${s.take(60)}")
    }

    @Test fun roundTripsEveryLengthAndLineWrapping() {
        val r = Random(1)
        for (len in 0..300) {
            val bytes = ByteArray(len).also { r.nextBytes(it) }
            val plain = Base64.getEncoder().encodeToString(bytes)
            val mime = Base64.getMimeEncoder().encodeToString(bytes)
            assertContentEquals(bytes, Base64Codec.decodeMime(plain))
            assertContentEquals(bytes, Base64Codec.decodeMime(mime))
            same(plain); same(mime); same(plain.trimEnd('=')); same("$mime\r\n"); same(" \t$plain\n")
        }
    }

    @Test fun malformedEndingsAreRejectedExactlyLikeTheJdk() {
        for (s in listOf("=", "A", "A=", "A==", "AB=", "AB=C", "AB= =", "AB=\n=", "ABC==", "ABCD=", "ABCD====", "AB==CD", "ABC=D",
            "AB==\n", "AB==??", "AB==x", "====", "AB\n==", "A\nB\nC\nD", "Zg", "Zg=", "Zg==", "Zm8", "Zm8=", "Zm9v", "Zm9vé", "Zm9€v")) {
            same(s)
        }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decodeMime("A") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decodeMime("AB=C") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decodeMime("AB==x") }
    }

    @Test fun randomGarbageAgreesWithTheJdk() {
        val r = Random(42)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\r\n \t-_.,*ÿĀé"
        repeat(20_000) {
            val n = r.nextInt(0, 40)
            same(String(CharArray(n) { alphabet[r.nextInt(alphabet.length)] }))
        }
    }

    @Test fun mutatedValidInputAgreesWithTheJdk() {
        val r = Random(7)
        repeat(5_000) {
            val valid = Base64.getMimeEncoder().encodeToString(ByteArray(r.nextInt(1, 120)).also { b -> r.nextBytes(b) })
            val sb = StringBuilder(valid)
            repeat(r.nextInt(1, 4)) {
                val i = r.nextInt(sb.length + 1)
                when (r.nextInt(4)) {
                    0 -> sb.insert(i, "=")
                    1 -> if (sb.isNotEmpty()) sb.deleteCharAt(i.coerceAtMost(sb.length - 1))
                    2 -> sb.insert(i, "\n")
                    else -> sb.insert(i, "#")
                }
            }
            same(sb.toString())
        }
    }
}

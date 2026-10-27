package com.example.merchandisecontrolsplitview.data

import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the existing internal chain seam and production private sha256; no new API. */
class ShopSyncCheckpointDigestEncodingTest {
    @Test
    fun sharedGoldenVectorsAndEmptyChainRemainByteIdentical() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            shopSyncCheckpointChainDigest(emptyList())
        )
        assertEquals(
            "f78359ac705f7a5d38c01325096b77a570fb8a3fda3ba96cb8b105bf0a860a24",
            shopSyncCheckpointChainDigest(listOf("abc", "é"))
        )
        assertEquals(
            "d35cc83a5331da3caac79921218db4c55d400a32b0a03846002fff8dfadaa08e",
            shopSyncCheckpointChainDigest(listOf("abc", "é", "xyz"))
        )
    }

    @Test
    fun utf8AndDigestBlockBoundariesPreserveTheCompleteChain() {
        val emptyState = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        // SHA receives chain framing too: 64 hex + US + decimal byte count + ':' + payload.
        for ((payloadLength, preimageLength) in listOf(51 to 119, 52 to 120, 59 to 127, 60 to 128)) {
            val line = "a".repeat(payloadLength)
            val framed = "$emptyState\u001f${line.toByteArray(Charsets.UTF_8).size}:$line"
            assertEquals(preimageLength, framed.toByteArray(Charsets.UTF_8).size)
            assertEquals(referenceChain(listOf(line)), shopSyncCheckpointChainDigest(listOf(line)))
        }
        val inputs = listOf(
            "", "\u0000", "\u001f", ":", "é", "e\u0301", "商品", "\uD83D\uDE00",
            "\uD800", "\uDC00", "\uD800x\uDC00",
            (0..255).map(Int::toChar).joinToString("")
        ) + listOf(1, 55, 56, 63, 64, 65, 127, 128, 129, 511, 512, 1024)
            .flatMap { size -> listOf("a".repeat(size), "é".repeat(size)) }
        inputs.forEach { value ->
            val lines = listOf(value, "", value.reversed(), "tail\u001f:")
            assertEquals(referenceChain(lines), shopSyncCheckpointChainDigest(lines))
        }
    }

    @Test
    fun seededPropertiesCoverEveryDigestByteWithoutChangingOrderingOrEncoding() {
        val random = Random(144145L)
        val observedBytes = BooleanArray(256)
        repeat(192) { case ->
            val lines = List(1 + random.nextInt(16)) {
                CharArray(random.nextInt(1025)) { random.nextInt(0x10000).toChar() }.concatToString()
            }
            val expected = referenceChain(lines, observedBytes)
            val actual = shopSyncCheckpointChainDigest(lines)
            assertEquals("case=$case", expected, actual)
            assertEquals("case=$case", 64, actual.length)
            assertTrue("case=$case", actual.all { it in '0'..'9' || it in 'a'..'f' })
        }
        assertEquals("Property corpus must cover each signed/unsigned digest byte", 256,
            observedBytes.count { it })
    }

    private fun referenceChain(lines: List<String>, observedBytes: BooleanArray? = null): String {
        fun hash(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            bytes.forEach { observedBytes?.set(it.toInt() and 0xff, true) }
            // Independent formatting oracle; production uses neither Formatter nor this helper.
            return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        }
        var state = hash("")
        for (line in lines) {
            val byteLength = line.toByteArray(Charsets.UTF_8).size
            state = hash("$state\u001f$byteLength:$line")
        }
        return state
    }
}

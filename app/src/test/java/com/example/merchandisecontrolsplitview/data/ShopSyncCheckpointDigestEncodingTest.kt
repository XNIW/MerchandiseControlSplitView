package com.example.merchandisecontrolsplitview.data

import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

    @Test
    fun privateAsciiHeaderPreservesDecimalTransitionsAndMaximumIntWithoutPayloadAllocation() {
        val type = Class.forName("com.example.merchandisecontrolsplitview.data.CanonicalLineDigest")
        val instance = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val header = type.getDeclaredField("header").apply { isAccessible = true }.get(instance) as ByteArray
        val writeHeader = type.getDeclaredMethod("writeHeader", Int::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
        val emptyState = referenceChain(emptyList())
        for (byteCount in listOf(0, 1, 9, 10, 99, 100, 999, 1000, Int.MAX_VALUE, 1, 0)) {
            val used = writeHeader.invoke(instance, byteCount) as Int
            val expected = "$emptyState\u001f$byteCount:".toByteArray(Charsets.UTF_8)
            assertEquals(expected.size, used)
            assertTrue(used <= 76)
            assertArrayEquals(expected, header.copyOfRange(0, used))
        }
    }

    @Test
    fun privateHeadersRemainIndependentAcrossInterleavingFinishAndShorterFrames() {
        val type = Class.forName("com.example.merchandisecontrolsplitview.data.CanonicalLineDigest")
        val constructor = type.getDeclaredConstructor().apply { isAccessible = true }
        val left = constructor.newInstance()
        val right = constructor.newInstance()
        val header = type.getDeclaredField("header").apply { isAccessible = true }
        val add = type.getDeclaredMethod("add", String::class.java).apply { isAccessible = true }
        val finish = type.getDeclaredMethod("finish").apply { isAccessible = true }
        assertNotSame(header.get(left), header.get(right))
        val leftLines = mutableListOf<String>()
        val rightLines = mutableListOf<String>()
        val inputs = listOf("a".repeat(1000), "x", "é", "", "商品", "\uD800", "\uDC00", "\uD83D\uDE00", "\u001f:")
        for ((index, line) in inputs.withIndex()) {
            leftLines += line
            add.invoke(left, line)
            val retained = finish.invoke(left) as String
            assertEquals(referenceChain(leftLines), retained)
            assertSame(retained, finish.invoke(left))
            val leftFrame = (header.get(left) as ByteArray).clone()
            val other = inputs[inputs.lastIndex - index]
            rightLines += other
            add.invoke(right, other)
            assertEquals(referenceChain(rightLines), finish.invoke(right))
            assertArrayEquals(leftFrame, header.get(left) as ByteArray)
            assertEquals(referenceChain(leftLines), retained)
        }
    }

    @Test
    fun longMultilineChainsMatchTheOriginalFreshEngineReference() {
        val inputs = listOf("", "é".repeat(50_000), "商品\n".repeat(25_000), "x", "\u001f:")
        for (length in 0..inputs.size) {
            val lines = inputs.take(length)
            assertEquals(referenceChain(lines), shopSyncCheckpointChainDigest(lines))
        }
        val reversed = inputs.reversed()
        assertEquals(referenceChain(reversed), shopSyncCheckpointChainDigest(reversed))
        assertTrue(shopSyncCheckpointChainDigest(inputs) != shopSyncCheckpointChainDigest(reversed))
    }

    @Test
    fun concurrentlyOwnedChainsRemainIndependentAndMatchTheOriginalReference() {
        val inputs = List(12) { index ->
            listOf("chain-$index", "é".repeat(1_000 + index), "", "商品\u001f:\n$index", "x")
        }
        val expected = inputs.map { referenceChain(it) }
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val futures = inputs.map { lines ->
                workers.submit(java.util.concurrent.Callable {
                    start.await()
                    List(4) { shopSyncCheckpointChainDigest(lines) }
                })
            }
            start.countDown()
            futures.forEachIndexed { index, future ->
                assertEquals(List(4) { expected[index] },
                    future.get(30, java.util.concurrent.TimeUnit.SECONDS))
            }
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
        }
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

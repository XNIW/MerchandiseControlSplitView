package com.example.merchandisecontrolsplitview.data

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalAcknowledgedPriceFingerprintTest {
    @Test
    fun byteEncodingMatchesExistingFormatterForEverySignedByte() {
        val bytes = ByteArray(256) { it.toByte() }
        val expected = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        assertEquals(512, expected.length)
        assertEquals(expected, numericHex(bytes))
        assertEquals("00017f80feff", numericHex(byteArrayOf(0, 1, 127, -128, -2, -1)))
    }

    @Test
    fun sha256KnownVectorsKeepLowercasePaddedEncoding() {
        val vectors = mapOf(
            "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "The quick brown fox jumps over the lazy dog" to
                "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"
        )
        for ((input, expected) in vectors) {
            assertEquals(expected, numericHex(MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())))
        }
    }

    @Test
    fun priceBodyGoldenVectorsPreserveNullUnicodeAndNegativeZero() {
        val original = price()
        assertEquals("local-price-body-v1:9b93fda428be5b6ee63af926af4b78d29f979e80dac5b23d056eaa5b36f39834",
            localAcknowledgedPriceFingerprint(original))
        assertEquals("local-price-body-v1:fb0133eb81eaaa7bb28062f5b51190fbf2d3689348ef159a87e1dfb8fbbe8d8b",
            localAcknowledgedPriceFingerprint(original.copy(type = "purchase", price = -0.0, source = "", note = "")))
        assertEquals("local-price-body-v1:e239563e62a2a71ed42cc32c3cfe685e10a9bbc1ee334dd894a55534eff2509b",
            localAcknowledgedPriceFingerprint(original.copy(price = 0.01, source = "供應商", note = "é e\u0301 🍎\n\"\\\u0000")))
        val variants = listOf(
            original.copy(id = "00000000-0000-4000-8000-000000000003"),
            original.copy(productId = "00000000-0000-4000-8000-000000000004"),
            original.copy(type = "purchase"), original.copy(price = 0.0),
            original.copy(effectiveAt = "2026-10-17T00:00:00Z"),
            original.copy(source = ""), original.copy(note = ""),
            original.copy(createdAt = "2026-10-17T00:00:00Z")
        )
        for (row in variants) {
            assertNotEquals(localAcknowledgedPriceFingerprint(original), localAcknowledgedPriceFingerprint(row))
            assertEquals(legacyFingerprint(row), localAcknowledgedPriceFingerprint(row))
        }
        assertNotEquals(localAcknowledgedPriceFingerprint(original.copy(price = 0.0)),
            localAcknowledgedPriceFingerprint(original.copy(price = -0.0)))
        // These fields were already excluded from this body hash; scope is checked separately.
        assertEquals(localAcknowledgedPriceFingerprint(original), localAcknowledgedPriceFingerprint(
            original.copy(ownerUserId = "another-owner", shopId = "another-shop",
                priceCanonical = "12.500000", updatedAt = "2026-10-17T00:00:00Z")))
    }

    @Test
    fun deterministicPriceBodiesMatchLegacyEncodingAndCoverEveryDigestByte() {
        val observedDigestBytes = mutableSetOf<Int>()
        val text = listOf<String?>(null, "", "plain", "供應商", "é e\u0301 🍎", "quote\" slash\\ newline\n zero\u0000")
        repeat(512) { index ->
            val row = price().copy(
                id = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                price = if (index % 17 == 0) -0.0 else (index - 256) / 8.0,
                source = text[index % text.size], note = text[(index / text.size) % text.size]
            )
            val legacy = legacyFingerprint(row)
            assertEquals("row $index", legacy, localAcknowledgedPriceFingerprint(row))
            legacy.removePrefix("local-price-body-v1:").chunked(2).forEach {
                observedDigestBytes += it.toInt(16)
            }
        }
        assertEquals((0..255).toSet(), observedDigestBytes)
    }

    private fun numericHex(bytes: ByteArray) = bytes.joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun legacyFingerprint(row: InventoryProductPriceRow): String {
        val body = JsonArray(listOf(JsonPrimitive(row.id), JsonPrimitive(row.productId), JsonPrimitive(row.type),
            JsonPrimitive(row.price), JsonPrimitive(row.effectiveAt), row.source?.let(::JsonPrimitive) ?: JsonNull,
            row.note?.let(::JsonPrimitive) ?: JsonNull, JsonPrimitive(row.createdAt)))
        return "local-price-body-v1:" + MessageDigest.getInstance("SHA-256")
            .digest(body.toString().encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun price() = InventoryProductPriceRow(
        id = "00000000-0000-4000-8000-000000000001", ownerUserId = "fixture-owner",
        productId = "00000000-0000-4000-8000-000000000002", type = "retail", price = 12.5,
        effectiveAt = "2026-10-16T00:00:00Z", createdAt = "2026-10-16T00:00:00Z"
    )
}

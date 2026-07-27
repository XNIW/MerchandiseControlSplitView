package com.example.merchandisecontrolsplitview.util

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTextPolicyTest {
    private val fixtureBytes: ByteArray
        get() = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(FIXTURE_PATH)
        ) { "Missing $FIXTURE_PATH" }.use { it.readBytes() }

    private val fixture: JsonObject
        get() = Json.parseToJsonElement(fixtureBytes.decodeToString()).jsonObject

    @Test
    fun `golden fixture is byte identical by pinned sha256`() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fixtureBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

        assertEquals(EXPECTED_SHA256, digest)
        assertEquals(CatalogTextPolicy.VERSION, fixture.getValue("policyVersion").jsonPrimitive.content)
        assertEquals("utf16_code_units_after_nfc", fixture.getValue("lengthUnit").jsonPrimitive.content)
    }

    @Test
    fun `limits match the common contract`() {
        val limits = fixture.getValue("limits").jsonObject

        assertEquals(CatalogTextPolicy.Limits.PRODUCT_NAME, limits.int("productName"))
        assertEquals(CatalogTextPolicy.Limits.SECOND_PRODUCT_NAME, limits.int("secondProductName"))
        assertEquals(CatalogTextPolicy.Limits.SUPPLIER_NAME, limits.int("supplierName"))
        assertEquals(CatalogTextPolicy.Limits.CATEGORY_NAME, limits.int("categoryName"))
        assertEquals(CatalogTextPolicy.Limits.BARCODE, limits.int("barcode"))
        assertEquals(CatalogTextPolicy.Limits.ITEM_NUMBER, limits.int("itemNumber"))
    }

    @Test
    fun `all display vectors match and accepted values are idempotent`() {
        fixture.getValue("displayCases").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val input = vector.input()
            val outcome = CatalogTextPolicy.display(
                raw = input,
                required = vector.getValue("required").jsonPrimitive.boolean,
                maxLength = vector.getValue("maxLength").jsonPrimitive.int
            )

            assertVector(vector, outcome)
            CatalogTextPolicy.valueOrNull(outcome)?.let { canonical ->
                assertEquals(
                    vector.id(),
                    CatalogTextPolicy.Outcome.Unchanged(canonical),
                    CatalogTextPolicy.display(
                        raw = canonical,
                        required = vector.getValue("required").jsonPrimitive.boolean,
                        maxLength = vector.getValue("maxLength").jsonPrimitive.int
                    )
                )
            }
        }
    }

    @Test
    fun `all strict vectors match and accepted values are idempotent`() {
        fixture.getValue("strictCases").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val outcome = CatalogTextPolicy.strict(
                raw = vector.input(),
                required = vector.getValue("required").jsonPrimitive.boolean,
                maxLength = vector.getValue("maxLength").jsonPrimitive.int
            )

            assertVector(vector, outcome)
            CatalogTextPolicy.valueOrNull(outcome)?.let { canonical ->
                assertEquals(
                    vector.id(),
                    CatalogTextPolicy.Outcome.Unchanged(canonical),
                    CatalogTextPolicy.strict(
                        raw = canonical,
                        required = vector.getValue("required").jsonPrimitive.boolean,
                        maxLength = vector.getValue("maxLength").jsonPrimitive.int
                    )
                )
            }
        }
    }

    @Test
    fun `encoding vectors reject malformed utf16 and utf8`() {
        fixture.getValue("encodingCases").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val required = vector.getValue("required").jsonPrimitive.boolean
            val maxLength = vector.getValue("maxLength").jsonPrimitive.int
            val outcome = when (vector.getValue("inputEncoding").jsonPrimitive.content) {
                "utf16_code_units" -> {
                    val raw = vector.getValue("inputCodeUnitsHex").jsonArray
                        .map { it.jsonPrimitive.content.toInt(16).toChar() }
                        .joinToString(separator = "")
                    if (vector.getValue("class").jsonPrimitive.content == "display") {
                        CatalogTextPolicy.display(raw, required, maxLength)
                    } else {
                        CatalogTextPolicy.strict(raw, required, maxLength)
                    }
                }
                "utf8_bytes" -> {
                    val bytes = vector.getValue("inputBytesHex").jsonPrimitive.content
                        .chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                    if (vector.getValue("class").jsonPrimitive.content == "display") {
                        CatalogTextPolicy.displayUtf8(bytes, required, maxLength)
                    } else {
                        CatalogTextPolicy.strictUtf8(bytes, required, maxLength)
                    }
                }
                else -> error("Unknown encoding vector ${vector.id()}")
            }

            assertVector(vector, outcome)
        }
    }

    @Test
    fun `strict collision vector is rejected without merging identities`() {
        val vector = fixture.getValue("collisionCases").jsonArray.single().jsonObject
        val collision = CatalogTextPolicy.validateDistinctStrictIdentities(
            rawValues = vector.getValue("inputs").jsonArray.map { it.jsonPrimitive.content },
            required = true,
            maxLength = CatalogTextPolicy.Limits.ITEM_NUMBER
        )

        assertEquals(vector.id(), "strict_collision_after_trim")
        assertEquals(
            CatalogTextPolicy.RejectionReason.IDENTITY_COLLISION_AFTER_TRIM,
            collision?.reason
        )
    }

    @Test
    fun `display preserves valid zwj while strict identity rejects it`() {
        val emoji = "Equipo 👩‍💻"

        assertEquals(
            CatalogTextPolicy.Outcome.Unchanged(emoji),
            CatalogTextPolicy.display(emoji, required = true, maxLength = 240)
        )
        assertEquals(
            CatalogTextPolicy.RejectionReason.PROHIBITED_ZERO_WIDTH,
            (CatalogTextPolicy.strict(emoji, required = true, maxLength = 240)
                as CatalogTextPolicy.Outcome.Rejected).reason
        )
    }

    private fun assertVector(
        vector: JsonObject,
        actual: CatalogTextPolicy.Outcome
    ) {
        when (vector.getValue("expectedStatus").jsonPrimitive.content) {
            "unchanged" -> {
                assertTrue(vector.id(), actual is CatalogTextPolicy.Outcome.Unchanged)
                assertEquals(
                    vector.getValue("expectedValue").jsonPrimitive.content,
                    (actual as CatalogTextPolicy.Outcome.Unchanged).value
                )
            }
            "normalized" -> {
                assertTrue(vector.id(), actual is CatalogTextPolicy.Outcome.Normalized)
                actual as CatalogTextPolicy.Outcome.Normalized
                assertEquals(vector.getValue("expectedValue").jsonPrimitive.content, actual.value)
                val expectedChanges = vector["expectedChanges"]
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?.toSet()
                    .orEmpty()
                assertEquals(
                    vector.id(),
                    expectedChanges,
                    actual.changes.map { it.contractValue }.toSet()
                )
            }
            "rejected" -> {
                assertTrue(vector.id(), actual is CatalogTextPolicy.Outcome.Rejected)
                assertEquals(
                    vector.getValue("expectedReason").jsonPrimitive.content,
                    (actual as CatalogTextPolicy.Outcome.Rejected).reason.contractValue
                )
                assertNull(CatalogTextPolicy.valueOrNull(actual))
            }
            else -> error("Unknown expected status for ${vector.id()}")
        }
    }

    private fun JsonObject.input(): String {
        get("input")?.let { return it.jsonPrimitive.content }
        val generator = getValue("inputGenerator").jsonObject
        require(generator.getValue("kind").jsonPrimitive.content == "repeat")
        return generator.getValue("value").jsonPrimitive.content
            .repeat(generator.getValue("count").jsonPrimitive.int)
    }

    private fun JsonObject.id(): String = getValue("id").jsonPrimitive.content

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private companion object {
        const val FIXTURE_PATH = "fixtures/catalog-text-policy-v1.json"
        const val EXPECTED_SHA256 =
            "139d63eedea47b54bb63a9289bef5fc6f7372668f209aac7753b586da7ccd9f8"
    }
}

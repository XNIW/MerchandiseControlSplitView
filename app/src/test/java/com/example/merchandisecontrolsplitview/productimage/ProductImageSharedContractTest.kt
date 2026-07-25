package com.example.merchandisecontrolsplitview.productimage

import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductImageSharedContractTest {
    private val processor = ProductImageProcessor()

    @Test
    fun `image references accept canonical PostgreSQL UUIDv7 and nil UUIDs`() {
        assertTrue(PRODUCT_IMAGE_UUID_PATTERN.matches("018f0ad4-77f2-7c9d-a8be-4f6b9d234567"))
        assertTrue(PRODUCT_IMAGE_UUID_PATTERN.matches("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `runtime consumes shared compression and synthetic vectors`() {
        val contract = json("product-image-v1.json").jsonObject
        val compression = contract.getValue("compression").jsonObject
        val factors = compression.getValue("sideFactors").jsonArray.map { it.jsonPrimitive.double }
        assertEquals(PRODUCT_IMAGE_OUTPUT_SIDE_FACTORS, factors)
        assertEquals(
            PRODUCT_IMAGE_MAIN_QUALITIES.toList(),
            compression.getValue("main").jsonObject.getValue("qualities").jsonArray
                .map { (it.jsonPrimitive.double * 100).toInt() }
        )
        assertEquals(
            PRODUCT_IMAGE_THUMB_QUALITIES.toList(),
            compression.getValue("thumb").jsonObject.getValue("qualities").jsonArray
                .map { (it.jsonPrimitive.double * 100).toInt() }
        )

        val vectors = json("fixtures/product-image-synthetic-v1.json").jsonObject
        for (vector in vectors.getValue("sideScheduleVectors").jsonArray.map { it.jsonObject }) {
            val expected = vector.getValue("expected").jsonArray.map { it.jsonPrimitive.int }
            assertEquals(
                vector.getValue("id").jsonPrimitive.content,
                expected,
                processor.outputSideSchedule(
                    vector.getValue("sourceLongestSide").jsonPrimitive.int,
                    vector.getValue("initialMaximum").jsonPrimitive.int,
                    vector.getValue("minimum").jsonPrimitive.int
                )
            )
        }
        val images = vectors.getValue("syntheticImages").jsonArray.map { it.jsonObject }
        val camera48MP = images.single { it.getValue("id").jsonPrimitive.content == "camera-48mp" }
        assertEquals(8_000, camera48MP.getValue("width").jsonPrimitive.int)
        assertEquals(6_000, camera48MP.getValue("height").jsonPrimitive.int)
    }

    @Test
    fun `shared contract hash manifest matches the exact JSON bytes`() {
        val root = contractRoot()
        val expected = root.resolve("product-image-v1.sha256")
            .readLines()
            .first()
            .substringBefore(' ')
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(root.resolve("product-image-v1.json").readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

        assertEquals(expected, actual)
    }

    @Test
    fun `common fixtures freeze API domain and JPEG policy`() {
        val valid = json("fixtures/product-image-v1-valid.json").jsonObject
        val invalid = json("fixtures/product-image-v1-invalid.json").jsonObject
        assertEquals("image/jpeg", valid.getValue("intent").jsonObject
            .getValue("main").jsonObject.getValue("mimeType").jsonPrimitive.content)
        assertTrue(invalid.getValue("jpegCases").jsonArray.any {
            it.jsonObject.getValue("id").jsonPrimitive.content == "app13-photoshop"
        })
        val boundary = json("product-image-v1.json").jsonObject
            .getValue("domainBoundary").jsonObject
        val forbidden = boundary.getValue("forbidden").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(forbidden.containsAll(listOf("blob", "signedURL", "storagePath")))
        assertFalse(boundary.getValue("allowed").jsonArray.any {
            it.jsonPrimitive.content.contains("url", ignoreCase = true)
        })
        assertEquals(16 * 1_024, PRODUCT_IMAGE_API_BODY_MAX_BYTES)
    }

    @Test
    fun `runtime budgets match the shared contract`() {
        val contract = json("product-image-v1.json").jsonObject
        val limits = contract.getValue("limits").jsonObject
        assertEquals(
            PRODUCT_IMAGE_INPUT_MAX_BYTES,
            limits.getValue("inputMaximumBytes").jsonPrimitive.int
        )
        assertEquals(
            PRODUCT_IMAGE_INPUT_MAX_PIXELS,
            limits.getValue("inputMaximumPixels").jsonPrimitive.long
        )
        assertEquals(
            PRODUCT_IMAGE_API_BODY_MAX_BYTES,
            limits.getValue("jsonBodyMaximumBytes").jsonPrimitive.int
        )
        assertEquals(
            PRODUCT_IMAGE_READ_URLS_MAX_REFS,
            limits.getValue("readBatchMaximum").jsonPrimitive.int
        )

        val readUrls = contract.getValue("api").jsonObject
            .getValue("readUrls").jsonObject
        val readUrlItemRequired = readUrls
            .getValue("itemRequired").jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        assertFalse(readUrlItemRequired.contains("metadata"))
        val readUrlRequiredByStatus = readUrls
            .getValue("itemRequiredByStatus").jsonObject
        assertEquals(
            emptyList<String>(),
            readUrlRequiredByStatus.getValue("not_found").jsonArray
                .map { it.jsonPrimitive.content }
        )
        assertTrue(
            readUrlRequiredByStatus.getValue("ready").jsonArray
                .map { it.jsonPrimitive.content }
                .containsAll(listOf("expiresAt", "metadata", "signedUrl"))
        )
        val readUrlMetadataRequired = readUrls
            .getValue("itemMetadataRequired").jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        assertTrue(
            readUrlMetadataRequired.containsAll(
                setOf("bytes", "height", "mimeType", "sha256", "width")
            )
        )

        val compression = contract.getValue("compression").jsonObject
        val main = compression.getValue("main").jsonObject
        assertEquals(
            PRODUCT_IMAGE_MAIN_MAX_SIDE,
            main.getValue("maximumSide").jsonPrimitive.int
        )
        assertEquals(
            PRODUCT_IMAGE_MAIN_TARGET_BYTES,
            main.getValue("targetBytes").jsonPrimitive.int
        )
        assertEquals(
            PRODUCT_IMAGE_MAIN_MAX_BYTES,
            main.getValue("hardMaximumBytes").jsonPrimitive.int
        )
        val thumb = compression.getValue("thumb").jsonObject
        assertEquals(
            PRODUCT_IMAGE_THUMB_MAX_SIDE,
            thumb.getValue("maximumSide").jsonPrimitive.int
        )
        assertEquals(
            PRODUCT_IMAGE_THUMB_MAX_BYTES,
            thumb.getValue("targetBytes").jsonPrimitive.int
        )
        assertEquals(
            PRODUCT_IMAGE_THUMB_MAX_BYTES,
            thumb.getValue("hardMaximumBytes").jsonPrimitive.int
        )

        val androidCache = contract.getValue("cache").jsonObject
            .getValue("platformBudgets").jsonObject
            .getValue("android").jsonObject
        assertEquals(
            PRODUCT_IMAGE_MEMORY_CACHE_MAX_BYTES,
            androidCache.getValue("memoryBytes").jsonPrimitive.long
        )
        assertEquals(
            PRODUCT_IMAGE_DISK_CACHE_MAX_BYTES,
            androidCache.getValue("diskBytes").jsonPrimitive.long
        )
    }

    @Test
    fun `runtime error codes are exactly the shared contract allowlist`() {
        val contractErrors = json("product-image-v1.json").jsonObject
            .getValue("errors").jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

        assertEquals(contractErrors, PRODUCT_IMAGE_ERROR_CODES)

        val sourceRoot = requireNotNull(contractRoot().parentFile).resolve(
            "app/src/main/java/com/example/merchandisecontrolsplitview/productimage"
        )
        assertTrue(sourceRoot.isDirectory)
        val runtimeLiterals = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("\"(image_[a-z0-9_]+)\"")
                    .findAll(file.readText())
                    .map { match -> match.groupValues[1] }
            }
            .toSet()
        assertEquals(emptySet<String>(), runtimeLiterals - contractErrors)
    }

    @Test
    fun `runtime rejects error codes outside the shared contract`() {
        val exceptionFailure = runCatching {
            ProductImageException("image_scope_changed")
        }.exceptionOrNull()
        assertTrue(exceptionFailure is IllegalArgumentException)

        val batchFailure = runCatching {
            ProductImageBatchItem(
                request = ProductImageLoadRequest(1L, ProductImageVariant.THUMB, null),
                errorCode = "image_metadata_strip_failed"
            )
        }.exceptionOrNull()
        assertTrue(batchFailure is IllegalArgumentException)
    }

    @Test
    fun `streamed response body is bounded before full materialization`() = runTest {
        val maximum = 64
        val exact = ByteArray(maximum) { it.toByte() }

        assertEquals(
            exact.toList(),
            readProductImageBodyBounded(ByteReadChannel(exact), maximum).toList()
        )

        val overflow = runCatching {
            readProductImageBodyBounded(ByteReadChannel(ByteArray(maximum + 1)), maximum)
        }.exceptionOrNull()
        assertTrue(overflow is ProductImageException)
        assertEquals("image_download_invalid", (overflow as ProductImageException).code)
    }

    private fun json(relativePath: String) = Json.parseToJsonElement(
        contractRoot().resolve(relativePath).readText()
    )

    private fun contractRoot(): File {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null) {
            val candidate = current.resolve("contracts")
            if (candidate.resolve("product-image-v1.json").isFile) return candidate
            current = current.parentFile
        }
        error("contracts/product-image-v1.json not found")
    }
}

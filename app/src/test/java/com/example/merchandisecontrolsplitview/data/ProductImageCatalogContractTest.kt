package com.example.merchandisecontrolsplitview.data

import com.example.merchandisecontrolsplitview.productimage.ProductImageApiClient
import com.example.merchandisecontrolsplitview.productimage.ProductImageException
import com.example.merchandisecontrolsplitview.productimage.downloadProductImageWithOneAuthRefresh
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class ProductImageCatalogContractTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `product push row omits remote authoritative image fields when unset`() {
        val encoded = json.encodeToString(
            InventoryProductRow(
                id = "00000000-0000-4000-8000-000000001371",
                ownerUserId = "00000000-0000-4000-8000-000000001372",
                shopId = "00000000-0000-4000-8000-000000001373",
                barcode = "task137",
                productName = "Product image contract"
            )
        )

        assertFalse(encoded.contains("primary_image_version_id"))
        assertFalse(encoded.contains("primary_image_updated_at"))
        assertFalse(encoded.contains("signedUrl"))
        assertFalse(encoded.contains("object_path"))
    }

    @Test
    fun `inbound image fields decode and participate in idempotency fingerprint`() {
        val withoutImage = InventoryProductRow(
            id = "00000000-0000-4000-8000-000000001374",
            ownerUserId = "00000000-0000-4000-8000-000000001375",
            barcode = "task137-inbound"
        )
        val withImage = json.decodeFromString<InventoryProductRow>(
            """
            {
              "id":"00000000-0000-4000-8000-000000001374",
              "owner_user_id":"00000000-0000-4000-8000-000000001375",
              "barcode":"task137-inbound",
              "primary_image_version_id":"00000000-0000-4000-8000-000000001376",
              "primary_image_updated_at":"2026-07-16T12:00:00Z"
            }
            """.trimIndent()
        )

        assertEquals(
            "00000000-0000-4000-8000-000000001376",
            withImage.primaryImageVersionId
        )
        assertNotEquals(fingerprintProductInbound(withoutImage), fingerprintProductInbound(withImage))
    }

    @Test
    fun `signed storage urls are bound to the configured Supabase origin`() = runTest {
        val api = ProductImageApiClient(
            apiBaseUrl = "https://admin.task137.invalid",
            storageBaseUrl = "https://project-137.supabase.co",
            debugBuild = false
        )
        val jpeg = byteArrayOf(
            0xff.toByte(),
            0xd8.toByte(),
            0xff.toByte(),
            0xd9.toByte()
        )
        try {
            api.putSignedJpeg(
                "https://attacker.invalid/storage/v1/object/upload/sign/product-images/main.jpg",
                jpeg
            )
            fail("Expected the cross-origin signed URL to be rejected")
        } catch (error: ProductImageException) {
            assertEquals("image_signed_url_invalid", error.code)
        }
        try {
            api.putSignedJpeg(
                "https://project-137.supabase.co/storage/v1/object/upload/sign/product-images/not-canonical/main.jpg",
                jpeg
            )
            fail("Expected the non-canonical object path to be rejected")
        } catch (error: ProductImageException) {
            assertEquals("image_signed_url_invalid", error.code)
        } finally {
            api.close()
        }
    }

    @Test
    fun `expired signed read is resolved again exactly once`() = runTest {
        var resolves = 0
        var downloads = 0
        val bytes = byteArrayOf(1, 3, 7)

        val result = downloadProductImageWithOneAuthRefresh(
            resolveSignedUrl = {
                resolves += 1
                "signed-$resolves"
            },
            download = {
                downloads += 1
                if (downloads == 1) {
                    throw ProductImageException("image_download_failed_403")
                }
                bytes
            }
        )

        assertEquals(bytes.toList(), result.toList())
        assertEquals(2, resolves)
        assertEquals(2, downloads)
    }

    @Test
    fun `invalid image bytes never trigger signed read refresh`() = runTest {
        var resolves = 0
        var downloads = 0
        try {
            downloadProductImageWithOneAuthRefresh(
                resolveSignedUrl = {
                    resolves += 1
                    "signed-$resolves"
                },
                download = {
                    downloads += 1
                    throw ProductImageException("image_download_invalid")
                }
            )
            fail("Expected invalid bytes to stay fail-closed")
        } catch (error: ProductImageException) {
            assertEquals("image_download_invalid", error.code)
        }
        assertEquals(1, resolves)
        assertEquals(1, downloads)
    }
}

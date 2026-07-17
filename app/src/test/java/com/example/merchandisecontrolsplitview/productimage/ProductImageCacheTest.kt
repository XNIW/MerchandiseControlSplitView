package com.example.merchandisecontrolsplitview.productimage

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProductImageCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val accountOne = "00000000-0000-4000-8000-000000001371"
    private val accountTwo = "00000000-0000-4000-8000-000000001372"
    private val shopOne = "00000000-0000-4000-8000-000000001373"
    private val shopTwo = "00000000-0000-4000-8000-000000001374"
    private val product = "00000000-0000-4000-8000-000000001375"
    private val versionOne = "00000000-0000-4000-8000-000000001376"
    private val versionTwo = "00000000-0000-4000-8000-000000001377"

    @Test
    fun `cache is isolated by account and shop and never falls through`() {
        val cache = ProductImageCache(temporaryFolder.newFolder("cache"), Unit)
        val bytes = jpegBytes()
        val accountOneScope = cache.accountScope(accountOne)
        val accountTwoScope = cache.accountScope(accountTwo)
        val reference = reference(accountOneScope, shopOne, versionOne)
        cache.write(reference, bytes)

        assertArrayEquals(bytes, cache.read(reference))
        assertNull(cache.read(reference(accountTwoScope, shopOne, versionOne)))
        assertNull(cache.read(reference(accountOneScope, shopTwo, versionOne)))
        assertFalse(accountOneScope.contains(accountOne))
        assertFalse(accountTwoScope.contains(accountTwo))
    }

    @Test
    fun `new version purge removes only older versions in the same scope`() {
        val cache = ProductImageCache(temporaryFolder.newFolder("cache-purge"), Unit)
        val bytes = jpegBytes()
        val accountOneScope = cache.accountScope(accountOne)
        val accountTwoScope = cache.accountScope(accountTwo)
        val oldReference = reference(accountOneScope, shopOne, versionOne)
        val newReference = reference(accountOneScope, shopOne, versionTwo)
        val otherAccountReference = reference(accountTwoScope, shopOne, versionOne)
        cache.write(oldReference, bytes)
        cache.write(newReference, bytes)
        cache.write(otherAccountReference, bytes)

        cache.purgeOtherVersions(accountOneScope, shopOne, product, versionTwo)

        assertNull(cache.read(oldReference))
        assertArrayEquals(bytes, cache.read(newReference))
        assertArrayEquals(bytes, cache.read(otherAccountReference))
    }

    @Test(expected = ProductImageException::class)
    fun `cache rejects non uuid path components`() {
        val cache = ProductImageCache(temporaryFolder.newFolder("cache-invalid"), Unit)
        cache.fileFor(reference(cache.accountScope(accountOne), "../other-shop", versionOne))
    }

    @Test(expected = ProductImageException::class)
    fun `cache rejects jpeg containing app1 metadata`() {
        val cache = ProductImageCache(temporaryFolder.newFolder("cache-app1-write"), Unit)
        val reference = reference(cache.accountScope(accountOne), shopOne, versionOne)

        cache.write(reference, jpegWithApp1(jpegBytes()))
    }

    @Test
    fun `cache treats an app1 jpeg already on disk as a miss`() {
        val cache = ProductImageCache(temporaryFolder.newFolder("cache-app1-read"), Unit)
        val reference = reference(cache.accountScope(accountOne), shopOne, versionOne)
        val file = cache.fileFor(reference)
        assertTrue(file.parentFile!!.mkdirs())
        file.writeBytes(jpegWithApp1(jpegBytes()))

        assertNull(cache.read(reference))
    }

    private fun reference(scope: String, shop: String, version: String) = ProductImageReference(
        accountScope = scope,
        shopId = shop,
        productId = product,
        versionId = version,
        variant = ProductImageVariant.THUMB
    )

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40, 100, 160))
        return try {
            val output = ByteArrayOutputStream()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output))
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun jpegWithApp1(jpeg: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) +
            byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0x00, 0x04, 0x00, 0x00) +
            jpeg.copyOfRange(2, jpeg.size)
}

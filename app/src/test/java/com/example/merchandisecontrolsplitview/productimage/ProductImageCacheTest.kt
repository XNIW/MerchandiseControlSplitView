package com.example.merchandisecontrolsplitview.productimage

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun `shop and account purge remove only the requested scope`() {
        val cache = ProductImageCache(temporaryFolder.newFolder("cache-scope-purge"), Unit)
        val bytes = jpegBytes()
        val accountOneScope = cache.accountScope(accountOne)
        val accountTwoScope = cache.accountScope(accountTwo)
        val firstShop = reference(accountOneScope, shopOne, versionOne)
        val secondShop = reference(accountOneScope, shopTwo, versionOne)
        val secondAccount = reference(accountTwoScope, shopOne, versionOne)
        cache.write(firstShop, bytes)
        cache.write(secondShop, bytes)
        cache.write(secondAccount, bytes)

        cache.purgeShop(accountOneScope, shopOne)

        assertNull(cache.read(firstShop))
        assertArrayEquals(bytes, cache.read(secondShop))
        assertArrayEquals(bytes, cache.read(secondAccount))

        cache.purgeAccount(accountOneScope)

        assertNull(cache.read(secondShop))
        assertArrayEquals(bytes, cache.read(secondAccount))
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

    @Test
    fun `memory cache uses real byte cost and evicts least recently used entry`() {
        val bytes = jpegBytes()
        val cache = ProductImageCache(
            testRoot = temporaryFolder.newFolder("cache-memory-lru"),
            memoryMaxBytes = bytes.size.toLong() + 1L,
            diskMaxBytes = 10L * 1024L * 1024L
        )
        val scope = cache.accountScope(accountOne)
        val first = reference(scope, shopOne, versionOne)
        val second = reference(scope, shopOne, versionTwo)

        cache.write(first, bytes)
        cache.write(second, bytes)

        assertEquals(1, cache.snapshot().memoryEntries)
        assertEquals(bytes.size.toLong(), cache.snapshot().memoryBytes)
        assertArrayEquals(bytes, cache.read(first))
        assertEquals(1, cache.snapshot().memoryEntries)
        assertEquals(bytes.size.toLong(), cache.snapshot().memoryBytes)
    }

    @Test
    fun `disk cache evicts oldest files by real byte budget`() {
        var now = 1_000L
        val bytes = jpegBytes()
        val root = temporaryFolder.newFolder("cache-disk-lru")
        val cache = ProductImageCache(
            testRoot = root,
            memoryMaxBytes = 10L * 1024L * 1024L,
            diskMaxBytes = bytes.size.toLong() + 1L,
            nowEpochMillis = { now }
        )
        val scope = cache.accountScope(accountOne)
        val first = reference(scope, shopOne, versionOne)
        val second = reference(scope, shopOne, versionTwo)

        cache.write(first, bytes)
        now += 1_000L
        cache.write(second, bytes)

        assertFalse(cache.fileFor(first).exists())
        assertTrue(cache.fileFor(second).isFile)
        assertEquals(1, cache.snapshot().diskEntries)
        assertEquals(bytes.size.toLong(), cache.snapshot().diskBytes)
    }

    @Test
    fun `invalid decode is deleted instead of entering cache`() {
        val cache = ProductImageCache(temporaryFolder.newFolder("cache-invalid-decode"), Unit)
        val reference = reference(cache.accountScope(accountOne), shopOne, versionOne)
        val file = cache.fileFor(reference)
        assertTrue(file.parentFile!!.mkdirs())
        file.writeBytes(
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        )

        assertNull(cache.read(reference))
        assertFalse(file.exists())
        assertEquals(0, cache.snapshot().memoryEntries)
        assertEquals(0, cache.snapshot().diskEntries)
    }

    @Test
    fun `startup removes abandoned atomic temporary files`() {
        val root = temporaryFolder.newFolder("cache-temp-cleanup")
        val directory = File(root, "stale")
        assertTrue(directory.mkdirs())
        val temporary = File(directory, ".thumb-abandoned.tmp")
        temporary.writeBytes(byteArrayOf(1, 2, 3))

        ProductImageCache(root, Unit)

        assertFalse(temporary.exists())
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

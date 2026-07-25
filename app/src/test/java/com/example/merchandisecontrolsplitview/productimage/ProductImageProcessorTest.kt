package com.example.merchandisecontrolsplitview.productimage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProductImageProcessorTest {
    private val processor = ProductImageProcessor()

    @Test
    fun `output dimensions never upscale and preserve aspect ratio`() {
        assertEquals(
            ProductImageProcessor.Dimensions(320, 200),
            processor.outputDimensions(320, 200, PRODUCT_IMAGE_MAIN_MAX_SIDE)
        )
        assertEquals(
            ProductImageProcessor.Dimensions(1600, 800),
            processor.outputDimensions(2400, 1200, PRODUCT_IMAGE_MAIN_MAX_SIDE)
        )
        assertEquals(
            ProductImageProcessor.Dimensions(192, 384),
            processor.outputDimensions(1000, 2000, PRODUCT_IMAGE_THUMB_MAX_SIDE)
        )
    }

    @Test
    fun `preprocess emits bounded opaque jpeg variants without app1 metadata`() {
        val source = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.TRANSPARENT)
        source.setPixel(600, 400, Color.rgb(20, 90, 180))

        val flattened = processor.renderOpaque(source, PRODUCT_IMAGE_MAIN_MAX_SIDE)
        try {
            assertFalse(flattened.hasAlpha())
        } finally {
            flattened.recycle()
        }

        val prepared = try {
            processor.prepareBitmap(source)
        } finally {
            source.recycle()
        }

        assertEquals(1200, prepared.main.metadata.width)
        assertEquals(800, prepared.main.metadata.height)
        assertEquals(384, prepared.thumb.metadata.width)
        assertEquals(256, prepared.thumb.metadata.height)
        assertTrue(prepared.main.bytes.size in 1..PRODUCT_IMAGE_MAIN_MAX_BYTES)
        assertTrue(prepared.thumb.bytes.size in 1..PRODUCT_IMAGE_THUMB_MAX_BYTES)
        assertTrue(PRODUCT_IMAGE_SHA256_PATTERN.matches(prepared.main.metadata.sha256))
        assertTrue(PRODUCT_IMAGE_SHA256_PATTERN.matches(prepared.thumb.metadata.sha256))
        assertTrue(isJpeg(prepared.main.bytes))
        assertTrue(isJpeg(prepared.thumb.bytes))
        assertFalse(jpegContainsForbiddenMetadata(prepared.main.bytes))
        assertFalse(jpegContainsForbiddenMetadata(prepared.thumb.bytes))

        val decoded = BitmapFactory.decodeByteArray(
            prepared.main.bytes,
            0,
            prepared.main.bytes.size
        )
        try {
            val corner = decoded.getPixel(10, 10)
            assertEquals(255, Color.alpha(corner))
        } finally {
            decoded.recycle()
        }
    }

    @Test(expected = ProductImageException::class)
    fun `input pixel ceiling is enforced before allocation`() {
        processor.outputDimensions(10_000, 7_000, PRODUCT_IMAGE_MAIN_MAX_SIDE)
    }

    @Test
    fun `canonicalizer reuses clean buffer and compacts forbidden metadata with one result allocation`() {
        val metadataBearing = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xe2.toByte(), 0x00, 0x07,
            0x49, 0x43, 0x43, 0x00, 0x01,
            0xff.toByte(), 0xda.toByte(), 0x00, 0x02,
            0x11, 0x22,
            0xff.toByte(), 0xd9.toByte()
        )

        assertTrue(jpegContainsForbiddenMetadata(metadataBearing))
        val canonical = canonicalizeJpegMetadata(metadataBearing)
        assertNotSame(metadataBearing, canonical)
        assertFalse(jpegContainsForbiddenMetadata(canonical))
        assertEquals(
            listOf(0xff, 0xd8, 0xff, 0xda),
            canonical.take(4).map { it.toInt() and 0xff }
        )

        val clean = ByteArray(PRODUCT_IMAGE_MAIN_MAX_BYTES).apply {
            this[0] = 0xff.toByte()
            this[1] = 0xd8.toByte()
            this[2] = 0xff.toByte()
            this[3] = 0xda.toByte()
            this[4] = 0x00
            this[5] = 0x02
            this[lastIndex - 1] = 0xff.toByte()
            this[lastIndex] = 0xd9.toByte()
        }
        val startedAt = System.nanoTime()
        assertSame(clean, canonicalizeJpegMetadata(clean))
        val elapsedNanos = System.nanoTime() - startedAt
        println(
            "TASK139_ANDROID_CANONICALIZER " +
                "cleanBytes=${clean.size} strippedInputBytes=${metadataBearing.size} " +
                "strippedOutputBytes=${canonical.size} " +
                "cleanIdentity=true elapsedNanos=$elapsedNanos"
        )
    }

    @Test
    fun `jpeg allowlist rejects non canonical JFIF application segment`() {
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(40, 100, 160))
        val encoded = try {
            processor.prepareBitmap(source).main.bytes
        } finally {
            source.recycle()
        }
        val app0Marker = (2 until encoded.lastIndex - 3).first { offset ->
            encoded[offset] == 0xff.toByte() &&
                encoded[offset + 1] == 0xe0.toByte()
        }
        val dataStart = app0Marker + 4
        val nonCanonical = encoded.copyOf().apply {
            this[dataStart + 7] = 0x03
        }

        assertFalse(jpegContainsForbiddenMetadata(encoded))
        assertTrue(jpegContainsForbiddenMetadata(nonCanonical))
    }

    @Test
    fun `noisy main image stays bounded and reduction sequence has a finite floor`() {
        val random = kotlin.random.Random(137)
        val source = Bitmap.createBitmap(1_600, 1_600, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(1_600 * 1_600) {
            0xff000000.toInt() or random.nextInt(0x01000000)
        }
        source.setPixels(pixels, 0, 1_600, 0, 0, 1_600, 1_600)

        val prepared = try {
            processor.prepareBitmap(source)
        } finally {
            source.recycle()
        }

        assertTrue(prepared.main.bytes.size <= PRODUCT_IMAGE_MAIN_MAX_BYTES)
        assertTrue(maxOf(prepared.main.metadata.width, prepared.main.metadata.height) >= 640)

        assertEquals(
            listOf(1_600, 1_360, 1_152, 976, 832, 704, 640),
            processor.outputSideSchedule(1_600, 1_600, 640)
        )
    }
}

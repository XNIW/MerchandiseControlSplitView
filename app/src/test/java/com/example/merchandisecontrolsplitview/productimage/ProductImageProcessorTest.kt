package com.example.merchandisecontrolsplitview.productimage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(jpegContainsApp1(prepared.main.bytes))
        assertFalse(jpegContainsApp1(prepared.thumb.bytes))

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

        val reductionSequence = mutableListOf<Int>()
        var current = 1_600
        while (true) {
            val reduced = processor.reducedMaximumSide(current, 640) ?: break
            reductionSequence += reduced
            current = reduced
        }
        assertEquals(listOf(1_360, 1_156, 982, 834, 708, 640), reductionSequence)
    }
}

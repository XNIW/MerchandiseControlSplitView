package com.example.merchandisecontrolsplitview.productimage

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProductImageProcessor {
    suspend fun prepare(context: Context, uri: Uri): PreparedProductImage =
        withContext(Dispatchers.IO) {
            val inputBytes = readValidatedInput(context.contentResolver, uri)
            val source = try {
                ImageDecoder.createSource(ByteBuffer.wrap(inputBytes))
            } catch (_: Throwable) {
                throw ProductImageException("image_decode_failed")
            }
            val decoded = try {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    validateDimensions(width, height)
                    val target = outputDimensions(width, height, PRODUCT_IMAGE_MAIN_MAX_SIDE)
                    if (target.width != width || target.height != height) {
                        decoder.setTargetSize(target.width, target.height)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (error: ProductImageException) {
                throw error
            } catch (_: Throwable) {
                throw ProductImageException("image_decode_failed")
            }
            try {
                prepareBitmap(decoded)
            } finally {
                decoded.recycle()
            }
        }

    internal fun prepareBitmap(source: Bitmap): PreparedProductImage {
        validateDimensions(source.width, source.height)
        val main = encodeWithinBudget(
            source = source,
            initialMaxSide = PRODUCT_IMAGE_MAIN_MAX_SIDE,
            minimumSide = 640,
            qualities = intArrayOf(82, 76, 70),
            targetBytes = PRODUCT_IMAGE_MAIN_TARGET_BYTES,
            hardMaxBytes = PRODUCT_IMAGE_MAIN_MAX_BYTES
        )
        val thumb = encodeWithinBudget(
            source = source,
            initialMaxSide = PRODUCT_IMAGE_THUMB_MAX_SIDE,
            minimumSide = 128,
            qualities = intArrayOf(75, 68, 60, 52),
            targetBytes = PRODUCT_IMAGE_THUMB_MAX_BYTES,
            hardMaxBytes = PRODUCT_IMAGE_THUMB_MAX_BYTES
        )
        if (jpegContainsApp1(main.bytes) || jpegContainsApp1(thumb.bytes)) {
            throw ProductImageException("image_metadata_strip_failed")
        }
        return PreparedProductImage(main = main, thumb = thumb)
    }

    private fun readValidatedInput(resolver: ContentResolver, uri: Uri): ByteArray {
        val declaredLength = try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        } catch (_: Throwable) {
            null
        }
        if (declaredLength != null && declaredLength >= 0L &&
            (declaredLength < 1L || declaredLength > PRODUCT_IMAGE_INPUT_MAX_BYTES.toLong())
        ) {
            throw ProductImageException("image_input_size_invalid")
        }

        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > PRODUCT_IMAGE_INPUT_MAX_BYTES) {
                        throw ProductImageException("image_input_size_invalid")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: ProductImageException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: throw ProductImageException("image_input_unreadable")

        if (bytes.isEmpty()) throw ProductImageException("image_input_size_invalid")
        val header = bytes.copyOf(minOf(16, bytes.size))

        val jpeg = header.size >= 3 &&
            header[0] == 0xff.toByte() &&
            header[1] == 0xd8.toByte() &&
            header[2] == 0xff.toByte()
        val png = header.size >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4e.toByte() &&
            header[3] == 0x47.toByte() &&
            header[4] == 0x0d.toByte() &&
            header[5] == 0x0a.toByte() &&
            header[6] == 0x1a.toByte() &&
            header[7] == 0x0a.toByte()
        if (!jpeg && !png) {
            throw ProductImageException("image_input_format_unsupported")
        }
        return bytes
    }

    private fun preparedVariant(bytes: ByteArray, bitmap: Bitmap): PreparedProductImageVariant {
        if (!isJpeg(bytes)) throw ProductImageException("image_encode_failed")
        return PreparedProductImageVariant(
            bytes = bytes,
            metadata = ProductImageMetadata(
                bytes = bytes.size,
                height = bitmap.height,
                sha256 = sha256(bytes),
                width = bitmap.width
            )
        )
    }

    private fun encodeWithinBudget(
        source: Bitmap,
        initialMaxSide: Int,
        minimumSide: Int,
        qualities: IntArray,
        targetBytes: Int,
        hardMaxBytes: Int
    ): PreparedProductImageVariant {
        val sourceLongestSide = maxOf(source.width, source.height)
        var maximumSide = minOf(initialMaxSide, sourceLongestSide)
        var fallback: PreparedProductImageVariant? = null

        while (maximumSide > 0) {
            val bitmap = renderOpaque(source, maximumSide)
            try {
                for (quality in qualities) {
                    val output = ByteArrayOutputStream()
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        throw ProductImageException("image_encode_failed")
                    }
                    val variant = preparedVariant(output.toByteArray(), bitmap)
                    if (variant.bytes.size <= hardMaxBytes &&
                        (fallback == null || variant.bytes.size < fallback.bytes.size)
                    ) {
                        fallback = variant
                    }
                    if (variant.bytes.size <= targetBytes) return variant
                }
            } finally {
                bitmap.recycle()
            }

            if (maximumSide <= minimumSide ||
                (maximumSide >= sourceLongestSide && sourceLongestSide < minimumSide)
            ) {
                break
            }
            val reduced = reducedMaximumSide(maximumSide, minimumSide) ?: break
            maximumSide = minOf(reduced, sourceLongestSide)
        }

        return fallback?.takeIf { it.bytes.isNotEmpty() && it.bytes.size <= hardMaxBytes }
            ?: throw ProductImageException("image_output_budget_exceeded")
    }

    internal fun reducedMaximumSide(current: Int, minimum: Int): Int? {
        if (current <= minimum) return null
        val reduced = maxOf(minimum, kotlin.math.floor(current * 0.85).toInt())
        return reduced.takeIf { it < current }
    }

    internal fun renderOpaque(source: Bitmap, maxSide: Int): Bitmap {
        val target = outputDimensions(source.width, source.height, maxSide)
        val output = createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val destination = android.graphics.Rect(0, 0, target.width, target.height)
        canvas.drawBitmap(source, null, destination, null)
        output.setHasAlpha(false)
        return output
    }

    internal data class Dimensions(val width: Int, val height: Int)

    internal fun outputDimensions(width: Int, height: Int, maxSide: Int): Dimensions {
        validateDimensions(width, height)
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(width, height).toDouble())
        return Dimensions(
            width = maxOf(1, kotlin.math.round(width * scale).toInt()),
            height = maxOf(1, kotlin.math.round(height * scale).toInt())
        )
    }

    private fun validateDimensions(width: Int, height: Int) {
        if (width < 1 || height < 1 || width.toLong() * height.toLong() > PRODUCT_IMAGE_INPUT_MAX_PIXELS) {
            throw ProductImageException("image_dimensions_invalid")
        }
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0xff.toByte() &&
        bytes[1] == 0xd8.toByte() &&
        bytes[bytes.lastIndex - 1] == 0xff.toByte() &&
        bytes[bytes.lastIndex] == 0xd9.toByte()

/** Controlla i marker prima dello scan compresso; APP1 contiene EXIF/XMP. */
internal fun jpegContainsApp1(bytes: ByteArray): Boolean {
    if (!isJpeg(bytes)) return true
    var offset = 2
    while (offset + 1 < bytes.size) {
        if (bytes[offset] != 0xff.toByte()) return true
        while (offset < bytes.size && bytes[offset] == 0xff.toByte()) offset++
        if (offset >= bytes.size) return true
        val marker = bytes[offset].toInt() and 0xff
        offset++
        if (marker == 0xda || marker == 0xd9) return false
        if (marker == 0xd8 || marker in 0xd0..0xd7 || marker == 0x01) continue
        if (offset + 1 >= bytes.size) return true
        val length = ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)
        if (length < 2 || offset + length > bytes.size) return true
        if (marker == 0xe1) return true
        offset += length
    }
    return true
}

package com.example.merchandisecontrolsplitview.productimage

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ProductImageProcessor {
    suspend fun prepare(context: Context, uri: Uri): PreparedProductImage =
        withContext(Dispatchers.IO) {
            val operationContext = currentCoroutineContext()
            val checkCancelled = { operationContext.ensureActive() }
            val resolver = context.contentResolver
            validateInput(resolver, uri, checkCancelled)
            val bounds = readBounds(resolver, uri)
            validateDimensions(bounds.width, bounds.height)
            checkCancelled()
            val source = try {
                if (uri.scheme == ContentResolver.SCHEME_FILE) {
                    ImageDecoder.createSource(File(requireNotNull(uri.path)))
                } else {
                    ImageDecoder.createSource(resolver, uri)
                }
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
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProductImageException) {
                throw error
            } catch (_: Throwable) {
                throw ProductImageException("image_decode_failed")
            }
            try {
                checkCancelled()
                prepareBitmap(decoded, checkCancelled)
            } finally {
                decoded.recycle()
            }
        }

    internal fun prepareBitmap(
        source: Bitmap,
        checkCancelled: () -> Unit = {}
    ): PreparedProductImage {
        checkCancelled()
        validateDimensions(source.width, source.height)
        val canUseSourceDirectly = !source.hasAlpha() &&
            maxOf(source.width, source.height) <= PRODUCT_IMAGE_MAIN_MAX_SIDE
        val normalizedMain = if (canUseSourceDirectly) {
            source
        } else {
            renderOpaque(source, PRODUCT_IMAGE_MAIN_MAX_SIDE)
        }
        return try {
            checkCancelled()
            val main = encodeWithinBudget(
                source = normalizedMain,
                initialMaxSide = PRODUCT_IMAGE_MAIN_MAX_SIDE,
                minimumSide = 640,
                qualities = intArrayOf(82, 76, 70),
                targetBytes = PRODUCT_IMAGE_MAIN_TARGET_BYTES,
                hardMaxBytes = PRODUCT_IMAGE_MAIN_MAX_BYTES,
                checkCancelled = checkCancelled
            )
            checkCancelled()
            // La thumb deriva sempre dal bitmap main normalizzato, mai dalla sorgente originale.
            val thumb = encodeWithinBudget(
                source = normalizedMain,
                initialMaxSide = PRODUCT_IMAGE_THUMB_MAX_SIDE,
                minimumSide = 128,
                qualities = intArrayOf(75, 68, 60, 52),
                targetBytes = PRODUCT_IMAGE_THUMB_MAX_BYTES,
                hardMaxBytes = PRODUCT_IMAGE_THUMB_MAX_BYTES,
                checkCancelled = checkCancelled
            )
            if (jpegContainsApp1(main.bytes) || jpegContainsApp1(thumb.bytes)) {
                throw ProductImageException("image_metadata_strip_failed")
            }
            PreparedProductImage(main = main, thumb = thumb)
        } finally {
            if (!canUseSourceDirectly) normalizedMain.recycle()
        }
    }

    private fun validateInput(
        resolver: ContentResolver,
        uri: Uri,
        checkCancelled: () -> Unit
    ) {
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

        val countEntireStream = declaredLength == null || declaredLength < 0L
        var total = if (countEntireStream) 0L else declaredLength
        val header = ByteArray(16)
        var headerSize = 0
        try {
            openInputStream(resolver, uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (countEntireStream) total += count
                    if (total > PRODUCT_IMAGE_INPUT_MAX_BYTES) {
                        throw ProductImageException("image_input_size_invalid")
                    }
                    if (headerSize < header.size) {
                        val copyCount = minOf(count, header.size - headerSize)
                        buffer.copyInto(header, headerSize, 0, copyCount)
                        headerSize += copyCount
                    }
                    if (!countEntireStream && headerSize == header.size) break
                }
            } ?: throw ProductImageException("image_input_unreadable")
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProductImageException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_input_unreadable")
        }

        if (total < 1L) throw ProductImageException("image_input_size_invalid")

        val jpeg = headerSize >= 3 &&
            header[0] == 0xff.toByte() &&
            header[1] == 0xd8.toByte() &&
            header[2] == 0xff.toByte()
        val png = headerSize >= 8 &&
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
    }

    private fun readBounds(resolver: ContentResolver, uri: Uri): Dimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            val input = openInputStream(resolver, uri)
                ?: throw ProductImageException("image_input_unreadable")
            input.use {
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (error: ProductImageException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_decode_failed")
        }
        if (options.outWidth < 1 || options.outHeight < 1) {
            throw ProductImageException("image_decode_failed")
        }
        return Dimensions(options.outWidth, options.outHeight)
    }

    private fun openInputStream(resolver: ContentResolver, uri: Uri): InputStream? =
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { path -> FileInputStream(File(path)) }
        } else {
            resolver.openInputStream(uri)
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
        hardMaxBytes: Int,
        checkCancelled: () -> Unit
    ): PreparedProductImageVariant {
        val sourceLongestSide = maxOf(source.width, source.height)
        var maximumSide = minOf(initialMaxSide, sourceLongestSide)
        var fallback: PreparedProductImageVariant? = null

        while (maximumSide > 0) {
            checkCancelled()
            val useSourceDirectly = maximumSide >= sourceLongestSide && !source.hasAlpha()
            val bitmap = if (useSourceDirectly) source else renderOpaque(source, maximumSide)
            try {
                for (quality in qualities) {
                    checkCancelled()
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
                if (!useSourceDirectly) bitmap.recycle()
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

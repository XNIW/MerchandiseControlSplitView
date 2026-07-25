package com.example.merchandisecontrolsplitview.productimage

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductImageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun pickerAndCameraContractsStayImageOnlyAndAppScoped() {
        val pickerIntent = ActivityResultContracts.PickVisualMedia().createIntent(
            context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
        assertEquals("image/*", pickerIntent.type)
        assertFalse(pickerIntent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))

        val captureDirectory = File(context.cacheDir, "product-image-capture")
        assertTrue(captureDirectory.exists() || captureDirectory.mkdirs())
        val captureFile = File.createTempFile("task137-", ".jpg", captureDirectory)
        try {
            val outputUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                captureFile
            )
            val cameraIntent = ActivityResultContracts.TakePicture().createIntent(context, outputUri)
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, cameraIntent.action)
            assertEquals(
                outputUri,
                IntentCompat.getParcelableExtra(cameraIntent, MediaStore.EXTRA_OUTPUT, Uri::class.java)
            )
            assertTrue(cameraIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertTrue(cameraIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            captureFile.delete()
        }
    }

    @Test
    fun highResolutionPreprocessAndCacheRunOnDeviceWithScopedIsolation() = runBlocking {
        val inputFile = File(context.cacheDir, "task137-high-resolution-${System.nanoTime()}.jpg")
        val inputWidth = 8_000
        val inputHeight = 6_000
        val source = patternedBitmap(
            width = inputWidth,
            height = inputHeight,
            config = Bitmap.Config.RGB_565
        )
        try {
            inputFile.outputStream().use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
        } finally {
            source.recycle()
        }
        val inputBytes = inputFile.length()
        assertTrue(inputBytes in 1..PRODUCT_IMAGE_INPUT_MAX_BYTES.toLong())
        val beforePssKb = Debug.getPss()
        val startedAt = SystemClock.elapsedRealtime()
        val prepared = try {
            ProductImageProcessor().prepare(context, Uri.fromFile(inputFile))
        } finally {
            inputFile.delete()
        }
        val elapsedMilliseconds = SystemClock.elapsedRealtime() - startedAt
        val afterPssKb = Debug.getPss()

        assertEquals(1_600, maxOf(prepared.main.metadata.width, prepared.main.metadata.height))
        assertEquals(384, maxOf(prepared.thumb.metadata.width, prepared.thumb.metadata.height))
        assertTrue(prepared.main.metadata.bytes <= PRODUCT_IMAGE_MAIN_TARGET_BYTES)
        assertTrue(prepared.thumb.metadata.bytes <= PRODUCT_IMAGE_THUMB_MAX_BYTES)
        assertFalse(jpegContainsForbiddenMetadata(prepared.main.bytes))
        assertFalse(jpegContainsForbiddenMetadata(prepared.thumb.bytes))

        val root = File(context.cacheDir, "task137-product-image-cache-${System.nanoTime()}")
        try {
            val cache = ProductImageCache(root, Unit)
            val accountId = "13700000-0000-4000-8000-000000000001"
            val accountScope = cache.accountScope(accountId)
            val reference = ProductImageReference(
                accountScope = accountScope,
                shopId = "13700000-0000-4000-8000-000000000002",
                productId = "13700000-0000-4000-8000-000000000003",
                versionId = "13700000-0000-4000-8000-000000000004",
                variant = ProductImageVariant.MAIN
            )
            cache.write(reference, prepared.main.bytes)
            assertArrayEquals(prepared.main.bytes, cache.read(reference))
            assertNull(
                cache.read(
                    reference.copy(shopId = "13700000-0000-4000-8000-000000000005")
                )
            )
            assertNull(
                cache.read(
                    reference.copy(
                        accountScope = cache.accountScope(
                            "13700000-0000-4000-8000-000000000006"
                        )
                    )
                )
            )
        } finally {
            root.deleteRecursively()
        }

        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "TASK137_ANDROID_METRICS " +
                        """{"inputBytes":$inputBytes,"inputWidth":$inputWidth,"inputHeight":$inputHeight,"inputMegapixels":48,"elapsedMilliseconds":$elapsedMilliseconds,"pssBeforeKb":$beforePssKb,"pssAfterKb":$afterPssKb,"mainBytes":${prepared.main.metadata.bytes},"mainWidth":${prepared.main.metadata.width},"mainHeight":${prepared.main.metadata.height},"thumbBytes":${prepared.thumb.metadata.bytes},"thumbWidth":${prepared.thumb.metadata.width},"thumbHeight":${prepared.thumb.metadata.height}}""" +
                        "\n"
                )
            }
        )
    }

    @Test
    fun imageClientRunsUploadReadAndRemoveAgainstLoopback() = runBlocking {
        val accountId = "13700000-0000-4000-8000-000000000001"
        val cacheScope = sha256("product-image-account:$accountId".encodeToByteArray())
        val versionId = "13700000-0000-4000-8000-000000000004"
        val prepared = preparedFixture()
        val server = ProductImageLoopbackServer(cacheScope, versionId, prepared.thumb.bytes)
        val api = ProductImageApiClient(
            apiBaseUrl = "http://127.0.0.1:${server.port}",
            storageBaseUrl = "http://127.0.0.1:${server.port}",
            debugBuild = true
        )
        try {
            val intent = api.createIntent(
                accessToken = "fixture-session",
                body = ProductImageIntentBody(
                    main = prepared.main.metadata.toBody(),
                    productId = "13700000-0000-4000-8000-000000000003",
                    shopId = "13700000-0000-4000-8000-000000000002",
                    thumb = prepared.thumb.metadata.toBody()
                )
            )
            assertTrue(intent.ok)
            assertEquals("upload_required", intent.status)
            assertEquals(cacheScope, intent.cacheScope)
            assertEquals(versionId, intent.versionId)

            val mainReference = ProductImageReference(
                accountScope = cacheScope,
                shopId = "13700000-0000-4000-8000-000000000002",
                productId = "13700000-0000-4000-8000-000000000003",
                versionId = versionId,
                variant = ProductImageVariant.MAIN
            )
            val thumbReference = mainReference.copy(variant = ProductImageVariant.THUMB)
            api.putSignedJpeg(intent.mainUploadUrl!!, prepared.main.bytes, mainReference)
            api.putSignedJpeg(intent.thumbUploadUrl!!, prepared.thumb.bytes, thumbReference)
            val finalized = api.finalizeImage(
                accessToken = "fixture-session",
                body = ProductImageFinalizeBody(
                    productId = "13700000-0000-4000-8000-000000000003",
                    shopId = "13700000-0000-4000-8000-000000000002",
                    versionId = versionId
                )
            )
            assertTrue(finalized.ok)
            assertEquals("finalized", finalized.status)
            assertEquals(versionId, finalized.versionId)

            val read = api.readUrls(
                accessToken = "fixture-session",
                body = ProductImageReadBody(
                    refs = listOf(
                        ProductImageReadRefBody(
                            productId = "13700000-0000-4000-8000-000000000003",
                            variant = "thumb",
                            versionId = versionId
                        )
                    ),
                    shopId = "13700000-0000-4000-8000-000000000002"
                )
            )
            assertTrue(read.ok)
            assertEquals(cacheScope, read.cacheScope)
            val readItem = read.items.single()
            assertEquals("ready", readItem.status)
            assertArrayEquals(
                prepared.thumb.bytes,
                api.downloadSignedJpeg(readItem.signedUrl!!, thumbReference)
            )

            val removed = api.removeImage(
                accessToken = "fixture-session",
                body = ProductImageRemoveBody(
                    expectedVersionId = versionId,
                    productId = "13700000-0000-4000-8000-000000000003",
                    shopId = "13700000-0000-4000-8000-000000000002"
                )
            )
            assertTrue(removed.ok)
            assertEquals("removed", removed.status)
            assertEquals(versionId, removed.versionId)
            assertEquals("remove", removed.operation)
            assertEquals("13700000-0000-4000-8000-000000000003", removed.productId)
            assertEquals("13700000-0000-4000-8000-000000000002", removed.shopId)
            assertNull(removed.currentImageVersionId)

            val records = server.records
            assertEquals(7, records.size)
            assertEquals("/api/shop/product-images/intent", records.first().path)
            assertEquals("/api/shop/product-images/remove", records.last().path)
            val posts = records.filter { it.method == "POST" }
            assertEquals(4, posts.size)
            assertTrue(posts.all {
                it.headers["authorization"] == "Bearer fixture-session" &&
                    it.headers["cookie"] == null &&
                    it.headers["content-type"]?.startsWith("application/json") == true
            })
            val puts = records.filter { it.method == "PUT" }
            assertEquals(2, puts.size)
            assertEquals(
                setOf(
                    "/storage/v1/object/upload/sign/product-images/shops/13700000-0000-4000-8000-000000000002/products/13700000-0000-4000-8000-000000000003/primary/$versionId/main.jpg",
                    "/storage/v1/object/upload/sign/product-images/shops/13700000-0000-4000-8000-000000000002/products/13700000-0000-4000-8000-000000000003/primary/$versionId/thumb.jpg"
                ),
                puts.map { it.path }.toSet()
            )
            assertTrue(puts.all {
                it.headers["authorization"] == null &&
                    it.headers["cookie"] == null &&
                    it.headers["x-upsert"] == "false" &&
                    it.headers["content-type"]?.startsWith("multipart/form-data") == true &&
                    it.bodyLength > 0
            })
            val downloads = records.filter { it.method == "GET" }
            assertEquals(1, downloads.size)
            assertEquals(
                "/storage/v1/object/sign/product-images/shops/13700000-0000-4000-8000-000000000002/products/13700000-0000-4000-8000-000000000003/primary/$versionId/thumb.jpg",
                downloads.single().path
            )
            assertNull(downloads.single().headers["authorization"])
            assertNull(downloads.single().headers["cookie"])
            assertNull(server.failure)
        } catch (error: Throwable) {
            val requestSummary = server.records.map { "${it.method} ${it.path}" }
            throw AssertionError(
                "Loopback upload failed after $requestSummary; server=${server.failure}",
                error
            )
        } finally {
            api.close()
            server.close()
        }
    }

    private fun patternedBitmap(
        width: Int,
        height: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(25, 100, 180))
        val stripeHeight = maxOf(1, height / 80)
        repeat(80) { index ->
            val red = (index * 31) % 255
            val green = (index * 47) % 255
            val blue = (index * 67) % 255
            val top = index * stripeHeight
            val paint = android.graphics.Paint().apply {
                color = Color.rgb(red, green, blue)
            }
            canvas.drawRect(0f, top.toFloat(), width.toFloat(), (top + stripeHeight).toFloat(), paint)
        }
        return bitmap
    }

    private fun preparedFixture(): PreparedProductImage {
        val source = patternedBitmap(width = 800, height = 600)
        return try {
            ProductImageProcessor().prepareBitmap(source)
        } finally {
            source.recycle()
        }
    }
}

private data class ProductImageHTTPRecord(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val bodyLength: Int
)

private class ProductImageLoopbackServer(
    private val cacheScope: String,
    private val versionId: String,
    private val downloadBytes: ByteArray
) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val captured = Collections.synchronizedList(mutableListOf<ProductImageHTTPRecord>())
    @Volatile private var closed = false
    @Volatile var failure: Throwable? = null
        private set
    private val thread = Thread({
        while (!closed) {
            try {
                server.accept().use(::handle)
            } catch (error: SocketException) {
                if (!closed) failure = error
                break
            } catch (error: Throwable) {
                failure = error
                break
            }
        }
    }, "task137-product-image-loopback").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = server.localPort

    val records: List<ProductImageHTTPRecord>
        get() = synchronized(captured) { captured.toList() }

    override fun close() {
        closed = true
        server.close()
        thread.join(2_000)
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readAsciiLine(input) ?: error("missing request line")
        val requestParts = requestLine.split(' ', limit = 3)
        require(requestParts.size == 3)
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: error("unexpected end of headers")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            require(separator > 0)
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        val bodyLength = when {
            headers["transfer-encoding"]?.equals("chunked", ignoreCase = true) == true ->
                readChunkedBody(input)
            else -> readFixedBody(input, headers["content-length"]?.toIntOrNull() ?: 0)
        }
        val record = ProductImageHTTPRecord(
            method = requestParts[0],
            path = requestParts[1].substringBefore('?'),
            headers = headers,
            bodyLength = bodyLength
        )
        captured += record
        val (responseBody, responseContentType) = when (record.path) {
            "/api/shop/product-images/intent" -> """
                {"cacheScope":"$cacheScope","mainUploadUrl":"http://127.0.0.1:$port/storage/v1/object/upload/sign/product-images/shops/13700000-0000-4000-8000-000000000002/products/13700000-0000-4000-8000-000000000003/primary/$versionId/main.jpg","ok":true,"status":"upload_required","thumbUploadUrl":"http://127.0.0.1:$port/storage/v1/object/upload/sign/product-images/shops/13700000-0000-4000-8000-000000000002/products/13700000-0000-4000-8000-000000000003/primary/$versionId/thumb.jpg","versionId":"$versionId"}
            """.trimIndent().encodeToByteArray() to "application/json"
            "/api/shop/product-images/finalize" -> """
                {"imageUpdatedAt":"2026-07-17T12:34:56Z","ok":true,"status":"finalized","versionId":"$versionId"}
            """.trimIndent().encodeToByteArray() to "application/json"
            "/api/shop/product-images/read-urls" -> """
                {"cacheScope":"$cacheScope","items":[{"expiresAt":"2026-07-17T12:39:56Z","productId":"13700000-0000-4000-8000-000000000003","signedUrl":"http://127.0.0.1:$port/storage/v1/object/sign/product-images/shops/13700000-0000-4000-8000-000000000002/products/13700000-0000-4000-8000-000000000003/primary/$versionId/thumb.jpg?token=redacted","status":"ready","variant":"thumb","versionId":"$versionId"}],"ok":true}
            """.trimIndent().encodeToByteArray() to "application/json"
            "/api/shop/product-images/remove" -> """
                {"cleanupStatus":"complete","currentImageVersionId":null,"imageUpdatedAt":"2026-07-17T12:40:00Z","ok":true,"operation":"remove","productId":"13700000-0000-4000-8000-000000000003","shopId":"13700000-0000-4000-8000-000000000002","status":"removed","versionId":"$versionId"}
            """.trimIndent().encodeToByteArray() to "application/json"
            "/storage/v1/object/sign/product-images/shops/13700000-0000-4000-8000-000000000002/products/13700000-0000-4000-8000-000000000003/primary/$versionId/thumb.jpg" ->
                downloadBytes to "image/jpeg"
            "/storage/v1/object/upload/sign/product-images/shops/13700000-0000-4000-8000-000000000002/products/13700000-0000-4000-8000-000000000003/primary/$versionId/main.jpg",
            "/storage/v1/object/upload/sign/product-images/shops/13700000-0000-4000-8000-000000000002/products/13700000-0000-4000-8000-000000000003/primary/$versionId/thumb.jpg" ->
                "{}".encodeToByteArray() to "application/json"
            else -> error("unexpected path ${record.path}")
        }
        val responseHeaders = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $responseContentType\r\n")
            append("Content-Length: ${responseBody.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        socket.getOutputStream().apply {
            write(responseHeaders)
            write(responseBody)
            flush()
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (output.size() == 0) null else output.toString(StandardCharsets.US_ASCII.name())
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
        }
        return output.toString(StandardCharsets.US_ASCII.name())
    }

    private fun readFixedBody(input: BufferedInputStream, length: Int): Int {
        var remaining = length
        val buffer = ByteArray(16 * 1_024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            require(count > 0)
            remaining -= count
        }
        return length
    }

    private fun readChunkedBody(input: BufferedInputStream): Int {
        var total = 0
        while (true) {
            val sizeLine = readAsciiLine(input) ?: error("missing chunk size")
            val chunkSize = sizeLine.substringBefore(';').trim().toInt(16)
            if (chunkSize == 0) {
                while (!readAsciiLine(input).isNullOrEmpty()) Unit
                return total
            }
            readFixedBody(input, chunkSize)
            total += chunkSize
            require(readAsciiLine(input)?.isEmpty() == true)
        }
    }
}

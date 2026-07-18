package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductRemoteRef
import com.example.merchandisecontrolsplitview.data.SelectedShop
import io.mockk.coEvery
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProductImageServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var cache: ProductImageCache
    private lateinit var cacheRoot: File
    private val accountId = uuid(1)
    private val shopId = uuid(2)
    private val accountScope by lazy { cache.accountScope(accountId) }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheRoot = temporaryFolder.newFolder("service-cache")
        cache = ProductImageCache(cacheRoot, Unit)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `product without version performs zero image network and zero cache writes`() = runTest {
        val remote = FakeProductImageRemote(accountScope, jpegBytes())
        val productId = insertProduct(index = 1, versionId = null)
        val service = service(remote)

        val item = service.loadBatch(
            listOf(ProductImageLoadRequest(productId, ProductImageVariant.THUMB, null))
        ).single()

        assertEquals(ProductImageLoadResult.Absent, item.result)
        assertNull(item.errorCode)
        assertEquals(0, remote.readCalls)
        assertEquals(0, remote.downloadCalls.get())
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `intent metadata includes required mime type with API serializer defaults`() {
        val metadata = ProductImageMetadata(
            bytes = 12_345,
            height = 720,
            sha256 = "a".repeat(64),
            width = 960
        )
        val body = ProductImageIntentBody(
            main = metadata.toBody(),
            productId = uuid(91),
            shopId = shopId,
            thumb = metadata.copy(
                bytes = 4_321,
                height = 288,
                sha256 = "b".repeat(64),
                width = 384
            ).toBody()
        )

        val encoded = Json.encodeToString(body)
        val json = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("image/jpeg", json.getValue("main").jsonObject
            .getValue("mimeType").jsonPrimitive.content)
        assertEquals("image/jpeg", json.getValue("thumb").jsonObject
            .getValue("mimeType").jsonPrimitive.content)
    }

    @Test
    fun `two hundred requests are deduplicated chunked to one hundred and bounded to four downloads`() =
        runTest {
            val remote = FakeProductImageRemote(accountScope, jpegBytes(), downloadDelayMs = 5L)
            val requests = (1..200).map { index ->
                val versionId = uuid(1_000 + index)
                ProductImageLoadRequest(
                    localProductId = insertProduct(index, versionId),
                    variant = ProductImageVariant.THUMB,
                    expectedVersionId = versionId
                )
            }
            val service = service(remote)

            val result = service.loadBatch(requests + requests.take(20))

            assertEquals(200, result.size)
            assertTrue(result.all { it.result is ProductImageLoadResult.Ready })
            assertEquals(listOf(100, 100), remote.readBatchSizes)
            assertEquals(200, remote.downloadCalls.get())
            assertTrue(remote.maxConcurrentDownloads.get() in 2..4)
        }

    @Test
    fun `concurrent duplicate load coalesces signed read and download`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val remote = FakeProductImageRemote(
            accountScope = accountScope,
            bytes = jpegBytes(),
            readGate = gate,
            readEntered = entered
        )
        val versionId = uuid(3_001)
        val productId = insertProduct(301, versionId)
        val request = ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId)
        val service = service(remote)

        val first = async { service.loadBatch(listOf(request)) }
        entered.await()
        val second = async { service.loadBatch(listOf(request)) }
        gate.complete(Unit)

        assertTrue(first.await().single().result is ProductImageLoadResult.Ready)
        assertTrue(second.await().single().result is ProductImageLoadResult.Ready)
        assertEquals(1, remote.readCalls)
        assertEquals(1, remote.downloadCalls.get())
    }

    @Test
    fun `invalid jpeg decode is rejected before cache write`() = runTest {
        val invalidJpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()
        )
        val remote = FakeProductImageRemote(accountScope, invalidJpeg)
        val versionId = uuid(4_001)
        val productId = insertProduct(401, versionId)
        val service = service(remote)

        val item = service.loadBatch(
            listOf(ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId))
        ).single()

        assertEquals("image_download_invalid", item.errorCode)
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `stale download completion cannot populate cache or return ready`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val remote = FakeProductImageRemote(
            accountScope = accountScope,
            bytes = jpegBytes(),
            downloadGate = gate,
            downloadEntered = entered
        )
        val firstVersion = uuid(5_001)
        val secondVersion = uuid(5_002)
        val productId = insertProduct(501, firstVersion)
        val service = service(remote)
        val deferred = async {
            service.loadBatch(
                listOf(
                    ProductImageLoadRequest(
                        productId,
                        ProductImageVariant.THUMB,
                        firstVersion
                    )
                )
            )
        }
        entered.await()
        database.productDao().updateRemoteImageReference(productId, secondVersion, "now")
        gate.complete(Unit)

        val item = deferred.await().single()
        assertEquals("image_version_changed", item.errorCode)
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `upload exposes ordered phases and cancellation stops before thumbnail and finalize`() = runTest {
        val prepared = preparedImage()
        val processor = mockk<ProductImageProcessor>()
        coEvery { processor.prepare(any(), any()) } returns prepared
        val remote = FakeProductImageRemote(accountScope, prepared.thumb.bytes).apply {
            uploadGate = CompletableDeferred()
        }
        val productId = insertProduct(601, null)
        val service = service(remote, processor)
        val phases = mutableListOf<ProductImageMutationPhase>()

        val upload = async {
            service.upload(productId, Uri.EMPTY) { phases += it }
        }
        remote.uploadEntered.await()
        upload.cancelAndJoin()

        assertEquals(
            listOf(
                ProductImageMutationPhase.PREPROCESSING,
                ProductImageMutationPhase.UPLOAD_MAIN
            ),
            phases
        )
        assertEquals(1, remote.uploadCalls)
        assertEquals(0, remote.finalizeCalls)
    }

    @Test
    fun `successful upload exposes every phase in order`() = runTest {
        val prepared = preparedImage()
        val processor = mockk<ProductImageProcessor>()
        coEvery { processor.prepare(any(), any()) } returns prepared
        val remote = FakeProductImageRemote(accountScope, prepared.thumb.bytes)
        val productId = insertProduct(602, null)
        val service = service(remote, processor)
        val phases = mutableListOf<ProductImageMutationPhase>()

        val result = service.upload(productId, Uri.EMPTY) { phases += it }

        assertEquals("finalized", result.status)
        assertEquals(
            listOf(
                ProductImageMutationPhase.PREPROCESSING,
                ProductImageMutationPhase.UPLOAD_MAIN,
                ProductImageMutationPhase.UPLOAD_THUMB,
                ProductImageMutationPhase.FINALIZING,
                ProductImageMutationPhase.COMPLETED
            ),
            phases
        )
        assertEquals(2, remote.uploadCalls)
        assertEquals(1, remote.finalizeCalls)
        assertEquals(
            result.versionId,
            database.productDao().getById(productId)?.primaryImageVersionId
        )
    }

    @Test
    fun `valid signed url lease is reused after byte cache eviction`() = runTest {
        var now = 10_000L
        val remote = FakeProductImageRemote(accountScope, jpegBytes()).apply {
            expiresAt = Instant.ofEpochMilli(now + 120_000L).toString()
        }
        val versionId = uuid(7_001)
        val productId = insertProduct(701, versionId)
        val remoteProductId = uuid(10_701)
        val request = ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId)
        val service = service(remote, nowEpochMillis = { now })

        assertTrue(service.loadBatch(listOf(request)).single().result is ProductImageLoadResult.Ready)
        cache.purgeProduct(accountScope, shopId, remoteProductId)
        assertTrue(service.loadBatch(listOf(request)).single().result is ProductImageLoadResult.Ready)

        assertEquals(1, remote.readCalls)
        assertEquals(2, remote.downloadCalls.get())
    }

    @Test
    fun `lease inside expiry safety window is refreshed once`() = runTest {
        var now = 20_000L
        val remote = FakeProductImageRemote(accountScope, jpegBytes()).apply {
            expiresAt = Instant.ofEpochMilli(now + 120_000L).toString()
        }
        val versionId = uuid(7_101)
        val productId = insertProduct(711, versionId)
        val request = ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId)
        val service = service(remote, nowEpochMillis = { now })

        service.loadBatch(listOf(request))
        cache.purgeProduct(accountScope, shopId, uuid(10_711))
        now += 91_000L
        service.loadBatch(listOf(request))

        assertEquals(2, remote.readCalls)
        assertEquals(2, remote.downloadCalls.get())
    }

    @Test
    fun `two forbidden downloads refresh once and never attempt a third time`() = runTest {
        val remote = FakeProductImageRemote(accountScope, jpegBytes()).apply {
            expiresAt = Instant.ofEpochMilli(200_000L).toString()
            downloadFailureCodes += "image_download_failed_403"
            downloadFailureCodes += "image_download_failed_403"
        }
        val versionId = uuid(7_201)
        val productId = insertProduct(721, versionId)
        val request = ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId)
        val service = service(remote, nowEpochMillis = { 30_000L })

        val item = service.loadBatch(listOf(request)).single()

        assertEquals("image_download_failed_403", item.errorCode)
        assertEquals(2, remote.readCalls)
        assertEquals(2, remote.downloadCalls.get())

        val recovered = service.loadBatch(listOf(request)).single()

        assertTrue(recovered.result is ProductImageLoadResult.Ready)
        assertEquals(3, remote.readCalls)
        assertEquals(3, remote.downloadCalls.get())
    }

    @Test
    fun `signed url lease cache evicts least recently used entry at bounded capacity`() = runTest {
        val remote = FakeProductImageRemote(accountScope, jpegBytes()).apply {
            expiresAt = Instant.ofEpochMilli(600_000L).toString()
        }
        val initialRequests = (801..1_056).map { index ->
            val versionId = uuid(20_000 + index)
            ProductImageLoadRequest(
                localProductId = insertProduct(index, versionId),
                variant = ProductImageVariant.THUMB,
                expectedVersionId = versionId
            )
        }
        val service = service(remote, nowEpochMillis = { 40_000L })

        service.loadBatch(initialRequests)
        assertEquals(3, remote.readCalls)

        val firstRequest = initialRequests.first()
        cache.purgeProduct(accountScope, shopId, uuid(10_801))
        assertTrue(
            service.loadBatch(listOf(firstRequest)).single().result is ProductImageLoadResult.Ready
        )
        assertEquals(3, remote.readCalls)

        val overflowVersion = uuid(30_057)
        val overflowRequest = ProductImageLoadRequest(
            localProductId = insertProduct(1_057, overflowVersion),
            variant = ProductImageVariant.THUMB,
            expectedVersionId = overflowVersion
        )
        service.loadBatch(listOf(overflowRequest))
        assertEquals(4, remote.readCalls)

        val secondRequest = initialRequests[1]
        cache.purgeProduct(accountScope, shopId, uuid(10_801))
        cache.purgeProduct(accountScope, shopId, uuid(10_802))
        service.loadBatch(listOf(firstRequest))
        assertEquals(4, remote.readCalls)
        service.loadBatch(listOf(secondRequest))
        assertEquals(5, remote.readCalls)
    }

    @Test
    fun `transient object upload is retried exactly once`() = runTest {
        val prepared = preparedImage()
        val processor = mockk<ProductImageProcessor>()
        coEvery { processor.prepare(any(), any()) } returns prepared
        val remote = FakeProductImageRemote(accountScope, prepared.thumb.bytes).apply {
            uploadFailureCodes += "image_upload_failed_503"
        }
        val productId = insertProduct(731, null)
        val service = service(remote, processor)

        val result = service.upload(productId, Uri.EMPTY)

        assertEquals("finalized", result.status)
        assertEquals(3, remote.uploadCalls)
        assertEquals(1, remote.finalizeCalls)
    }

    @Test
    fun `permanent object upload error is not retried or finalized`() = runTest {
        val prepared = preparedImage()
        val processor = mockk<ProductImageProcessor>()
        coEvery { processor.prepare(any(), any()) } returns prepared
        val remote = FakeProductImageRemote(accountScope, prepared.thumb.bytes).apply {
            uploadFailureCodes += "image_upload_failed_403"
        }
        val productId = insertProduct(741, null)
        val service = service(remote, processor)

        try {
            service.upload(productId, Uri.EMPTY)
            fail("Expected permanent upload failure")
        } catch (error: ProductImageException) {
            assertEquals("image_upload_failed_403", error.code)
        }
        assertEquals(1, remote.uploadCalls)
        assertEquals(0, remote.finalizeCalls)
    }

    private suspend fun insertProduct(index: Int, versionId: String?): Long {
        val barcode = "task138-$index"
        database.productDao().insert(
            Product(
                barcode = barcode,
                productName = "Product $index",
                primaryImageVersionId = versionId
            )
        )
        val id = requireNotNull(database.productDao().findByBarcode(barcode)).id
        database.productRemoteRefDao().insert(
            ProductRemoteRef(productId = id, remoteId = uuid(10_000 + index))
        )
        return id
    }

    private fun service(
        remote: FakeProductImageRemote,
        processor: ProductImageProcessor = ProductImageProcessor(),
        nowEpochMillis: () -> Long = System::currentTimeMillis
    ) = ProductImageService(
        context = context,
        database = database,
        api = remote,
        accountIdProvider = { accountId },
        selectedShopProvider = {
            SelectedShop(shopId, "T138", "Task 138", "owner", "active", true)
        },
        accessTokenProvider = { "fixture-token" },
        processor = processor,
        cache = cache,
        networkAvailable = { true },
        nowEpochMillis = nowEpochMillis
    )

    private fun preparedImage(): PreparedProductImage {
        val bytes = jpegBytes()
        val metadata = ProductImageMetadata(
            bytes = bytes.size,
            height = 32,
            sha256 = sha256(bytes),
            width = 32
        )
        val variant = PreparedProductImageVariant(bytes, metadata)
        return PreparedProductImage(variant, variant)
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 100, 170))
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        fun uuid(index: Int): String = "13800000-0000-4000-8000-${index.toString().padStart(12, '0')}"
    }
}

private class FakeProductImageRemote(
    private val accountScope: String,
    private val bytes: ByteArray,
    private val downloadDelayMs: Long = 0L,
    private val readGate: CompletableDeferred<Unit>? = null,
    private val readEntered: CompletableDeferred<Unit>? = null,
    private val downloadGate: CompletableDeferred<Unit>? = null,
    private val downloadEntered: CompletableDeferred<Unit>? = null
) : ProductImageRemoteGateway {
    override val isConfigured = true
    var readCalls = 0
    val readBatchSizes = mutableListOf<Int>()
    val downloadCalls = AtomicInteger()
    val maxConcurrentDownloads = AtomicInteger()
    private val activeDownloads = AtomicInteger()
    var uploadCalls = 0
    var finalizeCalls = 0
    var uploadGate: CompletableDeferred<Unit>? = null
    val uploadEntered = CompletableDeferred<Unit>()
    var expiresAt: String? = null
    val downloadFailureCodes = mutableListOf<String>()
    val uploadFailureCodes = mutableListOf<String>()

    override suspend fun readUrls(
        accessToken: String,
        body: ProductImageReadBody
    ): ProductImageReadResponse {
        readCalls += 1
        readBatchSizes += body.refs.size
        readEntered?.complete(Unit)
        readGate?.await()
        return ProductImageReadResponse(
            cacheScope = accountScope,
            ok = true,
            items = body.refs.map { ref ->
                ProductImageReadItemResponse(
                    expiresAt = expiresAt,
                    productId = ref.productId,
                    signedUrl = "fixture://${ref.productId}/${ref.versionId}/${ref.variant}",
                    status = "ready",
                    variant = ref.variant,
                    versionId = ref.versionId
                )
            }
        )
    }

    override suspend fun downloadSignedJpeg(
        signedUrl: String,
        variant: ProductImageVariant
    ): ByteArray {
        downloadCalls.incrementAndGet()
        val active = activeDownloads.incrementAndGet()
        maxConcurrentDownloads.updateAndGet { previous -> maxOf(previous, active) }
        return try {
            if (downloadFailureCodes.isNotEmpty()) {
                throw ProductImageException(downloadFailureCodes.removeAt(0))
            }
            downloadEntered?.complete(Unit)
            downloadGate?.await()
            if (downloadDelayMs > 0) delay(downloadDelayMs)
            bytes
        } finally {
            activeDownloads.decrementAndGet()
        }
    }

    override suspend fun createIntent(
        accessToken: String,
        body: ProductImageIntentBody
    ) = ProductImageIntentResponse(
        cacheScope = accountScope,
        mainUploadUrl = "fixture://main",
        ok = true,
        status = "upload_required",
        thumbUploadUrl = "fixture://thumb",
        versionId = "13800000-0000-4000-8000-000000009999"
    )

    override suspend fun putSignedJpeg(signedUrl: String, bytes: ByteArray) {
        uploadCalls += 1
        uploadEntered.complete(Unit)
        if (uploadFailureCodes.isNotEmpty()) {
            throw ProductImageException(uploadFailureCodes.removeAt(0))
        }
        uploadGate?.await()
    }

    override suspend fun finalizeImage(
        accessToken: String,
        body: ProductImageFinalizeBody
    ): ProductImageFinalizeResponse {
        finalizeCalls += 1
        return ProductImageFinalizeResponse(
            imageUpdatedAt = "now",
            ok = true,
            status = "finalized",
            versionId = body.versionId
        )
    }

    override suspend fun removeImage(
        accessToken: String,
        body: ProductImageRemoveBody
    ) = ProductImageRemoveResponse(ok = false)

    override fun close() = Unit
}

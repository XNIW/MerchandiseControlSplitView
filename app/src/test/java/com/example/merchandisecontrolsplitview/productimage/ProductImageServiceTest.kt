package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.BusinessDataScopeBinding
import com.example.merchandisecontrolsplitview.data.CatalogSyncStateTracker
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductRemoteRef
import com.example.merchandisecontrolsplitview.data.SelectedShop
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeChangedException
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeRuntimeGuard
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeState
import com.example.merchandisecontrolsplitview.data.Task126UnmanagedBusinessDataScopeRuntimeGuard
import com.example.merchandisecontrolsplitview.data.task126ActiveOwnerStoreScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
    fun `cache owns written bytes and returns defensive copies`() {
        val reference = ProductImageReference(
            accountScope = accountScope,
            shopId = shopId,
            productId = uuid(10_139),
            versionId = uuid(20_139),
            variant = ProductImageVariant.THUMB
        )
        val source = jpegBytes()
        val expected = source.copyOf()

        cache.write(reference, source)
        source.fill(0)
        val firstRead = requireNotNull(cache.read(reference))
        assertArrayEquals(expected, firstRead)

        firstRead.fill(0)
        assertArrayEquals(expected, cache.read(reference))

        cache.trimMemory()
        assertArrayEquals(expected, cache.read(reference))
    }

    @Test
    fun `local only product cannot start image preprocessing or remote mutation`() = runTest {
        val processor = mockk<ProductImageProcessor>()
        val remote = FakeProductImageRemote(accountScope, jpegBytes())
        val productId = insertProduct(index = 2, versionId = null, synced = false)
        val service = service(remote, processor)

        try {
            service.upload(productId, Uri.EMPTY)
            fail("Expected an unsynchronised product reference to be rejected")
        } catch (error: ProductImageException) {
            assertEquals("image_reference_invalid", error.code)
        }

        coVerify(exactly = 0) { processor.prepare(any(), any()) }
        assertEquals(0, remote.intentCalls)
        assertEquals(0, remote.uploadCalls)
        assertEquals(0, remote.finalizeCalls)
    }

    @Test
    fun `blocked business scope performs zero image network and preprocessing`() = runTest {
        val processor = mockk<ProductImageProcessor>()
        val remote = FakeProductImageRemote(accountScope, jpegBytes())
        val productId = insertProduct(index = 202, versionId = null)
        val service = service(remote, processor, businessScopeAllowed = false)

        assertFalse(service.canWriteNow())
        try {
            service.upload(productId, Uri.EMPTY)
            fail("Expected a blocked account-shop binding to reject the image mutation")
        } catch (error: ProductImageException) {
            assertEquals("image_account_changed", error.code)
        }
        try {
            service.remove(productId)
            fail("Expected a blocked account-shop binding to reject image removal")
        } catch (error: ProductImageException) {
            assertEquals("image_account_changed", error.code)
        }

        coVerify(exactly = 0) { processor.prepare(any(), any()) }
        assertEquals(0, remote.intentCalls)
        assertEquals(0, remote.uploadCalls)
        assertEquals(0, remote.finalizeCalls)
        assertEquals(0, remote.removeCalls)
    }

    @Test
    fun `scope discovery during create intent stops every later image mutation`() = runTest {
        val prepared = preparedImage()
        val processor = mockk<ProductImageProcessor>()
        coEvery { processor.prepare(any(), any()) } returns prepared
        val remote = FakeProductImageRemote(accountScope, prepared.thumb.bytes).apply {
            intentGate = CompletableDeferred()
            nonCancellableMutationGates = true
        }
        var currentShop: SelectedShop? = selectedShop()
        val tracker = readyBusinessScopeTracker(requireNotNull(currentShop))
        val productId = insertProduct(2_201, null)
        val service = service(
            remote = remote,
            processor = processor,
            selectedShopProvider = { currentShop },
            businessDataScopeRuntimeGuard = tracker
        )

        val mutation = async { service.upload(productId, Uri.EMPTY) }
        remote.intentEntered.await()
        currentShop = null
        tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
        requireNotNull(remote.intentGate).complete(Unit)

        assertBusinessScopeCancellation(runCatching { mutation.await() }.exceptionOrNull())
        assertEquals(1, remote.intentCalls)
        assertEquals(0, remote.uploadCalls)
        assertEquals(0, remote.finalizeCalls)
        assertNull(database.productDao().getById(productId)?.primaryImageVersionId)
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `139 write capability revoked during intent prevents upload and local publication`() = runTest {
        val prepared = preparedImage()
        val processor = mockk<ProductImageProcessor>()
        coEvery { processor.prepare(any(), any()) } returns prepared
        val remote = FakeProductImageRemote(accountScope, prepared.thumb.bytes).apply {
            intentGate = CompletableDeferred()
            nonCancellableMutationGates = true
        }
        var currentShop: SelectedShop? = selectedShop()
        val productId = insertProduct(2_205, null)
        val service = service(
            remote = remote,
            processor = processor,
            selectedShopProvider = { currentShop }
        )

        val mutation = async { runCatching { service.upload(productId, Uri.EMPTY) } }
        remote.intentEntered.await()
        currentShop = requireNotNull(currentShop).copy(canWrite = false)
        requireNotNull(remote.intentGate).complete(Unit)

        val error = mutation.await().exceptionOrNull()
        assertTrue(error is ProductImageException)
        assertEquals("image_account_changed", (error as ProductImageException).code)
        assertEquals(1, remote.intentCalls)
        assertEquals(0, remote.uploadCalls)
        assertEquals(0, remote.finalizeCalls)
        assertNull(database.productDao().getById(productId)?.primaryImageVersionId)
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `scope discovery during main upload stops thumb finalize and local mark`() = runTest {
        val prepared = preparedImage()
        val processor = mockk<ProductImageProcessor>()
        coEvery { processor.prepare(any(), any()) } returns prepared
        val remote = FakeProductImageRemote(accountScope, prepared.thumb.bytes).apply {
            uploadGate = CompletableDeferred()
            nonCancellableMutationGates = true
        }
        var currentShop: SelectedShop? = selectedShop()
        val tracker = readyBusinessScopeTracker(requireNotNull(currentShop))
        val productId = insertProduct(2_202, null)
        val service = service(
            remote = remote,
            processor = processor,
            selectedShopProvider = { currentShop },
            businessDataScopeRuntimeGuard = tracker
        )

        val mutation = async { service.upload(productId, Uri.EMPTY) }
        remote.uploadEntered.await()
        currentShop = null
        tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
        requireNotNull(remote.uploadGate).complete(Unit)

        assertBusinessScopeCancellation(runCatching { mutation.await() }.exceptionOrNull())
        assertEquals(1, remote.intentCalls)
        assertEquals(1, remote.uploadCalls)
        assertEquals(0, remote.finalizeCalls)
        assertNull(database.productDao().getById(productId)?.primaryImageVersionId)
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `scope transition during finalize waits then prevents stale local mark`() = runTest {
        val prepared = preparedImage()
        val processor = mockk<ProductImageProcessor>()
        coEvery { processor.prepare(any(), any()) } returns prepared
        val remote = FakeProductImageRemote(accountScope, prepared.thumb.bytes).apply {
            finalizeGate = CompletableDeferred()
            nonCancellableMutationGates = true
        }
        var currentShop: SelectedShop? = selectedShop()
        val originalShop = requireNotNull(currentShop)
        val tracker = readyBusinessScopeTracker(originalShop)
        val productId = insertProduct(2_203, null)
        val service = service(
            remote = remote,
            processor = processor,
            selectedShopProvider = { currentShop },
            businessDataScopeRuntimeGuard = tracker
        )

        val mutation = async { service.upload(productId, Uri.EMPTY) }
        remote.finalizeEntered.await()
        currentShop = null
        val transition = async {
            tracker.withBusinessDataScopeTransition {
                tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
            }
        }
        awaitBusinessScopeAdmissionClosed(tracker, originalShop)
        assertFalse(transition.isCompleted)
        requireNotNull(remote.finalizeGate).complete(Unit)

        assertBusinessScopeCancellation(runCatching { mutation.await() }.exceptionOrNull())
        transition.await()
        assertEquals(1, remote.intentCalls)
        assertEquals(2, remote.uploadCalls)
        assertEquals(1, remote.finalizeCalls)
        assertNull(database.productDao().getById(productId)?.primaryImageVersionId)
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `scope transition during remove waits then preserves local reference and cache`() = runTest {
        val initialVersionId = uuid(22_040)
        val remoteProductId = uuid(12_204)
        val remote = FakeProductImageRemote(accountScope, jpegBytes()).apply {
            removeGate = CompletableDeferred()
            nonCancellableMutationGates = true
        }
        var currentShop: SelectedShop? = selectedShop()
        val originalShop = requireNotNull(currentShop)
        val tracker = readyBusinessScopeTracker(originalShop)
        val productId = insertProduct(2_204, initialVersionId)
        val cachedReference = ProductImageReference(
            accountScope = accountScope,
            shopId = shopId,
            productId = remoteProductId,
            versionId = initialVersionId,
            variant = ProductImageVariant.THUMB
        )
        cache.write(cachedReference, jpegBytes())
        val service = service(
            remote = remote,
            selectedShopProvider = { currentShop },
            businessDataScopeRuntimeGuard = tracker
        )

        val mutation = async { service.remove(productId) }
        remote.removeEntered.await()
        currentShop = null
        val transition = async {
            tracker.withBusinessDataScopeTransition {
                tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
            }
        }
        awaitBusinessScopeAdmissionClosed(tracker, originalShop)
        assertFalse(transition.isCompleted)
        requireNotNull(remote.removeGate).complete(Unit)

        assertBusinessScopeCancellation(runCatching { mutation.await() }.exceptionOrNull())
        transition.await()
        assertEquals(1, remote.removeCalls)
        assertEquals(
            initialVersionId,
            database.productDao().getById(productId)?.primaryImageVersionId
        )
        assertTrue(cache.read(cachedReference)?.isNotEmpty() == true)
    }

    @Test
    fun `139 cold offline read uses owner verified Room binding and disk cache only`() = runTest {
        val remote = FakeProductImageRemote(accountScope, jpegBytes())
        val versionId = uuid(2_139)
        val productId = insertProduct(index = 139, versionId = versionId)
        val request = ProductImageLoadRequest(
            localProductId = productId,
            variant = ProductImageVariant.THUMB,
            expectedVersionId = versionId
        )
        val onlineService = service(remote)

        assertTrue(onlineService.loadBatch(listOf(request)).single().result is ProductImageLoadResult.Ready)
        database.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(
                task126ActiveOwnerStoreScope(accountId, selectedShop()),
                boundAtMs = 139L
            )
        )
        cache.trimMemory()
        val offlineService = service(
            remote = remote,
            selectedShop = null,
            networkAvailable = false,
            businessScopeAllowed = false,
            allowBoundCacheRead = true
        )

        val cached = offlineService.loadBatch(listOf(request)).single()

        assertEquals(ProductImageLoadSource.CACHE, (cached.result as ProductImageLoadResult.Ready).source)
        assertEquals(1, remote.readCalls)
        assertEquals(1, remote.downloadCalls.get())

        val mismatchedAccountService = service(
            remote = remote,
            selectedShop = null,
            networkAvailable = false,
            businessScopeAllowed = false,
            allowBoundCacheRead = true,
            providedAccountId = uuid(2_140)
        )
        val blocked = mismatchedAccountService.loadBatch(listOf(request)).single()

        assertEquals("image_account_changed", blocked.errorCode)
        assertEquals(1, remote.readCalls)
        assertEquals(1, remote.downloadCalls.get())
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
    fun `two hundred requests are deduplicated chunked to the V6 limit and bounded to four downloads`() =
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
            assertEquals(List(12) { 16 } + listOf(8), remote.readBatchSizes)
            assertEquals(200, remote.downloadCalls.get())
            assertTrue(remote.maxConcurrentDownloads.get() in 2..4)
        }

    @Test
    fun `read response missing integrity metadata is rejected before download or cache write`() = runTest {
        val remote = FakeProductImageRemote(accountScope, jpegBytes()).apply {
            readItemTransform = { item ->
                item.copy(metadata = item.metadata?.copy(sha256 = null))
            }
        }
        val versionId = uuid(2_139)
        val productId = insertProduct(2_139, versionId)
        val service = service(remote)

        val item = service.loadBatch(
            listOf(ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId))
        ).single()

        assertEquals("image_read_contract_invalid", item.errorCode)
        assertEquals(1, remote.readCalls)
        assertEquals(0, remote.downloadCalls.get())
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `download whose bytes differ from read metadata is never cached`() = runTest {
        val remote = FakeProductImageRemote(accountScope, jpegBytes()).apply {
            readItemTransform = { item ->
                item.copy(metadata = item.metadata?.copy(sha256 = "0".repeat(64)))
            }
        }
        val versionId = uuid(2_140)
        val productId = insertProduct(2_140, versionId)
        val service = service(remote)

        val item = service.loadBatch(
            listOf(ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId))
        ).single()

        assertEquals("image_download_invalid", item.errorCode)
        assertEquals(1, remote.readCalls)
        assertEquals(1, remote.downloadCalls.get())
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
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
    fun `cancelling first duplicate waiter does not cancel shared image producer`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val remote = FakeProductImageRemote(
            accountScope = accountScope,
            bytes = jpegBytes(),
            readGate = gate,
            readEntered = entered
        )
        val versionId = uuid(3_002)
        val productId = insertProduct(302, versionId)
        val request = ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId)
        val service = service(remote)

        val first = async { service.loadBatch(listOf(request)) }
        entered.await()
        val second = async { service.loadBatch(listOf(request)) }
        first.cancelAndJoin()
        gate.complete(Unit)

        assertTrue(second.await().single().result is ProductImageLoadResult.Ready)
        assertEquals(1, remote.readCalls)
        assertEquals(1, remote.downloadCalls.get())
    }

    @Test
    fun `mixed ready and not found response preserves ready item and returns absent`() = runTest {
        val bytes = jpegBytes()
        val missingRemoteId = uuid(10_304)
        val remote = FakeProductImageRemote(accountScope, bytes).apply {
            readItemTransform = { item ->
                if (item.productId == missingRemoteId) {
                    item.copy(
                        expiresAt = null,
                        metadata = null,
                        signedUrl = null,
                        status = "not_found"
                    )
                } else {
                    item
                }
            }
        }
        val readyVersionId = uuid(3_003)
        val missingVersionId = uuid(3_004)
        val readyProductId = insertProduct(303, readyVersionId)
        val missingProductId = insertProduct(304, missingVersionId)
        val service = service(remote)

        val items = service.loadBatch(
            listOf(
                ProductImageLoadRequest(
                    readyProductId,
                    ProductImageVariant.THUMB,
                    readyVersionId
                ),
                ProductImageLoadRequest(
                    missingProductId,
                    ProductImageVariant.THUMB,
                    missingVersionId
                )
            )
        )

        assertTrue(items[0].result is ProductImageLoadResult.Ready)
        assertEquals(ProductImageLoadResult.Absent, items[1].result)
        assertNull(items[1].errorCode)
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
        assertEquals("image_reference_invalid", item.errorCode)
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `139 scope transition cancels signed read before download and cache`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val remote = FakeProductImageRemote(
            accountScope = accountScope,
            bytes = jpegBytes(),
            readGate = gate,
            readEntered = entered
        ).apply { nonCancellableReadGates = true }
        var currentShop: SelectedShop? = selectedShop()
        val originalShop = requireNotNull(currentShop)
        val tracker = readyBusinessScopeTracker(originalShop)
        val versionId = uuid(5_101)
        val productId = insertProduct(5_101, versionId)
        val service = service(
            remote = remote,
            selectedShopProvider = { currentShop },
            businessDataScopeRuntimeGuard = tracker
        )

        val load = async {
            service.loadBatch(
                listOf(ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId))
            )
        }
        entered.await()
        currentShop = null
        val transition = async {
            tracker.withBusinessDataScopeTransition {
                tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
            }
        }
        awaitBusinessScopeAdmissionClosed(tracker, originalShop)
        assertFalse(transition.isCompleted)
        gate.complete(Unit)

        assertBusinessScopeCancellation(runCatching { load.await() }.exceptionOrNull())
        transition.await()
        assertEquals(1, remote.readCalls)
        assertEquals(0, remote.downloadCalls.get())
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun `139 scope transition joins stale download before it can write cache`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val remote = FakeProductImageRemote(
            accountScope = accountScope,
            bytes = jpegBytes(),
            downloadGate = gate,
            downloadEntered = entered
        ).apply { nonCancellableReadGates = true }
        var currentShop: SelectedShop? = selectedShop()
        val originalShop = requireNotNull(currentShop)
        val tracker = readyBusinessScopeTracker(originalShop)
        val versionId = uuid(5_102)
        val productId = insertProduct(5_102, versionId)
        val service = service(
            remote = remote,
            selectedShopProvider = { currentShop },
            businessDataScopeRuntimeGuard = tracker
        )

        val load = async {
            service.loadBatch(
                listOf(ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId))
            )
        }
        entered.await()
        currentShop = null
        val transition = async {
            tracker.withBusinessDataScopeTransition {
                tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
            }
        }
        awaitBusinessScopeAdmissionClosed(tracker, originalShop)
        assertFalse(transition.isCompleted)
        gate.complete(Unit)

        assertBusinessScopeCancellation(runCatching { load.await() }.exceptionOrNull())
        transition.await()
        assertEquals(1, remote.downloadCalls.get())
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
            service.upload(productId, Uri.EMPTY, onProgress = { phases += it })
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

        val result = service.upload(productId, Uri.EMPTY, onProgress = { phases += it })

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
            downloadFailureStatuses += 403
            downloadFailureStatuses += 403
        }
        val versionId = uuid(7_201)
        val productId = insertProduct(721, versionId)
        val request = ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId)
        val service = service(remote, nowEpochMillis = { 30_000L })

        val item = service.loadBatch(listOf(request)).single()

        assertEquals("image_request_failed", item.errorCode)
        assertEquals(2, remote.readCalls)
        assertEquals(2, remote.downloadCalls.get())

        val recovered = service.loadBatch(listOf(request)).single()

        assertTrue(recovered.result is ProductImageLoadResult.Ready)
        assertEquals(3, remote.readCalls)
        assertEquals(3, remote.downloadCalls.get())
    }

    @Test
    fun `forbidden refresh rejects changed integrity metadata before second download`() = runTest {
        var readItems = 0
        val remote = FakeProductImageRemote(accountScope, jpegBytes()).apply {
            expiresAt = Instant.ofEpochMilli(200_000L).toString()
            downloadFailureStatuses += 403
            readItemTransform = { item ->
                readItems += 1
                if (readItems == 2) {
                    item.copy(metadata = item.metadata?.copy(sha256 = "0".repeat(64)))
                } else {
                    item
                }
            }
        }
        val versionId = uuid(7_202)
        val productId = insertProduct(722, versionId)
        val request = ProductImageLoadRequest(productId, ProductImageVariant.THUMB, versionId)
        val service = service(remote, nowEpochMillis = { 30_000L })

        val item = service.loadBatch(listOf(request)).single()

        assertEquals("image_read_contract_invalid", item.errorCode)
        assertEquals(2, remote.readCalls)
        assertEquals(1, remote.downloadCalls.get())
        assertFalse(cacheRoot.walkTopDown().any { it.isFile })
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
        assertEquals(16, remote.readCalls)

        val firstRequest = initialRequests.first()
        cache.purgeProduct(accountScope, shopId, uuid(10_801))
        assertTrue(
            service.loadBatch(listOf(firstRequest)).single().result is ProductImageLoadResult.Ready
        )
        assertEquals(16, remote.readCalls)

        val overflowVersion = uuid(30_057)
        val overflowRequest = ProductImageLoadRequest(
            localProductId = insertProduct(1_057, overflowVersion),
            variant = ProductImageVariant.THUMB,
            expectedVersionId = overflowVersion
        )
        service.loadBatch(listOf(overflowRequest))
        assertEquals(17, remote.readCalls)

        val secondRequest = initialRequests[1]
        cache.purgeProduct(accountScope, shopId, uuid(10_801))
        cache.purgeProduct(accountScope, shopId, uuid(10_802))
        service.loadBatch(listOf(firstRequest))
        assertEquals(17, remote.readCalls)
        service.loadBatch(listOf(secondRequest))
        assertEquals(18, remote.readCalls)
    }

    @Test
    fun `transient object upload is retried exactly once`() = runTest {
        val prepared = preparedImage()
        val processor = mockk<ProductImageProcessor>()
        coEvery { processor.prepare(any(), any()) } returns prepared
        val remote = FakeProductImageRemote(accountScope, prepared.thumb.bytes).apply {
            uploadFailureStatuses += 503
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
            uploadFailureStatuses += 403
        }
        val productId = insertProduct(741, null)
        val service = service(remote, processor)

        try {
            service.upload(productId, Uri.EMPTY)
            fail("Expected permanent upload failure")
        } catch (error: ProductImageException) {
            assertEquals("image_upload_failed", error.code)
            assertEquals(403, error.httpStatus)
        }
        assertEquals(1, remote.uploadCalls)
        assertEquals(0, remote.finalizeCalls)
    }

    @Test
    fun `storefront adoption uses finalized operational version without linking publication`() = runTest {
        val sourceVersionId = uuid(30_901)
        val imagePublicationId = uuid(40_901)
        val publicationId = uuid(50_901)
        val productId = insertProduct(901, sourceVersionId)
        val remote = FakeProductImageRemote(accountScope, jpegBytes()).apply {
            storefrontAdoptResponse = StorefrontImageAdoptResponse(
                imagePublicationId = imagePublicationId,
                ok = true,
                status = "finalized"
            )
        }
        val service = service(remote)

        val adopted = service.adoptForStorefront(productId, publicationId)

        assertEquals(imagePublicationId, adopted)
        assertEquals(1, remote.storefrontAdoptCalls)
        assertEquals(publicationId, remote.lastStorefrontAdoptBody?.publicationId)
        assertEquals(sourceVersionId, remote.lastStorefrontAdoptBody?.sourceImageVersionId)
        assertEquals(shopId, remote.lastStorefrontAdoptBody?.shopId)
        assertEquals(sourceVersionId, database.productDao().getById(productId)?.primaryImageVersionId)
    }

    private suspend fun insertProduct(
        index: Int,
        versionId: String?,
        synced: Boolean = true
    ): Long {
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
            ProductRemoteRef(
                productId = id,
                remoteId = uuid(10_000 + index),
                lastRemoteAppliedAt = 1L.takeIf { synced }
            )
        )
        return id
    }

    private fun service(
        remote: FakeProductImageRemote,
        processor: ProductImageProcessor = ProductImageProcessor(),
        nowEpochMillis: () -> Long = System::currentTimeMillis,
        businessScopeAllowed: Boolean = true,
        selectedShop: SelectedShop? = selectedShop(),
        selectedShopProvider: () -> SelectedShop? = { selectedShop },
        businessDataScopeRuntimeGuard: Task126BusinessDataScopeRuntimeGuard =
            Task126UnmanagedBusinessDataScopeRuntimeGuard,
        networkAvailable: Boolean = true,
        allowBoundCacheRead: Boolean = false,
        providedAccountId: String = accountId
    ) = ProductImageService(
        context = context,
        database = database,
        api = remote,
        accountIdProvider = { providedAccountId },
        selectedShopProvider = selectedShopProvider,
        accessTokenProvider = { "fixture-token" },
        businessDataScopeAllowed = { _, _ -> businessScopeAllowed },
        businessDataScopeRuntimeGuard = businessDataScopeRuntimeGuard,
        allowBoundCacheRead = { allowBoundCacheRead },
        processor = processor,
        cache = cache,
        networkAvailable = { networkAvailable },
        nowEpochMillis = nowEpochMillis
    )

    private fun readyBusinessScopeTracker(shop: SelectedShop): CatalogSyncStateTracker =
        CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(
                task126ActiveOwnerStoreScope(accountId, shop)
            )
        )

    private suspend fun awaitBusinessScopeAdmissionClosed(
        tracker: CatalogSyncStateTracker,
        shop: SelectedShop
    ) {
        repeat(100) {
            if (!tracker.allowsBusinessDataScope(accountId, shop)) return
            yield()
        }
        fail("Expected business-scope transition to close new admissions")
    }

    private fun assertBusinessScopeCancellation(error: Throwable?) {
        assertTrue(
            "Expected scope invalidation cancellation, got $error",
            error is Task126BusinessDataScopeChangedException || error is CancellationException
        )
    }

    private fun selectedShop() =
        SelectedShop(shopId, "T138", "Task 138", "owner", "active", true)

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

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

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
    var intentCalls = 0
    var finalizeCalls = 0
    var removeCalls = 0
    var storefrontAdoptCalls = 0
    var storefrontAdoptResponse = StorefrontImageAdoptResponse()
    var lastStorefrontAdoptBody: StorefrontImageAdoptBody? = null
    var intentGate: CompletableDeferred<Unit>? = null
    val intentEntered = CompletableDeferred<Unit>()
    var uploadGate: CompletableDeferred<Unit>? = null
    val uploadEntered = CompletableDeferred<Unit>()
    var finalizeGate: CompletableDeferred<Unit>? = null
    val finalizeEntered = CompletableDeferred<Unit>()
    var removeGate: CompletableDeferred<Unit>? = null
    val removeEntered = CompletableDeferred<Unit>()
    var nonCancellableMutationGates = false
    var nonCancellableReadGates = false
    var expiresAt: String? = null
    var readItemTransform: ((ProductImageReadItemResponse) -> ProductImageReadItemResponse)? = null
    val downloadFailureStatuses = mutableListOf<Int>()
    val uploadFailureStatuses = mutableListOf<Int>()

    override suspend fun readUrls(
        accessToken: String,
        body: ProductImageReadBody
    ): ProductImageReadResponse {
        readCalls += 1
        readBatchSizes += body.refs.size
        readEntered?.complete(Unit)
        awaitReadGate(readGate)
        return ProductImageReadResponse(
            cacheScope = accountScope,
            ok = true,
            items = body.refs.map { ref ->
                val item = ProductImageReadItemResponse(
                    expiresAt = expiresAt,
                    metadata = ProductImageReadMetadataResponse(
                        bytes = bytes.size.toLong(),
                        height = 32,
                        mimeType = "image/jpeg",
                        sha256 = sha256(bytes),
                        width = 32
                    ),
                    productId = ref.productId,
                    signedUrl = "fixture://${ref.productId}/${ref.versionId}/${ref.variant}",
                    status = "ready",
                    variant = ref.variant,
                    versionId = ref.versionId
                )
                readItemTransform?.invoke(item) ?: item
            }
        )
    }

    override suspend fun downloadSignedJpeg(
        signedUrl: String,
        expectedReference: ProductImageReference
    ): ByteArray {
        downloadCalls.incrementAndGet()
        val active = activeDownloads.incrementAndGet()
        maxConcurrentDownloads.updateAndGet { previous -> maxOf(previous, active) }
        return try {
            if (downloadFailureStatuses.isNotEmpty()) {
                throw ProductImageException(
                    "image_request_failed",
                    downloadFailureStatuses.removeAt(0)
                )
            }
            downloadEntered?.complete(Unit)
            awaitReadGate(downloadGate)
            if (downloadDelayMs > 0) delay(downloadDelayMs)
            bytes
        } finally {
            activeDownloads.decrementAndGet()
        }
    }

    override suspend fun createIntent(
        accessToken: String,
        body: ProductImageIntentBody
    ): ProductImageIntentResponse {
        intentCalls += 1
        intentEntered.complete(Unit)
        awaitMutationGate(intentGate)
        return ProductImageIntentResponse(
            cacheScope = accountScope,
            mainUploadUrl = "fixture://main",
            ok = true,
            status = "upload_required",
            thumbUploadUrl = "fixture://thumb",
            versionId = "13800000-0000-4000-8000-000000009999"
        )
    }

    override suspend fun putSignedJpeg(
        signedUrl: String,
        bytes: ByteArray,
        expectedReference: ProductImageReference
    ) {
        uploadCalls += 1
        uploadEntered.complete(Unit)
        if (uploadFailureStatuses.isNotEmpty()) {
            throw ProductImageException(
                "image_upload_failed",
                uploadFailureStatuses.removeAt(0)
            )
        }
        awaitMutationGate(uploadGate)
    }

    override suspend fun finalizeImage(
        accessToken: String,
        body: ProductImageFinalizeBody
    ): ProductImageFinalizeResponse {
        finalizeCalls += 1
        finalizeEntered.complete(Unit)
        awaitMutationGate(finalizeGate)
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
    ): ProductImageRemoveResponse {
        removeCalls += 1
        removeEntered.complete(Unit)
        awaitMutationGate(removeGate)
        return ProductImageRemoveResponse(
            currentImageVersionId = null,
            imageUpdatedAt = "now",
            ok = true,
            operation = "remove",
            productId = body.productId,
            shopId = body.shopId,
            status = "removed",
            versionId = body.expectedVersionId
        )
    }

    override suspend fun adoptForStorefront(
        accessToken: String,
        body: StorefrontImageAdoptBody
    ): StorefrontImageAdoptResponse {
        storefrontAdoptCalls += 1
        lastStorefrontAdoptBody = body
        return storefrontAdoptResponse
    }

    private suspend fun awaitMutationGate(gate: CompletableDeferred<Unit>?) {
        if (gate == null) return
        if (nonCancellableMutationGates) {
            withContext(NonCancellable) { gate.await() }
        } else {
            gate.await()
        }
    }

    private suspend fun awaitReadGate(gate: CompletableDeferred<Unit>?) {
        if (gate == null) return
        if (nonCancellableReadGates) {
            withContext(NonCancellable) { gate.await() }
        } else {
            gate.await()
        }
    }

    override fun close() = Unit
}

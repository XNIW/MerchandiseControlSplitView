package com.example.merchandisecontrolsplitview.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Process
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.productimage.ProductImageCache
import com.example.merchandisecontrolsplitview.productimage.ProductImageFinalizeBody
import com.example.merchandisecontrolsplitview.productimage.ProductImageFinalizeResponse
import com.example.merchandisecontrolsplitview.productimage.ProductImageIntentBody
import com.example.merchandisecontrolsplitview.productimage.ProductImageIntentResponse
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadRequest
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadResult
import com.example.merchandisecontrolsplitview.productimage.ProductImageProcessor
import com.example.merchandisecontrolsplitview.productimage.ProductImageReadBody
import com.example.merchandisecontrolsplitview.productimage.ProductImageReadItemResponse
import com.example.merchandisecontrolsplitview.productimage.ProductImageReadMetadataResponse
import com.example.merchandisecontrolsplitview.productimage.ProductImageReadResponse
import com.example.merchandisecontrolsplitview.productimage.ProductImageReference
import com.example.merchandisecontrolsplitview.productimage.ProductImageRemoteGateway
import com.example.merchandisecontrolsplitview.productimage.ProductImageRemoveBody
import com.example.merchandisecontrolsplitview.productimage.ProductImageRemoveResponse
import com.example.merchandisecontrolsplitview.productimage.ProductImageService
import com.example.merchandisecontrolsplitview.productimage.ProductImageVariant
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime proof for sec-mobile-prebound-resource-003.
 *
 * The tests run in the actual instrumentation process and exercise the
 * production scope gate, Room transaction boundary and Product Image cache.
 * The two relaunch methods are intentionally invoked by the external harness
 * in separate `am instrument` processes.
 */
@RunWith(AndroidJUnit4::class)
class Task139PreboundResourceRuntimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun staleCompletionAndPreboundReuseNeverReachRoomOrFileSinkAcross64Flights(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(scope(OWNER_A, SHOP_A))
        )
        val sinkRoot = File(context.cacheDir, "task139-prebound-64-${System.nanoTime()}")
        val publishFile = File(sinkRoot, "published.txt")
        val terminalReceipt = File(sinkRoot, "terminal-receipt.txt")
        val noWorkMarker = File(sinkRoot, "no-work.txt")
        val releaseOldFlights = CompletableDeferred<Unit>()
        val allOldFlightsStarted = CompletableDeferred<Unit>()
        val startedCount = AtomicInteger()
        val stalePublishes = AtomicInteger()
        val retryLoops = AtomicInteger()

        try {
            assertTrue(sinkRoot.mkdirs())
            coroutineScope {
                val oldFlights = (0 until CONCURRENT_FLIGHTS).map { index ->
                    async(Dispatchers.Default) {
                        runCatching {
                            tracker.withBusinessDataScopeFlight(
                                OWNER_A,
                                selectedShop(SHOP_A)
                            ) {
                                if (startedCount.incrementAndGet() == CONCURRENT_FLIGHTS) {
                                    allOldFlightsStarted.complete(Unit)
                                }
                                // Models a transport that ignores cancellation until the
                                // old response has actually completed.
                                withContext(NonCancellable) { releaseOldFlights.await() }
                                tracker.requireCurrentBusinessDataScope()
                                database.withTransaction {
                                    tracker.requireCurrentBusinessDataScope()
                                    database.productDao().insert(
                                        Product(
                                            barcode = "prebound-resource-$index",
                                            productName = "stale scope A"
                                        )
                                    )
                                    database.syncEventWatermarkDao().upsert(
                                        SyncEventWatermark(
                                            OWNER_A,
                                            "shop:$SHOP_A",
                                            index.toLong() + 1L
                                        )
                                    )
                                    tracker.requireCurrentBusinessDataScope()
                                }
                                synchronized(publishFile) {
                                    publishFile.appendText("A:G1:$index\n")
                                }
                                stalePublishes.incrementAndGet()
                            }
                        }.exceptionOrNull()
                    }
                }
                withTimeout(TEST_TIMEOUT_MS) { allOldFlightsStarted.await() }

                val transition = async(Dispatchers.Default) {
                    tracker.withBusinessDataScopeTransition {
                        tracker.updateBusinessDataScopeState(
                            Task126BusinessDataScopeState.ready(scope(OWNER_B, SHOP_B))
                        )
                    }
                }
                withTimeout(TEST_TIMEOUT_MS) {
                    while (tracker.allowsBusinessDataScope(OWNER_A, selectedShop(SHOP_A))) {
                        delay(5L)
                    }
                }
                assertFalse("transition must await the real stale completion", transition.isCompleted)

                val staleAdmission = runCatching {
                    tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {
                        retryLoops.incrementAndGet()
                    }
                }.exceptionOrNull()
                assertTrue(staleAdmission is Task126BusinessDataScopeChangedException)

                releaseOldFlights.complete(Unit)
                val failures = withTimeout(TEST_TIMEOUT_MS) { oldFlights.awaitAll() }
                withTimeout(TEST_TIMEOUT_MS) { transition.await() }

                assertEquals(CONCURRENT_FLIGHTS, failures.size)
                assertTrue(failures.all { it is Task126BusinessDataScopeChangedException })
                assertEquals(0, stalePublishes.get())
                assertEquals(0, retryLoops.get())
                assertEquals(0, database.productDao().count())
                assertNull(database.syncEventWatermarkDao().get(OWNER_A, "shop:$SHOP_A"))
                assertFalse(publishFile.exists())
                assertFalse(terminalReceipt.exists())
                assertFalse(noWorkMarker.exists())

                // The same logical identity can now be bound to B/G2 without an
                // old completion taking over its final Room/file sink.
                tracker.withBusinessDataScopeFlight(OWNER_B, selectedShop(SHOP_B)) {
                    database.withTransaction {
                        tracker.requireCurrentBusinessDataScope()
                        database.productDao().insert(
                            Product(
                                barcode = PREBOUND_LOGICAL_ID,
                                productName = "current scope B"
                            )
                        )
                        database.syncEventWatermarkDao().upsert(
                            SyncEventWatermark(OWNER_B, "shop:$SHOP_B", 7L)
                        )
                        tracker.requireCurrentBusinessDataScope()
                    }
                    publishFile.writeText("B:G2:$PREBOUND_LOGICAL_ID\n")
                }
            }

            assertEquals(1, database.productDao().count())
            assertEquals("current scope B", database.productDao().findByBarcode(PREBOUND_LOGICAL_ID)?.productName)
            assertEquals(7L, database.syncEventWatermarkDao().get(OWNER_B, "shop:$SHOP_B")?.lastSyncEventId)
            assertNull(database.syncEventWatermarkDao().get(OWNER_A, "shop:$SHOP_A"))
            assertEquals("B:G2:$PREBOUND_LOGICAL_ID\n", publishFile.readText())
            assertFalse(terminalReceipt.exists())
            assertFalse(noWorkMarker.exists())
        } finally {
            releaseOldFlights.complete(Unit)
            database.close()
            sinkRoot.deleteRecursively()
        }
    }

    @Test
    fun lateV1ImageNeverEntersMemoryDiskOrUiAfterV2And100ConsumersSingleFlight(): Unit = runBlocking {
        val databaseName = "task139-image-runtime-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        val cacheRoot = File(context.noBackupFilesDir, "task139-image-runtime-${System.nanoTime()}")
        val cache = ProductImageCache(cacheRoot, Unit)
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(scope(OWNER_A, SHOP_A))
        )
        val account = AtomicReference(OWNER_A)
        val shop = AtomicReference(selectedShop(SHOP_A))
        val jpeg = validJpeg()
        val gateway = DeferredImageGateway(
            bytes = jpeg,
            accountScope = cache.accountScope(OWNER_A)
        )
        val service = ProductImageService(
            context = context,
            database = database,
            api = gateway,
            accountIdProvider = account::get,
            selectedShopProvider = shop::get,
            accessTokenProvider = { "runtime-fixture-token" },
            businessDataScopeAllowed = { owner, selected ->
                tracker.allowsBusinessDataScope(owner, selected)
            },
            businessDataScopeRuntimeGuard = tracker,
            cache = cache,
            networkAvailable = { true }
        )

        try {
            database.productDao().insert(
                Product(
                    barcode = PREBOUND_LOGICAL_ID,
                    productName = "Image identity reused",
                    primaryImageVersionId = VERSION_V1
                )
            )
            val localId = requireNotNull(database.productDao().findByBarcode(PREBOUND_LOGICAL_ID)).id
            database.productRemoteRefDao().insert(
                ProductRemoteRef(
                    productId = localId,
                    remoteId = REMOTE_PRODUCT,
                    lastRemoteAppliedAt = 1L
                )
            )

            val v1Request = ProductImageLoadRequest(
                localProductId = localId,
                variant = ProductImageVariant.THUMB,
                expectedVersionId = VERSION_V1
            )
            val lateV1 = async(Dispatchers.Default) {
                service.loadBatch(listOf(v1Request)).single()
            }
            withTimeout(TEST_TIMEOUT_MS) { gateway.v1DownloadStarted.await() }

            database.productDao().updateRemoteImageReference(
                productId = localId,
                versionId = VERSION_V2,
                updatedAt = "2026-07-23T12:00:00.000000Z"
            )
            gateway.releaseV1Download.complete(Unit)
            val staleResult = withTimeout(TEST_TIMEOUT_MS) { lateV1.await() }

            assertEquals("image_reference_invalid", staleResult.errorCode)
            assertNull(staleResult.result)
            val accountScope = cache.accountScope(OWNER_A)
            val v1Reference = reference(accountScope, SHOP_A, VERSION_V1)
            val v2Reference = reference(accountScope, SHOP_A, VERSION_V2)
            assertNull(cache.read(v1Reference))
            assertEquals(0, cache.snapshot().memoryEntries)
            assertEquals(0, cache.snapshot().diskEntries)

            val v2Request = v1Request.copy(expectedVersionId = VERSION_V2)
            val v2Ready = service.loadBatch(listOf(v2Request)).single()
            assertNull(v2Ready.errorCode)
            assertTrue(v2Ready.result is ProductImageLoadResult.Ready)
            assertArrayEquals(jpeg, cache.read(v2Reference))
            assertNull(cache.read(v1Reference))

            database.productDao().updateRemoteImageReference(
                productId = localId,
                versionId = VERSION_V3,
                updatedAt = "2026-07-23T12:00:01.000000Z"
            )
            val v3Request = v1Request.copy(expectedVersionId = VERSION_V3)
            val consumers = coroutineScope {
                (0 until SAME_KEY_CONSUMERS).map {
                    async(Dispatchers.Default) {
                        service.loadBatch(listOf(v3Request)).single()
                    }
                }.awaitAll()
            }
            assertTrue(consumers.all { it.errorCode == null && it.result is ProductImageLoadResult.Ready })
            assertEquals(1, gateway.downloadCount(VERSION_V1))
            assertEquals(1, gateway.downloadCount(VERSION_V2))
            assertEquals(1, gateway.downloadCount(VERSION_V3))
            assertEquals(1, gateway.readCount(VERSION_V3))
            assertNull(cache.read(v1Reference))
            assertNull(cache.read(v2Reference))
            assertNotNull(cache.read(reference(accountScope, SHOP_A, VERSION_V3)))
            assertEquals(1, cache.snapshot().memoryEntries)
            assertEquals(1, cache.snapshot().diskEntries)
        } finally {
            gateway.releaseV1Download.complete(Unit)
            service.close()
            database.close()
            context.deleteDatabase(databaseName)
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun prepareRelaunchFixtureUnderScopeA(): Unit = runBlocking {
        requireExternalRelaunchHarness()
        context.deleteDatabase(RELAUNCH_DATABASE)
        relaunchCacheRoot().deleteRecursively()
        relaunchStateRoot().deleteRecursively()
        assertTrue(relaunchStateRoot().mkdirs())

        val database = openRelaunchDatabase()
        val cache = ProductImageCache(relaunchCacheRoot(), Unit)
        val oldScope = scope(OWNER_A, SHOP_A)
        val jpeg = validJpeg()
        try {
            database.businessDataScopeBindingDao().upsert(
                BusinessDataScopeBinding.from(oldScope, 139L)
            )
            database.syncEventDeviceStateDao().insert(
                SyncEventDeviceState(deviceId = DEVICE_D1, createdAtMs = 139L)
            )
            database.productDao().insert(
                Product(
                    barcode = PREBOUND_LOGICAL_ID,
                    productName = "G1 remains readable",
                    primaryImageVersionId = VERSION_V1
                )
            )
            database.syncEventWatermarkDao().upsert(
                SyncEventWatermark(OWNER_A, "shop:$SHOP_A", 41L)
            )
            database.syncRecoveryJournalDao().upsert(
                SyncRecoveryJournal(
                    ownerHash = oldScope.ownerHash,
                    storeScope = oldScope.storeId,
                    shopId = SHOP_A,
                    deviceId = DEVICE_D1,
                    authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
                    runId = GENERATION_G1,
                    phase = SyncRecoveryJournalPhases.STAGING,
                    reason = "runtime_prebound_relaunch",
                    blockingEventId = 41L,
                    attemptCount = 1,
                    createdAtMs = 139L,
                    updatedAtMs = 140L,
                    nextRetryAtMs = 141L,
                    stagingDatabaseName = "sync_recovery_stage_$GENERATION_G1.db"
                )
            )
            cache.write(
                reference(cache.accountScope(OWNER_A), SHOP_A, VERSION_V1),
                jpeg
            )
            File(relaunchStateRoot(), "pending-g1").writeText("A|$SHOP_A|$DEVICE_D1|$GENERATION_G1")
            relaunchPreferences().edit()
                .putInt(PREF_PREPARE_PID, Process.myPid())
                .commit()
        } finally {
            database.close()
        }
    }

    @Test
    fun verifyRelaunchRejectsScopeAGenerationUnderScopeB(): Unit = runBlocking {
        requireExternalRelaunchHarness()
        val preparePid = relaunchPreferences().getInt(PREF_PREPARE_PID, -1)
        assertTrue("prepare process marker missing", preparePid > 0)
        assertNotEquals("verification must run after a real process restart", preparePid, Process.myPid())

        val database = openRelaunchDatabase()
        val cache = ProductImageCache(relaunchCacheRoot(), Unit)
        try {
            val state = DefaultInventoryRepository(database)
                .resolveBusinessDataScope(scope(OWNER_B, SHOP_B))

            assertEquals(Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH, state.status)
            assertEquals(scope(OWNER_A, SHOP_A).ownerHash, database.businessDataScopeBindingDao().get()?.ownerHash)
            assertEquals("G1 remains readable", database.productDao().findByBarcode(PREBOUND_LOGICAL_ID)?.productName)
            assertEquals(41L, database.syncEventWatermarkDao().get(OWNER_A, "shop:$SHOP_A")?.lastSyncEventId)
            assertNull(database.syncEventWatermarkDao().get(OWNER_B, "shop:$SHOP_B"))
            val journal = database.syncRecoveryJournalDao().get()
            assertEquals(GENERATION_G1, journal?.runId)
            assertEquals(scope(OWNER_A, SHOP_A).ownerHash, journal?.ownerHash)
            assertNull(database.syncRecoveryJournalDao().getForScope(scope(OWNER_B, SHOP_B).ownerHash, "shop:$SHOP_B"))

            val oldReference = reference(cache.accountScope(OWNER_A), SHOP_A, VERSION_V1)
            val conflictingNewReference = reference(cache.accountScope(OWNER_B), SHOP_B, VERSION_V1)
            assertNotNull(cache.read(oldReference))
            assertNull(cache.read(conflictingNewReference))
            assertTrue(File(relaunchStateRoot(), "pending-g1").isFile)
            assertFalse(File(relaunchStateRoot(), "published-g1").exists())
            assertFalse(File(relaunchStateRoot(), "terminal-receipt-g1").exists())
            assertFalse(File(relaunchStateRoot(), "no-work-g1").exists())
        } finally {
            database.close()
            context.deleteDatabase(RELAUNCH_DATABASE)
            relaunchCacheRoot().deleteRecursively()
            relaunchStateRoot().deleteRecursively()
            relaunchPreferences().edit().clear().commit()
        }
    }

    private fun openRelaunchDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, RELAUNCH_DATABASE)
            .allowMainThreadQueries()
            .build()

    private fun relaunchCacheRoot(): File =
        File(context.noBackupFilesDir, "task139-prebound-relaunch-cache")

    private fun relaunchStateRoot(): File =
        File(context.filesDir, "task139-prebound-relaunch-state")

    private fun relaunchPreferences() =
        context.getSharedPreferences("task139-prebound-runtime", Context.MODE_PRIVATE)

    private fun requireExternalRelaunchHarness() {
        val selectedTest = InstrumentationRegistry.getArguments().getString("class")
        assumeTrue(
            "TASK-139 relaunch proof requires the external single-method ADB harness",
            selectedTest?.startsWith("${javaClass.name}#") == true
        )
    }

    private fun scope(ownerUserId: String, shopId: String): Task126OwnerStoreScope =
        Task126OwnerStoreScope(
            ownerHash = task126OwnerHash(ownerUserId),
            storeId = "shop:$shopId",
            localStoreId = null
        )

    private fun selectedShop(shopId: String): SelectedShop =
        SelectedShop(
            shopId = shopId,
            code = shopId,
            name = shopId,
            role = "owner",
            status = "active",
            canWrite = true
        )

    private fun reference(
        accountScope: String,
        shopId: String,
        versionId: String
    ): ProductImageReference =
        ProductImageReference(
            accountScope = accountScope,
            shopId = shopId,
            productId = REMOTE_PRODUCT,
            versionId = versionId,
            variant = ProductImageVariant.THUMB
        )

    private fun validJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            eraseColor(Color.rgb(20, 80, 170))
        }
        return try {
            ProductImageProcessor().prepareBitmap(bitmap).thumb.bytes
        } finally {
            bitmap.recycle()
        }
    }

    private class DeferredImageGateway(
        private val bytes: ByteArray,
        private val accountScope: String
    ) : ProductImageRemoteGateway {
        override val isConfigured: Boolean = true
        private val imageBounds = BitmapFactory.Options().also { options ->
            options.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        val v1DownloadStarted = CompletableDeferred<Unit>()
        val releaseV1Download = CompletableDeferred<Unit>()
        private val reads = ConcurrentHashMap<String, AtomicInteger>()
        private val downloads = ConcurrentHashMap<String, AtomicInteger>()

        override suspend fun readUrls(
            accessToken: String,
            body: ProductImageReadBody
        ): ProductImageReadResponse {
            val reference = body.refs.single()
            reads.computeIfAbsent(reference.versionId) { AtomicInteger() }.incrementAndGet()
            return ProductImageReadResponse(
                ok = true,
                cacheScope = accountScope,
                items = listOf(
                    ProductImageReadItemResponse(
                        productId = reference.productId,
                        versionId = reference.versionId,
                        variant = reference.variant,
                        status = "ready",
                        signedUrl = "runtime://${reference.versionId}/${reference.variant}",
                        expiresAt = "2026-07-23T13:00:00Z",
                        metadata = ProductImageReadMetadataResponse(
                            sha256 = sha256(bytes),
                            bytes = bytes.size.toLong(),
                            width = imageBounds.outWidth,
                            height = imageBounds.outHeight,
                            mimeType = "image/jpeg"
                        )
                    )
                )
            )
        }

        override suspend fun downloadSignedJpeg(
            signedUrl: String,
            expectedReference: ProductImageReference
        ): ByteArray {
            downloads.computeIfAbsent(expectedReference.versionId) { AtomicInteger() }
                .incrementAndGet()
            if (expectedReference.versionId == VERSION_V1) {
                v1DownloadStarted.complete(Unit)
                withContext(NonCancellable) { releaseV1Download.await() }
            }
            return bytes
        }

        fun readCount(versionId: String): Int = reads[versionId]?.get() ?: 0

        fun downloadCount(versionId: String): Int = downloads[versionId]?.get() ?: 0

        override suspend fun createIntent(
            accessToken: String,
            body: ProductImageIntentBody
        ): ProductImageIntentResponse = error("upload not used by runtime read proof")

        override suspend fun finalizeImage(
            accessToken: String,
            body: ProductImageFinalizeBody
        ): ProductImageFinalizeResponse = error("finalize not used by runtime read proof")

        override suspend fun removeImage(
            accessToken: String,
            body: ProductImageRemoveBody
        ): ProductImageRemoveResponse = error("remove not used by runtime read proof")

        override suspend fun putSignedJpeg(
            signedUrl: String,
            bytes: ByteArray,
            expectedReference: ProductImageReference
        ) = error("upload not used by runtime read proof")

        override fun close() = Unit

        private fun sha256(value: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val CONCURRENT_FLIGHTS = 64
        const val SAME_KEY_CONSUMERS = 100
        const val TEST_TIMEOUT_MS = 15_000L
        const val PREBOUND_LOGICAL_ID = "task139-prebound-resource"
        const val OWNER_A = "10000000-0000-4000-8000-000000000139"
        const val OWNER_B = "20000000-0000-4000-8000-000000000139"
        const val SHOP_A = "30000000-0000-4000-8000-000000000139"
        const val SHOP_B = "40000000-0000-4000-8000-000000000139"
        const val REMOTE_PRODUCT = "50000000-0000-4000-8000-000000000139"
        const val VERSION_V1 = "60000000-0000-4000-8000-000000000139"
        const val VERSION_V2 = "70000000-0000-4000-8000-000000000139"
        const val VERSION_V3 = "80000000-0000-4000-8000-000000000139"
        const val DEVICE_D1 = "task139-device-d1"
        const val GENERATION_G1 = "90000000-0000-4000-8000-000000000139"
        const val RELAUNCH_DATABASE = "task139-prebound-relaunch.db"
        const val PREF_PREPARE_PID = "prepare_process_pid"
    }
}

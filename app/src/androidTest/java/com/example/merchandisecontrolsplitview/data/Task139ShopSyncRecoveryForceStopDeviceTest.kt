package com.example.merchandisecontrolsplitview.data

import android.os.Process
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Process-death proof for the production [ShopSyncRecoveryCoordinator].
 *
 * Each `prepare...` method is started by the external ADB harness, blocks
 * immediately after a durable production boundary, and is then terminated
 * with `am force-stop`. The paired verifier runs in a new instrumentation
 * process, inspects the persisted Room state before recovery, and invokes the
 * same coordinator to prove deterministic convergence.
 */
@RunWith(AndroidJUnit4::class)
class Task139ShopSyncRecoveryForceStopDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun prepareForceStopAtStaging(): Unit = runBlocking {
        prepare(ForceStopBoundary.STAGING)
    }

    @Test
    fun verifyForceStopAtStaging(): Unit = runBlocking {
        verify(ForceStopBoundary.STAGING)
    }

    @Test
    fun prepareForceStopAtReady(): Unit = runBlocking {
        prepare(ForceStopBoundary.READY)
    }

    @Test
    fun verifyForceStopAtReady(): Unit = runBlocking {
        verify(ForceStopBoundary.READY)
    }

    @Test
    fun prepareForceStopAfterCommit(): Unit = runBlocking {
        prepare(ForceStopBoundary.POST_COMMIT)
    }

    @Test
    fun verifyForceStopAfterCommit(): Unit = runBlocking {
        verify(ForceStopBoundary.POST_COMMIT)
    }

    private suspend fun prepare(boundary: ForceStopBoundary) {
        requireExternalSingleMethodHarness()
        resetFixture()
        val database = openDatabase()
        try {
            seedOldGeneration(database)
            val remote = EmptyRecoveryRemote()
            installBlockingHook(boundary)
            coordinator(database, remote).recover(ACCOUNT, selectedShop(), activeScope())
            throw AssertionError("ADB force-stop did not terminate ${boundary.wireValue}")
        } finally {
            ShopSyncRecoveryTestHooks.reset()
            database.close()
        }
    }

    private suspend fun verify(boundary: ForceStopBoundary) {
        requireExternalSingleMethodHarness()
        val marker = markerFile(boundary)
        assertTrue("force-stop marker missing for ${boundary.wireValue}", marker.isFile)
        val preparePid = marker.readText().substringAfter("pid=").substringBefore('\n').toInt()
        assertNotEquals("verification must run in a relaunched process", preparePid, Process.myPid())

        val database = openDatabase()
        val remote = EmptyRecoveryRemote()
        try {
            val journalBefore = requireNotNull(database.syncRecoveryJournalDao().get())
            assertEquals(boundary.expectedPhase, journalBefore.phase)
            when (boundary) {
                ForceStopBoundary.STAGING,
                ForceStopBoundary.READY -> {
                    assertNotNull(database.productDao().findByBarcode(OLD_BARCODE))
                    assertNull(database.syncRecoveryBaselineDao().get())
                    assertNull(
                        database.syncEventWatermarkDao().get(
                            ACCOUNT,
                            activeScope().storeId
                        )
                    )
                }
                ForceStopBoundary.POST_COMMIT -> {
                    assertNull(database.productDao().findByBarcode(OLD_BARCODE))
                    assertNotNull(database.syncRecoveryBaselineDao().get())
                    assertEquals(
                        activeScope().ownerHash,
                        database.businessDataScopeBindingDao().get()?.ownerHash
                    )
                    assertEquals(
                        EVENT_FENCE,
                        database.syncEventWatermarkDao()
                            .get(ACCOUNT, activeScope().storeId)
                            ?.lastSyncEventId
                    )
                }
            }

            val result = coordinator(database, remote)
                .recover(ACCOUNT, selectedShop(), activeScope())

            assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
            assertNull(database.productDao().findByBarcode(OLD_BARCODE))
            assertEquals(0, database.syncEventOutboxDao().countAll())
            assertEquals(activeScope().ownerHash, database.businessDataScopeBindingDao().get()?.ownerHash)
            assertEquals(
                EVENT_FENCE,
                database.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId
            )
            assertNotNull(database.syncRecoveryBaselineDao().get())
            assertNull(database.syncRecoveryJournalDao().get())
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                assertTrue("foreign key violation after relaunch", !cursor.moveToFirst())
            }
            if (boundary == ForceStopBoundary.POST_COMMIT) {
                assertEquals("post-commit relaunch must not redownload", 0, remote.pageCalls)
                assertEquals("post-commit relaunch must not restart checkpoint chain", 0, remote.checkpointCalls)
            } else {
                assertEquals(6, remote.pageCalls)
                assertEquals(2, remote.checkpointCalls)
            }
        } finally {
            ShopSyncRecoveryTestHooks.reset()
            database.close()
            resetFixture()
        }
    }

    private fun requireExternalSingleMethodHarness() {
        val selectedTest = InstrumentationRegistry.getArguments().getString("class")
        assumeTrue(
            "TASK-139 force-stop proof requires the external single-method ADB harness",
            selectedTest?.startsWith("${javaClass.name}#") == true
        )
    }

    private fun installBlockingHook(boundary: ForceStopBoundary) {
        val hook = {
            val marker = markerFile(boundary)
            marker.parentFile?.mkdirs()
            FileOutputStream(marker).use { output ->
                output.write(
                    "pid=${Process.myPid()}\nboundary=${boundary.wireValue}\n".encodeToByteArray()
                )
                output.fd.sync()
            }
            CountDownLatch(1).await()
        }
        when (boundary) {
            ForceStopBoundary.STAGING ->
                ShopSyncRecoveryTestHooks.afterStagingJournalPersisted = hook
            ForceStopBoundary.READY ->
                ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = hook
            ForceStopBoundary.POST_COMMIT ->
                ShopSyncRecoveryTestHooks.afterActivationCommitted = hook
        }
    }

    private suspend fun seedOldGeneration(database: AppDatabase) {
        database.syncEventDeviceStateDao().insert(
            SyncEventDeviceState(deviceId = DEVICE, createdAtMs = 139L)
        )
        database.productDao().insert(
            Product(
                barcode = OLD_BARCODE,
                productName = "old generation must be atomic"
            )
        )
        database.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(oldScope(), 139L)
        )
        database.syncEventWatermarkDao().upsert(
            SyncEventWatermark(OLD_ACCOUNT, oldScope().storeId, 7L)
        )
        database.syncEventOutboxDao().insert(
            SyncEventOutboxEntry(
                ownerUserId = OLD_ACCOUNT,
                storeScope = oldScope().storeId,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                source = "force-stop-fixture",
                sourceDeviceId = DEVICE,
                batchId = null,
                clientEventId = "force-stop-old-event",
                changedCount = 1,
                entityIdsJson = "{}",
                metadataJson = "{}",
                createdAtMs = 1L
            )
        )
        database.syncRecoveryJournalDao().upsert(
            SyncRecoveryJournal(
                ownerHash = activeScope().ownerHash,
                storeScope = activeScope().storeId,
                shopId = SHOP,
                deviceId = DEVICE,
                authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
                phase = SyncRecoveryJournalPhases.REQUIRED,
                reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
                blockingEventId = 40L,
                attemptCount = 1,
                createdAtMs = 100L,
                updatedAtMs = 100L,
                nextRetryAtMs = 100L
            )
        )
    }

    private fun coordinator(
        database: AppDatabase,
        remote: EmptyRecoveryRemote
    ): ShopSyncRecoveryCoordinator = ShopSyncRecoveryCoordinator(
        context = context,
        activeDb = database,
        activeRepository = DefaultInventoryRepository(database),
        remote = remote,
        scopeStillValid = { accountId, shopId -> accountId == ACCOUNT && shopId == SHOP },
        activationBoundary = { block -> block() },
        nowMs = System::currentTimeMillis,
        availableStorageBytes = { Long.MAX_VALUE }
    )

    private fun openDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }

    private fun resetFixture() {
        ShopSyncRecoveryTestHooks.reset()
        context.deleteDatabase(DATABASE_NAME)
        markerRoot().deleteRecursively()
        context.getDatabasePath(".").canonicalFile.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("sync_recovery_stage_") }
            .forEach { file ->
                context.deleteDatabase(file.name.removeSuffix("-journal").removeSuffix("-wal").removeSuffix("-shm"))
                file.delete()
            }
    }

    private fun markerRoot() = File(context.filesDir, "task139-recovery-force-stop")

    private fun markerFile(boundary: ForceStopBoundary) =
        File(markerRoot(), "${boundary.wireValue}.marker")

    private fun selectedShop() = SelectedShop(
        shopId = SHOP,
        code = "TARGET",
        name = "Target",
        role = "shop_owner",
        status = "active",
        canWrite = true
    )

    private fun activeScope() = task126ActiveOwnerStoreScope(ACCOUNT, selectedShop())
    private fun oldScope() = task126ActiveOwnerStoreScope(OLD_ACCOUNT, selectedShop())

    private enum class ForceStopBoundary(
        val wireValue: String,
        val expectedPhase: String
    ) {
        STAGING("staging", SyncRecoveryJournalPhases.STAGING),
        READY("ready", SyncRecoveryJournalPhases.READY_TO_ACTIVATE),
        POST_COMMIT("post-commit", SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING)
    }

    private class EmptyRecoveryRemote : ShopSyncReadRemoteDataSource {
        override val isConfigured = true
        val checkpoint = emptyCheckpoint()
        var checkpointCalls = 0
        var pageCalls = 0

        override suspend fun checkpoint(
            context: ShopSyncRpcContext
        ): Result<ShopSyncRecoveryCheckpoint> {
            checkpointCalls += 1
            return Result.success(
                checkpoint.copy(
                    syncEvents = checkpoint.syncEvents.copy(
                        verifiedBaselineId = context.verifiedBaselineId
                    )
                )
            )
        }

        override suspend fun convergenceMarker(
            context: ShopSyncRpcContext
        ): Result<ShopSyncConvergenceMarker> = Result.success(
            ShopSyncConvergenceMarker(
                schemaVersion = "shop-sync-convergence-marker-v1",
                status = "ready",
                shopId = SHOP,
                scope = checkpoint.scope,
                syncEvents = checkpoint.syncEvents.copy(
                    verifiedBaselineId = context.verifiedBaselineId,
                    requiresFullRecovery = false
                ),
                catalog = checkpoint.catalog,
                prices = checkpoint.prices,
                history = checkpoint.history,
                images = checkpoint.images,
                integrity = ShopSyncMarkerIntegrity(0),
                checkpointDigest = checkpoint.checkpointDigest,
                serverNoWorkEligible = true,
                markerDigest = "e".repeat(64)
            )
        )

        override suspend fun recoveryPage(
            context: ShopSyncRpcContext,
            domain: ShopSyncRowDomain,
            afterId: String?,
            limit: Int
        ): Result<ShopSyncRecoveryPage> {
            check(afterId == null)
            pageCalls += 1
            return Result.success(
                ShopSyncRecoveryPage(
                    schemaVersion = "shop-sync-recovery-page-v1",
                    shopId = SHOP,
                    scope = checkpoint.scope,
                    domain = domain,
                    snapshotEventMaxId = requireNotNull(context.expectedEventMaxId),
                    currentScopeEventMaxId = requireNotNull(context.expectedEventMaxId),
                    baselineDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
                    pageDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
                    domainScope = ShopSyncScopeKinds.SHOP_SCOPED,
                    pageLimit = limit,
                    rows = emptyRows(domain),
                    nextAfterId = null,
                    hasMore = false,
                    responseBytes = 2L
                )
            )
        }

        override suspend fun eventPage(
            context: ShopSyncRpcContext,
            afterId: Long,
            limit: Int
        ): Result<ShopSyncEventPage> =
            Result.failure(AssertionError("unchanged checkpoint must not request an event tail"))

        override suspend fun rowsByIds(
            context: ShopSyncRpcContext,
            domain: ShopSyncRowDomain,
            ids: List<String>
        ): Result<ShopSyncTargetedRows> =
            Result.failure(AssertionError("empty checkpoint must not request targeted rows"))
    }

    private companion object {
        const val DATABASE_NAME = "task139-recovery-force-stop.db"
        const val ACCOUNT = "10000000-0000-4000-8000-000000000001"
        const val OLD_ACCOUNT = "10000000-0000-4000-8000-000000000002"
        const val SHOP = "10000000-0000-4000-8000-000000000003"
        const val DEVICE = "android-recovery-force-stop-device"
        const val OLD_BARCODE = "task139-force-stop-old"
        const val EVENT_FENCE = 42L

        fun emptyCheckpoint(): ShopSyncRecoveryCheckpoint {
            val emptyDigest = sha256("")
            val empty = ShopSyncDomainCheckpoint(
                activeCount = 0,
                tombstoneCount = 0,
                idSetDigest = emptyDigest,
                versionDigest = emptyDigest
            )
            val products = empty.copy(identityDigest = emptyDigest)
            val catalog = ShopSyncCatalogCheckpoint(
                suppliers = empty,
                categories = empty,
                products = products,
                digest = sha256("$emptyDigest\n$emptyDigest\n$emptyDigest")
            )
            return ShopSyncRecoveryCheckpoint(
                schemaVersion = "shop-sync-recovery-checkpoint-v1",
                status = "ready",
                shopId = SHOP,
                scope = ShopSyncScope(
                    kind = ShopSyncScopeKinds.SHOP_SCOPED,
                    key = "c".repeat(64),
                    historyKind = ShopSyncScopeKinds.SHOP_SCOPED,
                    accountKey = sha256(ACCOUNT.lowercase()),
                    deviceKey = sha256(DEVICE)
                ),
                syncEvents = ShopSyncEventCheckpoint(
                    maxId = EVENT_FENCE.toString(),
                    verifiedBaselineId = "0",
                    requiresFullRecovery = false,
                    domainMaxIds = mapOf(
                        SyncEventDomains.CATALOG to EVENT_FENCE.toString(),
                        SyncEventDomains.PRICES to EVENT_FENCE.toString(),
                        SyncEventDomains.HISTORY to EVENT_FENCE.toString()
                    )
                ),
                catalog = catalog,
                prices = empty,
                history = empty,
                images = empty,
                integrity = ShopSyncIntegrityCheckpoint(0, 0, 0, 0, 0, 0),
                checkpointDigest = "d".repeat(64)
            )
        }

        fun emptyRows(domain: ShopSyncRowDomain): ShopSyncRows = when (domain) {
            ShopSyncRowDomain.SUPPLIERS -> ShopSyncRows.Suppliers(emptyList())
            ShopSyncRowDomain.CATEGORIES -> ShopSyncRows.Categories(emptyList())
            ShopSyncRowDomain.PRODUCTS -> ShopSyncRows.Products(emptyList())
            ShopSyncRowDomain.PRICES -> ShopSyncRows.Prices(emptyList())
            ShopSyncRowDomain.HISTORY -> ShopSyncRows.History(emptyList())
            ShopSyncRowDomain.IMAGES -> ShopSyncRows.Images(emptyList())
        }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.encodeToByteArray())
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
    }
}

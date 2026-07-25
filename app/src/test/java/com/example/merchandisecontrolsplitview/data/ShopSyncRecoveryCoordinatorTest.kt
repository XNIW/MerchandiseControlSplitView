package com.example.merchandisecontrolsplitview.data

import android.app.Application
import androidx.room.Room
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShopSyncRecoveryCoordinatorTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository
    private lateinit var remote: RecoveryRemoteFixture
    private val databaseNames = mutableSetOf<String>()

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase(ACTIVE_DATABASE)
        db = openDatabase(ACTIVE_DATABASE)
        remote = RecoveryRemoteFixture(targetFixture())
        repository = DefaultInventoryRepository(
            db = db,
            shopSyncReadRemoteDataSource = remote
        )
        ShopSyncRecoveryTestHooks.reset()
    }

    @After
    fun teardown() {
        ShopSyncRecoveryTestHooks.reset()
        if (::db.isInitialized && db.isOpen) db.close()
        databaseNames.forEach(app::deleteDatabase)
        app.getDatabasePath(".").canonicalFile.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("sync_recovery_stage_") }
            .forEach(File::delete)
    }

    @Test
    fun `V6 checkpoint chain matches Admin UTF-8 golden vectors`() {
        assertEquals(
            "f78359ac705f7a5d38c01325096b77a570fb8a3fda3ba96cb8b105bf0a860a24",
            shopSyncCheckpointChainDigest(listOf("abc", "é"))
        )
        assertEquals(
            "d35cc83a5331da3caac79921218db4c55d400a32b0a03846002fff8dfadaa08e",
            shopSyncCheckpointChainDigest(listOf("abc", "é", "xyz"))
        )
    }

    @Test
    fun `verified recovery atomically publishes target and preserves device identity`() = runTest {
        val deviceBefore = seedOldMismatchGeneration()

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(2, remote.checkpointCalls)
        assertEquals(6, remote.pageCalls)
        assertEquals(listOf(3), remote.requestedPageLimits[ShopSyncRowDomain.HISTORY])
        assertEquals(listOf(240), remote.requestedPageLimits[ShopSyncRowDomain.SUPPLIERS])
        assertEquals(listOf(240), remote.requestedPageLimits[ShopSyncRowDomain.CATEGORIES])
        assertEquals(listOf(60), remote.requestedPageLimits[ShopSyncRowDomain.PRODUCTS])
        assertEquals(listOf(120), remote.requestedPageLimits[ShopSyncRowDomain.PRICES])
        assertEquals(listOf(240), remote.requestedPageLimits[ShopSyncRowDomain.IMAGES])
        assertEquals(listOf("Target supplier"), db.supplierDao().getAll().map { it.name })
        assertEquals(listOf("Target category"), db.categoryDao().getAll().map { it.name })
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
        assertNull(db.productDao().findByBarcode("old-barcode"))
        assertEquals(1, db.productPriceDao().countAll())
        assertEquals(1, db.historyEntryDao().countUserVisible())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
        assertEquals(activeScope().ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        assertEquals(
            42L,
            db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId
        )
        val baseline = db.syncRecoveryBaselineDao().get()
        assertNotNull(baseline)
        assertEquals(
            "42",
            decodeRecoveryCheckpointJson(requireNotNull(baseline).checkpointJson)
                .syncEvents.verifiedBaselineId
        )
        assertEquals(6, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_recovery_manifest").use {
            it.moveToFirst()
            it.getInt(0)
        })
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `recovery rejects a UUIDv7 product entity before activation`() = runTest {
        val productId = "018f0ad4-77f2-7c9d-a8be-4f6b9d234567"
        seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(targetFixture(productId = productId))

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_entity_id_invalid",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(3, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `recovery rejects the nil UUID product entity before activation`() = runTest {
        val productId = "00000000-0000-0000-0000-000000000000"
        seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(targetFixture(productId = productId))

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_entity_id_invalid",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(3, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `forged active owner scope is rejected before cloud read or local mutation`() = runTest {
        seedOldMismatchGeneration()
        val expected = activeScope()
        val forged = Task126OwnerStoreScope(
            ownerHash = oldScope().ownerHash,
            storeId = expected.storeId,
            localStoreId = expected.localStoreId,
            syncProtocolVersion = expected.syncProtocolVersion,
            schemaVersion = expected.schemaVersion,
            storeEpoch = expected.storeEpoch
        )
        db.syncRecoveryJournalDao().upsert(
            requireNotNull(db.syncRecoveryJournalDao().get()).copy(
                ownerHash = forged.ownerHash,
                storeScope = forged.storeId
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), forged)

        assertEquals(
            "recovery_scope_identity_mismatch",
            (result as ShopSyncRecoveryResult.Rejected).code
        )
        assertEquals(0, remote.checkpointCalls)
        assertEquals(0, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
        assertEquals(forged.ownerHash, db.syncRecoveryJournalDao().get()?.ownerHash)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `checkpoint scope account or device hash mismatch is rejected before staging`() = runTest {
        seedOldMismatchGeneration()
        val checkpoint = remote.fixture.checkpoint
        remote.checkpoints = mutableListOf(
            checkpoint.copy(scope = checkpoint.scope.copy(accountKey = "f".repeat(64)))
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_scope_identity_key_mismatch",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(1, remote.checkpointCalls)
        assertEquals(0, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `same scope recovery preserves foreign outbox and performs zero cloud reads`() = runTest {
        db.syncEventDeviceStateDao().insert(
            SyncEventDeviceState(deviceId = DEVICE, createdAtMs = 139L)
        )
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(activeScope(), 100L)
        )
        db.syncEventOutboxDao().insert(
            SyncEventOutboxEntry(
                ownerUserId = OLD_ACCOUNT,
                storeScope = "shop:10000000-0000-4000-8000-000000000099",
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                source = "fixture",
                sourceDeviceId = DEVICE,
                batchId = null,
                clientEventId = "70000000-0000-4000-8000-000000000099",
                changedCount = 1,
                entityIdsJson = "{}",
                metadataJson = "{}",
                createdAtMs = 1L
            )
        )
        db.syncRecoveryJournalDao().upsert(
            SyncRecoveryJournal(
                ownerHash = activeScope().ownerHash,
                storeScope = activeScope().storeId,
                shopId = SHOP,
                deviceId = DEVICE,
                authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
                phase = SyncRecoveryJournalPhases.REQUIRED,
                reason = "fixture_same_scope_recovery",
                blockingEventId = 40L,
                attemptCount = 0,
                createdAtMs = 100L,
                updatedAtMs = 100L,
                nextRetryAtMs = 100L
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_local_pending",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(1, db.syncEventOutboxDao().countAll())
        assertEquals(0, remote.checkpointCalls)
        assertEquals(0, remote.pageCalls)
        assertEquals(activeScope().ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `late activation failure rolls every business table and metadata back`() = runTest {
        val deviceBefore = seedOldMismatchGeneration()
        ShopSyncRecoveryTestHooks.beforeActivationMetadata = {
            throw IllegalStateException("fixture_late_activation_failure")
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result is ShopSyncRecoveryResult.RetryRequired)
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
        assertEquals(oldScope().ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        assertNull(db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        assertNull(db.syncRecoveryBaselineDao().get())
        assertEquals(0, db.syncRecoveryManifestDao().count("unused", "products"))
        assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)

        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
        assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
    }

    @Test
    fun `cancel during staging preserves old generation and durable retry journal`() = runTest {
        val deviceBefore = seedOldMismatchGeneration()
        remote.cancelAtDomain = ShopSyncRowDomain.CATEGORIES

        var cancelled = false
        try {
            coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
        assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
        val journal = db.syncRecoveryJournalDao().get()
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, journal?.phase)
        assertEquals("recovery_cancelled", journal?.reason)
        assertEquals(
            SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            journal?.authorizationMode
        )
        assertNotNull(journal?.nextRetryAtMs)
        assertFalse(stageFiles().any())

        remote.cancelAtDomain = null
        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)

        val resumed = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(resumed.toString(), resumed is ShopSyncRecoveryResult.Activated)
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
        assertNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun `fatal VM error during staging propagates without durable retry conversion`() = runTest {
        seedOldMismatchGeneration()
        val fatal = OutOfMemoryError("fixture_fatal_vm_error")
        remote.fatalAtDomain = ShopSyncRowDomain.CATEGORIES to fatal

        var observed: OutOfMemoryError? = null
        try {
            coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        } catch (error: OutOfMemoryError) {
            observed = error
        }

        assertSame(fatal, observed?.cause ?: observed)
        assertEquals(1, db.syncRecoveryJournalDao().get()?.attemptCount)
        assertEquals(SyncRecoveryJournalPhases.STAGING, db.syncRecoveryJournalDao().get()?.phase)
        assertOldGenerationAndManifestIntact()
    }

    @Test
    fun `fatal VM error from staging delete is never masked as cleanup retry`() = runTest {
        seedOldMismatchGeneration()
        val stagingName =
            "sync_recovery_stage_90000000-0000-4000-8000-000000000139.db"
        val current = requireNotNull(db.syncRecoveryJournalDao().get())
        db.syncRecoveryJournalDao().upsert(current.copy(stagingDatabaseName = stagingName))
        val fatal = OutOfMemoryError("fixture_staging_delete_fatal_vm_error")
        val coordinator = coordinator(
            deleteStagingDatabase = { _, _ -> throw fatal }
        )

        var observed: OutOfMemoryError? = null
        try {
            coordinator.recover(ACCOUNT, selectedShop(), activeScope())
        } catch (error: OutOfMemoryError) {
            observed = error
        }

        assertSame(fatal, observed?.cause ?: observed)
        assertEquals(current.attemptCount, db.syncRecoveryJournalDao().get()?.attemptCount)
        assertEquals(0, remote.checkpointCalls)
        assertEquals(0, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
    }

    @Test
    fun `physical Room tamper fails before activation and preserves old generation`() = runTest {
        seedOldMismatchGeneration()
        ShopSyncRecoveryTestHooks.beforeStagingValidation = { staging ->
            staging.openHelper.writableDatabase.execSQL(
                "UPDATE products SET productName = ? WHERE barcode = ?",
                arrayOf("Tampered after apply", "target-barcode")
            )
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_physical_digest_mismatch_products",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `physical History v2 display or overlay tamper fails before activation`() = runTest {
        seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(v2HistoryFixture())
        ShopSyncRecoveryTestHooks.beforeStagingValidation = { staging ->
            staging.openHelper.writableDatabase.execSQL(
                "UPDATE history_entries SET displayName = ? WHERE id = ?",
                arrayOf("tampered-history-title", "20000000-0000-4000-8000-000000000005")
            )
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_physical_history_v2_payload_mismatch",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertFalse(stageFiles().any())
    }

    @Test
    fun `product tombstone carrying live references is rejected before activation`() = runTest {
        seedOldMismatchGeneration()
        val fixture = deletedProductImageFixture()
        val deletedProduct = (fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
            .values
            .single()
            .copy(
                categoryId = "20000000-0000-4000-8000-000000000002",
                supplierId = "20000000-0000-4000-8000-000000000001",
                primaryImageVersionId = "20000000-0000-4000-8000-000000000006",
                primaryImageUpdatedAt = "2026-07-21T10:00:02.000000Z"
            )
        remote = RecoveryRemoteFixture(
            fixture.copy(
                rows = fixture.rows + (
                    ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(deletedProduct))
                )
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_product_tombstone_reference_invalid",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertFalse(stageFiles().any())
    }

    @Test
    fun `recovery rejects a price type outside the server enum`() = runTest {
        seedOldMismatchGeneration()
        val fixture = targetFixture()
        val invalidPrice = (fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices)
            .values
            .single()
            .copy(type = "UNKNOWN")
        remote = RecoveryRemoteFixture(
            fixture.copy(
                rows = fixture.rows + (
                    ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(listOf(invalidPrice))
                )
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_price_type_invalid",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertFalse(stageFiles().any())
    }

    @Test
    fun `all zero cloud snapshot atomically publishes a valid empty generation`() = runTest {
        seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(emptyTargetFixture())

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(0, db.productDao().count())
        assertEquals(0, db.supplierDao().count())
        assertEquals(0, db.categoryDao().count())
        assertEquals(0, db.productPriceDao().countAll())
        assertEquals(0, db.historyEntryDao().countUserVisible())
        assertNotNull(db.syncRecoveryBaselineDao().get())
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `device lease change after remote page rejects staged generation`() = runTest {
        seedOldMismatchGeneration()
        remote.afterPage = { domain ->
            if (domain == ShopSyncRowDomain.SUPPLIERS) {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE sync_event_device_state SET deviceId = ? WHERE id = 1",
                    arrayOf("other-device")
                )
            }
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_lease_invalid_after_page",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertFalse(stageFiles().any())
    }

    @Test
    fun `unconfigured reader records bounded retry and preserves replace authorization`() = runTest {
        seedOldMismatchGeneration()
        remote.configured = false

        val first = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        val afterFirst = requireNotNull(db.syncRecoveryJournalDao().get())
        val second = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        val afterSecond = requireNotNull(db.syncRecoveryJournalDao().get())

        assertEquals("shop_sync_reader_unavailable", (first as ShopSyncRecoveryResult.RetryRequired).code)
        assertEquals("shop_sync_reader_unavailable", (second as ShopSyncRecoveryResult.RetryRequired).code)
        assertEquals(2, afterFirst.attemptCount)
        assertEquals(3, afterSecond.attemptCount)
        assertEquals(1_010_000L, afterFirst.nextRetryAtMs)
        assertEquals(1_020_000L, afterSecond.nextRetryAtMs)
        assertEquals(
            SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            afterSecond.authorizationMode
        )
        assertEquals(0, remote.checkpointCalls)
        assertEquals(0, remote.pageCalls)
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
    }

    @Test
    fun `recovery retry counter saturates on corrupt integer extremes`() = runTest {
        seedOldMismatchGeneration()
        remote.configured = false

        listOf(Int.MAX_VALUE, -1).forEach { corruptAttemptCount ->
            db.syncRecoveryJournalDao().upsert(
                requireNotNull(db.syncRecoveryJournalDao().get()).copy(
                    attemptCount = corruptAttemptCount
                )
            )

            val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

            assertEquals(
                "shop_sync_reader_unavailable",
                (result as ShopSyncRecoveryResult.RetryRequired).code
            )
            assertEquals(
                SYNC_RECOVERY_MAX_RECORDED_ATTEMPTS,
                db.syncRecoveryJournalDao().get()?.attemptCount
            )
        }
        assertEquals(0, remote.checkpointCalls)
        assertEquals(0, remote.pageCalls)
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
    }

    @Test
    fun `stale run cannot overwrite a newer recovery intent`() = runTest {
        seedOldMismatchGeneration()
        var replacement: SyncRecoveryJournal? = null
        ShopSyncRecoveryTestHooks.beforeStagingValidation = {
            val current = requireNotNull(db.syncRecoveryJournalDao().get())
            replacement = current.copy(
                runId = "newer-run",
                phase = SyncRecoveryJournalPhases.REQUIRED,
                reason = "newer_recovery_intent",
                stagingDatabaseName = null,
                checkpointADigest = null,
                checkpointBDigest = null
            )
            db.syncRecoveryJournalDao().upsert(requireNotNull(replacement))
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_journal_changed",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(replacement, db.syncRecoveryJournalDao().get())
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertFalse(stageFiles().any())
    }

    @Test
    fun `retry journal CAS preserves an intent written inside the old retry window`() = runTest {
        seedOldMismatchGeneration()
        remote.configured = false
        var replacement: SyncRecoveryJournal? = null
        ShopSyncRecoveryTestHooks.beforeRetryJournalPersisted = {
            val current = requireNotNull(db.syncRecoveryJournalDao().get())
            replacement = current.copy(
                reason = "newer_concurrent_intent",
                blockingEventId = 99L,
                attemptCount = current.attemptCount + 10,
                nextRetryAtMs = 999_999L
            )
            db.syncRecoveryJournalDao().upsert(requireNotNull(replacement))
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "shop_sync_reader_unavailable",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(replacement, db.syncRecoveryJournalDao().get())
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
    }

    @Test
    fun `retry journal CAS preserves same-run intent written before transaction entry`() = runTest {
        seedOldMismatchGeneration()
        remote.configured = false
        var replacement: SyncRecoveryJournal? = null
        ShopSyncRecoveryTestHooks.beforeRetryTransaction = {
            val current = requireNotNull(db.syncRecoveryJournalDao().get())
            replacement = current.copy(
                phase = SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING,
                reason = "newer_pre_transaction_intent",
                blockingEventId = 101L,
                attemptCount = current.attemptCount + 20,
                nextRetryAtMs = 1_222_333L,
                checkpointBDigest = "newer-checkpoint-b",
                stagingDatabaseName = "newer-stage.db"
            )
            db.syncRecoveryJournalDao().upsert(requireNotNull(replacement))
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "shop_sync_reader_unavailable",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(1_222_333L, result.nextRetryAtMs)
        assertEquals(replacement, db.syncRecoveryJournalDao().get())
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
    }

    @Test
    fun `cancellation delivered after activation commit keeps durable cleanup phase`() = runTest {
        seedOldMismatchGeneration()
        ShopSyncRecoveryTestHooks.afterActivationCommitted = {
            throw CancellationException("fixture_post_commit_pre_flag")
        }

        var cancelled = false
        try {
            coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
        assertNull(db.productDao().findByBarcode("old-barcode"))
        assertEquals(
            SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING,
            db.syncRecoveryJournalDao().get()?.phase
        )
        assertEquals("recovery_cancelled", db.syncRecoveryJournalDao().get()?.reason)
        assertNotNull(db.syncRecoveryBaselineDao().get())

        ShopSyncRecoveryTestHooks.afterActivationCommitted = null
        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)

        val resumed = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(resumed.toString(), resumed is ShopSyncRecoveryResult.Activated)
        assertNull(db.syncRecoveryJournalDao().get())
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
    }

    @Test
    fun `cleanup failure relaunch path keeps complete target and never downloads twice`() = runTest {
        seedOldMismatchGeneration()
        var purgeCalls = 0
        val coordinator = coordinator(
            onActivated = {
                purgeCalls++
                if (purgeCalls == 1) error("fixture_cleanup_failure")
            }
        )

        val first = coordinator.recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(first.toString(), first is ShopSyncRecoveryResult.RetryRequired)
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
        assertNull(db.productDao().findByBarcode("old-barcode"))
        assertEquals(
            SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING,
            db.syncRecoveryJournalDao().get()?.phase
        )
        val pageCallsAfterActivation = remote.pageCalls

        val resumed = coordinator.recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(resumed is ShopSyncRecoveryResult.Activated)
        assertEquals(pageCallsAfterActivation, remote.pageCalls)
        assertEquals(2, purgeCalls)
        assertNull(db.syncRecoveryJournalDao().get())
        assertNotNull(db.syncRecoveryBaselineDao().get())
        assertFalse(stageFiles().any())
    }

    @Test
    fun `fatal VM error during resumed cleanup propagates without retry loop`() = runTest {
        seedOldMismatchGeneration()
        val fatal = OutOfMemoryError("fixture_cleanup_fatal_vm_error")
        var activationCallbacks = 0
        val coordinator = coordinator(
            onActivated = {
                activationCallbacks += 1
                error("fixture_defer_cleanup_to_relaunch")
            }
        )

        val first = coordinator.recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(first is ShopSyncRecoveryResult.RetryRequired)
        val pageCallsAfterActivation = remote.pageCalls
        val attemptCountBeforeFatal = db.syncRecoveryJournalDao().get()?.attemptCount
        val fatalDecoderCoordinator = coordinator(
            checkpointDecoder = { throw fatal }
        )

        var observed: OutOfMemoryError? = null
        try {
            fatalDecoderCoordinator.recover(ACCOUNT, selectedShop(), activeScope())
        } catch (error: OutOfMemoryError) {
            observed = error
        }

        assertSame(fatal, observed?.cause ?: observed)
        assertEquals(attemptCountBeforeFatal, db.syncRecoveryJournalDao().get()?.attemptCount)
        assertEquals(
            SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING,
            db.syncRecoveryJournalDao().get()?.phase
        )
        assertEquals(pageCallsAfterActivation, remote.pageCalls)
        assertEquals(1, activationCallbacks)
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
    }

    @Test
    fun `device lease change after activation callback keeps cleanup journal durable`() = runTest {
        seedOldMismatchGeneration()
        var invalidateOnce = true
        val coordinator = coordinator(
            onActivated = {
                if (invalidateOnce) {
                    invalidateOnce = false
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE sync_event_device_state SET deviceId = ? WHERE id = 1",
                        arrayOf("other-device-after-activation")
                    )
                }
            }
        )

        val first = coordinator.recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_lease_invalid_after_activation_callback",
            (first as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(
            SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING,
            db.syncRecoveryJournalDao().get()?.phase
        )
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
        assertNull(db.productDao().findByBarcode("old-barcode"))
        val pageCallsAfterActivation = remote.pageCalls
        db.openHelper.writableDatabase.execSQL(
            "UPDATE sync_event_device_state SET deviceId = ? WHERE id = 1",
            arrayOf(DEVICE)
        )

        val resumed = coordinator.recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(resumed.toString(), resumed is ShopSyncRecoveryResult.Activated)
        assertEquals(pageCallsAfterActivation, remote.pageCalls)
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(DEVICE, db.syncEventDeviceStateDao().get()?.deviceId)
    }

    @Test
    fun `activation callback receives only the recovered scope inside the activation boundary`() = runTest {
        seedOldMismatchGeneration()
        var boundaryDepth = 0
        val callbackScopes = mutableListOf<Pair<String, String>>()

        val result = coordinator(
            onScopedActivated = { accountId, shopId ->
                assertEquals(1, boundaryDepth)
                callbackScopes += accountId to shopId
            },
            activationBoundary = { block ->
                boundaryDepth += 1
                try {
                    block()
                } finally {
                    boundaryDepth -= 1
                }
            }
        ).recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(listOf(ACCOUNT to SHOP), callbackScopes)
        assertEquals(0, boundaryDepth)
    }

    @Test
    fun `lease invalidated at activation boundary never executes scoped callback`() = runTest {
        seedOldMismatchGeneration()
        var leaseValid = true
        var callbacks = 0

        val result = coordinator(
            onScopedActivated = { _, _ -> callbacks += 1 },
            scopeStillValid = { _, _ -> leaseValid },
            activationBoundary = { block ->
                leaseValid = false
                block()
            }
        ).recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_lease_invalid_before_activation",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(0, callbacks)
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `device lease change in resumed cleanup never clears journal or redownloads`() = runTest {
        seedOldMismatchGeneration()
        var activationCallbacks = 0
        val coordinator = coordinator(
            onActivated = {
                activationCallbacks += 1
                when (activationCallbacks) {
                    1 -> error("fixture_defer_cleanup_to_relaunch")
                    2 -> db.openHelper.writableDatabase.execSQL(
                        "UPDATE sync_event_device_state SET deviceId = ? WHERE id = 1",
                        arrayOf("other-device-during-resumed-cleanup")
                    )
                }
            }
        )

        val first = coordinator.recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(first is ShopSyncRecoveryResult.RetryRequired)
        val pageCallsAfterActivation = remote.pageCalls

        val staleResume = coordinator.recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_lease_invalid_after_activation_callback",
            (staleResume as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(pageCallsAfterActivation, remote.pageCalls)
        assertEquals(
            SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING,
            db.syncRecoveryJournalDao().get()?.phase
        )
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE sync_event_device_state SET deviceId = ? WHERE id = 1",
            arrayOf(DEVICE)
        )

        val finalResume = coordinator.recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(finalResume.toString(), finalResume is ShopSyncRecoveryResult.Activated)
        assertEquals(pageCallsAfterActivation, remote.pageCalls)
        assertEquals(3, activationCallbacks)
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun `cleanup resume failure stays durable with backoff and does not redownload`() = runTest {
        seedOldMismatchGeneration()
        var purgeCalls = 0
        val coordinator = coordinator(
            onActivated = {
                purgeCalls++
                if (purgeCalls <= 2) error("fixture_cleanup_failure")
            }
        )

        val first = coordinator.recover(ACCOUNT, selectedShop(), activeScope())
        val pageCallsAfterActivation = remote.pageCalls
        val second = coordinator.recover(ACCOUNT, selectedShop(), activeScope())
        val retryJournal = requireNotNull(db.syncRecoveryJournalDao().get())

        assertTrue(first is ShopSyncRecoveryResult.RetryRequired)
        assertTrue(second is ShopSyncRecoveryResult.RetryRequired)
        assertEquals(SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING, retryJournal.phase)
        assertEquals(3, retryJournal.attemptCount)
        assertEquals(1_020_000L, retryJournal.nextRetryAtMs)
        assertEquals(pageCallsAfterActivation, remote.pageCalls)
        assertNotNull(db.productDao().findByBarcode("target-barcode"))

        val third = coordinator.recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(third.toString(), third is ShopSyncRecoveryResult.Activated)
        assertEquals(pageCallsAfterActivation, remote.pageCalls)
        assertEquals(3, purgeCalls)
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun `cloud drift during cleanup returns to required and fresh snapshot completes`() = runTest {
        seedOldMismatchGeneration()
        var purgeCalls = 0
        val coordinator = coordinator(
            onActivated = {
                purgeCalls++
                if (purgeCalls == 1) error("fixture_cleanup_failure")
            }
        )
        val first = coordinator.recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(first is ShopSyncRecoveryResult.RetryRequired)
        val pageCallsAfterActivation = remote.pageCalls
        val advanced = remote.fixture.checkpoint.copy(
            syncEvents = remote.fixture.checkpoint.syncEvents.copy(
                maxId = "43",
                domainMaxIds = mapOf(
                    SyncEventDomains.CATALOG to "43",
                    SyncEventDomains.PRICES to "43",
                    SyncEventDomains.HISTORY to "43"
                )
            )
        )
        remote.checkpoints = mutableListOf(advanced)

        val drift = coordinator.recover(ACCOUNT, selectedShop(), activeScope())
        val required = requireNotNull(db.syncRecoveryJournalDao().get())

        assertEquals(
            ShopSyncRecoveryReasons.POST_ACTIVATION_CHECKPOINT_CHANGED,
            (drift as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, required.phase)
        assertEquals(
            SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            required.authorizationMode
        )
        assertEquals(pageCallsAfterActivation, remote.pageCalls)
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
        assertFalse(stageFiles().any())

        val recovered = coordinator.recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
        assertEquals(pageCallsAfterActivation + 6, remote.pageCalls)
        assertEquals(43L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun `checkpoint drift never activates staged generation`() = runTest {
        seedOldMismatchGeneration()
        remote.checkpoints = mutableListOf(
            remote.fixture.checkpoint,
            remote.fixture.checkpoint.copy(
                syncEvents = remote.fixture.checkpoint.syncEvents.copy(
                    maxId = "43",
                    domainMaxIds = mapOf(
                        SyncEventDomains.CATALOG to "43",
                        SyncEventDomains.PRICES to "43",
                        SyncEventDomains.HISTORY to "43"
                    )
                )
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result is ShopSyncRecoveryResult.RetryRequired)
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertEquals(oldScope().ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        assertNull(db.syncRecoveryBaselineDao().get())
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
    }

    @Test
    fun `checkpoint B tail applies catalog prices and history only in staging before B publication`() = runTest {
        seedOldMismatchGeneration()
        val fixture = remote.fixture
        val checkpointA = fixture.checkpoint
        val productA = (fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
            .values
            .single()
        val priceA = (fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices)
            .values
            .single()
        val historyA = (fixture.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History)
            .values
            .single()
        val tailTimestamp = "2026-07-21T10:00:01.000000Z"
        val productB = productA.copy(
            productName = "Tail product",
            retailPrice = 9.0,
            updatedAt = tailTimestamp
        )
        val priceTimestamp = "2026-07-21T10:00:02.000000Z"
        val priceB = priceA.copy(
            id = "20000000-0000-4000-8000-000000000007",
            price = 9.0,
            priceCanonical = "9",
            effectiveAt = "2026-07-21 10:00:01",
            createdAt = "2026-07-21 10:00:01",
            updatedAt = priceTimestamp
        )
        val historyTimestamp = "2026-07-21T10:00:03.000000Z"
        val historyB = historyA.copy(
            displayName = "Tail history",
            updatedAt = historyTimestamp
        )
        val productCheckpointB = checkpointDomain(
            ids = listOf(productB.id),
            versions = listOf(testProductVersion(productB)),
            identities = listOf(testProductIdentity(productB))
        )
        val catalogB = checkpointA.catalog.copy(
            products = productCheckpointB,
            digest = testSha256(
                checkpointA.catalog.suppliers.versionDigest + "\n" +
                checkpointA.catalog.categories.versionDigest + "\n" +
                    productCheckpointB.versionDigest
            )
        )
        val pricesB = checkpointDomain(
            ids = listOf(priceA.id, priceB.id),
            versions = listOf(
                testPriceVersion(priceA),
                testPriceVersion(priceB)
            )
        )
        val historyBCheckpoint = checkpointDomain(
            ids = listOf(historyB.remoteId),
            versions = listOf(testHistoryVersion(historyB))
        )
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "45",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "43") +
                    (SyncEventDomains.PRICES to "44") +
                    (SyncEventDomains.HISTORY to "45")
            ),
            prices = pricesB,
            history = historyBCheckpoint,
            catalog = catalogB,
            checkpointDigest = "9".repeat(64)
        )
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(productIds = listOf(productB.id)),
                createdAt = tailTimestamp
            ),
            SyncEventRemoteRow(
                id = 44L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.PRICES,
                eventType = SyncEventTypes.PRICES_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(
                    priceIds = listOf(priceB.id),
                    productIds = listOf(productB.id)
                ),
                createdAt = priceTimestamp
            ),
            SyncEventRemoteRow(
                id = 45L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.HISTORY,
                eventType = SyncEventTypes.HISTORY_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(sessionIds = listOf(historyB.remoteId)),
                createdAt = historyTimestamp
            )
        )
        remote.tailRows = fixture.rows + (
            ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(productB))
            ) +
            (ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(listOf(priceA, priceB))) +
            (ShopSyncRowDomain.HISTORY to ShopSyncRows.History(listOf(historyB)))
        val canonicalMarkerCheckpointDigest = "c".repeat(64)
        remote.markerTransform = { marker ->
            marker.copy(checkpointDigest = canonicalMarkerCheckpointDigest)
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals("Tail product", db.productDao().findByBarcode("target-barcode")?.productName)
        assertEquals(2, db.productPriceDao().countAll())
        // v1 intentionally does not overwrite the local display name. The
        // successful physical validation above proves its new remote receipt
        // was nevertheless acknowledged in the staging bridge.
        assertNotNull(db.historyEntryDao().getById(historyB.remoteId))
        assertEquals(45L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val published = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        assertEquals("45", published.syncEvents.maxId)
        assertEquals("45", published.syncEvents.verifiedBaselineId)
        assertEquals(canonicalMarkerCheckpointDigest, published.checkpointDigest)
        assertEquals(listOf("45"), remote.tailEventContexts.mapNotNull { it.expectedEventMaxId }.distinct())
        assertTrue(
            remote.tailTargetedContexts.all { context ->
                context.expectedEventMaxId == "45" &&
                    context.expectedDomainEventMaxId != null &&
                    context.expectedScope == checkpointB.scope
            }
        )
        assertTrue(
            remote.tailTargetedRequests.any { (domain, ids) ->
                domain == ShopSyncRowDomain.PRODUCTS && ids == listOf(productB.id)
            }
        )
        assertTrue(remote.tailTargetedRequests.any { it.first == ShopSyncRowDomain.PRICES })
        assertTrue(remote.tailTargetedRequests.any { it.first == ShopSyncRowDomain.HISTORY })
        assertTrue(remote.tailTargetedRequests.any { it.first == ShopSyncRowDomain.IMAGES })
        assertNull(db.syncRecoveryJournalDao().get())
        val postRecoveryDrain = repository.drainSyncEventsFromRemote(
            remote = NoOpCatalogRemoteForRecoveryTest,
            priceRemote = NoOpPriceRemoteForRecoveryTest,
            syncEventRemote = NoOpSyncEventRemoteForRecoveryTest,
            ownerUserId = ACCOUNT,
            progressReporter = CatalogSyncProgressReporter { },
            selectedShop = selectedShop()
        ).getOrThrow()
        assertEquals(0, postRecoveryDrain.syncEventsFetched)
        assertEquals(0, postRecoveryDrain.syncEventsProcessed)
        assertEquals(45L, postRecoveryDrain.syncEventsWatermarkAfter)
        assertFalse(postRecoveryDrain.manualFullSyncRequired)
        assertFalse(postRecoveryDrain.syncEventsGapDetected)
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `checkpoint B with a blocking event never publishes even when receipt material is unchanged`() = runTest {
        seedOldMismatchGeneration()
        val checkpointA = remote.fixture.checkpoint
        remote.checkpoints = mutableListOf(
            checkpointA,
            checkpointA.copy(
                syncEvents = checkpointA.syncEvents.copy(
                    requiresFullRecovery = true,
                    oldestBlockingId = checkpointA.syncEvents.maxId,
                    newestBlockingId = checkpointA.syncEvents.maxId
                )
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_tail_requires_full_recovery",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(0, remote.tailEventContexts.size)
        assertOldGenerationAndManifestIntact()
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `marker C must be self verifying before recovery can publish`() = runTest {
        seedOldMismatchGeneration()
        remote.markerTransform = { marker ->
            marker.copy(
                syncEvents = marker.syncEvents.copy(verifiedBaselineId = "0")
            )
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_convergence_marker_mismatch",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(2, remote.checkpointCalls)
        assertOldGenerationAndManifestIntact()
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `checkpoint B product image removal deletes only staged image manifest before activation`() = runTest {
        seedOldMismatchGeneration()
        val fixture = remote.fixture
        val checkpointA = fixture.checkpoint
        val productA = (fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
            .values
            .single()
        val tailTimestamp = "2026-07-21T10:00:01.000000Z"
        val productB = productA.copy(
            primaryImageVersionId = null,
            primaryImageUpdatedAt = null,
            updatedAt = tailTimestamp
        )
        val productCheckpointB = checkpointDomain(
            ids = listOf(productB.id),
            versions = listOf(testProductVersion(productB)),
            identities = listOf(testProductIdentity(productB))
        )
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "43",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "43")
            ),
            catalog = checkpointA.catalog.copy(
                products = productCheckpointB,
                digest = testSha256(
                    checkpointA.catalog.suppliers.versionDigest + "\n" +
                        checkpointA.catalog.categories.versionDigest + "\n" +
                        productCheckpointB.versionDigest
                )
            ),
            images = checkpointDomain(emptyList(), emptyList()),
            checkpointDigest = "6".repeat(64)
        )
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(productIds = listOf(productB.id)),
                createdAt = tailTimestamp
            )
        )
        remote.tailRows = fixture.rows +
            (ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(productB))) +
            (ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(emptyList()))

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertNull(db.productDao().findByBarcode("target-barcode")?.primaryImageVersionId)
        val generationId = requireNotNull(db.syncRecoveryBaselineDao().get()).generationId
        assertEquals(0, db.syncRecoveryManifestDao().count(generationId, ShopSyncRowDomain.IMAGES.wireValue))
        assertFalse(remote.tailTargetedRequests.any { it.first == ShopSyncRowDomain.IMAGES })
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `live A pages may straddle a product image change because frozen B tail repairs staging`() = runTest {
        seedOldMismatchGeneration()
        val fixture = remote.fixture
        val checkpointA = fixture.checkpoint
        val productA = (fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
            .values
            .single()
        val imageA = (fixture.rows.getValue(ShopSyncRowDomain.IMAGES) as ShopSyncRows.Images)
            .values
            .single()
        val tailTimestamp = "2026-07-21T10:00:01.000000Z"
        val imageB = imageA.copy(
            versionId = "20000000-0000-4000-8000-000000000008",
            finalizedAt = tailTimestamp,
            main = imageA.main.copy(sha256 = "c".repeat(64)),
            thumb = imageA.thumb.copy(sha256 = "d".repeat(64))
        )
        val productB = productA.copy(
            primaryImageVersionId = imageB.versionId,
            primaryImageUpdatedAt = tailTimestamp,
            updatedAt = tailTimestamp
        )
        val productCheckpointB = checkpointDomain(
            ids = listOf(productB.id),
            versions = listOf(testProductVersion(productB)),
            identities = listOf(testProductIdentity(productB))
        )
        val imageCheckpointB = checkpointDomain(
            ids = listOf(imageB.productId),
            versions = listOf(testImageVersion(imageB))
        )
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "43",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "43")
            ),
            catalog = checkpointA.catalog.copy(
                products = productCheckpointB,
                digest = testSha256(
                    checkpointA.catalog.suppliers.versionDigest + "\n" +
                        checkpointA.catalog.categories.versionDigest + "\n" +
                        productCheckpointB.versionDigest
                )
            ),
            images = imageCheckpointB,
            checkpointDigest = "5".repeat(64)
        )
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        // Recovery pages are lower-bound live reads: simulate product B paired
        // with still-visible image A. The coordinator must not validate this
        // transient mix before it drains the frozen B tail.
        remote.recoveryRows = fixture.rows +
            (ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(productB)))
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(productIds = listOf(productB.id)),
                createdAt = tailTimestamp
            )
        )
        remote.tailRows = fixture.rows +
            (ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(productB))) +
            (ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(listOf(imageB)))

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(
            imageB.versionId,
            db.productDao().findByBarcode("target-barcode")?.primaryImageVersionId
        )
        val generationId = requireNotNull(db.syncRecoveryBaselineDao().get()).generationId
        val manifest = db.syncRecoveryManifestDao()
            .page(generationId, ShopSyncRowDomain.IMAGES.wireValue, null, 10)
            .single()
        assertEquals(imageB.productId, manifest.idLine)
        assertEquals(imageB.versionId, manifest.versionLine.split('\u001f')[1])
        assertTrue(remote.tailTargetedRequests.any { it.first == ShopSyncRowDomain.IMAGES })
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `live A page may include a B addition because final receipt is validated only after the tail`() = runTest {
        seedOldMismatchGeneration()
        val fixture = remote.fixture
        val checkpointA = fixture.checkpoint
        val productA = (fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
            .values
            .single()
        val tailTimestamp = "2026-07-21T10:00:01.000000Z"
        val productB = productA.copy(
            id = "20000000-0000-4000-8000-000000000009",
            barcode = "tail-added-barcode",
            productName = "Tail added product",
            primaryImageVersionId = null,
            primaryImageUpdatedAt = null,
            updatedAt = tailTimestamp
        )
        val productCheckpointB = checkpointDomain(
            ids = listOf(productA.id, productB.id),
            versions = listOf(testProductVersion(productA), testProductVersion(productB)),
            identities = listOf(
                testProductIdentity(productA),
                testProductIdentity(productB)
            )
        )
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "43",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "43")
            ),
            catalog = checkpointA.catalog.copy(
                products = productCheckpointB,
                digest = testSha256(
                    checkpointA.catalog.suppliers.versionDigest + "\n" +
                        checkpointA.catalog.categories.versionDigest + "\n" +
                        productCheckpointB.versionDigest
                )
            ),
            checkpointDigest = "3".repeat(64)
        )
        val rowsB = fixture.rows +
            (ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(productA, productB)))
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        remote.recoveryRows = rowsB
        remote.recoveryCurrentScopeEventMaxId = checkpointB.syncEvents.maxId
        remote.recoveryCurrentDomainEventMaxIds = checkpointB.syncEvents.domainMaxIds
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(productIds = listOf(productB.id)),
                createdAt = tailTimestamp
            )
        )
        remote.tailRows = rowsB

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertNotNull(db.productDao().findByBarcode("tail-added-barcode"))
        assertEquals(2, db.productDao().getAll().size)
        assertEquals(43L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `checkpoint B history tombstone removes only staged physical row and retains manifest tombstone`() = runTest {
        seedOldMismatchGeneration()
        val fixture = remote.fixture
        val checkpointA = fixture.checkpoint
        val historyA = (fixture.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History)
            .values
            .single()
        val tombstoneTimestamp = "2026-07-21T10:00:01.000000Z"
        val historyTombstone = historyA.copy(
            updatedAt = tombstoneTimestamp,
            deletedAt = tombstoneTimestamp
        )
        val historyCheckpointB = ShopSyncDomainCheckpoint(
            activeCount = 0,
            tombstoneCount = 1,
            idSetDigest = testLineDigest(listOf(historyTombstone.remoteId)),
            versionDigest = testLineDigest(listOf(testHistoryVersion(historyTombstone)))
        )
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "43",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.HISTORY to "43")
            ),
            history = historyCheckpointB,
            checkpointDigest = "4".repeat(64)
        )
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.HISTORY,
                eventType = SyncEventTypes.HISTORY_TOMBSTONE,
                changedCount = 1,
                entityIds = SyncEventEntityIds(sessionIds = listOf(historyTombstone.remoteId)),
                createdAt = tombstoneTimestamp
            )
        )
        remote.tailRows = fixture.rows +
            (ShopSyncRowDomain.HISTORY to ShopSyncRows.History(listOf(historyTombstone)))

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertNull(db.historyEntryDao().getById(historyTombstone.remoteId))
        assertNull(db.historyEntryRemoteRefDao().getByRemoteId(historyTombstone.remoteId))
        val generationId = requireNotNull(db.syncRecoveryBaselineDao().get()).generationId
        val manifest = db.syncRecoveryManifestDao()
            .page(generationId, ShopSyncRowDomain.HISTORY.wireValue, null, 10)
            .single()
        assertFalse(manifest.active)
        assertEquals(historyTombstone.remoteId, manifest.remoteId)
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `full recovery retains the canonical image tombstone for a deleted product`() = runTest {
        seedOldMismatchGeneration()
        val fixture = deletedProductImageFixture()
        remote = RecoveryRemoteFixture(fixture)

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertNull(db.productDao().findByBarcode("target-barcode"))
        val generationId = requireNotNull(db.syncRecoveryBaselineDao().get()).generationId
        val productManifest = db.syncRecoveryManifestDao()
            .page(generationId, ShopSyncRowDomain.PRODUCTS.wireValue, null, 10)
            .single()
        val imageManifest = db.syncRecoveryManifestDao()
            .page(generationId, ShopSyncRowDomain.IMAGES.wireValue, null, 10)
            .single()
        assertFalse(productManifest.active)
        assertFalse(imageManifest.active)
        assertEquals(productManifest.remoteId, imageManifest.remoteId)
        assertEquals(productManifest.versionLine.split('\u001f')[2], imageManifest.versionLine.split('\u001f')[3])
        assertEquals(productManifest.remoteId, imageManifest.idLine)
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `incomplete event inside frozen B tail leaves active generation and durable recovery intact`() = runTest {
        seedOldMismatchGeneration()
        val checkpointA = remote.fixture.checkpoint
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "43",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "43")
            ),
            checkpointDigest = "8".repeat(64)
        )
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(),
                createdAt = "2026-07-21T10:00:01.000000Z"
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_tail_event_unsafe",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(1, remote.tailEventContexts.size)
        assertOldGenerationAndManifestIntact()
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `cross shop targeted tail row is rejected before staging can publish it`() = runTest {
        seedOldMismatchGeneration()
        val fixture = remote.fixture
        val checkpointA = fixture.checkpoint
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "43",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "43")
            )
        )
        val product = (fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
            .values
            .single()
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(productIds = listOf(product.id)),
                createdAt = "2026-07-21T10:00:01.000000Z"
            )
        )
        remote.tailRows = fixture.rows +
            (ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(
                listOf(product.copy(shopId = "10000000-0000-4000-8000-000000000099"))
            ))

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_tail_targeted_row_scope_mismatch",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `frozen B tail paginates beyond one event page before publishing B`() = runTest {
        seedOldMismatchGeneration()
        val checkpointA = remote.fixture.checkpoint
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "193",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "193")
            ),
            checkpointDigest = "7".repeat(64)
        )
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        remote.tailEvents = (43L..193L).map { id ->
            SyncEventRemoteRow(
                id = id,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                changedCount = 0,
                entityIds = SyncEventEntityIds(),
                createdAt = "2026-07-21T10:00:01.000000Z"
            )
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(2, remote.tailEventContexts.size)
        assertTrue(remote.tailEventContexts.all { it.expectedEventMaxId == "193" })
        assertEquals(193L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertEquals(
            "193",
            decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson)
                .syncEvents
                .maxId
        )
        assertNull(db.syncRecoveryJournalDao().get())
        assertFalse(stageFiles().any())
    }

    @Test
    fun `tail response budget is cumulative with snapshot and cannot activate after overflow`() = runTest {
        seedOldMismatchGeneration()
        val checkpointA = remote.fixture.checkpoint
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "43",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "43")
            )
        )
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                changedCount = 0,
                entityIds = SyncEventEntityIds(),
                createdAt = "2026-07-21T10:00:01.000000Z"
            )
        )
        val limits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(totalResponseBytes = 6L)

        val result = coordinator(resourceLimits = limits)
            .recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_tail_total_response_budget_exceeded",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `cancel during frozen B tail preserves old generation and retry journal`() = runTest {
        seedOldMismatchGeneration()
        val checkpointA = remote.fixture.checkpoint
        val checkpointB = checkpointA.copy(
            syncEvents = checkpointA.syncEvents.copy(
                maxId = "43",
                domainMaxIds = checkpointA.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "43")
            )
        )
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                changedCount = 0,
                entityIds = SyncEventEntityIds(),
                createdAt = "2026-07-21T10:00:01.000000Z"
            )
        )
        remote.cancelAtTailEventPage = true

        var cancelled = false
        try {
            coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertOldGenerationAndManifestIntact()
        assertEquals("recovery_cancelled", db.syncRecoveryJournalDao().get()?.reason)
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `same counts with a changed domain digest never activate staged generation`() = runTest {
        seedOldMismatchGeneration()
        val checkpointA = remote.fixture.checkpoint
        val changedProductDigest = checkpointA.catalog.products.copy(
            versionDigest = "f".repeat(64)
        )
        val checkpointB = checkpointA.copy(
            catalog = checkpointA.catalog.copy(
                products = changedProductDigest,
                digest = "e".repeat(64)
            ),
            checkpointDigest = "d".repeat(64)
        )
        remote.checkpoints = mutableListOf(checkpointA, checkpointB)

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_checkpoint_changed_without_event_tail",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(
            checkpointA.catalog.products.activeCount,
            checkpointB.catalog.products.activeCount
        )
        assertEquals(
            checkpointA.catalog.products.tombstoneCount,
            checkpointB.catalog.products.tombstoneCount
        )
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertEquals(oldScope().ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        assertNull(db.syncRecoveryBaselineDao().get())
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `orphan staging cleanup is bounded durable and resumes before download`() = runTest {
        seedOldMismatchGeneration()
        repeat(9) { index ->
            val suffix = (index + 1).toString(16).padStart(12, '0')
            val name = "sync_recovery_stage_90000000-0000-4000-8000-$suffix.db"
            assertTrue(app.getDatabasePath(name).createNewFile())
        }

        val deferred = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            ShopSyncRecoveryResult.RetryRequired(
                code = "recovery_orphan_cleanup_deferred",
                nextRetryAtMs = 1_010_000L
            ),
            deferred
        )
        assertEquals(0, remote.checkpointCalls)
        assertEquals(0, remote.pageCalls)
        assertEquals(1, stageFiles().count())
        assertEquals(
            "recovery_orphan_cleanup_deferred",
            db.syncRecoveryJournalDao().get()?.reason
        )

        val resumed = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(resumed.toString(), resumed is ShopSyncRecoveryResult.Activated)
        assertFalse(stageFiles().any())
    }

    @Test
    fun `checkpoint count overflow fails before staging and preserves old manifest`() = runTest {
        seedOldMismatchGeneration()
        val checkpoint = remote.fixture.checkpoint
        remote.checkpoints = mutableListOf(
            checkpoint.copy(
                catalog = checkpoint.catalog.copy(
                    suppliers = checkpoint.catalog.suppliers.copy(
                        activeCount = Long.MAX_VALUE,
                        tombstoneCount = 1L
                    )
                )
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "checkpoint_count_overflow_suppliers",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(0, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())

    }

    @Test
    fun `checkpoint total row budget fails closed before first page`() = runTest {
        seedOldMismatchGeneration()
        val limits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(totalRows = 5L)

        val result = coordinator(resourceLimits = limits)
            .recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "checkpoint_total_row_budget_exceeded",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(0, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())

    }

    @Test
    fun `oversized page response never mutates active database or manifest`() = runTest {
        seedOldMismatchGeneration()
        val limits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS
        remote.responseBytes[ShopSyncRowDomain.SUPPLIERS] =
            limits.defaultPageResponseBytes + 1L

        val result = coordinator(resourceLimits = limits)
            .recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_page_response_budget_exceeded",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(1, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `oversized history row aborts staging and preserves old generation`() = runTest {
        seedOldMismatchGeneration()
        val limits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS
        remote.largestRowBytes[ShopSyncRowDomain.HISTORY] =
            limits.historyRowResponseBytes + 1L

        val result = coordinator(resourceLimits = limits)
            .recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "history_row_response_budget_exceeded",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `oversized product image metadata aborts staging before generation activation`() = runTest {
        seedOldMismatchGeneration()
        val fixture = targetFixture()
        val images = fixture.rows.getValue(ShopSyncRowDomain.IMAGES) as ShopSyncRows.Images
        val oversized = images.copy(
            values = images.values.map { row ->
                row.copy(main = row.main.copy(bytes = 1024L * 1024L + 1L))
            }
        )
        remote = RecoveryRemoteFixture(
            fixture.copy(rows = fixture.rows + (ShopSyncRowDomain.IMAGES to oversized))
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_image_metadata_invalid",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `non jpeg product image metadata aborts staging before generation activation`() = runTest {
        seedOldMismatchGeneration()
        val fixture = targetFixture()
        val images = fixture.rows.getValue(ShopSyncRowDomain.IMAGES) as ShopSyncRows.Images
        val invalidMime = images.copy(
            values = images.values.map { row ->
                row.copy(thumb = row.thumb.copy(mime = "image/png"))
            }
        )
        remote = RecoveryRemoteFixture(
            fixture.copy(rows = fixture.rows + (ShopSyncRowDomain.IMAGES to invalidMime))
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_image_metadata_invalid",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `paged relational manifest rejects primary image mismatch without global graph`() = runTest {
        seedOldMismatchGeneration()
        val fixture = targetFixture()
        val images = fixture.rows.getValue(ShopSyncRowDomain.IMAGES) as ShopSyncRows.Images
        val mismatched = images.copy(
            values = images.values.map { row ->
                row.copy(versionId = "20000000-0000-4000-8000-000000000099")
            }
        )
        remote = RecoveryRemoteFixture(
            fixture.copy(rows = fixture.rows + (ShopSyncRowDomain.IMAGES to mismatched))
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_primary_image_invalid",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `domain and total response budgets use overflow safe cumulative accounting`() = runTest {
        seedOldMismatchGeneration()
        val domainLimits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(
            domainResponseBytes = 5L,
            totalResponseBytes = 100L
        )
        remote.responseBytes[ShopSyncRowDomain.SUPPLIERS] = 6L

        val domainResult = coordinator(resourceLimits = domainLimits)
            .recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_domain_response_budget_exceeded",
            (domainResult as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())

        remote.responseBytes.clear()
        remote.responseBytes[ShopSyncRowDomain.SUPPLIERS] = 3L
        remote.responseBytes[ShopSyncRowDomain.CATEGORIES] = 3L
        val totalLimits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(
            domainResponseBytes = 100L,
            totalResponseBytes = 5L
        )

        val totalResult = coordinator(resourceLimits = totalLimits)
            .recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_total_response_budget_exceeded",
            (totalResult as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `generation disk ceiling and activation headroom fail before publication`() = runTest {
        seedOldMismatchGeneration()
        val limits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS

        val diskResult = coordinator(
            resourceLimits = limits,
            generationSizeBytes = { limits.generationBytes + 1L }
        ).recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_generation_disk_budget_exceeded",
            (diskResult as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(0, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())

        val headroomResult = coordinator(
            resourceLimits = limits,
            availableStorageBytes = { limits.activationHeadroomBytes }
        ).recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_activation_headroom_insufficient",
            (headroomResult as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertEquals(6, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `activation headroom accounts for old and new generations at exact boundary`() = runTest {
        seedOldMismatchGeneration()
        val fixedHeadroom = 64L
        val stagingBytes = 96L
        val activeBytes = 128L
        val required = stagingBytes + activeBytes + fixedHeadroom
        val limits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(
            activationHeadroomBytes = fixedHeadroom
        )

        val insufficient = coordinator(
            resourceLimits = limits,
            generationSizeBytes = { stagingBytes },
            activeGenerationSizeBytes = { activeBytes },
            availableStorageBytes = { required - 1L }
        ).recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_activation_headroom_insufficient",
            (insufficient as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())

        val exact = coordinator(
            resourceLimits = limits,
            generationSizeBytes = { stagingBytes },
            activeGenerationSizeBytes = { activeBytes },
            availableStorageBytes = { required }
        ).recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(exact.toString(), exact is ShopSyncRecoveryResult.Activated)
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
        assertNull(db.productDao().findByBarcode("old-barcode"))
        assertFalse(stageFiles().any())
    }

    @Test
    fun `activation headroom rejects negative sizes and overflow`() {
        val negative = runCatching {
            requiredRecoveryActivationHeadroomBytes(-1L, 1L, 1L)
        }.exceptionOrNull()
        assertEquals(
            "recovery_activation_headroom_size_invalid",
            (negative as ShopSyncContractException).code
        )

        val overflow = runCatching {
            requiredRecoveryActivationHeadroomBytes(Long.MAX_VALUE, 1L, 1L)
        }.exceptionOrNull()
        assertEquals(
            "recovery_activation_headroom_overflow",
            (overflow as ShopSyncContractException).code
        )
    }

    @Test
    fun `three real relaunches keep one verified generation without recovery loop`() = runTest {
        seedOldMismatchGeneration()
        val activated = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(activated.toString(), activated is ShopSyncRecoveryResult.Activated)
        val checkpointCalls = remote.checkpointCalls
        val pageCalls = remote.pageCalls
        val generation = requireNotNull(db.syncRecoveryBaselineDao().get()).generationId

        repeat(3) {
            db.close()
            db = openDatabase(ACTIVE_DATABASE)
            repository = DefaultInventoryRepository(db)

            val state = repository.resolveBusinessDataScope(activeScope())
            assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
            assertEquals(generation, db.syncRecoveryBaselineDao().get()?.generationId)
            assertNotNull(db.productDao().findByBarcode("target-barcode"))
            assertNull(db.productDao().findByBarcode("old-barcode"))
            assertEquals(42L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
            assertNull(db.syncRecoveryJournalDao().get())
            assertForeignKeysClean(db)
        }
        assertEquals(checkpointCalls, remote.checkpointCalls)
        assertEquals(pageCalls, remote.pageCalls)
        assertFalse(stageFiles().any())
    }

    private fun coordinator(
        onActivated: suspend () -> Unit = {},
        onScopedActivated: (suspend (accountId: String, shopId: String) -> Unit)? = null,
        scopeStillValid: suspend (accountId: String, shopId: String) -> Boolean = { accountId, shopId ->
            accountId == ACCOUNT && shopId == SHOP
        },
        activationBoundary: suspend (block: suspend () -> Unit) -> Unit = { block -> block() },
        resourceLimits: ShopSyncRecoveryResourceLimits =
            DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS,
        generationSizeBytes: (File) -> Long = ::testGenerationSizeBytes,
        activeGenerationSizeBytes: (File) -> Long = generationSizeBytes,
        availableStorageBytes: (File) -> Long = { Long.MAX_VALUE },
        checkpointDecoder: (String) -> ShopSyncRecoveryCheckpoint =
            ::decodeRecoveryCheckpointJson,
        deleteStagingDatabase: (android.content.Context, String) -> Boolean =
            { context, name -> context.deleteDatabase(name) }
    ): ShopSyncRecoveryCoordinator = ShopSyncRecoveryCoordinator(
        context = app,
        activeDb = db,
        activeRepository = repository,
        remote = remote,
        scopeStillValid = scopeStillValid,
        activationBoundary = activationBoundary,
        onActivated = { accountId, shopId ->
            onScopedActivated?.invoke(accountId, shopId) ?: onActivated()
        },
        nowMs = { 1_000_000L },
        resourceLimits = resourceLimits,
        generationSizeBytes = generationSizeBytes,
        activeGenerationSizeBytes = activeGenerationSizeBytes,
        availableStorageBytes = availableStorageBytes,
        checkpointDecoder = checkpointDecoder,
        deleteStagingDatabase = deleteStagingDatabase
    )

    private suspend fun seedOldMismatchGeneration(): SyncEventDeviceState {
        val device = SyncEventDeviceState(deviceId = DEVICE, createdAtMs = 139L)
        db.syncEventDeviceStateDao().insert(device)
        db.supplierDao().insert(Supplier(id = 1L, name = "Old supplier"))
        db.categoryDao().insert(Category(id = 1L, name = "Old category"))
        db.productDao().insert(
            Product(
                id = 1L,
                barcode = "old-barcode",
                productName = "Old product",
                supplierId = 1L,
                categoryId = 1L
            )
        )
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(oldScope(), 100L)
        )
        db.syncEventWatermarkDao().upsert(
            SyncEventWatermark(OLD_ACCOUNT, oldScope().storeId, 7L)
        )
        db.syncEventOutboxDao().insert(
            SyncEventOutboxEntry(
                ownerUserId = OLD_ACCOUNT,
                storeScope = oldScope().storeId,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_CHANGED,
                source = "fixture",
                sourceDeviceId = DEVICE,
                batchId = null,
                clientEventId = "70000000-0000-4000-8000-000000000001",
                changedCount = 1,
                entityIdsJson = "{}",
                metadataJson = "{}",
                createdAtMs = 1L
            )
        )
        db.syncRecoveryManifestDao().insertAll(
            listOf(
                SyncRecoveryManifestRow(
                    generationId = OLD_GENERATION,
                    domain = ShopSyncRowDomain.PRODUCTS.wireValue,
                    remoteId = OLD_MANIFEST_PRODUCT,
                    active = true,
                    idLine = OLD_MANIFEST_PRODUCT,
                    versionLine = OLD_MANIFEST_PRODUCT,
                    payloadDigest = "0".repeat(64)
                )
            )
        )
        db.syncRecoveryJournalDao().upsert(
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
        return device
    }

    private suspend fun assertOldGenerationAndManifestIntact() {
        assertNotNull(db.productDao().findByBarcode("old-barcode"))
        assertNull(db.productDao().findByBarcode("target-barcode"))
        assertEquals(
            1,
            db.syncRecoveryManifestDao().count(OLD_GENERATION, ShopSyncRowDomain.PRODUCTS.wireValue)
        )
        assertEquals(oldScope().ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        assertEquals(1, db.syncEventOutboxDao().countAll())
    }

    private fun openDatabase(name: String): AppDatabase {
        databaseNames += name
        val opened = Room.databaseBuilder(app, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
            .allowMainThreadQueries()
            .build()
        opened.openHelper.writableDatabase
        return opened
    }

    private fun stageFiles(): Sequence<File> =
        app.getDatabasePath(".").canonicalFile.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.name.startsWith("sync_recovery_stage_") }

    private fun assertForeignKeysClean(database: AppDatabase) {
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
    }

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

    companion object {
        private const val ACTIVE_DATABASE = "task139-recovery-active.db"
        private const val ACCOUNT = "10000000-0000-4000-8000-000000000001"
        private const val OLD_ACCOUNT = "10000000-0000-4000-8000-000000000002"
        private const val SHOP = "10000000-0000-4000-8000-000000000003"
        private const val DEVICE = "android-recovery-device"
        private const val OLD_GENERATION = "old-generation-139"
        private const val OLD_MANIFEST_PRODUCT = "30000000-0000-4000-8000-000000000139"
    }
}

private fun testGenerationSizeBytes(databaseFile: File): Long =
    listOf(
        databaseFile,
        File(databaseFile.path + "-journal"),
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm")
    ).filter(File::exists).sumOf(File::length)

private data class RecoveryFixture(
    val checkpoint: ShopSyncRecoveryCheckpoint,
    val rows: Map<ShopSyncRowDomain, ShopSyncRows>
)

private fun ShopSyncRowDomain.fixtureSyncEventDomain(): String = when (this) {
    ShopSyncRowDomain.SUPPLIERS,
    ShopSyncRowDomain.CATEGORIES,
    ShopSyncRowDomain.PRODUCTS,
    ShopSyncRowDomain.IMAGES -> SyncEventDomains.CATALOG
    ShopSyncRowDomain.PRICES -> SyncEventDomains.PRICES
    ShopSyncRowDomain.HISTORY -> SyncEventDomains.HISTORY
}

private class RecoveryRemoteFixture(val fixture: RecoveryFixture) : ShopSyncReadRemoteDataSource {
    var configured = true
    override val isConfigured: Boolean get() = configured
    var checkpoints = mutableListOf(fixture.checkpoint)
    var cancelAtDomain: ShopSyncRowDomain? = null
    var fatalAtDomain: Pair<ShopSyncRowDomain, Error>? = null
    var afterPage: (suspend (ShopSyncRowDomain) -> Unit)? = null
    var checkpointCalls = 0
    var pageCalls = 0
    val requestedPageLimits = mutableMapOf<ShopSyncRowDomain, MutableList<Int>>()
    val responseBytes = mutableMapOf<ShopSyncRowDomain, Long>()
    val largestRowBytes = mutableMapOf<ShopSyncRowDomain, Long>()
    var tailEvents: List<SyncEventRemoteRow> = emptyList()
    var tailRows: Map<ShopSyncRowDomain, ShopSyncRows>? = null
    var recoveryRows: Map<ShopSyncRowDomain, ShopSyncRows>? = null
    var recoveryCurrentScopeEventMaxId: String? = null
    var recoveryCurrentDomainEventMaxIds: Map<String, String>? = null
    var cancelAtTailEventPage = false
    var markerTransform: (ShopSyncConvergenceMarker) -> ShopSyncConvergenceMarker = { it }
    val tailEventContexts = mutableListOf<ShopSyncRpcContext>()
    val tailTargetedContexts = mutableListOf<ShopSyncRpcContext>()
    val tailTargetedRequests = mutableListOf<Pair<ShopSyncRowDomain, List<String>>>()

    override suspend fun checkpoint(
        context: ShopSyncRpcContext
    ): Result<ShopSyncRecoveryCheckpoint> {
        val template = checkpoints.getOrElse(checkpointCalls) { checkpoints.last() }
        checkpointCalls++
        // The actual V6 RPC echoes the baseline supplied by the caller. The
        // fixture keeps the material receipt declarative while modelling that
        // server-owned handshake for checkpoint B.
        return Result.success(
            template.copy(
                syncEvents = template.syncEvents.copy(
                    verifiedBaselineId = context.verifiedBaselineId
                )
            )
        )
    }

    override suspend fun convergenceMarker(
        context: ShopSyncRpcContext
    ): Result<ShopSyncConvergenceMarker> {
        val checkpoint = checkpoints.last()
        val marker = ShopSyncConvergenceMarker(
                schemaVersion = "shop-sync-convergence-marker-v1",
                status = "ready",
                shopId = context.shopId,
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
        return Result.success(markerTransform(marker))
    }

    override suspend fun recoveryPage(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        afterId: String?,
        limit: Int
    ): Result<ShopSyncRecoveryPage> {
        if (cancelAtDomain == domain) throw CancellationException("fixture_cancel")
        fatalAtDomain?.takeIf { it.first == domain }?.let { throw it.second }
        check(afterId == null)
        pageCalls++
        requestedPageLimits.getOrPut(domain) { mutableListOf() } += limit
        val rows = (recoveryRows ?: fixture.rows).getValue(domain)
        afterPage?.invoke(domain)
        return Result.success(
            ShopSyncRecoveryPage(
                schemaVersion = "shop-sync-recovery-page-v1",
                shopId = context.shopId,
                scope = fixture.checkpoint.scope,
                domain = domain,
                snapshotEventMaxId = requireNotNull(context.expectedEventMaxId),
                currentScopeEventMaxId = recoveryCurrentScopeEventMaxId
                    ?: requireNotNull(context.expectedEventMaxId),
                baselineDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
                pageDomainEventMaxId = recoveryCurrentDomainEventMaxIds
                    ?.get(domain.fixtureSyncEventDomain())
                    ?: requireNotNull(context.expectedDomainEventMaxId),
                domainScope = if (domain == ShopSyncRowDomain.HISTORY) {
                    fixture.checkpoint.scope.historyKind ?: fixture.checkpoint.scope.kind
                } else {
                    fixture.checkpoint.scope.kind
                },
                pageLimit = limit,
                rows = rows,
                nextAfterId = null,
                hasMore = false,
                responseBytes = responseBytes[domain] ?: 1L,
                largestRowBytes = largestRowBytes[domain] ?: 0L
            )
        )
    }

    override suspend fun eventPage(
        context: ShopSyncRpcContext,
        afterId: Long,
        limit: Int
    ): Result<ShopSyncEventPage> {
        if (cancelAtTailEventPage) throw CancellationException("fixture_tail_cancel")
        if (tailEvents.isEmpty()) {
            return Result.failure(IllegalStateException("fixture_event_page_not_configured"))
        }
        val frozenMax = requireNotNull(context.expectedEventMaxId)
        val fence = frozenMax.toLong()
        val checkpoint = checkpoints.lastOrNull { it.syncEvents.maxId == frozenMax }
            ?: return Result.failure(IllegalStateException("fixture_tail_checkpoint_missing"))
        val rows = tailEvents
            .asSequence()
            .filter { it.id > afterId && it.id <= fence }
            .sortedBy { it.id }
            .take(limit)
            .toList()
        val remaining = tailEvents.any { it.id > (rows.lastOrNull()?.id ?: afterId) && it.id <= fence }
        tailEventContexts += context
        return Result.success(
            ShopSyncEventPage(
                schemaVersion = "shop-sync-event-page-v1",
                shopId = context.shopId,
                scope = requireNotNull(context.expectedScope),
                scopeEventMaxId = frozenMax,
                asOfEventMaxId = frozenMax,
                asOfDomainEventMaxIds = checkpoint.syncEvents.domainMaxIds,
                pageLimit = limit,
                rows = rows,
                nextAfterId = rows.lastOrNull()?.id?.takeIf { remaining },
                hasMore = remaining
            )
        )
    }

    override suspend fun rowsByIds(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        ids: List<String>
    ): Result<ShopSyncTargetedRows> {
        val source = tailRows ?: return Result.failure(
            IllegalStateException("fixture_targeted_rows_not_configured")
        )
        val requested = ids.map(String::lowercase).toSet()
        val values = source.getValue(domain).filterRowsByIds(requested)
        val found = values.ids().map(String::lowercase).toSet()
        tailTargetedContexts += context
        tailTargetedRequests += domain to ids
        return Result.success(
            ShopSyncTargetedRows(
                schemaVersion = "shop-sync-rows-by-ids-v1",
                shopId = context.shopId,
                scope = requireNotNull(context.expectedScope),
                domain = domain,
                asOfEventMaxId = requireNotNull(context.expectedEventMaxId),
                currentScopeEventMaxId = requireNotNull(context.expectedEventMaxId),
                minimumDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
                materializedDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
                domainScope = if (domain == ShopSyncRowDomain.HISTORY) {
                    requireNotNull(context.expectedScope).historyKind
                        ?: requireNotNull(context.expectedScope).kind
                } else {
                    requireNotNull(context.expectedScope).kind
                },
                requestedCount = ids.size,
                rows = values,
                missingIds = ids.filterNot { it.lowercase() in found }
            )
        )
    }
}

private fun ShopSyncRows.filterRowsByIds(ids: Set<String>): ShopSyncRows = when (this) {
    is ShopSyncRows.Suppliers -> ShopSyncRows.Suppliers(
        values.filter { it.id.lowercase() in ids }
    )
    is ShopSyncRows.Categories -> ShopSyncRows.Categories(
        values.filter { it.id.lowercase() in ids }
    )
    is ShopSyncRows.Products -> ShopSyncRows.Products(
        values.filter { it.id.lowercase() in ids }
    )
    is ShopSyncRows.Prices -> ShopSyncRows.Prices(
        values.filter { it.id.lowercase() in ids }
    )
    is ShopSyncRows.History -> ShopSyncRows.History(
        values.filter { it.remoteId.lowercase() in ids }
    )
    is ShopSyncRows.Images -> ShopSyncRows.Images(
        values.filter { it.productId.lowercase() in ids }
    )
}

private fun targetFixture(
    productId: String = "20000000-0000-4000-8000-000000000003"
): RecoveryFixture {
    val supplierId = "20000000-0000-4000-8000-000000000001"
    val categoryId = "20000000-0000-4000-8000-000000000002"
    val priceId = "20000000-0000-4000-8000-000000000004"
    val historyId = "20000000-0000-4000-8000-000000000005"
    val imageVersionId = "20000000-0000-4000-8000-000000000006"
    val timestamp = "2026-07-21T10:00:00.000000Z"
    val owner = "10000000-0000-4000-8000-000000000001"
    val shop = "10000000-0000-4000-8000-000000000003"
    val supplier = InventorySupplierRow(supplierId, owner, shop, "Target supplier", timestamp)
    val category = InventoryCategoryRow(categoryId, owner, shop, "Target category", timestamp)
    val product = InventoryProductRow(
        id = productId,
        ownerUserId = owner,
        shopId = shop,
        barcode = "target-barcode",
        productName = "Target product",
        purchasePrice = 4.0,
        retailPrice = 7.0,
        supplierId = supplierId,
        categoryId = categoryId,
        stockQuantity = 3.0,
        primaryImageVersionId = imageVersionId,
        primaryImageUpdatedAt = timestamp,
        updatedAt = timestamp
    )
    val price = InventoryProductPriceRow(
        id = priceId,
        ownerUserId = owner,
        shopId = shop,
        productId = productId,
        type = "RETAIL",
        price = 7.0,
        priceCanonical = "7",
        effectiveAt = "2026-07-21 10:00:00",
        source = "REMOTE",
        createdAt = "2026-07-21 10:00:00",
        updatedAt = timestamp
    )
    val history = SharedSheetSessionRecord(
        remoteId = historyId,
        payloadVersion = 1,
        displayName = "Target history",
        timestamp = "2026-07-21 10:00:00",
        supplier = "Target supplier",
        category = "Target category",
        isManualEntry = false,
        data = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("target-barcode", "4", "1")
        ),
        dataCheckpointDigest = "e".repeat(64),
        overlayCheckpointDigest = "f".repeat(64),
        ownerUserId = owner,
        shopId = shop,
        updatedAt = timestamp
    )
    val image = ShopSyncImageRow(
        productId = productId,
        ownerUserId = owner,
        shopId = shop,
        versionId = imageVersionId,
        status = "ready",
        finalizedAt = timestamp,
        main = ShopSyncImageVariantRow("a".repeat(64), 1000L, 800, 800, "image/jpeg"),
        thumb = ShopSyncImageVariantRow("b".repeat(64), 200L, 200, 200, "image/jpeg")
    )
    val supplierCheckpoint = checkpointDomain(
        ids = listOf(supplierId),
        versions = listOf(line(supplierId, timestamp, "-"))
    )
    val categoryCheckpoint = checkpointDomain(
        ids = listOf(categoryId),
        versions = listOf(line(categoryId, timestamp, "-"))
    )
    val productCheckpoint = checkpointDomain(
        ids = listOf(productId),
        versions = listOf(testProductVersion(product)),
        identities = listOf(testProductIdentity(product))
    )
    val priceCheckpoint = checkpointDomain(
        ids = listOf(priceId),
        versions = listOf(testPriceVersion(price))
    )
    val historyCheckpoint = checkpointDomain(
        ids = listOf(historyId),
        versions = listOf(testHistoryVersion(history))
    )
    val imageCheckpoint = checkpointDomain(
        ids = listOf(productId),
        versions = listOf(testImageVersion(image))
    )
    val catalogDigest = testSha256(
        supplierCheckpoint.versionDigest + "\n" +
            categoryCheckpoint.versionDigest + "\n" +
            productCheckpoint.versionDigest
    )
    val checkpoint = ShopSyncRecoveryCheckpoint(
        schemaVersion = "shop-sync-recovery-checkpoint-v1",
        status = "ready",
        shopId = shop,
        scope = ShopSyncScope(
            kind = ShopSyncScopeKinds.SHOP_SCOPED,
            key = "c".repeat(64),
            historyKind = ShopSyncScopeKinds.SHOP_SCOPED,
            accountKey = testSha256(owner.lowercase()),
            deviceKey = testSha256("android-recovery-device")
        ),
        syncEvents = ShopSyncEventCheckpoint(
            maxId = "42",
            verifiedBaselineId = "0",
            requiresFullRecovery = false,
            domainMaxIds = mapOf(
                SyncEventDomains.CATALOG to "42",
                SyncEventDomains.PRICES to "42",
                SyncEventDomains.HISTORY to "42"
            )
        ),
        catalog = ShopSyncCatalogCheckpoint(
            suppliers = supplierCheckpoint,
            categories = categoryCheckpoint,
            products = productCheckpoint,
            digest = catalogDigest
        ),
        prices = priceCheckpoint,
        history = historyCheckpoint,
        images = imageCheckpoint,
        integrity = ShopSyncIntegrityCheckpoint(0, 0, 0, 0, 0, 0),
        checkpointDigest = "d".repeat(64)
    )
    return RecoveryFixture(
        checkpoint = checkpoint,
        rows = mapOf(
            ShopSyncRowDomain.SUPPLIERS to ShopSyncRows.Suppliers(listOf(supplier)),
            ShopSyncRowDomain.CATEGORIES to ShopSyncRows.Categories(listOf(category)),
            ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(product)),
            ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(listOf(price)),
            ShopSyncRowDomain.HISTORY to ShopSyncRows.History(listOf(history)),
            ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(listOf(image))
        )
    )
}

private fun v2HistoryFixture(): RecoveryFixture {
    val base = targetFixture()
    val history = (base.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History)
        .values
        .single()
        .copy(
            payloadVersion = SESSION_PAYLOAD_VERSION,
            displayName = "Target history v2",
            sessionOverlay = SessionOverlay(
                editable = listOf(listOf("", ""), listOf("", "")),
                complete = listOf(false, false)
            )
        )
    val historyCheckpoint = checkpointDomain(
        ids = listOf(history.remoteId),
        versions = listOf(testHistoryVersion(history))
    )
    return base.copy(
        checkpoint = base.checkpoint.copy(
            history = historyCheckpoint,
            checkpointDigest = "2".repeat(64)
        ),
        rows = base.rows + (ShopSyncRowDomain.HISTORY to ShopSyncRows.History(listOf(history)))
    )
}

/** V6 represents a deleted product's former primary image as an image tombstone. */
private fun deletedProductImageFixture(): RecoveryFixture {
    val base = targetFixture()
    val baseCheckpoint = base.checkpoint
    val product = (base.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
        .values
        .single()
        .copy(
            categoryId = null,
            supplierId = null,
            primaryImageVersionId = null,
            primaryImageUpdatedAt = null,
            updatedAt = "2026-07-21T10:00:02.000000Z",
            deletedAt = "2026-07-21T10:00:02.000000Z"
        )
    val image = (base.rows.getValue(ShopSyncRowDomain.IMAGES) as ShopSyncRows.Images)
        .values
        .single()
        .copy(productDeletedAt = requireNotNull(product.deletedAt))
    val empty = checkpointDomain(emptyList(), emptyList())
    val productCheckpoint = checkpointDomain(
        ids = listOf(product.id),
        versions = listOf(testProductVersion(product)),
        identities = listOf(testProductIdentity(product)),
        activeCount = 0,
        tombstoneCount = 1
    )
    val imageCheckpoint = checkpointDomain(
        ids = listOf(image.productId),
        versions = listOf(testImageVersion(image)),
        activeCount = 0,
        tombstoneCount = 1
    )
    val catalog = ShopSyncCatalogCheckpoint(
        suppliers = empty,
        categories = empty,
        products = productCheckpoint,
        digest = testSha256(
            empty.versionDigest + "\n" + empty.versionDigest + "\n" + productCheckpoint.versionDigest
        )
    )
    return RecoveryFixture(
        checkpoint = baseCheckpoint.copy(
            catalog = catalog,
            prices = empty,
            history = empty,
            images = imageCheckpoint,
            checkpointDigest = "1".repeat(64)
        ),
        rows = mapOf(
            ShopSyncRowDomain.SUPPLIERS to ShopSyncRows.Suppliers(emptyList()),
            ShopSyncRowDomain.CATEGORIES to ShopSyncRows.Categories(emptyList()),
            ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(product)),
            ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(emptyList()),
            ShopSyncRowDomain.HISTORY to ShopSyncRows.History(emptyList()),
            ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(listOf(image))
        )
    )
}

private fun emptyTargetFixture(): RecoveryFixture {
    val base = targetFixture().checkpoint
    val empty = checkpointDomain(ids = emptyList(), versions = emptyList())
    val emptyProducts = checkpointDomain(
        ids = emptyList(),
        versions = emptyList(),
        identities = emptyList()
    )
    val catalog = ShopSyncCatalogCheckpoint(
        suppliers = empty,
        categories = empty,
        products = emptyProducts,
        digest = testSha256(
            empty.versionDigest + "\n" + empty.versionDigest + "\n" + emptyProducts.versionDigest
        )
    )
    return RecoveryFixture(
        checkpoint = base.copy(
            catalog = catalog,
            prices = empty,
            history = empty,
            images = empty,
            checkpointDigest = "e".repeat(64)
        ),
        rows = mapOf(
            ShopSyncRowDomain.SUPPLIERS to ShopSyncRows.Suppliers(emptyList()),
            ShopSyncRowDomain.CATEGORIES to ShopSyncRows.Categories(emptyList()),
            ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(emptyList()),
            ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(emptyList()),
            ShopSyncRowDomain.HISTORY to ShopSyncRows.History(emptyList()),
            ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(emptyList())
        )
    )
}

private fun checkpointDomain(
    ids: List<String>,
    versions: List<String>,
    identities: List<String>? = null,
    activeCount: Long = ids.size.toLong(),
    tombstoneCount: Long = 0L
): ShopSyncDomainCheckpoint = ShopSyncDomainCheckpoint(
    activeCount = activeCount,
    tombstoneCount = tombstoneCount,
    idSetDigest = testLineDigest(ids),
    versionDigest = testLineDigest(versions),
    identityDigest = identities?.let(::testLineDigest)
)

private fun line(vararg values: String): String = values.joinToString("\u001f")
private fun testLineDigest(lines: List<String>): String = lines.fold(testSha256("")) { state, line ->
    testSha256("$state\u001f${line.toByteArray(Charsets.UTF_8).size}:$line")
}

private fun testProductVersion(row: InventoryProductRow): String {
    val active = row.deletedAt == null
    return line(
        row.id,
        requireNotNull(row.updatedAt),
        row.deletedAt ?: "-",
        row.categoryId.takeIf { active } ?: "-",
        row.supplierId.takeIf { active } ?: "-",
        row.primaryImageVersionId.takeIf { active } ?: "-",
        row.primaryImageUpdatedAt.takeIf { active } ?: "-"
    )
}

private fun testProductIdentity(row: InventoryProductRow): String = line(
    row.id,
    testSha256(row.barcode),
    testSha256(row.itemNumber.orEmpty())
)

private fun testPriceVersion(row: InventoryProductPriceRow): String = line(
    row.id,
    requireNotNull(row.updatedAt),
    row.productId,
    requireNotNull(row.priceCanonical),
    row.type,
    row.effectiveAt,
    row.createdAt,
    testSha256(row.source.orEmpty()),
    testSha256(row.note.orEmpty())
)

private fun testHistoryVersion(row: SharedSheetSessionRecord): String {
    val prefix = listOf(
        row.remoteId,
        requireNotNull(row.updatedAt),
        row.deletedAt ?: "-",
        row.payloadVersion.toString()
    )
    return if (row.deletedAt != null) {
        (prefix + "-").joinToString("\u001f")
    } else {
        (prefix + listOf(
            row.timestamp,
            testSha256(row.supplier),
            testSha256(row.category),
            row.isManualEntry.toString(),
            testSha256(row.displayName.orEmpty()),
            requireNotNull(row.dataCheckpointDigest),
            requireNotNull(row.overlayCheckpointDigest)
        )).joinToString("\u001f")
    }
}

private fun testImageVersion(row: ShopSyncImageRow): String = line(
    row.productId,
    row.versionId,
    row.status,
    row.productDeletedAt ?: "-",
    row.finalizedAt,
    row.main.sha256,
    row.main.bytes.toString(),
    row.main.width.toString(),
    row.main.height.toString(),
    row.main.mime,
    row.thumb.sha256,
    row.thumb.bytes.toString(),
    row.thumb.width.toString(),
    row.thumb.height.toString(),
    row.thumb.mime
)

/**
 * Local no-work adapters keep the recovery→incremental proof self-contained.
 * The test must never import private fixtures from DefaultInventoryRepositoryTest.
 */
private object NoOpCatalogRemoteForRecoveryTest : CatalogRemoteDataSource {
    override val isConfigured: Boolean = true

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> =
        error("catalog upsert is not expected after a verified no-work marker")

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
        error("catalog upsert is not expected after a verified no-work marker")

    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> =
        error("catalog upsert is not expected after a verified no-work marker")

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
        error("catalog fetch is not expected after a verified no-work marker")

    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle> =
        error("catalog fetch is not expected after a verified no-work marker")

    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        error("catalog tombstone is not expected after a verified no-work marker")

    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        error("catalog tombstone is not expected after a verified no-work marker")

    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        error("catalog tombstone is not expected after a verified no-work marker")
}

private object NoOpPriceRemoteForRecoveryTest : ProductPriceRemoteDataSource {
    override val isConfigured: Boolean = true

    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> =
        error("price upsert is not expected after a verified no-work marker")

    override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> =
        error("price fetch is not expected after a verified no-work marker")

    override suspend fun fetchProductPricesByIds(
        remoteIds: Set<String>
    ): Result<List<InventoryProductPriceRow>> =
        error("price fetch is not expected after a verified no-work marker")
}

private object NoOpSyncEventRemoteForRecoveryTest : SyncEventRemoteDataSource {
    override val isConfigured: Boolean = true

    override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
        Result.success(
            SyncEventRemoteCapabilities(
                syncEventsAvailable = true,
                recordSyncEventAvailable = true,
                realtimeSyncEventsAvailable = true
            )
        )

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> =
        error("event write is not expected after a verified no-work marker")

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> = Result.success(emptyList())
}
private fun testSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

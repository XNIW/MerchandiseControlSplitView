package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Task139BusinessDataScopeBindingTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultInventoryRepository(db)
    }

    @After
    fun teardown() {
        DefaultInventoryRepositoryTestHooks.beforeLocalProductInsert = null
        db.close()
    }

    @Test
    fun `binding policy separates empty unbound dirty same scope and mismatches`() {
        val scopeA = scope(ownerUserId = OWNER_A, shopId = SHOP_A)
        val scopeB = scope(ownerUserId = OWNER_B, shopId = SHOP_A)
        val scopeOtherShop = scope(ownerUserId = OWNER_A, shopId = SHOP_B)

        assertEquals(
            Task126BusinessDataBindingDecision.BindEmpty,
            Task126OwnerStoreGate.resolveBinding(null, scopeA, emptySnapshot())
        )

        val dirtySnapshot = emptySnapshot().copy(products = 1, pendingLocalChanges = 1)
        assertEquals(
            Task126BusinessDataBindingDecision.ReviewRequiredUnbound(dirtySnapshot),
            Task126OwnerStoreGate.resolveBinding(null, scopeA, dirtySnapshot)
        )

        assertEquals(
            Task126BusinessDataBindingDecision.AllowExisting,
            Task126OwnerStoreGate.resolveBinding(scopeA, scopeA, dirtySnapshot)
        )
        assertEquals(
            Task126BusinessDataBindingDecision.Blocked(
                Task126OwnerStoreGateDecision.Reason.OwnerMismatch
            ),
            Task126OwnerStoreGate.resolveBinding(scopeA, scopeB, dirtySnapshot)
        )
        assertEquals(
            Task126BusinessDataBindingDecision.Blocked(
                Task126OwnerStoreGateDecision.Reason.StoreMismatch
            ),
            Task126OwnerStoreGate.resolveBinding(scopeA, scopeOtherShop, dirtySnapshot)
        )
        assertEquals(
            Task126BusinessDataBindingDecision.Blocked(
                Task126OwnerStoreGateDecision.Reason.SchemaMismatch
            ),
            Task126OwnerStoreGate.resolveBinding(
                scopeA,
                Task126OwnerStoreScope(
                    ownerHash = scopeA.ownerHash,
                    storeId = scopeA.storeId,
                    localStoreId = scopeA.localStoreId,
                    storeEpoch = scopeA.storeEpoch + 1
                ),
                dirtySnapshot
            )
        )
    }

    @Test
    fun `unbound empty database binds atomically and returns verifiable ready scope`() = runTest {
        val activeScope = scope(ownerUserId = OWNER_A, shopId = SHOP_A)

        val state = repository.resolveBusinessDataScope(activeScope)

        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        assertScopeMatches(activeScope, state.boundScope)
        val persisted = db.businessDataScopeBindingDao().get()
        assertNotNull(persisted)
        assertEquals(BusinessDataScopeBinding.SINGLETON_ID, persisted!!.id)
        assertTrue(persisted.boundAtMs > 0L)
        assertEquals(
            Task126OwnerStoreGateDecision.Allowed,
            Task126OwnerStoreGate.validate(persisted.toOwnerStoreScope(), activeScope)
        )
        assertEquals(emptySnapshot(), repository.getLocalDatabaseStatusSnapshot(null, null))
    }

    @Test
    fun `unbound product database requires review without adopting or deleting data`() = runTest {
        db.productDao().insert(
            Product(
                barcode = "task139-unbound-product",
                productName = "TASK139 unbound product"
            )
        )

        val state = repository.resolveBusinessDataScope(scope(OWNER_A, SHOP_A))

        assertEquals(Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND, state.status)
        assertEquals(1, state.localSnapshot!!.products)
        assertTrue(state.localSnapshot.pendingLocalChanges > 0)
        assertNull(db.businessDataScopeBindingDao().get())
        assertNotNull(db.productDao().findByBarcode("task139-unbound-product"))
    }

    @Test
    fun `unbound database with only global outbox requires review`() = runTest {
        db.syncEventOutboxDao().insert(outboxEntry(ownerUserId = OWNER_B, storeScope = "shop:$SHOP_B"))

        val state = repository.resolveBusinessDataScope(scope(OWNER_A, SHOP_A))

        assertEquals(Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND, state.status)
        assertEquals(0, state.localSnapshot!!.products)
        assertEquals(1, state.localSnapshot.syncEventOutboxPending)
        assertEquals(1, state.localSnapshot.pendingLocalChanges)
        assertNull(db.businessDataScopeBindingDao().get())
        assertEquals(1, db.syncEventOutboxDao().countAll())
    }

    @Test
    fun `same owner and shop resumes with existing binding and preserves local work`() = runTest {
        val activeScope = scope(OWNER_A, SHOP_A)
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(activeScope, boundAtMs = 139L)
        )
        db.productDao().insert(Product(barcode = "task139-same-scope", productName = "Same scope"))
        db.syncEventOutboxDao().insert(outboxEntry(OWNER_A, "shop:$SHOP_A"))

        val state = repository.resolveBusinessDataScope(activeScope)

        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        assertScopeMatches(activeScope, state.boundScope)
        assertEquals(139L, db.businessDataScopeBindingDao().get()!!.boundAtMs)
        assertNotNull(db.productDao().findByBarcode("task139-same-scope"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
    }

    @Test
    fun `different owner binding fails closed and preserves binding and local work`() = runTest {
        val boundScope = scope(OWNER_A, SHOP_A)
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(boundScope, boundAtMs = 139L)
        )
        db.productDao().insert(Product(barcode = "task139-owner-mismatch", productName = "Owner mismatch"))
        db.syncEventOutboxDao().insert(outboxEntry(OWNER_A, "shop:$SHOP_A"))

        val state = repository.resolveBusinessDataScope(scope(OWNER_B, SHOP_A))

        assertEquals(Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH, state.status)
        assertScopeMatches(boundScope, state.boundScope)
        assertScopeMatches(boundScope, db.businessDataScopeBindingDao().get()!!.toOwnerStoreScope())
        assertNotNull(db.productDao().findByBarcode("task139-owner-mismatch"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
    }

    @Test
    fun `different shop binding fails closed and preserves binding and local work`() = runTest {
        val boundScope = scope(OWNER_A, SHOP_A)
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(boundScope, boundAtMs = 139L)
        )
        db.productDao().insert(Product(barcode = "task139-shop-mismatch", productName = "Shop mismatch"))
        db.syncEventOutboxDao().insert(outboxEntry(OWNER_A, "shop:$SHOP_A"))

        val state = repository.resolveBusinessDataScope(scope(OWNER_A, SHOP_B))

        assertEquals(Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH, state.status)
        assertScopeMatches(boundScope, state.boundScope)
        assertScopeMatches(boundScope, db.businessDataScopeBindingDao().get()!!.toOwnerStoreScope())
        assertNotNull(db.productDao().findByBarcode("task139-shop-mismatch"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
    }

    @Test
    fun `confirmed unbound discard commits cleanup and binding while preserving device identity`() = runTest {
        val activeScope = scope(OWNER_A, SHOP_A)
        insertDiscardFixture()

        val state = repository.discardUnboundBusinessDataAndBind(activeScope)

        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        assertScopeMatches(activeScope, db.businessDataScopeBindingDao().get()!!.toOwnerStoreScope())
        assertEquals(0, db.productDao().count())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        assertNull(db.syncEventWatermarkDao().get(OWNER_B, "shop:$SHOP_B"))
        assertNull(db.syncEventApplyStatusDao().get(OWNER_B, "shop:$SHOP_B", 139L))
        assertEquals(DEVICE_ID, db.syncEventDeviceStateDao().get()!!.deviceId)
    }

    @Test
    fun `confirmed unbound discard rolls back all cleanup when binding insert fails`() = runTest {
        val activeScope = scope(OWNER_A, SHOP_A)
        insertDiscardFixture()
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER task139_fail_binding_insert
            BEFORE INSERT ON business_data_scope_binding
            BEGIN
                SELECT RAISE(ABORT, 'task139 fixture binding failure');
            END
            """.trimIndent()
        )

        val failure = runCatching {
            repository.discardUnboundBusinessDataAndBind(activeScope)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertNull(db.businessDataScopeBindingDao().get())
        assertNotNull(db.productDao().findByBarcode("task139-discard-fixture"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
        assertNotNull(db.syncEventWatermarkDao().get(OWNER_B, "shop:$SHOP_B"))
        assertNotNull(db.syncEventApplyStatusDao().get(OWNER_B, "shop:$SHOP_B", 139L))
        assertEquals(DEVICE_ID, db.syncEventDeviceStateDao().get()!!.deviceId)
    }

    @Test
    fun `discard action cannot replace an existing mismatched owner binding`() = runTest {
        val boundScope = scope(OWNER_A, SHOP_A)
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(boundScope, boundAtMs = 139L)
        )
        db.productDao().insert(Product(barcode = "task139-bound-discard", productName = "Bound fixture"))
        db.syncEventOutboxDao().insert(outboxEntry(OWNER_A, "shop:$SHOP_A"))

        val state = repository.discardUnboundBusinessDataAndBind(scope(OWNER_B, SHOP_A))

        assertEquals(Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH, state.status)
        assertScopeMatches(boundScope, db.businessDataScopeBindingDao().get()!!.toOwnerStoreScope())
        assertNotNull(db.productDao().findByBarcode("task139-bound-discard"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
    }

    @Test
    fun `explicit mismatch replacement journals intent without exposing an empty generation`() = runTest {
        val previousScope = scope(OWNER_A, SHOP_A)
        val activeScope = scope(OWNER_B, SHOP_B)
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(previousScope, boundAtMs = 139L)
        )
        insertDiscardFixture()

        val state = repository.replaceMismatchedBusinessDataAndBind(activeScope)

        assertEquals(Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, state.status)
        assertEquals("sync_recovery_required", state.errorCode)
        assertScopeMatches(previousScope, db.businessDataScopeBindingDao().get()!!.toOwnerStoreScope())
        assertEquals(1, db.productDao().count())
        assertEquals(1, db.syncEventOutboxDao().countAll())
        assertNotNull(db.syncEventWatermarkDao().get(OWNER_B, "shop:$SHOP_B"))
        assertNotNull(db.syncEventApplyStatusDao().get(OWNER_B, "shop:$SHOP_B", 139L))
        assertEquals(DEVICE_ID, db.syncEventDeviceStateDao().get()!!.deviceId)
        val journal = db.syncRecoveryJournalDao().get()!!
        assertEquals(activeScope.ownerHash, journal.ownerHash)
        assertEquals(activeScope.storeId, journal.storeScope)
        assertEquals(SHOP_B, journal.shopId)
        assertEquals(DEVICE_ID, journal.deviceId)
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, journal.phase)
        assertEquals("scope_mismatch_replace_confirmed", journal.reason)
    }

    @Test
    fun `mismatch recovery attempt counter saturates instead of wrapping`() = runTest {
        val previousScope = scope(OWNER_A, SHOP_A)
        val activeScope = scope(OWNER_B, SHOP_B)
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(previousScope, boundAtMs = 139L)
        )
        insertDiscardFixture()
        repository.replaceMismatchedBusinessDataAndBind(activeScope)

        listOf(Int.MAX_VALUE, -1).forEach { corruptAttemptCount ->
            db.syncRecoveryJournalDao().upsert(
                requireNotNull(db.syncRecoveryJournalDao().get()).copy(
                    attemptCount = corruptAttemptCount
                )
            )

            val state = repository.replaceMismatchedBusinessDataAndBind(activeScope)

            assertEquals(Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, state.status)
            assertEquals(
                SYNC_RECOVERY_MAX_RECORDED_ATTEMPTS,
                db.syncRecoveryJournalDao().get()?.attemptCount
            )
        }
        assertScopeMatches(
            previousScope,
            db.businessDataScopeBindingDao().get()?.toOwnerStoreScope()
        )
        assertEquals(1, db.syncEventOutboxDao().countAll())
    }

    @Test
    fun `explicit mismatch replacement rolls back intent when journal insert fails`() = runTest {
        val previousScope = scope(OWNER_A, SHOP_A)
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(previousScope, boundAtMs = 139L)
        )
        insertDiscardFixture()
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER task139_fail_recovery_journal
            BEFORE INSERT ON sync_recovery_journal
            BEGIN
                SELECT RAISE(ABORT, 'task139 fixture recovery journal failure');
            END
            """.trimIndent()
        )

        val failure = runCatching {
            repository.replaceMismatchedBusinessDataAndBind(scope(OWNER_B, SHOP_B))
        }.exceptionOrNull()

        assertNotNull(failure)
        assertScopeMatches(previousScope, db.businessDataScopeBindingDao().get()!!.toOwnerStoreScope())
        assertNotNull(db.productDao().findByBarcode("task139-discard-fixture"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
        assertNotNull(db.syncEventWatermarkDao().get(OWNER_B, "shop:$SHOP_B"))
        assertNotNull(db.syncEventApplyStatusDao().get(OWNER_B, "shop:$SHOP_B", 139L))
        assertEquals(DEVICE_ID, db.syncEventDeviceStateDao().get()!!.deviceId)
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun `explicit mismatch replacement is a no-op for an already matching binding`() = runTest {
        val activeScope = scope(OWNER_A, SHOP_A)
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(activeScope, boundAtMs = 139L)
        )
        db.productDao().insert(Product(barcode = "task139-matching-replace", productName = "Keep"))
        db.syncEventOutboxDao().insert(outboxEntry(OWNER_A, "shop:$SHOP_A"))

        val state = repository.replaceMismatchedBusinessDataAndBind(activeScope)

        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        assertNotNull(db.productDao().findByBarcode("task139-matching-replace"))
        assertEquals(1, db.syncEventOutboxDao().countAll())
        assertEquals(139L, db.businessDataScopeBindingDao().get()!!.boundAtMs)
    }

    @Test
    fun `explicit account shop replacement cannot bypass a schema mismatch`() = runTest {
        val activeScope = scope(OWNER_A, SHOP_A)
        val incompatibleScope = Task126OwnerStoreScope(
            ownerHash = activeScope.ownerHash,
            storeId = activeScope.storeId,
            localStoreId = activeScope.localStoreId,
            schemaVersion = activeScope.schemaVersion + 1
        )
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(incompatibleScope, boundAtMs = 139L)
        )
        db.productDao().insert(Product(barcode = "task139-schema-replace", productName = "Keep"))

        val state = repository.replaceMismatchedBusinessDataAndBind(activeScope)

        assertEquals(Task126BusinessDataScopeStatus.BLOCKED_SCHEMA_MISMATCH, state.status)
        assertScopeMatches(incompatibleScope, db.businessDataScopeBindingDao().get()!!.toOwnerStoreScope())
        assertNotNull(db.productDao().findByBarcode("task139-schema-replace"))
    }

    @Test
    fun `deferred history response from previous scope cannot contaminate replacement`() = runTest {
        val previousScope = scope(OWNER_A, SHOP_A)
        val activeScope = scope(OWNER_B, SHOP_B)
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.READY,
                boundScope = previousScope
            )
        )
        val guardedRepository = DefaultInventoryRepository(
            db = db,
            businessDataScopeRuntimeGuard = tracker
        )
        db.businessDataScopeBindingDao().upsert(
            BusinessDataScopeBinding.from(previousScope, boundAtMs = 139L)
        )
        db.syncEventDeviceStateDao().insert(
            SyncEventDeviceState(deviceId = DEVICE_ID, createdAtMs = 139L)
        )
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var outboundUpsertCalls = 0
        val remote = object : SessionBackupRemoteDataSource {
            override val isConfigured: Boolean = true

            override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> {
                fetchStarted.complete(Unit)
                withContext(NonCancellable) { releaseFetch.await() }
                return Result.success(
                    listOf(
                        SharedSheetSessionRecord(
                            remoteId = "00000000-0000-4000-8000-000000000339",
                            payloadVersion = 1,
                            timestamp = "2026-07-19 12:00:00",
                            supplier = "Scope A",
                            category = "Deferred",
                            isManualEntry = true,
                            data = listOf(listOf("old-scope")),
                            ownerUserId = OWNER_A,
                            shopId = SHOP_A
                        )
                    )
                )
            }

            override suspend fun fetchSessionsByRemoteIds(
                remoteIds: Set<String>
            ): Result<List<SharedSheetSessionRecord>> = Result.success(emptyList())

            override suspend fun upsertSessions(
                rows: List<SharedSheetSessionUpsertRow>
            ): Result<Unit> {
                outboundUpsertCalls++
                return Result.success(Unit)
            }
        }

        val oldScopePull = async {
            runCatching {
                tracker.withBusinessDataScopeFlight(OWNER_A, selectedShop(SHOP_A)) {
                    guardedRepository.bootstrapHistorySessionsFromRemote(remote).getOrThrow()
                }
            }
        }
        fetchStarted.await()

        tracker.updateBusinessDataScopeState(
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
                boundScope = previousScope
            )
        )
        val replacement = async {
            tracker.withBusinessDataScopeTransition {
                val state = guardedRepository.replaceMismatchedBusinessDataAndBind(activeScope)
                tracker.updateBusinessDataScopeState(state)
                state
            }
        }
        testScheduler.runCurrent()
        assertFalse(replacement.isCompleted)

        releaseFetch.complete(Unit)
        val oldScopeFailure = oldScopePull.await().exceptionOrNull()
        val replacementState = replacement.await()

        assertTrue(oldScopeFailure is Task126BusinessDataScopeChangedException)
        assertEquals(Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, replacementState.status)
        assertScopeMatches(previousScope, db.businessDataScopeBindingDao().get()!!.toOwnerStoreScope())
        assertEquals(0, db.historyEntryDao().countUserVisible())
        assertEquals(0, outboundUpsertCalls)
        assertEquals(activeScope.ownerHash, db.syncRecoveryJournalDao().get()!!.ownerHash)
    }

    @Test
    fun `local writer admitted by old scope cannot commit after replacement boundary`() = runTest {
        val previousScope = scope(OWNER_A, SHOP_A)
        val activeScope = scope(OWNER_B, SHOP_B)
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(previousScope)
        )
        val guardedRepository = DefaultInventoryRepository(
            db = db,
            businessDataScopeRuntimeGuard = tracker
        )
        val writerStarted = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        DefaultInventoryRepositoryTestHooks.beforeLocalProductInsert = {
            writerStarted.complete(Unit)
            withContext(NonCancellable) { releaseWriter.await() }
        }

        val oldScopeWriter = async {
            runCatching {
                guardedRepository.addProduct(
                    Product(
                        barcode = "task139-stale-local-writer",
                        productName = "Old scope mutation"
                    )
                )
            }
        }
        writerStarted.await()

        val transition = async {
            tracker.withBusinessDataScopeTransition {
                tracker.updateBusinessDataScopeState(
                    Task126BusinessDataScopeState.ready(activeScope)
                )
            }
        }
        testScheduler.runCurrent()
        val transitionCompletedBeforeWriterQuiesced = transition.isCompleted

        releaseWriter.complete(Unit)
        val writerFailure = oldScopeWriter.await().exceptionOrNull()
        transition.await()

        assertFalse(transitionCompletedBeforeWriterQuiesced)
        assertTrue(writerFailure is Task126BusinessDataScopeChangedException)
        assertNull(db.productDao().findByBarcode("task139-stale-local-writer"))
        assertTrue(tracker.allowsBusinessDataScope(OWNER_B, selectedShop(SHOP_B)))
    }

    private suspend fun insertDiscardFixture() {
        db.productDao().insert(
            Product(barcode = "task139-discard-fixture", productName = "Discard fixture")
        )
        db.syncEventOutboxDao().insert(outboxEntry(OWNER_B, "shop:$SHOP_B"))
        db.syncEventWatermarkDao().upsert(
            SyncEventWatermark(
                ownerUserId = OWNER_B,
                storeScope = "shop:$SHOP_B",
                lastSyncEventId = 139L
            )
        )
        db.syncEventApplyStatusDao().upsert(
            SyncEventApplyStatus(
                ownerUserId = OWNER_B,
                storeScope = "shop:$SHOP_B",
                eventId = 139L,
                shopId = SHOP_B,
                domain = SyncEventDomains.CATALOG,
                entityType = "product",
                entityIdsJson = "{}",
                status = "blocked",
                reason = "fixture",
                attemptCount = 1,
                lastAttemptAtMs = 139L,
                nextRetryAtMs = null,
                correlationId = null,
                clientEventId = "task139-apply-fixture",
                remoteCreatedAt = "2026-07-19T00:00:00Z"
            )
        )
        db.syncEventDeviceStateDao().insert(
            SyncEventDeviceState(deviceId = DEVICE_ID, createdAtMs = 139L)
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

    private fun outboxEntry(ownerUserId: String, storeScope: String): SyncEventOutboxEntry =
        SyncEventOutboxEntry(
            ownerUserId = ownerUserId,
            storeScope = storeScope,
            domain = SyncEventDomains.CATALOG,
            eventType = SyncEventTypes.CATALOG_CHANGED,
            source = "android-test",
            sourceDeviceId = DEVICE_ID,
            batchId = "task139-binding-fixture",
            clientEventId = "task139-${ownerUserId.takeLast(4)}-${storeScope.takeLast(4)}",
            changedCount = 1,
            entityIdsJson = "{}",
            metadataJson = "{}",
            createdAtMs = 139L
        )

    private fun emptySnapshot(): LocalDatabaseStatusSnapshot =
        LocalDatabaseStatusSnapshot(
            products = 0,
            suppliers = 0,
            categories = 0,
            priceHistoryRows = 0,
            historySessions = 0,
            pendingLocalChanges = 0,
            syncEventOutboxPending = 0
        )

    private fun assertScopeMatches(
        expected: Task126OwnerStoreScope,
        actual: Task126OwnerStoreScope?
    ) {
        assertNotNull(actual)
        actual!!
        assertEquals(expected.ownerHash, actual.ownerHash)
        assertEquals(expected.storeId, actual.storeId)
        assertEquals(expected.localStoreId, actual.localStoreId)
        assertEquals(expected.syncProtocolVersion, actual.syncProtocolVersion)
        assertEquals(expected.schemaVersion, actual.schemaVersion)
        assertEquals(expected.storeEpoch, actual.storeEpoch)
    }

    private companion object {
        const val OWNER_A = "00000000-0000-4000-8000-000000000139"
        const val OWNER_B = "00000000-0000-4000-8000-000000000239"
        const val SHOP_A = "task139-shop-a"
        const val SHOP_B = "task139-shop-b"
        const val DEVICE_ID = "task139-stable-device"
    }
}

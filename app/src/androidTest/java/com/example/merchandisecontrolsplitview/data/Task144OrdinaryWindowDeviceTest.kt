package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native Room/adapter/repository proof only. No UI, real network or scheduler-liveness claim. */
@RunWith(AndroidJUnit4::class)
class Task144OrdinaryWindowDeviceTest {
    @Test
    fun healthyIncrementalPublishesCapturedWindowWithoutRecovery() = ordinaryCase(OrdinaryCase.HEALTHY) { f ->
        val before = f.publication()
        val generation = requireNotNull(f.db.syncRecoveryBaselineDao().get()).generationId
        val binding = f.db.businessDataScopeBindingDao().get()
        val device = f.db.syncEventDeviceStateDao().get()
        f.drain()
        val summary = requireNotNull(f.tracker.lastOutcome.value).summary
        assertEquals(1, summary.syncEventsFetched)
        assertEquals(1, summary.syncEventsProcessed)
        assertEquals(1, summary.targetedProductsFetched)
        assertEquals(45L, summary.syncEventsWatermarkAfter)
        assertFalse(summary.manualFullSyncRequired)
        assertFalse(summary.syncEventsGapDetected)
        assertFalse(summary.syncEventsOrdinaryPending)
        assertEquals("Ordinary C", f.db.productDao().getAll().single().productName)
        assertEquals(9.0, requireNotNull(f.db.productDao().getAll().single().stockQuantity), 0.0)
        assertEquals(binding, f.db.businessDataScopeBindingDao().get())
        assertEquals(device, f.db.syncEventDeviceStateDao().get())
        assertTrue(before != f.publication())
        f.assertReceipt(generation, 45L)
        assertEquals(Task126BusinessDataScopeStatus.READY, f.tracker.businessDataScopeState.value.status)
        assertNull(f.db.syncRecoveryJournalDao().get())
        assertTrue(f.recoveryRequests.isEmpty())
        assertTrue(f.reader.errors.isEmpty())
        assertEquals(listOf(42L), f.reader.eventAfterIds)
        assertEquals(listOf(45L), f.reader.eventIds.flatten()) // 42 -> 45 is sparse but valid.
        assertEquals(1, f.wire.ordinaryCalls("shop_sync_rows_by_ids_v1"))
        assertEquals(0, f.wire.ordinaryCalls("shop_sync_recovery_page_v1"))
        assertEquals(listOf("45"), f.wire.targetedFences())
        f.record("first-published", summary)

        val published = f.publication()
        val targeted = f.wire.ordinaryCalls("shop_sync_rows_by_ids_v1")
        f.drain()
        val noWork = requireNotNull(f.tracker.lastOutcome.value).summary
        assertEquals(0, noWork.syncEventsFetched)
        assertEquals(0, noWork.syncEventsProcessed)
        assertEquals(0, noWork.targetedProductsFetched)
        assertEquals(45L, noWork.syncEventsWatermarkAfter)
        assertFalse(noWork.manualFullSyncRequired)
        assertFalse(noWork.syncEventsGapDetected)
        assertFalse(noWork.syncEventsOrdinaryPending)
        assertEquals(published, f.publication())
        assertEquals(targeted, f.wire.ordinaryCalls("shop_sync_rows_by_ids_v1"))
        assertEquals(listOf(42L, 45L), f.reader.eventAfterIds)
        assertEquals(2, f.wire.ordinaryCalls("shop_sync_recovery_checkpoint_v1"))
        assertEquals(2, f.wire.ordinaryCalls("shop_sync_event_page_v1"))
        assertEquals(2, f.wire.ordinaryCalls("shop_sync_convergence_marker_v1"))
        assertEquals(0, f.wire.ordinaryCalls("shop_sync_recovery_page_v1"))
        assertEquals(Task126BusinessDataScopeStatus.READY, f.tracker.businessDataScopeState.value.status)
        assertNull(f.db.syncRecoveryJournalDao().get())
        assertTrue(f.recoveryRequests.isEmpty())
        assertTrue(f.reader.errors.isEmpty())
        f.assertReceipt(generation, 45L)
    }

    @Test
    fun checkpointDeclaredGapPreservesAAndRequestsRecovery() = ordinaryCase(OrdinaryCase.DECLARED_GAP) { f ->
        f.assertFallback(SyncEventApplyStatusReasons.MISSING_ENTITY_IDS, expectedCode = null, blockingId = 45L)
        assertEquals(listOf(true), f.reader.ordinaryCheckpointRequiresRecovery)
        assertEquals(listOf("45"), f.reader.ordinaryCheckpointBlockingIds)
        assertEquals(0, f.wire.ordinaryCalls("shop_sync_event_page_v1"))
    }

    @Test
    fun invalidEventCursorPreservesAAndRequestsRecovery() = ordinaryCase(OrdinaryCase.INVALID_CURSOR) { f ->
        f.assertFallback(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED,
            expectedCode = "event_cursor_not_increasing", blockingId = null)
        assertEquals(listOf(42L), f.reader.eventAfterIds)
        assertEquals(1, f.wire.ordinaryCalls("shop_sync_event_page_v1"))
    }

    @Test
    fun changedEventSnapshotFencePreservesAAndRequestsRecovery() = ordinaryCase(OrdinaryCase.CHANGED_AS_OF) { f ->
        f.assertFallback(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED,
            expectedCode = "event_as_of_max_id_mismatch", blockingId = null)
        assertEquals(listOf(42L), f.reader.eventAfterIds)
        assertEquals(1, f.wire.ordinaryCalls("shop_sync_event_page_v1"))
    }
}

private enum class OrdinaryCase(val method: String) {
    HEALTHY("healthyIncrementalPublishesCapturedWindowWithoutRecovery"),
    DECLARED_GAP("checkpointDeclaredGapPreservesAAndRequestsRecovery"),
    INVALID_CURSOR("invalidEventCursorPreservesAAndRequestsRecovery"),
    CHANGED_AS_OF("changedEventSnapshotFencePreservesAAndRequestsRecovery")
}

private object OrdinaryContract {
    const val PROTOCOL = "task144-native-ordinary-window-candidate01"
    const val OWNER = "14408000-0000-4000-8000-000000000001"
    const val SHOP = "14408000-0000-4000-8000-000000000002"
    const val PRODUCT = "14408000-0000-4000-8000-000000000003"
    const val DEVICE = "android-task144-ordinary-owned"
    const val DB = "task144-ordinary-owned.db"
    val json = Json { encodeDefaults = true }
    val shop = SelectedShop(SHOP, "CA08OWNED", "Synthetic ordinary fixture", "shop_owner", "active", true)
    val scope = task126ActiveOwnerStoreScope(OWNER, shop)
    val before = InventoryProductRow(PRODUCT, OWNER, SHOP, "TASK144-ORDINARY-ONE", itemNumber = "owned",
        productName = "Ordinary A", stockQuantity = 1.0, updatedAt = "2026-07-21T10:00:00.000000Z")
    val after = before.copy(productName = "Ordinary C", stockQuantity = 9.0, updatedAt = "2026-07-21T11:00:00.000000Z")
}

private fun ordinaryCase(case: OrdinaryCase, body: suspend (OrdinaryFixture) -> Unit) = runBlocking {
    withTimeout(90_000L) {
        val context = ordinaryOwnedContext(case)
        // use preserves a primary failure if close also fails; no evidence or DB deletion.
        OrdinaryFixture(context, case).use { f ->
            try {
                f.prepare()
                body(f)
                f.assertCleanLocalInvariants()
                f.record("ASSERTIONS_PASSED", requireNotNull(f.tracker.lastOutcome.value).summary)
            } catch (primary: Throwable) {
                try { f.record("FAIL", error = primary.javaClass.simpleName) }
                catch (archive: Throwable) { primary.addSuppressed(archive) }
                throw primary
            }
        }
        // Written only after AutoSync shutdown and owned Room close succeeded.
        ordinaryWrite(File(context.root, "completed.json"), buildJsonObject {
            put("protocol", OrdinaryContract.PROTOCOL); put("runId", context.runId)
            put("selector", case.method); put("pid", Process.myPid()); put("status", "PASS")
        }.toString())
    }
}

private class OrdinaryFixture(val context: OrdinaryOwnedContext, val case: OrdinaryCase) : AutoCloseable {
    var db = openDatabase()
        private set
    val wire = OrdinaryWire(context.root, case)
    val reader = OrdinaryObservedReader(SupabaseShopSyncReadRemoteDataSource(wire), wire, context.root)
    lateinit var tracker: CatalogSyncStateTracker
        private set
    private var auto: CatalogAutoSyncCoordinator? = null
    val recoveryRequests = mutableListOf<String>()

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, OrdinaryContract.DB)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build().also {
            check(File(requireNotNull(it.openHelper.writableDatabase.path)).canonicalFile == context.getDatabasePath(OrdinaryContract.DB))
        }

    suspend fun prepare() {
        val c = OrdinaryContract
        assertEquals(0, db.productDao().count())
        assertNull(db.syncRecoveryBaselineDao().get())
        db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = c.DEVICE, createdAtMs = 144L))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(
            ownerHash = c.scope.ownerHash, storeScope = c.scope.storeId, shopId = c.SHOP, deviceId = c.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
            blockingEventId = null, attemptCount = 0, createdAtMs = 1L, updatedAtMs = 1L, nextRetryAtMs = 1L))
        val recovered = ShopSyncRecoveryCoordinator(context, db, DefaultInventoryRepository(db), reader,
            registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = it)) },
            scopeStillValid = { owner, shop -> owner == c.OWNER && shop == c.SHOP },
            activationBoundary = { it() }, availableStorageBytes = { Long.MAX_VALUE })
            .recover(c.OWNER, c.shop, c.scope)
        assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
        assertEquals(6, wire.calls.count { it.function == "shop_sync_recovery_page_v1" })
        assertNull(db.syncRecoveryJournalDao().get())
        db.close()
        check(context.getDatabasePath(c.DB).isFile) { "Reopen must not create missing A" }
        db = openDatabase()
        val repositoryBefore = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        val qualified = repositoryBefore.resolveBusinessDataScope(c.scope)
        assertEquals(Task126BusinessDataScopeStatus.READY, qualified.status)
        assertFalse(repositoryBefore.shouldRunCatalogBootstrap(c.OWNER))
        assertEquals("Ordinary A", db.productDao().getAll().single().productName)
        assertReceipt(requireNotNull(db.syncRecoveryBaselineDao().get()).generationId, 42L)
        assertCleanLocalInvariants()
        ordinaryWrite(File(context.root, "publication-A.json"), publication().toString())
        wire.ordinary = true
        tracker = CatalogSyncStateTracker(qualified).also { it.updateNetworkAvailability(true) }
        val repository = DefaultInventoryRepository(db, businessDataScopeRuntimeGuard = tracker, shopSyncReadRemoteDataSource = reader)
        auto = CatalogAutoSyncCoordinator(
            repository = repository, remote = OrdinaryNoCatalog, priceRemote = OrdinaryNoPrices,
            syncEventRemote = OrdinaryNoLegacyEvents,
            deviceAuthorization = ShopDeviceAuthorizationRepository(OrdinaryActiveDevice(), businessDataScopeRuntimeGuard = tracker),
            authFlow = MutableStateFlow<AuthState>(AuthState.SignedIn(c.OWNER, "ordinary@synthetic.test")),
            selectedShopProvider = { c.shop }, syncStateTracker = tracker,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()), debounceMs = Long.MAX_VALUE,
            onRecoveryRequired = { recoveryRequests += it })
    }

    suspend fun drain() = requireNotNull(auto).runSyncEventDrainCycle("owned_${case.name}")

    suspend fun assertFallback(reason: String, expectedCode: String?, blockingId: Long?) {
        val before = publication()
        drain()
        val summary = requireNotNull(tracker.lastOutcome.value).summary
        assertTrue(summary.manualFullSyncRequired)
        assertTrue(summary.syncEventsGapDetected)
        assertFalse(summary.syncEventsOrdinaryPending)
        assertEquals(0, summary.syncEventsProcessed)
        assertEquals(0, summary.targetedProductsFetched)
        assertEquals(42L, summary.syncEventsWatermarkAfter)
        assertEquals(before, publication()) // Complete A body, bridge, ledger, receipt, watermark and binding.
        val journal = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, journal.phase)
        assertEquals(reason, journal.reason)
        assertEquals(blockingId, journal.blockingEventId)
        assertEquals(OrdinaryContract.scope.ownerHash, journal.ownerHash)
        assertEquals(OrdinaryContract.scope.storeId, journal.storeScope)
        assertEquals(OrdinaryContract.SHOP, journal.shopId)
        assertEquals(OrdinaryContract.DEVICE, journal.deviceId)
        assertEquals(listOf("sync_events_drain"), recoveryRequests)
        assertEquals(Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, tracker.businessDataScopeState.value.status)
        assertEquals(if (expectedCode == null) emptyList<String>() else listOf(expectedCode), reader.errors)
        assertEquals(0, wire.ordinaryCalls("shop_sync_rows_by_ids_v1"))
        assertEquals(0, wire.ordinaryCalls("shop_sync_convergence_marker_v1"))
        assertEquals(0, wire.ordinaryCalls("shop_sync_recovery_page_v1"))
        assertReceipt(requireNotNull(db.syncRecoveryBaselineDao().get()).generationId, 42L)
    }

    suspend fun assertReceipt(generation: String, watermark: Long) {
        val c = OrdinaryContract
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertEquals(generation, baseline.generationId)
        val receipt = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        val expected = ordinaryCheckpoint(watermark == 45L, watermark.toString()).let { checkpoint ->
            checkpoint.copy(syncEvents = checkpoint.syncEvents.copy(inspectionLimit = 10_000,
                scanComplete = true, inspectedCount = 0L, blockingCount = 0L))
        }
        assertEquals(expected, receipt)
        assertEquals(watermark, db.syncEventWatermarkDao().get(c.OWNER, c.scope.storeId)?.lastSyncEventId)
        val manifest = requireNotNull(db.syncRecoveryManifestDao().get(generation, "products", c.PRODUCT))
        assertEquals(ordinaryProductVersion(if (watermark == 45L) c.after else c.before), manifest.versionLine)
        assertNotNull(manifest.payloadDigest)
        db.withTransaction { validateShopSyncActiveReceipt(db, generation, receipt) }
    }

    suspend fun assertCleanLocalInvariants() {
        assertEquals(1, db.productDao().count())
        assertEquals(0, db.supplierDao().getAll().size)
        assertEquals(0, db.categoryDao().getAll().size)
        assertEquals(0, db.productPriceDao().countAll())
        assertEquals(0, db.historyEntryDao().countUserVisible())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        assertFalse(db.productRemoteRefDao().hasPendingWork())
        val ref = requireNotNull(db.productRemoteRefDao().getByRemoteId(OrdinaryContract.PRODUCT))
        assertEquals(ref.localChangeRevision, ref.lastSyncedLocalRevision)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    fun publication(): JsonObject = buildJsonObject {
        // Literal table whitelist; source pin records schema. Tiny owned database only.
        listOf("products", "product_remote_refs", "sync_recovery_manifest", "sync_recovery_baseline",
            "sync_event_watermarks", "business_data_scope_binding", "sync_event_device_state").forEach { table ->
            val rows = mutableListOf<JsonElement>()
            db.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                while (cursor.moveToNext()) rows.add(JsonArray((0 until cursor.columnCount).map {
                    if (cursor.isNull(it)) JsonNull else JsonPrimitive(cursor.getString(it))
                }))
            }
            put(table, JsonArray(rows.sortedBy { it.toString() }))
        }
    }

    fun record(status: String, summary: CatalogSyncSummary? = null, error: String? = null) {
        ordinaryWrite(File(context.root, "result.json"), buildJsonObject {
            put("protocol", OrdinaryContract.PROTOCOL); put("runId", context.runId)
            put("selector", case.method); put("pid", Process.myPid()); put("status", status)
            put("publication", publication()); put("recoveryRequests", JsonArray(recoveryRequests.map(::JsonPrimitive)))
            put("decoderErrors", JsonArray(reader.errors.map(::JsonPrimitive)))
            put("wireLedgerSha256", ordinaryHash(File(context.root, "wire-ledger.json").readText()))
            put("decodedResultsSha256", ordinaryHash(File(context.root, "decoded-results.json").readText()))
            val journalRows = mutableListOf<JsonElement>()
            db.openHelper.readableDatabase.query("SELECT * FROM sync_recovery_journal").use { cursor ->
                while (cursor.moveToNext()) journalRows.add(JsonArray((0 until cursor.columnCount).map {
                    if (cursor.isNull(it)) JsonNull else JsonPrimitive(cursor.getString(it))
                }))
            }
            put("journal", JsonArray(journalRows))
            error?.let { put("errorClass", it) }
            summary?.let { put("summary", buildJsonObject {
                put("fetched", it.syncEventsFetched); put("processed", it.syncEventsProcessed)
                put("watermark", it.syncEventsWatermarkAfter); put("manualFullSyncRequired", it.manualFullSyncRequired)
                put("gap", it.syncEventsGapDetected); put("targetedProducts", it.targetedProductsFetched)
            }) }
        }.toString())
    }

    override fun close() {
        try { auto?.shutdown() } finally { db.close() }
    }
}

/** Observes actual decoder results; never constructs a typed failure or replaces its result. */
private class OrdinaryObservedReader(
    private val actual: SupabaseShopSyncReadRemoteDataSource,
    private val wire: OrdinaryWire,
    private val root: File
) : ShopSyncReadRemoteDataSource by actual {
    val errors = mutableListOf<String>()
    val eventAfterIds = mutableListOf<Long>()
    val eventIds = mutableListOf<List<Long>>()
    val ordinaryCheckpointRequiresRecovery = mutableListOf<Boolean>()
    val ordinaryCheckpointBlockingIds = mutableListOf<String?>()
    private val observations = mutableListOf<JsonElement>()
    private fun observe(kind: String, failure: Throwable?, checkpoint: ShopSyncRecoveryCheckpoint? = null) {
        val code = (failure as? ShopSyncContractException)?.code
        if (code != null) errors += code
        observations += buildJsonObject {
            put("kind", kind); put("ordinary", wire.ordinary)
            put("errorCode", code?.let(::JsonPrimitive) ?: JsonNull)
            checkpoint?.let {
                put("requiresFullRecovery", it.syncEvents.requiresFullRecovery)
                put("blockingId", it.syncEvents.oldestBlockingId?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
        ordinaryWrite(File(root, "decoded-results.json"), JsonArray(observations).toString())
    }
    override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> =
        actual.checkpoint(context).also { result ->
            val value = result.getOrNull()
            if (wire.ordinary && value != null) {
                ordinaryCheckpointRequiresRecovery += value.syncEvents.requiresFullRecovery
                ordinaryCheckpointBlockingIds += value.syncEvents.oldestBlockingId
            }
            observe("checkpoint", result.exceptionOrNull(), value)
        }
    override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> {
        eventAfterIds += afterId
        return actual.eventPage(context, afterId, limit).also {
            it.getOrNull()?.let { page -> eventIds += page.rows.map { row -> row.id } }
            observe("eventPage", it.exceptionOrNull())
        }
    }
}

private data class OrdinaryWireCall(val ordinary: Boolean, val function: String, val params: JsonObject, val response: JsonObject? = null)

/** Only supplies synthetic wire objects. All decoding, scope/fence checks and publication remain production code. */
private class OrdinaryWire(private val root: File, private val case: OrdinaryCase) : ShopSyncRpcInvoker {
    var ordinary = false
    val calls = mutableListOf<OrdinaryWireCall>()
    override suspend fun call(function: String, params: JsonObject, maximumResponseBytes: Long, maximumHistoryRowBytes: Long?): ShopSyncRpcResponse {
        val c = OrdinaryContract
        val callIndex = calls.size
        calls += OrdinaryWireCall(ordinary, function, params)
        persist() // Record the raw entry before any parameter lookup, guard or dispatch.
        check(params.text("p_shop_id") == c.SHOP && params.text("p_device_identifier") == c.DEVICE)
        listOf("p_expected_scope_key", "p_expected_baseline_scope_key").forEach { key ->
            params[key]?.takeIf { it != JsonNull }?.let { check(it.jsonPrimitive.content == "c".repeat(64)) }
        }
        val response = when (function) {
            "shop_sync_recovery_checkpoint_v1" -> {
                val checkpoint = ordinaryCheckpoint(ordinary, params.text("p_verified_baseline_id"))
                val value = if (ordinary && case == OrdinaryCase.DECLARED_GAP)
                    checkpoint.copy(syncEvents = checkpoint.syncEvents.copy(requiresFullRecovery = true,
                        oldestBlockingId = "45", newestBlockingId = "45")) else checkpoint
                c.json.encodeToJsonElement(value).jsonObject
            }
            "shop_sync_convergence_marker_v1" -> {
                val baseline = params.text("p_verified_baseline_id")
                val cp = ordinaryCheckpoint(ordinary, baseline)
                val advanced = baseline.toLong() < cp.syncEvents.maxId.toLong()
                val events = cp.syncEvents.copy(inspectionLimit = 10_000, scanComplete = true,
                    inspectedCount = if (advanced) 1L else 0L, blockingCount = 0L)
                c.json.encodeToJsonElement(ShopSyncConvergenceMarker(
                    "shop-sync-convergence-marker-v1", "ready", c.SHOP, cp.scope, events, cp.catalog,
                    cp.prices, cp.history, cp.images, ShopSyncMarkerIntegrity(0), cp.checkpointDigest,
                    serverNoWorkEligible = !advanced, markerDigest = "e".repeat(64))).jsonObject
            }
            "shop_sync_recovery_page_v1" -> {
                check(!ordinary) { "Ordinary drain must never request full recovery pages" }
                check(params["p_after_id"] == JsonNull)
                check(params.text("p_expected_event_max_id") == "42")
                check(params.text("p_expected_domain_event_max_id") == "42")
                envelope("shop-sync-recovery-page-v1") {
                    val domain = params.text("p_domain")
                    put("domain", domain); put("snapshotEventMaxId", "42"); put("currentScopeEventMaxId", "42")
                    put("baselineDomainEventMaxId", "42"); put("pageDomainEventMaxId", "42")
                    put("domainScope", ShopSyncScopeKinds.SHOP_SCOPED); put("pageLimit", params.getValue("p_limit"))
                    put("rows", if (domain == "products") JsonArray(listOf(c.json.encodeToJsonElement(c.before))) else JsonArray(emptyList()))
                    put("nextAfterId", JsonNull); put("hasMore", false)
                }
            }
            "shop_sync_event_page_v1" -> {
                check(ordinary)
                check(params.text("p_expected_event_max_id") == "45")
                val after = params.text("p_after_id").toLong()
                check(after == 42L || after == 45L)
                val asOf = if (case == OrdinaryCase.CHANGED_AS_OF) "44" else "45"
                envelope("shop-sync-event-page-v1") {
                    put("scopeEventMaxId", "45"); put("asOfEventMaxId", asOf)
                    put("asOfDomainEventMaxIds", c.json.encodeToJsonElement(ordinaryCheckpoint(true, "42").syncEvents.domainMaxIds))
                    put("pageLimit", params.getValue("p_limit")); put("nextAfterId", JsonNull); put("hasMore", false)
                    put("rows", if (after == 45L) JsonArray(emptyList()) else JsonArray(listOf(buildJsonObject {
                        put("id", if (case == OrdinaryCase.INVALID_CURSOR) "42" else "45")
                        put("owner_user_id", c.OWNER); put("shop_id", c.SHOP); put("authorized_shop_id", c.SHOP)
                        put("domain", "catalog"); put("event_type", "catalog_changed"); put("source", "synthetic_peer")
                        put("source_device_key", ordinaryHash("synthetic-peer-device")); put("changed_count", 1)
                        put("entity_ids", c.json.encodeToJsonElement(SyncEventEntityIds(productIds = listOf(c.PRODUCT))))
                        put("requires_full_recovery", false); put("timestamp_valid", true)
                        put("created_at", requireNotNull(c.after.updatedAt)); put("metadata", JsonObject(emptyMap()))
                    })))
                }
            }
            "shop_sync_rows_by_ids_v1" -> {
                check(ordinary && case == OrdinaryCase.HEALTHY)
                check(params.text("p_domain") == "products")
                check(params.text("p_expected_event_max_id") == "45" && params.text("p_expected_domain_event_max_id") == "45")
                check(params.getValue("p_entity_ids").jsonArray.map { it.jsonPrimitive.content } == listOf(c.PRODUCT))
                envelope("shop-sync-rows-by-ids-v1") {
                    put("domain", "products"); put("asOfEventMaxId", "45"); put("currentScopeEventMaxId", "45")
                    put("minimumDomainEventMaxId", "45"); put("materializedDomainEventMaxId", "45")
                    put("domainScope", ShopSyncScopeKinds.SHOP_SCOPED); put("requestedCount", 1)
                    put("rows", JsonArray(listOf(c.json.encodeToJsonElement(c.after)))); put("missingIds", JsonArray(emptyList()))
                }
            }
            else -> error("Unexpected synthetic RPC: $function")
        }
        // Materialize/parse JSON text to ensure the actual adapter receives wire-shaped objects.
        val bytes = response.toString().encodeToByteArray()
        check(bytes.size.toLong() <= maximumResponseBytes)
        val largest = response["rows"]?.jsonArray?.maxOfOrNull { it.toString().encodeToByteArray().size.toLong() } ?: 0L
        check(maximumHistoryRowBytes == null || largest <= maximumHistoryRowBytes)
        calls[callIndex] = calls[callIndex].copy(response = response)
        persist()
        return ShopSyncRpcResponse(c.json.parseToJsonElement(bytes.decodeToString()).jsonObject, bytes.size.toLong(), largest)
    }
    private fun persist() = ordinaryWrite(File(root, "wire-ledger.json"), JsonArray(calls.map { call -> buildJsonObject {
        put("ordinary", call.ordinary); put("function", call.function); put("params", call.params)
        put("response", call.response ?: JsonNull)
    } }).toString())
    fun ordinaryCalls(function: String) = calls.count { it.ordinary && it.function == function }
    fun targetedFences() = calls.filter { it.ordinary && it.function == "shop_sync_rows_by_ids_v1" }
        .map { it.params.text("p_expected_domain_event_max_id") }
    private fun envelope(schema: String, body: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("schemaVersion", schema); put("shopId", OrdinaryContract.SHOP)
        put("scope", OrdinaryContract.json.encodeToJsonElement(ordinaryCheckpoint(false, "0").scope)); body()
    }
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun ordinaryProductVersion(row: InventoryProductRow) = listOf(row.id, requireNotNull(row.updatedAt), "-", "-", "-", "-", "-").joinToString("\u001f")
private fun ordinaryHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
private fun ordinaryDigest(lines: List<String>) = lines.fold(ordinaryHash("")) { state, line -> ordinaryHash("$state\u001f${line.encodeToByteArray().size}:$line") }
private fun ordinaryCheckpoint(advanced: Boolean, baseline: String): ShopSyncRecoveryCheckpoint {
    val c = OrdinaryContract
    val row = if (advanced) c.after else c.before
    val emptyHash = ordinaryHash("")
    val empty = ShopSyncDomainCheckpoint(0, 0, emptyHash, emptyHash)
    val product = ShopSyncDomainCheckpoint(1, 0, ordinaryDigest(listOf(row.id)), ordinaryDigest(listOf(ordinaryProductVersion(row))),
        ordinaryDigest(listOf(listOf(row.id, ordinaryHash(row.barcode), ordinaryHash(row.itemNumber.orEmpty())).joinToString("\u001f"))))
    return ShopSyncRecoveryCheckpoint(
        "shop-sync-recovery-checkpoint-v1", "ready", c.SHOP,
        ShopSyncScope(ShopSyncScopeKinds.SHOP_SCOPED, "c".repeat(64), historyKind = ShopSyncScopeKinds.SHOP_SCOPED,
            accountKey = ordinaryHash(c.OWNER), deviceKey = ordinaryHash(c.DEVICE)),
        ShopSyncEventCheckpoint(if (advanced) "45" else "42", baseline, false,
            mapOf("catalog" to if (advanced) "45" else "42", "prices" to "42", "history" to "42")),
        ShopSyncCatalogCheckpoint(empty, empty, product, ordinaryHash("$emptyHash\n$emptyHash\n${product.versionDigest}")),
        empty, empty, empty, ShopSyncIntegrityCheckpoint(0, 0, 0, 0, 0, 0), if (advanced) "b".repeat(64) else "a".repeat(64))
}

private class OrdinaryActiveDevice : ShopDeviceRegistrationRemote {
    override val isConfigured = true
    override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> = error("No registration in ordinary path")
    override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> = error("Selected shop status required")
    override suspend fun shopDeviceStatusForShop(shopId: String, reason: String): Result<ShopDeviceAuthorizationSnapshot> {
        check(shopId == OrdinaryContract.SHOP)
        return Result.success(ShopDeviceAuthorizationSnapshot(status = "active", code = "success", canWrite = true,
            serverTime = "2026-07-21T11:00:00Z", lastSeenAt = "2026-07-21T11:00:00Z",
            reasonCode = "active", recommendedAction = "allow", checkedAtMs = 1_000_000L))
    }
}
private object OrdinaryNoCatalog : CatalogRemoteDataSource {
    override val isConfigured = true
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> = error("Unexpected write")
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> = error("Unexpected write")
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> = error("Unexpected write")
    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> = error("Unexpected legacy full pull")
    override suspend fun fetchCatalogByIds(supplierIds: Set<String>, categoryIds: Set<String>, productIds: Set<String>): Result<InventoryCatalogFetchBundle> = error("Unexpected legacy targeted pull")
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("Unexpected write")
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("Unexpected write")
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("Unexpected write")
}
private object OrdinaryNoPrices : ProductPriceRemoteDataSource {
    override val isConfigured = true
    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> = error("Unexpected write")
    override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = error("Unexpected full price pull")
    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> = error("Unexpected legacy targeted pull")
}
private object OrdinaryNoLegacyEvents : SyncEventRemoteDataSource {
    override val isConfigured = true
    override suspend fun checkCapabilities(ownerUserId: String) = Result.success(SyncEventRemoteCapabilities(true, true, true))
    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> = error("Unexpected event write")
    override suspend fun fetchSyncEventsAfter(ownerUserId: String, storeId: String?, afterId: Long, limit: Long): Result<List<SyncEventRemoteRow>> = error("Unexpected legacy event fetch")
}

// Small local copy of reviewed native-gap02 ownership pattern; no transitive fixture dependency.
private class OrdinaryOwnedContext(base: Context, val runId: String, case: OrdinaryCase) : ContextWrapper(base) {
    val root: File
    private val databases: File
    init {
        require(Regex("[0-9a-f]{32}").matches(runId))
        val parent = base.filesDir.canonicalFile
        root = File(parent, "native-ordinary-$runId-${case.name.lowercase()}")
        require(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
        check(!root.exists()) { "Fresh owned run ID required; existing evidence is preserved" }
        check(root.mkdir())
        ordinaryWrite(File(root, "ownership.txt"), "${OrdinaryContract.PROTOCOL}\n$runId\n${case.method}\n")
        databases = File(root, "databases")
        check(databases.mkdir())
        require(databases.canonicalFile == databases.absoluteFile)
    }
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File {
        if (name == ".") return databases
        require(name.isNotBlank() && name != ".." && !name.contains('/') && !name.contains('\\'))
        return File(databases, name).also { require(it.canonicalFile == it.absoluteFile) }
    }
    override fun databaseList(): Array<String> = (databases.list()?.toList() ?: emptyList()).toTypedArray()
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    override fun getFilesDir(): File = child("files")
    override fun getCacheDir(): File = child("cache")
    override fun getNoBackupFilesDir(): File = child("no_backup")
    private fun child(name: String): File = File(root, name).also {
        require(it.canonicalFile == it.absoluteFile); check(it.isDirectory || it.mkdir())
    }
}
private fun ordinaryOwnedContext(case: OrdinaryCase): OrdinaryOwnedContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name)
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val args = InstrumentationRegistry.getArguments()
    check(args.getString("class") == Task144OrdinaryWindowDeviceTest::class.java.name + "#" + case.method)
    check(args.getString("nativeOrdinaryProtocol") == OrdinaryContract.PROTOCOL)
    return OrdinaryOwnedContext(instrumentation.targetContext, requireNotNull(args.getString("nativeOrdinaryRunId")), case)
}
private fun ordinaryWrite(file: File, text: String) {
    check(file.parentFile!!.isDirectory)
    val temporary = File(file.parentFile, file.name + ".pending")
    check(!temporary.exists()) { "Preserve unfinished evidence write" }
    FileOutputStream(temporary).use { it.write(text.encodeToByteArray()); it.fd.sync() }
    Os.rename(temporary.path, file.path)
    val directory = Os.open(file.parentFile!!.path, OsConstants.O_RDONLY, 0)
    try {
        check(OsConstants.S_ISDIR(Os.fstat(directory).st_mode)) { "Evidence descriptor is not a directory" }
        Os.fsync(directory)
    } finally { Os.close(directory) }
}

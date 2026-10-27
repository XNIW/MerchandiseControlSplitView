package com.example.merchandisecontrolsplitview.ui.screens

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.*
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncUiState
import com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native Options/VM/AutoSync/Room; only transport and authenticated inputs are synthetic.
 * Uses the existing automatic producer entry point, not a scheduler or live TEST acceptance probe.
 */
@RunWith(AndroidJUnit4::class)
class Task144AutomaticErrorDetailDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun automaticPriceFailureThenSuccessRemainsConsultableAfterOptionsRemount() {
        val context = gOwnedContext() // Admission precedes Room/fixture writes.
        val fixture = GFixture(context)
        val visibleVm = mutableStateOf<CatalogSyncViewModel?>(null)
        var store: ViewModelStore? = null
        var vm: CatalogSyncViewModel? = null
        var primary: Throwable? = null
        var oraclePassed = false
        val cleanupSteps = mutableListOf<JsonObject>()
        var phase = "setup"
        fun preserveFailure(t: Throwable) {
            val first = primary
            if (first == null) primary = t else if (first !== t) first.addSuppressed(t)
        }
        fun cleanupStep(name: String, block: () -> Unit) {
            val error = runCatching(block).exceptionOrNull()
            cleanupSteps += buildJsonObject {
                put("step", name); put("status", if (error == null) "PASS" else "FAIL")
                error?.let { put("errorClass", it.javaClass.name); put("message", it.message.orEmpty()) }
            }
            error?.let(::preserveFailure)
        }
        fun terminalDetails(): JsonObject = buildJsonObject {
            put("oraclePassed", oraclePassed); put("cleanup", JsonArray(cleanupSteps))
            primary?.let { first ->
                put("errorClass", first.javaClass.name); put("message", first.message.orEmpty())
                put("suppressed", JsonArray(first.suppressed.map { secondary -> buildJsonObject {
                    put("errorClass", secondary.javaClass.name); put("message", secondary.message.orEmpty())
                } }))
            }
        }
        fun mount() {
            compose.runOnIdle {
                val nextStore = ViewModelStore()
                val owner = object : ViewModelStoreOwner { override val viewModelStore = nextStore }
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        check(modelClass == CatalogSyncViewModel::class.java)
                        return CatalogSyncViewModel(
                            application = fixture.application, repository = fixture.repository,
                            remote = fixture.catalog, priceRemote = fixture.prices, sessionRemote = GNoHistory,
                            authFlow = fixture.auth, syncStateTracker = fixture.tracker,
                            shopContextFlow = fixture.shops
                        ) as T
                    }
                }
                store = nextStore
                vm = ViewModelProvider(owner, factory)[CatalogSyncViewModel::class.java]
                visibleVm.value = vm
                requireNotNull(vm).onOptionsScreenVisible()
            }
            compose.waitUntil(10_000) {
                requireNotNull(vm).uiState.value.businessDataScopeStatus == fixture.tracker.businessDataScopeState.value.status &&
                    !requireNotNull(vm).localDatabaseStatusUi.value.isLoading
            }
            compose.waitForIdle()
        }
        fun unmount() {
            val job = vm?.viewModelScope?.coroutineContext?.get(Job)
            compose.runOnIdle { visibleVm.value = null; store?.clear(); store = null; vm = null }
            gIo { job?.join() }
            compose.waitForIdle()
        }
        fun settled(): CatalogSyncUiState {
            compose.waitUntil(10_000) { !requireNotNull(vm).localDatabaseStatusUi.value.isLoading }
            compose.waitForIdle()
            return requireNotNull(vm).uiState.value
        }
        fun awaitPriceSuccessProjection(outcome: CatalogSyncOutcomeState): CatalogSyncUiState {
            val summary = outcome.summary
            check(!summary.priceSyncFailed && summary.pushedProductPrices == 1)
            val expectedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(requireNotNull(outcome.completedAtMs)))
            val expectedStats = context.getString(R.string.catalog_cloud_prices_sync_hint,
                summary.pushedProductPrices, summary.pulledProductPrices, summary.deferredProductPricesNoProductRef)
            val expectedLastOk = context.getString(R.string.catalog_cloud_last_ok, expectedDate)
            fun qualifies(ui: CatalogSyncUiState): Boolean {
                val local = requireNotNull(vm).localDatabaseStatusUi.value
                return fixture.tracker.lastOutcome.value === outcome &&
                    fixture.tracker.lastQualifiedOutcome.value === outcome &&
                    fixture.tracker.businessDataScopeState.value.status == Task126BusinessDataScopeStatus.READY &&
                    !fixture.tracker.isSyncing.value &&
                    ui.businessDataScopeStatus == Task126BusinessDataScopeStatus.READY && !ui.isSyncing &&
                    ui.primaryMessage == context.getString(R.string.catalog_cloud_state_synced) &&
                    ui.catalogDetail.orEmpty().lines().contains(expectedStats) &&
                    ui.catalogDetail.orEmpty().lines().contains(expectedLastOk) &&
                    local.businessDataScopeStatus == Task126BusinessDataScopeStatus.READY &&
                    local.lastSyncText == expectedDate && local.priceHistoryCount == 1 &&
                    local.pendingLocalChangesCount == 0 && local.syncEventOutboxPendingCount == 0
            }
            // This barrier never waits for retained error text: that remains the separate G oracle.
            compose.waitUntil(10_000) {
                val current = requireNotNull(vm).uiState.value
                qualifies(current) && compose.onAllNodesWithText(requireNotNull(current.catalogDetail),
                    useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            compose.waitForIdle()
            val qualified = requireNotNull(vm).uiState.value
            assertTrue("G_UI_SUCCESS_BARRIER: current price success projection changed", qualifies(qualified))
            compose.onNodeWithText(requireNotNull(qualified.catalogDetail), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            assertEquals(qualified, requireNotNull(vm).uiState.value)
            return qualified
        }
        fun retainedInVisibleDetail(errorText: String, ui: CatalogSyncUiState): Boolean {
            assertEquals("G_UI_SUCCESS_BARRIER: observer snapshot changed", ui, requireNotNull(vm).uiState.value)
            // A current warning alone cannot satisfy resolved-history consultability.
            if (ui.primaryMessage == errorText || ui.catalogDetail?.contains(errorText) != true) return false
            val body = requireNotNull(ui.catalogDetail)
            if (compose.onAllNodesWithText(body, useUnmergedTree = true).fetchSemanticsNodes().size != 1) return false
            compose.onNodeWithText(body, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            return true
        }
        try {
            gIo { fixture.seed() }
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MerchandiseControlTheme(darkTheme = false) {
                        Surface {
                            visibleVm.value?.let { current -> key(current) {
                                val ui by current.uiState.collectAsState()
                                val local by current.localDatabaseStatusUi.collectAsState()
                                val auth by fixture.auth.collectAsState()
                                OptionsScreen(authState = auth, authEnabled = true,
                                    catalogSyncUi = ui, localDatabaseStatusUi = local)
                            } }
                        }
                    }
                }
            }
            mount()
            phase = "real-price-failure"
            gIo {
                fixture.repository.recordPriceIfChanged(fixture.productId, "PURCHASE", 17.0,
                    "2026-10-16 12:00:00", "MANUAL")
                assertEquals(1, fixture.db.productPriceDao().getAllForCloudPush().size)
                fixture.cycle()
                assertEquals(1, fixture.prices.calls.size)
                assertTrue(requireNotNull(fixture.tracker.lastOutcome.value).summary.priceSyncFailed)
                assertEquals(1, fixture.priceHeaders().size)
                assertEquals(1, fixture.db.productPriceDao().getAllForCloudPush().size)
            }
            val expectedError = context.getString(R.string.catalog_cloud_state_prices_incomplete)
            compose.waitUntil(10_000) { requireNotNull(vm).uiState.value.primaryMessage == expectedError }
            val errorText = settled().primaryMessage
            val errorDescription = context.getString(R.string.catalog_cloud_section_cd, errorText)
            // Current production exposes this identity through actual Options accessibility semantics;
            // its SignedIn header is hidden. Do not pretend it is a visible Text banner.
            val currentError = compose.onNodeWithContentDescription(errorDescription, useUnmergedTree = true)
            currentError.performScrollTo().assertIsDisplayed()
            val capturedDescription = currentError.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            assertEquals(errorDescription, capturedDescription)
            val failureOutcome = requireNotNull(fixture.tracker.lastOutcome.value)
            assertEquals(CatalogSyncFlightOwner.AUTO_PUSH, failureOutcome.source)
            assertNotNull(failureOutcome.scopeSignal)
            assertNotNull(failureOutcome.completedAtMs)
            fixture.observe("current-error", settled(), extra = buildJsonObject {
                put("errorText", errorText); put("capturedContentDescription", capturedDescription)
                put("failureCompletedAtMs", requireNotNull(failureOutcome.completedAtMs))
                put("leaseGeneration", requireNotNull(failureOutcome.scopeSignal).lease.generation)
                put("currentIdentitySurface", "Options accessibility contentDescription")
            })

            phase = "real-price-success"
            fixture.prices.allowSuccess = true
            gIo {
                fixture.cycle() // Same actual receipt is replayed; no fabricated outcome publication.
                assertEquals(2, fixture.prices.calls.size)
                assertEquals(fixture.prices.calls[0].rawAttempt, fixture.prices.calls[1].rawAttempt)
                assertEquals(fixture.prices.calls[0].key, fixture.prices.calls[1].key)
                assertEquals(listOf(1, 2), fixture.prices.calls.map { it.attempt })
                assertTrue(fixture.priceHeaders().isEmpty())
                assertTrue(fixture.db.productPriceDao().getAllForCloudPush().isEmpty())
                val acknowledged = fixture.prices.calls.last().row
                assertNotNull(fixture.db.productPriceRemoteRefDao().getByRemoteId(acknowledged.id))
                assertEquals(acknowledged, fixture.prices.accepted)
                assertFalse(requireNotNull(fixture.tracker.lastOutcome.value).summary.priceSyncFailed)
                assertEquals(1, requireNotNull(fixture.tracker.lastOutcome.value).summary.pushedProductPrices)
                fixture.assertIntegrity()
            }
            val successOutcome = requireNotNull(fixture.tracker.lastQualifiedOutcome.value)
            assertEquals(CatalogSyncFlightOwner.AUTO_PUSH, successOutcome.source)
            assertNotNull(successOutcome.completedAtMs)
            assertTrue(requireNotNull(successOutcome.completedAtMs) >= requireNotNull(failureOutcome.completedAtMs))
            assertEquals(requireNotNull(failureOutcome.scopeSignal).lease, requireNotNull(successOutcome.scopeSignal).lease)
            val successUi = awaitPriceSuccessProjection(successOutcome)
            compose.onAllNodesWithContentDescription(errorDescription, useUnmergedTree = true).assertCountEquals(0)
            val retainedAfterSuccess = retainedInVisibleDetail(errorText, successUi)
            val dateBeforeRemount = requireNotNull(vm).localDatabaseStatusUi.value.lastSyncText
            fixture.observe("after-success", successUi, retainedAfterSuccess)

            phase = "same-process-remount"
            val callsBeforeRemount = fixture.prices.calls.size
            unmount()
            mount()
            val remountedUi = awaitPriceSuccessProjection(successOutcome)
            val retainedAfterRemount = retainedInVisibleDetail(errorText, remountedUi)
            assertNotEquals(errorText, remountedUi.primaryMessage)
            assertEquals(dateBeforeRemount, requireNotNull(vm).localDatabaseStatusUi.value.lastSyncText)
            assertSame(successOutcome, fixture.tracker.lastQualifiedOutcome.value)
            assertEquals(callsBeforeRemount, fixture.prices.calls.size)
            fixture.observe("after-remount", remountedUi, retainedAfterRemount)

            phase = "scope-negative-controls"
            // Read-only mismatches against the same owned A binding. No destructive switch/reset.
            listOf(
                Triple(GContract.FOREIGN_OWNER, GContract.shop, Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH),
                Triple(GContract.OWNER, GContract.shop.copy(shopId = GContract.FOREIGN_SHOP), Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH)
            ).forEach { (owner, shop, expectedStatus) ->
                unmount()
                val state = gIo { fixture.repository.resolveBusinessDataScope(task126ActiveOwnerStoreScope(owner, shop)) }
                assertEquals(expectedStatus, state.status)
                fixture.tracker.updateBusinessDataScopeState(state)
                assertFalse(fixture.tracker.isCurrentBusinessDataScopeSignal(requireNotNull(successOutcome.scopeSignal)))
                fixture.auth.value = AuthState.SignedIn(owner, "owned@synthetic.test")
                fixture.shops.value = ShopContext(owner, emptyList(), shop)
                mount()
                val isolated = settled()
                assertNotEquals(errorText, isolated.primaryMessage)
                assertFalse(isolated.catalogDetail.orEmpty().contains(errorText))
                assertFalse(isolated.sessionDetail.orEmpty().contains(errorText))
                compose.onAllNodesWithText(errorText, substring = true, useUnmergedTree = true).assertCountEquals(0)
                assertNull(requireNotNull(vm).localDatabaseStatusUi.value.lastSyncText)
                assertEquals(callsBeforeRemount, fixture.prices.calls.size)
                fixture.observe("scope-${expectedStatus.name}", isolated)
            }
            gIo { fixture.assertIntegrity() }
            phase = "retained-detail-oracle"
            val missing = buildList {
                if (!retainedAfterSuccess) add("after_success")
                if (!retainedAfterRemount) add("after_same_process_vm_remount")
            }
            fixture.record("ORACLE_REACHED", phase, buildJsonObject { put("missing", JsonArray(missing.map(::JsonPrimitive))) })
            assertTrue("G_DETAIL_RED: captured real price episode is not consultable in Options details: $missing", missing.isEmpty())
            oraclePassed = true
        } catch (t: Throwable) {
            primary = t
            runCatching { fixture.record("FAIL", phase, buildJsonObject {
                put("errorClass", t.javaClass.name); put("message", t.message.orEmpty())
            }) }.exceptionOrNull()?.let(t::addSuppressed)
        } finally {
            // Quiesce only this test's jobs and close its owned Room handle. Preserve all DB/files/prefs.
            var remainingVmJob: Job? = null
            cleanupStep("vm_job_capture") { remainingVmJob = vm?.viewModelScope?.coroutineContext?.get(Job) }
            cleanupStep("options_unmount") { unmount() }
            cleanupStep("vm_cancel") { remainingVmJob?.cancel() }
            cleanupStep("vm_join") { gIo { remainingVmJob?.join() } }
            cleanupStep("auto_shutdown") { fixture.shutdownAuto() }
            cleanupStep("auto_cancel") { fixture.cancelAuto() }
            cleanupStep("auto_join") { fixture.joinAuto() }
            cleanupStep("room_close") { fixture.closeRoom() }
            // The only terminal PASS publication is after every independent cleanup attempt succeeded.
            try {
                fixture.record(if (primary == null && oraclePassed) "PASS" else "FAIL", phase, terminalDetails())
            } catch (archive: Throwable) {
                preserveFailure(archive)
                // Best effort correction if a failed atomic write reached rename; never hides the primary.
                runCatching { fixture.record("FAIL_ARCHIVE", phase, terminalDetails()) }
                    .exceptionOrNull()?.let(::preserveFailure)
            }
        }
        primary?.let { throw it }
    }
}

private object GContract {
    const val METHOD = "automaticPriceFailureThenSuccessRemainsConsultableAfterOptionsRemount"
    const val PROTOCOL = "task144-g-detail-candidate01"
    const val OWNER = "14415000-0000-4000-8000-000000000001"
    const val SHOP = "14415000-0000-4000-8000-000000000002"
    const val DEVICE = "android-task144-g-owned"
    const val FOREIGN_OWNER = "14415000-0000-4000-8000-000000000011"
    const val FOREIGN_SHOP = "14415000-0000-4000-8000-000000000012"
    const val DB = "task144-g-owned.db"
    val json = Json { encodeDefaults = true }
    val shop = SelectedShop(SHOP, "GOWNED", "Synthetic G fixture", "shop_owner", "active", true)
    val scope = task126ActiveOwnerStoreScope(OWNER, shop)
}

private fun <T> gIo(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

private class GFixture(val context: GOwnedContext) {
    val db = Room.databaseBuilder(context, AppDatabase::class.java, GContract.DB)
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build().also {
            check(File(requireNotNull(it.openHelper.writableDatabase.path)).canonicalFile == context.getDatabasePath(GContract.DB))
        }
    val application = object : Application() { init { attachBaseContext(context) } }
    val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(GContract.OWNER, "owned@synthetic.test"))
    val shops = MutableStateFlow(ShopContext(GContract.OWNER, emptyList(), GContract.shop))
    val reader = GEmptyRecoveryRemote()
    val catalog = GSeedCatalog()
    val prices = GPrices(this)
    lateinit var tracker: CatalogSyncStateTracker
    lateinit var repository: DefaultInventoryRepository
    private lateinit var auto: CatalogAutoSyncCoordinator
    private val autoJob = SupervisorJob()
    var productId = 0L
    private var generation: String? = null
    private val observations = mutableListOf<JsonObject>()

    suspend fun seed() {
        assertEquals(0, db.productDao().count())
        assertNull(db.businessDataScopeBindingDao().get())
        db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = GContract.DEVICE, createdAtMs = 1))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(
            ownerHash = GContract.scope.ownerHash, storeScope = GContract.scope.storeId,
            shopId = GContract.SHOP, deviceId = GContract.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
            blockingEventId = null, attemptCount = 0, createdAtMs = 1, updatedAtMs = 1, nextRetryAtMs = 1))
        val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        val recovered = ShopSyncRecoveryCoordinator(context, db, initial, reader,
            registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = it)) },
            scopeStillValid = { owner, shop -> owner == GContract.OWNER && shop == GContract.SHOP },
            activationBoundary = { it() }, availableStorageBytes = { Long.MAX_VALUE })
            .recover(GContract.OWNER, GContract.shop, GContract.scope)
        assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        generation = baseline.generationId
        db.withTransaction { validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson)) }
        val state = initial.resolveBusinessDataScope(GContract.scope)
        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        tracker = CatalogSyncStateTracker(state).also { it.updateNetworkAvailability(true) }
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader, businessDataScopeRuntimeGuard = tracker)
        repository.addProduct(Product(barcode = "TASK144-G-ONE", productName = "G owned product"))
        tracker.withBusinessDataScopeFlight(GContract.OWNER, GContract.shop) {
            repository.pushDirtyCatalogDeltaToRemote(catalog, prices, GContract.OWNER,
                CatalogSyncProgressReporter { }, GContract.shop).getOrThrow()
        }
        productId = db.productDao().getAll().single().id
        assertTrue(prices.calls.isEmpty())
        assertFalse(repository.shouldRunCatalogBootstrap(GContract.OWNER))
        assertIntegrity()
        auto = CatalogAutoSyncCoordinator(repository, catalog, prices,
            syncEventRemote = DisabledSyncEventRemoteDataSource,
            deviceAuthorization = ShopDeviceAuthorizationRepository(GActiveDevice(), businessDataScopeRuntimeGuard = tracker),
            authFlow = auth, selectedShopProvider = { shops.value.selectedShop }, syncStateTracker = tracker,
            scope = CoroutineScope(Dispatchers.IO + autoJob), debounceMs = Long.MAX_VALUE,
            foregroundSyncEventIntervalMs = 0L,
            onRecoveryRequired = { error("G must not request recovery: $it") })
        record("SEEDED", "setup")
    }
    suspend fun cycle() = auto.runPushCycle("local_catalog_commit_g_detail")
    suspend fun priceHeaders() = db.syncEventOutboxDao().listBusinessWriteHeaders(
        GContract.OWNER, GContract.scope.storeId, setOf("PRICES"), 10)
    suspend fun assertIntegrity() {
        val ref = requireNotNull(db.productRemoteRefDao().getByProductId(productId))
        assertEquals(ref.localChangeRevision, ref.lastSyncedLocalRevision)
        assertNotNull(ref.lastRemoteAppliedAt)
        assertEquals(generation, db.syncRecoveryBaselineDao().get()?.generationId)
        assertEquals(GContract.scope.ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        assertEquals(GContract.scope.storeId, db.businessDataScopeBindingDao().get()?.storeId)
        assertNull(db.syncRecoveryJournalDao().get())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    fun observe(stage: String, ui: CatalogSyncUiState, retained: Boolean? = null, extra: JsonObject = JsonObject(emptyMap())) {
        observations += buildJsonObject {
            put("stage", stage); put("primaryMessage", ui.primaryMessage)
            put("catalogDetail", ui.catalogDetail?.let(::JsonPrimitive) ?: JsonNull)
            put("sessionDetail", ui.sessionDetail?.let(::JsonPrimitive) ?: JsonNull)
            put("scopeStatus", ui.businessDataScopeStatus.name)
            put("retainedAndDisplayed", retained?.let(::JsonPrimitive) ?: JsonNull)
            put("extra", extra)
        }
        record("OBSERVED", stage)
    }
    fun record(status: String, phase: String, extra: JsonObject = JsonObject(emptyMap())) = gWrite(File(context.root, "result.json"), buildJsonObject {
        put("protocol", GContract.PROTOCOL); put("runId", context.runId); put("selector", GContract.METHOD)
        put("pid", Process.myPid()); put("status", status); put("phase", phase)
        put("generation", generation?.let(::JsonPrimitive) ?: JsonNull)
        put("observations", JsonArray(observations)); put("extra", extra)
        put("priceCalls", prices.ledger())
    }.toString())
    fun shutdownAuto() {
        if (::auto.isInitialized) auto.shutdown() else autoJob.cancel()
    }
    fun cancelAuto() = autoJob.cancel()
    fun joinAuto() = gIo { autoJob.join() }
    fun closeRoom() = db.close()
}

private data class GPriceCall(val key: String, val rawAttempt: String, val attempt: Int, val row: InventoryProductPriceRow)
private class GPrices(private val fixture: GFixture) : ProductPriceRemoteDataSource {
    override val isConfigured = true
    var allowSuccess = false
    var accepted: InventoryProductPriceRow? = null
    val calls = mutableListOf<GPriceCall>()
    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> {
        val row = rows.single()
        val header = fixture.priceHeaders().single()
        val entry = requireNotNull(fixture.db.syncEventOutboxDao().getById(header.id))
        val attempt = GContract.json.decodeFromString<BusinessWriteAttempt>(entry.metadataJson)
        validateBusinessWritePayload(attempt)
        check(attempt.owner == GContract.OWNER && attempt.shop == GContract.SHOP && attempt.device == GContract.DEVICE)
        check(attempt.generation == fixture.db.syncRecoveryBaselineDao().get()?.generationId)
        check(attempt.payload.kind == "PRICES" && attempt.payload.prices == rows)
        check(row.ownerUserId == GContract.OWNER && row.shopId == GContract.SHOP)
        calls += GPriceCall(entry.clientEventId, entry.metadataJson, entry.attemptCount, row)
        gWrite(File(fixture.context.root, "price-wire-ledger.json"), ledger().toString())
        if (!allowSuccess) return Result.failure(IOException("TASK144_G_SYNTHETIC_PRICE_UNAVAILABLE"))
        accepted = row
        return Result.success(Unit)
    }
    fun ledger(): JsonArray = JsonArray(calls.map { call -> buildJsonObject {
        put("operationKey", call.key); put("rawAttempt", call.rawAttempt); put("attemptCount", call.attempt)
        put("row", GContract.json.encodeToJsonElement(call.row))
    } })
    override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = error("No full price pull")
    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> = error("No targeted price pull")
}

private class GSeedCatalog : CatalogRemoteDataSource {
    override val isConfigured = true
    private var seeded = false
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
        check(!seeded && rows.size == 1)
        check(rows.single().ownerUserId == GContract.OWNER && rows.single().shopId == GContract.SHOP)
        seeded = true
        return Result.success(Unit)
    }
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> = error("No supplier write")
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> = error("No category write")
    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> = error("No legacy full pull")
    override suspend fun fetchCatalogByIds(supplierIds: Set<String>, categoryIds: Set<String>, productIds: Set<String>): Result<InventoryCatalogFetchBundle> = error("No legacy targeted pull")
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("No tombstone")
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("No tombstone")
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("No tombstone")
}
private object GNoHistory : SessionBackupRemoteDataSource {
    override val isConfigured = false
    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> = error("No History pull")
    override suspend fun fetchSessionsByRemoteIds(remoteIds: Set<String>): Result<List<SharedSheetSessionRecord>> = error("No targeted History pull")
    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> = error("No History write")
}
private class GActiveDevice : ShopDeviceRegistrationRemote {
    override val isConfigured = true
    override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> = error("No registration in push")
    override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> = error("Selected shop required")
    override suspend fun shopDeviceStatusForShop(shopId: String, reason: String): Result<ShopDeviceAuthorizationSnapshot> {
        check(shopId == GContract.SHOP)
        return Result.success(ShopDeviceAuthorizationSnapshot(status = "active", code = "success", canWrite = true,
            serverTime = "2026-10-16T12:00:00Z", lastSeenAt = "2026-10-16T12:00:00Z", reasonCode = "active",
            recommendedAction = "allow", checkedAtMs = System.currentTimeMillis()))
    }
}

private class GOwnedContext(base: Context, val runId: String) : ContextWrapper(base) {
    val root: File
    private val databases: File
    init {
        require(Regex("[0-9a-f]{32}").matches(runId))
        val parent = base.filesDir.canonicalFile
        root = File(parent, "native-g-detail-$runId")
        require(root.canonicalFile == root.absoluteFile && root.parentFile == parent)
        check(!root.exists()) { "Fresh owned run ID required; preserve prior evidence" }
        check(root.mkdir())
        gWrite(File(root, "ownership.txt"), "${GContract.PROTOCOL}\n$runId\n${GContract.METHOD}\nprefs=task144_g_${runId}_settings\n")
        databases = File(root, "databases")
        check(databases.mkdir())
    }
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "settings")
        return baseContext.getSharedPreferences("task144_g_${runId}_$name", mode)
    }
    override fun getDatabasePath(name: String): File {
        if (name == ".") return databases
        require(name.isNotBlank() && name != ".." && !name.contains('/') && !name.contains('\\'))
        return File(databases, name).also { require(it.canonicalFile == it.absoluteFile) }
    }
    override fun databaseList(): Array<String> = (databases.list()?.toList() ?: emptyList()).toTypedArray()
    override fun deleteDatabase(name: String): Boolean = error("G fixture preserves owned database evidence")
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
private fun gOwnedContext(): GOwnedContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name)
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val args = InstrumentationRegistry.getArguments()
    check(args.getString("class") == Task144AutomaticErrorDetailDeviceTest::class.java.name + "#" + GContract.METHOD)
    check(args.getString("gDetailProtocol") == GContract.PROTOCOL)
    val base = instrumentation.targetContext
    return GOwnedContext(base, requireNotNull(args.getString("gDetailRunId")))
}
private fun gWrite(file: File, text: String) {
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

// Small source-pinned adaptation of native-gap02 empty recovery transport; no fixture dependencies.
private class GEmptyRecoveryRemote : ShopSyncReadRemoteDataSource {
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
            shopId = GContract.SHOP,
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
                shopId = GContract.SHOP,
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
    private companion object {
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
                shopId = GContract.SHOP,
                scope = ShopSyncScope(
                    kind = ShopSyncScopeKinds.SHOP_SCOPED,
                    key = "c".repeat(64),
                    historyKind = ShopSyncScopeKinds.SHOP_SCOPED,
                    accountKey = sha256(GContract.OWNER.lowercase()),
                    deviceKey = sha256(GContract.DEVICE)
                ),
                syncEvents = ShopSyncEventCheckpoint(
                    maxId = "0",
                    verifiedBaselineId = "0",
                    requiresFullRecovery = false,
                    domainMaxIds = mapOf(
                        SyncEventDomains.CATALOG to "0",
                        SyncEventDomains.PRICES to "0",
                        SyncEventDomains.HISTORY to "0"
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

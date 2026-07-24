package com.example.merchandisecontrolsplitview

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogDeleteStrategy
import com.example.merchandisecontrolsplitview.data.CatalogEntityKind
import com.example.merchandisecontrolsplitview.data.CatalogSyncFlightOwner
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.CatalogSyncSummary
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import com.example.merchandisecontrolsplitview.data.InventoryCategoryRow
import com.example.merchandisecontrolsplitview.data.InventoryProductRow
import com.example.merchandisecontrolsplitview.data.InventorySupplierRow
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.SharedSheetSessionRecord
import com.example.merchandisecontrolsplitview.data.SyncEventDomains
import com.example.merchandisecontrolsplitview.data.SyncEventRemoteRow
import com.example.merchandisecontrolsplitview.data.SyncEventWatermark
import com.example.merchandisecontrolsplitview.data.SyncStatus
import com.example.merchandisecontrolsplitview.data.remoteStoreIdFromStoreScope
import com.example.merchandisecontrolsplitview.data.shopScopedStoreScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task072DAndroidReceiverHarnessTest {

    @Test
    fun androidReceiverCatalogHistoryMatrixDbSnapshotAndOutbox() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val fixture = fixture(args.getString("task072DRunPrefix"))
        val runtime = runtime()
        val startedAt = System.currentTimeMillis()

        runtime.app.catalogAutoSyncCoordinator.onAppBackground()
        runtime.app.historySessionPushCoordinator.onAppBackground()
        primeSyncEventWatermarkWindow(runtime)
        assertTask072DPrefixIsFresh(runtime, fixture)

        val productSupplier = runtime.repository.addSupplier(fixture.productSupplier)
            ?: throw AssertionError("TASK-072D product supplier create failed")
        val productCategory = runtime.repository.addCategory(fixture.productCategory)
            ?: throw AssertionError("TASK-072D product category create failed")
        val standaloneSupplier = runtime.repository.addSupplier(fixture.supplierInitial)
            ?: throw AssertionError("TASK-072D standalone supplier create failed")
        val standaloneCategory = runtime.repository.addCategory(fixture.categoryInitial)
            ?: throw AssertionError("TASK-072D standalone category create failed")

        runtime.repository.addProduct(
            Product(
                barcode = fixture.productCreateBarcode,
                itemNumber = "${fixture.prefix}ITEM_CREATE",
                productName = fixture.productCreateName,
                supplierId = productSupplier.id,
                categoryId = productCategory.id,
                purchasePrice = 11.25,
                retailPrice = 15.50,
                stockQuantity = 3.0
            )
        )
        runtime.repository.addProduct(
            Product(
                barcode = fixture.productUpdateBarcode,
                itemNumber = "${fixture.prefix}ITEM_UPDATE",
                productName = fixture.productUpdateInitialName,
                supplierId = productSupplier.id,
                categoryId = productCategory.id,
                purchasePrice = 21.25,
                retailPrice = 25.50,
                stockQuantity = 4.0
            )
        )
        runtime.repository.addProduct(
            Product(
                barcode = fixture.productTombstoneBarcode,
                itemNumber = "${fixture.prefix}ITEM_TOMBSTONE",
                productName = fixture.productTombstoneName,
                supplierId = productSupplier.id,
                categoryId = productCategory.id,
                purchasePrice = 31.25,
                retailPrice = 35.50,
                stockQuantity = 5.0
            )
        )

        val createSummary = waitForCatalogAutoPush(runtime, "catalog create") {
            val catalog = remoteCatalog(runtime.client, fixture)
            activeProduct(catalog.products, fixture.productCreateBarcode)?.productName == fixture.productCreateName &&
                activeProduct(catalog.products, fixture.productUpdateBarcode)?.productName == fixture.productUpdateInitialName &&
                activeProduct(catalog.products, fixture.productTombstoneBarcode)?.productName == fixture.productTombstoneName
        }

        val renamedSupplier = runtime.repository.renameCatalogEntry(
            kind = CatalogEntityKind.SUPPLIER,
            id = standaloneSupplier.id,
            newName = fixture.supplierFinal
        )
        val renamedCategory = runtime.repository.renameCatalogEntry(
            kind = CatalogEntityKind.CATEGORY,
            id = standaloneCategory.id,
            newName = fixture.categoryFinal
        )
        val updateProduct = runtime.repository.findProductByBarcode(fixture.productUpdateBarcode)
            ?: throw AssertionError("TASK-072D update product missing locally")
        runtime.repository.updateProduct(
            updateProduct.copy(
                productName = fixture.productUpdateFinalName,
                purchasePrice = 22.75,
                retailPrice = 26.25,
                stockQuantity = 6.0
            )
        )

        val updateSummary = waitForCatalogAutoPush(runtime, "catalog update") {
            val catalog = remoteCatalog(runtime.client, fixture)
            activeSupplier(catalog.suppliers, fixture.supplierFinal) != null &&
                activeCategory(catalog.categories, fixture.categoryFinal) != null &&
                activeProduct(catalog.products, fixture.productUpdateBarcode)?.productName == fixture.productUpdateFinalName
        }

        val tombstoneProduct = runtime.repository.findProductByBarcode(fixture.productTombstoneBarcode)
            ?: throw AssertionError("TASK-072D tombstone product missing locally")
        runtime.repository.deleteProduct(tombstoneProduct)
        runtime.repository.deleteCatalogEntry(
            kind = CatalogEntityKind.SUPPLIER,
            id = renamedSupplier.id,
            strategy = CatalogDeleteStrategy.DeleteIfUnused
        )
        runtime.repository.deleteCatalogEntry(
            kind = CatalogEntityKind.CATEGORY,
            id = renamedCategory.id,
            strategy = CatalogDeleteStrategy.DeleteIfUnused
        )

        val tombstoneSummary = waitForCatalogAutoPush(runtime, "catalog tombstone") {
            val catalog = remoteCatalog(runtime.client, fixture)
            productByBarcode(catalog.products, fixture.productTombstoneBarcode)?.deletedAt != null &&
                supplierByName(catalog.suppliers, fixture.supplierFinal)?.deletedAt != null &&
                categoryByName(catalog.categories, fixture.categoryFinal)?.deletedAt != null
        }

        val catalogWatermark = maxOf(
            createSummary.syncEventsWatermarkAfter,
            updateSummary.syncEventsWatermarkAfter,
            tombstoneSummary.syncEventsWatermarkAfter
        )
        assertCatalogSyncEvents(runtime, createSummary, updateSummary, tombstoneSummary)

        val historyCreateUid = runtime.repository.insertHistoryEntry(historyEntry(fixture.historyCreate, fixture))
        val historyUpdateUid = runtime.repository.insertHistoryEntry(historyEntry(fixture.historyUpdateInitial, fixture))
        val historyTombstoneUid = runtime.repository.insertHistoryEntry(historyEntry(fixture.historyTombstone, fixture))
        var historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "history create",
            watermarkBefore = catalogWatermark
        ) {
            val sessions = remoteSessions(runtime, fixture)
            sessionByDisplayName(sessions, fixture.historyCreate)?.deletedAt == null &&
                sessionByDisplayName(sessions, fixture.historyUpdateInitial)?.deletedAt == null &&
                sessionByDisplayName(sessions, fixture.historyTombstone)?.deletedAt == null
        }

        val updateHistory = runtime.app.database.historyEntryDao().getByUid(historyUpdateUid)
            ?: throw AssertionError("TASK-072D update history missing locally")
        runtime.repository.updateHistoryEntry(updateHistory.copy(displayName = fixture.historyUpdateFinal))
        historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "history update",
            watermarkBefore = historyWatermark
        ) {
            val sessions = remoteSessions(runtime, fixture)
            sessionByDisplayName(sessions, fixture.historyUpdateFinal)?.deletedAt == null
        }

        val tombstoneHistory = runtime.app.database.historyEntryDao().getByUid(historyTombstoneUid)
            ?: throw AssertionError("TASK-072D tombstone history missing locally")
        runtime.repository.deleteHistoryEntry(tombstoneHistory)
        historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "history tombstone",
            watermarkBefore = historyWatermark
        ) {
            val sessions = remoteSessions(runtime, fixture)
            sessionByDisplayName(sessions, fixture.historyTombstone)?.deletedAt != null
        }

        val receiverDrain = verifyReceiverDrain(runtime, "post local matrix")
        val adminReceiver = verifyOptionalExternalReceiver(runtime, "admin", args.getString("task072DAdminRunPrefix"))
        val iosReceiver = verifyOptionalExternalReceiver(runtime, "ios", args.getString("task072DIOSRunPrefix"))
        val dbSnapshot = localDbSnapshot(runtime, fixture)
        val runAsSnapshot = runAsDatabaseSnapshot()
        assertLocalHarnessQueuesDrained(runtime, fixture, dbSnapshot)
        assertOwnerOutboxZero(runtime, "final")

        val totalMs = System.currentTimeMillis() - startedAt
        val summaryLine =
            "TASK072D_ANDROID_HARNESS owner_hash=${hash(runtime.ownerUserId)} " +
                "prefix=${fixture.prefix} product_create=pass product_update=pass product_tombstone=pass " +
                "history_create=pass history_update=pass history_tombstone=pass " +
                "receiver=${receiverDrain.status} receiverSource=${receiverDrain.source} " +
                "adminReceiver=${adminReceiver.status} iosReceiver=${iosReceiver.status} " +
                "syncType=EVENT_INCREMENTAL fullPull=false " +
                "localProducts=${dbSnapshot.products} localHistory=${dbSnapshot.history} " +
                "pendingCatalog=${dbSnapshot.pendingCatalog} pendingHistory=${dbSnapshot.pendingHistory} " +
                "pendingTombstones=${dbSnapshot.pendingTombstones} outbox=${dbSnapshot.outbox} " +
                "runAsDb=${runAsSnapshot.status} historyWatermark=$historyWatermark totalMs=$totalMs"
        println(summaryLine)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("TASK072D_ANDROID_HARNESS", summaryLine) }
        )
    }

    private suspend fun runtime(): Runtime {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "TASK-072D harness gated. Pass -e task072DLiveHarness true -e task072DRunPrefix TASK072D_ANDROID_<RUN>_.",
            isEnabled(args.getString("task072DLiveHarness"))
        )
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("service_role"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("secret_key"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("sb_secret"))

        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        val signedIn = signedInState(app) ?: run {
            importTemporarySessionIfPresent(app, args.getString("task072DSessionFile"))
            signedInState(app)
        }
            ?: throw AssertionError(
                "TASK-072D live harness requires an existing signed-in Supabase session. " +
                    "Safe repo-controlled attempts tried: restoreSession/current local Supabase session " +
                    "and optional task072DSessionFile import when provided; no interactive Google context is available here."
            )
        val client = app.supabaseClient ?: throw AssertionError("TASK-072D Supabase client missing")
        assertTrue(app.catalogRemoteDataSource.isConfigured)
        assertTrue(app.productPriceRemoteDataSource.isConfigured)
        assertTrue(app.sessionBackupRemoteDataSource.isConfigured)
        assertTrue(app.syncEventRemoteDataSource.isConfigured)
        // TASK-139 revoca il canale realtime/SELECT diretto su sync_events.
        // Il receiver viene attivato dal coordinator lifecycle e legge soltanto
        // tramite l'envelope RPC shop-scoped; il live harness non deve riaprire
        // il subscriber legacy rimosso.
        assertDeviceActiveForHarness(app)
        withTimeoutOrNull(5_000) {
            app.catalogSyncStateTracker.state.first { !it.isBusy }
        }
        return Runtime(
            app = app,
            repository = app.repository,
            ownerUserId = signedIn.userId,
            client = client
        )
    }

    private suspend fun signedInState(app: MerchandiseControlApplication): AuthState.SignedIn? {
        app.authManager.restoreSession()
        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it !is AuthState.Checking }
        } ?: app.authManager.state.value
        return authState as? AuthState.SignedIn
    }

    private suspend fun assertDeviceActiveForHarness(app: MerchandiseControlApplication) {
        var lastStatus = "missing"
        var lastCode = "missing"
        val active = withTimeoutOrNull(15_000) {
            while (true) {
                val snapshot = app.shopDeviceAuthorizationRepository
                    .registerHeartbeatAndCheck("task072d_harness_start")
                    .getOrNull()
                lastStatus = snapshot?.status ?: "null"
                lastCode = snapshot?.code ?: "null"
                if (snapshot?.status == "active" && snapshot.canWrite) {
                    return@withTimeoutOrNull true
                }
                delay(500)
            }
        } == true

        if (!active) {
            throw AssertionError("TASK-072D Android device not active for harness status=$lastStatus code=$lastCode")
        }
    }

    private suspend fun primeSyncEventWatermarkWindow(runtime: Runtime) {
        var afterId = 0L
        var latestId = 0L
        var iterations = 0
        while (iterations < 40) {
            val rows = runtime.app.syncEventRemoteDataSource.fetchSyncEventsAfter(
                ownerUserId = runtime.ownerUserId,
                storeId = null,
                afterId = afterId,
                limit = 100
            ).getOrThrow()
            if (rows.isEmpty()) break
            latestId = rows.maxOf { it.id }
            afterId = latestId
            iterations++
            if (rows.size < 100) break
        }

        val primedId = (latestId - 100).coerceAtLeast(0L)
        runtime.app.database.syncEventWatermarkDao().upsert(
            SyncEventWatermark(
                ownerUserId = runtime.ownerUserId,
                storeScope = "",
                lastSyncEventId = primedId
            )
        )
        println("TASK072D_ANDROID_WATERMARK_PRIME latest=$latestId primed=$primedId window=100")
    }

    private suspend fun importTemporarySessionIfPresent(
        app: MerchandiseControlApplication,
        sessionFilePath: String?
    ) {
        val path = sessionFilePath?.takeIf { it.isNotBlank() }
            ?: throw AssertionError(
                "TASK-072D emulator is signed out and task072DSessionFile was not provided. " +
                    "No token or credential was printed."
            )
        val file = File(path)
        if (!file.exists()) {
            throw AssertionError("TASK-072D task072DSessionFile does not exist")
        }

        val json = JSONObject(file.readText())
        val access = json.getString("access")
        val refresh = json.getString("refresh")
        if (access.isBlank() || refresh.isBlank()) {
            throw AssertionError("TASK-072D task072DSessionFile is malformed")
        }

        app.supabaseClient?.auth?.importAuthToken(
            accessToken = access,
            refreshToken = refresh,
            retrieveUser = true,
            autoRefresh = true
        ) ?: throw AssertionError("TASK-072D Supabase client missing")
        file.delete()
    }

    private fun fixture(prefix: String?): Fixture {
        val cleanPrefix = prefix
            ?: throw AssertionError("task072DRunPrefix must be explicit, e.g. TASK072D_ANDROID_R20260619_")
        assertTrue(
            cleanPrefix.startsWith("TASK072D_ANDROID_") ||
                cleanPrefix.startsWith("SYNC_TEST_")
        )
        assertTrue(cleanPrefix.endsWith("_"))
        return Fixture(cleanPrefix)
    }

    private suspend fun assertTask072DPrefixIsFresh(runtime: Runtime, fixture: Fixture) {
        for (barcode in fixture.productBarcodes) {
            if (runtime.repository.findProductByBarcode(barcode) != null) {
                throw AssertionError("TASK-072D local product already exists for barcode suffix=${barcode.takeLast(24)}")
            }
        }
        for (name in fixture.supplierNames) {
            if (runtime.repository.findSupplierByName(name) != null) {
                throw AssertionError("TASK-072D local supplier already exists for suffix=${name.takeLast(24)}")
            }
        }
        for (name in fixture.categoryNames) {
            if (runtime.repository.findCategoryByName(name) != null) {
                throw AssertionError("TASK-072D local category already exists for suffix=${name.takeLast(24)}")
            }
        }
        val remoteCatalog = remoteCatalog(runtime.client, fixture)
        if (remoteCatalog.products.isNotEmpty() || remoteCatalog.suppliers.isNotEmpty() || remoteCatalog.categories.isNotEmpty()) {
            throw AssertionError("TASK-072D remote catalog rows already exist for this prefix")
        }
        if (remoteSessions(runtime, fixture).isNotEmpty()) {
            throw AssertionError("TASK-072D remote history rows already exist for this prefix")
        }
    }

    private suspend fun waitForCatalogAutoPush(
        runtime: Runtime,
        label: String,
        remoteCondition: suspend () -> Boolean
    ): CatalogSyncSummary {
        runtime.app.catalogAutoSyncCoordinator.onAppForeground()
        val summary = withTimeoutOrNull(180_000) {
            runtime.repository.syncCatalogQuickWithEvents(
                remote = runtime.app.catalogRemoteDataSource,
                priceRemote = runtime.app.productPriceRemoteDataSource,
                syncEventRemote = runtime.app.syncEventRemoteDataSource,
                sessionRemote = runtime.app.sessionBackupRemoteDataSource,
                ownerUserId = runtime.ownerUserId,
                progressReporter = CatalogSyncProgressReporter { progress ->
                    runtime.app.catalogSyncStateTracker.update(progress)
                }
            ).getOrElse { error ->
                throw AssertionError(
                    "TASK-072D $label direct incremental push failed err=${error::class.java.simpleName}"
                )
            }
        } ?: throw AssertionError("TASK-072D $label direct incremental push did not complete within 180s")
        runtime.app.catalogSyncStateTracker.publishSummary(
            runtime.ownerUserId,
            CatalogSyncFlightOwner.AUTO_PUSH,
            summary
        )
        if (
            !summary.recordSyncEventAvailable ||
            summary.syncEventsDisabled ||
            summary.syncEventsFallback044 ||
            summary.fullCatalogFetch ||
            summary.fullPriceFetch ||
            summary.manualFullSyncRequired
        ) {
            throw AssertionError("TASK-072D $label expected EVENT_INCREMENTAL summary=$summary")
        }
        if (summary.syncEventOutboxPending != 0) {
            throw AssertionError("TASK-072D $label left sync_event outbox pending=${summary.syncEventOutboxPending}")
        }
        val remoteOk = withTimeoutOrNull(20_000) {
            while (!remoteCondition()) delay(500)
            true
        } ?: false
        if (!remoteOk) {
            throw AssertionError("TASK-072D $label remote read-back did not match after auto push")
        }
        println(
            "TASK072D_ANDROID_CATALOG_AUTOPUSH label=${label.replace(' ', '_')} " +
                "syncType=EVENT_INCREMENTAL fullPull=false " +
                "pushedCatalog=${summary.pushedSuppliers + summary.pushedCategories + summary.pushedProducts} " +
                "pushedPrices=${summary.pushedProductPrices} " +
                "watermarkBefore=${summary.syncEventsWatermarkBefore} " +
                "watermarkAfter=${summary.syncEventsWatermarkAfter}"
        )
        assertOwnerOutboxZero(runtime, label)
        return summary
    }

    private suspend fun waitForHistoryAutoPush(
        runtime: Runtime,
        label: String,
        watermarkBefore: Long,
        remoteCondition: suspend () -> Boolean
    ): Long {
        runtime.app.historySessionPushCoordinator.onAppForeground()
        withTimeoutOrNull(120_000) {
            runtime.app.historySessionPushCoordinator.runPushCycle("local_commit")
        } ?: throw AssertionError("TASK-072D $label direct history push did not complete within 120s")
        val selectedShop = runtime.app.shopContextRepository.state.value.selectedShop
        val storeScope = shopScopedStoreScope(selectedShop)
        var lastHistoryEvents: List<SyncEventRemoteRow> = emptyList()
        var completed = false
        withTimeoutOrNull(30_000) {
            while (!completed) {
                val rows = runtime.app.syncEventRemoteDataSource.fetchSyncEventsAfter(
                    ownerUserId = runtime.ownerUserId,
                    storeId = remoteStoreIdFromStoreScope(storeScope),
                    shopId = selectedShop?.shopId,
                    afterId = watermarkBefore,
                    limit = 100
                ).getOrThrow()
                lastHistoryEvents = rows.filter { it.domain == SyncEventDomains.HISTORY }
                if (lastHistoryEvents.isNotEmpty() && remoteCondition()) {
                    completed = true
                } else {
                    delay(500)
                }
            }
        }
        if (!completed) {
            throw AssertionError("TASK-072D $label direct history push did not create/apply history sync_event within 30s")
        }
        if (lastHistoryEvents.any { it.changedCount > 0 && it.entityIds?.sessionIds.orEmpty().isEmpty() }) {
            throw AssertionError("TASK-072D $label history sync_event used changed_count without targeted session_ids")
        }
        val targetedSessions = lastHistoryEvents.flatMap { it.entityIds?.sessionIds.orEmpty() }.toSet()
        println(
            "TASK072D_ANDROID_HISTORY_AUTOPUSH label=${label.replace(' ', '_')} " +
                "syncType=EVENT_INCREMENTAL fullPull=false " +
                "historyEvents=${lastHistoryEvents.size} targetedSessions=${targetedSessions.size}"
        )
        assertOwnerOutboxZero(runtime, label)
        return lastHistoryEvents.maxOf { it.id }
    }

    private suspend fun verifyReceiverDrain(runtime: Runtime, label: String): ReceiverResult {
        val previous = runtime.app.catalogSyncStateTracker.lastOutcome.value
        runtime.app.catalogAutoSyncCoordinator.onAppForeground()
        runtime.app.catalogAutoSyncCoordinator.onNetworkAvailable()
        runtime.app.catalogAutoSyncCoordinator.onRemoteSyncEventSignal()
        val outcome = withTimeoutOrNull(35_000) {
            runtime.app.catalogSyncStateTracker.lastOutcome.first { next ->
                next != null &&
                    next !== previous &&
                    next.ownerUserId == runtime.ownerUserId &&
                    next.source == CatalogSyncFlightOwner.SYNC_EVENTS &&
                    !next.summary.syncEventsDisabled &&
                    !next.summary.syncEventsFallback044 &&
                    !next.summary.fullCatalogFetch &&
                    !next.summary.fullPriceFetch &&
                    next.summary.syncEventOutboxPending == 0
            }
        }
        if (outcome == null) {
            assertOwnerOutboxZero(runtime, label)
            println(
                "TASK072D_ANDROID_RECEIVER_DRAIN label=${label.replace(' ', '_')} " +
                    "source=ALREADY_CURRENT fetched=0 processed=0 skippedSelf=0 " +
                    "targetedProducts=0 targetedHistory=0 outbox=0 syncType=EVENT_INCREMENTAL fullPull=false"
            )
            return ReceiverResult(status = "pass", source = "ALREADY_CURRENT")
        }
        println(
            "TASK072D_ANDROID_RECEIVER_DRAIN label=${label.replace(' ', '_')} " +
                "source=${outcome.source} fetched=${outcome.summary.syncEventsFetched} " +
                "processed=${outcome.summary.syncEventsProcessed} skippedSelf=${outcome.summary.syncEventsSkippedSelf} " +
                "targetedProducts=${outcome.summary.targetedProductsFetched} targetedHistory=${outcome.summary.targetedHistoryFetched} " +
                "outbox=${outcome.summary.syncEventOutboxPending} syncType=EVENT_INCREMENTAL fullPull=false"
        )
        return ReceiverResult(status = "pass", source = outcome.source.name)
    }

    private suspend fun verifyOptionalExternalReceiver(
        runtime: Runtime,
        label: String,
        prefix: String?
    ): ExternalReceiverResult {
        if (prefix.isNullOrBlank()) return ExternalReceiverResult(status = "not_configured")
        require((prefix.startsWith("TASK072D_") || prefix.startsWith("SYNC_TEST_")) && prefix.endsWith("_")) {
            "TASK-072D external prefix for $label must start with TASK072D_ or SYNC_TEST_ and end with _"
        }
        val external = ExternalFixture(prefix)
        val beforeCatalog = remoteCatalog(runtime.client, external)
        val beforeSessions = remoteSessions(runtime, external)
        val remoteRows = beforeCatalog.products.size + beforeCatalog.suppliers.size + beforeCatalog.categories.size + beforeSessions.size
        if (remoteRows == 0) {
            println("TASK072D_ANDROID_EXTERNAL_RECEIVER source=$label status=not_available prefix_hash=${hash(prefix)}")
            return ExternalReceiverResult(status = "not_available")
        }

        verifyReceiverDrain(runtime, "external $label")
        val local = localDbSnapshot(runtime, external)
        if (local.products == 0 && local.history == 0) {
            throw AssertionError("TASK-072D external $label remote rows were not applied locally")
        }
        val status = "pass"
        println(
            "TASK072D_ANDROID_EXTERNAL_RECEIVER source=$label status=$status prefix_hash=${hash(prefix)} " +
                "remoteRows=$remoteRows localProducts=${local.products} localHistory=${local.history}"
        )
        return ExternalReceiverResult(status = status)
    }

    private suspend fun assertCatalogSyncEvents(
        runtime: Runtime,
        vararg summaries: CatalogSyncSummary
    ) {
        val watermarkBefore = summaries.minOf { it.syncEventsWatermarkBefore }
        val rows = runtime.app.syncEventRemoteDataSource.fetchSyncEventsAfter(
            ownerUserId = runtime.ownerUserId,
            storeId = null,
            afterId = watermarkBefore,
            limit = 100
        ).getOrThrow()
        val catalogEvents = rows.filter { it.domain == SyncEventDomains.CATALOG && it.source == "android" }
        if (catalogEvents.isEmpty()) {
            throw AssertionError("TASK-072D catalog sync_events missing")
        }
        if (catalogEvents.any { it.changedCount > 0 && it.entityIds == null }) {
            throw AssertionError("TASK-072D catalog sync_event missing targeted entity_ids")
        }
        println(
            "TASK072D_ANDROID_SYNC_EVENTS catalogEvents=${catalogEvents.size} " +
                "targetedProducts=${catalogEvents.flatMap { it.entityIds?.productIds.orEmpty() }.toSet().size} " +
                "syncType=EVENT_INCREMENTAL fullPull=false"
        )
    }

    private suspend fun assertOwnerOutboxZero(runtime: Runtime, label: String) {
        val pending = runtime.app.database.syncEventOutboxDao().countPending(runtime.ownerUserId)
        if (pending != 0) {
            throw AssertionError("TASK-072D $label expected owner sync_event_outbox=0, got $pending")
        }
    }

    private fun assertLocalHarnessQueuesDrained(
        runtime: Runtime,
        fixture: PrefixScope,
        snapshot: LocalDbSnapshot
    ) {
        if (snapshot.pendingCatalog != 0 || snapshot.pendingHistory != 0 || snapshot.outbox != 0) {
            throw AssertionError(
                "TASK-072D local queues not drained for prefix: " +
                    "pendingCatalog=${snapshot.pendingCatalog} pendingHistory=${snapshot.pendingHistory} outbox=${snapshot.outbox}"
            )
        }
        if (snapshot.products < 2 || snapshot.history < 3) {
            throw AssertionError(
                "TASK-072D local snapshot unexpected for prefix suffix=${fixture.prefix.takeLast(24)} " +
                    "products=${snapshot.products} history=${snapshot.history}"
            )
        }
        println(
            "TASK072D_ANDROID_DB_SNAPSHOT prefix_hash=${hash(fixture.prefix)} " +
                "products=${snapshot.products} suppliers=${snapshot.suppliers} categories=${snapshot.categories} " +
                "productPrices=${snapshot.productPrices} history=${snapshot.history} " +
                "pendingCatalog=${snapshot.pendingCatalog} pendingHistory=${snapshot.pendingHistory} " +
                "pendingTombstones=${snapshot.pendingTombstones} outbox=${snapshot.outbox}"
        )
    }

    private fun historyEntry(title: String, fixture: Fixture): HistoryEntry =
        HistoryEntry(
            id = title,
            displayName = title,
            timestamp = "2026-06-19 12:00:00",
            data = listOf(listOf("barcode", "count"), listOf(title, "1")),
            editable = listOf(listOf("", ""), listOf("", "1")),
            complete = listOf(false, true),
            supplier = fixture.productSupplier,
            category = fixture.productCategory,
            syncStatus = SyncStatus.NOT_ATTEMPTED,
            totalItems = 1,
            paymentTotal = 1.0,
            missingItems = 0,
            isManualEntry = true
        )

    private suspend fun remoteCatalog(client: SupabaseClient, fixture: PrefixScope): RemoteCatalog {
        val products = client.postgrest["inventory_products"].select {
            filter { isIn("barcode", fixture.productBarcodes) }
        }.decodeList<InventoryProductRow>()
        val suppliers = client.postgrest["inventory_suppliers"].select {
            filter { isIn("name", fixture.supplierNames) }
        }.decodeList<InventorySupplierRow>()
        val categories = client.postgrest["inventory_categories"].select {
            filter { isIn("name", fixture.categoryNames) }
        }.decodeList<InventoryCategoryRow>()
        return RemoteCatalog(suppliers = suppliers, categories = categories, products = products)
    }

    private suspend fun remoteSessions(
        runtime: Runtime,
        fixture: PrefixScope
    ): List<SharedSheetSessionRecord> =
        runtime.app.sessionBackupRemoteDataSource.fetchAllSessionsForOwner().getOrThrow()
            .filter { it.displayName in fixture.historyNames }

    private fun activeProduct(rows: List<InventoryProductRow>, barcode: String): InventoryProductRow? =
        productByBarcode(rows, barcode)?.takeIf { it.deletedAt.isNullOrBlank() }

    private fun productByBarcode(rows: List<InventoryProductRow>, barcode: String): InventoryProductRow? {
        val matches = rows.filter { it.barcode == barcode }
        return if (matches.size == 1) matches.single() else null
    }

    private fun activeSupplier(rows: List<InventorySupplierRow>, name: String): InventorySupplierRow? =
        supplierByName(rows, name)?.takeIf { it.deletedAt.isNullOrBlank() }

    private fun supplierByName(rows: List<InventorySupplierRow>, name: String): InventorySupplierRow? {
        val matches = rows.filter { it.name == name }
        return if (matches.size == 1) matches.single() else null
    }

    private fun activeCategory(rows: List<InventoryCategoryRow>, name: String): InventoryCategoryRow? =
        categoryByName(rows, name)?.takeIf { it.deletedAt.isNullOrBlank() }

    private fun categoryByName(rows: List<InventoryCategoryRow>, name: String): InventoryCategoryRow? {
        val matches = rows.filter { it.name == name }
        return if (matches.size == 1) matches.single() else null
    }

    private fun sessionByDisplayName(
        sessions: List<SharedSheetSessionRecord>,
        displayName: String
    ): SharedSheetSessionRecord? {
        val matches = sessions.filter { it.displayName == displayName }
        return if (matches.size == 1) matches.single() else null
    }

    private fun localDbSnapshot(runtime: Runtime, fixture: PrefixScope): LocalDbSnapshot {
        val db = runtime.app.database.openHelper.readableDatabase
        val likePrefix = fixture.likePrefix
        val products = countQuery(
            runtime,
            "SELECT COUNT(*) FROM products WHERE barcode LIKE ? OR productName LIKE ?",
            arrayOf(likePrefix, likePrefix)
        )
        val suppliers = countQuery(runtime, "SELECT COUNT(*) FROM suppliers WHERE name LIKE ?", arrayOf(likePrefix))
        val categories = countQuery(runtime, "SELECT COUNT(*) FROM categories WHERE name LIKE ?", arrayOf(likePrefix))
        val productPrices = countQuery(
            runtime,
            """
            SELECT COUNT(*)
            FROM product_prices pp
            JOIN products p ON p.id = pp.productId
            WHERE p.barcode LIKE ? OR p.productName LIKE ?
            """.trimIndent(),
            arrayOf(likePrefix, likePrefix)
        )
        val history = countQuery(
            runtime,
            "SELECT COUNT(*) FROM history_entries WHERE displayName LIKE ? OR id LIKE ?",
            arrayOf(likePrefix, likePrefix)
        )
        val pendingCatalog = countQuery(
            runtime,
            """
            SELECT
                (SELECT COUNT(*) FROM suppliers s JOIN supplier_remote_refs r ON r.supplierId = s.id
                 WHERE s.name LIKE ? AND (r.lastRemoteAppliedAt IS NULL OR r.localChangeRevision > r.lastSyncedLocalRevision)) +
                (SELECT COUNT(*) FROM categories c JOIN category_remote_refs r ON r.categoryId = c.id
                 WHERE c.name LIKE ? AND (r.lastRemoteAppliedAt IS NULL OR r.localChangeRevision > r.lastSyncedLocalRevision)) +
                (SELECT COUNT(*) FROM products p JOIN product_remote_refs r ON r.productId = p.id
                 WHERE (p.barcode LIKE ? OR p.productName LIKE ?)
                   AND (r.lastRemoteAppliedAt IS NULL OR r.localChangeRevision > r.lastSyncedLocalRevision))
            """.trimIndent(),
            arrayOf(likePrefix, likePrefix, likePrefix, likePrefix)
        )
        val pendingHistory = countQuery(
            runtime,
            """
            SELECT COUNT(*)
            FROM history_entries h
            LEFT JOIN history_entry_remote_refs r ON r.historyEntryUid = h.uid
            WHERE (h.displayName LIKE ? OR h.id LIKE ?)
              AND (
                r.historyEntryUid IS NULL
                OR r.lastRemoteAppliedAt IS NULL
                OR r.localChangeRevision > r.lastSyncedLocalRevision
              )
            """.trimIndent(),
            arrayOf(likePrefix, likePrefix)
        )
        val pendingTombstones = countQuery(runtime, "SELECT COUNT(*) FROM pending_catalog_tombstones", emptyArray())
        val outbox = countQuery(
            runtime,
            "SELECT COUNT(*) FROM sync_event_outbox WHERE ownerUserId = ?",
            arrayOf(runtime.ownerUserId)
        )
        db.query("PRAGMA wal_checkpoint(PASSIVE)").use { }
        return LocalDbSnapshot(
            products = products,
            suppliers = suppliers,
            categories = categories,
            productPrices = productPrices,
            history = history,
            pendingCatalog = pendingCatalog,
            pendingHistory = pendingHistory,
            pendingTombstones = pendingTombstones,
            outbox = outbox
        )
    }

    private fun countQuery(runtime: Runtime, sql: String, bindArgs: Array<Any>): Int {
        val db = runtime.app.database.openHelper.readableDatabase
        return db.query(sql, bindArgs).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun runAsDatabaseSnapshot(): RunAsSnapshot {
        val command = "run-as $PACKAGE_NAME sh -c 'ls -l databases/app_database* 2>/dev/null'"
        val output = executeShell(command)
        val status = if (output.contains("app_database")) "pass" else "missing"
        val redacted = output
            .lineSequence()
            .filter { it.contains("app_database") }
            .joinToString(";") { it.substringAfterLast(' ') }
            .ifBlank { "none" }
        println(
            "TASK072D_ANDROID_RUN_AS_DB status=$status package=$PACKAGE_NAME " +
                "adb='adb shell $command' files=$redacted"
        )
        return RunAsSnapshot(status = status)
    }

    private fun executeShell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun isEnabled(value: String?): Boolean =
        value?.lowercase() == "1" || value?.lowercase() == "true"

    private data class Runtime(
        val app: MerchandiseControlApplication,
        val repository: DefaultInventoryRepository,
        val ownerUserId: String,
        val client: SupabaseClient
    )

    private data class RemoteCatalog(
        val suppliers: List<InventorySupplierRow>,
        val categories: List<InventoryCategoryRow>,
        val products: List<InventoryProductRow>
    )

    private data class LocalDbSnapshot(
        val products: Int,
        val suppliers: Int,
        val categories: Int,
        val productPrices: Int,
        val history: Int,
        val pendingCatalog: Int,
        val pendingHistory: Int,
        val pendingTombstones: Int,
        val outbox: Int
    )

    private data class ReceiverResult(
        val status: String,
        val source: String
    )

    private data class ExternalReceiverResult(val status: String)

    private data class RunAsSnapshot(val status: String)

    private interface PrefixScope {
        val prefix: String
        val likePrefix: String
        val supplierNames: List<String>
        val categoryNames: List<String>
        val productBarcodes: List<String>
        val historyNames: List<String>
    }

    private data class Fixture(override val prefix: String) : PrefixScope {
        override val likePrefix: String = "$prefix%"
        val productSupplier: String = "${prefix}PRODUCT_SUPPLIER"
        val productCategory: String = "${prefix}PRODUCT_CATEGORY"
        val supplierInitial: String = "${prefix}SUPPLIER_UPDATE_INITIAL"
        val supplierFinal: String = "${prefix}SUPPLIER_UPDATE_FINAL"
        val categoryInitial: String = "${prefix}CATEGORY_UPDATE_INITIAL"
        val categoryFinal: String = "${prefix}CATEGORY_UPDATE_FINAL"
        val productCreateBarcode: String = "${prefix}PRODUCT_CREATE"
        val productUpdateBarcode: String = "${prefix}PRODUCT_UPDATE"
        val productTombstoneBarcode: String = "${prefix}PRODUCT_TOMBSTONE"
        val productCreateName: String = "${prefix}PRODUCT_CREATE_NAME"
        val productUpdateInitialName: String = "${prefix}PRODUCT_UPDATE_INITIAL_NAME"
        val productUpdateFinalName: String = "${prefix}PRODUCT_UPDATE_FINAL_NAME"
        val productTombstoneName: String = "${prefix}PRODUCT_TOMBSTONE_NAME"
        val historyCreate: String = "${prefix}HISTORY_CREATE"
        val historyUpdateInitial: String = "${prefix}HISTORY_UPDATE_INITIAL"
        val historyUpdateFinal: String = "${prefix}HISTORY_UPDATE_FINAL"
        val historyTombstone: String = "${prefix}HISTORY_TOMBSTONE"
        override val supplierNames: List<String> = listOf(productSupplier, supplierInitial, supplierFinal)
        override val categoryNames: List<String> = listOf(productCategory, categoryInitial, categoryFinal)
        override val productBarcodes: List<String> = listOf(
            productCreateBarcode,
            productUpdateBarcode,
            productTombstoneBarcode
        )
        override val historyNames: List<String> = listOf(
            historyCreate,
            historyUpdateInitial,
            historyUpdateFinal,
            historyTombstone
        )
    }

    private data class ExternalFixture(override val prefix: String) : PrefixScope {
        override val likePrefix: String = "$prefix%"
        override val supplierNames: List<String> = listOf(
            "${prefix}PRODUCT_SUPPLIER",
            "${prefix}SUPPLIER_UPDATE_INITIAL",
            "${prefix}SUPPLIER_UPDATE_FINAL"
        )
        override val categoryNames: List<String> = listOf(
            "${prefix}PRODUCT_CATEGORY",
            "${prefix}CATEGORY_UPDATE_INITIAL",
            "${prefix}CATEGORY_UPDATE_FINAL"
        )
        override val productBarcodes: List<String> = listOf(
            "${prefix}PRODUCT_CREATE",
            "${prefix}PRODUCT_UPDATE",
            "${prefix}PRODUCT_TOMBSTONE"
        )
        override val historyNames: List<String> = listOf(
            "${prefix}HISTORY_CREATE",
            "${prefix}HISTORY_UPDATE_INITIAL",
            "${prefix}HISTORY_UPDATE_FINAL",
            "${prefix}HISTORY_TOMBSTONE"
        )
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.merchandisecontrolsplitview"
    }
}

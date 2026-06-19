package com.example.merchandisecontrolsplitview

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogDeleteStrategy
import com.example.merchandisecontrolsplitview.data.CatalogEntityKind
import com.example.merchandisecontrolsplitview.data.CatalogSyncFlightOwner
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
import com.example.merchandisecontrolsplitview.data.SyncStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task072CAndroidBidirectionalHarnessTest {

    @Test
    fun androidCatalogAndHistoryPushMatrixWithTask072CPrefix() = runBlocking {
        val fixture = fixture()
        val runtime = runtime()
        val startedAt = System.currentTimeMillis()

        runtime.app.catalogAutoSyncCoordinator.onAppBackground()
        runtime.app.historySessionPushCoordinator.onAppBackground()
        assertTask072CPrefixIsFresh(runtime, fixture)

        val productSupplier = runtime.repository.addSupplier(fixture.productSupplier)
            ?: throw AssertionError("TASK-072C product supplier create failed")
        val productCategory = runtime.repository.addCategory(fixture.productCategory)
            ?: throw AssertionError("TASK-072C product category create failed")
        val standaloneSupplier = runtime.repository.addSupplier(fixture.supplierInitial)
            ?: throw AssertionError("TASK-072C standalone supplier create failed")
        val standaloneCategory = runtime.repository.addCategory(fixture.categoryInitial)
            ?: throw AssertionError("TASK-072C standalone category create failed")

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
                activeProduct(catalog.products, fixture.productTombstoneBarcode)?.productName == fixture.productTombstoneName &&
                activeSupplier(catalog.suppliers, fixture.supplierInitial) != null &&
                activeCategory(catalog.categories, fixture.categoryInitial) != null
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
            ?: throw AssertionError("TASK-072C update product missing locally")
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
            ?: throw AssertionError("TASK-072C tombstone product missing locally")
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

        assertCatalogSyncEvents(runtime, createSummary, updateSummary, tombstoneSummary)

        val historyCreateUid = runtime.repository.insertHistoryEntry(historyEntry(fixture.historyCreate, fixture))
        val historyUpdateUid = runtime.repository.insertHistoryEntry(historyEntry(fixture.historyUpdateInitial, fixture))
        val historyTombstoneUid = runtime.repository.insertHistoryEntry(historyEntry(fixture.historyTombstone, fixture))
        val catalogWatermark = maxOf(
            createSummary.syncEventsWatermarkAfter,
            updateSummary.syncEventsWatermarkAfter,
            tombstoneSummary.syncEventsWatermarkAfter
        )
        var historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "history create",
            watermarkBefore = catalogWatermark,
            changedUid = historyCreateUid
        ) {
            val sessions = remoteSessions(runtime, fixture)
            sessionByDisplayName(sessions, fixture.historyCreate)?.deletedAt == null &&
                sessionByDisplayName(sessions, fixture.historyUpdateInitial)?.deletedAt == null &&
                sessionByDisplayName(sessions, fixture.historyTombstone)?.deletedAt == null
        }

        val updateHistory = runtime.app.database.historyEntryDao().getByUid(historyUpdateUid)
            ?: throw AssertionError("TASK-072C update history missing locally")
        runtime.repository.updateHistoryEntry(updateHistory.copy(displayName = fixture.historyUpdateFinal))
        historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "history update",
            watermarkBefore = historyWatermark,
            changedUid = historyUpdateUid
        ) {
            val sessions = remoteSessions(runtime, fixture)
            sessionByDisplayName(sessions, fixture.historyUpdateFinal)?.deletedAt == null
        }

        val tombstoneHistory = runtime.app.database.historyEntryDao().getByUid(historyTombstoneUid)
            ?: throw AssertionError("TASK-072C tombstone history missing locally")
        runtime.repository.deleteHistoryEntry(tombstoneHistory)
        historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "history tombstone",
            watermarkBefore = historyWatermark,
            changedUid = historyTombstoneUid
        ) {
            val sessions = remoteSessions(runtime, fixture)
            sessionByDisplayName(sessions, fixture.historyTombstone)?.deletedAt != null
        }

        val localCounts = localTask072CCounts(runtime, fixture)
        val totalMs = System.currentTimeMillis() - startedAt
        val summaryLine =
            "TASK072C_ANDROID_HARNESS owner_hash=${hash(runtime.ownerUserId)} " +
                "prefix=${fixture.prefix} catalog_create=pass catalog_update=pass catalog_tombstone=pass " +
                "history_create=pass history_update=pass history_tombstone=pass " +
                "restore=not_supported_public_runtime_path " +
                "syncType=EVENT_INCREMENTAL fullPull=false " +
                "localProducts=${localCounts.products} localHistory=${localCounts.history} " +
                "historyWatermark=$historyWatermark totalMs=$totalMs"
        println(summaryLine)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("TASK072C_ANDROID_HARNESS", summaryLine) }
        )
    }

    private suspend fun runtime(): Runtime {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "TASK-072C harness gated. Pass -e task072CLiveHarness true -e task072CRunPrefix TASK072C_ANDROID_<RUN>_.",
            isEnabled(args.getString("task072CLiveHarness"))
        )
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("service_role"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("secret_key"))
        assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.lowercase().contains("sb_secret"))

        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        app.authManager.restoreSession()
        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it !is AuthState.Checking }
        } ?: app.authManager.state.value
        val signedIn = authState as? AuthState.SignedIn
            ?: throw AssertionError("TASK-072C live harness requires signed-in Supabase session: ${authState::class.java.simpleName}")
        val client = app.supabaseClient ?: throw AssertionError("TASK-072C Supabase client missing")
        assertTrue(app.catalogRemoteDataSource.isConfigured)
        assertTrue(app.productPriceRemoteDataSource.isConfigured)
        assertTrue(app.sessionBackupRemoteDataSource.isConfigured)
        assertTrue(app.syncEventRemoteDataSource.isConfigured)
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

    private fun fixture(): Fixture {
        val prefix = InstrumentationRegistry.getArguments()
            .getString("task072CRunPrefix")
            ?: throw AssertionError("task072CRunPrefix must be explicit, e.g. TASK072C_ANDROID_R20260619_")
        assertTrue(prefix.startsWith("TASK072C_ANDROID_"))
        assertTrue(prefix.endsWith("_"))
        return Fixture(prefix)
    }

    private suspend fun assertTask072CPrefixIsFresh(runtime: Runtime, fixture: Fixture) {
        for (barcode in fixture.productBarcodes) {
            if (runtime.repository.findProductByBarcode(barcode) != null) {
                throw AssertionError("TASK-072C local product already exists for barcode suffix=${barcode.takeLast(24)}; use a fresh task072CRunPrefix")
            }
        }
        for (name in fixture.supplierNames) {
            if (runtime.repository.findSupplierByName(name) != null) {
                throw AssertionError("TASK-072C local supplier already exists for suffix=${name.takeLast(24)}; use a fresh task072CRunPrefix")
            }
        }
        for (name in fixture.categoryNames) {
            if (runtime.repository.findCategoryByName(name) != null) {
                throw AssertionError("TASK-072C local category already exists for suffix=${name.takeLast(24)}; use a fresh task072CRunPrefix")
            }
        }
        val remoteCatalog = remoteCatalog(runtime.client, fixture)
        if (remoteCatalog.products.isNotEmpty() || remoteCatalog.suppliers.isNotEmpty() || remoteCatalog.categories.isNotEmpty()) {
            throw AssertionError("TASK-072C remote catalog rows already exist for this prefix; use a fresh task072CRunPrefix")
        }
        if (remoteSessions(runtime, fixture).isNotEmpty()) {
            throw AssertionError("TASK-072C remote history rows already exist for this prefix; use a fresh task072CRunPrefix")
        }
    }

    private suspend fun waitForCatalogAutoPush(
        runtime: Runtime,
        label: String,
        remoteCondition: suspend () -> Boolean
    ): CatalogSyncSummary {
        val previous = runtime.app.catalogSyncStateTracker.lastOutcome.value
        runtime.app.catalogAutoSyncCoordinator.onAppForeground()
        runtime.app.catalogAutoSyncCoordinator.onLocalCatalogChanged()
        val outcome = withTimeoutOrNull(60_000) {
            runtime.app.catalogSyncStateTracker.lastOutcome.first { next ->
                next != null &&
                    next !== previous &&
                    next.source == CatalogSyncFlightOwner.AUTO_PUSH &&
                    next.ownerUserId == runtime.ownerUserId &&
                    next.summary.recordSyncEventAvailable &&
                    !next.summary.syncEventsDisabled &&
                    !next.summary.syncEventsFallback044 &&
                    !next.summary.fullCatalogFetch &&
                    !next.summary.fullPriceFetch &&
                    !next.summary.manualFullSyncRequired
            }
        } ?: throw AssertionError("TASK-072C $label auto push did not complete through EVENT_INCREMENTAL within 60s")
        if (outcome.summary.syncEventOutboxPending != 0) {
            throw AssertionError("TASK-072C $label left sync_event outbox pending=${outcome.summary.syncEventOutboxPending}")
        }
        val remoteOk = withTimeoutOrNull(20_000) {
            while (!remoteCondition()) {
                delay(500)
            }
            true
        } ?: false
        if (!remoteOk) {
            throw AssertionError("TASK-072C $label remote read-back did not match after auto push")
        }
        println(
            "TASK072C_ANDROID_CATALOG_AUTOPUSH label=${label.replace(' ', '_')} " +
                "syncType=EVENT_INCREMENTAL fullPull=false " +
                "pushedCatalog=${outcome.summary.pushedSuppliers + outcome.summary.pushedCategories + outcome.summary.pushedProducts} " +
                "pushedPrices=${outcome.summary.pushedProductPrices} " +
                "watermarkBefore=${outcome.summary.syncEventsWatermarkBefore} " +
                "watermarkAfter=${outcome.summary.syncEventsWatermarkAfter}"
        )
        return outcome.summary
    }

    private suspend fun waitForHistoryAutoPush(
        runtime: Runtime,
        label: String,
        watermarkBefore: Long,
        changedUid: Long,
        remoteCondition: suspend () -> Boolean
    ): Long {
        runtime.app.historySessionPushCoordinator.onAppForeground()
        runtime.app.historySessionPushCoordinator.onLocalHistorySessionChanged(changedUid)
        var lastHistoryEvents: List<SyncEventRemoteRow> = emptyList()
        var completed = false
        withTimeoutOrNull(60_000) {
            while (!completed) {
                val rows = runtime.app.syncEventRemoteDataSource.fetchSyncEventsAfter(
                    ownerUserId = runtime.ownerUserId,
                    storeId = null,
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
            throw AssertionError("TASK-072C $label auto push did not create/apply history sync_event within 60s")
        }
        if (lastHistoryEvents.any { it.changedCount > 0 && it.entityIds?.sessionIds.orEmpty().isEmpty() }) {
            throw AssertionError("TASK-072C $label history sync_event used changed_count without targeted session_ids")
        }
        val targetedSessions = lastHistoryEvents.flatMap { it.entityIds?.sessionIds.orEmpty() }.toSet()
        println(
            "TASK072C_ANDROID_HISTORY_AUTOPUSH label=${label.replace(' ', '_')} " +
                "syncType=EVENT_INCREMENTAL fullPull=false " +
                "historyEvents=${lastHistoryEvents.size} targetedSessions=${targetedSessions.size}"
        )
        return lastHistoryEvents.maxOf { it.id }
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
            throw AssertionError("TASK-072C catalog sync_events missing")
        }
        if (catalogEvents.any { it.changedCount > 0 && it.entityIds == null }) {
            throw AssertionError("TASK-072C catalog sync_event missing targeted entity_ids")
        }
        println(
            "TASK072C_ANDROID_SYNC_EVENTS catalogEvents=${catalogEvents.size} " +
                "targetedProducts=${catalogEvents.flatMap { it.entityIds?.productIds.orEmpty() }.toSet().size} " +
                "syncType=EVENT_INCREMENTAL fullPull=false"
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

    private suspend fun remoteCatalog(client: SupabaseClient, fixture: Fixture): RemoteCatalog {
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
        fixture: Fixture
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

    private fun localTask072CCounts(runtime: Runtime, fixture: Fixture): LocalCounts {
        val db = runtime.app.database.openHelper.readableDatabase
        val products = db.query(
            "SELECT COUNT(*) FROM products WHERE barcode LIKE ? OR productName LIKE ?",
            arrayOf(fixture.likePrefix, fixture.likePrefix)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val history = db.query(
            "SELECT COUNT(*) FROM history_entries WHERE displayName LIKE ? OR id LIKE ?",
            arrayOf(fixture.likePrefix, fixture.likePrefix)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return LocalCounts(products = products, history = history)
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

    private data class LocalCounts(
        val products: Int,
        val history: Int
    )

    private data class Fixture(val prefix: String) {
        val likePrefix: String = "$prefix%"
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
        val supplierNames: List<String> = listOf(productSupplier, supplierInitial, supplierFinal)
        val categoryNames: List<String> = listOf(productCategory, categoryInitial, categoryFinal)
        val productBarcodes: List<String> = listOf(
            productCreateBarcode,
            productUpdateBarcode,
            productTombstoneBarcode
        )
        val historyNames: List<String> = listOf(
            historyCreate,
            historyUpdateInitial,
            historyUpdateFinal,
            historyTombstone
        )
    }
}

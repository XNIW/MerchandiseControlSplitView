package com.example.merchandisecontrolsplitview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogSyncProgressReporter
import com.example.merchandisecontrolsplitview.data.CatalogTombstonePatch
import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.HistoryEntry
import com.example.merchandisecontrolsplitview.data.HistoryEntryRemoteRef
import com.example.merchandisecontrolsplitview.data.InventoryCatalogFetchBundle
import com.example.merchandisecontrolsplitview.data.InventoryCategoryRow
import com.example.merchandisecontrolsplitview.data.InventoryProductPriceRow
import com.example.merchandisecontrolsplitview.data.InventoryProductRow
import com.example.merchandisecontrolsplitview.data.InventorySupplierRow
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.CatalogEntityKind
import com.example.merchandisecontrolsplitview.data.ProductRemoteRef
import com.example.merchandisecontrolsplitview.data.ProductPriceRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SessionBackupRemoteDataSource
import com.example.merchandisecontrolsplitview.data.SharedSheetSessionRecord
import com.example.merchandisecontrolsplitview.data.SyncStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task103CrossPlatformAcceptanceTest {
    @Test
    fun test02AndroidPullIOSSmokeAndLocalReadBack() = runBlocking {
        requireLiveAcceptanceEnabled()
        val fixture = fixture()
        val runtime = runtime(fixture)
        val summary = runtime.repository.pullCatalogBootstrapFromRemote(
            remote = runtime.catalogRemote,
            priceRemote = runtime.priceRemote,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        val product = runtime.repository.findProductByBarcode(fixture.barcodeIOS)
            ?: throw AssertionError("TASK-103 iOS canary missing after Android pull")
        assertEquals(fixture.productIOS, product.productName)
        assertLocalPrices(runtime.repository, fixture.barcodeIOS, expectedIOS())

        val details = runtime.repository.getProductDetailsById(product.id)
        assertNotNull(details)
        assertPrice(details?.currentPurchasePrice, 12.35, "Android iOS-canary current purchase")
        assertPrice(details?.prevPurchase, 11.10, "Android iOS-canary previous purchase")
        assertPrice(details?.currentRetailPrice, 20.50, "Android iOS-canary current retail")
        assertPrice(details?.prevRetail, 18.90, "Android iOS-canary previous retail")

        println(
            "${fixture.logPrefix}_ANDROID_PULL_IOS owner_hash=${hash(runtime.ownerUserId)} " +
                "pulled_products=${summary.pulledProducts} pulled_prices=${summary.pulledProductPrices} " +
                "room_detail=true"
        )
    }

    @Test
    fun test03AndroidWriteSmokeAndRemoteReadBack() = runBlocking {
        requireLiveAcceptanceEnabled()
        val fixture = fixture()
        val runtime = runtime(fixture)
        runtime.app.database.productDao().findByBarcode(fixture.barcodeAndroid)?.let {
            runtime.app.database.productDao().delete(it)
        }

        val supplier = runtime.repository.addSupplier(fixture.supplierAndroid)
            ?: runtime.repository.findSupplierByName(fixture.supplierAndroid)
            ?: throw AssertionError("TASK-103 Android supplier unavailable")
        val category = runtime.repository.addCategory(fixture.categoryAndroid)
            ?: runtime.repository.findCategoryByName(fixture.categoryAndroid)
            ?: throw AssertionError("TASK-103 Android category unavailable")
        runtime.repository.renameCatalogEntry(
            kind = CatalogEntityKind.SUPPLIER,
            id = supplier.id,
            newName = fixture.supplierAndroid
        )
        runtime.repository.renameCatalogEntry(
            kind = CatalogEntityKind.CATEGORY,
            id = category.id,
            newName = fixture.categoryAndroid
        )

        val existing = runtime.repository.findProductByBarcode(fixture.barcodeAndroid)
        if (existing == null) {
            runtime.repository.addProduct(
                Product(
                    barcode = fixture.barcodeAndroid,
                    itemNumber = "${fixture.prefix}ITEM_ANDROID_0001",
                    productName = fixture.productAndroid,
                    supplierId = supplier.id,
                    categoryId = category.id,
                    purchasePrice = null,
                    retailPrice = null,
                    stockQuantity = 9.0
                )
            )
        } else {
            assertEquals(fixture.productAndroid, existing.productName)
        }

        val product = runtime.repository.findProductByBarcode(fixture.barcodeAndroid)
            ?: throw AssertionError("TASK-103 Android product was not inserted locally")
        for (point in expectedAndroid()) {
            runtime.repository.recordPriceIfChanged(
                productId = product.id,
                type = point.type,
                price = point.price,
                at = point.effectiveAt,
                source = "TASK103_ANDROID_PUSH"
            )
        }
        runtime.app.database.productDao().update(
            product.copy(
                purchasePrice = 22.35,
                retailPrice = 33.50
            )
        )

        val summary = runtime.repository.syncCatalogWithRemote(
            remote = runtime.catalogRemote,
            priceRemote = runtime.priceRemote,
            ownerUserId = runtime.ownerUserId
        ).getOrThrow()

        val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
        val prices = runtime.priceRemote.fetchProductPrices().getOrThrow()
        val remote = singleActiveProduct(catalog, fixture.barcodeAndroid)
            ?: throw AssertionError("TASK-103 remote Android canary missing")
        assertEquals(fixture.productAndroid, remote.productName)
        assertPrice(remote.purchasePrice, 22.35, "remote Android catalog purchase")
        assertPrice(remote.retailPrice, 33.50, "remote Android catalog retail")
        assertRemotePrices(prices.filter { it.productId == remote.id }, expectedAndroid())
        assertLocalPrices(runtime.repository, fixture.barcodeAndroid, expectedAndroid())

        val afterSecondSync = runtime.repository.syncCatalogWithRemote(
            remote = runtime.catalogRemote,
            priceRemote = runtime.priceRemote,
            ownerUserId = runtime.ownerUserId
        ).getOrThrow()
        val catalogAfterNoOp = runtime.catalogRemote.fetchCatalog().getOrThrow()
        assertEquals(1, activeProducts(catalogAfterNoOp, setOf(fixture.barcodeAndroid)).size)

        println(
            "${fixture.logPrefix}_ANDROID_WRITE_SMOKE owner_hash=${hash(runtime.ownerUserId)} " +
                "pushed_catalog=${summary.pushedSuppliers + summary.pushedCategories + summary.pushedProducts} " +
                "pushed_prices=${summary.pushedProductPrices} product_hash=${hash(remote.id)} " +
                "second_noop_pushed=${afterSecondSync.pushedSuppliers + afterSecondSync.pushedCategories + afterSecondSync.pushedProducts + afterSecondSync.pushedProductPrices}"
        )
    }

    @Test
    fun test114AndroidWriteProductHistoryMatrix() = runBlocking {
        requireLiveAcceptanceEnabled()
        val fixture = fixture()
        val runtime = runtime(fixture)
        val supplier = runtime.repository.addSupplier(fixture.matrixSupplierAndroid)
            ?: runtime.repository.findSupplierByName(fixture.matrixSupplierAndroid)
            ?: throw AssertionError("TASK-114 Android matrix supplier unavailable")
        val category = runtime.repository.addCategory(fixture.matrixCategoryAndroid)
            ?: runtime.repository.findCategoryByName(fixture.matrixCategoryAndroid)
            ?: throw AssertionError("TASK-114 Android matrix category unavailable")

        val createProduct = Product(
            barcode = fixture.matrixBarcodeAndroidCreate,
            itemNumber = "${fixture.prefix}MATRIX_ANDROID_CREATE_ITEM",
            productName = fixture.matrixProductAndroidCreate,
            supplierId = supplier.id,
            categoryId = category.id,
            purchasePrice = 44.10,
            retailPrice = 55.20,
            stockQuantity = 6.0
        )
        val updateProduct = Product(
            barcode = fixture.matrixBarcodeAndroidUpdate,
            itemNumber = "${fixture.prefix}MATRIX_ANDROID_UPDATE_ITEM",
            productName = "${fixture.prefix}MATRIX_ANDROID_PRODUCT_UPDATE_INITIAL",
            supplierId = supplier.id,
            categoryId = category.id,
            purchasePrice = 45.10,
            retailPrice = 56.20,
            stockQuantity = 7.0
        )
        val tombstoneProduct = Product(
            barcode = fixture.matrixBarcodeAndroidTombstone,
            itemNumber = "${fixture.prefix}MATRIX_ANDROID_TOMBSTONE_ITEM",
            productName = fixture.matrixProductAndroidTombstone,
            supplierId = supplier.id,
            categoryId = category.id,
            purchasePrice = 46.10,
            retailPrice = 57.20,
            stockQuantity = 8.0
        )
        for (product in listOf(createProduct, updateProduct, tombstoneProduct)) {
            runtime.repository.findProductByBarcode(product.barcode)?.let { runtime.repository.deleteProduct(it) }
            runtime.repository.addProduct(product)
        }
        runtime.repository.syncCatalogWithRemote(runtime.catalogRemote, runtime.priceRemote, runtime.ownerUserId).getOrThrow()

        val updateLocal = runtime.repository.findProductByBarcode(fixture.matrixBarcodeAndroidUpdate)
            ?: throw AssertionError("TASK-114 Android update product missing locally")
        runtime.repository.updateProduct(updateLocal.copy(productName = fixture.matrixProductAndroidUpdateFinal))
        runtime.repository.syncCatalogWithRemote(runtime.catalogRemote, runtime.priceRemote, runtime.ownerUserId).getOrThrow()

        val tombstoneLocal = runtime.repository.findProductByBarcode(fixture.matrixBarcodeAndroidTombstone)
            ?: throw AssertionError("TASK-114 Android tombstone product missing locally")
        runtime.repository.deleteProduct(tombstoneLocal)
        runtime.repository.syncCatalogWithRemote(runtime.catalogRemote, runtime.priceRemote, runtime.ownerUserId).getOrThrow()

        val createHistoryUid = runtime.repository.insertHistoryEntry(matrixHistoryEntry(fixture.matrixHistoryAndroidCreate, fixture))
        val updateHistoryUid = runtime.repository.insertHistoryEntry(matrixHistoryEntry("${fixture.prefix}MATRIX_ANDROID_HISTORY_UPDATE_INITIAL", fixture))
        val tombstoneHistoryUid = runtime.repository.insertHistoryEntry(matrixHistoryEntry(fixture.matrixHistoryAndroidTombstone, fixture))
        runtime.repository.pushHistorySessionsToRemote(
            runtime.sessionRemote,
            runtime.ownerUserId,
            setOf(createHistoryUid, updateHistoryUid, tombstoneHistoryUid)
        ).getOrThrow()

        val updateHistory = runtime.app.database.historyEntryDao().getByUid(updateHistoryUid)
            ?: throw AssertionError("TASK-114 Android update history missing locally")
        runtime.repository.updateHistoryEntry(updateHistory.copy(displayName = fixture.matrixHistoryAndroidUpdateFinal))
        runtime.repository.pushHistorySessionsToRemote(runtime.sessionRemote, runtime.ownerUserId, setOf(updateHistoryUid)).getOrThrow()

        val tombstoneHistory = runtime.app.database.historyEntryDao().getByUid(tombstoneHistoryUid)
            ?: throw AssertionError("TASK-114 Android tombstone history missing locally")
        runtime.repository.deleteHistoryEntry(tombstoneHistory)
        runtime.repository.pushHistorySessionsToRemote(runtime.sessionRemote, runtime.ownerUserId, setOf(tombstoneHistoryUid)).getOrThrow()

        val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
        assertEquals(fixture.matrixProductAndroidCreate, singleActiveProduct(catalog, fixture.matrixBarcodeAndroidCreate)?.productName)
        assertEquals(fixture.matrixProductAndroidUpdateFinal, singleActiveProduct(catalog, fixture.matrixBarcodeAndroidUpdate)?.productName)
        assertNotNull(productByBarcode(catalog, fixture.matrixBarcodeAndroidTombstone)?.deletedAt)

        val sessions = matrixSessions(runtime, fixture)
        assertNotNull(sessionByDisplayName(sessions, fixture.matrixHistoryAndroidCreate))
        assertNotNull(sessionByDisplayName(sessions, fixture.matrixHistoryAndroidUpdateFinal))
        assertNotNull(sessionByDisplayName(sessions, fixture.matrixHistoryAndroidTombstone)?.deletedAt)

        println(
            "${fixture.logPrefix}_ANDROID_WRITE_MATRIX owner_hash=${hash(runtime.ownerUserId)} " +
                "product_create=pass product_update=pass product_tombstone=pass " +
                "history_create=pass history_update=pass history_tombstone=pass"
        )
    }

    @Test
    fun test114AndroidPullIOSProductHistoryMatrix() = runBlocking {
        requireLiveAcceptanceEnabled()
        val fixture = fixture()
        val runtime = runtime(fixture)
        val catalogBefore = runtime.catalogRemote.fetchCatalog().getOrThrow()
        val iosTombstone = productByBarcode(catalogBefore, fixture.matrixBarcodeIOSTombstone)
            ?: throw AssertionError("TASK-114 iOS tombstone product missing remotely")
        seedLocalProductTombstoneTarget(runtime, iosTombstone)

        val sessionsBefore = matrixSessions(runtime, fixture)
        val iosHistoryTombstone = sessionByDisplayName(sessionsBefore, fixture.matrixHistoryIOSTombstone)
            ?: throw AssertionError("TASK-114 iOS tombstone history missing remotely")
        seedLocalHistoryTombstoneTarget(runtime, iosHistoryTombstone)

        runtime.repository.pullCatalogBootstrapFromRemote(
            remote = runtime.catalogRemote,
            priceRemote = runtime.priceRemote,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()
        runtime.repository.bootstrapHistorySessionsFromRemote(runtime.sessionRemote).getOrThrow()

        assertEquals(
            fixture.matrixProductIOSCreate,
            runtime.repository.findProductByBarcode(fixture.matrixBarcodeIOSCreate)?.productName
        )
        assertEquals(
            fixture.matrixProductIOSUpdateFinal,
            runtime.repository.findProductByBarcode(fixture.matrixBarcodeIOSUpdate)?.productName
        )
        assertNull(runtime.repository.findProductByBarcode(fixture.matrixBarcodeIOSTombstone))

        val historyCreate = localHistoryByRemote(runtime, sessionByDisplayName(sessionsBefore, fixture.matrixHistoryIOSCreate)!!)
        val historyUpdate = localHistoryByRemote(runtime, sessionByDisplayName(sessionsBefore, fixture.matrixHistoryIOSUpdateFinal)!!)
        val historyDeleted = localHistoryByRemote(runtime, iosHistoryTombstone)
        assertEquals(fixture.matrixHistoryIOSCreate, historyCreate?.displayName)
        assertEquals(fixture.matrixHistoryIOSUpdateFinal, historyUpdate?.displayName)
        assertNotNull(historyDeleted?.deletedAt)

        println(
            "${fixture.logPrefix}_ANDROID_PULL_IOS_MATRIX owner_hash=${hash(runtime.ownerUserId)} " +
                "product_create=pass product_update=pass product_tombstone=pass " +
            "history_create=pass history_update=pass history_tombstone=pass"
        )
    }

    @Test
    fun test114AndroidCleanupLocalHistoryResidue() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "TASK-114 local cleanup is gated. Pass -e task114LocalCleanup true.",
            isEnabled(args.getString("task114LocalCleanup")?.lowercase())
        )
        val prefix = args.getString("task114CleanupPrefix")
            ?: throw AssertionError("task114CleanupPrefix is required")
        require(prefix.startsWith("TASK114_") && prefix.endsWith("_") && !prefix.contains("%")) {
            "TASK-114 cleanup prefix must be explicit, task-scoped and suffix '_'"
        }
        val execute = isEnabled(args.getString("task114CleanupExecute")?.lowercase())
        val likePrefix = "$prefix%"
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        val db = app.database.openHelper.writableDatabase
        val beforeHistory = countTask114LocalHistory(db, likePrefix)
        val beforeRefs = countTask114LocalHistoryRefs(db, likePrefix)

        if (execute && beforeHistory > 0) {
            db.beginTransaction()
            try {
                db.execSQL(
                    """
                    DELETE FROM history_entry_remote_refs
                    WHERE historyEntryUid IN (
                        SELECT uid FROM history_entries
                        WHERE displayName LIKE ? OR id LIKE ?
                    )
                    """.trimIndent(),
                    arrayOf(likePrefix, likePrefix)
                )
                db.execSQL(
                    "DELETE FROM history_entries WHERE displayName LIKE ? OR id LIKE ?",
                    arrayOf(likePrefix, likePrefix)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        val afterHistory = countTask114LocalHistory(db, likePrefix)
        val afterRefs = countTask114LocalHistoryRefs(db, likePrefix)
        if (execute) {
            assertEquals(0, afterHistory)
            assertEquals(0, afterRefs)
        }
        println(
            "TASK114_ANDROID_LOCAL_CLEANUP prefix_hash=${hash(prefix)} execute=$execute " +
                "history_before=$beforeHistory refs_before=$beforeRefs " +
                "history_after=$afterHistory refs_after=$afterRefs"
        )
    }

    @Test
    fun test04AndroidPullMediumReadBack() = runBlocking {
        requireLiveAcceptanceEnabled()
        val fixture = fixture()
        val runtime = runtime(fixture)
        val summary = runtime.repository.pullCatalogBootstrapFromRemote(
            remote = runtime.catalogRemote,
            priceRemote = runtime.priceRemote,
            progressReporter = CatalogSyncProgressReporter { }
        ).getOrThrow()

        val mediumProducts = fixture.mediumBarcodes.mapNotNull { runtime.repository.findProductByBarcode(it) }
        assertEquals(50, mediumProducts.size)
        val canary = runtime.repository.findProductByBarcode(fixture.mediumCanaryBarcode)
            ?: throw AssertionError("TASK-103 Android MEDIUM canary missing after pull")
        assertEquals(fixture.mediumCanaryProduct, canary.productName)
        assertLocalPrices(runtime.repository, fixture.mediumCanaryBarcode, expectedMediumCanary())

        val details = runtime.repository.getProductDetailsById(canary.id)
        assertNotNull(details)
        assertPrice(details?.currentPurchasePrice, 41.01, "Android MEDIUM current purchase")
        assertPrice(details?.prevPurchase, 40.01, "Android MEDIUM previous purchase")
        assertPrice(details?.currentRetailPrice, 61.01, "Android MEDIUM current retail")
        assertPrice(details?.prevRetail, 60.01, "Android MEDIUM previous retail")

        println(
            "${fixture.logPrefix}_ANDROID_PULL_MEDIUM owner_hash=${hash(runtime.ownerUserId)} " +
                "medium_products=${mediumProducts.size} pulled_products=${summary.pulledProducts} " +
                "pulled_prices=${summary.pulledProductPrices} room_detail=true"
        )
    }

    private suspend fun runtime(fixture: Fixture): Runtime {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        app.catalogAutoSyncCoordinator.onAppBackground()
        app.authManager.restoreSession()

        val authState = withTimeoutOrNull(15_000) {
            app.authManager.state.first { it !is AuthState.Checking }
        } ?: app.authManager.state.value
        val signedIn = authState as? AuthState.SignedIn
            ?: throw AssertionError("TASK-103 Android Supabase session is not signed in: ${authState::class.java.simpleName}")

        withTimeoutOrNull(5_000) {
            app.catalogSyncStateTracker.state.first { !it.isBusy }
        }
        val client = app.supabaseClient ?: throw AssertionError("TASK-103 Supabase client missing")
        return Runtime(
            app = app,
            repository = app.repository,
            ownerUserId = signedIn.userId,
            catalogRemote = Task103ScopedCatalogRemoteDataSource(app.catalogRemoteDataSource, client, fixture),
            priceRemote = Task103ScopedProductPriceRemoteDataSource(app.productPriceRemoteDataSource, client, fixture),
            sessionRemote = app.sessionBackupRemoteDataSource
        )
    }

    private fun requireLiveAcceptanceEnabled() {
        val args = InstrumentationRegistry.getArguments()
        val task103Value = args
            .getString("task103LiveAcceptance")
            ?.lowercase()
        val task104Value = args
            .getString("task104Pass2LiveAcceptance")
            ?.lowercase()
        val task112Value = args
            .getString("task112LiveAcceptance")
            ?.lowercase()
        val task114Value = args
            .getString("task114LiveAcceptance")
            ?.lowercase()
        assumeTrue(
            "Live acceptance is gated. Pass -e task103LiveAcceptance true, -e task104Pass2LiveAcceptance true, -e task112LiveAcceptance true or -e task114LiveAcceptance true.",
            task103Value == "1" || task103Value == "true" ||
                task104Value == "1" || task104Value == "true" ||
                task112Value == "1" || task112Value == "true" ||
                task114Value == "1" || task114Value == "true"
        )
    }

    private fun isEnabled(value: String?): Boolean =
        value == "1" || value == "true"

    private fun fixture(): Fixture {
        val args = InstrumentationRegistry.getArguments()
        val prefix = args
            .getString("task104Pass2RunPrefix")
            ?: args
            .getString("task103RunPrefix")
            ?: args
            .getString("task112RunPrefix")
            ?: args
            .getString("task114RunPrefix")
            ?: throw AssertionError("task104Pass2RunPrefix, task103RunPrefix, task112RunPrefix or task114RunPrefix must be explicitly set for live acceptance.")
        assertTrue(
            prefix.startsWith("TASK103_REAL_R") ||
                prefix.startsWith("TASK104_PASS2_") ||
                prefix.startsWith("TASK112_") ||
                prefix.startsWith("TASK114_")
        )
        assertTrue(prefix.endsWith("_"))
        return Fixture(prefix)
    }

    private suspend fun assertLocalPrices(
        repository: DefaultInventoryRepository,
        barcode: String,
        expected: List<ExpectedPoint>
    ) {
        val rows = repository.getAllPriceHistoryRows().filter { it.barcode == barcode }
        assertEquals(expected.size, rows.size)
        for (point in expected) {
            val row = rows.firstOrNull { it.type == point.type && it.timestamp == point.effectiveAt }
                ?: throw AssertionError("Missing local price ${point.type} ${point.effectiveAt}")
            assertPrice(row.price, point.price, "local $barcode ${point.type} ${point.effectiveAt}")
        }
    }

    private fun assertRemotePrices(
        rows: List<InventoryProductPriceRow>,
        expected: List<ExpectedPoint>
    ) {
        assertEquals(expected.size, rows.size)
        for (point in expected) {
            val row = rows.firstOrNull {
                it.type.uppercase() == point.type && canonicalEffectiveAt(it.effectiveAt) == point.effectiveAt
            } ?: throw AssertionError("Missing remote price ${point.type} ${point.effectiveAt}")
            assertPrice(row.price, point.price, "remote ${point.type} ${point.effectiveAt}")
        }
    }

    private fun assertPrice(actual: Double?, expected: Double, label: String) {
        assertNotNull("$label missing", actual)
        assertTrue("$label expected $expected got $actual", abs(actual!! - expected) <= TOLERANCE)
    }

    private fun canonicalEffectiveAt(value: String): String =
        value.replace("T", " ").take(19)

    private fun activeProducts(catalog: InventoryCatalogFetchBundle, barcodes: Set<String>) =
        catalog.products.filter { it.deletedAt == null && it.barcode in barcodes }

    private fun singleActiveProduct(catalog: InventoryCatalogFetchBundle, barcode: String): InventoryProductRow? {
        val matches = activeProducts(catalog, setOf(barcode))
        return if (matches.size == 1) matches.single() else null
    }

    private fun productByBarcode(catalog: InventoryCatalogFetchBundle, barcode: String): InventoryProductRow? {
        val matches = catalog.products.filter { it.barcode == barcode }
        return if (matches.size == 1) matches.single() else null
    }

    private suspend fun matrixSessions(runtime: Runtime, fixture: Fixture): List<SharedSheetSessionRecord> =
        runtime.sessionRemote.fetchAllSessionsForOwner().getOrThrow()
            .filter { it.displayName?.startsWith("${fixture.prefix}MATRIX_") == true }

    private fun sessionByDisplayName(
        sessions: List<SharedSheetSessionRecord>,
        displayName: String
    ): SharedSheetSessionRecord? {
        val matches = sessions.filter { it.displayName == displayName }
        return if (matches.size == 1) matches.single() else null
    }

    private fun matrixHistoryEntry(title: String, fixture: Fixture): HistoryEntry =
        HistoryEntry(
            id = title,
            displayName = title,
            timestamp = "2026-05-21 18:00:00",
            data = listOf(listOf("barcode", "count"), listOf(title, "2")),
            editable = listOf(listOf("", ""), listOf("", "2")),
            complete = listOf(false, true),
            supplier = fixture.matrixSupplierAndroid,
            category = fixture.matrixCategoryAndroid,
            syncStatus = SyncStatus.NOT_ATTEMPTED,
            totalItems = 1,
            paymentTotal = 2.0,
            missingItems = 0,
            isManualEntry = true
        )

    private suspend fun seedLocalProductTombstoneTarget(
        runtime: Runtime,
        remote: InventoryProductRow
    ) {
        runtime.repository.findProductByBarcode(remote.barcode)?.let { return }
        runtime.app.database.productDao().insert(
            Product(
                barcode = remote.barcode,
                itemNumber = remote.itemNumber,
                productName = remote.productName,
                secondProductName = remote.secondProductName,
                purchasePrice = remote.purchasePrice,
                retailPrice = remote.retailPrice,
                stockQuantity = remote.stockQuantity
            )
        )
        val local = runtime.repository.findProductByBarcode(remote.barcode)
            ?: throw AssertionError("TASK-114 local product tombstone seed failed")
        runtime.app.database.productRemoteRefDao().insert(
            ProductRemoteRef(
                productId = local.id,
                remoteId = remote.id,
                localChangeRevision = 0,
                lastSyncedLocalRevision = 0,
                lastRemoteAppliedAt = System.currentTimeMillis(),
                remoteUpdatedAt = remote.updatedAt
            )
        )
    }

    private suspend fun seedLocalHistoryTombstoneTarget(
        runtime: Runtime,
        remote: SharedSheetSessionRecord
    ) {
        if (runtime.app.database.historyEntryRemoteRefDao().getByRemoteId(remote.remoteId) != null) return
        val uid = runtime.app.database.historyEntryDao().insert(
            HistoryEntry(
                id = remote.remoteId,
                displayName = remote.displayName.orEmpty(),
                timestamp = remote.timestamp,
                data = remote.data,
                editable = remote.sessionOverlay?.editable.orEmpty(),
                complete = remote.sessionOverlay?.complete.orEmpty(),
                supplier = remote.supplier,
                category = remote.category,
                syncStatus = SyncStatus.SYNCED_SUCCESSFULLY,
                totalItems = 1,
                isManualEntry = remote.isManualEntry
            )
        )
        runtime.app.database.historyEntryRemoteRefDao().insert(
            HistoryEntryRemoteRef(
                historyEntryUid = uid,
                remoteId = remote.remoteId,
                localChangeRevision = 0,
                lastSyncedLocalRevision = 0,
                lastRemoteAppliedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun localHistoryByRemote(
        runtime: Runtime,
        remote: SharedSheetSessionRecord
    ): HistoryEntry? {
        val ref = runtime.app.database.historyEntryRemoteRefDao().getByRemoteId(remote.remoteId) ?: return null
        return runtime.app.database.historyEntryDao().getByUid(ref.historyEntryUid)
    }

    private fun countTask114LocalHistory(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        likePrefix: String
    ): Int {
        val cursor = db.query(
            "SELECT COUNT(*) FROM history_entries WHERE displayName LIKE ? OR id LIKE ?",
            arrayOf(likePrefix, likePrefix)
        )
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun countTask114LocalHistoryRefs(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        likePrefix: String
    ): Int {
        val cursor = db.query(
            """
            SELECT COUNT(*)
            FROM history_entry_remote_refs
            WHERE historyEntryUid IN (
                SELECT uid FROM history_entries
                WHERE displayName LIKE ? OR id LIKE ?
            )
            """.trimIndent(),
            arrayOf(likePrefix, likePrefix)
        )
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun expectedIOS() = listOf(
        ExpectedPoint("PURCHASE", 11.10, "2026-05-12 13:00:00"),
        ExpectedPoint("PURCHASE", 12.35, "2026-05-12 13:15:00"),
        ExpectedPoint("RETAIL", 18.90, "2026-05-12 13:00:00"),
        ExpectedPoint("RETAIL", 20.50, "2026-05-12 13:15:00")
    )

    private fun expectedAndroid() = listOf(
        ExpectedPoint("PURCHASE", 21.10, "2026-05-12 14:00:00"),
        ExpectedPoint("PURCHASE", 22.35, "2026-05-12 14:15:00"),
        ExpectedPoint("RETAIL", 31.90, "2026-05-12 14:00:00"),
        ExpectedPoint("RETAIL", 33.50, "2026-05-12 14:15:00")
    )

    private fun expectedMediumCanary() = listOf(
        ExpectedPoint("PURCHASE", 40.01, "2026-05-12 15:00:00"),
        ExpectedPoint("PURCHASE", 41.01, "2026-05-12 15:15:01"),
        ExpectedPoint("RETAIL", 60.01, "2026-05-12 15:00:00"),
        ExpectedPoint("RETAIL", 61.01, "2026-05-12 15:15:01")
    )

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private data class Runtime(
        val app: MerchandiseControlApplication,
        val repository: DefaultInventoryRepository,
        val ownerUserId: String,
        val catalogRemote: CatalogRemoteDataSource,
        val priceRemote: ProductPriceRemoteDataSource,
        val sessionRemote: SessionBackupRemoteDataSource
    )

    private data class Fixture(val prefix: String) {
        val logPrefix: String = when {
            prefix.startsWith("TASK114_") -> "TASK114"
            prefix.startsWith("TASK112_") -> "TASK112"
            prefix.startsWith("TASK104_PASS2_") -> "TASK104_PASS2"
            else -> "TASK103"
        }
        val supplierIOS: String = "${prefix}SUP_IOS_01"
        val categoryIOS: String = "${prefix}CAT_IOS_01"
        val productIOS: String = "${prefix}CANARY_IOS_01"
        val barcodeIOS: String = "${prefix}IOS_0001"
        val supplierAndroid: String = "${prefix}SUP_ANDROID_01"
        val categoryAndroid: String = "${prefix}CAT_ANDROID_01"
        val productAndroid: String = "${prefix}CANARY_ANDROID_01"
        val barcodeAndroid: String = "${prefix}ANDROID_0001"
        val matrixSupplierIOS: String = "${prefix}MATRIX_SUP_IOS"
        val matrixCategoryIOS: String = "${prefix}MATRIX_CAT_IOS"
        val matrixBarcodeIOSCreate: String = "${prefix}MATRIX_IOS_CREATE"
        val matrixBarcodeIOSUpdate: String = "${prefix}MATRIX_IOS_UPDATE"
        val matrixBarcodeIOSTombstone: String = "${prefix}MATRIX_IOS_TOMBSTONE"
        val matrixProductIOSCreate: String = "${prefix}MATRIX_IOS_PRODUCT_CREATE"
        val matrixProductIOSUpdateFinal: String = "${prefix}MATRIX_IOS_PRODUCT_UPDATE_FINAL"
        val matrixHistoryIOSCreate: String = "${prefix}MATRIX_IOS_HISTORY_CREATE"
        val matrixHistoryIOSUpdateFinal: String = "${prefix}MATRIX_IOS_HISTORY_UPDATE_FINAL"
        val matrixHistoryIOSTombstone: String = "${prefix}MATRIX_IOS_HISTORY_TOMBSTONE"
        val matrixSupplierAndroid: String = "${prefix}MATRIX_SUP_ANDROID"
        val matrixCategoryAndroid: String = "${prefix}MATRIX_CAT_ANDROID"
        val matrixBarcodeAndroidCreate: String = "${prefix}MATRIX_ANDROID_CREATE"
        val matrixBarcodeAndroidUpdate: String = "${prefix}MATRIX_ANDROID_UPDATE"
        val matrixBarcodeAndroidTombstone: String = "${prefix}MATRIX_ANDROID_TOMBSTONE"
        val matrixProductAndroidCreate: String = "${prefix}MATRIX_ANDROID_PRODUCT_CREATE"
        val matrixProductAndroidUpdateFinal: String = "${prefix}MATRIX_ANDROID_PRODUCT_UPDATE_FINAL"
        val matrixProductAndroidTombstone: String = "${prefix}MATRIX_ANDROID_PRODUCT_TOMBSTONE"
        val matrixHistoryAndroidCreate: String = "${prefix}MATRIX_ANDROID_HISTORY_CREATE"
        val matrixHistoryAndroidUpdateFinal: String = "${prefix}MATRIX_ANDROID_HISTORY_UPDATE_FINAL"
        val matrixHistoryAndroidTombstone: String = "${prefix}MATRIX_ANDROID_HISTORY_TOMBSTONE"

        val mediumSuppliers: List<String> = (1..5).map { "${prefix}SUP_MEDIUM_${it.padded3()}" }
        val mediumCategories: List<String> = (1..5).map { "${prefix}CAT_MEDIUM_${it.padded3()}" }
        val mediumBarcodes: List<String> = (1..50).map { mediumBarcode(it) }
        val mediumCanaryBarcode: String = mediumBarcode(1)
        val mediumCanaryProduct: String = "${prefix}MEDIUM_PRODUCT_001"
        val allSupplierNames: List<String> = listOf(
            supplierIOS,
            supplierAndroid,
            matrixSupplierIOS,
            matrixSupplierAndroid
        ) + mediumSuppliers
        val allCategoryNames: List<String> = listOf(
            categoryIOS,
            categoryAndroid,
            matrixCategoryIOS,
            matrixCategoryAndroid
        ) + mediumCategories
        val allBarcodes: List<String> = listOf(
            barcodeIOS,
            barcodeAndroid,
            matrixBarcodeIOSCreate,
            matrixBarcodeIOSUpdate,
            matrixBarcodeIOSTombstone,
            matrixBarcodeAndroidCreate,
            matrixBarcodeAndroidUpdate,
            matrixBarcodeAndroidTombstone
        ) + mediumBarcodes

        private fun mediumBarcode(index: Int): String =
            "${prefix}MEDIUM_${index.padded3()}"
    }

    private data class ExpectedPoint(
        val type: String,
        val price: Double,
        val effectiveAt: String
    )

    private class Task103ScopedCatalogRemoteDataSource(
        private val delegate: CatalogRemoteDataSource,
        private val client: SupabaseClient,
        private val fixture: Fixture
    ) : CatalogRemoteDataSource {
        override val isConfigured: Boolean get() = delegate.isConfigured

        override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> =
            delegate.upsertSuppliers(rows)

        override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
            delegate.upsertCategories(rows)

        override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> =
            delegate.upsertProducts(rows)

        override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> = runCatching {
            val products = fetchProductsByBarcodes(fixture.allBarcodes)
            val supplierIds = products.mapNotNull { it.supplierId }.toSet()
            val categoryIds = products.mapNotNull { it.categoryId }.toSet()
            val parents = delegate.fetchCatalogByIds(
                supplierIds = supplierIds,
                categoryIds = categoryIds,
                productIds = emptySet()
            ).getOrThrow()
            InventoryCatalogFetchBundle(
                suppliers = (
                    parents.suppliers +
                        fetchSuppliersByNames(fixture.allSupplierNames)
                    ).distinctBy { it.id },
                categories = (
                    parents.categories +
                        fetchCategoriesByNames(fixture.allCategoryNames)
                    ).distinctBy { it.id },
                products = products,
                isCompleteSnapshot = false
            )
        }

        override suspend fun fetchCatalogByIds(
            supplierIds: Set<String>,
            categoryIds: Set<String>,
            productIds: Set<String>
        ): Result<InventoryCatalogFetchBundle> =
            delegate.fetchCatalogByIds(supplierIds, categoryIds, productIds)

        override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
            delegate.markSupplierTombstoned(patch)

        override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
            delegate.markCategoryTombstoned(patch)

        override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
            delegate.markProductTombstoned(patch)

        private suspend fun fetchProductsByBarcodes(barcodes: List<String>): List<InventoryProductRow> =
            client.postgrest["inventory_products"].select {
                filter {
                    isIn("barcode", barcodes)
                }
            }.decodeList()

        private suspend fun fetchSuppliersByNames(names: List<String>): List<InventorySupplierRow> =
            client.postgrest["inventory_suppliers"].select {
                filter {
                    isIn("name", names)
                }
            }.decodeList()

        private suspend fun fetchCategoriesByNames(names: List<String>): List<InventoryCategoryRow> =
            client.postgrest["inventory_categories"].select {
                filter {
                    isIn("name", names)
                }
            }.decodeList()
    }

    private class Task103ScopedProductPriceRemoteDataSource(
        private val delegate: ProductPriceRemoteDataSource,
        private val client: SupabaseClient,
        private val fixture: Fixture
    ) : ProductPriceRemoteDataSource {
        override val isConfigured: Boolean get() = delegate.isConfigured

        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> =
            delegate.upsertProductPrices(rows)

        override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = runCatching {
            val products = client.postgrest["inventory_products"].select {
                filter {
                    isIn("barcode", fixture.allBarcodes)
                }
            }.decodeList<InventoryProductRow>()
            val productIds = products.map { it.id }.toSet()
            if (productIds.isEmpty()) return@runCatching emptyList()
            client.postgrest["inventory_product_prices"].select {
                filter {
                    isIn("product_id", productIds.toList())
                }
            }.decodeList()
        }

        override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
            delegate.fetchProductPricesByIds(remoteIds)
    }

    private companion object {
        const val TOLERANCE = 0.005
    }
}

private fun Int.padded3(): String = toString().padStart(3, '0')

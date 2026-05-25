package com.example.merchandisecontrolsplitview

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.AuthState
import com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinator
import com.example.merchandisecontrolsplitview.data.CatalogRemoteDataSource
import com.example.merchandisecontrolsplitview.data.CatalogSyncFlightOwner
import com.example.merchandisecontrolsplitview.data.CatalogSyncSummary
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
import com.example.merchandisecontrolsplitview.data.SyncEventDomains
import com.example.merchandisecontrolsplitview.data.SyncEventRemoteRow
import com.example.merchandisecontrolsplitview.data.SyncStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.delay
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
        val matrixStartedMs = System.currentTimeMillis()
        val fixture = fixture()
        val runtime = runtime(fixture, foregroundAutoSync = true)
        val localCatalogSaveStartedMs = System.currentTimeMillis()
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
        val localCatalogSaveFinishedMs = System.currentTimeMillis()
        val catalogPushStartedMs = System.currentTimeMillis()
        val createSummary = waitForCatalogAutoPush(runtime, "android matrix create") {
            val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
            activeProducts(
                catalog,
                setOf(
                    fixture.matrixBarcodeAndroidCreate,
                    fixture.matrixBarcodeAndroidUpdate,
                    fixture.matrixBarcodeAndroidTombstone
                )
            ).size == 3
        }

        val updateLocal = runtime.repository.findProductByBarcode(fixture.matrixBarcodeAndroidUpdate)
            ?: throw AssertionError("TASK-114 Android update product missing locally")
        runtime.repository.updateProduct(
            updateLocal.copy(
                productName = fixture.matrixProductAndroidUpdateFinal,
                purchasePrice = 47.15
            )
        )
        val updateSummary = waitForCatalogAutoPush(runtime, "android matrix update") {
            val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
            singleActiveProduct(catalog, fixture.matrixBarcodeAndroidUpdate)?.productName ==
                fixture.matrixProductAndroidUpdateFinal
        }

        val tombstoneLocal = runtime.repository.findProductByBarcode(fixture.matrixBarcodeAndroidTombstone)
            ?: throw AssertionError("TASK-114 Android tombstone product missing locally")
        runtime.repository.deleteProduct(tombstoneLocal)
        val tombstoneSummary = waitForCatalogAutoPush(runtime, "android matrix tombstone") {
            val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
            productByBarcode(catalog, fixture.matrixBarcodeAndroidTombstone)?.deletedAt != null
        }
        val catalogPushFinishedMs = System.currentTimeMillis()

        val localHistorySaveStartedMs = System.currentTimeMillis()
        val createHistoryUid = runtime.repository.insertHistoryEntry(matrixHistoryEntry(fixture.matrixHistoryAndroidCreate, fixture))
        val updateHistoryUid = runtime.repository.insertHistoryEntry(matrixHistoryEntry("${fixture.prefix}MATRIX_ANDROID_HISTORY_UPDATE_INITIAL", fixture))
        val tombstoneHistoryUid = runtime.repository.insertHistoryEntry(matrixHistoryEntry(fixture.matrixHistoryAndroidTombstone, fixture))
        val localHistorySaveFinishedMs = System.currentTimeMillis()
        val historyPushStartedMs = System.currentTimeMillis()
        var historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "android history create",
            watermarkBefore = maxOf(
                createSummary.syncEventsWatermarkAfter,
                updateSummary.syncEventsWatermarkAfter,
                tombstoneSummary.syncEventsWatermarkAfter
            )
        ) {
            val sessions = matrixSessions(runtime, fixture)
            sessionByDisplayName(sessions, fixture.matrixHistoryAndroidCreate) != null &&
                sessionByDisplayName(sessions, "${fixture.prefix}MATRIX_ANDROID_HISTORY_UPDATE_INITIAL") != null &&
                sessionByDisplayName(sessions, fixture.matrixHistoryAndroidTombstone) != null
        }

        val updateHistory = runtime.app.database.historyEntryDao().getByUid(updateHistoryUid)
            ?: throw AssertionError("TASK-114 Android update history missing locally")
        runtime.repository.updateHistoryEntry(updateHistory.copy(displayName = fixture.matrixHistoryAndroidUpdateFinal))
        historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "android history update",
            watermarkBefore = historyWatermark
        ) {
            val sessions = matrixSessions(runtime, fixture)
            sessionByDisplayName(sessions, fixture.matrixHistoryAndroidUpdateFinal) != null
        }

        val tombstoneHistory = runtime.app.database.historyEntryDao().getByUid(tombstoneHistoryUid)
            ?: throw AssertionError("TASK-114 Android tombstone history missing locally")
        runtime.repository.deleteHistoryEntry(tombstoneHistory)
        waitForHistoryAutoPush(
            runtime = runtime,
            label = "android history tombstone",
            watermarkBefore = historyWatermark
        ) {
            val sessions = matrixSessions(runtime, fixture)
            sessionByDisplayName(sessions, fixture.matrixHistoryAndroidTombstone)?.deletedAt != null
        }
        val historyPushFinishedMs = System.currentTimeMillis()

        val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
        assertEquals(fixture.matrixProductAndroidCreate, singleActiveProduct(catalog, fixture.matrixBarcodeAndroidCreate)?.productName)
        assertEquals(fixture.matrixProductAndroidUpdateFinal, singleActiveProduct(catalog, fixture.matrixBarcodeAndroidUpdate)?.productName)
        assertNotNull(productByBarcode(catalog, fixture.matrixBarcodeAndroidTombstone)?.deletedAt)
        assertCatalogSyncEventTargets(
            runtime = runtime,
            watermarkBefore = minOf(
                createSummary.syncEventsWatermarkBefore,
                updateSummary.syncEventsWatermarkBefore,
                tombstoneSummary.syncEventsWatermarkBefore
            ),
            catalog = catalog,
            fixture = fixture
        )
        assertPriceSyncEventTargets(
            runtime = runtime,
            watermarkBefore = minOf(
                createSummary.syncEventsWatermarkBefore,
                updateSummary.syncEventsWatermarkBefore,
                tombstoneSummary.syncEventsWatermarkBefore
            )
        )

        val sessions = matrixSessions(runtime, fixture)
        assertNotNull(sessionByDisplayName(sessions, fixture.matrixHistoryAndroidCreate))
        assertNotNull(sessionByDisplayName(sessions, fixture.matrixHistoryAndroidUpdateFinal))
        assertNotNull(sessionByDisplayName(sessions, fixture.matrixHistoryAndroidTombstone)?.deletedAt)

        println(
            "${fixture.logPrefix}_ANDROID_WRITE_MATRIX owner_hash=${hash(runtime.ownerUserId)} " +
                "product_create=pass product_update=pass product_tombstone=pass " +
                "product_price_create=pass product_price_correction=pass product_price_tombstone=not_supported_append_only " +
                "history_create=pass history_update=pass history_tombstone=pass"
        )
        val timingLine =
            "TASK114_ANDROID_WRITE_TIMINGS " +
                "localCatalogSaveMs=${localCatalogSaveFinishedMs - localCatalogSaveStartedMs} " +
                "catalogPushAndEventsMs=${catalogPushFinishedMs - catalogPushStartedMs} " +
                "localHistorySaveMs=${localHistorySaveFinishedMs - localHistorySaveStartedMs} " +
                "historyPushAndEventsMs=${historyPushFinishedMs - historyPushStartedMs} " +
                "totalMatrixMs=${System.currentTimeMillis() - matrixStartedMs} " +
                "syncType=EVENT_INCREMENTAL fullPull=false"
        println(timingLine)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("TASK114_ANDROID_WRITE_TIMINGS", timingLine)
            }
        )
    }

    @Test
    fun test123AndroidSingleCatalogCreatePropagation() = runBlocking {
        requireLiveAcceptanceEnabled()
        val startedMs = System.currentTimeMillis()
        val fixture = fixture()
        val runtime = runtime(fixture, foregroundAutoSync = true)
        val localSaveStartedMs = System.currentTimeMillis()
        val supplier = runtime.repository.addSupplier("${fixture.prefix}SINGLE_SUP_ANDROID")
            ?: runtime.repository.findSupplierByName("${fixture.prefix}SINGLE_SUP_ANDROID")
            ?: throw AssertionError("TASK-123 Android single supplier unavailable")
        val category = runtime.repository.addCategory("${fixture.prefix}SINGLE_CAT_ANDROID")
            ?: runtime.repository.findCategoryByName("${fixture.prefix}SINGLE_CAT_ANDROID")
            ?: throw AssertionError("TASK-123 Android single category unavailable")
        runtime.repository.findProductByBarcode("${fixture.prefix}SINGLE_ANDROID_CREATE")
            ?.let { runtime.repository.deleteProduct(it) }
        runtime.repository.addProduct(
            Product(
                barcode = "${fixture.prefix}SINGLE_ANDROID_CREATE",
                itemNumber = "${fixture.prefix}SINGLE_ANDROID_CREATE_ITEM",
                productName = "${fixture.prefix}SINGLE_ANDROID_PRODUCT_CREATE",
                supplierId = supplier.id,
                categoryId = category.id,
                purchasePrice = 44.10,
                retailPrice = 55.20,
                stockQuantity = 6.0
            )
        )
        val localSaveMs = System.currentTimeMillis() - localSaveStartedMs
        val pushStartedMs = System.currentTimeMillis()
        val summary = waitForCatalogAutoPush(runtime, "task123 android single catalog create") {
            val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
            singleActiveProduct(catalog, "${fixture.prefix}SINGLE_ANDROID_CREATE")?.productName ==
                "${fixture.prefix}SINGLE_ANDROID_PRODUCT_CREATE"
        }
        val remotePushMs = System.currentTimeMillis() - pushStartedMs

        val timingLine =
            "TASK123_ANDROID_SINGLE_PROPAGATION " +
                "kind=catalog_product_create owner_hash=${hash(runtime.ownerUserId)} " +
                "localSaveMs=$localSaveMs localOutboxEnqueueMs=$localSaveMs " +
                "sourceAutoPushStartDelayMs=${CatalogAutoSyncCoordinator.DEBOUNCE_MS} " +
                "remotePushMs=$remotePushMs syncEventAvailableMs=$remotePushMs " +
                "watermarkAfter=${summary.syncEventsWatermarkAfter} " +
                "totalSourceMs=${System.currentTimeMillis() - startedMs} " +
                "syncType=EVENT_INCREMENTAL fullPull=false"
        println(timingLine)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("TASK123_ANDROID_SINGLE_PROPAGATION", timingLine)
            }
        )
    }

    @Test
    fun test114AndroidOfflineReconnectProductHistoryMatrix() = runBlocking {
        requireLiveAcceptanceEnabled()
        val matrixStartedMs = System.currentTimeMillis()
        val fixture = fixture()
        val runtime = runtime(fixture, foregroundAutoSync = true)
        val supplier = runtime.repository.addSupplier("${fixture.prefix}OFFLINE_ANDROID_SUPPLIER")
            ?: runtime.repository.findSupplierByName("${fixture.prefix}OFFLINE_ANDROID_SUPPLIER")
            ?: throw AssertionError("TASK-114 Android offline supplier unavailable")
        val category = runtime.repository.addCategory("${fixture.prefix}OFFLINE_ANDROID_CATEGORY")
            ?: runtime.repository.findCategoryByName("${fixture.prefix}OFFLINE_ANDROID_CATEGORY")
            ?: throw AssertionError("TASK-114 Android offline category unavailable")

        val seedUpdate = Product(
            barcode = "${fixture.prefix}OFFLINE_ANDROID_UPDATE",
            itemNumber = "${fixture.prefix}OFFLINE_ANDROID_UPDATE_ITEM",
            productName = "${fixture.prefix}OFFLINE_ANDROID_UPDATE_INITIAL",
            supplierId = supplier.id,
            categoryId = category.id,
            purchasePrice = 61.10,
            retailPrice = 71.20,
            stockQuantity = 7.0
        )
        val seedTombstone = Product(
            barcode = "${fixture.prefix}OFFLINE_ANDROID_TOMBSTONE",
            itemNumber = "${fixture.prefix}OFFLINE_ANDROID_TOMBSTONE_ITEM",
            productName = "${fixture.prefix}OFFLINE_ANDROID_TOMBSTONE_PRODUCT",
            supplierId = supplier.id,
            categoryId = category.id,
            purchasePrice = 62.10,
            retailPrice = 72.20,
            stockQuantity = 8.0
        )
        for (product in listOf(seedUpdate, seedTombstone)) {
            runtime.repository.findProductByBarcode(product.barcode)?.let { runtime.repository.deleteProduct(it) }
            runtime.repository.addProduct(product)
        }
        val seedSummary = waitForCatalogAutoPush(runtime, "android offline seed") {
            val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
            activeProducts(
                catalog,
                setOf("${fixture.prefix}OFFLINE_ANDROID_UPDATE", "${fixture.prefix}OFFLINE_ANDROID_TOMBSTONE")
            ).size == 2
        }

        val seededHistoryUpdateUid = runtime.repository.insertHistoryEntry(
            matrixHistoryEntry("${fixture.prefix}OFFLINE_ANDROID_HISTORY_UPDATE_INITIAL", fixture)
        )
        val seededHistoryTombstoneUid = runtime.repository.insertHistoryEntry(
            matrixHistoryEntry("${fixture.prefix}OFFLINE_ANDROID_HISTORY_TOMBSTONE", fixture)
        )
        var historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "android offline history seed",
            watermarkBefore = seedSummary.syncEventsWatermarkAfter
        ) {
            val sessions = matrixSessions(runtime, fixture)
            sessionByDisplayName(sessions, "${fixture.prefix}OFFLINE_ANDROID_HISTORY_UPDATE_INITIAL") != null &&
                sessionByDisplayName(sessions, "${fixture.prefix}OFFLINE_ANDROID_HISTORY_TOMBSTONE") != null
        }

        runtime.app.catalogAutoSyncCoordinator.onAppBackground()
        runtime.app.historySessionPushCoordinator.onAppBackground()
        val localSaveStartedMs = System.currentTimeMillis()
        runtime.repository.addProduct(
            Product(
                barcode = "${fixture.prefix}OFFLINE_ANDROID_CREATE",
                itemNumber = "${fixture.prefix}OFFLINE_ANDROID_CREATE_ITEM",
                productName = "${fixture.prefix}OFFLINE_ANDROID_CREATE_PRODUCT",
                supplierId = supplier.id,
                categoryId = category.id,
                purchasePrice = 63.10,
                retailPrice = 73.20,
                stockQuantity = 9.0
            )
        )
        val updateLocal = runtime.repository.findProductByBarcode("${fixture.prefix}OFFLINE_ANDROID_UPDATE")
            ?: throw AssertionError("TASK-114 Android offline update product missing locally")
        runtime.repository.updateProduct(
            updateLocal.copy(
                productName = "${fixture.prefix}OFFLINE_ANDROID_UPDATE_FINAL",
                purchasePrice = 64.15
            )
        )
        val tombstoneLocal = runtime.repository.findProductByBarcode("${fixture.prefix}OFFLINE_ANDROID_TOMBSTONE")
            ?: throw AssertionError("TASK-114 Android offline tombstone product missing locally")
        runtime.repository.deleteProduct(tombstoneLocal)

        val historyCreateUid = runtime.repository.insertHistoryEntry(
            matrixHistoryEntry("${fixture.prefix}OFFLINE_ANDROID_HISTORY_CREATE", fixture)
        )
        val historyUpdate = runtime.app.database.historyEntryDao().getByUid(seededHistoryUpdateUid)
            ?: throw AssertionError("TASK-114 Android offline history update missing locally")
        runtime.repository.updateHistoryEntry(historyUpdate.copy(displayName = "${fixture.prefix}OFFLINE_ANDROID_HISTORY_UPDATE_FINAL"))
        val historyTombstone = runtime.app.database.historyEntryDao().getByUid(seededHistoryTombstoneUid)
            ?: throw AssertionError("TASK-114 Android offline history tombstone missing locally")
        runtime.repository.deleteHistoryEntry(historyTombstone)
        val localSaveMs = System.currentTimeMillis() - localSaveStartedMs

        val pendingCatalogBefore =
            runtime.app.database.supplierDao().getCatalogPushCandidates().size +
                runtime.app.database.categoryDao().getCatalogPushCandidates().size +
                runtime.app.database.productDao().getCatalogPushCandidates().size
        val pendingPricesBefore = runtime.app.database.productPriceDao().getAllForCloudPush().size
        val pendingHistoryBefore = runtime.app.database.historyEntryDao().getUserVisibleSessionPushCandidateUids().size
        assertTrue("Expected offline catalog pending", pendingCatalogBefore >= 1)
        assertTrue("Expected offline price pending", pendingPricesBefore >= 1)
        assertTrue("Expected offline history pending", pendingHistoryBefore >= 1)

        val reconnectStartedMs = System.currentTimeMillis()
        runtime.app.catalogAutoSyncCoordinator.onNetworkAvailable()
        runtime.app.catalogAutoSyncCoordinator.onAppForeground()
        runtime.app.historySessionPushCoordinator.onNetworkAvailable()
        runtime.app.historySessionPushCoordinator.onAppForeground()
        val reconnectDetectedMs = System.currentTimeMillis() - reconnectStartedMs
        val reconnectSummary = waitForCatalogAutoPush(runtime, "android offline reconnect") {
            val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
            singleActiveProduct(catalog, "${fixture.prefix}OFFLINE_ANDROID_CREATE")?.productName ==
                "${fixture.prefix}OFFLINE_ANDROID_CREATE_PRODUCT" &&
                singleActiveProduct(catalog, "${fixture.prefix}OFFLINE_ANDROID_UPDATE")?.productName ==
                "${fixture.prefix}OFFLINE_ANDROID_UPDATE_FINAL" &&
                productByBarcode(catalog, "${fixture.prefix}OFFLINE_ANDROID_TOMBSTONE")?.deletedAt != null
        }
        val historyWatermarkBeforeReconnect = historyWatermark
        historyWatermark = waitForHistoryAutoPush(
            runtime = runtime,
            label = "android offline reconnect history",
            watermarkBefore = historyWatermarkBeforeReconnect
        ) {
            val sessions = matrixSessions(runtime, fixture)
            sessionByDisplayName(sessions, "${fixture.prefix}OFFLINE_ANDROID_HISTORY_CREATE") != null &&
                sessionByDisplayName(sessions, "${fixture.prefix}OFFLINE_ANDROID_HISTORY_UPDATE_FINAL") != null &&
                sessionByDisplayName(sessions, "${fixture.prefix}OFFLINE_ANDROID_HISTORY_TOMBSTONE")?.deletedAt != null
        }
        val remotePushMs = System.currentTimeMillis() - reconnectStartedMs

        assertEquals(0, runtime.app.database.supplierDao().getCatalogPushCandidates().size)
        assertEquals(0, runtime.app.database.categoryDao().getCatalogPushCandidates().size)
        assertEquals(0, runtime.app.database.productDao().getCatalogPushCandidates().size)
        assertEquals(0, runtime.app.database.productPriceDao().getAllForCloudPush().size)

        val catalog = runtime.catalogRemote.fetchCatalog().getOrThrow()
        assertEquals(
            "${fixture.prefix}OFFLINE_ANDROID_CREATE_PRODUCT",
            singleActiveProduct(catalog, "${fixture.prefix}OFFLINE_ANDROID_CREATE")?.productName
        )
        assertEquals(
            "${fixture.prefix}OFFLINE_ANDROID_UPDATE_FINAL",
            singleActiveProduct(catalog, "${fixture.prefix}OFFLINE_ANDROID_UPDATE")?.productName
        )
        assertNotNull(productByBarcode(catalog, "${fixture.prefix}OFFLINE_ANDROID_TOMBSTONE")?.deletedAt)
        assertCatalogSyncEventTargets(
            runtime = runtime,
            watermarkBefore = reconnectSummary.syncEventsWatermarkBefore,
            catalog = catalog,
            fixture = fixture,
            productBarcodes = listOf(
                "${fixture.prefix}OFFLINE_ANDROID_CREATE",
                "${fixture.prefix}OFFLINE_ANDROID_UPDATE",
                "${fixture.prefix}OFFLINE_ANDROID_TOMBSTONE"
            )
        )
        assertPriceSyncEventTargets(
            runtime = runtime,
            watermarkBefore = reconnectSummary.syncEventsWatermarkBefore
        )
        assertTrue("Expected history create uid", historyCreateUid > 0L)
        assertTrue("Expected history reconnect watermark", historyWatermark > historyWatermarkBeforeReconnect)

        println(
            "${fixture.logPrefix}_ANDROID_OFFLINE_RECONNECT owner_hash=${hash(runtime.ownerUserId)} " +
                "localSaveMs=$localSaveMs pendingCatalog=$pendingCatalogBefore pendingPrices=$pendingPricesBefore pendingHistory=$pendingHistoryBefore " +
                "reconnectDetectedMs=$reconnectDetectedMs remotePushMs=$remotePushMs product_create=pass product_update=pass product_tombstone=pass " +
                "product_price_create=pass product_price_correction=pass history_create=pass history_update=pass history_tombstone=pass " +
                "coalescing=last_write_wins conflictPolicy=fail_closed syncType=EVENT_INCREMENTAL fullPull=false"
        )
        val timingLine =
            "TASK114_ANDROID_OFFLINE_TIMINGS " +
                "localSaveMs=$localSaveMs pendingCatalog=$pendingCatalogBefore pendingPrices=$pendingPricesBefore pendingHistory=$pendingHistoryBefore " +
                "reconnectDetectedMs=$reconnectDetectedMs remotePushMs=$remotePushMs totalOfflineMs=${System.currentTimeMillis() - matrixStartedMs} " +
                "syncType=EVENT_INCREMENTAL fullPull=false"
        println(timingLine)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("TASK114_ANDROID_OFFLINE_TIMINGS", timingLine)
            }
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
        val allowedCleanupPrefix = prefix.startsWith("TASK114_") || prefix.startsWith("TASK123_")
        require(allowedCleanupPrefix && prefix.endsWith("_") && !prefix.contains("%")) {
            "TASK-114/TASK-123 cleanup prefix must be explicit, task-scoped and suffix '_'"
        }
        val execute = isEnabled(args.getString("task114CleanupExecute")?.lowercase())
        val likePrefix = "$prefix%"
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        val db = app.database.openHelper.writableDatabase
        val beforeHistory = countTask114LocalHistory(db, likePrefix)
        val beforeRefs = countTask114LocalHistoryRefs(db, likePrefix)
        val beforeProducts = countTask114LocalProducts(db, likePrefix)
        val beforeProductPrices = countTask114LocalProductPrices(db, likePrefix)
        val beforeCatalogRefs = countTask114LocalCatalogRefs(db, likePrefix)
        val beforePriceRefs = countTask114LocalPriceRefs(db, likePrefix)
        val beforeLookups = countTask114LocalLookups(db, likePrefix)
        val beforeOutbox = countTask114LocalSyncEventOutbox(db, likePrefix)

        if (execute && (
                beforeHistory > 0 ||
                    beforeProducts > 0 ||
                    beforeProductPrices > 0 ||
                    beforeCatalogRefs > 0 ||
                    beforePriceRefs > 0 ||
                    beforeLookups > 0 ||
                    beforeOutbox > 0
                )
        ) {
            db.beginTransaction()
            try {
                deleteTask114LocalSyncEventOutbox(db, likePrefix)
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
                db.execSQL(
                    """
                    DELETE FROM product_price_remote_refs
                    WHERE productPriceId IN (
                        SELECT pp.id
                        FROM product_prices pp
                        JOIN products p ON p.id = pp.productId
                        WHERE p.barcode LIKE ? OR p.productName LIKE ?
                    )
                    """.trimIndent(),
                    arrayOf(likePrefix, likePrefix)
                )
                db.execSQL(
                    """
                    DELETE FROM product_prices
                    WHERE productId IN (
                        SELECT id FROM products
                        WHERE barcode LIKE ? OR productName LIKE ?
                    )
                    """.trimIndent(),
                    arrayOf(likePrefix, likePrefix)
                )
                db.execSQL(
                    """
                    DELETE FROM product_remote_refs
                    WHERE productId IN (
                        SELECT id FROM products
                        WHERE barcode LIKE ? OR productName LIKE ?
                    )
                    """.trimIndent(),
                    arrayOf(likePrefix, likePrefix)
                )
                db.execSQL(
                    "DELETE FROM products WHERE barcode LIKE ? OR productName LIKE ?",
                    arrayOf(likePrefix, likePrefix)
                )
                db.execSQL(
                    """
                    DELETE FROM supplier_remote_refs
                    WHERE supplierId IN (SELECT id FROM suppliers WHERE name LIKE ?)
                    """.trimIndent(),
                    arrayOf(likePrefix)
                )
                db.execSQL(
                    """
                    DELETE FROM category_remote_refs
                    WHERE categoryId IN (SELECT id FROM categories WHERE name LIKE ?)
                    """.trimIndent(),
                    arrayOf(likePrefix)
                )
                db.execSQL("DELETE FROM suppliers WHERE name LIKE ?", arrayOf(likePrefix))
                db.execSQL("DELETE FROM categories WHERE name LIKE ?", arrayOf(likePrefix))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        val afterHistory = countTask114LocalHistory(db, likePrefix)
        val afterRefs = countTask114LocalHistoryRefs(db, likePrefix)
        val afterProducts = countTask114LocalProducts(db, likePrefix)
        val afterProductPrices = countTask114LocalProductPrices(db, likePrefix)
        val afterCatalogRefs = countTask114LocalCatalogRefs(db, likePrefix)
        val afterPriceRefs = countTask114LocalPriceRefs(db, likePrefix)
        val afterLookups = countTask114LocalLookups(db, likePrefix)
        val afterOutbox = countTask114LocalSyncEventOutbox(db, likePrefix)
        if (execute) {
            assertEquals(0, afterHistory)
            assertEquals(0, afterRefs)
            assertEquals(0, afterProducts)
            assertEquals(0, afterProductPrices)
            assertEquals(0, afterCatalogRefs)
            assertEquals(0, afterPriceRefs)
            assertEquals(0, afterLookups)
            assertEquals(0, afterOutbox)
        }
        val cleanupLine =
            "TASK114_ANDROID_LOCAL_CLEANUP prefix_hash=${hash(prefix)} execute=$execute " +
                "history_before=$beforeHistory refs_before=$beforeRefs " +
                "products_before=$beforeProducts product_prices_before=$beforeProductPrices " +
                "catalog_refs_before=$beforeCatalogRefs price_refs_before=$beforePriceRefs " +
                "lookups_before=$beforeLookups outbox_before=$beforeOutbox " +
                "history_after=$afterHistory refs_after=$afterRefs " +
                "products_after=$afterProducts product_prices_after=$afterProductPrices " +
                "catalog_refs_after=$afterCatalogRefs price_refs_after=$afterPriceRefs " +
                "lookups_after=$afterLookups outbox_after=$afterOutbox"
        println(cleanupLine)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("TASK114_ANDROID_LOCAL_CLEANUP", cleanupLine)
            }
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

    private suspend fun runtime(fixture: Fixture, foregroundAutoSync: Boolean = false): Runtime {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MerchandiseControlApplication
        if (foregroundAutoSync) {
            app.catalogAutoSyncCoordinator.onAppForeground()
            app.historySessionPushCoordinator.onAppForeground()
        } else {
            app.catalogAutoSyncCoordinator.onAppBackground()
        }
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

    private suspend fun waitForCatalogAutoPush(
        runtime: Runtime,
        label: String,
        remoteCondition: suspend () -> Boolean
    ): CatalogSyncSummary {
        val previous = runtime.app.catalogSyncStateTracker.lastOutcome.value
        val outcome = withTimeoutOrNull(35_000) {
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
        } ?: throw AssertionError("TASK-114 $label auto push did not complete through EVENT_INCREMENTAL within 35s")
        if (outcome.summary.syncEventOutboxPending != 0) {
            throw AssertionError("TASK-114 $label left sync_event outbox pending=${outcome.summary.syncEventOutboxPending}")
        }
        val remoteOk = withTimeoutOrNull(15_000) {
            while (!remoteCondition()) {
                delay(500)
            }
            true
        } ?: false
        if (!remoteOk) {
            throw AssertionError("TASK-114 $label remote read-back did not match after auto push")
        }
        println(
            "TASK114_ANDROID_AUTOPUSH label=${label.replace(' ', '_')} " +
                "syncType=EVENT_INCREMENTAL fullPull=false " +
                "pushedCatalog=${outcome.summary.pushedSuppliers + outcome.summary.pushedCategories + outcome.summary.pushedProducts} " +
                "watermarkBefore=${outcome.summary.syncEventsWatermarkBefore} " +
                "watermarkAfter=${outcome.summary.syncEventsWatermarkAfter}"
        )
        return outcome.summary
    }

    private suspend fun waitForHistoryAutoPush(
        runtime: Runtime,
        label: String,
        watermarkBefore: Long,
        remoteCondition: suspend () -> Boolean
    ): Long {
        var lastHistoryEvents: List<SyncEventRemoteRow> = emptyList()
        var completed = false
        withTimeoutOrNull(35_000) {
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
            throw AssertionError("TASK-114 $label auto push did not create/apply history sync_event within 35s")
        }
        if (lastHistoryEvents.any { it.changedCount > 0 && it.entityIds?.sessionIds.orEmpty().isEmpty() }) {
            throw AssertionError("TASK-114 $label history sync_event used changed_count without targeted session_ids")
        }
        val targetedSessions = lastHistoryEvents.flatMap { it.entityIds?.sessionIds.orEmpty() }.toSet()
        println(
            "TASK114_ANDROID_HISTORY_AUTOPUSH label=${label.replace(' ', '_')} " +
                "syncType=EVENT_INCREMENTAL fullPull=false " +
                "historyEvents=${lastHistoryEvents.size} targetedSessions=${targetedSessions.size}"
        )
        return lastHistoryEvents.maxOf { it.id }
    }

    private suspend fun assertCatalogSyncEventTargets(
        runtime: Runtime,
        watermarkBefore: Long,
        catalog: InventoryCatalogFetchBundle,
        fixture: Fixture,
        productBarcodes: List<String> = listOf(
            fixture.matrixBarcodeAndroidCreate,
            fixture.matrixBarcodeAndroidUpdate,
            fixture.matrixBarcodeAndroidTombstone
        )
    ) {
        val rows = runtime.app.syncEventRemoteDataSource.fetchSyncEventsAfter(
            ownerUserId = runtime.ownerUserId,
            storeId = null,
            afterId = watermarkBefore,
            limit = 50
        ).getOrThrow()
        val catalogEvents = rows.filter { it.domain == SyncEventDomains.CATALOG && it.source == "android" }
        if (catalogEvents.isEmpty()) {
            throw AssertionError("TASK-114 Android auto push did not create catalog sync_events")
        }
        val eventProductIds = catalogEvents.flatMap { it.entityIds?.productIds.orEmpty() }.toSet()
        val expectedProductIds = productBarcodes.map { barcode ->
            productByBarcode(catalog, barcode)?.id
                ?: throw AssertionError("TASK-114 remote product missing for sync_event target barcode=$barcode")
        }.toSet()
        val missing = expectedProductIds - eventProductIds
        if (missing.isNotEmpty()) {
            throw AssertionError("TASK-114 Android catalog sync_events missing targeted product ids count=${missing.size}")
        }
        if (catalogEvents.any { it.changedCount > 0 && it.entityIds == null }) {
            throw AssertionError("TASK-114 Android catalog sync_event used changed_count without targeted entity_ids")
        }
        println(
            "TASK114_ANDROID_SYNC_EVENTS syncType=EVENT_INCREMENTAL " +
            "catalogEvents=${catalogEvents.size} targetedProducts=${eventProductIds.size} fullPull=false"
        )
    }

    private suspend fun assertPriceSyncEventTargets(
        runtime: Runtime,
        watermarkBefore: Long
    ) {
        val rows = runtime.app.syncEventRemoteDataSource.fetchSyncEventsAfter(
            ownerUserId = runtime.ownerUserId,
            storeId = null,
            afterId = watermarkBefore,
            limit = 100
        ).getOrThrow()
        val priceEvents = rows.filter { it.domain == SyncEventDomains.PRICES && it.source == "android" }
        if (priceEvents.isEmpty()) {
            throw AssertionError("TASK-114 Android auto push did not create price sync_events")
        }
        val eventPriceIds = priceEvents.flatMap { it.entityIds?.priceIds.orEmpty() }.toSet()
        if (eventPriceIds.isEmpty()) {
            throw AssertionError("TASK-114 Android price sync_events missing targeted price_ids")
        }
        if (priceEvents.any { it.changedCount > 0 && it.entityIds?.priceIds.orEmpty().isEmpty() }) {
            throw AssertionError("TASK-114 Android price sync_event used changed_count without targeted price_ids")
        }
        println(
            "TASK114_ANDROID_PRICE_SYNC_EVENTS syncType=EVENT_INCREMENTAL " +
                "priceEvents=${priceEvents.size} targetedPrices=${eventPriceIds.size} fullPull=false"
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
        val task115Value = args
            .getString("task115LiveAcceptance")
            ?.lowercase()
        assumeTrue(
            "Live acceptance is gated. Pass -e task103LiveAcceptance true, -e task104Pass2LiveAcceptance true, -e task112LiveAcceptance true, -e task114LiveAcceptance true or -e task115LiveAcceptance true.",
            task103Value == "1" || task103Value == "true" ||
                task104Value == "1" || task104Value == "true" ||
                task112Value == "1" || task112Value == "true" ||
                task114Value == "1" || task114Value == "true" ||
                task115Value == "1" || task115Value == "true"
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
            .getString("task115RunPrefix")
            ?: args
            .getString("task114RunPrefix")
            ?: throw AssertionError("task104Pass2RunPrefix, task103RunPrefix, task112RunPrefix, task114RunPrefix or task115RunPrefix must be explicitly set for live acceptance.")
        assertTrue(
            prefix.startsWith("TASK103_REAL_R") ||
                prefix.startsWith("TASK104_PASS2_") ||
                prefix.startsWith("TASK112_") ||
                prefix.startsWith("TASK114_") ||
                prefix.startsWith("TASK115_") ||
                prefix.startsWith("TASK123_")
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
            .filter { it.displayName?.startsWith(fixture.prefix) == true }

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
        return countTask114Rows(
            db,
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
    }

    private fun countTask114LocalProducts(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        likePrefix: String
    ): Int = countTask114Rows(
        db,
        "SELECT COUNT(*) FROM products WHERE barcode LIKE ? OR productName LIKE ?",
        arrayOf(likePrefix, likePrefix)
    )

    private fun countTask114LocalProductPrices(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        likePrefix: String
    ): Int = countTask114Rows(
        db,
        """
        SELECT COUNT(*)
        FROM product_prices pp
        JOIN products p ON p.id = pp.productId
        WHERE p.barcode LIKE ? OR p.productName LIKE ?
        """.trimIndent(),
        arrayOf(likePrefix, likePrefix)
    )

    private fun countTask114LocalCatalogRefs(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        likePrefix: String
    ): Int = countTask114Rows(
        db,
        """
        SELECT
            (SELECT COUNT(*)
             FROM product_remote_refs r
             JOIN products p ON p.id = r.productId
             WHERE p.barcode LIKE ? OR p.productName LIKE ?) +
            (SELECT COUNT(*)
             FROM supplier_remote_refs r
             JOIN suppliers s ON s.id = r.supplierId
             WHERE s.name LIKE ?) +
            (SELECT COUNT(*)
             FROM category_remote_refs r
             JOIN categories c ON c.id = r.categoryId
             WHERE c.name LIKE ?)
        """.trimIndent(),
        arrayOf(likePrefix, likePrefix, likePrefix, likePrefix)
    )

    private fun countTask114LocalPriceRefs(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        likePrefix: String
    ): Int = countTask114Rows(
        db,
        """
        SELECT COUNT(*)
        FROM product_price_remote_refs r
        JOIN product_prices pp ON pp.id = r.productPriceId
        JOIN products p ON p.id = pp.productId
        WHERE p.barcode LIKE ? OR p.productName LIKE ?
        """.trimIndent(),
        arrayOf(likePrefix, likePrefix)
    )

    private fun countTask114LocalLookups(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        likePrefix: String
    ): Int = countTask114Rows(
        db,
        """
        SELECT
            (SELECT COUNT(*) FROM suppliers WHERE name LIKE ?) +
            (SELECT COUNT(*) FROM categories WHERE name LIKE ?)
        """.trimIndent(),
        arrayOf(likePrefix, likePrefix)
    )

    private fun countTask114LocalSyncEventOutbox(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        likePrefix: String
    ): Int = countTask114Rows(
        db,
        task114LocalSyncEventOutboxPredicateSql(select = "SELECT COUNT(*)"),
        task114LocalSyncEventOutboxArgs(likePrefix)
    )

    private fun deleteTask114LocalSyncEventOutbox(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        likePrefix: String
    ) {
        db.execSQL(
            task114LocalSyncEventOutboxPredicateSql(select = "DELETE"),
            task114LocalSyncEventOutboxArgs(likePrefix)
        )
    }

    private fun task114LocalSyncEventOutboxPredicateSql(select: String): String =
        """
        $select FROM sync_event_outbox
        WHERE EXISTS (
            SELECT 1
            FROM product_remote_refs r
            JOIN products p ON p.id = r.productId
            WHERE (p.barcode LIKE ? OR p.productName LIKE ?)
              AND sync_event_outbox.entityIdsJson LIKE '%' || r.remoteId || '%'
        )
        OR EXISTS (
            SELECT 1
            FROM product_price_remote_refs r
            JOIN product_prices pp ON pp.id = r.productPriceId
            JOIN products p ON p.id = pp.productId
            WHERE (p.barcode LIKE ? OR p.productName LIKE ?)
              AND sync_event_outbox.entityIdsJson LIKE '%' || r.remoteId || '%'
        )
        OR EXISTS (
            SELECT 1
            FROM supplier_remote_refs r
            JOIN suppliers s ON s.id = r.supplierId
            WHERE s.name LIKE ?
              AND sync_event_outbox.entityIdsJson LIKE '%' || r.remoteId || '%'
        )
        OR EXISTS (
            SELECT 1
            FROM category_remote_refs r
            JOIN categories c ON c.id = r.categoryId
            WHERE c.name LIKE ?
              AND sync_event_outbox.entityIdsJson LIKE '%' || r.remoteId || '%'
        )
        """.trimIndent()

    private fun task114LocalSyncEventOutboxArgs(likePrefix: String): Array<Any> =
        arrayOf(likePrefix, likePrefix, likePrefix, likePrefix, likePrefix, likePrefix)

    private fun countTask114Rows(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String,
        bindArgs: Array<Any>
    ): Int {
        val cursor = db.query(sql, bindArgs)
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
            prefix.startsWith("TASK123_") -> "TASK123"
            prefix.startsWith("TASK115_") -> "TASK115"
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
        val singleSupplierIOS: String = "${prefix}SINGLE_SUP_IOS"
        val singleCategoryIOS: String = "${prefix}SINGLE_CAT_IOS"
        val singleBarcodeIOSCreate: String = "${prefix}SINGLE_IOS_CREATE"
        val singleSupplierAndroid: String = "${prefix}SINGLE_SUP_ANDROID"
        val singleCategoryAndroid: String = "${prefix}SINGLE_CAT_ANDROID"
        val singleBarcodeAndroidCreate: String = "${prefix}SINGLE_ANDROID_CREATE"
        val supplierOfflineAndroid: String = "${prefix}OFFLINE_ANDROID_SUPPLIER"
        val categoryOfflineAndroid: String = "${prefix}OFFLINE_ANDROID_CATEGORY"
        val barcodeOfflineAndroidCreate: String = "${prefix}OFFLINE_ANDROID_CREATE"
        val barcodeOfflineAndroidUpdate: String = "${prefix}OFFLINE_ANDROID_UPDATE"
        val barcodeOfflineAndroidTombstone: String = "${prefix}OFFLINE_ANDROID_TOMBSTONE"

        val mediumSuppliers: List<String> = (1..5).map { "${prefix}SUP_MEDIUM_${it.padded3()}" }
        val mediumCategories: List<String> = (1..5).map { "${prefix}CAT_MEDIUM_${it.padded3()}" }
        val mediumBarcodes: List<String> = (1..50).map { mediumBarcode(it) }
        val mediumCanaryBarcode: String = mediumBarcode(1)
        val mediumCanaryProduct: String = "${prefix}MEDIUM_PRODUCT_001"
        val allSupplierNames: List<String> = listOf(
            supplierIOS,
            supplierAndroid,
            matrixSupplierIOS,
            matrixSupplierAndroid,
            singleSupplierIOS,
            singleSupplierAndroid,
            supplierOfflineAndroid
        ) + mediumSuppliers
        val allCategoryNames: List<String> = listOf(
            categoryIOS,
            categoryAndroid,
            matrixCategoryIOS,
            matrixCategoryAndroid,
            singleCategoryIOS,
            singleCategoryAndroid,
            categoryOfflineAndroid
        ) + mediumCategories
        val allBarcodes: List<String> = listOf(
            barcodeIOS,
            barcodeAndroid,
            matrixBarcodeIOSCreate,
            matrixBarcodeIOSUpdate,
            matrixBarcodeIOSTombstone,
            matrixBarcodeAndroidCreate,
            matrixBarcodeAndroidUpdate,
            matrixBarcodeAndroidTombstone,
            singleBarcodeIOSCreate,
            singleBarcodeAndroidCreate,
            barcodeOfflineAndroidCreate,
            barcodeOfflineAndroidUpdate,
            barcodeOfflineAndroidTombstone
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

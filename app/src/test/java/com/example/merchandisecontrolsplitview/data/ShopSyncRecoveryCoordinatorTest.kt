package com.example.merchandisecontrolsplitview.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.robolectric.shadows.ShadowLog

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
        DefaultInventoryRepositoryTestHooks.afterOrdinaryShopSyncWrites = null
    }

    @After
    fun teardown() {
        ShopSyncRecoveryTestHooks.reset()
        DefaultInventoryRepositoryTestHooks.afterOrdinaryShopSyncWrites = null
        if (::db.isInitialized && db.isOpen) db.close()
        databaseNames.forEach(app::deleteDatabase)
        app.getDatabasePath(".").canonicalFile.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("sync_recovery_stage_") }
            .forEach(File::delete)
    }

    @Test
    fun `143 same scope verified snapshot remains navigable while recovery transport is held`() = runTest {
        seedVerifiedSameScopeRecovery()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        remote.afterPage = { domain ->
            if (domain == ShopSyncRowDomain.PRODUCTS) { entered.complete(Unit); release.await() }
        }
        val flight = async { coordinator().recover(ACCOUNT, selectedShop(), activeScope()) }
        try {
            entered.await()
            assertFalse(flight.isCompleted)
            val resolved = repository.resolveBusinessDataScope(activeScope())
            assertEquals("sync_recovery_required", resolved.errorCode)
            assertFalse(resolved.allowsCloudSync)
            assertNotNull(db.productDao().findByBarcode("target-barcode"))
            assertTrue("verified local snapshot must be usable before releasing transport",
                com.example.merchandisecontrolsplitview.ui.navigation.businessContentAvailable(
                    true, AuthState.SignedIn(ACCOUNT, "qa@example.test"), resolved))
        } finally { release.complete(Unit); flight.cancel(); flight.join() }
    }

    @Test
    fun `143 same scope save commits durably before recovery transport is released`() = runTest {
        seedVerifiedSameScopeRecovery()
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        val guarded = DefaultInventoryRepository(db = db, businessDataScopeRuntimeGuard = tracker)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        remote.afterPage = { domain ->
            if (domain == ShopSyncRowDomain.PRODUCTS) { entered.complete(Unit); release.await() }
        }
        val flight = async { coordinator().recover(ACCOUNT, selectedShop(), activeScope()) }
        try {
            entered.await()
            val original = requireNotNull(db.productDao().findByBarcode("target-barcode"))
            val save = runCatching { guarded.updateProduct(original.copy(productName = "Edited while downloading")) }
            assertTrue("local Save rejected while network is deliberately held: ${save.exceptionOrNull()}", save.isSuccess)
            assertFalse(flight.isCompleted)
            assertEquals("Edited while downloading", db.productDao().getById(original.id)?.productName)
            val ref = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
            assertTrue(ref.localChangeRevision > ref.lastSyncedLocalRevision)
            assertNotNull(db.syncRecoveryJournalDao().get())
        } finally { release.complete(Unit); flight.cancel(); flight.join() }
    }

    @Test
    fun `143 edit during recovery survives atomic cutover and normal reopen`() = runTest {
        seedVerifiedSameScopeRecovery()
        val original = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        val refBefore = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        remote.afterPage = { domain ->
            if (domain == ShopSyncRowDomain.PRODUCTS) {
                repository.updateProduct(original.copy(productName = "Edited during recovery"))
                remote.afterPage = null
            }
        }
        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue("recovery must preserve local edits and complete: $result", result is ShopSyncRecoveryResult.Activated)
        db.close(); db = openDatabase(ACTIVE_DATABASE); repository = DefaultInventoryRepository(db)
        assertEquals("Edited during recovery", db.productDao().getById(original.id)?.productName)
        val ref = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        assertEquals(refBefore.remoteId, ref.remoteId)
        assertTrue(ref.localChangeRevision > ref.lastSyncedLocalRevision)
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `143 pending edit merges a disjoint newer remote field without changing local intent`() = runTest {
        seedVerifiedSameScopeRecovery()
        val original = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        repository.updateProduct(original.copy(productName="Offline name"))
        val intent = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        val base = remote.fixture
        val rows = (base.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.map {
            it.copy(retailPrice=99.0, updatedAt="2026-07-21T12:00:00.000000Z")
        }
        remote = RecoveryRemoteFixture(ordinaryDeltaFixture(base, rows, 43L, mapOf(SyncEventDomains.CATALOG to "43")))
        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue("disjoint change must converge while preserving local intent: $result", result is ShopSyncRecoveryResult.Activated)
        db.close(); db = openDatabase(ACTIVE_DATABASE); repository = DefaultInventoryRepository(db)
        val actual = requireNotNull(db.productDao().getById(original.id))
        assertEquals("Offline name", actual.productName)
        assertEquals(99.0, actual.retailPrice)
        assertEquals(intent, db.productRemoteRefDao().getByProductId(original.id))
        assertForeignKeysClean(db)
    }

    @Test
    fun `143 timestamp only remote advance does not turn pending Save into conflict`() = runTest {
        seedVerifiedSameScopeRecovery()
        val original = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        repository.updateProduct(original.copy(productName="Offline name"))
        val intent = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        val base = remote.fixture
        val rows = (base.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.map {
            it.copy(updatedAt="2026-07-21T12:00:00.000000Z")
        }
        remote = RecoveryRemoteFixture(ordinaryDeltaFixture(base, rows, 43L, mapOf(SyncEventDomains.CATALOG to "43")))
        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals("Offline name", db.productDao().getById(original.id)?.productName)
        assertEquals(intent, db.productRemoteRefDao().getByProductId(original.id))
    }

    @Test
    fun `143 incompatible change to pending field preserves old generation and exact intent`() = runTest {
        seedVerifiedSameScopeRecovery()
        val original = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        repository.updateProduct(original.copy(productName="Offline name"))
        val intent = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val base = remote.fixture
        val rows = (base.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.map {
            it.copy(productName="Independent remote name", updatedAt="2026-07-21T12:00:00.000000Z")
        }
        remote = RecoveryRemoteFixture(ordinaryDeltaFixture(base, rows, 43L, mapOf(SyncEventDomains.CATALOG to "43")))
        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertEquals("recovery_local_remote_conflict", (result as ShopSyncRecoveryResult.RetryRequired).code)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals("Offline name", db.productDao().getById(original.id)?.productName)
        assertEquals(intent, db.productRemoteRefDao().getByProductId(original.id))
        assertForeignKeysClean(db)
    }

    @Test
    fun `143 clean physical corruption is unavailable offline despite intact ledger`() = runTest {
        seedVerifiedSameScopeRecovery()
        val product = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        db.productDao().update(product.copy(productName = "Unrecorded physical corruption"))
        val local = repository.resolveOfflineBusinessDataScope(activeScope(), canWrite = true)
        assertFalse("an intact canonical ledger cannot qualify corrupted clean bodies", local.allowsLocalOperations)
        assertNull(local.localAccessScope)
        assertEquals("Unrecorded physical corruption", db.productDao().getById(product.id)?.productName)
    }

    @Test
    fun `143 sealed A and later B survive recovery cutover before original replay and B confirmation`() = runTest {
        seedVerifiedSameScopeRecovery()
        db.syncRecoveryJournalDao().deleteAll()
        val original = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        val attempts = mutableListOf<InventoryProductPatch>()
        val operationKeys = mutableListOf<String>()
        var loseFirst = true
        var cloudName = original.productName
        val push = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch): Result<Unit> {
                attempts += patch
                operationKeys += db.syncEventOutboxDao().listPending(ACCOUNT,100)
                    .single { it.eventType == BUSINESS_WRITE_EVENT }.clientEventId
                cloudName = patch.productName
                if (loseFirst) { loseFirst=false; return Result.failure(java.io.IOException("fixture A committed ACK lost")) }
                return Result.success(Unit)
            }
        }
        val report = CatalogSyncProgressReporter { }
        val acknowledgedPrices = mutableMapOf<String,InventoryProductPriceRow>()
        val pricePush = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest {
            override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>, shopId: String?): Result<Unit> {
                rows.forEach { acknowledgedPrices[it.id]=it }
                return Result.success(Unit)
            }
        }
        repository.updateProduct(original.copy(productName="Lost ACK A"))
        assertTrue(repository.pushDirtyCatalogDeltaToRemote(push,pricePush,ACCOUNT,report,selectedShop()).isFailure)
        val sealedA = db.syncEventOutboxDao().listPending(ACCOUNT,100).single { it.eventType == BUSINESS_WRITE_EVENT }
        repository.updateProduct(requireNotNull(db.productDao().getById(original.id)).copy(productName="Pending B"))
        val intendedB = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash=activeScope().ownerHash,
            storeScope=activeScope().storeId,shopId=SHOP,deviceId=DEVICE,
            authorizationMode=SyncRecoveryAuthorizationModes.SAME_SCOPE,phase=SyncRecoveryJournalPhases.REQUIRED,
            reason="fixture lost ACK recovery",blockingEventId=42L,attemptCount=0,
            createdAtMs=100L,updatedAtMs=100L,nextRetryAtMs=100L))
        val base = remote.fixture
        val rows = (base.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.map {
            it.copy(productName="Lost ACK A",updatedAt="2026-07-21T12:00:00.000000Z")
        }
        remote = RecoveryRemoteFixture(ordinaryDeltaFixture(base,rows,43L,mapOf(SyncEventDomains.CATALOG to "43")))
        val result = coordinator().recover(ACCOUNT,selectedShop(),activeScope())
        assertTrue("owned original A proof must preserve B across cutover: $result", result is ShopSyncRecoveryResult.Activated)
        assertEquals(sealedA,db.syncEventOutboxDao().listPending(ACCOUNT,100).single { it.eventType == BUSINESS_WRITE_EVENT })
        assertEquals(intendedB,db.productRemoteRefDao().getByProductId(original.id))
        db.close(); db=openDatabase(ACTIVE_DATABASE); repository=DefaultInventoryRepository(db)
        assertEquals("Pending B",db.productDao().getById(original.id)?.productName)
        repository.pushDirtyCatalogDeltaToRemote(push,pricePush,ACCOUNT,report,selectedShop()).getOrThrow()
        assertEquals(3,attempts.size)
        assertEquals(attempts[0],attempts[1])
        assertEquals(operationKeys[0],operationKeys[1])
        assertTrue(operationKeys[1] != operationKeys[2])
        assertEquals("Pending B",cloudName)
        assertFalse(db.productRemoteRefDao().hasPendingWork())
        assertEquals(0,db.syncEventOutboxDao().countAll())
        val priceCount=db.productPriceDao().countAll();val historyCount=db.historyEntryDao().countUserVisible()
        repository.pushDirtyCatalogDeltaToRemote(push,pricePush,ACCOUNT,report,selectedShop()).getOrThrow()
        assertEquals(3,attempts.size)
        assertEquals(priceCount,db.productPriceDao().countAll());assertEquals(historyCount,db.historyEntryDao().countUserVisible())
        assertForeignKeysClean(db)
    }

    @Test
    fun `143 lost price ACK survives cutover original replay and later price confirmation`() = runTest {
        seedVerifiedSameScopeRecovery();db.syncRecoveryJournalDao().deleteAll()
        val initial=requireNotNull(db.productDao().findByBarcode("target-barcode"))
        val attempts=mutableListOf<List<InventoryProductPriceRow>>()
        val keys=mutableListOf<String>()
        val remotePrices=linkedMapOf<String,InventoryProductPriceRow>()
        val base=remote.fixture
        (base.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values.forEach { remotePrices[it.id]=it }
        var lost=true
        val prices=object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest {
            override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>, shopId: String?): Result<Unit> {
                attempts+=rows
                keys+=db.syncEventOutboxDao().listPending(ACCOUNT,100).single { it.eventType==BUSINESS_WRITE_EVENT && it.domain=="PRICES" }.clientEventId
                rows.forEach { remotePrices[it.id]=it.copy(priceCanonical=java.math.BigDecimal(it.price.toString()).stripTrailingZeros().toPlainString(),updatedAt="2026-10-04T20:00:00.000000Z") }
                if(lost) { lost=false;return Result.failure(java.io.IOException("actual price committed ACK lost")) }
                return Result.success(Unit)
            }
        }
        val catalog=object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun patchProduct(id: String,ownerUserId: String,shopId: String?,patch: InventoryProductPatch)=Result.success(Unit)
        }
        repository.updateProduct(initial.copy(retailPrice=31.0))
        val partial=repository.pushDirtyCatalogDeltaToRemote(catalog,prices,ACCOUNT,CatalogSyncProgressReporter { },selectedShop()).getOrThrow()
        assertTrue("the lost price ACK remains a partial outcome",partial.priceSyncFailed)
        val original=db.syncEventOutboxDao().listPending(ACCOUNT,100).single { it.eventType==BUSINESS_WRITE_EVENT }
        assertFalse(repository.observeProductCloudConfirmed(initial.id).first())
        val firstPriceIds=attempts.single().map { it.id }.toSet()
        repository.updateProduct(requireNotNull(db.productDao().getById(initial.id)).copy(retailPrice=37.0))
        val localPriceCount=db.productPriceDao().countAll()
        val remoteProduct=(base.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.map { it.copy(retailPrice=31.0,updatedAt="2026-10-04T20:00:00.000000Z") }
        remote=RecoveryRemoteFixture(ordinaryDeltaFixture(base,remoteProduct,43L,mapOf(SyncEventDomains.CATALOG to "43",SyncEventDomains.PRICES to "43"),remotePrices.values.sortedBy { it.id }))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash=activeScope().ownerHash,storeScope=activeScope().storeId,
            shopId=SHOP,deviceId=DEVICE,authorizationMode=SyncRecoveryAuthorizationModes.SAME_SCOPE,phase=SyncRecoveryJournalPhases.REQUIRED,
            reason="lost price ACK cutover",blockingEventId=42L,attemptCount=0,createdAtMs=100L,updatedAtMs=100L,nextRetryAtMs=100L))
        val recovery=coordinator().recover(ACCOUNT,selectedShop(),activeScope())
        assertTrue("the original own price identity must survive cutover: $recovery",recovery is ShopSyncRecoveryResult.Activated)
        assertEquals(original,db.syncEventOutboxDao().listPending(ACCOUNT,100).single { it.eventType==BUSINESS_WRITE_EVENT })
        assertEquals(localPriceCount,db.productPriceDao().countAll())
        db.close();db=openDatabase(ACTIVE_DATABASE);repository=DefaultInventoryRepository(db)
        repository.pushDirtyCatalogDeltaToRemote(catalog,prices,ACCOUNT,CatalogSyncProgressReporter { },selectedShop()).getOrThrow()
        assertEquals(attempts[0],attempts[1]);assertEquals(keys[0],keys[1])
        assertTrue(keys[1]!=keys[2]);assertEquals(firstPriceIds,attempts[1].map { it.id }.toSet())
        assertEquals(localPriceCount,db.productPriceDao().countAll());assertEquals(localPriceCount,remotePrices.size)
        assertEquals(37.0,db.productDao().getById(initial.id)?.retailPrice)
        assertEquals(0,db.syncEventOutboxDao().countAll());assertFalse(db.productRemoteRefDao().hasPendingWork())
        assertTrue(repository.observeProductCloudConfirmed(initial.id).first())
        db.close();db=openDatabase(ACTIVE_DATABASE);repository=DefaultInventoryRepository(db)
        assertTrue("actual price/product ACK bodies remain qualified after reopen",
            repository.resolveOfflineBusinessDataScope(activeScope(),true).allowsLocalOperations)
        val attemptsBefore=attempts.size
        repository.pushDirtyCatalogDeltaToRemote(catalog,prices,ACCOUNT,CatalogSyncProgressReporter { },selectedShop()).getOrThrow()
        assertEquals(attemptsBefore,attempts.size);assertEquals(localPriceCount,remotePrices.size);assertForeignKeysClean(db)
        repository.addProduct(Product(barcode="unrelated-confirmation-pending",productName="Other pending record"))
        assertTrue("other records cannot mask this exact record confirmation",repository.observeProductCloudConfirmed(initial.id).first())
        val acknowledgedPointId=requireNotNull(db.productPriceRemoteRefDao().getByRemoteId(firstPriceIds.first())).productPriceId
        db.openHelper.writableDatabase.execSQL("UPDATE product_prices SET note='unrecorded corruption' WHERE id=?",arrayOf(acknowledgedPointId))
        assertFalse("a sparse actual ACK proof cannot authorize a corrupted price body",
            repository.resolveOfflineBusinessDataScope(activeScope(),true).allowsLocalOperations)
    }

    @Test fun `143 new price ACK then scoped parent delete remains qualified after reopen`() = runTest {
        verifyAcknowledgedPriceParentDeletion(legitimate = true)
    }

    @Test fun `143 raw parent cascade after new price ACK remains corruption after reopen`() = runTest {
        verifyAcknowledgedPriceParentDeletion(legitimate = false)
    }

    @Test fun `143 parent delete cannot retire a corrupted acknowledged price proof`() = runTest {
        verifyAcknowledgedPriceParentDeletion(legitimate = true, corruptPriceBody = true)
    }

    @Test fun `143 parent delete cannot retire an acknowledged price proof with corrupted revision`() = runTest {
        verifyAcknowledgedPriceParentDeletion(legitimate = true, corruptPriceRevision = true)
    }

    private suspend fun verifyAcknowledgedPriceParentDeletion(
        legitimate: Boolean, corruptPriceBody: Boolean = false, corruptPriceRevision: Boolean = false
    ) {
        seedVerifiedSameScopeRecovery()
        db.syncRecoveryJournalDao().deleteAll()
        val initial = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        val parentRemoteId = requireNotNull(db.productRemoteRefDao().getByProductId(initial.id)).remoteId
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val canonical = ShopSyncRowDomain.entries.associateWith {
            db.syncRecoveryManifestDao().page(baseline.generationId, it.wireValue, null, 1000)
        }
        val acknowledgedPrices = mutableListOf<InventoryProductPriceRow>()
        val prices = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest {
            override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>, shopId: String?): Result<Unit> {
                acknowledgedPrices += rows
                return Result.success(Unit)
            }
        }
        val catalog = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch) = Result.success(Unit)
        }
        repository.updateProduct(initial.copy(retailPrice = 31.0))
        repository.pushDirtyCatalogDeltaToRemote(catalog, prices, ACCOUNT, CatalogSyncProgressReporter { }, selectedShop()).getOrThrow()
        assertTrue(acknowledgedPrices.isNotEmpty())
        assertTrue(acknowledgedPrices.all { row -> canonical.getValue(ShopSyncRowDomain.PRICES).none { it.remoteId == row.id } })
        assertTrue(repository.observeProductCloudConfirmed(initial.id).first())
        var privateProofs = db.syncRecoveryManifestDao().page(baseline.generationId, LOCAL_ACK_BODY_PREFIX + "prices", null, 1000)
        assertEquals(acknowledgedPrices.map { it.id }.toSet(), privateProofs.map { it.remoteId }.toSet())
        repository.addProduct(Product(barcode = "other-pending-delete-control", productName = "Protected unrelated pending"))
        val otherId = requireNotNull(db.productDao().findByBarcode("other-pending-delete-control")).id
        val pendingRef = db.productRemoteRefDao().getByProductId(otherId)
        val pendingOutbox = db.syncEventOutboxDao().listPending(ACCOUNT, 100)
        val current = requireNotNull(db.productDao().getById(initial.id))
        if (corruptPriceBody || corruptPriceRevision) {
            if (corruptPriceBody) {
                val pointId = requireNotNull(db.productPriceRemoteRefDao().getByRemoteId(acknowledgedPrices.first().id)).productPriceId
                db.openHelper.writableDatabase.execSQL("UPDATE product_prices SET note='unrecorded corruption' WHERE id=?", arrayOf(pointId))
            } else {
                val proof = privateProofs.first()
                val identity = Json.decodeFromString<LocalAcknowledgedBodyIdentity>(proof.versionLine)
                db.syncRecoveryManifestDao().upsertAll(listOf(proof.copy(versionLine = Json.encodeToString(identity.copy(revision = 1)))))
                privateProofs = db.syncRecoveryManifestDao().page(baseline.generationId, LOCAL_ACK_BODY_PREFIX + "prices", null, 1000)
            }
            assertFalse("corruption is already refused by the reopen qualifier", repository.resolveOfflineBusinessDataScope(activeScope(), true).allowsLocalOperations)
            val deletion = runCatching { repository.deleteProduct(current) }
            assertEquals(if (corruptPriceBody) "local_ack_delete_body_mismatch" else "local_ack_identity_mismatch", deletion.exceptionOrNull()?.message)
            assertEquals(current, db.productDao().getById(initial.id))
        } else if (legitimate) repository.deleteProduct(current) else db.productDao().delete(current)
        val tombstones = db.pendingCatalogTombstoneDao().listPendingOrdered()
        if (legitimate && !corruptPriceBody && !corruptPriceRevision) assertEquals(parentRemoteId, tombstones.single().remoteId) else assertTrue(tombstones.isEmpty())
        db.close(); db = openDatabase(ACTIVE_DATABASE); repository = DefaultInventoryRepository(db)
        val local = repository.resolveOfflineBusinessDataScope(activeScope(), true)
        assertEquals("only a legitimate scoped deletion may explain the price cascade", legitimate && !corruptPriceBody && !corruptPriceRevision, local.allowsLocalOperations)
        if (corruptPriceBody || corruptPriceRevision) assertEquals(current, db.productDao().getById(initial.id)) else {
            assertNull(db.productDao().getById(initial.id))
            assertTrue(db.productPriceDao().getForProducts(listOf(initial.id)).isEmpty())
        }
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        for ((domain, rows) in canonical) assertEquals(rows, db.syncRecoveryManifestDao().page(baseline.generationId, domain.wireValue, null, 1000))
        assertEquals(tombstones, db.pendingCatalogTombstoneDao().listPendingOrdered())
        assertEquals(pendingRef, db.productRemoteRefDao().getByProductId(otherId))
        assertEquals(pendingOutbox, db.syncEventOutboxDao().listPending(ACCOUNT, 100))
        if (!legitimate || corruptPriceBody || corruptPriceRevision) assertEquals(privateProofs, db.syncRecoveryManifestDao().page(baseline.generationId, LOCAL_ACK_BODY_PREFIX + "prices", null, 1000))
        assertForeignKeysClean(db)
    }

    @Test fun `143 lost History ACK and same field B survive cutover original replay and B confirmation`() = runTest {
        verifyLostHistoryCutover(false)
    }
    @Test fun `143 lost History ACK and different field B survive cutover original replay and B confirmation`() = runTest {
        verifyLostHistoryCutover(true)
    }

    private suspend fun verifyLostHistoryCutover(differentField: Boolean) {
        seedOldMismatchGeneration();remote=RecoveryRemoteFixture(v2HistoryFixture())
        assertTrue(coordinator().recover(ACCOUNT,selectedShop(),activeScope()) is ShopSyncRecoveryResult.Activated)
        val initial=db.historyEntryDao().getAllUserVisibleSnapshot().single()
        val base=remote.fixture
        val attempts=mutableListOf<List<SharedSheetSessionUpsertRow>>();val keys=mutableListOf<String>();var lost=true
        var cloud: SharedSheetSessionUpsertRow?=null
        val push=object : SessionBackupRemoteDataSource {
            override val isConfigured=true
            override suspend fun fetchAllSessionsForOwner()=Result.success(emptyList<SharedSheetSessionRecord>())
            override suspend fun fetchSessionsByRemoteIds(remoteIds: Set<String>)=Result.success(emptyList<SharedSheetSessionRecord>())
            override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> {
                attempts+=rows;cloud=rows.single()
                keys+=db.syncEventOutboxDao().listPending(ACCOUNT,100).single { it.eventType==BUSINESS_WRITE_EVENT }.clientEventId
                if(lost) { lost=false;return Result.failure(java.io.IOException("actual History committed ACK lost")) }
                return Result.success(Unit)
            }
        }
        repository.updateHistoryEntry(initial.copy(displayName="History lost ACK A"))
        assertTrue(repository.pushHistorySessionsToRemote(push,ACCOUNT,setOf(initial.uid),selectedShop()).isFailure)
        val original=db.syncEventOutboxDao().listPending(ACCOUNT,100).single { it.eventType==BUSINESS_WRITE_EVENT }
        val a=requireNotNull(db.historyEntryDao().getByUid(initial.uid))
        val b=if(differentField) a.copy(supplier="History different field B") else a.copy(displayName="History same field B")
        repository.updateHistoryEntry(b)
        val intention=requireNotNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(initial.uid))
        val received=(base.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History).values.single().copy(
            displayName="History lost ACK A",updatedAt="2026-10-04T20:00:00.000000Z")
        remote=RecoveryRemoteFixture(ordinaryHistoryDeltaFixture(base,received))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash=activeScope().ownerHash,storeScope=activeScope().storeId,
            shopId=SHOP,deviceId=DEVICE,authorizationMode=SyncRecoveryAuthorizationModes.SAME_SCOPE,phase=SyncRecoveryJournalPhases.REQUIRED,
            reason="lost History ACK cutover",blockingEventId=42L,attemptCount=0,createdAtMs=100L,updatedAtMs=100L,nextRetryAtMs=100L))
        val recovery=coordinator().recover(ACCOUNT,selectedShop(),activeScope())
        assertTrue("an exact owned original History A must preserve B through cutover: $recovery",recovery is ShopSyncRecoveryResult.Activated)
        assertEquals(original,db.syncEventOutboxDao().listPending(ACCOUNT,100).single { it.eventType==BUSINESS_WRITE_EVENT })
        assertEquals(intention,db.historyEntryRemoteRefDao().getByHistoryEntryUid(initial.uid))
        db.close();db=openDatabase(ACTIVE_DATABASE);repository=DefaultInventoryRepository(db)
        val persisted=requireNotNull(db.historyEntryDao().getByUid(initial.uid))
        assertEquals(b.displayName,persisted.displayName);assertEquals(b.supplier,persisted.supplier)
        repository.pushHistorySessionsToRemote(push,ACCOUNT,setOf(initial.uid),selectedShop()).getOrThrow()
        assertEquals(3,attempts.size);assertEquals(attempts[0],attempts[1]);assertEquals(keys[0],keys[1]);assertTrue(keys[1]!=keys[2])
        assertEquals(b.displayName,cloud?.displayName);assertEquals(b.supplier,cloud?.supplier)
        assertEquals(0,db.syncEventOutboxDao().countAll());assertEquals(1,db.historyEntryDao().countUserVisible())
        val clean=requireNotNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(initial.uid))
        assertEquals(clean.localChangeRevision,clean.lastSyncedLocalRevision)
        db.close();db=openDatabase(ACTIVE_DATABASE);repository=DefaultInventoryRepository(db)
        assertTrue("actual History B ACK remains qualified after reopen",
            repository.resolveOfflineBusinessDataScope(activeScope(),true).allowsLocalOperations)
        repository.pushHistorySessionsToRemote(push,ACCOUNT,setOf(initial.uid),selectedShop()).getOrThrow()
        assertEquals(3,attempts.size);assertEquals(1,db.historyEntryDao().countUserVisible());assertForeignKeysClean(db)
        db.historyEntryDao().update(requireNotNull(db.historyEntryDao().getByUid(initial.uid)).copy(displayName="Unrecorded clean History corruption"))
        assertFalse("the actual History ACK proof cannot authorize unrecorded body corruption",
            repository.resolveOfflineBusinessDataScope(activeScope(),true).allowsLocalOperations)
    }

    @Test
    fun `143 acknowledged clean local edit remains qualified after actual reopen`() = runTest {
        seedVerifiedSameScopeRecovery()
        db.syncRecoveryJournalDao().deleteAll()
        val product = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        repository.updateProduct(product.copy(productName="Actually acknowledged local edit"))
        val push = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch) = Result.success(Unit)
        }
        val prices = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest {
            override val isConfigured = false
        }
        repository.pushDirtyCatalogDeltaToRemote(push,prices,ACCOUNT,CatalogSyncProgressReporter { },selectedShop()).getOrThrow()
        val acknowledged = requireNotNull(db.productRemoteRefDao().getByProductId(product.id))
        assertEquals(acknowledged.localChangeRevision,acknowledged.lastSyncedLocalRevision)
        db.close();db=openDatabase(ACTIVE_DATABASE);repository=DefaultInventoryRepository(db)
        val local=repository.resolveOfflineBusinessDataScope(activeScope(),true)
        assertTrue("a real ACK must not turn legitimate current clean bodies into corrupt historical C: $local",local.allowsLocalOperations)
        val tracker=CatalogSyncStateTracker(local)
        val guarded=DefaultInventoryRepository(db,tracker)
        guarded.updateProduct(requireNotNull(db.productDao().getById(product.id)).copy(stockQuantity=71.0))
        assertEquals(71.0,db.productDao().getById(product.id)?.stockQuantity)
    }

    @Test
    fun `143 fresh cloud binding cannot regrant corrupted local generation`() = runTest {
        seedVerifiedSameScopeRecovery()
        db.syncRecoveryJournalDao().deleteAll()
        val product = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        db.productDao().update(product.copy(productName = "Unrecorded physical corruption"))
        val fresh = repository.resolveBusinessDataScope(activeScope())
        assertFalse(fresh.allowsLocalOperations)
        assertFalse(fresh.allowsCloudSync)
        assertEquals("sync_recovery_required", fresh.errorCode)
        assertEquals(SyncRecoveryAuthorizationModes.SAME_SCOPE,
            db.syncRecoveryJournalDao().get()?.authorizationMode)
        assertEquals("Unrecorded physical corruption", db.productDao().getById(product.id)?.productName)
    }

    private suspend fun seedVerifiedSameScopeRecovery() {
        seedOldMismatchGeneration()
        assertTrue(coordinator().recover(ACCOUNT, selectedShop(), activeScope()) is ShopSyncRecoveryResult.Activated)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        assertEquals(activeScope().ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(
            ownerHash = activeScope().ownerHash, storeScope = activeScope().storeId, shopId = SHOP,
            deviceId = DEVICE, authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = "fixture_slow_cloud_same_scope",
            blockingEventId = 40L, attemptCount = 0, createdAtMs = 100L, updatedAtMs = 100L, nextRetryAtMs = 100L))
    }

    @Test
    fun `143 reopen after second page timeout reuses durably accepted first page and cursor`() = runTest {
        seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(mixedRetainedPriceFixture())
        val paged = PagedRecoveryRemoteFixture(remote)
        val requests = mutableListOf<Pair<ShopSyncRowDomain, String?>>()
        var failSecond = true
        val flaky = object : ShopSyncReadRemoteDataSource by paged {
            override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain,
                afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
                requests += domain to afterId
                if (domain == ShopSyncRowDomain.PRICES && afterId != null && failSecond) {
                    failSecond = false
                    return Result.failure(java.io.IOException("fixture second page timeout"))
                }
                return paged.recoveryPage(context, domain, afterId, limit)
            }
        }
        val failed = coordinator(recoveryRemote=flaky).recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(failed.toString(), failed is ShopSyncRecoveryResult.RetryRequired)
        assertOldGenerationAndManifestIntact()
        db.close(); db = openDatabase(ACTIVE_DATABASE); repository = DefaultInventoryRepository(db)
        val resumed = coordinator(recoveryRemote=flaky).recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(resumed.toString(), resumed is ShopSyncRecoveryResult.Activated)
        assertEquals("first committed price page must not be fetched twice", 1,
            requests.count { it.first == ShopSyncRowDomain.PRICES && it.second == null })
        for (domain in listOf(ShopSyncRowDomain.SUPPLIERS, ShopSyncRowDomain.CATEGORIES, ShopSyncRowDomain.PRODUCTS)) {
            assertEquals("completed dependency domain must be reused: $domain", 1, requests.count { it.first == domain })
        }
        assertEquals(560, db.productPriceDao().countAll())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `143 interrupted page rolls back ledger projection and cursor together`() = runTest {
        seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(mixedRetainedPriceFixture())
        val paged = PagedRecoveryRemoteFixture(remote)
        val requests = mutableListOf<Pair<ShopSyncRowDomain, String?>>()
        val counted = object : ShopSyncReadRemoteDataSource by paged {
            override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain,
                afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
                requests += domain to afterId
                return paged.recoveryPage(context, domain, afterId, limit)
            }
        }
        ShopSyncRecoveryTestHooks.afterPageMaterializedBeforeCommit = { domain ->
            if (domain == ShopSyncRowDomain.PRICES) throw java.io.IOException("fixture death before page commit")
        }
        assertTrue(coordinator(recoveryRemote=counted).recover(ACCOUNT, selectedShop(), activeScope()) is ShopSyncRecoveryResult.RetryRequired)
        assertOldGenerationAndManifestIntact()
        val journal = requireNotNull(db.syncRecoveryJournalDao().get())
        val stage = openDatabase(requireNotNull(journal.stagingDatabaseName))
        try {
            assertEquals(0, stage.productPriceDao().countAll())
            assertNull(RecoveryPageProgress(stage.openHelper.writableDatabase).accepted(ShopSyncRowDomain.PRICES))
            stage.openHelper.readableDatabase.query("SELECT count(*) FROM sync_recovery_manifest WHERE domain='prices'").use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
            }
        } finally { stage.close() }
        ShopSyncRecoveryTestHooks.afterPageMaterializedBeforeCommit = null
        db.close(); db = openDatabase(ACTIVE_DATABASE); repository = DefaultInventoryRepository(db)
        val result = coordinator(recoveryRemote=counted).recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(2, requests.count { it.first == ShopSyncRowDomain.PRICES && it.second == null })
        assertEquals(1, requests.count { it.first == ShopSyncRowDomain.PRODUCTS })
        assertEquals(560, db.productPriceDao().countAll())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
    }

    @Test
    fun `143 death after validation resumes original accepted generation before publication`() = runTest {
        seedOldMismatchGeneration()
        val fatal = OutOfMemoryError("fixture process death after validated stage")
        ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = { throw fatal }
        val error = runCatching { coordinator().recover(ACCOUNT, selectedShop(), activeScope()) }.exceptionOrNull()
        assertTrue(error is OutOfMemoryError)
        assertEquals(fatal.message, error?.message)
        assertOldGenerationAndManifestIntact()
        val journal = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals(SyncRecoveryJournalPhases.READY_TO_ACTIVATE, journal.phase)
        val pages = remote.pageCalls
        ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = null
        db.close(); db = openDatabase(ACTIVE_DATABASE); repository = DefaultInventoryRepository(db)
        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(journal.runId, (result as ShopSyncRecoveryResult.Activated).generationId)
        assertEquals(pages, remote.pageCalls)
        assertNull(db.syncRecoveryJournalDao().get())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
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
    fun `real recovery and wire DTO match all 45 shared History timestamp oracle cases`() = runTest {
        val oracleText = requireNotNull(javaClass.classLoader).getResourceAsStream(
            "fixtures/history-timestamp-compatibility-v1.json"
        )!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(
            "b5848df09494112d85509297c5430d8e4d64398428b3b71631a195f9caae6459",
            testSha256(oracleText)
        )
        val oracle = Json.parseToJsonElement(oracleText).jsonObject
        assertEquals("history-timestamp-compatibility-v1", oracle.getValue("schemaVersion").jsonPrimitive.content)
        val cases = oracle.getValue("cases").jsonArray
        assertEquals(45, cases.size)
        assertEquals(45, cases.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet().size)
        val disagreements = mutableListOf<String>()
        var checked = 0
        cases.forEachIndexed { index, element ->
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val wireTimestamp = case.getValue("value")
            val rawTimestamp = wireTimestamp.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val expectedAccepted = case.getValue("historyAccepted").jsonPrimitive.boolean
            // legacyAccepted is backend metadata; no assertion or widening outside History.
            db.close()
            db = openDatabase("${ACTIVE_DATABASE}_history_oracle_$index")
            repository = DefaultInventoryRepository(db)
            val fixture = targetFixture(historyTimestamp = rawTimestamp ?: "null-wire-template")
            remote = RecoveryRemoteFixture(fixture)
            seedOldMismatchGeneration()
            val row = (fixture.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History)
                .values.single()
            val encoded = JsonObject(
                Json.parseToJsonElement(Json.encodeToString(row)).jsonObject + ("timestamp" to wireTimestamp)
            )
            val decoded = runCatching { Json.decodeFromString<SharedSheetSessionRecord>(encoded.toString()) }
            val accepted: Boolean
            val outcome: String
            if (rawTimestamp == null) {
                // The actual wire model rejects null before a recovery page can reach Room.
                assertTrue(name, decoded.exceptionOrNull() is SerializationException)
                assertEquals(0, remote.pageCalls)
                assertOldGenerationAndManifestIntact()
                accepted = false
                outcome = "wire_timestamp_rejected"
            } else {
                val decodedRow = decoded.getOrThrow()
                assertEquals(name, rawTimestamp, decodedRow.timestamp)
                remote = RecoveryRemoteFixture(
                    fixture.copy(rows = fixture.rows + (ShopSyncRowDomain.HISTORY to ShopSyncRows.History(listOf(decodedRow))))
                )
                val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
                accepted = result is ShopSyncRecoveryResult.Activated
                outcome = if (accepted) "Activated" else (result as ShopSyncRecoveryResult.RetryRequired).code
                assertTrue(name, remote.requestedPageLimits.containsKey(ShopSyncRowDomain.HISTORY))
                if (accepted) {
                    assertEquals(name, rawTimestamp, db.historyEntryDao().getById(row.remoteId)?.timestamp)
                    assertEquals(
                        name,
                        fixture.checkpoint.history.versionDigest,
                        decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson)
                            .history.versionDigest
                    )
                    assertNull(db.syncRecoveryJournalDao().get())
                    assertForeignKeysClean(db)
                } else {
                    assertEquals(name, "recovery_manifest_digest_mismatch_history", outcome)
                    assertOldGenerationAndManifestIntact()
                    assertFalse(stageFiles().any())
                }
            }
            if (accepted != expectedAccepted) {
                disagreements += "$name expectedAccepted=$expectedAccepted actualAccepted=$accepted outcome=$outcome"
            }
            checked++
        }
        assertEquals(45, checked)
        assertEquals("All shared cases must match the actual History recovery boundary", emptyList<String>(), disagreements)
    }

    @Test
    fun `recovery preserves ISO millisecond History timestamp and raw checkpoint across Room restart`() = runTest {
        val rawTimestamp = "2026-07-05T15:40:11.305Z"
        val fixture = targetFixture(historyTimestamp = rawTimestamp)
        val history = (fixture.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History)
            .values.single()
        remote = RecoveryRemoteFixture(fixture)
        seedOldMismatchGeneration()

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(rawTimestamp, db.historyEntryDao().getById(history.remoteId)?.timestamp)
        assertEquals(
            fixture.checkpoint.history.versionDigest,
            decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson)
                .history.versionDigest
        )
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)

        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)

        assertEquals(rawTimestamp, db.historyEntryDao().getById(history.remoteId)?.timestamp)
        val persistedCheckpoint = decodeRecoveryCheckpointJson(
            requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson
        )
        assertEquals(fixture.checkpoint.history.versionDigest, persistedCheckpoint.history.versionDigest)
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun `recovery accepts Gregorian leap day and ISO millisecond boundaries`() = runTest {
        listOf(
            "2024-02-29T00:00:00.000Z",
            "2000-02-29T23:59:59.999Z",
            "0001-01-01T00:00:00.001Z",
            "9999-12-31T23:59:59.999Z"
        ).forEachIndexed { index, rawTimestamp ->
            db.close()
            db = openDatabase("${ACTIVE_DATABASE}_history_valid_$index")
            repository = DefaultInventoryRepository(db)
            val fixture = targetFixture(historyTimestamp = rawTimestamp)
            remote = RecoveryRemoteFixture(fixture)
            seedOldMismatchGeneration()

            val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

            assertTrue(rawTimestamp + ": " + result, result is ShopSyncRecoveryResult.Activated)
            assertEquals(rawTimestamp, db.historyEntryDao().getAllUserVisibleSnapshot().single().timestamp)
            assertEquals(
                fixture.checkpoint.history.versionDigest,
                decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson)
                    .history.versionDigest
            )
        }
    }

    @Test
    fun `recovery rejects unsupported ISO History grammar and invalid Gregorian values`() = runTest {
        listOf(
            "2026-07-05T15:40:11Z",
            "2026-07-05T15:40:11.3Z",
            "2026-07-05T15:40:11.30Z",
            "2026-07-05T15:40:11.3050Z",
            "2026-07-05T15:40:11.305000Z",
            "2026-07-05T15:40:11.3050000Z",
            "2026-07-05T15:40:11.305+00:00",
            "2026-07-05T15:40:11.305z",
            "2026-07-05 15:40:11.305Z",
            " 2026-07-05T15:40:11.305Z",
            "2026-07-05T15:40:11.305Z ",
            "2026-7-05T15:40:11.305Z",
            "2026-13-05T15:40:11.305Z",
            "2026-02-29T15:40:11.305Z",
            "1900-02-29T15:40:11.305Z",
            "2026-04-31T15:40:11.305Z",
            "2026-07-05T24:00:00.305Z",
            "2026-07-05T15:60:11.305Z",
            "2026-07-05T15:40:60.305Z",
            "0000-07-05T15:40:11.305Z"
        ).forEachIndexed { index, rawTimestamp ->
            db.close()
            db = openDatabase("${ACTIVE_DATABASE}_history_invalid_$index")
            repository = DefaultInventoryRepository(db)
            remote = RecoveryRemoteFixture(targetFixture(historyTimestamp = rawTimestamp))
            seedOldMismatchGeneration()

            val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

            assertEquals(
                rawTimestamp,
                "recovery_manifest_digest_mismatch_history",
                (result as ShopSyncRecoveryResult.RetryRequired).code
            )
            assertTrue(rawTimestamp, remote.requestedPageLimits.containsKey(ShopSyncRowDomain.HISTORY))
            assertOldGenerationAndManifestIntact()
            assertFalse(stageFiles().any())
        }
    }

    @Test
    fun `recovery rejects ISO History raw checkpoint digest mismatch`() = runTest {
        val fixture = targetFixture(historyTimestamp = "2026-07-05T15:40:11.305Z")
        val differentMillisecondCheckpoint = targetFixture(
            historyTimestamp = "2026-07-05T15:40:11.306Z"
        ).checkpoint
        remote = RecoveryRemoteFixture(fixture.copy(checkpoint = differentMillisecondCheckpoint))
        seedOldMismatchGeneration()

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_manifest_digest_mismatch_history",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
    }

    @Test
    fun `ISO History support does not widen legacy price timestamp grammar`() = runTest {
        val fixture = targetFixture(historyTimestamp = "2026-07-05T15:40:11.305Z")
        val price = (fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices)
            .values.single().copy(effectiveAt = "2026-07-05T15:40:11.305Z")
        remote = RecoveryRemoteFixture(
            fixture.copy(
                checkpoint = fixture.checkpoint.copy(
                    prices = checkpointDomain(listOf(price.id), listOf(testPriceVersion(price)))
                ),
                rows = fixture.rows + (ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(listOf(price)))
            )
        )
        seedOldMismatchGeneration()

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals(
            "recovery_manifest_digest_mismatch_prices",
            (result as ShopSyncRecoveryResult.RetryRequired).code
        )
        assertOldGenerationAndManifestIntact()
        assertFalse(stageFiles().any())
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
    fun `confirmed replacement registers the same install identity before checkpoint`() = runTest {
        val device = seedOldMismatchGeneration()
        var registrations = 0
        val result = coordinator(registerDeviceForRecovery = { shopId ->
            registrations++
            assertEquals(SHOP, shopId)
            assertEquals(device.deviceId, DeviceInstallIdProvider(db.syncEventDeviceStateDao()).getOrCreate())
            assertEquals(0, remote.checkpointCalls)
            assertOldGenerationAndManifestIntact()
            Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = shopId))
        }).recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(1, registrations)
        assertEquals(device, db.syncEventDeviceStateDao().get())
    }

    @Test
    fun `registration denial preserves old generation and prevents checkpoint`() = runTest {
        seedOldMismatchGeneration()
        val result = coordinator(registerDeviceForRecovery = { shopId ->
            Result.success(ShopDeviceRegistrationResult(ok = false, code = "unauthorized", shopId = shopId))
        }).recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals("recovery_device_registration_denied", (result as ShopSyncRecoveryResult.RetryRequired).code)
        assertEquals(0, remote.checkpointCalls)
        assertEquals(0, remote.pageCalls)
        assertOldGenerationAndManifestIntact()
    }

    @Test
    fun `registration receipt for another shop cannot authorize recovery`() = runTest {
        seedOldMismatchGeneration()
        val result = coordinator(registerDeviceForRecovery = {
            Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = "other-shop"))
        }).recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals("recovery_device_registration_denied", (result as ShopSyncRecoveryResult.RetryRequired).code)
        assertEquals(0, remote.checkpointCalls)
        assertOldGenerationAndManifestIntact()
    }

    @Test
    fun `registration failure retries the persisted identity without data loss`() = runTest {
        val device = seedOldMismatchGeneration()
        val first = coordinator(registerDeviceForRecovery = {
            Result.failure(IllegalStateException("fixture network failure"))
        }).recover(ACCOUNT, selectedShop(), activeScope())
        assertEquals("recovery_device_registration_failed", (first as ShopSyncRecoveryResult.RetryRequired).code)
        assertEquals(0, remote.checkpointCalls)
        assertOldGenerationAndManifestIntact()

        val second = coordinator(registerDeviceForRecovery = { shopId ->
            assertEquals(device.deviceId, DeviceInstallIdProvider(db.syncEventDeviceStateDao()).getOrCreate())
            Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = shopId))
        }).recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(second.toString(), second is ShopSyncRecoveryResult.Activated)
        assertEquals(device, db.syncEventDeviceStateDao().get())
    }

    @Test
    fun `scope changed during registration prevents checkpoint and local activation`() = runTest {
        seedOldMismatchGeneration()
        var scopeCurrent = true
        val result = coordinator(
            scopeStillValid = { _, _ -> scopeCurrent },
            registerDeviceForRecovery = { shopId ->
                scopeCurrent = false
                Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = shopId))
            }
        ).recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals("recovery_lease_invalid_after_registration", (result as ShopSyncRecoveryResult.RetryRequired).code)
        assertEquals(0, remote.checkpointCalls)
        assertOldGenerationAndManifestIntact()
    }

    @Test
    fun `newer journal during registration cannot be overwritten or used for checkpoint`() = runTest {
        seedOldMismatchGeneration()
        var newer: SyncRecoveryJournal? = null
        val result = coordinator(registerDeviceForRecovery = { shopId ->
            newer = requireNotNull(db.syncRecoveryJournalDao().get()).copy(runId = "newer-registration-run")
            db.syncRecoveryJournalDao().upsert(requireNotNull(newer))
            Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = shopId))
        }).recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals("recovery_journal_changed", (result as ShopSyncRecoveryResult.RetryRequired).code)
        assertEquals(newer, db.syncRecoveryJournalDao().get())
        assertEquals(0, remote.checkpointCalls)
        assertOldGenerationAndManifestIntact()
    }

    @Test
    fun `registration cancellation remains cancellation and keeps recovery durable`() = runTest {
        seedOldMismatchGeneration()
        val failure = runCatching {
            coordinator(registerDeviceForRecovery = {
                Result.failure(CancellationException("fixture registration cancelled"))
            }).recover(ACCOUNT, selectedShop(), activeScope())
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("recovery_cancelled", db.syncRecoveryJournalDao().get()?.reason)
        assertEquals(0, remote.checkpointCalls)
        assertOldGenerationAndManifestIntact()
    }

    @Test
    fun `server denial stops retry window without activation and later trigger can recover`() = runTest {
        seedOldMismatchGeneration()
        val logs = mutableListOf<String>()
        listOf("resource_exceeded", "invalid_baseline", "integrity_blocked").forEach { status ->
            val code = "checkpoint_$status"
            remote.checkpointFailure = ShopSyncContractException(code, "shop_sync_recovery_checkpoint_v1")
            val result = coordinator(logger = logs::add).recover(ACCOUNT, selectedShop(), activeScope())
            assertEquals(code, (result as ShopSyncRecoveryResult.Rejected).code)
            assertEquals(code, db.syncRecoveryJournalDao().get()?.reason)
            assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
            assertEquals(0, remote.pageCalls)
            assertOldGenerationAndManifestIntact()
            assertFalse(stageFiles().any())
        }
        assertTrue(logs.any { "code=checkpoint_resource_exceeded rpc=shop_sync_recovery_checkpoint_v1" in it })
        assertFalse(logs.any { ACCOUNT in it || SHOP in it || DEVICE in it })

        remote.checkpointFailure = null
        val repaired = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(repaired.toString(), repaired is ShopSyncRecoveryResult.Activated)
    }

    @Test
    fun `missing response fields remain durable technical diagnostics without business payload`() = runTest {
        seedOldMismatchGeneration()
        remote.checkpointFailure = ShopSyncContractException(
            "rpc_response_missing_fields", "shop_sync_recovery_checkpoint_v1", listOf("catalog", "integrity")
        )
        val logs = mutableListOf<String>()
        val result = coordinator(logger = logs::add).recover(ACCOUNT, selectedShop(), activeScope())

        assertEquals("rpc_response_missing_fields", (result as ShopSyncRecoveryResult.Rejected).code)
        assertEquals("rpc_response_missing_fields", db.syncRecoveryJournalDao().get()?.reason)
        assertEquals(0, remote.pageCalls)
        assertTrue(logs.any { "missingFields=catalog,integrity" in it })
        assertFalse(logs.any { ACCOUNT in it || SHOP in it || DEVICE in it || "old-barcode" in it })
        assertOldGenerationAndManifestIntact()
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
        assertEquals(SyncRecoveryJournalPhases.STAGING, db.syncRecoveryJournalDao().get()?.phase)
        assertEquals(remote.fixture.checkpoint.checkpointDigest, db.syncRecoveryJournalDao().get()?.checkpointADigest)
        assertEquals(1, stageFiles().count())

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
        assertEquals(SyncRecoveryJournalPhases.STAGING, journal?.phase)
        assertEquals("recovery_cancelled", journal?.reason)
        assertEquals(
            SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            journal?.authorizationMode
        )
        assertNotNull(journal?.nextRetryAtMs)
        assertEquals(1, stageFiles().count())
        assertEquals(remote.fixture.checkpoint.checkpointDigest, journal?.checkpointADigest)

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
    fun `ordinary failure after activation commit preserves exact durable generation for reopen cleanup`() = runTest {
        seedOldMismatchGeneration()
        var committedJournal: SyncRecoveryJournal? = null
        var committedBaseline: SyncRecoveryBaseline? = null
        ShopSyncRecoveryTestHooks.afterActivationCommitted = {
            runBlocking {
                committedJournal = db.syncRecoveryJournalDao().get()
                committedBaseline = db.syncRecoveryBaselineDao().get()
            }
            throw java.io.IOException("fixture_post_commit_pre_flag")
        }

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.RetryRequired)
        val persisted = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals(SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING, persisted.phase)
        assertEquals(committedJournal?.runId, persisted.runId)
        assertEquals(committedJournal?.checkpointADigest, persisted.checkpointADigest)
        assertEquals(committedJournal?.checkpointBDigest, persisted.checkpointBDigest)
        assertEquals(committedJournal?.stagingDatabaseName, persisted.stagingDatabaseName)
        assertEquals(committedBaseline, db.syncRecoveryBaselineDao().get())
        assertNotNull(db.productDao().findByBarcode("target-barcode"))
        assertNull(db.productDao().findByBarcode("old-barcode"))

        ShopSyncRecoveryTestHooks.afterActivationCommitted = null
        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)
        val resumed = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(resumed.toString(), resumed is ShopSyncRecoveryResult.Activated)
        assertEquals(committedBaseline, db.syncRecoveryBaselineDao().get())
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
        assertEquals(SyncRecoveryJournalPhases.STAGING, db.syncRecoveryJournalDao().get()?.phase)
        assertEquals(remote.fixture.checkpoint.checkpointDigest, db.syncRecoveryJournalDao().get()?.checkpointADigest)
        assertEquals(1, stageFiles().count())
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
    fun retainedAppendOnlyPriceForTombstonedProductRecoversCompleteLedger() = runTest {
        val deviceBefore = seedOldMismatchGeneration()
        val activeFixture = targetFixture()
        val deletedFixture = deletedProductImageFixture()
        val retainedPriceRows = activeFixture.rows.getValue(ShopSyncRowDomain.PRICES)
        val retainedPrice = (retainedPriceRows as ShopSyncRows.Prices).values.single()
        remote = RecoveryRemoteFixture(
            deletedFixture.copy(
                checkpoint = deletedFixture.checkpoint.copy(prices = activeFixture.checkpoint.prices),
                rows = deletedFixture.rows + (ShopSyncRowDomain.PRICES to retainedPriceRows)
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertEquals(
            activeFixture.checkpoint.prices,
            decodeRecoveryCheckpointJson(baseline.checkpointJson).prices
        )
        val priceManifest = db.syncRecoveryManifestDao()
            .page(baseline.generationId, ShopSyncRowDomain.PRICES.wireValue, null, 10)
            .single()
        assertTrue(priceManifest.active)
        assertEquals(retainedPrice.id, priceManifest.remoteId)
        assertEquals(testPriceVersion(retainedPrice), priceManifest.versionLine)
        assertNotNull(priceManifest.payloadDigest)
        val productManifest = requireNotNull(
            db.syncRecoveryManifestDao().get(
                baseline.generationId,
                ShopSyncRowDomain.PRODUCTS.wireValue,
                retainedPrice.productId
            )
        )
        assertFalse(productManifest.active)
        assertEquals(0, db.productDao().getAll().size)
        assertEquals(0, db.productPriceDao().countAll())
        assertNull(db.productRemoteRefDao().getByRemoteId(retainedPrice.productId))
        assertNull(db.productPriceRemoteRefDao().getByRemoteId(retainedPrice.id))
        assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)

        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)

        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(
            priceManifest,
            db.syncRecoveryManifestDao().get(baseline.generationId, ShopSyncRowDomain.PRICES.wireValue, retainedPrice.id)
        )
        assertEquals(0, db.productDao().getAll().size)
        assertEquals(0, db.productPriceDao().countAll())
        assertNull(db.productRemoteRefDao().getByRemoteId(retainedPrice.productId))
        assertNull(db.productPriceRemoteRefDao().getByRemoteId(retainedPrice.id))
        assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
        assertEquals(42L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
        assertFalse(stageFiles().any())
    }

    @Test
    fun priceParentFromAnotherGenerationRemainsUnproven() = runTest {
        seedOldMismatchGeneration()
        val fixture = retainedPriceForDeletedProductFixture()
        val price = (fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices)
            .values.single().copy(productId = OLD_MANIFEST_PRODUCT)
        remote = RecoveryRemoteFixture(
            fixture.copy(
                checkpoint = fixture.checkpoint.copy(
                    prices = checkpointDomain(listOf(price.id), listOf(testPriceVersion(price)))
                ),
                rows = fixture.rows + (ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(listOf(price)))
            )
        )

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.RetryRequired)
        assertEquals("recovery_price_parent_manifest_missing", (result as ShopSyncRecoveryResult.RetryRequired).code)
        assertOldGenerationAndManifestIntact()
        assertNull(db.syncRecoveryBaselineDao().get())
        assertNotNull(db.syncRecoveryJournalDao().get())
        assertFalse(stageFiles().any())
    }

    @Test
    fun checkpointBTombstonedParentRetainsExistingAndTailPricesOnlyInLedger() = runTest {
        val deviceBefore = seedOldMismatchGeneration()
        val fixture = remote.fixture
        val deletedFixture = deletedProductImageFixture()
        val deletedProducts = deletedFixture.rows.getValue(ShopSyncRowDomain.PRODUCTS)
        val deletedProduct = (deletedProducts as ShopSyncRows.Products).values.single()
        val priceA = (fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values.single()
        val priceB = priceA.copy(
            id = "20000000-0000-4000-8000-000000000007",
            effectiveAt = "2026-07-21 10:00:01",
            createdAt = "2026-07-21 10:00:01",
            updatedAt = "2026-07-21T10:00:03.000000Z"
        )
        val pricesB = checkpointDomain(
            listOf(priceA.id, priceB.id),
            listOf(testPriceVersion(priceA), testPriceVersion(priceB))
        )
        val catalogB = fixture.checkpoint.catalog.copy(
            products = deletedFixture.checkpoint.catalog.products,
            digest = testSha256(
                fixture.checkpoint.catalog.suppliers.versionDigest + "\n" +
                    fixture.checkpoint.catalog.categories.versionDigest + "\n" +
                    deletedFixture.checkpoint.catalog.products.versionDigest
            )
        )
        val checkpointB = fixture.checkpoint.copy(
            syncEvents = fixture.checkpoint.syncEvents.copy(
                maxId = "44",
                domainMaxIds = fixture.checkpoint.syncEvents.domainMaxIds +
                    (SyncEventDomains.CATALOG to "43") + (SyncEventDomains.PRICES to "44")
            ),
            catalog = catalogB,
            prices = pricesB,
            images = deletedFixture.checkpoint.images,
            checkpointDigest = "5".repeat(64)
        )
        remote.checkpoints = mutableListOf(fixture.checkpoint, checkpointB)
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 43L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.CATALOG,
                eventType = SyncEventTypes.CATALOG_TOMBSTONE,
                changedCount = 1,
                entityIds = SyncEventEntityIds(productIds = listOf(deletedProduct.id)),
                createdAt = requireNotNull(deletedProduct.updatedAt)
            ),
            SyncEventRemoteRow(
                id = 44L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.PRICES,
                eventType = SyncEventTypes.PRICES_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(priceIds = listOf(priceB.id), productIds = listOf(deletedProduct.id)),
                createdAt = requireNotNull(priceB.updatedAt)
            )
        )
        remote.tailRows = fixture.rows + (ShopSyncRowDomain.PRODUCTS to deletedProducts) +
            (ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(listOf(priceA, priceB))) +
            (ShopSyncRowDomain.IMAGES to deletedFixture.rows.getValue(ShopSyncRowDomain.IMAGES))

        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertEquals(pricesB, decodeRecoveryCheckpointJson(baseline.checkpointJson).prices)
        val prices = db.syncRecoveryManifestDao().page(baseline.generationId, ShopSyncRowDomain.PRICES.wireValue, null, 10)
        assertEquals(listOf(priceA.id, priceB.id), prices.map { it.remoteId })
        assertTrue(prices.all { it.active && it.payloadDigest != null })
        assertEquals(listOf(testPriceVersion(priceA), testPriceVersion(priceB)), prices.map { it.versionLine })
        assertEquals(0, db.productDao().getAll().size)
        assertEquals(0, db.productPriceDao().countAll())
        assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
        assertEquals(44L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)

        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)

        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(prices, db.syncRecoveryManifestDao().page(baseline.generationId, ShopSyncRowDomain.PRICES.wireValue, null, 10))
        assertEquals(0, db.productPriceDao().countAll())
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun retainedPriceCleanupResumeVerifiesFreshStoreWithoutRedownloading() = runTest {
        val deviceBefore = seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(retainedPriceForDeletedProductFixture())
        val first = coordinator(onActivated = { error("fixture_cleanup_failure") })
            .recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(first.toString(), first is ShopSyncRecoveryResult.RetryRequired)
        assertEquals(SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING, db.syncRecoveryJournalDao().get()?.phase)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val pagesAfterActivation = remote.pageCalls
        val checkpointsAfterActivation = remote.checkpointCalls

        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)

        val resumed = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(resumed.toString(), resumed is ShopSyncRecoveryResult.Activated)
        assertEquals(pagesAfterActivation, remote.pageCalls)
        assertEquals(checkpointsAfterActivation, remote.checkpointCalls)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(remote.fixture.checkpoint.prices, decodeRecoveryCheckpointJson(baseline.checkpointJson).prices)
        assertEquals(1, db.syncRecoveryManifestDao().count(baseline.generationId, ShopSyncRowDomain.PRICES.wireValue))
        assertEquals(0, db.productPriceDao().countAll())
        assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
        assertFalse(stageFiles().any())
    }

    @Test
    fun retainedPriceRecoveryReachesOrdinaryNoWorkWithoutRelatching() = runTest {
        seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(retainedPriceForDeletedProductFixture())
        val price = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values.single()
        // The ordinary event page contains no events newer than the published baseline.
        remote.tailEvents = listOf(
            SyncEventRemoteRow(
                id = 42L,
                ownerUserId = ACCOUNT,
                shopId = SHOP,
                domain = SyncEventDomains.PRICES,
                eventType = SyncEventTypes.PRICES_CHANGED,
                changedCount = 1,
                entityIds = SyncEventEntityIds(priceIds = listOf(price.id), productIds = listOf(price.productId)),
                createdAt = requireNotNull(price.updatedAt)
            )
        )
        val recovered = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = remote)

        val summary = repository.drainSyncEventsFromRemote(
            remote = NoOpCatalogRemoteForRecoveryTest,
            priceRemote = NoOpPriceRemoteForRecoveryTest,
            syncEventRemote = NoOpSyncEventRemoteForRecoveryTest,
            ownerUserId = ACCOUNT,
            progressReporter = CatalogSyncProgressReporter { },
            selectedShop = selectedShop()
        ).getOrThrow()

        assertEquals(0, summary.syncEventsFetched)
        assertEquals(0, summary.syncEventsProcessed)
        assertEquals(42L, summary.syncEventsWatermarkAfter)
        assertFalse(summary.manualFullSyncRequired)
        assertFalse(summary.syncEventsGapDetected)
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(remote.fixture.checkpoint.prices, decodeRecoveryCheckpointJson(baseline.checkpointJson).prices)
        assertEquals(1, db.syncRecoveryManifestDao().count(baseline.generationId, ShopSyncRowDomain.PRICES.wireValue))
        assertEquals(0, db.productPriceDao().countAll())
    }

    @Test
    fun mixedRetainedPriceLedgerCrossesRawAndPhysicalPageBoundaries() = runTest {
        seedOldMismatchGeneration()
        val fixture = mixedRetainedPriceFixture()
        remote = RecoveryRemoteFixture(fixture)
        val pagedRemote = PagedRecoveryRemoteFixture(remote)

        val result = coordinator(recoveryRemote = pagedRemote).recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertEquals(6, remote.requestedPageLimits[ShopSyncRowDomain.PRICES]?.size)
        assertTrue(remote.requestedPageLimits[ShopSyncRowDomain.PRICES].orEmpty().all { it == 120 })
        assertEquals(560, db.productPriceDao().countAll())
        assertEquals(1, db.productDao().getAll().size)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertEquals(fixture.checkpoint.prices, decodeRecoveryCheckpointJson(baseline.checkpointJson).prices)
        assertEquals(655, db.syncRecoveryManifestDao().count(baseline.generationId, ShopSyncRowDomain.PRICES.wireValue))
        val allPrices = (fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values
        assertNull(db.productPriceRemoteRefDao().getByRemoteId(allPrices.first().id))
        assertNotNull(db.productPriceRemoteRefDao().getByRemoteId(allPrices[95].id))
        assertNotNull(db.productPriceRemoteRefDao().getByRemoteId(allPrices.last().id))
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)

        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)

        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(560, db.productPriceDao().countAll())
        assertEquals(655, db.syncRecoveryManifestDao().count(baseline.generationId, ShopSyncRowDomain.PRICES.wireValue))
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun ordinaryCatalogDeltaAfterActualRecoveryKeepsReadyAndSecondTriggerNoWork() = runTest {
        val deviceBefore = seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baselineBefore = requireNotNull(db.syncRecoveryBaselineDao().get())
        val bindingBefore = db.businessDataScopeBindingDao().get()
        val fullPagesBefore = remote.pageCalls
        val original = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.single()
        val updated = original.copy(
            productName = "Ordinary updated product",
            stockQuantity = 9.0,
            updatedAt = "2026-07-21T11:00:00.000000Z"
        )
        val delta = ordinaryDeltaFixture(
            remote.fixture,
            products = listOf(updated),
            maxId = 43L,
            domainMaxIds = mapOf(SyncEventDomains.CATALOG to "43")
        )
        remote.checkpoints += delta.checkpoint
        remote.tailRows = delta.rows
        remote.tailEvents = listOf(
            ordinaryEvent(43L, SyncEventDomains.CATALOG, SyncEventTypes.CATALOG_CHANGED,
                SyncEventEntityIds(productIds = listOf(updated.id)))
        )
        val reader = OrdinaryFencedReadFixture(remote)
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(ACCOUNT, "ordinary@example.test"))
        val deviceRemote = ActiveShopDeviceRemoteForOrdinaryTest(SHOP)
        val recoveryTriggers = mutableListOf<String>()
        val auto = ordinaryAutoSync(reader, tracker, auth, deviceRemote, backgroundScope, recoveryTriggers)
        try {
            auto.runSyncEventDrainCycle("ordinary_catalog_delta")

            val summary = requireNotNull(tracker.lastOutcome.value).summary
            assertEquals(1, summary.syncEventsFetched)
            assertEquals(1, summary.syncEventsProcessed)
            assertEquals(1, summary.targetedProductsFetched)
            assertEquals(43L, summary.syncEventsWatermarkAfter)
            val physical = db.productDao().getAll().single()
            assertEquals(updated.productName, physical.productName)
            assertEquals(requireNotNull(updated.stockQuantity), requireNotNull(physical.stockQuantity), 0.0)
            assertTrue(reader.targetedContexts.any { it.expectedDomainEventMaxId == "43" })
            assertTrue(
                "ordinary delta incorrectly required recovery: reason=${db.syncRecoveryJournalDao().get()?.reason}, summary=$summary",
                !summary.manualFullSyncRequired
            )
            assertEquals(Task126BusinessDataScopeStatus.READY, tracker.businessDataScopeState.value.status)
            assertNull(db.syncRecoveryJournalDao().get())
            assertTrue(recoveryTriggers.isEmpty())
            assertOrdinaryPublishedReceipt(baselineBefore.generationId, delta.checkpoint, 43L)
            val productManifest = requireNotNull(db.syncRecoveryManifestDao().get(
                baselineBefore.generationId, ShopSyncRowDomain.PRODUCTS.wireValue, updated.id
            ))
            assertEquals(testProductVersion(updated), productManifest.versionLine)
            assertNotNull(productManifest.payloadDigest)
            assertEquals(bindingBefore, db.businessDataScopeBindingDao().get())
            assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
            assertEquals(0, db.syncEventOutboxDao().countAll())
            assertEquals(AuthState.SignedIn(ACCOUNT, "ordinary@example.test"), auth.value)
            assertEquals(fullPagesBefore, remote.pageCalls)
            assertEquals(1, deviceRemote.statusCalls)
            assertForeignKeysClean(db)

            val targetedBefore = reader.targetedContexts.size
            auto.runSyncEventDrainCycle("ordinary_catalog_second_trigger")

            val noWork = requireNotNull(tracker.lastOutcome.value).summary
            assertEquals(0, noWork.syncEventsFetched)
            assertEquals(0, noWork.syncEventsProcessed)
            assertEquals(43L, noWork.syncEventsWatermarkAfter)
            assertFalse(noWork.manualFullSyncRequired)
            assertFalse(noWork.syncEventsGapDetected)
            assertEquals(targetedBefore, reader.targetedContexts.size)
            assertEquals(Task126BusinessDataScopeStatus.READY, tracker.businessDataScopeState.value.status)
            assertNull(db.syncRecoveryJournalDao().get())
            assertTrue(recoveryTriggers.isEmpty())
            assertEquals(fullPagesBefore, remote.pageCalls)
            assertOrdinaryPublishedReceipt(baselineBefore.generationId, delta.checkpoint, 43L)
        } finally {
            auto.shutdown()
        }
    }

    @Test
    fun ordinaryRetainedPriceForProvenTombstoneKeepsCompleteLedgerAndReady() = runTest {
        val deviceBefore = seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(smallMixedRetainedPriceFixture())
        val baselineBefore = requireNotNull(db.syncRecoveryBaselineDao().get())
        val bindingBefore = db.businessDataScopeBindingDao().get()
        val fullPagesBefore = remote.pageCalls
        val delta = ordinaryRetainedPriceDeltaFixture(remote.fixture)
        val newPrice = (delta.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values.last()
        val tombstone = (delta.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
            .values.single { it.deletedAt != null }
        remote.checkpoints += delta.checkpoint
        remote.tailRows = delta.rows
        remote.tailEvents = ordinaryRetainedPriceEvents(newPrice, tombstone)
        val reader = OrdinaryFencedReadFixture(remote)
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(ACCOUNT, "ordinary@example.test"))
        val deviceRemote = ActiveShopDeviceRemoteForOrdinaryTest(SHOP)
        val recoveryTriggers = mutableListOf<String>()
        val auto = ordinaryAutoSync(reader, tracker, auth, deviceRemote, backgroundScope, recoveryTriggers)
        try {
            auto.runSyncEventDrainCycle("ordinary_retained_price")

            val summary = requireNotNull(tracker.lastOutcome.value).summary
            assertEquals(1, summary.targetedPricesFetched)
            assertTrue(reader.targetedContexts.any { it.expectedDomainEventMaxId == "43" })
            assertTrue(reader.materializedFences.any { it.first == ShopSyncRowDomain.PRODUCTS && it.second == "44" })
            assertNull(db.productRemoteRefDao().getByRemoteId(newPrice.productId))
            assertNull(db.productPriceRemoteRefDao().getByRemoteId(newPrice.id))
            assertEquals(1, db.productPriceDao().countAll())
            assertTrue(
                "known tombstone price incorrectly required recovery: reason=${db.syncRecoveryJournalDao().get()?.reason}, summary=$summary",
                !summary.manualFullSyncRequired
            )
            assertEquals(2, summary.syncEventsProcessed)
            assertEquals(44L, summary.syncEventsWatermarkAfter)
            assertEquals(Task126BusinessDataScopeStatus.READY, tracker.businessDataScopeState.value.status)
            assertNull(db.syncRecoveryJournalDao().get())
            assertTrue(recoveryTriggers.isEmpty())
            assertOrdinaryPublishedReceipt(baselineBefore.generationId, delta.checkpoint, 44L)
            val manifests = db.syncRecoveryManifestDao().page(
                baselineBefore.generationId, ShopSyncRowDomain.PRICES.wireValue, null, 10
            )
            assertEquals(3, manifests.size)
            assertTrue(manifests.all { it.active && it.payloadDigest != null })
            assertEquals(testPriceVersion(newPrice), manifests.single { it.remoteId == newPrice.id }.versionLine)
            assertEquals(bindingBefore, db.businessDataScopeBindingDao().get())
            assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
            assertEquals(0, db.syncEventOutboxDao().countAll())
            assertEquals(AuthState.SignedIn(ACCOUNT, "ordinary@example.test"), auth.value)
            assertEquals(fullPagesBefore, remote.pageCalls)
            assertForeignKeysClean(db)

            auto.runSyncEventDrainCycle("ordinary_retained_price_second_trigger")

            val noWork = requireNotNull(tracker.lastOutcome.value).summary
            assertEquals(0, noWork.syncEventsFetched)
            assertFalse(noWork.manualFullSyncRequired)
            assertEquals(44L, noWork.syncEventsWatermarkAfter)
            assertEquals(Task126BusinessDataScopeStatus.READY, tracker.businessDataScopeState.value.status)
            assertNull(db.syncRecoveryJournalDao().get())
            assertTrue(recoveryTriggers.isEmpty())
            assertEquals(fullPagesBefore, remote.pageCalls)
        } finally {
            auto.shutdown()
        }
    }

    @Test
    fun ordinaryPriceWithUnprovenTargetedParentRejectsAndPreservesPublishedBaseline() = runTest {
        val deviceBefore = seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(smallMixedRetainedPriceFixture())
        val baselineBefore = requireNotNull(db.syncRecoveryBaselineDao().get())
        val bindingBefore = db.businessDataScopeBindingDao().get()
        val fullPagesBefore = remote.pageCalls
        val delta = ordinaryRetainedPriceDeltaFixture(remote.fixture)
        val prices = (delta.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values
        val canonicalPrice = prices.last()
        val tombstone = (delta.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
            .values.single { it.deletedAt != null }
        val unproven = canonicalPrice.copy(productId = "20000000-0000-4000-8000-000000000019")
        remote.checkpoints += delta.checkpoint
        // Negative fault injection: the checkpoint declares the known parent,
        // but targeted material corrupts that relation and its parent is absent.
        remote.tailRows = delta.rows + (ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(prices.dropLast(1) + unproven))
        remote.tailEvents = ordinaryRetainedPriceEvents(canonicalPrice, tombstone)
        val reader = OrdinaryFencedReadFixture(remote)
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(ACCOUNT, "ordinary@example.test"))
        val deviceRemote = ActiveShopDeviceRemoteForOrdinaryTest(SHOP)
        val recoveryTriggers = mutableListOf<String>()
        val auto = ordinaryAutoSync(reader, tracker, auth, deviceRemote, backgroundScope, recoveryTriggers)
        try {
            auto.runSyncEventDrainCycle("ordinary_unknown_price_parent")

            val summary = requireNotNull(tracker.lastOutcome.value).summary
            assertTrue(summary.manualFullSyncRequired)
            assertTrue(summary.syncEventsGapDetected)
            assertEquals(42L, summary.syncEventsWatermarkAfter)
            assertEquals(SyncEventApplyStatusReasons.MISSING_REMOTE, db.syncRecoveryJournalDao().get()?.reason)
            assertEquals(SyncRecoveryJournalPhases.REQUIRED, db.syncRecoveryJournalDao().get()?.phase)
            assertEquals(Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, tracker.businessDataScopeState.value.status)
            assertEquals(listOf("sync_events_drain"), recoveryTriggers)
            assertEquals(baselineBefore, db.syncRecoveryBaselineDao().get())
            assertNull(db.syncRecoveryManifestDao().get(
                baselineBefore.generationId, ShopSyncRowDomain.PRICES.wireValue, canonicalPrice.id
            ))
            assertNull(db.productPriceRemoteRefDao().getByRemoteId(canonicalPrice.id))
            assertEquals(1, db.productPriceDao().countAll())
            assertEquals(bindingBefore, db.businessDataScopeBindingDao().get())
            assertEquals(deviceBefore, db.syncEventDeviceStateDao().get())
            assertEquals(0, db.syncEventOutboxDao().countAll())
            assertEquals(AuthState.SignedIn(ACCOUNT, "ordinary@example.test"), auth.value)
            assertEquals(fullPagesBefore, remote.pageCalls)
            assertTrue(remote.tailTargetedRequests.any { it.first == ShopSyncRowDomain.PRODUCTS && unproven.productId in it.second })
            assertForeignKeysClean(db)
        } finally {
            auto.shutdown()
        }
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
        assertEquals(SyncRecoveryJournalPhases.STAGING, db.syncRecoveryJournalDao().get()?.phase)
        assertEquals(1, stageFiles().count())
        assertEquals(checkpointA.checkpointDigest, db.syncRecoveryJournalDao().get()?.checkpointADigest)
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
        logger: (String) -> Unit = {},
        registerDeviceForRecovery: suspend (String) -> Result<ShopDeviceRegistrationResult> = { shopId ->
            Result.success(ShopDeviceRegistrationResult(ok = true, code = "success", shopId = shopId))
        },
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
            { context, name -> context.deleteDatabase(name) },
        recoveryRemote: ShopSyncReadRemoteDataSource = remote
    ): ShopSyncRecoveryCoordinator = ShopSyncRecoveryCoordinator(
        context = app,
        activeDb = db,
        activeRepository = repository,
        remote = recoveryRemote,
        registerDeviceForRecovery = registerDeviceForRecovery,
        logger = logger,
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

    @Test
    fun ordinaryCommitCancellationRollsBackPhysicalLedgerReceiptAndWatermark() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.productDao().getAll().single()
        val delta = prepareOrdinaryCatalogDelta()
        var observedWrites = false
        DefaultInventoryRepositoryTestHooks.afterOrdinaryShopSyncWrites = {
            assertEquals("R-A10 adjacent product", db.productDao().getAll().single().productName)
            assertEquals(testProductVersion((delta.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.single()),
                db.syncRecoveryManifestDao().page(baseline.generationId, ShopSyncRowDomain.PRODUCTS.wireValue, null, 10).single().versionLine)
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            assertEquals(42L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
            observedWrites = true
            throw CancellationException("ordinary_sql_cancel")
        }
        try {
            val failure = runCatching { drainOrdinary(OrdinaryFencedReadFixture(remote)) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is CancellationException)
            assertTrue(observedWrites)
        } finally {
            DefaultInventoryRepositoryTestHooks.afterOrdinaryShopSyncWrites = null
        }
        reopenOrdinaryDatabase()
        assertEquals(original, db.productDao().getAll().single())
        assertOrdinaryUnchangedPublication(baseline)
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        assertNull(db.syncEventApplyStatusDao().get(ACCOUNT, activeScope().storeId, 43L))
    }

    @Test
    fun ordinarySqlPublicationAbortRollsBackEarlierPhysicalAndLedgerWrites() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.productDao().getAll().single()
        prepareOrdinaryCatalogDelta()
        db.openHelper.writableDatabase.execSQL("""
            CREATE TEMP TRIGGER ra10_abort_publication BEFORE INSERT ON sync_recovery_baseline
            BEGIN SELECT RAISE(ABORT, 'ra10_sql_publish_abort'); END
        """.trimIndent())
        val failed = drainOrdinary(OrdinaryFencedReadFixture(remote))
        assertTrue(failed.toString(), failed.isFailure)
        reopenOrdinaryDatabase()
        assertEquals(original, db.productDao().getAll().single())
        assertOrdinaryUnchangedPublication(baseline)
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        assertNull(db.syncEventApplyStatusDao().get(ACCOUNT, activeScope().storeId, 43L))
    }

    @Test
    fun ordinaryCapturedEntireBaselineEntityRacePreservesNewerPublication() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val original = db.productDao().getAll().single()
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val newer = baseline.copy(activatedAtMs = baseline.activatedAtMs + 1L)
        prepareOrdinaryCatalogDelta()
        val source = OrdinaryFencedReadFixture(remote)
        val racing = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                db.syncRecoveryBaselineDao().upsert(newer)
                return source.convergenceMarker(context)
            }
        }
        val failed = drainOrdinary(racing)
        assertEquals("ordinary_captured_publication_changed", (failed.exceptionOrNull() as? ShopSyncContractException)?.code)
        assertEquals(newer, db.syncRecoveryBaselineDao().get())
        assertEquals(original, db.productDao().getAll().single())
        assertOrdinaryUnchangedPublication(newer)
        validateShopSyncActiveReceipt(db, newer.generationId, decodeRecoveryCheckpointJson(newer.checkpointJson))
    }

    @Test
    fun ordinaryGenerationRacePreservesNewGenerationAndRejectsPreparedOldRows() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val original = db.productDao().getAll().single()
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val newer = baseline.copy(generationId = "70000000-0000-4000-8000-000000000001")
        prepareOrdinaryCatalogDelta()
        val source = OrdinaryFencedReadFixture(remote)
        val racing = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                db.withTransaction {
                    for (domain in ShopSyncRowDomain.entries) {
                        val rows = db.syncRecoveryManifestDao().page(baseline.generationId, domain.wireValue, null, 10)
                        db.syncRecoveryManifestDao().upsertAll(rows.map { it.copy(generationId = newer.generationId) })
                    }
                    db.syncRecoveryBaselineDao().upsert(newer)
                }
                return source.convergenceMarker(context)
            }
        }
        val failed = drainOrdinary(racing)
        assertEquals("ordinary_captured_publication_changed", (failed.exceptionOrNull() as? ShopSyncContractException)?.code)
        assertEquals(original, db.productDao().getAll().single())
        assertOrdinaryUnchangedPublication(newer)
        validateShopSyncActiveReceipt(db, newer.generationId, decodeRecoveryCheckpointJson(newer.checkpointJson))
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
    }

    @Test
    fun ordinaryPendingHistoryDefersWithoutJournalAndAllowsRealPush() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(v2HistoryFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val history = db.historyEntryDao().getAllUserVisibleSnapshot().single()
        repository.deleteHistoryEntry(history)
        val dirtyRef = requireNotNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(history.uid))
        assertTrue(dirtyRef.localChangeRevision > dirtyRef.lastSyncedLocalRevision)
        prepareOrdinaryCatalogDelta()
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(ACCOUNT, "ordinary@example.test"))
        val triggers = mutableListOf<String>()
        val auto = ordinaryAutoSync(OrdinaryFencedReadFixture(remote), tracker, auth,
            ActiveShopDeviceRemoteForOrdinaryTest(SHOP), backgroundScope, triggers)
        try {
            auto.runSyncEventDrainCycle("ordinary_local_history_pending")
            val summary = requireNotNull(tracker.lastOutcome.value).summary
            assertTrue(summary.syncEventsSkippedDirtyLocal > 0)
            assertEquals(0, summary.syncEventsProcessed)
            assertFalse(summary.manualFullSyncRequired)
            assertEquals(Task126BusinessDataScopeStatus.READY, tracker.businessDataScopeState.value.status)
            assertTrue(triggers.isEmpty())
            assertOrdinaryUnchangedPublication(baseline)
            assertEquals(dirtyRef, db.historyEntryRemoteRefDao().getByHistoryEntryUid(history.uid))
            val pushed = mutableListOf<SharedSheetSessionUpsertRow>()
            val pushRemote = object : SessionBackupRemoteDataSource {
                override val isConfigured = true
                override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> = error("no pull")
                override suspend fun fetchSessionsByRemoteIds(remoteIds: Set<String>): Result<List<SharedSheetSessionRecord>> = error("no pull")
                override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> {
                    pushed += rows
                    return Result.success(Unit)
                }
            }
            val pushedSummary = tracker.withBusinessDataScopeFlight(ACCOUNT, selectedShop()) {
                repository.pushHistorySessionsToRemote(pushRemote, ACCOUNT, setOf(history.uid), selectedShop()).getOrThrow()
            }
            assertEquals(1, pushedSummary.uploaded)
            assertEquals(1, pushed.size)
            assertNotNull(pushed.single().deletedAt)
            val cleanRef = requireNotNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(history.uid))
            assertEquals(cleanRef.localChangeRevision, cleanRef.lastSyncedLocalRevision)
            assertEquals(Task126BusinessDataScopeStatus.READY, tracker.businessDataScopeState.value.status)
            assertNull(db.syncRecoveryJournalDao().get())
        } finally {
            auto.shutdown()
        }
    }

    @Test
    fun ordinaryPendingInsertedAfterLastRpcDefersBeforeAnyPublication() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(v2HistoryFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.productDao().getAll().single()
        val history = db.historyEntryDao().getAllUserVisibleSnapshot().single()
        prepareOrdinaryCatalogDelta()
        val source = OrdinaryFencedReadFixture(remote)
        val racing = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                val marker = source.convergenceMarker(context)
                repository.deleteHistoryEntry(history)
                return marker
            }
        }
        val summary = drainOrdinary(racing).getOrThrow()
        assertTrue(summary.syncEventsSkippedDirtyLocal > 0)
        assertFalse(summary.manualFullSyncRequired)
        assertEquals(original, db.productDao().getAll().single())
        assertOrdinaryUnchangedPublication(baseline)
        assertNotNull(db.historyEntryDao().getByUid(history.uid)?.deletedAt)
        val dirty = requireNotNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(history.uid))
        assertTrue(dirty.localChangeRevision > dirty.lastSyncedLocalRevision)
    }

    @Test
    fun ordinaryHistoryTombstoneRetainsOldBodyAndProvesShadowAcrossReopenAndNextDelta() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(v2HistoryFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.historyEntryDao().getAllUserVisibleSnapshot().single()
        val wire = (remote.fixture.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History).values.single()
        val tomb = wire.copy(deletedAt = "2026-07-21T11:00:00.000000Z", updatedAt = "2026-07-21T11:00:00.000000Z",
            data = listOf(listOf("remote tombstone old body deliberately differs")), sessionOverlay = null)
        val delta = ordinaryHistoryDeltaFixture(remote.fixture, tomb)
        remote.checkpoints += delta.checkpoint
        remote.tailRows = delta.rows
        remote.tailEvents = listOf(ordinaryEvent(43L, SyncEventDomains.HISTORY, SyncEventTypes.HISTORY_TOMBSTONE,
            SyncEventEntityIds(sessionIds = listOf(tomb.remoteId))))
        assertFalse(drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow().manualFullSyncRequired)
        val retained = requireNotNull(db.historyEntryDao().getByUid(original.uid))
        assertEquals(original.data, retained.data)
        assertEquals(original.editable, retained.editable)
        assertEquals(tomb.deletedAt, retained.deletedAt)
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 43L)
        val manifest = requireNotNull(db.syncRecoveryManifestDao().get(baseline.generationId, ShopSyncRowDomain.HISTORY.wireValue, tomb.remoteId))
        assertFalse(manifest.active)
        assertNull(manifest.payloadDigest)
        assertEquals(5, manifest.versionLine.split('\u001f').size)
        reopenOrdinaryDatabase()
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson))
        val products = (delta.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values
        val next = ordinaryDeltaFixture(delta, products.map { it.copy(productName = "after shadow", updatedAt = "2026-07-21T12:00:00.000000Z") },
            44L, mapOf(SyncEventDomains.CATALOG to "44"))
        remote.checkpoints += next.checkpoint
        remote.tailRows = next.rows
        remote.tailEvents = listOf(ordinaryEvent(44L, SyncEventDomains.CATALOG, SyncEventTypes.CATALOG_CHANGED,
            SyncEventEntityIds(productIds = products.map { it.id })))
        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertFalse(summary.manualFullSyncRequired)
        assertEquals(44L, summary.syncEventsWatermarkAfter)
        assertEquals(original.data, db.historyEntryDao().getByUid(original.uid)?.data)
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun ordinarySyncedHistoryOrphanShadowRejectsInsteadOfHidingOrDeferring() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.productDao().getAll().single()
        val template = db.historyEntryDao().getAllUserVisibleSnapshot().single()
        val orphanUid = db.historyEntryDao().insert(template.copy(uid = 0L, id = "APPLY_IMPORT_R_A10_ORPHAN_SHADOW",
            deletedAt = "2026-07-21T11:00:00.000000Z", syncStatus = SyncStatus.SYNCED_SUCCESSFULLY))
        assertNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(orphanUid))
        prepareOrdinaryCatalogDelta()
        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertTrue(summary.manualFullSyncRequired)
        assertEquals(0, summary.syncEventsSkippedDirtyLocal)
        assertEquals(original, db.productDao().getAll().single())
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(42L, summary.syncEventsWatermarkAfter)
        assertEquals(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED, db.syncRecoveryJournalDao().get()?.reason)
    }

    @Test
    fun ordinaryParentRestoreFetchesRetainedPriceBodiesAtActualPriceFence() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(smallMixedRetainedPriceFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val delta = prepareOrdinaryRestoreDelta()
        val reader = OrdinaryFencedReadFixture(remote)
        val summary = drainOrdinary(reader).getOrThrow()
        assertFalse(summary.manualFullSyncRequired)
        assertEquals(43L, summary.syncEventsWatermarkAfter)
        assertEquals(2, db.productDao().count())
        assertEquals(2, db.productPriceDao().countAll())
        assertTrue(reader.materializedFences.any { it.first == ShopSyncRowDomain.PRICES && it.second == "42" })
        assertTrue(reader.targetedContexts.any { it.expectedDomainEventMaxId == "42" })
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 43L)
        reopenOrdinaryDatabase()
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson))
        assertFalse(drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow().manualFullSyncRequired)
    }

    @Test
    fun ordinaryParentRestoreWithCorruptRetainedPriceBodyRollsBack() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(smallMixedRetainedPriceFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        prepareOrdinaryRestoreDelta()
        val prices = (requireNotNull(remote.tailRows)[ShopSyncRowDomain.PRICES] as ShopSyncRows.Prices).values
        remote.tailRows = requireNotNull(remote.tailRows) + (ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(
            prices.map { if (it.productId == "20000000-0000-4000-8000-000000000009") it.copy(price = it.price + 1.0, priceCanonical = java.math.BigDecimal(requireNotNull(it.priceCanonical))
                    .add(java.math.BigDecimal.ONE).stripTrailingZeros().toPlainString()) else it }))
        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertTrue(summary.manualFullSyncRequired)
        assertEquals(42L, summary.syncEventsWatermarkAfter)
        assertEquals(1, db.productDao().count())
        assertEquals(1, db.productPriceDao().countAll())
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
    }

    @Test
    fun ordinaryPersistsOriginalOpaqueServerCDigestAfterFullMaterialProof() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val delta = prepareOrdinaryCatalogDelta()
        remote.markerTransform = { it.copy(checkpointDigest = "6".repeat(64)) }
        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertFalse(summary.manualFullSyncRequired)
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint.copy(checkpointDigest = "6".repeat(64)), 43L)
        assertEquals(delta.checkpoint.catalog, decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson).catalog)
    }

    @Test
    fun ordinaryHistoryShadowScanIncludesNegativeUidOrphans() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val history = db.historyEntryDao().getAllUserVisibleSnapshot().single()
        db.historyEntryDao().insert(history.copy(uid = -1L, id = "APPLY_IMPORT_NEGATIVE_UID_SHADOW",
            deletedAt = "2026-07-21T11:00:00.000000Z", syncStatus = SyncStatus.SYNCED_SUCCESSFULLY))
        val failure = runCatching {
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        }.exceptionOrNull()
        assertEquals("ordinary_history_shadow_ref_invalid", (failure as? ShopSyncContractException)?.code)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
    }

    @Test
    fun ordinaryHistoryShadowScanCoversUnknownBridgeBeyondFiveHundredAndRejectsInvalidUtc() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(v2HistoryFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val history = db.historyEntryDao().getAllUserVisibleSnapshot().single()
        val activeWire = (remote.fixture.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History).values.single()
        val tombstones = (1..501).map { index -> activeWire.copy(
            remoteId = "60000000-0000-4000-8000-" + index.toString().padStart(12, '0'),
            deletedAt = "2026-07-21T11:00:00.000000Z", updatedAt = "2026-07-21T11:00:00.000000Z") }
        db.withTransaction {
            db.syncRecoveryManifestDao().upsertAll(ShopSyncRows.History(tombstones).toManifestRows(baseline.generationId, ShopSyncRowDomain.HISTORY))
            tombstones.forEach { wire ->
                val uid = db.historyEntryDao().insert(history.copy(uid = 0L, id = "retained old file " + wire.remoteId,
                    deletedAt = wire.deletedAt, data = listOf(listOf("body is intentionally unproved"))))
                db.historyEntryRemoteRefDao().insert(HistoryEntryRemoteRef(historyEntryUid = uid, remoteId = wire.remoteId,
                    lastRemoteAppliedAt = 1L, lastRemotePayloadFingerprint = "old-body-fingerprint"))
            }
        }
        val checkpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson).copy(history = checkpointDomain(
            (listOf(activeWire) + tombstones).map { it.remoteId }, (listOf(activeWire) + tombstones).map(::testHistoryVersion),
            activeCount = 1L, tombstoneCount = 501L))
        validateShopSyncActiveReceipt(db, baseline.generationId, checkpoint)
        val lastRef = requireNotNull(db.historyEntryRemoteRefDao().getByRemoteId(tombstones.last().remoteId))
        val last = requireNotNull(db.historyEntryDao().getByUid(lastRef.historyEntryUid))
        db.historyEntryRemoteRefDao().insert(lastRef.copy(id = 0L, historyEntryUid = db.historyEntryDao().insert(
            last.copy(uid = 0L, id = "unknown final shadow")), remoteId = "60000000-0000-4000-8000-000000000999"))
        val unknown = runCatching { validateShopSyncActiveReceipt(db, baseline.generationId, checkpoint) }.exceptionOrNull()
        assertEquals("ordinary_history_shadow_manifest_missing", (unknown as? ShopSyncContractException)?.code)
        val firstRef = requireNotNull(db.historyEntryRemoteRefDao().getByRemoteId(tombstones.first().remoteId))
        val first = requireNotNull(db.historyEntryDao().getByUid(firstRef.historyEntryUid))
        db.historyEntryDao().update(first.copy(deletedAt = "2026-02-30T11:00:00.000000Z"))
        val invalid = runCatching { validateShopSyncActiveReceipt(db, baseline.generationId, checkpoint) }.exceptionOrNull()
        assertEquals("ordinary_history_shadow_tombstone_invalid", (invalid as? ShopSyncContractException)?.code)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
    }

    @Test
    fun ordinaryValidEmptyCanonicalBaselineAcceptsCapturedHistoryDelta() = runTest {
        seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(emptyTargetFixture())
        assertTrue(coordinator().recover(ACCOUNT, selectedShop(), activeScope()) is ShopSyncRecoveryResult.Activated)
        reopenOrdinaryDatabase()
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val history = (targetFixture().rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History).values.single()
        val delta = ordinaryHistoryDeltaFixture(remote.fixture, history)
        remote.checkpoints += delta.checkpoint
        remote.tailRows = delta.rows
        remote.tailEvents = listOf(ordinaryEvent(43L, SyncEventDomains.HISTORY, SyncEventTypes.HISTORY_CHANGED,
            SyncEventEntityIds(sessionIds = listOf(history.remoteId))))
        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertFalse(summary.manualFullSyncRequired)
        assertEquals(43L, summary.syncEventsWatermarkAfter)
        assertEquals(0, db.productDao().count())
        assertEquals(1, db.historyEntryDao().countUserVisible())
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 43L)
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun ordinaryUncoveredPhysicalCorruptionRejectsAndRollsBackCoveredProductDelta() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.productDao().getAll().single()
        val history = db.historyEntryDao().getAllUserVisibleSnapshot().single()
        db.historyEntryDao().update(history.copy(supplier = "Uncovered corrupted History"))
        prepareOrdinaryCatalogDelta()
        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertTrue(summary.manualFullSyncRequired)
        assertEquals(42L, summary.syncEventsWatermarkAfter)
        assertEquals(original, db.productDao().getAll().single())
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals("Uncovered corrupted History", db.historyEntryDao().getByUid(history.uid)?.supplier)
        assertEquals(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED, db.syncRecoveryJournalDao().get()?.reason)
    }

    @Test
    fun ordinaryPendingProductRealPushAckThenSelfReceiptPublishesCWithoutRecovery() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.productDao().getAll().single()
        repository.updateProductFromEditor(original, original.copy(productName = "Pending product pushed and acknowledged"))
        val local = requireNotNull(db.productDao().getById(original.id))
        val dirty = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        assertTrue(dirty.localChangeRevision > dirty.lastSyncedLocalRevision)
        val oldProducts = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values
        val external = ordinaryDeltaFixture(remote.fixture, oldProducts, 43L, mapOf(SyncEventDomains.CATALOG to "43"))
        val harmless = ordinaryEvent(43L, SyncEventDomains.CATALOG, SyncEventTypes.CATALOG_CHANGED,
            SyncEventEntityIds()).copy(changedCount = 0)
        remote.checkpoints += external.checkpoint
        remote.tailRows = external.rows
        remote.tailEvents = listOf(harmless)
        val deferred = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertTrue(deferred.syncEventsSkippedDirtyLocal > 0)
        assertFalse(deferred.manualFullSyncRequired)
        assertOrdinaryUnchangedPublication(baseline)
        val pushed = mutableListOf<InventoryProductRow>()
        val patches = mutableListOf<InventoryProductPatch>()
        val pushRemote = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
                pushed += rows
                return Result.success(Unit)
            }
            override suspend fun patchProduct(id: String, ownerUserId: String, patch: InventoryProductPatch): Result<Unit> {
                assertEquals(dirty.remoteId, id)
                assertEquals(ACCOUNT, ownerUserId)
                patches += patch
                return Result.success(Unit)
            }
            override suspend fun patchProduct(
                id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch
            ): Result<Unit> {
                assertEquals(SHOP, shopId)
                return patchProduct(id, ownerUserId, patch)
            }
        }
        val pushedSummary = repository.pushDirtyCatalogDeltaToRemote(pushRemote, NoOpPriceRemoteForRecoveryTest, ACCOUNT,
            CatalogSyncProgressReporter { }, selectedShop()).getOrThrow()
        assertEquals(1, pushedSummary.pushedProducts)
        assertEquals(1, pushed.size + patches.size)
        val acknowledged = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        assertEquals(acknowledged.localChangeRevision, acknowledged.lastSyncedLocalRevision)
        assertEquals(local.productName, db.productDao().getById(original.id)?.productName)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        val received = oldProducts.map { it.copy(productName = local.productName, updatedAt = "2026-07-21T12:00:00.000000Z") }
        val delta = ordinaryDeltaFixture(external, received, 44L, mapOf(SyncEventDomains.CATALOG to "44"))
        remote.checkpoints += delta.checkpoint
        remote.tailRows = delta.rows
        remote.tailEvents = listOf(harmless, ordinaryEvent(44L, SyncEventDomains.CATALOG, SyncEventTypes.CATALOG_CHANGED,
            SyncEventEntityIds(productIds = listOf(dirty.remoteId))).copy(sourceDeviceId = DEVICE))
        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertFalse(summary.manualFullSyncRequired)
        assertEquals(1, summary.syncEventsSkippedSelf)
        assertEquals(44L, summary.syncEventsWatermarkAfter)
        assertEquals(local.productName, db.productDao().getById(original.id)?.productName)
        assertEquals(received.single().updatedAt, db.productRemoteRefDao().getByProductId(original.id)?.remoteUpdatedAt)
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 44L)
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        reopenOrdinaryDatabase()
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson))
        assertFalse(drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow().manualFullSyncRequired)
    }

    @Test
    fun ordinaryNoEventCannotPublishNoWorkOverCleanPhysicalPriceTamper() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        db.openHelper.writableDatabase.execSQL("UPDATE product_prices SET price = price + 0.5")
        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertTrue(summary.manualFullSyncRequired)
        assertTrue(summary.syncEventsGapDetected)
        assertEquals(0, summary.syncEventsFetched)
        assertEquals(42L, summary.syncEventsWatermarkAfter)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED, db.syncRecoveryJournalDao().get()?.reason)
    }

    @Test
    fun ordinaryNoEventLocalUnbridgedPriceDefersWithoutFalseNoWorkOrJournal() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val product = db.productDao().getAll().single()
        db.productPriceDao().insert(ProductPrice(productId = product.id, type = "PURCHASE", price = 5.0,
            effectiveAt = "2026-07-21 12:00:00", createdAt = "2026-07-21 12:00:00"))
        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertTrue(summary.syncEventsSkippedDirtyLocal > 0)
        assertFalse(summary.manualFullSyncRequired)
        assertEquals(0, summary.syncEventsProcessed)
        assertOrdinaryUnchangedPublication(baseline)
        assertEquals(2, db.productPriceDao().countAll())
    }

    @Test
    fun ordinaryActivatedZeroBaselinePublishesFirstEventAtomicallyWithoutRecovery() = runTest {
        val device = seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val binding = db.businessDataScopeBindingDao().get()
        val fullPages = remote.pageCalls
        val delta = prepareFirstZeroHistoryDelta()
        val reader = OrdinaryFencedReadFixture(remote)

        val summary = drainOrdinary(reader).getOrThrow()

        assertTrue("activated C0 must publish event1 without recovery: reason=${db.syncRecoveryJournalDao().get()?.reason}, summary=$summary",
            !summary.manualFullSyncRequired)
        assertEquals(1, summary.syncEventsFetched)
        assertEquals(1, summary.syncEventsProcessed)
        assertEquals(1L, summary.syncEventsWatermarkAfter)
        assertEquals(0, db.productDao().count())
        assertEquals(1, db.historyEntryDao().countUserVisible())
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 1L)
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        assertEquals(binding, db.businessDataScopeBindingDao().get())
        assertEquals(device, db.syncEventDeviceStateDao().get())
        assertEquals(fullPages, remote.pageCalls)
        assertTrue(reader.targetedContexts.any { it.expectedDomainEventMaxId == "1" })
        reopenOrdinaryDatabase()
        validateShopSyncActiveReceipt(db, baseline.generationId,
            decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson))
        val noWork = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertFalse(noWork.manualFullSyncRequired)
        assertEquals(0, noWork.syncEventsFetched)
        assertEquals(1L, noWork.syncEventsWatermarkAfter)
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun ordinaryAutoSyncActivatedZeroBaselineDrainsFirstEventWithoutBootstrapSkip() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val delta = prepareFirstZeroHistoryDelta()
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(ACCOUNT, "ordinary@example.test"))
        val triggers = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val device = ActiveShopDeviceRemoteForOrdinaryTest(SHOP)
        val fullPages = remote.pageCalls
        val auto = ordinaryAutoSync(OrdinaryFencedReadFixture(remote), tracker, auth, device,
            backgroundScope, triggers, logger = { logs += it })
        try {
            auto.runSyncEventDrainCycle("ordinary_first_event_after_activated_zero")

            assertFalse("verified Activated C0 must not schedule catalog bootstrap: $logs",
                logs.any { it.contains("cycle=sync_events_drain outcome=skip reason=bootstrap_required") })
            val summary = requireNotNull(tracker.lastOutcome.value).summary
            assertFalse(summary.manualFullSyncRequired)
            assertEquals(1, summary.syncEventsFetched)
            assertEquals(1L, summary.syncEventsWatermarkAfter)
            assertEquals(1, db.historyEntryDao().countUserVisible())
            assertEquals(Task126BusinessDataScopeStatus.READY, tracker.businessDataScopeState.value.status)
            assertTrue(triggers.isEmpty())
            assertNull(db.syncRecoveryJournalDao().get())
            assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 1L)
            assertEquals(1, device.statusCalls)
            assertEquals(fullPages, remote.pageCalls)
            auto.runSyncEventDrainCycle("ordinary_second_trigger_after_first_zero_event")
            val noWork = requireNotNull(tracker.lastOutcome.value).summary
            assertEquals(0, noWork.syncEventsFetched)
            assertEquals(1L, noWork.syncEventsWatermarkAfter)
            assertFalse(noWork.manualFullSyncRequired)
            assertTrue(triggers.isEmpty())
            assertNull(db.syncRecoveryJournalDao().get())
        } finally {
            auto.shutdown()
        }
    }

    @Test
    fun ordinaryDefaultZeroWithoutActivatedBaselineStillRequiresBootstrap() = runTest {
        val device = SyncEventDeviceState(deviceId = DEVICE, createdAtMs = 139L)
        db.syncEventDeviceStateDao().insert(device)
        db.businessDataScopeBindingDao().upsert(BusinessDataScopeBinding.from(activeScope(), 100L))
        db.syncEventWatermarkDao().upsert(SyncEventWatermark(ACCOUNT, activeScope().storeId, 0L))
        assertNull(db.syncRecoveryBaselineDao().get())
        assertEquals(0, db.syncRecoveryManifestDao().count("absent-generation", ShopSyncRowDomain.PRODUCTS.wireValue))
        assertTrue(repository.shouldRunCatalogBootstrap(ACCOUNT))
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(ACCOUNT, "ordinary@example.test"))
        val triggers = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val deviceRemote = ActiveShopDeviceRemoteForOrdinaryTest(SHOP)
        val auto = ordinaryAutoSync(OrdinaryFencedReadFixture(remote), tracker, auth, deviceRemote,
            backgroundScope, triggers, logger = { logs += it })
        try {
            auto.runSyncEventDrainCycle("ordinary_default_zero_without_receipt")
            assertTrue(logs.any { it.contains("cycle=sync_events_drain outcome=skip reason=bootstrap_required") })
            assertNull(tracker.lastOutcome.value)
            assertEquals(0, remote.checkpointCalls)
            assertEquals(0, deviceRemote.statusCalls)
            assertEquals(0L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
            assertNull(db.syncRecoveryBaselineDao().get())
            assertNull(db.syncRecoveryJournalDao().get())
        } finally {
            auto.shutdown()
        }
    }

    @Test
    fun ordinaryCoveredPhysicalCorruptionWithFingerprintAlreadyCRejectsAndRollsBack() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.productDao().getAll().single()
        val delta = prepareOrdinaryCatalogDelta()
        val received = (delta.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.single()
        val oldManifest = db.syncRecoveryManifestDao().get(baseline.generationId,
            ShopSyncRowDomain.PRODUCTS.wireValue, received.id)
        val ref = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        val corrupted = original.copy(productName = "Covered corruption despite fingerprint already C")
        db.productDao().update(corrupted)
        db.productRemoteRefDao().updateRemoteApplyState(original.id, ref.localChangeRevision, 1_000L,
            fingerprintProductInbound(received), received.updatedAt)
        val apparentC = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        assertEquals(apparentC.localChangeRevision, apparentC.lastSyncedLocalRevision)
        assertEquals(fingerprintProductInbound(received), apparentC.lastRemotePayloadFingerprint)

        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()

        assertTrue(summary.manualFullSyncRequired)
        assertEquals(42L, summary.syncEventsWatermarkAfter)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(oldManifest, db.syncRecoveryManifestDao().get(baseline.generationId,
            ShopSyncRowDomain.PRODUCTS.wireValue, received.id))
        assertEquals(corrupted, db.productDao().getById(original.id))
        assertEquals(apparentC, db.productRemoteRefDao().getByProductId(original.id))
        assertEquals(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED, db.syncRecoveryJournalDao().get()?.reason)
        reopenOrdinaryDatabase()
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(corrupted, db.productDao().getById(original.id))
        assertEquals(42L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertForeignKeysClean(db)
    }

    @Test
    fun ordinaryPendingHistoryRealTombstonePushAckThenSelfReceiptPublishesC() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(v2HistoryFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.historyEntryDao().getAllUserVisibleSnapshot().single()
        val wire = (remote.fixture.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History).values.single()
        repository.deleteHistoryEntry(original)
        val dirty = requireNotNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(original.uid))
        assertTrue(dirty.localChangeRevision > dirty.lastSyncedLocalRevision)
        val received = wire.copy(deletedAt = "2026-07-21T12:00:00.000000Z",
            updatedAt = "2026-07-21T12:00:00.000000Z")
        val delta = ordinaryHistoryDeltaFixture(remote.fixture, received)
        remote.checkpoints += delta.checkpoint
        remote.tailRows = delta.rows
        remote.tailEvents = listOf(ordinaryEvent(43L, SyncEventDomains.HISTORY, SyncEventTypes.HISTORY_TOMBSTONE,
            SyncEventEntityIds(sessionIds = listOf(received.remoteId))).copy(sourceDeviceId = DEVICE))
        val deferred = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertTrue(deferred.syncEventsSkippedDirtyLocal > 0)
        assertFalse(deferred.manualFullSyncRequired)
        assertOrdinaryUnchangedPublication(baseline)
        val pushed = mutableListOf<SharedSheetSessionUpsertRow>()
        val pushRemote = object : SessionBackupRemoteDataSource {
            override val isConfigured = true
            override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> = error("no pull")
            override suspend fun fetchSessionsByRemoteIds(remoteIds: Set<String>): Result<List<SharedSheetSessionRecord>> = error("no pull")
            override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> {
                pushed += rows
                return Result.success(Unit)
            }
        }
        val ack = repository.pushHistorySessionsToRemote(pushRemote, ACCOUNT, setOf(original.uid), selectedShop()).getOrThrow()
        assertEquals(1, ack.uploaded)
        assertNotNull(pushed.single().deletedAt)
        val clean = requireNotNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(original.uid))
        assertEquals(clean.localChangeRevision, clean.lastSyncedLocalRevision)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())

        val summary = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()

        assertFalse(summary.manualFullSyncRequired)
        assertEquals(1, summary.syncEventsSkippedSelf)
        assertEquals(43L, summary.syncEventsWatermarkAfter)
        val retained = requireNotNull(db.historyEntryDao().getByUid(original.uid))
        assertEquals(received.deletedAt, retained.deletedAt)
        assertEquals(original.data, retained.data)
        assertEquals(original.editable, retained.editable)
        val tombstone = requireNotNull(db.syncRecoveryManifestDao().get(baseline.generationId,
            ShopSyncRowDomain.HISTORY.wireValue, received.remoteId))
        assertFalse(tombstone.active)
        assertNull(tombstone.payloadDigest)
        assertEquals(5, tombstone.versionLine.split('\u001f').size)
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 43L)
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        reopenOrdinaryDatabase()
        validateShopSyncActiveReceipt(db, baseline.generationId,
            decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson))
        val noWork = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertFalse(noWork.manualFullSyncRequired)
        assertEquals(0, noWork.syncEventsFetched)
        assertEquals(43L, noWork.syncEventsWatermarkAfter)
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun ordinaryActivatedEmptyZeroBaselineImmediatelyProvesNoEventNoWork() = runTest {
        val device = seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val binding = db.businessDataScopeBindingDao().get()
        val fullPages = remote.pageCalls
        val reader = OrdinaryFencedReadFixture(remote)

        val summary = drainOrdinary(reader).getOrThrow()

        assertTrue("Activated empty C0 with fenced noEvents must stay READY: reason=${db.syncRecoveryJournalDao().get()?.reason}, summary=$summary",
            !summary.manualFullSyncRequired)
        assertFalse(summary.syncEventsGapDetected)
        assertEquals(0, summary.syncEventsFetched)
        assertEquals(0, summary.syncEventsProcessed)
        assertEquals(0L, summary.syncEventsWatermarkAfter)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        assertEquals(binding, db.businessDataScopeBindingDao().get())
        assertEquals(device, db.syncEventDeviceStateDao().get())
        assertEquals(fullPages, remote.pageCalls)
        assertTrue(reader.targetedContexts.isEmpty())
        reopenOrdinaryDatabase()
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        val second = drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow()
        assertFalse(second.manualFullSyncRequired)
        assertEquals(0, second.syncEventsFetched)
        assertEquals(0L, second.syncEventsWatermarkAfter)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun ordinaryEmptyZeroBootstrapExemptionRequiresCurrentManagedMatchingScope() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val binding = db.businessDataScopeBindingDao().get()
        val device = db.syncEventDeviceStateDao().get()
        val watermark = db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)
        val calls = remote.pageCalls
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        val managed = DefaultInventoryRepository(db, businessDataScopeRuntimeGuard = tracker,
            shopSyncReadRemoteDataSource = remote)

        assertFalse(managed.shouldRunCatalogBootstrap(ACCOUNT))
        assertTrue(repository.shouldRunCatalogBootstrap(ACCOUNT))
        assertTrue(managed.shouldRunCatalogBootstrap(OLD_ACCOUNT))
        tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(oldScope()))
        assertTrue(managed.shouldRunCatalogBootstrap(OLD_ACCOUNT))
        val foreignStore = Task126OwnerStoreScope(activeScope().ownerHash,
            "shop:10000000-0000-4000-8000-000000000099", null)
        tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(foreignStore))
        assertTrue(managed.shouldRunCatalogBootstrap(ACCOUNT))
        val foreignLocalStore = Task126OwnerStoreScope(activeScope().ownerHash,
            activeScope().storeId, "foreign-local-store")
        tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(foreignLocalStore))
        assertTrue(managed.shouldRunCatalogBootstrap(ACCOUNT))
        tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.ready(activeScope()))
        assertFalse(managed.shouldRunCatalogBootstrap(ACCOUNT))

        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(binding, db.businessDataScopeBindingDao().get())
        assertEquals(device, db.syncEventDeviceStateDao().get())
        assertEquals(watermark, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        assertEquals(calls, remote.pageCalls)
        assertEquals(0, db.syncEventOutboxDao().countAll())
        assertNull(db.syncRecoveryJournalDao().get())
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
    }

    @Test
    fun ordinaryEmptyZeroMissingWatermarkOrMalformedReceiptStillRequiresBootstrap() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val binding = db.businessDataScopeBindingDao().get()
        val device = db.syncEventDeviceStateDao().get()
        val watermark = requireNotNull(db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        val managed = DefaultInventoryRepository(db, businessDataScopeRuntimeGuard = tracker,
            shopSyncReadRemoteDataSource = remote)
        assertFalse(managed.shouldRunCatalogBootstrap(ACCOUNT))

        db.syncEventWatermarkDao().deleteAll()
        assertTrue(managed.shouldRunCatalogBootstrap(ACCOUNT))
        assertNull(db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        db.syncEventWatermarkDao().upsert(watermark)
        val malformed = baseline.copy(checkpointJson = "{malformed-canonical-receipt")
        db.syncRecoveryBaselineDao().upsert(malformed)
        assertTrue(managed.shouldRunCatalogBootstrap(ACCOUNT))
        assertEquals(malformed, db.syncRecoveryBaselineDao().get())
        assertEquals(watermark, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        assertEquals(binding, db.businessDataScopeBindingDao().get())
        assertEquals(device, db.syncEventDeviceStateDao().get())
        assertEquals(0, db.productDao().count())
        assertEquals(0, db.historyEntryDao().countUserVisible())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        assertNull(db.syncRecoveryJournalDao().get())
        db.syncRecoveryBaselineDao().upsert(baseline)
        assertFalse(managed.shouldRunCatalogBootstrap(ACCOUNT))
    }

    @Test
    fun ordinaryChangedZeroWatermarkRowLossRejectsAtomicPublication() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val binding = db.businessDataScopeBindingDao().get()
        val device = db.syncEventDeviceStateDao().get()
        val manifests = ShopSyncRowDomain.entries.associateWith {
            db.syncRecoveryManifestDao().page(baseline.generationId, it.wireValue, null, 500)
        }
        prepareFirstZeroHistoryDelta()
        val source = OrdinaryFencedReadFixture(remote)
        var removedDuringRemote = false
        val racing = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                val marker = source.convergenceMarker(context)
                assertTrue(marker.isSuccess)
                assertEquals(0L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
                db.syncEventWatermarkDao().deleteAll()
                removedDuringRemote = true
                return marker
            }
        }

        val failed = drainOrdinary(racing)

        assertTrue(removedDuringRemote)
        assertEquals("ordinary_captured_publication_changed", (failed.exceptionOrNull() as? ShopSyncContractException)?.code)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(binding, db.businessDataScopeBindingDao().get())
        assertEquals(device, db.syncEventDeviceStateDao().get())
        assertNull(db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        assertEquals(0, db.historyEntryDao().countUserVisible())
        assertNull(db.syncEventApplyStatusDao().get(ACCOUNT, activeScope().storeId, 1L))
        assertNull(db.syncRecoveryJournalDao().get())
        reopenOrdinaryDatabase()
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertNull(db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        assertEquals(0, db.historyEntryDao().countUserVisible())
        manifests.forEach { (domain, rows) ->
            assertEquals(rows, db.syncRecoveryManifestDao().page(baseline.generationId, domain.wireValue, null, 500))
        }
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
    }

    @Test
    fun ordinaryNoEventZeroWatermarkRowLossCannotPublishNoWork() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val binding = db.businessDataScopeBindingDao().get()
        val device = db.syncEventDeviceStateDao().get()
        val manifests = ShopSyncRowDomain.entries.associateWith {
            db.syncRecoveryManifestDao().page(baseline.generationId, it.wireValue, null, 500)
        }
        val source = OrdinaryFencedReadFixture(remote)
        var removedDuringRemote = false
        val racing = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                val marker = source.convergenceMarker(context)
                assertTrue(marker.isSuccess)
                assertEquals(0L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
                db.syncEventWatermarkDao().deleteAll()
                removedDuringRemote = true
                return marker
            }
        }

        val failed = drainOrdinary(racing)

        assertTrue(removedDuringRemote)
        assertTrue(failed.isFailure)
        assertEquals("ordinary_captured_publication_changed", (failed.exceptionOrNull() as? ShopSyncContractException)?.code)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(binding, db.businessDataScopeBindingDao().get())
        assertEquals(device, db.syncEventDeviceStateDao().get())
        assertNull(db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        assertEquals(0, db.historyEntryDao().countUserVisible())
        assertNull(db.syncRecoveryJournalDao().get())
        reopenOrdinaryDatabase()
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertNull(db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        manifests.forEach { (domain, rows) ->
            assertEquals(rows, db.syncRecoveryManifestDao().page(baseline.generationId, domain.wireValue, null, 500))
        }
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
    }

    @Test
    fun ordinaryNoEventMarkerHttp500FailsWithoutRecoveryAndCanRetryAfterReopen() = runTest {
        assertNoEventMarkerTransientPreservesActivatedZero(
            ShopSyncContractException("shop_sync_rpc_http_500")
        )
    }

    @Test
    fun ordinaryNoEventMarkerIOExceptionFailsWithoutRecoveryAndCanRetryAfterReopen() = runTest {
        assertNoEventMarkerTransientPreservesActivatedZero(java.io.IOException("fixture_marker_io"))
    }

    @Test
    fun ordinaryChangedWindowMarkerHttp500PreservesAThenRetryPublishesC() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        assertNotNull(db.syncEventDeviceStateDao().get())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        val before = ordinaryMarkerApplicationRows()
        val fullPages = remote.pageCalls
        val delta = prepareFirstZeroHistoryDelta()
        val source = OrdinaryFencedReadFixture(remote)
        val transportFailure = ShopSyncContractException("shop_sync_rpc_http_500")
        var markerCalls = 0
        val reader = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                markerCalls++
                assertEquals("1", context.verifiedBaselineId)
                return if (markerCalls == 1) Result.failure(transportFailure) else source.convergenceMarker(context)
            }
        }

        val failed = drainOrdinary(reader)

        assertTrue("HTTP failure must not become a successful recovery-required summary: $failed", failed.isFailure)
        assertSame(transportFailure, failed.exceptionOrNull())
        assertEquals(1, markerCalls)
        assertTrue("Changed window must reach targeted material before its marker", source.targetedContexts.isNotEmpty())
        assertActivatedZeroMarkerFailureUnchanged(baseline, before)
        assertNull(db.syncEventApplyStatusDao().get(ACCOUNT, activeScope().storeId, 1L))
        reopenOrdinaryDatabase()
        assertActivatedZeroMarkerFailureUnchanged(baseline, before)

        val retried = drainOrdinary(reader).getOrThrow()

        assertEquals(2, markerCalls)
        assertFalse(retried.manualFullSyncRequired)
        assertFalse(retried.syncEventsGapDetected)
        assertEquals(1, retried.syncEventsProcessed)
        assertEquals(1L, retried.syncEventsWatermarkAfter)
        assertEquals(1, db.historyEntryDao().countUserVisible())
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(fullPages, remote.pageCalls)
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 1L)
        reopenOrdinaryDatabase()
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 1L)
        validateShopSyncActiveReceipt(db, baseline.generationId,
            decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson))
        assertFalse(drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow().manualFullSyncRequired)
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(fullPages, remote.pageCalls)
    }

    @Test
    fun ordinaryNoEventWrongShopAndMalformedMarkerStillRequireRecovery() = runTest {
        for (malformed in listOf(false, true)) {
            if (malformed) {
                db.close()
                app.deleteDatabase(ACTIVE_DATABASE)
                db = openDatabase(ACTIVE_DATABASE)
                repository = DefaultInventoryRepository(db)
            }
            seedOldMismatchGeneration()
            val baseline = recoverAndReopenZeroOrdinaryFixture()
            assertNotNull(db.syncEventDeviceStateDao().get())
            assertEquals(0, db.syncEventOutboxDao().countAll())
            val before = ordinaryMarkerApplicationRows()
            val fullPages = remote.pageCalls
            val source = OrdinaryFencedReadFixture(remote)
            var markerCalls = 0
            val reader = object : ShopSyncReadRemoteDataSource by source {
                override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                    markerCalls++
                    return if (malformed) {
                        Result.failure(ShopSyncContractException("shop_sync_rpc_json_invalid"))
                    } else {
                        source.convergenceMarker(context).map { it.copy(shopId = OLD_ACCOUNT) }
                    }
                }
            }

            val summary = drainOrdinary(reader).getOrThrow()

            assertEquals(1, markerCalls)
            assertTrue("Contract failure must retain the recovery latch (malformed=$malformed)", summary.manualFullSyncRequired)
            assertTrue(summary.syncEventsGapDetected)
            assertEquals(0L, summary.syncEventsWatermarkAfter)
            val journal = requireNotNull(db.syncRecoveryJournalDao().get())
            assertEquals(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED, journal.reason)
            assertEquals(SyncRecoveryJournalPhases.REQUIRED, journal.phase)
            assertEquals(SyncRecoveryAuthorizationModes.SAME_SCOPE, journal.authorizationMode)
            assertEquals(activeScope().ownerHash, journal.ownerHash)
            assertEquals(SHOP, journal.shopId)
            assertEquals(before.filterKeys { it != "sync_recovery_journal" },
                ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
            reopenOrdinaryDatabase()
            assertEquals(journal, db.syncRecoveryJournalDao().get())
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            assertEquals(before.filterKeys { it != "sync_recovery_journal" },
                ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
            assertEquals(fullPages, remote.pageCalls)
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        }
    }

    @Test
    fun ordinaryMarkerCancellationPropagatesWithoutJournalForNoEventAndChangedWindow() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        assertNotNull(db.syncEventDeviceStateDao().get())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        val before = ordinaryMarkerApplicationRows()
        val fullPages = remote.pageCalls
        for (changed in listOf(false, true)) {
            if (changed) prepareFirstZeroHistoryDelta()
            val source = OrdinaryFencedReadFixture(remote)
            var markerCalls = 0
            val reader = object : ShopSyncReadRemoteDataSource by source {
                override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                    markerCalls++
                    assertEquals(if (changed) "1" else "0", context.verifiedBaselineId)
                    return Result.failure(CancellationException("fixture_marker_cancel"))
                }
            }

            val cancelled = runCatching { drainOrdinary(reader) }.exceptionOrNull()

            assertTrue("Cancellation must propagate rather than becoming Result.failure or a recovery latch", cancelled is CancellationException)
            assertEquals("fixture_marker_cancel", cancelled?.message)
            assertEquals(1, markerCalls)
            assertActivatedZeroMarkerFailureUnchanged(baseline, before)
            reopenOrdinaryDatabase()
            assertActivatedZeroMarkerFailureUnchanged(baseline, before)
            assertEquals(fullPages, remote.pageCalls)
        }
    }

    @Test
    fun ordinaryProofDiagnosticMarkerErrorsAreClosedAndNeverLogPayload() = runTest {
        val privateValue = "private-marker-body-DO-NOT-LOG"
        val failures = listOf(
            ShopSyncContractException("shop_sync_rpc_json_invalid") to "shop_sync_rpc_json_invalid",
            ShopSyncContractException("recovery_manifest_digest_mismatch_products\n$privateValue") to "other_contract",
            IllegalStateException("recovery_$privateValue") to "illegal_state",
            IllegalArgumentException(privateValue) to "illegal_argument",
            android.database.sqlite.SQLiteException(privateValue) to "sqlite",
            SerializationException(privateValue) to "serialization",
            Exception(privateValue) to "other_exception"
        )
        for ((failure, expectedCode) in failures) {
            resetOrdinaryDiagnosticFixture()
            seedOldMismatchGeneration()
            val baseline = recoverAndReopenZeroOrdinaryFixture()
            val before = ordinaryMarkerApplicationRows()
            val source = OrdinaryFencedReadFixture(remote)
            var markerCalls = 0
            val reader = object : ShopSyncReadRemoteDataSource by source {
                override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                    markerCalls++
                    return Result.failure(failure)
                }
            }
            ShadowLog.clear()

            val summary = drainOrdinary(reader).getOrThrow()

            assertTrue(summary.manualFullSyncRequired)
            assertEquals(1, markerCalls)
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            assertEquals(before.filterKeys { it != "sync_recovery_journal" },
                ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
            assertEquals(listOf(
                "ordinary_convergence_proof branch=no_work stage=marker_error errorCode=$expectedCode",
                "ordinary_convergence_proof branch=no_work stage=recovery_required localReceipt=true " +
                    "markerEvaluated=true markerResult=false firstFalse=marker_absent markerError=$expectedCode localError=none"
            ), ordinaryProofDiagnosticMessages())
            assertFalse(ordinaryProofDiagnosticMessages().joinToString().contains(privateValue))
        }
    }

    @Test
    fun ordinaryProofDiagnosticKeepsFirstFalseMarkerPredicateOrder() = runTest {
        for ((status, eligible, expectedPredicate) in listOf(
            Triple("not-ready-private-status", false, "status_not_ready"),
            Triple("ready", false, "server_not_eligible"),
            Triple("ready", true, "shop_mismatch")
        )) {
            resetOrdinaryDiagnosticFixture()
            seedOldMismatchGeneration()
            val baseline = recoverAndReopenZeroOrdinaryFixture()
            val source = OrdinaryFencedReadFixture(remote)
            var markerCalls = 0
            val reader = object : ShopSyncReadRemoteDataSource by source {
                override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                    markerCalls++
                    return source.convergenceMarker(context).map {
                        it.copy(status = status, serverNoWorkEligible = eligible, shopId = OLD_ACCOUNT,
                            checkpointDigest = "private-digest-DO-NOT-LOG")
                    }
                }
            }
            ShadowLog.clear()

            val summary = drainOrdinary(reader).getOrThrow()

            assertTrue(summary.manualFullSyncRequired)
            assertEquals(1, markerCalls)
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            assertEquals(listOf("ordinary_convergence_proof branch=no_work stage=recovery_required localReceipt=true " +
                "markerEvaluated=true markerResult=false firstFalse=$expectedPredicate markerError=none localError=none"),
                ordinaryProofDiagnosticMessages())
        }
    }

    @Test
    fun ordinaryProofDiagnosticLocalFailureSkipsMarkerPredicateAndLatchedReentry() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        db.openHelper.writableDatabase.execSQL("UPDATE product_prices SET price = price + 0.5")
        val before = ordinaryMarkerApplicationRows()
        val source = OrdinaryFencedReadFixture(remote)
        var markerCalls = 0
        val reader = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                markerCalls++
                return source.convergenceMarker(context).map { it.copy(status = "private-status", shopId = OLD_ACCOUNT) }
            }
        }
        ShadowLog.clear()

        val summary = drainOrdinary(reader).getOrThrow()

        assertTrue(summary.manualFullSyncRequired)
        assertEquals(1, markerCalls)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(before.filterKeys { it != "sync_recovery_journal" },
            ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
        val expected = listOf(
            "ordinary_convergence_proof branch=no_work stage=local_receipt_error errorCode=recovery_physical_digest_mismatch_prices",
            "ordinary_convergence_proof branch=no_work stage=recovery_required localReceipt=false " +
                "markerEvaluated=false markerResult=not_evaluated firstFalse=local_receipt " +
                "markerError=none localError=recovery_physical_digest_mismatch_prices"
        )
        assertEquals(expected, ordinaryProofDiagnosticMessages())
        drainOrdinary(reader)
        assertEquals("Existing recovery latch must not trigger an extra marker request", 1, markerCalls)
        assertEquals(expected, ordinaryProofDiagnosticMessages())
    }

    @Test
    fun ordinaryProofDiagnosticSeparatesIncrementalFailureBeforeAndAfterEvents() = runTest {
        for (beforeEvents in listOf(true, false)) {
            resetOrdinaryDiagnosticFixture()
            seedOldMismatchGeneration()
            recoverAndReopenOrdinaryFixture(targetFixture())
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            prepareOrdinaryCatalogDelta()
            if (beforeEvents) db.openHelper.writableDatabase.execSQL(
                "UPDATE sync_recovery_manifest SET versionLine = versionLine || 'tampered' WHERE domain = 'prices'")
            val before = ordinaryMarkerApplicationRows()
            val source = OrdinaryFencedReadFixture(remote)
            var markerCalls = 0
            val reader = object : ShopSyncReadRemoteDataSource by source {
                override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                    markerCalls++
                    return source.convergenceMarker(context).map { it.copy(shopId = OLD_ACCOUNT) }
                }
            }
            ShadowLog.clear()

            val summary = drainOrdinary(reader).getOrThrow()

            assertTrue(summary.manualFullSyncRequired)
            assertEquals(if (beforeEvents) 0 else 1, markerCalls)
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            assertEquals(before.filterKeys { it != "sync_recovery_journal" },
                ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
            assertEquals(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED, db.syncRecoveryJournalDao().get()?.reason)
            val expectedCode = if (beforeEvents) "recovery_manifest_digest_mismatch_prices" else "recovery_convergence_marker_mismatch"
            assertEquals(listOf("ordinary_convergence_proof branch=incremental_window stage=recovery_required " +
                "errorCode=$expectedCode initialProofComplete=${!beforeEvents} eventsPresent=${!beforeEvents}"),
                ordinaryProofDiagnosticMessages())
        }
    }

    @Test
    fun ordinaryProofDiagnosticDoesNotReportUncommittedIncrementalLatch() = runTest {
        for (publicationChanged in listOf(false, true)) {
            resetOrdinaryDiagnosticFixture()
            seedOldMismatchGeneration()
            recoverAndReopenOrdinaryFixture(targetFixture())
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            prepareOrdinaryCatalogDelta()
            val source = OrdinaryFencedReadFixture(remote)
            var pageCalls = 0
            val reader = object : ShopSyncReadRemoteDataSource by source {
                override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> {
                    val page = source.eventPage(context, afterId, limit)
                    pageCalls++
                    // Local work/publication changes while the existing remote page is in flight.
                    // Canonical A then fails before events are accepted, but the latch must not commit.
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE sync_recovery_manifest SET versionLine = versionLine || 'tampered' WHERE domain = 'prices'")
                    db.openHelper.writableDatabase.execSQL(if (publicationChanged)
                        "DELETE FROM sync_event_watermarks"
                    else "UPDATE product_remote_refs SET localChangeRevision = localChangeRevision + 1")
                    return page
                }
            }
            ShadowLog.clear()

            val outcome = drainOrdinary(reader)

            assertEquals(1, pageCalls)
            if (publicationChanged) {
                assertTrue(outcome.isFailure)
                assertEquals("ordinary_captured_publication_changed",
                    (outcome.exceptionOrNull() as? ShopSyncContractException)?.code)
            } else {
                val summary = outcome.getOrThrow()
                assertTrue(summary.syncEventsSkippedDirtyLocal > 0)
                assertFalse(summary.manualFullSyncRequired)
            }
            assertNull(db.syncRecoveryJournalDao().get())
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            assertTrue("A deferred or rejected transaction must not report a committed recovery latch",
                ordinaryProofDiagnosticMessages().isEmpty())
        }
    }

    private fun resetOrdinaryDiagnosticFixture() {
        db.close()
        app.deleteDatabase(ACTIVE_DATABASE)
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)
    }

    private fun ordinaryProofDiagnosticMessages(): List<String> =
        ShadowLog.getLogsForTag("CatalogCloudSync")
            .filter { it.msg.startsWith("ordinary_convergence_proof ") }
            .map {
                assertNull("Diagnostic must never attach an exception/stack", it.throwable)
                it.msg
            }

    private suspend fun assertNoEventMarkerTransientPreservesActivatedZero(transportFailure: Exception) {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        assertNotNull(db.syncEventDeviceStateDao().get())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        val before = ordinaryMarkerApplicationRows()
        val fullPages = remote.pageCalls
        val source = OrdinaryFencedReadFixture(remote)
        var markerCalls = 0
        val reader = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                markerCalls++
                assertEquals("0", context.verifiedBaselineId)
                assertEquals(SHOP, context.shopId)
                return if (markerCalls == 1) Result.failure(transportFailure) else source.convergenceMarker(context)
            }
        }

        val failed = drainOrdinary(reader)

        assertTrue("Transient marker failure must not become successful no-work or recovery-required: $failed", failed.isFailure)
        assertSame(transportFailure, failed.exceptionOrNull())
        assertEquals(1, markerCalls)
        assertTrue(source.targetedContexts.isEmpty())
        assertActivatedZeroMarkerFailureUnchanged(baseline, before)
        reopenOrdinaryDatabase()
        assertActivatedZeroMarkerFailureUnchanged(baseline, before)

        val retried = drainOrdinary(reader).getOrThrow()

        assertEquals(2, markerCalls)
        assertFalse(retried.manualFullSyncRequired)
        assertFalse(retried.syncEventsGapDetected)
        assertEquals(0, retried.syncEventsFetched)
        assertEquals(0, retried.syncEventsProcessed)
        assertEquals(0L, retried.syncEventsWatermarkAfter)
        assertActivatedZeroMarkerFailureUnchanged(baseline, before)
        assertEquals(fullPages, remote.pageCalls)
    }

    private suspend fun assertActivatedZeroMarkerFailureUnchanged(
        baseline: SyncRecoveryBaseline,
        before: Map<String, List<List<String>>>
    ) {
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(0L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(before, ordinaryMarkerApplicationRows())
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        assertForeignKeysClean(db)
    }

    /** Exact typed rows in the synthetic Room database, including manifests, queues and nullable watermark. */
    private fun ordinaryMarkerApplicationRows(): Map<String, List<List<String>>> {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            val quoted = table.replace("\"", "\"\"")
            sql.query("SELECT * FROM \"$quoted\"").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(List(cursor.columnCount) { column ->
                            when (cursor.getType(column)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                                android.database.Cursor.FIELD_TYPE_BLOB -> "BLOB:${cursor.getBlob(column).contentToString()}"
                                else -> "${cursor.getType(column)}:${cursor.getString(column)}"
                            }
                        })
                    }
                }
            }.sortedBy { Json.encodeToString(it) }
        }
    }

    private suspend fun recoverAndReopenZeroOrdinaryFixture(): SyncRecoveryBaseline {
        val empty = emptyTargetFixture()
        val zero = empty.copy(checkpoint = empty.checkpoint.copy(syncEvents = empty.checkpoint.syncEvents.copy(
            maxId = "0", verifiedBaselineId = "0",
            domainMaxIds = empty.checkpoint.syncEvents.domainMaxIds.mapValues { "0" })))
        remote = RecoveryRemoteFixture(zero)
        remote.emptyTailConfigured = true
        val journal = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals(activeScope().ownerHash, journal.ownerHash)
        assertEquals(activeScope().storeId, journal.storeScope)
        assertEquals(SHOP, journal.shopId)
        assertEquals(DEVICE, journal.deviceId)
        assertEquals(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED, journal.authorizationMode)
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, journal.phase)
        assertEquals(SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED, journal.reason)
        val emptyTargetJournal = journal.copy(blockingEventId = null)
        db.syncRecoveryJournalDao().upsert(emptyTargetJournal)
        assertEquals(emptyTargetJournal, db.syncRecoveryJournalDao().get())
        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        reopenOrdinaryDatabase()
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertEquals("0", decodeRecoveryCheckpointJson(baseline.checkpointJson).syncEvents.maxId)
        assertEquals("0", decodeRecoveryCheckpointJson(baseline.checkpointJson).syncEvents.verifiedBaselineId)
        assertEquals(0L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertEquals(0, db.productDao().count())
        assertEquals(0, db.historyEntryDao().countUserVisible())
        assertNull(db.syncRecoveryJournalDao().get())
        assertNotNull(db.businessDataScopeBindingDao().get())
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        return baseline
    }

    private fun prepareFirstZeroHistoryDelta(): RecoveryFixture {
        val history = (targetFixture().rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History).values.single()
        val draft = ordinaryHistoryDeltaFixture(remote.fixture, history)
        val delta = draft.copy(checkpoint = draft.checkpoint.copy(syncEvents = draft.checkpoint.syncEvents.copy(
            maxId = "1", verifiedBaselineId = "0",
            domainMaxIds = remote.fixture.checkpoint.syncEvents.domainMaxIds + (SyncEventDomains.HISTORY to "1"))))
        remote.checkpoints += delta.checkpoint
        remote.tailRows = delta.rows
        remote.tailEvents = listOf(ordinaryEvent(1L, SyncEventDomains.HISTORY, SyncEventTypes.HISTORY_CHANGED,
            SyncEventEntityIds(sessionIds = listOf(history.remoteId))))
        return delta
    }

    private suspend fun drainOrdinary(reader: ShopSyncReadRemoteDataSource): Result<CatalogSyncSummary> {
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        return repository.drainSyncEventsFromRemote(NoOpCatalogRemoteForRecoveryTest, NoOpPriceRemoteForRecoveryTest,
            NoOpSyncEventRemoteForRecoveryTest, ACCOUNT, CatalogSyncProgressReporter { }, selectedShop = selectedShop())
    }

    private fun prepareOrdinaryCatalogDelta(): RecoveryFixture {
        val products = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values
        val delta = ordinaryDeltaFixture(remote.fixture, products.map { it.copy(productName = "R-A10 adjacent product",
            updatedAt = "2026-07-21T11:00:00.000000Z") }, 43L, mapOf(SyncEventDomains.CATALOG to "43"))
        remote.checkpoints += delta.checkpoint
        remote.tailRows = delta.rows
        remote.tailEvents = listOf(ordinaryEvent(43L, SyncEventDomains.CATALOG, SyncEventTypes.CATALOG_CHANGED,
            SyncEventEntityIds(productIds = products.map { it.id })))
        return delta
    }

    private fun prepareOrdinaryRestoreDelta(): RecoveryFixture {
        val base = remote.fixture
        val products = (base.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values
        val tomb = products.single { it.deletedAt != null }
        val updated = products.map { if (it.id == tomb.id) it.copy(deletedAt = null, productName = "Restored parent",
            updatedAt = "2026-07-21T11:00:00.000000Z") else it }
        val images = (base.rows.getValue(ShopSyncRowDomain.IMAGES) as ShopSyncRows.Images).values.filter { it.productId != tomb.id }
        val draft = ordinaryDeltaFixture(base, updated, 43L, mapOf(SyncEventDomains.CATALOG to "43"))
        val delta = draft.copy(checkpoint = draft.checkpoint.copy(images = checkpointDomain(images.map { it.productId }, images.map(::testImageVersion))),
            rows = draft.rows + (ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(images)))
        remote.checkpoints += delta.checkpoint
        remote.tailRows = delta.rows
        remote.tailEvents = listOf(ordinaryEvent(43L, SyncEventDomains.CATALOG, SyncEventTypes.CATALOG_CHANGED,
            SyncEventEntityIds(productIds = listOf(tomb.id))))
        return delta
    }

    private suspend fun assertOrdinaryUnchangedPublication(baseline: SyncRecoveryBaseline) {
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(42L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertNull(db.syncRecoveryJournalDao().get())
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        assertForeignKeysClean(db)
    }

    private fun reopenOrdinaryDatabase() {
        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = remote)
    }

    private suspend fun recoverAndReopenOrdinaryFixture(fixture: RecoveryFixture) {
        remote = RecoveryRemoteFixture(fixture)
        val recovered = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
        assertNull(db.syncRecoveryJournalDao().get())
        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = remote)
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        assertEquals(42L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
        assertEquals("42", decodeRecoveryCheckpointJson(requireNotNull(db.syncRecoveryBaselineDao().get()).checkpointJson).syncEvents.verifiedBaselineId)
        assertFalse(repository.shouldRunCatalogBootstrap(ACCOUNT))
    }

    private fun ordinaryAutoSync(
        reader: ShopSyncReadRemoteDataSource,
        tracker: CatalogSyncStateTracker,
        auth: MutableStateFlow<AuthState>,
        deviceRemote: ActiveShopDeviceRemoteForOrdinaryTest,
        scope: kotlinx.coroutines.CoroutineScope,
        recoveryTriggers: MutableList<String>,
        logger: (String) -> Unit = {}
    ): CatalogAutoSyncCoordinator {
        repository = DefaultInventoryRepository(db, tracker, reader)
        return CatalogAutoSyncCoordinator(
            repository = repository,
            remote = NoOpCatalogRemoteForRecoveryTest,
            priceRemote = NoOpPriceRemoteForRecoveryTest,
            syncEventRemote = NoOpSyncEventRemoteForRecoveryTest,
            deviceAuthorization = ShopDeviceAuthorizationRepository(deviceRemote, businessDataScopeRuntimeGuard = tracker),
            authFlow = auth,
            selectedShopProvider = { selectedShop() },
            syncStateTracker = tracker,
            scope = scope,
            debounceMs = Long.MAX_VALUE,
            onRecoveryRequired = { recoveryTriggers += it },
            logger = logger
        )
    }

    private suspend fun assertOrdinaryPublishedReceipt(generationId: String, expected: ShopSyncRecoveryCheckpoint, watermark: Long) {
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertEquals(generationId, baseline.generationId)
        val receipt = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        assertEquals(watermark.toString(), receipt.syncEvents.maxId)
        assertEquals(watermark.toString(), receipt.syncEvents.verifiedBaselineId)
        assertEquals(expected.syncEvents.domainMaxIds, receipt.syncEvents.domainMaxIds)
        assertEquals(expected.checkpointDigest, receipt.checkpointDigest)
        assertEquals(expected.catalog, receipt.catalog)
        assertEquals(expected.prices, receipt.prices)
        assertEquals(expected.history, receipt.history)
        assertEquals(expected.images, receipt.images)
    }

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
    var checkpointFailure: Exception? = null
    var cancelAtDomain: ShopSyncRowDomain? = null
    var fatalAtDomain: Pair<ShopSyncRowDomain, Error>? = null
    var afterPage: (suspend (ShopSyncRowDomain) -> Unit)? = null
    var checkpointCalls = 0
    var pageCalls = 0
    val requestedPageLimits = mutableMapOf<ShopSyncRowDomain, MutableList<Int>>()
    val responseBytes = mutableMapOf<ShopSyncRowDomain, Long>()
    val largestRowBytes = mutableMapOf<ShopSyncRowDomain, Long>()
    var tailEvents: List<SyncEventRemoteRow> = emptyList()
    var emptyTailConfigured = false
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
        checkpointFailure?.let {
            checkpointCalls++
            return Result.failure(it)
        }
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
        if (tailEvents.isEmpty() && !emptyTailConfigured) {
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

/** Ordinary RPC material carries each domain's actual captured fence. */
private class OrdinaryFencedReadFixture(
    private val source: RecoveryRemoteFixture
) : ShopSyncReadRemoteDataSource by source {
    val targetedContexts = mutableListOf<ShopSyncRpcContext>()
    val materializedFences = mutableListOf<Pair<ShopSyncRowDomain, String>>()

    override suspend fun rowsByIds(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        ids: List<String>
    ): Result<ShopSyncTargetedRows> {
        val response = source.rowsByIds(context, domain, ids).getOrThrow()
        val materialized = source.checkpoints.last().syncEvents.domainMaxIds
            .getValue(domain.fixtureSyncEventDomain())
        check(materialized.toLong() >= requireNotNull(context.expectedDomainEventMaxId).toLong())
        targetedContexts += context
        materializedFences += domain to materialized
        return Result.success(response.copy(materializedDomainEventMaxId = materialized))
    }
}

private class ActiveShopDeviceRemoteForOrdinaryTest(private val shopId: String) : ShopDeviceRegistrationRemote {
    override val isConfigured = true
    var statusCalls = 0

    override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> =
        error("ordinary drain must use the already registered scoped device")

    override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> =
        error("ordinary drain must check the selected shop device")

    override suspend fun shopDeviceStatusForShop(
        shopId: String,
        reason: String
    ): Result<ShopDeviceAuthorizationSnapshot> {
        check(shopId == this.shopId)
        statusCalls++
        return Result.success(ShopDeviceAuthorizationSnapshot(
            status = "active", code = "success", canWrite = true,
            serverTime = "2026-07-21T11:00:00Z", lastSeenAt = "2026-07-21T11:00:00Z",
            reasonCode = "active", recommendedAction = "allow", checkedAtMs = 1_000_000L
        ))
    }
}

private fun ordinaryEvent(
    id: Long,
    domain: String,
    type: String,
    ids: SyncEventEntityIds
) = SyncEventRemoteRow(
    id = id,
    ownerUserId = "10000000-0000-4000-8000-000000000001",
    shopId = "10000000-0000-4000-8000-000000000003",
    domain = domain,
    eventType = type,
    changedCount = 1,
    entityIds = ids,
    sourceDeviceId = "00000000-0000-4000-8000-000000000099",
    createdAt = "2026-07-21T11:00:00.000000Z"
)

private fun ordinaryDeltaFixture(
    base: RecoveryFixture,
    products: List<InventoryProductRow>,
    maxId: Long,
    domainMaxIds: Map<String, String>,
    prices: List<InventoryProductPriceRow> = (base.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values
): RecoveryFixture {
    val productsCheckpoint = checkpointDomain(
        products.map { it.id }, products.map(::testProductVersion), products.map(::testProductIdentity),
        activeCount = products.count { it.deletedAt == null }.toLong(),
        tombstoneCount = products.count { it.deletedAt != null }.toLong()
    )
    val catalog = base.checkpoint.catalog.copy(
        products = productsCheckpoint,
        digest = testSha256(base.checkpoint.catalog.suppliers.versionDigest + "\n" +
            base.checkpoint.catalog.categories.versionDigest + "\n" + productsCheckpoint.versionDigest)
    )
    return base.copy(
        checkpoint = base.checkpoint.copy(
            syncEvents = base.checkpoint.syncEvents.copy(
                maxId = maxId.toString(),
                domainMaxIds = base.checkpoint.syncEvents.domainMaxIds + domainMaxIds
            ),
            catalog = catalog,
            prices = checkpointDomain(prices.map { it.id }, prices.map(::testPriceVersion)),
            checkpointDigest = if (maxId == 43L) "8".repeat(64) else "9".repeat(64)
        ),
        rows = base.rows + (ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(products)) +
            (ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(prices))
    )
}

private fun ordinaryHistoryDeltaFixture(base: RecoveryFixture, history: SharedSheetSessionRecord): RecoveryFixture = base.copy(
    checkpoint = base.checkpoint.copy(
        syncEvents = base.checkpoint.syncEvents.copy(maxId = "43",
            domainMaxIds = base.checkpoint.syncEvents.domainMaxIds + (SyncEventDomains.HISTORY to "43")),
        history = checkpointDomain(listOf(history.remoteId), listOf(testHistoryVersion(history)),
            activeCount = if (history.deletedAt == null) 1L else 0L,
            tombstoneCount = if (history.deletedAt == null) 0L else 1L),
        checkpointDigest = "8".repeat(64)),
    rows = base.rows + (ShopSyncRowDomain.HISTORY to ShopSyncRows.History(listOf(history)))
)

private fun smallMixedRetainedPriceFixture(): RecoveryFixture {
    val base = mixedRetainedPriceFixture()
    val prices = (base.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values
    val small = listOf(prices.first(), prices[95])
    return base.copy(
        checkpoint = base.checkpoint.copy(prices = checkpointDomain(small.map { it.id }, small.map(::testPriceVersion))),
        rows = base.rows + (ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(small))
    )
}

private fun ordinaryRetainedPriceDeltaFixture(base: RecoveryFixture): RecoveryFixture {
    val existing = (base.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values
    val retained = existing.first()
    val newPrice = retained.copy(
        id = "40000000-0000-4000-8000-000000000019",
        effectiveAt = "2026-07-21 11:00:00",
        createdAt = "2026-07-21 11:00:00",
        updatedAt = "2026-07-21T11:00:00.000000Z"
    )
    val products = (base.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.map {
        if (it.deletedAt != null) it.copy(updatedAt = "2026-07-21T11:00:01.000000Z") else it
    }
    // The following catalog event provides a real catalog fence >= the price
    // parent lookup's minimum, rather than echoing an impossible domain fence.
    return ordinaryDeltaFixture(
        base, products, 44L,
        mapOf(SyncEventDomains.PRICES to "43", SyncEventDomains.CATALOG to "44"),
        existing + newPrice
    )
}

private fun ordinaryRetainedPriceEvents(price: InventoryProductPriceRow, tombstone: InventoryProductRow) = listOf(
    ordinaryEvent(43L, SyncEventDomains.PRICES, SyncEventTypes.PRICES_CHANGED,
        SyncEventEntityIds(priceIds = listOf(price.id), productIds = listOf(price.productId))),
    ordinaryEvent(44L, SyncEventDomains.CATALOG, SyncEventTypes.CATALOG_TOMBSTONE,
        SyncEventEntityIds(productIds = listOf(tombstone.id))).copy(createdAt = requireNotNull(tombstone.updatedAt))
)

/** A separate faithful keyset adapter leaves the original single-page fake unchanged. */
private class PagedRecoveryRemoteFixture(
    private val source: RecoveryRemoteFixture
) : ShopSyncReadRemoteDataSource by source {
    override suspend fun recoveryPage(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        afterId: String?,
        limit: Int
    ): Result<ShopSyncRecoveryPage> {
        val template = source.recoveryPage(context, domain, null, limit).getOrThrow()
        val ids = template.rows.ids()
        val offset = if (afterId == null) {
            0
        } else {
            val previous = ids.indexOf(afterId)
            check(previous >= 0)
            previous + 1
        }
        val pageIds = ids.drop(offset).take(limit)
        val hasMore = offset + pageIds.size < ids.size
        return Result.success(
            template.copy(
                rows = template.rows.filterRowsByIds(pageIds.toSet()),
                nextAfterId = pageIds.lastOrNull().takeIf { hasMore },
                hasMore = hasMore
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
    productId: String = "20000000-0000-4000-8000-000000000003",
    historyTimestamp: String = "2026-07-21 10:00:00"
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
        timestamp = historyTimestamp,
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

private fun retainedPriceForDeletedProductFixture(): RecoveryFixture {
    val active = targetFixture()
    val deleted = deletedProductImageFixture()
    return deleted.copy(
        checkpoint = deleted.checkpoint.copy(prices = active.checkpoint.prices),
        rows = deleted.rows + (ShopSyncRowDomain.PRICES to active.rows.getValue(ShopSyncRowDomain.PRICES))
    )
}

private fun mixedRetainedPriceFixture(): RecoveryFixture {
    val active = targetFixture()
    val deleted = deletedProductImageFixture()
    val activeProduct = (active.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.single()
    val deletedProduct = (deleted.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
        .values.single().copy(
            id = "20000000-0000-4000-8000-000000000009",
            barcode = "deleted-parent-barcode"
        )
    val originalPrice = (active.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values.single()
    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val start = java.time.LocalDateTime.of(2026, 7, 21, 10, 0)
    val prices = (1..655).map { index ->
        val timestamp = start.plusSeconds(index.toLong()).format(formatter)
        originalPrice.copy(
            id = "30000000-0000-4000-8000-" + index.toString().padStart(12, '0'),
            productId = if (index <= 95) deletedProduct.id else activeProduct.id,
            effectiveAt = timestamp,
            createdAt = timestamp
        )
    }
    val activeImage = (active.rows.getValue(ShopSyncRowDomain.IMAGES) as ShopSyncRows.Images).values.single()
    val deletedImage = (deleted.rows.getValue(ShopSyncRowDomain.IMAGES) as ShopSyncRows.Images)
        .values.single().copy(
            productId = deletedProduct.id,
            versionId = "20000000-0000-4000-8000-000000000010"
        )
    val productsCheckpoint = checkpointDomain(
        listOf(activeProduct.id, deletedProduct.id),
        listOf(testProductVersion(activeProduct), testProductVersion(deletedProduct)),
        listOf(testProductIdentity(activeProduct), testProductIdentity(deletedProduct)),
        activeCount = 1,
        tombstoneCount = 1
    )
    val catalog = active.checkpoint.catalog.copy(
        products = productsCheckpoint,
        digest = testSha256(
            active.checkpoint.catalog.suppliers.versionDigest + "\n" +
                active.checkpoint.catalog.categories.versionDigest + "\n" + productsCheckpoint.versionDigest
        )
    )
    return active.copy(
        checkpoint = active.checkpoint.copy(
            catalog = catalog,
            prices = checkpointDomain(prices.map { it.id }, prices.map(::testPriceVersion)),
            images = checkpointDomain(
                listOf(activeImage.productId, deletedImage.productId),
                listOf(testImageVersion(activeImage), testImageVersion(deletedImage)),
                activeCount = 1,
                tombstoneCount = 1
            )
        ),
        rows = active.rows +
            (ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(activeProduct, deletedProduct))) +
            (ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(prices)) +
            (ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(listOf(activeImage, deletedImage)))
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

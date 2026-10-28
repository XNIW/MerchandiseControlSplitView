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
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShopSyncRecoveryCoordinatorTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository
    private lateinit var remote: RecoveryRemoteFixture
    private val databaseNames = mutableSetOf<String>()
    private val ackWitnessJson = Json { encodeDefaults = true }

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
    fun activeReceiptKeepsSavedPendingProductSeparateFromRemoteBaseline() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val checkpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        val canonical = db.syncRecoveryManifestDao().page(
            baseline.generationId, ShopSyncRowDomain.PRODUCTS.wireValue, null, 100)
        repository.addProduct(Product(barcode = "r23-t05-pending-overlay", productName = "Saved while recovering"))
        val saved = requireNotNull(repository.findProductByBarcode("r23-t05-pending-overlay"))
        val pending = requireNotNull(db.productRemoteRefDao().getByProductId(saved.id))
        assertNull(pending.lastRemoteAppliedAt)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        reopenOrdinaryDatabase()
        db.withTransaction { validateShopSyncActiveReceipt(db, baseline.generationId, checkpoint) }
        assertEquals(saved, db.productDao().getById(saved.id))
        assertEquals(pending, db.productRemoteRefDao().getByProductId(saved.id))
        assertEquals(canonical, db.syncRecoveryManifestDao().page(
            baseline.generationId, ShopSyncRowDomain.PRODUCTS.wireValue, null, 100))
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun nonPriceCatalogPagesWithoutAckDoNotBuildSecondaryFingerprints() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture()) // Legacy History needs no v2 canonical payload recomputation.
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        for (domain in ShopSyncRowDomain.entries) {
            assertTrue(db.syncRecoveryManifestDao().page(
                baseline.generationId, LOCAL_ACK_BODY_PREFIX + domain.wireValue, null, 1000).isEmpty())
        }
        io.mockk.mockkStatic(::fingerprintSupplierInbound, ::fingerprintCategoryInbound,
            ::fingerprintProductInbound)
        try {
            io.mockk.every { fingerprintSupplierInbound(any()) } answers { error("Unexpected eager supplier ACK fingerprint") }
            io.mockk.every { fingerprintCategoryInbound(any()) } answers { error("Unexpected eager category ACK fingerprint") }
            io.mockk.every { fingerprintProductInbound(any()) } answers { error("Unexpected eager product ACK fingerprint") }
            db.withTransaction {
                validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                    localOverlay = true, localAcknowledgements = true)
            }
            io.mockk.verify(exactly = 0) { fingerprintSupplierInbound(any()) }
            io.mockk.verify(exactly = 0) { fingerprintCategoryInbound(any()) }
            io.mockk.verify(exactly = 0) { fingerprintProductInbound(any()) }
        } finally {
            io.mockk.unmockkStatic(::fingerprintSupplierInbound, ::fingerprintCategoryInbound,
                ::fingerprintProductInbound)
        }
        assertForeignKeysClean(db)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
    }

    @Test
    fun wrongDomainAndMixedAckPrefixesCannotHideNonPricePhysicalCorruption() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(v2HistoryFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val cases = listOf(
            Triple(ShopSyncRowDomain.SUPPLIERS, ShopSyncRowDomain.CATEGORIES.wireValue,
                "UPDATE suppliers SET name='unrecorded supplier corruption'"),
            Triple(ShopSyncRowDomain.CATEGORIES, ShopSyncRowDomain.PRODUCTS.wireValue,
                "UPDATE categories SET name='unrecorded category corruption'"),
            Triple(ShopSyncRowDomain.PRODUCTS, ShopSyncRowDomain.HISTORY.wireValue,
                "UPDATE products SET productName='unrecorded product corruption'"),
            Triple(ShopSyncRowDomain.HISTORY, LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.HISTORY.wireValue,
                "UPDATE history_entries SET displayName='unrecorded History v2 corruption'")
        )
        for ((domain, wrongSuffix, corruption) in cases) {
            val canonical = db.syncRecoveryManifestDao().page(
                baseline.generationId, domain.wireValue, null, 1000).first()
            val wrongKind = canonical.copy(domain = LOCAL_ACK_BODY_PREFIX + wrongSuffix)
            val failure = runCatching {
                db.withTransaction {
                    db.syncRecoveryManifestDao().upsertAll(listOf(wrongKind))
                    db.openHelper.writableDatabase.execSQL(corruption)
                    validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                        localOverlay = true, localAcknowledgements = true)
                }
            }.exceptionOrNull()
            val expected = if (domain == ShopSyncRowDomain.HISTORY)
                "recovery_physical_history_v2_payload_mismatch" else "recovery_physical_digest_mismatch_${domain.wireValue}"
            assertEquals(expected, (failure as? ShopSyncContractException)?.code)
            assertNull(db.syncRecoveryManifestDao().get(baseline.generationId, wrongKind.domain, wrongKind.remoteId))
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        }
        assertForeignKeysClean(db)
    }

    @Test
    fun acknowledgedCategoryUsesExactSecondaryFingerprintAndRejectsBodyCorruption() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val created = requireNotNull(repository.addCategory("Same-ID acknowledged category"))
        val sent = mutableListOf<InventoryCategoryRow>()
        val catalog = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> {
                sent += rows
                return Result.success(Unit)
            }
            override suspend fun upsertCategories(rows: List<InventoryCategoryRow>, shopId: String?): Result<Unit> {
                assertEquals(SHOP, shopId)
                return upsertCategories(rows)
            }
        }
        val categoryPush = repository.syncCatalogWithRemote(catalog, NoOpPriceRemoteForRecoveryTest, ACCOUNT,
            CatalogSyncProgressReporter { }, selectedShop())
        val handoff = categoryPush.exceptionOrNull()
        assertTrue(handoff is CanonicalCatalogInboundRequired)
        val outbound = requireNotNull((handoff as CanonicalCatalogInboundRequired).outbound)
        assertEquals(1, outbound.pushedCategories)
        assertEquals(0, outbound.pulledCategories)
        assertFalse(outbound.fullCatalogFetch)
        val ref = requireNotNull(db.categoryRemoteRefDao().getByCategoryId(created.id))
        assertTrue(sent.any { it.id == ref.remoteId })
        assertEquals(ref.localChangeRevision, ref.lastSyncedLocalRevision)
        assertNotNull(db.syncRecoveryManifestDao().get(baseline.generationId,
            LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.CATEGORIES.wireValue, ref.remoteId))
        db.withTransaction {
            validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                localOverlay = true, localAcknowledgements = true)
        }
        val failure = runCatching {
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE categories SET name=? WHERE id=?",
                    arrayOf<Any?>("unrecorded acknowledged category corruption", created.id))
                validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                    localOverlay = true, localAcknowledgements = true)
            }
        }.exceptionOrNull()
        assertEquals("local_ack_physical_body_mismatch", (failure as? ShopSyncContractException)?.code)
        assertEquals(created, db.categoryDao().getById(created.id))
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun nonAckCatalogRowsRetainTextPolicyAndFirstRejectionOrder() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        for (domain in ShopSyncRowDomain.entries) {
            assertTrue(db.syncRecoveryManifestDao().page(
                baseline.generationId, LOCAL_ACK_BODY_PREFIX + domain.wireValue, null, 1000).isEmpty())
        }
        val fields = com.example.merchandisecontrolsplitview.util.CatalogTextField.entries
        val productSql = "UPDATE products SET barcode=?, itemNumber=?, productName=?, secondProductName=?"
        val barcodeTooLong = "b".repeat(com.example.merchandisecontrolsplitview.util.CatalogTextPolicy.Limits.BARCODE + 1)
        val itemTooLong = "i".repeat(com.example.merchandisecontrolsplitview.util.CatalogTextPolicy.Limits.ITEM_NUMBER + 1)
        val nameTooLong = "p".repeat(com.example.merchandisecontrolsplitview.util.CatalogTextPolicy.Limits.PRODUCT_NAME + 1)
        val secondTooLong = "s".repeat(com.example.merchandisecontrolsplitview.util.CatalogTextPolicy.Limits.SECOND_PRODUCT_NAME + 1)
        val cases = listOf(
            Triple("UPDATE suppliers SET name=?", arrayOf<Any?>("s".repeat(com.example.merchandisecontrolsplitview.util.CatalogTextPolicy.Limits.SUPPLIER_NAME + 1)), "SUPPLIER_NAME"),
            Triple("UPDATE categories SET name=?", arrayOf<Any?>("c".repeat(com.example.merchandisecontrolsplitview.util.CatalogTextPolicy.Limits.CATEGORY_NAME + 1)), "CATEGORY_NAME"),
            Triple(productSql, arrayOf<Any?>(barcodeTooLong, itemTooLong, nameTooLong, secondTooLong), "BARCODE"),
            Triple(productSql, arrayOf<Any?>("valid-code", itemTooLong, nameTooLong, secondTooLong), "ITEM_NUMBER"),
            Triple(productSql, arrayOf<Any?>("valid-code", null, nameTooLong, secondTooLong), "PRODUCT_NAME"),
            Triple(productSql, arrayOf<Any?>("valid-code", null, "valid name", secondTooLong), "SECOND_PRODUCT_NAME")
        )
        for ((sql, args, firstField) in cases) {
            val failure = runCatching {
                db.withTransaction {
                    db.openHelper.writableDatabase.execSQL(sql, args)
                    validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                        localOverlay = true, localAcknowledgements = true)
                }
            }.exceptionOrNull()
            assertTrue(failure is com.example.merchandisecontrolsplitview.util.CatalogTextValidationException)
            val rejection = (failure as com.example.merchandisecontrolsplitview.util.CatalogTextValidationException).rejection
            assertEquals(fields.single { it.name == firstField }, rejection.field)
            assertEquals(com.example.merchandisecontrolsplitview.util.CatalogTextPolicy.RejectionReason.TOO_LONG, rejection.reason)
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        }
        assertForeignKeysClean(db)
    }

    @Test
    fun pricePhysicalPageWithoutAcknowledgementsStillRejectsCanonicalCorruption() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val canonicalPrices = db.syncRecoveryManifestDao().page(
            baseline.generationId, ShopSyncRowDomain.PRICES.wireValue, null, 1000)
        assertTrue(canonicalPrices.isNotEmpty())
        assertTrue(db.syncRecoveryManifestDao().page(
            baseline.generationId, LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.PRICES.wireValue, null, 1000).isEmpty())
        db.withTransaction {
            validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                localOverlay = true, localAcknowledgements = true)
        }
        val product = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        val price = db.productPriceDao().getForProducts(listOf(product.id)).single()
        // The same remote ID in a different ACK domain cannot select the PRICES ACK branch.
        // HISTORY follows PRICES, so the asserted PRICES digest failure proves this check is reached first.
        val wrongKind = canonicalPrices.single().copy(domain = LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.HISTORY.wireValue)
        db.syncRecoveryManifestDao().upsertAll(listOf(wrongKind))
        assertTrue(db.syncRecoveryManifestDao().page(
            baseline.generationId, LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.PRICES.wireValue, null, 1000).isEmpty())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_prices SET note='unrecorded canonical price corruption' WHERE id=?", arrayOf(price.id))
        val failure = runCatching {
            db.withTransaction {
                validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                    localOverlay = true, localAcknowledgements = true)
            }
        }.exceptionOrNull()
        assertEquals("recovery_physical_digest_mismatch_prices", (failure as? ShopSyncContractException)?.code)
        assertEquals(canonicalPrices, db.syncRecoveryManifestDao().page(
            baseline.generationId, ShopSyncRowDomain.PRICES.wireValue, null, 1000))
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals("unrecorded canonical price corruption",
            db.productPriceDao().getForProducts(listOf(product.id)).single().note)
        assertForeignKeysClean(db)
    }

    @Test
    fun pricePhysicalPageAcknowledgementsRequireExactDomainIdBodyAndAuthority() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
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
            override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?,
                patch: InventoryProductPatch) = Result.success(Unit)
        }
        val initial = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        repository.updateProduct(initial.copy(retailPrice = 31.0))
        repository.pushDirtyCatalogDeltaToRemote(catalog, prices, ACCOUNT,
            CatalogSyncProgressReporter { }, selectedShop()).getOrThrow()
        assertTrue(acknowledgedPrices.isNotEmpty())
        assertTrue(acknowledgedPrices.all { row -> canonical.getValue(ShopSyncRowDomain.PRICES).none { it.remoteId == row.id } })
        reopenOrdinaryDatabase()
        val priceDomain = LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.PRICES.wireValue
        val proofs = db.syncRecoveryManifestDao().page(baseline.generationId, priceDomain, null, 1000)
        assertEquals(acknowledgedPrices.map { it.id }.toSet(), proofs.map { it.remoteId }.toSet())
        val proof = proofs.first()
        db.withTransaction {
            validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                localOverlay = true, localAcknowledgements = true)
        }

        val acknowledgedPointId = requireNotNull(db.productPriceRemoteRefDao().getByRemoteId(proof.remoteId)).productPriceId
        val acknowledgedPoint = db.productPriceDao().getForProducts(listOf(initial.id)).single { it.id == acknowledgedPointId }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_prices SET note='unrecorded acknowledged price corruption' WHERE id=?", arrayOf(acknowledgedPointId))
        val physicalBody = runCatching {
            db.withTransaction {
                validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                    localOverlay = true, localAcknowledgements = true)
            }
        }.exceptionOrNull()
        assertEquals("local_ack_physical_body_mismatch", (physicalBody as? ShopSyncContractException)?.code)
        db.openHelper.writableDatabase.execSQL("UPDATE product_prices SET note=? WHERE id=?",
            arrayOf<Any?>(acknowledgedPoint.note, acknowledgedPointId))

        // An additional ACK for a missing ID must fail count matching; it cannot authorize another row.
        val missingId = "f7000000-0000-4000-8000-000000000099"
        assertTrue(proofs.none { it.remoteId == missingId })
        assertTrue(canonical.getValue(ShopSyncRowDomain.PRICES).none { it.remoteId == missingId })
        db.syncRecoveryManifestDao().upsertAll(listOf(proof.copy(remoteId = missingId, idLine = missingId)))
        val missing = runCatching {
            db.withTransaction {
                validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                    localOverlay = true, localAcknowledgements = true)
            }
        }.exceptionOrNull()
        assertEquals("recovery_physical_count_mismatch_prices", (missing as? ShopSyncContractException)?.code)
        db.syncRecoveryManifestDao().deleteByRemoteIds(baseline.generationId, priceDomain, listOf(missingId))

        val wrongBody = "local-price-body-v1:" + "0".repeat(64)
        assertTrue(proof.payloadDigest != wrongBody)
        db.syncRecoveryManifestDao().upsertAll(listOf(proof.copy(payloadDigest = wrongBody)))
        val body = runCatching {
            db.withTransaction {
                validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                    localOverlay = true, localAcknowledgements = true)
            }
        }.exceptionOrNull()
        assertEquals("local_ack_physical_body_mismatch", (body as? ShopSyncContractException)?.code)
        db.syncRecoveryManifestDao().upsertAll(proofs)

        val identity = Json.decodeFromString<LocalAcknowledgedBodyIdentity>(proof.versionLine)
        for (wrongIdentity in listOf(identity.copy(revision = identity.revision + 1),
            identity.copy(ownerHash = "0".repeat(64)))) {
            db.syncRecoveryManifestDao().upsertAll(listOf(proof.copy(versionLine = Json.encodeToString(wrongIdentity))))
            val authority = runCatching {
                db.withTransaction {
                    validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                        localOverlay = true, localAcknowledgements = true)
                }
            }.exceptionOrNull()
            assertEquals("local_ack_scope_mismatch", (authority as? ShopSyncContractException)?.code)
            db.syncRecoveryManifestDao().upsertAll(proofs)
        }
        db.withTransaction {
            validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true,
                localOverlay = true, localAcknowledgements = true)
        }
        for ((domain, rows) in canonical) assertEquals(rows, db.syncRecoveryManifestDao().page(
            baseline.generationId, domain.wireValue, null, 1000))
        assertEquals(proofs, db.syncRecoveryManifestDao().page(baseline.generationId, priceDomain, null, 1000))
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun activePendingPartitionRejectsInvalidRevisionWithoutChangingPublication() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val checkpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        repository.addProduct(Product(barcode = "r23-t05-invalid-pending", productName = "Preserve invalid evidence"))
        val saved = requireNotNull(repository.findProductByBarcode("r23-t05-invalid-pending"))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE product_remote_refs SET lastSyncedLocalRevision = localChangeRevision + 1 WHERE productId = ?",
            arrayOf(saved.id))
        val invalid = requireNotNull(db.productRemoteRefDao().getByProductId(saved.id))
        val failure = runCatching {
            db.withTransaction { validateShopSyncActiveReceipt(db, baseline.generationId, checkpoint) }
        }.exceptionOrNull()
        assertNotNull("An invalid pending revision cannot be hidden in the local overlay", failure)
        assertEquals(invalid, db.productRemoteRefDao().getByProductId(saved.id))
        assertEquals(saved, db.productDao().getById(saved.id))
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
    }

    @Test
    fun manualOrdinaryCallerKeepsFullProofWithoutAutomaticPreemptionOptIn() = runBlocking {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        repository = DefaultInventoryRepository(db, tracker, OrdinaryFencedReadFixture(remote))
        val priority = db.ordinaryNoWorkReceiptPriority
        val lease = tracker.captureBusinessDataScopeSignal(ACCOUNT, SHOP).lease
        val writer = priority.reserveWriter(lease.copy(localOnly = true))
        check(priority.checkpointForTest == null)
        priority.checkpointForTest = { error("manual proof must not enter automatic priority context") }
        try {
            val summary = tracker.withBusinessDataScopeFlight(ACCOUNT, selectedShop()) {
                repository.drainSyncEventsFromRemote(NoOpCatalogRemoteForRecoveryTest,
                    NoOpPriceRemoteForRecoveryTest, NoOpSyncEventRemoteForRecoveryTest,
                    ACCOUNT, CatalogSyncProgressReporter { }, selectedShop = selectedShop()).getOrThrow()
            }
            assertFalse(summary.manualFullSyncRequired)
            assertFalse(summary.syncEventsOrdinaryPending)
            assertEquals(0, summary.syncEventsSkippedDirtyLocal)
            assertNull(db.syncRecoveryJournalDao().get())
        } finally {
            priority.releaseWriter(writer)
            priority.checkpointForTest = null
        }
    }

    @Test
    fun ordinaryNoWorkCanonicalPreemptionUsesRealSaveAndFreshAckReceipt() = runBlocking {
        verifyAutomaticReceiptSavePreemption("canonical_row")
    }

    @Test
    fun ordinaryNoWorkPhysicalPreemptionUsesRealSaveAndFreshAckReceipt() = runBlocking {
        verifyAutomaticReceiptSavePreemption("physical_sql_row")
    }

    @Test
    fun ordinaryNoWorkSqlBoundaryProbeDoesNotClaimInFlightSqlCancellation() = runBlocking {
        verifyAutomaticReceiptSavePreemption("integrity_sql_end")
    }

    @Test
    fun ordinaryNoWorkPreemptionStillRollsBackCancelledLocalSaveAndRetriesQuietly() = runBlocking {
        verifyAutomaticReceiptSavePreemption("canonical_row", cancelSaveAfterWrite = true)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun ordinaryAutomaticPushKeepsCommittedAckWhenReceiptDefersForSecondSave() = runBlocking {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.productDao().getAll().single()
        val originalRef = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        val canonicalProduct = requireNotNull(db.syncRecoveryManifestDao().get(
            baseline.generationId, ShopSyncRowDomain.PRODUCTS.wireValue, originalRef.remoteId))
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        // Keep queued retries dormant; this witness controls both consumer invocations explicitly.
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val controls = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val triggers = mutableListOf<String>()
        val firstAck = kotlinx.coroutines.CompletableDeferred<Pair<ProductRemoteRef, SyncRecoveryManifestRow>>()
        val receiptEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writerReserved = kotlinx.coroutines.CompletableDeferred<Unit>()
        val checkpointA = remote.fixture.checkpoint
        var cloudFixture = remote.fixture
        var retainCapturedFenceA = true
        var witnessStage = "prepared"
        val sentPatches = mutableListOf<InventoryProductPatch>()
        val recordedEvents = mutableListOf<SyncEventRemoteRow>()
        val reader = object : ShopSyncReadRemoteDataSource by OrdinaryFencedReadFixture(remote) {
            override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
                if (!retainCapturedFenceA) return remote.checkpoint(context)
                // Deliberately return the earlier captured fence A, while the later marker exposes B.
                // This is the supported A -> advanced-B race, never a false server no-work assertion.
                assertTrue(firstAck.isCompleted)
                assertEquals(1, recordedEvents.size)
                return Result.success(checkpointA.copy(syncEvents = checkpointA.syncEvents.copy(
                    verifiedBaselineId = context.verifiedBaselineId)))
            }

            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> =
                remote.convergenceMarker(context).map { marker ->
                    if (retainCapturedFenceA) {
                        assertEquals("43", marker.syncEvents.maxId)
                        withCompleteAdvancedScan(marker).also { assertFalse(it.serverNoWorkEligible) }
                    } else marker
                }
        }
        repository = DefaultInventoryRepository(db, tracker, reader)
        repository.updateProductFromEditor(original, original.copy(productName = "Push witness first Save"))
        val firstLocal = requireNotNull(db.productDao().getById(original.id))
        val firstDirty = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        assertEquals(originalRef.localChangeRevision + 1, firstDirty.localChangeRevision)
        assertTrue(firstDirty.localChangeRevision > firstDirty.lastSyncedLocalRevision)
        val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun patchProduct(
                id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch
            ): Result<Unit> {
                assertEquals(firstDirty.remoteId, id)
                assertEquals(ACCOUNT, ownerUserId)
                assertEquals(SHOP, shopId)
                assertEquals(setOf("productname"), patch.changedFields)
                assertTrue("Only the two intended Saves may be uploaded", sentPatches.size < 2)
                assertEquals(if (sentPatches.isEmpty()) "Push witness first Save" else "Push witness second Save",
                    patch.productName)
                sentPatches += patch
                return Result.success(Unit)
            }
        }
        val events = object : SyncEventRemoteDataSource by NoOpSyncEventRemoteForRecoveryTest {
            override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
                assertEquals(SyncEventDomains.CATALOG, params.domain)
                assertEquals(SyncEventTypes.CATALOG_CHANGED, params.eventType)
                assertEquals(listOf(firstDirty.remoteId), params.entityIds?.productIds)
                assertEquals(1, params.changedCount)
                assertEquals(recordedEvents.size + 1, sentPatches.size)
                val ack = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
                val proof = requireNotNull(db.syncRecoveryManifestDao().get(baseline.generationId,
                    LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.PRODUCTS.wireValue, ack.remoteId))
                assertEquals(ack.localChangeRevision, ack.lastSyncedLocalRevision)
                assertEquals(ack.lastSyncedLocalRevision.toLong(),
                    Json.decodeFromString<LocalAcknowledgedBodyIdentity>(proof.versionLine).revision)
                assertEquals(ack.lastRemotePayloadFingerprint, proof.payloadDigest)
                assertTrue(db.syncEventOutboxDao().listPending(ACCOUNT, 100).none {
                    it.eventType == BUSINESS_WRITE_EVENT
                })
                if (recordedEvents.isEmpty()) {
                    assertEquals(firstDirty.localChangeRevision, ack.lastSyncedLocalRevision)
                    assertEquals(firstLocal, db.productDao().getById(original.id))
                    assertEquals(baseline, db.syncRecoveryBaselineDao().get())
                    assertTrue(firstAck.complete(ack to proof))
                    witnessStage = "r1_ack_committed"
                }
                val event = SyncEventRemoteRow(id = 43L + recordedEvents.size,
                    ownerUserId = ACCOUNT, shopId = SHOP, storeId = params.storeId,
                    domain = params.domain, eventType = params.eventType,
                    sourceDeviceId = params.sourceDeviceId, clientEventId = params.clientEventId,
                    changedCount = params.changedCount, entityIds = params.entityIds,
                    createdAt = "2026-07-21T12:00:00Z")
                recordedEvents += event
                val rows = (cloudFixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
                    .values.map { it.copy(productName = requireNotNull(sentPatches.last().productName),
                        updatedAt = "2026-07-21T12:00:${event.id - 43L}0.000000Z") }
                cloudFixture = ordinaryDeltaFixture(cloudFixture, rows, event.id,
                    mapOf(SyncEventDomains.CATALOG to event.id.toString()))
                remote.checkpoints += cloudFixture.checkpoint
                remote.tailRows = cloudFixture.rows
                remote.tailEvents = recordedEvents.toList()
                return Result.success(event)
            }
        }
        val prices = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest {
            override val isConfigured = false
        }
        val auto = CatalogAutoSyncCoordinator(repository, cloud, prices, syncEventRemote = events,
            deviceAuthorization = ShopDeviceAuthorizationRepository(ActiveShopDeviceRemoteForOrdinaryTest(SHOP),
                businessDataScopeRuntimeGuard = tracker),
            authFlow = MutableStateFlow(AuthState.SignedIn(ACCOUNT, "push-witness@example.test")),
            selectedShopProvider = { selectedShop() }, syncStateTracker = tracker,
            scope = controls, debounceMs = Long.MAX_VALUE,
            onRecoveryRequired = { triggers += it }, logger = { logs += it })
        val confirmed = CatalogSyncSummary(0, 0, 0, 0, 0, 0)
        tracker.publishSummary(ACCOUNT, CatalogSyncFlightOwner.SYNC_EVENTS, confirmed)
        val previousOutcome = requireNotNull(tracker.lastOutcome.value)
        val priority = db.ordinaryNoWorkReceiptPriority
        check(priority.checkpointForTest == null && priority.traceForTest == null)
        val phases = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        priority.checkpointForTest = { phase ->
            phases += phase
            if (phase == "canonical_row" && receiptEntered.complete(Unit)) writerReserved.await()
            if (phase == "writer_reserved") writerReserved.complete(Unit)
        }
        var consumer: kotlinx.coroutines.Deferred<Unit>? = null
        var secondSave: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            kotlinx.coroutines.withTimeout(15_000L) {
                consumer = async(kotlinx.coroutines.Dispatchers.IO) { auto.runPushCycle("local_commit") }
                receiptEntered.await()
                assertTrue("The actual outbound ACK must already be durable before Save B", firstAck.isCompleted)
                secondSave = async(kotlinx.coroutines.Dispatchers.IO) {
                    repository.updateProductFromEditor(firstLocal,
                        firstLocal.copy(productName = "Push witness second Save"))
                }
                requireNotNull(consumer).await()
                requireNotNull(secondSave).await()
            }
            val (ackBeforeSecondSave, proofBeforeSecondSave) = firstAck.await()
            assertTrue(phases.contains("writer_reserved"))
            assertFalse("Interrupted proof cannot reach its final acceptance", phases.contains("transaction_proved"))
            assertEquals(1, logs.count { it == "cycle=catalog_push outcome=deferred reason=local_writer_pending" })
            assertFalse(logs.any { it.startsWith("cycle=catalog_push outcome=ok") })
            assertFalse(logs.any { it.startsWith("cycle=sync_events_drain") })
            assertEquals(CatalogSyncStatus.IDLE, tracker.state.value.status)
            assertSame(previousOutcome, tracker.lastOutcome.value)
            assertTrue(triggers.isEmpty())
            assertTrue(tracker.tryBegin(CatalogSyncFlightOwner.MANUAL))
            tracker.finish(CatalogSyncFlightOwner.MANUAL)
            val pending = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
            assertEquals("Push witness second Save", db.productDao().getById(original.id)?.productName)
            assertEquals(ackBeforeSecondSave.lastSyncedLocalRevision, pending.lastSyncedLocalRevision)
            assertEquals(ackBeforeSecondSave.localChangeRevision + 1, pending.localChangeRevision)
            assertEquals(ackBeforeSecondSave.lastRemotePayloadFingerprint, pending.lastRemotePayloadFingerprint)
            assertEquals(proofBeforeSecondSave, db.syncRecoveryManifestDao().get(baseline.generationId,
                LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.PRODUCTS.wireValue, pending.remoteId))
            assertEquals(canonicalProduct, db.syncRecoveryManifestDao().get(baseline.generationId,
                ShopSyncRowDomain.PRODUCTS.wireValue, pending.remoteId))
            val retainedEvidence = readLocalBusinessEntries(db, ACCOUNT, activeScope().storeId)
            assertTrue(retainedEvidence.isNotEmpty())
            assertTrue(retainedEvidence.all { it.eventType == BUSINESS_RESOLUTION_EVENT &&
                BusinessResolutionLedger.decode(it).kind == "intent_provenance" })
            val effective = requireNotNull(loadBusinessResolutionSnapshot(db, ACCOUNT, activeScope().storeId,
                retainedEvidence)).requireValid()
            assertEquals(BusinessResolutionLedger.EffectiveRevision.Active,
                effective.effectiveRevision(pending.remoteId, pending.localChangeRevision))
            assertEquals(listOf("Push witness first Save"), sentPatches.map { it.productName })
            assertEquals(listOf(43L), recordedEvents.map { it.id })
            assertOrdinaryUnchangedPublication(baseline)
            witnessStage = "r1_ack_preserved_after_receipt_defer"

            retainCapturedFenceA = false
            priority.checkpointForTest = { phases += it }
            // The next actual push ACKs B and reconciles the complete, correctly fenced remote delta.
            kotlinx.coroutines.withTimeout(15_000L) { auto.runPushCycle("local_commit_push_witness_quiet") }
            assertEquals(CatalogSyncStatus.COMPLETED, tracker.state.value.status)
            assertEquals(listOf("Push witness first Save", "Push witness second Save"), sentPatches.map { it.productName })
            assertEquals(listOf(43L, 44L), recordedEvents.map { it.id })
            val finalAck = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
            assertEquals(pending.localChangeRevision, finalAck.localChangeRevision)
            assertEquals(finalAck.localChangeRevision, finalAck.lastSyncedLocalRevision)
            assertOrdinaryPublishedReceipt(baseline.generationId, cloudFixture.checkpoint, 44L)
            // A separate fresh no-work consumer must traverse the complete proof, with the old token retired.
            phases.clear()
            kotlinx.coroutines.withTimeout(15_000L) { auto.runSyncEventDrainCycle("push_witness_fresh_full_receipt") }
            assertTrue(phases.containsAll(setOf("transaction_acquired", "canonical_row", "physical_sql_row", "transaction_proved")))
            assertEquals(CatalogSyncStatus.COMPLETED, tracker.state.value.status)
            val outcome = requireNotNull(tracker.lastOutcome.value)
            assertFalse(outcome.summary.manualFullSyncRequired)
            assertFalse(outcome.summary.syncEventsOrdinaryPending)
            assertEquals(0, outcome.summary.syncEventsSkippedDirtyLocal)
            val acknowledgedEvidence = readLocalBusinessEntries(db, ACCOUNT, activeScope().storeId)
            assertEquals(acknowledgedEvidence.size, db.syncEventOutboxDao().countAll())
            assertTrue(acknowledgedEvidence.isNotEmpty())
            assertTrue(acknowledgedEvidence.all { it.eventType == BUSINESS_RESOLUTION_EVENT &&
                BusinessResolutionLedger.decode(it).kind == "intent_provenance" })
            assertTrue(acknowledgedEvidence.containsAll(retainedEvidence))
            val acknowledgedProjection = requireNotNull(loadBusinessResolutionSnapshot(db, ACCOUNT,
                activeScope().storeId, acknowledgedEvidence)).requireValid()
            assertEquals(setOf(firstDirty.localChangeRevision, pending.localChangeRevision),
                acknowledgedProjection.intents.map { requireNotNull(it.intent).revision }.toSet())
            assertFalse(repository.hasCatalogCloudPendingWorkInclusive())
            assertTrue(repository.observeProductCloudConfirmed(original.id).first())
            assertTrue(triggers.isEmpty())
            assertNull(db.syncRecoveryJournalDao().get())
            assertEquals("Push witness second Save", db.productDao().getById(original.id)?.productName)
            witnessStage = "fresh_full_receipt_proved"
        } finally {
            writerReserved.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                consumer?.cancel(); secondSave?.cancel()
                consumer?.join(); secondSave?.join()
                auto.shutdown()
                val controlsJob = controls.coroutineContext[kotlinx.coroutines.Job]
                controlsJob?.cancel()
                scheduler.runCurrent()
                controlsJob?.join()
                priority.checkpointForTest = null
            }
            println("PREEMPTION_REAL_PUSH_WITNESS stage=$witnessStage " +
                "sql_tail=UNKNOWN performance=NOT_QUALIFIED")
        }
    }

    /** Small correctness fixture, not a timing qualification or a 20k/40k performance series. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun verifyAutomaticReceiptSavePreemption(
        phase: String, cancelSaveAfterWrite: Boolean = false
    ) = kotlinx.coroutines.coroutineScope {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val original = db.productDao().getAll().single()
        val originalRef = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
        val productCount = db.productDao().getAll().size
        fun priceRowsCount(): Long = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM product_prices").use {
            check(it.moveToFirst()); it.getLong(0)
        }
        val priceCount = priceRowsCount()
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        // Separate scheduler: retry timing advances only when this test explicitly advances it.
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val controls = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val triggers = mutableListOf<String>()
        val auto = ordinaryAutoSync(OrdinaryFencedReadFixture(remote), tracker,
            MutableStateFlow(AuthState.SignedIn(ACCOUNT, "preemption@example.test")),
            ActiveShopDeviceRemoteForOrdinaryTest(SHOP), controls, triggers) { logs.add(it) }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requested = kotlinx.coroutines.CompletableDeferred<Unit>()
        val trace = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val priority = db.ordinaryNoWorkReceiptPriority
        check(priority.checkpointForTest == null && priority.traceForTest == null)
        val oldWriteHook = DefaultInventoryRepositoryTestHooks.afterLocalProductWrite
        check(oldWriteHook == null)
        var consumer: kotlinx.coroutines.Deferred<Unit>? = null
        var save: kotlinx.coroutines.Deferred<Result<Unit>>? = null
        var functional = false
        priority.traceForTest = { name -> trace.putIfAbsent(name, System.nanoTime()) }
        priority.checkpointForTest = { name ->
            if (name == phase && entered.complete(Unit)) requested.await()
            if (name == "writer_reserved") requested.complete(Unit)
        }
        DefaultInventoryRepositoryTestHooks.afterLocalProductWrite = {
            trace.putIfAbsent("local_product_written", System.nanoTime())
            if (cancelSaveAfterWrite) throw CancellationException("preemption_test_local_rollback")
        }
        try {
            kotlinx.coroutines.withTimeout(15_000L) {
                consumer = async(kotlinx.coroutines.Dispatchers.IO) {
                    auto.runSyncEventDrainCycle("preemption_actual_consumer")
                }
                entered.await()
                save = async(kotlinx.coroutines.Dispatchers.IO) {
                    trace.putIfAbsent("save_begin", System.nanoTime())
                    runCatching { repository.updateProductFromEditor(original,
                        original.copy(productName = "Preemption local Save")) }
                        .also { trace.putIfAbsent("save_return", System.nanoTime()) }
                }
                requireNotNull(consumer).await()
                val saved = requireNotNull(save).await()
                if (cancelSaveAfterWrite) assertTrue(saved.exceptionOrNull() is CancellationException)
                else saved.getOrThrow()
            }
            assertTrue(logs.any { it.contains("outcome=deferred reason=local_writer_pending") })
            assertEquals(CatalogSyncStatus.IDLE, tracker.state.value.status)
            assertNull(tracker.lastOutcome.value)
            assertTrue(triggers.isEmpty())
            assertNull(db.syncRecoveryJournalDao().get())
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            assertEquals(42L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
            assertTrue(tracker.tryBegin(CatalogSyncFlightOwner.MANUAL))
            tracker.finish(CatalogSyncFlightOwner.MANUAL)
            val current = requireNotNull(db.productDao().getById(original.id))
            val currentRef = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
            if (cancelSaveAfterWrite) {
                assertEquals(original, current)
                assertEquals(originalRef, currentRef)
            } else {
                assertEquals("Preemption local Save", current.productName)
                assertEquals(originalRef.localChangeRevision + 1, currentRef.localChangeRevision)
                assertTrue(currentRef.localChangeRevision > currentRef.lastSyncedLocalRevision)
            }
            assertEquals(productCount, db.productDao().getAll().size)
            assertEquals(priceCount, priceRowsCount())
            val ordered = listOf("transaction_acquired", "writer_reserved", "preemption_observed", "transaction_unwound")
                .map { requireNotNull(trace[it]) { "missing milestone: $it" } }
            assertTrue(ordered.zipWithNext().all { (a, b) -> b >= a })
            // Commit can release the writer before the receipt caller resumes: do not order against unwound.
            assertTrue(requireNotNull(trace["local_product_written"]) >= requireNotNull(trace["preemption_observed"]))
            assertTrue(requireNotNull(trace["save_return"]) >= requireNotNull(trace["save_begin"]))
            priority.checkpointForTest = null
            DefaultInventoryRepositoryTestHooks.afterLocalProductWrite = oldWriteHook
            if (!cancelSaveAfterWrite) {
                val ackRemote = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
                    override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch) =
                        Result.success(Unit)
                }
                val prices = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest {
                    override val isConfigured = false
                }
                tracker.withBusinessDataScopeFlight(ACCOUNT, selectedShop()) {
                    repository.pushDirtyCatalogDeltaToRemote(ackRemote, prices, ACCOUNT,
                        CatalogSyncProgressReporter { }, selectedShop()).getOrThrow()
                }
                val ack = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
                assertEquals(ack.localChangeRevision, ack.lastSyncedLocalRevision)
            }
            // Fresh ordinary admission proves all stages again; the old token was retired, never reused.
            auto.runSyncEventDrainCycle("preemption_quiet_gap")
            assertEquals(CatalogSyncStatus.COMPLETED, tracker.state.value.status)
            val outcome = requireNotNull(tracker.lastOutcome.value)
            assertFalse(outcome.summary.manualFullSyncRequired)
            assertFalse(outcome.summary.syncEventsOrdinaryPending)
            assertEquals(0, outcome.summary.syncEventsSkippedDirtyLocal)
            assertNull(db.syncRecoveryJournalDao().get())
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            functional = true
        } finally {
            requested.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                consumer?.cancel(); save?.cancel()
                consumer?.join(); save?.join()
                auto.shutdown()
                val controlsJob = controls.coroutineContext[kotlinx.coroutines.Job]
                controlsJob?.cancel()
                scheduler.runCurrent()
                controlsJob?.join()
                priority.checkpointForTest = null
                priority.traceForTest = null
                DefaultInventoryRepositoryTestHooks.afterLocalProductWrite = oldWriteHook
            }
            // Persisted by the test runner stdout even on failure; no latency threshold or PASS inference.
            println("PREEMPTION_TEST_PROBE phase=$phase functional=$functional milestonesNs=${trace.toSortedMap()} " +
                "sql_in_flight_cancellation=UNKNOWN sql_tail_bound=UNKNOWN performance_status=NOT_QUALIFIED")
        }
    }

    @Test
    fun recoveryRequiredEventDrainRetainsQualifiedLocalAccessWhileRecoveryHeld() = runTest {
        verifyOrdinaryRecoveryKeepsLocalAccess(push = false, saveAlreadyStarted = false)
    }

    @Test
    fun recoveryRequiredCatalogPushRetainsQualifiedLocalAccessWhileRecoveryHeld() = runTest {
        verifyOrdinaryRecoveryKeepsLocalAccess(push = true, saveAlreadyStarted = false)
    }

    @Test
    fun recoveryRequiredEventDrainDoesNotCancelAlreadyAdmittedLocalSave() = runTest {
        verifyOrdinaryRecoveryKeepsLocalAccess(push = false, saveAlreadyStarted = true)
    }

    @Test
    fun recoveryRequiredCorruptLocalRowsNeverGrantLocalAccess() = runTest {
        verifyRecoveryRequiredDeniesUnqualifiedSnapshot(corrupt = true)
    }

    @Test
    fun recoveryRequiredForeignBindingNeverGrantsLocalAccess() = runTest {
        verifyRecoveryRequiredDeniesUnqualifiedSnapshot(corrupt = false)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyRecoveryRequiredDeniesUnqualifiedSnapshot(corrupt: Boolean) {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val reader = object : ShopSyncReadRemoteDataSource by remote {
            override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
                if (corrupt) {
                    val row = db.productDao().getAll().single()
                    db.productDao().update(row.copy(productName = "Untracked corruption"))
                } else db.businessDataScopeBindingDao().upsert(BusinessDataScopeBinding.from(oldScope(), 100L))
                return remote.checkpoint(context).map { it.copy(syncEvents = it.syncEvents.copy(requiresFullRecovery = true)) }
            }
        }
        val triggers = mutableListOf<String>()
        val auto = ordinaryAutoSync(reader, tracker, MutableStateFlow(AuthState.SignedIn(ACCOUNT, "qa@example.test")),
            ActiveShopDeviceRemoteForOrdinaryTest(SHOP), backgroundScope, triggers)
        try {
            auto.runSyncEventDrainCycle("unqualified_local_recovery")
            assertEquals(listOf("sync_events_drain"), triggers)
            assertNotNull(db.syncRecoveryJournalDao().get())
            assertFalse(tracker.businessDataScopeState.value.allowsLocalOperations)
            assertFalse(tracker.businessDataScopeState.value.allowsCloudSync)
            val product = db.productDao().getAll().single()
            assertTrue(runCatching { repository.updateProduct(product.copy(productName = "Must not save")) }
                .exceptionOrNull() is Task126BusinessDataScopeChangedException)
            assertEquals(product, db.productDao().getById(product.id))
        } finally { auto.shutdown() }
    }

    @Test
    fun recoveryRequiredPreservesReadOnlyCapabilityWhileKeepingVerifiedRowsAvailable() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val initial = repository.resolveBusinessDataScope(activeScope()).copy(localWritesAllowed = false)
        val tracker = CatalogSyncStateTracker(initial)
        tracker.updateNetworkAvailability(true)
        val reader = object : ShopSyncReadRemoteDataSource by remote {
            override suspend fun checkpoint(context: ShopSyncRpcContext) = remote.checkpoint(context).map {
                it.copy(syncEvents = it.syncEvents.copy(requiresFullRecovery = true))
            }
        }
        val triggers = mutableListOf<String>()
        val auto = ordinaryAutoSync(reader, tracker, MutableStateFlow(AuthState.SignedIn(ACCOUNT, "qa@example.test")),
            ActiveShopDeviceRemoteForOrdinaryTest(SHOP), backgroundScope, triggers)
        try {
            auto.runSyncEventDrainCycle("readonly_recovery")
            assertEquals(listOf("sync_events_drain"), triggers)
            assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
            assertFalse(tracker.businessDataScopeState.value.localWritesAllowed)
            val product = db.productDao().getAll().single()
            assertTrue(runCatching { repository.updateProduct(product.copy(productName = "Denied")) }
                .exceptionOrNull() is Task126BusinessDataScopeChangedException)
            assertEquals(product, db.productDao().getById(product.id))
        } finally { auto.shutdown() }
    }

    @Test
    fun recoveryCompletionDoesNotCancelLocalSaveAdmittedAfterActualCutover() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        repository = DefaultInventoryRepository(db, tracker, remote)
        val product = db.productDao().getAll().single()
        withApplicationRecoveryOwner(tracker, selectedShop()) { application, _, _ ->
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val save = backgroundScope.async {
                runCatching { tracker.withLocalBusinessDataScopeFlight {
                    entered.complete(Unit); release.await()
                    repository.updateProduct(product.copy(productName = "Post-cutover Save"))
                } }
            }
            try {
                entered.await()
                invokeApplicationRecoveryCompletion(application, selectedShop())
                release.complete(Unit)
                assertTrue(save.await().isSuccess)
                assertEquals("Post-cutover Save", db.productDao().getById(product.id)?.productName)
                assertNull(db.syncRecoveryJournalDao().get())
            } finally { release.complete(Unit); save.cancel(); save.join() }
        }
    }

    @Test
    fun recoveryCompletionRetainsCurrentReadOnlyAuthorization() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val readonly = selectedShop().copy(canWrite = false)
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()).copy(localWritesAllowed = false))
        repository = DefaultInventoryRepository(db, tracker, remote)
        withApplicationRecoveryOwner(tracker, readonly) { application, _, _ ->
            invokeApplicationRecoveryCompletion(application, readonly)
            assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
            assertFalse(tracker.businessDataScopeState.value.localWritesAllowed)
            val product = db.productDao().getAll().single()
            assertTrue(runCatching { repository.updateProduct(product.copy(productName = "Denied completion Save")) }
                .exceptionOrNull() is Task126BusinessDataScopeChangedException)
            assertEquals(product, db.productDao().getById(product.id))
        }
    }

    @Test
    fun recoveryCompletionCannotUpgradeAnExplicitLocalCapabilityDenial() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()).copy(
            localReadsAllowed = false, localWritesAllowed = false))
        repository = DefaultInventoryRepository(db, tracker, remote)
        withApplicationRecoveryOwner(tracker, selectedShop()) { application, _, _ ->
            invokeApplicationRecoveryCompletion(application, selectedShop())
            assertFalse(tracker.businessDataScopeState.value.localReadsAllowed)
            assertFalse(tracker.businessDataScopeState.value.localWritesAllowed)
        }
    }

    @Test
    fun recoverySchedulerRechecksRevokedContextBeforeResumingPersistedJournal() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val reader = object : ShopSyncReadRemoteDataSource by remote {
            override suspend fun checkpoint(context: ShopSyncRpcContext) = remote.checkpoint(context).map {
                it.copy(syncEvents = it.syncEvents.copy(requiresFullRecovery = true))
            }
        }
        val auto = ordinaryAutoSync(reader, tracker, MutableStateFlow(AuthState.SignedIn(ACCOUNT, "qa@example.test")),
            ActiveShopDeviceRemoteForOrdinaryTest(SHOP), backgroundScope, mutableListOf())
        try {
            auto.runSyncEventDrainCycle("application_recovery_pending")
            val journal = requireNotNull(db.syncRecoveryJournalDao().get())
            val checkpoints = remote.checkpointCalls
            withApplicationRecoveryOwner(tracker, selectedShop()) { application, _, shops ->
                val mutex = applicationField<kotlinx.coroutines.sync.Mutex>(application, "businessRecoveryExecutionMutex")
                mutex.lock()
                application.requestPendingBusinessRecovery("persisted_journal")
                val scheduled = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
                application.requestPendingBusinessRecovery("duplicate_trigger")
                assertSame(scheduled, applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
                applicationField<MutableStateFlow<ShopContext>>(shops, "mutableState").value = ShopContext.blocked(ACCOUNT, "revoked")
                mutex.unlock()
                scheduled.join()
                assertEquals(checkpoints, remote.checkpointCalls)
                assertEquals(journal, db.syncRecoveryJournalDao().get())
                assertNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
            }
        } finally { auto.shutdown() }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun automaticRecoverySchedulesOneFutureWindowAfterFiveTransientFailuresWithoutAnotherTrigger() = runTest {
        verifyAutomaticRecoveryAfterExhaustedWindow(revokeDuringCooldown = false)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun automaticRecoveryCooldownNeverSendsForRevokedShop() = runTest {
        verifyAutomaticRecoveryAfterExhaustedWindow(revokeDuringCooldown = true)
    }

    @Test
    fun automaticRecoveryResumesPersistedCooldownAfterDatabaseReopen() = runTest {
        verifyAutomaticRecoveryAfterExhaustedWindow(revokeDuringCooldown = false, reopenDuringCooldown = true)
    }

    @Test
    fun automaticRecoveryReobservationKeepsCompletionAndDeadline() = runTest {
        verifyAutomaticRecoveryAfterExhaustedWindow(revokeDuringCooldown = false, observeDuringCooldown = true)
    }

    @Test
    fun automaticRecoverySameShopRefreshDoesNotStrandPersistedCooldown() = runTest {
        verifyAutomaticRecoveryAfterExhaustedWindow(revokeDuringCooldown = false, refreshDuringCooldown = true)
    }

    @Test
    fun automaticRecoveryRealShopChangeNeverSpendsOldScopeCooldown() = runTest {
        verifyAutomaticRecoveryAfterExhaustedWindow(revokeDuringCooldown = false, changeShopDuringCooldown = true)
    }

    @Test
    fun automaticRecoverySameShopRefreshDuringCheckpointDoesNotStrandConsumedClaim() = runTest {
        verifyAutomaticRecoveryContextChangeDuringCheckpoint(changeShop = false)
    }

    @Test
    fun automaticRecoveryRealShopChangeDuringCheckpointNeverReplaysOldScope() = runTest {
        verifyAutomaticRecoveryContextChangeDuringCheckpoint(changeShop = true)
    }

    @Test
    fun automaticRecoveryCheckpointReturningDuringSameShopLoadingDoesNotStrandOrdinaryOpportunity() = runTest {
        verifyAutomaticHealthyRecoveryAfterSameShopRefresh(holdRefreshLoading = true)
    }

    @Test
    fun automaticRecoveryHealthyActivationAfterSameShopRefreshPublishesApplicationReady() = runTest {
        verifyAutomaticHealthyRecoveryAfterSameShopRefresh(holdRefreshLoading = false)
    }

    @Test
    fun automaticRecoveryWaitingScopeCancellationPreservesConsumedIntent() = runTest {
        verifyAutomaticRecoveryWaitingScopeTermination(WaitingScopeTermination.CANCEL)
    }

    @Test
    fun automaticRecoveryWaitingScopeDeviceChangeRejectsHealthyResponse() = runTest {
        verifyAutomaticRecoveryWaitingScopeTermination(WaitingScopeTermination.DEVICE)
    }

    @Test
    fun automaticRecoveryWaitingScopeRevocationRejectsHealthyResponse() = runTest {
        verifyAutomaticRecoveryWaitingScopeTermination(WaitingScopeTermination.REVOKE)
    }

    @Test
    fun automaticRecoveryWaitingScopeDeadlineUsesOnlyRemainingRequestBudget() = runTest {
        verifyAutomaticRecoveryWaitingScopeTermination(WaitingScopeTermination.DEADLINE)
    }

    private enum class WaitingScopeTermination { CANCEL, DEVICE, REVOKE, DEADLINE }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, io.github.jan.supabase.annotations.SupabaseInternal::class)
    private suspend fun kotlinx.coroutines.test.TestScope.verifyAutomaticRecoveryWaitingScopeTermination(
        termination: WaitingScopeTermination
    ) {
        qualifiedAutomaticRecoveryJournal()
        val beforeBaseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val beforeDevice = requireNotNull(db.syncEventDeviceStateDao().get())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        var now = 1_000_000L
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseCheckpoint = kotlinx.coroutines.CompletableDeferred<Unit>()
        val loading = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseRefresh = kotlinx.coroutines.CompletableDeferred<Unit>()
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val waitingObservedAtNanos = java.util.concurrent.atomic.AtomicLong()
        val fetches = java.util.concurrent.atomic.AtomicInteger()
        val rpcStartedAtNanos = java.util.concurrent.atomic.AtomicLong()
        val requestedTimeout = if (termination == WaitingScopeTermination.DEADLINE) 2.seconds else 10.seconds
        // This client supplies configuration only: no API method or network transport is invoked.
        val timeoutClient = createSupabaseClient("http://127.0.0.1:1", "synthetic-public-key") {
            defaultLogLevel = io.github.jan.supabase.logging.LogLevel.NONE
            requestTimeout = requestedTimeout
            install(Postgrest) { timeout = requestedTimeout }
        }
        val timeoutMs = timeoutClient.postgrest.config.timeout.inWholeMilliseconds
        assertEquals("The fixture must use the SDK timeout read by the production invoker", requestedTimeout.inWholeMilliseconds, timeoutMs)
        fun elapsedMs() = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - rpcStartedAtNanos.get())
        fun pumpUntil(message: String, condition: () -> Boolean) {
            repeat(5_000) {
                testScheduler.runCurrent()
                if (condition()) return
                Thread.sleep(1)
            }
            assertTrue(message, condition())
        }
        fun stableRows() = ordinaryMarkerApplicationRows().filterKeys {
            it !in setOf("sync_recovery_journal", "sync_event_device_state")
        }
        remote.beforeCheckpoint = { entered.complete(Unit); releaseCheckpoint.await() }
        ShopSyncRecoveryTestHooks.onScopeResolutionWaiting = {
            waitingObservedAtNanos.set(System.nanoTime())
            waiting.complete(Unit)
        }
        try {
            withApplicationRecoveryOwner(tracker, selectedShop(), recoveryNowMs = { now }, beforeLinkedShopFetch = {
                if (fetches.incrementAndGet() == 2) { loading.complete(Unit); releaseRefresh.await() }
            }) { application, auth, shops ->
                val observedRemote = object : ShopSyncReadRemoteDataSource by remote {
                    override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
                        rpcStartedAtNanos.set(System.nanoTime())
                        recordRecoveryRpcTimeout(timeoutMs)
                        val result = remote.checkpoint(context)
                        if (termination == WaitingScopeTermination.DEADLINE) {
                            // Model real response latency on the transport dispatcher, charging it to the same budget.
                            kotlinx.coroutines.delay(timeoutMs * 3L / 4L)
                        }
                        return result
                    }
                }
                val scoped = coordinator(nowMs = { now }, recoveryRemote = observedRemote, scopeStillValid = { owner, shop ->
                    val current = shops.state.value
                    (auth.state.value as? AuthState.SignedIn)?.userId == owner && current.ownerUserId == owner &&
                        !current.isLoading && current.syncAllowed && current.activeShopId == shop
                }, activationBoundary = { tracker.withBusinessDataScopeTransition { it() } })
                application.javaClass.getDeclaredField("shopSyncRecoveryCoordinator\$delegate").apply { isAccessible = true }
                    .set(application, lazyOf(scoped))
                var refreshFlight: kotlinx.coroutines.Deferred<Unit>? = null
                try {
                    application.javaClass.getDeclaredMethod("observeShopContextForRemoteComponents")
                        .apply { isAccessible = true }.invoke(application)
                    application.requestPendingBusinessRecovery("task144_wait_termination")
                    pumpUntil("A real checkpoint must enter after its durable claim") { entered.isCompleted }
                    val origin = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
                    refreshFlight = backgroundScope.async { shops.refresh(ACCOUNT) }
                    pumpUntil("The real same-shop refresh must be held Loading") { loading.isCompleted }
                    releaseCheckpoint.complete(Unit)
                    pumpUntil("The actual combined authority flow must be waiting before the terminating action") { waiting.isCompleted }
                    assertTrue(origin.isActive)
                    assertTrue(shops.state.value.isLoading)
                    val atWait = requireNotNull(runBlocking { db.syncRecoveryJournalDao().get() })
                    val claim = requireNotNull(AutomaticRecoveryRetryMetadata.decode(atWait.reason))
                    assertEquals("in_flight", claim.state)
                    assertEquals(1, atWait.attemptCount)
                    assertEquals(1, claim.windowAttempts)
                    val rowsAtWait = stableRows()
                    val checkpointsAtWait = remote.checkpointCalls
                    val pagesAtWait = remote.pageCalls
                    when (termination) {
                        WaitingScopeTermination.CANCEL -> origin.cancel(CancellationException("fixture explicit cancellation while waiting"))
                        WaitingScopeTermination.DEVICE -> runBlocking {
                            db.withTransaction {
                                db.openHelper.writableDatabase.execSQL(
                                    "UPDATE sync_event_device_state SET deviceId = ? WHERE id = 1",
                                    arrayOf("task144-replaced-install-identity"))
                            }
                        }
                        WaitingScopeTermination.REVOKE -> {
                            shops.invalidateLocalAuthorization(ACCOUNT, SHOP)
                            assertFalse("The real revocation must remove local authorization", shops.state.value.localAccessAllowed)
                            assertFalse(shops.state.value.syncAllowed)
                        }
                        WaitingScopeTermination.DEADLINE -> {
                            val waitingElapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                waitingObservedAtNanos.get() - rpcStartedAtNanos.get())
                            assertTrue("RPC time must already consume most of the configured request budget", waitingElapsedMs >= timeoutMs * 3L / 4L)
                            assertTrue("WAIT must be observed before the original request deadline", waitingElapsedMs < timeoutMs)
                        }
                    }
                    pumpUntil("The owned invocation must settle while refresh is still unresolved") { origin.isCompleted }
                    if (termination == WaitingScopeTermination.DEADLINE) {
                        assertFalse("Deadline expiry is a refused continuation, not an invented task cancellation", origin.isCancelled)
                        assertTrue("A still-Loading invocation must actually wait through the remaining budget", elapsedMs() >= timeoutMs - 2L)
                        assertTrue("The wait must expire against the original deadline, not receive a fresh timeout", elapsedMs() < timeoutMs + timeoutMs / 4L)
                    }
                    assertFalse(applicationField<kotlinx.coroutines.sync.Mutex>(application, "businessRecoveryExecutionMutex").isLocked)
                    val settled = requireNotNull(runBlocking { db.syncRecoveryJournalDao().get() })
                    val evidence = requireNotNull(AutomaticRecoveryRetryMetadata.decode(settled.reason))
                    assertEquals(atWait.ownerHash, settled.ownerHash)
                    assertEquals(atWait.storeScope, settled.storeScope)
                    assertEquals(atWait.deviceId, settled.deviceId)
                    assertEquals(atWait.authorizationMode, settled.authorizationMode)
                    assertEquals(atWait.createdAtMs, settled.createdAtMs)
                    assertEquals(claim.claimID, evidence.claimID)
                    assertEquals(1, settled.attemptCount)
                    assertEquals(1, evidence.windowAttempts)
                    assertEquals(SyncErrorCategory.Unknown, evidence.category)
                    assertTrue(evidence.state in setOf("in_flight", "blocked"))
                    assertEquals(checkpointsAtWait, remote.checkpointCalls)
                    assertEquals(pagesAtWait, remote.pageCalls)
                    assertEquals(beforeBaseline, runBlocking { db.syncRecoveryBaselineDao().get() })
                    assertEquals(rowsAtWait, stableRows())
                    releaseRefresh.complete(Unit)
                    pumpUntil("The held refresh must settle without replaying the old response") { refreshFlight.isCompleted }
                    refreshFlight.await()
                    now += 300_001L
                    testScheduler.advanceTimeBy(300_001L)
                    testScheduler.runCurrent()
                    application.requestPendingBusinessRecovery("task144_wait_termination_future_opportunity")
                    repeat(100) { testScheduler.runCurrent(); Thread.sleep(1) }
                    assertEquals("Unknown/cancelled/revoked claims never renew merely because time passed", checkpointsAtWait, remote.checkpointCalls)
                    assertEquals(pagesAtWait, remote.pageCalls)
                    assertEquals(beforeBaseline, runBlocking { db.syncRecoveryBaselineDao().get() })
                    assertEquals(rowsAtWait, stableRows())
                    assertEquals(1, runBlocking { db.syncRecoveryJournalDao().get() }?.attemptCount)
                    assertEquals(if (termination == WaitingScopeTermination.DEVICE)
                        beforeDevice.copy(deviceId = "task144-replaced-install-identity") else beforeDevice,
                        runBlocking { db.syncEventDeviceStateDao().get() })
                } finally {
                    releaseCheckpoint.complete(Unit); releaseRefresh.complete(Unit)
                    refreshFlight?.let { it.cancel(); it.join() }
                }
            }
        } finally { timeoutClient.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun kotlinx.coroutines.test.TestScope.verifyAutomaticHealthyRecoveryAfterSameShopRefresh(holdRefreshLoading: Boolean) {
        qualifiedAutomaticRecoveryJournal()
        val beforeProduct = db.productDao().getAll().single()
        val beforeBaseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        var now = 1_000_000L
        val checkpointEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseCheckpoint = kotlinx.coroutines.CompletableDeferred<Unit>()
        val checkpointReturned = kotlinx.coroutines.CompletableDeferred<Unit>()
        val postRpcLoadingObserved = kotlinx.coroutines.CompletableDeferred<Unit>()
        val refreshLoading = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseRefresh = kotlinx.coroutines.CompletableDeferred<Unit>()
        val linkedFetches = java.util.concurrent.atomic.AtomicInteger()
        val checkpointEntries = java.util.concurrent.atomic.AtomicInteger()
        var claimAtTransport: SyncRecoveryJournal? = null
        remote.beforeCheckpoint = {
            if (checkpointEntries.incrementAndGet() == 1) {
                claimAtTransport = db.syncRecoveryJournalDao().get()
                checkpointEntered.complete(Unit)
                releaseCheckpoint.await()
            }
        }
        fun pumpUntil(message: String, condition: () -> Boolean) {
            for (step in 0 until 5_000) {
                testScheduler.runCurrent()
                if (condition()) return
                Thread.sleep(1)
            }
            assertTrue(message, condition())
        }
        withApplicationRecoveryOwner(tracker, selectedShop(), recoveryNowMs = { now }, beforeLinkedShopFetch = {
            if (linkedFetches.incrementAndGet() == 2 && holdRefreshLoading) {
                refreshLoading.complete(Unit)
                releaseRefresh.await()
            }
        }) { application, auth, shops ->
            val returnedWhileLoading = java.util.concurrent.atomic.AtomicBoolean()
            val observedRemote = object : ShopSyncReadRemoteDataSource by remote {
                override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
                    // Synthetic transport declares its own bounded timeout; production reads the actual client config.
                    recordRecoveryRpcTimeout(90_000L)
                    val result = remote.checkpoint(context)
                    if (!checkpointReturned.isCompleted) {
                        returnedWhileLoading.set(shops.state.value.isLoading)
                        checkpointReturned.complete(Unit)
                    }
                    return result
                }
            }
            val scopedCoordinator = coordinator(nowMs = { now }, recoveryRemote = observedRemote, scopeStillValid = { owner, shop ->
                val current = shops.state.value
                val allowed = (auth.state.value as? AuthState.SignedIn)?.userId == owner && current.ownerUserId == owner &&
                    !current.isLoading && current.syncAllowed && current.activeShopId == shop
                if (checkpointReturned.isCompleted && current.isLoading) postRpcLoadingObserved.complete(Unit)
                allowed
            }, activationBoundary = { tracker.withBusinessDataScopeTransition { it() } })
            application.javaClass.getDeclaredField("shopSyncRecoveryCoordinator\$delegate").apply { isAccessible = true }
                .set(application, lazyOf(scopedCoordinator))
            var refreshFlight: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                application.javaClass.getDeclaredMethod("observeShopContextForRemoteComponents")
                    .apply { isAccessible = true }.invoke(application)
                application.requestPendingBusinessRecovery("task144_healthy_checkpoint_before_refresh")
                pumpUntil("The healthy checkpoint must enter after its durable claim") { checkpointEntered.isCompleted }
                val claimed = requireNotNull(claimAtTransport)
                assertEquals(1, claimed.attemptCount)
                assertEquals("in_flight", AutomaticRecoveryRetryMetadata.decode(claimed.reason)?.state)
                val oldFlight = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
                val epoch = shops.diagnosticShopEpoch()
                val ownedRefresh = backgroundScope.async { shops.refresh(ACCOUNT) }
                refreshFlight = ownedRefresh
                if (holdRefreshLoading) {
                    pumpUntil("The real refresh must stay Loading while its linked-shop response is held") { refreshLoading.isCompleted }
                    assertTrue(shops.state.value.isLoading)
                    assertEquals(SHOP, shops.state.value.activeShopId)
                    assertTrue(shops.state.value.localAccessAllowed)
                    releaseCheckpoint.complete(Unit)
                    pumpUntil("The healthy remote checkpoint must return before the held refresh is resolved") { checkpointReturned.isCompleted }
                    assertTrue("The result must actually return while the real shop context is Loading", returnedWhileLoading.get())
                    pumpUntil("The real post-RPC authority predicate must observe the held Loading context") { postRpcLoadingObserved.isCompleted }
                    releaseRefresh.complete(Unit)
                }
                pumpUntil("The same-shop refresh must resolve its actual authority") { ownedRefresh.isCompleted }
                ownedRefresh.await()
                assertTrue(shops.diagnosticShopEpoch() > epoch)
                assertEquals(SHOP, shops.state.value.activeShopId)
                assertFalse(shops.state.value.isLoading)
                assertTrue(shops.state.value.syncAllowed)
                if (!holdRefreshLoading) {
                    // Observe the refreshed context while the recovery journal still exists, before activation.
                    repeat(100) { testScheduler.runCurrent(); Thread.sleep(1) }
                    assertNotNull(runBlocking { db.syncRecoveryJournalDao().get() })
                    releaseCheckpoint.complete(Unit)
                }
                pumpUntil("The original recovery invocation must settle") { oldFlight.isCompleted }
                if (holdRefreshLoading) {
                    val settledJournal = runBlocking { db.syncRecoveryJournalDao().get() }
                    val settledRetry = settledJournal?.let { AutomaticRecoveryRetryMetadata.decode(it.reason) }
                    // Observe the actual result before time/admission can change it; omit identity and payload fields.
                    println("task144_loading_settlement journalPresent=${settledJournal != null} " +
                        "code=${settledRetry?.code ?: "unclassified"} state=${settledRetry?.state ?: "none"} " +
                        "category=${settledRetry?.category?.name ?: "none"} cumulative=${settledJournal?.attemptCount} " +
                        "window=${settledRetry?.windowAttempts} completedAt=${settledRetry?.lastCompletedAtMs} " +
                        "nextEligibleAt=${settledRetry?.nextEligibleAtMs} oldFlightCancelled=${oldFlight.isCancelled} " +
                        "checkpointEntries=${checkpointEntries.get()}")
                    now += 300_001L
                    testScheduler.advanceTimeBy(300_001L)
                    testScheduler.runCurrent()
                    application.requestPendingBusinessRecovery("task144_ordinary_opportunity_after_loading_resolved")
                }
                pumpUntil("A healthy same-shop recovery must finish its durable journal after refresh") {
                    runBlocking { db.syncRecoveryJournalDao().get() } == null
                }
                val afterBaseline = requireNotNull(runBlocking { db.syncRecoveryBaselineDao().get() })
                assertFalse("The actual coordinator must publish a new durable generation", beforeBaseline.generationId == afterBaseline.generationId)
                validateShopSyncActiveReceipt(db, afterBaseline.generationId, decodeRecoveryCheckpointJson(afterBaseline.checkpointJson))
                assertEquals(beforeProduct.productName, runBlocking { db.productDao().getAll().single() }.productName)
                // Durable activation is a separate proof from the Application publishing its normal usable state.
                pumpUntil("A verified healthy activation must also publish Application READY without another trigger") {
                    tracker.businessDataScopeState.value.status == Task126BusinessDataScopeStatus.READY
                }
                val published = tracker.businessDataScopeState.value
                assertNull(published.errorCode)
                assertTrue(published.localReadsAllowed)
                assertTrue(published.localWritesAllowed)
                assertTrue(tracker.allowsBusinessDataScope(ACCOUNT, selectedShop()))
                assertFalse(applicationField<kotlinx.coroutines.sync.Mutex>(application, "businessRecoveryExecutionMutex").isLocked)
            } finally {
                releaseCheckpoint.complete(Unit)
                releaseRefresh.complete(Unit)
                refreshFlight?.let { it.cancel(); it.join() }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun kotlinx.coroutines.test.TestScope.verifyAutomaticRecoveryContextChangeDuringCheckpoint(changeShop: Boolean) {
        qualifiedAutomaticRecoveryJournal()
        val beforeProduct = db.productDao().getAll().single()
        val beforeBaseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        var now = 1_000_000L
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entries = java.util.concurrent.atomic.AtomicInteger()
        val observedClaim = java.util.concurrent.atomic.AtomicReference<SyncRecoveryJournal?>()
        remote.checkpointFailure = java.io.IOException("task144_in_flight_refresh_offline")
        remote.beforeCheckpoint = {
            if (entries.incrementAndGet() == 1) {
                observedClaim.set(db.syncRecoveryJournalDao().get())
                entered.complete(Unit)
                release.await()
            }
        }
        val otherShop = "90000000-0000-4000-8000-000000000144"
        val linked = listOf(
            LinkedShop(SHOP, null, selectedShop().name, selectedShop().role, "active", selectable = true, canWrite = true),
            LinkedShop(otherShop, null, "Other synthetic shop", "owner", "active", selectable = true, canWrite = true))
        withApplicationRecoveryOwner(tracker, selectedShop(), recoveryNowMs = { now }, linkedShops = linked) { application, auth, shops ->
            // Use the production coordinator's changing auth/shop predicate, rather than the fixture's fixed scope.
            val scopedCoordinator = coordinator(nowMs = { now }, scopeStillValid = { owner, shop ->
                val current = shops.state.value
                (auth.state.value as? AuthState.SignedIn)?.userId == owner && current.ownerUserId == owner &&
                    !current.isLoading && current.syncAllowed && current.activeShopId == shop
            }, activationBoundary = { tracker.withBusinessDataScopeTransition { it() } })
            application.javaClass.getDeclaredField("shopSyncRecoveryCoordinator\$delegate").apply { isAccessible = true }
                .set(application, lazyOf(scopedCoordinator))
            try {
                application.javaClass.getDeclaredMethod("observeShopContextForRemoteComponents")
                    .apply { isAccessible = true }.invoke(application)
                application.requestPendingBusinessRecovery("task144_in_flight_context_change")
                for (step in 0 until 5_000) {
                    testScheduler.runCurrent()
                    if (entered.isCompleted) break
                    Thread.sleep(1)
                }
                assertTrue("The real checkpoint must be held after the durable claim, before changing context", entered.isCompleted)
                val claim = requireNotNull(observedClaim.get())
                val metadata = requireNotNull(AutomaticRecoveryRetryMetadata.decode(claim.reason))
                assertEquals(1, claim.attemptCount)
                assertEquals(1, metadata.windowAttempts)
                assertEquals("in_flight", metadata.state)
                val oldFlight = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
                val epoch = shops.diagnosticShopEpoch()
                if (changeShop) assertTrue(shops.selectShop(otherShop)) else shops.refresh(ACCOUNT)
                assertTrue("The real shop operation advances its epoch", shops.diagnosticShopEpoch() > epoch)
                assertEquals(if (changeShop) otherShop else SHOP, shops.state.value.activeShopId)
                assertTrue(shops.state.value.syncAllowed)
                release.complete(Unit)
                for (step in 0 until 5_000) {
                    testScheduler.runCurrent()
                    if (oldFlight.isCompleted) break
                    Thread.sleep(1)
                }
                assertTrue("The held invocation must settle before a new ordinary opportunity", oldFlight.isCompleted)
                assertFalse(applicationField<kotlinx.coroutines.sync.Mutex>(application, "businessRecoveryExecutionMutex").isLocked)
                val settled = runBlocking { db.syncRecoveryJournalDao().get() }
                assertEquals(beforeProduct, runBlocking { db.productDao().getAll().single() })
                assertEquals(beforeBaseline, runBlocking { db.syncRecoveryBaselineDao().get() })
                remote.checkpointFailure = null
                now += 300_001L
                testScheduler.advanceTimeBy(300_001L)
                testScheduler.runCurrent()
                // An existing foreground/reconnect opportunity must not require resetting the consumed claim.
                application.requestPendingBusinessRecovery("task144_ordinary_opportunity_after_in_flight_refresh")
                for (step in 0 until 5_000) {
                    testScheduler.runCurrent()
                    if ((!changeShop && entries.get() > 1) ||
                        (changeShop && applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")?.isCompleted != false)) break
                    Thread.sleep(1)
                }
                if (changeShop) {
                    assertEquals("A real shop change must never replay the old scope", 1, entries.get())
                    assertEquals(settled, runBlocking { db.syncRecoveryJournalDao().get() })
                    assertEquals(beforeProduct, runBlocking { db.productDao().getAll().single() })
                    assertEquals(beforeBaseline, runBlocking { db.syncRecoveryBaselineDao().get() })
                } else {
                    assertTrue("A same-shop refresh must not strand a consumed claim; ordinary opportunity produced " +
                        "${entries.get()} checkpoint entries, settled metadata=${settled?.let { AutomaticRecoveryRetryMetadata.decode(it.reason) }}",
                        entries.get() > 1)
                }
            } finally { release.complete(Unit) }
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun newlyConfirmedMismatchRunsActualProducerAndApplicationRecoveryWithoutResettingDiagnosticCount() = runTest {
        seedOldMismatchGeneration()
        db.syncRecoveryJournalDao().deleteAll() // Synthetic precondition: no confirmation or remote recovery attempt yet.
        val initial = repository.resolveBusinessDataScope(activeScope())
        assertEquals(Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH, initial.status)
        val tracker = CatalogSyncStateTracker(initial)
        tracker.updateNetworkAvailability(true)
        val beforeProduct = db.productDao().getAll().single()
        val beforeOutbox = db.syncEventOutboxDao().countAll()
        val claimAtTransport = java.util.concurrent.atomic.AtomicReference<SyncRecoveryJournal?>()
        remote.beforeCheckpoint = { claimAtTransport.set(db.syncRecoveryJournalDao().get()) }
        remote.checkpointFailure = java.io.IOException("task144_new_confirmation_offline")
        withApplicationRecoveryOwner(tracker, selectedShop(), recoveryNowMs = System::currentTimeMillis) { application, _, _ ->
            // This is the real confirmation entry point, including its repository producer and scheduler.
            application.replaceMismatchedLocalBusinessDataAndBind()
            for (step in 0 until 5_000) {
                testScheduler.runCurrent()
                if (claimAtTransport.get() != null) break
                Thread.sleep(1)
            }
            assertTrue("A new user confirmation must reach the real checkpoint without erasing diagnostic count1",
                claimAtTransport.get() != null)
            val claim = requireNotNull(claimAtTransport.get())
            assertEquals(SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED, claim.authorizationMode)
            assertEquals("Producer diagnostic1 plus one durable automatic claim", 2, claim.attemptCount)
            assertEquals(1, AutomaticRecoveryRetryMetadata.decode(claim.reason)?.windowAttempts)
            assertEquals(beforeProduct, runBlocking { db.productDao().getAll().single() })
            assertEquals(beforeOutbox, runBlocking { db.syncEventOutboxDao().countAll() })
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun automaticRecoveryFirstRetryUsesContractOneToThreeSecondDelay() = runTest {
        verifyAutomaticFirstRetryDelay(jitter = 1.0, expectedDelay = 2_000L)
    }

    @Test
    fun automaticRecoverySeededMinimumJitterSchedulesFirstRetryAtOneSecond() = runTest {
        verifyAutomaticFirstRetryDelay(jitter = 0.5, expectedDelay = 1_000L)
    }

    @Test
    fun automaticRecoverySeededMaximumJitterSchedulesFirstRetryAtThreeSeconds() = runTest {
        verifyAutomaticFirstRetryDelay(jitter = 1.5, expectedDelay = 3_000L)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun kotlinx.coroutines.test.TestScope.verifyAutomaticFirstRetryDelay(jitter: Double, expectedDelay: Long) {
        qualifiedAutomaticRecoveryJournal()
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        remote.checkpointFailure = java.io.IOException("task144_cadence_offline")
        val callsBefore = remote.checkpointCalls
        var now = 1_000_000L
        withApplicationRecoveryOwner(tracker, selectedShop(), recoveryNowMs = { now }, automaticRetryJitter = { jitter }) { application, _, _ ->
            application.requestPendingBusinessRecovery("task144_automatic_retry_cadence")
            var completed: AutomaticRecoveryRetryMetadata? = null
            for (step in 0 until 5_000) {
                testScheduler.runCurrent()
                val observed = runBlocking { db.syncRecoveryJournalDao().get() }
                    ?.let { AutomaticRecoveryRetryMetadata.decode(it.reason) }
                if (observed?.state == "waiting") { completed = observed; break }
                Thread.sleep(1)
            }
            val first = requireNotNull(completed)
            assertEquals(callsBefore + 1, remote.checkpointCalls)
            val delay = first.nextEligibleAtMs - requireNotNull(first.lastCompletedAtMs)
            assertTrue("AND-R02: actual automatic failure must schedule its first retry within 1000..3000ms; actual=$delay",
                delay in 1_000L..3_000L)
            assertEquals("Seeded jitter must control the real automatic deadline", expectedDelay, delay)
            now += 999L
            testScheduler.advanceTimeBy(999L); testScheduler.runCurrent()
            assertEquals("No transport before the contract minimum", callsBefore + 1, remote.checkpointCalls)
            now += 2_001L
            testScheduler.advanceTimeBy(2_001L); testScheduler.runCurrent()
            for (step in 0 until 5_000) {
                testScheduler.runCurrent()
                if (remote.checkpointCalls >= callsBefore + 2) break
                Thread.sleep(1)
            }
            assertTrue("Existing automatic scheduler must actually deliver the retry by 3s", remote.checkpointCalls >= callsBefore + 2)
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun automaticRecoverySaturatedDiagnosticCountDoesNotPermanentlyDisableTransientWindow() = runTest {
        val original = qualifiedAutomaticRecoveryJournal()
        val metadata = AutomaticRecoveryRetryMetadata("recovery_failed", SyncErrorCategory.NetworkOfflineOrTimeout,
            5, "cooldown", 300_000L, "70000000-0000-4000-8000-000000000145", 0L, 0L)
        db.syncRecoveryJournalDao().upsert(original.copy(reason = metadata.encode(),
            attemptCount = SYNC_RECOVERY_MAX_RECORDED_ATTEMPTS, nextRetryAtMs = 300_000L, updatedAtMs = 0L))
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val callsBefore = remote.checkpointCalls
        remote.checkpointFailure = java.io.IOException("task144_saturated_diagnostic_counter")
        withApplicationRecoveryOwner(tracker, selectedShop()) { application, _, _ ->
            application.requestPendingBusinessRecovery("task144_saturated_counter_due_transient")
            for (step in 0 until 5_000) {
                testScheduler.runCurrent()
                if (remote.checkpointCalls > callsBefore) break
                Thread.sleep(1)
            }
            assertEquals("A saturated historical diagnostic must not permanently disable a due transient window", callsBefore + 1,
                remote.checkpointCalls)
            val observed = runBlocking { db.syncRecoveryJournalDao().get() }
            assertEquals("The diagnostic cumulative count never resets", SYNC_RECOVERY_MAX_RECORDED_ATTEMPTS, observed?.attemptCount)
        }
    }

    @Test
    fun legacyExhaustedRecoveryResumesOnlyItsAcceptedPrefixThroughApplicationAfterReopen() = runTest {
        seedVerifiedSameScopeRecovery()
        val oldBaseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val localBefore = db.productDao().getAll()
        remote = RecoveryRemoteFixture(mixedRetainedPriceFixture())
        val paged = PagedRecoveryRemoteFixture(remote)
        val requests = mutableListOf<Pair<ShopSyncRowDomain, String?>>()
        val order = mutableListOf<String>()
        var historicalFailure = true
        val reader = object : ShopSyncReadRemoteDataSource by paged {
            override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
                if (!historicalFailure) order += "checkpoint"
                return paged.checkpoint(context)
            }
            override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain,
                afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
                requests += domain to afterId
                if (!historicalFailure) order += "page"
                if (domain == ShopSyncRowDomain.PRICES && afterId != null && historicalFailure) {
                    return Result.failure(java.io.IOException("legacy interrupted same-scope download"))
                }
                return paged.recoveryPage(context, domain, afterId, limit)
            }
        }
        // The direct pre-metadata producer persists raw reason/count after each actual failed RPC.
        repeat(5) {
            assertTrue(coordinator(recoveryRemote = reader).recover(ACCOUNT, selectedShop(), activeScope())
                is ShopSyncRecoveryResult.RetryRequired)
        }
        val legacy = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals("recovery_failed", legacy.reason)
        assertEquals(5, legacy.attemptCount)
        assertEquals(SyncRecoveryJournalPhases.STAGING, legacy.phase)
        assertEquals(SyncRecoveryAuthorizationModes.SAME_SCOPE, legacy.authorizationMode)
        assertNull(AutomaticRecoveryRetryMetadata.decode(legacy.reason))
        val stage = openDatabase(requireNotNull(legacy.stagingDatabaseName))
        val accepted = try {
            val progress = RecoveryPageProgress(stage.openHelper.writableDatabase)
            assertEquals(legacy.checkpointADigest, progress.checkpoint(legacy).checkpointDigest)
            requireNotNull(progress.accepted(ShopSyncRowDomain.PRICES)).also {
                assertTrue(it.rows > 0)
                assertFalse(it.complete)
                validatePhysicalSnapshot(stage, requireNotNull(legacy.runId))
            }
        } finally { stage.close() }
        assertEquals(oldBaseline, db.syncRecoveryBaselineDao().get())
        assertEquals(localBefore, db.productDao().getAll())
        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)
        assertEquals("Legacy journal survives actual Room reopen", legacy, db.syncRecoveryJournalDao().get())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
        tracker.updateNetworkAvailability(true)
        historicalFailure = false
        val beforeRequests = requests.size
        withApplicationRecoveryOwner(tracker, selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 1_400_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_upgrade")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
        }
        assertTrue("The existing application coordinator must automatically requalify exhausted legacy work", order.isNotEmpty())
        assertEquals("Current checkpoint requalification precedes any resumed page", "checkpoint", order.first())
        val resumedPages = requests.drop(beforeRequests)
        assertEquals(ShopSyncRowDomain.PRICES to accepted.afterId, resumedPages.first())
        assertEquals("The accepted first price page must never be downloaded twice", 1,
            requests.count { it.first == ShopSyncRowDomain.PRICES && it.second == null })
        assertEquals("Upgrade continues the existing generation, never a replacement run", legacy.runId,
            db.syncRecoveryBaselineDao().get()?.generationId)
        assertNull(db.syncRecoveryJournalDao().get())
        val final = requireNotNull(db.syncRecoveryBaselineDao().get())
        validateShopSyncActiveReceipt(db, final.generationId, decodeRecoveryCheckpointJson(final.checkpointJson))
    }

    @Test
    fun legacyRecoveryRejectsHeaderOnlyWithoutChangingStoredRows() = runTest {
        verifyLegacyPrefixRejected { stage ->
            stage.clearAllTables()
            stage.openHelper.writableDatabase.execSQL("DELETE FROM recovery_transfer_progress")
        }
    }

    @Test
    fun legacyRecoveryRejectsMissingManifestRowsWithoutRepair() = runTest {
        verifyLegacyPrefixRejected { stage ->
            stage.openHelper.writableDatabase.execSQL("DELETE FROM sync_recovery_manifest WHERE domain='prices'")
        }
    }

    @Test
    fun legacyRecoveryRejectsCursorThatDoesNotNameLastAcceptedRow() = runTest {
        verifyLegacyPrefixRejected { stage ->
            stage.openHelper.writableDatabase.execSQL("UPDATE recovery_transfer_progress SET afterId='ffffffff-ffff-4fff-8fff-ffffffffffff' WHERE domain='prices'")
        }
    }

    @Test
    fun legacyRecoveryRejectsCorruptedPhysicalBodyWithoutRepair() = runTest {
        verifyLegacyPrefixRejected { stage ->
            val product = stage.productDao().getAll().first()
            stage.productDao().update(product.copy(productName = "Unrecorded legacy corruption"))
        }
    }

    @Test
    fun legacyRecoveryRejectsHeaderFromAnotherDeviceWithoutRepair() = runTest {
        verifyLegacyPrefixRejected { stage ->
            stage.openHelper.writableDatabase.execSQL("UPDATE recovery_transfer_header SET deviceId='other-device'")
        }
    }

    @Test
    fun legacyRecoveryRejectsInvalidChronologyAuthorityAndMarkerWithoutRpc() = runTest {
        val (legacy, reader) = seedLegacyAcceptedRecoveryPrefix()
        val tracker = legacyRecoveryTracker()
        val marker = LegacyRecoveryResume(requireNotNull(legacy.runId), legacy.reason, requireNotNull(legacy.checkpointADigest))
        val retry = AutomaticRecoveryRetryMetadata("recovery_failed", SyncErrorCategory.NetworkOfflineOrTimeout,
            5, "cooldown", 1_400_000L, "70000000-0000-4000-8000-000000000155", 1_000_000L, 1_000_000L, marker)
        assertEquals(retry, AutomaticRecoveryRetryMetadata.decode(retry.encode()))
        val v1 = retry.copy(legacyResume = null)
        assertEquals(v1, AutomaticRecoveryRetryMetadata.decode(v1.encode()))
        for (bad in listOf(
            legacy.copy(createdAtMs = -1L), legacy.copy(updatedAtMs = legacy.createdAtMs - 1L),
            legacy.copy(updatedAtMs = Long.MAX_VALUE), legacy.copy(nextRetryAtMs = null),
            legacy.copy(nextRetryAtMs = -1L), legacy.copy(deviceId = "another-device"),
            legacy.copy(runId = "70000000-0000-4000-8000-000000000156"),
            legacy.copy(authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED),
            legacy.copy(reason = retry.encode().replace("\"v\":2", "\"v\":1")),
            legacy.copy(reason = retry.encode().replace("\"originalReason\":\"recovery_failed\"", "\"originalReason\":\"Unknown\"")),
            legacy.copy(reason = retry.encode().replace("\"legacyResume\":{", "\"legacyResume\":{\"extra\":true,"))
        )) {
            db.syncRecoveryJournalDao().upsert(bad)
            val before = ordinaryMarkerApplicationRows()
            val calls = remote.checkpointCalls
            withApplicationRecoveryOwner(tracker, selectedShop(), recoveryRemote = reader,
                recoveryNowMs = { 1_400_000L }) { application, _, _ ->
                application.requestPendingBusinessRecovery("legacy_recovery_negative_metadata")
                requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
            }
            assertEquals(calls, remote.checkpointCalls)
            assertEquals(before, ordinaryMarkerApplicationRows())
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun legacyRecoveryCancellationConsumesDiagnosticClaimAcrossReopen() = runTest {
        val (legacy, reader) = seedLegacyAcceptedRecoveryPrefix()
        val stageBefore = legacyStageRows(legacy)
        val localBefore = ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" }
        var entries = 0
        var observed: SyncRecoveryJournal? = null
        remote.beforeCheckpoint = {
            entries++
            observed = db.syncRecoveryJournalDao().get()
            throw CancellationException("legacy diagnostic interrupted after durable claim")
        }
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 1_400_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_cancel")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
        }
        assertEquals(1, entries)
        val claim = requireNotNull(observed)
        val metadata = requireNotNull(AutomaticRecoveryRetryMetadata.decode(claim.reason))
        assertEquals(6, claim.attemptCount)
        assertEquals(5, metadata.windowAttempts)
        assertEquals(SyncErrorCategory.Unknown, metadata.category)
        assertEquals("in_flight", metadata.state)
        assertEquals(legacy.runId, metadata.legacyResume?.runId)
        assertEquals(stageBefore, legacyStageRows(legacy))
        assertEquals(localBefore, ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
        reopenOrdinaryDatabase()
        remote.beforeCheckpoint = { entries++ }
        var durableWaiting: SyncRecoveryJournal? = null
        repeat(2) {
            withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
                recoveryNowMs = { 5_000_000L }) { application, _, _ ->
                application.requestPendingBusinessRecovery("legacy_recovery_reopen_consumed")
                val ownedWait = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
                // The interrupted claim now owns a durable future opportunity. Do not join an
                // intentionally waiting scheduler while its injected wall clock remains fixed.
                for (step in 0 until 2_000) {
                    testScheduler.runCurrent()
                    val current = runBlocking { db.syncRecoveryJournalDao().get() }
                    if (AutomaticRecoveryRetryMetadata.decode(current?.reason.orEmpty())?.state == "requalifying" ||
                        ownedWait.isCompleted) break
                    Thread.sleep(1)
                }
                val waiting = requireNotNull(db.syncRecoveryJournalDao().get())
                val retry = requireNotNull(AutomaticRecoveryRetryMetadata.decode(waiting.reason))
                assertEquals("requalifying", retry.state)
                assertEquals(5_300_000L, retry.nextEligibleAtMs)
                assertEquals(retry.nextEligibleAtMs, waiting.nextRetryAtMs)
                assertEquals(6, waiting.attemptCount)
                assertEquals(5, retry.windowAttempts)
                assertEquals(metadata.claimID, retry.claimID)
                assertEquals(metadata.legacyResume, retry.legacyResume)
                assertEquals(SyncErrorCategory.Unknown, retry.category)
                assertEquals(testSha256(claim.reason), retry.previousClaimSha256)
                assertEquals(1, entries)
                durableWaiting?.let { previous -> assertEquals(previous, waiting) }
                durableWaiting = waiting
                ownedWait.cancel()
                ownedWait.join()
            }
            reopenOrdinaryDatabase()
            assertEquals(durableWaiting, db.syncRecoveryJournalDao().get())
        }
        assertEquals(1, entries)
        assertEquals(localBefore, ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
        assertEquals(stageBefore, legacyStageRows(legacy))
    }

    @Test
    fun legacyRecoveryCheckpointSnapshotChangeStopsBeforePagesAndPreservesPrefix() = runTest {
        val (legacy, reader) = seedLegacyAcceptedRecoveryPrefix()
        val stageBefore = legacyStageRows(legacy)
        val localBefore = ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" }
        val old = remote.fixture.checkpoint
        remote.checkpoints = mutableListOf(old.copy(syncEvents = old.syncEvents.copy(requiresFullRecovery = true)))
        val beforeCalls = remote.checkpointCalls
        val beforePages = remote.pageCalls
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 1_400_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_snapshot_changed")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
        }
        assertEquals(beforeCalls + 1, remote.checkpointCalls)
        assertEquals(beforePages, remote.pageCalls)
        val settled = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals(legacy.runId, settled.runId)
        assertEquals("blocked", AutomaticRecoveryRetryMetadata.decode(settled.reason)?.state)
        assertEquals(legacy.checkpointADigest, settled.checkpointADigest)
        assertEquals(stageBefore, legacyStageRows(legacy))
        assertEquals(localBefore, ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun legacyRecoveryFreshTimeoutPreservesLineageAndCooldownBeforeSameRunSuccess() = runTest {
        val (legacy, reader) = seedLegacyAcceptedRecoveryPrefix()
        val beforeCalls = remote.checkpointCalls
        val beforePages = remote.pageCalls
        val stageBefore = legacyStageRows(legacy)
        remote.checkpointFailure = legacyRecoveryHttpFailure("601")
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 1_400_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_fresh_timeout")
            awaitLegacyRecoveryState("cooldown")
        }
        val failed = requireNotNull(db.syncRecoveryJournalDao().get())
        val metadata = requireNotNull(AutomaticRecoveryRetryMetadata.decode(failed.reason))
        assertEquals(beforeCalls + 1, remote.checkpointCalls)
        assertEquals(beforePages, remote.pageCalls)
        assertEquals(5, metadata.windowAttempts)
        assertEquals(6, failed.attemptCount)
        assertEquals(SyncErrorCategory.NetworkOfflineOrTimeout, metadata.category)
        assertEquals(2_001_000L, metadata.nextEligibleAtMs)
        assertEquals(legacy.runId, metadata.legacyResume?.runId)
        assertEquals(legacy.stagingDatabaseName, failed.stagingDatabaseName)
        assertEquals(stageBefore, legacyStageRows(legacy))
        reopenOrdinaryDatabase()
        remote.checkpointFailure = null
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 2_000_999L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_before_new_floor")
            repeat(100) { testScheduler.runCurrent(); Thread.sleep(1) }
            assertEquals(beforeCalls + 1, remote.checkpointCalls)
            assertEquals(failed, db.syncRecoveryJournalDao().get())
        }
        var successor: SyncRecoveryJournal? = null
        remote.beforeCheckpoint = { if (successor == null) successor = db.syncRecoveryJournalDao().get() }
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 2_001_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_successor_due")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
        }
        val next = requireNotNull(successor)
        assertEquals(legacy.runId, next.runId)
        assertEquals(7, next.attemptCount)
        assertEquals(1, AutomaticRecoveryRetryMetadata.decode(next.reason)?.windowAttempts)
        assertEquals(metadata.legacyResume, AutomaticRecoveryRetryMetadata.decode(next.reason)?.legacyResume)
        assertEquals(legacy.runId, db.syncRecoveryBaselineDao().get()?.generationId)
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun legacyRecoveryTransientSuccessorNeverCreatesMissingStage() = runTest {
        val (legacy, reader) = seedLegacyAcceptedRecoveryPrefix()
        remote.checkpointFailure = java.io.IOException("current diagnostic timeout")
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 1_400_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_timeout_before_missing_stage")
            awaitLegacyRecoveryState("cooldown")
        }
        val calls = remote.checkpointCalls
        val stageName = requireNotNull(legacy.stagingDatabaseName)
        assertTrue(app.deleteDatabase(stageName)) // Synthetic loss outside the recovery owner.
        reopenOrdinaryDatabase()
        val before = ordinaryMarkerApplicationRows()
        remote.checkpointFailure = null
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 1_700_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_existing_stage_required")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
        }
        assertEquals(calls, remote.checkpointCalls)
        assertEquals(before, ordinaryMarkerApplicationRows())
        assertFalse(app.getDatabasePath(stageName).exists())
    }

    @Test
    fun legacyRecoveryMissingStageAfterPreflightCannotFallbackToFreshRun() = runTest {
        val (legacy, reader) = seedLegacyAcceptedRecoveryPrefix()
        val calls = remote.checkpointCalls
        val before = ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" }
        var clockReads = 0
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = {
                clockReads++
                if (clockReads == 1) assertTrue(app.deleteDatabase(requireNotNull(legacy.stagingDatabaseName)))
                1_400_000L
            }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_stage_lost_after_preflight")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
        }
        assertTrue("Clock is read only after successful prefix preflight", clockReads > 0)
        assertEquals(calls, remote.checkpointCalls)
        assertEquals(legacy.runId, db.syncRecoveryJournalDao().get()?.runId)
        assertEquals(before, ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
        assertFalse(app.getDatabasePath(requireNotNull(legacy.stagingDatabaseName)).exists())
    }

    @Test
    fun legacyRecoveryStaleCheckpointCannotCompleteReplacedRun() = runTest {
        val (legacy, reader) = seedLegacyAcceptedRecoveryPrefix()
        val beforePages = remote.pageCalls
        val stageBefore = legacyStageRows(legacy)
        var replacement: SyncRecoveryJournal? = null
        remote.beforeCheckpoint = {
            val current = requireNotNull(db.syncRecoveryJournalDao().get())
            replacement = current.copy(runId = "70000000-0000-4000-8000-000000000157")
            db.syncRecoveryJournalDao().upsert(requireNotNull(replacement))
        }
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 1_400_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_stale_run_callback")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
        }
        assertNotNull(replacement)
        assertEquals(beforePages, remote.pageCalls)
        assertEquals("Old callback cannot complete metadata on a replaced run", replacement, db.syncRecoveryJournalDao().get())
        assertEquals(stageBefore, legacyStageRows(legacy))
    }

    @Test
    fun legacyRecoveryLeaseRevokedDuringCheckpointCannotRequestPages() = runTest {
        val (legacy, reader) = seedLegacyAcceptedRecoveryPrefix()
        val beforePages = remote.pageCalls
        val stageBefore = legacyStageRows(legacy)
        var leaseCurrent = true
        remote.beforeCheckpoint = { leaseCurrent = false }
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryLease = { _, _ -> leaseCurrent }, recoveryNowMs = { 1_400_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_revoked_lease")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
        }
        assertFalse("Checkpoint must actually have crossed the revocation boundary", leaseCurrent)
        assertEquals(beforePages, remote.pageCalls)
        assertEquals(legacy.runId, db.syncRecoveryJournalDao().get()?.runId)
        assertEquals(stageBefore, legacyStageRows(legacy))
    }

    private fun legacyRecoveryHttpFailure(retryAfter: String): Exception {
        val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
        val call = io.mockk.mockk<io.ktor.client.call.HttpClientCall>()
        val request = io.mockk.mockk<io.ktor.client.request.HttpRequest>()
        io.mockk.every { response.status } returns io.ktor.http.HttpStatusCode.GatewayTimeout
        io.mockk.every { response.headers } returns io.ktor.http.headersOf(io.ktor.http.HttpHeaders.RetryAfter, retryAfter)
        io.mockk.every { response.call } returns call
        io.mockk.every { call.request } returns request
        io.mockk.every { request.url } returns io.ktor.http.Url("https://example.test/rest/v1/rpc/shop_sync_recovery_checkpoint_v1")
        io.mockk.every { request.headers } returns io.ktor.http.Headers.Empty
        io.mockk.every { request.method } returns io.ktor.http.HttpMethod.Post
        return ShopSyncContractException("shop_sync_rpc_http_504").apply {
            initCause(io.ktor.client.plugins.ResponseException(response, "shop_sync_rpc_http_504"))
        }
    }

    private suspend fun seedLegacyAcceptedRecoveryPrefix(): Pair<SyncRecoveryJournal, ShopSyncReadRemoteDataSource> {
        seedVerifiedSameScopeRecovery()
        remote = RecoveryRemoteFixture(mixedRetainedPriceFixture())
        val paged = PagedRecoveryRemoteFixture(remote)
        var failSecondPrice = true
        val reader = object : ShopSyncReadRemoteDataSource by paged {
            override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain,
                afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
                if (failSecondPrice && domain == ShopSyncRowDomain.PRICES && afterId != null) {
                    return Result.failure(java.io.IOException("historical interrupted prefix"))
                }
                return paged.recoveryPage(context, domain, afterId, limit)
            }
        }
        repeat(5) { assertTrue(coordinator(recoveryRemote = reader).recover(ACCOUNT, selectedShop(), activeScope())
            is ShopSyncRecoveryResult.RetryRequired) }
        val legacy = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals("recovery_failed", legacy.reason)
        assertEquals(5, legacy.attemptCount)
        assertEquals(SyncRecoveryJournalPhases.STAGING, legacy.phase)
        reopenOrdinaryDatabase()
        assertEquals(legacy, db.syncRecoveryJournalDao().get())
        failSecondPrice = false
        return legacy to reader
    }

    private suspend fun legacyRecoveryTracker() = CatalogSyncStateTracker(
        repository.resolveBusinessDataScope(activeScope())
    ).also { assertTrue(it.businessDataScopeState.value.allowsLocalOperations); it.updateNetworkAvailability(true) }

    private fun legacyStageRows(journal: SyncRecoveryJournal): Map<String, List<List<String>>> =
        openDatabase(requireNotNull(journal.stagingDatabaseName)).let { stage ->
            try { ordinaryMarkerApplicationRows(stage) } finally { stage.close() }
        }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyLegacyPrefixRejected(mutate: suspend (AppDatabase) -> Unit) {
        val (legacy, reader) = seedLegacyAcceptedRecoveryPrefix()
        val stage = openDatabase(requireNotNull(legacy.stagingDatabaseName))
        try { mutate(stage) } finally { stage.close() }
        val activeBefore = ordinaryMarkerApplicationRows()
        val stageBefore = legacyStageRows(legacy)
        val calls = remote.checkpointCalls
        withApplicationRecoveryOwner(legacyRecoveryTracker(), selectedShop(), recoveryRemote = reader,
            recoveryNowMs = { 1_400_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("legacy_recovery_invalid_prefix")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
        }
        assertEquals("Invalid prefix never invokes checkpoint", calls, remote.checkpointCalls)
        assertEquals(activeBefore, ordinaryMarkerApplicationRows())
        assertEquals(stageBefore, legacyStageRows(legacy))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun kotlinx.coroutines.test.TestScope.awaitLegacyRecoveryState(expected: String) {
        for (step in 0 until 5_000) {
            testScheduler.runCurrent()
            if (db.syncRecoveryJournalDao().get()?.let { AutomaticRecoveryRetryMetadata.decode(it.reason)?.state } == expected) return
            Thread.sleep(1)
        }
        throw AssertionError("Recovery did not reach $expected: ${db.syncRecoveryJournalDao().get()}")
    }

    @Test
    fun automaticRecoveryNeverRenewsLegacyUnknownOrMalformedMetadata() = runTest {
        val original = qualifiedAutomaticRecoveryJournal()
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        assertTrue("Upgrade fixture has qualified same-scope local data", tracker.businessDataScopeState.value.allowsLocalOperations)
        val callsBefore = remote.checkpointCalls
        withApplicationRecoveryOwner(tracker, selectedShop()) { application, _, _ ->
            for ((reason, attempts) in listOf("recovery_failed" to 5, "Unknown" to 5,
                "{\"v\":2}" to 0, "{broken" to 0)) {
                val persisted = original.copy(reason = reason, attemptCount = attempts)
                db.syncRecoveryJournalDao().upsert(persisted)
                val before = ordinaryMarkerApplicationRows()
                application.requestPendingBusinessRecovery("task144_legacy_or_unknown_metadata")
                requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
                assertEquals("Unproven metadata never authorizes a remote attempt", callsBefore, remote.checkpointCalls)
                assertEquals(persisted, db.syncRecoveryJournalDao().get())
                assertEquals("Upgrade preserves every local row, journal and budget", before, ordinaryMarkerApplicationRows())
            }
        }
    }

    @Test
    fun automaticRecoveryCanonicalConfirmationRequiresTheExactInitialProducerState() = runTest {
        val original = qualifiedAutomaticRecoveryJournal()
        val initial = original.copy(reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
            authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            attemptCount = 1, nextRetryAtMs = original.createdAtMs)
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val callsBefore = remote.checkpointCalls
        withApplicationRecoveryOwner(tracker, selectedShop()) { application, _, _ ->
            val invalidIntents = listOf(initial.copy(attemptCount = 0), initial.copy(attemptCount = 2),
                initial.copy(attemptCount = 5), initial.copy(authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE),
                initial.copy(phase = SyncRecoveryJournalPhases.STAGING), initial.copy(runId = "prior-run"),
                initial.copy(stagingDatabaseName = "prior-staging.db"), initial.copy(checkpointADigest = "prior-a"),
                initial.copy(checkpointBDigest = "prior-b"), initial.copy(blockingEventId = 12L),
                initial.copy(createdAtMs = -1L, updatedAtMs = -1L, nextRetryAtMs = -1L),
                initial.copy(updatedAtMs = initial.createdAtMs + 1L), initial.copy(nextRetryAtMs = 0L),
                initial.copy(shopId = "90000000-0000-4000-8000-000000000144"), initial.copy(deviceId = ""))
            for (intent in invalidIntents) {
                db.syncRecoveryJournalDao().upsert(intent)
                val before = ordinaryMarkerApplicationRows()
                application.requestPendingBusinessRecovery("task144_invalid_initial_confirmation")
                requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
                assertEquals("A mutated confirmation never acquires remote authority", callsBefore, remote.checkpointCalls)
                assertEquals("Invalid intent preserves every local row, journal and budget", before, ordinaryMarkerApplicationRows())
            }
        }
    }

    @Test
    fun automaticRecoveryRejectsIncoherentCooldownDeadlineWithoutTouchingValidStore() = runTest {
        val original = qualifiedAutomaticRecoveryJournal()
        val callsBefore = remote.checkpointCalls
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        withApplicationRecoveryOwner(tracker, selectedShop()) { application, _, _ ->
            val invalidDue = AutomaticRecoveryRetryMetadata("recovery_failed", SyncErrorCategory.NetworkOfflineOrTimeout,
                5, "cooldown", 0L, "70000000-0000-4000-8000-000000000144", 1_000_000L, 1_000_000L)
            for (metadata in listOf(invalidDue, invalidDue.copy(nextEligibleAtMs = 1_300_000L, lastCompletedAtMs = 999_999L))) {
                db.syncRecoveryJournalDao().upsert(original.copy(reason = metadata.encode(), attemptCount = 5,
                    updatedAtMs = 1_000_000L, nextRetryAtMs = metadata.nextEligibleAtMs))
                val before = ordinaryMarkerApplicationRows()
                application.requestPendingBusinessRecovery("task144_corrupt_cooldown_deadline")
                requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
                assertEquals(callsBefore, remote.checkpointCalls)
                assertEquals(before, ordinaryMarkerApplicationRows())
            }
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun automaticRecoveryRechecksNetworkAfterWaitingForExecutionMutex() = runTest {
        qualifiedAutomaticRecoveryJournal()
        val before = ordinaryMarkerApplicationRows()
        val callsBefore = remote.checkpointCalls
        val clockReads = java.util.concurrent.atomic.AtomicInteger()
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        withApplicationRecoveryOwner(tracker, selectedShop(), recoveryNowMs = {
            clockReads.incrementAndGet(); 1_000_000L
        }) { application, _, _ ->
            val mutex = applicationField<kotlinx.coroutines.sync.Mutex>(application, "businessRecoveryExecutionMutex")
            mutex.lock()
            try {
                application.requestPendingBusinessRecovery("task144_network_drop_behind_owner")
                val scheduled = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
                for (step in 0 until 1_000) {
                    testScheduler.runCurrent()
                    if (clockReads.get() > 0) break
                    Thread.sleep(1)
                }
                assertTrue("Admission reached the held execution mutex", clockReads.get() > 0)
                assertTrue(scheduled.isActive)
                tracker.updateNetworkAvailability(false)
                mutex.unlock()
                scheduled.join()
                assertEquals(callsBefore, remote.checkpointCalls)
                assertEquals(before, ordinaryMarkerApplicationRows())
            } finally {
                if (mutex.isLocked) mutex.unlock()
            }
        }
    }

    private suspend fun qualifiedAutomaticRecoveryJournal(): SyncRecoveryJournal {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
        val journal = SyncRecoveryJournal(ownerHash = activeScope().ownerHash, storeScope = activeScope().storeId,
            shopId = SHOP, deviceId = DEVICE, authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED,
            blockingEventId = null, attemptCount = 0, createdAtMs = 1_000_000L, updatedAtMs = 1_000_000L,
            nextRetryAtMs = 0L)
        db.syncRecoveryJournalDao().upsert(journal)
        return journal
    }

    @Test
    fun automaticRecoveryClaimsBudgetBeforeCheckpointAndNeverRefundsInterruptedClaimAfterReopen() = runTest {
        seedOldMismatchGeneration()
        db.syncRecoveryJournalDao().get()!!.let { db.syncRecoveryJournalDao().upsert(it.copy(attemptCount = 0,
            reason = SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED)) }
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState(
            status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, errorCode = "sync_recovery_required"))
        tracker.updateNetworkAvailability(true)
        var checkpointEntries = 0
        var observedClaim: SyncRecoveryJournal? = null
        remote.beforeCheckpoint = {
            checkpointEntries++
            observedClaim = db.syncRecoveryJournalDao().get()
            throw CancellationException("task144_synthetic_process_interruption")
        }
        withApplicationRecoveryOwner(tracker, selectedShop()) { application, _, _ ->
            application.requestPendingBusinessRecovery("task144_claim_before_checkpoint")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
            val observed = requireNotNull(observedClaim)
            val metadata = requireNotNull(AutomaticRecoveryRetryMetadata.decode(observed.reason))
            assertEquals(1, observed.attemptCount)
            assertEquals(1, metadata.windowAttempts)
            assertEquals("in_flight", metadata.state)
            assertEquals(1, checkpointEntries)
            val interrupted = requireNotNull(db.syncRecoveryJournalDao().get())
            assertEquals(1, interrupted.attemptCount)
            reopenOrdinaryDatabase()
            assertEquals(interrupted, db.syncRecoveryJournalDao().get())
            remote.beforeCheckpoint = null
            application.javaClass.getDeclaredField("database\$delegate").apply { isAccessible = true }.set(application, lazyOf(db))
            application.javaClass.getDeclaredField("repository\$delegate").apply { isAccessible = true }.set(application, lazyOf(repository))
            application.javaClass.getDeclaredField("shopSyncRecoveryCoordinator\$delegate").apply { isAccessible = true }
                .set(application, lazyOf(coordinator()))
            application.requestPendingBusinessRecovery("task144_restart_unknown_in_flight")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
            assertEquals("Unknown interrupted attempt is never silently refunded", interrupted, db.syncRecoveryJournalDao().get())
            assertEquals(0, remote.checkpointCalls)
            assertEquals(1, checkpointEntries)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun interruptedClaimPersistsOneRequalificationDeadlineAcrossReopenWithoutRefundingAttempt() = runTest {
        val original = qualifiedAutomaticRecoveryJournal()
        val oldClaim = AutomaticRecoveryRetryMetadata(
            code = SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED,
            category = SyncErrorCategory.Unknown, windowAttempts = 1, state = "in_flight",
            nextEligibleAtMs = 900_000L, claimID = java.util.UUID.randomUUID().toString(),
            lastAttemptAtMs = 900_000L, lastCompletedAtMs = null)
        val interrupted = original.copy(reason = oldClaim.encode(), attemptCount = 1,
            createdAtMs = 900_000L, updatedAtMs = 900_000L, nextRetryAtMs = 900_000L)
        db.syncRecoveryJournalDao().upsert(interrupted)
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val callsBefore = remote.checkpointCalls
        var now = 1_000_000L
        withApplicationRecoveryOwner(tracker, selectedShop(), recoveryNowMs = { now }) { application, _, _ ->
            application.requestPendingBusinessRecovery("r4_interrupted_first_qualification")
            val firstJob = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
            for (step in 0 until 2_000) {
                testScheduler.runCurrent()
                if (runBlocking { db.syncRecoveryJournalDao().get() }?.reason != interrupted.reason || firstJob.isCompleted) break
                Thread.sleep(1)
            }
            val waiting = requireNotNull(db.syncRecoveryJournalDao().get())
            val metadata = requireNotNull(AutomaticRecoveryRetryMetadata.decode(waiting.reason))
            assertTrue("R4-T06: a dead interrupted claim gets a durable requalification deadline, not a permanent consumed latch",
                metadata.nextEligibleAtMs >= now + 300_000L)
            assertEquals("Local qualification must not refund or consume a cloud attempt", 1, waiting.attemptCount)
            assertEquals(callsBefore, remote.checkpointCalls)
            val due = metadata.nextEligibleAtMs
            firstJob.cancel(); firstJob.join()
            reopenOrdinaryDatabase()
            assertEquals(waiting, db.syncRecoveryJournalDao().get())
            application.javaClass.getDeclaredField("database\$delegate").apply { isAccessible = true }.set(application, lazyOf(db))
            application.javaClass.getDeclaredField("repository\$delegate").apply { isAccessible = true }.set(application, lazyOf(repository))
            application.javaClass.getDeclaredField("shopSyncRecoveryCoordinator\$delegate").apply { isAccessible = true }
                .set(application, lazyOf(coordinator(nowMs = { now })))
            now = due - 1L
            application.requestPendingBusinessRecovery("r4_interrupted_reopen_before_due")
            val resumed = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
            testScheduler.runCurrent()
            assertEquals("Reopen cannot move the persisted first deadline", due,
                AutomaticRecoveryRetryMetadata.decode(requireNotNull(db.syncRecoveryJournalDao().get()).reason)?.nextEligibleAtMs)
            assertEquals(callsBefore, remote.checkpointCalls)
            val claims = mutableListOf<SyncRecoveryJournal>()
            remote.beforeCheckpoint = { db.syncRecoveryJournalDao().get()?.let { claims += it } }
            now = due
            testScheduler.advanceTimeBy(1L)
            testScheduler.runCurrent()
            resumed.join()
            assertTrue("The first due opportunity must requalify through the existing recovery path", claims.isNotEmpty())
            assertTrue("The consumed original attempt stays counted", claims.all { it.attemptCount >= 2 })
            assertEquals("One owned invocation after requalification", 1,
                claims.mapNotNull { AutomaticRecoveryRetryMetadata.decode(it.reason)?.claimID }.toSet().size)
        }
    }

    private suspend fun interruptedBeforeFirstRecoveryPage(): SyncRecoveryJournal {
        val original = qualifiedAutomaticRecoveryJournal()
        val metadata = AutomaticRecoveryRetryMetadata(
            code = SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED,
            category = SyncErrorCategory.Unknown, windowAttempts = 1, state = "in_flight",
            nextEligibleAtMs = 1_000_000L, claimID = java.util.UUID.randomUUID().toString(),
            lastAttemptAtMs = 1_000_000L, lastCompletedAtMs = null)
        db.syncRecoveryJournalDao().upsert(original.copy(reason = metadata.encode(), attemptCount = 1))
        val fatal = OutOfMemoryError("owned interruption before first accepted page")
        remote.fatalAtDomain = ShopSyncRowDomain.SUPPLIERS to fatal
        var observed: OutOfMemoryError? = null
        try { coordinator().recover(ACCOUNT, selectedShop(), activeScope(), automaticClaimID = metadata.claimID) }
        catch (error: OutOfMemoryError) { observed = error }
        finally { remote.fatalAtDomain = null }
        assertSame(fatal, observed?.cause ?: observed)
        val interrupted = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals(metadata.encode(), interrupted.reason)
        assertEquals(SyncRecoveryJournalPhases.STAGING, interrupted.phase)
        assertNotNull(interrupted.checkpointADigest)
        openDatabase(requireNotNull(interrupted.stagingDatabaseName)).let { stage ->
            try {
                for (table in listOf("sync_recovery_manifest", "recovery_transfer_progress", "products", "suppliers", "categories", "product_prices", "history_entries")) {
                    stage.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
                        assertTrue(it.moveToFirst()); assertEquals("No accepted page or business row before interruption: $table", 0L, it.getLong(0))
                    }
                }
            } finally { stage.close() }
        }
        return interrupted
    }

    @Test
    fun interruptedBeforeFirstAcceptedPagePersistsRequalificationDeadline() = runTest {
        val interrupted = interruptedBeforeFirstRecoveryPage()
        val callsBefore = remote.checkpointCalls
        assertTrue("A current typed orphan can have a valid checkpoint and zero accepted pages",
            coordinator().qualifyInterruptedAutomaticRecovery(interrupted, ACCOUNT, selectedShop(), activeScope()))
        assertEquals("Qualification cannot change the journal", interrupted, db.syncRecoveryJournalDao().get())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        withApplicationRecoveryOwner(tracker, selectedShop(), recoveryNowMs = { 1_000_000L }) { application, _, _ ->
            application.requestPendingBusinessRecovery("zero_prefix_orphan")
            val job = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
            for (step in 0 until 2_000) {
                testScheduler.runCurrent()
                if (runBlocking { db.syncRecoveryJournalDao().get() }?.reason != interrupted.reason || job.isCompleted) break
                Thread.sleep(1)
            }
            val waiting = requireNotNull(db.syncRecoveryJournalDao().get())
            val metadata = requireNotNull(AutomaticRecoveryRetryMetadata.decode(waiting.reason))
            assertEquals("requalifying", metadata.state)
            assertTrue(metadata.nextEligibleAtMs >= 1_300_000L)
            assertEquals(1, waiting.attemptCount)
            assertEquals(interrupted.runId, waiting.runId)
            assertEquals(callsBefore, remote.checkpointCalls)
            job.cancel(); job.join()
            reopenOrdinaryDatabase()
            assertEquals(waiting, db.syncRecoveryJournalDao().get())
        }
    }

    @Test
    fun interruptedEmptyPrefixRejectsUnmanifestedBusinessRowWithoutRepair() = runTest {
        val interrupted = interruptedBeforeFirstRecoveryPage()
        openDatabase(requireNotNull(interrupted.stagingDatabaseName)).let { stage ->
            try { stage.productDao().insert(Product(barcode = "unmanifested-owned-row", productName = "unqualified")) }
            finally { stage.close() }
        }
        val callsBefore = remote.checkpointCalls
        assertFalse(coordinator().qualifyInterruptedAutomaticRecovery(interrupted, ACCOUNT, selectedShop(), activeScope()))
        assertEquals(interrupted, db.syncRecoveryJournalDao().get())
        assertEquals(callsBefore, remote.checkpointCalls)
        openDatabase(requireNotNull(interrupted.stagingDatabaseName)).let { stage ->
            try { assertEquals(1, stage.productDao().getAll().size) } finally { stage.close() }
        }
    }

    @Test
    fun automaticRecoveryDoesNotRetryStablePayloadFailure() = runTest {
        seedOldMismatchGeneration()
        db.syncRecoveryJournalDao().get()!!.let { db.syncRecoveryJournalDao().upsert(it.copy(attemptCount = 0,
            reason = SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED)) }
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState(
            status = Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, errorCode = "sync_recovery_required"))
        tracker.updateNetworkAvailability(true)
        remote.checkpointFailure = SerializationException("task144_stable_invalid_payload")
        val callsBefore = remote.checkpointCalls
        withApplicationRecoveryOwner(tracker, selectedShop()) { application, _, _ ->
            application.requestPendingBusinessRecovery("task144_single_stable_failure")
            requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).join()
            assertEquals("Stable payload failures must not consume a five-attempt retry window", callsBefore + 1,
                remote.checkpointCalls)
            assertNotNull("Stable refusal preserves the durable journal", db.syncRecoveryJournalDao().get())
            assertFalse(applicationField<kotlinx.coroutines.sync.Mutex>(application, "businessRecoveryExecutionMutex").isLocked)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun kotlinx.coroutines.test.TestScope.verifyAutomaticRecoveryAfterExhaustedWindow(
        revokeDuringCooldown: Boolean,
        reopenDuringCooldown: Boolean = false,
        observeDuringCooldown: Boolean = false,
        refreshDuringCooldown: Boolean = false,
        changeShopDuringCooldown: Boolean = false
    ) {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        var recoveryNow = 1_000_000L
        val beforeProduct = db.productDao().getAll().single()
        val beforeBaseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val journal = SyncRecoveryJournal(ownerHash = activeScope().ownerHash, storeScope = activeScope().storeId,
            shopId = SHOP, deviceId = DEVICE, authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = "task144_synthetic_transient_recovery",
            blockingEventId = null, attemptCount = 0, createdAtMs = recoveryNow,
            updatedAtMs = recoveryNow, nextRetryAtMs = null)
        db.syncRecoveryJournalDao().upsert(journal)
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        assertEquals("sync_recovery_required", tracker.businessDataScopeState.value.errorCode)
        val checkpointCallsBefore = remote.checkpointCalls
        remote.checkpointFailure = java.io.IOException("task144_synthetic_offline")
        val otherShop = "90000000-0000-4000-8000-000000000144"
        val linkedShops = if (changeShopDuringCooldown) listOf(
            LinkedShop(SHOP, null, selectedShop().name, selectedShop().role, "active", selectable = true, canWrite = true),
            LinkedShop(otherShop, null, "Other synthetic shop", "owner", "active", selectable = true, canWrite = true)) else null
        withApplicationRecoveryOwner(tracker, selectedShop(), recoveryNowMs = { recoveryNow }, linkedShops = linkedShops) { application, _, shops ->
            val clockBefore = application.automaticBusinessRecoveryNowMs()
            recoveryNow += 1L
            assertEquals("Fixture must control the clock used by Application recovery admission", clockBefore + 1L,
                application.automaticBusinessRecoveryNowMs())
            val executionMutex = applicationField<kotlinx.coroutines.sync.Mutex>(application, "businessRecoveryExecutionMutex")
            fun currentJournal() = runBlocking { db.syncRecoveryJournalDao().get() }
            fun currentProduct() = runBlocking { db.productDao().getAll().single() }
            suspend fun awaitJournalAttempt(expected: Int, released: Boolean = false) {
                // Only pump ready callbacks. Advancing time here would hide a missing timer.
                repeat(5_000) {
                    testScheduler.runCurrent()
                    val observed = currentJournal()
                    val inFlight = observed?.let { AutomaticRecoveryRetryMetadata.decode(it.reason)?.state == "in_flight" } == true
                    if (observed?.attemptCount == expected && !inFlight && (!released || !executionMutex.isLocked)) {
                        repeat(10) { testScheduler.runCurrent(); Thread.sleep(1) }
                        return
                    }
                    Thread.sleep(1)
                }
                throw AssertionError("Recovery fixture did not complete attempt $expected; " +
                    "observed=${currentJournal()?.attemptCount} mutexLocked=${executionMutex.isLocked}")
            }
            fun advanceClock(milliseconds: Long) {
                recoveryNow += milliseconds
                testScheduler.advanceTimeBy(milliseconds)
                testScheduler.runCurrent()
            }

            application.requestPendingBusinessRecovery("task144_single_initial_trigger")
            val initialFlight = requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
            application.requestPendingBusinessRecovery("task144_duplicate_initial_trigger")
            assertSame("Existing single flight coalesces concurrent admission", initialFlight,
                applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob"))
            repeat(5) { attempt ->
                awaitJournalAttempt(attempt + 1, released = attempt == 4)
                assertEquals(checkpointCallsBefore + attempt + 1, remote.checkpointCalls)
                if (attempt < 4) {
                    val next = requireNotNull(currentJournal()?.nextRetryAtMs)
                    advanceClock((next - recoveryNow).coerceAtLeast(1L))
                }
            }
            var exhausted = requireNotNull(currentJournal())
            assertEquals(5, exhausted.attemptCount)
            assertEquals(beforeProduct, currentProduct())
            assertEquals(beforeBaseline, runBlocking { db.syncRecoveryBaselineDao().get() })
            assertFalse("Cooldown must release the recovery execution owner", executionMutex.isLocked)
            remote.checkpointFailure = null
            if (observeDuringCooldown) {
                val completion = requireNotNull(AutomaticRecoveryRetryMetadata.decode(exhausted.reason))
                remote.emptyTailConfigured = true
                val summary = runBlocking { drainOrdinary(OrdinaryFencedReadFixture(remote)).getOrThrow() }
                assertTrue(summary.manualFullSyncRequired)
                val observed = requireNotNull(currentJournal())
                assertEquals(completion, AutomaticRecoveryRetryMetadata.decode(observed.reason))
                assertEquals(exhausted.attemptCount, observed.attemptCount)
                assertEquals(exhausted.nextRetryAtMs, observed.nextRetryAtMs)
                assertTrue("The observer may update journal time without inventing another completion", observed.updatedAtMs > exhausted.updatedAtMs)
                exhausted = observed
            }
            val callsAtCooldown = remote.checkpointCalls
            if (refreshDuringCooldown || changeShopDuringCooldown) {
                application.javaClass.getDeclaredMethod("observeShopContextForRemoteComponents")
                    .apply { isAccessible = true }.invoke(application)
                repeat(50) { testScheduler.runCurrent(); Thread.sleep(1) }
                val epoch = shops.diagnosticShopEpoch()
                if (refreshDuringCooldown) {
                    runBlocking { shops.refresh(ACCOUNT) }
                    assertEquals(SHOP, shops.state.value.activeShopId)
                    assertTrue(shops.state.value.syncAllowed)
                } else assertTrue(shops.selectShop(otherShop))
                assertTrue("The real context operation must advance the actual shop epoch", shops.diagnosticShopEpoch() > epoch)
                repeat(100) { testScheduler.runCurrent(); Thread.sleep(1) }
                if (refreshDuringCooldown) {
                    assertEquals("Retargeting preserves the original deadline and budget", exhausted, currentJournal())
                }
            }
            if (reopenDuringCooldown) {
                requireNotNull(applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")).let {
                    it.cancel(); it.join()
                }
                reopenOrdinaryDatabase()
                assertEquals(exhausted, currentJournal())
                application.javaClass.getDeclaredField("database\$delegate").apply { isAccessible = true }.set(application, lazyOf(db))
                application.javaClass.getDeclaredField("repository\$delegate").apply { isAccessible = true }.set(application, lazyOf(repository))
                application.javaClass.getDeclaredField("shopSyncRecoveryCoordinator\$delegate").apply { isAccessible = true }
                    .set(application, lazyOf(coordinator(nowMs = { recoveryNow })))
                application.requestPendingBusinessRecovery("task144_restart_persisted_cooldown")
                testScheduler.runCurrent()
                assertEquals(exhausted, currentJournal())
            }
            if (revokeDuringCooldown || changeShopDuringCooldown) {
                if (revokeDuringCooldown) applicationField<MutableStateFlow<ShopContext>>(shops, "mutableState").value =
                    ShopContext.blocked(ACCOUNT, "task144_revoked_during_cooldown")
                advanceClock(300_001L)
                repeat(100) { testScheduler.runCurrent(); Thread.sleep(1) }
                assertEquals("A retired shop must never spend the future opportunity", callsAtCooldown,
                    remote.checkpointCalls)
                assertEquals(exhausted, currentJournal())
                assertEquals(beforeProduct, currentProduct())
                assertFalse(executionMutex.isLocked)
                return@withApplicationRecoveryOwner
            }
            advanceClock(299_999L)
            assertEquals("No sixth attempt before five minutes from last completion", callsAtCooldown,
                remote.checkpointCalls)
            assertEquals(exhausted, currentJournal())
            advanceClock(1L)
            for (step in 0 until 5_000) {
                testScheduler.runCurrent()
                if (remote.checkpointCalls > callsAtCooldown) break
                Thread.sleep(1)
            }
            assertTrue("AND-R01: the real Application scheduler must create a future opportunity without another trigger",
                remote.checkpointCalls > callsAtCooldown)
            for (step in 0 until 5_000) {
                testScheduler.runCurrent()
                if (currentJournal() == null && !executionMutex.isLocked) break
                Thread.sleep(1)
            }
            assertNull("Healthy retry must activate and finish the real recovery journal", currentJournal())
            assertEquals(beforeProduct.productName, currentProduct().productName)
            assertFalse(executionMutex.isLocked)
        }
    }

    @Test
    fun foreignGenerationPriceManifestCannotHideCurrentPhysicalCorruptionAfterReopen() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val originalPrice = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values.single()
        repository.addProduct(Product(barcode = "different-local-parent", productName = "Different local parent"))
        val other = requireNotNull(db.productDao().findByBarcode("different-local-parent"))
        val otherRemote = requireNotNull(db.productRemoteRefDao().getByProductId(other.id)).remoteId
        repository.deleteProduct(other)
        assertEquals(otherRemote, db.pendingCatalogTombstoneDao().listPendingOrdered().single().remoteId)
        assertTrue(repository.resolveOfflineBusinessDataScope(activeScope(), true).allowsLocalOperations)
        db.openHelper.writableDatabase.execSQL("UPDATE product_prices SET note='Unrecorded current price corruption'")
        assertFalse(repository.resolveOfflineBusinessDataScope(activeScope(), true).allowsLocalOperations)
        val foreignPrice = originalPrice.copy(productId = otherRemote)
        db.syncRecoveryManifestDao().upsertAll(ShopSyncRows.Prices(listOf(foreignPrice))
            .toManifestRows("unrelated-stale-generation", ShopSyncRowDomain.PRICES))
        val before = ordinaryMarkerApplicationRows()
        reopenOrdinaryDatabase()
        assertFalse("A foreign generation must not exempt a corrupted current-generation price",
            repository.resolveOfflineBusinessDataScope(activeScope(), true).allowsLocalOperations)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(before, ordinaryMarkerApplicationRows())
        assertForeignKeysClean(db)
    }

    @Test
    fun currentPriceExemptionUsesGenerationBoundLookupInActualRoomQuery() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val price = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values.single()
        // Stale generations are intentionally retained; their cardinality must not affect an A lookup.
        repeat(20) { index ->
            db.syncRecoveryManifestDao().upsertAll(ShopSyncRows.Prices(listOf(price))
                .toManifestRows("stale-$index", ShopSyncRowDomain.PRICES))
        }
        db.close()
        val queries = mutableListOf<Pair<String, List<Any?>>>()
        db = openDatabase(ACTIVE_DATABASE, object : androidx.room.RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (sqlQuery.startsWith("SELECT m.remoteId") && sqlQuery.contains("m.domain IN") && sqlQuery.contains("lp.domain='prices'"))
                    queries += sqlQuery to bindArgs.toList()
            }
        })
        repository = DefaultInventoryRepository(db)
        assertTrue(repository.resolveOfflineBusinessDataScope(activeScope(), true).allowsLocalOperations)
        val actual = queries.single { it.second.getOrNull(1) == "prices" }
        val plan = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN " + actual.first, actual.second.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(3)) }
        }
        assertTrue(plan.toString(), plan.any {
            it.startsWith("SEARCH ") && (it.contains(" AS lp ") || it.startsWith("SEARCH lp ")) &&
                it.contains("generationId=? AND domain=? AND remoteId=?")
        })
        assertFalse(plan.toString(), plan.any {
            it.startsWith("SCAN ") && (it.contains(" AS lp") || it.startsWith("SCAN lp"))
        })
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun canonicalBootstrapWakesDurableCatalogIntentAfterRealReopenWithoutRamTickle() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val checkpointA = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        validateShopSyncActiveReceipt(db, baseline.generationId, checkpointA)
        val created = repository.createCatalogEntry(CatalogEntityKind.SUPPLIER, "Durable previous process supplier")
        reopenOrdinaryDatabase()
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        val pendingRef = requireNotNull(db.supplierRemoteRefDao().getBySupplierId(created.id))
        assertTrue("SETUP durable intent uses the production pending predicate",
            pendingRef.lastRemoteAppliedAt == null || pendingRef.localChangeRevision > pendingRef.lastSyncedLocalRevision)
        assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
        assertNull(db.syncRecoveryJournalDao().get())
        val historyBefore = ordinaryMarkerApplicationRows().filterKeys { it.contains("history") }
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        assertEquals(Task126BusinessDataScopeStatus.READY, tracker.businessDataScopeState.value.status)
        tracker.updateNetworkAvailability(true)
        val reader = OrdinaryFencedReadFixture(remote)
        var fetchCalls = 0
        var upsertCalls = 0
        var eventCalls = 0
        var sent: BusinessWriteAttempt? = null
        val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun fetchCatalog(shopId: String?): Result<InventoryCatalogFetchBundle> {
                fetchCalls++
                return Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList(), true))
            }
            override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>, shopId: String?): Result<Unit> {
                assertEquals(SHOP, shopId)
                val header = db.syncEventOutboxDao().listBusinessWriteHeaders(
                    ACCOUNT, activeScope().storeId, setOf("SUPPLIERS"), 1).single()
                val attempt = Json.decodeFromString<BusinessWriteAttempt>(
                    requireNotNull(db.syncEventOutboxDao().getById(header.id)).metadataJson)
                validateBusinessWritePayload(attempt)
                assertEquals(rows, attempt.payload.suppliers)
                assertEquals(baseline.generationId, attempt.generation)
                sent = attempt
                upsertCalls++
                return Result.success(Unit)
            }
        }
        val events = object : SyncEventRemoteDataSource by NoOpSyncEventRemoteForRecoveryTest {
            override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
                val row = requireNotNull(sent).payload.suppliers.single()
                assertEquals(listOf(row.id), requireNotNull(params.entityIds).supplierIds)
                eventCalls++
                val suppliers = ((remote.fixture.rows.getValue(ShopSyncRowDomain.SUPPLIERS) as ShopSyncRows.Suppliers)
                    .values + row.copy(updatedAt = "2026-07-21T12:00:00.000000Z")).sortedBy { it.id }
                val domain = checkpointDomain(suppliers.map { it.id }, suppliers.map { line(it.id, requireNotNull(it.updatedAt), "-") })
                val checkpoint = checkpointA.copy(syncEvents = checkpointA.syncEvents.copy(maxId = "43",
                    domainMaxIds = checkpointA.syncEvents.domainMaxIds + (SyncEventDomains.CATALOG to "43")),
                    catalog = checkpointA.catalog.copy(suppliers = domain, digest = testSha256(domain.versionDigest + "\n" +
                        checkpointA.catalog.categories.versionDigest + "\n" + checkpointA.catalog.products.versionDigest)),
                    checkpointDigest = "9".repeat(64))
                val event = SyncEventRemoteRow(id = 43L, ownerUserId = ACCOUNT, shopId = SHOP, storeId = params.storeId,
                    domain = params.domain, eventType = params.eventType, sourceDeviceId = params.sourceDeviceId,
                    clientEventId = params.clientEventId, changedCount = params.changedCount, entityIds = params.entityIds,
                    createdAt = "2026-07-21T12:00:00.000000Z")
                remote.checkpoints += checkpoint
                remote.tailRows = remote.fixture.rows + (ShopSyncRowDomain.SUPPLIERS to ShopSyncRows.Suppliers(suppliers))
                remote.tailEvents = listOf(event)
                return Result.success(event)
            }
        }
        val noPrices = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest { override val isConfigured = false }
        repository = DefaultInventoryRepository(db, tracker, reader)
        val triggers = mutableListOf<String>()
        val auto = CatalogAutoSyncCoordinator(repository = repository, remote = cloud, priceRemote = noPrices,
            syncEventRemote = events, deviceAuthorization = ShopDeviceAuthorizationRepository(
                ActiveShopDeviceRemoteForOrdinaryTest(SHOP), businessDataScopeRuntimeGuard = tracker),
            authFlow = MutableStateFlow(AuthState.SignedIn(ACCOUNT, "durable@example.test")),
            selectedShopProvider = { selectedShop() }, syncStateTracker = tracker, scope = backgroundScope,
            debounceMs = Long.MAX_VALUE, onRecoveryRequired = { triggers += it })
        try {
            auto.runBootstrapCycle("foreground")
            val foregroundReleased = !tracker.isSyncing.value
            val current = requireNotNull(db.syncRecoveryBaselineDao().get())
            val currentProof = runCatching { validateShopSyncActiveReceipt(db, current.generationId,
                decodeRecoveryCheckpointJson(current.checkpointJson)) }.isSuccess
            val ref = requireNotNull(db.supplierRemoteRefDao().getBySupplierId(created.id))
            val pending = repository.hasCatalogCloudPendingWorkInclusive()
            println("TASK143_DURABLE_CANONICAL_PUSH upserts=$upsertCalls events=$eventCalls pending=$pending proof=$currentProof legacyFetch=$fetchCalls")
            println("TASK143_DURABLE_CANONICAL_POSTCONDITIONS refSynced=${ref.localChangeRevision == ref.lastSyncedLocalRevision} " +
                "outboxZero=${db.syncEventOutboxDao().countAll() == 0} sameGeneration=${current.generationId == baseline.generationId} " +
                "journalNull=${db.syncRecoveryJournalDao().get() == null} noRecovery=${triggers.isEmpty()} " +
                "historySame=${ordinaryMarkerApplicationRows().filterKeys { it.contains("history") } == historyBefore} foregroundReleased=$foregroundReleased")
            assertTrue("DURABLE_PUSH_DESIRED: normal foreground must drain and wake real durable previous-process catalog intent without RAM tickle",
                upsertCalls == 1 && eventCalls == 1 && !pending && currentProof && fetchCalls == 0 &&
                    ref.localChangeRevision == ref.lastSyncedLocalRevision && db.syncEventOutboxDao().countAll() == 0 &&
                    current.generationId == baseline.generationId && db.syncRecoveryJournalDao().get() == null && triggers.isEmpty() &&
                    ordinaryMarkerApplicationRows().filterKeys { it.contains("history") } == historyBefore && foregroundReleased)
        } finally { auto.shutdown() }
    }

    @Test
    fun automaticBootstrapWithCanonicalAUsesRealDrainWithoutLegacyPrune() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val checkpointA = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        val before = ordinaryMarkerApplicationRows()
        validateShopSyncActiveReceipt(db, baseline.generationId, checkpointA)
        assertFalse(repository.hasCatalogCloudPendingWorkInclusive())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        assertNull(db.syncRecoveryJournalDao().get())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        var legacyFetchCalls = 0
        var checkpointCalls = 0
        var markerCalls = 0
        val source = OrdinaryFencedReadFixture(remote)
        val reader = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
                assertEquals(CatalogSyncStage.SYNC_EVENTS_DRAIN, tracker.state.value.stage)
                checkpointCalls++
                return source.checkpoint(context)
            }
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                markerCalls++
                return source.convergenceMarker(context)
            }
        }
        val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun fetchCatalog(shopId: String?): Result<InventoryCatalogFetchBundle> {
                assertEquals(SHOP, shopId)
                legacyFetchCalls++
                return Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList(),
                    isCompleteSnapshot = true))
            }
        }
        val noPrices = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest {
            override val isConfigured = false
        }
        repository = DefaultInventoryRepository(db, tracker, reader)
        val triggers = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val fullPages = remote.pageCalls
        val auto = CatalogAutoSyncCoordinator(repository = repository, remote = cloud, priceRemote = noPrices,
            syncEventRemote = NoOpSyncEventRemoteForRecoveryTest,
            deviceAuthorization = ShopDeviceAuthorizationRepository(ActiveShopDeviceRemoteForOrdinaryTest(SHOP),
                businessDataScopeRuntimeGuard = tracker),
            authFlow = MutableStateFlow(AuthState.SignedIn(ACCOUNT, "ordinary@example.test")),
            selectedShopProvider = { selectedShop() }, syncStateTracker = tracker, scope = backgroundScope,
            debounceMs = Long.MAX_VALUE, onRecoveryRequired = { triggers += it }, logger = { logs += it })
        try {
            println("TASK143_BOOTSTRAP_CONSUMER_WITNESS stage=prerequisites actualRecoveryAndReopen=true canonicalAValid=true pending=0")
            auto.runBootstrapCycle("foreground")
            val after = ordinaryMarkerApplicationRows()
            val proofError = runCatching { validateShopSyncActiveReceipt(db, baseline.generationId, checkpointA) }
                .exceptionOrNull() as? ShopSyncContractException
            val outcome = tracker.lastOutcome.value
            println("TASK143_BOOTSTRAP_CONSUMER_WITNESS stage=returned legacyFetchCalls=$legacyFetchCalls checkpointCalls=$checkpointCalls markerCalls=$markerCalls samePhysical=${before == after} proofError=${proofError?.code ?: "none"} outcome=${outcome?.source} recoveryTriggers=${triggers.size}")
            logs.filter { it.startsWith("cycle=catalog_bootstrap") || it.startsWith("cycle=sync_events_drain") }
                .forEach(::println)
            assertTrue("BOOTSTRAP_CONSUMER_DESIRED: actual foreground bootstrap must preserve A and complete canonical drain without legacy inbound or recovery",
                before == after && proofError == null && legacyFetchCalls == 0 && checkpointCalls == 1 &&
                    markerCalls == 1 && remote.pageCalls == fullPages && triggers.isEmpty() &&
                    db.syncRecoveryJournalDao().get() == null && outcome != null && outcome.source == CatalogSyncFlightOwner.SYNC_EVENTS &&
                    !outcome.summary.manualFullSyncRequired && !outcome.summary.syncEventsGapDetected &&
                    !outcome.summary.syncEventsOrdinaryPending)
        } finally { auto.shutdown() }
    }

    @Test
    fun canonicalActiveReceiptDefersLegacyBootstrapBeforeFetchOrApply() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val before = ordinaryMarkerApplicationRows()
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        var fetchCalls = 0
        val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun fetchCatalog(shopId: String?): Result<InventoryCatalogFetchBundle> {
                fetchCalls++
                return Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList(), true))
            }
        }
        val result = repository.pullCatalogBootstrapFromRemote(cloud, NoOpPriceRemoteForRecoveryTest,
            CatalogSyncProgressReporter { }, selectedShop())
        assertTrue(result.exceptionOrNull() is CanonicalCatalogInboundRequired)
        assertEquals(0, fetchCalls)
        assertEquals(before, ordinaryMarkerApplicationRows())
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
    }

    @Test
    fun legacyInboundRejectsCanonicalAAppearingDuringCatalogPriceOrHistoryFetch() = runTest {
        for (domain in listOf("catalog", "prices", "history")) {
            resetOrdinaryDiagnosticFixture()
            seedOldMismatchGeneration()
            remote = RecoveryRemoteFixture(targetFixture())
            repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = remote)
            var entered = 0
            var captured: Map<String, List<List<String>>>? = null
            suspend fun publishA() {
                entered++
                assertTrue(coordinator().recover(ACCOUNT, selectedShop(), activeScope()) is ShopSyncRecoveryResult.Activated)
                val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
                validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
                captured = ordinaryMarkerApplicationRows()
            }
            val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
                override suspend fun fetchCatalog(shopId: String?): Result<InventoryCatalogFetchBundle> {
                    assertEquals(SHOP, shopId)
                    if (domain == "catalog") publishA()
                    return Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList(), true))
                }
            }
            val prices = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest {
                override val isConfigured = domain == "prices"
                override suspend fun fetchProductPricesPage(afterId: String?, limit: Long, shopId: String?): Result<List<InventoryProductPriceRow>> {
                    assertEquals(SHOP, shopId)
                    publishA()
                    val row = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values.single()
                    return Result.success(listOf(row.copy(note = "Must not apply outside A")))
                }
            }
            val result = if (domain == "history") {
                val history = object : SessionBackupRemoteDataSource {
                    override val isConfigured = true
                    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> {
                        publishA()
                        val row = (remote.fixture.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History).values.single()
                        return Result.success(listOf(row.copy(displayName = "Must not apply outside A")))
                    }
                    override suspend fun fetchSessionsByRemoteIds(remoteIds: Set<String>) = error("no targeted fetch")
                    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>) = error("no push")
                }
                repository.bootstrapHistorySessionsFromRemote(history, selectedShop())
            } else repository.pullCatalogBootstrapFromRemote(cloud, prices, CatalogSyncProgressReporter { }, selectedShop())
            assertEquals(domain, 1, entered)
            assertTrue("$domain must defer at the apply CAS", result.exceptionOrNull() is CanonicalCatalogInboundRequired)
            assertEquals(domain, requireNotNull(captured), ordinaryMarkerApplicationRows())
        }
    }

    @Test
    fun legacyInboundWithNoCanonicalBaselineRetainsCompleteBootstrap() = runTest {
        assertNull(db.syncRecoveryBaselineDao().get())
        val fixture = targetFixture()
        var empty = false
        var fetchCalls = 0
        val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun fetchCatalog(shopId: String?): Result<InventoryCatalogFetchBundle> {
                fetchCalls++
                return Result.success(if (empty) InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList(), true)
                    else InventoryCatalogFetchBundle(
                        (fixture.rows.getValue(ShopSyncRowDomain.SUPPLIERS) as ShopSyncRows.Suppliers).values,
                        (fixture.rows.getValue(ShopSyncRowDomain.CATEGORIES) as ShopSyncRows.Categories).values,
                        (fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values, true))
            }
        }
        val prices = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest { override val isConfigured = false }
        repository.pullCatalogBootstrapFromRemote(cloud, prices, CatalogSyncProgressReporter { }, selectedShop()).getOrThrow()
        assertEquals(1, db.supplierRemoteRefDao().getCleanRefs().size)
        assertEquals(1, db.categoryRemoteRefDao().getCleanRefs().size)
        assertEquals(1, db.productRemoteRefDao().getCleanRefs().size)
        assertNull(db.syncRecoveryBaselineDao().get())
        empty = true
        val result = repository.pullCatalogBootstrapFromRemote(cloud, prices, CatalogSyncProgressReporter { }, selectedShop()).getOrThrow()
        assertEquals(2, fetchCalls)
        assertTrue(result.fullCatalogFetch)
        assertEquals(1, result.prunedSuppliers)
        assertEquals(1, result.prunedCategories)
        assertEquals(1, result.prunedProducts)
        assertEquals(0, db.productDao().count())
        assertEquals(0, db.supplierDao().count())
        assertEquals(0, db.categoryDao().count())
        assertNull(db.syncRecoveryBaselineDao().get())
    }

    @Test
    fun legacyInboundNeverFallsThroughForInvalidCanonicalAuthority() = runTest {
        for (fault in listOf("shop", "missing-shop", "device", "watermark", "manifest")) {
            resetOrdinaryDiagnosticFixture()
            seedOldMismatchGeneration()
            recoverAndReopenOrdinaryFixture(targetFixture())
            when (fault) {
                "device" -> db.openHelper.writableDatabase.execSQL("UPDATE sync_event_device_state SET deviceId='foreign-device'")
                "watermark" -> db.openHelper.writableDatabase.execSQL("DELETE FROM sync_event_watermarks")
                "manifest" -> db.openHelper.writableDatabase.execSQL("UPDATE sync_recovery_manifest SET payloadDigest='corrupt' WHERE domain='suppliers'")
            }
            val before = ordinaryMarkerApplicationRows()
            var fetched = false
            val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
                override suspend fun fetchCatalog(shopId: String?): Result<InventoryCatalogFetchBundle> {
                    fetched = true
                    return Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList(), true))
                }
            }
            val shop = when (fault) { "shop" -> selectedShop().copy(shopId = "foreign-shop"); "missing-shop" -> null; else -> selectedShop() }
            val result = repository.pullCatalogBootstrapFromRemote(cloud, NoOpPriceRemoteForRecoveryTest, CatalogSyncProgressReporter { }, shop)
            assertTrue(fault, result.isFailure)
            assertFalse(fault, fetched)
            assertEquals(fault, before, ordinaryMarkerApplicationRows())
        }
    }

    @Test
    fun activeReceiptNeverAcceptsNewCleanSupplierWithoutActualAcknowledgement() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val checkpointA = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        val id = db.supplierDao().insert(Supplier(name = "Unproven clean supplier"))
        db.supplierRemoteRefDao().insert(SupplierRemoteRef(supplierId = id,
            remoteId = "70000000-0000-4000-8000-000000000099", lastRemoteAppliedAt = 1_000_000L))
        assertFalse(repository.hasCatalogCloudPendingWorkInclusive())
        val error = runCatching { validateShopSyncActiveReceipt(db, baseline.generationId, checkpointA) }.exceptionOrNull()
        assertEquals("recovery_staging_table_count_mismatch_suppliers", (error as? ShopSyncContractException)?.code)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
    }

    @Test
    fun ordinaryAdvancedMarkerKeepsActualAcknowledgedSupplierCreateWithoutRecovery() = runBlocking {
        kotlinx.coroutines.withTimeout(30_000) {
            seedOldMismatchGeneration()
            recoverAndReopenOrdinaryFixture(targetFixture())
            remote.emptyTailConfigured = true
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            val checkpointA = decodeRecoveryCheckpointJson(baseline.checkpointJson)
            val binding = db.businessDataScopeBindingDao().get()
            val device = db.syncEventDeviceStateDao().get()
            val watermark = db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)
            val canonicalA = ShopSyncRowDomain.entries.associateWith {
                db.syncRecoveryManifestDao().page(baseline.generationId, it.wireValue, null, 100)
            }
            validateShopSyncActiveReceipt(db, baseline.generationId, checkpointA)
            val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
            tracker.updateNetworkAvailability(true)
            val markerEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseMarker = kotlinx.coroutines.CompletableDeferred<Unit>()
            val writerCheckpointEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseWriterCheckpoint = kotlinx.coroutines.CompletableDeferred<Unit>()
            var sent: BusinessWriteAttempt? = null
            var sentOutboxId: Long? = null
            var eventWrites = 0
            var markerValidated = false
            val reader = object : ShopSyncReadRemoteDataSource by OrdinaryFencedReadFixture(remote) {
                override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                    markerEntered.complete(Unit)
                    releaseMarker.await()
                    val marker = withCompleteAdvancedScan(remote.convergenceMarker(context).getOrThrow())
                    val raw = ackWitnessJson.encodeToString(marker)
                    val codec = SupabaseShopSyncReadRemoteDataSource(ShopSyncRpcInvoker { function, _, _, _ ->
                        check(function == "shop_sync_convergence_marker_v1")
                        ShopSyncRpcResponse(Json.parseToJsonElement(raw).jsonObject, raw.encodeToByteArray().size.toLong())
                    })
                    return codec.convergenceMarker(context).onSuccess {
                        check(!it.serverNoWorkEligible && it.syncEvents.maxId == "43" &&
                            it.syncEvents.verifiedBaselineId == "42")
                        markerValidated = true
                    }
                }
            }
            val writerReader = object : ShopSyncReadRemoteDataSource by remote {
                override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
                    writerCheckpointEntered.complete(Unit)
                    releaseWriterCheckpoint.await()
                    return remote.checkpoint(context)
                }
            }
            val writerRepository = DefaultInventoryRepository(db, tracker, writerReader)
            val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
                override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>, shopId: String?): Result<Unit> {
                    assertEquals(SHOP, shopId)
                    return upsertSuppliers(rows)
                }
                override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> {
                    val header = db.syncEventOutboxDao().listBusinessWriteHeaders(
                        ACCOUNT, activeScope().storeId, setOf("SUPPLIERS"), 1).single()
                    val attempt = Json.decodeFromString<BusinessWriteAttempt>(
                        requireNotNull(db.syncEventOutboxDao().getById(header.id)).metadataJson)
                    validateBusinessWritePayload(attempt)
                    assertEquals(rows, attempt.payload.suppliers)
                    assertEquals(baseline.generationId, attempt.generation)
                    sent = attempt
                    sentOutboxId = header.id
                    return Result.success(Unit)
                }
            }
            val events = object : SyncEventRemoteDataSource by NoOpSyncEventRemoteForRecoveryTest {
                override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
                    val row = requireNotNull(sent).payload.suppliers.single()
                    assertEquals(listOf(row.id), requireNotNull(params.entityIds).supplierIds)
                    eventWrites++
                    val suppliers = ((remote.fixture.rows.getValue(ShopSyncRowDomain.SUPPLIERS) as ShopSyncRows.Suppliers)
                        .values + row.copy(updatedAt = "2026-07-21T12:00:00.000000Z")).sortedBy { it.id }
                    val domain = checkpointDomain(suppliers.map { it.id }, suppliers.map {
                        line(it.id, requireNotNull(it.updatedAt), "-") })
                    remote.checkpoints += checkpointA.copy(
                        syncEvents = checkpointA.syncEvents.copy(maxId = "43", domainMaxIds =
                            checkpointA.syncEvents.domainMaxIds + (SyncEventDomains.CATALOG to "43")),
                        catalog = checkpointA.catalog.copy(suppliers = domain, digest = testSha256(
                            domain.versionDigest + "\n" + checkpointA.catalog.categories.versionDigest + "\n" +
                                checkpointA.catalog.products.versionDigest)), checkpointDigest = "9".repeat(64))
                    return Result.success(SyncEventRemoteRow(id = 43L, ownerUserId = ACCOUNT, shopId = SHOP,
                        storeId = params.storeId, domain = params.domain, eventType = params.eventType,
                        sourceDeviceId = params.sourceDeviceId, clientEventId = params.clientEventId,
                        changedCount = params.changedCount, entityIds = params.entityIds,
                        createdAt = "2026-07-21T12:00:00.000000Z"))
                }
            }
            val outerRepository = DefaultInventoryRepository(db, tracker, reader)
            val drain = async {
                tracker.withBusinessDataScopeFlight(ACCOUNT, selectedShop()) {
                    outerRepository.drainSyncEventsFromRemote(NoOpCatalogRemoteForRecoveryTest,
                        NoOpPriceRemoteForRecoveryTest, NoOpSyncEventRemoteForRecoveryTest, ACCOUNT,
                        CatalogSyncProgressReporter { }, selectedShop = selectedShop()).getOrThrow()
                }
            }
            var writer: kotlinx.coroutines.Deferred<Result<CatalogSyncSummary>>? = null
            try {
                markerEntered.await()
                val created = writerRepository.createCatalogEntry(CatalogEntityKind.SUPPLIER, "Actual ACK new supplier")
                writer = async {
                    tracker.withBusinessDataScopeFlight(ACCOUNT, selectedShop()) {
                        writerRepository.syncCatalogQuickWithEvents(cloud, NoOpPriceRemoteForRecoveryTest, events,
                            ACCOUNT, CatalogSyncProgressReporter { }, sessionRemote = null, selectedShop = selectedShop())
                    }
                }
                writerCheckpointEntered.await()
                val attempt = requireNotNull(sent)
                val revision = attempt.payload.revisions.single()
                val ref = requireNotNull(db.supplierRemoteRefDao().getBySupplierId(created.id))
                assertEquals(1, eventWrites)
                assertEquals(revision.remoteId, ref.remoteId)
                assertEquals(revision.revision, ref.lastSyncedLocalRevision.toLong())
                assertEquals(ref.localChangeRevision, ref.lastSyncedLocalRevision)
                assertEquals(revision.fingerprint, ref.lastRemotePayloadFingerprint)
                assertNotNull(ref.lastRemoteAppliedAt)
                assertNull(db.syncEventOutboxDao().getById(requireNotNull(sentOutboxId)))
                assertEquals(0, db.syncEventOutboxDao().countAll())
                assertTrue(db.pendingCatalogTombstoneDao().listPendingOrdered().isEmpty())
                assertFalse(writerRepository.hasCatalogCloudPendingWorkInclusive())
                assertEquals(checkpointA.catalog.suppliers.activeCount + 1, db.supplierDao().count().toLong())
                assertEquals(checkpointA.catalog.suppliers.activeCount + 1, db.supplierRemoteRefDao().countRows().toLong())
                assertEquals(baseline, db.syncRecoveryBaselineDao().get())
                assertEquals(binding, db.businessDataScopeBindingDao().get())
                assertEquals(device, db.syncEventDeviceStateDao().get())
                assertEquals(watermark, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
                assertNull(db.syncRecoveryJournalDao().get())
                for (domain in ShopSyncRowDomain.entries) assertEquals(canonicalA.getValue(domain),
                    db.syncRecoveryManifestDao().page(baseline.generationId, domain.wireValue, null, 100))
                assertNotNull(db.syncRecoveryManifestDao().get(baseline.generationId,
                    LOCAL_ACK_BODY_PREFIX + ShopSyncRowDomain.SUPPLIERS.wireValue, ref.remoteId))
                validateShopSyncCanonicalReceipt(db, baseline.generationId, checkpointA)
                validatePhysicalSnapshot(db, baseline.generationId, activeStoreHistory = true, localAcknowledgements = true)
                println("TASK143_ACK_CARDINALITY_WITNESS stage=prerequisites actualTypedACK=true pending=0 canonicalAUnchanged=true physicalPlusOne=true")
                releaseMarker.complete(Unit)
                val result = drain.await()
                assertTrue("SETUP: advanced marker must pass the production codec", markerValidated)
                assertEquals(0, result.syncEventsSkippedDirtyLocal)
                assertEquals(baseline, db.syncRecoveryBaselineDao().get())
                assertEquals(watermark, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
                val localErrors = ShadowLog.getLogs().map { it.msg }.filter {
                    it.startsWith("ordinary_convergence_proof branch=no_work") }
                println("TASK143_ACK_CARDINALITY_WITNESS stage=outer-result pending=${result.syncEventsOrdinaryPending} manual=${result.manualFullSyncRequired} gap=${result.syncEventsGapDetected} journal=${db.syncRecoveryJournalDao().get() != null}")
                localErrors.forEach(::println)
                assertTrue("ACK_CARDINALITY_DESIRED: valid actual Supplier ACK plus legal B>A must defer ordinary work without recovery: $result",
                    result.syncEventsOrdinaryPending && !result.manualFullSyncRequired && !result.syncEventsGapDetected &&
                        db.syncRecoveryJournalDao().get() == null)
            } finally {
                writer?.cancel()
                drain.cancel()
                releaseWriterCheckpoint.complete(Unit)
                releaseMarker.complete(Unit)
                writer?.join()
                drain.join()
            }
        }
    }

    @Test
    fun ordinaryAdvancedMarkerPreservesActivatedReceiptWithoutRecovery() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val products = db.productDao().getAll()
        val pages = remote.pageCalls
        val before = ordinaryMarkerApplicationRows()
        var markers = 0
        val reader = object : ShopSyncReadRemoteDataSource by OrdinaryFencedReadFixture(remote) {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                markers++
                val rows = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values
                val delta = ordinaryDeltaFixture(remote.fixture, rows.map { it.copy(productName = "Advanced remote B",
                    updatedAt = "2026-07-21T12:00:00.000000Z") }, 49L, mapOf(SyncEventDomains.CATALOG to "49"))
                remote.checkpoints += delta.checkpoint
                remote.tailRows = delta.rows
                remote.tailEvents = listOf(ordinaryEvent(49L, SyncEventDomains.CATALOG, SyncEventTypes.CATALOG_CHANGED,
                    SyncEventEntityIds(productIds = rows.map { it.id })))
                return remote.convergenceMarker(context).map(::withCompleteAdvancedScan)
            }
        }
        val result = drainOrdinary(reader).getOrThrow()
        assertEquals(1, markers)
        assertEquals(0, result.syncEventsFetched)
        assertFalse("A remains valid when the server advanced only after its exhausted window", result.manualFullSyncRequired)
        assertFalse(result.syncEventsGapDetected)
        assertTrue(result.syncEventsOrdinaryPending)
        assertEquals(before, ordinaryMarkerApplicationRows())
        assertEquals(products, db.productDao().getAll())
        assertEquals(pages, remote.pageCalls)
        assertOrdinaryUnchangedPublication(baseline)
    }

    @Test
    fun ordinaryAdvancedCoordinatorRereadsAndPublishesOnlyTheFreshProvedWindow() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val before = ordinaryMarkerApplicationRows()
        val pages = remote.pageCalls
        val rows = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values
        val delta = ordinaryDeltaFixture(remote.fixture, rows.map { it.copy(productName = "Fresh window B",
            updatedAt = "2026-07-21T12:00:00.000000Z") }, 49L, mapOf(SyncEventDomains.CATALOG to "49"))
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        var markers = 0
        var successor = false
        val reader = object : ShopSyncReadRemoteDataSource by OrdinaryFencedReadFixture(remote) {
            override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
                if (markers == 1) {
                    successor = true
                    assertEquals(before, ordinaryMarkerApplicationRows())
                    assertNull(tracker.lastOutcome.value)
                    assertEquals("42", context.verifiedBaselineId)
                }
                return remote.checkpoint(context)
            }
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                markers++
                if (markers == 1) {
                    remote.checkpoints += delta.checkpoint
                    remote.tailRows = delta.rows
                    remote.tailEvents = listOf(ordinaryEvent(49L, SyncEventDomains.CATALOG, SyncEventTypes.CATALOG_CHANGED,
                        SyncEventEntityIds(productIds = rows.map { it.id })))
                    return remote.convergenceMarker(context).map(::withCompleteAdvancedScan)
                }
                return remote.convergenceMarker(context)
            }
        }
        val recovery = mutableListOf<String>()
        val auto = ordinaryAutoSync(reader, tracker, MutableStateFlow(AuthState.SignedIn(ACCOUNT, "qa@example.test")),
            ActiveShopDeviceRemoteForOrdinaryTest(SHOP), backgroundScope, recovery)
        try {
            auto.runSyncEventDrainCycle("race_remote_advanced")
            assertTrue(successor)
            assertEquals(2, markers)
            assertEquals(emptyList<String>(), recovery)
            assertEquals(pages, remote.pageCalls)
            assertEquals("Fresh window B", db.productDao().getAll().single().productName)
            assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 49L)
            assertNull(db.syncRecoveryJournalDao().get())
            assertEquals(49L, tracker.lastOutcome.value?.summary?.syncEventsWatermarkAfter)
            assertFalse(requireNotNull(tracker.lastOutcome.value).summary.syncEventsOrdinaryPending)
        } finally { auto.shutdown() }
    }

    @Test
    fun ordinaryAdvancedMarkerRejectsCorruptPhysicalA() = runTest { verifyAdvancedMarkerGuard("corrupt") }

    @Test
    fun ordinaryAdvancedMarkerDefersNewLocalSave() = runTest { verifyAdvancedMarkerGuard("pending") }

    @Test
    fun ordinaryAdvancedMarkerRejectsChangedWatermark() = runTest { verifyAdvancedMarkerGuard("watermark") }

    @Test
    fun ordinaryAdvancedMarkerRejectsChangedOwner() = runTest { verifyAdvancedMarkerGuard("owner") }

    @Test
    fun ordinaryAdvancedMarkerRejectsRegressedDomain() = runTest { verifyAdvancedMarkerGuard("domain") }

    @Test
    fun ordinaryAdvancedMarkerPreservesOrdinaryEventBudget() = runTest { verifyAdvancedMarkerGuard("budget") }

    @Test
    fun ordinaryAdvancedMarkerPropagatesCancellation() = runTest { verifyAdvancedMarkerGuard("cancel") }

    private suspend fun verifyAdvancedMarkerGuard(kind: String) {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val before = ordinaryMarkerApplicationRows()
        var afterFault = before
        val reader = object : ShopSyncReadRemoteDataSource by OrdinaryFencedReadFixture(remote) {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                when (kind) {
                    "cancel" -> throw CancellationException("synthetic marker cancellation")
                    "corrupt" -> db.openHelper.writableDatabase.execSQL("UPDATE products SET productName='Untracked tamper'")
                    "pending" -> db.productDao().getAll().single().let {
                        repository.updateProductFromEditor(it, it.copy(productName = "New local Save"))
                    }
                    "watermark" -> db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.let {
                        db.syncEventWatermarkDao().upsert(it.copy(lastSyncEventId = 41L))
                    }
                    "owner" -> db.businessDataScopeBindingDao().get()?.let {
                        db.businessDataScopeBindingDao().upsert(it.copy(ownerHash = task126OwnerHash("another-owner")))
                    }
                }
                afterFault = ordinaryMarkerApplicationRows()
                return remote.convergenceMarker(context).map { original ->
                    val marker = withCompleteAdvancedScan(original.copy(syncEvents = original.syncEvents.copy(
                        maxId = "49", domainMaxIds = original.syncEvents.domainMaxIds + (SyncEventDomains.CATALOG to "49"))))
                    when (kind) {
                        "domain" -> marker.copy(syncEvents = marker.syncEvents.copy(domainMaxIds =
                            marker.syncEvents.domainMaxIds + (SyncEventDomains.CATALOG to "41")))
                        "budget" -> marker.copy(syncEvents = marker.syncEvents.copy(inspectedCount = 101))
                        else -> marker
                    }
                }
            }
        }
        val result = runCatching { drainOrdinary(reader).getOrThrow() }
        when (kind) {
            "cancel" -> {
                assertTrue(result.exceptionOrNull() is CancellationException)
                assertEquals(before, ordinaryMarkerApplicationRows())
            }
            "watermark", "owner" -> {
                assertEquals("ordinary_captured_publication_changed", (result.exceptionOrNull() as? ShopSyncContractException)?.code)
                assertEquals(afterFault, ordinaryMarkerApplicationRows())
            }
            "pending" -> {
                val summary = result.getOrThrow()
                assertTrue(summary.syncEventsSkippedDirtyLocal > 0)
                assertFalse(summary.syncEventsOrdinaryPending)
                assertFalse(summary.manualFullSyncRequired)
                assertEquals(afterFault, ordinaryMarkerApplicationRows())
            }
            else -> {
                val summary = result.getOrThrow()
                assertTrue(summary.manualFullSyncRequired)
                assertFalse(summary.syncEventsOrdinaryPending)
                assertEquals(kind == "budget", summary.syncEventsTooLarge)
                assertEquals(if (kind == "budget") SyncEventApplyStatusReasons.DRAIN_LIMIT_REACHED
                    else SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED, db.syncRecoveryJournalDao().get()?.reason)
                assertEquals(afterFault.filterKeys { it != "sync_recovery_journal" },
                    ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
            }
        }
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
    }

    private fun withCompleteAdvancedScan(marker: ShopSyncConvergenceMarker): ShopSyncConvergenceMarker {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val raw = json.parseToJsonElement(json.encodeToString(marker)).jsonObject
        val events = raw.getValue("syncEvents").jsonObject
        val scan = JsonObject(events + mapOf(
            "inspectionLimit" to kotlinx.serialization.json.JsonPrimitive(10000),
            "inspectedCount" to kotlinx.serialization.json.JsonPrimitive(1),
            "scanComplete" to kotlinx.serialization.json.JsonPrimitive(true),
            "blockingCount" to kotlinx.serialization.json.JsonPrimitive(0)))
        return json.decodeFromString(ShopSyncConvergenceMarker.serializer(), JsonObject(raw +
            ("serverNoWorkEligible" to kotlinx.serialization.json.JsonPrimitive(false)) + ("syncEvents" to scan)).toString())
    }

    @Test
    fun recoveryCompletionResumesDurableCatalogIntentWithoutAnotherLocalSignal() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val tracker = CatalogSyncStateTracker(repository.resolveBusinessDataScope(activeScope()))
        tracker.updateNetworkAvailability(true)
        val reader = object : ShopSyncReadRemoteDataSource by remote {
            override suspend fun checkpoint(context: ShopSyncRpcContext) = remote.checkpoint(context).map {
                it.copy(syncEvents = it.syncEvents.copy(requiresFullRecovery = true))
            }
        }
        val oldAuto = ordinaryAutoSync(reader, tracker, MutableStateFlow(AuthState.SignedIn(ACCOUNT, "qa@example.test")),
            ActiveShopDeviceRemoteForOrdinaryTest(SHOP), backgroundScope, mutableListOf())
        try {
            oldAuto.runSyncEventDrainCycle("before_missed_local_signal")
            assertTrue(tracker.businessDataScopeState.value.allowsLocalOperations)
            assertFalse(tracker.businessDataScopeState.value.allowsCloudSync)
            val original = db.productDao().getAll().single()
            repository.updateProduct(original.copy(productName = "Durable intent with lost callback"))
            assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
            assertTrue(coordinator(activationBoundary = { block -> tracker.withBusinessDataScopeTransition { block() } })
                .recover(ACCOUNT, selectedShop(), activeScope()) is ShopSyncRecoveryResult.Activated)
            assertNull(db.syncRecoveryJournalDao().get())
            assertFalse(tracker.businessDataScopeState.value.allowsCloudSync)
            val pushed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
                override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
                    assertEquals("Durable intent with lost callback", rows.single().productName)
                    pushed.complete(Unit); return Result.success(Unit)
                }
                override suspend fun patchProduct(id: String, ownerUserId: String, patch: InventoryProductPatch): Result<Unit> {
                    assertEquals("Durable intent with lost callback", patch.productName)
                    pushed.complete(Unit); return Result.success(Unit)
                }
                override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch) =
                    patchProduct(id, ownerUserId, patch)
            }
            val acknowledgedPrices = mutableListOf<InventoryProductPriceRow>()
            val priceCloud = object : ProductPriceRemoteDataSource by NoOpPriceRemoteForRecoveryTest {
                override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>, shopId: String?): Result<Unit> {
                    assertEquals(SHOP, shopId)
                    acknowledgedPrices += rows
                    return Result.success(Unit)
                }
            }
            withApplicationRecoveryOwner(tracker, selectedShop(), cloud = cloud, priceCloud = priceCloud,
                debounceMs = 0) { application, _, _ ->
                testScheduler.runCurrent() // Initial auth work sees ERROR, before completion becomes READY.
                assertFalse(pushed.isCompleted)
                invokeApplicationRecoveryCompletion(application, selectedShop())
                val resumed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(5_000) { pushed.await(); true }
                }
                assertEquals("completion must rediscover the durable queue without another edit/foreground", true, resumed)
                val confirmed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(5_000) { repository.observeProductCloudConfirmed(original.id).first { it }; true }
                }
                val confirmationPredicates = db.openHelper.readableDatabase.query("""
                    SELECT (r.localChangeRevision=r.lastSyncedLocalRevision),
                        (SELECT COUNT(*) FROM product_prices p LEFT JOIN product_price_remote_refs b ON b.productPriceId=p.id
                         WHERE p.productId=r.productId AND b.id IS NULL),
                        (SELECT COUNT(*) FROM sync_event_outbox WHERE eventType='LOCAL_BUSINESS_WRITE_V1' AND domain='PRICES')
                    FROM product_remote_refs r WHERE r.productId=?
                """.trimIndent(), arrayOf(original.id)).use { cursor ->
                    check(cursor.moveToFirst())
                    "productAck=${cursor.getInt(0)}, missingPriceBridges=${cursor.getInt(1)}, priceAttempts=${cursor.getInt(2)}"
                }
                assertEquals(confirmationPredicates, true, confirmed)
                assertEquals("Durable intent with lost callback", db.productDao().getById(original.id)?.productName)
                assertFalse(db.productRemoteRefDao().hasPendingWork())
                assertTrue(acknowledgedPrices.any { it.type == "PURCHASE" && it.price == 4.0 })
            }
        } finally { oldAuto.shutdown() }
    }

    private suspend fun invokeApplicationRecoveryCompletion(
        application: com.example.merchandisecontrolsplitview.MerchandiseControlApplication,
        shop: SelectedShop
    ): Unit = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
        application.javaClass.getDeclaredMethod("completeBusinessRecovery", AuthState.SignedIn::class.java,
            SelectedShop::class.java, Task126OwnerStoreScope::class.java, kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible = true }
            .invoke(application, AuthState.SignedIn(ACCOUNT, "qa@example.test"), shop, activeScope(), continuation)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> applicationField(target: Any, name: String): T = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target) as T

    private suspend fun kotlinx.coroutines.test.TestScope.withApplicationRecoveryOwner(
        tracker: CatalogSyncStateTracker,
        selected: SelectedShop,
        cloud: CatalogRemoteDataSource = NoOpCatalogRemoteForRecoveryTest,
        priceCloud: ProductPriceRemoteDataSource = NoOpPriceRemoteForRecoveryTest,
        debounceMs: Long = Long.MAX_VALUE,
        recoveryNowMs: () -> Long = { 1_000_000L },
        automaticRetryJitter: () -> Double = { 1.0 },
        recoveryRemote: ShopSyncReadRemoteDataSource = remote,
        recoveryLease: suspend (String, String) -> Boolean = { account, shop -> account == ACCOUNT && shop == SHOP },
        linkedShops: List<LinkedShop>? = null,
        beforeLinkedShopFetch: suspend () -> Unit = {},
        block: suspend (com.example.merchandisecontrolsplitview.MerchandiseControlApplication, SupabaseAuthManager, ShopContextRepository) -> Unit
    ) {
        val application = app as com.example.merchandisecontrolsplitview.MerchandiseControlApplication
        // Coordinator.shutdown owns its scope: each reopened application needs a live child,
        // never the TestScope background parent that later invocations also need.
        val ownerScope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext +
            kotlinx.coroutines.SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job]))
        assertTrue("Application fixture owner must start alive", ownerScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
        val original = application.javaClass.declaredFields.filter {
            it.name.endsWith("\$delegate") || it.name == "appScope" || it.name == "automaticBusinessRecoveryNowMs"
        }
            .associateWith { it.apply { isAccessible = true }.get(application) }
        fun install(name: String, value: Any?) {
            application.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(application, value)
        }
        val auth = SupabaseAuthManager(client = null, googleWebClientId = "", scope = ownerScope)
        applicationField<MutableStateFlow<AuthState>>(auth, "_state").value = AuthState.SignedIn(ACCOUNT, "qa@example.test")
        val shops = ShopContextRepository(object : LinkedShopRemoteDataSource {
            override val isConfigured = true
            override suspend fun fetchLinkedShops(): Result<List<LinkedShop>> {
                beforeLinkedShopFetch()
                return Result.success(linkedShops ?: listOf(LinkedShop(SHOP, null, selected.name,
                    selected.role, "active", selectable = true, canWrite = selected.canWrite)))
            }
        }, object : SelectedShopStore {
            override fun getSelectedShopId(ownerUserId: String) = SHOP
            override fun setSelectedShopId(ownerUserId: String, shopId: String) = Unit
            override fun clearSelectedShopId(ownerUserId: String) = Unit
        }, currentOwnerUserId = { ACCOUNT })
        shops.refresh(ACCOUNT)
        val auto = CatalogAutoSyncCoordinator(repository, cloud, priceCloud,
            authFlow = auth.state, selectedShopProvider = { selected }, syncStateTracker = tracker,
            scope = ownerScope, debounceMs = debounceMs)
        val history = HistorySessionPushCoordinator(repository, SupabaseSessionBackupRemoteDataSource(null),
            authFlow = auth.state, selectedShopProvider = { selected }, flightOwner = SessionCloudSessionFlightOwner(),
            syncStateTracker = tracker, scope = ownerScope, debounceMs = Long.MAX_VALUE)
        try {
            install("appScope", ownerScope)
            install("automaticBusinessRecoveryNowMs", recoveryNowMs)
            install("database\$delegate", lazyOf(db)); install("repository\$delegate", lazyOf(repository))
            install("authManager\$delegate", lazyOf(auth)); install("shopContextRepository\$delegate", lazyOf(shops))
            install("catalogSyncStateTracker\$delegate", lazyOf(tracker))
            install("catalogAutoSyncCoordinator\$delegate", lazyOf(auto)); install("historySessionPushCoordinator\$delegate", lazyOf(history))
            install("supabaseClient\$delegate", lazyOf(null))
            install("syncEventRemoteDataSource\$delegate", lazyOf(NoOpSyncEventRemoteForRecoveryTest))
            install("shopDeviceRegistrationRemoteDataSource\$delegate", lazyOf(ShopDeviceRegistrationRemoteDataSource(null,
                DeviceInstallIdProvider(db.syncEventDeviceStateDao()))))
            install("shopSyncRecoveryCoordinator\$delegate", lazyOf(coordinator(activationBoundary = {
                tracker.withBusinessDataScopeTransition { it() }
            }, nowMs = recoveryNowMs, automaticRetryJitter = automaticRetryJitter, recoveryRemote = recoveryRemote,
                scopeStillValid = recoveryLease)))
            block(application, auth, shops)
        } finally {
            applicationField<kotlinx.coroutines.Job?>(application, "businessRecoveryJob")?.let { owned ->
                owned.cancel()
                owned.join()
            }
            applicationField<kotlinx.coroutines.Job?>(application, "shopDeviceStatusPollingJob")?.cancel()
            auto.shutdown(); history.shutdown()
            original.forEach { (field, value) -> field.set(application, value) }
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyOrdinaryRecoveryKeepsLocalAccess(
        push: Boolean,
        saveAlreadyStarted: Boolean
    ) {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        val initial = repository.resolveBusinessDataScope(activeScope())
        assertEquals(Task126BusinessDataScopeStatus.READY, initial.status)
        assertNull(db.syncRecoveryJournalDao().get())
        val tracker = CatalogSyncStateTracker(initial)
        tracker.updateNetworkAvailability(true)
        val reader = object : ShopSyncReadRemoteDataSource by remote {
            override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> =
                remote.checkpoint(context).map { it.copy(syncEvents = it.syncEvents.copy(requiresFullRecovery = true)) }
        }
        repository = DefaultInventoryRepository(db, tracker, reader)
        val original = requireNotNull(db.productDao().findByBarcode("target-barcode"))
        if (push) repository.updateProduct(original.copy(productName = "Local intent before catalog push"))
        val cloud = object : CatalogRemoteDataSource by NoOpCatalogRemoteForRecoveryTest {
            override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> = Result.success(Unit)
            override suspend fun patchProduct(id: String, ownerUserId: String, patch: InventoryProductPatch): Result<Unit> = Result.success(Unit)
            override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch): Result<Unit> =
                patchProduct(id, ownerUserId, patch)
        }
        val events = object : SyncEventRemoteDataSource by NoOpSyncEventRemoteForRecoveryTest {
            override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> =
                Result.success(SyncEventRemoteRow(id = 43L, ownerUserId = ACCOUNT, shopId = SHOP,
                    storeId = params.storeId, domain = params.domain, eventType = params.eventType,
                    sourceDeviceId = params.sourceDeviceId, clientEventId = params.clientEventId,
                    changedCount = params.changedCount, entityIds = params.entityIds,
                    createdAt = "2026-07-21T12:00:00Z"))
        }
        val recoveryEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseRecovery = kotlinx.coroutines.CompletableDeferred<Unit>()
        remote.afterPage = { domain ->
            if (domain == ShopSyncRowDomain.PRODUCTS) { recoveryEntered.complete(Unit); releaseRecovery.await() }
        }
        var recoveryFlight: kotlinx.coroutines.Deferred<ShopSyncRecoveryResult>? = null
        val triggers = mutableListOf<String>()
        val auto = CatalogAutoSyncCoordinator(repository, cloud, NoOpPriceRemoteForRecoveryTest,
            syncEventRemote = events,
            deviceAuthorization = ShopDeviceAuthorizationRepository(ActiveShopDeviceRemoteForOrdinaryTest(SHOP),
                businessDataScopeRuntimeGuard = tracker),
            authFlow = MutableStateFlow(AuthState.SignedIn(ACCOUNT, "local@example.test")),
            selectedShopProvider = { selectedShop() }, syncStateTracker = tracker,
            scope = backgroundScope, debounceMs = Long.MAX_VALUE,
            onRecoveryRequired = { reason ->
                triggers += reason
                recoveryFlight = backgroundScope.async { coordinator().recover(ACCOUNT, selectedShop(), activeScope()) }
            })
        val saveEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseSave = kotlinx.coroutines.CompletableDeferred<Unit>()
        val saving = if (saveAlreadyStarted) backgroundScope.async {
            runCatching {
                tracker.withLocalBusinessDataScopeFlight {
                    saveEntered.complete(Unit)
                    releaseSave.await()
                    repository.updateProduct(original.copy(productName = "Save admitted before recovery publication"))
                }
            }
        } else null
        try {
            if (saving != null) saveEntered.await()
            if (push) auto.runPushCycle("local_commit") else auto.runSyncEventDrainCycle("held_recovery_regression")
            assertEquals(listOf(if (push) "catalog_push" else "sync_events_drain"), triggers)
            recoveryEntered.await()
            assertFalse(requireNotNull(recoveryFlight).isCompleted)
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            assertNotNull(db.syncRecoveryJournalDao().get())
            if (saving != null) {
                releaseSave.complete(Unit)
                val saved = saving.await()
                assertTrue("already admitted same-scope Save must survive cloud recovery publication: ${saved.exceptionOrNull()}", saved.isSuccess)
                assertEquals("Save admitted before recovery publication", db.productDao().getById(original.id)?.productName)
            }
            val observed = tracker.businessDataScopeState.value
            assertEquals("sync_recovery_required", observed.errorCode)
            assertFalse(observed.allowsCloudSync)
            assertTrue("real ordinary coordinator removed access to the activated local generation: $observed",
                com.example.merchandisecontrolsplitview.ui.navigation.businessContentAvailable(
                    true, AuthState.SignedIn(ACCOUNT, "local@example.test"), observed))
            val current = requireNotNull(db.productDao().getById(original.id))
            repository.updateProduct(current.copy(productName = "Save while recovery is held"))
            assertEquals("Save while recovery is held", db.productDao().getById(original.id)?.productName)
            assertFalse(releaseRecovery.isCompleted)
        } finally {
            releaseSave.complete(Unit)
            releaseRecovery.complete(Unit)
            saving?.cancel(); saving?.join()
            recoveryFlight?.cancel(); recoveryFlight?.join()
            auto.shutdown()
        }
        reopenOrdinaryDatabase()
        assertEquals("Save while recovery is held", db.productDao().getById(original.id)?.productName)
        assertTrue(repository.resolveBusinessDataScope(activeScope()).allowsLocalOperations)
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
        assertOnlyRetainedResolutionEvidenceAfterActualAck(sealedA)
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
        assertOnlyRetainedResolutionEvidenceAfterActualAck(original);assertFalse(db.productRemoteRefDao().hasPendingWork())
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
        assertOnlyRetainedResolutionEvidenceAfterActualAck(original);assertEquals(1,db.historyEntryDao().countUserVisible())
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

    @Test
    fun sameScopeBatchCopyPreservesBoundariesNullableUnicodeLargeHistoryAndLateSaveAcrossReopen() = runTest {
        seedVerifiedSameScopeRecovery()
        val base = targetFixture()
        val original = (base.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products).values.single()
        val products = listOf(original) + (2..130).map { index ->
            original.copy(id = "20000000-0000-4000-9000-" + index.toString().padStart(12, '0'),
                barcode = "batch-$index", productName = "Quote ' 漢 😀 $index",
                secondProductName = if (index % 2 == 0) null else "second-$index",
                itemNumber = if (index % 3 == 0) null else "item-$index",
                purchasePrice = if (index % 2 == 0) null else index / 10.0,
                retailPrice = index / 5.0, stockQuantity = if (index % 4 == 0) null else index.toDouble(),
                primaryImageVersionId = null, primaryImageUpdatedAt = null)
        }
        val historyBase = (base.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History).values.single()
        val histories = (1..65).map { index ->
            historyBase.copy(remoteId = "40000000-0000-4000-9000-" + index.toString().padStart(12, '0'),
                displayName = "Large history $index", data = listOf(listOf("barcode", "note"),
                    listOf("batch-$index", "' 漢 😀 ".repeat(2_000))))
        }
        val expanded = ordinaryDeltaFixture(base, products, 43L,
            mapOf(SyncEventDomains.CATALOG to "43", SyncEventDomains.HISTORY to "43"))
        remote = RecoveryRemoteFixture(expanded.copy(
            checkpoint = expanded.checkpoint.copy(history = checkpointDomain(
                histories.map { it.remoteId }, histories.map(::testHistoryVersion))),
            rows = expanded.rows + (ShopSyncRowDomain.HISTORY to ShopSyncRows.History(histories))))
        val localBefore = requireNotNull(db.productDao().findByBarcode(original.barcode))
        var readyBoundaryTraversed = false
        ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = {
            readyBoundaryTraversed = true
            runBlocking { repository.updateProduct(localBefore.copy(productName = "Late durable local save")) }
        }
        val result = try { coordinator(recoveryRemote = PagedRecoveryRemoteFixture(remote)).recover(ACCOUNT, selectedShop(), activeScope()) }
            finally { ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = null }
        assertTrue("The valid fixture must traverse staging validation before exercising the copy", readyBoundaryTraversed)
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        suspend fun verify() {
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            assertEquals((result as ShopSyncRecoveryResult.Activated).generationId, baseline.generationId)
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
            assertEquals(130, db.productDao().count())
            assertEquals(65, db.historyEntryDao().countUserVisible())
            for (expected in products.drop(1)) {
                val actual = requireNotNull(db.productDao().findByBarcode(expected.barcode))
                assertEquals(expected.productName, actual.productName)
                assertEquals(expected.secondProductName, actual.secondProductName)
                assertEquals(expected.itemNumber, actual.itemNumber)
                assertEquals(expected.purchasePrice, actual.purchasePrice)
                assertEquals(expected.retailPrice, actual.retailPrice)
                assertEquals(expected.stockQuantity ?: 0.0, actual.stockQuantity)
                assertEquals(expected.id, db.productRemoteRefDao().getByProductId(actual.id)?.remoteId)
            }
            val late = requireNotNull(db.productDao().getById(localBefore.id))
            assertEquals("Late durable local save", late.productName)
            val ref = requireNotNull(db.productRemoteRefDao().getByProductId(late.id))
            assertTrue(ref.localChangeRevision > ref.lastSyncedLocalRevision)
            assertEquals("productname", ref.localChangedFields)
            assertNull(db.syncRecoveryJournalDao().get())
            assertForeignKeysClean(db)
        }
        verify()
        repeat(2) { reopenOrdinaryDatabase(); verify() }
    }

    @Test
    fun sameScopeIdenticalBodyRetentionPublishesNewGenerationWithoutBusinessDmlAndReopens() = runTest {
        seedVerifiedSameScopeRecovery()
        val beforeBaseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val beforeBody = captureCutoverTables(cutoverProbeBusinessTables)
        val beforeManifest = captureCutoverManifest(beforeBaseline.generationId)
        installCutoverDmlProbe()
        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        val activated = result as ShopSyncRecoveryResult.Activated
        assertTrue(activated.generationId != beforeBaseline.generationId)
        assertEquals(0L, cutoverBusinessDmlCount())
        assertEquals(0L, cutoverManifestDmlCount("INSERT"))
        assertEquals(0L, cutoverManifestDmlCount("DELETE"))
        assertEquals(beforeManifest.size.toLong(), cutoverManifestDmlCount("UPDATE"))
        suspend fun verify() {
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            assertEquals(activated.generationId, baseline.generationId)
            assertEquals(beforeBody, captureCutoverTables(cutoverProbeBusinessTables))
            assertEquals(beforeManifest, captureCutoverManifest(baseline.generationId)
                .map { it.copy(generationId = beforeBaseline.generationId) })
            assertTrue(captureCutoverManifest(beforeBaseline.generationId).isEmpty())
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
            assertNull(db.syncRecoveryJournalDao().get())
            assertEquals(42L, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId)
            assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
            assertForeignKeysClean(db)
        }
        verify()
        repeat(2) { reopenOrdinaryDatabase(); verify() }
    }

    @Test
    fun sameScopeRetentionFallsBackForActualLateSaveAndPreservesItsProvenanceAcrossTwoReopens() = runTest {
        seedVerifiedSameScopeRecovery()
        val previous = requireNotNull(db.syncRecoveryBaselineDao().get())
        val product = db.productDao().getAll().single()
        var saved: Product? = null
        var savedRef: ProductRemoteRef? = null
        var savedEvidence = emptyList<SyncEventOutboxEntry>()
        installCutoverDmlProbe()
        ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = {
            runBlocking {
                repository.updateProductFromEditor(product, product.copy(productName = "Late Save keeps exact intent"))
                saved = db.productDao().getById(product.id)
                savedRef = db.productRemoteRefDao().getByProductId(product.id)
                savedEvidence = readLocalBusinessEntries(db, ACCOUNT, activeScope().storeId)
                requireNotNull(loadBusinessResolutionSnapshot(db, ACCOUNT, activeScope().storeId, savedEvidence)).requireValid()
            }
        }
        val result = try { coordinator().recover(ACCOUNT, selectedShop(), activeScope()) }
            finally { ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = null }
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        assertTrue("A real pending Save must use the full overlay path", cutoverBusinessDmlCount("DELETE") > 0L)
        assertTrue(savedEvidence.isNotEmpty())
        val captured = requireNotNull(savedRef)
        assertTrue(captured.localChangeRevision > captured.lastSyncedLocalRevision)
        suspend fun verify() {
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            assertTrue(previous.generationId != baseline.generationId)
            assertEquals((result as ShopSyncRecoveryResult.Activated).generationId, baseline.generationId)
            assertEquals(saved, db.productDao().getById(product.id))
            assertEquals(captured, db.productRemoteRefDao().getByProductId(product.id))
            val evidence = readLocalBusinessEntries(db, ACCOUNT, activeScope().storeId)
            assertTrue(evidence.containsAll(savedEvidence))
            val effective = requireNotNull(loadBusinessResolutionSnapshot(db, ACCOUNT, activeScope().storeId, evidence)).requireValid()
            assertEquals(BusinessResolutionLedger.EffectiveRevision.Active,
                effective.effectiveRevision(captured.remoteId, captured.localChangeRevision))
            assertTrue(effective.records.any { it.kind == "generation_transfer" })
            assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
            assertFalse(repository.observeProductCloudConfirmed(product.id).first())
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
            assertNull(db.syncRecoveryJournalDao().get())
            assertForeignKeysClean(db)
        }
        verify()
        repeat(2) { reopenOrdinaryDatabase(); verify() }
    }

    @Test
    fun sameScopeRetainedBodyAndManifestGenerationRollBackAfterLatePublicationFailure() = runTest {
        seedVerifiedSameScopeRecovery()
        val beforeBaseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val beforeBody = captureCutoverTables(cutoverProbeBusinessTables + listOf(
            "supplier_remote_refs", "category_remote_refs", "product_remote_refs",
            "product_price_remote_refs", "history_entry_remote_refs", "business_data_scope_binding",
            "sync_event_watermarks", "sync_recovery_baseline", "sync_recovery_manifest"))
        installCutoverDmlProbe()
        var stagedGenerationObserved: String? = null
        var businessWritesObserved = -1L
        ShopSyncRecoveryTestHooks.beforeActivationMetadata = {
            val sql = db.openHelper.writableDatabase
            businessWritesObserved = sql.query("SELECT COUNT(*) FROM temp.cutover_equality_probe WHERE tableName <> 'sync_recovery_manifest'").use { c ->
                check(c.moveToFirst()); c.getLong(0)
            }
            stagedGenerationObserved = sql.query("SELECT generationId FROM sync_recovery_baseline").use { c ->
                check(c.moveToFirst()); c.getString(0)
            }
            throw IllegalStateException("owned_equality_publication_failure")
        }
        var observedFailure: Throwable? = null
        ShopSyncRecoveryTestHooks.onRecoveryFailure = { observedFailure = it }
        val result = try { coordinator().recover(ACCOUNT, selectedShop(), activeScope()) }
            finally { ShopSyncRecoveryTestHooks.beforeActivationMetadata = null }
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.RetryRequired)
        assertEquals("owned_equality_publication_failure", observedFailure?.message)
        assertEquals(0L, businessWritesObserved)
        assertNotNull(stagedGenerationObserved)
        assertTrue(stagedGenerationObserved != beforeBaseline.generationId)
        suspend fun verify() {
            assertEquals(beforeBaseline, db.syncRecoveryBaselineDao().get())
            assertEquals(beforeBody, captureCutoverTables(beforeBody.keys.toList()))
            validateShopSyncActiveReceipt(db, beforeBaseline.generationId,
                decodeRecoveryCheckpointJson(beforeBaseline.checkpointJson))
            assertNotNull(db.syncRecoveryJournalDao().get())
            assertForeignKeysClean(db)
        }
        verify()
        repeat(2) { reopenOrdinaryDatabase(); verify() }
    }

    private val cutoverProbeBusinessTables = listOf("suppliers", "categories", "products", "product_prices", "history_entries")

    private suspend fun installCutoverDmlProbe() = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TEMP TABLE cutover_equality_probe(tableName TEXT NOT NULL, operation TEXT NOT NULL)")
        for (table in cutoverProbeBusinessTables + "sync_recovery_manifest") for (event in listOf("INSERT", "UPDATE", "DELETE")) {
            sql.execSQL("CREATE TEMP TRIGGER cutover_probe_${table}_$event AFTER $event ON main.`$table` BEGIN " +
                "INSERT INTO cutover_equality_probe VALUES('$table','$event'); END")
        }
    }

    private suspend fun cutoverBusinessDmlCount(operation: String? = null): Long = db.withTransaction {
        val predicate = if (operation == null) "" else " AND operation=?"
        val args = if (operation == null) emptyArray<Any>() else arrayOf<Any>(operation)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM temp.cutover_equality_probe " +
            "WHERE tableName <> 'sync_recovery_manifest'" + predicate, args).use { c ->
            check(c.moveToFirst()); c.getLong(0)
        }
    }

    private suspend fun cutoverManifestDmlCount(operation: String): Long = db.withTransaction {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM temp.cutover_equality_probe " +
            "WHERE tableName='sync_recovery_manifest' AND operation=?", arrayOf<Any>(operation)).use { c ->
            check(c.moveToFirst()); c.getLong(0)
        }
    }

    private suspend fun captureCutoverTables(tables: List<String>): Map<String, List<List<String>>> = db.withTransaction {
        tables.associateWith { table ->
            db.openHelper.writableDatabase.query("SELECT * FROM `$table` ORDER BY 1").use { c ->
                buildList {
                    while (c.moveToNext()) add((0 until c.columnCount).map { i ->
                        if (c.isNull(i)) "NULL" else c.getType(i).toString() + ":" + when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString("") { "%02x".format(it) }
                            else -> c.getString(i)
                        }
                    })
                }
            }
        }
    }

    private suspend fun captureCutoverManifest(generation: String): List<SyncRecoveryManifestRow> =
        ShopSyncRowDomain.entries.flatMap { db.syncRecoveryManifestDao().page(generation, it.wireValue, null, 100) }


    @Test
    fun componentBatchCopy59SmallHistoriesAndOversizeRowWithExplicitLargerWireLimitPreservesReopen() = runTest {
        seedVerifiedSameScopeRecovery()
        val base = targetFixture()
        val historyBase = (base.rows.getValue(ShopSyncRowDomain.HISTORY) as ShopSyncRows.History).values.single()
        val histories = (1..60).map { index ->
            historyBase.copy(remoteId = "40000000-0000-4000-9100-" + index.toString().padStart(12, '0'),
                displayName = "Bounded copy history $index",
                data = listOf(listOf("barcode", "note"), listOf("boundary-$index",
                    if (index == 60) "x".repeat(530_000) else "small ' 漢 $index")))
        }
        remote = RecoveryRemoteFixture(base.copy(
            checkpoint = base.checkpoint.copy(history = checkpointDomain(
                histories.map { it.remoteId }, histories.map(::testHistoryVersion))),
            rows = base.rows + (ShopSyncRowDomain.HISTORY to ShopSyncRows.History(histories))))
        val defaultLimits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS
        val encodedOversizeRow = Json.encodeToString(histories.last()).toByteArray(Charsets.UTF_8)
        assertTrue("The fixture deliberately exceeds production's 512KiB wire row limit",
            encodedOversizeRow.size.toLong() > defaultLimits.historyRowResponseBytes)
        val rawOversizeEnvelope = ("{\"rows\":[" + encodedOversizeRow.toString(Charsets.UTF_8) + "]}")
            .toByteArray(Charsets.UTF_8)
        val defaultFailure = runCatching {
            validateShopSyncHistoryRowsRawBudget(rawOversizeEnvelope, defaultLimits.historyRowResponseBytes)
        }.exceptionOrNull()
        assertTrue(defaultFailure is ShopSyncContractException)
        assertEquals("history_row_response_budget_exceeded", defaultFailure?.message)

        // Component boundary only: exercise the copier's >1MiB UTF16 branch with
        // an explicit larger test budget. This is NOT default/native wire acceptance.
        val componentLimits = defaultLimits.copy(historyRowResponseBytes = 1024L * 1024L)
        assertTrue(encodedOversizeRow.size.toLong() < componentLimits.historyRowResponseBytes)
        val paged = PagedRecoveryRemoteFixture(remote)
        var largestMeasured = 0L
        var historyPages = 0
        val measured = object : ShopSyncReadRemoteDataSource by paged {
            override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain,
                afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
                val page = paged.recoveryPage(context, domain, afterId, limit).getOrThrow()
                if (domain != ShopSyncRowDomain.HISTORY) return Result.success(page)
                val rows = (page.rows as ShopSyncRows.History).values
                fun string(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
                val envelope = JsonObject(mapOf(
                    "schemaVersion" to string(page.schemaVersion), "shopId" to string(page.shopId),
                    "scope" to Json.parseToJsonElement(Json.encodeToString(page.scope)),
                    "domain" to string(page.domain.wireValue),
                    "snapshotEventMaxId" to string(page.snapshotEventMaxId),
                    "currentScopeEventMaxId" to string(page.currentScopeEventMaxId),
                    "baselineDomainEventMaxId" to string(page.baselineDomainEventMaxId),
                    "pageDomainEventMaxId" to string(page.pageDomainEventMaxId),
                    "domainScope" to string(page.domainScope),
                    "pageLimit" to kotlinx.serialization.json.JsonPrimitive(page.pageLimit),
                    "rows" to Json.parseToJsonElement(Json.encodeToString(rows)),
                    "nextAfterId" to (page.nextAfterId?.let(::string) ?: JsonNull),
                    "hasMore" to kotlinx.serialization.json.JsonPrimitive(page.hasMore)))
                val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
                val largest = validateShopSyncHistoryRowsRawBudget(bytes, componentLimits.historyRowResponseBytes)
                assertTrue(bytes.size.toLong() <= componentLimits.historyPageResponseBytes)
                assertEquals(rows.maxOfOrNull { Json.encodeToString(it).toByteArray(Charsets.UTF_8).size.toLong() } ?: 0L, largest)
                largestMeasured = maxOf(largestMeasured, largest)
                historyPages += 1
                return Result.success(page.copy(responseBytes = bytes.size.toLong(), largestRowBytes = largest))
            }
        }
        var reachedReady = false
        ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = { reachedReady = true }
        val result = try { coordinator(recoveryRemote = measured, resourceLimits = componentLimits)
            .recover(ACCOUNT, selectedShop(), activeScope()) }
            finally { ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = null }
        assertTrue("Must traverse validated staging before claiming copy coverage", reachedReady)
        assertTrue(historyPages >= 20)
        assertEquals(encodedOversizeRow.size.toLong(), largestMeasured)
        assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
        suspend fun verify() {
            val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
            assertEquals(60, db.historyEntryDao().countUserVisible())
            for (expected in histories) {
                val ref = requireNotNull(db.historyEntryRemoteRefDao().getByRemoteId(expected.remoteId))
                assertEquals(expected.data, db.historyEntryDao().getByUid(ref.historyEntryUid)?.data)
            }
            val large = requireNotNull(db.historyEntryRemoteRefDao().getByRemoteId(histories.last().remoteId))
            // Actual row storage, not a declaration based on the source string's length.
            db.openHelper.readableDatabase.query("SELECT data, editable FROM history_entries WHERE uid = ?",
                arrayOf(large.historyEntryUid)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val strings = listOf(cursor.getString(0), cursor.getString(1))
                assertTrue("One row must exceed the batch's UTF16 estimate", strings.sumOf { it.length.toLong()*2L } > 1024L*1024L)
                assertTrue("Keep this fixture below an ordinary CursorWindow's byte budget", strings.sumOf { it.toByteArray().size.toLong() } < 1_800_000L)
            }
            assertForeignKeysClean(db)
            assertNull(db.syncRecoveryJournalDao().get())
        }
        verify()
        repeat(2) { reopenOrdinaryDatabase(); verify() }
    }

    @Test
    fun actualRoomBatchCopyUses986Then17BindingsAndSecondBatchSqlAbortRollsBack() = runTest {
        val sourceRows = seedBatchBoundaryHistoryRows(59)
        val destination = openDatabase("batch-copy-abort-${java.util.UUID.randomUUID()}.db")
        try {
            val sentinel = Product(barcode="batch-destination-sentinel", productName="Must remain")
            destination.productDao().insert(sentinel)
            val before = destination.productDao().getAll()
            val destinationSql = destination.openHelper.writableDatabase
            val binds = mutableListOf<Int>()
            var copiedBeforeSecond = -1L
            val observed = object : androidx.sqlite.db.SupportSQLiteDatabase by destinationSql {
                override fun compileStatement(sql: String): androidx.sqlite.db.SupportSQLiteStatement {
                    if (sql.startsWith("INSERT INTO `mc_recovery_source_history_entries`")) {
                        binds += sql.count { it == '?' }
                        if (binds.size == 1) {
                            destinationSql.execSQL("CREATE TEMP TRIGGER batch_copy_sql_abort BEFORE INSERT ON mc_recovery_source_history_entries " +
                                "WHEN NEW.uid = ${sourceRows.last().uid} BEGIN SELECT RAISE(ABORT, 'BATCH_SECOND_CONSTRAINT'); END")
                        } else if (binds.size == 2) {
                            copiedBeforeSecond = batchBoundaryCount(destinationSql, "temp.mc_recovery_source_history_entries")
                        }
                    }
                    return destinationSql.compileStatement(sql)
                }
            }
            destinationSql.beginTransaction()
            val failure = try {
                runCatching { invokeActualRecoverySourceCopy(db.openHelper.readableDatabase, observed) }.exceptionOrNull()
            } finally { destinationSql.endTransaction() } // Deliberately never marked successful.
            assertTrue("Must fail in SQLite's real second INSERT: $failure", failure is android.database.sqlite.SQLiteConstraintException)
            assertEquals(listOf(58*17,17), binds)
            assertTrue(binds.all { it <= 999 })
            assertEquals(58L, copiedBeforeSecond)
            assertEquals(before, destination.productDao().getAll())
            assertEquals(sourceRows, db.historyEntryDao().getAllUserVisibleFlow().first().sortedBy { it.uid })
            assertEquals(0L, batchBoundaryTempCount(destinationSql))
            assertForeignKeysClean(destination)
        } finally { destination.close() }
    }

    @Test
    fun actualRoomBatchCopyDuplicateUidFailsRealUniqueIndexAndRollsBack() = runTest {
        val sourceRows = seedBatchBoundaryHistoryRows(59)
        val source = db.openHelper.writableDatabase
        val destination = openDatabase("batch-copy-duplicate-${java.util.UUID.randomUUID()}.db")
        try {
            destination.productDao().insert(Product(barcode="duplicate-destination-sentinel", productName="Must remain"))
            val before = destination.productDao().getAll()
            val destinationSql = destination.openHelper.writableDatabase
            var copiedAtIndex = -1L
            val observed = object : androidx.sqlite.db.SupportSQLiteDatabase by destinationSql {
                override fun execSQL(sql: String) {
                    if (sql.startsWith("CREATE UNIQUE INDEX `mc_recovery_source_history_entries_uid`")) {
                        copiedAtIndex = batchBoundaryCount(destinationSql, "temp.mc_recovery_source_history_entries")
                    }
                    destinationSql.execSQL(sql)
                }
            }
            // TEMP is connection-local. Pin the source writer for DDL, cursor and copy;
            // no suspending DAO call occurs while either raw SQLite transaction is held.
            source.beginTransaction()
            val failure = try {
                source.execSQL("CREATE TEMP TABLE history_entries AS SELECT * FROM main.history_entries")
                source.execSQL("INSERT INTO temp.history_entries SELECT * FROM main.history_entries WHERE uid = ?",
                    arrayOf(sourceRows.last().uid))
                assertEquals(60L, batchBoundaryCount(source, "temp.history_entries"))
                destinationSql.beginTransaction()
                try {
                    runCatching { invokeActualRecoverySourceCopy(source, observed) }.exceptionOrNull()
                } finally { destinationSql.endTransaction() }
            } finally { source.endTransaction() } // Roll back the test-only TEMP shadow as well.
            assertTrue("Duplicate must fail the actual unique-index creation: $failure", failure is android.database.sqlite.SQLiteConstraintException)
            assertEquals(60L, copiedAtIndex)
            assertEquals(59L, batchBoundaryCount(source,"main.history_entries"))
            assertEquals(before,destination.productDao().getAll())
            assertEquals(0L,batchBoundaryTempCount(destinationSql))
            assertForeignKeysClean(destination)
        } finally {
            destination.close()
        }
        assertEquals(sourceRows,db.historyEntryDao().getAllUserVisibleFlow().first().sortedBy { it.uid })
    }

    private suspend fun seedBatchBoundaryHistoryRows(count: Int): List<HistoryEntry> {
        val values = (1..count).map { index ->
            val entry = HistoryEntry(id="batch-history-$index", displayName="Small $index", timestamp="2026-10-23 12:00:00",
                data=listOf(listOf("barcode","note"),listOf("batch-$index","' 漢")),
                editable=listOf(listOf("batch-$index","' 漢")), complete=listOf(false))
            entry.copy(uid=db.historyEntryDao().insert(entry))
        }
        return values.sortedBy { it.uid }
    }

    /** Narrow component oracle: the production private copy primitive, real Room/SQLite, no alternate implementation. */
    private fun invokeActualRecoverySourceCopy(source: androidx.sqlite.db.SupportSQLiteDatabase,
        destination: androidx.sqlite.db.SupportSQLiteDatabase) {
        val method=Class.forName("com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryCoordinatorKt")
            .getDeclaredMethod("copyRecoverySourceToTemp", androidx.sqlite.db.SupportSQLiteDatabase::class.java,
                androidx.sqlite.db.SupportSQLiteDatabase::class.java)
        method.isAccessible=true
        try { method.invoke(null,source,destination) }
        catch (wrapper: java.lang.reflect.InvocationTargetException) { throw requireNotNull(wrapper.cause) }
    }
    private fun batchBoundaryCount(sql: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Long =
        sql.query("SELECT COUNT(*) FROM $table").use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
    private fun batchBoundaryTempCount(sql: androidx.sqlite.db.SupportSQLiteDatabase): Long {
        // The rollback oracle must inspect the writer's TEMP schema, not an arbitrary WAL reader.
        sql.beginTransaction()
        return try {
            sql.query("SELECT COUNT(*) FROM sqlite_temp_master WHERE name LIKE 'mc_recovery_source_%' OR name='batch_copy_sql_abort'")
                .use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        } finally { sql.endTransaction() }
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
    fun automaticRecoveryStructured503PreservesAcceptedPricePageAndCursorAfterReopen() = runTest {
        seedOldMismatchGeneration()
        remote = RecoveryRemoteFixture(mixedRetainedPriceFixture())
        val paged = PagedRecoveryRemoteFixture(remote)
        val requests = mutableListOf<Pair<ShopSyncRowDomain, String?>>()
        val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>()
        val call = io.mockk.mockk<io.ktor.client.call.HttpClientCall>()
        val request = io.mockk.mockk<io.ktor.client.request.HttpRequest>()
        io.mockk.every { response.status } returns io.ktor.http.HttpStatusCode.ServiceUnavailable
        io.mockk.every { response.headers } returns io.ktor.http.headersOf(io.ktor.http.HttpHeaders.RetryAfter, "180")
        io.mockk.every { response.call } returns call
        io.mockk.every { call.request } returns request
        io.mockk.every { request.url } returns io.ktor.http.Url("https://example.test/rest/v1/rpc/shop_sync_recovery_page_v1")
        io.mockk.every { request.headers } returns io.ktor.http.Headers.Empty
        io.mockk.every { request.method } returns io.ktor.http.HttpMethod.Post
        // This is the canonical invoker's public contract wrapper plus structured HTTP cause/header.
        val unavailable = ShopSyncContractException("shop_sync_rpc_http_503").apply {
            initCause(io.ktor.client.plugins.ResponseException(response, "shop_sync_rpc_http_503"))
        }
        val classified = SyncErrorClassifier.classify(unavailable, 1_000_000L)
        assertEquals(SyncErrorCategory.RemoteTransient, classified.category)
        assertEquals(1_180_000L, classified.retryAfterAtMs)
        var failSecond = true
        var acceptedJournal: SyncRecoveryJournal? = null
        var acceptedPricePage: RecoveryPageProgress.Accepted? = null
        var acceptedPhysicalPriceRows: Int? = null
        val flaky = object : ShopSyncReadRemoteDataSource by paged {
            override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain,
                afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
                requests += domain to afterId
                if (domain == ShopSyncRowDomain.PRICES && afterId != null && failSecond) {
                    failSecond = false
                    val journal = requireNotNull(db.syncRecoveryJournalDao().get())
                    val stage = openDatabase(requireNotNull(journal.stagingDatabaseName))
                    try {
                        val accepted = requireNotNull(RecoveryPageProgress(stage.openHelper.writableDatabase)
                            .accepted(ShopSyncRowDomain.PRICES))
                        assertEquals("The previous price page must already be durable before HTTP503", 1L, accepted.pages)
                        assertFalse(accepted.complete)
                        assertEquals(afterId, accepted.afterId)
                        assertTrue(accepted.rows > 0)
                        // The wire ledger also includes prices whose parent is a product tombstone.
                        // Such rows remain in the manifest but must not be materialized in product_prices.
                        val activeParents = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRODUCTS) as ShopSyncRows.Products)
                            .values.filter { it.deletedAt == null }.map { it.id }.toSet()
                        val priceRows = (remote.fixture.rows.getValue(ShopSyncRowDomain.PRICES) as ShopSyncRows.Prices).values
                        val cursorIndex = priceRows.indexOfFirst { it.id == afterId }
                        assertTrue("The persisted cursor must identify a real row in the accepted fixture prefix", cursorIndex >= 0)
                        val prefix = priceRows.take(cursorIndex + 1)
                        assertEquals(prefix.size.toLong(), accepted.rows)
                        assertEquals(accepted.rows.toInt(), stage.syncRecoveryManifestDao()
                            .count(requireNotNull(journal.runId), ShopSyncRowDomain.PRICES.wireValue))
                        val physicalRows = prefix.count { it.productId in activeParents }
                        assertEquals(physicalRows, stage.productPriceDao().countAll())
                        acceptedJournal = journal
                        acceptedPricePage = accepted
                        acceptedPhysicalPriceRows = physicalRows
                    } finally { stage.close() }
                    return Result.failure(unavailable)
                }
                return paged.recoveryPage(context, domain, afterId, limit)
            }
        }
        val failed = coordinator(recoveryRemote = flaky).recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(failed.toString(), failed is ShopSyncRecoveryResult.RetryRequired)
        assertEquals(SyncErrorCategory.RemoteTransient, (failed as ShopSyncRecoveryResult.RetryRequired).classification?.category)
        assertOldGenerationAndManifestIntact()
        val accepted = requireNotNull(acceptedJournal)
        val afterFailure = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals("A typed transient HTTP503 must retain the already accepted staging generation",
            accepted.stagingDatabaseName, afterFailure.stagingDatabaseName)
        assertEquals(accepted.runId, afterFailure.runId)
        assertEquals(accepted.checkpointADigest, afterFailure.checkpointADigest)
        assertEquals(SyncRecoveryJournalPhases.STAGING, afterFailure.phase)
        db.close()
        db = openDatabase(ACTIVE_DATABASE)
        repository = DefaultInventoryRepository(db)
        assertEquals(afterFailure, db.syncRecoveryJournalDao().get())
        val reopenedStage = openDatabase(requireNotNull(afterFailure.stagingDatabaseName))
        try {
            assertEquals(acceptedPricePage, RecoveryPageProgress(reopenedStage.openHelper.writableDatabase)
                .accepted(ShopSyncRowDomain.PRICES))
            assertEquals(requireNotNull(acceptedPricePage).rows.toInt(), reopenedStage.syncRecoveryManifestDao()
                .count(requireNotNull(afterFailure.runId), ShopSyncRowDomain.PRICES.wireValue))
            assertEquals(requireNotNull(acceptedPhysicalPriceRows), reopenedStage.productPriceDao().countAll())
        } finally { reopenedStage.close() }
        val resumed = coordinator(recoveryRemote = flaky).recover(ACCOUNT, selectedShop(), activeScope())
        assertTrue(resumed.toString(), resumed is ShopSyncRecoveryResult.Activated)
        assertEquals("The accepted first price page must not be downloaded again after structured503/reopen", 1,
            requests.count { it.first == ShopSyncRowDomain.PRICES && it.second == null })
        for (domain in listOf(ShopSyncRowDomain.SUPPLIERS, ShopSyncRowDomain.CATEGORIES, ShopSyncRowDomain.PRODUCTS)) {
            assertEquals("A completed dependency domain remains accepted: $domain", 1, requests.count { it.first == domain })
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

        var observedFailure: Throwable? = null
        ShopSyncRecoveryTestHooks.onRecoveryFailure = { observedFailure = it }
        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result is ShopSyncRecoveryResult.RetryRequired)
        assertEquals("fixture_late_activation_failure", observedFailure?.message)
        assertEquals(ShopSyncRecoveryReasons.RECOVERY_FAILED, (result as ShopSyncRecoveryResult.RetryRequired).code)
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

        var observedFailure: Throwable? = null
        ShopSyncRecoveryTestHooks.onRecoveryFailure = { observedFailure = it }
        val result = coordinator().recover(ACCOUNT, selectedShop(), activeScope())

        assertTrue(result is ShopSyncRecoveryResult.RetryRequired)
        assertEquals("fixture_event_page_not_configured", observedFailure?.message)
        assertEquals(ShopSyncRecoveryReasons.RECOVERY_FAILED, (result as ShopSyncRecoveryResult.RetryRequired).code)
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
        recoveryRemote: ShopSyncReadRemoteDataSource = remote,
        nowMs: () -> Long = { 1_000_000L },
        automaticRetryJitter: () -> Double = { 1.0 }
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
        nowMs = nowMs,
        resourceLimits = resourceLimits,
        generationSizeBytes = generationSizeBytes,
        activeGenerationSizeBytes = activeGenerationSizeBytes,
        availableStorageBytes = availableStorageBytes,
        checkpointDecoder = checkpointDecoder,
        deleteStagingDatabase = deleteStagingDatabase,
        automaticRetryJitter = automaticRetryJitter
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
        val oldReceipt = runCatching {
            validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        }.exceptionOrNull()
        assertEquals("recovery_active_partition_scope_mismatch", (oldReceipt as? ShopSyncContractException)?.code)
        assertTrue(db.syncRecoveryManifestDao().page(baseline.generationId,
            ShopSyncRowDomain.PRODUCTS.wireValue, null, 10).isNotEmpty())
        validateShopSyncActiveReceipt(db, newer.generationId, decodeRecoveryCheckpointJson(newer.checkpointJson))
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
    fun ordinaryNoEventPreflightZeroReadsFreshFullMarkerOnBothCycles() = runTest {
        assertNoEventPreflightCycles(emptyZero = true)
    }

    @Test
    fun ordinaryNoEventPreflightPopulatedReadsFreshFullMarkerOnBothCycles() = runTest {
        assertNoEventPreflightCycles(emptyZero = false)
    }

    private suspend fun assertNoEventPreflightCycles(emptyZero: Boolean) {
        seedOldMismatchGeneration()
        if (emptyZero) recoverAndReopenZeroOrdinaryFixture()
        else recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val checkpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson)
        val watermark = if (emptyZero) 0L else 42L
        val before = ordinaryMarkerApplicationRows()
        val checkpoints = remote.checkpointCalls
        val pages = remote.pageCalls
        val reader = OrdinaryPreflightObservedReader(OrdinaryFencedReadFixture(remote))
        repeat(2) { cycle ->
            if (cycle == 1) reopenOrdinaryDatabase()
            val summary = drainOrdinary(reader).getOrThrow()
            assertFalse(summary.manualFullSyncRequired)
            assertFalse(summary.syncEventsGapDetected)
            assertEquals(0, summary.syncEventsFetched)
            assertEquals(0, summary.syncEventsProcessed)
            assertEquals(watermark, summary.syncEventsWatermarkAfter)
            assertEquals(List(cycle + 1) { listOf("event", "marker") }.flatten(), reader.reads)
            assertEquals(checkpoints, remote.checkpointCalls)
            assertEquals(pages, remote.pageCalls)
            assertTrue(reader.checkpointContexts.isEmpty())
            assertTrue(reader.targetedContexts.isEmpty())
            assertEquals(cycle + 1, reader.eventContexts.size)
            assertEquals(cycle + 1, reader.markerContexts.size)
            reader.eventContexts.forEach { context ->
                assertEquals(checkpoint.scope, context.expectedScope)
                assertEquals(watermark.toString(), context.expectedEventMaxId)
            }
            reader.markerContexts.forEach { context ->
                assertEquals(checkpoint.scope, context.expectedScope)
                assertEquals(watermark.toString(), context.verifiedBaselineId)
                assertEquals(checkpoint.scope.key, context.expectedBaselineScopeKey)
            }
            assertEquals(List(cycle + 1) { watermark }, reader.afterIds)
            assertEquals(before, ordinaryMarkerApplicationRows())
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
            assertNull(db.syncRecoveryJournalDao().get())
            assertEquals(Task126BusinessDataScopeStatus.READY, repository.resolveBusinessDataScope(activeScope()).status)
            validateShopSyncActiveReceipt(db, baseline.generationId, checkpoint)
            assertForeignKeysClean(db)
        }
    }

    @Test
    fun ordinaryNoEventPreflightFreshHeadRaceUsesCheckpointBeforeApplyingRealDelta() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val before = ordinaryMarkerApplicationRows()
        val delta = prepareFirstZeroHistoryDelta()
        val reader = OrdinaryPreflightObservedReader(OrdinaryFencedReadFixture(remote))
        reader.pageTransform = { call, page ->
            if (call == 1) {
                assertEquals("0", page.asOfEventMaxId)
                page.copy(scopeEventMaxId = "1") // H is fresh; asOf is still the requested old W.
            } else page
        }
        reader.beforeCheckpoint = { assertEquals(before, ordinaryMarkerApplicationRows()) }
        reader.beforeTargeted = {
            assertTrue(reader.checkpointContexts.isNotEmpty())
            assertEquals(listOf("event", "checkpoint"), reader.reads.take(2))
        }

        val summary = drainOrdinary(reader).getOrThrow()

        assertEquals(listOf("event", "checkpoint"), reader.reads.take(2))
        assertEquals(1, reader.checkpointContexts.size)
        assertTrue(reader.targetedContexts.isNotEmpty())
        assertEquals(1, summary.syncEventsFetched)
        assertEquals(1, summary.syncEventsProcessed)
        assertEquals(1L, summary.syncEventsWatermarkAfter)
        assertFalse(summary.manualFullSyncRequired)
        assertFalse(summary.syncEventsGapDetected)
        assertOrdinaryPublishedReceipt(baseline.generationId, delta.checkpoint, 1L)
        assertEquals(1, db.historyEntryDao().countUserVisible())
        assertNull(db.syncRecoveryJournalDao().get())
        assertForeignKeysClean(db)
    }

    @Test
    fun ordinaryNoEventPreflightUnsafeSuccessfulPageFallsBackBeforeAnyMarker() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val before = ordinaryMarkerApplicationRows()
        val unsafe = listOf("head", "asof", "domain", "scope", "shop", "rows", "terminal", "cursor", "schema", "limit")
        for (kind in unsafe) {
            val reader = OrdinaryPreflightObservedReader(OrdinaryFencedReadFixture(remote))
            reader.pageTransform = { call, page ->
                if (call != 1) page else when (kind) {
                    "head" -> page.copy(scopeEventMaxId = "1")
                    "asof" -> page.copy(asOfEventMaxId = "1")
                    "domain" -> page.copy(asOfDomainEventMaxIds = page.asOfDomainEventMaxIds + (SyncEventDomains.HISTORY to "1"))
                    "scope" -> page.copy(scope = page.scope.copy(key = "f".repeat(64)))
                    "shop" -> page.copy(shopId = OLD_ACCOUNT)
                    "rows" -> page.copy(rows = listOf(ordinaryEvent(1L, SyncEventDomains.CATALOG,
                        SyncEventTypes.CATALOG_CHANGED, SyncEventEntityIds()).copy(changedCount = 0)))
                    "terminal" -> page.copy(hasMore = true, nextAfterId = 1L)
                    "cursor" -> page.copy(nextAfterId = 1L)
                    "schema" -> page.copy(schemaVersion = "unsupported-event-page")
                    else -> page.copy(pageLimit = page.pageLimit + 1)
                }
            }
            reader.beforeCheckpoint = { assertEquals(before, ordinaryMarkerApplicationRows()) }

            val summary = drainOrdinary(reader).getOrThrow()

            assertEquals(kind, listOf("event", "checkpoint"), reader.reads.take(2))
            assertEquals(kind, 1, reader.checkpointContexts.size)
            assertEquals(kind, 1, reader.markerContexts.size)
            assertEquals(kind, 2, reader.eventContexts.size)
            assertTrue(kind, reader.targetedContexts.isEmpty())
            assertFalse(kind, summary.manualFullSyncRequired)
            assertFalse(kind, summary.syncEventsGapDetected)
            assertEquals(kind, 0, summary.syncEventsFetched)
            assertEquals(kind, before, ordinaryMarkerApplicationRows())
            assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        }
    }

    @Test
    fun ordinaryNoEventPreflightChangedDigestStillRequiresOriginalCheckpointAndRecovery() = runTest {
        assertNoEventPreflightMarkerDrift(materialPrice = false)
    }

    @Test
    fun ordinaryNoEventPreflightChangedPriceMaterialStillRequiresOriginalCheckpointAndRecovery() = runTest {
        assertNoEventPreflightMarkerDrift(materialPrice = true)
    }

    private suspend fun assertNoEventPreflightMarkerDrift(materialPrice: Boolean) {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val before = ordinaryMarkerApplicationRows()
        val reader = OrdinaryPreflightObservedReader(OrdinaryFencedReadFixture(remote))
        reader.markerTransform = { marker ->
            if (materialPrice) marker.copy(prices = marker.prices.copy(versionDigest = "f".repeat(64)))
            else marker.copy(checkpointDigest = "f".repeat(64))
        }
        reader.beforeCheckpoint = { assertEquals(before, ordinaryMarkerApplicationRows()) }

        val summary = drainOrdinary(reader).getOrThrow()

        assertEquals(listOf("event", "marker", "checkpoint"), reader.reads.take(3))
        assertEquals(1, reader.checkpointContexts.size)
        assertEquals(2, reader.markerContexts.size)
        assertTrue(summary.manualFullSyncRequired)
        assertTrue(summary.syncEventsGapDetected)
        assertEquals(0, summary.syncEventsProcessed)
        assertEquals(0L, summary.syncEventsWatermarkAfter)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED, db.syncRecoveryJournalDao().get()?.reason)
        assertEquals(before.filterKeys { it != "sync_recovery_journal" },
            ordinaryMarkerApplicationRows().filterKeys { it != "sync_recovery_journal" })
        assertTrue(reader.targetedContexts.isEmpty())
    }

    @Test
    fun ordinaryNoEventPreflightRegressedDeletedEventHeadCannotPublishFalseNoWork() = runTest {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val current = remote.fixture.checkpoint
        remote.checkpoints += current.copy(syncEvents = current.syncEvents.copy(maxId = "0",
            domainMaxIds = current.syncEvents.domainMaxIds.mapValues { "0" }))
        val reader = OrdinaryPreflightObservedReader(OrdinaryFencedReadFixture(remote))
        reader.pageTransform = { call, page -> if (call == 1) page.copy(scopeEventMaxId = "0") else page }

        val summary = drainOrdinary(reader).getOrThrow()

        assertEquals(listOf("event", "checkpoint"), reader.reads.take(2))
        assertEquals("42", reader.eventContexts.first().expectedEventMaxId)
        assertEquals(42L, reader.afterIds.first())
        assertTrue(summary.manualFullSyncRequired)
        assertTrue(summary.syncEventsGapDetected)
        assertEquals(0, summary.syncEventsProcessed)
        assertEquals(42L, summary.syncEventsWatermarkAfter)
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(SyncEventApplyStatusReasons.CONVERGENCE_PROOF_REQUIRED, db.syncRecoveryJournalDao().get()?.reason)
    }

    @Test
    fun ordinaryNoEventPreflightTransportAndAuthFailuresNeverRetryCheckpointOrMutateRoom() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val before = ordinaryMarkerApplicationRows()
        for (atMarker in listOf(false, true)) {
            for (failure in listOf(java.io.IOException("fixture_preflight_io"),
                ShopSyncContractException("shop_sync_rpc_http_401"))) {
                val reader = OrdinaryPreflightObservedReader(OrdinaryFencedReadFixture(remote))
                if (atMarker) reader.markerFailure = failure else reader.eventFailure = failure

                val result = drainOrdinary(reader)

                assertTrue(result.isFailure)
                assertSame(failure, result.exceptionOrNull())
                assertEquals(if (atMarker) listOf("event", "marker") else listOf("event"), reader.reads)
                assertTrue(reader.checkpointContexts.isEmpty())
                assertTrue(reader.targetedContexts.isEmpty())
                assertActivatedZeroMarkerFailureUnchanged(baseline, before)
            }
        }
    }

    @Test
    fun ordinaryNoEventPreflightCancellationNeverRetriesCheckpointOrMutatesRoom() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val before = ordinaryMarkerApplicationRows()
        for (atMarker in listOf(false, true)) {
            val cancellation = CancellationException("fixture_preflight_cancel")
            val reader = OrdinaryPreflightObservedReader(OrdinaryFencedReadFixture(remote))
            if (atMarker) reader.markerFailure = cancellation else reader.eventFailure = cancellation

            val thrown = runCatching { drainOrdinary(reader) }.exceptionOrNull()

            assertTrue("Cancellation must propagate across the original IO coroutine boundary", thrown is CancellationException)
            // Coroutine stack recovery may copy the exception while retaining the injected original as its cause.
            var original: Throwable? = thrown
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (original != null && original !== cancellation && visited.add(original)) original = original.cause
            assertSame("The exact injected cancellation must remain in the cause chain", cancellation, original)
            assertEquals(if (atMarker) listOf("event", "marker") else listOf("event"), reader.reads)
            assertTrue(reader.checkpointContexts.isEmpty())
            assertTrue(reader.targetedContexts.isEmpty())
            assertActivatedZeroMarkerFailureUnchanged(baseline, before)
        }
    }

    @Test
    fun ordinaryNoEventPreflightStillRejectsCapturedWatermarkRaceInsideOriginalTransaction() = runTest {
        seedOldMismatchGeneration()
        val baseline = recoverAndReopenZeroOrdinaryFixture()
        val watermark = requireNotNull(db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        val successor = watermark.copy(lastSyncEventId = 1L)
        val reader = OrdinaryPreflightObservedReader(OrdinaryFencedReadFixture(remote))
        var afterRace: Map<String, List<List<String>>>? = null
        reader.beforeMarker = {
            db.syncEventWatermarkDao().upsert(successor)
            afterRace = ordinaryMarkerApplicationRows()
        }

        val result = drainOrdinary(reader)

        assertTrue(result.isFailure)
        assertEquals("ordinary_captured_publication_changed", (result.exceptionOrNull() as? ShopSyncContractException)?.code)
        assertEquals(listOf("event", "marker"), reader.reads)
        assertTrue(reader.checkpointContexts.isEmpty())
        assertEquals(requireNotNull(afterRace), ordinaryMarkerApplicationRows())
        assertEquals(successor, db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId))
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertNull(db.syncRecoveryJournalDao().get())
    }

    @Test
    fun ordinaryNoEventPreflightDoesNotSkipPendingProduct() = runTest {
        assertNoEventPreflightKeepsPending("product")
    }

    @Test
    fun ordinaryNoEventPreflightDoesNotSkipPendingHistory() = runTest {
        assertNoEventPreflightKeepsPending("history")
    }

    @Test
    fun ordinaryNoEventPreflightDoesNotSkipPendingOutbox() = runTest {
        assertNoEventPreflightKeepsPending("outbox")
    }

    private suspend fun assertNoEventPreflightKeepsPending(kind: String) {
        seedOldMismatchGeneration()
        recoverAndReopenOrdinaryFixture(if (kind == "history") v2HistoryFixture() else targetFixture())
        remote.emptyTailConfigured = true
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        when (kind) {
            "product" -> {
                val original = db.productDao().getAll().single()
                repository.updateProductFromEditor(original, original.copy(productName = "Pending during no-event preflight"))
                val ref = requireNotNull(db.productRemoteRefDao().getByProductId(original.id))
                assertTrue(ref.localChangeRevision > ref.lastSyncedLocalRevision)
            }
            "history" -> {
                val history = db.historyEntryDao().getAllUserVisibleSnapshot().single()
                repository.deleteHistoryEntry(history)
                val ref = requireNotNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(history.uid))
                assertTrue(ref.localChangeRevision > ref.lastSyncedLocalRevision)
            }
            else -> {
                // This case isolates retention of a genuinely deferred notification, not a retry-due send.
                // Due sends/ACK and the inclusive retry floor remain covered by the original repository tests.
                val deferred = SyncEventRetryMetadata(
                    category = SyncErrorCategory.NetworkOfflineOrTimeout, phase = "cooldown", windowAttempts = 5,
                    lastAttemptAtMs = 1L, lastCompletedAtMs = 1L, nextEligibleAtMs = Long.MAX_VALUE,
                    claimID = "70000000-0000-4000-8000-000000000078")
                val encoded = deferred.encode()
                assertEquals(deferred, SyncEventRetryMetadata.decode(encoded))
                db.syncEventOutboxDao().insert(SyncEventOutboxEntry(
                    ownerUserId = ACCOUNT, storeScope = activeScope().storeId,
                    domain = SyncEventDomains.CATALOG, eventType = SyncEventTypes.CATALOG_CHANGED,
                    source = "fixture-preflight", sourceDeviceId = DEVICE, batchId = null,
                    clientEventId = "70000000-0000-4000-8000-000000000077", changedCount = 0,
                    entityIdsJson = "{}", metadataJson = "{}", createdAtMs = 1L,
                    attemptCount = 5, lastAttemptAtMs = 1L, lastErrorType = encoded))
                assertEquals(1, db.syncEventOutboxDao().countAll())
            }
        }
        val pendingRows = ordinaryMarkerApplicationRows()
        val reader = OrdinaryPreflightObservedReader(OrdinaryFencedReadFixture(remote))

        val summary = drainOrdinary(reader).getOrThrow()

        assertEquals(listOf("event", "marker"), reader.reads)
        assertTrue(summary.syncEventsSkippedDirtyLocal > 0)
        assertFalse(summary.manualFullSyncRequired)
        assertFalse(summary.syncEventsGapDetected)
        assertEquals(0, summary.syncEventsProcessed)
        assertEquals(42L, summary.syncEventsWatermarkAfter)
        assertEquals(pendingRows, ordinaryMarkerApplicationRows())
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertNull(db.syncRecoveryJournalDao().get())
        assertTrue(reader.checkpointContexts.isEmpty())
        assertTrue(reader.targetedContexts.isEmpty())
        assertForeignKeysClean(db)
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
        var markerCalls = 0
        var watermarkPresentAtLastMarker: Boolean? = null
        val racing = object : ShopSyncReadRemoteDataSource by source {
            override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
                markerCalls++
                val marker = source.convergenceMarker(context)
                assertTrue(marker.isSuccess)
                val currentWatermark = db.syncEventWatermarkDao().get(ACCOUNT, activeScope().storeId)?.lastSyncEventId
                watermarkPresentAtLastMarker = currentWatermark != null
                assertEquals(0L, currentWatermark)
                db.syncEventWatermarkDao().deleteAll()
                removedDuringRemote = true
                return marker
            }
        }

        val failed = drainOrdinary(racing)

        assertTrue(removedDuringRemote)
        assertTrue(failed.isFailure)
        val observedFailure = failed.exceptionOrNull()
        assertEquals("failure_type=${observedFailure?.javaClass?.name ?: "absent"}; marker_calls=$markerCalls; " +
            "watermark_present_last=$watermarkPresentAtLastMarker", IllegalArgumentException::class.java,
            observedFailure?.javaClass)
        assertEquals("resolution_owner_provenance_missing", observedFailure?.message)
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

            assertEquals(2, markerCalls)
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
                override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> =
                    source.eventPage(context, afterId, limit).map { page ->
                        // The current head is independent of the requested as-of fence and may advance past it.
                        page.copy(scopeEventMaxId = remote.checkpoints.last().syncEvents.maxId)
                    }
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
            assertEquals(2, markerCalls)
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
            assertEquals(2, markerCalls)
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
        assertEquals(2, markerCalls)
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
        assertEquals("Existing recovery latch must not trigger an extra marker request", 2, markerCalls)
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
                override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> =
                    source.eventPage(context, afterId, limit).map {
                        it.copy(scopeEventMaxId = remote.checkpoints.last().syncEvents.maxId)
                    }
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
                    return page.map {
                        it.copy(scopeEventMaxId = remote.checkpoints.last().syncEvents.maxId)
                    }
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
    private fun ordinaryMarkerApplicationRows(source: AppDatabase = db): Map<String, List<List<String>>> {
        val sql = source.openHelper.readableDatabase
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

    private fun openDatabase(name: String, queryCallback: androidx.room.RoomDatabase.QueryCallback? = null): AppDatabase {
        databaseNames += name
        val opened = Room.databaseBuilder(app, AppDatabase::class.java, name)
            .apply { if (queryCallback != null) setQueryCallback(queryCallback, java.util.concurrent.Executor { it.run() }) }
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

    private suspend fun assertOnlyRetainedResolutionEvidenceAfterActualAck(original: SyncEventOutboxEntry) {
        val entries = readLocalBusinessEntries(db, ACCOUNT, activeScope().storeId)
        assertTrue("local lineage evidence must remain after real ACK", entries.isNotEmpty())
        assertTrue(entries.all { it.eventType == BUSINESS_RESOLUTION_EVENT })
        assertEquals(entries.size, db.syncEventOutboxDao().countAll())
        val ledger = requireNotNull(loadBusinessResolutionSnapshot(db, ACCOUNT, activeScope().storeId)).requireValid()
        assertTrue("immutable original seal must survive in the transfer anchor", ledger.records.any { record ->
            record.transfer?.originalAttemptSeals?.any {
                it.operationKey == original.clientEventId && it.rawAttempt == original.metadataJson
            } == true
        })
        assertEquals(0, repository.getLocalDatabaseStatusSnapshot(ACCOUNT, selectedShop()).syncEventOutboxPending)
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
    var beforeCheckpoint: (suspend () -> Unit)? = null
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
        beforeCheckpoint?.invoke()
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

/** Per-test observation only; the existing source still supplies every unmodified response. */
private class OrdinaryPreflightObservedReader(
    private val source: ShopSyncReadRemoteDataSource
) : ShopSyncReadRemoteDataSource by source {
    val reads = mutableListOf<String>()
    val checkpointContexts = mutableListOf<ShopSyncRpcContext>()
    val eventContexts = mutableListOf<ShopSyncRpcContext>()
    val markerContexts = mutableListOf<ShopSyncRpcContext>()
    val targetedContexts = mutableListOf<ShopSyncRpcContext>()
    val afterIds = mutableListOf<Long>()
    var pageTransform: (Int, ShopSyncEventPage) -> ShopSyncEventPage = { _, page -> page }
    var markerTransform: (ShopSyncConvergenceMarker) -> ShopSyncConvergenceMarker = { it }
    var eventFailure: Exception? = null
    var markerFailure: Exception? = null
    var beforeCheckpoint: (() -> Unit)? = null
    var beforeTargeted: (() -> Unit)? = null
    var beforeMarker: (suspend () -> Unit)? = null

    override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
        reads += "checkpoint"
        checkpointContexts += context
        beforeCheckpoint?.invoke()
        return source.checkpoint(context)
    }

    override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> {
        reads += "event"
        eventContexts += context
        afterIds += afterId
        eventFailure?.let { return Result.failure(it) }
        return source.eventPage(context, afterId, limit).map { pageTransform(eventContexts.size, it) }
    }

    override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
        reads += "marker"
        markerContexts += context
        beforeMarker?.invoke()
        markerFailure?.let { return Result.failure(it) }
        return source.convergenceMarker(context).map(markerTransform)
    }

    override suspend fun rowsByIds(context: ShopSyncRpcContext, domain: ShopSyncRowDomain,
        ids: List<String>): Result<ShopSyncTargetedRows> {
        reads += "rows"
        targetedContexts += context
        beforeTargeted?.invoke()
        return source.rowsByIds(context, domain, ids)
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

/** Reuses the real empty recovery/activation path before tests create actual typed ACK bodies. */
internal suspend fun activateEmptyV6EventRecoveryFixture(
    context: android.content.Context, db: AppDatabase, owner: String, shop: SelectedShop
): ShopSyncRecoveryCheckpoint {
    val device = db.syncEventDeviceStateDao().get()?.deviceId ?: "00000000-0000-4000-8000-000000000807".also {
        db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId=it, createdAtMs=1L))
    }
    val scope = task126ActiveOwnerStoreScope(owner, shop)
    val empty = emptyTargetFixture()
    val checkpoint = empty.checkpoint.copy(shopId=shop.shopId,
        scope=empty.checkpoint.scope.copy(accountKey=testSha256(owner), deviceKey=testSha256(device)),
        syncEvents=empty.checkpoint.syncEvents.copy(maxId="0", verifiedBaselineId="0",
            domainMaxIds=empty.checkpoint.syncEvents.domainMaxIds.mapValues { "0" }))
    val reader = RecoveryRemoteFixture(empty.copy(checkpoint=checkpoint)).apply { emptyTailConfigured=true }
    db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash=scope.ownerHash, storeScope=scope.storeId,
        shopId=shop.shopId, deviceId=device, authorizationMode=SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
        phase=SyncRecoveryJournalPhases.REQUIRED, reason=SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
        blockingEventId=null, attemptCount=0, createdAtMs=1L, updatedAtMs=1L, nextRetryAtMs=1L))
    val repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource=reader)
    val recovered = ShopSyncRecoveryCoordinator(context,db,repository,reader,
        registerDeviceForRecovery={ Result.success(ShopDeviceRegistrationResult(ok=true,code="success",shopId=it)) },
        scopeStillValid={ account, selected -> account==owner && selected==shop.shopId },
        activationBoundary={ it() }, availableStorageBytes={ Long.MAX_VALUE })
        .recover(owner,shop,scope)
    assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
    assertNull(db.syncRecoveryJournalDao().get())
    val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
    val actual = decodeRecoveryCheckpointJson(baseline.checkpointJson)
    validateShopSyncActiveReceipt(db,baseline.generationId,actual)
    assertEquals("0",actual.syncEvents.verifiedBaselineId)
    assertEquals(0L,db.syncEventWatermarkDao().get(owner,scope.storeId)?.lastSyncEventId)
    return actual
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

package com.example.merchandisecontrolsplitview.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val RESOLUTION_OWNER = "14400000-0000-4000-8000-000000000101"
private const val RESOLUTION_SHOP = "14400000-0000-4000-8000-000000000102"
private const val RESOLUTION_DEVICE = "14400000-0000-4000-8000-000000000103"
private const val RESOLUTION_BARCODE = "TASK144-R4-RESOLUTION"
private const val RESOLUTION_BARCODE_B = "TASK144-R4-RESIDUAL-B"

/** Real disk Room, recovery, writer, outbox and resolution consumer. Transport is controlled, not live acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PersistedCatalogConflictRepositoryTest {
    private lateinit var context: ResolutionOwnedContext
    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository
    private lateinit var tracker: CatalogSyncStateTracker
    private lateinit var reader: ResolutionQualifiedReader
    private lateinit var backend: ResolutionCatalogTransport
    private var rejectedBatchOriginals: List<SyncEventOutboxEntry> = emptyList()
    private val shop = SelectedShop(RESOLUTION_SHOP, "R4-OWNED", "Controlled R4", "shop_owner", "active", true)
    private val scope get() = task126ActiveOwnerStoreScope(RESOLUTION_OWNER, shop)
    private val prices = object : ProductPriceRemoteDataSource {
        override val isConfigured = true
        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>) = Result.success(Unit)
        override suspend fun fetchProductPrices() = Result.success(emptyList<InventoryProductPriceRow>())
        override suspend fun fetchProductPricesByIds(remoteIds: Set<String>) = Result.success(emptyList<InventoryProductPriceRow>())
    }

    @Before
    fun setup() = runBlocking {
        context = ResolutionOwnedContext(RuntimeEnvironment.getApplication())
        reader = ResolutionQualifiedReader()
        backend = ResolutionCatalogTransport(reader)
        db = openDatabase()
        db.syncEventDeviceStateDao().insert(SyncEventDeviceState(deviceId = RESOLUTION_DEVICE, createdAtMs = 1))
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash = scope.ownerHash, storeScope = scope.storeId,
            shopId = RESOLUTION_SHOP, deviceId = RESOLUTION_DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED,
            blockingEventId = null, attemptCount = 0, createdAtMs = 1, updatedAtMs = 1, nextRetryAtMs = 1))
        val initial = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
        val recovered = ShopSyncRecoveryCoordinator(context, db, initial, reader,
            registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(true, "success", it)) },
            scopeStillValid = { owner, selected -> owner == RESOLUTION_OWNER && selected == RESOLUTION_SHOP },
            activationBoundary = { it() }, availableStorageBytes = { Long.MAX_VALUE })
            .recover(RESOLUTION_OWNER, shop, scope)
        assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
        qualify()
        repository.addProduct(Product(barcode = RESOLUTION_BARCODE, productName = "Original", itemNumber = "Original item"))
        push().getOrThrow()
        assertEquals(ref().localChangeRevision, ref().lastSyncedLocalRevision)
        assertNotNull(reader.remoteRow)
    }

    @After
    fun teardown() {
        DefaultInventoryRepositoryTestHooks.afterLocalProductWrite = null
        if (::db.isInitialized && db.isOpen) db.close()
        // Owned fixture evidence remains available after a failure; no generic staging/database sweep.
    }

    @Test
    fun adoptRemoteNoOpPersistsDecisionThenOrdinarySaveUsesOnlyNewFieldsAcrossTwoReopens() = runTest {
        val base = product()
        repository.updateProductFromEditor(base, base.copy(productName = "Saved A"))
        val revisionA = ref().localChangeRevision
        backend.peerChange { it.copy(productName = "Saved A") }
        backend.mode = ResolutionTransportMode.RejectWithoutCommit
        assertTrue(push().isFailure)
        val original = writes().single()
        assertTrue(BusinessResolutionLedger.hasNoCommitProof(original))
        assertEquals(listOf(base.id), repository.findPersistedCatalogConflictProductIds(RESOLUTION_OWNER, RESOLUTION_SHOP))
        val contextA = prepare()
        assertTrue(contextA.blockingReason, contextA.canResolve)
        assertEquals(contextA.local.productName, contextA.remote?.productName)
        val before = product()
        val lastAck = ref().lastSyncedLocalRevision
        val resolved = repository.resolvePersistedCatalogConflict(contextA, PersistedCatalogConflictChoice.AdoptRemote)
        assertEquals(PersistedCatalogConflictResult.Resolved(null, true), resolved)
        assertEquals(before, product())
        assertFalse(repository.observeProductCloudConfirmed(base.id).first())
        assertEquals(lastAck, ref().lastSyncedLocalRevision) // A local decision is never a remote ACK.
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        assertRejectedBatchOriginalsUnchanged()
        assertEquals(1, decisions().size)
        assertTrue(repository.findPersistedCatalogConflictProductIds(RESOLUTION_OWNER, RESOLUTION_SHOP).isEmpty())
        reopen()
        assertEquals(1, decisions().size)
        assertEquals(revisionA, ref().localChangeRevision)
        val decisionR1 = decisions().single()
        val originalDecisionEntries = entries().filter { it.eventType == BUSINESS_RESOLUTION_EVENT &&
            BusinessResolutionLedger.decode(it).kind == "decision" }
        val remoteR1 = requireNotNull(reader.remoteRow)
        backend.peerChange { it.copy(productName = "Canonical R2") }
        val remoteR2 = requireNotNull(reader.remoteRow)
        assertTrue(remoteR1.updatedAt != remoteR2.updatedAt)
        recoverCurrentRemoteSnapshot()
        assertEquals("Canonical R2", product().productName)
        assertEquals(remoteR2.updatedAt, ref().remoteUpdatedAt)
        assertTrue(decisions().contains(decisionR1))
        for (entry in originalDecisionEntries) assertEquals(entry, db.syncEventOutboxDao().getById(entry.id))
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        val adopted = product()
        repository.updateProductFromEditor(adopted, adopted.copy(itemNumber = "Only B"))
        val revisionB = ref().localChangeRevision
        val nextIntent = entries().filter { it.eventType == BUSINESS_RESOLUTION_EVENT }
            .map(BusinessResolutionLedger::decode).single {
                it.kind == "intent_provenance" && it.intent?.revision == revisionB
            }.intent!!
        assertEquals(remoteR2, nextIntent.provenBase)
        assertEquals(listOf("itemnumber"), nextIntent.intendedFields)
        assertEquals(remoteR1, decisionR1.decision?.remote?.row)
        for (entry in originalDecisionEntries) assertEquals(entry, db.syncEventOutboxDao().getById(entry.id))
        assertEquals("itemnumber", ref().localChangedFields)
        assertTrue(ref().localChangeRevision > revisionA)
        backend.mode = ResolutionTransportMode.Accept
        push().getOrThrow()
        assertEquals(setOf("itemnumber"), backend.calls.last().changedFields)
        assertEquals("Canonical R2", reader.remoteRow?.productName)
        assertEquals("Only B", reader.remoteRow?.itemNumber)
        assertTrue(repository.observeProductCloudConfirmed(base.id).first())
        assertEquals(ref().localChangeRevision, ref().lastSyncedLocalRevision)
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        assertRejectedBatchOriginalsUnchanged()
        reopen()
        val calls = backend.calls.toList()
        push().getOrThrow()
        assertEquals(calls, backend.calls)
        assertEquals("Only B", product().itemNumber)
        assertEquals(1, decisions().size)
    }

    @Test
    fun unknownFirstDispatchCannotBeResolvedOrPromotedToAcknowledged() = runTest {
        val base = product()
        repository.updateProductFromEditor(base, base.copy(productName = "Unknown A"))
        backend.mode = ResolutionTransportMode.CommitThenLoseAck
        val ack = ref().lastSyncedLocalRevision
        assertTrue(push().isFailure)
        val original = writes().single()
        assertEquals("business_write_response_unconfirmed", original.lastErrorType)
        assertFalse(repository.observeProductCloudConfirmed(base.id).first())
        val contextA = prepare()
        assertFalse(contextA.canResolve)
        assertEquals("resolution_outcome_unconfirmed", contextA.blockingReason)
        assertTrue(repository.resolvePersistedCatalogConflict(contextA, PersistedCatalogConflictChoice.AdoptRemote)
            is PersistedCatalogConflictResult.Blocked)
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        assertRejectedBatchOriginalsUnchanged()
        assertEquals(ack, ref().lastSyncedLocalRevision)
        assertTrue(decisions().isEmpty())
        reopen()
        assertFalse(prepare().canResolve)
        assertEquals(original.metadataJson, writes().single().metadataJson)
    }

    @Test
    fun tombstoneWithDurablePriceChildRemainsBlockedWithoutCascade() = runTest {
        val base = product()
        repository.updateProductFromEditor(base, base.copy(productName = "Unsynced", purchasePrice = 17.0))
        val childCount = db.productPriceDao().countAll()
        assertTrue(childCount > 0)
        backend.peerChange { it.copy(deletedAt = "2026-10-23T12:00:30.000000Z") }
        val contextA = prepare()
        assertTrue(contextA.remoteDeleted)
        val outcome = repository.resolvePersistedCatalogConflict(contextA, PersistedCatalogConflictChoice.AdoptRemoteTombstone)
        assertEquals(PersistedCatalogConflictResult.Blocked("resolution_tombstone_has_dependencies"), outcome)
        assertEquals(childCount, db.productPriceDao().countAll())
        assertNotNull(db.productDao().getById(base.id))
        assertTrue(decisions().isEmpty())
        reopen()
        assertEquals(childCount, db.productPriceDao().countAll())
        assertNotNull(db.productDao().getById(base.id))
    }

    @Test
    fun changedRemoteBetweenComparisonAndCommitReturnsChangedWithoutDecision() = runTest {
        val base = product()
        repository.updateProductFromEditor(base, base.copy(productName = "Saved A"))
        val contextA = prepare()
        backend.peerChange { it.copy(itemNumber = "Peer drift") }
        val result = repository.resolvePersistedCatalogConflict(contextA,
            PersistedCatalogConflictChoice.SelectedFields(setOf("productname"), contextA.local))
        assertTrue(result is PersistedCatalogConflictResult.Changed)
        assertEquals("Peer drift", (result as PersistedCatalogConflictResult.Changed).context.remote?.itemNumber)
        assertTrue(decisions().isEmpty())
        assertEquals("Original item", product().itemNumber)
    }

    @Test
    fun selectedFieldsSealConditionalSuccessorAndZeroRowsNeverAcknowledgeIt() = runTest {
        val base = product()
        repository.updateProductFromEditor(base, base.copy(productName = "Selected local"))
        backend.peerChange { it.copy(itemNumber = "Peer disjoint") }
        val contextA = prepare()
        val result = repository.resolvePersistedCatalogConflict(contextA,
            PersistedCatalogConflictChoice.SelectedFields(setOf("productname"), contextA.local))
        assertTrue(result is PersistedCatalogConflictResult.Resolved)
        assertNotNull((result as PersistedCatalogConflictResult.Resolved).successorRevision)
        assertEquals("Peer disjoint", product().itemNumber)
        val seal = writes().single()
        assertEquals("PATCH_CONDITIONAL", seal.domain)
        val ack = ref().lastSyncedLocalRevision
        backend.mode = ResolutionTransportMode.ConditionalZero
        assertEquals(0, push().getOrThrow().pushedProducts)
        val retained = requireNotNull(db.syncEventOutboxDao().getById(seal.id))
        assertEquals(seal.metadataJson, retained.metadataJson)
        assertTrue(BusinessResolutionLedger.hasNoCommitProof(retained))
        assertEquals(ack, ref().lastSyncedLocalRevision)
        assertEquals(1, backend.conditionalCalls)
        assertEquals(0, backend.conditionalMutations)
        assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
        assertFalse(repository.observeProductCloudConfirmed(base.id).first())
        reopen()
        assertEquals(seal.metadataJson, writes().single().metadataJson)
        assertEquals(ack, ref().lastSyncedLocalRevision)
        assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
        assertFalse(repository.observeProductCloudConfirmed(base.id).first())
    }

    @Test
    fun conditionalLostAckThenZeroReplayRetainsUnconfirmedExactAttempt() = runTest {
        val base = product()
        repository.updateProductFromEditor(base, base.copy(productName = "Selected local"))
        val contextA = prepare()
        repository.resolvePersistedCatalogConflict(contextA,
            PersistedCatalogConflictChoice.SelectedFields(setOf("productname"), contextA.local))
        val seal = writes().single()
        val ack = ref().lastSyncedLocalRevision
        backend.mode = ResolutionTransportMode.CommitThenLoseAck
        assertEquals(0, push().getOrThrow().pushedProducts)
        val first = requireNotNull(db.syncEventOutboxDao().getById(seal.id))
        assertEquals("business_write_response_unconfirmed", first.lastErrorType)
        assertEquals(1, first.attemptCount)
        assertEquals(1, backend.conditionalCalls)
        assertEquals(1, backend.conditionalMutations)
        assertEquals(ack, ref().lastSyncedLocalRevision)
        assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
        assertFalse(repository.observeProductCloudConfirmed(base.id).first())
        reopen()
        backend.mode = ResolutionTransportMode.Accept // The same expected version no longer matches.
        assertEquals(0, push().getOrThrow().pushedProducts)
        val replay = requireNotNull(db.syncEventOutboxDao().getById(seal.id))
        assertEquals(first.metadataJson, replay.metadataJson)
        assertEquals("business_write_response_unconfirmed", replay.lastErrorType)
        assertEquals(2, replay.attemptCount)
        assertEquals(ack, ref().lastSyncedLocalRevision)
        assertEquals(1, backend.conditionalMutations)
        assertEquals(2, backend.conditionalCalls)
        assertFalse(BusinessResolutionLedger.hasNoCommitProof(replay))
        assertTrue(repository.hasCatalogCloudPendingWorkInclusive())
        assertFalse(repository.observeProductCloudConfirmed(base.id).first())
    }

    @Test
    fun targetedReadWithForeignScopeCannotProduceAResolutionContext() = runTest {
        val base = product()
        repository.updateProductFromEditor(base, base.copy(productName = "Saved A"))
        reader.rejectReadScope = true
        val failure = runCatching { prepare() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(decisions().isEmpty())
        assertEquals("Saved A", product().productName)
        assertTrue(reader.targetedCalls > 0)
    }

    @Test
    fun exactMutationReceiptPrecedesOuterCancellationAndSurvivesRealRoomReopen() = runTest {
        val base = product()
        val boundary = object : Task126BusinessDataScopeRuntimeGuard by tracker {
            override suspend fun <T> withLocalBusinessDataScopeFlight(block: suspend () -> T): T =
                tracker.withLocalBusinessDataScopeFlight {
                    block()
                    throw kotlinx.coroutines.CancellationException("controlled after actual writer return")
                }
        }
        val observed = java.util.concurrent.atomic.AtomicReference<ProductEditorSaveReceipt?>(null)
        val writer = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader,
            businessDataScopeRuntimeGuard = boundary)
        val desired = base.copy(productName = "Durable before outer cancellation", retailPrice = 18.0)
        val error = runCatching { writer.updateProductFromEditor(base, desired, observed::set) }.exceptionOrNull()
        assertTrue("Expected post-commit cancellation; actual=${error?.javaClass?.name}: ${error?.message}",
            error is kotlinx.coroutines.CancellationException)
        val receipt = requireNotNull(observed.get())
        assertTrue(receipt.mutationId.isNotBlank())
        assertEquals(desired, receipt.product)
        assertTrue(receipt.changed)
        assertEquals(desired, product())
        val priceCount = db.productPriceDao().countAll()
        assertEquals(1, priceCount)
        val ref = ref()
        assertTrue(ref.localChangeRevision > ref.lastSyncedLocalRevision)
        reopen()
        assertEquals(receipt.product, product())
        assertEquals(ref, ref())
        assertEquals(priceCount, db.productPriceDao().countAll())
    }

    @Test
    fun rolledBackWriterDoesNotEmitAReceiptOrLeaveProductPriceOrIntentChanges() = runTest {
        val base = product()
        val originalRef = ref()
        val originalEntries = entries()
        val pricesBefore = db.productPriceDao().countAll()
        val observed = java.util.concurrent.atomic.AtomicReference<ProductEditorSaveReceipt?>(null)
        DefaultInventoryRepositoryTestHooks.afterLocalProductWrite = { throw IOException("controlled writer rollback") }
        val error = try {
            runCatching { repository.updateProductFromEditor(base,
                base.copy(productName = "Must rollback", retailPrice = 19.0), observed::set) }.exceptionOrNull()
        } finally { DefaultInventoryRepositoryTestHooks.afterLocalProductWrite = null }
        assertTrue(error is IOException)
        assertNull(observed.get())
        assertEquals(base, product())
        assertEquals(originalRef, ref())
        assertEquals(originalEntries, entries())
        assertEquals(pricesBefore, db.productPriceDao().countAll())
        reopen()
        assertEquals(base, product())
        assertEquals(originalEntries, entries())
    }

    @Test
    fun authoritativeTombstoneWithoutChildrenRetiresIntentWithoutResurrection() = runTest {
        val base = product()
        repository.updateProductFromEditor(base, base.copy(productName = "Saved before delete"))
        assertEquals(0, db.productPriceDao().countAll())
        backend.peerChange { it.copy(deletedAt = "2026-10-23T12:00:30.000000Z") }
        val review = prepare()
        assertTrue(review.remoteDeleted)
        val result = repository.resolvePersistedCatalogConflict(review, PersistedCatalogConflictChoice.AdoptRemoteTombstone)
        assertEquals(PersistedCatalogConflictResult.Resolved(null, true), result)
        assertNull(db.productDao().getById(base.id))
        assertEquals(1, decisions().size)
        reopen()
        assertNull(db.productDao().getById(base.id))
        val calls = backend.calls.toList()
        push().getOrThrow()
        assertEquals(calls, backend.calls)
        assertNotNull(reader.remoteRow?.deletedAt)
        assertEquals(1, decisions().size)
    }

    @Test
    fun adoptedTombstoneRejectsMissingDecisionAndPreservesRecoveryJournalAfterRestore() = runTest {
        verifyTombstoneDecisionCorruption("missing")
    }

    @Test
    fun adoptedTombstoneRejectsMalformedDecisionAndPreservesRecoveryJournalAfterRestore() = runTest {
        verifyTombstoneDecisionCorruption("malformed")
    }

    @Test
    fun adoptedTombstoneRejectsForeignDecisionAndPreservesRecoveryJournalAfterRestore() = runTest {
        verifyTombstoneDecisionCorruption("foreign")
    }

    @Test
    fun adoptedTombstoneRejectsWrongGenerationAndPreservesRecoveryJournalAfterRestore() = runTest {
        verifyTombstoneDecisionCorruption("generation")
    }

    private suspend fun verifyTombstoneDecisionCorruption(label: String) {
        val base = product()
        repository.updateProductFromEditor(base, base.copy(productName = "Saved before delete"))
        backend.peerChange { it.copy(deletedAt = "2026-10-23T12:00:30.000000Z") }
        assertEquals(PersistedCatalogConflictResult.Resolved(null, true),
            repository.resolvePersistedCatalogConflict(prepare(), PersistedCatalogConflictChoice.AdoptRemoteTombstone))
        val decisionEntry = entries().single { it.eventType == BUSINESS_RESOLUTION_EVENT &&
            BusinessResolutionLedger.decode(it).kind == "decision" }
        val record = BusinessResolutionLedger.decode(decisionEntry)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        val acknowledged = db.syncRecoveryManifestDao().page(baseline.generationId,
            "local-ack-body-v1:products", null, 100)
        assertEquals(1, acknowledged.size)
        assertTrue(acknowledged.single().active)
        reopen()
        fun altered(raw: String, owner: String = decisionEntry.ownerUserId) = decisionEntry.copy(
            ownerUserId = owner, metadataJson = raw,
            batchId = MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray())
                .joinToString("") { "%02x".format(it) })
        val foreignOwner = "14400000-0000-4000-8000-000000000999"
        val variants = listOf(
            "missing" to null,
            "malformed" to altered("{malformed-resolution"),
            "foreign" to altered(decisionEntry.metadataJson.replace(
                "\"owner\":\"$RESOLUTION_OWNER\"", "\"owner\":\"$foreignOwner\""), foreignOwner),
            "generation" to altered(decisionEntry.metadataJson.replace(
                "\"generationId\":\"${record.generationId}\"",
                "\"generationId\":\"14400000-0000-4000-8000-000000000998\""))
        )
        val alteredEntry = variants.single { it.first == label }.second
        db.syncEventOutboxDao().deleteById(decisionEntry.id)
        if (alteredEntry != null) db.syncEventOutboxDao().insert(alteredEntry)
        val rejected = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
            .resolveBusinessDataScope(scope)
        assertFalse("$label receipt cannot authorize a missing physical body: $rejected", rejected.allowsLocalOperations)
        assertEquals(Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, rejected.status)
        val requiredJournal = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals(SyncRecoveryAuthorizationModes.SAME_SCOPE, requiredJournal.authorizationMode)
        assertEquals(SyncRecoveryJournalPhases.REQUIRED, requiredJournal.phase)
        assertEquals("local_snapshot_unavailable", requiredJournal.reason)
        assertEquals(acknowledged, db.syncRecoveryManifestDao().page(baseline.generationId,
            "local-ack-body-v1:products", null, 100))
        assertNull(db.productDao().getById(base.id))
        db.syncEventOutboxDao().deleteById(decisionEntry.id)
        db.syncEventOutboxDao().insert(decisionEntry)
        db.close()
        db = openDatabase()
        assertEquals(decisionEntry, db.syncEventOutboxDao().getById(decisionEntry.id))
        assertEquals(requiredJournal, db.syncRecoveryJournalDao().get())
        val restored = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader)
            .resolveBusinessDataScope(scope)
        // Restored proof can qualify local reads; it cannot erase the durable recovery requirement.
        assertEquals(Task126BusinessDataScopeStatus.ERROR_RECOVERABLE, restored.status)
        assertTrue("$label exact restored receipt qualifies the existing local snapshot: $restored",
            restored.allowsLocalOperations)
        assertFalse(restored.allowsCloudSync)
        assertEquals(requiredJournal, db.syncRecoveryJournalDao().get())
        assertEquals(baseline, db.syncRecoveryBaselineDao().get())
        assertEquals(acknowledged, db.syncRecoveryManifestDao().page(baseline.generationId,
            "local-ack-body-v1:products", null, 100))
        assertNull(db.productDao().getById(base.id))
    }

    @Test
    fun wholeRejectedBatchResolvesAAndDispatchesResidualBOnceAcrossReopen() = runTest {
        val (original, b) = prepareRejectedWholeBatch()
        val bRef = requireNotNull(db.productRemoteRefDao().getByProductId(b.id))
        val review = prepare()
        assertTrue(review.blockingReason, review.canResolve)
        assertEquals(PersistedCatalogConflictResult.Resolved(null, true),
            repository.resolvePersistedCatalogConflict(review, PersistedCatalogConflictChoice.AdoptRemote))
        val residual = requireNotNull(decisions().single().decision).residuals.single()
        assertEquals(original.clientEventId, residual.originalOperationKey)
        assertEquals(bRef.remoteId, residual.remoteId)
        assertEquals(bRef.localChangeRevision, residual.revision)
        assertEquals(bRef, db.productRemoteRefDao().getByProductId(b.id))
        assertEquals(b, db.productDao().getById(b.id))
        val successor = writes().single { it.clientEventId == residual.newOperationKey }
        assertEquals(residual.rawSuccessorAttempt, successor.metadataJson)
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        assertRejectedBatchOriginalsUnchanged()
        val bulkBefore = backend.bulkCalls.size
        reopen()
        assertTrue(requireNotNull(loadBusinessResolutionSnapshot(db, RESOLUTION_OWNER, scope.storeId)).supersededAttempt(original))
        backend.mode = ResolutionTransportMode.Accept
        push().getOrThrow()
        assertEquals(bulkBefore, backend.bulkCalls.size) // the original two-product body never replays
        assertEquals(listOf(bRef.remoteId), backend.conditionalIds)
        assertEquals("Saved B1", reader.remoteRows.getValue(bRef.remoteId).productName)
        assertEquals(bRef.localChangeRevision, db.productRemoteRefDao().getByProductId(b.id)?.lastSyncedLocalRevision)
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        assertRejectedBatchOriginalsUnchanged()
        assertNull(db.syncEventOutboxDao().getById(successor.id)) // only the real ACK retires the conditional seal
        val ledger = requireNotNull(loadBusinessResolutionSnapshot(db, RESOLUTION_OWNER, scope.storeId)).requireValid()
        val bRevision = readResolutionAttemptForTest(original).payload.revisions.single { it.remoteId == bRef.remoteId }
        assertTrue(ledger.hasActualAcknowledgement(original, bRevision))
        reopen()
        push().getOrThrow()
        assertEquals(listOf(bRef.remoteId), backend.conditionalIds)
        assertEquals(bulkBefore, backend.bulkCalls.size)
    }

    @Test
    fun newerB2SaveSurvivesActualAcknowledgementOfSealedB1Residual() = runTest {
        val (original, b1) = prepareRejectedWholeBatch()
        val b1Ref = requireNotNull(db.productRemoteRefDao().getByProductId(b1.id))
        val review = prepare()
        assertTrue(review.blockingReason, review.canResolve)
        repository.resolvePersistedCatalogConflict(review, PersistedCatalogConflictChoice.AdoptRemote)
        val residual = requireNotNull(decisions().single().decision).residuals.single()
        repository.updateProductFromEditor(b1, b1.copy(itemNumber = "Saved B2 after seal"))
        val b2 = requireNotNull(db.productDao().getById(b1.id))
        val b2Ref = requireNotNull(db.productRemoteRefDao().getByProductId(b1.id))
        assertTrue(b2Ref.localChangeRevision > b1Ref.localChangeRevision)
        backend.mode = ResolutionTransportMode.Accept
        backend.failOrdinaryTransport = true // B1 CAS may ACK; the independent B2 request fails before response/mutation.
        assertTrue(push().isFailure)
        assertEquals(b2, db.productDao().getById(b1.id))
        val afterB1Ack = requireNotNull(db.productRemoteRefDao().getByProductId(b1.id))
        assertEquals(b2Ref.localChangeRevision, afterB1Ack.localChangeRevision)
        assertEquals(b2Ref.localChangedFields, afterB1Ack.localChangedFields)
        assertEquals(b1Ref.localChangeRevision, afterB1Ack.lastSyncedLocalRevision)
        assertFalse(repository.observeProductCloudConfirmed(b1.id).first())
        assertEquals(listOf(b1Ref.remoteId), backend.conditionalIds)
        assertEquals("Original item B", reader.remoteRows.getValue(b1Ref.remoteId).itemNumber)
        assertFalse(writes().any { it.clientEventId == residual.newOperationKey })
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        assertRejectedBatchOriginalsUnchanged()
        reopen()
        assertEquals(b2, db.productDao().getById(b1.id))
        backend.failOrdinaryTransport = false
        push().getOrThrow()
        assertEquals("Saved B2 after seal", reader.remoteRows.getValue(b1Ref.remoteId).itemNumber)
        assertEquals(b2Ref.localChangeRevision, db.productRemoteRefDao().getByProductId(b1.id)?.lastSyncedLocalRevision)
        assertTrue(repository.observeProductCloudConfirmed(b1.id).first())
        assertEquals(listOf(b1Ref.remoteId), backend.conditionalIds)
    }

    @Test
    fun residualDecisionAndExactSealTransferThroughRealRecoveryBeforeAcknowledgement() = runTest {
        val (original, b) = prepareRejectedWholeBatch()
        val review = prepare()
        assertTrue(review.blockingReason, review.canResolve)
        repository.resolvePersistedCatalogConflict(review, PersistedCatalogConflictChoice.AdoptRemote)
        val residual = requireNotNull(decisions().single().decision).residuals.single()
        val seal = writes().single { it.clientEventId == residual.newOperationKey }
        val beforeGeneration = requireNotNull(db.syncRecoveryBaselineDao().get()).generationId
        recoverCurrentRemoteSnapshot()
        assertNotEquals(beforeGeneration, requireNotNull(db.syncRecoveryBaselineDao().get()).generationId)
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        assertRejectedBatchOriginalsUnchanged()
        assertEquals(seal, db.syncEventOutboxDao().getById(seal.id))
        assertEquals(b, db.productDao().getById(b.id))
        val ledger = requireNotNull(loadBusinessResolutionSnapshot(db, RESOLUTION_OWNER, scope.storeId)).requireValid()
        assertTrue(ledger.records.any { it.kind == "generation_transfer" })
        assertTrue(ledger.supersededAttempt(original))
        reopen()
        backend.mode = ResolutionTransportMode.Accept
        push().getOrThrow()
        assertEquals(listOf(residual.remoteId), backend.conditionalIds)
        requireNotNull(loadBusinessResolutionSnapshot(db, RESOLUTION_OWNER, scope.storeId)).requireValid()
    }

    @Test
    fun missingResidualRemoteBlocksResolutionWithoutChangingBatchOrLocalMember() = runTest {
        val (original, b) = prepareRejectedWholeBatch()
        val bRef = requireNotNull(db.productRemoteRefDao().getByProductId(b.id))
        reader.remoteRows.remove(bRef.remoteId)
        val review = prepare()
        assertFalse(review.canResolve)
        assertEquals("resolution_residual_remote_changed", review.blockingReason)
        assertTrue(repository.resolvePersistedCatalogConflict(review, PersistedCatalogConflictChoice.AdoptRemote)
            is PersistedCatalogConflictResult.Blocked)
        assertTrue(decisions().isEmpty())
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        assertRejectedBatchOriginalsUnchanged()
        assertEquals(b, db.productDao().getById(b.id))
        assertEquals(bRef, db.productRemoteRefDao().getByProductId(b.id))
    }

    @Test
    fun changedResidualRemoteAfterComparisonInvalidatesWholeDecisionWithoutChangingBatch() = runTest {
        val (original, b) = prepareRejectedWholeBatch()
        val bRef = requireNotNull(db.productRemoteRefDao().getByProductId(b.id))
        val review = prepare()
        assertTrue(review.blockingReason, review.canResolve)
        backend.peerChange(bRef.remoteId) { it.copy(itemNumber = "Peer B drift") }
        val outcome = repository.resolvePersistedCatalogConflict(review, PersistedCatalogConflictChoice.AdoptRemote)
        assertTrue(outcome is PersistedCatalogConflictResult.Changed)
        val changed = (outcome as PersistedCatalogConflictResult.Changed).context
        assertFalse(changed.canResolve)
        assertEquals("resolution_residual_remote_changed", changed.blockingReason)
        assertTrue(decisions().isEmpty())
        assertEquals(original, db.syncEventOutboxDao().getById(original.id))
        assertRejectedBatchOriginalsUnchanged()
        assertEquals(b, db.productDao().getById(b.id))
        assertEquals(bRef, db.productRemoteRefDao().getByProductId(b.id))
    }

    private suspend fun prepareRejectedWholeBatch(): Pair<SyncEventOutboxEntry, Product> {
        repository.addProduct(Product(barcode = RESOLUTION_BARCODE_B, productName = "Original B", itemNumber = "Original item B"))
        push().getOrThrow()
        recoverCurrentRemoteSnapshot()
        val a = product()
        val b = requireNotNull(db.productDao().findByBarcode(RESOLUTION_BARCODE_B))
        val bRef = requireNotNull(db.productRemoteRefDao().getByProductId(b.id))
        assertNotNull(bRef.remoteUpdatedAt)
        assertNotNull(bRef.lastRemotePayloadFingerprint)
        assertEquals(bRef.localChangeRevision, bRef.lastSyncedLocalRevision)
        // Import is a real full-row writer, unlike the editor's field-mask patch path.
        val imported = repository.applyImport(ImportApplyRequest(emptyList(), listOf(
            ProductUpdate(a, a.copy(productName = "Saved A"), listOf(2)),
            ProductUpdate(b, b.copy(productName = "Saved B1"), listOf(2)))))
        assertEquals(ImportApplyResult.Success, imported)
        backend.mode = ResolutionTransportMode.RejectWithoutCommit
        assertTrue(push().isFailure)
        rejectedBatchOriginals = writes()
        val original = rejectedBatchOriginals.single {
            val payload = readResolutionAttemptForTest(it).payload
            payload.kind == "PRODUCTS" && payload.products.size == 2
        }
        // The actual bulk fallback may append a singleton rejection. Keep every such original immutable.
        assertTrue(rejectedBatchOriginals.all { BusinessResolutionLedger.hasNoCommitProof(it) })
        assertTrue(rejectedBatchOriginals.all { entry -> readResolutionAttemptForTest(entry).payload.products.all { row ->
            row.barcode in setOf(RESOLUTION_BARCODE, RESOLUTION_BARCODE_B)
        } })
        assertEquals(2, readResolutionAttemptForTest(original).payload.products.size)
        assertTrue(BusinessResolutionLedger.hasNoCommitProof(original))
        assertEquals(setOf(a.id, b.id), repository.findPersistedCatalogConflictProductIds(RESOLUTION_OWNER, RESOLUTION_SHOP).toSet())
        return original to requireNotNull(db.productDao().getById(b.id))
    }

    private suspend fun assertRejectedBatchOriginalsUnchanged() {
        for (entry in rejectedBatchOriginals) assertEquals(entry, db.syncEventOutboxDao().getById(entry.id))
    }

    private suspend fun recoverCurrentRemoteSnapshot() {
        // Qualify both actual remote rows through the production same-scope recovery publication.
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(ownerHash = scope.ownerHash, storeScope = scope.storeId,
            shopId = RESOLUTION_SHOP, deviceId = RESOLUTION_DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE, phase = SyncRecoveryJournalPhases.REQUIRED,
            reason = "controlled residual base recovery", blockingEventId = null, attemptCount = 0,
            createdAtMs = 2, updatedAtMs = 2, nextRetryAtMs = 2))
        val recovered = ShopSyncRecoveryCoordinator(context, db, repository, reader,
            registerDeviceForRecovery = { Result.success(ShopDeviceRegistrationResult(true, "success", it)) },
            scopeStillValid = { owner, selected -> owner == RESOLUTION_OWNER && selected == RESOLUTION_SHOP },
            activationBoundary = { it() }, availableStorageBytes = { Long.MAX_VALUE })
            .recover(RESOLUTION_OWNER, shop, scope)
        assertTrue(recovered.toString(), recovered is ShopSyncRecoveryResult.Activated)
        qualify()
    }

    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, "resolution.db")
        .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
        .allowMainThreadQueries().build()
    private suspend fun qualify() {
        val state = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader).resolveBusinessDataScope(scope)
        assertEquals(Task126BusinessDataScopeStatus.READY, state.status)
        tracker = CatalogSyncStateTracker(state)
        repository = DefaultInventoryRepository(db, shopSyncReadRemoteDataSource = reader, businessDataScopeRuntimeGuard = tracker)
    }
    private suspend fun reopen() { db.close(); db = openDatabase(); qualify() }
    private suspend fun product() = requireNotNull(db.productDao().findByBarcode(RESOLUTION_BARCODE))
    private suspend fun ref() = requireNotNull(db.productRemoteRefDao().getByProductId(product().id))
    private suspend fun prepare() = repository.preparePersistedCatalogConflict(product().id, RESOLUTION_OWNER, RESOLUTION_SHOP)
    private suspend fun push() = tracker.withBusinessDataScopeFlight(RESOLUTION_OWNER, shop) {
        repository.pushDirtyCatalogDeltaToRemote(backend, prices, RESOLUTION_OWNER, CatalogSyncProgressReporter {}, shop)
    }
    private suspend fun entries() = readLocalBusinessEntries(db, RESOLUTION_OWNER, scope.storeId)
    private suspend fun writes() = entries().filter { it.eventType == BUSINESS_WRITE_EVENT }
    private suspend fun decisions() = entries().filter { it.eventType == BUSINESS_RESOLUTION_EVENT }
        .map(BusinessResolutionLedger::decode).filter { it.kind == "decision" }
}

private class ResolutionOwnedContext(base: Context) : ContextWrapper(base) {
    private val root = Files.createTempDirectory("task144-r4-room-").toFile()
    private val databases = File(root, "databases").apply { check(mkdir()) }
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String) = if (name == ".") databases else File(databases, name).also {
        require(it.canonicalFile.parentFile == databases.canonicalFile)
    }
    override fun getFilesDir() = File(root, "files").apply { check(isDirectory || mkdir()) }
    override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdir()) }
    override fun getNoBackupFilesDir() = File(root, "no_backup").apply { check(isDirectory || mkdir()) }
    override fun databaseList(): Array<String> = databases.list() ?: emptyArray()
    override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
}

private enum class ResolutionTransportMode { Accept, RejectWithoutCommit, CommitThenLoseAck, ConditionalZero }
private class ResolutionCatalogTransport(private val reader: ResolutionQualifiedReader) : CatalogRemoteDataSource {
    override val isConfigured = true
    var mode = ResolutionTransportMode.Accept
    var failOrdinaryTransport = false
    val calls = mutableListOf<InventoryProductPatch>()
    val conditionalIds = mutableListOf<String>()
    val bulkCalls = mutableListOf<List<InventoryProductRow>>()
    var conditionalCalls = 0
    var conditionalMutations = 0
    private var version = 0
    private fun nextTimestamp() = "2026-10-23T12:00:${(++version).toString().padStart(2, '0')}.000000Z"
    fun peerChange(change: (InventoryProductRow) -> InventoryProductRow) = peerChange(requireNotNull(reader.remoteRow).id, change)
    fun peerChange(id: String, change: (InventoryProductRow) -> InventoryProductRow) {
        reader.remoteRows[id] = change(requireNotNull(reader.remoteRows[id])).copy(updatedAt = nextTimestamp())
    }
    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
        check(rows.isNotEmpty() && rows.all { it.ownerUserId == RESOLUTION_OWNER && it.shopId == RESOLUTION_SHOP })
        bulkCalls += rows.toList()
        if (failOrdinaryTransport) return Result.failure(IOException("controlled ordinary request failed before mutation"))
        if (mode == ResolutionTransportMode.RejectWithoutCommit) return typedConstraintRejection { it.upsertProducts(rows) }
        rows.forEach { reader.remoteRows[it.id] = it.copy(updatedAt = nextTimestamp()) }
        return Result.success(Unit)
    }
    override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch): Result<Unit> {
        check(id in reader.remoteRows && ownerUserId == RESOLUTION_OWNER && shopId == RESOLUTION_SHOP)
        calls += patch
        if (failOrdinaryTransport) return Result.failure(IOException("controlled ordinary request failed before mutation"))
        if (mode == ResolutionTransportMode.RejectWithoutCommit) return typedConstraintRejection {
            it.patchProduct(id, ownerUserId, shopId, patch)
        }
        reader.remoteRows[id] = apply(requireNotNull(reader.remoteRows[id]), patch).copy(updatedAt = nextTimestamp())
        return if (mode == ResolutionTransportMode.CommitThenLoseAck) Result.failure(IOException("controlled lost response")) else Result.success(Unit)
    }
    override suspend fun patchProductConditionally(id: String, ownerUserId: String, shopId: String?,
        patch: InventoryProductPatch, expectedUpdatedAt: String, expectedDeletedAt: String?): Result<List<InventoryProductRow>> {
        check(id in reader.remoteRows && ownerUserId == RESOLUTION_OWNER && shopId == RESOLUTION_SHOP)
        conditionalCalls++
        conditionalIds += id
        calls += patch
        val row = requireNotNull(reader.remoteRows[id])
        if (mode == ResolutionTransportMode.ConditionalZero || row.updatedAt != expectedUpdatedAt || row.deletedAt != expectedDeletedAt)
            return Result.success(emptyList())
        val updated = apply(row, patch).copy(updatedAt = nextTimestamp())
        reader.remoteRows[id] = updated
        conditionalMutations++
        return if (mode == ResolutionTransportMode.CommitThenLoseAck) Result.failure(IOException("controlled lost conditional response"))
            else Result.success(listOf(updated))
    }
    private fun apply(row: InventoryProductRow, patch: InventoryProductPatch) = row.copy(
        barcode = if ("barcode" in patch.changedFields) requireNotNull(patch.barcode) else row.barcode,
        productName = if ("productname" in patch.changedFields) patch.productName else row.productName,
        itemNumber = if ("itemnumber" in patch.changedFields) patch.itemNumber else row.itemNumber,
        secondProductName = if ("secondproductname" in patch.changedFields) patch.secondProductName else row.secondProductName,
        purchasePrice = if ("purchaseprice" in patch.changedFields) patch.purchasePrice else row.purchasePrice,
        retailPrice = if ("retailprice" in patch.changedFields) patch.retailPrice else row.retailPrice,
        supplierId = if ("supplier" in patch.changedFields) patch.supplierId else row.supplierId,
        categoryId = if ("category" in patch.changedFields) patch.categoryId else row.categoryId,
        stockQuantity = if ("stockquantity" in patch.changedFields) patch.stockQuantity else row.stockQuantity)
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>) = Result.failure<Unit>(AssertionError("unexpected supplier write"))
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>) = Result.failure<Unit>(AssertionError("unexpected category write"))
    override suspend fun fetchCatalog() = Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), reader.remoteRows.values.toList()))
    override suspend fun fetchCatalogByIds(supplierIds: Set<String>, categoryIds: Set<String>, productIds: Set<String>) =
        Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), reader.remoteRows.values.filter { it.id in productIds }))
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch) = Result.failure<Unit>(AssertionError("unexpected supplier deletion"))
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch) = Result.failure<Unit>(AssertionError("unexpected category deletion"))
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch) = Result.failure<Unit>(AssertionError("unexpected product deletion"))
}

private class ResolutionQualifiedReader : ShopSyncReadRemoteDataSource {
    override val isConfigured = true
    val initialCheckpoint = emptyCheckpoint()
    val remoteRows = linkedMapOf<String, InventoryProductRow>()
    var remoteRow: InventoryProductRow?
        get() = remoteRows.values.singleOrNull { it.barcode == RESOLUTION_BARCODE }
        set(value) {
            val previous = remoteRows.values.singleOrNull { it.barcode == RESOLUTION_BARCODE }
            if (previous != null) remoteRows.remove(previous.id)
            if (value != null) remoteRows[value.id] = value
        }
    var rejectReadScope = false
    var targetedCalls = 0
    val checkpoint: ShopSyncRecoveryCheckpoint get() = currentCheckpoint()
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
            shopId = RESOLUTION_SHOP,
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
        val productRows = if (domain == ShopSyncRowDomain.PRODUCTS) remoteRows.values.sortedBy { it.id } else emptyList()
        val body = buildJsonObject {
            put("schema_version", "shop-sync-recovery-page-v1"); put("shop_id", RESOLUTION_SHOP)
            put("scope", Json.parseToJsonElement(Json.encodeToString(checkpoint.scope)))
            put("domain", domain.wireValue); put("snapshot_event_max_id", requireNotNull(context.expectedEventMaxId))
            put("current_scope_event_max_id", requireNotNull(context.expectedEventMaxId))
            put("baseline_domain_event_max_id", requireNotNull(context.expectedDomainEventMaxId))
            put("page_domain_event_max_id", requireNotNull(context.expectedDomainEventMaxId))
            put("domain_scope", ShopSyncScopeKinds.SHOP_SCOPED); put("page_limit", limit)
            put("rows", Json.parseToJsonElement(Json.encodeToString(productRows)))
            put("next_after_id", JsonNull); put("has_more", false)
        }.toString()
        return Result.success(
            ShopSyncRecoveryPage(
                schemaVersion = "shop-sync-recovery-page-v1",
                shopId = RESOLUTION_SHOP,
                scope = checkpoint.scope,
                domain = domain,
                snapshotEventMaxId = requireNotNull(context.expectedEventMaxId),
                currentScopeEventMaxId = requireNotNull(context.expectedEventMaxId),
                baselineDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
                pageDomainEventMaxId = requireNotNull(context.expectedDomainEventMaxId),
                domainScope = ShopSyncScopeKinds.SHOP_SCOPED,
                pageLimit = limit,
                rows = if (domain == ShopSyncRowDomain.PRODUCTS) ShopSyncRows.Products(productRows) else emptyRows(domain),
                nextAfterId = null,
                hasMore = false,
                responseBytes = body.encodeToByteArray().size.toLong()
            )
        )
    }

    override suspend fun eventPage(
        context: ShopSyncRpcContext,
        afterId: Long,
        limit: Int
    ): Result<ShopSyncEventPage> =
        Result.failure(AssertionError("unchanged checkpoint must not request an event tail"))

    override suspend fun rowsByIds(context: ShopSyncRpcContext, domain: ShopSyncRowDomain,
        ids: List<String>): Result<ShopSyncTargetedRows> {
        targetedCalls++
        check(domain == ShopSyncRowDomain.PRODUCTS && ids.size == 1)
        val rows = remoteRows.values.filter { it.id in ids }
        return Result.success(ShopSyncTargetedRows("shop-sync-rows-by-ids-v1", RESOLUTION_SHOP,
            requireNotNull(context.expectedScope).let { if (rejectReadScope) it.copy(key = "f".repeat(64)) else it },
            domain, requireNotNull(context.expectedEventMaxId), requireNotNull(context.expectedEventMaxId),
            requireNotNull(context.expectedDomainEventMaxId), requireNotNull(context.expectedDomainEventMaxId),
            ShopSyncScopeKinds.SHOP_SCOPED, ids.size, ShopSyncRows.Products(rows),
            ids.filter { id -> rows.none { it.id == id } }))
    }

    private fun currentCheckpoint(): ShopSyncRecoveryCheckpoint {
        val rows = remoteRows.values.sortedBy { it.id }
        if (rows.isEmpty()) return initialCheckpoint
        val versions = rows.map { row ->
            val active = row.deletedAt == null
            listOf(row.id, requireNotNull(row.updatedAt), row.deletedAt ?: "-",
                row.categoryId.takeIf { active } ?: "-", row.supplierId.takeIf { active } ?: "-",
                row.primaryImageVersionId.takeIf { active } ?: "-", row.primaryImageUpdatedAt.takeIf { active } ?: "-").joinToString("\u001f")
        }
        val identities = rows.map { row -> listOf(row.id, sha256(row.barcode), sha256(row.itemNumber.orEmpty())).joinToString("\u001f") }
        val products = ShopSyncDomainCheckpoint(rows.count { it.deletedAt == null }.toLong(), rows.count { it.deletedAt != null }.toLong(),
            shopSyncCheckpointChainDigest(rows.map { it.id }), shopSyncCheckpointChainDigest(versions),
            shopSyncCheckpointChainDigest(identities))
        val catalog = initialCheckpoint.catalog.copy(products = products,
            digest = sha256(initialCheckpoint.catalog.suppliers.versionDigest + "\n" +
                initialCheckpoint.catalog.categories.versionDigest + "\n" + products.versionDigest))
        return initialCheckpoint.copy(catalog = catalog,
            syncEvents = initialCheckpoint.syncEvents.copy(maxId = "10",
                domainMaxIds = mapOf(SyncEventDomains.CATALOG to "10", SyncEventDomains.PRICES to "0", SyncEventDomains.HISTORY to "0")),
            checkpointDigest = sha256(products.versionDigest))
    }
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
                shopId = RESOLUTION_SHOP,
                scope = ShopSyncScope(
                    kind = ShopSyncScopeKinds.SHOP_SCOPED,
                    key = "c".repeat(64),
                    historyKind = ShopSyncScopeKinds.SHOP_SCOPED,
                    accountKey = sha256(RESOLUTION_OWNER.lowercase()),
                    deviceKey = sha256(RESOLUTION_DEVICE)
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

/** The actual pinned SDK constructs the typed HTTP 409/23503 failure; no string classifier shortcut. */
@OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
private suspend fun <T> typedConstraintRejection(send: suspend (CatalogRemoteDataSource) -> Result<T>): Result<T> {
    val requests = java.util.concurrent.atomic.AtomicInteger()
    val sdk = createSupabaseClient("http://127.0.0.1:1", "synthetic-public-key") {
        httpEngine = OkHttp.create { config { addInterceptor { chain ->
            requests.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(409).message("Controlled atomic rejection")
                .body("""{"code":"23503","message":"controlled atomic rejection","details":null,"hint":null}"""
                    .toResponseBody("application/json".toMediaType())).build()
        } } }
        coroutineDispatcher = Dispatchers.IO
        defaultLogLevel = LogLevel.NONE
        requestTimeout = 3.seconds
        install(Auth) {
            autoLoadFromStorage = false; autoSetupPlatform = false
            sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache()
        }
        install(Postgrest) { timeout = 3.seconds }
    }
    try {
        sdk.auth.importSession(UserSession(accessToken = "synthetic-access", refreshToken = "synthetic-refresh",
            expiresIn = 86_400, tokenType = "bearer", user = UserInfo(aud = "authenticated", id = RESOLUTION_OWNER),
            expiresAt = Clock.System.now() + 1.days), autoRefresh = false)
        val result = kotlinx.coroutines.withTimeout(5_000L) { send(SupabaseCatalogRemoteDataSource(sdk)) }
        val error = result.exceptionOrNull()
        check(error is PostgrestRestException && error.code == "23503" && error.statusCode == 409)
        check(requests.get() == 1)
        return result
    } finally { sdk.close() }
}

private fun readResolutionAttemptForTest(entry: SyncEventOutboxEntry): BusinessWriteAttempt {
    require(entry.metadataJson.encodeToByteArray().size in 1..BUSINESS_WRITE_MAX_BYTES)
    val attempt = Json.decodeFromString<BusinessWriteAttempt>(entry.metadataJson)
    validateBusinessWritePayload(attempt)
    val key = "business-write-v1:" + MessageDigest.getInstance("SHA-256")
        .digest(entry.metadataJson.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    require(entry.eventType == BUSINESS_WRITE_EVENT && entry.clientEventId == key &&
        entry.ownerUserId == attempt.owner && entry.storeScope == attempt.store &&
        entry.sourceDeviceId == attempt.device && entry.domain == attempt.payload.kind)
    return attempt
}

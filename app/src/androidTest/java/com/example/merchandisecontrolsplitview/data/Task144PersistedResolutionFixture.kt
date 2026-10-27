package com.example.merchandisecontrolsplitview.data

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.annotations.SupabaseInternal
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
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Test support in the existing native campaign. No client queue or production authority is replaced. */
internal object NativeResolutionContract {
    const val PROTOCOL = "task144-native-resolution-v1"
    const val PEER_DEVICE = "14400000-0000-4000-8000-000000000004"
    const val BARCODE_B = "TASK144-NATIVE-R4-TWO"
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    val variantMethods = mapOf(
        "r4-drift" to "prepareDriftRequiresNewDecision",
        "r4-cas-drift" to "prepareCasDriftAfterDecisionNeverAcknowledges",
        "r4-null" to "prepareExplicitNullSelectedField",
        "r4-tombstone" to "prepareAllowedTombstoneWithoutChildren",
        "r4-tombstone-child" to "prepareTombstonePreservesChildren",
        "r4-scope" to "prepareScopeSwitchInvalidatesReview",
        "r4-generation" to "prepareGenerationChangeInvalidatesReview",
        "r4-unknown-23503" to "prepareUnknownThen23503Replay",
        "r4-unknown-23505" to "prepareUnknownThen23505Replay",
        "r4-residual-b2" to "prepareResidualB1AckPreservesNewerB2")
    val crashMethods = mapOf(
        "r4-crash-before-seal" to "prepareCrashBeforeSuccessorSeal",
        "r4-crash-before-decision" to "prepareCrashAfterSealBeforeDecisionInsert",
        "r4-crash-before-commit" to "prepareCrashAfterReceiptBeforeDecisionCommit",
        "r4-crash-after-commit" to "prepareCrashAfterDecisionCommitBeforeDispatchStamp",
        "r4-crash-before-remote" to "prepareCrashAfterDispatchBeforeRemoteCommit",
        "r4-crash-after-remote" to "prepareCrashAfterRemoteCommitBeforeResponse",
        "r4-crash-before-ack" to "prepareCrashAfterResponseBeforeAcknowledgement",
        "r4-crash-during-ack" to "prepareCrashAfterAckProjectionBeforeReceiptDelete",
        "r4-crash-after-ack" to "prepareCrashAfterAcknowledgementCommit")
    val scenarios = setOf("r4-adopt", "r4-selected-unknown") + variantMethods.keys + crashMethods.keys
}

internal fun nativeResolutionContext(scenario: String, fresh: Boolean): ScopeSwitchContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name)
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val arguments = InstrumentationRegistry.getArguments()
    val methods = when (scenario to fresh) {
        "r4-adopt" to true -> setOf("prepareAdoptNoOpWithIndependentPrice")
        "r4-adopt" to false -> setOf("verifyAdoptReopenThenNormalSaveB", "verifyNormalSaveBAfterSecondReopen")
        "r4-selected-unknown" to true -> setOf("prepareSelectedConditionalCommitWithLostAck")
        "r4-selected-unknown" to false -> setOf("verifySelectedUnknownReopenAndExactZeroReplay")
        else -> if (scenario in NativeResolutionContract.variantMethods) setOf(
            if (fresh) NativeResolutionContract.variantMethods.getValue(scenario) else "verifyVariantInNewProcess")
            else if (scenario in NativeResolutionContract.crashMethods) setOf(
                if (fresh) NativeResolutionContract.crashMethods.getValue(scenario) else "verifyCrashBoundaryInNewProcess")
            else error("Unsupported native R4 scenario")
    }
    val className = if (scenario in NativeResolutionContract.crashMethods) "Task144PersistedResolutionCrashDeviceTest"
        else if (scenario in NativeResolutionContract.variantMethods)
        "Task144PersistedResolutionVariantsDeviceTest" else "Task144PersistedResolutionDeviceTest"
    check(arguments.getString("class") in methods.map {
        "com.example.merchandisecontrolsplitview.ui.navigation.$className#$it"
    })
    if (scenario in NativeResolutionContract.variantMethods || scenario in NativeResolutionContract.crashMethods)
        check(arguments.getString("nativeResolutionScenario") == scenario)
    check(arguments.getString("nativeResolutionProtocol") == NativeResolutionContract.PROTOCOL)
    check(scenario in NativeResolutionContract.scenarios)
    return ScopeSwitchContext(instrumentation.targetContext,
        requireNotNull(arguments.getString("nativeResolutionRunId")), scenario, fresh,
        protocol = NativeResolutionContract.PROTOCOL)
}

@Serializable
internal enum class NativeResolutionTransportMode { Accept, RejectWithoutCommit, RejectUniqueWithoutCommit, CommitThenLoseAck, ConditionalZero }

@Serializable
internal data class NativeResolutionWireCall(
    val sequence: Int,
    val kind: String,
    val products: List<InventoryProductRow> = emptyList(),
    val patchId: String? = null,
    val patch: InventoryProductPatch? = null,
    val prices: List<InventoryProductPriceRow> = emptyList(),
    val expectedUpdatedAt: String? = null,
    val expectedDeletedAt: String? = null,
    val operationKey: String? = null,
    val rawAttempt: String? = null,
    val stampedAttemptCount: Int? = null,
    val decisionRecordIds: List<String> = emptyList(),
    val parentLocalRevision: Int? = null,
    val parentAcknowledgedRevision: Int? = null,
    val parentEffectiveRevision: String? = null,
    val outcome: String = "entered",
    val rejectionSqlState: String? = null
)

@Serializable
internal data class NativeResolutionServerState(
    val protocol: String = NativeResolutionContract.PROTOCOL,
    val product: InventoryProductRow? = null,
    val additionalProducts: List<InventoryProductRow> = emptyList(),
    val prices: List<InventoryProductPriceRow> = emptyList(),
    val calls: List<NativeResolutionWireCall> = emptyList(),
    val reads: List<JsonObject> = emptyList(),
    val events: List<SyncEventRemoteRow> = emptyList(),
    val version: Long = 0L,
    val mode: NativeResolutionTransportMode = NativeResolutionTransportMode.Accept
)

/**
 * Narrow persistent response fixture. Each real typed transport invocation is recorded before
 * validation; exact existing outbox bytes are observed, never manufactured or rewritten.
 * Request keys do not suppress a replay. State and call outcomes survive OS process death.
 * This does not claim the pinned SDK's HTTP serialization or authenticated TEST behavior.
 */
internal class NativeResolutionPersistentRemote(private val db: AppDatabase, val file: File) : CatalogRemoteDataSource {
    override val isConfigured = true
    private val json get() = NativeResolutionContract.json
    private val remoteScope = NativeAckEmptyRecoveryRemote().checkpoint.scope
    // Test-owned latency boundaries, never a replacement writer/queue. Durable commit precedes them.
    var afterConditionalCommit: (suspend (InventoryProductRow) -> Unit)? = null
    var afterPatchCommit: (suspend (InventoryProductRow) -> Unit)? = null
    var beforeConditionalCompare: (() -> Unit)? = null

    init {
        check(file.parentFile?.isDirectory == true)
        check(file.canonicalFile == file.absoluteFile)
    }

    @Synchronized fun state(): NativeResolutionServerState = readState()

    private fun readState(): NativeResolutionServerState {
        val state = if (file.isFile) json.decodeFromString<NativeResolutionServerState>(file.readText())
            else NativeResolutionServerState()
        check(state.protocol == NativeResolutionContract.PROTOCOL && state.version >= 0L)
        check(state.calls.map { it.sequence } == (1..state.calls.size).toList())
        check(state.events.map { it.id } == (1L..state.events.size.toLong()).toList())
        check(state.prices.map { it.id }.distinct().size == state.prices.size)
        state.product?.let { requireRow(it) }
        state.additionalProducts.forEach(::requireRow)
        check(allProducts(state).map { it.id }.distinct().size == allProducts(state).size)
        check(allProducts(state).map { it.barcode }.distinct().size == allProducts(state).size)
        check(state.prices.all { it.ownerUserId == NativeAckContract.OWNER &&
            it.shopId == NativeAckContract.SHOP && it.productId in allProducts(state).map { row -> row.id } })
        return state
    }

    @Synchronized private fun change(block: (NativeResolutionServerState) -> NativeResolutionServerState): NativeResolutionServerState {
        val next = block(readState())
        nativeDurableWrite(file, json.encodeToString(next).encodeToByteArray())
        return next
    }

    fun setMode(mode: NativeResolutionTransportMode) { change { it.copy(mode = mode) } }

    fun peerChange(update: (InventoryProductRow) -> InventoryProductRow) {
        peerChange(requireNotNull(state().product).id, update)
    }

    fun peerChange(id: String, update: (InventoryProductRow) -> InventoryProductRow) {
        change { old ->
            val before = allProducts(old).single { it.id == id }
            val updated = update(before).copy(updatedAt = timestamp(old.version + 1))
            requireRow(updated)
            check(updated.id == before.id)
            replacing(old, listOf(updated)).copy(version = old.version + 1,
                events = old.events + event(old, SyncEventDomains.CATALOG,
                    SyncEventEntityIds(productIds = listOf(updated.id)), peer = true, tombstone = updated.deletedAt != null))
        }
    }

    private fun requireRow(row: InventoryProductRow) {
        check(row.ownerUserId == NativeAckContract.OWNER && row.shopId == NativeAckContract.SHOP)
        check(row.barcode in setOf(NativeAckContract.BARCODE, NativeResolutionContract.BARCODE_B))
        check(canonicalShopSyncRecoveryEntityIdOrNull(row.id) == row.id)
        check(row.supplierId == null && row.categoryId == null &&
            row.primaryImageVersionId == null && row.primaryImageUpdatedAt == null)
    }

    private fun allProducts(state: NativeResolutionServerState): List<InventoryProductRow> =
        (listOfNotNull(state.product) + state.additionalProducts).sortedBy { it.id }

    private fun replacing(old: NativeResolutionServerState, rows: List<InventoryProductRow>): NativeResolutionServerState {
        val all = (allProducts(old).associateBy { it.id } + rows.associateBy { it.id }).values.toList()
        check(all.size <= 2 && all.map { it.barcode }.distinct().size == all.size)
        return old.copy(product = all.singleOrNull { it.barcode == NativeAckContract.BARCODE },
            additionalProducts = all.filter { it.barcode != NativeAckContract.BARCODE }.sortedBy { it.id })
    }

    private fun timestamp(version: Long): String = DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)
        .format(Instant.parse("2026-10-23T12:00:00Z").plusMillis(version))

    private fun event(old: NativeResolutionServerState, domain: String, ids: SyncEventEntityIds,
        peer: Boolean = false, tombstone: Boolean = false): SyncEventRemoteRow {
        val sequence = old.events.size + 1L
        return SyncEventRemoteRow(sequence, NativeAckContract.OWNER, NativeAckContract.SHOP,
            authorizedShopId = NativeAckContract.SHOP, storeId = NativeAckContract.SHOP, domain = domain,
            eventType = when {
                domain == SyncEventDomains.PRICES -> SyncEventTypes.PRICES_CHANGED
                tombstone -> SyncEventTypes.CATALOG_TOMBSTONE
                else -> SyncEventTypes.CATALOG_CHANGED
            }, source = "NATIVE_R4_CONTROLLED", sourceDeviceId = if (peer) NativeResolutionContract.PEER_DEVICE else NativeAckContract.DEVICE,
            clientEventId = "native-r4-controlled-" + sequence, changedCount = ids.totalIds, entityIds = ids,
            createdAt = timestamp(old.version + 1))
    }

    private fun enter(call: NativeResolutionWireCall): Int {
        val next = change { old -> old.copy(calls = old.calls + call.copy(sequence = old.calls.size + 1)) }
        return next.calls.last().sequence
    }

    private fun updateCall(sequence: Int, transform: (NativeResolutionWireCall) -> NativeResolutionWireCall) {
        change { old -> old.copy(calls = old.calls.map { if (it.sequence == sequence) transform(it) else it }) }
    }

    private suspend fun capture(sequence: Int): NativeResolutionWireCall {
        val call = state().calls.single { it.sequence == sequence }
        val entries = readLocalBusinessEntries(db, NativeAckContract.OWNER, NativeAckContract.scope.storeId)
        val ledger = loadBusinessResolutionSnapshot(db, NativeAckContract.OWNER, NativeAckContract.scope.storeId, entries)
            ?.requireValid()
        val matches = entries.filter { it.eventType == BUSINESS_WRITE_EVENT && it.domain == call.kind }
            .filterNot { ledger?.supersededAttempt(it) == true }.mapNotNull { entry ->
                val attempt = json.decodeFromString<BusinessWriteAttempt>(entry.metadataJson)
                validateBusinessWritePayload(attempt)
                val payload = attempt.payload
                if (payload.products != call.products || payload.patchId != call.patchId || payload.patch != call.patch ||
                    payload.prices != call.prices || payload.expectedUpdatedAt != call.expectedUpdatedAt ||
                    payload.expectedDeletedAt != call.expectedDeletedAt) null else entry to attempt
            }
        val (entry, attempt) = matches.single()
        check(entry.attemptCount > 0 && entry.lastAttemptAtMs != null)
        check(entry.clientEventId == "business-write-v1:" + nativeSha(entry.metadataJson.encodeToByteArray()))
        check(attempt.owner == NativeAckContract.OWNER && attempt.shop == NativeAckContract.SHOP &&
            attempt.store == NativeAckContract.scope.storeId && attempt.device == NativeAckContract.DEVICE &&
            attempt.generation == db.syncRecoveryBaselineDao().get()?.generationId)
        check(entry.ownerUserId == attempt.owner && entry.storeScope == attempt.store &&
            entry.sourceDeviceId == attempt.device && entry.domain == attempt.payload.kind)
        val binding = requireNotNull(db.businessDataScopeBindingDao().get())
        check(attempt.schema == binding.schemaVersion && attempt.protocol == binding.syncProtocolVersion &&
            attempt.epoch == binding.storeEpoch && attempt.localStore == binding.localStoreId)
        val observedId = call.patchId ?: call.prices.firstOrNull()?.productId ?: call.products.firstOrNull()?.id
        val observed = allProducts(state()).singleOrNull { it.id == observedId }
        val product = requireNotNull(db.productDao().findByBarcode(observed?.barcode ?: call.products.firstOrNull()?.barcode ?: NativeAckContract.BARCODE))
        val parent = requireNotNull(db.productRemoteRefDao().getByProductId(product.id))
        val captured = call.copy(operationKey = entry.clientEventId, rawAttempt = entry.metadataJson,
            stampedAttemptCount = entry.attemptCount, decisionRecordIds = ledger?.decisions.orEmpty().map { it.recordId },
            parentLocalRevision = parent.localChangeRevision, parentAcknowledgedRevision = parent.lastSyncedLocalRevision,
            parentEffectiveRevision = ledger?.effectiveRevision(parent.remoteId, parent.localChangeRevision)?.name,
            outcome = "captured")
        updateCall(sequence) { captured }
        return captured
    }

    private fun apply(row: InventoryProductRow, patch: InventoryProductPatch): InventoryProductRow = row.copy(
        barcode = if ("barcode" in patch.changedFields) requireNotNull(patch.barcode) else row.barcode,
        itemNumber = if ("itemnumber" in patch.changedFields) patch.itemNumber else row.itemNumber,
        productName = if ("productname" in patch.changedFields) patch.productName else row.productName,
        secondProductName = if ("secondproductname" in patch.changedFields) patch.secondProductName else row.secondProductName,
        purchasePrice = if ("purchaseprice" in patch.changedFields) patch.purchasePrice else row.purchasePrice,
        retailPrice = if ("retailprice" in patch.changedFields) patch.retailPrice else row.retailPrice,
        supplierId = if ("supplier" in patch.changedFields) patch.supplierId else row.supplierId,
        categoryId = if ("category" in patch.changedFields) patch.categoryId else row.categoryId,
        stockQuantity = if ("stockquantity" in patch.changedFields) patch.stockQuantity else row.stockQuantity)

    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> {
        val sequence = enter(NativeResolutionWireCall(0, "PRODUCTS", products = rows))
        check(rows.size in 1..2 && rows.map { it.id }.distinct().size == rows.size); rows.forEach(::requireRow)
        capture(sequence)
        if (state().mode in setOf(NativeResolutionTransportMode.RejectWithoutCommit, NativeResolutionTransportMode.RejectUniqueWithoutCommit)) {
            val sqlState = if (state().mode == NativeResolutionTransportMode.RejectUniqueWithoutCommit) "23505" else "23503"
            val rejected = nativeResolutionTypedRejection(sqlState) { it.upsertProducts(rows) }
            updateCall(sequence) { it.copy(outcome = "rejected_no_commit", rejectionSqlState = sqlState) }
            return rejected
        }
        val committed = change { old ->
            replacing(old, rows.map { it.copy(updatedAt = timestamp(old.version + 1)) }).copy(
                version = old.version + 1, events = old.events + event(old, SyncEventDomains.CATALOG,
                    SyncEventEntityIds(productIds = rows.map { it.id })),
                calls = old.calls.map { if (it.sequence == sequence) it.copy(outcome =
                    if (old.mode == NativeResolutionTransportMode.CommitThenLoseAck) "committed_response_lost" else "committed_returned") else it })
        }
        return if (committed.calls[sequence - 1].outcome == "committed_response_lost")
            Result.failure(IOException("controlled native R4 lost product response")) else Result.success(Unit)
    }

    override suspend fun patchProduct(id: String, ownerUserId: String, shopId: String?, patch: InventoryProductPatch): Result<Unit> {
        val sequence = enter(NativeResolutionWireCall(0, "PATCH", patchId = id, patch = patch))
        check(ownerUserId == NativeAckContract.OWNER && shopId == NativeAckContract.SHOP)
        capture(sequence)
        if (state().mode in setOf(NativeResolutionTransportMode.RejectWithoutCommit, NativeResolutionTransportMode.RejectUniqueWithoutCommit)) {
            val sqlState = if (state().mode == NativeResolutionTransportMode.RejectUniqueWithoutCommit) "23505" else "23503"
            val rejected = nativeResolutionTypedRejection(sqlState) { it.patchProduct(id, ownerUserId, shopId, patch) }
            updateCall(sequence) { it.copy(outcome = "rejected_no_commit", rejectionSqlState = sqlState) }
            return rejected
        }
        val committed = commitPatch(sequence, conditional = false)
        committed.first?.let { row -> afterPatchCommit?.let { hold ->
            updateCall(sequence) { it.copy(outcome = "committed_ack_held") }
            hold(row)
            updateCall(sequence) { it.copy(outcome = if (committed.second) "committed_response_lost" else "committed_returned") }
        } }
        return if (committed.second) Result.failure(IOException("controlled native R4 lost patch response")) else Result.success(Unit)
    }

    override suspend fun patchProductConditionally(id: String, ownerUserId: String, shopId: String?,
        patch: InventoryProductPatch, expectedUpdatedAt: String, expectedDeletedAt: String?): Result<List<InventoryProductRow>> {
        val sequence = enter(NativeResolutionWireCall(0, "PATCH_CONDITIONAL", patchId = id, patch = patch,
            expectedUpdatedAt = expectedUpdatedAt, expectedDeletedAt = expectedDeletedAt))
        check(ownerUserId == NativeAckContract.OWNER && shopId == NativeAckContract.SHOP)
        capture(sequence)
        if (state().mode in setOf(NativeResolutionTransportMode.RejectWithoutCommit, NativeResolutionTransportMode.RejectUniqueWithoutCommit)) {
            val sqlState = if (state().mode == NativeResolutionTransportMode.RejectUniqueWithoutCommit) "23505" else "23503"
            val rejected = nativeResolutionTypedRejection(sqlState) {
                it.patchProductConditionally(id, ownerUserId, shopId, patch, expectedUpdatedAt, expectedDeletedAt)
            }
            updateCall(sequence) { it.copy(outcome = "rejected_no_commit", rejectionSqlState = sqlState) }
            return rejected
        }
        beforeConditionalCompare?.invoke()
        val (row, lost) = commitPatch(sequence, conditional = true)
        row?.let { committed -> afterConditionalCommit?.let { hold ->
            updateCall(sequence) { it.copy(outcome = "committed_ack_held") }
            hold(committed)
            updateCall(sequence) { it.copy(outcome = if (lost) "committed_response_lost" else "committed_returned") }
        } }
        return if (lost) Result.failure(IOException("controlled native R4 lost conditional response"))
            else Result.success(listOfNotNull(row))
    }

    private fun commitPatch(sequence: Int, conditional: Boolean): Pair<InventoryProductRow?, Boolean> {
        val committed = change { old ->
            val call = old.calls.single { it.sequence == sequence }
            val current = allProducts(old).single { it.id == call.patchId }
            val zero = conditional && (old.mode == NativeResolutionTransportMode.ConditionalZero ||
                current.updatedAt != call.expectedUpdatedAt || current.deletedAt != call.expectedDeletedAt)
            if (zero) old.copy(calls = old.calls.map {
                if (it.sequence == sequence) it.copy(outcome = "conditional_zero") else it
            }) else {
                val updated = apply(current, requireNotNull(call.patch)).copy(updatedAt = timestamp(old.version + 1))
                requireRow(updated)
                replacing(old, listOf(updated)).copy(version = old.version + 1,
                    events = old.events + event(old, SyncEventDomains.CATALOG, SyncEventEntityIds(productIds = listOf(updated.id))),
                    calls = old.calls.map { if (it.sequence == sequence) it.copy(outcome =
                        if (old.mode == NativeResolutionTransportMode.CommitThenLoseAck) "committed_response_lost" else "committed_returned") else it })
            }
        }
        val outcome = committed.calls[sequence - 1].outcome
        return allProducts(committed).single { it.id == committed.calls[sequence - 1].patchId }
            .takeUnless { outcome == "conditional_zero" } to (outcome == "committed_response_lost")
    }

    val prices: ProductPriceRemoteDataSource = object : ProductPriceRemoteDataSource {
        override val isConfigured = true
        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> {
            val sequence = enter(NativeResolutionWireCall(0, "PRICES", prices = rows))
            check(rows.isNotEmpty() && rows.map { it.id }.distinct().size == rows.size)
            capture(sequence)
            change { old ->
                check(rows.all { it.ownerUserId == NativeAckContract.OWNER && it.shopId == NativeAckContract.SHOP &&
                    allProducts(old).any { parent -> parent.id == it.productId && parent.deletedAt == null } &&
                    it.type in setOf("PURCHASE", "RETAIL", "purchase", "retail") && it.price.isFinite() })
                val byID = old.prices.associateBy { it.id }.toMutableMap()
                rows.forEach { row ->
                    val prior = byID[row.id]
                    check(prior == null || prior.copy(updatedAt = row.updatedAt, priceCanonical = row.priceCanonical) == row)
                    byID[row.id] = row.copy(updatedAt = timestamp(old.version + 1),
                        priceCanonical = BigDecimal.valueOf(row.price).stripTrailingZeros().toPlainString())
                }
                old.copy(prices = byID.values.sortedBy { it.id }, version = old.version + 1,
                    events = old.events + event(old, SyncEventDomains.PRICES, SyncEventEntityIds(priceIds = rows.map { it.id })),
                    calls = old.calls.map { if (it.sequence == sequence) it.copy(outcome = "committed_returned") else it })
            }
            return Result.success(Unit)
        }
        override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = Result.success(state().prices)
        override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
            Result.success(state().prices.filter { it.id in remoteIds })
        override suspend fun fetchProductPricesByProductIds(productRemoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
            Result.success(state().prices.filter { it.productId in productRemoteIds })
    }

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
        Result.failure(AssertionError("R4 fixture requires the qualified reader, not a full catalog fallback"))
    override suspend fun fetchCatalogByIds(supplierIds: Set<String>, categoryIds: Set<String>, productIds: Set<String>): Result<InventoryCatalogFetchBundle> =
        Result.failure(AssertionError("R4 fixture requires the qualified reader, not an unqualified targeted fallback"))
    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> = error("R4 supplier writes outside this fixture")
    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> = error("R4 category writes outside this fixture")
    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("R4 supplier tombstone outside this fixture")
    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("R4 category tombstone outside this fixture")
    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> = error("R4 outgoing tombstone outside this fixture")

    private fun rows(state: NativeResolutionServerState, domain: ShopSyncRowDomain): ShopSyncRows = when (domain) {
        ShopSyncRowDomain.SUPPLIERS -> ShopSyncRows.Suppliers(emptyList())
        ShopSyncRowDomain.CATEGORIES -> ShopSyncRows.Categories(emptyList())
        ShopSyncRowDomain.PRODUCTS -> ShopSyncRows.Products(allProducts(state))
        ShopSyncRowDomain.PRICES -> ShopSyncRows.Prices(state.prices)
        ShopSyncRowDomain.HISTORY -> ShopSyncRows.History(emptyList())
        ShopSyncRowDomain.IMAGES -> ShopSyncRows.Images(emptyList())
    }
    private fun encoded(rows: ShopSyncRows): JsonArray = when (rows) {
        is ShopSyncRows.Suppliers -> json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Categories -> json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Products -> json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Prices -> json.encodeToJsonElement(rows.values)
        is ShopSyncRows.History -> json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Images -> json.encodeToJsonElement(rows.values)
    }.jsonArray
    private fun fences(state: NativeResolutionServerState): Map<String, String> =
        listOf(SyncEventDomains.CATALOG, SyncEventDomains.PRICES, SyncEventDomains.HISTORY).associateWith { domain ->
            (state.events.filter { it.domain == domain }.maxOfOrNull { it.id } ?: 0L).toString()
        }
    private fun domainFence(domain: ShopSyncRowDomain, state: NativeResolutionServerState): String =
        fences(state).getValue(domain.syncEventDomain())

    private fun readSnapshot(method: String, context: ShopSyncRpcContext, domain: ShopSyncRowDomain? = null,
        ids: List<String> = emptyList()): NativeResolutionServerState {
        val snapshot = change { old -> old.copy(reads = old.reads + buildJsonObject {
            put("method", method); put("account", context.accountId); put("shop", context.shopId); put("device", context.deviceIdentifier)
            put("scope", context.expectedScope?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            put("expectedMax", context.expectedEventMaxId?.let(::JsonPrimitive) ?: JsonNull)
            put("domain", domain?.wireValue?.let(::JsonPrimitive) ?: JsonNull)
            put("ids", JsonArray(ids.map(::JsonPrimitive))); put("observedMax", old.events.size)
        }) }
        check(context.accountId == NativeAckContract.OWNER && context.shopId == NativeAckContract.SHOP &&
            context.deviceIdentifier == NativeAckContract.DEVICE)
        check(context.expectedScope == null || context.expectedScope == remoteScope)
        check(context.expectedBaselineScopeKey == null || context.expectedBaselineScopeKey == remoteScope.key)
        check(context.verifiedBaselineId.toLong() in 0L..snapshot.events.size.toLong())
        context.expectedEventMaxId?.let { check(it == snapshot.events.size.toString()) { "controlled remote snapshot changed" } }
        if (domain != null) context.expectedDomainEventMaxId?.let { check(it == domainFence(domain, snapshot)) }
        return snapshot
    }

    private fun checkpoint(snapshot: NativeResolutionServerState, context: ShopSyncRpcContext): ShopSyncRecoveryCheckpoint {
        val domains = ShopSyncRowDomain.entries.associateWith { domain ->
            val manifest = rows(snapshot, domain).toManifestRows("native-r4-materials", domain).sortedBy { it.remoteId }
            ShopSyncDomainCheckpoint(manifest.count { it.active }.toLong(), manifest.count { !it.active }.toLong(),
                shopSyncCheckpointChainDigest(manifest.map { it.idLine }), shopSyncCheckpointChainDigest(manifest.map { it.versionLine }),
                shopSyncCheckpointChainDigest(manifest.mapNotNull { it.identityLine }).takeIf { domain == ShopSyncRowDomain.PRODUCTS })
        }
        val catalog = ShopSyncCatalogCheckpoint(domains.getValue(ShopSyncRowDomain.SUPPLIERS),
            domains.getValue(ShopSyncRowDomain.CATEGORIES), domains.getValue(ShopSyncRowDomain.PRODUCTS),
            nativeSha(listOf(ShopSyncRowDomain.SUPPLIERS, ShopSyncRowDomain.CATEGORIES, ShopSyncRowDomain.PRODUCTS)
                .joinToString("\n") { domains.getValue(it).versionDigest }.encodeToByteArray()))
        val digest = nativeSha(buildJsonObject {
            put("version", snapshot.version); put("scope", json.encodeToJsonElement(remoteScope)); put("fences", json.encodeToJsonElement(fences(snapshot)))
            put("products", encoded(rows(snapshot, ShopSyncRowDomain.PRODUCTS))); put("prices", encoded(rows(snapshot, ShopSyncRowDomain.PRICES)))
        }.toString().encodeToByteArray())
        val inspected = snapshot.events.count { it.id > context.verifiedBaselineId.toLong() }.toLong()
        check(inspected <= 10_000L)
        return ShopSyncRecoveryCheckpoint("shop-sync-recovery-checkpoint-v1", "ready", NativeAckContract.SHOP, remoteScope,
            ShopSyncEventCheckpoint(snapshot.events.size.toString(), context.verifiedBaselineId, false, fences(snapshot),
                inspectionLimit = 10_000, inspectedCount = inspected, scanComplete = true, blockingCount = 0L), catalog,
            domains.getValue(ShopSyncRowDomain.PRICES), domains.getValue(ShopSyncRowDomain.HISTORY), domains.getValue(ShopSyncRowDomain.IMAGES),
            ShopSyncIntegrityCheckpoint(0, 0, 0, 0, 0, 0), digest)
    }

    private inline fun <T> readResult(block: () -> T): Result<T> = try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }

    private val ordinaryWakeConfigured = java.util.concurrent.atomic.AtomicBoolean(true)

    /** Called only after a proven clean ordinary cycle is quiescent. The next Save keeps its
     * original unconfigured-event-transport fixture path; a new process enables its own wake. */
    fun finishOrdinaryWakeBeforeNormalSave() {
        check(ordinaryWakeConfigured.compareAndSet(true, false))
    }

    /** Clean reopen enables an actual ordinary read opportunity. No event/business write
     * may be fabricated to make this opportunity complete; legacy fallback also fails closed. */
    val ordinaryWakeEvents: SyncEventRemoteDataSource = object : SyncEventRemoteDataSource {
        override val isConfigured get() = ordinaryWakeConfigured.get()
        override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> {
            check(ownerUserId == NativeAckContract.OWNER)
            change { old -> old.copy(reads = old.reads + buildJsonObject {
                put("method", "ordinaryWakeCapabilities"); put("account", ownerUserId)
            }) }
            return Result.success(SyncEventRemoteCapabilities(true, true, false))
        }
        override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
            change { old -> old.copy(reads = old.reads + buildJsonObject {
                put("method", "unexpectedOrdinaryWakeEventWrite"); put("params", json.encodeToJsonElement(params))
            }) }
            return Result.failure(AssertionError("A clean terminal ordinary wake must not create event writes"))
        }
        override suspend fun fetchSyncEventsAfter(ownerUserId: String, storeId: String?, afterId: Long,
            limit: Long): Result<List<SyncEventRemoteRow>> =
            Result.failure(AssertionError("The terminal shop wake must use the qualified V6 reader"))
    }

    val reader: ShopSyncReadRemoteDataSource = object : ShopSyncReadRemoteDataSource {
        override val isConfigured = true
        override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> =
            readResult { checkpoint(readSnapshot("checkpoint", context), context) }
        override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> = readResult {
            val snapshot = readSnapshot("marker", context); val checkpoint = checkpoint(snapshot, context)
            ShopSyncConvergenceMarker("shop-sync-convergence-marker-v1", "ready", NativeAckContract.SHOP, remoteScope, checkpoint.syncEvents,
                checkpoint.catalog, checkpoint.prices, checkpoint.history, checkpoint.images, ShopSyncMarkerIntegrity(0),
                checkpoint.checkpointDigest, context.verifiedBaselineId == snapshot.events.size.toString(),
                nativeSha(("native-r4-marker|" + checkpoint.checkpointDigest + "|" + context.verifiedBaselineId).encodeToByteArray()))
        }
        override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, afterId: String?,
            limit: Int): Result<ShopSyncRecoveryPage> = readResult {
            val snapshot = readSnapshot("recoveryPage", context, domain)
            check(afterId == null && limit in 1..250)
            val values = rows(snapshot, domain); check(values.size <= limit)
            val wire = encoded(values)
            val envelope = buildJsonObject {
                put("schemaVersion", "shop-sync-recovery-page-v1"); put("shopId", NativeAckContract.SHOP)
                put("scope", json.encodeToJsonElement(remoteScope)); put("domain", domain.wireValue)
                put("snapshotEventMaxId", snapshot.events.size.toString()); put("currentScopeEventMaxId", snapshot.events.size.toString())
                put("baselineDomainEventMaxId", domainFence(domain, snapshot)); put("pageDomainEventMaxId", domainFence(domain, snapshot))
                put("domainScope", ShopSyncScopeKinds.SHOP_SCOPED); put("pageLimit", limit); put("rows", wire)
                put("nextAfterId", JsonNull); put("hasMore", false)
            }
            val bytes = envelope.toString().encodeToByteArray().size.toLong()
            ShopSyncRecoveryPage("shop-sync-recovery-page-v1", NativeAckContract.SHOP, remoteScope, domain,
                snapshot.events.size.toString(), snapshot.events.size.toString(), domainFence(domain, snapshot), domainFence(domain, snapshot),
                ShopSyncScopeKinds.SHOP_SCOPED, limit, values, null, false, bytes,
                wire.maxOfOrNull { it.toString().encodeToByteArray().size.toLong() } ?: 0L)
        }
        override suspend fun rowsByIds(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, ids: List<String>): Result<ShopSyncTargetedRows> = readResult {
            val snapshot = readSnapshot("rowsByIds", context, domain, ids)
            check(ids.isNotEmpty() && ids.distinct().size == ids.size)
            val selected: ShopSyncRows = when (domain) {
                ShopSyncRowDomain.PRODUCTS -> ShopSyncRows.Products(allProducts(snapshot).filter { it.id in ids })
                ShopSyncRowDomain.PRICES -> ShopSyncRows.Prices(snapshot.prices.filter { it.id in ids })
                else -> error("unexpected R4 targeted domain")
            }
            val wire = encoded(selected)
            val missing = ids.filter { it !in selected.ids() }
            val envelope = buildJsonObject {
                put("schemaVersion", "shop-sync-rows-by-ids-v1"); put("shopId", NativeAckContract.SHOP)
                put("scope", json.encodeToJsonElement(remoteScope)); put("domain", domain.wireValue)
                put("asOfEventMaxId", snapshot.events.size.toString()); put("currentScopeEventMaxId", snapshot.events.size.toString())
                put("minimumDomainEventMaxId", domainFence(domain, snapshot)); put("materializedDomainEventMaxId", domainFence(domain, snapshot))
                put("domainScope", ShopSyncScopeKinds.SHOP_SCOPED); put("requestedCount", ids.size); put("rows", wire)
                put("missingIds", JsonArray(missing.map(::JsonPrimitive)))
            }
            ShopSyncTargetedRows("shop-sync-rows-by-ids-v1", NativeAckContract.SHOP, remoteScope, domain,
                snapshot.events.size.toString(), snapshot.events.size.toString(), domainFence(domain, snapshot), domainFence(domain, snapshot),
                ShopSyncScopeKinds.SHOP_SCOPED, ids.size, selected, missing,
                envelope.toString().encodeToByteArray().size.toLong(),
                wire.maxOfOrNull { it.toString().encodeToByteArray().size.toLong() } ?: 0L)
        }
        override suspend fun eventPage(context: ShopSyncRpcContext, afterId: Long, limit: Int): Result<ShopSyncEventPage> = readResult {
            val snapshot = readSnapshot("eventPage", context)
            check(afterId in 0L..snapshot.events.size.toLong() && limit in 1..150)
            val remaining = snapshot.events.filter { it.id > afterId }; val selected = remaining.take(limit)
            val more = remaining.size > selected.size
            val next = selected.lastOrNull()?.id.takeIf { more }
            val envelope = buildJsonObject {
                put("schemaVersion", "shop-sync-event-page-v1"); put("shopId", NativeAckContract.SHOP)
                put("scope", json.encodeToJsonElement(remoteScope)); put("scopeEventMaxId", snapshot.events.size.toString())
                put("asOfEventMaxId", snapshot.events.size.toString()); put("asOfDomainEventMaxIds", json.encodeToJsonElement(fences(snapshot)))
                put("pageLimit", limit); put("rows", json.encodeToJsonElement(selected)); put("nextAfterId", next); put("hasMore", more)
            }
            ShopSyncEventPage("shop-sync-event-page-v1", NativeAckContract.SHOP, remoteScope,
                snapshot.events.size.toString(), snapshot.events.size.toString(), fences(snapshot), limit, selected,
                next, more, envelope.toString().encodeToByteArray().size.toLong())
        }
    }
}

/** Same pinned-SDK typed rejection as the existing R4 Room test, with no real endpoint. */
@OptIn(SupabaseInternal::class)
private suspend fun <T> nativeResolutionTypedRejection(sqlState: String, send: suspend (CatalogRemoteDataSource) -> Result<T>): Result<T> {
    check(sqlState in setOf("23503", "23505"))
    val count = java.util.concurrent.atomic.AtomicInteger()
    val sdk = createSupabaseClient("http://127.0.0.1:1", "synthetic-public-key") {
        httpEngine = OkHttp.create { config { addInterceptor { chain ->
            count.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(409)
                .message("Controlled atomic rejection")
                .body("""{"code":"$sqlState","message":"controlled atomic rejection","details":null,"hint":null}"""
                    .toResponseBody("application/json".toMediaType())).build()
        } } }
        coroutineDispatcher = Dispatchers.IO; defaultLogLevel = LogLevel.NONE; requestTimeout = 3.seconds
        install(Auth) { autoLoadFromStorage = false; autoSetupPlatform = false
            sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache() }
        install(Postgrest) { timeout = 3.seconds }
    }
    try {
        sdk.auth.importSession(UserSession(accessToken = "synthetic-access", refreshToken = "synthetic-refresh", expiresIn = 86_400,
            tokenType = "bearer", user = UserInfo(aud = "authenticated", id = NativeAckContract.OWNER),
            expiresAt = Clock.System.now() + 1.days), autoRefresh = false)
        val result = withTimeout(5_000L) { send(SupabaseCatalogRemoteDataSource(sdk)) }
        val error = result.exceptionOrNull()
        check(error is PostgrestRestException && error.code == sqlState && error.statusCode == 409 && count.get() == 1)
        return result
    } finally { sdk.close() }
}

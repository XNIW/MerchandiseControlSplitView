package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.api.authenticatedSupabaseApi
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal data class ShopSyncRpcResponse(
    val payload: JsonObject,
    val bodyBytes: Long,
    val largestRawRowsElementBytes: Long = 0L
)

internal fun interface ShopSyncRpcInvoker {
    suspend fun call(
        function: String,
        params: JsonObject,
        maximumResponseBytes: Long,
        maximumHistoryRowBytes: Long?
    ): ShopSyncRpcResponse
}

@OptIn(SupabaseInternal::class)
private class SupabaseShopSyncRpcInvoker(
    private val client: SupabaseClient
) : ShopSyncRpcInvoker {
    private val api = client.authenticatedSupabaseApi(client.postgrest)

    override suspend fun call(
        function: String,
        params: JsonObject,
        maximumResponseBytes: Long,
        maximumHistoryRowBytes: Long?
    ): ShopSyncRpcResponse {
        if (maximumResponseBytes !in 1..Int.MAX_VALUE.toLong()) {
            contractFailure("rpc_response_limit_invalid")
        }
        return api.prepareRequest("rpc/$function") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            headers.append("Content-Profile", client.postgrest.config.defaultSchema)
            setBody(params)
            timeout {
                requestTimeoutMillis = client.postgrest.config.timeout.inWholeMilliseconds
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw ShopSyncContractException("shop_sync_rpc_http_${response.status.value}")
            }
            val boundedBody = readShopSyncResponseBounded(
                channel = response.bodyAsChannel(),
                maximumBytes = maximumResponseBytes,
                maximumHistoryRowBytes = maximumHistoryRowBytes
            )
            val payload = try {
                SHOP_SYNC_JSON.parseToJsonElement(boundedBody.bytes.decodeToString()).jsonObject
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw ShopSyncContractException("shop_sync_rpc_json_invalid")
            }
            ShopSyncRpcResponse(
                payload = payload,
                bodyBytes = boundedBody.bytes.size.toLong(),
                largestRawRowsElementBytes = boundedBody.largestRowsElementBytes
            )
        }
    }
}

class SupabaseShopSyncReadRemoteDataSource internal constructor(
    private val invoker: ShopSyncRpcInvoker?,
    private val resourceLimits: ShopSyncRecoveryResourceLimits =
        DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS
) : ShopSyncReadRemoteDataSource {

    constructor(client: SupabaseClient?) : this(
        client?.let(::SupabaseShopSyncRpcInvoker),
        DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS
    )

    internal constructor(
        client: SupabaseClient?,
        resourceLimits: ShopSyncRecoveryResourceLimits
    ) : this(client?.let(::SupabaseShopSyncRpcInvoker), resourceLimits)

    override val isConfigured: Boolean get() = invoker != null

    override suspend fun checkpoint(
        context: ShopSyncRpcContext
    ): Result<ShopSyncRecoveryCheckpoint> = rpcResult {
        validateContext(context)
        val response = requireInvoker().call(
            SHOP_SYNC_RECOVERY_CHECKPOINT_RPC,
            params(
                ShopSyncCheckpointParams(
                    shopId = canonicalUuid(context.shopId, "shop_id_invalid"),
                    deviceIdentifier = context.deviceIdentifier.trim(),
                    verifiedBaselineId = canonicalEventId(
                        context.verifiedBaselineId,
                        "verified_baseline_id_invalid"
                    ),
                    expectedBaselineScopeKey = context.expectedBaselineScopeKey?.also {
                        validateDigest(it, "expected_baseline_scope_key_invalid")
                    }
                )
            ),
            resourceLimits.defaultPageResponseBytes,
            null
        )
        SHOP_SYNC_JSON.decodeFromJsonElement<ShopSyncRecoveryCheckpoint>(response.payload)
            .also { validateCheckpoint(context, it) }
    }

    override suspend fun convergenceMarker(
        context: ShopSyncRpcContext
    ): Result<ShopSyncConvergenceMarker> = rpcResult {
        validateContext(context)
        val response = requireInvoker().call(
            SHOP_SYNC_CONVERGENCE_MARKER_RPC,
            params(
                ShopSyncCheckpointParams(
                    shopId = canonicalUuid(context.shopId, "shop_id_invalid"),
                    deviceIdentifier = context.deviceIdentifier.trim(),
                    verifiedBaselineId = canonicalEventId(
                        context.verifiedBaselineId,
                        "verified_baseline_id_invalid"
                    ),
                    expectedBaselineScopeKey = context.expectedBaselineScopeKey?.also {
                        validateDigest(it, "expected_baseline_scope_key_invalid")
                    }
                )
            ),
            resourceLimits.defaultPageResponseBytes,
            null
        )
        SHOP_SYNC_JSON.decodeFromJsonElement<ShopSyncConvergenceMarker>(response.payload)
            .also { validateConvergenceMarker(context, it) }
    }

    override suspend fun recoveryPage(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        afterId: String?,
        limit: Int
    ): Result<ShopSyncRecoveryPage> = rpcResult {
        validateContext(context)
        require(limit in 1..250) { throw ShopSyncContractException("page_limit_invalid") }
        val fence = requirePageFence(context)
        val canonicalAfterId = afterId?.let {
            canonicalRecoveryEntityUuid(it, "page_cursor_invalid")
        }
        val effectiveLimit = minOf(limit, resourceLimits.pageRows(domain))
        val response = requireInvoker().call(
            SHOP_SYNC_RECOVERY_PAGE_RPC,
            params(
                ShopSyncRecoveryPageParams(
                    shopId = canonicalUuid(context.shopId, "shop_id_invalid"),
                    deviceIdentifier = context.deviceIdentifier.trim(),
                    domain = domain.wireValue,
                    afterId = canonicalAfterId,
                    limit = effectiveLimit,
                    expectedScopeKey = fence.scopeKey,
                    expectedEventMaxId = fence.eventMaxId,
                    expectedDomainEventMaxId = fence.domainEventMaxId
                )
            ),
            resourceLimits.pageResponseBytes(domain),
            maximumHistoryRowBytes = resourceLimits.historyRowResponseBytes
                .takeIf { domain == ShopSyncRowDomain.HISTORY }
        )
        val wire = SHOP_SYNC_JSON.decodeFromJsonElement<ShopSyncRecoveryPageWire>(
            response.payload
        )
        validateEnvelope(
            context = context,
            schemaVersion = wire.schemaVersion,
            expectedSchemaVersion = SHOP_SYNC_RECOVERY_PAGE_SCHEMA,
            shopId = wire.shopId,
            scope = wire.scope
        )
        if (wire.domain != domain.wireValue) contractFailure("page_domain_mismatch")
        if (wire.pageLimit != effectiveLimit) contractFailure("page_limit_mismatch")
        validateRecoveryPageFence(wire, domain, fence)
        val largestRowBytes = response.largestRawRowsElementBytes
        if (domain == ShopSyncRowDomain.HISTORY) {
            requireHistoryRowResponseWithinBudget(largestRowBytes, resourceLimits)
        }
        val rows = decodeRows(domain, wire.rows)
        validateRowsScope(context, wire.scope, rows)
        validateUuidPage(
            ids = rows.ids(),
            afterId = canonicalAfterId,
            pageLimit = wire.pageLimit,
            nextAfterId = wire.nextAfterId,
            hasMore = wire.hasMore
        )
        ShopSyncRecoveryPage(
            schemaVersion = wire.schemaVersion,
            shopId = wire.shopId,
            scope = wire.scope,
            domain = domain,
            snapshotEventMaxId = wire.snapshotEventMaxId,
            currentScopeEventMaxId = wire.currentScopeEventMaxId,
            baselineDomainEventMaxId = wire.baselineDomainEventMaxId,
            pageDomainEventMaxId = wire.pageDomainEventMaxId,
            domainScope = wire.domainScope,
            pageLimit = wire.pageLimit,
            rows = rows,
            nextAfterId = wire.nextAfterId,
            hasMore = wire.hasMore,
            responseBytes = response.bodyBytes,
            largestRowBytes = largestRowBytes
        )
    }

    override suspend fun eventPage(
        context: ShopSyncRpcContext,
        afterId: Long,
        limit: Int
    ): Result<ShopSyncEventPage> = rpcResult {
        validateContext(context)
        if (afterId < 0L) contractFailure("event_cursor_invalid")
        if (limit !in 1..SHOP_SYNC_EVENT_PAGE_LIMIT) contractFailure("event_limit_invalid")
        val expectedScope = context.expectedScope
            ?: contractFailure("event_scope_fence_missing")
        validateDigest(expectedScope.key, "expected_scope_key_invalid")
        val expectedEventMax = context.expectedEventMaxId?.let {
            canonicalEventId(it, "expected_event_max_id_invalid")
        }
        if (expectedEventMax == null && afterId != 0L) {
            contractFailure("event_bootstrap_fence_missing")
        }
        val effectiveLimit = minOf(limit, SHOP_SYNC_EVENT_PAGE_LIMIT)
        val response = requireInvoker().call(
            SHOP_SYNC_EVENT_PAGE_RPC,
            params(
                ShopSyncEventPageParams(
                    shopId = canonicalUuid(context.shopId, "shop_id_invalid"),
                    deviceIdentifier = context.deviceIdentifier.trim(),
                    afterId = afterId.toString(),
                    limit = effectiveLimit,
                    expectedScopeKey = expectedScope.key,
                    expectedEventMaxId = expectedEventMax
                )
            ),
            resourceLimits.defaultPageResponseBytes,
            null
        )
        val wire = SHOP_SYNC_JSON.decodeFromJsonElement<ShopSyncEventPageWire>(
            response.payload
        )
        validateEnvelope(
            context = context,
            schemaVersion = wire.schemaVersion,
            expectedSchemaVersion = SHOP_SYNC_EVENT_PAGE_SCHEMA,
            shopId = wire.shopId,
            scope = wire.scope
        )
        if (wire.pageLimit != effectiveLimit || wire.rows.size > effectiveLimit) {
            contractFailure("event_limit_mismatch")
        }
        validateEventPageFence(wire, expectedEventMax)
        val rows = wire.rows.map(::decodeEventRow)
        validateEventRowsScope(context, wire.scope, rows)
        val ids = rows.map { it.id }
        if (ids.any { it <= afterId } || ids.zipWithNext().any { (a, b) -> b <= a }) {
            contractFailure("event_cursor_not_increasing")
        }
        if (wire.hasMore) {
            if (
                wire.rows.size != effectiveLimit ||
                wire.nextAfterId != ids.lastOrNull()?.toString()
            ) {
                contractFailure("event_cursor_stalled")
            }
        } else if (wire.nextAfterId != null) {
            contractFailure("event_terminal_cursor_present")
        }
        ShopSyncEventPage(
            schemaVersion = wire.schemaVersion,
            shopId = wire.shopId,
            scope = wire.scope,
            pageLimit = wire.pageLimit,
            scopeEventMaxId = wire.scopeEventMaxId,
            asOfEventMaxId = wire.asOfEventMaxId,
            asOfDomainEventMaxIds = wire.asOfDomainEventMaxIds,
            rows = rows,
            nextAfterId = wire.nextAfterId?.let {
                parseShopSyncMaxEventId(it)
            },
            hasMore = wire.hasMore,
            responseBytes = response.bodyBytes
        )
    }

    override suspend fun rowsByIds(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        ids: List<String>
    ): Result<ShopSyncTargetedRows> = rpcResult {
        validateContext(context)
        val fence = requirePageFence(context)
        if (ids.size !in 1..resourceLimits.targetedRows(domain)) {
            contractFailure("targeted_ids_count_invalid")
        }
        val canonicalIds = ids.map { canonicalRecoveryEntityUuid(it, "targeted_id_invalid") }
        if (canonicalIds.distinct().size != canonicalIds.size) contractFailure("targeted_ids_duplicate")
        val response = requireInvoker().call(
                SHOP_SYNC_ROWS_BY_IDS_RPC,
                params(
                    ShopSyncRowsByIdsParams(
                        shopId = canonicalUuid(context.shopId, "shop_id_invalid"),
                        deviceIdentifier = context.deviceIdentifier.trim(),
                        domain = domain.wireValue,
                        entityIds = canonicalIds,
                        expectedScopeKey = fence.scopeKey,
                        expectedEventMaxId = fence.eventMaxId,
                        expectedDomainEventMaxId = fence.domainEventMaxId
                    )
                ),
                resourceLimits.pageResponseBytes(domain),
                maximumHistoryRowBytes = resourceLimits.historyRowResponseBytes
                    .takeIf { domain == ShopSyncRowDomain.HISTORY }
            )
        val wire = SHOP_SYNC_JSON.decodeFromJsonElement<ShopSyncTargetedRowsWire>(response.payload)
        validateEnvelope(
            context = context,
            schemaVersion = wire.schemaVersion,
            expectedSchemaVersion = SHOP_SYNC_ROWS_BY_IDS_SCHEMA,
            shopId = wire.shopId,
            scope = wire.scope
        )
        if (wire.domain != domain.wireValue || wire.requestedCount != canonicalIds.size) {
            contractFailure("targeted_envelope_mismatch")
        }
        validateTargetedRowsFence(wire, domain, fence)
        if (domain == ShopSyncRowDomain.HISTORY) {
            requireHistoryRowResponseWithinBudget(
                response.largestRawRowsElementBytes,
                resourceLimits
            )
        }
        val rows = decodeRows(domain, wire.rows)
        validateRowsScope(context, wire.scope, rows)
        val returned = rows.ids().map {
            canonicalRecoveryEntityUuid(it, "targeted_row_id_invalid")
        }
        val missing = wire.missingIds.map {
            canonicalRecoveryEntityUuid(it, "targeted_missing_id_invalid")
        }
        if (
            returned.distinct().size != returned.size ||
            missing.distinct().size != missing.size ||
            returned.toSet().intersect(missing.toSet()).isNotEmpty() ||
            (returned + missing).toSet() != canonicalIds.toSet()
        ) {
            contractFailure("targeted_partition_invalid")
        }
        ShopSyncTargetedRows(
            schemaVersion = wire.schemaVersion,
            shopId = wire.shopId,
            scope = wire.scope,
            domain = domain,
            asOfEventMaxId = wire.asOfEventMaxId,
            currentScopeEventMaxId = wire.currentScopeEventMaxId,
            minimumDomainEventMaxId = wire.minimumDomainEventMaxId,
            materializedDomainEventMaxId = wire.materializedDomainEventMaxId,
            domainScope = wire.domainScope,
            requestedCount = wire.requestedCount,
            rows = rows,
            missingIds = missing,
            responseBytes = response.bodyBytes,
            largestRowBytes = response.largestRawRowsElementBytes
        )
    }

    private fun validateCheckpoint(
        context: ShopSyncRpcContext,
        checkpoint: ShopSyncRecoveryCheckpoint
    ) {
        validateEnvelope(
            context = context,
            schemaVersion = checkpoint.schemaVersion,
            expectedSchemaVersion = SHOP_SYNC_RECOVERY_CHECKPOINT_SCHEMA,
            shopId = checkpoint.shopId,
            scope = checkpoint.scope
        )
        if (checkpoint.status != SHOP_SYNC_READY_STATUS) {
            contractFailure("checkpoint_not_ready")
        }
        validateEventCheckpoint(checkpoint.syncEvents, context.verifiedBaselineId)
        validateDigest(checkpoint.checkpointDigest, "checkpoint_digest_invalid")
        validateDigest(checkpoint.catalog.digest, "catalog_digest_invalid")
        listOf(
            checkpoint.catalog.suppliers,
            checkpoint.catalog.categories,
            checkpoint.catalog.products,
            checkpoint.prices,
            checkpoint.history,
            checkpoint.images
        ).forEach(::validateDomainCheckpoint)
        validateRecoveryCheckpointResourceBounds(checkpoint, resourceLimits)
        if (checkpoint.catalog.products.identityDigest == null) {
            contractFailure("product_identity_digest_missing")
        }
        validateDigest(checkpoint.catalog.products.identityDigest, "product_identity_digest_invalid")
        val integrity = checkpoint.integrity
        val parts = listOf(
            integrity.productCategoryViolationCount,
            integrity.productSupplierViolationCount,
            integrity.priceProductViolationCount,
            integrity.primaryImageViolationCount,
            integrity.historyIdViolationCount
        )
        val calculatedTotal = parts.fold(0L) { total, value ->
            if (value < 0L || total > Long.MAX_VALUE - value) {
                contractFailure("integrity_count_invalid")
            }
            total + value
        }
        if (
            integrity.totalViolationCount < 0L ||
            integrity.totalViolationCount != calculatedTotal
        ) {
            contractFailure("integrity_count_invalid")
        }
    }

    private fun validateConvergenceMarker(
        context: ShopSyncRpcContext,
        marker: ShopSyncConvergenceMarker
    ) {
        validateEnvelope(
            context = context,
            schemaVersion = marker.schemaVersion,
            expectedSchemaVersion = SHOP_SYNC_CONVERGENCE_MARKER_SCHEMA,
            shopId = marker.shopId,
            scope = marker.scope
        )
        if (marker.status != SHOP_SYNC_READY_STATUS || !marker.serverNoWorkEligible) {
            contractFailure("convergence_marker_not_eligible")
        }
        validateEventCheckpoint(marker.syncEvents, context.verifiedBaselineId)
        if (marker.syncEvents.maxId != canonicalEventId(
                context.verifiedBaselineId,
                "verified_baseline_id_invalid"
            )
        ) {
            contractFailure("convergence_marker_baseline_mismatch")
        }
        if (marker.integrity.totalViolationCount != 0L) {
            contractFailure("convergence_marker_integrity_violation")
        }
        validateDigest(marker.checkpointDigest, "marker_checkpoint_digest_invalid")
        validateDigest(marker.markerDigest, "marker_digest_invalid")
        validateDigest(marker.catalog.digest, "marker_catalog_digest_invalid")
        listOf(
            marker.catalog.suppliers,
            marker.catalog.categories,
            marker.catalog.products,
            marker.prices,
            marker.history,
            marker.images
        ).forEach(::validateDomainCheckpoint)
        if (marker.catalog.products.identityDigest == null) {
            contractFailure("marker_product_identity_digest_missing")
        }
        validateDigest(marker.catalog.products.identityDigest, "marker_product_identity_digest_invalid")
    }

    private fun validateEventCheckpoint(
        events: ShopSyncEventCheckpoint,
        expectedBaselineId: String
    ) {
        val maxId = canonicalEventId(events.maxId, "event_max_id_invalid")
        val baselineId = canonicalEventId(events.verifiedBaselineId, "verified_baseline_id_invalid")
        if (baselineId != canonicalEventId(expectedBaselineId, "verified_baseline_id_invalid")) {
            contractFailure("verified_baseline_id_mismatch")
        }
        if (parseShopSyncMaxEventId(maxId) < parseShopSyncMaxEventId(baselineId)) {
            contractFailure("event_max_before_baseline")
        }
        if (events.domainMaxIds.keys != SHOP_SYNC_EVENT_DOMAINS) {
            contractFailure("domain_event_max_ids_invalid")
        }
        events.domainMaxIds.values.forEach { domainMax ->
            if (parseShopSyncMaxEventId(canonicalEventId(domainMax, "domain_event_max_id_invalid")) >
                parseShopSyncMaxEventId(maxId)
            ) {
                contractFailure("domain_event_max_after_global")
            }
        }
        events.oldestBlockingId?.let {
            canonicalEventId(it, "blocking_event_id_invalid")
        }
        events.newestBlockingId?.let {
            canonicalEventId(it, "blocking_event_id_invalid")
        }
    }

    private fun validateDomainCheckpoint(value: ShopSyncDomainCheckpoint) {
        if (value.activeCount < 0L || value.tombstoneCount < 0L) {
            contractFailure("checkpoint_count_invalid")
        }
        validateDigest(value.idSetDigest, "id_set_digest_invalid")
        validateDigest(value.versionDigest, "version_digest_invalid")
        value.identityDigest?.let { validateDigest(it, "identity_digest_invalid") }
    }

    private fun validateEnvelope(
        context: ShopSyncRpcContext,
        schemaVersion: String,
        expectedSchemaVersion: String,
        shopId: String,
        scope: ShopSyncScope
    ) {
        if (schemaVersion != expectedSchemaVersion) contractFailure("schema_version_mismatch")
        if (canonicalUuid(shopId, "response_shop_id_invalid") != context.shopId.lowercase()) {
            contractFailure("response_shop_mismatch")
        }
        validateScope(context, scope)
    }

    private fun validateScope(context: ShopSyncRpcContext, scope: ShopSyncScope) {
        validateDigest(scope.key, "scope_key_invalid")
        validateDigest(scope.deviceKey, "device_key_invalid")
        val historyKind = scope.historyKind ?: contractFailure("scope_history_kind_missing")
        val accountKey = scope.accountKey ?: contractFailure("scope_account_key_missing")
        validateDigest(accountKey, "scope_account_key_invalid")
        if (accountKey != sha256(context.accountId.trim().lowercase())) {
            contractFailure("scope_account_identity_mismatch")
        }
        if (scope.deviceKey != sha256(context.deviceIdentifier.trim())) {
            contractFailure("scope_device_identity_mismatch")
        }
        if (historyKind !in SHOP_SYNC_HISTORY_SCOPE_KINDS) {
            contractFailure("scope_history_kind_unsupported")
        }
        if (scope.kind !in SHOP_SYNC_CATALOG_SCOPE_KINDS) {
            contractFailure("scope_kind_unsupported")
        }
        val needsLegacyKey = scope.kind != ShopSyncScopeKinds.SHOP_SCOPED ||
            historyKind == ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY
        if (needsLegacyKey) {
            val legacyKey = scope.legacyOwnerKey ?: contractFailure("legacy_owner_key_missing")
            validateDigest(legacyKey, "legacy_owner_key_invalid")
        } else if (scope.legacyOwnerKey != null) {
            contractFailure("scope_legacy_key_unexpected")
        }
        if (context.expectedScope != null && context.expectedScope != scope) {
            contractFailure("scope_changed")
        }
    }

    private fun validateRowsScope(
        context: ShopSyncRpcContext,
        scope: ShopSyncScope,
        rows: ShopSyncRows
    ) {
        when (rows) {
            is ShopSyncRows.Suppliers -> rows.values.forEach {
                validateRowScope(context, scope, it.ownerUserId, it.shopId, isHistory = false)
            }
            is ShopSyncRows.Categories -> rows.values.forEach {
                validateRowScope(context, scope, it.ownerUserId, it.shopId, isHistory = false)
            }
            is ShopSyncRows.Products -> rows.values.forEach {
                validateRowScope(context, scope, it.ownerUserId, it.shopId, isHistory = false)
            }
            is ShopSyncRows.Prices -> rows.values.forEach {
                validateRowScope(context, scope, it.ownerUserId, it.shopId, isHistory = false)
            }
            is ShopSyncRows.History -> rows.values.forEach {
                validateRowScope(
                    context,
                    scope,
                    it.ownerUserId ?: contractFailure("history_owner_missing"),
                    it.shopId,
                    isHistory = true
                )
            }
            is ShopSyncRows.Images -> rows.values.forEach {
                validateRowScope(context, scope, it.ownerUserId, it.shopId, isHistory = false)
            }
        }
    }

    private fun validateEventRowsScope(
        context: ShopSyncRpcContext,
        scope: ShopSyncScope,
        rows: List<SyncEventRemoteRow>
    ) {
        rows.forEach { row ->
            validateRowScope(
                context,
                scope,
                row.ownerUserId,
                row.shopId,
                isHistory = row.domain == SyncEventDomains.HISTORY
            )
        }
    }

    private fun validateRowScope(
        context: ShopSyncRpcContext,
        scope: ShopSyncScope,
        ownerUserId: String,
        rowShopId: String?,
        isHistory: Boolean
    ) {
        val kind = if (isHistory) {
            scope.historyKind ?: contractFailure("scope_history_kind_missing")
        } else {
            scope.kind
        }
        when (kind) {
            ShopSyncScopeKinds.SHOP_SCOPED -> {
                if (rowShopId?.lowercase() != context.shopId.lowercase()) {
                    contractFailure("row_shop_scope_mismatch")
                }
            }
            ShopSyncScopeKinds.LEGACY_OWNER_BRIDGE -> {
                if (
                    rowShopId != null ||
                    sha256(ownerUserId.lowercase()) != scope.legacyOwnerKey
                ) {
                    contractFailure("row_legacy_scope_mismatch")
                }
            }
            ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY -> {
                val belongsToShop = rowShopId?.lowercase() == context.shopId.lowercase()
                val belongsToLegacyOwner = rowShopId == null &&
                    sha256(ownerUserId.lowercase()) == scope.legacyOwnerKey
                if (!belongsToShop && !belongsToLegacyOwner) {
                    contractFailure("row_compound_scope_mismatch")
                }
            }
            else -> contractFailure("row_scope_kind_unsupported")
        }
    }

    private data class ShopSyncPageFence(
        val scopeKey: String,
        val eventMaxId: String,
        val domainEventMaxId: String
    )

    /** Recovery and targeted calls are never allowed to discover a snapshot
     * implicitly. The checkpoint is the sole source of this opaque fence. */
    private fun requirePageFence(context: ShopSyncRpcContext): ShopSyncPageFence {
        val scope = context.expectedScope ?: contractFailure("page_scope_fence_missing")
        val scopeKey = scope.key.also { validateDigest(it, "expected_scope_key_invalid") }
        val eventMaxId = context.expectedEventMaxId?.let {
            canonicalEventId(it, "expected_event_max_id_invalid")
        } ?: contractFailure("page_event_fence_missing")
        val domainEventMaxId = context.expectedDomainEventMaxId?.let {
            canonicalEventId(it, "expected_domain_event_max_id_invalid")
        } ?: contractFailure("page_domain_fence_missing")
        if (parseShopSyncMaxEventId(domainEventMaxId) > parseShopSyncMaxEventId(eventMaxId)) {
            contractFailure("page_domain_fence_after_event_fence")
        }
        return ShopSyncPageFence(scopeKey, eventMaxId, domainEventMaxId)
    }

    private fun validateRecoveryPageFence(
        wire: ShopSyncRecoveryPageWire,
        domain: ShopSyncRowDomain,
        expected: ShopSyncPageFence
    ) {
        if (wire.domainScope != expectedDomainScope(wire.scope, domain)) {
            contractFailure("page_domain_scope_mismatch")
        }
        if (canonicalEventId(wire.snapshotEventMaxId, "page_snapshot_event_max_id_invalid") !=
            expected.eventMaxId
        ) {
            contractFailure("page_snapshot_event_max_id_mismatch")
        }
        val current = canonicalEventId(
            wire.currentScopeEventMaxId,
            "page_current_scope_event_max_id_invalid"
        )
        if (parseShopSyncMaxEventId(current) < parseShopSyncMaxEventId(expected.eventMaxId)) {
            contractFailure("page_scope_event_regressed")
        }
        if (canonicalEventId(
                wire.baselineDomainEventMaxId,
                "page_baseline_domain_event_max_id_invalid"
            ) != expected.domainEventMaxId
        ) {
            contractFailure("page_domain_event_max_id_mismatch")
        }
        val currentDomain = canonicalEventId(
            wire.pageDomainEventMaxId,
            "page_current_domain_event_max_id_invalid"
        )
        if (parseShopSyncMaxEventId(currentDomain) <
            parseShopSyncMaxEventId(expected.domainEventMaxId)
        ) {
            contractFailure("page_domain_event_regressed")
        }
    }

    private fun validateTargetedRowsFence(
        wire: ShopSyncTargetedRowsWire,
        domain: ShopSyncRowDomain,
        expected: ShopSyncPageFence
    ) {
        if (wire.domainScope != expectedDomainScope(wire.scope, domain)) {
            contractFailure("targeted_domain_scope_mismatch")
        }
        if (canonicalEventId(wire.asOfEventMaxId, "targeted_as_of_event_max_id_invalid") !=
            expected.eventMaxId
        ) {
            contractFailure("targeted_event_max_id_mismatch")
        }
        val current = canonicalEventId(
            wire.currentScopeEventMaxId,
            "targeted_current_scope_event_max_id_invalid"
        )
        if (parseShopSyncMaxEventId(current) < parseShopSyncMaxEventId(expected.eventMaxId)) {
            contractFailure("targeted_scope_event_regressed")
        }
        if (canonicalEventId(
                wire.minimumDomainEventMaxId,
                "targeted_minimum_domain_event_max_id_invalid"
            ) != expected.domainEventMaxId
        ) {
            contractFailure("targeted_domain_event_max_id_mismatch")
        }
        val materialized = canonicalEventId(
            wire.materializedDomainEventMaxId,
            "targeted_materialized_domain_event_max_id_invalid"
        )
        if (parseShopSyncMaxEventId(materialized) <
            parseShopSyncMaxEventId(expected.domainEventMaxId)
        ) {
            contractFailure("targeted_domain_event_regressed")
        }
    }

    private fun validateEventPageFence(
        wire: ShopSyncEventPageWire,
        expectedEventMaxId: String?
    ) {
        val asOf = canonicalEventId(wire.asOfEventMaxId, "event_as_of_max_id_invalid")
        val scopeMax = canonicalEventId(wire.scopeEventMaxId, "event_scope_max_id_invalid")
        if (parseShopSyncMaxEventId(scopeMax) < parseShopSyncMaxEventId(asOf)) {
            contractFailure("event_scope_max_before_as_of")
        }
        if (expectedEventMaxId != null && asOf != expectedEventMaxId) {
            contractFailure("event_as_of_max_id_mismatch")
        }
        if (wire.asOfDomainEventMaxIds.keys != SHOP_SYNC_EVENT_DOMAINS) {
            contractFailure("event_domain_max_ids_invalid")
        }
        wire.asOfDomainEventMaxIds.values.forEach { value ->
            if (parseShopSyncMaxEventId(canonicalEventId(value, "event_domain_max_id_invalid")) >
                parseShopSyncMaxEventId(asOf)
            ) {
                contractFailure("event_domain_max_after_as_of")
            }
        }
        wire.nextAfterId?.let { next ->
            canonicalEventId(next, "event_next_cursor_invalid")
        }
    }

    private fun expectedDomainScope(scope: ShopSyncScope, domain: ShopSyncRowDomain): String =
        if (domain == ShopSyncRowDomain.HISTORY) {
            scope.historyKind ?: contractFailure("scope_history_kind_missing")
        } else {
            scope.kind
        }

    private fun decodeEventRow(wire: ShopSyncEventRowWire): SyncEventRemoteRow {
        val id = parseShopSyncMaxEventId(canonicalEventId(wire.id, "event_id_invalid"))
        wire.sourceDeviceKey?.let { validateDigest(it, "event_source_device_key_invalid") }
        if (!wire.timestampValid) contractFailure("event_timestamp_invalid")
        return SyncEventRemoteRow(
            id = id,
            ownerUserId = canonicalUuid(wire.ownerUserId, "event_owner_id_invalid"),
            shopId = wire.shopId?.let { canonicalUuid(it, "event_shop_id_invalid") },
            authorizedShopId = wire.authorizedShopId?.let {
                canonicalUuid(it, "event_authorized_shop_id_invalid")
            },
            storeId = wire.storeId?.let { canonicalUuid(it, "event_store_id_invalid") },
            domain = wire.domain,
            eventType = wire.eventType,
            source = wire.source,
            sourceDeviceKey = wire.sourceDeviceKey,
            batchId = wire.batchId,
            clientEventKey = wire.clientEventKey,
            changedCount = wire.changedCount,
            entityIds = wire.entityIds,
            requiresFullRecovery = wire.requiresFullRecovery,
            timestampValid = wire.timestampValid,
            createdAt = wire.createdAt,
            metadata = wire.metadata
        )
    }

    private fun validateUuidPage(
        ids: List<String>,
        afterId: String?,
        pageLimit: Int,
        nextAfterId: String?,
        hasMore: Boolean
    ) {
        if (ids.size > pageLimit) contractFailure("page_overfull")
        val canonicalIds = ids.map { canonicalRecoveryEntityUuid(it, "page_row_id_invalid") }
        if (
            canonicalIds.distinct().size != canonicalIds.size ||
            canonicalIds.zipWithNext().any { (a, b) -> compareUuid(a, b) >= 0 } ||
            (afterId != null && canonicalIds.any { compareUuid(it, afterId) <= 0 })
        ) {
            contractFailure("page_cursor_not_increasing")
        }
        if (hasMore) {
            val canonicalNext = nextAfterId?.let {
                canonicalRecoveryEntityUuid(it, "page_next_cursor_invalid")
            }
                ?: contractFailure("page_next_cursor_missing")
            if (canonicalIds.size != pageLimit || canonicalNext != canonicalIds.lastOrNull()) {
                contractFailure("page_cursor_stalled")
            }
        } else if (nextAfterId != null) {
            contractFailure("page_terminal_cursor_present")
        }
    }

    private fun decodeRows(domain: ShopSyncRowDomain, rows: JsonArray): ShopSyncRows = when (domain) {
        ShopSyncRowDomain.SUPPLIERS -> ShopSyncRows.Suppliers(
            SHOP_SYNC_JSON.decodeFromJsonElement(ListSerializer(InventorySupplierRow.serializer()), rows)
        )
        ShopSyncRowDomain.CATEGORIES -> ShopSyncRows.Categories(
            SHOP_SYNC_JSON.decodeFromJsonElement(ListSerializer(InventoryCategoryRow.serializer()), rows)
        )
        ShopSyncRowDomain.PRODUCTS -> ShopSyncRows.Products(
            SHOP_SYNC_JSON.decodeFromJsonElement(ListSerializer(InventoryProductRow.serializer()), rows)
        )
        ShopSyncRowDomain.PRICES -> ShopSyncRows.Prices(
            SHOP_SYNC_JSON.decodeFromJsonElement(ListSerializer(InventoryProductPriceRow.serializer()), rows)
        )
        ShopSyncRowDomain.HISTORY -> ShopSyncRows.History(
            SHOP_SYNC_JSON.decodeFromJsonElement(ListSerializer(SharedSheetSessionRecord.serializer()), rows)
        )
        ShopSyncRowDomain.IMAGES -> ShopSyncRows.Images(
            SHOP_SYNC_JSON.decodeFromJsonElement(ListSerializer(ShopSyncImageRow.serializer()), rows)
        )
    }

    private fun validateContext(context: ShopSyncRpcContext) {
        canonicalUuid(context.accountId, "account_id_invalid")
        canonicalUuid(context.shopId, "shop_id_invalid")
        val device = context.deviceIdentifier.trim()
        if (device.isEmpty() || device.length > 160) contractFailure("device_identity_invalid")
        val baseline = canonicalEventId(context.verifiedBaselineId, "verified_baseline_id_invalid")
        context.expectedBaselineScopeKey?.let {
            validateDigest(it, "expected_baseline_scope_key_invalid")
        }
        if (baseline != "0" && context.expectedBaselineScopeKey == null) {
            contractFailure("expected_baseline_scope_key_missing")
        }
        context.expectedScope?.let { scope ->
            validateDigest(scope.key, "expected_scope_key_invalid")
            if (
                context.expectedBaselineScopeKey != null &&
                context.expectedBaselineScopeKey != scope.key
            ) {
                contractFailure("baseline_scope_context_mismatch")
            }
        }
    }

    private fun requireInvoker(): ShopSyncRpcInvoker =
        invoker ?: contractFailure("shop_sync_client_missing")

    private inline fun <reified T> params(value: T): JsonObject =
        SHOP_SYNC_JSON.encodeToJsonElement(value).jsonObject

    private suspend fun <T> rpcResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

internal fun requireHistoryRowResponseWithinBudget(
    encodedRowBytes: Long,
    limits: ShopSyncRecoveryResourceLimits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS
) {
    if (encodedRowBytes < 0L) contractFailure("history_row_response_size_invalid")
    if (encodedRowBytes > limits.historyRowResponseBytes) {
        contractFailure("history_row_response_budget_exceeded")
    }
}

internal data class ShopSyncBoundedBody(
    val bytes: ByteArray,
    val largestRowsElementBytes: Long
)

internal suspend fun readShopSyncResponseBounded(
    channel: ByteReadChannel,
    maximumBytes: Long,
    lexicalLimits: ShopSyncJsonLexicalLimits = DEFAULT_SHOP_SYNC_JSON_LEXICAL_LIMITS,
    maximumHistoryRowBytes: Long? = null
): ShopSyncBoundedBody {
    if (maximumBytes !in 1..Int.MAX_VALUE.toLong()) {
        contractFailure("rpc_response_limit_invalid")
    }
    val bytes = channel.readRemaining(maximumBytes + 1L).readByteArray()
    if (bytes.size.toLong() > maximumBytes) {
        channel.cancel()
        contractFailure("rpc_response_budget_exceeded")
    }
    validateShopSyncJsonLexicalBudget(bytes, lexicalLimits)
    val largestRowsElementBytes = maximumHistoryRowBytes?.let { maximum ->
        validateShopSyncHistoryRowsRawBudget(bytes, maximum)
    } ?: 0L
    return ShopSyncBoundedBody(bytes, largestRowsElementBytes)
}

internal data class ShopSyncJsonLexicalLimits(
    val maximumDepth: Int = 64,
    val maximumTokens: Int = 250_000,
    val maximumStringBytes: Int = 512 * 1024,
    val maximumScalarBytes: Int = 256
) {
    init {
        require(
            maximumDepth > 0 &&
                maximumTokens > 0 &&
                maximumStringBytes > 0 &&
                maximumScalarBytes > 0
        )
    }
}

internal val DEFAULT_SHOP_SYNC_JSON_LEXICAL_LIMITS = ShopSyncJsonLexicalLimits()

/**
 * Preflight allocation-free sul body raw. Il parser JSON tree amplifica ogni
 * token in oggetti heap: il solo cap in byte non protegge da milioni di valori
 * minuscoli o profondita' patologica. Questo guard viene eseguito prima di
 * ByteArray -> String -> JsonElement e non intercetta mai VM Error.
 */
internal fun validateShopSyncJsonLexicalBudget(
    bytes: ByteArray,
    limits: ShopSyncJsonLexicalLimits = DEFAULT_SHOP_SYNC_JSON_LEXICAL_LIMITS
) {
    var depth = 0
    var tokens = 0
    var inString = false
    var escaped = false
    var stringBytes = 0
    var scalarBytes = 0

    fun recordToken() {
        tokens += 1
        if (tokens > limits.maximumTokens) {
            contractFailure("rpc_response_json_token_budget_exceeded")
        }
    }

    fun finishScalar() {
        scalarBytes = 0
    }

    for (raw in bytes) {
        val value = raw.toInt() and 0xff
        if (inString) {
            when {
                escaped -> {
                    escaped = false
                    stringBytes += 1
                }
                value == '\\'.code -> {
                    escaped = true
                    stringBytes += 1
                }
                value == '"'.code -> {
                    inString = false
                    stringBytes = 0
                }
                else -> stringBytes += 1
            }
            if (stringBytes > limits.maximumStringBytes) {
                contractFailure("rpc_response_json_string_budget_exceeded")
            }
            continue
        }

        when (value) {
            '"'.code -> {
                finishScalar()
                inString = true
                escaped = false
                stringBytes = 0
                recordToken()
            }
            '{'.code, '['.code -> {
                finishScalar()
                depth += 1
                if (depth > limits.maximumDepth) {
                    contractFailure("rpc_response_json_depth_budget_exceeded")
                }
                recordToken()
            }
            '}'.code, ']'.code -> {
                finishScalar()
                depth -= 1
                recordToken()
            }
            ':'.code, ','.code -> {
                finishScalar()
                recordToken()
            }
            ' '.code, '\t'.code, '\r'.code, '\n'.code -> finishScalar()
            else -> {
                if (scalarBytes == 0) recordToken()
                scalarBytes += 1
                if (scalarBytes > limits.maximumScalarBytes) {
                    contractFailure("rpc_response_json_scalar_budget_exceeded")
                }
            }
        }
    }
}

/**
 * Misura ogni elemento diretto dell'array top-level `rows` sul body UTF-8 raw,
 * prima di `ByteArray -> String -> JsonElement`. Quote, escape e strutture
 * annidate vengono attraversati senza creare substring o collection.
 */
internal fun validateShopSyncHistoryRowsRawBudget(
    bytes: ByteArray,
    maximumRowBytes: Long
): Long {
    if (maximumRowBytes !in 1..Int.MAX_VALUE.toLong()) {
        contractFailure("history_row_response_size_invalid")
    }
    var index = 0
    var depth = 0
    var rowsArrays = 0
    var largest = 0L
    while (index < bytes.size) {
        when (bytes[index].toInt() and 0xff) {
            '{'.code, '['.code -> {
                depth += 1
                index += 1
            }
            '}'.code, ']'.code -> {
                depth -= 1
                index += 1
            }
            '"'.code -> {
                val closingQuote = findRawJsonStringEnd(bytes, index)
                val isRowsKey = depth == 1 &&
                    closingQuote == index + 5 &&
                    bytes[index + 1] == 'r'.code.toByte() &&
                    bytes[index + 2] == 'o'.code.toByte() &&
                    bytes[index + 3] == 'w'.code.toByte() &&
                    bytes[index + 4] == 's'.code.toByte()
                if (!isRowsKey) {
                    index = closingQuote + 1
                    continue
                }
                var cursor = skipRawJsonWhitespace(bytes, closingQuote + 1)
                if (cursor >= bytes.size || bytes[cursor] != ':'.code.toByte()) {
                    index = closingQuote + 1
                    continue
                }
                cursor = skipRawJsonWhitespace(bytes, cursor + 1)
                if (cursor >= bytes.size || bytes[cursor] != '['.code.toByte()) {
                    contractFailure("shop_sync_rpc_json_invalid")
                }
                rowsArrays += 1
                if (rowsArrays != 1) contractFailure("shop_sync_rpc_json_invalid")
                val scan = scanRawJsonRowsArray(bytes, cursor, maximumRowBytes)
                largest = scan.largestElementBytes
                index = scan.closingBracketIndex + 1
            }
            else -> index += 1
        }
    }
    if (rowsArrays != 1) contractFailure("shop_sync_rpc_json_invalid")
    return largest
}

private data class RawJsonRowsScan(
    val closingBracketIndex: Int,
    val largestElementBytes: Long
)

private fun scanRawJsonRowsArray(
    bytes: ByteArray,
    openingBracketIndex: Int,
    maximumRowBytes: Long
): RawJsonRowsScan {
    var index = skipRawJsonWhitespace(bytes, openingBracketIndex + 1)
    if (index >= bytes.size) contractFailure("shop_sync_rpc_json_invalid")
    if (bytes[index] == ']'.code.toByte()) return RawJsonRowsScan(index, 0L)
    var largest = 0L
    while (index < bytes.size) {
        val elementStart = index
        var nestedDepth = 0
        var inString = false
        var escaped = false
        while (index < bytes.size) {
            val value = bytes[index].toInt() and 0xff
            if (inString) {
                when {
                    escaped -> escaped = false
                    value == '\\'.code -> escaped = true
                    value == '"'.code -> inString = false
                }
                index += 1
                continue
            }
            when (value) {
                '"'.code -> inString = true
                '{'.code, '['.code -> nestedDepth += 1
                '}'.code -> {
                    if (nestedDepth <= 0) contractFailure("shop_sync_rpc_json_invalid")
                    nestedDepth -= 1
                }
                ']'.code -> {
                    if (nestedDepth > 0) {
                        nestedDepth -= 1
                    } else {
                        largest = maxOf(
                            largest,
                            rawJsonElementBytes(bytes, elementStart, index, maximumRowBytes)
                        )
                        return RawJsonRowsScan(index, largest)
                    }
                }
                ','.code -> if (nestedDepth == 0) {
                    largest = maxOf(
                        largest,
                        rawJsonElementBytes(bytes, elementStart, index, maximumRowBytes)
                    )
                    index = skipRawJsonWhitespace(bytes, index + 1)
                    if (index >= bytes.size || bytes[index] == ']'.code.toByte()) {
                        contractFailure("shop_sync_rpc_json_invalid")
                    }
                    break
                }
            }
            index += 1
        }
    }
    contractFailure("shop_sync_rpc_json_invalid")
}

private fun rawJsonElementBytes(
    bytes: ByteArray,
    start: Int,
    endExclusive: Int,
    maximumRowBytes: Long
): Long {
    var trimmedEnd = endExclusive
    while (
        trimmedEnd > start &&
        isRawJsonWhitespace(bytes[trimmedEnd - 1].toInt() and 0xff)
    ) {
        trimmedEnd -= 1
    }
    if (trimmedEnd <= start) contractFailure("shop_sync_rpc_json_invalid")
    val size = (trimmedEnd - start).toLong()
    if (size > maximumRowBytes) {
        contractFailure("history_row_response_budget_exceeded")
    }
    return size
}

private fun findRawJsonStringEnd(bytes: ByteArray, openingQuoteIndex: Int): Int {
    var index = openingQuoteIndex + 1
    var escaped = false
    while (index < bytes.size) {
        val value = bytes[index].toInt() and 0xff
        when {
            escaped -> escaped = false
            value == '\\'.code -> escaped = true
            value == '"'.code -> return index
        }
        index += 1
    }
    contractFailure("shop_sync_rpc_json_invalid")
}

private fun skipRawJsonWhitespace(bytes: ByteArray, start: Int): Int {
    var index = start
    while (
        index < bytes.size &&
        isRawJsonWhitespace(bytes[index].toInt() and 0xff)
    ) {
        index += 1
    }
    return index
}

private fun isRawJsonWhitespace(value: Int): Boolean =
    value == ' '.code || value == '\t'.code || value == '\r'.code || value == '\n'.code

internal fun validateRecoveryCheckpointResourceBounds(
    checkpoint: ShopSyncRecoveryCheckpoint,
    limits: ShopSyncRecoveryResourceLimits = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS
) {
    var total = 0L
    ShopSyncRowDomain.entries.forEach { domain ->
        val value = checkpoint.domainForResourceValidation(domain)
        if (value.activeCount < 0L || value.tombstoneCount < 0L) {
            contractFailure("checkpoint_count_invalid")
        }
        if (value.activeCount > Long.MAX_VALUE - value.tombstoneCount) {
            contractFailure("checkpoint_count_overflow_${domain.wireValue}")
        }
        val rows = value.activeCount + value.tombstoneCount
        if (rows > limits.rows(domain)) {
            contractFailure("checkpoint_row_budget_exceeded_${domain.wireValue}")
        }
        if (total > Long.MAX_VALUE - rows) {
            contractFailure("checkpoint_total_count_overflow")
        }
        total += rows
        if (total > limits.totalRows) {
            contractFailure("checkpoint_total_row_budget_exceeded")
        }
    }
}

private fun ShopSyncRecoveryCheckpoint.domainForResourceValidation(
    domain: ShopSyncRowDomain
): ShopSyncDomainCheckpoint = when (domain) {
    ShopSyncRowDomain.SUPPLIERS -> catalog.suppliers
    ShopSyncRowDomain.CATEGORIES -> catalog.categories
    ShopSyncRowDomain.PRODUCTS -> catalog.products
    ShopSyncRowDomain.PRICES -> prices
    ShopSyncRowDomain.HISTORY -> history
    ShopSyncRowDomain.IMAGES -> images
}

@Serializable
internal data class ShopSyncCheckpointParams(
    @SerialName("p_shop_id") val shopId: String,
    @SerialName("p_device_identifier") val deviceIdentifier: String,
    @SerialName("p_verified_baseline_id") val verifiedBaselineId: String,
    @SerialName("p_expected_baseline_scope_key") val expectedBaselineScopeKey: String? = null
)

@Serializable
internal data class ShopSyncRecoveryPageParams(
    @SerialName("p_shop_id") val shopId: String,
    @SerialName("p_device_identifier") val deviceIdentifier: String,
    @SerialName("p_domain") val domain: String,
    @SerialName("p_after_id") val afterId: String?,
    @SerialName("p_limit") val limit: Int,
    @SerialName("p_expected_scope_key") val expectedScopeKey: String,
    @SerialName("p_expected_event_max_id") val expectedEventMaxId: String,
    @SerialName("p_expected_domain_event_max_id") val expectedDomainEventMaxId: String
)

@Serializable
internal data class ShopSyncEventPageParams(
    @SerialName("p_shop_id") val shopId: String,
    @SerialName("p_device_identifier") val deviceIdentifier: String,
    @SerialName("p_after_id") val afterId: String,
    @SerialName("p_limit") val limit: Int,
    @SerialName("p_expected_scope_key") val expectedScopeKey: String,
    @SerialName("p_expected_event_max_id") val expectedEventMaxId: String? = null
)

@Serializable
internal data class ShopSyncRowsByIdsParams(
    @SerialName("p_shop_id") val shopId: String,
    @SerialName("p_device_identifier") val deviceIdentifier: String,
    @SerialName("p_domain") val domain: String,
    @SerialName("p_entity_ids") val entityIds: List<String>,
    @SerialName("p_expected_scope_key") val expectedScopeKey: String,
    @SerialName("p_expected_event_max_id") val expectedEventMaxId: String,
    @SerialName("p_expected_domain_event_max_id") val expectedDomainEventMaxId: String
)

@Serializable
private data class ShopSyncRecoveryPageWire(
    val schemaVersion: String,
    val shopId: String,
    val scope: ShopSyncScope,
    val domain: String,
    val snapshotEventMaxId: String,
    val currentScopeEventMaxId: String,
    val baselineDomainEventMaxId: String,
    val pageDomainEventMaxId: String,
    val domainScope: String,
    val pageLimit: Int,
    val rows: JsonArray,
    val nextAfterId: String? = null,
    val hasMore: Boolean
)

@Serializable
private data class ShopSyncEventPageWire(
    val schemaVersion: String,
    val shopId: String,
    val scope: ShopSyncScope,
    val scopeEventMaxId: String,
    val asOfEventMaxId: String,
    val asOfDomainEventMaxIds: Map<String, String>,
    val pageLimit: Int,
    val rows: List<ShopSyncEventRowWire>,
    val nextAfterId: String? = null,
    val hasMore: Boolean
)

/** V6 returns bigints as JSON strings. This DTO is intentionally separate
 * from [SyncEventRemoteRow], whose Long ID is local-only after validation. */
@Serializable
private data class ShopSyncEventRowWire(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("shop_id") val shopId: String? = null,
    @SerialName("authorized_shop_id") val authorizedShopId: String? = null,
    @SerialName("store_id") val storeId: String? = null,
    val domain: String,
    @SerialName("event_type") val eventType: String,
    val source: String? = null,
    @SerialName("source_device_key") val sourceDeviceKey: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("client_event_key") val clientEventKey: String? = null,
    @SerialName("changed_count") val changedCount: Int = 0,
    @SerialName("entity_ids") val entityIds: SyncEventEntityIds? = null,
    @SerialName("requires_full_recovery") val requiresFullRecovery: Boolean = true,
    @SerialName("timestamp_valid") val timestampValid: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    val metadata: JsonObject = JsonObject(emptyMap())
)

@Serializable
private data class ShopSyncTargetedRowsWire(
    val schemaVersion: String,
    val shopId: String,
    val scope: ShopSyncScope,
    val domain: String,
    val asOfEventMaxId: String,
    val currentScopeEventMaxId: String,
    val minimumDomainEventMaxId: String,
    val materializedDomainEventMaxId: String,
    val domainScope: String,
    val requestedCount: Int,
    val rows: JsonArray,
    val missingIds: List<String>
)

internal fun parseShopSyncMaxEventId(value: String): Long {
    if (!SHOP_SYNC_EVENT_ID_PATTERN.matches(value)) contractFailure("event_max_id_invalid")
    val parsed = value.toLongOrNull() ?: contractFailure("event_max_id_overflow")
    if (parsed.toString() != value) contractFailure("event_max_id_noncanonical")
    return parsed
}

/** Canonical V6 bigint wire boundary. Keep the string until the one local
 * Long conversion required by the Room watermark/event tables. */
private fun canonicalEventId(value: String, errorCode: String): String {
    if (!SHOP_SYNC_EVENT_ID_PATTERN.matches(value)) contractFailure(errorCode)
    val parsed = value.toLongOrNull() ?: contractFailure(errorCode)
    if (parsed.toString() != value) contractFailure(errorCode)
    return value
}

private fun canonicalUuid(value: String, errorCode: String): String {
    val canonical = value.trim().lowercase()
    if (!SHOP_SYNC_UUID_PATTERN.matches(canonical)) contractFailure(errorCode)
    if (runCatching { UUID.fromString(canonical).toString() }.getOrNull() != canonical) {
        contractFailure(errorCode)
    }
    return canonical
}

private fun canonicalRecoveryEntityUuid(value: String, errorCode: String): String =
    canonicalShopSyncRecoveryEntityIdOrNull(value) ?: contractFailure(errorCode)

private fun compareUuid(left: String, right: String): Int =
    left.replace("-", "").compareTo(right.replace("-", ""))

private fun validateDigest(value: String, errorCode: String) {
    if (!SHOP_SYNC_DIGEST_PATTERN.matches(value)) contractFailure(errorCode)
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun contractFailure(code: String): Nothing = throw ShopSyncContractException(code)

private val SHOP_SYNC_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
    coerceInputValues = false
}

private const val SHOP_SYNC_RECOVERY_CHECKPOINT_RPC = "shop_sync_recovery_checkpoint_v1"
private const val SHOP_SYNC_CONVERGENCE_MARKER_RPC = "shop_sync_convergence_marker_v1"
private const val SHOP_SYNC_RECOVERY_PAGE_RPC = "shop_sync_recovery_page_v1"
private const val SHOP_SYNC_EVENT_PAGE_RPC = "shop_sync_event_page_v1"
private const val SHOP_SYNC_ROWS_BY_IDS_RPC = "shop_sync_rows_by_ids_v1"
private const val SHOP_SYNC_RECOVERY_CHECKPOINT_SCHEMA = "shop-sync-recovery-checkpoint-v1"
private const val SHOP_SYNC_CONVERGENCE_MARKER_SCHEMA = "shop-sync-convergence-marker-v1"
private const val SHOP_SYNC_RECOVERY_PAGE_SCHEMA = "shop-sync-recovery-page-v1"
private const val SHOP_SYNC_EVENT_PAGE_SCHEMA = "shop-sync-event-page-v1"
private const val SHOP_SYNC_ROWS_BY_IDS_SCHEMA = "shop-sync-rows-by-ids-v1"
private val SHOP_SYNC_UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
private val SHOP_SYNC_DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
private val SHOP_SYNC_EVENT_ID_PATTERN = Regex("^(0|[1-9][0-9]{0,18})$")
private const val SHOP_SYNC_EVENT_PAGE_LIMIT = 150
private const val SHOP_SYNC_READY_STATUS = "ready"
private val SHOP_SYNC_EVENT_DOMAINS = setOf(
    SyncEventDomains.CATALOG,
    SyncEventDomains.PRICES,
    SyncEventDomains.HISTORY
)
private val SHOP_SYNC_CATALOG_SCOPE_KINDS = setOf(
    ShopSyncScopeKinds.SHOP_SCOPED,
    ShopSyncScopeKinds.LEGACY_OWNER_BRIDGE,
    ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY
)
private val SHOP_SYNC_HISTORY_SCOPE_KINDS = setOf(
    ShopSyncScopeKinds.SHOP_SCOPED,
    ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY
)

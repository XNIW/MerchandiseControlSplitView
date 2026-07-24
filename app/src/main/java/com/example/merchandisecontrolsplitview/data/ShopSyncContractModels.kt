package com.example.merchandisecontrolsplitview.data

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ShopSyncRowDomain(val wireValue: String) {
    SUPPLIERS("suppliers"),
    CATEGORIES("categories"),
    PRODUCTS("products"),
    PRICES("prices"),
    HISTORY("history"),
    IMAGES("images");

    companion object {
        fun fromWire(value: String): ShopSyncRowDomain? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Canonical identifier accepted by the frozen V6 recovery RPC boundaries.
 *
 * `shop_sync_recovery_page_v1.p_after_id` and
 * `shop_sync_rows_by_ids_v1.p_entity_ids` are intentionally narrower than a
 * generic PostgreSQL UUID: the server accepts RFC UUID versions 1 through 5
 * with an RFC variant only. Keep this distinct from opaque account/shop and
 * image-version fields, whose endpoint contracts are separate.
 */
internal fun canonicalShopSyncRecoveryEntityIdOrNull(value: String): String? {
    val canonical = value.trim().lowercase()
    if (!SHOP_SYNC_RECOVERY_ENTITY_ID_PATTERN.matches(canonical)) return null
    return canonical.takeIf {
        runCatching { UUID.fromString(it).toString() }.getOrNull() == it
    }
}

private val SHOP_SYNC_RECOVERY_ENTITY_ID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)

/**
 * Limiti difensivi condivisi dal decoder RPC e dal coordinator di recovery.
 *
 * I valori impediscono che un checkpoint formalmente valido autorizzi una
 * materializzazione non compatibile con le risorse di un dispositivo mobile.
 * Restano separati dai conteggi cloud: superarli richiede recovery manuale e
 * non consente mai di pubblicare una generation parziale.
 */
@Suppress("DEPRECATION") // validates the retained legacy constructor knob below.
internal data class ShopSyncRecoveryResourceLimits(
    val supplierRows: Long = 25_000L,
    val categoryRows: Long = 25_000L,
    val productRows: Long = 100_000L,
    val priceRows: Long = 150_000L,
    val historyRows: Long = 10_000L,
    val imageRows: Long = 100_000L,
    val totalRows: Long = 350_000L,
    /** V6 effective limits for snapshot pages. */
    val supplierPageRows: Int = 240,
    val categoryPageRows: Int = 240,
    val productPageRows: Int = 60,
    val pricePageRows: Int = 120,
    val historyPageRows: Int = 3,
    val imagePageRows: Int = 240,
    /**
     * Kept only as a source-compatible constructor knob for older local test
     * fixtures. Production calls use [pageRows] and therefore the explicit
     * V6 domain caps above.
     */
    @Deprecated("Use domain page caps") val defaultPageRows: Int = 240,
    val defaultPageResponseBytes: Long = 4L * 1024L * 1024L,
    val historyPageResponseBytes: Long = 4L * 1024L * 1024L,
    val historyRowResponseBytes: Long = 512L * 1024L,
    val domainResponseBytes: Long = 192L * 1024L * 1024L,
    val totalResponseBytes: Long = 384L * 1024L * 1024L,
    val generationBytes: Long = 768L * 1024L * 1024L,
    val activationHeadroomBytes: Long = 128L * 1024L * 1024L
) {
    init {
        require(
            listOf(
                supplierRows,
                categoryRows,
                productRows,
                priceRows,
                historyRows,
                imageRows,
                totalRows,
                defaultPageResponseBytes,
                historyPageResponseBytes,
                historyRowResponseBytes,
                domainResponseBytes,
                totalResponseBytes,
                generationBytes,
                activationHeadroomBytes
            ).all { it > 0L }
        ) { "shop sync resource limits must be positive" }
        // These caps are part of the V6 server contract. Test/resource
        // overrides may narrow a page, never widen it beyond the negotiated
        // effective cap; otherwise a future caller could emit a valid outer
        // RPC limit which is nevertheless invalid for its specific domain.
        require(
            supplierPageRows in 1..240 &&
                categoryPageRows in 1..240 &&
                productPageRows in 1..60 &&
                pricePageRows in 1..120 &&
                historyPageRows in 1..3 &&
                imagePageRows in 1..240 &&
                defaultPageRows in 1..250
        ) {
            "shop sync page limits exceed V6 caps"
        }
        require(historyRowResponseBytes <= historyPageResponseBytes) {
            "history row limit exceeds page limit"
        }
    }

    fun rows(domain: ShopSyncRowDomain): Long = when (domain) {
        ShopSyncRowDomain.SUPPLIERS -> supplierRows
        ShopSyncRowDomain.CATEGORIES -> categoryRows
        ShopSyncRowDomain.PRODUCTS -> productRows
        ShopSyncRowDomain.PRICES -> priceRows
        ShopSyncRowDomain.HISTORY -> historyRows
        ShopSyncRowDomain.IMAGES -> imageRows
    }

    fun pageRows(domain: ShopSyncRowDomain): Int = when (domain) {
        ShopSyncRowDomain.SUPPLIERS -> supplierPageRows
        ShopSyncRowDomain.CATEGORIES -> categoryPageRows
        ShopSyncRowDomain.PRODUCTS -> productPageRows
        ShopSyncRowDomain.PRICES -> pricePageRows
        ShopSyncRowDomain.HISTORY -> historyPageRows
        ShopSyncRowDomain.IMAGES -> imagePageRows
    }

    /** Targeted reads have the same V6 cap except suppliers/categories, which
     * are only fetched as catalog bundles under the 60-row catalog cap. */
    fun targetedRows(domain: ShopSyncRowDomain): Int = when (domain) {
        ShopSyncRowDomain.SUPPLIERS,
        ShopSyncRowDomain.CATEGORIES,
        ShopSyncRowDomain.PRODUCTS -> 60
        ShopSyncRowDomain.PRICES -> 120
        ShopSyncRowDomain.HISTORY -> 3
        ShopSyncRowDomain.IMAGES -> 240
    }

    fun pageResponseBytes(domain: ShopSyncRowDomain): Long =
        if (domain == ShopSyncRowDomain.HISTORY) {
            historyPageResponseBytes
        } else {
            defaultPageResponseBytes
        }
}

internal val DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS = ShopSyncRecoveryResourceLimits()

@Serializable
data class ShopSyncScope(
    val kind: String,
    val key: String,
    val legacyOwnerKey: String? = null,
    val historyKind: String? = null,
    val accountKey: String? = null,
    val deviceKey: String
)

object ShopSyncScopeKinds {
    const val SHOP_SCOPED = "shop_scoped"
    const val LEGACY_OWNER_BRIDGE = "legacy_owner_bridge"
    const val AUTHORIZED_SHOP_PLUS_LEGACY = "authorized_shop_plus_legacy"
}

@Serializable
data class ShopSyncEventCheckpoint(
    val maxId: String,
    val verifiedBaselineId: String = "0",
    val requiresFullRecovery: Boolean = true,
    val domainMaxIds: Map<String, String> = emptyMap(),
    val oldestBlockingId: String? = null,
    val newestBlockingId: String? = null
)

@Serializable
data class ShopSyncDomainCheckpoint(
    val activeCount: Long,
    val tombstoneCount: Long,
    val idSetDigest: String,
    val versionDigest: String,
    val identityDigest: String? = null
)

@Serializable
data class ShopSyncCatalogCheckpoint(
    val suppliers: ShopSyncDomainCheckpoint,
    val categories: ShopSyncDomainCheckpoint,
    val products: ShopSyncDomainCheckpoint,
    val digest: String
)

@Serializable
data class ShopSyncIntegrityCheckpoint(
    val productCategoryViolationCount: Long,
    val productSupplierViolationCount: Long,
    val priceProductViolationCount: Long,
    val primaryImageViolationCount: Long,
    val historyIdViolationCount: Long,
    val totalViolationCount: Long
)

@Serializable
data class ShopSyncRecoveryCheckpoint(
    val schemaVersion: String,
    val status: String = "missing",
    val shopId: String,
    val scope: ShopSyncScope,
    val syncEvents: ShopSyncEventCheckpoint,
    val catalog: ShopSyncCatalogCheckpoint,
    val prices: ShopSyncDomainCheckpoint,
    val history: ShopSyncDomainCheckpoint,
    val images: ShopSyncDomainCheckpoint,
    val integrity: ShopSyncIntegrityCheckpoint,
    val checkpointDigest: String
)

@Serializable
data class ShopSyncImageVariantRow(
    val sha256: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val mime: String
)

@Serializable
data class ShopSyncImageRow(
    @SerialName("product_id") val productId: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("shop_id") val shopId: String? = null,
    @SerialName("product_deleted_at") val productDeletedAt: String? = null,
    @SerialName("version_id") val versionId: String,
    val status: String,
    @SerialName("finalized_at") val finalizedAt: String,
    val main: ShopSyncImageVariantRow,
    val thumb: ShopSyncImageVariantRow
)

sealed interface ShopSyncRows {
    val size: Int
    fun ids(): List<String>

    data class Suppliers(val values: List<InventorySupplierRow>) : ShopSyncRows {
        override val size: Int get() = values.size
        override fun ids(): List<String> = values.map { it.id }
    }

    data class Categories(val values: List<InventoryCategoryRow>) : ShopSyncRows {
        override val size: Int get() = values.size
        override fun ids(): List<String> = values.map { it.id }
    }

    data class Products(val values: List<InventoryProductRow>) : ShopSyncRows {
        override val size: Int get() = values.size
        override fun ids(): List<String> = values.map { it.id }
    }

    data class Prices(val values: List<InventoryProductPriceRow>) : ShopSyncRows {
        override val size: Int get() = values.size
        override fun ids(): List<String> = values.map { it.id }
    }

    data class History(val values: List<SharedSheetSessionRecord>) : ShopSyncRows {
        override val size: Int get() = values.size
        override fun ids(): List<String> = values.map { it.remoteId }
    }

    data class Images(val values: List<ShopSyncImageRow>) : ShopSyncRows {
        override val size: Int get() = values.size
        override fun ids(): List<String> = values.map { it.productId }
    }
}

data class ShopSyncRecoveryPage(
    val schemaVersion: String,
    val shopId: String,
    val scope: ShopSyncScope,
    val domain: ShopSyncRowDomain,
    val snapshotEventMaxId: String,
    val currentScopeEventMaxId: String,
    val baselineDomainEventMaxId: String,
    val pageDomainEventMaxId: String,
    val domainScope: String,
    val pageLimit: Int,
    val rows: ShopSyncRows,
    val nextAfterId: String?,
    val hasMore: Boolean,
    /** Byte UTF-8 del body RPC, misurati prima del decode JSON. */
    val responseBytes: Long,
    /** Massimo body JSON di una singola riga; usato per il payload history. */
    val largestRowBytes: Long = 0L
)

data class ShopSyncEventPage(
    val schemaVersion: String,
    val shopId: String,
    val scope: ShopSyncScope,
    /** Canonical strings captured by the bootstrap page and retained on every continuation. */
    val scopeEventMaxId: String,
    val asOfEventMaxId: String,
    val asOfDomainEventMaxIds: Map<String, String>,
    val pageLimit: Int,
    val rows: List<SyncEventRemoteRow>,
    val nextAfterId: Long?,
    val hasMore: Boolean,
    /** Raw RPC body bytes; used to retain recovery-tail transfer bounds. */
    val responseBytes: Long = 1L
)

data class ShopSyncTargetedRows(
    val schemaVersion: String,
    val shopId: String,
    val scope: ShopSyncScope,
    val domain: ShopSyncRowDomain,
    val asOfEventMaxId: String,
    val currentScopeEventMaxId: String,
    val minimumDomainEventMaxId: String,
    val materializedDomainEventMaxId: String,
    val domainScope: String,
    val requestedCount: Int,
    val rows: ShopSyncRows,
    val missingIds: List<String>,
    /** Raw RPC body bytes; used to retain recovery-tail transfer bounds. */
    val responseBytes: Long = 1L,
    /** Raw largest row bytes for the stricter History payload guard. */
    val largestRowBytes: Long = 0L
)

data class ShopSyncRpcContext(
    val accountId: String,
    val shopId: String,
    val deviceIdentifier: String,
    /** Opaque server scope. It is compared, never reconstructed client-side. */
    val expectedScope: ShopSyncScope? = null,
    /** Checkpoint/marker baseline string. Never serialize as a JSON number. */
    val verifiedBaselineId: String = "0",
    val expectedBaselineScopeKey: String? = null,
    /** Required by recovery/targeted pages and by event continuations. */
    val expectedEventMaxId: String? = null,
    val expectedDomainEventMaxId: String? = null
)

/** Authoritative server half of the no-work proof; local state is checked by the coordinator. */
@Serializable
data class ShopSyncConvergenceMarker(
    val schemaVersion: String,
    val status: String,
    val shopId: String,
    val scope: ShopSyncScope,
    val syncEvents: ShopSyncEventCheckpoint,
    val catalog: ShopSyncCatalogCheckpoint,
    val prices: ShopSyncDomainCheckpoint,
    val history: ShopSyncDomainCheckpoint,
    val images: ShopSyncDomainCheckpoint,
    val integrity: ShopSyncMarkerIntegrity,
    val checkpointDigest: String,
    val serverNoWorkEligible: Boolean,
    val markerDigest: String
)

@Serializable
data class ShopSyncMarkerIntegrity(
    val totalViolationCount: Long
)

interface ShopSyncReadRemoteDataSource {
    val isConfigured: Boolean

    suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint>

    suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker>

    suspend fun recoveryPage(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        afterId: String?,
        limit: Int = 250
    ): Result<ShopSyncRecoveryPage>

    suspend fun eventPage(
        context: ShopSyncRpcContext,
        afterId: Long,
        limit: Int = 250
    ): Result<ShopSyncEventPage>

    suspend fun rowsByIds(
        context: ShopSyncRpcContext,
        domain: ShopSyncRowDomain,
        ids: List<String>
    ): Result<ShopSyncTargetedRows>
}

class ShopSyncContractException(val code: String) : IllegalStateException(code)

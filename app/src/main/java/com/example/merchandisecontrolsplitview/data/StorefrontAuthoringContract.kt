package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val STOREFRONT_AUTHORING_READ_RPC =
    "storefront_publications_authoring_read_v1"
private const val STOREFRONT_AUTHORING_MUTATE_RPC =
    "storefront_publication_authoring_mutate_v1"
private const val STOREFRONT_AUTHORING_SUMMARY_RPC =
    "storefront_publications_authoring_summary_v1"
private const val STOREFRONT_AUTHORING_BIND_SESSION_RPC =
    "storefront_authoring_bind_android_session_v1"
private val STOREFRONT_UUID = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
internal fun isStorefrontRemoteIdentity(value: String): Boolean = STOREFRONT_UUID.matches(value)

enum class StorefrontPublicationStatus(val wireName: String) {
    UNPUBLISHED("unpublished"),
    DRAFT("draft"),
    SCHEDULED("scheduled"),
    PUBLISHED("published"),
    HIDDEN("paused"),
    ARCHIVED("ended");

    companion object {
        fun fromWire(value: String?): StorefrontPublicationStatus = when (value) {
            "draft" -> DRAFT
            "scheduled" -> SCHEDULED
            "published" -> PUBLISHED
            "paused" -> HIDDEN
            "ended" -> ARCHIVED
            else -> UNPUBLISHED
        }
    }
}

enum class StorefrontMutationOperation(val wireName: String) {
    SAVE_DRAFT("save_draft"),
    PUBLISH("publish"),
    SCHEDULE("schedule"),
    HIDE("hide"),
    ARCHIVE("archive")
}

enum class StorefrontSummaryFilter(val wireName: String) {
    ALL("all"),
    PUBLISHED("published"),
    UNPUBLISHED("unpublished"),
    DRAFT("draft"),
    SCHEDULED("scheduled"),
    HIDDEN("hidden"),
    NEEDS_UPDATE("needs_update")
}

enum class StorefrontAvailability(val wireName: String) {
    AVAILABLE("available"),
    LOW_STOCK("low_stock"),
    UNAVAILABLE("unavailable"),
    RESERVATION_ONLY("reservation_only"),
    PICKUP_ONLY("pickup_only"),
    DELIVERY_ONLY("delivery_only");

    companion object {
        fun fromWire(value: String?): StorefrontAvailability =
            entries.firstOrNull { it.wireName == value } ?: AVAILABLE
    }
}

@Serializable
data class StorefrontPublication(
    val publicationId: String,
    val sourceProductId: String,
    val status: String,
    val publicName: String,
    val publicDescription: String? = null,
    val storefrontCategoryId: String? = null,
    val publicBrand: String? = null,
    val publicPrice: Long,
    val compareAtPrice: Long? = null,
    val priceSourceMode: String = "override",
    val promotionStartsAt: String? = null,
    val promotionEndsAt: String? = null,
    val featured: Boolean = false,
    val homeOrder: Long = 0,
    val pickupEnabled: Boolean = false,
    val deliveryEnabled: Boolean = false,
    val reservationEnabled: Boolean = false,
    val availability: String = "available",
    val publicImageId: String? = null,
    val publicImageThumbnailUrl: String? = null,
    val publicImageDetailUrl: String? = null,
    val version: Long,
    val updatedAt: String,
    val mutationSource: String = "system",
    val changedFields: List<String> = emptyList()
) {
    val publicationStatus: StorefrontPublicationStatus
        get() = StorefrontPublicationStatus.fromWire(status)

    /** Projection preview esplicitamente allowlisted: nessun dato operativo interno. */
    fun toPublicPreviewPayload(): JsonObject = buildJsonObject {
        put("publicationId", publicationId)
        put("status", status)
        put("name", publicName)
        put("description", publicDescription?.let(::JsonPrimitive) ?: JsonNull)
        put("categoryId", storefrontCategoryId?.let(::JsonPrimitive) ?: JsonNull)
        put("brand", publicBrand?.let(::JsonPrimitive) ?: JsonNull)
        put("price", publicPrice)
        put("compareAtPrice", compareAtPrice?.let(::JsonPrimitive) ?: JsonNull)
        put("featured", featured)
        put("homeOrder", homeOrder)
        put("pickupEnabled", pickupEnabled)
        put("deliveryEnabled", deliveryEnabled)
        put("reservationEnabled", reservationEnabled)
        put("availability", availability)
        put("imageId", publicImageId?.let(::JsonPrimitive) ?: JsonNull)
        put("version", version)
        put("updatedAt", updatedAt)
    }
}

@Serializable
data class StorefrontCategory(
    val categoryId: String,
    val sourceCategoryId: String? = null,
    val publicName: String,
    val status: String,
    val updatedAt: String
)

data class StorefrontEditorDraft(
    val publicName: String = "",
    val publicDescription: String = "",
    val storefrontCategoryId: String? = null,
    val publicBrand: String = "",
    val publicPrice: Long? = null,
    val compareAtPrice: Long? = null,
    val priceSourceMode: String = "override",
    val promotionStartsAt: String? = null,
    val promotionEndsAt: String? = null,
    val featured: Boolean = false,
    val homeOrder: Long = 0,
    val pickupEnabled: Boolean = true,
    val deliveryEnabled: Boolean = false,
    val reservationEnabled: Boolean = false,
    val availability: StorefrontAvailability = StorefrontAvailability.AVAILABLE,
    val publicImageId: String? = null
) {
    companion object {
        fun fromPublication(value: StorefrontPublication) = StorefrontEditorDraft(
            publicName = value.publicName,
            publicDescription = value.publicDescription.orEmpty(),
            storefrontCategoryId = value.storefrontCategoryId,
            publicBrand = value.publicBrand.orEmpty(),
            publicPrice = value.publicPrice,
            compareAtPrice = value.compareAtPrice,
            priceSourceMode = value.priceSourceMode,
            promotionStartsAt = value.promotionStartsAt,
            promotionEndsAt = value.promotionEndsAt,
            featured = value.featured,
            homeOrder = value.homeOrder,
            pickupEnabled = value.pickupEnabled,
            deliveryEnabled = value.deliveryEnabled,
            reservationEnabled = value.reservationEnabled,
            availability = StorefrontAvailability.fromWire(value.availability),
            publicImageId = value.publicImageId
        )
    }
}

enum class StorefrontDraftField {
    PUBLIC_NAME,
    PUBLIC_DESCRIPTION,
    STOREFRONT_CATEGORY,
    PUBLIC_BRAND,
    PUBLIC_PRICE,
    COMPARE_AT_PRICE,
    PRICE_SOURCE_MODE,
    PROMOTION_START,
    PROMOTION_END,
    FEATURED,
    HOME_ORDER,
    PICKUP,
    DELIVERY,
    RESERVATION,
    AVAILABILITY,
    PUBLIC_IMAGE
}

fun storefrontChangedDraftFields(
    before: StorefrontEditorDraft,
    after: StorefrontEditorDraft
): Set<StorefrontDraftField> = buildSet {
    if (before.publicName != after.publicName) add(StorefrontDraftField.PUBLIC_NAME)
    if (before.publicDescription != after.publicDescription) add(StorefrontDraftField.PUBLIC_DESCRIPTION)
    if (before.storefrontCategoryId != after.storefrontCategoryId) add(StorefrontDraftField.STOREFRONT_CATEGORY)
    if (before.publicBrand != after.publicBrand) add(StorefrontDraftField.PUBLIC_BRAND)
    if (before.publicPrice != after.publicPrice) add(StorefrontDraftField.PUBLIC_PRICE)
    if (before.compareAtPrice != after.compareAtPrice) add(StorefrontDraftField.COMPARE_AT_PRICE)
    if (before.priceSourceMode != after.priceSourceMode) add(StorefrontDraftField.PRICE_SOURCE_MODE)
    if (before.promotionStartsAt != after.promotionStartsAt) add(StorefrontDraftField.PROMOTION_START)
    if (before.promotionEndsAt != after.promotionEndsAt) add(StorefrontDraftField.PROMOTION_END)
    if (before.featured != after.featured) add(StorefrontDraftField.FEATURED)
    if (before.homeOrder != after.homeOrder) add(StorefrontDraftField.HOME_ORDER)
    if (before.pickupEnabled != after.pickupEnabled) add(StorefrontDraftField.PICKUP)
    if (before.deliveryEnabled != after.deliveryEnabled) add(StorefrontDraftField.DELIVERY)
    if (before.reservationEnabled != after.reservationEnabled) add(StorefrontDraftField.RESERVATION)
    if (before.availability != after.availability) add(StorefrontDraftField.AVAILABILITY)
    if (before.publicImageId != after.publicImageId) add(StorefrontDraftField.PUBLIC_IMAGE)
}

fun StorefrontEditorDraft.overlayChangedFields(
    local: StorefrontEditorDraft,
    fields: Set<StorefrontDraftField>
): StorefrontEditorDraft = copy(
    publicName = if (StorefrontDraftField.PUBLIC_NAME in fields) local.publicName else publicName,
    publicDescription = if (StorefrontDraftField.PUBLIC_DESCRIPTION in fields) local.publicDescription else publicDescription,
    storefrontCategoryId = if (StorefrontDraftField.STOREFRONT_CATEGORY in fields) local.storefrontCategoryId else storefrontCategoryId,
    publicBrand = if (StorefrontDraftField.PUBLIC_BRAND in fields) local.publicBrand else publicBrand,
    publicPrice = if (StorefrontDraftField.PUBLIC_PRICE in fields) local.publicPrice else publicPrice,
    compareAtPrice = if (StorefrontDraftField.COMPARE_AT_PRICE in fields) local.compareAtPrice else compareAtPrice,
    priceSourceMode = if (StorefrontDraftField.PRICE_SOURCE_MODE in fields) local.priceSourceMode else priceSourceMode,
    promotionStartsAt = if (StorefrontDraftField.PROMOTION_START in fields) local.promotionStartsAt else promotionStartsAt,
    promotionEndsAt = if (StorefrontDraftField.PROMOTION_END in fields) local.promotionEndsAt else promotionEndsAt,
    featured = if (StorefrontDraftField.FEATURED in fields) local.featured else featured,
    homeOrder = if (StorefrontDraftField.HOME_ORDER in fields) local.homeOrder else homeOrder,
    pickupEnabled = if (StorefrontDraftField.PICKUP in fields) local.pickupEnabled else pickupEnabled,
    deliveryEnabled = if (StorefrontDraftField.DELIVERY in fields) local.deliveryEnabled else deliveryEnabled,
    reservationEnabled = if (StorefrontDraftField.RESERVATION in fields) local.reservationEnabled else reservationEnabled,
    availability = if (StorefrontDraftField.AVAILABILITY in fields) local.availability else availability,
    publicImageId = if (StorefrontDraftField.PUBLIC_IMAGE in fields) local.publicImageId else publicImageId
)

@Serializable
data class StorefrontAuthoringReadResponse(
    val ok: Boolean = false,
    val code: String = "backend_unavailable",
    @SerialName("shop_id") val shopId: String? = null,
    val rows: List<StorefrontPublication> = emptyList(),
    val categories: List<StorefrontCategory> = emptyList(),
    val pagination: StorefrontPagination = StorefrontPagination()
)

@Serializable
data class StorefrontPublicationListSummary(
    val sourceProductId: String,
    val status: String,
    val publicName: String? = null,
    val publicPrice: Long? = null,
    val storefrontCategoryId: String? = null,
    val publicImageId: String? = null,
    val version: Long? = null,
    val updatedAt: String? = null,
    val differsFromOperational: Boolean = false
) {
    val publicationStatus: StorefrontPublicationStatus
        get() = StorefrontPublicationStatus.fromWire(status)
}

@Serializable
data class StorefrontAuthoringSummaryResponse(
    val ok: Boolean = false,
    val code: String = "backend_unavailable",
    @SerialName("shop_id") val shopId: String? = null,
    val rows: List<StorefrontPublicationListSummary> = emptyList(),
    val pagination: StorefrontPagination = StorefrontPagination()
)

@Serializable
private data class StorefrontSessionBindingResponse(
    val ok: Boolean = false,
    val code: String = "backend_unavailable",
    val source: String? = null
)

@Serializable
data class StorefrontPagination(
    val page: Int = 1,
    val pageSize: Int = 100,
    val total: Int = 0,
    val totalPages: Int = 1
)

@Serializable
data class StorefrontAuthoringMutationResponse(
    val ok: Boolean = false,
    val code: String = "backend_unavailable",
    @SerialName("shop_id") val shopId: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    val idempotent: Boolean = false,
    val payload: StorefrontPublication? = null,
    val server: StorefrontPublication? = null
)

interface StorefrontAuthoringRemoteDataSource {
    val isConfigured: Boolean

    suspend fun read(
        shopId: String,
        sourceProductIds: List<String>? = null,
        status: StorefrontPublicationStatus? = null,
        page: Int = 1
    ): Result<StorefrontAuthoringReadResponse>

    suspend fun mutate(
        shopId: String,
        sourceProductId: String,
        operation: StorefrontMutationOperation,
        draft: StorefrontEditorDraft,
        expectedVersion: Long,
        idempotencyKey: String
    ): Result<StorefrontAuthoringMutationResponse>

    suspend fun readSummary(
        shopId: String,
        filter: StorefrontSummaryFilter,
        query: String? = null,
        sourceProductIds: List<String>? = null,
        page: Int = 1,
        pageSize: Int = 100
    ): Result<StorefrontAuthoringSummaryResponse> =
        Result.failure(UnsupportedOperationException("storefront_summary_unavailable"))
}

class SupabaseStorefrontAuthoringRemoteDataSource(
    private val client: SupabaseClient?
) : StorefrontAuthoringRemoteDataSource {
    override val isConfigured: Boolean get() = client != null

    override suspend fun read(
        shopId: String,
        sourceProductIds: List<String>?,
        status: StorefrontPublicationStatus?,
        page: Int
    ): Result<StorefrontAuthoringReadResponse> = runCatching {
        require(sourceProductIds == null || sourceProductIds.size in 1..100)
        require(sourceProductIds == null || sourceProductIds.distinct().size == sourceProductIds.size)
        require(page in 1..10_000)
        require(isStorefrontRemoteIdentity(shopId))
        require(sourceProductIds == null || sourceProductIds.all(::isStorefrontRemoteIdentity))
        requireNotNull(client) { "Supabase non configurato" }
            .postgrest
            .rpc(
                STOREFRONT_AUTHORING_READ_RPC,
                StorefrontAuthoringReadParams(
                    shopId = shopId,
                    sourceProductIds = sourceProductIds,
                    status = status?.takeIf { it != StorefrontPublicationStatus.UNPUBLISHED }
                        ?.wireName,
                    page = page
                )
            )
            .decodeAs<StorefrontAuthoringReadResponse>()
    }

    override suspend fun mutate(
        shopId: String,
        sourceProductId: String,
        operation: StorefrontMutationOperation,
        draft: StorefrontEditorDraft,
        expectedVersion: Long,
        idempotencyKey: String
    ): Result<StorefrontAuthoringMutationResponse> = runCatching {
        require(expectedVersion >= 0)
        require(isStorefrontRemoteIdentity(shopId))
        require(isStorefrontRemoteIdentity(sourceProductId))
        require(isStorefrontRemoteIdentity(idempotencyKey))
        val configured = requireNotNull(client) { "Supabase non configurato" }
        val binding = configured.postgrest
            .rpc(
                STOREFRONT_AUTHORING_BIND_SESSION_RPC
            )
            .decodeAs<StorefrontSessionBindingResponse>()
        require(binding.ok && binding.source == "android") { binding.code }
        configured.postgrest
            .rpc(
                STOREFRONT_AUTHORING_MUTATE_RPC,
                StorefrontAuthoringMutateParams(
                    shopId = shopId,
                    operation = operation.wireName,
                    payload = storefrontMutationPayload(sourceProductId, operation, draft),
                    idempotencyKey = idempotencyKey,
                    expectedVersion = expectedVersion
                )
            )
            .decodeAs<StorefrontAuthoringMutationResponse>()
    }

    override suspend fun readSummary(
        shopId: String,
        filter: StorefrontSummaryFilter,
        query: String?,
        sourceProductIds: List<String>?,
        page: Int,
        pageSize: Int
    ): Result<StorefrontAuthoringSummaryResponse> = runCatching {
        require(isStorefrontRemoteIdentity(shopId))
        require(query == null || query.trim().length <= 120)
        require(sourceProductIds == null || sourceProductIds.size in 1..100)
        require(sourceProductIds == null || sourceProductIds.distinct().size == sourceProductIds.size)
        require(sourceProductIds == null || sourceProductIds.all(::isStorefrontRemoteIdentity))
        require(page in 1..10_000)
        require(pageSize in 1..100)
        requireNotNull(client) { "Supabase non configurato" }
            .postgrest
            .rpc(
                STOREFRONT_AUTHORING_SUMMARY_RPC,
                StorefrontAuthoringSummaryParams(
                    shopId = shopId,
                    filter = filter.wireName,
                    query = query?.trim()?.takeIf(String::isNotEmpty),
                    sourceProductIds = sourceProductIds,
                    page = page,
                    pageSize = pageSize
                )
            )
            .decodeAs<StorefrontAuthoringSummaryResponse>()
    }
}

@Serializable
private data class StorefrontAuthoringReadParams(
    @SerialName("p_shop_id") val shopId: String,
    @SerialName("p_source_product_ids") val sourceProductIds: List<String>? = null,
    @SerialName("p_status") val status: String? = null,
    @SerialName("p_page") val page: Int,
    @SerialName("p_page_size") val pageSize: Int = 100
)

@Serializable
private data class StorefrontAuthoringMutateParams(
    @SerialName("p_shop_id") val shopId: String,
    @SerialName("p_operation") val operation: String,
    @SerialName("p_payload") val payload: JsonObject,
    @SerialName("p_idempotency_key") val idempotencyKey: String,
    @SerialName("p_expected_version") val expectedVersion: Long
)

@Serializable
private data class StorefrontAuthoringSummaryParams(
    @SerialName("p_shop_id") val shopId: String,
    @SerialName("p_filter") val filter: String,
    @SerialName("p_query") val query: String? = null,
    @SerialName("p_source_product_ids") val sourceProductIds: List<String>? = null,
    @SerialName("p_page") val page: Int,
    @SerialName("p_page_size") val pageSize: Int
)

internal fun storefrontMutationPayload(
    sourceProductId: String,
    operation: StorefrontMutationOperation,
    draft: StorefrontEditorDraft
): JsonObject = buildJsonObject {
    put("sourceProductId", sourceProductId)
    if (operation == StorefrontMutationOperation.HIDE ||
        operation == StorefrontMutationOperation.ARCHIVE
    ) return@buildJsonObject
    put("publicName", draft.publicName.trim())
    put("publicDescription", draft.publicDescription.trim())
    put("storefrontCategoryId", draft.storefrontCategoryId?.let(::JsonPrimitive) ?: JsonNull)
    put("publicBrand", draft.publicBrand.trim())
    put("publicPrice", draft.publicPrice?.let(::JsonPrimitive) ?: JsonNull)
    put("compareAtPrice", draft.compareAtPrice?.let(::JsonPrimitive) ?: JsonNull)
    put("priceSourceMode", draft.priceSourceMode)
    put("promotionStartsAt", draft.promotionStartsAt?.let(::JsonPrimitive) ?: JsonNull)
    put("promotionEndsAt", draft.promotionEndsAt?.let(::JsonPrimitive) ?: JsonNull)
    put("featured", draft.featured)
    put("homeOrder", draft.homeOrder)
    put("pickupEnabled", draft.pickupEnabled)
    put("deliveryEnabled", draft.deliveryEnabled)
    put("reservationEnabled", draft.reservationEnabled)
    put("availability", draft.availability.wireName)
    put("publicImageId", draft.publicImageId?.let(::JsonPrimitive) ?: JsonNull)
}

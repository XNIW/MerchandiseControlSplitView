package com.example.merchandisecontrolsplitview.productimage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val PRODUCT_IMAGE_MAIN_MAX_SIDE = 1600
internal const val PRODUCT_IMAGE_MAIN_TARGET_BYTES = 750 * 1024
internal const val PRODUCT_IMAGE_MAIN_MAX_BYTES = 1024 * 1024
internal const val PRODUCT_IMAGE_THUMB_MAX_SIDE = 384
internal const val PRODUCT_IMAGE_THUMB_MAX_BYTES = 90 * 1024
internal const val PRODUCT_IMAGE_INPUT_MAX_BYTES = 25 * 1024 * 1024
internal const val PRODUCT_IMAGE_INPUT_MAX_PIXELS = 64_000_000L
internal const val PRODUCT_IMAGE_API_BODY_MAX_BYTES = 64 * 1024

internal val PRODUCT_IMAGE_UUID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)
internal val PRODUCT_IMAGE_CACHE_SCOPE_PATTERN = Regex("^[0-9a-f]{64}$")
internal val PRODUCT_IMAGE_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

enum class ProductImageVariant(val wireName: String, val maxBytes: Int) {
    MAIN("main", PRODUCT_IMAGE_MAIN_MAX_BYTES),
    THUMB("thumb", PRODUCT_IMAGE_THUMB_MAX_BYTES)
}

data class ProductImageMetadata(
    val bytes: Int,
    val height: Int,
    val mimeType: String = "image/jpeg",
    val sha256: String,
    val width: Int
)

data class PreparedProductImageVariant(
    val bytes: ByteArray,
    val metadata: ProductImageMetadata
)

data class PreparedProductImage(
    val main: PreparedProductImageVariant,
    val thumb: PreparedProductImageVariant
)

data class ProductImageReference(
    val accountScope: String,
    val shopId: String,
    val productId: String,
    val versionId: String,
    val variant: ProductImageVariant
)

data class ProductImageLoadRequest(
    val localProductId: Long,
    val variant: ProductImageVariant,
    val expectedVersionId: String?
)

data class ProductImageBatchItem(
    val request: ProductImageLoadRequest,
    val result: ProductImageLoadResult? = null,
    val errorCode: String? = null
)

enum class ProductImageLoadSource { CACHE, NETWORK }

sealed interface ProductImageLoadResult {
    data object Absent : ProductImageLoadResult
    data class Ready(
        val bytes: ByteArray,
        val source: ProductImageLoadSource,
        val versionId: String
    ) : ProductImageLoadResult
}

data class ProductImageMutationResult(
    val status: String,
    val versionId: String?,
    val imageUpdatedAt: String?
)

enum class ProductImageMutationPhase {
    PREPROCESSING,
    UPLOAD_MAIN,
    UPLOAD_THUMB,
    FINALIZING,
    COMPLETED
}

class ProductImageException(val code: String) : Exception(code)

@Serializable
internal data class ProductImageUploadMetadataBody(
    val bytes: Int,
    val height: Int,
    val mimeType: String,
    val sha256: String,
    val width: Int
)

@Serializable
internal data class ProductImageIntentBody(
    val main: ProductImageUploadMetadataBody,
    val productId: String,
    val shopId: String,
    val thumb: ProductImageUploadMetadataBody
)

@Serializable
internal data class ProductImageFinalizeBody(
    val productId: String,
    val shopId: String,
    val versionId: String
)

@Serializable
internal data class ProductImageRemoveBody(
    val expectedVersionId: String,
    val productId: String,
    val shopId: String
)

@Serializable
internal data class ProductImageReadBody(
    val refs: List<ProductImageReadRefBody>,
    val shopId: String
)

@Serializable
internal data class ProductImageReadRefBody(
    val productId: String,
    val variant: String,
    val versionId: String
)

@Serializable
internal data class ProductImageIntentResponse(
    val cacheScope: String? = null,
    val expiresAt: String? = null,
    val mainUploadUrl: String? = null,
    val ok: Boolean = false,
    val status: String? = null,
    val thumbUploadUrl: String? = null,
    val versionId: String? = null
)

@Serializable
internal data class ProductImageFinalizeResponse(
    val imageUpdatedAt: String? = null,
    val ok: Boolean = false,
    val status: String? = null,
    val versionId: String? = null
)

@Serializable
internal data class ProductImageRemoveResponse(
    val cleanupStatus: String? = null,
    val currentImageVersionId: String? = PRODUCT_IMAGE_MISSING_FIELD,
    val imageUpdatedAt: String? = null,
    val ok: Boolean = false,
    val operation: String? = null,
    val productId: String? = null,
    val shopId: String? = null,
    val status: String? = null,
    val versionId: String? = null
)

private const val PRODUCT_IMAGE_MISSING_FIELD = "__missing__"

@Serializable
internal data class ProductImageReadResponse(
    val cacheScope: String? = null,
    val items: List<ProductImageReadItemResponse> = emptyList(),
    val ok: Boolean = false
)

@Serializable
internal data class ProductImageReadItemResponse(
    val expiresAt: String? = null,
    val productId: String,
    val signedUrl: String? = null,
    val status: String,
    val variant: String,
    val versionId: String
)

internal interface ProductImageRemoteGateway {
    val isConfigured: Boolean

    suspend fun createIntent(
        accessToken: String,
        body: ProductImageIntentBody
    ): ProductImageIntentResponse

    suspend fun finalizeImage(
        accessToken: String,
        body: ProductImageFinalizeBody
    ): ProductImageFinalizeResponse

    suspend fun removeImage(
        accessToken: String,
        body: ProductImageRemoveBody
    ): ProductImageRemoveResponse

    suspend fun readUrls(
        accessToken: String,
        body: ProductImageReadBody
    ): ProductImageReadResponse

    suspend fun putSignedJpeg(signedUrl: String, bytes: ByteArray)

    suspend fun downloadSignedJpeg(
        signedUrl: String,
        variant: ProductImageVariant
    ): ByteArray

    fun close()
}

internal fun ProductImageMetadata.toBody() = ProductImageUploadMetadataBody(
    bytes = bytes,
    height = height,
    mimeType = mimeType,
    sha256 = sha256,
    width = width
)

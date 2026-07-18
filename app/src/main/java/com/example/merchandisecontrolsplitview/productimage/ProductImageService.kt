package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.SelectedShop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class ProductImageService internal constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val api: ProductImageApiClient,
    private val accountIdProvider: () -> String?,
    private val selectedShopProvider: () -> SelectedShop?,
    private val accessTokenProvider: () -> String?,
    private val processor: ProductImageProcessor = ProductImageProcessor(),
    private val cache: ProductImageCache = ProductImageCache(context),
    private val networkAvailable: () -> Boolean = { context.hasValidatedNetwork() }
) {
    val isConfigured: Boolean get() = api.isConfigured

    fun canWriteNow(): Boolean =
        api.isConfigured &&
            !accountIdProvider().isNullOrBlank() &&
            selectedShopProvider()?.let { it.canWrite && it.shopId.isNotBlank() } == true

    suspend fun load(localProductId: Long, variant: ProductImageVariant): ProductImageLoadResult =
        withContext(Dispatchers.IO) {
            val product = database.productDao().getById(localProductId)
                ?: return@withContext ProductImageLoadResult.Absent
            val remoteId = database.productRemoteRefDao().getByProductId(localProductId)?.remoteId
            val versionId = product.primaryImageVersionId
            if (versionId == null) {
                if (remoteId != null) {
                    val operation = try {
                        resolveOperationContext(requireWrite = false)
                    } catch (_: ProductImageException) {
                        null
                    }
                    if (operation != null) {
                        try {
                            cache.purgeProduct(operation.accountScope, operation.shop.shopId, remoteId)
                        } catch (_: ProductImageException) {
                            // Nessuna immagine visibile: il purge locale resta best-effort.
                        }
                    }
                }
                return@withContext ProductImageLoadResult.Absent
            }
            if (remoteId == null) throw ProductImageException("image_product_not_synced")
            val operation = resolveOperationContext(requireWrite = false)
            val reference = reference(operation, remoteId, versionId, variant)
            cache.read(reference)?.let { bytes ->
                return@withContext ProductImageLoadResult.Ready(
                    bytes = bytes,
                    source = ProductImageLoadSource.CACHE,
                    versionId = versionId
                )
            }
            if (!networkAvailable()) throw ProductImageException("image_offline_not_cached")
            val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
                ?: throw ProductImageException("image_session_missing")
            val readBody = ProductImageReadBody(
                shopId = operation.shop.shopId,
                refs = listOf(
                    ProductImageReadRefBody(
                        productId = remoteId,
                        variant = variant.wireName,
                        versionId = versionId
                    )
                )
            )
            val bytes = downloadProductImageWithOneAuthRefresh(
                resolveSignedUrl = {
                    val response = api.readUrls(token, readBody)
                    if (!response.ok || response.cacheScope != operation.accountScope) {
                        throw ProductImageException("image_read_contract_invalid")
                    }
                    val item = response.items.singleOrNull()
                        ?.takeIf {
                            it.productId == remoteId &&
                                it.versionId == versionId &&
                                it.variant == variant.wireName
                        }
                        ?: throw ProductImageException("image_read_contract_invalid")
                    item.signedUrl?.takeIf { item.status == "ready" && it.isNotBlank() }
                        ?: throw ProductImageException("image_not_found")
                },
                download = { signedUrl -> api.downloadSignedJpeg(signedUrl, variant) }
            )
            cache.write(reference, bytes)
            cache.purgeOtherVersions(
                operation.accountScope,
                operation.shop.shopId,
                remoteId,
                versionId
            )
            ProductImageLoadResult.Ready(
                bytes = bytes,
                source = ProductImageLoadSource.NETWORK,
                versionId = versionId
            )
        }

    suspend fun upload(localProductId: Long, sourceUri: Uri): ProductImageMutationResult {
        if (!networkAvailable()) throw ProductImageException("image_upload_requires_online")
        val operation = resolveOperationContext(requireWrite = true)
        val product = withContext(Dispatchers.IO) {
            database.productDao().getById(localProductId)
        } ?: throw ProductImageException("image_product_not_found")
        val remoteId = withContext(Dispatchers.IO) {
            database.productRemoteRefDao().getByProductId(localProductId)?.remoteId
        } ?: throw ProductImageException("image_product_not_synced")
        requireUuid(remoteId, "image_product_reference_invalid")
        val prepared = processor.prepare(context, sourceUri)
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw ProductImageException("image_session_missing")
        val intent = api.createIntent(
            accessToken = token,
            body = ProductImageIntentBody(
                main = prepared.main.metadata.toBody(),
                productId = remoteId,
                shopId = operation.shop.shopId,
                thumb = prepared.thumb.metadata.toBody()
            )
        )
        val versionId = intent.versionId
            ?.takeIf(PRODUCT_IMAGE_UUID_PATTERN::matches)
            ?: throw ProductImageException("image_intent_contract_invalid")
        if (!intent.ok || intent.cacheScope != operation.accountScope) {
            throw ProductImageException("image_intent_contract_invalid")
        }

        val mutation = when (intent.status) {
            "noop" -> ProductImageMutationResult(
                status = "noop",
                versionId = versionId,
                imageUpdatedAt = product.primaryImageUpdatedAt
            )

            "upload_required" -> {
                val mainUrl = intent.mainUploadUrl
                    ?: throw ProductImageException("image_intent_contract_invalid")
                val thumbUrl = intent.thumbUploadUrl
                    ?: throw ProductImageException("image_intent_contract_invalid")
                coroutineScope {
                    listOf(
                        async { api.putSignedJpeg(mainUrl, prepared.main.bytes) },
                        async { api.putSignedJpeg(thumbUrl, prepared.thumb.bytes) }
                    ).awaitAll()
                }
                val finalized = api.finalizeImage(
                    accessToken = token,
                    body = ProductImageFinalizeBody(
                        productId = remoteId,
                        shopId = operation.shop.shopId,
                        versionId = versionId
                    )
                )
                if (!finalized.ok ||
                    finalized.versionId != versionId ||
                    finalized.status !in setOf("finalized", "already_finalized")
                ) {
                    throw ProductImageException("image_finalize_contract_invalid")
                }
                val finalizedStatus = finalized.status
                    ?: throw ProductImageException("image_finalize_contract_invalid")
                ProductImageMutationResult(
                    status = finalizedStatus,
                    versionId = versionId,
                    imageUpdatedAt = finalized.imageUpdatedAt
                )
            }

            else -> throw ProductImageException("image_intent_contract_invalid")
        }

        withContext(Dispatchers.IO) {
            ensureContextStillCurrent(operation)
            database.productDao().updateRemoteImageReference(
                productId = localProductId,
                versionId = versionId,
                updatedAt = mutation.imageUpdatedAt
            )
            cachePrepared(operation, remoteId, versionId, prepared)
        }
        return mutation
    }

    suspend fun remove(localProductId: Long): ProductImageMutationResult {
        if (!networkAvailable()) throw ProductImageException("image_remove_requires_online")
        val operation = resolveOperationContext(requireWrite = true)
        val product = withContext(Dispatchers.IO) {
            database.productDao().getById(localProductId)
        } ?: throw ProductImageException("image_product_not_found")
        val versionId = product.primaryImageVersionId
            ?: return ProductImageMutationResult("already_removed", null, product.primaryImageUpdatedAt)
        requireUuid(versionId, "image_version_invalid")
        val remoteId = withContext(Dispatchers.IO) {
            database.productRemoteRefDao().getByProductId(localProductId)?.remoteId
        } ?: throw ProductImageException("image_product_not_synced")
        requireUuid(remoteId, "image_product_reference_invalid")
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw ProductImageException("image_session_missing")
        val response = api.removeImage(
            accessToken = token,
            body = ProductImageRemoveBody(
                expectedVersionId = versionId,
                productId = remoteId,
                shopId = operation.shop.shopId
            )
        )
        if (!response.ok ||
            response.operation != "remove" ||
            response.productId != remoteId ||
            response.shopId != operation.shop.shopId ||
            response.status !in setOf("removed", "already_removed") ||
            response.versionId != versionId ||
            response.currentImageVersionId != null
        ) {
            throw ProductImageException("image_remove_contract_invalid")
        }
        val removalStatus = response.status
            ?: throw ProductImageException("image_remove_contract_invalid")
        withContext(Dispatchers.IO) {
            ensureContextStillCurrent(operation)
            database.productDao().updateRemoteImageReference(
                productId = localProductId,
                versionId = null,
                updatedAt = response.imageUpdatedAt ?: product.primaryImageUpdatedAt
            )
            try {
                cache.purgeProduct(operation.accountScope, operation.shop.shopId, remoteId)
            } catch (_: ProductImageException) {
                // Rimozione server gia' conclusa: un errore cache locale non la annulla.
            }
        }
        return ProductImageMutationResult(
            status = removalStatus,
            versionId = null,
            imageUpdatedAt = response.imageUpdatedAt
        )
    }

    fun close() {
        api.close()
    }

    private fun cachePrepared(
        operation: OperationContext,
        productId: String,
        versionId: String,
        prepared: PreparedProductImage
    ) {
        try {
            cache.write(
                reference(operation, productId, versionId, ProductImageVariant.MAIN),
                prepared.main.bytes
            )
            cache.write(
                reference(operation, productId, versionId, ProductImageVariant.THUMB),
                prepared.thumb.bytes
            )
            cache.purgeOtherVersions(
                operation.accountScope,
                operation.shop.shopId,
                productId,
                versionId
            )
        } catch (_: ProductImageException) {
            // Cache best-effort: il record remoto finalizzato resta autorevole.
        }
    }

    private fun reference(
        operation: OperationContext,
        productId: String,
        versionId: String,
        variant: ProductImageVariant
    ) = ProductImageReference(
        accountScope = operation.accountScope,
        shopId = operation.shop.shopId,
        productId = productId,
        versionId = versionId,
        variant = variant
    )

    private fun resolveOperationContext(requireWrite: Boolean): OperationContext {
        if (!api.isConfigured) throw ProductImageException("image_api_not_configured")
        val accountId = accountIdProvider()?.takeIf { it.isNotBlank() }
            ?: throw ProductImageException("image_session_missing")
        requireUuid(accountId, "image_account_scope_invalid")
        val shop = selectedShopProvider()
            ?: throw ProductImageException("image_shop_missing")
        requireUuid(shop.shopId, "image_shop_invalid")
        if (requireWrite && !shop.canWrite) {
            throw ProductImageException("image_write_not_allowed")
        }
        return OperationContext(
            accountId = accountId,
            accountScope = cache.accountScope(accountId),
            shop = shop
        )
    }

    private fun ensureContextStillCurrent(operation: OperationContext) {
        val currentShop = selectedShopProvider()
        if (accountIdProvider() != operation.accountId || currentShop?.shopId != operation.shop.shopId) {
            throw ProductImageException("image_scope_changed")
        }
    }

    private fun requireUuid(value: String, code: String) {
        if (!PRODUCT_IMAGE_UUID_PATTERN.matches(value)) throw ProductImageException(code)
    }

    private data class OperationContext(
        val accountId: String,
        val accountScope: String,
        val shop: SelectedShop
    )
}

internal suspend fun downloadProductImageWithOneAuthRefresh(
    resolveSignedUrl: suspend () -> String,
    download: suspend (String) -> ByteArray
): ByteArray {
    val firstUrl = resolveSignedUrl()
    return try {
        download(firstUrl)
    } catch (error: ProductImageException) {
        if (error.code != "image_download_failed_401" &&
            error.code != "image_download_failed_403"
        ) {
            throw error
        }
        download(resolveSignedUrl())
    }
}

private fun Context.hasValidatedNetwork(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

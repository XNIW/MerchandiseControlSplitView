package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.SelectedShop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.LinkedHashMap

class ProductImageService internal constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val api: ProductImageRemoteGateway,
    private val accountIdProvider: () -> String?,
    private val selectedShopProvider: () -> SelectedShop?,
    private val accessTokenProvider: () -> String?,
    private val processor: ProductImageProcessor = ProductImageProcessor(),
    private val cache: ProductImageCache = ProductImageCache(context),
    private val networkAvailable: () -> Boolean = { context.hasValidatedNetwork() },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    private val inFlightMutex = Mutex()
    private val inFlightDownloads = mutableMapOf<ProductImageReference, CompletableDeferred<ByteArray>>()
    private val downloadSlots = Semaphore(PRODUCT_IMAGE_MAX_CONCURRENT_DOWNLOADS)
    private val signedUrlLeaseLock = Any()
    private val signedUrlLeases = LinkedHashMap<ProductImageReference, SignedUrlLease>(
        16,
        0.75f,
        true
    )

    val isConfigured: Boolean get() = api.isConfigured

    fun canWriteNow(): Boolean =
        api.isConfigured &&
            !accountIdProvider().isNullOrBlank() &&
            selectedShopProvider()?.let { it.canWrite && it.shopId.isNotBlank() } == true

    suspend fun load(localProductId: Long, variant: ProductImageVariant): ProductImageLoadResult {
        val expectedVersionId = withContext(Dispatchers.IO) {
            database.productDao().getById(localProductId)?.primaryImageVersionId
        }
        val item = loadBatch(
            listOf(ProductImageLoadRequest(localProductId, variant, expectedVersionId))
        ).single()
        item.errorCode?.let { throw ProductImageException(it) }
        return item.result ?: ProductImageLoadResult.Absent
    }

    suspend fun loadBatch(requests: List<ProductImageLoadRequest>): List<ProductImageBatchItem> =
        withContext(Dispatchers.IO) {
            val uniqueRequests = requests.distinct()
            if (uniqueRequests.isEmpty()) return@withContext emptyList()

            val completed = linkedMapOf<ProductImageLoadRequest, ProductImageBatchItem>()
            val misses = mutableListOf<ResolvedLoadRequest>()
            var operation: OperationContext? = null

            for (request in uniqueRequests) {
                val product = database.productDao().getById(request.localProductId)
                if (product == null) {
                    completed[request] = ProductImageBatchItem(
                        request = request,
                        result = ProductImageLoadResult.Absent
                    )
                    continue
                }
                val versionId = product.primaryImageVersionId
                if (versionId != request.expectedVersionId) {
                    completed[request] = ProductImageBatchItem(request, errorCode = "image_version_changed")
                    continue
                }
                if (versionId == null) {
                    completed[request] = ProductImageBatchItem(
                        request = request,
                        result = ProductImageLoadResult.Absent
                    )
                    continue
                }
                val remoteId = database.productRemoteRefDao()
                    .getByProductId(request.localProductId)
                    ?.remoteId
                if (remoteId == null) {
                    completed[request] = ProductImageBatchItem(request, errorCode = "image_product_not_synced")
                    continue
                }
                val currentOperation = operation ?: try {
                    resolveOperationContext(requireWrite = false).also { operation = it }
                } catch (error: ProductImageException) {
                    completed[request] = ProductImageBatchItem(request, errorCode = error.code)
                    continue
                }
                val ref = reference(currentOperation, remoteId, versionId, request.variant)
                val cached = cache.read(ref)
                if (cached != null) {
                    completed[request] = ProductImageBatchItem(
                        request = request,
                        result = ProductImageLoadResult.Ready(
                            bytes = cached,
                            source = ProductImageLoadSource.CACHE,
                            versionId = versionId
                        )
                    )
                } else {
                    misses += ResolvedLoadRequest(request, ref)
                }
            }

            if (misses.isNotEmpty()) {
                val currentOperation = operation
                    ?: throw ProductImageException("image_shop_missing")
                if (!networkAvailable()) {
                    misses.forEach { miss ->
                        completed[miss.request] = ProductImageBatchItem(
                            miss.request,
                            errorCode = "image_offline_not_cached"
                        )
                    }
                } else {
                    val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
                    if (token == null) {
                        misses.forEach { miss ->
                            completed[miss.request] = ProductImageBatchItem(
                                miss.request,
                                errorCode = "image_session_missing"
                            )
                        }
                    } else {
                        resolveNetworkMisses(currentOperation, token, misses, completed)
                    }
                }
            }

            uniqueRequests.map { request ->
                completed[request] ?: ProductImageBatchItem(
                    request = request,
                    errorCode = "image_request_failed"
                )
            }
        }

    private suspend fun resolveNetworkMisses(
        operation: OperationContext,
        token: String,
        misses: List<ResolvedLoadRequest>,
        completed: MutableMap<ProductImageLoadRequest, ProductImageBatchItem>
    ) {
        val acquisitions = inFlightMutex.withLock {
            misses.map { miss ->
                val existing = inFlightDownloads[miss.reference]
                if (existing != null) {
                    DownloadAcquisition(miss, existing, owner = false)
                } else {
                    val deferred = CompletableDeferred<ByteArray>()
                    inFlightDownloads[miss.reference] = deferred
                    DownloadAcquisition(miss, deferred, owner = true)
                }
            }
        }

        val owners = acquisitions.filter(DownloadAcquisition::owner)
        try {
            for (chunk in owners.chunked(PRODUCT_IMAGE_READ_BATCH_MAX)) {
                currentCoroutineContext().ensureActive()
                val signedUrls = try {
                    readSignedUrls(operation, token, chunk.map { it.miss.reference })
                } catch (error: CancellationException) {
                    chunk.forEach { acquisition ->
                        acquisition.deferred.completeExceptionally(error)
                        releaseInFlight(acquisition)
                    }
                    throw error
                } catch (error: ProductImageException) {
                    chunk.forEach { acquisition ->
                        acquisition.deferred.completeExceptionally(error)
                        releaseInFlight(acquisition)
                    }
                    continue
                }

                supervisorScope {
                    chunk.map { acquisition ->
                        async {
                            try {
                                val initialUrl = signedUrls[acquisition.miss.reference]
                                    ?: throw ProductImageException("image_read_contract_invalid")
                                var useInitialUrl = true
                                val bytes = try {
                                    downloadSlots.withPermit {
                                        downloadProductImageWithOneAuthRefresh(
                                            resolveSignedUrl = {
                                                if (useInitialUrl) {
                                                    useInitialUrl = false
                                                    initialUrl
                                                } else {
                                                    readSignedUrls(
                                                        operation,
                                                        token,
                                                        listOf(acquisition.miss.reference),
                                                        forceRefresh = setOf(acquisition.miss.reference)
                                                    ).getValue(acquisition.miss.reference)
                                                }
                                            },
                                            download = { signedUrl ->
                                                api.downloadSignedJpeg(
                                                    signedUrl,
                                                    acquisition.miss.reference.variant
                                                )
                                            }
                                        )
                                    }
                                } catch (error: ProductImageException) {
                                    if (error.isDownloadAuthorizationFailure()) {
                                        invalidateSignedUrlLease(acquisition.miss.reference)
                                    }
                                    throw error
                                }
                                if (!isStillCurrent(acquisition.miss)) {
                                    throw ProductImageException("image_version_changed")
                                }
                                try {
                                    cache.write(acquisition.miss.reference, bytes)
                                } catch (error: ProductImageException) {
                                    if (error.code == "image_cache_bytes_invalid") {
                                        throw ProductImageException("image_download_invalid")
                                    }
                                    throw error
                                }
                                cache.purgeOtherVersions(
                                    operation.accountScope,
                                    operation.shop.shopId,
                                    acquisition.miss.reference.productId,
                                    acquisition.miss.reference.versionId
                                )
                                acquisition.deferred.complete(bytes)
                            } catch (error: CancellationException) {
                                acquisition.deferred.completeExceptionally(error)
                                throw error
                            } catch (error: ProductImageException) {
                                acquisition.deferred.completeExceptionally(error)
                            } catch (_: Throwable) {
                                acquisition.deferred.completeExceptionally(
                                    ProductImageException("image_download_failed")
                                )
                            } finally {
                                releaseInFlight(acquisition)
                            }
                        }
                    }.awaitAll()
                }
            }

            for (acquisition in acquisitions) {
                val item = try {
                    val bytes = acquisition.deferred.await()
                    ProductImageBatchItem(
                        request = acquisition.miss.request,
                        result = ProductImageLoadResult.Ready(
                            bytes = bytes,
                            source = ProductImageLoadSource.NETWORK,
                            versionId = acquisition.miss.reference.versionId
                        )
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ProductImageException) {
                    ProductImageBatchItem(acquisition.miss.request, errorCode = error.code)
                } catch (_: Throwable) {
                    ProductImageBatchItem(
                        acquisition.miss.request,
                        errorCode = "image_download_failed"
                    )
                }
                completed[acquisition.miss.request] = item
            }
        } catch (error: CancellationException) {
            owners.forEach { acquisition ->
                if (!acquisition.deferred.isCompleted) {
                    acquisition.deferred.completeExceptionally(error)
                }
                releaseInFlight(acquisition)
            }
            throw error
        }
    }

    private suspend fun readSignedUrls(
        operation: OperationContext,
        token: String,
        references: List<ProductImageReference>,
        forceRefresh: Set<ProductImageReference> = emptySet()
    ): Map<ProductImageReference, String> {
        if (references.isEmpty() || references.size > PRODUCT_IMAGE_READ_BATCH_MAX) {
            throw ProductImageException("image_read_contract_invalid")
        }
        val uniqueReferences = references.distinct()
        if (uniqueReferences.size != references.size) {
            throw ProductImageException("image_read_contract_invalid")
        }

        val now = nowEpochMillis()
        val cachedUrls = linkedMapOf<ProductImageReference, String>()
        synchronized(signedUrlLeaseLock) {
            forceRefresh.forEach(signedUrlLeases::remove)
            uniqueReferences.forEach { reference ->
                val lease = signedUrlLeases[reference]
                if (lease != null && lease.expiresAtEpochMillis - SIGNED_URL_SAFETY_WINDOW_MS > now) {
                    cachedUrls[reference] = lease.signedUrl
                } else if (lease != null) {
                    signedUrlLeases.remove(reference)
                }
            }
        }
        val missingReferences = uniqueReferences.filterNot(cachedUrls::containsKey)
        if (missingReferences.isEmpty()) return cachedUrls

        val response = api.readUrls(
            token,
            ProductImageReadBody(
                shopId = operation.shop.shopId,
                refs = missingReferences.map { reference ->
                    ProductImageReadRefBody(
                        productId = reference.productId,
                        variant = reference.variant.wireName,
                        versionId = reference.versionId
                    )
                }
            )
        )
        if (!response.ok || response.cacheScope != operation.accountScope) {
            throw ProductImageException("image_read_contract_invalid")
        }
        val requestedByWireKey = missingReferences.associateBy { it.wireKey }
        val signedUrls = linkedMapOf<ProductImageReference, String>()
        val leasesToStore = mutableMapOf<ProductImageReference, SignedUrlLease>()
        for (item in response.items) {
            val reference = requestedByWireKey[Triple(item.productId, item.versionId, item.variant)]
                ?: throw ProductImageException("image_read_contract_invalid")
            val signedUrl = item.signedUrl
                ?.takeIf { item.status == "ready" && it.isNotBlank() }
                ?: throw ProductImageException("image_not_found")
            if (signedUrls.put(reference, signedUrl) != null) {
                throw ProductImageException("image_read_contract_invalid")
            }
            val expiresAtEpochMillis = item.expiresAt
                ?.let { value -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() }
            if (expiresAtEpochMillis != null &&
                expiresAtEpochMillis - SIGNED_URL_SAFETY_WINDOW_MS > now
            ) {
                leasesToStore[reference] = SignedUrlLease(signedUrl, expiresAtEpochMillis)
            }
        }
        if (signedUrls.size != missingReferences.size) {
            throw ProductImageException("image_read_contract_invalid")
        }
        synchronized(signedUrlLeaseLock) {
            leasesToStore.forEach { (reference, lease) ->
                signedUrlLeases[reference] = lease
            }
            val iterator = signedUrlLeases.entries.iterator()
            while (signedUrlLeases.size > SIGNED_URL_LEASE_MAX_ENTRIES && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return uniqueReferences.associateWith { reference ->
            cachedUrls[reference] ?: signedUrls.getValue(reference)
        }
    }

    private suspend fun releaseInFlight(acquisition: DownloadAcquisition) {
        inFlightMutex.withLock {
            if (inFlightDownloads[acquisition.miss.reference] === acquisition.deferred) {
                inFlightDownloads.remove(acquisition.miss.reference)
            }
        }
    }

    private suspend fun isStillCurrent(miss: ResolvedLoadRequest): Boolean =
        database.productDao().getById(miss.request.localProductId)?.primaryImageVersionId ==
            miss.reference.versionId

    suspend fun upload(
        localProductId: Long,
        sourceUri: Uri,
        onProgress: (ProductImageMutationPhase) -> Unit = {}
    ): ProductImageMutationResult {
        if (!networkAvailable()) throw ProductImageException("image_upload_requires_online")
        val operation = resolveOperationContext(requireWrite = true)
        val product = withContext(Dispatchers.IO) {
            database.productDao().getById(localProductId)
        } ?: throw ProductImageException("image_product_not_found")
        val remoteId = withContext(Dispatchers.IO) {
            database.productRemoteRefDao().getByProductId(localProductId)?.remoteId
        } ?: throw ProductImageException("image_product_not_synced")
        requireUuid(remoteId, "image_product_reference_invalid")
        onProgress(ProductImageMutationPhase.PREPROCESSING)
        currentCoroutineContext().ensureActive()
        val prepared = processor.prepare(context, sourceUri)
        currentCoroutineContext().ensureActive()
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
                onProgress(ProductImageMutationPhase.UPLOAD_MAIN)
                currentCoroutineContext().ensureActive()
                putSignedJpegWithOneTransientRetry(mainUrl, prepared.main.bytes)
                onProgress(ProductImageMutationPhase.UPLOAD_THUMB)
                currentCoroutineContext().ensureActive()
                putSignedJpegWithOneTransientRetry(thumbUrl, prepared.thumb.bytes)
                onProgress(ProductImageMutationPhase.FINALIZING)
                currentCoroutineContext().ensureActive()
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
            purgeSignedUrlLeases(operation.accountScope, operation.shop.shopId, remoteId)
            cachePrepared(operation, remoteId, versionId, prepared)
        }
        onProgress(ProductImageMutationPhase.COMPLETED)
        return mutation
    }

    suspend fun purgeScope(accountId: String, shopId: String?) = withContext(Dispatchers.IO) {
        val accountScope = cache.accountScope(accountId)
        purgeSignedUrlLeases(accountScope, shopId)
        if (shopId.isNullOrBlank()) {
            cache.purgeAccount(accountScope)
        } else {
            cache.purgeShop(accountScope, shopId)
        }
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
                purgeSignedUrlLeases(operation.accountScope, operation.shop.shopId, remoteId)
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

    fun trimMemory() {
        cache.trimMemory()
        synchronized(signedUrlLeaseLock) { signedUrlLeases.clear() }
    }

    private suspend fun putSignedJpegWithOneTransientRetry(
        signedUrl: String,
        bytes: ByteArray
    ) {
        try {
            api.putSignedJpeg(signedUrl, bytes)
        } catch (error: ProductImageException) {
            if (!error.isTransientUploadFailure()) throw error
            currentCoroutineContext().ensureActive()
            api.putSignedJpeg(signedUrl, bytes)
        }
    }

    private fun ProductImageException.isTransientUploadFailure(): Boolean {
        if (code == "image_upload_failed") return true
        val status = code.removePrefix("image_upload_failed_").toIntOrNull()
        return status != null && status in 500..599
    }

    private fun ProductImageException.isDownloadAuthorizationFailure(): Boolean =
        code == "image_download_failed_401" || code == "image_download_failed_403"

    private fun invalidateSignedUrlLease(reference: ProductImageReference) {
        synchronized(signedUrlLeaseLock) { signedUrlLeases.remove(reference) }
    }

    private fun purgeSignedUrlLeases(
        accountScope: String,
        shopId: String?,
        productId: String? = null
    ) {
        synchronized(signedUrlLeaseLock) {
            val iterator = signedUrlLeases.keys.iterator()
            while (iterator.hasNext()) {
                val reference = iterator.next()
                if (reference.accountScope == accountScope &&
                    (shopId == null || reference.shopId == shopId) &&
                    (productId == null || reference.productId == productId)
                ) {
                    iterator.remove()
                }
            }
        }
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

    private data class ResolvedLoadRequest(
        val request: ProductImageLoadRequest,
        val reference: ProductImageReference
    )

    private data class DownloadAcquisition(
        val miss: ResolvedLoadRequest,
        val deferred: CompletableDeferred<ByteArray>,
        val owner: Boolean
    )

    private data class SignedUrlLease(
        val signedUrl: String,
        val expiresAtEpochMillis: Long
    )

    private val ProductImageReference.wireKey: Triple<String, String, String>
        get() = Triple(productId, versionId, variant.wireName)

    private companion object {
        const val PRODUCT_IMAGE_READ_BATCH_MAX = 100
        const val PRODUCT_IMAGE_MAX_CONCURRENT_DOWNLOADS = 4
        const val SIGNED_URL_LEASE_MAX_ENTRIES = 256
        const val SIGNED_URL_SAFETY_WINDOW_MS = 30_000L
    }
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

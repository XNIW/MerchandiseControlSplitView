package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.room.withTransaction
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.SelectedShop
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeRuntimeGuard
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreGate
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreGateDecision
import com.example.merchandisecontrolsplitview.data.Task126OwnerStoreScope
import com.example.merchandisecontrolsplitview.data.Task126UnmanagedBusinessDataScopeRuntimeGuard
import com.example.merchandisecontrolsplitview.data.shopIdFromStoreScope
import com.example.merchandisecontrolsplitview.data.task126OwnerHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.security.MessageDigest
import java.util.LinkedHashMap

class ProductImageService internal constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val api: ProductImageRemoteGateway,
    private val accountIdProvider: () -> String?,
    private val selectedShopProvider: () -> SelectedShop?,
    private val accessTokenProvider: () -> String?,
    private val businessDataScopeAllowed: (String, SelectedShop?) -> Boolean = { _, _ -> true },
    private val businessDataScopeRuntimeGuard: Task126BusinessDataScopeRuntimeGuard =
        Task126UnmanagedBusinessDataScopeRuntimeGuard,
    private val allowBoundCacheRead: () -> Boolean = { false },
    private val processor: ProductImageProcessor = ProductImageProcessor(),
    private val cache: ProductImageCache = ProductImageCache(context),
    private val networkAvailable: () -> Boolean = { context.hasValidatedNetwork() },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    private val inFlightMutex = Mutex()
    private val inFlightDownloads = mutableMapOf<ProductImageReference, InFlightDownload>()
    private val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            accountIdProvider()?.let { accountId ->
                accountId.isNotBlank() &&
                    selectedShopProvider()?.let { shop ->
                        shop.canWrite &&
                            shop.shopId.isNotBlank() &&
                            businessDataScopeAllowed(accountId, shop)
                    } == true
            } == true

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
            val candidates = mutableListOf<ProductImageLoadRequest>()
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
                    completed[request] = ProductImageBatchItem(request, errorCode = "image_reference_invalid")
                    continue
                }
                if (versionId == null) {
                    completed[request] = ProductImageBatchItem(
                        request = request,
                        result = ProductImageLoadResult.Absent
                    )
                    continue
                }
                candidates += request
            }
            if (candidates.isEmpty()) {
                return@withContext uniqueRequests.map(completed::getValue)
            }

            val operation = try {
                resolveReadOperationContext()
            } catch (error: ProductImageException) {
                candidates.forEach { request ->
                    completed[request] = ProductImageBatchItem(request, errorCode = error.code)
                }
                return@withContext uniqueRequests.map(completed::getValue)
            }
            try {
                businessDataScopeRuntimeGuard.withBusinessDataScopeFlight(
                    ownerUserId = operation.accountId,
                    selectedShop = operation.shop
                ) {
                    loadBatchInBusinessDataScope(
                        uniqueRequests = uniqueRequests,
                        candidates = candidates,
                        completed = completed,
                        operation = operation
                    )
                }
            } catch (error: ProductImageException) {
                candidates.forEach { request ->
                    completed[request] = ProductImageBatchItem(request, errorCode = error.code)
                }
                uniqueRequests.map(completed::getValue)
            }
        }

    private suspend fun loadBatchInBusinessDataScope(
        uniqueRequests: List<ProductImageLoadRequest>,
        candidates: List<ProductImageLoadRequest>,
        completed: MutableMap<ProductImageLoadRequest, ProductImageBatchItem>,
        operation: OperationContext
    ): List<ProductImageBatchItem> {
        val misses = mutableListOf<ResolvedLoadRequest>()
        for (request in candidates) {
            requireReadOperationCurrent(operation)
            val product = database.productDao().getById(request.localProductId)
            requireReadOperationCurrent(operation)
            val versionId = product?.primaryImageVersionId
            if (versionId == null || versionId != request.expectedVersionId) {
                completed[request] = ProductImageBatchItem(request, errorCode = "image_reference_invalid")
                continue
            }
            val remoteId = database.productRemoteRefDao()
                .getByProductId(request.localProductId)
                ?.remoteId
            requireReadOperationCurrent(operation)
            if (remoteId == null) {
                completed[request] = ProductImageBatchItem(request, errorCode = "image_reference_invalid")
                continue
            }
            val ref = reference(operation, remoteId, versionId, request.variant)
            requireReadOperationCurrent(operation)
            val cached = cache.read(ref)
            requireReadOperationCurrent(operation)
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
            requireReadOperationCurrent(operation)
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
                    requireReadOperationCurrent(operation)
                    resolveNetworkMisses(operation, token, misses, completed)
                    requireReadOperationCurrent(operation)
                }
            }
        }

        requireReadOperationCurrent(operation)
        return uniqueRequests.map { request ->
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
                    existing.waiters += 1
                    DownloadAcquisition(miss, existing, owner = false)
                } else {
                    val entry = InFlightDownload(
                        deferred = CompletableDeferred(),
                        waiters = 1
                    )
                    inFlightDownloads[miss.reference] = entry
                    DownloadAcquisition(miss, entry, owner = true)
                }
            }
        }

        val owners = acquisitions.filter(DownloadAcquisition::owner)
        try {
            startProducerBatches(operation, token, owners)

            for (acquisition in acquisitions) {
                val item = try {
                    val bytes = acquisition.entry.deferred.await()
                    requireReadOperationCurrent(operation)
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
                } catch (_: ProductImageNotFoundException) {
                    ProductImageBatchItem(
                        request = acquisition.miss.request,
                        result = ProductImageLoadResult.Absent
                    )
                } catch (error: ProductImageException) {
                    ProductImageBatchItem(acquisition.miss.request, errorCode = error.code)
                } catch (_: Throwable) {
                    ProductImageBatchItem(
                        acquisition.miss.request,
                        errorCode = "image_request_failed"
                    )
                }
                completed[acquisition.miss.request] = item
            }
        } finally {
            acquisitions.forEach { releaseWaiter(it) }
        }
    }

    private fun startProducerBatches(
        operation: OperationContext,
        token: String,
        owners: List<DownloadAcquisition>
    ) {
        if (owners.isEmpty()) return
        producerScope.launch {
            val pending = owners.toMutableList()
            try {
                businessDataScopeRuntimeGuard.withBusinessDataScopeFlight(
                    ownerUserId = operation.accountId,
                    selectedShop = operation.shop
                ) {
                    owners.chunked(PRODUCT_IMAGE_READ_BATCH_MAX).forEach { chunk ->
                        try {
                            produceNetworkChunk(operation, token, chunk)
                        } catch (error: CancellationException) {
                            chunk.forEach { acquisition ->
                                acquisition.entry.deferred.completeExceptionally(error)
                            }
                            throw error
                        } catch (error: ProductImageException) {
                            chunk.forEach { acquisition ->
                                acquisition.entry.deferred.completeExceptionally(error)
                            }
                        } catch (_: Throwable) {
                            val error = ProductImageException("image_request_failed")
                            chunk.forEach { acquisition ->
                                acquisition.entry.deferred.completeExceptionally(error)
                            }
                        } finally {
                            chunk.forEach { acquisition ->
                                markProducerCompleted(acquisition)
                                pending.remove(acquisition)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                pending.forEach { acquisition ->
                    acquisition.entry.deferred.completeExceptionally(error)
                }
            } catch (error: ProductImageException) {
                pending.forEach { acquisition ->
                    acquisition.entry.deferred.completeExceptionally(error)
                }
            } catch (_: Throwable) {
                val error = ProductImageException("image_request_failed")
                pending.forEach { acquisition ->
                    acquisition.entry.deferred.completeExceptionally(error)
                }
            } finally {
                pending.forEach { markProducerCompleted(it) }
            }
        }
    }

    private suspend fun produceNetworkChunk(
        operation: OperationContext,
        token: String,
        chunk: List<DownloadAcquisition>
    ) {
        requireReadOperationCurrent(operation)
        val signedUrls = readSignedUrls(operation, token, chunk.map { it.miss.reference })
        supervisorScope {
            chunk.map { acquisition ->
                async {
                    try {
                        requireReadOperationCurrent(operation)
                        val initialLease = signedUrls[acquisition.miss.reference]
                        if (initialLease == null) {
                            acquisition.entry.deferred.completeExceptionally(
                                ProductImageNotFoundException
                            )
                            return@async
                        }
                        var useInitialUrl = true
                        var expectedMetadata = initialLease.metadata
                        val bytes = try {
                            downloadSlots.withPermit {
                                downloadProductImageWithOneAuthRefresh(
                                    resolveSignedUrl = {
                                        requireReadOperationCurrent(operation)
                                        if (useInitialUrl) {
                                            useInitialUrl = false
                                            initialLease.signedUrl
                                        } else {
                                            val refreshed = readSignedUrls(
                                                operation,
                                                token,
                                                listOf(acquisition.miss.reference),
                                                forceRefresh = setOf(acquisition.miss.reference)
                                            )[acquisition.miss.reference]
                                                ?: throw ProductImageNotFoundException
                                            if (refreshed.metadata != initialLease.metadata) {
                                                throw ProductImageException(
                                                    "image_read_contract_invalid"
                                                )
                                            }
                                            expectedMetadata = refreshed.metadata
                                            refreshed.signedUrl
                                        }
                                    },
                                    download = { signedUrl ->
                                        requireReadOperationCurrent(operation)
                                        val downloaded = api.downloadSignedJpeg(
                                            signedUrl,
                                            acquisition.miss.reference
                                        )
                                        requireReadOperationCurrent(operation)
                                        downloaded
                                    }
                                )
                            }
                        } catch (error: ProductImageException) {
                            if (error.isDownloadAuthorizationFailure()) {
                                invalidateSignedUrlLease(acquisition.miss.reference)
                            }
                            throw error
                        }
                        validateReadMetadata(
                            reference = acquisition.miss.reference,
                            bytes = bytes,
                            metadata = expectedMetadata
                        )
                        if (!isStillCurrent(operation, acquisition.miss)) {
                            throw ProductImageException("image_reference_invalid")
                        }
                        requireReadOperationCurrent(operation)
                        cache.write(
                            acquisition.miss.reference,
                            bytes,
                            expectedMetadata.toCacheMetadata()
                        )
                        requireReadOperationCurrent(operation)
                        cache.purgeOtherVersions(
                            operation.accountScope,
                            operation.shop.shopId,
                            acquisition.miss.reference.productId,
                            acquisition.miss.reference.versionId
                        )
                        requireReadOperationCurrent(operation)
                        acquisition.entry.deferred.complete(bytes)
                    } catch (error: CancellationException) {
                        acquisition.entry.deferred.completeExceptionally(error)
                        throw error
                    } catch (error: ProductImageNotFoundException) {
                        acquisition.entry.deferred.completeExceptionally(error)
                    } catch (error: ProductImageException) {
                        acquisition.entry.deferred.completeExceptionally(error)
                    } catch (_: Throwable) {
                        acquisition.entry.deferred.completeExceptionally(
                            ProductImageException("image_request_failed")
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun readSignedUrls(
        operation: OperationContext,
        token: String,
        references: List<ProductImageReference>,
        forceRefresh: Set<ProductImageReference> = emptySet()
    ): Map<ProductImageReference, ResolvedSignedUrl> {
        requireReadOperationCurrent(operation)
        if (references.isEmpty() || references.size > PRODUCT_IMAGE_READ_BATCH_MAX) {
            throw ProductImageException("image_read_contract_invalid")
        }
        val uniqueReferences = references.distinct()
        if (uniqueReferences.size != references.size) {
            throw ProductImageException("image_read_contract_invalid")
        }

        val now = nowEpochMillis()
        val cachedUrls = linkedMapOf<ProductImageReference, ResolvedSignedUrl>()
        synchronized(signedUrlLeaseLock) {
            forceRefresh.forEach(signedUrlLeases::remove)
            uniqueReferences.forEach { reference ->
                val lease = signedUrlLeases[reference]
                if (lease != null && lease.expiresAtEpochMillis - SIGNED_URL_SAFETY_WINDOW_MS > now) {
                    cachedUrls[reference] = ResolvedSignedUrl(
                        signedUrl = lease.signedUrl,
                        metadata = lease.metadata
                    )
                } else if (lease != null) {
                    signedUrlLeases.remove(reference)
                }
            }
        }
        val missingReferences = uniqueReferences.filterNot(cachedUrls::containsKey)
        if (missingReferences.isEmpty()) {
            requireReadOperationCurrent(operation)
            return cachedUrls
        }

        requireReadOperationCurrent(operation)
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
        requireReadOperationCurrent(operation)
        if (!response.ok || response.cacheScope != operation.accountScope) {
            throw ProductImageException("image_read_contract_invalid")
        }
        if (response.items.size != missingReferences.size) {
            throw ProductImageException("image_read_contract_invalid")
        }
        val signedUrls = linkedMapOf<ProductImageReference, ResolvedSignedUrl>()
        val leasesToStore = mutableMapOf<ProductImageReference, SignedUrlLease>()
        response.items.forEachIndexed { index, item ->
            val reference = missingReferences[index]
            if (item.productId != reference.productId ||
                item.versionId != reference.versionId ||
                item.variant != reference.variant.wireName
            ) {
                throw ProductImageException("image_read_contract_invalid")
            }
            if (item.status == "not_found") {
                if (item.signedUrl != null || item.expiresAt != null || item.metadata != null) {
                    throw ProductImageException("image_read_contract_invalid")
                }
                return@forEachIndexed
            }
            if (item.status != "ready") {
                throw ProductImageException("image_read_contract_invalid")
            }
            val signedUrl = item.signedUrl
                ?.takeIf { it.isNotBlank() }
                ?: throw ProductImageException("image_read_contract_invalid")
            val metadata = readMetadata(reference.variant, item)
            val resolved = ResolvedSignedUrl(signedUrl, metadata)
            if (signedUrls.put(reference, resolved) != null) {
                throw ProductImageException("image_read_contract_invalid")
            }
            val expiresAtEpochMillis = item.expiresAt
                ?.let { value -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() }
            if (expiresAtEpochMillis != null &&
                expiresAtEpochMillis - SIGNED_URL_SAFETY_WINDOW_MS > now
            ) {
                leasesToStore[reference] = SignedUrlLease(
                    signedUrl = signedUrl,
                    expiresAtEpochMillis = expiresAtEpochMillis,
                    metadata = metadata
                )
            }
        }
        requireReadOperationCurrent(operation)
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
        requireReadOperationCurrent(operation)
        return cachedUrls + signedUrls
    }

    private fun readMetadata(
        variant: ProductImageVariant,
        item: ProductImageReadItemResponse
    ): ProductImageReadMetadata {
        val metadata = item.metadata
            ?: throw ProductImageException("image_read_contract_invalid")
        val sha256 = metadata.sha256?.takeIf(PRODUCT_IMAGE_SHA256_PATTERN::matches)
            ?: throw ProductImageException("image_read_contract_invalid")
        val bytes = metadata.bytes?.takeIf { it in 1..variant.maxBytes.toLong() }
            ?: throw ProductImageException("image_read_contract_invalid")
        val width = metadata.width?.takeIf { it > 0 }
            ?: throw ProductImageException("image_read_contract_invalid")
        val height = metadata.height?.takeIf { it > 0 }
            ?: throw ProductImageException("image_read_contract_invalid")
        val maxSide = when (variant) {
            ProductImageVariant.MAIN -> PRODUCT_IMAGE_MAIN_MAX_SIDE
            ProductImageVariant.THUMB -> PRODUCT_IMAGE_THUMB_MAX_SIDE
        }
        if (maxOf(width, height) > maxSide || metadata.mimeType != "image/jpeg") {
            throw ProductImageException("image_read_contract_invalid")
        }
        return ProductImageReadMetadata(sha256, bytes, width, height)
    }

    /**
     * The server metadata is an integrity commitment, not catalog payload. It
     * is held only with the in-memory signed-URL lease and checked before any
     * cache write, so stale/cross-generation bytes cannot repopulate cache.
     */
    private fun validateReadMetadata(
        reference: ProductImageReference,
        bytes: ByteArray,
        metadata: ProductImageReadMetadata?
    ) {
        val expected = metadata ?: throw ProductImageException("image_read_contract_invalid")
        if (bytes.size.toLong() != expected.bytes ||
            sha256(bytes) != expected.sha256 ||
            jpegContainsForbiddenMetadata(bytes)
        ) {
            throw ProductImageException("image_download_invalid")
        }
        val bounds = decodeProductImageBounds(bytes)
            ?: throw ProductImageException("image_download_invalid")
        if (bounds.width != expected.width ||
            bounds.height != expected.height ||
            maxOf(bounds.width, bounds.height) > when (reference.variant) {
                ProductImageVariant.MAIN -> PRODUCT_IMAGE_MAIN_MAX_SIDE
                ProductImageVariant.THUMB -> PRODUCT_IMAGE_THUMB_MAX_SIDE
            }
        ) {
            throw ProductImageException("image_download_invalid")
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        } ?: throw ProductImageException("image_download_invalid")
        try {
            if (bitmap.width != expected.width || bitmap.height != expected.height) {
                throw ProductImageException("image_download_invalid")
            }
        } finally {
            bitmap.recycle()
        }
        if (reference.variant == ProductImageVariant.THUMB &&
            maxOf(expected.width, expected.height) > PRODUCT_IMAGE_THUMB_MAX_SIDE
        ) {
            throw ProductImageException("image_download_invalid")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private suspend fun releaseWaiter(acquisition: DownloadAcquisition) {
        inFlightMutex.withLock {
            val current = inFlightDownloads[acquisition.miss.reference]
            if (current === acquisition.entry) {
                current.waiters = (current.waiters - 1).coerceAtLeast(0)
            }
            if (current === acquisition.entry && current.producerCompleted && current.waiters == 0) {
                inFlightDownloads.remove(acquisition.miss.reference)
            }
        }
    }

    private suspend fun markProducerCompleted(acquisition: DownloadAcquisition) {
        inFlightMutex.withLock {
            val current = inFlightDownloads[acquisition.miss.reference]
            if (current === acquisition.entry) {
                current.producerCompleted = true
                if (current.waiters == 0) {
                    inFlightDownloads.remove(acquisition.miss.reference)
                }
            }
        }
    }

    private suspend fun isStillCurrent(
        operation: OperationContext,
        miss: ResolvedLoadRequest
    ): Boolean {
        requireReadOperationCurrent(operation)
        val isCurrent = database.productDao()
            .getById(miss.request.localProductId)
            ?.primaryImageVersionId == miss.reference.versionId
        requireReadOperationCurrent(operation)
        return isCurrent
    }

    suspend fun upload(
        localProductId: Long,
        sourceUri: Uri,
        onProgress: (ProductImageMutationPhase) -> Unit = {},
        onPrepared: (PreparedProductImage) -> Unit = {}
    ): ProductImageMutationResult {
        if (!networkAvailable()) throw ProductImageException("image_request_failed")
        val operation = resolveOperationContext(requireWrite = true)
        return businessDataScopeRuntimeGuard.withBusinessDataScopeFlight(
            ownerUserId = operation.accountId,
            selectedShop = operation.shop
        ) {
            requireOperationCurrent(operation)
            uploadInBusinessDataScope(
                operation = operation,
                localProductId = localProductId,
                sourceUri = sourceUri,
                onProgress = onProgress,
                onPrepared = onPrepared
            )
        }
    }

    private suspend fun uploadInBusinessDataScope(
        operation: OperationContext,
        localProductId: Long,
        sourceUri: Uri,
        onProgress: (ProductImageMutationPhase) -> Unit,
        onPrepared: (PreparedProductImage) -> Unit
    ): ProductImageMutationResult {
        val product = withContext(Dispatchers.IO) {
            database.productDao().getById(localProductId)
        } ?: throw ProductImageException("image_reference_invalid")
        requireOperationCurrent(operation)
        val remoteId = withContext(Dispatchers.IO) {
            database.productRemoteRefDao().getByProductId(localProductId)
                ?.takeIf { it.lastRemoteAppliedAt != null }
                ?.remoteId
        } ?: throw ProductImageException("image_reference_invalid")
        requireUuid(remoteId)
        requireOperationCurrent(operation)
        onProgress(ProductImageMutationPhase.PREPROCESSING)
        val prepared = processor.prepare(context, sourceUri)
        requireOperationCurrent(operation)
        onPrepared(prepared)
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw ProductImageException("image_session_missing")
        requireOperationCurrent(operation)
        val intent = api.createIntent(
            accessToken = token,
            body = ProductImageIntentBody(
                main = prepared.main.metadata.toBody(),
                productId = remoteId,
                shopId = operation.shop.shopId,
                thumb = prepared.thumb.metadata.toBody()
            )
        )
        requireOperationCurrent(operation)
        val versionId = intent.versionId
            ?.takeIf(PRODUCT_IMAGE_UUID_PATTERN::matches)
            ?: throw ProductImageException("image_request_failed")
        if (!intent.ok || intent.cacheScope != operation.accountScope) {
            throw ProductImageException("image_request_failed")
        }

        val mutation = when (intent.status) {
            "noop" -> ProductImageMutationResult(
                status = "noop",
                versionId = versionId,
                imageUpdatedAt = product.primaryImageUpdatedAt
            )

            "upload_required" -> {
                val mainUrl = intent.mainUploadUrl
                    ?: throw ProductImageException("image_request_failed")
                val thumbUrl = intent.thumbUploadUrl
                    ?: throw ProductImageException("image_request_failed")
                onProgress(ProductImageMutationPhase.UPLOAD_MAIN)
                putSignedJpegWithOneTransientRetry(
                    operation = operation,
                    signedUrl = mainUrl,
                    bytes = prepared.main.bytes,
                    expectedReference = reference(
                        operation,
                        remoteId,
                        versionId,
                        ProductImageVariant.MAIN
                    )
                )
                onProgress(ProductImageMutationPhase.UPLOAD_THUMB)
                putSignedJpegWithOneTransientRetry(
                    operation = operation,
                    signedUrl = thumbUrl,
                    bytes = prepared.thumb.bytes,
                    expectedReference = reference(
                        operation,
                        remoteId,
                        versionId,
                        ProductImageVariant.THUMB
                    )
                )
                onProgress(ProductImageMutationPhase.FINALIZING)
                requireOperationCurrent(operation)
                val finalized = api.finalizeImage(
                    accessToken = token,
                    body = ProductImageFinalizeBody(
                        productId = remoteId,
                        shopId = operation.shop.shopId,
                        versionId = versionId
                    )
                )
                requireOperationCurrent(operation)
                if (!finalized.ok ||
                    finalized.versionId != versionId ||
                    finalized.status !in setOf("finalized", "already_finalized")
                ) {
                    throw ProductImageException("image_request_failed")
                }
                val finalizedStatus = finalized.status
                    ?: throw ProductImageException("image_request_failed")
                ProductImageMutationResult(
                    status = finalizedStatus,
                    versionId = versionId,
                    imageUpdatedAt = finalized.imageUpdatedAt
                )
            }

            else -> throw ProductImageException("image_request_failed")
        }

        withContext(Dispatchers.IO) {
            database.withTransaction {
                requireOperationCurrent(operation)
                database.productDao().updateRemoteImageReference(
                    productId = localProductId,
                    versionId = versionId,
                    updatedAt = mutation.imageUpdatedAt
                )
                // Se il lease cambia durante la query, l'eccezione esegue il rollback Room.
                requireOperationCurrent(operation)
            }
            requireOperationCurrent(operation)
            purgeSignedUrlLeases(operation.accountScope, operation.shop.shopId, remoteId)
            cachePrepared(operation, remoteId, versionId, prepared)
        }
        requireOperationCurrent(operation)
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

    suspend fun purgeAllScopes() = withContext(Dispatchers.IO) {
        synchronized(signedUrlLeaseLock) { signedUrlLeases.clear() }
        cache.purgeAll()
    }

    suspend fun remove(localProductId: Long): ProductImageMutationResult {
        if (!networkAvailable()) throw ProductImageException("image_request_failed")
        val operation = resolveOperationContext(requireWrite = true)
        return businessDataScopeRuntimeGuard.withBusinessDataScopeFlight(
            ownerUserId = operation.accountId,
            selectedShop = operation.shop
        ) {
            requireOperationCurrent(operation)
            removeInBusinessDataScope(operation, localProductId)
        }
    }

    private suspend fun removeInBusinessDataScope(
        operation: OperationContext,
        localProductId: Long
    ): ProductImageMutationResult {
        val product = withContext(Dispatchers.IO) {
            database.productDao().getById(localProductId)
        } ?: throw ProductImageException("image_reference_invalid")
        requireOperationCurrent(operation)
        val versionId = product.primaryImageVersionId
            ?: return ProductImageMutationResult("already_removed", null, product.primaryImageUpdatedAt)
        requireUuid(versionId)
        val remoteId = withContext(Dispatchers.IO) {
            database.productRemoteRefDao().getByProductId(localProductId)
                ?.takeIf { it.lastRemoteAppliedAt != null }
                ?.remoteId
        } ?: throw ProductImageException("image_reference_invalid")
        requireUuid(remoteId)
        requireOperationCurrent(operation)
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw ProductImageException("image_session_missing")
        requireOperationCurrent(operation)
        val response = api.removeImage(
            accessToken = token,
            body = ProductImageRemoveBody(
                expectedVersionId = versionId,
                productId = remoteId,
                shopId = operation.shop.shopId
            )
        )
        requireOperationCurrent(operation)
        if (!response.ok ||
            response.operation != "remove" ||
            response.productId != remoteId ||
            response.shopId != operation.shop.shopId ||
            response.status !in setOf("removed", "already_removed") ||
            response.versionId != versionId ||
            response.currentImageVersionId != null
        ) {
            throw ProductImageException("image_request_failed")
        }
        val removalStatus = response.status
            ?: throw ProductImageException("image_request_failed")
        withContext(Dispatchers.IO) {
            database.withTransaction {
                requireOperationCurrent(operation)
                database.productDao().updateRemoteImageReference(
                    productId = localProductId,
                    versionId = null,
                    updatedAt = response.imageUpdatedAt ?: product.primaryImageUpdatedAt
                )
                // Non pubblicare una rimozione locale sotto un lease write scaduto.
                requireOperationCurrent(operation)
            }
            requireOperationCurrent(operation)
            try {
                purgeSignedUrlLeases(operation.accountScope, operation.shop.shopId, remoteId)
                cache.purgeProduct(operation.accountScope, operation.shop.shopId, remoteId)
            } catch (_: ProductImageException) {
                // Rimozione server gia' conclusa: un errore cache locale non la annulla.
            }
        }
        requireOperationCurrent(operation)
        return ProductImageMutationResult(
            status = removalStatus,
            versionId = null,
            imageUpdatedAt = response.imageUpdatedAt
        )
    }

    fun close() {
        producerScope.cancel()
        api.close()
    }

    fun trimMemory() {
        cache.trimMemory()
        synchronized(signedUrlLeaseLock) { signedUrlLeases.clear() }
    }

    private suspend fun putSignedJpegWithOneTransientRetry(
        operation: OperationContext,
        signedUrl: String,
        bytes: ByteArray,
        expectedReference: ProductImageReference
    ) {
        requireOperationCurrent(operation)
        try {
            api.putSignedJpeg(signedUrl, bytes, expectedReference)
        } catch (error: ProductImageException) {
            if (!error.isTransientUploadFailure()) throw error
            requireOperationCurrent(operation)
            api.putSignedJpeg(signedUrl, bytes, expectedReference)
        }
        requireOperationCurrent(operation)
    }

    private fun ProductImageException.isTransientUploadFailure(): Boolean {
        return code == "image_upload_failed" && (httpStatus == null || httpStatus in 500..599)
    }

    private fun ProductImageException.isDownloadAuthorizationFailure(): Boolean =
        code == "image_request_failed" && httpStatus in setOf(401, 403)

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
                prepared.main.bytes,
                prepared.main.metadata
            )
            cache.write(
                reference(operation, productId, versionId, ProductImageVariant.THUMB),
                prepared.thumb.bytes,
                prepared.thumb.metadata
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
        if (!api.isConfigured) throw ProductImageException("image_request_failed")
        val accountId = accountIdProvider()?.takeIf { it.isNotBlank() }
            ?: throw ProductImageException("image_session_missing")
        requireUuid(accountId)
        val shop = selectedShopProvider()
            ?: throw ProductImageException("image_reference_invalid")
        requireUuid(shop.shopId)
        if (!businessDataScopeAllowed(accountId, shop)) {
            throw ProductImageException("image_account_changed")
        }
        if (requireWrite && !shop.canWrite) {
            throw ProductImageException("image_request_failed")
        }
        return OperationContext(
            accountId = accountId,
            accountScope = cache.accountScope(accountId),
            shop = shop
        )
    }

    private suspend fun resolveReadOperationContext(): OperationContext {
        if (!api.isConfigured) throw ProductImageException("image_request_failed")
        val accountId = accountIdProvider()?.takeIf { it.isNotBlank() }
            ?: throw ProductImageException("image_session_missing")
        requireUuid(accountId)
        selectedShopProvider()?.let { shop ->
            requireUuid(shop.shopId)
            if (!businessDataScopeAllowed(accountId, shop)) {
                throw ProductImageException("image_account_changed")
            }
            return OperationContext(
                accountId = accountId,
                accountScope = cache.accountScope(accountId),
                shop = shop
            )
        }
        if (networkAvailable() || !allowBoundCacheRead()) {
            throw ProductImageException("image_reference_invalid")
        }

        val boundScope = database.businessDataScopeBindingDao().get()?.toOwnerStoreScope()
            ?: throw ProductImageException("image_reference_invalid")
        val activeScope = Task126OwnerStoreScope(
            ownerHash = task126OwnerHash(accountId),
            storeId = boundScope.storeId,
            localStoreId = null
        )
        if (Task126OwnerStoreGate.validate(boundScope, activeScope) !=
            Task126OwnerStoreGateDecision.Allowed
        ) {
            throw ProductImageException("image_account_changed")
        }
        val boundShopId = shopIdFromStoreScope(boundScope.storeId)
            ?: throw ProductImageException("image_reference_invalid")
        requireUuid(boundShopId)
        return OperationContext(
            accountId = accountId,
            accountScope = cache.accountScope(accountId),
            shop = SelectedShop(
                shopId = boundShopId,
                code = null,
                name = "",
                role = null,
                status = null,
                canWrite = false
            ),
            boundCacheRead = true
        )
    }

    private fun ensureContextStillCurrent(operation: OperationContext) {
        val currentAccountId = accountIdProvider()
        val currentShop = selectedShopProvider()
        if (
            currentAccountId != operation.accountId ||
            currentShop?.shopId != operation.shop.shopId ||
            !currentShop.canWrite ||
            !businessDataScopeAllowed(currentAccountId, currentShop)
        ) {
            throw ProductImageException("image_account_changed")
        }
    }

    private suspend fun requireOperationCurrent(operation: OperationContext) {
        currentCoroutineContext().ensureActive()
        businessDataScopeRuntimeGuard.requireCurrentBusinessDataScope()
        ensureContextStillCurrent(operation)
    }

    private suspend fun requireReadOperationCurrent(operation: OperationContext) {
        currentCoroutineContext().ensureActive()
        businessDataScopeRuntimeGuard.requireCurrentBusinessDataScope()
        if (!operation.boundCacheRead) {
            ensureContextStillCurrent(operation)
            return
        }
        if (accountIdProvider() != operation.accountId ||
            selectedShopProvider() != null ||
            networkAvailable() ||
            !allowBoundCacheRead()
        ) {
            throw ProductImageException("image_account_changed")
        }
        val boundScope = database.businessDataScopeBindingDao().get()?.toOwnerStoreScope()
            ?: throw ProductImageException("image_account_changed")
        val expectedScope = Task126OwnerStoreScope(
            ownerHash = task126OwnerHash(operation.accountId),
            storeId = "shop:${operation.shop.shopId}",
            localStoreId = null
        )
        if (Task126OwnerStoreGate.validate(boundScope, expectedScope) !=
            Task126OwnerStoreGateDecision.Allowed
        ) {
            throw ProductImageException("image_account_changed")
        }
    }

    private fun requireUuid(value: String) {
        if (!PRODUCT_IMAGE_UUID_PATTERN.matches(value)) {
            throw ProductImageException("image_reference_invalid")
        }
    }

    private data class OperationContext(
        val accountId: String,
        val accountScope: String,
        val shop: SelectedShop,
        val boundCacheRead: Boolean = false
    )

    private data class ResolvedLoadRequest(
        val request: ProductImageLoadRequest,
        val reference: ProductImageReference
    )

    private data class DownloadAcquisition(
        val miss: ResolvedLoadRequest,
        val entry: InFlightDownload,
        val owner: Boolean
    )

    private data class InFlightDownload(
        val deferred: CompletableDeferred<ByteArray>,
        var waiters: Int,
        var producerCompleted: Boolean = false
    )

    private data object ProductImageNotFoundException : Exception()

    private data class SignedUrlLease(
        val signedUrl: String,
        val expiresAtEpochMillis: Long,
        val metadata: ProductImageReadMetadata
    )

    private data class ProductImageReadMetadata(
        val sha256: String,
        val bytes: Long,
        val width: Int,
        val height: Int
    ) {
        fun toCacheMetadata() = ProductImageMetadata(
            bytes = bytes.toInt(),
            height = height,
            mimeType = "image/jpeg",
            sha256 = sha256,
            width = width
        )
    }

    private data class ResolvedSignedUrl(
        val signedUrl: String,
        val metadata: ProductImageReadMetadata
    )

    private val ProductImageReference.wireKey: Triple<String, String, String>
        get() = Triple(productId, versionId, variant.wireName)

    private companion object {
        const val PRODUCT_IMAGE_READ_BATCH_MAX = PRODUCT_IMAGE_READ_URLS_MAX_REFS
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
        if (error.code != "image_request_failed" || error.httpStatus !in setOf(401, 403)) {
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

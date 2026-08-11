package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.example.merchandisecontrolsplitview.data.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.util.DatabaseExportSheet
import com.example.merchandisecontrolsplitview.util.ExportSheetSelection
import com.example.merchandisecontrolsplitview.util.ImportAnalyzer
import com.example.merchandisecontrolsplitview.util.ImportDatasetFingerprint
import com.example.merchandisecontrolsplitview.util.SmartImportWorkbookOutcome
import com.example.merchandisecontrolsplitview.util.analyzeSmartImportWorkbook
import com.example.merchandisecontrolsplitview.util.analyzeFullDbImportStreaming
import com.example.merchandisecontrolsplitview.util.buildDatabaseSnapshotFingerprint
import com.example.merchandisecontrolsplitview.util.buildDatabaseExportSchema
import com.example.merchandisecontrolsplitview.util.readAndAnalyzeExcel
import com.example.merchandisecontrolsplitview.util.resolveExcelFileErrorMessage
import com.example.merchandisecontrolsplitview.util.CatalogTextValidationException
import com.example.merchandisecontrolsplitview.util.catalogTextErrorMessage
import com.example.merchandisecontrolsplitview.util.writeDatabaseExportStreaming
import java.io.IOException
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import com.example.merchandisecontrolsplitview.BuildConfig
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import com.example.merchandisecontrolsplitview.productimage.ProductImageBatchItem
import com.example.merchandisecontrolsplitview.productimage.ProductImageException
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadRequest
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadResult
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadSource
import com.example.merchandisecontrolsplitview.productimage.ProductImageMutationPhase
import com.example.merchandisecontrolsplitview.productimage.ProductImageProcessor
import com.example.merchandisecontrolsplitview.productimage.PreparedProductImage
import com.example.merchandisecontrolsplitview.productimage.ProductImageService
import com.example.merchandisecontrolsplitview.productimage.ProductImageVariant
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOrigin
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.io.File
import java.util.UUID

sealed class UiState {
    data object Idle : UiState()
    data class Loading(val message: String? = null, val progress: Int? = null) : UiState()
    data class Success(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

sealed interface ProductEditorSaveResult {
    data object Saved : ProductEditorSaveResult
    data class Failed(val message: String) : ProductEditorSaveResult
}

sealed interface ProductEditorOperationState {
    data object Idle : ProductEditorOperationState
    data object Saving : ProductEditorOperationState
    data object Saved : ProductEditorOperationState
    data class Failed(val message: String) : ProductEditorOperationState
}

sealed interface StagedProductImageState {
    data object Empty : StagedProductImageState
    data object Preparing : StagedProductImageState
    data class Ready(
        val fileUri: Uri,
        val previewBytes: ByteArray
    ) : StagedProductImageState
    data class Failed(val message: String) : StagedProductImageState
}

sealed interface ScannedBarcodeLookupResult {
    data class Resolved(val destination: Product?) : ScannedBarcodeLookupResult
    data object StaleScope : ScannedBarcodeLookupResult
    data object Failed : ScannedBarcodeLookupResult
}

sealed interface ImportFlowState {
    data object Idle : ImportFlowState
    data object PreviewLoading : ImportFlowState
    data class PreviewReady(val previewId: Long) : ImportFlowState
    data class Applying(val previewId: Long) : ImportFlowState
    data class Success(val previewId: Long) : ImportFlowState
    data class Error(
        val previewId: Long?,
        val message: String,
        val occurredDuringApply: Boolean
    ) : ImportFlowState
    data class Cancelled(val previewId: Long?) : ImportFlowState
}

data class ExportUiState(
    val inProgress: Boolean = false,
    val message: String? = null,
    val progress: Int? = null
)

data class ProductImageUiKey(
    val productId: Long,
    val variant: ProductImageVariant
)

enum class ProductImageUiStatus {
    ABSENT,
    LOADING,
    READY,
    UPLOADING,
    REMOVING,
    ERROR
}

data class ProductImageUiState(
    val status: ProductImageUiStatus,
    val bytes: ByteArray? = null,
    /** Preview JPEG bounded prodotta dalla stessa pipeline dell'upload, mai riferimento remoto. */
    val pendingPreviewBytes: ByteArray? = null,
    val versionId: String? = null,
    val source: ProductImageLoadSource? = null,
    val errorCode: String? = null,
    val mutationPhase: ProductImageMutationPhase? = null
)

private data class FullImportDbSnapshot(
    val products: List<Product>,
    val fingerprint: ImportDatasetFingerprint
)

private data class ExportProgressSnapshot(
    val message: String,
    val progress: Int
)

private class ExportProgressTracker(
    private val context: Context,
    selection: ExportSheetSelection
) {
    private val totalWeight = selection.selectedSheetsInOrder()
        .sumOf(DatabaseExportSheet::weight)
        .coerceAtLeast(1)
    private var completedWeight = 0f

    fun preparing(): ExportProgressSnapshot =
        ExportProgressSnapshot(
            message = context.getString(R.string.export_preparing),
            progress = 5
        )

    fun fetching(sheet: DatabaseExportSheet): ExportProgressSnapshot =
        ExportProgressSnapshot(
            message = context.getString(
                R.string.export_fetching_sheet,
                context.getString(sheet.labelRes)
            ),
            progress = progressFor(completedWeight)
        )

    fun fetched(sheet: DatabaseExportSheet): ExportProgressSnapshot =
        ExportProgressSnapshot(
            message = context.getString(
                R.string.export_writing_sheet,
                context.getString(sheet.labelRes)
            ),
            progress = progressFor(completedWeight + (sheet.weight * 0.4f))
        )

    fun sheetWritten(sheet: DatabaseExportSheet): ExportProgressSnapshot {
        completedWeight += sheet.weight.toFloat()
        return ExportProgressSnapshot(
            message = context.getString(
                R.string.export_writing_sheet,
                context.getString(sheet.labelRes)
            ),
            progress = progressFor(completedWeight)
        )
    }

    fun finishing(): ExportProgressSnapshot =
        ExportProgressSnapshot(
            message = context.getString(R.string.export_finishing),
            progress = 97
        )

    private fun progressFor(consumedWeight: Float): Int {
        val normalized = (consumedWeight / totalWeight).coerceIn(0f, 1f)
        return (5 + (normalized * 90f)).toInt().coerceIn(5, 95)
    }
}

private class FullImportAlreadyInProgressException : RuntimeException(null, null, false, false)

private const val PRODUCT_DETAILS_OVERRIDE_LIMIT = 100
private const val PRODUCT_SEARCH_DEBOUNCE_MS = 250L
private const val PRODUCT_IMAGE_VISIBLE_BATCH_WINDOW_MS = 16L
private val PRICE_HISTORY_MANUAL_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private data class ProductImageOwnerShopScope(
    val accountId: String,
    val shopId: String?
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class DatabaseViewModel(
    app: Application,
    private val repository: InventoryRepository,
    private val productImageService: ProductImageService =
        (app as MerchandiseControlApplication).productImageService,
    private val stagedImagePreparer: suspend (Context, Uri) -> PreparedProductImage =
        ProductImageProcessor()::prepare,
    private val stagedImageWriter: suspend (Context, ByteArray) -> Uri =
        ::writeStagedProductImage,
    private val canStageProductImages: () -> Boolean =
        productImageService::canWriteNow
) : AndroidViewModel(app) {
    private val importMutex = Mutex()
    private val exportMutex = Mutex()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val _importFlowState = MutableStateFlow<ImportFlowState>(ImportFlowState.Idle)
    val importFlowState: StateFlow<ImportFlowState> = _importFlowState.asStateFlow()
    private val _exportUiState = MutableStateFlow(ExportUiState())
    val exportUiState: StateFlow<ExportUiState> = _exportUiState.asStateFlow()
    private val _selectedHubTab = MutableStateFlow(DatabaseHubTab.PRODUCTS)
    val selectedHubTab: StateFlow<DatabaseHubTab> = _selectedHubTab.asStateFlow()
    private val _productEditorTarget = MutableStateFlow<Product?>(null)
    val productEditorTarget: StateFlow<Product?> = _productEditorTarget.asStateFlow()
    private val _productEditorSessionId = MutableStateFlow(0L)
    val productEditorSessionId: StateFlow<Long> = _productEditorSessionId.asStateFlow()
    private val _productEditorOperationState =
        MutableStateFlow<ProductEditorOperationState>(ProductEditorOperationState.Idle)
    val productEditorOperationState: StateFlow<ProductEditorOperationState> =
        _productEditorOperationState.asStateFlow()
    private val _stagedProductImageState =
        MutableStateFlow<StagedProductImageState>(StagedProductImageState.Empty)
    val stagedProductImageState: StateFlow<StagedProductImageState> =
        _stagedProductImageState.asStateFlow()

    fun consumeUiState() { _uiState.value = UiState.Idle }
    private val _filter = MutableStateFlow<String?>(null)
    private val productDetailsOverrideMutex = Mutex()
    private val _productDetailsOverrides = MutableStateFlow<Map<Long, ProductWithDetails>>(emptyMap())
    val productDetailsOverrides: StateFlow<Map<Long, ProductWithDetails>> =
        _productDetailsOverrides.asStateFlow()

    private val appContext = getApplication<Application>().applicationContext
    private val merchandiseApplication =
        getApplication<Application>() as MerchandiseControlApplication
    private var productImageScopeGeneration = 0L
    private val _productImageScopeEpoch = MutableStateFlow(0L)
    val productImageScopeEpoch: StateFlow<Long> = _productImageScopeEpoch.asStateFlow()
    private val _productImageStates = MutableStateFlow<Map<ProductImageUiKey, ProductImageUiState>>(emptyMap())
    val productImageStates: StateFlow<Map<ProductImageUiKey, ProductImageUiState>> =
        _productImageStates.asStateFlow()
    private val desiredProductImageVersions = mutableMapOf<ProductImageUiKey, String?>()
    private val productImageLoadJobs = mutableMapOf<ProductImageUiKey, Job>()
    private val productImageMutationJobs = mutableMapOf<Long, Job>()
    private val failedProductImageRollbackStates =
        mutableMapOf<Long, Map<ProductImageUiKey, ProductImageUiState?>>()
    private val pendingStagedProductImages = mutableMapOf<Long, Uri>()
    private var stagedImagePreparationJob: Job? = null
    private var productEditorSaveJob: Job? = null
    private val visibleThumbVersions = linkedMapOf<Long, String?>()
    private val pendingVisibleThumbs = linkedMapOf<Long, String?>()
    private var inFlightVisibleThumbProductIds = emptySet<Long>()
    private var visibleThumbBatchJob: Job? = null
    val filter: StateFlow<String?> = _filter.asStateFlow()

    private val _supplierCatalogQuery = MutableStateFlow("")
    private val _supplierCatalogRefresh = MutableStateFlow(0)
    val supplierCatalogQuery: StateFlow<String> = _supplierCatalogQuery.asStateFlow()
    val supplierCatalogSection: StateFlow<CatalogSectionUiState> = catalogSectionState(
        kind = CatalogEntityKind.SUPPLIER,
        queryFlow = _supplierCatalogQuery,
        refreshFlow = _supplierCatalogRefresh
    )

    private val _categoryCatalogQuery = MutableStateFlow("")
    private val _categoryCatalogRefresh = MutableStateFlow(0)
    val categoryCatalogQuery: StateFlow<String> = _categoryCatalogQuery.asStateFlow()
    val categoryCatalogSection: StateFlow<CatalogSectionUiState> = catalogSectionState(
        kind = CatalogEntityKind.CATEGORY,
        queryFlow = _categoryCatalogQuery,
        refreshFlow = _categoryCatalogRefresh
    )

    internal val appliedProductFilter: StateFlow<String?> = filter
        .transformLatest { rawQuery ->
            val normalized = rawQuery?.trim()?.takeIf(String::isNotEmpty)
            if (normalized != null) {
                delay(PRODUCT_SEARCH_DEBOUNCE_MS)
            }
            emit(normalized)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val pager = appliedProductFilter.flatMapLatest { filterStr ->
        Pager(PagingConfig(pageSize = 20)) {
            repository.getProductsWithDetailsPaged(filterStr)
        }.flow.cachedIn(viewModelScope)
    }

    init {
        observeRemoteAppliedProductIds()
        observeProductImageScope()
    }

    fun canManageProductImages(): Boolean = productImageService.canWriteNow()

    fun productImagesConfigured(): Boolean = productImageService.isConfigured

    fun openProductEditor(product: Product) {
        if (productEditorSaveJob?.isActive == true) return
        clearStagedProductImage(deleteFile = true)
        _productEditorTarget.value = product
        _productEditorSessionId.value += 1L
        _productEditorOperationState.value = ProductEditorOperationState.Idle
    }

    fun dismissProductEditor() {
        if (productEditorSaveJob?.isActive == true &&
            _productEditorOperationState.value != ProductEditorOperationState.Saved
        ) return
        _productEditorTarget.value?.id?.takeIf { it > 0L }?.let(::closeProductImageEditor)
        _productEditorTarget.value = null
        _productEditorOperationState.value = ProductEditorOperationState.Idle
        clearStagedProductImage(deleteFile = true)
    }

    fun stageNewProductImage(sourceUri: Uri, onFinished: () -> Unit = {}) {
        if (_productEditorTarget.value?.id != 0L || !canStageProductImages()) {
            onFinished()
            return
        }
        stagedImagePreparationJob?.cancel()
        clearStagedProductImage(deleteFile = true)
        _stagedProductImageState.value = StagedProductImageState.Preparing
        stagedImagePreparationJob = viewModelScope.launch {
            try {
                val prepared = stagedImagePreparer(appContext, sourceUri)
                _stagedProductImageState.value = StagedProductImageState.Ready(
                    fileUri = stagedImageWriter(appContext, prepared.main.bytes),
                    previewBytes = prepared.main.bytes
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _stagedProductImageState.value = StagedProductImageState.Failed(
                    appContext.getString(R.string.product_image_operation_failed)
                )
            } finally {
                onFinished()
            }
        }
    }

    fun discardStagedProductImage() {
        if (stagedImagePreparationJob?.isActive == true) {
            stagedImagePreparationJob?.cancel()
        }
        clearStagedProductImage(deleteFile = true)
    }

    fun hasPendingStagedProductImage(productId: Long): Boolean =
        pendingStagedProductImages.containsKey(productId)

    fun retryPendingStagedProductImage(productId: Long) {
        uploadPendingStagedProductImage(productId)
    }

    fun discardPendingStagedProductImage(productId: Long) {
        pendingStagedProductImages.remove(productId)?.let(::deleteStagedImageFile)
        discardFailedProductImageOperation(productId)
    }

    fun startProductEditorSave(
        product: Product,
        save: suspend (Product) -> ProductEditorSaveResult = ::saveProductFromEditor
    ) {
        if (productEditorSaveJob?.isActive == true) return
        _productEditorOperationState.value = ProductEditorOperationState.Saving
        productEditorSaveJob = viewModelScope.launch {
            val result = try {
                save(product)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProductEditorSaveResult.Failed(
                    appContext.getString(R.string.product_editor_save_failed)
                )
            }
            when (result) {
                ProductEditorSaveResult.Saved -> {
                    if (product.id == 0L) {
                        val persisted = runCatching {
                            repository.findProductByBarcode(product.barcode)
                        }.getOrNull()
                        val staged = _stagedProductImageState.value as? StagedProductImageState.Ready
                        if (persisted != null && staged != null) {
                            pendingStagedProductImages[persisted.id] = staged.fileUri
                            _stagedProductImageState.value = StagedProductImageState.Empty
                            if (repository.hasSyncedProductRemoteRef(persisted.id)) {
                                uploadPendingStagedProductImage(persisted.id)
                            }
                        }
                    }
                    _productEditorOperationState.value = ProductEditorOperationState.Saved
                }
                is ProductEditorSaveResult.Failed -> {
                    _productEditorOperationState.value =
                        ProductEditorOperationState.Failed(result.message)
                }
            }
        }
    }

    fun resetProductEditorOperation() {
        if (productEditorSaveJob?.isActive != true) {
            _productEditorOperationState.value = ProductEditorOperationState.Idle
        }
    }

    fun currentProductImageScopeEpoch(): Long = productImageScopeGeneration

    suspend fun lookupScannedBarcode(
        barcode: String,
        expectedScopeEpoch: Long
    ): ScannedBarcodeLookupResult = try {
        val product = repository.findProductByBarcode(barcode)
        if (expectedScopeEpoch != productImageScopeGeneration) {
            ScannedBarcodeLookupResult.StaleScope
        } else {
            ScannedBarcodeLookupResult.Resolved(product)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ScannedBarcodeLookupResult.Failed
    }

    suspend fun hasSyncedProductImageReference(productId: Long): Boolean =
        productId > 0L && repository.hasSyncedProductRemoteRef(productId)

    fun setProductImageVisible(
        productId: Long,
        expectedVersionId: String?,
        visible: Boolean
    ) {
        val key = ProductImageUiKey(productId, ProductImageVariant.THUMB)
        if (!visible) {
            visibleThumbVersions.remove(productId)
            pendingVisibleThumbs.remove(productId)
            desiredProductImageVersions.remove(key)
            productImageLoadJobs.remove(key)?.cancel()
            _productImageStates.update { current -> current - key }
            if (inFlightVisibleThumbProductIds.isNotEmpty() &&
                inFlightVisibleThumbProductIds.none(visibleThumbVersions::containsKey)
            ) {
                visibleThumbBatchJob?.cancel()
                visibleThumbBatchJob = null
                inFlightVisibleThumbProductIds = emptySet()
            }
            return
        }

        visibleThumbVersions[productId] = expectedVersionId
        desiredProductImageVersions[key] = expectedVersionId
        if (expectedVersionId == null) {
            pendingVisibleThumbs.remove(productId)
            updateProductImageState(key, ProductImageUiState(ProductImageUiStatus.ABSENT))
            return
        }
        val current = _productImageStates.value[key]
        if (current?.status == ProductImageUiStatus.READY &&
            current.versionId == expectedVersionId
        ) {
            return
        }
        if (current?.versionId != expectedVersionId) {
            updateProductImageState(
                key,
                ProductImageUiState(
                    status = ProductImageUiStatus.LOADING,
                    versionId = expectedVersionId
                )
            )
        }
        pendingVisibleThumbs[productId] = expectedVersionId
        scheduleVisibleThumbBatch()
    }

    fun loadProductImage(
        productId: Long,
        variant: ProductImageVariant,
        expectedVersionId: String?,
        force: Boolean = false
    ) {
        val key = ProductImageUiKey(productId, variant)
        desiredProductImageVersions[key] = expectedVersionId
        if (expectedVersionId == null) {
            productImageLoadJobs.remove(key)?.cancel()
            updateProductImageState(key, ProductImageUiState(ProductImageUiStatus.ABSENT))
            return
        }
        val current = _productImageStates.value[key]
        if (!force &&
            ((productImageLoadJobs[key]?.isActive == true &&
                current?.versionId == expectedVersionId) ||
                (current?.status == ProductImageUiStatus.READY && current.versionId == expectedVersionId))
        ) {
            return
        }
        productImageLoadJobs.remove(key)?.cancel()
        val keepCurrentBytes = current?.versionId == expectedVersionId
        updateProductImageState(
            key,
            ProductImageUiState(
                status = ProductImageUiStatus.LOADING,
                bytes = current?.bytes.takeIf { keepCurrentBytes },
                versionId = expectedVersionId
            )
        )
        val generation = productImageScopeGeneration
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                loadProductImageNow(key, expectedVersionId, generation)
            } finally {
                val runningJob = currentCoroutineContext()[Job]
                if (productImageLoadJobs[key] === runningJob) {
                    productImageLoadJobs.remove(key)
                }
            }
        }
        productImageLoadJobs[key] = job
        job.start()
    }

    fun loadProductImageProgressively(
        productId: Long,
        expectedVersionId: String?,
        force: Boolean = false
    ) {
        val thumbKey = ProductImageUiKey(productId, ProductImageVariant.THUMB)
        val mainKey = ProductImageUiKey(productId, ProductImageVariant.MAIN)
        val keys = listOf(thumbKey, mainKey)
        keys.forEach { desiredProductImageVersions[it] = expectedVersionId }
        if (expectedVersionId == null) {
            keys.mapNotNull(productImageLoadJobs::remove).toSet().forEach(Job::cancel)
            keys.forEach { key ->
                updateProductImageState(key, ProductImageUiState(ProductImageUiStatus.ABSENT))
            }
            return
        }

        val mainState = _productImageStates.value[mainKey]
        if (!force &&
            ((mainState?.status == ProductImageUiStatus.READY &&
                mainState.versionId == expectedVersionId) ||
                (productImageLoadJobs[mainKey]?.isActive == true &&
                    mainState?.versionId == expectedVersionId))
        ) {
            return
        }

        keys.mapNotNull(productImageLoadJobs::remove).toSet().forEach(Job::cancel)
        val thumbState = _productImageStates.value[thumbKey]
        val thumbReady = thumbState?.status == ProductImageUiStatus.READY &&
            thumbState.versionId == expectedVersionId
        if (!thumbReady) {
            updateProductImageState(
                thumbKey,
                ProductImageUiState(
                    status = ProductImageUiStatus.LOADING,
                    bytes = thumbState?.takeIf { it.versionId == expectedVersionId }?.bytes,
                    versionId = expectedVersionId
                )
            )
        }
        updateProductImageState(
            mainKey,
            ProductImageUiState(
                status = ProductImageUiStatus.LOADING,
                bytes = mainState?.takeIf { it.versionId == expectedVersionId }?.bytes,
                versionId = expectedVersionId
            )
        )

        val generation = productImageScopeGeneration
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!thumbReady) {
                    loadProductImageNow(thumbKey, expectedVersionId, generation)
                }
                currentCoroutineContext().ensureActive()
                loadProductImageNow(mainKey, expectedVersionId, generation)
            } finally {
                val runningJob = currentCoroutineContext()[Job]
                keys.forEach { key ->
                    if (productImageLoadJobs[key] === runningJob) {
                        productImageLoadJobs.remove(key)
                    }
                }
            }
        }
        keys.forEach { key -> productImageLoadJobs[key] = job }
        job.start()
    }

    fun cancelProductImageLoad(productId: Long, variant: ProductImageVariant) {
        val key = ProductImageUiKey(productId, variant)
        desiredProductImageVersions.remove(key)
        productImageLoadJobs.remove(key)?.cancel()
        if (variant == ProductImageVariant.THUMB) {
            visibleThumbVersions.remove(productId)
            pendingVisibleThumbs.remove(productId)
        }
        _productImageStates.update { current -> current - key }
    }

    fun uploadProductImage(
        productId: Long,
        sourceUri: Uri,
        onFinished: () -> Unit = {},
        onCommitted: () -> Unit = {}
    ) {
        if (productImageMutationJobs[productId]?.isActive == true) return
        val keys = ProductImageVariant.entries.map { ProductImageUiKey(productId, it) }
        val previousStates = keys.associateWith { key -> _productImageStates.value[key] }
        cancelProductImageLoadsForMutation(keys)
        failedProductImageRollbackStates.remove(productId)
        keys.forEach { key ->
            val current = _productImageStates.value[key]
            updateProductImageState(
                key,
                ProductImageUiState(
                    status = ProductImageUiStatus.UPLOADING,
                    bytes = current?.bytes,
                    versionId = current?.versionId,
                    mutationPhase = ProductImageMutationPhase.PREPROCESSING
                )
            )
        }
        val job = viewModelScope.launch {
            val generation = productImageScopeGeneration
            try {
                val result = productImageService.upload(
                    localProductId = productId,
                    sourceUri = sourceUri,
                    onProgress = { phase ->
                        if (generation == productImageScopeGeneration) {
                            keys.forEach { key ->
                                val current = _productImageStates.value[key]
                                updateProductImageState(
                                    key,
                                    ProductImageUiState(
                                        status = ProductImageUiStatus.UPLOADING,
                                        bytes = current?.bytes,
                                        pendingPreviewBytes = current?.pendingPreviewBytes,
                                        versionId = current?.versionId,
                                        mutationPhase = phase
                                    )
                                )
                            }
                        }
                    },
                    onPrepared = onPrepared@{ prepared ->
                        if (generation != productImageScopeGeneration) return@onPrepared
                        val pendingByVariant = mapOf(
                            ProductImageVariant.MAIN to prepared.main.bytes,
                            ProductImageVariant.THUMB to prepared.thumb.bytes
                        )
                        keys.forEach { key ->
                            val current = _productImageStates.value[key]
                            updateProductImageState(
                                key,
                                ProductImageUiState(
                                    status = ProductImageUiStatus.UPLOADING,
                                    bytes = current?.bytes,
                                    pendingPreviewBytes = pendingByVariant.getValue(key.variant),
                                    versionId = current?.versionId,
                                    mutationPhase = current?.mutationPhase
                                        ?: ProductImageMutationPhase.PREPROCESSING
                                )
                            )
                        }
                    }
                )
                if (generation != productImageScopeGeneration) return@launch
                onCommitted()
                keys.forEach { key ->
                    desiredProductImageVersions[key] = result.versionId
                    updateProductImageState(
                        key,
                        ProductImageUiState(
                            status = ProductImageUiStatus.LOADING,
                            pendingPreviewBytes = _productImageStates.value[key]
                                ?.pendingPreviewBytes,
                            versionId = result.versionId,
                            mutationPhase = ProductImageMutationPhase.COMPLETED
                        )
                    )
                    loadProductImageNow(
                        key = key,
                        expectedVersionId = result.versionId,
                        generation = generation,
                        completedMutation = true
                    )
                }
            } catch (error: CancellationException) {
                if (generation == productImageScopeGeneration) {
                    restoreProductImageStates(previousStates)
                }
                failedProductImageRollbackStates.remove(productId)
                throw error
            } catch (error: ProductImageException) {
                if (generation != productImageScopeGeneration) return@launch
                if (BuildConfig.DEBUG) {
                    val failedPhase = keys.asSequence()
                        .mapNotNull { key -> _productImageStates.value[key]?.mutationPhase }
                        .firstOrNull()
                    val retriable = error.code == "image_upload_failed" &&
                        (error.httpStatus == null || error.httpStatus in 500..599)
                    Log.w(
                        "ProductImage",
                        "operation=upload outcome=failed " +
                            "errorCode=${error.code} " +
                            "httpStatus=${error.httpStatus ?: "none"} " +
                            "phase=${failedPhase?.name ?: "UNKNOWN"} " +
                            "retriable=$retriable"
                    )
                }
                failedProductImageRollbackStates[productId] = previousStates
                keys.forEach { key ->
                    val previous = previousStates[key]
                    updateProductImageState(
                        key,
                        ProductImageUiState(
                            status = ProductImageUiStatus.ERROR,
                            bytes = previous?.bytes,
                            versionId = previous?.versionId,
                            source = previous?.source,
                            errorCode = error.code
                        )
                    )
                }
            } finally {
                onFinished()
                val runningJob = currentCoroutineContext()[Job]
                if (productImageMutationJobs[productId] === runningJob) {
                    productImageMutationJobs.remove(productId)
                }
            }
        }
        productImageMutationJobs[productId] = job
    }

    /**
     * Scarta soltanto lo stato transitorio di un tentativo fallito. L'immagine
     * corrente resta quella fotografata prima della mutation e il source URI
     * non viene conservato per un retry implicito.
     */
    fun discardFailedProductImageOperation(productId: Long) {
        if (productImageMutationJobs[productId]?.isActive == true) return
        val rollback = failedProductImageRollbackStates.remove(productId) ?: return
        val keys = ProductImageVariant.entries.map { ProductImageUiKey(productId, it) }
        cancelProductImageLoadsForMutation(keys)
        restoreProductImageStates(rollback)
    }

    fun closeProductImageEditor(productId: Long) {
        cancelProductImageOperation(productId)
        val keys = ProductImageVariant.entries.map { ProductImageUiKey(productId, it) }
        cancelProductImageLoadsForMutation(keys)
        failedProductImageRollbackStates.remove(productId)?.let(::restoreProductImageStates)
    }

    fun cancelProductImageOperation(productId: Long) {
        val phase = _productImageStates.value[
            ProductImageUiKey(productId, ProductImageVariant.MAIN)
        ]?.mutationPhase
        if (phase in setOf(
                ProductImageMutationPhase.PREPROCESSING,
                ProductImageMutationPhase.UPLOAD_MAIN,
                ProductImageMutationPhase.UPLOAD_THUMB
            )
        ) {
            productImageMutationJobs.remove(productId)?.cancel()
        }
    }

    fun removeProductImage(productId: Long) {
        if (productImageMutationJobs[productId]?.isActive == true) return
        val keys = ProductImageVariant.entries.map { ProductImageUiKey(productId, it) }
        val previousStates = keys.associateWith { key -> _productImageStates.value[key] }
        cancelProductImageLoadsForMutation(keys)
        failedProductImageRollbackStates.remove(productId)
        keys.forEach { key ->
            val current = _productImageStates.value[key]
            updateProductImageState(
                key,
                ProductImageUiState(
                    status = ProductImageUiStatus.REMOVING,
                    bytes = current?.bytes,
                    versionId = current?.versionId
                )
            )
        }
        val job = viewModelScope.launch {
            val generation = productImageScopeGeneration
            try {
                productImageService.remove(productId)
                if (generation != productImageScopeGeneration) return@launch
                keys.forEach { key ->
                    desiredProductImageVersions[key] = null
                    updateProductImageState(key, ProductImageUiState(ProductImageUiStatus.ABSENT))
                }
            } catch (error: CancellationException) {
                if (generation == productImageScopeGeneration) {
                    restoreProductImageStates(previousStates)
                }
                throw error
            } catch (error: ProductImageException) {
                if (generation != productImageScopeGeneration) return@launch
                keys.forEach { key ->
                    val current = _productImageStates.value[key]
                    updateProductImageState(
                        key,
                        ProductImageUiState(
                            status = ProductImageUiStatus.ERROR,
                            bytes = current?.bytes,
                            versionId = current?.versionId,
                            errorCode = error.code
                        )
                    )
                }
            } finally {
                val runningJob = currentCoroutineContext()[Job]
                if (productImageMutationJobs[productId] === runningJob) {
                    productImageMutationJobs.remove(productId)
                }
            }
        }
        productImageMutationJobs[productId] = job
    }

    private suspend fun loadProductImageNow(
        key: ProductImageUiKey,
        expectedVersionId: String?,
        generation: Long,
        completedMutation: Boolean = false
    ) {
        val request = ProductImageLoadRequest(key.productId, key.variant, expectedVersionId)
        val item = try {
            productImageService.loadBatch(listOf(request)).single()
        } catch (error: ProductImageException) {
            ProductImageBatchItem(request, errorCode = error.code)
        }
        applyProductImageBatchItem(key, expectedVersionId, generation, item, completedMutation)
    }

    private fun scheduleVisibleThumbBatch() {
        if (visibleThumbBatchJob?.isActive == true) return
        visibleThumbBatchJob = viewModelScope.launch {
            try {
                while (pendingVisibleThumbs.isNotEmpty()) {
                    delay(PRODUCT_IMAGE_VISIBLE_BATCH_WINDOW_MS)
                    val snapshot = pendingVisibleThumbs.toMap()
                    pendingVisibleThumbs.keys.removeAll(snapshot.keys)
                    val requests = snapshot.mapNotNull { (productId, versionId) ->
                        if (visibleThumbVersions[productId] == versionId && versionId != null) {
                            ProductImageLoadRequest(
                                localProductId = productId,
                                variant = ProductImageVariant.THUMB,
                                expectedVersionId = versionId
                            )
                        } else {
                            null
                        }
                    }
                    if (requests.isEmpty()) continue
                    inFlightVisibleThumbProductIds = requests
                        .mapTo(linkedSetOf()) { it.localProductId }
                    requests.forEach { request ->
                        val key = ProductImageUiKey(request.localProductId, request.variant)
                        val current = _productImageStates.value[key]
                        updateProductImageState(
                            key,
                            ProductImageUiState(
                                status = ProductImageUiStatus.LOADING,
                                bytes = current
                                    ?.takeIf { it.versionId == request.expectedVersionId }
                                    ?.bytes,
                                versionId = request.expectedVersionId
                            )
                        )
                    }
                    val generation = productImageScopeGeneration
                    val items = try {
                        productImageService.loadBatch(requests)
                    } catch (error: ProductImageException) {
                        requests.map { request ->
                            ProductImageBatchItem(request, errorCode = error.code)
                        }
                    } finally {
                        inFlightVisibleThumbProductIds = emptySet()
                    }
                    items.forEach { item ->
                        val key = ProductImageUiKey(
                            item.request.localProductId,
                            item.request.variant
                        )
                        if (visibleThumbVersions[item.request.localProductId] ==
                            item.request.expectedVersionId
                        ) {
                            applyProductImageBatchItem(
                                key,
                                item.request.expectedVersionId,
                                generation,
                                item
                            )
                        }
                    }
                }
            } finally {
                visibleThumbBatchJob = null
                if (pendingVisibleThumbs.isNotEmpty()) scheduleVisibleThumbBatch()
            }
        }
    }

    private fun applyProductImageBatchItem(
        key: ProductImageUiKey,
        expectedVersionId: String?,
        generation: Long,
        item: ProductImageBatchItem,
        completedMutation: Boolean = false
    ) {
        if (generation != productImageScopeGeneration ||
            (!completedMutation && productImageMutationJobs[key.productId]?.isActive == true) ||
            !desiredProductImageVersions.containsKey(key) ||
            desiredProductImageVersions[key] != expectedVersionId
        ) {
            return
        }
        when (val result = item.result) {
            ProductImageLoadResult.Absent ->
                updateProductImageState(key, ProductImageUiState(ProductImageUiStatus.ABSENT))

            is ProductImageLoadResult.Ready -> updateProductImageState(
                key,
                ProductImageUiState(
                    status = ProductImageUiStatus.READY,
                    bytes = result.bytes,
                    versionId = result.versionId,
                    source = result.source,
                    mutationPhase = ProductImageMutationPhase.COMPLETED.takeIf {
                        completedMutation
                    }
                )
            )

            null -> {
                val current = _productImageStates.value[key]
                updateProductImageState(
                    key,
                    ProductImageUiState(
                        status = ProductImageUiStatus.ERROR,
                        bytes = current?.takeIf { it.versionId == expectedVersionId }?.bytes,
                        versionId = expectedVersionId,
                        errorCode = item.errorCode ?: "image_request_failed"
                    )
                )
            }
        }
    }

    private fun observeProductImageScope() {
        viewModelScope.launch {
            var previousScope: ProductImageOwnerShopScope? = null
            combine(
                merchandiseApplication.authManager.state,
                merchandiseApplication.shopContextRepository.state
            ) { auth, shopContext ->
                when (auth) {
                    AuthState.Checking -> ProductImageOwnerShopScope("", null)
                    AuthState.SignedOut,
                    is AuthState.ErrorRecoverable -> ProductImageOwnerShopScope("", null)
                    is AuthState.SignedIn -> {
                        if (
                            shopContext.ownerUserId != auth.userId ||
                            shopContext.isLoading ||
                            !shopContext.syncAllowed
                        ) {
                            ProductImageOwnerShopScope("", null)
                        } else {
                            ProductImageOwnerShopScope(auth.userId, shopContext.activeShopId)
                        }
                    }
                }
            }
                .distinctUntilChanged()
                .collect { currentScope ->
                    val oldScope = previousScope
                    previousScope = currentScope
                    if (oldScope == null || oldScope == currentScope) return@collect

                    productImageScopeGeneration += 1
                    _productImageScopeEpoch.value = productImageScopeGeneration
                    productEditorSaveJob?.cancel()
                    productEditorSaveJob = null
                    _productEditorTarget.value = null
                    _productEditorOperationState.value = ProductEditorOperationState.Idle
                    clearStagedProductImage(deleteFile = true)
                    pendingStagedProductImages.values.forEach(::deleteStagedImageFile)
                    pendingStagedProductImages.clear()
                    val jobsToJoin = buildSet {
                        addAll(productImageLoadJobs.values)
                        addAll(productImageMutationJobs.values)
                        visibleThumbBatchJob?.let { add(it) }
                    }
                    jobsToJoin.forEach(Job::cancel)
                    productImageLoadJobs.clear()
                    productImageMutationJobs.clear()
                    failedProductImageRollbackStates.clear()
                    visibleThumbBatchJob?.cancel()
                    visibleThumbBatchJob = null
                    inFlightVisibleThumbProductIds = emptySet()
                    desiredProductImageVersions.clear()
                    visibleThumbVersions.clear()
                    pendingVisibleThumbs.clear()
                    _productImageStates.value = emptyMap()

                    jobsToJoin.joinAll()

                    val oldAccountId = oldScope.accountId
                    if (oldAccountId.isNotBlank()) {
                        try {
                            val oldShopOnly = oldScope.shopId.takeIf {
                                currentScope.accountId == oldAccountId &&
                                    currentScope.accountId.isNotBlank()
                            }
                            productImageService.purgeScope(oldAccountId, oldShopOnly)
                        } catch (_: ProductImageException) {
                            // Separazione memoria immediata; purge disco best-effort.
                        }
                    }
                }
        }
    }

    private fun updateProductImageState(key: ProductImageUiKey, state: ProductImageUiState) {
        _productImageStates.update { current -> current + (key to state) }
    }

    private fun cancelProductImageLoadsForMutation(keys: List<ProductImageUiKey>) {
        keys.mapNotNull(productImageLoadJobs::remove).toSet().forEach(Job::cancel)
        keys.forEach(desiredProductImageVersions::remove)
    }

    private fun restoreProductImageStates(
        states: Map<ProductImageUiKey, ProductImageUiState?>
    ) {
        _productImageStates.update { current ->
            states.entries.fold(current) { restored, (key, state) ->
                if (state == null) restored - key else restored + (key to state)
            }
        }
    }

    private val _supplierInputText = MutableStateFlow("")
    val supplierInputText: StateFlow<String> = _supplierInputText.asStateFlow()

    fun onSupplierSearchQueryChanged(query: String) {
        _supplierInputText.value = query
    }

    val suppliers: StateFlow<List<Supplier>> = _supplierInputText
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { query -> repository.observeSuppliersForHubSearch(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _categoryInputText = MutableStateFlow("")
    val categoryInputText: StateFlow<String> = _categoryInputText.asStateFlow()

    fun onCategorySearchQueryChanged(query: String) {
        _categoryInputText.value = query
    }

    val categories: StateFlow<List<Category>> = _categoryInputText
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { query -> repository.observeCategoriesForHubSearch(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private var pendingPriceHistory: List<ImportPriceHistoryEntry> = emptyList()
    private var pendingSupplierNames: Set<String> = emptySet()
    private var pendingCategoryNames: Set<String> = emptySet()
    private var pendingTempSuppliers: Map<Long, String> = emptyMap()
    private var pendingTempCategories: Map<Long, String> = emptyMap()
    private var pendingPriceHistoryRepresentsFullDatabase: Boolean = false
    private var pendingImportDiagnostics: ImportApplyDiagnostics? = null
    private var pendingDbSnapshotFingerprint: ImportDatasetFingerprint? = null
    private var activePreviewId: Long? = null
    private var nextPreviewId = 1L
    private var nextPendingSupplierTempId = -1L
    private var nextPendingCategoryTempId = -1L

    private fun analysisErrorMessage(context: Context, throwable: Throwable): String {
        return resolveExcelFileErrorMessage(
            context = context,
            throwable = throwable,
            unknownFallbackResId = R.string.error_data_analysis_generic
        )
    }

    private fun importErrorMessage(context: Context, throwable: Throwable): String {
        return resolveExcelFileErrorMessage(
            context = context,
            throwable = throwable,
            unknownFallbackResId = R.string.error_import_generic
        )
    }

    private fun exportErrorMessage(context: Context, throwable: Throwable): String {
        return when (throwable) {
            is OutOfMemoryError -> context.getString(R.string.error_file_too_large_or_complex)
            is SecurityException, is IOException ->
                context.getString(R.string.error_file_access_denied)
            else -> context.getString(R.string.error_export_generic)
        }
    }

    private fun updateExportUiState(snapshot: ExportProgressSnapshot) {
        _exportUiState.value = ExportUiState(
            inProgress = true,
            message = snapshot.message,
            progress = snapshot.progress
        )
    }

    private fun clearExportUiState() {
        _exportUiState.value = ExportUiState()
    }

    fun selectHubTab(tab: DatabaseHubTab) {
        _selectedHubTab.value = tab
    }

    fun onCatalogQueryChanged(kind: CatalogEntityKind, query: String) {
        when (kind) {
            CatalogEntityKind.SUPPLIER -> _supplierCatalogQuery.value = query
            CatalogEntityKind.CATEGORY -> _categoryCatalogQuery.value = query
        }
    }

    fun retryCatalogSection(kind: CatalogEntityKind) {
        refreshCatalogSection(kind)
    }

    private fun allocatePreviewId(): Long = nextPreviewId++

    private fun updatePendingTempCounters() {
        nextPendingSupplierTempId = (pendingTempSuppliers.keys.minOrNull() ?: 0L) - 1L
        nextPendingCategoryTempId = (pendingTempCategories.keys.minOrNull() ?: 0L) - 1L
    }

    private fun publishPreviewAnalysis(
        analysis: ImportAnalysis,
        pendingPriceHistory: List<ImportPriceHistoryEntry> = emptyList(),
        pendingSupplierNames: Set<String> = emptySet(),
        pendingCategoryNames: Set<String> = emptySet(),
        pendingTempSuppliers: Map<Long, String> = emptyMap(),
        pendingTempCategories: Map<Long, String> = emptyMap(),
        priceHistoryRepresentsFullDatabase: Boolean = false,
        diagnostics: ImportApplyDiagnostics? = null,
        navigationOrigin: ImportNavOrigin = ImportNavOrigin.HOME
    ) {
        _importNavigationOrigin.value = navigationOrigin
        _importAnalysisResult.value = analysis
        this.pendingPriceHistory = pendingPriceHistory
        this.pendingSupplierNames = pendingSupplierNames
        this.pendingCategoryNames = pendingCategoryNames
        this.pendingTempSuppliers = pendingTempSuppliers
        this.pendingTempCategories = pendingTempCategories
        this.pendingPriceHistoryRepresentsFullDatabase = priceHistoryRepresentsFullDatabase
        this.pendingImportDiagnostics = diagnostics
        updatePendingTempCounters()
        val previewId = allocatePreviewId()
        activePreviewId = previewId
        _importFlowState.value = ImportFlowState.PreviewReady(previewId)
    }

    private fun clearPendingImportState(clearAnalysisResult: Boolean) {
        if (clearAnalysisResult) {
            _importAnalysisResult.value = null
            _importNavigationOrigin.value = ImportNavOrigin.HOME
        }
        pendingPriceHistory = emptyList()
        pendingSupplierNames = emptySet()
        pendingCategoryNames = emptySet()
        pendingTempSuppliers = emptyMap()
        pendingTempCategories = emptyMap()
        pendingPriceHistoryRepresentsFullDatabase = false
        pendingImportDiagnostics = null
        pendingDbSnapshotFingerprint = null
        activePreviewId = null
        nextPendingSupplierTempId = -1L
        nextPendingCategoryTempId = -1L
    }

    fun cancelImportPreview() {
        val previewId = activePreviewId
        clearPendingImportState(clearAnalysisResult = true)
        _importFlowState.value = if (previewId != null) {
            ImportFlowState.Cancelled(previewId)
        } else {
            ImportFlowState.Idle
        }
    }

    fun dismissImportPreview() {
        clearPendingImportState(clearAnalysisResult = true)
        _importFlowState.value = ImportFlowState.Idle
    }

    private fun markPreviewLoading() {
        _importFlowState.value = ImportFlowState.PreviewLoading
    }

    private fun markPreviewError(message: String) {
        _importFlowState.value = ImportFlowState.Error(
            previewId = activePreviewId,
            message = message,
            occurredDuringApply = false
        )
    }

    suspend fun resolveImportPreviewSupplierId(name: String): Long? {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return null
        repository.findSupplierByName(normalizedName)?.let { return it.id }
        pendingTempSuppliers.entries.firstOrNull { (_, value) ->
            value.equals(normalizedName, ignoreCase = true)
        }?.let { return it.key }

        val tempId = nextPendingSupplierTempId--
        pendingTempSuppliers = pendingTempSuppliers + (tempId to normalizedName)
        return tempId
    }

    suspend fun resolveImportPreviewCategoryId(name: String): Long? {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return null
        repository.findCategoryByName(normalizedName)?.let { return it.id }
        pendingTempCategories.entries.firstOrNull { (_, value) ->
            value.equals(normalizedName, ignoreCase = true)
        }?.let { return it.key }

        val tempId = nextPendingCategoryTempId--
        pendingTempCategories = pendingTempCategories + (tempId to normalizedName)
        return tempId
    }

    suspend fun getSupplierDisplayName(id: Long?): String? {
        if (id == null) return null
        if (id < 0L) return pendingTempSuppliers[id]
        return repository.getSupplierById(id)?.name
    }

    suspend fun getCategoryDisplayName(id: Long?): String? {
        if (id == null) return null
        if (id < 0L) return pendingTempCategories[id]
        return repository.getCategoryById(id)?.name
    }

    fun setFilter(text: String) {
        _filter.value = text.ifBlank { null }
    }

    private fun catalogSectionState(
        kind: CatalogEntityKind,
        queryFlow: StateFlow<String>,
        refreshFlow: StateFlow<Int>
    ): StateFlow<CatalogSectionUiState> {
        val debouncedQuery = queryFlow
            .debounce(250L)
            .distinctUntilChanged()

        return combine(debouncedQuery, refreshFlow) { query, _ -> query }
            .flatMapLatest { query ->
                val trimmedQuery = query.trim().takeIf { it.isNotEmpty() }
                repository.observeCatalogItems(kind, trimmedQuery)
                    .map { items ->
                        CatalogSectionUiState(query = query, isLoading = false, items = items)
                    }
                    .onStart {
                        emit(CatalogSectionUiState(query = query, isLoading = true))
                    }
                    .catch { throwable ->
                        Log.e("DATABASE_HUB", "Unable to load catalog section: $kind", throwable)
                        emit(
                            CatalogSectionUiState(
                                query = query,
                                isLoading = false,
                                errorMessage = catalogLoadErrorMessage(kind)
                            )
                        )
                    }
            }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = CatalogSectionUiState(isLoading = true)
        )
    }

    private fun refreshCatalogSection(kind: CatalogEntityKind) {
        when (kind) {
            CatalogEntityKind.SUPPLIER -> _supplierCatalogRefresh.value += 1
            CatalogEntityKind.CATEGORY -> _categoryCatalogRefresh.value += 1
        }
    }

    private fun catalogEntityLabel(kind: CatalogEntityKind): String =
        appContext.getString(
            when (kind) {
                CatalogEntityKind.SUPPLIER -> R.string.database_catalog_entity_supplier
                CatalogEntityKind.CATEGORY -> R.string.database_catalog_entity_category
            }
        )

    private fun catalogLoadErrorMessage(kind: CatalogEntityKind): String =
        appContext.getString(
            when (kind) {
                CatalogEntityKind.SUPPLIER -> R.string.database_suppliers_load_failed
                CatalogEntityKind.CATEGORY -> R.string.database_categories_load_failed
            }
        )

    private fun catalogOperationErrorMessage(
        kind: CatalogEntityKind,
        throwable: Throwable
    ): String = when (throwable) {
        is CatalogTextValidationException ->
            appContext.catalogTextErrorMessage(throwable.rejection)

        is CatalogBlankNameException -> appContext.getString(
            R.string.database_catalog_name_required,
            catalogEntityLabel(kind)
        )

        is CatalogNameConflictException -> appContext.getString(
            R.string.database_catalog_name_exists,
            catalogEntityLabel(kind)
        )

        is CatalogEntityInUseException -> appContext.resources.getQuantityString(
            R.plurals.database_catalog_delete_requires_resolution,
            throwable.productCount,
            catalogEntityLabel(kind),
            throwable.productCount
        )

        is CatalogInvalidReplacementException -> appContext.getString(
            R.string.database_catalog_replacement_invalid,
            catalogEntityLabel(kind)
        )

        is CatalogNotFoundException -> appContext.getString(
            R.string.database_catalog_item_missing,
            catalogEntityLabel(kind)
        )

        else -> appContext.getString(
            R.string.database_catalog_operation_failed,
            catalogEntityLabel(kind)
        )
    }

    private fun catalogDeleteSuccessMessage(
        kind: CatalogEntityKind,
        result: CatalogDeleteResult
    ): String = when (result.strategy) {
        CatalogDeleteStrategy.DeleteIfUnused -> appContext.getString(
            R.string.database_catalog_deleted,
            catalogEntityLabel(kind)
        )

        is CatalogDeleteStrategy.ReplaceWithExisting,
        is CatalogDeleteStrategy.CreateNewAndReplace -> appContext.resources.getQuantityString(
            R.plurals.database_catalog_deleted_reassigned,
            result.affectedProducts,
            catalogEntityLabel(kind),
            result.affectedProducts,
            result.replacementName.orEmpty()
        )

        CatalogDeleteStrategy.ClearAssignments -> appContext.resources.getQuantityString(
            R.plurals.database_catalog_deleted_cleared,
            result.affectedProducts,
            catalogEntityLabel(kind),
            result.affectedProducts
        )
    }

    private suspend fun <T> runCatalogMutation(
        kind: CatalogEntityKind,
        successMessage: (T) -> String,
        action: suspend () -> T
    ): T? = try {
        val result = action()
        _uiState.value = UiState.Success(successMessage(result))
        result
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) {
            throw throwable
        }
        throwable.printStackTrace()
        _uiState.value = UiState.Error(catalogOperationErrorMessage(kind, throwable))
        null
    }

    private val _importAnalysisResult = MutableStateFlow<ImportAnalysis?>(null)
    val importAnalysisResult: StateFlow<ImportAnalysis?> = _importAnalysisResult.asStateFlow()

    private val _importNavigationOrigin = MutableStateFlow(ImportNavOrigin.HOME)
    val importNavigationOrigin: StateFlow<ImportNavOrigin> = _importNavigationOrigin.asStateFlow()

    fun startSmartImport(context: Context, uri: Uri, navigationOrigin: ImportNavOrigin = ImportNavOrigin.DATABASE) {
        if (importFlowState.value is ImportFlowState.Applying) return
        viewModelScope.launch {
            var fullImportLockAcquired = false
            try {
                when (val outcome = withContext(Dispatchers.IO) {
                    analyzeSmartImportWorkbook(
                        context = context,
                        uri = uri,
                        repository = repository,
                        loadCurrentDbProducts = {
                            if (!importMutex.tryLock()) {
                                throw FullImportAlreadyInProgressException()
                            }
                            fullImportLockAcquired = true
                            prepareSmartFullImportAnalysis(context, uri)
                        }
                    )
                }) {
                    SmartImportWorkbookOutcome.SingleSheet -> startImportAnalysis(context, uri, navigationOrigin)
                    is SmartImportWorkbookOutcome.FullDatabaseAnalyzed ->
                        finalizeFullImportAnalysisSuccess(outcome.result, navigationOrigin)
                }
            } catch (_: FullImportAlreadyInProgressException) {
                return@launch
            } catch (e: CancellationException) {
                if (fullImportLockAcquired) {
                    handleSmartFullImportCancelled()
                }
                throw e
            } catch (e: OutOfMemoryError) {
                if (fullImportLockAcquired) {
                    handleSmartFullImportFailure(context, e)
                } else {
                    e.printStackTrace()
                    clearPendingImportState(clearAnalysisResult = true)
                    val userMessage = analysisErrorMessage(context, e)
                    _uiState.value = UiState.Error(userMessage)
                    markPreviewError(userMessage)
                }
            } catch (e: Exception) {
                if (fullImportLockAcquired) {
                    handleSmartFullImportFailure(context, e)
                } else {
                    handleImportAnalysisError(context, e)
                }
            } finally {
                if (fullImportLockAcquired) {
                    importMutex.unlock()
                }
            }
        }
    }

    private suspend fun prepareSmartFullImportAnalysis(
        context: Context,
        uri: Uri
    ): List<Product> {
        clearPendingImportState(clearAnalysisResult = true)
        markPreviewLoading()
        withContext(Dispatchers.Main) {
            _uiState.value = UiState.Loading(
                message = context.getString(R.string.import_loading_file),
                progress = 5
            )
        }

        Log.d("DB_IMPORT", "FULL_IMPORT START uri=$uri")

        withContext(Dispatchers.Main) {
            _uiState.value = UiState.Loading(
                message = context.getString(R.string.import_fetching_db),
                progress = 55
            )
        }
        val dbSnapshot = loadFullImportDbSnapshot()
        pendingDbSnapshotFingerprint = dbSnapshot.fingerprint

        withContext(Dispatchers.Main) {
            _uiState.value = UiState.Loading(
                message = context.getString(R.string.import_analyzing),
                progress = 85
            )
        }

        return dbSnapshot.products
    }

    private suspend fun loadFullImportDbSnapshot(): FullImportDbSnapshot {
        val products = repository.getAllProducts()
        val productDetails = repository.getAllProductsWithDetails()
        val suppliers = repository.getAllSuppliers()
        val categories = repository.getAllCategories()
        val priceHistoryRows = repository.getAllPriceHistoryRows()
        return FullImportDbSnapshot(
            products = products,
            fingerprint = buildDatabaseSnapshotFingerprint(
                products = productDetails,
                suppliers = suppliers,
                categories = categories,
                priceHistoryRows = priceHistoryRows
            )
        )
    }

    private fun buildImportApplyDiagnostics(
        importResult: com.example.merchandisecontrolsplitview.util.FullDbImportStreamingResult
    ): ImportApplyDiagnostics? {
        val dbSnapshot = pendingDbSnapshotFingerprint ?: return null
        val importFingerprint = importResult.datasetFingerprint
        return ImportApplyDiagnostics(
            fileProductCount = importFingerprint.productCount,
            fileSupplierCount = importFingerprint.supplierCount,
            fileCategoryCount = importFingerprint.categoryCount,
            filePriceHistoryCount = importFingerprint.priceHistoryCount,
            dbProductCountBefore = dbSnapshot.productCount,
            dbSupplierCountBefore = dbSnapshot.supplierCount,
            dbCategoryCountBefore = dbSnapshot.categoryCount,
            dbPriceHistoryCountBefore = dbSnapshot.priceHistoryCount,
            importFingerprintShort = importFingerprint.fingerprintShort,
            dbSnapshotFingerprintShort = dbSnapshot.fingerprintShort
        )
    }

    private fun logFullImportSuccess(
        importResult: com.example.merchandisecontrolsplitview.util.FullDbImportStreamingResult,
        diagnostics: ImportApplyDiagnostics?
    ) {
        val diagnosticsLog = diagnostics?.let {
            "fileProductCount=${it.fileProductCount} " +
                "fileSupplierCount=${it.fileSupplierCount} " +
                "fileCategoryCount=${it.fileCategoryCount} " +
                "filePriceHistoryCount=${it.filePriceHistoryCount} " +
                "dbProductCountBefore=${it.dbProductCountBefore} " +
                "dbSupplierCountBefore=${it.dbSupplierCountBefore} " +
                "dbCategoryCountBefore=${it.dbCategoryCountBefore} " +
                "dbPriceHistoryCountBefore=${it.dbPriceHistoryCountBefore} " +
                "importFingerprintShort=${it.importFingerprintShort} " +
                "dbSnapshotFingerprintShort=${it.dbSnapshotFingerprintShort} " +
                "classificazione_risultato=${it.resultClassification}"
        }.orEmpty()
        Log.d(
            "DB_IMPORT",
            "FULL_IMPORT SUCCESS products=${importResult.productsRowCount} " +
                "suppliers=${importResult.supplierRowCount} " +
                "categories=${importResult.categoryRowCount} " +
                "priceHistory=${importResult.hasPriceHistorySheet} " +
                diagnosticsLog
        )
    }

    private suspend fun finalizeFullImportAnalysisSuccess(
        importResult: com.example.merchandisecontrolsplitview.util.FullDbImportStreamingResult,
        navigationOrigin: ImportNavOrigin
    ) {
        val diagnostics = buildImportApplyDiagnostics(importResult)
        publishPreviewAnalysis(
            analysis = importResult.analysis.analysis,
            pendingPriceHistory = importResult.pendingPriceHistory,
            pendingSupplierNames = importResult.pendingSupplierNames,
            pendingCategoryNames = importResult.pendingCategoryNames,
            pendingTempSuppliers = importResult.analysis.pendingSuppliers,
            pendingTempCategories = importResult.analysis.pendingCategories,
            diagnostics = diagnostics,
            navigationOrigin = navigationOrigin
        )
        _uiState.value = UiState.Idle
        logFullImportSuccess(importResult, diagnostics)
    }

    private suspend fun handleSmartFullImportCancelled() {
        cancelImportPreview()
        _uiState.value = UiState.Idle
        Log.w("DB_IMPORT", "FULL_IMPORT CANCELLED")
    }

    private suspend fun handleSmartFullImportFailure(
        context: Context,
        throwable: Throwable
    ) {
        clearPendingImportState(clearAnalysisResult = true)
        val userMessage = analysisErrorMessage(context, throwable)
        _uiState.value = UiState.Error(userMessage)
        markPreviewError(userMessage)
        Log.e("DB_IMPORT", "FULL_IMPORT FAILED message=$userMessage", throwable)
    }

    fun startImportAnalysis(
        context: Context,
        uri: Uri,
        navigationOrigin: ImportNavOrigin = ImportNavOrigin.HOME
    ) {
        if (importFlowState.value is ImportFlowState.Applying) return
        clearPendingImportState(clearAnalysisResult = true)
        markPreviewLoading()
        _uiState.value = UiState.Loading(message = context.getString(R.string.import_loading_file), progress = 5)
        viewModelScope.launch {
            try {
                val (normalizedHeader, dataRows) = parseImportFile(context, uri)
                if (!validateImportFile(context, normalizedHeader, dataRows)) return@launch

                _uiState.value = UiState.Loading(message = context.getString(R.string.import_mapping_rows), progress = 30)

                val currentDbProducts = fetchCurrentDatabaseProducts()

                _uiState.value = UiState.Loading(message = context.getString(R.string.import_analyzing), progress = 85)

                val chunks = buildChunkedRows(normalizedHeader, dataRows)
                val analysis = analyzeImportStreaming(context, chunks, currentDbProducts)

                publishPreviewAnalysis(
                    analysis = analysis.analysis,
                    pendingTempSuppliers = analysis.pendingSuppliers,
                    pendingTempCategories = analysis.pendingCategories,
                    navigationOrigin = navigationOrigin
                )
                _uiState.value = UiState.Idle
            } catch (e: OutOfMemoryError) {
                e.printStackTrace()
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
            } catch (e: Exception) {
                handleImportAnalysisError(context, e)
            }
        }
    }

    private suspend fun parseImportFile(
        context: Context,
        uri: Uri
    ): Pair<List<String>, List<List<String>>> {
        val (normalizedHeader, dataRows, _) = withContext(Dispatchers.IO) {
            readAndAnalyzeExcel(context, uri)
        }
        return normalizedHeader to dataRows
    }

    private fun validateImportFile(
        context: Context,
        normalizedHeader: List<String>,
        dataRows: List<List<String>>
    ): Boolean {
        if (normalizedHeader.isEmpty() || dataRows.isEmpty()) {
            val userMessage = context.getString(R.string.error_file_empty_or_invalid)
            _uiState.value = UiState.Error(userMessage)
            markPreviewError(userMessage)
            return false
        }
        return true
    }

    private suspend fun fetchCurrentDatabaseProducts(): List<Product> {
        _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_fetching_db), progress = 55)
        return withContext(Dispatchers.IO) {
            repository.getAllProducts()
        }
    }

    private fun buildChunkedRows(
        normalizedHeader: List<String>,
        dataRows: List<List<String>>
    ): Sequence<List<Map<String, String>>> {
        return sequence {
            val seq = dataRows.asSequence().map { row ->
                normalizedHeader.mapIndexed { index, headerKey ->
                    headerKey to (row.getOrNull(index) ?: "")
                }.toMap()
            }
            for (block in seq.chunked(1000)) yield(block)
        }
    }

    private suspend fun analyzeImportStreaming(
        context: Context,
        chunks: Sequence<List<Map<String, String>>>,
        currentDbProducts: List<Product>
    ): ImportAnalyzer.DeferredRelationImportAnalysis {
        return withContext(Dispatchers.Default) {
            ImportAnalyzer.analyzeStreamingDeferredRelations(
                context = context,
                currentDbProducts = currentDbProducts,
                repository = repository
            ) { consumer ->
                chunks.forEach { chunk -> chunk.forEach(consumer) }
            }
        }
    }

    private fun handleImportAnalysisError(context: Context, e: Exception) {
        e.printStackTrace()
        clearPendingImportState(clearAnalysisResult = true)
        val userMessage = analysisErrorMessage(context, e)
        _uiState.value = UiState.Error(userMessage)
        markPreviewError(userMessage)
    }

    fun clearImportAnalysis() {
        when (importFlowState.value) {
            is ImportFlowState.Applying -> Unit
            is ImportFlowState.PreviewReady,
            is ImportFlowState.PreviewLoading -> cancelImportPreview()
            else -> dismissImportPreview()
        }
    }

    /**
     * Dopo un errore in fase di apply: torna a preview senza perdere l’analisi (task 044C).
     */
    fun recoverImportPreviewAfterApplyError() {
        val previewId = activePreviewId
        if (previewId != null && _importAnalysisResult.value != null) {
            _importFlowState.value = ImportFlowState.PreviewReady(previewId)
        }
        _uiState.value = UiState.Idle
    }

    fun importProducts(
        previewId: Long,
        newProducts: List<Product>,
        updatedProducts: List<ProductUpdate>,
        context: Context
    ) {
        val hasMatchingPreview = when (val state = importFlowState.value) {
            is ImportFlowState.PreviewReady -> state.previewId == previewId
            is ImportFlowState.Error -> state.previewId == previewId
            else -> false
        }
        if (!hasMatchingPreview || activePreviewId != previewId) {
            val message = context.getString(R.string.import_preview_invalidated)
            _uiState.value = UiState.Error(message)
            _importFlowState.value = ImportFlowState.Error(
                previewId = activePreviewId,
                message = message,
                occurredDuringApply = false
            )
            return
        }
        if (newProducts.isEmpty() && updatedProducts.isEmpty()) {
            val message = context.getString(R.string.import_no_valid_rows_to_apply)
            _uiState.value = UiState.Error(message)
            _importFlowState.value = ImportFlowState.Error(
                previewId = previewId,
                message = message,
                occurredDuringApply = false
            )
            return
        }

        val importRequest = ImportApplyRequest(
            newProducts = newProducts,
            updatedProducts = updatedProducts,
            pendingSupplierNames = pendingSupplierNames.toSet(),
            pendingCategoryNames = pendingCategoryNames.toSet(),
            pendingTempSuppliers = pendingTempSuppliers.toMap(),
            pendingTempCategories = pendingTempCategories.toMap(),
            pendingPriceHistory = pendingPriceHistory.toList(),
            priceHistoryRepresentsFullDatabase = pendingPriceHistoryRepresentsFullDatabase,
            diagnostics = pendingImportDiagnostics
        )
        _importFlowState.value = ImportFlowState.Applying(previewId)

        _uiState.value = UiState.Loading(
            message = context.getString(R.string.import_applying_changes),
            progress = 90
        )

        viewModelScope.launch {
            Log.d(
                "DB_IMPORT",
                "APPLY_IMPORT START previewId=$previewId new=${newProducts.size} updated=${updatedProducts.size}"
            )

            try {
                when (val outcome = withContext(Dispatchers.IO) {
                    repository.applyImport(importRequest)
                }) {
                    ImportApplyResult.Success -> {
                        _uiState.value = UiState.Loading(
                            message = context.getString(R.string.import_applying_changes),
                            progress = 98
                        )

                        Log.d("DB_IMPORT", "APPLY_IMPORT SUCCESS previewId=$previewId")
                        _importFlowState.value = ImportFlowState.Success(previewId)
                        _uiState.value = UiState.Success(context.getString(R.string.import_success))
                    }
                    ImportApplyResult.AlreadyRunning -> {
                        val userMessage = context.getString(R.string.operation_in_progress)
                        _uiState.value = UiState.Error(userMessage)
                        _importFlowState.value = ImportFlowState.Error(
                            previewId = previewId,
                            message = userMessage,
                            occurredDuringApply = true
                        )
                    }
                    is ImportApplyResult.Failure -> {
                        val userMessage = importErrorMessage(context, outcome.cause)
                        _uiState.value = UiState.Error(userMessage)
                        _importFlowState.value = ImportFlowState.Error(
                            previewId = previewId,
                            message = userMessage,
                            occurredDuringApply = true
                        )
                        Log.e("DB_IMPORT", "APPLY_IMPORT FAILED previewId=$previewId", outcome.cause)
                    }
                }
            } catch (e: CancellationException) {
                val userMessage = importErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                _importFlowState.value = ImportFlowState.Error(
                    previewId = previewId,
                    message = userMessage,
                    occurredDuringApply = true
                )
                Log.e("DB_IMPORT", "APPLY_IMPORT CANCELLED previewId=$previewId", e)
            } catch (e: Exception) {
                val userMessage = importErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                _importFlowState.value = ImportFlowState.Error(
                    previewId = previewId,
                    message = userMessage,
                    occurredDuringApply = true
                )
                Log.e("DB_IMPORT", "APPLY_IMPORT FAILED previewId=$previewId", e)
            }
        }
    }

    fun exportDatabase(
        context: Context,
        uri: Uri,
        selection: ExportSheetSelection
    ) {
        if (selection.isEmpty || !exportMutex.tryLock()) return

        val progressTracker = ExportProgressTracker(context, selection)
        updateExportUiState(progressTracker.preparing())

        viewModelScope.launch {
            try {
                val schema = buildDatabaseExportSchema(context)

                val suppliers = if (selection.suppliers) {
                    updateExportUiState(progressTracker.fetching(DatabaseExportSheet.SUPPLIERS))
                    repository.getAllSuppliers().also {
                        updateExportUiState(progressTracker.fetched(DatabaseExportSheet.SUPPLIERS))
                    }
                } else {
                    emptyList()
                }

                val categories = if (selection.categories) {
                    updateExportUiState(progressTracker.fetching(DatabaseExportSheet.CATEGORIES))
                    repository.getAllCategories().also {
                        updateExportUiState(progressTracker.fetched(DatabaseExportSheet.CATEGORIES))
                    }
                } else {
                    emptyList()
                }

                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        writeDatabaseExportStreaming(
                            outputStream = outputStream,
                            selection = selection,
                            schema = schema,
                            suppliers = suppliers,
                            categories = categories,
                            fetchProductPage = { limit, offset ->
                                repository.getProductsWithDetailsPage(limit, offset)
                            },
                            fetchPriceHistoryPage = { limit, offset ->
                                repository.getPriceHistoryRowsPage(limit, offset)
                            },
                            onBeforeProductsSheet = {
                                if (selection.products) {
                                    updateExportUiState(progressTracker.fetching(DatabaseExportSheet.PRODUCTS))
                                }
                            },
                            onAfterFirstProductPageFetched = {
                                if (selection.products) {
                                    updateExportUiState(progressTracker.fetched(DatabaseExportSheet.PRODUCTS))
                                }
                            },
                            onBeforePriceHistorySheet = {
                                if (selection.priceHistory) {
                                    updateExportUiState(progressTracker.fetching(DatabaseExportSheet.PRICE_HISTORY))
                                }
                            },
                            onAfterFirstPriceHistoryPageFetched = {
                                if (selection.priceHistory) {
                                    updateExportUiState(progressTracker.fetched(DatabaseExportSheet.PRICE_HISTORY))
                                }
                            },
                            onSheetWritten = { sheet ->
                                updateExportUiState(progressTracker.sheetWritten(sheet))
                            }
                        )
                    } ?: throw IOException("Unable to open output stream for $uri")
                }

                updateExportUiState(progressTracker.finishing())
                _uiState.value = UiState.Success(context.getString(R.string.export_success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _uiState.value = UiState.Error(exportErrorMessage(context, e))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(exportErrorMessage(context, e))
            } finally {
                clearExportUiState()
                exportMutex.unlock()
            }
        }
    }

    fun addProduct(product: Product) {
        viewModelScope.launch {
            when (val result = persistProductFromEditor(product, isNewProduct = true)) {
                ProductEditorSaveResult.Saved -> Unit
                is ProductEditorSaveResult.Failed -> _uiState.value = UiState.Error(result.message)
            }
        }
    }

    fun updateProduct(product: Product) {
        viewModelScope.launch {
            when (val result = persistProductFromEditor(product, isNewProduct = false)) {
                ProductEditorSaveResult.Saved -> Unit
                is ProductEditorSaveResult.Failed -> _uiState.value = UiState.Error(result.message)
            }
        }
    }

    /**
     * Salvataggio atteso dall'editor: il chiamante riceve l'esito reale prima di chiudere
     * il dialog. Il repository mantiene prodotto, history e dirty marker nella stessa
     * transazione; un retry parte quindi da uno stato interamente precedente o successivo.
     */
    suspend fun saveProductFromEditor(product: Product): ProductEditorSaveResult =
        persistProductFromEditor(product, isNewProduct = product.id == 0L)

    private suspend fun persistProductFromEditor(
        product: Product,
        isNewProduct: Boolean
    ): ProductEditorSaveResult {
        return try {
            if (isNewProduct) {
                repository.addProduct(product)
            } else {
                productDetailsOverrideMutex.withLock {
                    repository.updateProduct(product)
                    try {
                        refreshProductDetailsOverridesLocked(listOf(product.id))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (refreshFailure: Exception) {
                        // La write e la relativa history sono gia state confermate dalla
                        // transazione repository. Un read-through secondario non deve
                        // trasformare quel successo in un falso errore con retry duplicato.
                        _productDetailsOverrides.update { current -> current - product.id }
                        Log.w(
                            "DB_PRODUCT_SAVE",
                            "Product saved; details override refresh deferred",
                            refreshFailure
                        )
                    }
                }
            }
            _uiState.value = UiState.Success(
                appContext.getString(
                    if (isNewProduct) R.string.success_product_added
                    else R.string.success_product_updated
                )
            )
            ProductEditorSaveResult.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            ProductEditorSaveResult.Failed(
                appContext.getString(R.string.error_barcode_already_exists)
            )
        } catch (_: Exception) {
            ProductEditorSaveResult.Failed(
                appContext.getString(
                    if (isNewProduct) R.string.error_product_added
                    else R.string.error_product_updated
                )
            )
        }
    }

    suspend fun updateCurrentPriceFromHistory(
        productId: Long,
        type: String,
        price: Double
    ): Product? {
        return try {
            val timestamp = LocalDateTime.now().format(PRICE_HISTORY_MANUAL_FORMATTER)
            val updatedProduct = productDetailsOverrideMutex.withLock {
                repository.updateCurrentPriceFromHistory(
                    productId = productId,
                    type = type,
                    price = price,
                    at = timestamp,
                    source = "MANUAL"
                ).also { updated ->
                    if (updated != null) {
                        refreshProductDetailsOverridesLocked(listOf(productId))
                    }
                }
            }
            if (updatedProduct != null) {
                _uiState.value = UiState.Success(
                    appContext.getString(R.string.price_history_current_price_updated)
                )
            }
            updatedProduct
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            _uiState.value = UiState.Error(
                appContext.getString(R.string.price_history_update_failed)
            )
            null
        }
    }

    private fun observeRemoteAppliedProductIds() {
        viewModelScope.launch {
            repository.remoteAppliedProductIds
                .map { productIds -> productIds.filter { it > 0L }.distinct() }
                .filter { it.isNotEmpty() }
                .collect { productIds ->
                    try {
                        refreshProductDetailsOverrides(productIds)
                        productIds.forEach { productId ->
                            if (pendingStagedProductImages.containsKey(productId)) {
                                uploadPendingStagedProductImage(productId)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("DB_REMOTE_REFRESH", "Unable to refresh remote product overrides", e)
                    }
                }
        }
    }

    private fun uploadPendingStagedProductImage(productId: Long) {
        val sourceUri = pendingStagedProductImages[productId] ?: return
        if (productImageMutationJobs[productId]?.isActive == true) return
        viewModelScope.launch {
            val hasRemoteReference = try {
                repository.hasSyncedProductRemoteRef(productId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!hasRemoteReference || pendingStagedProductImages[productId] != sourceUri) {
                return@launch
            }
            uploadProductImage(
                productId = productId,
                sourceUri = sourceUri,
                onCommitted = {
                    if (pendingStagedProductImages[productId] == sourceUri) {
                        pendingStagedProductImages.remove(productId)
                        deleteStagedImageFile(sourceUri)
                    }
                },
                onFinished = {}
            )
        }
    }

    private fun clearStagedProductImage(deleteFile: Boolean) {
        val staged = _stagedProductImageState.value as? StagedProductImageState.Ready
        _stagedProductImageState.value = StagedProductImageState.Empty
        if (deleteFile && staged != null) {
            deleteStagedImageFile(staged.fileUri)
        }
    }

    private fun deleteStagedImageFile(uri: Uri) {
        if (uri.scheme != "file") return
        runCatching { uri.path?.let(::File)?.delete() }
    }

    private suspend fun refreshProductDetailsOverrides(productIds: Iterable<Long>) {
        productDetailsOverrideMutex.withLock {
            refreshProductDetailsOverridesLocked(productIds)
        }
    }

    private suspend fun refreshProductDetailsOverridesLocked(productIds: Iterable<Long>) {
        for (productId in productIds.distinct()) {
            val details = repository.getProductDetailsById(productId)
            _productDetailsOverrides.update { current ->
                if (details != null) {
                    current.withCappedProductDetailsOverride(productId, details)
                } else if (current.containsKey(productId)) {
                    current - productId
                } else {
                    current
                }
            }
        }
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch {
            try {
                productDetailsOverrideMutex.withLock {
                    repository.deleteProduct(product)
                    _productDetailsOverrides.update { current ->
                        if (current.containsKey(product.id)) current - product.id else current
                    }
                }
                _uiState.value = UiState.Success(appContext.getString(R.string.success_product_deleted))
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(appContext.getString(R.string.error_product_deleted))
            }
        }
    }

    fun analyzeGridData(
        gridData: List<Map<String, String>>,
        navigationOrigin: ImportNavOrigin = ImportNavOrigin.HOME
    ) {
        if (importFlowState.value is ImportFlowState.Applying) return
        clearPendingImportState(clearAnalysisResult = true)
        markPreviewLoading()
        _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_analyzing), progress = 10)
        viewModelScope.launch {
            try {
                val currentDbProducts = withContext(Dispatchers.IO) {
                    _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_fetching_db), progress = 30)
                    repository.getAllProducts()
                }
                _uiState.value = UiState.Loading(message = appContext.getString(R.string.import_analyzing), progress = 70)
                val analysis = withContext(Dispatchers.Default) {
                    ImportAnalyzer.analyzeStreamingDeferredRelations(
                        context = appContext,
                        currentDbProducts = currentDbProducts,
                        repository = repository
                    ) { consumer ->
                        gridData.forEach(consumer)
                    }
                }
                publishPreviewAnalysis(
                    analysis = analysis.analysis,
                    pendingTempSuppliers = analysis.pendingSuppliers,
                    pendingTempCategories = analysis.pendingCategories,
                    navigationOrigin = navigationOrigin
                )
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(appContext, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
            }
        }
    }

    suspend fun addSupplier(name: String): Supplier? {
        return repository.addSupplier(name)
    }

    suspend fun addCategory(name: String): Category? {
        return repository.addCategory(name)
    }

    suspend fun createCatalogEntry(
        kind: CatalogEntityKind,
        name: String
    ): CatalogListItem? = runCatalogMutation(
        kind = kind,
        successMessage = {
            appContext.getString(
                R.string.database_catalog_created,
                catalogEntityLabel(kind)
            )
        }
    ) {
        repository.createCatalogEntry(kind, name)
    }

    suspend fun renameCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        newName: String
    ): CatalogListItem? = runCatalogMutation(
        kind = kind,
        successMessage = {
            appContext.getString(
                R.string.database_catalog_renamed,
                catalogEntityLabel(kind)
            )
        }
    ) {
        repository.renameCatalogEntry(kind, id, newName)
    }

    suspend fun deleteCatalogEntry(
        kind: CatalogEntityKind,
        id: Long,
        strategy: CatalogDeleteStrategy
    ): CatalogDeleteResult? = runCatalogMutation(
        kind = kind,
        successMessage = { result -> catalogDeleteSuccessMessage(kind, result) }
    ) {
        repository.deleteCatalogEntry(kind, id, strategy)
    }

    suspend fun getSupplierById(id: Long): Supplier? {
        return repository.getSupplierById(id)
    }

    suspend fun getCategoryById(id: Long): Category? {
        return repository.getCategoryById(id)
    }

    suspend fun findProductByBarcode(barcode: String): Product? {
        return repository.findProductByBarcode(barcode)
    }
    fun getPriceSeries(productId: Long, type: String) =
        repository.getPriceSeries(productId, type)

    // ⬇️ IMPORT COMPLETO: nuovo metodo pubblico
    fun startFullDbImport(
        context: Context,
        uri: Uri,
        navigationOrigin: ImportNavOrigin = ImportNavOrigin.DATABASE
    ) {
        if (importFlowState.value is ImportFlowState.Applying) return
        // blocca se c'è già un import in corso
        if (!importMutex.tryLock()) return
        clearPendingImportState(clearAnalysisResult = true)
        markPreviewLoading()
        _uiState.value = UiState.Loading(message = context.getString(R.string.operation_in_progress))

        viewModelScope.launch {
            try {
                Log.d("DB_IMPORT", "FULL_IMPORT START uri=$uri")

                // 2) lettura/analisi su IO
                _uiState.value = UiState.Loading(
                    message = context.getString(R.string.import_loading_file),
                    progress = 5
                )

                _uiState.value = UiState.Loading(
                    message = context.getString(R.string.import_fetching_db),
                    progress = 55
                )
                val dbSnapshot = withContext(Dispatchers.IO) {
                    loadFullImportDbSnapshot()
                }
                pendingDbSnapshotFingerprint = dbSnapshot.fingerprint
                val currentDbProducts = dbSnapshot.products

                _uiState.value = UiState.Loading(
                    message = context.getString(R.string.import_analyzing),
                    progress = 85
                )

                val importResult = withContext(Dispatchers.IO) {
                    analyzeFullDbImportStreaming(
                        context = context,
                        uri = uri,
                        currentDbProducts = currentDbProducts,
                        repository = repository
                    )
                }

                publishPreviewAnalysis(
                    analysis = importResult.analysis.analysis,
                    pendingPriceHistory = importResult.pendingPriceHistory,
                    pendingSupplierNames = importResult.pendingSupplierNames,
                    pendingCategoryNames = importResult.pendingCategoryNames,
                    pendingTempSuppliers = importResult.analysis.pendingSuppliers,
                    pendingTempCategories = importResult.analysis.pendingCategories,
                    priceHistoryRepresentsFullDatabase = importResult.hasPriceHistorySheet,
                    diagnostics = buildImportApplyDiagnostics(importResult),
                    navigationOrigin = navigationOrigin
                )
                _uiState.value = UiState.Idle
                logFullImportSuccess(importResult, pendingImportDiagnostics)

            } catch (e: CancellationException) {
                cancelImportPreview()
                _uiState.value = UiState.Idle
                Log.w("DB_IMPORT", "FULL_IMPORT CANCELLED")
                throw e

            } catch (e: OutOfMemoryError) {
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
                Log.e("DB_IMPORT", "FULL_IMPORT OOM message=$userMessage", e)

            } catch (e: Exception) {
                clearPendingImportState(clearAnalysisResult = true)
                val userMessage = analysisErrorMessage(context, e)
                _uiState.value = UiState.Error(userMessage)
                markPreviewError(userMessage)
                Log.e("DB_IMPORT", "FULL_IMPORT FAILED message=$userMessage", e)

            } finally {
                importMutex.unlock()
            }
        }
    }

    companion object {
        fun factory(app: Application, repository: InventoryRepository): androidx.lifecycle.ViewModelProvider.Factory {
            return object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(DatabaseViewModel::class.java)) {
                        return DatabaseViewModel(app, repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }

    private fun Map<Long, ProductWithDetails>.withCappedProductDetailsOverride(
        productId: Long,
        details: ProductWithDetails
    ): Map<Long, ProductWithDetails> {
        val updated = LinkedHashMap(this)
        updated.remove(productId)
        updated[productId] = details
        while (updated.size > PRODUCT_DETAILS_OVERRIDE_LIMIT) {
            val oldestProductId = updated.keys.firstOrNull() ?: break
            updated.remove(oldestProductId)
        }
        return updated.toMap()
    }
}

internal suspend fun writeStagedProductImage(context: Context, bytes: ByteArray): Uri =
    withContext(Dispatchers.IO) {
        val stageDirectory = File(context.cacheDir, "product-image-stage")
        if (!stageDirectory.exists() && !stageDirectory.mkdirs()) {
            throw IOException("Unable to create staged image directory")
        }
        val stageFile = File(stageDirectory, "${UUID.randomUUID()}.jpg")
        stageFile.outputStream().use { output -> output.write(bytes) }
        Uri.fromFile(stageFile)
    }

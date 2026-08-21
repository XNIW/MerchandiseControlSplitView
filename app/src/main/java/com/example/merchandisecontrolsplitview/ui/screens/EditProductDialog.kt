package com.example.merchandisecontrolsplitview.ui.screens

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.merchandisecontrolsplitview.PortraitCaptureActivity
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.CatalogTextCanonicalizer
import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.data.StorefrontDraftField
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadSource
import com.example.merchandisecontrolsplitview.productimage.ProductImageMutationPhase
import com.example.merchandisecontrolsplitview.productimage.ProductImageVariant
import com.example.merchandisecontrolsplitview.util.formatClPriceInput
import com.example.merchandisecontrolsplitview.util.formatClPricePlainDisplay
import com.example.merchandisecontrolsplitview.util.formatClQuantityInput
import com.example.merchandisecontrolsplitview.util.CatalogTextField
import com.example.merchandisecontrolsplitview.util.CatalogTextValidationException
import com.example.merchandisecontrolsplitview.util.catalogTextErrorMessage
import com.example.merchandisecontrolsplitview.util.normalizeClPriceInput
import com.example.merchandisecontrolsplitview.util.normalizeClQuantityInput
import com.example.merchandisecontrolsplitview.util.parseUserPriceInput
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput
import com.example.merchandisecontrolsplitview.util.OptionalNumericInputStatus
import com.example.merchandisecontrolsplitview.util.validateOptionalUserPriceInput
import com.example.merchandisecontrolsplitview.util.validateOptionalUserQuantityInput
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiKey
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiState
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiStatus
import com.example.merchandisecontrolsplitview.viewmodel.ProductEditorSaveResult
import com.example.merchandisecontrolsplitview.viewmodel.ProductEditorOperationState
import com.example.merchandisecontrolsplitview.viewmodel.StagedProductImageState
import com.example.merchandisecontrolsplitview.viewmodel.StorefrontEditorUiState
import com.example.merchandisecontrolsplitview.data.StorefrontEditorDraft
import com.example.merchandisecontrolsplitview.data.StorefrontMutationOperation
import com.example.merchandisecontrolsplitview.data.StorefrontPublicationStatus
import com.example.merchandisecontrolsplitview.data.StorefrontAvailability
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.ScanOptions.ALL_CODE_TYPES
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal enum class QuickCreateStatus {
    IDLE,
    SAVING,
    SUCCESS,
    ERROR
}

internal data class QuickCreateUiState(
    val status: QuickCreateStatus = QuickCreateStatus.IDLE,
    val errorMessage: String? = null
)

@Composable
internal fun EditProductDialog(
    product: Product,
    sessionId: Long = product.id,
    viewModel: DatabaseViewModel,
    onResolveSupplierId: suspend (String) -> Long? = { name -> viewModel.addSupplier(name)?.id },
    onResolveCategoryId: suspend (String) -> Long? = { name -> viewModel.addCategory(name)?.id },
    enablePriceHistory: Boolean = false,
    onDismiss: () -> Unit,
    onSave: suspend (Product) -> ProductEditorSaveResult
) {
    var barcode by rememberSaveable(sessionId) { mutableStateOf(product.barcode) }
    var productName by rememberSaveable(sessionId) { mutableStateOf(product.productName ?: "") }
    var secondProductName by rememberSaveable(sessionId) { mutableStateOf(product.secondProductName ?: "") }
    var itemNumber by rememberSaveable(sessionId) { mutableStateOf(product.itemNumber ?: "") }
    var purchasePrice by rememberSaveable(sessionId) { mutableStateOf(formatClPriceInput(product.purchasePrice)) }
    var retailPrice by rememberSaveable(sessionId) { mutableStateOf(formatClPriceInput(product.retailPrice)) }
    var stockQuantity by rememberSaveable(sessionId) { mutableStateOf(formatClQuantityInput(product.stockQuantity)) }

    var barcodeError by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var productNameError by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var purchasePriceError by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var retailPriceError by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var stockQuantityError by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    val editorOperation by viewModel.productEditorOperationState.collectAsState()
    val stagedImageState by viewModel.stagedProductImageState.collectAsState()
    val storefrontState by viewModel.storefrontEditorState.collectAsState()
    val isSaving = editorOperation == ProductEditorOperationState.Saving
    val saveError = (editorOperation as? ProductEditorOperationState.Failed)?.message
    var supplierQuickCreateState by remember(product.id) { mutableStateOf(QuickCreateUiState()) }
    var categoryQuickCreateState by remember(product.id) { mutableStateOf(QuickCreateUiState()) }
    val supplierQuickCreateInFlight = remember(product.id) { AtomicBoolean(false) }
    val categoryQuickCreateInFlight = remember(product.id) { AtomicBoolean(false) }
    val barcodeRequiredErrorText = stringResource(id = R.string.error_barcode_required)
    val productNameRequiredAtLeastOneErrorText = stringResource(id = R.string.error_productname_required_at_least_one)
    val purchasePriceInvalidErrorText = stringResource(id = R.string.error_invalid_purchase_price)
    val negativePriceErrorText = stringResource(id = R.string.error_negative_prices)
    val retailPriceErrorText = stringResource(id = R.string.error_invalid_or_missing_retail_price)
    val quantityInvalidErrorText = stringResource(id = R.string.error_invalid_quantity)
    val negativeQuantityErrorText = stringResource(id = R.string.error_negative_quantity)
    val supplierQuickCreateFailedText = stringResource(id = R.string.quick_create_supplier_failed)
    val categoryQuickCreateFailedText = stringResource(id = R.string.quick_create_category_failed)

    fun purchasePriceValidationMessage(value: String): String? =
        when (validateOptionalUserPriceInput(value).status) {
            OptionalNumericInputStatus.INVALID -> purchasePriceInvalidErrorText
            OptionalNumericInputStatus.NEGATIVE -> negativePriceErrorText
            OptionalNumericInputStatus.EMPTY,
            OptionalNumericInputStatus.VALID -> null
        }

    fun stockQuantityValidationMessage(value: String): String? =
        when (validateOptionalUserQuantityInput(value).status) {
            OptionalNumericInputStatus.INVALID -> quantityInvalidErrorText
            OptionalNumericInputStatus.NEGATIVE -> negativeQuantityErrorText
            OptionalNumericInputStatus.EMPTY,
            OptionalNumericInputStatus.VALID -> null
        }

    var showSecondNameField by rememberSaveable(sessionId) { mutableStateOf(!product.secondProductName.isNullOrBlank()) }
    var showItemNumberField by rememberSaveable(sessionId) { mutableStateOf(!product.itemNumber.isNullOrBlank()) }

    val purchaseSeries by viewModel.getPriceSeries(product.id, "PURCHASE").collectAsState(emptyList())
    val retailSeries by viewModel.getPriceSeries(product.id, "RETAIL").collectAsState(emptyList())

    val lastPurchase = purchaseSeries.getOrNull(0)?.price
    val prevPurchase = purchaseSeries.getOrNull(1)?.price
    val lastRetail = retailSeries.getOrNull(0)?.price
    val prevRetail = retailSeries.getOrNull(1)?.price
    var showPriceHistorySheet by rememberSaveable(sessionId) { mutableStateOf(false) }
    val retailFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var askedKeyboard by rememberSaveable(sessionId) { mutableStateOf(false) }
    val productImageStates by viewModel.productImageStates.collectAsState()
    val mainImageState = productImageStates[
        ProductImageUiKey(product.id, ProductImageVariant.MAIN)
    ]
    val thumbImageState = productImageStates[
        ProductImageUiKey(product.id, ProductImageVariant.THUMB)
    ]
    val observedImageState = mainImageState != null || thumbImageState != null
    val effectiveImageVersionId = if (observedImageState) {
        mainImageState?.versionId ?: thumbImageState?.versionId
    } else {
        product.primaryImageVersionId
    }
    val stagedPreviewState = when (val staged = stagedImageState) {
        StagedProductImageState.Empty -> null
        StagedProductImageState.Preparing -> ProductImageUiState(
            status = ProductImageUiStatus.UPLOADING,
            mutationPhase = ProductImageMutationPhase.PREPROCESSING
        )
        is StagedProductImageState.Ready -> ProductImageUiState(
            status = ProductImageUiStatus.READY,
            bytes = staged.previewBytes
        )
        is StagedProductImageState.Failed -> ProductImageUiState(
            status = ProductImageUiStatus.ERROR,
            errorCode = "image_decode_failed"
        )
    }
    val displayedMainImageState = if (product.id == 0L) stagedPreviewState else mainImageState
    val displayedPreviewState = productImagePreviewState(displayedMainImageState, thumbImageState)
    val imageMutationBusy = productImageMutationBusy(displayedMainImageState, thumbImageState)
    var pendingCapturePath by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var confirmImageRemoval by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showImagePreview by rememberSaveable(sessionId) { mutableStateOf(false) }
    var hasSyncedRemoteRef by remember(product.id) { mutableStateOf<Boolean?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            if (product.id == 0L) {
                viewModel.stageNewProductImage(uri)
            } else {
                viewModel.uploadProductImage(product.id, uri)
            }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { captured ->
        val file = pendingCapturePath?.let(::File)
        val uri = file?.takeIf(File::isFile)?.let { captureFile ->
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    captureFile
                )
            }.getOrNull()
        }
        pendingCapturePath = null
        if (captured && uri != null) {
            if (product.id == 0L) {
                viewModel.stageNewProductImage(uri) { file.delete() }
            } else {
                viewModel.uploadProductImage(product.id, uri) {
                    file.delete()
                }
            }
        } else {
            file?.delete()
        }
    }

    LaunchedEffect(product.id) {
        hasSyncedRemoteRef = if (product.id == 0L) {
            false
        } else {
            runCatching { viewModel.hasSyncedProductImageReference(product.id) }
                .getOrDefault(false)
        }
    }

    LaunchedEffect(product.id, product.primaryImageVersionId) {
        if (product.id != 0L) {
            viewModel.loadProductImageProgressively(
                productId = product.id,
                expectedVersionId = product.primaryImageVersionId
            )
        }
    }
    fun launchProductCamera() {
        val directory = File(context.cacheDir, "product-image-capture")
        if (!directory.exists() && !directory.mkdirs()) return
        directory.listFiles()
            ?.filter { file ->
                file.isFile && System.currentTimeMillis() - file.lastModified() > 86_400_000L
            }
            ?.forEach(File::delete)
        val file = runCatching {
            File.createTempFile("product-", ".jpg", directory)
        }.getOrNull() ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrElse {
            file.delete()
            return
        }
        pendingCapturePath = file.absolutePath
        cameraLauncher.launch(uri)
    }

    var retailPriceTf by rememberSaveable(sessionId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(retailPrice, TextRange(retailPrice.length)))
    }

    fun normalizeRetailPriceField() {
        val normalized = normalizeClPriceInput(retailPriceTf.text)
        retailPrice = normalized
        retailPriceTf = TextFieldValue(normalized, TextRange(normalized.length))
    }

    fun validate(): Boolean {
        barcodeError = if (barcode.isBlank()) barcodeRequiredErrorText else null
        productNameError = if (productName.isBlank() && secondProductName.isBlank()) productNameRequiredAtLeastOneErrorText else null
        purchasePriceError = purchasePriceValidationMessage(purchasePrice)
        stockQuantityError = stockQuantityValidationMessage(stockQuantity)

        val retailPriceValue = parseUserPriceInput(retailPrice)
        retailPriceError = if (retailPriceValue == null || retailPriceValue <= 0) {
            retailPriceErrorText
        } else {
            null
        }

        return barcodeError == null &&
            productNameError == null &&
            purchasePriceError == null &&
            retailPriceError == null &&
            stockQuantityError == null
    }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val fieldScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result?.contents
        if (!scanned.isNullOrBlank()) {
            barcode = scanned
            validate()
        }
    }

    var supplierId by rememberSaveable(sessionId) { mutableStateOf(product.supplierId) }
    val noSupplierText = stringResource(R.string.no_supplier)
    var supplierName by rememberSaveable(sessionId) { mutableStateOf(noSupplierText) }
    var showSupplierSelectionDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    val supplierIdPrefix = stringResource(id = R.string.supplier_id_prefix)
    val scanPromptText = stringResource(R.string.scan_prompt)
    LaunchedEffect(supplierId) {
        supplierName = if (supplierId != null) {
            viewModel.getSupplierDisplayName(supplierId) ?: "$supplierIdPrefix $supplierId"
        } else {
            noSupplierText
        }
    }

    var categoryId by rememberSaveable(sessionId) { mutableStateOf(product.categoryId) }
    val noCategoryText = stringResource(R.string.no_category)
    var categoryName by rememberSaveable(sessionId) { mutableStateOf(noCategoryText) }
    var showCategorySelectionDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    val categoryIdPrefix = stringResource(id = R.string.category_id_prefix)
    LaunchedEffect(categoryId) {
        categoryName = if (categoryId != null) {
            viewModel.getCategoryDisplayName(categoryId) ?: "$categoryIdPrefix $categoryId"
        } else {
            noCategoryText
        }
    }

    LaunchedEffect(editorOperation) {
        if (editorOperation == ProductEditorOperationState.Saved) {
            focusManager.clearFocus()
            keyboardController?.hide()
            onDismiss()
        }
    }

    LaunchedEffect(product.id) {
        if (product.id != 0L) {
            delay(60)
            retailFocusRequester.requestFocus()
        }
    }

    fun productForPriceHistory(): Product = product.copy(
        barcode = barcode.trim().ifBlank { product.barcode },
        productName = productName.trim().takeIf { it.isNotBlank() },
        secondProductName = secondProductName.trim().takeIf { it.isNotBlank() },
        itemNumber = itemNumber.trim().takeIf { it.isNotBlank() },
        purchasePrice = parseUserPriceInput(purchasePrice),
        retailPrice = parseUserPriceInput(retailPrice),
        supplierId = supplierId,
        categoryId = categoryId,
        stockQuantity = parseUserQuantityInput(stockQuantity)
    )

    if (showSupplierSelectionDialog) {
        SupplierSelectionDialog(
            viewModel = viewModel,
            quickCreateState = supplierQuickCreateState,
            onDismiss = {
                if (supplierQuickCreateState.status != QuickCreateStatus.SAVING) {
                    showSupplierSelectionDialog = false
                }
            },
            onSupplierSelected = { selectedSupplier ->
                if (supplierQuickCreateState.status == QuickCreateStatus.SAVING) {
                    return@SupplierSelectionDialog
                }
                supplierId = selectedSupplier.id
                showSupplierSelectionDialog = false
            },
            onAddNewSupplier = { name ->
                if (!supplierQuickCreateInFlight.compareAndSet(false, true)) {
                    return@SupplierSelectionDialog
                }
                supplierQuickCreateState = QuickCreateUiState(QuickCreateStatus.SAVING)
                scope.launch {
                    try {
                        val canonicalName = CatalogTextCanonicalizer.supplierName(name)
                        val resolvedId = onResolveSupplierId(canonicalName)
                        if (resolvedId == null) {
                            supplierQuickCreateState = QuickCreateUiState(
                                QuickCreateStatus.ERROR,
                                supplierQuickCreateFailedText
                            )
                        } else {
                            supplierQuickCreateState = QuickCreateUiState(QuickCreateStatus.SUCCESS)
                            supplierId = resolvedId
                            showSupplierSelectionDialog = false
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: CatalogTextValidationException) {
                        supplierQuickCreateState = QuickCreateUiState(
                            QuickCreateStatus.ERROR,
                            context.catalogTextErrorMessage(throwable.rejection)
                        )
                    } catch (_: Exception) {
                        supplierQuickCreateState = QuickCreateUiState(
                            QuickCreateStatus.ERROR,
                            supplierQuickCreateFailedText
                        )
                    } finally {
                        supplierQuickCreateInFlight.set(false)
                    }
                }
            }
        )
    }

    if (showCategorySelectionDialog) {
        CategorySelectionDialog(
            viewModel = viewModel,
            quickCreateState = categoryQuickCreateState,
            onDismiss = {
                if (categoryQuickCreateState.status != QuickCreateStatus.SAVING) {
                    showCategorySelectionDialog = false
                }
            },
            onCategorySelected = { selectedCategory ->
                if (categoryQuickCreateState.status == QuickCreateStatus.SAVING) {
                    return@CategorySelectionDialog
                }
                categoryId = selectedCategory.id
                showCategorySelectionDialog = false
            },
            onAddNewCategory = { name ->
                if (!categoryQuickCreateInFlight.compareAndSet(false, true)) {
                    return@CategorySelectionDialog
                }
                categoryQuickCreateState = QuickCreateUiState(QuickCreateStatus.SAVING)
                scope.launch {
                    try {
                        val canonicalName = CatalogTextCanonicalizer.categoryName(name)
                        val resolvedId = onResolveCategoryId(canonicalName)
                        if (resolvedId == null) {
                            categoryQuickCreateState = QuickCreateUiState(
                                QuickCreateStatus.ERROR,
                                categoryQuickCreateFailedText
                            )
                        } else {
                            categoryQuickCreateState = QuickCreateUiState(QuickCreateStatus.SUCCESS)
                            categoryId = resolvedId
                            showCategorySelectionDialog = false
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: CatalogTextValidationException) {
                        categoryQuickCreateState = QuickCreateUiState(
                            QuickCreateStatus.ERROR,
                            context.catalogTextErrorMessage(throwable.rejection)
                        )
                    } catch (_: Exception) {
                        categoryQuickCreateState = QuickCreateUiState(
                            QuickCreateStatus.ERROR,
                            categoryQuickCreateFailedText
                        )
                    } finally {
                        categoryQuickCreateInFlight.set(false)
                    }
                }
            }
        )
    }

    Dialog(
        onDismissRequest = {
            if (!isSaving && !imageMutationBusy) {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .testTag("task141.edit.dialog-root"),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .heightIn(max = 820.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(stringResource(R.string.edit_product_title), style = MaterialTheme.typography.titleLarge)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                    Text(
                        stringResource(R.string.storefront_operational_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ProductImageEditorSection(
                        product = product,
                        mainState = displayedMainImageState,
                        thumbState = thumbImageState,
                        apiConfigured = viewModel.productImagesConfigured(),
                        canManage = viewModel.canManageProductImages(),
                        hasSyncedRemoteRef = hasSyncedRemoteRef == true,
                        currentImageVersionId = effectiveImageVersionId,
                        onChoosePhoto = {
                            if (product.id == 0L) {
                                viewModel.discardStagedProductImage()
                            } else if (viewModel.hasPendingStagedProductImage(product.id)) {
                                viewModel.discardPendingStagedProductImage(product.id)
                            } else {
                                viewModel.discardFailedProductImageOperation(product.id)
                            }
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onTakePhoto = {
                            if (product.id == 0L) {
                                viewModel.discardStagedProductImage()
                            } else if (viewModel.hasPendingStagedProductImage(product.id)) {
                                viewModel.discardPendingStagedProductImage(product.id)
                            } else {
                                viewModel.discardFailedProductImageOperation(product.id)
                            }
                            launchProductCamera()
                        },
                        onRetry = {
                            if (product.id == 0L) {
                                viewModel.discardStagedProductImage()
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            } else if (viewModel.hasPendingStagedProductImage(product.id)) {
                                viewModel.retryPendingStagedProductImage(product.id)
                            } else if (product.id != 0L) {
                                viewModel.loadProductImageProgressively(
                                    productId = product.id,
                                    expectedVersionId = effectiveImageVersionId,
                                    force = true
                                )
                            }
                        },
                        onCancelOperation = {
                            viewModel.cancelProductImageOperation(product.id)
                        },
                        onDiscardFailure = {
                            if (product.id == 0L) {
                                viewModel.discardStagedProductImage()
                            } else if (viewModel.hasPendingStagedProductImage(product.id)) {
                                viewModel.discardPendingStagedProductImage(product.id)
                            } else {
                                viewModel.discardFailedProductImageOperation(product.id)
                            }
                        },
                        onRemove = {
                            if (product.id == 0L) {
                                viewModel.discardStagedProductImage()
                            } else {
                                confirmImageRemoval = true
                            }
                        },
                        onOpenPreview = { showImagePreview = true }
                    )

                    OutlinedTextField(
                        value = barcode,
                        onValueChange = { barcode = it; validate() },
                        label = { Text(stringResource(R.string.barcode_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        isError = barcodeError != null,
                        supportingText = { barcodeError?.let { Text(it) } },
                        trailingIcon = {
                            IconButton(onClick = {
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ALL_CODE_TYPES)
                                    setPrompt(scanPromptText)
                                    setBeepEnabled(true)
                                    setBarcodeImageEnabled(false)
                                    setCaptureActivity(PortraitCaptureActivity::class.java)
                                }
                                fieldScanLauncher.launch(options)
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.CameraAlt,
                                    contentDescription = stringResource(R.string.scan_barcode)
                                )
                            }
                        }
                    )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = productName,
                        onValueChange = { productName = it; validate() },
                        label = { Text(stringResource(R.string.product_name_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("product-editor-name"),
                        isError = productNameError != null,
                        supportingText = { productNameError?.let { Text(it) } }
                    )
                    if (showSecondNameField) {
                        OutlinedTextField(value = secondProductName, onValueChange = { secondProductName = it; validate() }, label = { Text(stringResource(R.string.second_product_name_label)) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        TextButton(onClick = { showSecondNameField = true }) { Text(stringResource(R.string.add_second_name)) }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = purchasePrice,
                        onValueChange = {
                            purchasePrice = it
                            if (purchasePriceError != null) {
                                purchasePriceError = purchasePriceValidationMessage(it)
                            }
                        },
                        label = { CompactFieldLabel(R.string.purchase_price_label) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("task141.edit.purchase-price")
                            .onFocusChanged { state ->
                                if (!state.isFocused) {
                                    purchasePrice = normalizeClPriceInput(purchasePrice)
                                    if (purchasePriceError != null) {
                                        purchasePriceError = purchasePriceValidationMessage(purchasePrice)
                                    }
                                }
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = purchasePriceError != null,
                        supportingText = {
                            purchasePriceError?.let { Text(it) } ?: PriceHistorySupportingText(
                                lastPrice = lastPurchase,
                                previousPrice = prevPurchase
                            )
                        }
                    )

                    OutlinedTextField(
                        value = retailPriceTf,
                        onValueChange = { tf ->
                            retailPriceTf = tf
                            retailPrice = tf.text
                            validate()
                        },
                        label = { CompactFieldLabel(R.string.retail_price_label) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product-editor-retail-price")
                            .focusRequester(retailFocusRequester)
                            .onFocusChanged { state ->
                                if (state.isFocused && !askedKeyboard) {
                                    askedKeyboard = true
                                    keyboardController?.show()
                                } else if (!state.isFocused) {
                                    normalizeRetailPriceField()
                                }
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = retailPriceError != null,
                        supportingText = {
                            retailPriceError?.let { Text(it) } ?: PriceHistorySupportingText(
                                lastPrice = lastRetail,
                                previousPrice = prevRetail
                            )
                        }
                    )
                }

                if (enablePriceHistory && product.id != 0L) {
                    TextButton(
                        onClick = { showPriceHistorySheet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.price_history))
                    }
                }

                if (showItemNumberField) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = itemNumber,
                            onValueChange = { itemNumber = it },
                            label = { CompactFieldLabel(R.string.item_code_label) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = stockQuantity,
                            onValueChange = {
                                stockQuantity = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '-' }
                                if (stockQuantityError != null) {
                                    stockQuantityError = stockQuantityValidationMessage(stockQuantity)
                                }
                            },
                            label = { CompactFieldLabel(R.string.header_stock_quantity) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("task141.edit.stock-quantity")
                                .semantics {
                                    stockQuantityError?.let(::error)
                                }
                                .onFocusChanged { state ->
                                    if (!state.isFocused) {
                                        stockQuantity = normalizeClQuantityInput(stockQuantity)
                                        if (stockQuantityError != null) {
                                            stockQuantityError = stockQuantityValidationMessage(stockQuantity)
                                        }
                                    }
                                },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = stockQuantityError != null,
                        )
                    }
                } else {
                    TextButton(onClick = { showItemNumberField = true }) { Text(stringResource(R.string.add_item_code)) }
                    OutlinedTextField(
                        value = stockQuantity,
                        onValueChange = {
                            stockQuantity = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '-' }
                            if (stockQuantityError != null) {
                                stockQuantityError = stockQuantityValidationMessage(stockQuantity)
                            }
                        },
                        label = { CompactFieldLabel(R.string.header_stock_quantity) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("task141.edit.stock-quantity")
                            .onFocusChanged { state ->
                                if (!state.isFocused) {
                                    stockQuantity = normalizeClQuantityInput(stockQuantity)
                                    if (stockQuantityError != null) {
                                        stockQuantityError = stockQuantityValidationMessage(stockQuantity)
                                    }
                                }
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = stockQuantityError != null,
                        supportingText = { stockQuantityError?.let { Text(it) } }
                    )
                }

                SelectionTextField(
                    value = supplierName,
                    labelRes = R.string.supplier_label,
                    contentDescriptionRes = R.string.select_supplier,
                    onClick = {
                        supplierQuickCreateState = QuickCreateUiState()
                        supplierQuickCreateInFlight.set(false)
                        showSupplierSelectionDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("product-editor-supplier-selector")
                )

                SelectionTextField(
                    value = categoryName,
                    labelRes = R.string.category_label,
                    contentDescriptionRes = R.string.select_category,
                    onClick = {
                        categoryQuickCreateState = QuickCreateUiState()
                        categoryQuickCreateInFlight.set(false)
                        showCategorySelectionDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("product-editor-category-selector")
                )
                    }

                if (showItemNumberField) {
                    stockQuantityError?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("task141.edit.stock-quantity-error"),
                        )
                    }
                }

                StorefrontEditorSection(
                    product = product,
                    state = storefrontState,
                    operationalImageState = displayedPreviewState,
                    onExpandedChange = viewModel::setStorefrontExpanded,
                    onDraftChange = viewModel::updateStorefrontDraft,
                    onAlign = {
                        viewModel.alignStorefrontWithOperational(
                            product.copy(
                                productName = productName,
                                retailPrice = parseUserPriceInput(retailPrice)
                            )
                        )
                    },
                    onAction = viewModel::mutateStorefront,
                    onPreview = { viewModel.setStorefrontPreviewVisible(true) },
                    onReload = viewModel::reloadStorefrontEditor,
                    onRetryPending = viewModel::retryPendingStorefrontDraft,
                    onReapplyConflict = viewModel::reapplyStorefrontConflict,
                    onCancelConflict = viewModel::cancelStorefrontConflict,
                    onUseOperationalImage = {
                        viewModel.adoptOperationalImageForStorefront(product)
                    },
                    onDismissPreview = { viewModel.setStorefrontPreviewVisible(false) }
                )

                HorizontalDivider()
                saveError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("product-editor-save-error")
                            .semantics {
                                error(message)
                                liveRegion = LiveRegionMode.Polite
                            }
                    )
                }
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = !isSaving && !imageMutationBusy,
                        onClick = onDismiss
                    ) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !isSaving && !imageMutationBusy,
                        modifier = Modifier.testTag("product-editor-save"),
                        onClick = {
                        if (validate()) {
                            val productToSave = runCatching {
                                CatalogTextCanonicalizer.product(
                                    product.copy(
                                        barcode = barcode,
                                        productName = productName,
                                        secondProductName = secondProductName,
                                        itemNumber = itemNumber,
                                        purchasePrice = parseUserPriceInput(purchasePrice),
                                        retailPrice = parseUserPriceInput(retailPrice),
                                        supplierId = supplierId,
                                        categoryId = categoryId,
                                        stockQuantity = parseUserQuantityInput(stockQuantity)
                                    )
                                ).product
                            }.getOrElse { throwable ->
                                val rejection = (throwable as? CatalogTextValidationException)?.rejection
                                if (rejection != null) {
                                    val message = context.catalogTextErrorMessage(rejection)
                                    when (rejection.field) {
                                        CatalogTextField.BARCODE,
                                        CatalogTextField.ITEM_NUMBER,
                                        CatalogTextField.REMOTE_ID -> barcodeError = message
                                        else -> productNameError = message
                                    }
                                }
                                return@Button
                            }
                            viewModel.resetProductEditorOperation()
                            viewModel.startProductEditorSave(productToSave, onSave)
                        }
                    }) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.product_editor_saving))
                        } else {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
                }
            }
        }

            if (showPriceHistorySheet) {
                PriceHistoryBottomSheet(
                    product = productForPriceHistory(),
                    purchase = purchaseSeries,
                    retail = retailSeries,
                    onDismiss = { showPriceHistorySheet = false },
                    onUpdateCurrentPrice = { type, price ->
                        scope.launch {
                            viewModel.updateCurrentPriceFromHistory(
                                productId = product.id,
                                type = type,
                                price = price
                            )?.let { updated ->
                                if (type == "PURCHASE") {
                                    purchasePrice = formatClPriceInput(updated.purchasePrice)
                                } else if (type == "RETAIL") {
                                    val formattedRetailPrice = formatClPriceInput(updated.retailPrice)
                                    retailPrice = formattedRetailPrice
                                    retailPriceTf = TextFieldValue(
                                        formattedRetailPrice,
                                        TextRange(formattedRetailPrice.length)
                                    )
                                    validate()
                                }
                            }
                        }
                    }
                )
            }
            if (confirmImageRemoval) {
                AlertDialog(
                    onDismissRequest = { confirmImageRemoval = false },
                    title = { Text(stringResource(R.string.product_image_remove_confirm_title)) },
                    text = { Text(stringResource(R.string.product_image_remove_confirm_body)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmImageRemoval = false
                            viewModel.removeProductImage(product.id)
                        }) {
                            Text(stringResource(R.string.product_image_remove_confirm_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmImageRemoval = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
            if (showImagePreview) {
                ProductImageFullscreenDialog(
                    state = displayedPreviewState,
                    contentDescription = stringResource(R.string.product_image_main),
                    onDismiss = { showImagePreview = false },
                    onRetry = if (product.id == 0L) null else ({
                        viewModel.loadProductImageProgressively(
                            productId = product.id,
                            expectedVersionId = effectiveImageVersionId,
                            force = true
                        )
                    })
                )
            }
    }
}

@Composable
internal fun StorefrontEditorSection(
    product: Product,
    state: StorefrontEditorUiState,
    operationalImageState: ProductImageUiState?,
    onExpandedChange: (Boolean) -> Unit,
    onDraftChange: ((StorefrontEditorDraft) -> StorefrontEditorDraft) -> Unit,
    onAlign: () -> Unit,
    onAction: (StorefrontMutationOperation) -> Unit,
    onPreview: () -> Unit,
    onReload: () -> Unit,
    onRetryPending: () -> Unit,
    onReapplyConflict: () -> Unit,
    onCancelConflict: () -> Unit,
    onUseOperationalImage: () -> Unit,
    onDismissPreview: () -> Unit
) {
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showAvailabilityPicker by remember { mutableStateOf(false) }
    val status = state.publication?.publicationStatus ?: StorefrontPublicationStatus.UNPUBLISHED
    val selectedCategory = state.categories.firstOrNull {
        it.categoryId == state.draft.storefrontCategoryId
    }?.publicName
    val expansionState = stringResource(
        if (state.expanded) R.string.storefront_expanded else R.string.storefront_collapsed
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandedChange(!state.expanded) }
            .testTag("storefront-editor-section"),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProductImagePreview(
                    state = state.publicImageState,
                    contentDescription = stringResource(R.string.storefront_thumbnail),
                    modifier = Modifier.size(52.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.storefront_customer_app),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        storefrontStatusLabel(status),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        state.draft.publicName.ifBlank {
                            stringResource(R.string.storefront_public_name_missing)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        state.draft.publicPrice?.let { formatClPricePlainDisplay(it.toDouble()) }
                            ?: stringResource(R.string.storefront_public_price_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        selectedCategory ?: stringResource(R.string.storefront_category_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val operationalPrice = product.retailPrice
                    val differs = state.publication != null && (
                        state.draft.publicName != product.productName.orEmpty() ||
                            operationalPrice == null || operationalPrice % 1.0 != 0.0 ||
                            state.draft.publicPrice != operationalPrice.toLong()
                        )
                    if (differs) {
                        Text(
                            stringResource(R.string.storefront_data_differs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    if (state.expanded) "▲" else "▼",
                    modifier = Modifier.semantics {
                        stateDescription = expansionState
                    }
                )
            }

            state.publication?.let { publication ->
                Text(
                    stringResource(
                        R.string.storefront_version_updated,
                        publication.version,
                        publication.updatedAt,
                        publication.mutationSource
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!state.enabled) {
                Text(
                    stringResource(R.string.storefront_feature_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.remoteProductId == null && !state.loading) {
                Text(
                    stringResource(R.string.storefront_sync_before_publish),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (state.pendingConnection) {
                Text(
                    stringResource(R.string.storefront_local_draft_waiting),
                    color = MaterialTheme.colorScheme.tertiary
                )
                TextButton(onClick = onRetryPending, enabled = !state.busy) {
                    Text(stringResource(R.string.retry))
                }
            } else if (state.serverVersionUnverified) {
                Text(
                    stringResource(R.string.storefront_server_version_unverified),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            state.errorCode?.let { code ->
                Text(
                    storefrontErrorLabel(code),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics {
                        error(code)
                        liveRegion = LiveRegionMode.Polite
                    }
                )
            }

            state.conflict?.let { conflict ->
                val dirtyFieldLabels = buildList {
                    conflict.dirtyFields.forEach { field ->
                        add(storefrontDraftFieldLabel(field))
                    }
                }.joinToString(", ")
                Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            stringResource(R.string.storefront_conflict_title),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            stringResource(
                                R.string.storefront_conflict_details,
                                conflict.server.version,
                                conflict.server.mutationSource,
                                conflict.server.updatedAt
                            )
                        )
                        Text(
                            stringResource(
                                R.string.storefront_conflict_server_name,
                                conflict.server.publicName,
                                conflict.server.publicPrice
                            )
                        )
                        Text(
                            stringResource(
                                R.string.storefront_conflict_local_name,
                                conflict.localDraft.publicName,
                                conflict.localDraft.publicPrice ?: 0L
                            )
                        )
                        Text(
                            stringResource(
                                R.string.storefront_conflict_local_fields,
                                dirtyFieldLabels
                            )
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onReload) {
                                Text(stringResource(R.string.storefront_reload))
                            }
                            TextButton(onClick = onReapplyConflict) {
                                Text(stringResource(R.string.storefront_reapply))
                            }
                            TextButton(onClick = onCancelConflict) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }

            if (state.expanded && state.canAuthor && state.conflict == null) {
                HorizontalDivider()
                OutlinedTextField(
                    value = state.draft.publicName,
                    onValueChange = { value -> onDraftChange { it.copy(publicName = value) } },
                    label = { Text(stringResource(R.string.storefront_public_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy
                )
                OutlinedTextField(
                    value = state.draft.publicDescription,
                    onValueChange = { value -> onDraftChange { it.copy(publicDescription = value) } },
                    label = { Text(stringResource(R.string.storefront_public_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = !state.busy
                )
                OutlinedTextField(
                    value = state.draft.publicBrand,
                    onValueChange = { value -> onDraftChange { it.copy(publicBrand = value) } },
                    label = { Text(stringResource(R.string.storefront_public_brand)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy
                )
                SelectionTextField(
                    value = selectedCategory.orEmpty(),
                    labelRes = R.string.storefront_public_category,
                    contentDescriptionRes = R.string.storefront_choose_category,
                    onClick = { showCategoryPicker = true },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.draft.publicPrice?.toString().orEmpty(),
                        onValueChange = { value ->
                            if (value.all(Char::isDigit)) {
                                onDraftChange { it.copy(publicPrice = value.toLongOrNull()) }
                            }
                        },
                        label = { Text(stringResource(R.string.storefront_public_price)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        enabled = !state.busy
                    )
                    OutlinedTextField(
                        value = state.draft.compareAtPrice?.toString().orEmpty(),
                        onValueChange = { value ->
                            if (value.all(Char::isDigit)) {
                                onDraftChange { it.copy(compareAtPrice = value.toLongOrNull()) }
                            }
                        },
                        label = { Text(stringResource(R.string.storefront_compare_at_price)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        enabled = !state.busy
                    )
                }
                TextButton(onClick = onAlign, enabled = !state.busy) {
                    Text(stringResource(R.string.storefront_align_internal))
                }
                StorefrontBooleanField(
                    label = stringResource(R.string.storefront_pickup),
                    checked = state.draft.pickupEnabled,
                    enabled = !state.busy,
                    onChecked = { checked -> onDraftChange { it.copy(pickupEnabled = checked) } }
                )
                StorefrontBooleanField(
                    label = stringResource(R.string.storefront_delivery),
                    checked = state.draft.deliveryEnabled,
                    enabled = !state.busy,
                    onChecked = { checked -> onDraftChange { it.copy(deliveryEnabled = checked) } }
                )
                StorefrontBooleanField(
                    label = stringResource(R.string.storefront_featured),
                    checked = state.draft.featured,
                    enabled = !state.busy,
                    onChecked = { checked -> onDraftChange { it.copy(featured = checked) } }
                )
                StorefrontBooleanField(
                    label = stringResource(R.string.storefront_reservation),
                    checked = state.draft.reservationEnabled,
                    enabled = !state.busy,
                    onChecked = { checked -> onDraftChange { it.copy(reservationEnabled = checked) } }
                )
                SelectionTextField(
                    value = storefrontAvailabilityLabel(state.draft.availability),
                    labelRes = R.string.storefront_availability,
                    contentDescriptionRes = R.string.storefront_choose_availability,
                    onClick = { showAvailabilityPicker = true },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.draft.homeOrder.toString(),
                    onValueChange = { value ->
                        if (value.all(Char::isDigit)) {
                            onDraftChange { it.copy(homeOrder = value.toLongOrNull() ?: 0L) }
                        }
                    },
                    label = { Text(stringResource(R.string.storefront_home_order)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy
                )
                StorefrontBooleanField(
                    label = stringResource(R.string.storefront_promotion),
                    checked = state.draft.priceSourceMode == "promotion",
                    enabled = !state.busy,
                    onChecked = { checked ->
                        onDraftChange {
                            it.copy(
                                priceSourceMode = if (checked) "promotion" else "override",
                                promotionStartsAt = if (checked) it.promotionStartsAt else null,
                                promotionEndsAt = if (checked) it.promotionEndsAt else null
                            )
                        }
                    }
                )
                if (state.draft.priceSourceMode == "promotion") {
                    OutlinedTextField(
                        value = state.draft.promotionStartsAt.orEmpty(),
                        onValueChange = { value ->
                            onDraftChange { it.copy(promotionStartsAt = value.ifBlank { null }) }
                        },
                        label = { Text(stringResource(R.string.storefront_promotion_starts)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy
                    )
                    OutlinedTextField(
                        value = state.draft.promotionEndsAt.orEmpty(),
                        onValueChange = { value ->
                            onDraftChange { it.copy(promotionEndsAt = value.ifBlank { null }) }
                        },
                        label = { Text(stringResource(R.string.storefront_promotion_ends)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy
                    )
                }
                TextButton(
                    onClick = onUseOperationalImage,
                    enabled = !state.busy && state.publication != null && operationalImageState != null
                ) {
                    Text(stringResource(R.string.storefront_use_operational_image))
                }
                Text(
                    if (state.draft.publicImageId == null) {
                        stringResource(R.string.storefront_public_image_missing)
                    } else {
                        stringResource(R.string.storefront_public_image_ready)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { onAction(StorefrontMutationOperation.SAVE_DRAFT) },
                        enabled = !state.busy && status !in setOf(
                            StorefrontPublicationStatus.PUBLISHED,
                            StorefrontPublicationStatus.ARCHIVED
                        )
                    ) { Text(stringResource(R.string.storefront_save_draft)) }
                    Button(
                        onClick = { onAction(StorefrontMutationOperation.PUBLISH) },
                        enabled = !state.busy && status != StorefrontPublicationStatus.ARCHIVED
                    ) { Text(stringResource(R.string.storefront_publish)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = { onAction(StorefrontMutationOperation.SCHEDULE) },
                        enabled = !state.busy && status != StorefrontPublicationStatus.ARCHIVED
                    ) { Text(stringResource(R.string.storefront_schedule)) }
                    TextButton(
                        onClick = { onAction(StorefrontMutationOperation.HIDE) },
                        enabled = !state.busy && state.publication != null &&
                            status != StorefrontPublicationStatus.ARCHIVED
                    ) { Text(stringResource(R.string.storefront_hide)) }
                    TextButton(
                        onClick = { onAction(StorefrontMutationOperation.ARCHIVE) },
                        enabled = !state.busy && state.publication != null &&
                            status != StorefrontPublicationStatus.ARCHIVED
                    ) { Text(stringResource(R.string.storefront_archive)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onPreview, enabled = !state.busy) {
                        Text(stringResource(R.string.storefront_preview))
                    }
                    TextButton(onClick = onReload, enabled = !state.busy) {
                        Text(stringResource(R.string.storefront_reload))
                    }
                }
                Text(
                    stringResource(R.string.storefront_operational_save_separate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showCategoryPicker) {
        AlertDialog(
            onDismissRequest = { showCategoryPicker = false },
            title = { Text(stringResource(R.string.storefront_choose_category)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(state.categories, key = { it.categoryId }) { category ->
                        TextButton(
                            onClick = {
                                onDraftChange { it.copy(storefrontCategoryId = category.categoryId) }
                                showCategoryPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(category.publicName) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAvailabilityPicker) {
        AlertDialog(
            onDismissRequest = { showAvailabilityPicker = false },
            title = { Text(stringResource(R.string.storefront_choose_availability)) },
            text = {
                Column {
                    StorefrontAvailability.entries.forEach { availability ->
                        TextButton(
                            onClick = {
                                onDraftChange { it.copy(availability = availability) }
                                showAvailabilityPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(storefrontAvailabilityLabel(availability)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAvailabilityPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (state.previewVisible) {
        AlertDialog(
            onDismissRequest = onDismissPreview,
            title = { Text(state.draft.publicName.ifBlank { product.productName.orEmpty() }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProductImagePreview(
                        state = state.publicImageState,
                        contentDescription = stringResource(R.string.storefront_preview),
                        modifier = Modifier.size(180.dp)
                    )
                    if (state.draft.publicImageId != null &&
                        state.publicImageState.status != ProductImageUiStatus.READY
                    ) {
                        Text(stringResource(R.string.storefront_public_image_pending_preview))
                    }
                    Text(state.draft.publicDescription)
                    Text(
                        state.draft.publicPrice?.let { formatClPricePlainDisplay(it.toDouble()) }
                            ?: stringResource(R.string.storefront_public_price_missing),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(selectedCategory ?: stringResource(R.string.storefront_category_missing))
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissPreview) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@Composable
private fun StorefrontBooleanField(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onChecked
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun storefrontDraftFieldLabel(field: StorefrontDraftField): String = stringResource(
    when (field) {
        StorefrontDraftField.PUBLIC_NAME -> R.string.storefront_public_name
        StorefrontDraftField.PUBLIC_DESCRIPTION -> R.string.storefront_public_description
        StorefrontDraftField.STOREFRONT_CATEGORY -> R.string.storefront_public_category
        StorefrontDraftField.PUBLIC_BRAND -> R.string.storefront_public_brand
        StorefrontDraftField.PUBLIC_PRICE -> R.string.storefront_public_price
        StorefrontDraftField.COMPARE_AT_PRICE -> R.string.storefront_compare_at_price
        StorefrontDraftField.PRICE_SOURCE_MODE -> R.string.storefront_price_mode
        StorefrontDraftField.PROMOTION_START -> R.string.storefront_promotion_starts
        StorefrontDraftField.PROMOTION_END -> R.string.storefront_promotion_ends
        StorefrontDraftField.FEATURED -> R.string.storefront_featured
        StorefrontDraftField.HOME_ORDER -> R.string.storefront_home_order
        StorefrontDraftField.PICKUP -> R.string.storefront_pickup
        StorefrontDraftField.DELIVERY -> R.string.storefront_delivery
        StorefrontDraftField.RESERVATION -> R.string.storefront_reservation
        StorefrontDraftField.AVAILABILITY -> R.string.storefront_availability
        StorefrontDraftField.PUBLIC_IMAGE -> R.string.storefront_public_image
    }
)

@Composable
private fun storefrontStatusLabel(status: StorefrontPublicationStatus): String =
    stringResource(
        when (status) {
            StorefrontPublicationStatus.UNPUBLISHED -> R.string.storefront_status_unpublished
            StorefrontPublicationStatus.DRAFT -> R.string.storefront_status_draft
            StorefrontPublicationStatus.SCHEDULED -> R.string.storefront_status_scheduled
            StorefrontPublicationStatus.PUBLISHED -> R.string.storefront_status_published
            StorefrontPublicationStatus.HIDDEN -> R.string.storefront_status_hidden
            StorefrontPublicationStatus.ARCHIVED -> R.string.storefront_status_archived
        }
    )

@Composable
private fun storefrontAvailabilityLabel(value: StorefrontAvailability): String =
    stringResource(
        when (value) {
            StorefrontAvailability.AVAILABLE -> R.string.storefront_availability_available
            StorefrontAvailability.LOW_STOCK -> R.string.storefront_availability_low_stock
            StorefrontAvailability.UNAVAILABLE -> R.string.storefront_availability_unavailable
            StorefrontAvailability.RESERVATION_ONLY -> R.string.storefront_availability_reservation
            StorefrontAvailability.PICKUP_ONLY -> R.string.storefront_availability_pickup
            StorefrontAvailability.DELIVERY_ONLY -> R.string.storefront_availability_delivery
        }
    )

@Composable
private fun storefrontErrorLabel(code: String): String = when (code) {
    "product_not_synced" -> stringResource(R.string.storefront_sync_before_publish)
    "network_required", "network_or_image_required" ->
        stringResource(R.string.storefront_network_required)
    "stale_revision" -> stringResource(R.string.storefront_conflict_title)
    "storefront_category_required" -> stringResource(R.string.storefront_category_missing)
    "public_price_required", "public_price_invalid", "public_price_requires_clp_integer" ->
        stringResource(R.string.storefront_public_price_invalid)
    "promotion_window_invalid" -> stringResource(R.string.storefront_promotion_invalid)
    "save_draft_before_image" -> stringResource(R.string.storefront_save_before_image)
    "permission_denied" -> stringResource(R.string.storefront_permission_denied)
    else -> stringResource(R.string.storefront_operation_failed)
}

@Composable
internal fun ProductImageEditorSection(
    product: Product,
    mainState: ProductImageUiState?,
    thumbState: ProductImageUiState?,
    apiConfigured: Boolean,
    canManage: Boolean,
    hasSyncedRemoteRef: Boolean,
    currentImageVersionId: String?,
    onChoosePhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onRetry: () -> Unit,
    onCancelOperation: () -> Unit,
    onDiscardFailure: () -> Unit,
    onRemove: () -> Unit,
    onOpenPreview: () -> Unit = {}
) {
    val mutationPhase = mainState?.mutationPhase ?: thumbState?.mutationPhase
    val mutating = productImageMutationBusy(mainState, thumbState)
    val hasFailure = mainState?.status == ProductImageUiStatus.ERROR ||
        thumbState?.status == ProductImageUiStatus.ERROR
    val cancellable = mutationPhase in setOf(
        ProductImageMutationPhase.PREPROCESSING,
        ProductImageMutationPhase.UPLOAD_MAIN,
        ProductImageMutationPhase.UPLOAD_THUMB
    )
    val previewState = productImagePreviewState(mainState, thumbState)
    val hasImage = currentImageVersionId != null || previewState?.bytes != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.product_image_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            ProductImagePreview(
                state = previewState,
                contentDescription = stringResource(R.string.product_image_main),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clickable(
                        enabled = previewState?.bytes != null,
                        onClick = onOpenPreview
                    )
            )
            when {
                product.id == 0L -> Text(
                    text = stringResource(R.string.product_image_staged_before_save),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                !hasSyncedRemoteRef -> Text(
                    text = stringResource(R.string.product_image_save_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                !apiConfigured -> Text(
                    text = stringResource(R.string.product_image_not_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                hasFailure -> Text(
                    text = stringResource(R.string.product_image_operation_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                previewState?.source == ProductImageLoadSource.CACHE -> Text(
                    text = stringResource(R.string.product_image_offline_cache),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            mutationPhase?.let { phase ->
                Text(
                    text = stringResource(phase.productImageProgressLabel()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (cancellable) {
                TextButton(
                    onClick = onCancelOperation,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.product_image_cancel_operation))
                }
            }
            if (hasFailure) {
                TextButton(
                    enabled = !mutating,
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.product_image_retry))
                }
                TextButton(
                    enabled = !mutating,
                    onClick = onDiscardFailure,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.product_image_discard_failed_attempt))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    enabled = (product.id == 0L || hasSyncedRemoteRef) &&
                        apiConfigured && canManage && !mutating,
                    onClick = onTakePhoto,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(
                            if (hasImage) R.string.product_image_camera_new
                            else R.string.product_image_camera
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    enabled = (product.id == 0L || hasSyncedRemoteRef) &&
                        apiConfigured && canManage && !mutating,
                    onClick = onChoosePhoto,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.product_image_choose),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (hasImage) {
                TextButton(
                    enabled = (product.id == 0L || hasSyncedRemoteRef) &&
                        canManage && !mutating,
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.product_image_remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

internal fun productImageMutationBusy(
    mainState: ProductImageUiState?,
    thumbState: ProductImageUiState?
): Boolean {
    val phase = mainState?.mutationPhase ?: thumbState?.mutationPhase
    return mainState?.status in setOf(
        ProductImageUiStatus.UPLOADING,
        ProductImageUiStatus.REMOVING
    ) || thumbState?.status in setOf(
        ProductImageUiStatus.UPLOADING,
        ProductImageUiStatus.REMOVING
    ) || phase == ProductImageMutationPhase.COMPLETED && (
        mainState?.status == ProductImageUiStatus.LOADING ||
            thumbState?.status == ProductImageUiStatus.LOADING
    )
}

internal fun productImagePreviewState(
    mainState: ProductImageUiState?,
    thumbState: ProductImageUiState?
): ProductImageUiState? {
    val phase = mainState?.mutationPhase ?: thumbState?.mutationPhase
    return when {
        mainState?.pendingPreviewBytes != null -> mainState.copy(
            bytes = mainState.pendingPreviewBytes
        )
        thumbState?.pendingPreviewBytes != null -> thumbState.copy(
            bytes = thumbState.pendingPreviewBytes,
            status = mainState?.status ?: thumbState.status,
            errorCode = mainState?.errorCode,
            mutationPhase = phase
        )
        mainState?.bytes != null -> mainState
        thumbState?.bytes != null -> thumbState.copy(
            status = mainState?.status ?: thumbState.status,
            errorCode = mainState?.errorCode,
            mutationPhase = phase
        )
        else -> mainState ?: thumbState
    }
}

private fun ProductImageMutationPhase.productImageProgressLabel(): Int = when (this) {
    ProductImageMutationPhase.PREPROCESSING -> R.string.product_image_progress_preprocessing
    ProductImageMutationPhase.UPLOAD_MAIN -> R.string.product_image_progress_upload_main
    ProductImageMutationPhase.UPLOAD_THUMB -> R.string.product_image_progress_upload_thumb
    ProductImageMutationPhase.FINALIZING -> R.string.product_image_progress_finalize
    ProductImageMutationPhase.COMPLETED -> R.string.product_image_progress_completed
}

@Composable
private fun CompactFieldLabel(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun PriceHistorySupportingText(
    lastPrice: Double?,
    previousPrice: Double?
) {
    if (lastPrice != null || previousPrice != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lastPrice?.let {
                Text(
                    text = stringResource(R.string.price_last, formatClPricePlainDisplay(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            previousPrice?.let {
                Text(
                    text = stringResource(R.string.price_previous, formatClPricePlainDisplay(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SelectionTextField(
    value: String,
    labelRes: Int,
    contentDescriptionRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.clickable(onClick = onClick)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { CompactFieldLabel(labelRes) },
            enabled = false,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    stringResource(contentDescriptionRes)
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        )
    }
}

@Composable
private fun SupplierSelectionDialog(
    viewModel: DatabaseViewModel,
    quickCreateState: QuickCreateUiState,
    onDismiss: () -> Unit,
    onSupplierSelected: (Supplier) -> Unit,
    onAddNewSupplier: (String) -> Unit
) {
    val suppliers by viewModel.suppliers.collectAsState()
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.onSupplierSearchQueryChanged("") }
    val quickCreateBusy = quickCreateState.status == QuickCreateStatus.SAVING

    AlertDialog(
        onDismissRequest = {
            if (!quickCreateBusy) {
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.select_supplier_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it; viewModel.onSupplierSearchQueryChanged(it) },
                    label = { Text(stringResource(R.string.search_or_add_supplier)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    enabled = !quickCreateBusy,
                    trailingIcon = {
                        if (searchText.isNotBlank()) {
                            IconButton(onClick = { searchText = ""; viewModel.onSupplierSearchQueryChanged("") }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.clear_text))
                            }
                        }
                    }
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(suppliers) { supplier ->
                        SelectionDialogRow(
                            text = supplier.name,
                            onClick = { onSupplierSelected(supplier) },
                            enabled = !quickCreateBusy
                        )
                    }
                    if (suppliers.none { it.name.equals(searchText, ignoreCase = true) } && searchText.isNotBlank()) {
                        item {
                            SelectionDialogRow(
                                text = when (quickCreateState.status) {
                                    QuickCreateStatus.SAVING -> stringResource(
                                        R.string.quick_create_supplier_saving,
                                        searchText
                                    )
                                    QuickCreateStatus.ERROR -> stringResource(
                                        R.string.quick_create_supplier_retry,
                                        searchText
                                    )
                                    QuickCreateStatus.IDLE,
                                    QuickCreateStatus.SUCCESS -> stringResource(
                                        R.string.add_new_supplier_prompt,
                                        searchText
                                    )
                                },
                                onClick = { onAddNewSupplier(searchText) },
                                highlighted = true,
                                enabled = !quickCreateBusy
                            )
                        }
                    }
                }
                if (quickCreateBusy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supplier-quick-create-saving")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(stringResource(R.string.quick_create_in_progress))
                    }
                }
                quickCreateState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supplier-quick-create-error")
                            .semantics {
                                error(message)
                                liveRegion = LiveRegionMode.Polite
                            }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !quickCreateBusy,
                onClick = onDismiss
            ) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun CategorySelectionDialog(
    viewModel: DatabaseViewModel,
    quickCreateState: QuickCreateUiState,
    onDismiss: () -> Unit,
    onCategorySelected: (Category) -> Unit,
    onAddNewCategory: (String) -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.onCategorySearchQueryChanged("")
    }
    val quickCreateBusy = quickCreateState.status == QuickCreateStatus.SAVING

    AlertDialog(
        onDismissRequest = {
            if (!quickCreateBusy) {
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.select_category_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        viewModel.onCategorySearchQueryChanged(it)
                    },
                    label = { Text(stringResource(R.string.search_or_add_category)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !quickCreateBusy,
                    trailingIcon = {
                        if (searchText.isNotBlank()) {
                            IconButton(onClick = {
                                searchText = ""
                                viewModel.onCategorySearchQueryChanged("")
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear_text)
                                )
                            }
                        }
                    }
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories) { category ->
                        SelectionDialogRow(
                            text = category.name,
                            onClick = { onCategorySelected(category) },
                            enabled = !quickCreateBusy
                        )
                    }

                    if (categories.none { it.name.equals(searchText, ignoreCase = true) } && searchText.isNotBlank()) {
                        item {
                            SelectionDialogRow(
                                text = when (quickCreateState.status) {
                                    QuickCreateStatus.SAVING -> stringResource(
                                        R.string.quick_create_category_saving,
                                        searchText
                                    )
                                    QuickCreateStatus.ERROR -> stringResource(
                                        R.string.quick_create_category_retry,
                                        searchText
                                    )
                                    QuickCreateStatus.IDLE,
                                    QuickCreateStatus.SUCCESS -> stringResource(
                                        R.string.add_new_category_prompt,
                                        searchText
                                    )
                                },
                                onClick = { onAddNewCategory(searchText) },
                                highlighted = true,
                                enabled = !quickCreateBusy
                            )
                        }
                    }
                }
                if (quickCreateBusy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("category-quick-create-saving")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(stringResource(R.string.quick_create_in_progress))
                    }
                }
                quickCreateState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("category-quick-create-error")
                            .semantics {
                                error(message)
                                liveRegion = LiveRegionMode.Polite
                            }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !quickCreateBusy,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun SelectionDialogRow(
    text: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    enabled: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }.copy(alpha = if (enabled) 1f else 0.55f),
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

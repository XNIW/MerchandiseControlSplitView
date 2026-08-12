package com.example.merchandisecontrolsplitview.ui.screens

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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

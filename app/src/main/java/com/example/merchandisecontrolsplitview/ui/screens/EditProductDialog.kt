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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.Supplier
import com.example.merchandisecontrolsplitview.productimage.ProductImageLoadSource
import com.example.merchandisecontrolsplitview.productimage.ProductImageVariant
import com.example.merchandisecontrolsplitview.util.formatClPriceInput
import com.example.merchandisecontrolsplitview.util.formatClPricePlainDisplay
import com.example.merchandisecontrolsplitview.util.formatClQuantityInput
import com.example.merchandisecontrolsplitview.util.normalizeClPriceInput
import com.example.merchandisecontrolsplitview.util.normalizeClQuantityInput
import com.example.merchandisecontrolsplitview.util.parseUserPriceInput
import com.example.merchandisecontrolsplitview.util.parseUserQuantityInput
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiKey
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiState
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiStatus
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.ScanOptions.ALL_CODE_TYPES
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun EditProductDialog(
    product: Product,
    viewModel: DatabaseViewModel,
    onResolveSupplierId: suspend (String) -> Long? = { name -> viewModel.addSupplier(name)?.id },
    onResolveCategoryId: suspend (String) -> Long? = { name -> viewModel.addCategory(name)?.id },
    enablePriceHistory: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Product) -> Unit
) {
    var barcode by remember { mutableStateOf(product.barcode) }
    var productName by remember { mutableStateOf(product.productName ?: "") }
    var secondProductName by remember { mutableStateOf(product.secondProductName ?: "") }
    var itemNumber by remember { mutableStateOf(product.itemNumber ?: "") }
    var purchasePrice by remember { mutableStateOf(formatClPriceInput(product.purchasePrice)) }
    var retailPrice by remember { mutableStateOf(formatClPriceInput(product.retailPrice)) }
    var stockQuantity by remember { mutableStateOf(formatClQuantityInput(product.stockQuantity)) }

    var barcodeError by remember { mutableStateOf<String?>(null) }
    var productNameError by remember { mutableStateOf<String?>(null) }
    var retailPriceError by remember { mutableStateOf<String?>(null) }
    val barcodeRequiredErrorText = stringResource(id = R.string.error_barcode_required)
    val productNameRequiredAtLeastOneErrorText = stringResource(id = R.string.error_productname_required_at_least_one)
    val retailPriceErrorText = stringResource(id = R.string.error_invalid_or_missing_retail_price)

    var showSecondNameField by remember(product) { mutableStateOf(!product.secondProductName.isNullOrBlank()) }
    var showItemNumberField by remember(product) { mutableStateOf(!product.itemNumber.isNullOrBlank()) }

    val purchaseSeries by viewModel.getPriceSeries(product.id, "PURCHASE").collectAsState(emptyList())
    val retailSeries by viewModel.getPriceSeries(product.id, "RETAIL").collectAsState(emptyList())

    val lastPurchase = purchaseSeries.getOrNull(0)?.price
    val prevPurchase = purchaseSeries.getOrNull(1)?.price
    val lastRetail = retailSeries.getOrNull(0)?.price
    val prevRetail = retailSeries.getOrNull(1)?.price
    var showPriceHistorySheet by remember { mutableStateOf(false) }
    val retailFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var askedKeyboard by remember { mutableStateOf(false) }
    val productImageStates by viewModel.productImageStates.collectAsState()
    val mainImageState = productImageStates[
        ProductImageUiKey(product.id, ProductImageVariant.MAIN)
    ]
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var confirmImageRemoval by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && product.id != 0L) {
            viewModel.uploadProductImage(product.id, uri)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { captured ->
        val file = pendingCaptureFile
        val uri = pendingCaptureUri
        pendingCaptureFile = null
        pendingCaptureUri = null
        if (captured && uri != null && product.id != 0L) {
            viewModel.uploadProductImage(product.id, uri) {
                file?.delete()
            }
        } else {
            file?.delete()
        }
    }

    LaunchedEffect(product.id, product.primaryImageVersionId) {
        if (product.id != 0L) {
            viewModel.loadProductImage(
                productId = product.id,
                variant = ProductImageVariant.MAIN,
                expectedVersionId = product.primaryImageVersionId
            )
        }
    }

    fun launchProductCamera() {
        if (product.id == 0L) return
        val directory = File(context.cacheDir, "product-image-capture")
        if (!directory.exists() && !directory.mkdirs()) return
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
        pendingCaptureFile = file
        pendingCaptureUri = uri
        cameraLauncher.launch(uri)
    }

    var retailPriceTf by remember(product) {
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

        val retailPriceValue = parseUserPriceInput(retailPrice)
        retailPriceError = if (retailPriceValue == null || retailPriceValue <= 0) {
            retailPriceErrorText
        } else {
            null
        }

        return barcodeError == null && productNameError == null && retailPriceError == null
    }

    val scope = rememberCoroutineScope()

    val fieldScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result?.contents
        if (!scanned.isNullOrBlank()) {
            barcode = scanned
            validate()
        }
    }

    var supplierId by remember { mutableStateOf(product.supplierId) }
    val noSupplierText = stringResource(R.string.no_supplier)
    var supplierName by remember { mutableStateOf(noSupplierText) }
    var showSupplierSelectionDialog by remember { mutableStateOf(false) }
    val supplierIdPrefix = stringResource(id = R.string.supplier_id_prefix)
    val scanPromptText = stringResource(R.string.scan_prompt)
    LaunchedEffect(supplierId) {
        supplierName = if (supplierId != null) {
            viewModel.getSupplierDisplayName(supplierId) ?: "$supplierIdPrefix $supplierId"
        } else {
            noSupplierText
        }
    }

    var categoryId by remember { mutableStateOf(product.categoryId) }
    val noCategoryText = stringResource(R.string.no_category)
    var categoryName by remember { mutableStateOf(noCategoryText) }
    var showCategorySelectionDialog by remember { mutableStateOf(false) }
    val categoryIdPrefix = stringResource(id = R.string.category_id_prefix)
    LaunchedEffect(categoryId) {
        categoryName = if (categoryId != null) {
            viewModel.getCategoryDisplayName(categoryId) ?: "$categoryIdPrefix $categoryId"
        } else {
            noCategoryText
        }
    }

    LaunchedEffect(
        product.id,
        product.barcode,
        product.productName,
        product.secondProductName,
        product.itemNumber,
        product.purchasePrice,
        product.retailPrice,
        product.stockQuantity,
        product.supplierId,
        product.categoryId
    ) {
        barcode = product.barcode
        productName = product.productName ?: ""
        secondProductName = product.secondProductName ?: ""
        itemNumber = product.itemNumber ?: ""
        purchasePrice = formatClPriceInput(product.purchasePrice)
        val formattedRetailPrice = formatClPriceInput(product.retailPrice)
        retailPrice = formattedRetailPrice
        retailPriceTf = TextFieldValue(
            formattedRetailPrice,
            TextRange(formattedRetailPrice.length)
        )
        stockQuantity = formatClQuantityInput(product.stockQuantity)
        supplierId = product.supplierId
        categoryId = product.categoryId
        showSecondNameField = !product.secondProductName.isNullOrBlank()
        showItemNumberField = !product.itemNumber.isNullOrBlank()
        barcodeError = null
        productNameError = null
        retailPriceError = null
        askedKeyboard = false
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
            onDismiss = { showSupplierSelectionDialog = false },
            onSupplierSelected = { selectedSupplier ->
                supplierId = selectedSupplier.id
                showSupplierSelectionDialog = false
            },
            onAddNewSupplier = { name ->
                scope.launch {
                    onResolveSupplierId(name)?.let { supplierId = it }
                    showSupplierSelectionDialog = false
                }
            }
        )
    }

    if (showCategorySelectionDialog) {
        CategorySelectionDialog(
            viewModel = viewModel,
            onDismiss = { showCategorySelectionDialog = false },
            onCategorySelected = { selectedCategory ->
                categoryId = selectedCategory.id
                showCategorySelectionDialog = false
            },
            onAddNewCategory = { name ->
                scope.launch {
                    onResolveCategoryId(name)?.let { categoryId = it }
                    showCategorySelectionDialog = false
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
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
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(stringResource(R.string.edit_product_title), style = MaterialTheme.typography.titleLarge)

                    ProductImageEditorSection(
                        product = product,
                        state = mainImageState,
                        apiConfigured = viewModel.productImagesConfigured(),
                        canManage = viewModel.canManageProductImages(),
                        onChoosePhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onTakePhoto = ::launchProductCamera,
                        onRetry = {
                            viewModel.loadProductImage(
                                productId = product.id,
                                variant = ProductImageVariant.MAIN,
                                expectedVersionId = product.primaryImageVersionId
                            )
                        },
                        onRemove = { confirmImageRemoval = true }
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
                    OutlinedTextField(value = productName, onValueChange = { productName = it; validate() }, label = { Text(stringResource(R.string.product_name_label)) }, modifier = Modifier.fillMaxWidth(), isError = productNameError != null, supportingText = { productNameError?.let { Text(it) } })
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
                        onValueChange = { purchasePrice = it },
                        label = { CompactFieldLabel(R.string.purchase_price_label) },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { state ->
                                if (!state.isFocused) {
                                    purchasePrice = normalizeClPriceInput(purchasePrice)
                                }
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        supportingText = {
                            PriceHistorySupportingText(
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
                            onValueChange = { stockQuantity = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            label = { CompactFieldLabel(R.string.header_stock_quantity) },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { state ->
                                    if (!state.isFocused) {
                                        stockQuantity = normalizeClQuantityInput(stockQuantity)
                                    }
                                },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    }
                } else {
                    TextButton(onClick = { showItemNumberField = true }) { Text(stringResource(R.string.add_item_code)) }
                    OutlinedTextField(
                        value = stockQuantity,
                        onValueChange = { stockQuantity = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                        label = { CompactFieldLabel(R.string.header_stock_quantity) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { state ->
                                if (!state.isFocused) {
                                    stockQuantity = normalizeClQuantityInput(stockQuantity)
                                }
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }

                SelectionTextField(
                    value = supplierName,
                    labelRes = R.string.supplier_label,
                    contentDescriptionRes = R.string.select_supplier,
                    onClick = { showSupplierSelectionDialog = true },
                    modifier = Modifier.fillMaxWidth()
                )

                SelectionTextField(
                    value = categoryName,
                    labelRes = R.string.category_label,
                    contentDescriptionRes = R.string.select_category,
                    onClick = { showCategorySelectionDialog = true },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (validate()) {
                            val productToSave = product.copy(
                                barcode = barcode.trim(),
                                productName = productName.trim(),
                                secondProductName = secondProductName.trim().takeIf { it.isNotBlank() },
                                itemNumber = itemNumber.trim().takeIf { it.isNotBlank() },
                                purchasePrice = parseUserPriceInput(purchasePrice),
                                retailPrice = parseUserPriceInput(retailPrice),
                                supplierId = supplierId,
                                categoryId = categoryId,
                                stockQuantity = parseUserQuantityInput(stockQuantity)
                            )
                            onSave(productToSave)
                        }
                    }) { Text(stringResource(R.string.save)) }
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
    }
}

@Composable
private fun ProductImageEditorSection(
    product: Product,
    state: ProductImageUiState?,
    apiConfigured: Boolean,
    canManage: Boolean,
    onChoosePhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val working = state?.status in setOf(
        ProductImageUiStatus.LOADING,
        ProductImageUiStatus.UPLOADING,
        ProductImageUiStatus.REMOVING
    )
    val hasImage = product.primaryImageVersionId != null || state?.versionId != null
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
                state = state,
                contentDescription = stringResource(R.string.product_image_main),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )
            when {
                product.id == 0L -> Text(
                    text = stringResource(R.string.product_image_save_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                !apiConfigured -> Text(
                    text = stringResource(R.string.product_image_not_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                state?.status == ProductImageUiStatus.ERROR -> Text(
                    text = stringResource(R.string.product_image_operation_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                state?.source == ProductImageLoadSource.CACHE -> Text(
                    text = stringResource(R.string.product_image_offline_cache),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state?.status == ProductImageUiStatus.ERROR) {
                TextButton(
                    enabled = !working,
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.product_image_retry))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    enabled = product.id != 0L && canManage && !working,
                    onClick = onChoosePhoto,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.product_image_choose))
                }
                TextButton(
                    enabled = product.id != 0L && canManage && !working,
                    onClick = onTakePhoto,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.product_image_camera))
                }
            }
            if (hasImage) {
                TextButton(
                    enabled = canManage && !working,
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.product_image_remove))
                }
            }
        }
    }
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
    onDismiss: () -> Unit,
    onSupplierSelected: (Supplier) -> Unit,
    onAddNewSupplier: (String) -> Unit
) {
    val suppliers by viewModel.suppliers.collectAsState()
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.onSupplierSearchQueryChanged("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_supplier_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it; viewModel.onSupplierSearchQueryChanged(it) },
                    label = { Text(stringResource(R.string.search_or_add_supplier)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
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
                            onClick = { onSupplierSelected(supplier) }
                        )
                    }
                    if (suppliers.none { it.name.equals(searchText, ignoreCase = true) } && searchText.isNotBlank()) {
                        item {
                            SelectionDialogRow(
                                text = stringResource(R.string.add_new_supplier_prompt, searchText),
                                onClick = { onAddNewSupplier(searchText) },
                                highlighted = true
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun CategorySelectionDialog(
    viewModel: DatabaseViewModel,
    onDismiss: () -> Unit,
    onCategorySelected: (Category) -> Unit,
    onAddNewCategory: (String) -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.onCategorySearchQueryChanged("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                            onClick = { onCategorySelected(category) }
                        )
                    }

                    if (categories.none { it.name.equals(searchText, ignoreCase = true) } && searchText.isNotBlank()) {
                        item {
                            SelectionDialogRow(
                                text = stringResource(R.string.add_new_category_prompt, searchText),
                                onClick = { onAddNewCategory(searchText) },
                                highlighted = true
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun SelectionDialogRow(
    text: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
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
            .clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.runtime.Composable
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiState

/**
 * Entry point disponibile soltanto nell'APK debug per l'evidence UI strumentale.
 * La release non espone hook e il comportamento del composable resta invariato.
 */
@Composable
fun ProductImageEditorSectionDebugTestHook(
    product: Product,
    mainState: ProductImageUiState?,
    thumbState: ProductImageUiState?,
    apiConfigured: Boolean,
    canManage: Boolean,
    onChoosePhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onRetry: () -> Unit,
    onCancelOperation: () -> Unit,
    onRemove: () -> Unit
) {
    ProductImageEditorSection(
        product = product,
        mainState = mainState,
        thumbState = thumbState,
        apiConfigured = apiConfigured,
        canManage = canManage,
        onChoosePhoto = onChoosePhoto,
        onTakePhoto = onTakePhoto,
        onRetry = onRetry,
        onCancelOperation = onCancelOperation,
        onRemove = onRemove
    )
}

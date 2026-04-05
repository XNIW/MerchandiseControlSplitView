package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.ConfigurationCompat
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductPrice
import com.example.merchandisecontrolsplitview.util.DatabaseExportSheet
import com.example.merchandisecontrolsplitview.util.ExportSheetSelection
import com.example.merchandisecontrolsplitview.util.formatClPricePlainDisplay
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val priceHistoryStorageFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private const val LOADING_SAFETY_TIMEOUT_MS = 180_000L
private const val PRICE_HISTORY_CUSTOM_SOURCE_MAX_LENGTH = 24
private const val PRICE_HISTORY_ELLIPSIS = "\u2026"

@Composable
internal fun LoadingDialog(
    loading: UiState.Loading,
    onSafetyTimeout: () -> Unit = {}
) {
    LoadingDialog(
        message = loading.message,
        progress = loading.progress,
        onSafetyTimeout = onSafetyTimeout
    )
}

@Composable
internal fun LoadingDialog(
    message: String?,
    progress: Int?,
    onSafetyTimeout: () -> Unit = {}
) {
    val currentOnSafetyTimeout by rememberUpdatedState(onSafetyTimeout)

    LaunchedEffect(Unit) {
        delay(LOADING_SAFETY_TIMEOUT_MS)
        currentOnSafetyTimeout()
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(min = 280.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 6.dp
                    )

                    Text(
                        text = message ?: stringResource(R.string.operation_in_progress),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val target = ((progress ?: 0).coerceIn(0, 100)) / 100f
                    val animated by animateFloatAsState(targetValue = target, label = "importProgress")

                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { animated },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            text = "${(animated * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DatabaseExportDialog(
    selection: ExportSheetSelection,
    exportInProgress: Boolean,
    onSelectionChange: (ExportSheetSelection) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val presets = remember {
        listOf(
            R.string.export_preset_all to ExportSheetSelection.full(),
            R.string.export_preset_products_only to ExportSheetSelection.productsOnly(),
            R.string.export_preset_catalog to ExportSheetSelection.catalogOnly(),
            R.string.export_preset_price_history_only to ExportSheetSelection.priceHistoryOnly()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.export_database_dialog_title))
                Text(
                    text = stringResource(R.string.export_database_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.export_database_dialog_presets_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { (labelRes, presetSelection) ->
                            FilterChip(
                                selected = selection == presetSelection,
                                onClick = { onSelectionChange(presetSelection) },
                                label = { Text(stringResource(labelRes)) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.export_database_dialog_sheets_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DatabaseExportSheet.entries.forEach { sheet ->
                        ExportSheetSelectionRow(
                            sheet = sheet,
                            checked = when (sheet) {
                                DatabaseExportSheet.PRODUCTS -> selection.products
                                DatabaseExportSheet.SUPPLIERS -> selection.suppliers
                                DatabaseExportSheet.CATEGORIES -> selection.categories
                                DatabaseExportSheet.PRICE_HISTORY -> selection.priceHistory
                            },
                            onCheckedChange = { checked ->
                                onSelectionChange(selection.withSheet(sheet, checked))
                            }
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                ) {
                    Text(
                        text = stringResource(
                            if (selection.isFullExport) {
                                R.string.export_database_dialog_full_copy
                            } else {
                                R.string.export_database_dialog_partial_copy
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !selection.isEmpty && !exportInProgress,
                onClick = onConfirm
            ) {
                Text(stringResource(R.string.export_file))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ExportSheetSelectionRow(
    sheet: DatabaseExportSheet,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(sheet.labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = sheet.technicalName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun DeleteProductConfirmationDialog(
    product: Product,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        title = { Text(stringResource(R.string.delete_confirmation_title)) },
        text = {
            val trimmedName = product.productName?.trim().orEmpty()
            val nameLine = if (trimmedName.isEmpty()) {
                stringResource(R.string.unnamed_product)
            } else {
                trimmedName
            }
            val trimmedBarcode = product.barcode.trim()
            val barcodeLine = if (trimmedBarcode.isEmpty()) {
                stringResource(R.string.delete_confirmation_barcode_empty)
            } else {
                trimmedBarcode
            }
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.delete_confirmation_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = nameLine,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.barcode_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = barcodeLine,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PriceHistoryBottomSheet(
    product: Product,
    purchase: List<ProductPrice>,
    retail: List<ProductPrice>,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
    }
    val dateTimeFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(product.productName ?: stringResource(R.string.unnamed_product), style = MaterialTheme.typography.titleMedium)
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_purchase)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_retail)) })
            }
            val list = if (tab == 0) purchase else retail
            if (list.isEmpty()) {
                Text(
                    text = stringResource(
                        R.string.price_history_empty_for_tab,
                        stringResource(if (tab == 0) R.string.tab_purchase else R.string.tab_retail)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
            } else {
                LazyColumn {
                    items(list) { pt ->
                        PriceHistoryRow(
                            pricePoint = pt,
                            dateTimeFormatter = dateTimeFormatter
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceHistoryRow(
    pricePoint: ProductPrice,
    dateTimeFormatter: DateTimeFormatter
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = formatClPricePlainDisplay(pricePoint.price),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatPriceHistoryDateTime(
                rawEffectiveAt = pricePoint.effectiveAt,
                dateTimeFormatter = dateTimeFormatter
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = priceHistorySourceLabel(pricePoint.source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    HorizontalDivider()
}

private fun formatPriceHistoryDateTime(
    rawEffectiveAt: String,
    dateTimeFormatter: DateTimeFormatter
): String = runCatching {
    LocalDateTime.parse(rawEffectiveAt, priceHistoryStorageFormatter).format(dateTimeFormatter)
}.getOrDefault(rawEffectiveAt)

@Composable
private fun priceHistorySourceLabel(source: String?): String {
    val raw = source?.trim().orEmpty()
    if (raw.isEmpty()) {
        return stringResource(R.string.price_history_source_unspecified)
    }

    return when (raw) {
        "MANUAL" -> stringResource(R.string.price_history_source_manual)
        "IMPORT" -> stringResource(R.string.price_history_source_import)
        "IMPORT_PREV" -> stringResource(R.string.price_history_source_import_prev)
        "BACKFILL_CURR" -> stringResource(R.string.price_history_source_backfill)
        "IMPORT_SHEET" -> stringResource(R.string.price_history_source_sheet)
        else -> stringResource(
            R.string.price_history_source_custom,
            raw.toPriceHistoryCustomDisplaySegment()
        )
    }
}

private fun String.toPriceHistoryCustomDisplaySegment(): String {
    if (length <= PRICE_HISTORY_CUSTOM_SOURCE_MAX_LENGTH) {
        return this
    }

    return take(PRICE_HISTORY_CUSTOM_SOURCE_MAX_LENGTH) + PRICE_HISTORY_ELLIPSIS
}

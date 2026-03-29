package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.ConfigurationCompat
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductPrice
import com.example.merchandisecontrolsplitview.util.formatNumberAsRoundedString
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val priceHistoryStorageFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private const val PRICE_HISTORY_CUSTOM_SOURCE_MAX_LENGTH = 24
private const val PRICE_HISTORY_ELLIPSIS = "\u2026"

@Composable
internal fun LoadingDialog(loading: UiState.Loading) {
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
                shape = RoundedCornerShape(24.dp),
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
                        text = loading.message ?: stringResource(R.string.operation_in_progress),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val target = ((loading.progress ?: 0).coerceIn(0, 100)) / 100f
                    val animated by animateFloatAsState(targetValue = target, label = "importProgress")

                    if (loading.progress != null) {
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

@Composable
internal fun DeleteProductConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirmation_title)) },
        text = { Text(stringResource(R.string.delete_confirmation_message)) },
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
            text = formatNumberAsRoundedString(pricePoint.price),
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

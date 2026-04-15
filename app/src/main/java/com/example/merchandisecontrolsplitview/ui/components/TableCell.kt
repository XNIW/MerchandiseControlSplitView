package com.example.merchandisecontrolsplitview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.ui.theme.appColors
import com.example.merchandisecontrolsplitview.ui.theme.appSpacing
import java.util.Locale

@Composable
fun TableCell(
    text: String,
    width: Dp,
    height: Dp,
    isHeader: Boolean = false,
    isSelectedColumn: Boolean = false,
    isRowFilled: Boolean = false,
    isSearchMatch: Boolean = false,
    isRowComplete: Boolean = false,
    onCellClick: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null,
    columnKey: String? = null,
    overrideBackgroundColor: Color? = null
) {
    val appColors = MaterialTheme.appColors
    val spacing = MaterialTheme.appSpacing
    val isDarkTheme = isSystemInDarkTheme()
    val defaultBackgroundColor = Color.Transparent
    val searchStateAlpha = if (isDarkTheme) 0.20f else 0.08f
    val rowFilledBackgroundAlpha = if (isDarkTheme) 0.52f else 0.34f
    val rowCompleteBackgroundAlpha = if (isDarkTheme) 0.48f else 0.30f
    val headerBackgroundAlpha = if (isDarkTheme) 0.74f else 0.38f
    val selectedColumnBorderAlpha = if (isDarkTheme) 0.64f else 0.34f
    val hasErrorOverride = overrideBackgroundColor != null && !isHeader
    val normalizedColumnKey = columnKey?.lowercase(Locale.ROOT)
    val isProductColumn = normalizedColumnKey == "productname" || normalizedColumnKey == "secondproductname"
    val isQuantityColumn = normalizedColumnKey == "quantity" || normalizedColumnKey == "realquantity"
    val isMoneyColumn = normalizedColumnKey == "purchaseprice" ||
        normalizedColumnKey == "retailprice" ||
        normalizedColumnKey == "totalprice" ||
        normalizedColumnKey == "discountedprice" ||
        normalizedColumnKey == "oldpurchaseprice" ||
        normalizedColumnKey == "oldretailprice"
    val isCodeColumn = normalizedColumnKey == "barcode" || normalizedColumnKey == "itemnumber"
    val isStatusColumn = normalizedColumnKey == "complete"
    val isTrailingValueColumn = isMoneyColumn ||
        isCodeColumn ||
        normalizedColumnKey == "discount" ||
        normalizedColumnKey == "rownumber" ||
        normalizedColumnKey == "supplierid" ||
        normalizedColumnKey == "categoryid"
    val isEditableColumn = normalizedColumnKey == "realquantity" || normalizedColumnKey == "retailprice"
    val isMutedValueColumn = normalizedColumnKey == "oldpurchaseprice" || normalizedColumnKey == "oldretailprice"
    val isPrimaryContentColumn = isProductColumn || isQuantityColumn || isMoneyColumn
    val hasHeaderAccent = isHeader && overrideBackgroundColor != null
    // Shared visual rule: text columns start, quantities/status center, numeric/id values trail.
    val horizontalCellAlignment = when {
        isQuantityColumn || isStatusColumn -> Alignment.Center
        isTrailingValueColumn -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    val editableContentAlignment = when {
        isQuantityColumn -> Alignment.Center
        isMoneyColumn -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    val textAlign = when {
        isQuantityColumn || isStatusColumn -> TextAlign.Center
        isTrailingValueColumn -> TextAlign.End
        else -> TextAlign.Start
    }
    val textStyle = when {
        isHeader -> MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp
        )
        isQuantityColumn -> MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp
        )
        isMoneyColumn -> MaterialTheme.typography.bodySmall.copy(
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp
        )
        isProductColumn -> MaterialTheme.typography.bodySmall.copy(
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp
        )
        else -> MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp)
    }
    val textFontFamily = if (isCodeColumn && !isHeader) FontFamily.Monospace else null
    val textMaxLines = when {
        isHeader || isProductColumn -> 2
        else -> 1
    }

    // Visual priority: explicit override (error rows / header meta-state) > completed rows.
    // Search, selected column and rowFilled stay secondary via light tint and border only.
    val finalBackgroundColor = when {
        hasErrorOverride -> overrideBackgroundColor
        isRowComplete -> appColors.successContainer.copy(alpha = rowCompleteBackgroundAlpha)
        isHeader -> (overrideBackgroundColor ?: MaterialTheme.colorScheme.surfaceVariant).copy(alpha = headerBackgroundAlpha)
        isSearchMatch -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = searchStateAlpha)
        isRowFilled -> appColors.filledContainer.copy(alpha = rowFilledBackgroundAlpha)
        else -> defaultBackgroundColor
    }

    val textBaseColor = when {
        hasErrorOverride -> MaterialTheme.colorScheme.onErrorContainer
        isRowComplete -> appColors.onSuccessContainer
        isRowFilled -> appColors.onFilledContainer
        isSearchMatch -> MaterialTheme.colorScheme.tertiary
        isHeader && hasHeaderAccent -> MaterialTheme.colorScheme.onSurface
        isHeader || isMutedValueColumn -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val textAlpha = when {
        hasErrorOverride -> if (isPrimaryContentColumn) 0.96f else 0.84f
        isRowComplete -> if (isPrimaryContentColumn) 1.0f else 0.92f
        isRowFilled -> if (isPrimaryContentColumn) 0.99f else 0.90f
        isSearchMatch -> if (isPrimaryContentColumn) 0.90f else 0.78f
        hasHeaderAccent -> 0.84f
        isHeader -> 0.72f
        isMutedValueColumn -> 0.56f
        isCodeColumn -> 0.70f
        isPrimaryContentColumn -> 0.88f
        else -> 0.80f
    }
    val textColor = textBaseColor.copy(alpha = textAlpha)

    val borderColor = when {
        hasErrorOverride -> MaterialTheme.colorScheme.error.copy(alpha = 0.32f)
        isSearchMatch -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)
        isSelectedColumn && !isRowComplete -> MaterialTheme.colorScheme.primary.copy(alpha = selectedColumnBorderAlpha)
        else -> Color.Transparent
    }

    val borderThickness = when {
        isSelectedColumn && !isRowComplete -> 0.5.dp
        isSearchMatch && !isEditableColumn -> 0.45.dp
        else -> 0.dp
    }
    val showEditableShell = !isHeader && isEditableColumn
    val editableShellBackground = when {
        hasErrorOverride -> MaterialTheme.colorScheme.errorContainer.copy(alpha = if (isDarkTheme) 0.34f else 0.20f)
        isRowComplete -> appColors.successContainer.copy(alpha = if (isDarkTheme) 0.56f else 0.42f)
        isRowFilled -> appColors.filledContainer.copy(alpha = if (isDarkTheme) 0.52f else 0.40f)
        else -> MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    }
    val editableShellBorder = when {
        hasErrorOverride -> MaterialTheme.colorScheme.error.copy(alpha = 0.32f)
        isRowFilled -> appColors.warning.copy(alpha = if (isDarkTheme) 0.58f else 0.46f)
        isSearchMatch -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
        isSelectedColumn -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        isRowComplete -> appColors.success.copy(alpha = if (isDarkTheme) 0.54f else 0.42f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDarkTheme) 0.18f else 0.10f)
    }
    val cellBorderModifier = if (borderThickness > 0.dp) {
        Modifier.border(borderThickness, borderColor)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(finalBackgroundColor)
            .then(cellBorderModifier)
            .then(
                // L'intera cella rimane cliccabile per la selezione/deselezione
                if (onCellClick != null) Modifier.clickable { onCellClick() }
                else Modifier
            ),
        contentAlignment = horizontalCellAlignment
    ) {
        // Header cells above editable columns must use the same total horizontal
        // padding as the body (Row padding + editable-shell internal padding) so
        // that text sits on the same vertical axis across the column.
        val headerMatchesEditableShell = isHeader && isEditableColumn
        val horizontalPadding = when {
            headerMatchesEditableShell && isQuantityColumn -> spacing.xs + spacing.xs   // 12.dp — matches body 6+6
            headerMatchesEditableShell -> spacing.xs + spacing.sm                       // 14.dp — matches body 6+8
            showEditableShell || isQuantityColumn || isMoneyColumn -> spacing.xs
            else -> spacing.sm
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = horizontalPadding,
                    vertical = if (isHeader) spacing.xs else if (showEditableShell) 4.dp else 5.dp
                ),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showEditableShell) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(
                            color = editableShellBackground,
                            shape = MaterialTheme.shapes.medium
                        )
                        .border(
                            width = 0.6.dp,
                            color = editableShellBorder,
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(horizontal = if (isQuantityColumn) spacing.xs else spacing.sm),
                    contentAlignment = editableContentAlignment
                ) {
                    Text(
                        text = text,
                        color = textColor,
                        style = textStyle,
                        fontFamily = textFontFamily,
                        textAlign = textAlign,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Text(
                    text = text,
                    color = textColor,
                    style = textStyle,
                    fontFamily = textFontFamily,
                    textAlign = textAlign,
                    maxLines = textMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            if (isHeader && onEditClick != null) {
                IconButton(onClick = onEditClick, modifier = Modifier.size(22.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit_column_type),
                        tint = textColor
                    )
                }
            }
        }
    }
}

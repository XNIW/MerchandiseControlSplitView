package com.example.merchandisecontrolsplitview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    overrideBackgroundColor: Color? = null
) {
    val defaultBackgroundColor = MaterialTheme.colorScheme.surface

    // --- NUOVI COLORI VIVACI ---
    val finalBackgroundColor = overrideBackgroundColor ?: when {
        isHeader -> MaterialTheme.colorScheme.surfaceVariant

        // Verde VIVACE
        isRowComplete -> if (isSystemInDarkTheme()) Color(0xFF00C853).copy(alpha = 0.5f) else Color(0xFFB9F6CA)

        // Giallo VIVACE
        isRowFilled -> if (isSystemInDarkTheme()) Color(0xFFFFD600).copy(alpha = 0.5f) else Color(0xFFFFF176)

        isSearchMatch -> MaterialTheme.colorScheme.tertiaryContainer
        isSelectedColumn -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else -> defaultBackgroundColor
    }

    val textColor = when {
        isHeader -> MaterialTheme.colorScheme.onSurfaceVariant
        isSearchMatch -> MaterialTheme.colorScheme.onTertiaryContainer
        isSelectedColumn -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(finalBackgroundColor)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .then(
                if (onCellClick != null) Modifier.clickable { onCellClick() }
                else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            color = textColor,
            style = if (isHeader)
                MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            else
                MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
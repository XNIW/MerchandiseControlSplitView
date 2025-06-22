package com.example.merchandisecontrolsplitview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip

@Composable
fun TableCell(
    text: String,
    width: Dp,
    height: Dp,
    isHeader: Boolean,
    isSelectedColumn: Boolean,
    isRowFilled: Boolean,
    isSearchMatch: Boolean,
    isRowComplete: Boolean,
    onCellClick: (() -> Unit)?,
    backgroundColor: Color = Color.Unspecified
) {
    val backgroundColor = when {
        isRowComplete    -> Color.Green
        isSearchMatch    -> Color.Cyan
        isRowFilled      -> Color.Yellow
        isSelectedColumn -> Color(0xFFD6EAF8)
        isHeader         -> Color(0xFFE0E0E0)
        else             -> Color.White
    }
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(backgroundColor)
            .border(1.dp, Color.Gray)
            .clip(RoundedCornerShape(2.dp))
            .padding(4.dp)
            .then(
                if (onCellClick != null) Modifier.clickable { onCellClick() }
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = if (isHeader)
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
            else
                MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
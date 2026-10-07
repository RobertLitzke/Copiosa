package com.copiosa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.copiosa.data.epub.PipeTable
import com.copiosa.ui.theme.LocalReadingStyle

/**
 * A stored [PipeTable] as an actual grid, scrolled sideways when it is wider than the page.
 *
 * The cell content is the caller's, which is the whole point: the reader's target side puts a
 * [SelectableMessage] in every cell, so a conjugation chart or a glossary — the tables a language
 * learner most wants to tap — stays tappable word by word, while the native gloss puts a plain
 * `Text` in the same layout. One geometry, two behaviours, and no second copy of the layout.
 *
 * Columns are a fixed width rather than measured: Compose has no intrinsic table, and measuring
 * every cell to find natural column widths would mean a custom layout pass on a composable sitting
 * inside a scrolling `LazyColumn`. A fixed width scrolls predictably and aligns exactly.
 */
@Composable
fun ReadingTable(
    table: PipeTable,
    modifier: Modifier = Modifier,
    /**
     * Renders one cell. [header] is true for the first row, so the caller can emphasise it, and
     * [index] counts cells in reading order — the reader keys each cell's selection on it, so it
     * must be stable across recomposition rather than a counter the lambda increments itself.
     */
    cell: @Composable (text: String, header: Boolean, index: Int) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerTint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = HEADER_ALPHA)
    val width = (CELL_WIDTH_DP * LocalReadingStyle.current.scale).dp

    Column(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .clip(MaterialTheme.shapes.small),
    ) {
        val rows = table.padded()
        rows.forEachIndexed { rowIndex, row ->
            val header = rowIndex == 0
            Row(modifier = if (header) Modifier.background(headerTint) else Modifier) {
                row.forEachIndexed { columnIndex, text ->
                    Box(
                        modifier = Modifier
                            .width(width)
                            .cellBorder(outline)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        cell(text, header, rowIndex * table.width + columnIndex)
                    }
                }
            }
        }
    }
}

/** Hairlines on the right and bottom edges only, so neighbouring cells share one line. */
private fun Modifier.cellBorder(color: Color): Modifier = drawBehind {
    val stroke = density
    drawRect(color, topLeft = Offset(size.width - stroke, 0f), size = Size(stroke, size.height))
    drawRect(color, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
}

private const val CELL_WIDTH_DP = 140f
private const val HEADER_ALPHA = 0.6f

package com.copiosa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * A hairline showing how far through a text the reader is: two pixels of filled rule along the
 * bottom of the reading pane, and nothing else. A reader wants the answer to "how much is left" to
 * be available without being asked for — a percentage, a page count or a scrollbar all demand a
 * glance and a moment's arithmetic, where a rule filling up is read peripherally and costs nothing.
 *
 * [fraction] is read in the draw phase, so scrolling redraws this without recomposing anything.
 */
@Composable
fun ReadingProgressRule(fraction: () -> Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)
    val fill = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.fillMaxWidth().height(RULE_HEIGHT)) {
        drawRect(color = track)
        val width = size.width * fraction().coerceIn(0f, 1f)
        if (width > 0f) drawRect(color = fill, size = Size(width, size.height))
    }
}

/**
 * How far through a lazy list the reader has got, by item: the *last* visible item is what counts,
 * so a text that fits on one screen reads as finished and reaching the end of a long one lands on
 * exactly full. Item-granular rather than pixel-granular — paragraphs vary in height, and a hairline
 * cannot show the difference anyway.
 */
fun LazyListState.readFraction(): Float {
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total <= 0) return 0f
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return 0f
    return ((last + 1).toFloat() / total).coerceIn(0f, 1f)
}

/** Deliberately hairline: present at the edge of vision, invisible until looked for. */
private val RULE_HEIGHT = 2.dp

/** The unread remainder is a groove rather than a bar — barely there against the surface. */
private const val TRACK_ALPHA = 0.10f

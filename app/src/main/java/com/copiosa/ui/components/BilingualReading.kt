package com.copiosa.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.epub.PipeTable
import com.copiosa.data.epub.parsePipeTable
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.reading.ReadMode
import com.copiosa.ui.theme.LocalReadingStyle
import com.copiosa.ui.theme.skin.LocalAppSkin
import kotlinx.coroutines.delay

/**
 * One row of a bilingual reader, rendered in either language — or, in [ReadMode.BOTH], in both at
 * once, target first with the native gloss beneath it. Which stored column supplies which language
 * is the caller's problem (a reverse book's "target" is its original text); this only ever thinks in
 * target/native.
 *
 * The target side gets the full tap-to-translate machinery, in both directions — it is the language
 * being learned, so it is the one worth looking words up in. Either side can be absent (a book past
 * the frontier, an article still translating, or an alignment miss).
 *
 * A missing *target* is called out rather than papered over: showing the source text muted in the
 * translation's place reads as a translation that happens to be identical, which is exactly the
 * confusion a reader hits on a freshly opened book. In Both mode the native gloss still shows
 * beneath the notice, since that side is legitimately part of the view. A missing *native* in Native
 * mode still falls back to the target — there is nothing pending there to wait for.
 *
 * Both texts stay inside the one list item: in the book reader index == ord + header items is what
 * scroll restore, read-ahead, the TOC jump and the tint range all rely on, and a second item per
 * paragraph would break it.
 *
 * [kind] is the stored block slug. It is read, never exhaustively matched: an unrecognised value
 * renders as an ordinary paragraph, which is the whole reason the slug is plain text.
 */
@Composable
fun BilingualBlockRow(
    target: String?,
    native: String?,
    mode: ReadMode,
    colorizer: WordColorizer,
    isActive: Boolean,
    selection: ReadingSelectionSpan,
    block: Int,
    onSelected: (text: String, anchorY: Float, sourceText: String) -> Unit,
    onCleared: () -> Unit,
    kind: String = BookParagraphEntity.KIND_PARA,
    onFootnote: ((String) -> Unit)? = null,
) {
    // A table is the one block that cannot go through the flat-string path: it has to be laid out
    // as a grid. Only reached when the text still parses as one — a translation the model reflowed
    // into prose falls through and renders as prose, which is what it now is.
    if (kind == BookParagraphEntity.KIND_TABLE) {
        val shown = if (mode == ReadMode.NATIVE) native ?: target else target ?: native
        val table = remember(shown) { shown?.let(::parsePipeTable) }
        if (table != null) {
            BilingualTableRow(
                table = table,
                gloss = native.takeIf { mode == ReadMode.BOTH && it != target },
                interactive = mode != ReadMode.NATIVE && !target.isNullOrBlank(),
                colorizer = colorizer,
                isActive = isActive,
                selection = selection,
                block = block,
                onSelected = onSelected,
                onCleared = onCleared,
            )
            return
        }
    }
    when {
        mode == ReadMode.NATIVE -> if (native.isNullOrBlank()) {
            // Nothing pending — a reverse book simply has no gloss here. Show what there is, muted.
            SubordinateGloss(target.orEmpty())
        } else {
            SelectionContainer { MarkdownText(native) }
        }
        // No translation for this passage yet. Say so instead of showing the source in its place.
        target.isNullOrBlank() ->
            if (mode == ReadMode.BOTH && !native.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PendingTranslationNotice()
                    SubordinateGloss(native)
                }
            } else {
                PendingTranslationNotice()
            }
        mode == ReadMode.BOTH && !native.isNullOrBlank() ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // A tight gap against the list's 12.dp spacing is what makes the pair read as one
                // block; the correspondence tint wraps both and reinforces it.
                SelectableMessage(
                    content = target,
                    colorizer = colorizer,
                    isActive = isActive,
                    onSelected = { text, y -> onSelected(text, y, target) },
                    onCleared = onCleared,
                    selection = selection,
                    block = block,
                    onFootnote = onFootnote,
                )
                SubordinateGloss(native)
            }
        else -> SelectableMessage(
            content = target,
            colorizer = colorizer,
            isActive = isActive,
            onSelected = { text, y -> onSelected(text, y, target) },
            onCleared = onCleared,
            selection = selection,
            block = block,
            onFootnote = onFootnote,
        )
    }
}

/**
 * A table row, rendered as a grid with every cell independently tappable.
 *
 * Each cell is its own [SelectableMessage] keyed `block + cell + 1`. [ReadingSelectionSpan] treats
 * the key as an ordered integer — it compares them and iterates the range between the ends of a
 * selection — so the cells have to sort after their own row and before the next one. That is what
 * the caller's stride buys: rows sit at `ord * BLOCK_STRIDE` and the cells fill the gap, so a drag
 * out of a table into the paragraph below still resolves in reading order.
 *
 * Tables in a language reader are conjugation charts and glossaries — the densest vocabulary on the
 * page — so per-cell tapping is worth the extra keys rather than a table being one dead block.
 */
@Composable
private fun BilingualTableRow(
    table: PipeTable,
    gloss: String?,
    interactive: Boolean,
    colorizer: WordColorizer,
    isActive: Boolean,
    selection: ReadingSelectionSpan,
    block: Int,
    onSelected: (text: String, anchorY: Float, sourceText: String) -> Unit,
    onCleared: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReadingTable(table) { text, header, index ->
            when {
                text.isBlank() -> Unit
                // Past the stride there is no key left before the next row's, and a colliding key
                // would splice this cell into that row's selection. Such a table is pathological;
                // its tail simply reads as plain text.
                !interactive || index + 1 >= BLOCK_STRIDE -> Text(
                    text,
                    style = readingStyle(MaterialTheme.typography.bodyLarge)
                        .let { if (header) it.copy(fontWeight = FontWeight.Bold) else it },
                )
                else -> SelectableMessage(
                    content = text,
                    colorizer = colorizer,
                    isActive = isActive,
                    onSelected = { selected, y -> onSelected(selected, y, text) },
                    onCleared = onCleared,
                    selection = selection,
                    block = block + index + 1,
                    styleOverride = MaterialTheme.typography.bodyLarge
                        .let { if (header) it.copy(fontWeight = FontWeight.Bold) else it },
                )
            }
        }
        // The gloss is the whole table again in the other language rather than cell-by-cell: a
        // translated table need not have kept the same shape, and pairing cells that no longer
        // correspond would be worse than showing the two tables one under the other.
        if (gloss != null) SubordinateGloss(gloss)
    }
}

/**
 * The subordinate language as a gloss: muted and a touch smaller, so in Both mode the eye stays on
 * the target language and drops to this only to check itself. Also how an untranslated passage
 * renders. MarkdownText needs no parameters for it — it already honours both of these locals.
 */
@Composable
fun SubordinateGloss(text: String) {
    val reading = LocalReadingStyle.current
    CompositionLocalProvider(
        LocalReadingStyle provides reading.copy(scale = reading.scale * GLOSS_SCALE),
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        SelectionContainer { MarkdownText(text) }
    }
}

/**
 * Stands in for a passage whose translation has not arrived yet — quiet, italic, and clearly not
 * prose, so it reads as a status rather than as text to be read.
 */
@Composable
private fun PendingTranslationNotice() {
    Text(
        stringResource(R.string.book_paragraph_pending),
        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The correspondence tint: on a mode switch, rows near the viewport pick up a faint colour from the
 * theme's reading palette, cycled by row index, so the matching passage is easy to find in the other
 * language. It fades in, holds, then ebbs away — except in [ReadMode.BOTH], where the tint is what
 * binds each target/native pair and so stays lit until the reader leaves that mode.
 *
 * Only rows inside [range] read the animation, so the rest never recompose for it, and the value is
 * consumed in the draw phase (see [Modifier.correspondenceTint]) rather than in composition.
 */
@Stable
class CorrespondenceTint internal constructor() {
    internal val alpha = Animatable(0f)
    internal var epoch by mutableIntStateOf(0)
        private set

    /** Rows eligible for the tint: the captured window, or every row while Both mode holds it lit. */
    var range by mutableStateOf(IntRange.EMPTY)
        private set

    /**
     * Captures the window to tint for a switch into [next] and restarts the animation. [headerItems]
     * is how many non-row items precede the rows in [listState], so the window is in row indices.
     */
    fun capture(next: ReadMode, listState: LazyListState, headerItems: Int) {
        range = if (next == ReadMode.BOTH) {
            // The tint persists here, so scrolling must not outrun the captured window.
            0..Int.MAX_VALUE
        } else {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = (visible.firstOrNull()?.index ?: 0) - headerItems
            val last = (visible.lastOrNull()?.index ?: 0) - headerItems
            val extent = (last - first + 1).coerceAtLeast(1)
            (first - extent).coerceAtLeast(0)..(last + extent)
        }
        epoch += 1
    }
}

/**
 * Drives a [CorrespondenceTint] for a reader currently in [mode]. Keyed on the epoch alone: a mode
 * change bumps the epoch, so the effect restarts and re-reads [mode] with it.
 */
@Composable
fun rememberCorrespondenceTint(mode: ReadMode): CorrespondenceTint {
    val tint = remember { CorrespondenceTint() }
    LaunchedEffect(tint.epoch) {
        if (tint.epoch == 0) return@LaunchedEffect
        // Eased in rather than snapped on: colour arriving is as noticeable as colour leaving.
        tint.alpha.animateTo(1f, tween(TINT_RISE_MS, easing = FastOutSlowInEasing))
        // In Both mode the tint is the only thing pairing the two languages — it stays until the
        // reader leaves the mode, which restarts this effect and lets it fade.
        if (mode == ReadMode.BOTH) return@LaunchedEffect
        delay(TINT_HOLD_MS - TINT_RISE_MS)
        // Linear, so the colour ebbs evenly. The default ease-out holds near full and then drops
        // away at the end, which reads as the tint vanishing rather than fading.
        tint.alpha.animateTo(0f, tween(TINT_FADE_MS, easing = LinearEasing))
    }
    return tint
}

/**
 * Paints [tint] behind a row. Drawn, not composed: reading the animation inside `drawBehind`
 * redraws without recomposing the row's text on every frame of the fade. The padding is
 * unconditional so a tinted row never shifts the text.
 */
@Composable
fun Modifier.correspondenceTint(tint: CorrespondenceTint, row: Int): Modifier {
    val tints = LocalAppSkin.current.palette.tints
    return this
        .drawBehind {
            val alpha = tint.alpha.value
            if (alpha <= 0.004f || row !in tint.range || tints.isEmpty()) return@drawBehind
            drawRoundRect(
                color = tints[row.mod(tints.size)].copy(alpha = alpha),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
        }
        .padding(horizontal = 6.dp, vertical = 3.dp)
}

/**
 * Spacing between consecutive rows' selection keys.
 *
 * [ReadingSelectionSpan] orders blocks by that integer and iterates the range between the ends of a
 * selection, so a row rendering several independently selectable pieces — today only a table's
 * cells — needs keys of its own in the gap before the next row. A caller that keys its rows
 * `ord * BLOCK_STRIDE` gets that gap; one that keys them `ord` has no room and must not render a
 * table interactively.
 */
const val BLOCK_STRIDE = 256

/** How much smaller the subordinate gloss reads than the target text it sits under. */
private const val GLOSS_SCALE = 0.9f

// Tint timing: eased in, at full strength until eight seconds after the switch, then ebbing away
// over the next few. The colours themselves come from the theme's reading palette
// (CountryPalette.tints), which is where their subtlety lives — this animates them 0..1.
private const val TINT_RISE_MS = 300
private const val TINT_HOLD_MS = 8_000L
private const val TINT_FADE_MS = 2_800

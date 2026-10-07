package com.copiosa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange

/**
 * One selection shared across the paragraphs of a continuous text, so a drag that runs off the end
 * of one paragraph carries on into the next instead of stopping dead at its last word.
 *
 * Each [SelectableMessage] is a *block*, identified by its index in the list, and registers its text
 * layout and window position here as it is laid out. That is what lets a drag be resolved against
 * whichever paragraph the finger is currently over rather than only the one it started in: the
 * gesture's local position is lifted into window space, hit-tested against the registered blocks,
 * and dropped back into the target block's coordinates.
 *
 * Blocks that share a span share a selection — starting one anywhere clears the last, which is what
 * makes "only one highlight at a time" fall out rather than needing to be policed. A surface whose
 * blocks are *not* a continuous text (chat messages, separated by the reader's own turns) simply
 * gives each block its own span, which is what the default parameter on [SelectableMessage] does.
 */
@Composable
fun rememberReadingSelectionSpan(): ReadingSelectionSpan = remember { ReadingSelectionSpan() }

@Stable
class ReadingSelectionSpan {

    /**
     * Geometry of the blocks currently composed. Deliberately *not* snapshot state: it is written
     * from layout callbacks and read only by gesture code, and making it observable would have one
     * paragraph's re-layout recompose every other paragraph on screen.
     */
    private val blocks = HashMap<Int, Geometry>()

    // The two ends of the selection, each snapped to a word. Which is which doesn't matter — the
    // span is their union — so dragging backwards up the page works the same as dragging down.
    private var anchor by mutableStateOf<WordSpan?>(null)
    private var focus by mutableStateOf<WordSpan?>(null)

    /** [text] stays null until the block has actually been laid out once — see [onLayout]. */
    private class Geometry(var text: String? = null, var layout: TextLayoutResult? = null, var window: Offset = Offset.Zero)

    private data class WordSpan(val block: Int, val start: Int, val end: Int)

    private data class Caret(val block: Int, val offset: Int)

    private val start: Caret?
        get() {
            val a = anchor ?: return null
            val f = focus ?: a
            return minOf(Caret(a.block, a.start), Caret(f.block, f.start), CARET_ORDER)
        }

    private val end: Caret?
        get() {
            val a = anchor ?: return null
            val f = focus ?: a
            return maxOf(Caret(a.block, a.end), Caret(f.block, f.end), CARET_ORDER)
        }

    // --- registration, from the block's layout callbacks ---

    fun onLayout(block: Int, text: String, layout: TextLayoutResult) {
        val geometry = blocks.getOrPut(block) { Geometry() }
        val known = geometry.text
        geometry.text = text
        geometry.layout = layout
        // The block's text was *replaced* under a live selection — a translation chunk landing on a
        // paragraph that was pending. The offsets the selection holds no longer mean anything, so
        // drop it rather than leave a highlight sitting over the wrong words. A block being laid out
        // for the first time (known == null, e.g. scrolled back into a lazy list) is not a change:
        // its highlight is recomputed from the selection's block indices and is still correct.
        if (known != null && known != text && covers(block)) clear()
    }

    fun onPositioned(block: Int, windowOffset: Offset) {
        blocks.getOrPut(block) { Geometry() }.window = windowOffset
    }

    /** Called when a block leaves composition (scrolled out of a lazy list, or re-keyed). */
    fun forget(block: Int) {
        blocks.remove(block)
    }

    // --- the gesture ---

    /** Starts a selection at the word under [localPos], given in [block]'s own coordinates. */
    fun begin(block: Int, localPos: Offset) {
        val word = wordAt(block, localPos) ?: return
        anchor = word
        focus = word
    }

    /**
     * Extends the selection to the word under [localPos] — reported in [fromBlock]'s coordinates
     * (the block the gesture started in, which keeps receiving the pointer), but resolved against
     * whichever block the finger is actually over.
     */
    fun extendTo(fromBlock: Int, localPos: Offset) {
        val origin = blocks[fromBlock] ?: return
        val inWindow = origin.window + localPos
        val target = blockAt(inWindow) ?: fromBlock
        val geometry = blocks[target] ?: return
        focus = wordAt(target, inWindow - geometry.window) ?: return
    }

    fun clear() {
        anchor = null
        focus = null
    }

    // --- what the blocks and the card read ---

    /** The range to highlight inside [block], whose text is [length] characters long. */
    fun rangeFor(block: Int, length: Int): TextRange {
        val from = start ?: return TextRange.Zero
        val to = end ?: return TextRange.Zero
        if (block < from.block || block > to.block) return TextRange.Zero
        val first = if (block == from.block) from.offset else 0
        val last = if (block == to.block) to.offset else length
        return TextRange(first.coerceIn(0, length), last.coerceIn(0, length))
    }

    /** Everything selected, paragraphs rejoined by a blank line so a copied passage reads as prose. */
    fun selectedText(): String {
        val from = start ?: return ""
        val to = end ?: return ""
        return (from.block..to.block)
            .mapNotNull { block ->
                val text = blocks[block]?.text ?: return@mapNotNull null
                val length = text.length
                val first = (if (block == from.block) from.offset else 0).coerceIn(0, length)
                val last = (if (block == to.block) to.offset else length).coerceIn(0, length)
                text.substring(first, last).trim().ifBlank { null }
            }
            .joinToString("\n\n")
    }

    /** Vertical centre of the selection's first line in window pixels, for the card to dodge. */
    fun anchorY(): Float {
        val from = start ?: return 0f
        val geometry = blocks[from.block] ?: return 0f
        val layout = geometry.layout ?: return geometry.window.y
        val length = geometry.text?.length ?: 0
        if (length == 0) return geometry.window.y
        val box = layout.getBoundingBox(from.offset.coerceIn(0, length - 1))
        return geometry.window.y + (box.top + box.bottom) / 2f
    }

    // --- internals ---

    private fun covers(block: Int): Boolean {
        val from = start ?: return false
        val to = end ?: return false
        return block in from.block..to.block
    }

    /** Which registered block a window-space point falls in; the nearest one if it falls in a gap. */
    private fun blockAt(inWindow: Offset): Int? {
        var nearest: Int? = null
        var nearestGap = Float.MAX_VALUE
        for ((block, geometry) in blocks) {
            val layout = geometry.layout ?: continue
            val top = geometry.window.y
            val bottom = top + layout.size.height
            if (inWindow.y in top..bottom) return block
            val gap = if (inWindow.y < top) top - inWindow.y else inWindow.y - bottom
            if (gap < nearestGap) {
                nearestGap = gap
                nearest = block
            }
        }
        return nearest
    }

    private fun wordAt(block: Int, localPos: Offset): WordSpan? {
        val geometry = blocks[block] ?: return null
        val layout = geometry.layout ?: return null
        val text = geometry.text ?: return null
        val word = layout.getWordBoundary(layout.getOffsetForPosition(localPos))
        val (start, end) = trimWordPunctuation(text, word.min, word.max)
        return WordSpan(block, start, end)
    }

    private companion object {
        /** Reading order: earlier block first, then earlier character. */
        val CARET_ORDER = compareBy<Caret>({ it.block }, { it.offset })
    }
}

/**
 * Shrinks a word boundary off any punctuation clinging to its ends.
 *
 * `TextLayoutResult.getWordBoundary` delegates to Android's `WordIterator`, which treats a run of
 * punctuation as part of the boundary it abuts: tapping the right half of the "a" in "hola." comes
 * back as `hola.`, full stop included, and that range is what gets highlighted, copied and sent to
 * the dictionary. Only the ends are trimmed, so a word's own internal marks — the apostrophe in
 * *l'ami*, the hyphen in *ex-marido* — survive.
 *
 * A boundary that is *all* punctuation (tapping the stop itself) is handed back untouched: there is
 * no word there to find, and collapsing it to an empty span would silently move the caret onto a
 * neighbouring word.
 */
internal fun trimWordPunctuation(text: String, min: Int, max: Int): Pair<Int, Int> {
    var start = min.coerceIn(0, text.length)
    var end = max.coerceIn(start, text.length)
    while (end > start && !text[end - 1].isLetterOrDigit()) end--
    while (start < end && !text[start].isLetterOrDigit()) start++
    return if (start < end) start to end else min to max
}

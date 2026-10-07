package com.copiosa.domain.reading

/**
 * One row of a bilingual reader: a passage in the target language and the native-language passage it
 * corresponds to. Either side can be absent — a book past its translation frontier has no [target]
 * yet, and an article paragraph the aligner could not claim has no [native].
 *
 * Books get these row-exact from `BookSectionAligner` (the model echoes `[[P<ord>]]` markers, so
 * paragraph correspondence survives the round trip). Articles are translated as prose with no such
 * contract, so [BilingualBlocks.align] reconstructs the pairing after the fact. The reader itself is
 * written against this one shape either way.
 */
data class BilingualBlock(val target: String?, val native: String?)

/** Splitting an article's two versions into blocks, and pairing them up. */
object BilingualBlocks {

    /** Splits Markdown text into paragraph/heading blocks (separated by a blank line). */
    fun split(text: String): List<String> =
        text.split(BLANK_LINE).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Pairs an article's [native] (source) blocks with its [target] (translated) blocks.
     *
     * Rows are anchored on the *native* side, because that side is complete and fixed from the
     * moment the article is imported while the translation arrives in chunks. Anchoring there means
     * the row list — and so the reader's scroll position — doesn't shift under the reader as
     * translation lands, and an untranslated tail shows as pending rows rather than as an article
     * that appears to end early.
     *
     * While the translation is still incomplete the two sides are matched in order from the start:
     * the blocks that exist are the *first* n paragraphs, so spreading them over the whole article
     * would scatter three translated paragraphs across fifty. Only once it is complete does a count
     * mismatch (the model merged or split a paragraph) justify the order-proportional fallback —
     * the same best-effort rule the book aligner falls back to.
     */
    fun align(native: List<String>, target: List<String>, targetComplete: Boolean): List<BilingualBlock> {
        if (native.isEmpty()) return target.map { BilingualBlock(target = it, native = null) }
        if (target.isEmpty()) return native.map { BilingualBlock(target = null, native = it) }

        if (targetComplete && native.size != target.size) {
            val claimed = mutableMapOf<Int, String>()
            target.forEachIndexed { j, block ->
                val row = (j.toLong() * native.size / target.size).toInt().coerceIn(0, native.lastIndex)
                claimed[row] = claimed[row]?.let { "$it\n\n$block" } ?: block
            }
            return native.mapIndexed { i, source -> BilingualBlock(claimed[i], source) }
        }

        return native.mapIndexed { i, source ->
            val translated = when {
                // A model that split paragraphs can outrun the source; the surplus joins the last
                // row rather than being dropped on the floor.
                i == native.lastIndex && target.size > native.size ->
                    target.subList(i, target.size).joinToString("\n\n")
                else -> target.getOrNull(i)
            }
            BilingualBlock(translated, source)
        }
    }

    private val BLANK_LINE = Regex("\\n\\s*\\n")
}

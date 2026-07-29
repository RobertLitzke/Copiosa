package com.copiosa.domain.prompt

/**
 * Parses a book-section translation back into per-paragraph texts keyed by ord, following the
 * `[[P<ord>]]` marker contract in PromptBuilder.bookSectionSystemPrompt.
 *
 * Three grades of output survive:
 *  - a perfect echo (every expected marker exactly once) — [Result.Aligned];
 *  - right paragraph *count* but wrong/renumbered markers — zipped by order, still [Result.Aligned];
 *  - anything else — [Result.Misaligned] with an order-proportional best effort. Every output block
 *    is used once and order is preserved; expected ords left without a block simply stay absent
 *    (their rows keep a null translation and the reader falls back to the original), so paragraph
 *    correspondence stays row-exact even on a bad day.
 */
object BookSectionAligner {

    sealed interface Result {
        val byOrd: Map<Int, String>

        data class Aligned(override val byOrd: Map<Int, String>) : Result
        data class Misaligned(override val byOrd: Map<Int, String>) : Result
    }

    /** Marker anywhere in a line; the body runs to the next marker. */
    private val MARKER = Regex("""\[\[\s*P\s*(\d+)\s*]]""")

    fun parse(raw: String, expectedOrds: List<Int>): Result {
        val matches = MARKER.findAll(raw).toList()
        val ords = matches.map { it.groupValues[1].toInt() }
        val bodies = matches.mapIndexed { i, m ->
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else raw.length
            raw.substring(m.range.last + 1, end).trim()
        }

        // Perfect echo: every expected ord exactly once, every body non-blank.
        if (ords == expectedOrds || (ords.size == expectedOrds.size && ords.toSet() == expectedOrds.toSet())) {
            val byOrd = ords.zip(bodies).toMap()
            if (byOrd.keys == expectedOrds.toSet() && byOrd.values.all { it.isNotBlank() }) {
                return Result.Aligned(byOrd)
            }
        }

        // Wrong numbers but the count matches: trust the order.
        if (bodies.size == expectedOrds.size && bodies.all { it.isNotBlank() }) {
            return Result.Aligned(expectedOrds.zip(bodies).toMap())
        }

        return Result.Misaligned(bestEffort(raw, expectedOrds))
    }

    /**
     * Strips whatever markers exist, splits the rest into blank-line blocks, and maps block `j` of
     * `k` onto expected index `floor(j * n / k)`, concatenating collisions.
     */
    private fun bestEffort(raw: String, expectedOrds: List<Int>): Map<Int, String> {
        val stripped = raw.replace(MARKER, "\n")
        val blocks = stripped.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.isEmpty() || expectedOrds.isEmpty()) return emptyMap()

        val n = expectedOrds.size
        val k = blocks.size
        val out = mutableMapOf<Int, String>()
        blocks.forEachIndexed { j, block ->
            val ord = expectedOrds[(j.toLong() * n / k).toInt().coerceIn(0, n - 1)]
            out[ord] = out[ord]?.let { "$it\n\n$block" } ?: block
        }
        return out
    }
}

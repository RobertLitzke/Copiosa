package com.copiosa.data.epub

/**
 * A GFM pipe table — the form a [BlockKind.TABLE] block is stored in, and the *only* place that
 * form is parsed or written.
 *
 * There is one of these rather than one per consumer because the reader and the exporter must agree
 * on what a stored table means, and this codebase already carries four hand-synced copies of the
 * inline-emphasis tokenizer (see the note on [inlineXhtml]). A fifth duplicated grammar is not a
 * debt worth taking on.
 *
 * Deliberately forgiving, because a stored table is not always one the parser wrote: a table's
 * `translatedText` is LLM output, and a model may reflow the rows, drop the `|---|` separator, or
 * come back with a different column count. Anything that still reads as rows of pipe-separated
 * cells parses; anything that doesn't returns null, and both callers fall back to rendering the
 * text as an ordinary paragraph rather than emitting a malformed table.
 */
data class PipeTable(
    /** The first row. GFM cannot express a headerless table, so there is always one. */
    val header: List<String>,
    val rows: List<List<String>>,
) {
    val width: Int get() = header.size

    /** Every row padded to [width], so a consumer can index cells without bounds checks. */
    fun padded(): List<List<String>> =
        (listOf(header) + rows).map { row -> List(width) { row.getOrElse(it) { "" } } }
}

/** Parses [text] as a pipe table, or returns null when it isn't one. */
fun parsePipeTable(text: String): PipeTable? {
    val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 2) return null
    // Every line has to look like a table line, or this is prose that merely contains a pipe.
    if (lines.any { !it.startsWith("|") }) return null

    val parsed = lines.map { splitCells(it) }.filterNot { isSeparator(it) }
    if (parsed.isEmpty()) return null
    val width = parsed.maxOf { it.size }
    if (width == 0) return null

    return PipeTable(
        header = List(width) { parsed.first().getOrElse(it) { "" } },
        rows = parsed.drop(1).map { row -> List(width) { row.getOrElse(it) { "" } } },
    )
}

/** Renders [table] back to the stored form. Inverse of [parsePipeTable] for anything it produced. */
fun renderPipeTable(table: PipeTable): String = buildString {
    append(line(table.header))
    append('\n').append(line(List(table.width) { "---" }))
    for (row in table.rows) append('\n').append(line(row))
}

/** Escapes a cell's own pipes so the round trip through [splitCells] is lossless. */
fun escapeCell(text: String): String = text.replace("|", "\\|")

private fun line(cells: List<String>) = cells.joinToString(" | ", prefix = "| ", postfix = " |")

/**
 * Splits one table line into cells on unescaped pipes. The leading and trailing pipes delimit
 * rather than separate, so the empty cells they would otherwise imply are dropped.
 */
private fun splitCells(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '\\' && i + 1 < line.length && line[i + 1] == '|' -> { cell.append('|'); i += 2 }
            c == '|' -> { cells.add(cell.toString().trim()); cell.setLength(0); i++ }
            else -> { cell.append(c); i++ }
        }
    }
    cells.add(cell.toString().trim())
    // Drop the empties created by the delimiting pipes at either end.
    if (cells.isNotEmpty() && cells.first().isEmpty()) cells.removeAt(0)
    if (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.lastIndex)
    return cells
}

/** True for a `| --- | :--: |` alignment row, which carries no content. */
private fun isSeparator(cells: List<String>): Boolean =
    cells.isNotEmpty() && cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' || it == ' ' } }

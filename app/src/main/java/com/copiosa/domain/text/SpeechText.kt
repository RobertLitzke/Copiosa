package com.copiosa.domain.text

/**
 * Markdown, as a chat reply or a card field carries it, reduced to what a voice should say.
 *
 * The reader renders `**bold**`, `## headings` and `- bullets`; a TTS engine handed the same string
 * reads the punctuation out, or stumbles on it. Block markers come off the front of each line and
 * inline ones out of the middle, and the line structure is kept — [Sentences] treats a line break as
 * a boundary, so a heading or a list item still lands with a pause after it.
 *
 * Pure, so it is JVM-testable. Book chapters have their own cleaner (`speechTextOf`), because they
 * read structure from the stored block kind rather than from the text.
 */
object SpeechText {

    fun fromMarkdown(markdown: String): String =
        markdown.lineSequence()
            .filterNot { RULE.matches(it) || isTableRule(it) }
            .joinToString("\n") { line ->
                line.replace(BLOCK_PREFIX, "")
                    .replace(FOOTNOTE_MARKER, "")
                    .replace(INLINE_LINK) { it.groupValues[1] }
                    .replace(EMPHASIS, "")
                    // What is left of a table row: its cells, without the bars between them.
                    .replace('|', ' ')
                    .trim()
            }
            .replace(BLANK_LINES, "\n\n")
            .trim()

    /** The `|---|:--:|` line under a table's header, which has no spoken form at all. */
    private fun isTableRule(line: String): Boolean =
        '|' in line && '-' in line && line.all { it in "|:- \t" }

    /** A heading's hashes, a quote's bar, a list item's bullet or number. */
    private val BLOCK_PREFIX = Regex("""^\s*(?:#{1,6}\s+|>\s?|[-*+]\s+|\d{1,3}[.)]\s+)""")

    /** A thematic break: three or more of the same mark on a line of their own. */
    private val RULE = Regex("""^\s*([-*_])(?:\s*\1){2,}\s*$""")
    private val FOOTNOTE_MARKER = Regex("""\[\^[^\]]{1,16}]""")
    private val INLINE_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val EMPHASIS = Regex("""\*+|_+|`+|~~""")
    private val BLANK_LINES = Regex("""\n{3,}""")
}

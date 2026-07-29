package com.copiosa.domain.vocab

/**
 * Splits a crammed flashcard `back`/translation field into a concise translation plus an optional
 * usage note. Some cards (LLM-generated older definitions) stuffed a labelled translation *and* a
 * usage note — often with Markdown — into one field, e.g.
 *
 * ```
 * **Translation:**
 * * **Crucible** (literal)
 * * **Melting pot** (figurative)
 *
 * **Usage Note:**
 * ...
 * ```
 *
 * This pulls the usage note into its own field and strips the redundant "**Translation:**" label so
 * the translation stays clean. It splits at the usage-note *section header* (a bold/italic label that
 * names usage/note/nuance/nota/uso and ends in a colon), never at an ordinary translation bullet.
 *
 * Deliberately conservative: with no recognizable note header it leaves the text as the translation
 * (only stripping a leading label), so a plain translation is never mangled.
 */
object BackNoteSplitter {

    private val NOTE_KEYWORDS = listOf("usage", "note", "notes", "nuance", "nota", "notas", "uso")

    /** A leading "**Translation:**" / "**Traducción:**" label to drop from the translation part. */
    private val TRANS_LABEL =
        Regex("""^[*_]{0,2}\s*(translation|traducci[oó]n)\b\s*:?\s*[*_]{0,2}\s*:?\s*""", RegexOption.IGNORE_CASE)

    /** Returns `(translation, usageNote)`; `usageNote` is null when there's no distinct note. */
    fun split(back: String): Pair<String, String?> {
        val text = back.trim()
        if (text.isEmpty()) return back to null
        val lines = text.split("\n")

        val headerIdx = lines.indices.firstOrNull { it > 0 && isNoteHeader(lines[it]) }
        if (headerIdx != null) {
            val translation = stripLabel(lines.subList(0, headerIdx).joinToString("\n"))
            val note = lines.subList(headerIdx, lines.size).joinToString("\n").trim()
            if (translation.isNotEmpty() && note.isNotEmpty()) return translation to note
        }
        // No note section (or nothing left after the label): keep it all as the translation, minus a
        // lone leading label.
        return stripLabel(text) to null
    }

    /** True for a usage-note section header: a (bold/italic) label naming a note keyword, ending in ':'. */
    private fun isNoteHeader(line: String): Boolean {
        val t = line.trim()
        if (':' !in t) return false
        val stripped = t.trimStart('*', '_', ' ').lowercase()
        return NOTE_KEYWORDS.any { stripped.startsWith(it) }
    }

    /** Drops a leading translation label and any blank lines it left behind. */
    private fun stripLabel(text: String): String = text.replaceFirst(TRANS_LABEL, "").trim()
}

package com.copiosa.domain.model

/**
 * Renders Anki cloze-deletion markup for display. Anki writes deletions as `{{c1::answer}}` or
 * `{{c1::answer::hint}}`; one imported Anki card targets a single cloze number (its template ordinal
 * + 1), while app-created clozes have no ordinal and blank every deletion.
 *
 * Pure (string in/out) so it can be unit-tested and reused across review, browse, and detail.
 */
object Cloze {
    /** Placeholder shown where the active answer is hidden. */
    const val BLANK = "_____"

    private val CLOZE_RE = Regex("""\{\{c(\d+)::(.*?)(?:::(.*?))?\}\}""")

    /** Whether [text] contains any cloze markup. */
    fun hasCloze(text: String?): Boolean = text != null && CLOZE_RE.containsMatchIn(text)

    /** Picks whichever side carries cloze markup (the import may swap front/back), else null. */
    fun source(front: String?, back: String?): String? = when {
        hasCloze(front) -> front
        hasCloze(back) -> back
        else -> null
    }

    /**
     * The cloze text with the **active** deletion blanked — its `::hint` shown as `[hint]` when present,
     * otherwise [BLANK] — and every other deletion filled in with its answer. A null [activeOrd] blanks
     * every deletion (app-created clozes that don't target one number).
     */
    fun question(text: String, activeOrd: Int?): String =
        CLOZE_RE.replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull()
            val answer = m.groupValues[2]
            val hint = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
            if (activeOrd == null || n == activeOrd) hint?.let { "[$it]" } ?: BLANK else answer
        }.trim()

    /** The cloze text fully filled in — every deletion replaced by its answer. */
    fun answer(text: String): String =
        CLOZE_RE.replace(text) { it.groupValues[2] }.trim()

    /** The answer word(s) for the active deletion (or every deletion when [activeOrd] is null). */
    fun activeAnswers(text: String, activeOrd: Int?): List<String> =
        CLOZE_RE.findAll(text)
            .filter { activeOrd == null || it.groupValues[1].toIntOrNull() == activeOrd }
            .map { it.groupValues[2].trim() }
            .filter { it.isNotEmpty() }
            .toList()
}

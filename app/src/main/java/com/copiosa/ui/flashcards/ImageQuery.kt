package com.copiosa.ui.flashcards

/**
 * Turns a flashcard's native-language gloss into a clean image-search keyword.
 *
 * Glosses (especially on imported Anki cards) are often multi-sense and annotated, e.g.
 * `"to call; to phone, (reflexive) to be named"`. Feeding that straight to a keyless image search
 * returns keyword soup, which is why results so often look unrelated. This keeps only the first sense
 * and strips the noise so the query is a single concept ("call"); the search box stays editable, so
 * the user can refine it. Returns "" when nothing usable remains (callers fall back to the raw gloss).
 */
fun imageSearchQuery(gloss: String): String {
    var s = gloss
    // Drop HTML tags and bracketed/parenthetical asides ("(reflexive)", "[fig.]", "<b>…</b>").
    s = s.replace(Regex("<[^>]*>"), " ")
    s = s.replace(Regex("\\([^)]*\\)"), " ")
    s = s.replace(Regex("\\[[^\\]]*]"), " ")
    // Keep only the first sense — senses are separated by , ; / |.
    s = s.split(',', ';', '/', '|').firstOrNull { it.isNotBlank() }?.trim() ?: ""
    // Drop a leading English infinitive marker or article so "to call"/"the dog" → "call"/"dog".
    s = s.replace(Regex("^(?:to|a|an|the)\\s+", RegexOption.IGNORE_CASE), "")
    return s.trim()
}

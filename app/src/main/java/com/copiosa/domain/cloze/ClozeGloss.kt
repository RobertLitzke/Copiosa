package com.copiosa.domain.cloze

/** Meanings shown beside a word after the answer. Past three it stops being a gloss and starts
 *  being a dictionary entry. */
private const val MAX_SENSES = 3

/** Rough width the joined gloss has to live within to sit under a word without wrapping twice. */
private const val BUDGET = 40

private val PARENTHETICAL = Regex("""\s*\([^)]*\)""")
private val WHITESPACE = Regex("""\s+""")

/**
 * A dictionary's definitions for one word, reduced to the few words that fit beneath it in the
 * feedback panel — "up, above" against *arriba*, "wink, indicator" against *guiño*.
 *
 * The reduction is aggressive on purpose, because dictionary prose is written for a page. A single
 * definition arrives as its own comma-list with regional asides attached — *guiño* really does come
 * back as "wink, indicator (UK, Australia, New Zealand), turn signal (US), blinker (informal, US)"
 * — and printed whole it buries the comparison it exists to serve. So the asides go, the list is
 * flattened to its parts, and parts are taken only while they fit.
 *
 * At least one meaning always survives, even an over-long one: a truncated gloss still says more
 * than no gloss, and the caller reads null as "the dictionary had nothing".
 *
 * Deduplication is not cosmetic. Entries routinely repeat a sense across a lemma and its inflected
 * forms, and a gloss reading "up, up" looks like a bug in the app rather than a quirk of the source.
 */
fun formatGloss(definitions: List<String>): String? {
    val parts = definitions
        .asSequence()
        .map { it.replace(PARENTHETICAL, "") }
        .flatMap { it.split(',').asSequence() }
        // One pass over both, or "above ;" trims its semicolon and keeps the space in front of it.
        .map { it.replace(WHITESPACE, " ").trim { c -> c.isWhitespace() || c == ';' } }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .take(MAX_SENSES)
        .toList()
    if (parts.isEmpty()) return null

    val kept = StringBuilder(parts.first())
    for (part in parts.drop(1)) {
        if (kept.length + 2 + part.length > BUDGET) break
        kept.append(", ").append(part)
    }
    return kept.toString()
}

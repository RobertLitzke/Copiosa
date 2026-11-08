package com.copiosa.domain.highlight

import com.copiosa.data.local.FlashcardEntity

/**
 * The card's target-language side — what in-text highlighting matches against. Usually [front], but a
 * reverse card imported from Anki ([FlashcardEntity.frontIsTarget] false) carries the native gloss on
 * its front and the target headword on its back. Highlighting the front unconditionally would tint
 * native words in the reading text, so every highlight-path caller resolves the side through here.
 */
val FlashcardEntity.targetTerm: String get() = if (frontIsTarget) front else back

/**
 * A saved word eligible for in-text highlighting. [forms] holds every surface form that should light
 * up — the headword plus its plural/conjugated inflections (see [HighlightTargetProvider]) — each
 * already [normalizeWord]ed so the renderer can match tokens by plain set membership. [gender] is the
 * stored noun-gender code (M/F/MF/MPL/FPL) when this is a gendered noun, enabling article highlighting;
 * null otherwise. [lang] is the card's target language, which selects the article table.
 */
data class HighlightTarget(
    val forms: Set<String>,
    val gender: String?,
    val lang: String,
)

/**
 * Lowercase and strip leading/trailing non-alphanumerics so "Gato," matches a saved "gato". This is
 * the single source of truth for word normalization shared by the highlight index and the renderer;
 * [com.copiosa.ui.components.WordColorizer.normalize] delegates here.
 */
fun normalizeWord(word: String): String = word.lowercase().trim { !it.isLetterOrDigit() }

private val TRAILING_HINT = Regex("\\s*\\([^)]*\\)\\s*$")

/**
 * The term without a trailing disambiguating hint in brackets, and without surrounding whitespace:
 * "callejero (papel)" is the word "callejero" plus a hint for the learner, and only the word ever
 * appears in running text. A term that is nothing but a hint is returned as it stands (trimmed) so a
 * card never ends up with no headword at all.
 */
fun stripTrailingHint(term: String): String {
    val stripped = term.replace(TRAILING_HINT, "").trim()
    return stripped.ifEmpty { term.trim() }
}

/** [normalizeWord] of a card term after its bracketed hint is dropped — the key a card highlights by. */
fun normalizeHeadword(term: String): String = normalizeWord(stripTrailingHint(term))

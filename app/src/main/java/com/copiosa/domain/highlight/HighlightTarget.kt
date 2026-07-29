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

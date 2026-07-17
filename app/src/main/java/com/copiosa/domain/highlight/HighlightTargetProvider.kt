package com.copiosa.domain.highlight

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.translate.LocalDictionary
import com.copiosa.domain.srs.ReviewWindows
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the learner's saved flashcards into the set of [HighlightTarget]s the renderer tints, so a
 * saved "gato" also lights up "gatos" and "vivir" lights up "vivió".
 *
 * The expensive part — expanding a headword into its plural/conjugated forms via the on-device
 * dictionary pack — is **not** done on the render path. Instead each card carries its forms in
 * [FlashcardEntity.inflectedForms] (filled on save/edit and by a one-time backfill in
 * FlashcardRepository), and [build]/[formsByCard] simply read that cached column. [computeForms] /
 * [computeFormsFor] are the only dictionary callers and are used solely to populate the cache.
 *
 * Eligibility: a card highlights only while it is due for review soon — on or before
 * `now + `[ReviewWindows.HIGHLIGHT_DUE_MS] (which includes overdue cards) — so reading material across
 * every reader (Texts, chat, routines) spotlights the words worth restudying now rather than every
 * saved word.
 */
@Singleton
class HighlightTargetProvider @Inject constructor(
    private val dictionary: LocalDictionary,
) {
    /**
     * The highlight targets (with gender, for article highlighting) for cards due within the window.
     * Pure and fast: reads each card's cached [FlashcardEntity.inflectedForms], falling back to the
     * bare headword for cards not yet backfilled.
     */
    fun build(cards: List<FlashcardEntity>, now: Long): List<HighlightTarget> =
        cards.filter { it.dueAt <= now + ReviewWindows.HIGHLIGHT_DUE_MS }
            .mapNotNull { card ->
                storedForms(card).takeIf { it.isNotEmpty() }
                    ?.let { HighlightTarget(it, card.gender, card.targetLanguage) }
            }

    /**
     * Each card's id mapped to all surface forms that count as a sighting of it — used by passive
     * review so seeing "gatos" reviews a "gato" card. Pure: reads the cached forms (headword-only until
     * the card is backfilled), without the eligibility filter (callers decide which cards apply).
     */
    fun formsByCard(cards: List<FlashcardEntity>): Map<Long, Set<String>> =
        cards.associate { it.id to storedForms(it) }

    /**
     * The highlight target for a single card (its cached forms + gender), ignoring the due-window
     * filter — used to light up a just-saved word immediately. Null when the card has no usable form.
     */
    fun targetFor(card: FlashcardEntity): HighlightTarget? =
        storedForms(card).takeIf { it.isNotEmpty() }
            ?.let { HighlightTarget(it, card.gender, card.targetLanguage) }

    /** A card's cached surface forms (its headword plus any stored inflections), all normalized. */
    private fun storedForms(card: FlashcardEntity): Set<String> = decode(card.inflectedForms, card.front)

    /**
     * Expands [cards] into their normalized {headword + inflected forms} via the dictionary pack — the
     * expensive lookup, run only to populate the cache (on save/edit and the one-time backfill), never
     * on the render path. Falls back to the bare headword when a language's pack isn't downloaded.
     */
    suspend fun computeForms(cards: List<FlashcardEntity>): Map<Long, Set<String>> {
        val out = HashMap<Long, Set<String>>(cards.size)
        // Expand per language so each card's inflections come from its own pack.
        for ((lang, group) in cards.groupBy { it.targetLanguage }) {
            // Only single-word headwords can be inflection-expanded; multi-word phrases stay exact.
            val expandable = group.filterNot { it.front.trim().any(Char::isWhitespace) }.map { it.front }
            val expansions = if (expandable.isNotEmpty() && dictionary.isDownloaded(lang)) {
                dictionary.inflectedFormsFor(expandable, lang)
            } else {
                emptyMap()
            }
            for (card in group) {
                out[card.id] = normalizedExpansion(card.front, expansions[card.front])
            }
        }
        return out
    }

    /** Surface forms for a single new/edited headword, to persist into [FlashcardEntity.inflectedForms]. */
    suspend fun computeFormsFor(front: String, lang: String): Set<String> {
        val key = normalizeWord(front)
        if (key.isEmpty()) return emptySet()
        if (front.trim().any(Char::isWhitespace) || !dictionary.isDownloaded(lang)) return setOf(key)
        return normalizedExpansion(front, dictionary.inflectedFormsFor(listOf(front), lang)[front])
    }

    /** Normalizes a dictionary expansion (raw forms for [front]) into the headword + its inflections. */
    private fun normalizedExpansion(front: String, raw: Set<String>?): Set<String> {
        val key = normalizeWord(front)
        if (key.isEmpty()) return emptySet()
        return raw?.mapNotNullTo(mutableSetOf()) { normalizeWord(it).takeIf(String::isNotEmpty) }
            ?.apply { add(key) }
            ?: setOf(key)
    }

    companion object {
        /** Separator for the ";"-joined [FlashcardEntity.inflectedForms] column. */
        const val FORM_SEPARATOR = ";"

        /** Joins computed forms for storage; empty string when there's nothing to store. */
        fun encode(forms: Set<String>): String =
            forms.filter { it.isNotEmpty() }.joinToString(FORM_SEPARATOR)

        /**
         * Parses a stored [FlashcardEntity.inflectedForms] string back into the normalized surface-form
         * set, always including the card's own [headword] (so an un-backfilled card still matches itself).
         */
        fun decode(stored: String?, headword: String): Set<String> {
            val key = normalizeWord(headword)
            val out = if (key.isEmpty()) mutableSetOf<String>() else mutableSetOf(key)
            stored?.split(FORM_SEPARATOR)?.forEach { f ->
                normalizeWord(f).takeIf(String::isNotEmpty)?.let { out.add(it) }
            }
            return out
        }
    }
}

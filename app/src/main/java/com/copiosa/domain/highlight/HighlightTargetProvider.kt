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
 * Every form is derived from the card's [targetTerm], never blindly its front: a reverse Anki import
 * holds the native gloss on its front, and matching that would tint native words in the reading text.
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
    private fun storedForms(card: FlashcardEntity): Set<String> = decode(card.inflectedForms, card.targetTerm)

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
            val expandable = group.filterNot { it.targetTerm.trim().any(Char::isWhitespace) }
                .associate { it.targetTerm to posFilter(it.partOfSpeech, it.gender) }
            val expansions = if (expandable.isNotEmpty() && dictionary.isDownloaded(lang)) {
                dictionary.inflectedFormsFor(expandable, lang)
            } else {
                emptyMap()
            }
            for (card in group) {
                val term = card.targetTerm
                out[card.id] = normalizedExpansion(term, expansions[term])
            }
        }
        return out
    }

    /**
     * Surface forms for a single new/edited headword, to persist into [FlashcardEntity.inflectedForms].
     * [term] must be the card's target-language side (see [targetTerm]), not blindly its front.
     */
    suspend fun computeFormsFor(
        term: String,
        lang: String,
        partOfSpeech: String? = null,
        gender: String? = null,
    ): Set<String> {
        val key = normalizeWord(term)
        if (key.isEmpty()) return emptySet()
        if (term.trim().any(Char::isWhitespace) || !dictionary.isDownloaded(lang)) return setOf(key)
        val expansions = dictionary.inflectedFormsFor(mapOf(term to posFilter(partOfSpeech, gender)), lang)
        return normalizedExpansion(term, expansions[term])
    }

    /**
     * Which `lemmas.pos` values a card may expand through, or null for "no idea, allow any".
     *
     * This is what keeps a noun off a verb's paradigm. Spanish "son" is both a noun (the music genre)
     * and third-person plural of "ser"; unfiltered, the noun card inherited soy/eres/es/…, so tapping
     * "es" in a text resolved to "el son". A stored gender is itself a noun assertion, so it stands in
     * when the part of speech was never filled (cards default to "unknown").
     */
    private fun posFilter(partOfSpeech: String?, gender: String?): Set<String>? =
        when (partOfSpeech?.trim()?.lowercase()) {
            // "name" is the pack's proper-noun tag; a saved noun may legitimately be either.
            "noun" -> setOf("noun", "name")
            "verb" -> setOf("verb")
            "adjective", "adj" -> setOf("adj")
            "adverb", "adv" -> setOf("adv")
            else -> if (gender != null) setOf("noun", "name") else null
        }

    /** Normalizes a dictionary expansion (raw forms for [term]) into the headword + its inflections. */
    private fun normalizedExpansion(term: String, raw: Set<String>?): Set<String> {
        val key = normalizeWord(term)
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

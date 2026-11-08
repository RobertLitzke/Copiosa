package com.copiosa.ui.components

import com.copiosa.data.translate.DictionaryEntry
import com.copiosa.domain.highlight.normalizeWord

/**
 * Which headword a save from the lookup card defaults to, and what goes on its back.
 *
 * One rule runs through both: **the back is the meaning of the headword on the front.** A selection
 * is usually an inflected form — "madrigueras", "catapultó" — and the card offers to save its base
 * form instead. The meaning then has to be the base form's too, or the note reads *madriguera* /
 * "burrows": a singular asked for with a plural, which is wrong in both review directions.
 *
 * Pure (entries in, strings out) so the guesses can be pinned by tests against the shapes the
 * dictionary pack really returns.
 */
internal object SaveHeadword {

    /**
     * The base form to offer for [word] — null to save it as written — and the part of speech that
     * goes with the choice.
     *
     * A selection that is a headword in its own right is saved as written. The pack resolves a token
     * to everything it could be a form of, so *entorno* (surroundings) also comes back as the
     * first-person present of *entornar* and *gafas* (glasses) as a form of *gafar*; the word the
     * reader actually pointed at is the better default, and the card's picker still lists the rest.
     * "In its own right" means it has English meanings: the pack also carries a row for every
     * inflected form ("madrigueras: plural of madriguera"), and those have none.
     *
     * Otherwise the selection is an inflection, and among the headwords it could belong to one the
     * pack can gloss beats one it cannot — participles and reflexives have rows with no English
     * (*emboscado*, *embarrarse*), and choosing those leaves the back to be guessed from the form in
     * the sentence. Within that, see [readingRank]: a conjugation is the commonest thing to need
     * un-inflecting, but not when the verb reading is a second-person form or a participle.
     */
    fun suggest(word: String, entries: List<DictionaryEntry>): Pair<String?, String?> {
        val written = word.trim()
        entries.firstOrNull { it.inflectionOf == null && it.isGlossed && it.lemma.equals(written, ignoreCase = true) }
            ?.let { return null to it.pos }
        val inflections = entries.filter { it.inflectionOf != null && !it.lemma.equals(written, ignoreCase = true) }
        val candidates = inflections.filter { it.isGlossed }.ifEmpty { inflections }
        val pick = candidates.minByOrNull(::readingRank)
        return pick?.lemma to (pick?.pos ?: entries.firstOrNull()?.pos)
    }

    /**
     * Lower is the likelier reading of a form met in a text. A verb reading comes first, except the
     * two kinds that collide with noun plurals: a second-person form (*matices* is "you qualify" as
     * well as the plural of *matiz*; *luces*, *cruces*, *placas*, *llamas* are the same) and a
     * participle (*comidas*, *salidas*, *emboscadas*). Second person is rare in narrative and a
     * plural participle mostly works as an adjective, so the noun is the better default there. The
     * pack's collisions are nearly all of these two kinds, because a Spanish plural ends in -s and
     * so do the tú forms. The picker still lists the verb.
     */
    private fun readingRank(entry: DictionaryEntry): Int {
        val isVerb = entry.pos.equals("verb", ignoreCase = true)
        val note = entry.inflectionOf.orEmpty()
        return when {
            !isVerb -> 1
            "second-person" in note || "participle" in note -> 2
            else -> 0
        }
    }

    /**
     * Whether a saved card found through its cached forms is the word the reader selected. Those
     * forms are generated from the card's term alone, so a verb card claims every noun spelled like
     * one of its forms: *tratar* claims *tratados* (treaties), *matizar* claims *matices*, a *vieron*
     * card claims *vistas* (views). The dictionary's default for the selection — [suggestion], or the
     * selection as written when that is null — says which headword was meant, and overrules the
     * match when it is another word the pack can gloss. Without English for it the pack has no
     * better answer than the card, so the match stands.
     */
    fun formMatchHolds(savedTerm: String, word: String, suggestion: String?, entries: List<DictionaryEntry>): Boolean {
        val meant = normalizeWord(suggestion ?: word)
        if (meant == normalizeWord(savedTerm)) return true
        return entries.none { it.isGlossed && normalizeWord(it.lemma) == meant }
    }

    /**
     * [headword]'s English meanings as a card back, or null when the pack has none for it — the
     * entry of that part of speech when there is one, since *lecho* the noun and *lecho* the verb
     * form do not mean the same thing.
     *
     * Capped: a back is a prompt to recall, and four comma-separated meanings is a paragraph to
     * memorise. The rest stay in the dictionary, where they can be looked up again.
     */
    fun englishBack(entries: List<DictionaryEntry>, headword: String, pos: String?): String? {
        val named = entries.filter { it.lemma.equals(headword.trim(), ignoreCase = true) }
        val entry = named.firstOrNull { it.pos == pos && it.isGlossed } ?: named.firstOrNull { it.isGlossed }
        return entry?.englishDefinitions?.take(MAX_SAVED_MEANINGS)?.joinToString("; ")?.ifBlank { null }
    }

    private val DictionaryEntry.isGlossed: Boolean get() = englishDefinitions.isNotEmpty()

    /** English meanings kept on a saved card's back; the rest are a lookup, not a thing to recall. */
    private const val MAX_SAVED_MEANINGS = 3
}

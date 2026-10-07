package com.copiosa.domain.vocab.freq

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.domain.srs.ReviewWindows
import javax.inject.Inject
import javax.inject.Singleton

/** How much of one frequency band the learner holds. [inDeck] and [mature] are both out of [total]. */
data class BucketCoverage(
    val bucket: FrequencyBucket,
    val total: Int,
    /** Words with a card of any kind, studied or not. */
    val inDeck: Int,
    /** Words whose card has reached [ReviewWindows.MATURE_THRESHOLD_DAYS] — actually known. */
    val mature: Int,
) {
    val inDeckPercent: Int get() = percent(inDeck)
    val maturePercent: Int get() = percent(mature)

    private fun percent(n: Int): Int = if (total == 0) 0 else (n * 100) / total
}

/**
 * Matches the learner's cards against a [FrequencyList] — the join behind the coverage figures, the
 * rank chips, the frequency-weighted review order, and the "add frequent words" count.
 *
 * Deliberately does no lemmatising of its own. Every card already carries its expanded surface forms
 * in [FlashcardEntity.inflectedForms] (filled on save and by the backfill in FlashcardRepository,
 * from the same dictionary pack the frequency list was generated against), and the list carries the
 * corpus forms of each headword, so matching is a set intersection over data that already exists.
 * That keeps this off the dictionary and cheap enough to run on every list render.
 */
@Singleton
class VocabCoverage @Inject constructor() {

    /**
     * The frequency rank of the word [card] teaches, or null when it isn't in [list].
     *
     * Matching goes through [targetTerm], never blindly the front: a reverse Anki card holds its
     * native gloss on the front, and matching that would rank the card by its English word.
     */
    fun rankOf(card: FlashcardEntity, list: FrequencyList): Int? {
        // Only single words are ranked; the list has no multi-word entries to match a phrase against.
        if (!card.cardKind.isLexicalVocab) return null
        val term = card.targetTerm
        if (term.isBlank() || term.trim().any(Char::isWhitespace)) return null
        rankOfForm(card, list, term)?.let { return it }
        // A card headworded as an inflection ("gatos") still resolves through its cached expansion.
        return HighlightTargetProvider.decode(card.inflectedForms, term)
            .firstNotNullOfOrNull { rankOfForm(card, list, it) }
    }

    /**
     * [form]'s rank, unless the entry claiming it is about a different part of speech than the card.
     *
     * A list entry carries every surface form the corpus spelled its headword with, and the
     * generator rolls a homograph's counts onto the reading the corpus is mostly about. So *dado*
     * sits in the paradigm of *dar* and *traje* in that of *traer*, with no noun entry of their own
     * — and without this check a card for the die or the suit is ranked as a top-100 verb, shows a
     * "top 100" chip, and is drilled as one of the commonest words in the language.
     *
     * The card's own headword being a list headword is never refused: whatever part of speech we
     * think the card is, it is demonstrably that word. Only an *inflection* match is adjudicated,
     * and only when the card actually states a part of speech that disagrees.
     */
    private fun rankOfForm(card: FlashcardEntity, list: FrequencyList, form: String): Int? {
        val match = list.matchOfForm(form) ?: return null
        if (match.isHeadword) return match.rank
        return match.rank.takeIf { FrequencyPos.compatible(match.pos, posOf(card)) }
    }

    /**
     * What part of speech the card is, as far as anything on it says.
     *
     * A recorded gender counts as "noun" even when the part-of-speech field was never filled in:
     * only a noun carries grammatical gender, and on an imported deck the gender is often the only
     * thing that was captured. It is what lets an un-annotated `el traje` be told apart from the
     * preterite of *traer* without waiting for the enrichment pass to reach it.
     */
    private fun posOf(card: FlashcardEntity): String? =
        card.partOfSpeech?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "unknown" }
            ?: "noun".takeIf { !card.gender.isNullOrBlank() }

    /** Rank per card id, for cards that are in [list]; absent means "not a listed word". */
    fun ranksByCard(cards: List<FlashcardEntity>, list: FrequencyList): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (card in cards) rankOf(card, list)?.let { out[card.id] = it }
        return out
    }

    /**
     * Coverage per band. A word counts as [BucketCoverage.inDeck] once any card teaches it — frozen
     * cards included, since the learner has met the word and deliberately set it aside — and as
     * [BucketCoverage.mature] once one of its cards has a mature interval.
     *
     * The two figures are reported separately on purpose: seeding a thousand words moves "in deck"
     * immediately and should not be allowed to read as "you now know 90% of Spanish".
     */
    fun coverage(cards: List<FlashcardEntity>, list: FrequencyList): List<BucketCoverage> {
        val inDeck = HashSet<Int>()
        val mature = HashSet<Int>()
        for (card in cards) {
            val rank = rankOf(card, list) ?: continue
            inDeck += rank
            if (card.intervalDays >= ReviewWindows.MATURE_THRESHOLD_DAYS) mature += rank
        }
        return FrequencyBucket.entries.map { bucket ->
            val total = minOf(bucket.maxRank, list.size)
            BucketCoverage(
                bucket = bucket,
                total = total,
                inDeck = inDeck.count { it <= total },
                mature = mature.count { it <= total },
            )
        }
    }

    /**
     * The words ranked at or above [maxRank] that no card teaches yet, most frequent first — the
     * seeding backlog. Frozen cards count as owned, so a word the learner has deliberately set aside
     * is never silently re-added.
     */
    fun missing(cards: List<FlashcardEntity>, list: FrequencyList, maxRank: Int): List<FrequencyEntry> {
        val owned = HashSet<Int>()
        for (card in cards) rankOf(card, list)?.let { owned += it }
        return list.through(maxRank).filter { it.rank !in owned }
    }
}

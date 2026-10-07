package com.copiosa.domain.cloze

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.model.Cloze

/** One bank row the conversion intends to create, described before anything is written. */
data class PlannedCloze(
    val card: FlashcardEntity,
    /** The cloze text for this row, with exactly one deletion, renumbered to `c1`. */
    val text: String,
    val answer: String,
    val lemma: String,
    val cardId: Long?,
    val translation: String?,
    /**
     * Whether this row inherits the flashcard's schedule and review history. Exactly one row per
     * converted card does; a second deletion starts fresh rather than duplicating the history.
     */
    val carriesHistory: Boolean,
)

/**
 * What a conversion run would do, so the learner sees counts before anything is deleted.
 *
 * The skip counts are not decoration — they are the difference between "4 cards vanished" and "4
 * cards keep their media and were left alone".
 */
data class ClozeConversionPlan(
    val rows: List<PlannedCloze>,
    /** Cards that will be deleted once their rows are written. */
    val cards: List<FlashcardEntity>,
    val skippedMedia: Int,
    val skippedTooManyDeletions: Int,
    val skippedUnparseable: Int,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
    val linked: Int get() = rows.count { it.cardId != null }
}

/**
 * Decides what converting the learner's hand-made cloze flashcards into the bank would produce.
 *
 * Pure — cards and a form index in, a plan out — so every edge case below is testable without a
 * database, which matters more here than anywhere else in the feature: this is the one operation
 * that deletes the learner's cards.
 */
object LegacyClozePlan {

    /**
     * Deletions per card the conversion will expand. A hand-made sentence with two or three gaps is
     * a real card; one with nine is a paragraph exercise that would become nine near-identical
     * items, so it is left alone and counted.
     */
    const val MAX_DELETIONS = 4

    /**
     * [cards] must already be filtered to markup-bearing, non-generated, media-free cards (see the
     * DAO query); [skippedMedia] is passed in because that filter happens in SQL.
     *
     * [formToCard] links a blanked word to the vocab card that teaches it, using the deck's cached
     * inflected forms — no dictionary needed.
     */
    fun plan(
        cards: List<FlashcardEntity>,
        formToCard: Map<String, Long>,
        skippedMedia: Int = 0,
    ): ClozeConversionPlan {
        val rows = ArrayList<PlannedCloze>()
        val converted = ArrayList<FlashcardEntity>()
        var tooMany = 0
        var unparseable = 0

        for (card in cards) {
            val source = Cloze.source(card.front, card.back)
            if (source == null) {
                unparseable++
                continue
            }
            val ords = Cloze.ords(source)
            if (ords.isEmpty()) {
                unparseable++
                continue
            }

            // An imported Anki *card* is about exactly one deletion — its template ordinal plus one,
            // which is what the renderer has always used. Expanding it would invent cards Anki never
            // had. A hand-made card carries no ordinal, so every gap becomes its own sentence.
            val targets = card.ankiOrd?.plus(1)?.takeIf { it in ords }?.let { listOf(it) } ?: ords
            if (targets.size > MAX_DELETIONS) {
                tooMany++
                continue
            }

            val built = targets.mapIndexedNotNull { index, ord ->
                val text = Cloze.singleDeletion(source, ord) ?: return@mapIndexedNotNull null
                val answer = Cloze.activeAnswers(text, activeOrd = 1).firstOrNull() ?: return@mapIndexedNotNull null
                val normalized = normalizeWord(answer)
                if (normalized.isEmpty()) return@mapIndexedNotNull null
                val linkedCard = formToCard[normalized]
                PlannedCloze(
                    card = card,
                    text = text,
                    answer = answer,
                    // With no vocab card to name the headword, the blanked form is the best key there
                    // is — it still caps repeats of the same word.
                    lemma = normalized,
                    cardId = linkedCard,
                    translation = extraSideOf(card, source, answer),
                    // Only the first row inherits the card's history; copying it onto every split
                    // would inflate the log with reviews that never happened.
                    carriesHistory = index == 0,
                )
            }

            if (built.isEmpty()) {
                unparseable++
                continue
            }
            rows += built
            converted += card
        }

        return ClozeConversionPlan(
            rows = rows,
            cards = converted,
            skippedMedia = skippedMedia,
            skippedTooManyDeletions = tooMany,
            skippedUnparseable = unparseable,
        )
    }

    /**
     * The card's non-markup side, used as the sentence's translation — but only when it plausibly is
     * one.
     *
     * On an Anki cloze note that side is the "Extra"/hint field as often as it is a translation, and
     * a hint containing the answer would simply give the item away. Showing nothing is better than
     * showing something wrong, so anything doubtful returns null.
     */
    private fun extraSideOf(card: FlashcardEntity, source: String, answer: String): String? {
        val extra = (if (source == card.front) card.back else card.front).trim()
        if (extra.isBlank() || Cloze.hasCloze(extra)) return null
        if (extra.contains(answer, ignoreCase = true)) return null
        return extra
    }
}

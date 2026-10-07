package com.copiosa.domain.cloze

import com.copiosa.domain.vocab.freq.FrequencyList
import com.copiosa.domain.vocab.freq.FunctionWords

/**
 * Everything the miners and the gate need about the learner, assembled **once per mining run**.
 *
 * That is the whole point of this type. The gate asks "does the learner know this word" of every
 * token of every candidate sentence, and a book chapter is thousands of tokens; done per candidate
 * it would be a database round trip each time. Built once, each question is a hash lookup.
 *
 * [formToCard] maps every normalized surface form the learner has a card for — *gatos* as well as
 * *gato* — to that card, read from the cached `inflectedForms` column rather than the dictionary
 * pack, which may not even be downloaded.
 */
class ClozeMineContext(
    val lang: String,
    val formToCard: Map<String, Long>,
    val list: FrequencyList?,
    /** Sentences already banked per lemma, including suspended ones — the per-lemma cap. */
    val banked: Map<String, Int>,
    /** Each card's stated part of speech, for the gate's content-word rule when the list is silent. */
    val posByCard: Map<Long, String?>,
    /** The language's grammar words, which the gate reads as known. See [FunctionWords]. */
    val functionWords: Set<String> = emptySet(),
    /** How many sentences the bank already holds for this language, against [BANK_MAX_PER_LANGUAGE]. */
    val bankSize: Int,
) {
    /** Every form the learner has a card for; the gate's "can they read this" set. */
    val knownForms: Set<String> get() = formToCard.keys

    /**
     * The same relation the other way round: each card and every form that counts as a sighting of
     * it. Built once and lazily, because only the sources that start from a card need it.
     */
    val formsByCard: Map<Long, Set<String>> by lazy {
        val out = HashMap<Long, MutableSet<String>>()
        for ((form, cardId) in formToCard) out.getOrPut(cardId) { HashSet() } += form
        out
    }

    /** Whether [lemma] has room for another sentence. */
    fun hasRoomFor(lemma: String): Boolean =
        (banked[lemma] ?: 0) < PER_LEMMA_CAP && bankSize < BANK_MAX_PER_LANGUAGE

    companion object {
        /**
         * Sentences per word. More than a few for one word crowds a sitting with near-repeats, and
         * — with the nudge — would let one word's sentences dominate what the deck sees.
         */
        const val PER_LEMMA_CAP = 3

        /** A ceiling so background mining can't grow the database without bound. */
        const val BANK_MAX_PER_LANGUAGE = 1_500
    }
}

package com.copiosa.domain.cloze

import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.vocab.freq.FrequencyList
import com.copiosa.domain.vocab.freq.FrequencyPos
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * Chooses the wrong options for a multiple-choice cloze.
 *
 * The design rule, which every filter below serves:
 *
 * > **A good distractor is grammatically admissible but semantically impossible in this sentence.**
 *
 * Both halves matter, and they pull in opposite directions. If the options disagree grammatically
 * with the sentence — a masculine noun against *una …*, a first-person verb against *ellos* — the
 * grammar gives the answer away and the item is free. If they agree *semantically* as well, the item
 * has two right answers, which is the thing that makes Clozemaster frustrating.
 *
 * The pool is [FrequencyList.formIndex], not the learner's deck and not the dictionary. The deck is
 * small, it is biased (every option being a word they are studying turns the item into "which one do
 * I recognise"), and its `partOfSpeech` is frequently the literal string "unknown". The dictionary is
 * a large optional download, and a core mechanic must not depend on one. The frequency list is
 * compiled in, is part-of-speech tagged, and — uniquely — carries *inflections*, which is what makes
 * the suffix agreement below possible without a morphological analyser.
 */
object ClozeDistractors {

    /** How many scored candidates to sample from, so an item isn't identical every showing. */
    const val SHORTLIST = 8

    /** Longest suffix worth matching on; past three it stops discriminating between inflections. */
    const val MAX_SUFFIX = 3

    /**
     * [count] distractors for [answer], or null when the pool can't field enough — in which case the
     * caller must **drop the item** rather than pad it with words that give the answer away.
     *
     * [sentenceTokens] and [excluded] are normalized. [excluded] is where semantic closeness is
     * refused: the answer card's `altForms` synonyms, and (when a dictionary pack happens to be
     * present) words sharing an English gloss with the answer.
     */
    /**
     * [deckForms] is the learner's own vocabulary as a second pool: every normalized form they have a
     * card for, mapped to that card's stated part of speech (null when it says nothing useful). The
     * list alone made a rare answer easy by elimination — *yermo* beside three top-2,000 words is the
     * only one a learner wouldn't recognise, so the odd one out is the answer. Words they are also
     * studying, in the same shape, are the ones they can actually confuse it with. The list still
     * supplies at least one option whenever it can, so an item never becomes "which of my cards".
     *
     * [deckPhrases] is the deck's multi-word terms, the only pool a multi-word answer can draw on.
     */
    fun pick(
        answer: String,
        answerPos: String?,
        list: FrequencyList?,
        sentenceTokens: Set<String>,
        excluded: Set<String>,
        deckFallback: List<String>,
        random: Random,
        count: Int = 3,
        deckForms: Map<String, String?> = emptyMap(),
        deckPhrases: List<String> = emptyList(),
    ): List<String>? {
        val answerNorm = normalizeWord(answer)
        if (answerNorm.isEmpty()) return null
        if (answerNorm.any(Char::isWhitespace)) return pickPhrases(answerNorm, excluded, deckPhrases, random, count)

        val match = list?.matchOfForm(answer)
        // One vocabulary for the comparison: the list says "adj", a card says "adjective".
        val pos = match?.pos ?: packPosFor(answerPos)
        val answerRank = match?.rank

        fun usable(form: String) =
            form.isNotEmpty() && form != answerNorm && form !in sentenceTokens && form !in excluded &&
                // A phrase can't stand in a one-word blank.
                form.none(Char::isWhitespace)

        val fromList = list?.formIndex?.asSequence()
            ?.filter { (form, m) ->
                usable(form) &&
                    // A different lemma: offering "comí" against "comimos" is a conjugation
                    // drill, not a vocabulary item, and both may fit the sentence.
                    m.rank != answerRank &&
                    (pos == null || m.pos == pos)
            }
            ?.map { (form, m) -> Option(form, m.rank, fromDeck = false) }
            ?.toList()
            .orEmpty()

        val fromDeck = if (list != null) {
            deckForms.asSequence()
                .filter { (form, cardPos) ->
                    usable(form) &&
                        // The list's own words are already in the other pool, with a rank.
                        list.matchOfForm(form) == null &&
                        (pos == null || FrequencyPos.compatible(pos, cardPos))
                }
                .map { (form, _) -> Option(form, rank = null, fromDeck = true) }
                .toList()
        } else {
            // No list for this language: the deck is all there is. Part of speech is not reliable
            // enough there to filter on, so shape alone has to carry the item.
            (deckFallback.asSequence().map(::normalizeWord) + deckForms.keys.asSequence())
                .filter(::usable)
                .distinct()
                .map { Option(it, rank = null, fromDeck = true) }
                .toList()
        }

        // A common answer keeps common company whenever the list can field it. Setting *he* or *ser*
        // against the deck's rarer words reverses the problem the deck pool exists to fix: the
        // answer becomes the only everyday word on offer.
        val common = answerRank != null && answerRank <= COMMON_RANK
        val pool = if (common && fromList.size >= count) fromList else fromList + fromDeck
        if (pool.size < count) return null

        val order = compareByDescending<Option> { sharedSuffix(answerNorm, it.form) }
            .thenBy { abs(it.form.length - answerNorm.length) }
            .thenBy { rankDistance(answerRank, it.rank) }
            .thenBy { it.form }

        // Sample only among candidates that agree with the answer's ending as closely as the pool
        // allows: widening to a weaker suffix score while better matches exist would let an
        // infinitive sit beside three first-person plurals and hand the answer over. Groups are
        // admitted whole, best first, until there are enough to choose from.
        val byAgreement = pool.sortedWith(order).groupBy { sharedSuffix(answerNorm, it.form) }
            .toSortedMap(reverseOrder())
        val eligible = ArrayList<Option>()
        for ((_, group) in byAgreement) {
            if (eligible.size >= count) break
            eligible += group
        }

        // A rare answer leans on the deck, where its real competition is; a common one keeps mostly
        // common company. Whichever pool comes up short, the other makes up the number.
        val wantedFromDeck = if (answerRank == null || answerRank > COMMON_RANK) count - 1 else 1
        val deckShort = eligible.filter { it.fromDeck }.take(SHORTLIST).shuffled(random)
        val listShort = eligible.filter { !it.fromDeck }.take(SHORTLIST).shuffled(random)
        val deckTaken = deckShort.take(minOf(wantedFromDeck, maxOf(0, count - minOf(1, listShort.size))))
        val listTaken = listShort.take(count - deckTaken.size)
        val chosen = (listTaken + deckTaken + deckShort.drop(deckTaken.size)).take(count)
        return chosen.map { it.form }.shuffled(random).takeIf { chosen.size == count }
    }

    /**
     * Options for a multi-word answer: other phrases the learner is studying, the nearest in length
     * first. There is no frequency list of phrases, and a single word beside a three-word blank
     * would be eliminated on sight.
     */
    private fun pickPhrases(
        answerNorm: String,
        excluded: Set<String>,
        deckPhrases: List<String>,
        random: Random,
        count: Int,
    ): List<String>? {
        val words = answerNorm.split(WHITESPACE).size
        val head = answerNorm.substringBefore(' ')
        val pool = deckPhrases.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != answerNorm && it !in excluded && it.any(Char::isWhitespace) }
            .distinct()
            // Shape first: *hacer estragos* beside *a pie*, *a mano* and *a ciegas* is the only verb
            // phrase on offer, and the slot after "volvió a" takes nothing else.
            .sortedWith(
                compareBy<String> { shapeDistance(head, it.substringBefore(' ')) }
                    .thenBy { abs(it.split(WHITESPACE).size - words) }
                    .thenBy { it },
            )
            .toList()
        if (pool.size < count) return null
        // As with single words: sample only among the best-shaped phrases the pool has, widening a
        // whole group at a time, so a worse fit is never drawn while a better one is left over.
        val eligible = ArrayList<String>()
        for ((_, group) in pool.groupBy { shapeDistance(head, it.substringBefore(' ')) }.toSortedMap()) {
            if (eligible.size >= count) break
            eligible += group
        }
        return eligible.take(maxOf(SHORTLIST, count)).shuffled(random).take(count)
    }

    /**
     * How unlike two phrases' opening words are: 0 for the same word, 1 for the same kind of word —
     * both verbs in the infinitive, or neither — and 2 otherwise. A crude stand-in for "would this
     * phrase fit the same slot", which is all a phrase's first word can say without a parser.
     */
    private fun shapeDistance(a: String, b: String): Int = when {
        a == b -> 0
        INFINITIVE.matches(a) == INFINITIVE.matches(b) -> 1
        else -> 2
    }

    /** Spanish and Portuguese infinitives, bare or reflexive; other languages simply never match. */
    private val INFINITIVE = Regex(".{2,}(?:ar|er|ir|ír)(?:se)?")

    /** A card's part of speech in the list's vocabulary, or null when it says nothing to filter on. */
    private fun packPosFor(cardPos: String?): String? = when (cardPos?.trim()?.lowercase()) {
        "noun" -> "noun"
        "verb" -> "verb"
        "adjective", "adj" -> "adj"
        "adverb", "adv" -> "adv"
        else -> null
    }

    private class Option(val form: String, val rank: Int?, val fromDeck: Boolean)

    /** At or under this rank an answer is common enough that common words are fair company. */
    const val COMMON_RANK = 1_000

    private val WHITESPACE = Regex("\\s+")

    /**
     * Length of the shared trailing substring, capped at [MAX_SUFFIX].
     *
     * This is the morphology proxy: *comimos* and *vivimos* share three, *comimos* and *gato* share
     * none. Same ending means the same person/number/gender shape, so agreement with the surrounding
     * article or subject can't be used to eliminate options.
     */
    fun sharedSuffix(a: String, b: String): Int {
        var n = 0
        while (n < MAX_SUFFIX && n < a.length && n < b.length && a[a.length - 1 - n] == b[b.length - 1 - n]) n++
        return n
    }

    /** Log distance, because rank 5 against 50 is a wider perceptual gap than 1500 against 1545. */
    private fun rankDistance(answerRank: Int?, candidateRank: Int?): Double {
        if (answerRank == null || candidateRank == null) return 0.0
        return abs(ln(answerRank.toDouble()) - ln(candidateRank.toDouble()))
    }
}

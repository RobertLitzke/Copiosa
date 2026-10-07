package com.copiosa.domain.cloze

import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.vocab.freq.FrequencyList
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
    fun pick(
        answer: String,
        answerPos: String?,
        list: FrequencyList?,
        sentenceTokens: Set<String>,
        excluded: Set<String>,
        deckFallback: List<String>,
        random: Random,
        count: Int = 3,
    ): List<String>? {
        val answerNorm = normalizeWord(answer)
        if (answerNorm.isEmpty()) return null
        val match = list?.matchOfForm(answer)
        val pos = match?.pos ?: answerPos
        val answerRank = match?.rank

        val pool = if (list != null) {
            list.formIndex.asSequence()
                .filter { (form, m) ->
                    form != answerNorm &&
                        form.isNotEmpty() &&
                        form !in sentenceTokens &&
                        form !in excluded &&
                        // A different lemma: offering "comí" against "comimos" is a conjugation
                        // drill, not a vocabulary item, and both may fit the sentence.
                        m.rank != answerRank &&
                        (pos == null || m.pos == pos)
                }
                .map { (form, m) -> form to m.rank }
                .toList()
        } else {
            // No list for this language: the deck is all there is. Part of speech is not reliable
            // enough there to filter on, so shape alone has to carry the item.
            deckFallback.asSequence()
                .map(::normalizeWord)
                .filter { it.isNotEmpty() && it != answerNorm && it !in sentenceTokens && it !in excluded }
                .distinct()
                .map { it to null as Int? }
                .toList()
        }
        if (pool.size < count) return null

        val ranked = pool.sortedWith(
            compareByDescending<Pair<String, Int?>> { sharedSuffix(answerNorm, it.first) }
                .thenBy { abs(it.first.length - answerNorm.length) }
                .thenBy { rankDistance(answerRank, it.second) }
                .thenBy { it.first },
        )

        // Sample only among candidates that agree with the answer's ending as closely as the pool
        // allows: widening to a weaker suffix score while better matches exist would let an
        // infinitive sit beside three first-person plurals and hand the answer over. Groups are
        // admitted whole, best first, until there are enough to choose from.
        val byAgreement = ranked.groupBy { sharedSuffix(answerNorm, it.first) }
            .toSortedMap(reverseOrder())
        val eligible = ArrayList<Pair<String, Int?>>()
        for ((_, group) in byAgreement) {
            if (eligible.size >= count) break
            eligible += group
        }

        val chosen = eligible.take(SHORTLIST).shuffled(random).take(count)
        return chosen.map { it.first }.takeIf { chosen.size == count }
    }

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

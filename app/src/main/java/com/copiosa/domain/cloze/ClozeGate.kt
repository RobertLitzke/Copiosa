package com.copiosa.domain.cloze

import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.vocab.freq.FrequencyList
import com.copiosa.domain.vocab.freq.FrequencyPos
import com.copiosa.domain.vocab.freq.FunctionWords

/** Why a candidate was refused, so a miner can report what it dropped instead of failing silently. */
enum class ClozeReject {
    TOO_SHORT,
    TOO_LONG,
    MARKUP,
    ANSWER_NOT_FOUND,
    ANSWER_REPEATED,
    ANSWER_SENTENCE_INITIAL,
    ANSWER_NOT_CONTENT_WORD,
    UNANCHORED,
    TOO_LITTLE_CONTEXT,
    TOO_HARD,
}

/**
 * Decides whether one mined sentence is worth putting in the bank.
 *
 * This is what separates the mode from Clozemaster's habit of blanking whatever it likes. Two of the
 * rules do most of the work:
 *
 * - **The blank must be a content word.** Articles, prepositions, pronouns and conjunctions are the
 *   slots where several fillers are usually grammatical, so blanking one produces an item with no
 *   single right answer. Those contrasts (por/para, ser/estar) are taught properly by the course and
 *   contrast-cloze machinery; this mode leaves them alone.
 * - **The rest of the sentence must be comprehensible** — the i+1 rule. An item is only a vocabulary
 *   test if the learner can read everything except the gap. Grammar words count as read: the bundled
 *   frequency lists hold content words only, so without [FunctionWords] every `la`, `de` and `que`
 *   scores as unknown vocabulary and no real sentence can clear the bar.
 *
 * Pure, and cheap: the comprehensibility check is one hash lookup per token against maps the caller
 * builds once per mining run, not once per candidate.
 */
object ClozeGate {

    /** Below this there is no disambiguating context; above it the item becomes a reading test. */
    const val MIN_WORDS = 4
    const val MAX_WORDS = 18

    /** Content words besides the blank, so the sentence actually constrains the answer. */
    const val MIN_OTHER_CONTENT_WORDS = 3

    /** Share of the other tokens the learner must already know for the item to be i+1. */
    const val COMPREHENSIBLE_FRACTION = 0.75

    /** Card-vocabulary parts of speech that can carry a determinable blank. */
    val CONTENT_POS = setOf("noun", "verb", "adjective", "adverb")

    /** Markdown and URL fragments; book paragraphs are markdown, so this is a real hazard. */
    private val JUNK = listOf("{{", "](", "http://", "https://", "|", "<", ">")

    /**
     * Null when [candidate] should be banked, otherwise why it was refused.
     *
     * [knownForms] is every normalized surface form the learner has a card for. [answerPos] is the
     * linked card's stated part of speech, used when the frequency list has nothing to say (or when
     * there is no list for the language at all — the whole gate then degrades to the structural
     * checks, which is still stricter than blanking at random).
     *
     * [functionWords] is the language's grammar words, from [FunctionWords.forTag]. It defaults to
     * empty only so tests can pin the structural rules in isolation; a real mining run must pass it,
     * or the comprehensibility check counts the commonest words in the language as unknown.
     */
    fun reject(
        candidate: ClozeCandidate,
        knownForms: Set<String>,
        list: FrequencyList?,
        answerPos: String? = null,
        functionWords: Set<String> = emptySet(),
    ): ClozeReject? {
        val sentence = candidate.sentence
        if (JUNK.any { it in sentence }) return ClozeReject.MARKUP

        val rawTokens = sentence.split(WHITESPACE).filter { it.isNotBlank() }
        if (rawTokens.size < MIN_WORDS) return ClozeReject.TOO_SHORT
        if (rawTokens.size > MAX_WORDS) return ClozeReject.TOO_LONG

        val answerNorm = normalizeWord(candidate.answer)
        if (answerNorm.isEmpty()) return ClozeReject.ANSWER_NOT_FOUND
        val tokens = rawTokens.map(::normalizeWord)
        val occurrences = tokens.count { it == answerNorm }
        if (occurrences == 0) return ClozeReject.ANSWER_NOT_FOUND
        // Two sightings and the other one spells the answer out.
        if (occurrences > 1) return ClozeReject.ANSWER_REPEATED
        // Options render lower case, so a capitalised blank would give itself away.
        if (tokens.first() == answerNorm) return ClozeReject.ANSWER_SENTENCE_INITIAL

        val match = list?.matchOfForm(candidate.answer)
        val pos = match?.pos?.let(FrequencyPos::cardPosFor)?.takeIf { it != "unknown" }
            ?: answerPos?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "unknown" }
        // No stated part of speech anywhere: refuse rather than guess, since a wrongly blanked
        // function word is exactly the failure this gate exists to prevent.
        if (pos == null || pos !in CONTENT_POS) return ClozeReject.ANSWER_NOT_CONTENT_WORD

        // The blank has to be worth practising: a word the learner is studying, or a frequent one.
        if (candidate.cardId == null && match == null) return ClozeReject.UNANCHORED

        val blankIndex = tokens.indexOf(answerNorm)
        val others = tokens.filterIndexed { i, t -> i != blankIndex && t.isScorable() }
        if (others.isEmpty()) return ClozeReject.TOO_LITTLE_CONTEXT

        // Both remaining checks ask "what are these other words", which only the frequency list can
        // answer. For a language that ships no list there is no way to tell a readable sentence from
        // an impenetrable one, and applying the checks anyway would reject nearly everything and
        // leave the mode permanently empty — so they are skipped, as the app's other
        // dictionary-dependent validators do.
        if (list == null) return null

        val contentWords = others.count { t ->
            list.matchOfForm(t)?.pos?.let(FrequencyPos::cardPosFor) in CONTENT_POS
        }
        if (contentWords < MIN_OTHER_CONTENT_WORDS) return ClozeReject.TOO_LITTLE_CONTEXT

        // Grammar words are read, not learned: the learner has no card for "de" and the list has no
        // entry for it, so anything counting only those two sources reads a plain sentence as gibberish.
        val known = others.count {
            it in knownForms || it in functionWords || list.matchOfForm(it) != null
        }
        if (known.toDouble() / others.size < COMPREHENSIBLE_FRACTION) return ClozeReject.TOO_HARD

        return null
    }

    /** Digits and single characters say nothing about whether the sentence is readable. */
    private fun String.isScorable(): Boolean = length > 1 && any(Char::isLetter)

    private val WHITESPACE = Regex("\\s+")
}

package com.copiosa.domain.cloze

/**
 * One sentence a miner is proposing for the cloze bank, before it has been gated or stored.
 *
 * [sentence] is the raw text with no markup; the blank is described by [answer] and [answerStart]
 * rather than by rewriting the string, so the gate can inspect the original and the repository can
 * build `{{c1::…}}` markup exactly once, at the point of insert.
 */
data class ClozeCandidate(
    val sentence: String,
    /** The surface token to blank, exactly as it appears in [sentence]. */
    val answer: String,
    /** Character offset of [answer] in [sentence] — so an ambiguous token doesn't have to be re-found. */
    val answerStart: Int,
    /** Normalized headword the blank teaches; the dedup and per-lemma-cap key. */
    val lemma: String,
    /** The vocab card this sentence practises, when the learner has one. Null is still drillable. */
    val cardId: Long?,
    /** Native-language rendering of the whole sentence, when the source can supply one. */
    val translation: String?,
    /** Provenance slug — "context", "dict", "book:<id>", "llm". */
    val source: String,
) {
    /** The answer's own character range, for building markup. */
    val answerEnd: Int get() = answerStart + answer.length
}

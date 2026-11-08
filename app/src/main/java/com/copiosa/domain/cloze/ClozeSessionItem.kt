package com.copiosa.domain.cloze

/**
 * One ready-to-answer cloze: the prompt with its blank, the shuffled options, and enough of the
 * row's state to grade the answer without another read.
 *
 * Options are resolved before the screen composes, never inside it — the runner takes its items by
 * value, so choosing them during composition would reshuffle them under the learner on every
 * recomposition.
 */
data class ClozeSessionItem(
    val sentenceId: Long,
    /** The sentence with the answer blanked out, ready to render. */
    val prompt: String,
    /** The sentence whole, as it is spoken in a listening sitting. */
    val sentence: String,
    val options: List<String>,
    val answerIndex: Int,
    /** Native-language rendering of the whole sentence, shown after the answer when there is one. */
    val translation: String?,
    /**
     * Short English meanings parallel to [options], for the after-the-answer comparison of the word
     * picked against the word wanted. A null entry is a word the dictionary had nothing for — and
     * with no dictionary pack downloaded every entry is null, which costs the comparison and
     * nothing else.
     */
    val glosses: List<String?> = emptyList(),
    /** Drives the length-scaled latency thresholds — the reported time includes reading this. */
    val wordCount: Int,
    /** The sentence's own review count, which gates the EASY grade against a lucky first guess. */
    val repetitions: Int,
    /** The vocab card this practises, if any; the nudge target. */
    val cardId: Long?,
) {
    val answer: String get() = options[answerIndex]
}

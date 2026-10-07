package com.copiosa.domain.cloze

/**
 * The two string transformations the bank needs: the dedup key a sentence is stored under, and the
 * `{{c1::…}}` markup it is stored as.
 *
 * Both are pure and both are load-bearing, so they live apart from the repository that calls them.
 */
object ClozeText {

    /**
     * The key the unique index is built on: the sentence lowercased, stripped of everything that
     * isn't a letter, digit or space, with runs of whitespace collapsed.
     *
     * The answer is deliberately **left in**. Two sources quoting the same sentence differ only by a
     * trailing period or a curly apostrophe, and without this they would both be banked; with the
     * answer removed, two different blanks in the same sentence would instead collide and only one
     * could ever be stored.
     */
    fun keyOf(sentence: String): String {
        val sb = StringBuilder(sentence.length)
        var lastWasSpace = false
        for (ch in sentence.lowercase()) {
            when {
                ch.isLetterOrDigit() -> {
                    sb.append(ch)
                    lastWasSpace = false
                }
                ch.isWhitespace() -> {
                    if (!lastWasSpace && sb.isNotEmpty()) sb.append(' ')
                    lastWasSpace = true
                }
                // Punctuation is dropped without inserting a gap, so "gato," and "gato" agree.
                else -> Unit
            }
        }
        return sb.toString().trim()
    }

    /**
     * [candidate] rewritten with its answer wrapped in Anki cloze markup, which is how the row is
     * stored — so `com.copiosa.domain.model.Cloze` renders and answers it with no new parser.
     *
     * The offset is used rather than a search: a sentence can spell the answer more than once (the
     * gate refuses those, but a caller shouldn't have to rely on that to be correct).
     */
    fun markupFor(candidate: ClozeCandidate): String {
        val start = candidate.answerStart
        val end = candidate.answerEnd
        if (start < 0 || end > candidate.sentence.length || start >= end) return candidate.sentence
        return buildString {
            append(candidate.sentence, 0, start)
            append("{{c1::")
            append(candidate.answer)
            append("}}")
            append(candidate.sentence, end, candidate.sentence.length)
        }
    }
}

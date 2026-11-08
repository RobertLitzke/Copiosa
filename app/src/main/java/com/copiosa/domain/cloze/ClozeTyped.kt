package com.copiosa.domain.cloze

import java.text.Normalizer

/** How a typed answer compares with the word that was blanked. */
enum class TypedVerdict {
    /** The word, as written. */
    EXACT,

    /** The right word with an accent missing or misplaced — right, and worth showing the spelling. */
    ACCENTS,

    WRONG,
}

/**
 * Judges a typed cloze answer.
 *
 * Typing is the production half the multiple-choice sitting can't reach: nothing on screen to
 * recognise, so the word has to come from memory. The judgement is deliberately forgiving about the
 * things a phone keyboard makes awkward — case, stray spaces, a missing accent — and strict about
 * the letters, since those are the word. *ñ* is a letter, not an accented *n*: *ano* is not *año*.
 */
object ClozeTyped {

    fun judge(input: String, answer: String): TypedVerdict {
        val typed = tidy(input)
        val wanted = tidy(answer)
        return when {
            typed.isEmpty() -> TypedVerdict.WRONG
            typed == wanted -> TypedVerdict.EXACT
            unaccented(typed) == unaccented(wanted) -> TypedVerdict.ACCENTS
            else -> TypedVerdict.WRONG
        }
    }

    /** The first letter and one dot per remaining letter — a hint that narrows without telling. */
    fun firstLetterHint(answer: String): String {
        val word = answer.trim()
        if (word.isEmpty()) return ""
        return word.first() + word.drop(1).map { if (it.isWhitespace()) ' ' else '·' }.joinToString("")
    }

    private fun tidy(s: String): String =
        s.trim().lowercase().trim { !it.isLetterOrDigit() }.replace(WHITESPACE, " ")

    private fun unaccented(s: String): String =
        Normalizer.normalize(s.replace('ñ', ENYE), Normalizer.Form.NFD).replace(MARKS, "").replace(ENYE, 'ñ')

    private val WHITESPACE = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")

    /** Stands in for ñ while marks are stripped, so its tilde is not taken for an accent. */
    private const val ENYE = ''
}

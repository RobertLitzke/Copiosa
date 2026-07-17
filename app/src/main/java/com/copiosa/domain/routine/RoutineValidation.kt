package com.copiosa.domain.routine

/**
 * Pure, dictionary-free validation of generated grammar items — the part that needs no language pack,
 * so it always runs and is unit-testable on its own. [RoutineContentValidator] layers the forms-table
 * checks on top when a pack is present.
 */
object RoutineValidation {

    /**
     * Subject pronouns and time adverbs that must NOT survive in a pro-dropped referential PI
     * sentence: their presence would hand the learner the referent instead of making them parse the
     * verb. Only unambiguous / accented forms are listed, so "tu" (your) and "el" (the) aren't
     * mistaken for the pronouns "tú"/"él".
     */
    val CUE_WORDS = setOf(
        "yo", "tú", "usted", "ustedes", "él", "ella", "ellos", "ellas",
        "nosotros", "nosotras", "vosotros", "vosotras",
        "ayer", "hoy", "mañana", "manana", "ahora", "anoche",
    )

    fun tokenize(sentence: String): List<String> =
        sentence.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }

    /** Structural soundness + no surviving pronoun/time-adverb cue. */
    fun piStructurallyValid(item: PiItem): Boolean {
        if (item.sentence.isBlank()) return false
        if (item.options.size < 2) return false
        if (item.answerIndex !in item.options.indices) return false
        if (item.options.any { it.isBlank() }) return false
        return tokenize(item.sentence).none { it in CUE_WORDS }
    }

    /** Structural soundness + a single blank + exactly two distinct options. */
    fun clozeStructurallyValid(item: ClozeItem): Boolean {
        if (!item.sentence.contains(ClozeItem.BLANK)) return false
        if (item.options.size != 2) return false
        if (item.answerIndex !in item.options.indices) return false
        if (item.options.any { it.isBlank() }) return false
        return !item.options[0].equals(item.options[1], ignoreCase = true)
    }

    /** Structural soundness + a single blank + exactly three distinct options (generic MC cloze). */
    fun mcClozeStructurallyValid(item: ClozeItem): Boolean {
        if (!item.sentence.contains(ClozeItem.BLANK)) return false
        if (item.options.size != 3) return false
        if (item.answerIndex !in item.options.indices) return false
        if (item.options.any { it.isBlank() }) return false
        return item.options.mapTo(HashSet()) { it.trim().lowercase() }.size == 3
    }
}

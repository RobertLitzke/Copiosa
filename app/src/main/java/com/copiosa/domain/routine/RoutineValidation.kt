package com.copiosa.domain.routine

import com.copiosa.domain.language.LanguageProfiles

/**
 * Pure, dictionary-free validation of generated grammar items — the part that needs no language pack,
 * so it always runs and is unit-testable on its own. [RoutineContentValidator] layers the forms-table
 * checks on top when a pack is present.
 */
object RoutineValidation {

    /**
     * Subject pronouns and time adverbs that must NOT survive in a pro-dropped referential PI
     * sentence: their presence would hand the learner the referent instead of making them parse the
     * verb. They come off the language's own profile — see
     * [com.copiosa.domain.language.LanguageProfile.proDropCueWords], which also explains why only
     * unambiguous forms belong in the list.
     */
    fun cueWords(lang: String): Set<String> =
        LanguageProfiles.forTag(lang)?.proDropCueWords.orEmpty()

    fun tokenize(sentence: String): List<String> =
        sentence.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }

    /** Structural soundness + no surviving pronoun/time-adverb cue for [lang]. */
    fun piStructurallyValid(item: PiItem, lang: String): Boolean {
        if (item.sentence.isBlank()) return false
        if (item.options.size < 2) return false
        if (item.answerIndex !in item.options.indices) return false
        if (item.options.any { it.isBlank() }) return false
        val cues = cueWords(lang)
        // A multi-word cue ("a gente") won't survive tokenization, so scan the sentence for those.
        val lowered = item.sentence.lowercase()
        if (cues.any { it.contains(' ') && lowered.contains(it) }) return false
        return tokenize(item.sentence).none { it in cues }
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

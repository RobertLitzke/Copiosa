package com.copiosa.domain.routine

import com.copiosa.data.translate.DictionaryResult
import com.copiosa.data.translate.LocalDictionary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates generated grammar items, hybrid per the design: [RoutineValidation]'s pack-free structural
 * and cue-scan checks always run; when the target-language dictionary pack is downloaded we add
 * forms-table checks — a referential PI sentence must contain a recognizable conjugated verb (so the
 * verb actually carries the referent), and both cloze options must resolve to real word forms (so a
 * gibberish distractor is rejected). With no pack we fall back to the structural checks alone.
 *
 * Mirrors [com.copiosa.domain.highlight.HighlightTargetProvider]'s use of [LocalDictionary].
 */
@Singleton
class RoutineContentValidator @Inject constructor(
    private val dictionary: LocalDictionary,
) {
    suspend fun validatePi(item: PiItem, lang: String): Boolean {
        if (!RoutineValidation.piStructurallyValid(item, lang)) return false
        if (!dictionary.isDownloaded(lang)) return true
        return containsVerb(RoutineValidation.tokenize(item.sentence), lang)
    }

    suspend fun validateCloze(item: ClozeItem, lang: String): Boolean {
        if (!RoutineValidation.clozeStructurallyValid(item)) return false
        if (!dictionary.isDownloaded(lang)) return true
        return item.options.all { isKnownWord(it, lang) }
    }

    /** Keeps the structurally-and-(when possible)-grammatically valid PI items, up to [max]. */
    suspend fun validReferentialItems(items: List<PiItem>, lang: String, max: Int): List<PiItem> =
        items.filter { validatePi(it, lang) }.take(max)

    suspend fun validClozeItems(items: List<ClozeItem>, lang: String, max: Int): List<ClozeItem> =
        items.filter { validateCloze(it, lang) }.take(max)

    suspend fun validateMcCloze(item: ClozeItem, lang: String): Boolean {
        if (!RoutineValidation.mcClozeStructurallyValid(item)) return false
        if (!dictionary.isDownloaded(lang)) return true
        return item.options.all { isKnownWord(it, lang) }
    }

    /** Keeps the structurally-and-(when possible)-grammatically valid 3-option cloze items, up to [max]. */
    suspend fun validMcClozeItems(items: List<ClozeItem>, lang: String, max: Int): List<ClozeItem> =
        items.filter { validateMcCloze(it, lang) }.take(max)

    /** True if any token resolves (via the pack) to a verb lemma — i.e. the sentence has a conjugated verb. */
    private suspend fun containsVerb(tokens: List<String>, lang: String): Boolean =
        tokens.any { token ->
            val result = dictionary.lookup(token, lang)
            result is DictionaryResult.Found && result.entries.any { it.pos?.contains("verb", ignoreCase = true) == true }
        }

    private suspend fun isKnownWord(word: String, lang: String): Boolean =
        dictionary.lookup(word, lang) is DictionaryResult.Found
}

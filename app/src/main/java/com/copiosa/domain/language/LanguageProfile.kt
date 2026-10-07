package com.copiosa.domain.language

import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.ConjugationSpecs
import com.copiosa.domain.routine.ContrastCategory

/** One bin of a gender sort: the code stored on a flashcard, and the article a learner reads it as. */
data class GenderBin(val code: String, val label: String)

/**
 * The grammar of one target language, as the app needs to know it.
 *
 * This is the seam that stopped the activity library being Spanish-shaped. Before it, seven of the
 * twelve routine activities carried `supportedLanguages = setOf("es")` and the Spanish specifics were
 * inlined where they were used — a pronoun list in one prompt, `embarazada` in another, `el (m.)` in a
 * string resource, `ser vs estar` as a hardcoded fallback. All of that now lives here, once per
 * language, and the activities read it.
 *
 * Adding a language is therefore: write one of these, and (if you want the conjugation drill) generate
 * its verb lexicon. Nothing in `domain/routine/activities/` changes.
 */
data class LanguageProfile(
    /** The BCP-47 tag, which is also the settings-profile identity. */
    val tag: String,
    /**
     * The grammatical contrasts worth drilling, in no particular order — the planner weights them by
     * how the learner is doing. Slugs are proficiency-bucket keys and so are frozen; two languages
     * may share one (ser/estar is a contrast in both Spanish and Portuguese) because proficiency rows
     * are unique on (bucketKey, targetLanguage).
     */
    val contrasts: List<ContrastCategory>,
    /**
     * Subject pronouns. Referential PI bans these from its sentences — their presence would hand the
     * learner the referent instead of making them parse the verb — and the validator scans for them.
     */
    val subjectPronouns: List<String>,
    /** Time adverbs, banned from referential PI for the same reason. */
    val timeAdverbs: List<String>,
    /** Real false friends, quoted to the model so cognate hunt asks for the right kind of trap. */
    val falseFriendExamples: List<String>,
    /** Null when the language has no grammatical gender, which retires the swipe-sort activity. */
    val genderBins: List<GenderBin>?,
) {
    /**
     * The verb paradigm, or null when the drill has no usable data — either the language has no spec
     * at all or its lexicon hasn't been generated yet. Resolved rather than stored so a profile that
     * is never selected never touches the generated lexicon blocks.
     */
    val conjugation: ConjugationSpec?
        get() = ConjugationSpecs.forTag(tag)?.takeIf { it.lexicon.verbs.isNotEmpty() }

    /**
     * Lowercased tokens a pro-dropped sentence must not contain. Only unambiguous forms belong here:
     * Spanish "tu" (your) and "el" (the) are excluded so they aren't mistaken for "tú"/"él".
     */
    val proDropCueWords: Set<String> by lazy {
        (subjectPronouns + timeAdverbs).map { it.lowercase() }.toSet()
    }

    fun supports(feature: LanguageFeature): Boolean = when (feature) {
        LanguageFeature.GRAMMAR -> true
        LanguageFeature.GENDER -> genderBins != null
        LanguageFeature.CONJUGATION -> conjugation != null
    }
}

/**
 * What an activity needs from a language before it can be planned. This replaced the per-activity
 * list of language tags: an activity says what it depends on, and a language says what it has, so
 * neither has to be edited when the other changes.
 */
enum class LanguageFeature {
    /** A grammar profile at all — contrasts, pronouns, false-friend examples. */
    GRAMMAR,

    /** Grammatical gender, so nouns can be sorted into bins. */
    GENDER,

    /** A generated verb lexicon, so the conjugation drill has something to ask about. */
    CONJUGATION,
}

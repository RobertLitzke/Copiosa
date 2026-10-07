package com.copiosa.domain.conjugation

/**
 * A tense that is built from another cell rather than from a stem plus an ending table.
 *
 * These are the places a language builds one paradigm out of another, and modelling them explicitly
 * is what keeps them out of the lexicon: a derived cell inherits its source cell's irregularity for
 * free, so `supieron -> supiera` and `fizeram -> fizer` cost nothing to store.
 *
 * [source] names the cell to read, or is null for "the infinitive itself" (Portuguese's personal
 * infinitive). [strip] is trimmed off the source form before [endings] attach, and [accentPersons]
 * are those whose stem takes a written accent because the stress lands a syllable further back —
 * Spanish accents only the first-person plural (habláramos), Portuguese both plurals (falássemos,
 * falásseis).
 */
data class DerivedTense(
    val source: Cell?,
    val strip: String,
    val endings: List<String>,
    val accentPersons: Set<Person> = emptySet(),
)

/**
 * Everything the conjugation drill needs to know about one language's verb morphology: how to spell a
 * form that follows the rules, which paradigms exist, which persons are in play, and the lexicon of
 * verbs that break the rules.
 *
 * This is the seam that makes the drill language-parametric. [Conjugator] reads it to produce regular
 * forms, [ConjugationEngine] to resolve and segment real ones, [ConjugationReference] to build the
 * tables — none of them hold language data of their own.
 *
 * Lexicon and helpers are lazy: the generated `VerbLexiconData` blocks are large and a profile the
 * learner never selects should cost nothing.
 */
class ConjugationSpec(
    val language: String,
    /** Infinitive endings mapped to the class they mark. Longest match wins, so "ír" beats "ir". */
    val classEndings: Map<String, VerbClass>,
    /** Endings per tense per class, indexed by [Person.ordinal]. A tense absent here must be [derived]. */
    val endings: Map<Tense, Map<VerbClass, List<String>>>,
    /**
     * Tenses whose endings attach to the whole infinitive rather than the stem. This is exactly why an
     * irregular future stem (tendr-, far-) is its own class of deviation rather than a stem change.
     */
    val infinitiveStemTenses: Set<Tense>,
    val derived: Map<Tense, DerivedTense>,
    /** The paradigms this language drills, in display order. */
    val tenses: List<Tense>,
    /** The persons this language drills, in paradigm order. */
    val persons: List<Person>,
    /** The person a learner can switch off (vosotros, vós); null when every person is core. */
    val optionalPerson: Person?,
    /** Subject pronouns as they head a paradigm column. Language data, not interface copy. */
    val pronouns: Map<Person, String>,
    val transformRules: List<TransformRule>,
    /** The model verbs a course opens with, one per class. */
    val modelVerbs: List<String>,
    /** The unstressed endings a strong preterite takes, for the stem/ending highlight split. */
    val strongPreteriteEndings: List<String>,
    /** The class the peeled-off ending half of that split is coloured as. */
    val strongPreteriteEndingClass: IrregularityClass,
    /** Plain vowel to its stressed spelling, for the derived tenses that write an accent. */
    val accents: Map<Char, Char>,
    verbs: () -> List<VerbEntry>,
) {
    val lexicon: VerbLexicon by lazy { VerbLexicon(verbs()) }

    val conjugator: Conjugator by lazy { Conjugator(this) }

    val engine: ConjugationEngine by lazy { ConjugationEngine(this) }

    val reference: ConjugationReference by lazy { ConjugationReference(this) }

    /** Every irregularity class this language's lexicon actually exhibits. */
    val irregularityClasses: List<IrregularityClass> by lazy {
        IrregularityClass.entries.filter { lexicon.verbsInClass(it).isNotEmpty() }
    }

    /** Writes the stressed spelling on the last vowel of [text]; unchanged when it has none. */
    fun accentFinalVowel(text: String): String {
        val index = text.indexOfLast { it in accents }
        if (index < 0) return text
        return text.substring(0, index) + accents.getValue(text[index]) + text.substring(index + 1)
    }
}

/** The languages the conjugation drill has paradigm data for. */
object ConjugationSpecs {

    val SPANISH: ConjugationSpec by lazy { spanishConjugation() }

    val PORTUGUESE: ConjugationSpec by lazy { portugueseConjugation() }

    /**
     * The spec for a BCP-47 tag, or null when the drill has no data for that language. The region
     * subtag is ignored: a pt-BR profile still drills the Portuguese paradigm.
     */
    fun forTag(tag: String): ConjugationSpec? = when (tag.substringBefore('-').lowercase()) {
        "es" -> SPANISH
        "pt" -> PORTUGUESE
        else -> null
    }
}

package com.copiosa.domain.conjugation

/**
 * The rule half of Spanish conjugation: what a verb *would* look like if it never deviated. Every
 * regular cell in the drill comes from here, and every irregular cell is displayed against the form
 * this produces — the naive counterpart shown struck through on reveal is literally
 * [regular]'s output.
 *
 * Pure and allocation-light; no lexicon, no lookups beyond the ending tables.
 */
object RegularConjugator {

    /**
     * Null when [infinitive] doesn't end in a recognisable -ar/-er/-ir (accented -ír counts). Two
     * characters is enough — "ir" is a verb, and its empty stem is exactly why its naive forms come
     * out as non-words.
     */
    fun classOf(infinitive: String): VerbClass? = when {
        infinitive.length < 2 -> null
        infinitive.endsWith("ar") || infinitive.endsWith("ár") -> VerbClass.AR
        infinitive.endsWith("er") || infinitive.endsWith("ér") -> VerbClass.ER
        infinitive.endsWith("ir") || infinitive.endsWith("ír") -> VerbClass.IR
        else -> null
    }

    /** The infinitive minus its two-character ending: "hablar" -> "habl", "oír" -> "o". */
    fun stem(infinitive: String): String =
        if (infinitive.length < 2) infinitive else infinitive.dropLast(2)

    /**
     * The regular form for a cell, or null when [infinitive] isn't a conjugable verb.
     *
     * Future and conditional attach their endings to the *whole* infinitive rather than the stem —
     * which is exactly why an irregular future stem (tendr-, sabr-, har-) is its own class of
     * deviation rather than a stem change.
     */
    fun regular(infinitive: String, tense: Tense, person: Person): String? {
        val cls = classOf(infinitive) ?: return null
        if (tense == Tense.IMPERFECT_SUBJUNCTIVE) {
            val pret3p = regular(infinitive, Tense.PRETERITE, Person.ELLOS) ?: return null
            return imperfectSubjunctive(pret3p, person)
        }
        val base = if (tense == Tense.FUTURE || tense == Tense.CONDITIONAL) infinitive else stem(infinitive)
        return base + ending(cls, tense, person)
    }

    /**
     * The bare ending for a cell. For [Tense.IMPERFECT_SUBJUNCTIVE] this is the -ra ending alone; the
     * base it attaches to comes from the preterite, not the infinitive, so [regular] handles that
     * tense separately.
     */
    fun ending(cls: VerbClass, tense: Tense, person: Person): String {
        val table = when (tense) {
            Tense.PRESENT -> when (cls) {
                VerbClass.AR -> PRESENT_AR
                VerbClass.ER -> PRESENT_ER
                VerbClass.IR -> PRESENT_IR
            }
            Tense.PRETERITE -> if (cls == VerbClass.AR) PRETERITE_AR else PRETERITE_ER_IR
            Tense.IMPERFECT -> if (cls == VerbClass.AR) IMPERFECT_AR else IMPERFECT_ER_IR
            Tense.FUTURE -> FUTURE
            Tense.CONDITIONAL -> CONDITIONAL
            Tense.PRESENT_SUBJUNCTIVE -> if (cls == VerbClass.AR) SUBJ_PRESENT_AR else SUBJ_PRESENT_ER_IR
            Tense.IMPERFECT_SUBJUNCTIVE -> IMPERFECT_SUBJ
        }
        return table[person.ordinal]
    }

    /**
     * The imperfect subjunctive of whatever [preterite3p] is handed in — the *actual* third-plural
     * preterite for a real form, the *regular* one for the naive counterpart. Drop -ron, add the -ra
     * endings; the first-person plural additionally takes a written accent on the stem's final vowel
     * (hablaron -> habláramos, supieron -> supiéramos, dijeron -> dijéramos).
     *
     * The -se set (hablase, hablases, ...) is equally correct Spanish but isn't drilled in v1.
     */
    fun imperfectSubjunctive(preterite3p: String, person: Person): String? {
        if (!preterite3p.endsWith("ron")) return null
        val base = preterite3p.dropLast(3)
        if (base.isEmpty()) return null
        val stem = if (person == Person.NOSOTROS) accentFinalVowel(base) else base
        return stem + IMPERFECT_SUBJ[person.ordinal]
    }

    /**
     * Writes an acute accent on the last vowel of [text]. Used only for the imperfect subjunctive's
     * nosotros form, whose stress falls one syllable further back than the ending would suggest.
     */
    internal fun accentFinalVowel(text: String): String {
        val index = text.indexOfLast { it in PLAIN_VOWELS }
        if (index < 0) return text
        return text.substring(0, index) + ACCENTED[text[index]] + text.substring(index + 1)
    }

    private const val PLAIN_VOWELS = "aeiou"
    private val ACCENTED = mapOf('a' to 'á', 'e' to 'é', 'i' to 'í', 'o' to 'ó', 'u' to 'ú')

    // Ending tables, indexed by Person.ordinal (yo, tú, él, nosotros, vosotros, ellos).
    private val PRESENT_AR = arrayOf("o", "as", "a", "amos", "áis", "an")
    private val PRESENT_ER = arrayOf("o", "es", "e", "emos", "éis", "en")
    private val PRESENT_IR = arrayOf("o", "es", "e", "imos", "ís", "en")

    private val PRETERITE_AR = arrayOf("é", "aste", "ó", "amos", "asteis", "aron")
    private val PRETERITE_ER_IR = arrayOf("í", "iste", "ió", "imos", "isteis", "ieron")

    private val IMPERFECT_AR = arrayOf("aba", "abas", "aba", "ábamos", "abais", "aban")
    private val IMPERFECT_ER_IR = arrayOf("ía", "ías", "ía", "íamos", "íais", "ían")

    private val FUTURE = arrayOf("é", "ás", "á", "emos", "éis", "án")
    private val CONDITIONAL = arrayOf("ía", "ías", "ía", "íamos", "íais", "ían")

    // The subjunctive flips the theme vowel: -ar verbs take e-endings, -er/-ir verbs take a-endings.
    private val SUBJ_PRESENT_AR = arrayOf("e", "es", "e", "emos", "éis", "en")
    private val SUBJ_PRESENT_ER_IR = arrayOf("a", "as", "a", "amos", "áis", "an")

    private val IMPERFECT_SUBJ = arrayOf("ra", "ras", "ra", "ramos", "rais", "ran")
}

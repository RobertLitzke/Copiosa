package com.copiosa.domain.conjugation

import com.copiosa.domain.conjugation.data.VerbLexiconData

/**
 * Spanish verb morphology. Mirrored by `tools/conj/es.py`, and every cell of it is replayed through
 * [ConjugationEngine] by `ConjugationGoldenTest`, so the two implementations cannot drift apart.
 *
 * The -se imperfect subjunctive (hablase, hablases, …) is equally correct Spanish but isn't drilled.
 */
internal fun spanishConjugation(): ConjugationSpec = ConjugationSpec(
    language = "es",
    classEndings = mapOf(
        "ar" to VerbClass.AR, "ár" to VerbClass.AR,
        "er" to VerbClass.ER, "ér" to VerbClass.ER,
        "ir" to VerbClass.IR, "ír" to VerbClass.IR,
    ),
    endings = mapOf(
        Tense.PRESENT to mapOf(
            VerbClass.AR to listOf("o", "as", "a", "amos", "áis", "an"),
            VerbClass.ER to listOf("o", "es", "e", "emos", "éis", "en"),
            VerbClass.IR to listOf("o", "es", "e", "imos", "ís", "en"),
        ),
        Tense.PRETERITE to mapOf(
            VerbClass.AR to listOf("é", "aste", "ó", "amos", "asteis", "aron"),
            VerbClass.ER to listOf("í", "iste", "ió", "imos", "isteis", "ieron"),
            VerbClass.IR to listOf("í", "iste", "ió", "imos", "isteis", "ieron"),
        ),
        Tense.IMPERFECT to mapOf(
            VerbClass.AR to listOf("aba", "abas", "aba", "ábamos", "abais", "aban"),
            VerbClass.ER to listOf("ía", "ías", "ía", "íamos", "íais", "ían"),
            VerbClass.IR to listOf("ía", "ías", "ía", "íamos", "íais", "ían"),
        ),
        // Future and conditional attach to the whole infinitive — see [infinitiveStemTenses].
        Tense.FUTURE to allClasses(listOf("é", "ás", "á", "emos", "éis", "án")),
        Tense.CONDITIONAL to allClasses(listOf("ía", "ías", "ía", "íamos", "íais", "ían")),
        // The subjunctive flips the theme vowel: -ar verbs take e-endings, -er/-ir verbs take a-endings.
        Tense.PRESENT_SUBJUNCTIVE to mapOf(
            VerbClass.AR to listOf("e", "es", "e", "emos", "éis", "en"),
            VerbClass.ER to listOf("a", "as", "a", "amos", "áis", "an"),
            VerbClass.IR to listOf("a", "as", "a", "amos", "áis", "an"),
        ),
    ),
    infinitiveStemTenses = setOf(Tense.FUTURE, Tense.CONDITIONAL),
    derived = mapOf(
        // Drop -ron from the third-plural preterite, add the -ra endings; the first-person plural
        // additionally takes a written accent (hablaron -> habláramos, dijeron -> dijéramos).
        Tense.IMPERFECT_SUBJUNCTIVE to DerivedTense(
            source = Cell(Tense.PRETERITE, Person.ELLOS),
            strip = "ron",
            endings = listOf("ra", "ras", "ra", "ramos", "rais", "ran"),
            accentPersons = setOf(Person.NOSOTROS),
        ),
    ),
    tenses = listOf(
        Tense.PRESENT, Tense.PRETERITE, Tense.IMPERFECT, Tense.FUTURE,
        Tense.CONDITIONAL, Tense.PRESENT_SUBJUNCTIVE, Tense.IMPERFECT_SUBJUNCTIVE,
    ),
    persons = listOf(Person.YO, Person.TU, Person.EL, Person.NOSOTROS, Person.VOSOTROS, Person.ELLOS),
    optionalPerson = Person.VOSOTROS,
    pronouns = mapOf(
        Person.YO to "yo",
        Person.TU to "tú",
        Person.EL to "él / ella",
        Person.NOSOTROS to "nosotros",
        Person.VOSOTROS to "vosotros",
        Person.ELLOS to "ellos / ellas",
    ),
    transformRules = TransformRules.SPANISH,
    modelVerbs = listOf("hablar", "comer", "vivir"),
    strongPreteriteEndings = listOf("e", "iste", "o", "imos", "isteis", "ieron"),
    strongPreteriteEndingClass = IrregularityClass.STRONG_PRET_ENDING,
    accents = mapOf('a' to 'á', 'e' to 'é', 'i' to 'í', 'o' to 'ó', 'u' to 'ú'),
    verbs = { VerbLexiconData.entries },
)

/** One ending table shared by every class — the shape future and conditional take. */
internal fun allClasses(endings: List<String>): Map<VerbClass, List<String>> =
    VerbClass.entries.associateWith { endings }

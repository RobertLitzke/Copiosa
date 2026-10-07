package com.copiosa.domain.conjugation

import com.copiosa.domain.conjugation.data.VerbLexiconDataPt

/**
 * European Portuguese verb morphology. Mirrored by `tools/conj/pt.py` and replayed cell-by-cell by
 * `ConjugationGoldenPtTest`, the same anti-drift contract Spanish has.
 *
 * Two paradigms have no Spanish counterpart, and both are why this profile is worth drilling:
 *
 * - the **future subjunctive** (*se fizer*), built off the third-plural preterite exactly as the
 *   imperfect subjunctive is, and
 * - the **personal infinitive** (*para fazermos*), built off the infinitive.
 *
 * For a regular verb the two are spelled identically; for an irregular one they diverge (*fizer* vs
 * *fazer*), which is the single most tested distinction in the language and the reason
 * [TransformRules.PT_PRETERITE_TO_FUTURE_SUBJUNCTIVE] exists.
 *
 * This is pt-PT, not pt-BR: the first-person plural preterite is written *falámos*, and *tu* is a
 * core person rather than a regionalism.
 */
internal fun portugueseConjugation(): ConjugationSpec = ConjugationSpec(
    language = "pt",
    classEndings = mapOf(
        "ar" to VerbClass.AR,
        "er" to VerbClass.ER,
        "ir" to VerbClass.IR,
        // pôr and its compounds (compor, supor, repor) descend from *poer* and take -er's shape; their
        // one-character stem means the naive forms are non-words, which the engine already suppresses.
        "or" to VerbClass.ER,
        "ôr" to VerbClass.ER,
    ),
    endings = mapOf(
        Tense.PRESENT to mapOf(
            VerbClass.AR to listOf("o", "as", "a", "amos", "ais", "am"),
            VerbClass.ER to listOf("o", "es", "e", "emos", "eis", "em"),
            VerbClass.IR to listOf("o", "es", "e", "imos", "is", "em"),
        ),
        // The acute on -ámos is what separates the preterite from the present in pt-PT.
        Tense.PRETERITE to mapOf(
            VerbClass.AR to listOf("ei", "aste", "ou", "ámos", "astes", "aram"),
            VerbClass.ER to listOf("i", "este", "eu", "emos", "estes", "eram"),
            VerbClass.IR to listOf("i", "iste", "iu", "imos", "istes", "iram"),
        ),
        Tense.IMPERFECT to mapOf(
            VerbClass.AR to listOf("ava", "avas", "ava", "ávamos", "áveis", "avam"),
            VerbClass.ER to listOf("ia", "ias", "ia", "íamos", "íeis", "iam"),
            VerbClass.IR to listOf("ia", "ias", "ia", "íamos", "íeis", "iam"),
        ),
        Tense.FUTURE to allClasses(listOf("ei", "ás", "á", "emos", "eis", "ão")),
        Tense.CONDITIONAL to allClasses(listOf("ia", "ias", "ia", "íamos", "íeis", "iam")),
        // Same theme-vowel flip as Spanish: -ar takes e-endings, -er/-ir take a-endings.
        Tense.PRESENT_SUBJUNCTIVE to mapOf(
            VerbClass.AR to listOf("e", "es", "e", "emos", "eis", "em"),
            VerbClass.ER to listOf("a", "as", "a", "amos", "ais", "am"),
            VerbClass.IR to listOf("a", "as", "a", "amos", "ais", "am"),
        ),
    ),
    infinitiveStemTenses = setOf(Tense.FUTURE, Tense.CONDITIONAL),
    derived = mapOf(
        // falaram -> falasse / falássemos. Both plurals take the written accent, unlike Spanish.
        Tense.IMPERFECT_SUBJUNCTIVE to DerivedTense(
            source = Cell(Tense.PRETERITE, Person.ELLOS),
            strip = "ram",
            endings = listOf("sse", "sses", "sse", "ssemos", "sseis", "ssem"),
            accentPersons = setOf(Person.NOSOTROS, Person.VOSOTROS),
        ),
        // falaram -> falar / falarmos; fizeram -> fizer / fizermos.
        Tense.FUTURE_SUBJUNCTIVE to DerivedTense(
            source = Cell(Tense.PRETERITE, Person.ELLOS),
            strip = "ram",
            endings = listOf("r", "res", "r", "rmos", "rdes", "rem"),
        ),
        // Off the infinitive, so it is regular even where the future subjunctive is not.
        Tense.PERSONAL_INFINITIVE to DerivedTense(
            source = null,
            strip = "",
            endings = listOf("", "es", "", "mos", "des", "em"),
        ),
    ),
    tenses = listOf(
        Tense.PRESENT, Tense.PRETERITE, Tense.IMPERFECT, Tense.FUTURE, Tense.CONDITIONAL,
        Tense.PRESENT_SUBJUNCTIVE, Tense.IMPERFECT_SUBJUNCTIVE, Tense.FUTURE_SUBJUNCTIVE,
        Tense.PERSONAL_INFINITIVE,
    ),
    persons = listOf(Person.YO, Person.TU, Person.EL, Person.NOSOTROS, Person.VOSOTROS, Person.ELLOS),
    // vós is archaic outside liturgy and the north; off by default exactly as vosotros is outside Spain.
    optionalPerson = Person.VOSOTROS,
    pronouns = mapOf(
        Person.YO to "eu",
        Person.TU to "tu",
        Person.EL to "ele / ela",
        Person.NOSOTROS to "nós",
        Person.VOSOTROS to "vós",
        Person.ELLOS to "eles / elas",
    ),
    transformRules = TransformRules.PORTUGUESE,
    modelVerbs = listOf("falar", "comer", "partir"),
    strongPreteriteEndings = listOf("e", "este", "e", "emos", "estes", "eram"),
    strongPreteriteEndingClass = IrregularityClass.PT_STRONG_PRET_ENDING,
    // Portuguese writes a circumflex where the stressed vowel is closed, which is the regular case
    // (comêssemos, fôssemos). The irregular é/ó stems (tivéssemos, puséssemos) are stored as
    // overrides on the derived cell rather than guessed at here — see [ConjugationEngine.conjugate].
    accents = mapOf('a' to 'á', 'e' to 'ê', 'i' to 'í', 'o' to 'ô', 'u' to 'ú'),
    verbs = { VerbLexiconDataPt.entries },
)

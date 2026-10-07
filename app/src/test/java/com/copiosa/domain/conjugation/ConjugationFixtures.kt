package com.copiosa.domain.conjugation

/**
 * Hand-built verb entries for the engine tests. Only the cells a test actually exercises are filled
 * in — the real lexicon is generated (see `tools/build_conjugations.py`) and verified separately by
 * `ConjugationGoldenTest`.
 */
internal object ConjugationFixtures {

    /** The Spanish paradigm, which every fixture in this file belongs to. */
    val SPANISH: ConjugationSpec = ConjugationSpecs.SPANISH

    fun verb(
        infinitive: String,
        gloss: String = "",
        rank: Int = 1,
        frequency: Double = 1.0,
        weightMultiplier: Double = 1.0,
        restriction: DrillRestriction? = null,
        overrides: List<Triple<Tense, Person, FormOverride>> = emptyList(),
    ): VerbEntry = VerbEntry(
        infinitive = infinitive,
        rank = rank,
        frequency = frequency,
        gloss = gloss,
        verbClass = SPANISH.conjugator.classOf(infinitive)!!,
        weightMultiplier = weightMultiplier,
        restriction = restriction,
        overrides = overrides
            .groupBy { it.first }
            .mapValues { (_, cells) -> cells.associate { it.second to it.third } },
    )

    fun cell(
        tense: Tense,
        person: Person,
        form: String,
        vararg classes: IrregularityClass,
    ): Triple<Tense, Person, FormOverride> = Triple(tense, person, FormOverride(form, classes.toList()))

    // -- Regular models -------------------------------------------------------------------------

    val HABLAR = verb("hablar", "to speak")
    val COMER = verb("comer", "to eat")
    val VIVIR = verb("vivir", "to live")

    // -- Stem changers --------------------------------------------------------------------------

    val PENSAR = verb(
        "pensar", "to think",
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "pienso", IrregularityClass.STEM_E_IE),
            cell(Tense.PRESENT, Person.TU, "piensas", IrregularityClass.STEM_E_IE),
            cell(Tense.PRESENT, Person.EL, "piensa", IrregularityClass.STEM_E_IE),
            cell(Tense.PRESENT, Person.ELLOS, "piensan", IrregularityClass.STEM_E_IE),
        ),
    )

    val PODER = verb(
        "poder", "to be able",
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "puedo", IrregularityClass.STEM_O_UE),
            cell(Tense.PRETERITE, Person.YO, "pude", IrregularityClass.STRONG_PRET_U),
            cell(Tense.PRETERITE, Person.ELLOS, "pudieron", IrregularityClass.STRONG_PRET_U),
            cell(Tense.FUTURE, Person.YO, "podré", IrregularityClass.FUT_DROP),
        ),
    )

    val PEDIR = verb(
        "pedir", "to ask for",
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "pido", IrregularityClass.STEM_E_I),
            cell(Tense.PRETERITE, Person.EL, "pidió", IrregularityClass.STEM_RAISE_PRET),
        ),
    )

    val JUGAR = verb(
        "jugar", "to play",
        overrides = listOf(cell(Tense.PRESENT, Person.YO, "juego", IrregularityClass.STEM_U_UE)),
    )

    val DORMIR = verb(
        "dormir", "to sleep",
        overrides = listOf(cell(Tense.PRETERITE, Person.EL, "durmió", IrregularityClass.STEM_RAISE_PRET)),
    )

    // -- Yo forms and strong preterites ----------------------------------------------------------

    val TENER = verb(
        "tener", "to have",
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "tengo", IrregularityClass.YO_GO),
            cell(Tense.PRESENT, Person.TU, "tienes", IrregularityClass.STEM_E_IE),
            cell(Tense.PRETERITE, Person.YO, "tuve", IrregularityClass.STRONG_PRET_U),
            cell(Tense.PRETERITE, Person.TU, "tuviste", IrregularityClass.STRONG_PRET_U),
            cell(Tense.PRETERITE, Person.EL, "tuvo", IrregularityClass.STRONG_PRET_U),
            cell(Tense.PRETERITE, Person.ELLOS, "tuvieron", IrregularityClass.STRONG_PRET_U),
            cell(Tense.FUTURE, Person.YO, "tendré", IrregularityClass.FUT_DR),
            cell(Tense.PRESENT_SUBJUNCTIVE, Person.YO, "tenga", IrregularityClass.SUBJ_YO_STEM),
        ),
    )

    val SABER = verb(
        "saber", "to know",
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "sé", IrregularityClass.YO_OTHER),
            cell(Tense.PRETERITE, Person.NOSOTROS, "supimos", IrregularityClass.STRONG_PRET_U),
            cell(Tense.PRETERITE, Person.ELLOS, "supieron", IrregularityClass.STRONG_PRET_U),
            cell(Tense.FUTURE, Person.YO, "sabré", IrregularityClass.FUT_DROP),
        ),
    )

    val DECIR = verb(
        "decir", "to say",
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "digo", IrregularityClass.YO_GO),
            cell(Tense.PRETERITE, Person.YO, "dije", IrregularityClass.STRONG_PRET_J),
            cell(Tense.PRETERITE, Person.ELLOS, "dijeron", IrregularityClass.STRONG_PRET_J),
            cell(Tense.FUTURE, Person.YO, "diré", IrregularityClass.FUT_IRREGULAR),
        ),
    )

    val HACER = verb(
        "hacer", "to do",
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "hago", IrregularityClass.YO_GO),
            cell(Tense.PRETERITE, Person.YO, "hice", IrregularityClass.STRONG_PRET_I),
        ),
    )

    val CABER = verb(
        "caber", "to fit",
        overrides = listOf(cell(Tense.PRESENT, Person.YO, "quepo", IrregularityClass.YO_OTHER)),
    )

    val CONOCER = verb(
        "conocer", "to know",
        overrides = listOf(cell(Tense.PRESENT, Person.YO, "conozco", IrregularityClass.YO_ZCO)),
    )

    // -- Spelling ---------------------------------------------------------------------------------

    val ESTAR = verb(
        "estar", "to be",
        weightMultiplier = 0.35,
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "estoy", IrregularityClass.YO_OY),
            cell(Tense.PRESENT, Person.TU, "estás", IrregularityClass.ACCENT_SHIFT),
            cell(Tense.PRETERITE, Person.YO, "estuve", IrregularityClass.STRONG_PRET_U),
        ),
    )

    val LEER = verb(
        "leer", "to read",
        overrides = listOf(
            cell(Tense.PRETERITE, Person.EL, "leyó", IrregularityClass.ORTH_Y_HIATUS),
            cell(Tense.PRETERITE, Person.ELLOS, "leyeron", IrregularityClass.ORTH_Y_HIATUS),
        ),
    )

    val BUSCAR = verb(
        "buscar", "to look for",
        overrides = listOf(cell(Tense.PRETERITE, Person.YO, "busqué", IrregularityClass.ORTH_CAR_GAR_ZAR)),
    )

    // -- Suppletive and short-stem ----------------------------------------------------------------

    val IR = verb(
        "ir", "to go",
        weightMultiplier = 0.35,
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "voy", IrregularityClass.SUPPLETIVE),
            cell(Tense.PRETERITE, Person.ELLOS, "fueron", IrregularityClass.SUPPLETIVE),
            cell(Tense.IMPERFECT, Person.YO, "iba", IrregularityClass.IMPERFECT_IRREGULAR),
        ),
    )

    val SER = verb(
        "ser", "to be",
        weightMultiplier = 0.35,
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "soy", IrregularityClass.SUPPLETIVE),
            cell(Tense.IMPERFECT, Person.YO, "era", IrregularityClass.IMPERFECT_IRREGULAR),
        ),
    )

    val VER = verb(
        "ver", "to see",
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "veo", IrregularityClass.YO_OTHER),
            cell(Tense.IMPERFECT, Person.YO, "veía", IrregularityClass.IMPERFECT_IRREGULAR),
        ),
    )

    val DAR = verb(
        "dar", "to give",
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "doy", IrregularityClass.YO_OY),
            // dar borrows the -er/-ir preterite wholesale; no tidier class fits.
            cell(Tense.PRETERITE, Person.YO, "di", IrregularityClass.OTHER),
        ),
    )

    /** Decode-only, mirroring how the real lexicon restricts it. */
    val HABER = verb(
        "haber", "to have (auxiliary)",
        weightMultiplier = 0.35,
        restriction = DrillRestriction(allowedDirections = setOf(DrillDirection.DECODE)),
        overrides = listOf(
            cell(Tense.PRESENT, Person.YO, "he", IrregularityClass.SUPPLETIVE),
            cell(Tense.PRESENT, Person.NOSOTROS, "hemos", IrregularityClass.SUPPLETIVE),
            cell(Tense.PRETERITE, Person.ELLOS, "hubieron", IrregularityClass.STRONG_PRET_U),
            cell(Tense.FUTURE, Person.YO, "habré", IrregularityClass.FUT_DROP),
        ),
    )

    val ALL = listOf(
        HABLAR, COMER, VIVIR, PENSAR, PODER, PEDIR, JUGAR, DORMIR, TENER, SABER, DECIR, HACER,
        CABER, CONOCER, ESTAR, LEER, BUSCAR, IR, SER, VER, DAR, HABER,
    )
}

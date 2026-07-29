package com.copiosa.domain.conjugation

/**
 * One derivation the learner is asked to perform: given a cell, produce the cell it feeds.
 *
 * These are curated rather than arbitrary cell pairs, because only a handful of pairs are actually
 * *derivations* — places where Spanish builds one paradigm out of another, and where knowing the
 * source cell is genuinely sufficient to get the target right. Asking for an unrelated pair would
 * just be a produce card with extra reading.
 */
data class TransformRule(
    val slug: String,
    val sourceTense: Tense,
    val sourcePerson: Person,
    val targetTense: Tense,
    /** Null when any person may be asked for; set when the derivation only reads in one person. */
    val fixedTargetPerson: Person? = null,
    val label: String,
) {
    fun sourceCell(): Cell = Cell(sourceTense, sourcePerson)

    fun targetCells(persons: Collection<Person>): List<Cell> =
        (fixedTargetPerson?.let { listOf(it) } ?: persons.toList()).map { Cell(targetTense, it) }
}

object TransformRules {

    /** Third-plural preterite -> imperfect subjunctive. The one place the derivational spine is visible. */
    val PRETERITE_TO_IMPERFECT_SUBJUNCTIVE = TransformRule(
        slug = "pret3p_to_subimp",
        sourceTense = Tense.PRETERITE, sourcePerson = Person.ELLOS,
        targetTense = Tense.IMPERFECT_SUBJUNCTIVE,
        label = "preterite -> imperfect subjunctive",
    )

    /** Irregular yo -> present subjunctive: tengo -> tenga, conozco -> conozca, digo -> diga. */
    val YO_TO_PRESENT_SUBJUNCTIVE = TransformRule(
        slug = "pres1s_to_subpres",
        sourceTense = Tense.PRESENT, sourcePerson = Person.YO,
        targetTense = Tense.PRESENT_SUBJUNCTIVE,
        label = "yo form -> present subjunctive",
    )

    /** Future and conditional share one stem, so either gives the other away. */
    val FUTURE_TO_CONDITIONAL = TransformRule(
        slug = "fut1s_to_cond1s",
        sourceTense = Tense.FUTURE, sourcePerson = Person.YO,
        targetTense = Tense.CONDITIONAL, fixedTargetPerson = Person.YO,
        label = "future -> conditional",
    )

    /** Present -> preterite in the third singular: where a present-only stem change stops applying. */
    val PRESENT_TO_PRETERITE = TransformRule(
        slug = "pres3s_to_pret3s",
        sourceTense = Tense.PRESENT, sourcePerson = Person.EL,
        targetTense = Tense.PRETERITE, fixedTargetPerson = Person.EL,
        label = "present -> preterite",
    )

    val ALL = listOf(
        PRETERITE_TO_IMPERFECT_SUBJUNCTIVE,
        YO_TO_PRESENT_SUBJUNCTIVE,
        FUTURE_TO_CONDITIONAL,
        PRESENT_TO_PRETERITE,
    )

    fun fromSlug(slug: String?): TransformRule? = ALL.firstOrNull { it.slug == slug }

    /**
     * Whether a rule is worth asking about for a verb: the source cell must actually deviate, or the
     * card degenerates into "apply the ending you already know". The verb's own restrictions apply
     * too — `haber` is excluded from transform cards entirely, so this never fires for it.
     */
    fun applicableTo(verb: VerbEntry, rule: TransformRule): Boolean {
        if (!verb.allows(DrillDirection.TRANSFORM)) return false
        val source = rule.sourceCell()
        if (!verb.allows(source)) return false
        return verb.overrideAt(source.tense, source.person) != null
    }

    /** Every (rule, target cell) pair a verb can be drilled on, given the persons in play. */
    fun cardsFor(verb: VerbEntry, persons: Collection<Person>): List<Pair<TransformRule, Cell>> =
        ALL.filter { applicableTo(verb, it) }
            .flatMap { rule -> rule.targetCells(persons).filter { verb.allows(it) }.map { rule to it } }
}

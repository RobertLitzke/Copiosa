package com.copiosa.domain.conjugation

/**
 * One cell that deviates from [RegularConjugator]. Only deviations are stored — regular cells are
 * produced by rule — which is what keeps the bundled lexicon to roughly 1,200 rows rather than 8,400.
 *
 * No character indices here: the highlighted span is derived at runtime by diffing [form] against
 * the naive regular form, so there is nothing to keep in sync by hand.
 */
data class FormOverride(
    val form: String,
    val classes: List<IrregularityClass>,
    /**
     * Forces the struck-through naive form off for this cell. The engine already suppresses it for
     * suppletive verbs and for stems too short to yield a real word; this is the manual escape hatch
     * for anything those two rules miss.
     */
    val suppressNaive: Boolean = false,
)

/**
 * Restricts which cards a verb may appear in. Null members mean "no restriction".
 *
 * The motivating case is `haber`: compound tenses are out of scope, so asking a learner to *produce*
 * "hemos" in isolation drills a form they would never emit alone — but recognising "hubiera" or
 * "habría" genuinely matters, so it stays in [DrillDirection.DECODE]. Same shape covers `soler`
 * (no future/conditional in real use) and weather verbs (third-person singular only).
 */
data class DrillRestriction(
    val allowedDirections: Set<DrillDirection>? = null,
    val allowedTenses: Set<Tense>? = null,
    val allowedPersons: Set<Person>? = null,
) {
    fun allows(direction: DrillDirection): Boolean = allowedDirections?.contains(direction) ?: true
    fun allows(cell: Cell): Boolean =
        (allowedTenses?.contains(cell.tense) ?: true) && (allowedPersons?.contains(cell.person) ?: true)
}

/**
 * A drillable verb: its identity, how often it turns up in the language, and every way it deviates
 * from the rules. Built by `tools/build_conjugations.py` into the generated `VerbLexiconData`.
 */
data class VerbEntry(
    val infinitive: String,
    /** 1 = most frequent of the bundled verbs. Unique across the lexicon. */
    val rank: Int,
    /** Raw corpus count summed over every surface form of the lemma — the selector's frequency input. */
    val frequency: Double,
    val gloss: String,
    val verbClass: VerbClass,
    /**
     * Deliberate de-weighting, applied after the frequency curve. ser/estar/haber/ir sit so far above
     * everything else that linear weighting would crowd the drill out; they are also the verbs a
     * learner gets the most incidental exposure to, and ser/estar are already drilled by
     * `ContrastCategory.SER_ESTAR`.
     */
    val weightMultiplier: Double = 1.0,
    val restriction: DrillRestriction? = null,
    val overrides: Map<Tense, Map<Person, FormOverride>> = emptyMap(),
    /**
     * A second form that coexists with the canonical one — impersonal "hay" beside "ha". Shown in the
     * reference tables; never used as a drill prompt or answer.
     */
    val alternateForms: Map<Cell, String> = emptyMap(),
) {
    /** True when this verb conjugates entirely by rule across every drilled paradigm. */
    val isRegular: Boolean get() = overrides.isEmpty()

    fun overrideAt(tense: Tense, person: Person): FormOverride? = overrides[tense]?.get(person)

    fun allows(direction: DrillDirection): Boolean = restriction?.allows(direction) ?: true
    fun allows(cell: Cell): Boolean = restriction?.allows(cell) ?: true

    /** Every irregularity class this verb exhibits anywhere, for reference grouping and generalize pools. */
    val classes: Set<IrregularityClass>
        get() = overrides.values.flatMap { byPerson -> byPerson.values.flatMap { it.classes } }.toSet()
}

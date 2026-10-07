package com.copiosa.domain.conjugation

/**
 * Narrows what a drill session may draw from: which verbs, and which cells of their paradigm. Null
 * members mean "no restriction", mirroring [DrillRestriction] — but where a restriction is a property
 * of a *verb*, a filter is a property of a *session*: a course lesson scoped to "regular -ar verbs,
 * yo and tú only" passes one through [ConjugationDrillSelector.select], and the standalone drill
 * passes none.
 *
 * Not serialized anywhere — a filter is rebuilt from its lesson definition each time — so it is free
 * to grow fields without a migration.
 */
data class DrillVerbFilter(
    /** Only these infinitives (the suppletive lesson names ser/ir/dar/ver outright). */
    val infinitives: Set<String>? = null,
    val verbClasses: Set<VerbClass>? = null,
    /** Only verbs exhibiting at least one of these classes somewhere in their paradigm. */
    val irregularityClasses: Set<IrregularityClass>? = null,
    /**
     * Only verbs with no stored deviation in any of the session's tenses. Scoped to the tenses in
     * play rather than [VerbEntry.isRegular]: *pensar* is a fine "regular preterite" example even
     * though its present tense stem-changes.
     */
    val regularOnly: Boolean = false,
    /**
     * Only these persons. This is the one member that narrows *cells* rather than verbs, and it is
     * what lets a course introduce a paradigm a couple of endings at a time — "-é and -aste" before
     * the third person exists — instead of asking for all six the moment the first is taught. It
     * intersects with the config's own person list, so it can never re-admit vosotros.
     */
    val persons: Set<Person>? = null,
) {
    /** Whether [verb] may appear in a session drilling [tenses]. */
    fun allows(verb: VerbEntry, tenses: Set<Tense>): Boolean =
        (infinitives?.contains(verb.infinitive) ?: true) &&
            (verbClasses?.contains(verb.verbClass) ?: true) &&
            (irregularityClasses?.let { wanted -> verb.classes.any { it in wanted } } ?: true) &&
            (!regularOnly || tenses.none { verb.overrides.containsKey(it) })

    /** [all] narrowed to this filter's persons, in paradigm order. */
    fun personsIn(all: List<Person>): List<Person> =
        persons?.let { wanted -> all.filter { it in wanted } }?.ifEmpty { all } ?: all

    /** True when every member is unset, i.e. the filter would pass every verb and every cell. */
    val isEmpty: Boolean
        get() = infinitives == null && verbClasses == null &&
            irregularityClasses == null && !regularOnly && persons == null
}

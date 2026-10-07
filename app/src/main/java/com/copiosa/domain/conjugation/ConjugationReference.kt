package com.copiosa.domain.conjugation

/** One row of a reference table: a label (verb or class exemplar) and its forms across the persons. */
data class ReferenceRow(val label: String, val note: String? = null, val forms: List<ConjugatedForm>)

/** A paradigm table: one tense, one row per verb. */
data class ReferenceTable(
    val titleKey: String,
    val tense: Tense?,
    val persons: List<Person>,
    val rows: List<ReferenceRow>,
)

/** A group of tables shown together under one heading. */
data class ReferenceSection(val key: String, val subtitle: String? = null, val tables: List<ReferenceTable>)

/**
 * The reference content behind the drill's top-right icon, built **from the engine** rather than
 * written out — so a table can never disagree with what the drill just marked you on. One instance per
 * language; reach it through [ConjugationSpec.reference].
 *
 * Kept in `domain` and keyed by plain strings rather than `@StringRes`, so a future app-wide
 * reference section can reuse it by supplying its own key → resource mapping; the UI half is the only
 * thing that knows about `R.string`.
 */
class ConjugationReference(private val spec: ConjugationSpec) {

    private val lexicon get() = spec.lexicon
    private val engine get() = spec.engine

    /** The model verbs the language's courses open with, one per conjugation class. */
    val modelVerbs: List<String> get() = spec.modelVerbs

    private fun defaultPersons() = spec.persons

    /**
     * How each class conjugates when nothing deviates: one table per tense, the model verbs as rows.
     * This is the baseline every highlighted irregularity is a departure from.
     */
    fun paradigmSections(persons: List<Person> = defaultPersons()): List<ReferenceSection> {
        val models = spec.modelVerbs.mapNotNull { lexicon.byInfinitive(it) }
        if (models.isEmpty()) return emptyList()
        return listOf(
            ReferenceSection(
                key = KEY_PATTERNS,
                tables = spec.tenses.map { tense ->
                    ReferenceTable(
                        titleKey = tense.slug,
                        tense = tense,
                        persons = persons,
                        rows = models.map { verb -> row(verb, tense, persons) },
                    )
                },
            ),
        )
    }

    /**
     * One table per class of irregularity, showing the verbs that exhibit it in the tense where it
     * actually shows up. Classes with too few examples are dropped rather than shown half-empty.
     */
    fun irregularSections(persons: List<Person> = defaultPersons()): List<ReferenceSection> {
        val tables = spec.irregularityClasses.mapNotNull { cls ->
            val verbs = lexicon.verbsInClass(cls).take(EXEMPLARS_PER_CLASS)
            if (verbs.isEmpty()) return@mapNotNull null
            // The tense a class is clearest in: whichever most of its verbs deviate in.
            val tense = verbs.flatMap { verb ->
                verb.overrides.filterValues { byPerson -> byPerson.values.any { cls in it.classes } }.keys
            }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return@mapNotNull null
            ReferenceTable(
                titleKey = cls.slug,
                tense = tense,
                persons = persons,
                rows = verbs.map { row(it, tense, persons) },
            )
        }
        return listOf(ReferenceSection(key = KEY_IRREGULAR, tables = tables))
    }

    /**
     * Verbs matching [query], most frequent first. Deliberately returns the entries rather than built
     * tables: conjugating every verb across every paradigm is thousands of forms, which is a visible
     * stutter if done on every keystroke. The caller builds each verb's table with [verbSection] as it
     * scrolls into view.
     */
    fun verbMatches(query: String = ""): List<VerbEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return lexicon.verbs.sortedBy { it.rank }
        return lexicon.verbs
            .filter { it.infinitive.startsWith(needle) || it.gloss.lowercase().contains(needle) }
            .sortedBy { it.rank }
    }

    fun byInfinitive(infinitive: String): VerbEntry? = lexicon.byInfinitive(infinitive)

    /** One verb's full paradigms — every tense the language drills, as rows. */
    fun verbSection(verb: VerbEntry, persons: List<Person> = defaultPersons()): ReferenceSection =
        ReferenceSection(
            key = verb.infinitive,
            subtitle = verb.gloss,
            tables = listOf(
                ReferenceTable(
                    titleKey = verb.infinitive,
                    tense = null,
                    persons = persons,
                    rows = spec.tenses.map { tense ->
                        ReferenceRow(
                            label = tense.slug,
                            forms = persons.mapNotNull { engine.conjugate(verb, tense, it) },
                        )
                    },
                ),
            ),
        )

    private fun row(verb: VerbEntry, tense: Tense, persons: List<Person>) = ReferenceRow(
        label = verb.infinitive,
        note = verb.gloss.takeIf { it.isNotBlank() },
        forms = persons.mapNotNull { engine.conjugate(verb, tense, it) },
    )

    companion object {
        const val KEY_PATTERNS = "patterns"
        const val KEY_IRREGULAR = "irregular"
        private const val EXEMPLARS_PER_CLASS = 4
    }
}

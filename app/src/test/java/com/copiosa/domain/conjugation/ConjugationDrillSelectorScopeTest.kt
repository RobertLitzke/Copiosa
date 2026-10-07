package com.copiosa.domain.conjugation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The [DrillVerbFilter] seam added for course lessons: a scoped selection must stay inside its
 * scope, and passing no scope must leave the selector exactly as it was.
 */
class ConjugationDrillSelectorScopeTest {

    private val spec = ConjugationSpecs.SPANISH

    private fun select(
        config: ConjugationDrillConfig,
        scope: DrillVerbFilter?,
        count: Int = 30,
    ): List<DrillItem> =
        ConjugationDrillSelector(spec, Random(11)).select(config = config, count = count, scope = scope)

    @Test
    fun `null scope changes nothing`() {
        val config = ConjugationDrillConfig()
        val bare = ConjugationDrillSelector(spec, Random(3)).select(config)
        val scoped = ConjugationDrillSelector(spec, Random(3)).select(config, scope = null)
        assertEquals(bare, scoped)
    }

    @Test
    fun `an infinitive allowlist is exclusive`() {
        val wanted = setOf("ser", "ir", "dar", "ver")
        val items = select(
            ConjugationDrillConfig(tenses = setOf(Tense.PRETERITE)),
            DrillVerbFilter(infinitives = wanted),
        )
        assertTrue(items.isNotEmpty())
        items.forEach { assertTrue("${it.infinitive} not in allowlist", it.infinitive in wanted) }
    }

    @Test
    fun `a class filter with regularOnly keeps regular -ar preterites only`() {
        val config = ConjugationDrillConfig(tenses = setOf(Tense.PRETERITE))
        val items = select(
            config,
            DrillVerbFilter(verbClasses = setOf(VerbClass.AR), regularOnly = true),
        )
        assertTrue(items.isNotEmpty())
        for (item in items) {
            val verb = spec.lexicon.byInfinitive(item.infinitive)!!
            assertEquals(VerbClass.AR, verb.verbClass)
            assertTrue(
                "${item.infinitive} deviates in the preterite",
                !verb.overrides.containsKey(Tense.PRETERITE),
            )
        }
    }

    @Test
    fun `regularOnly is scoped to the session's tenses, not the whole paradigm`() {
        // pensar stem-changes in the present but is regular in the preterite — a "regular
        // preterite" lesson must still be allowed to use it.
        val verb = spec.lexicon.byInfinitive("pensar")!!
        assertTrue(verb.overrides.isNotEmpty())
        val filter = DrillVerbFilter(regularOnly = true)
        assertTrue(filter.allows(verb, setOf(Tense.PRETERITE)))
        assertTrue(!filter.allows(verb, setOf(Tense.PRESENT)))
    }

    @Test
    fun `an irregularity-class filter keeps only verbs exhibiting the pattern`() {
        val wanted = setOf(IrregularityClass.STEM_RAISE_PRET)
        val items = select(
            ConjugationDrillConfig(tenses = setOf(Tense.PRETERITE), irregularEmphasis = 0.9),
            DrillVerbFilter(irregularityClasses = wanted),
        )
        assertTrue(items.isNotEmpty())
        for (item in items) {
            val verb = spec.lexicon.byInfinitive(item.infinitive)!!
            assertTrue(
                "${item.infinitive} lacks the scoped pattern",
                verb.classes.any { it in wanted },
            )
        }
    }

    @Test
    fun `generalize cards outside the scoped classes are not offered`() {
        val wanted = setOf(IrregularityClass.ORTH_CAR_GAR_ZAR)
        val items = select(
            ConjugationDrillConfig(
                tenses = setOf(Tense.PRETERITE),
                directions = setOf(DrillDirection.GENERALIZE),
                irregularEmphasis = 1.0,
            ),
            DrillVerbFilter(irregularityClasses = wanted),
            count = 50,
        )
        for (item in items) {
            val generalize = item as DrillItem.Generalize
            assertTrue("unexpected class ${generalize.cls}", generalize.cls in wanted)
        }
    }

    @Test
    fun `a person filter narrows the cells drilled, not the verbs`() {
        val wanted = setOf(Person.YO, Person.TU)
        val items = select(
            ConjugationDrillConfig(tenses = setOf(Tense.PRETERITE)),
            DrillVerbFilter(persons = wanted),
        )
        assertTrue(items.isNotEmpty())
        // Both persons actually appear — a narrowed session must still be varied.
        assertEquals(wanted, items.map { it.target.person }.toSet())
        assertTrue("more than one verb", items.map { it.infinitive }.distinct().size > 1)
    }

    @Test
    fun `person and verb narrowing compose`() {
        val items = select(
            ConjugationDrillConfig(tenses = setOf(Tense.PRETERITE)),
            DrillVerbFilter(
                verbClasses = setOf(VerbClass.AR),
                regularOnly = true,
                persons = setOf(Person.EL, Person.ELLOS),
            ),
        )
        assertTrue(items.isNotEmpty())
        for (item in items) {
            assertEquals(VerbClass.AR, spec.lexicon.byInfinitive(item.infinitive)!!.verbClass)
            assertTrue("${item.target.person} out of scope", item.target.person != Person.YO)
        }
    }

    /**
     * A filter naming only the optional second-person plural would otherwise intersect the config's
     * person list to nothing and silently produce an empty session. It falls back to the full list
     * instead: a lesson that cannot be honoured exactly is still worth running.
     */
    @Test
    fun `a person filter the config excludes falls back rather than emptying`() {
        val items = select(
            ConjugationDrillConfig(tenses = setOf(Tense.PRETERITE), includeVosotros = false),
            DrillVerbFilter(persons = setOf(Person.VOSOTROS)),
        )
        assertTrue(items.isNotEmpty())
        assertTrue(items.none { it.target.person == Person.VOSOTROS })
    }

    @Test
    fun `an over-narrow scope yields an empty session rather than leaking`() {
        val items = select(
            ConjugationDrillConfig(tenses = setOf(Tense.PRETERITE)),
            DrillVerbFilter(infinitives = setOf("no_such_verb")),
        )
        assertTrue(items.isEmpty())
    }
}

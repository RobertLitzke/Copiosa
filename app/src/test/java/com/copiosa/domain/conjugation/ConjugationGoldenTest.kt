package com.copiosa.domain.conjugation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays every cell the generator produced through the Kotlin engine.
 *
 * This is the load-bearing test of the whole feature. `tools/build_conjugations.py` reimplements
 * [Conjugator] in Python to work out which cells deviate; if the two implementations ever
 * disagree — an ending table typo, a different accent rule, a changed suppression heuristic — the
 * lexicon would silently encode the wrong thing and the drill would teach it. Comparing all 8,400
 * cells makes that impossible to miss.
 */
class ConjugationGoldenTest {

    private val SPANISH = ConjugationSpecs.SPANISH

    private data class Row(
        val infinitive: String, val tense: Tense, val person: Person,
        val form: String, val naive: String, val showsNaive: Boolean,
        val classes: List<IrregularityClass>,
    )

    private val rows: List<Row> by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(GOLDEN)) {
            "$GOLDEN missing — regenerate with: python tools/build_conjugations.py"
        }
        stream.bufferedReader().useLines { lines ->
            lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val cols = line.split(",")
                Row(
                    infinitive = cols[0],
                    tense = checkNotNull(Tense.fromSlug(cols[1])) { "bad tense ${cols[1]}" },
                    person = checkNotNull(Person.fromSlug(cols[2])) { "bad person ${cols[2]}" },
                    form = cols[3],
                    naive = cols[4],
                    showsNaive = cols[5] == "1",
                    classes = cols[6].split(" ").filter { it.isNotBlank() }
                        .map { slug -> checkNotNull(IrregularityClass.fromSlug(slug)) { "bad class $slug" } },
                )
            }.toList()
        }
    }

    @Test
    fun theEngineReproducesEveryGeneratedCell() {
        assertEquals("golden set should cover every verb x tense x person",
            SPANISH.lexicon.verbs.size * SPANISH.tenses.size * SPANISH.persons.size, rows.size)

        rows.forEach { row ->
            val verb = checkNotNull(SPANISH.lexicon.byInfinitive(row.infinitive)) { "missing ${row.infinitive}" }
            val where = "${row.infinitive} ${row.tense.slug}.${row.person.slug}"
            val actual = SPANISH.engine.conjugate(verb, row.tense, row.person)
            assertNotNull(where, actual)
            assertEquals("$where form", row.form, actual!!.form)
            assertEquals("$where naive", row.naive, actual.naiveForm)
            assertEquals("$where showsNaive", row.showsNaive, actual.showsNaive)
            assertEquals("$where classes", row.classes, actual.classes)
            assertEquals("$where segments must rebuild the form",
                actual.form, actual.segments.joinToString("") { it.text })
        }
    }

    @Test
    fun everyIrregularCellHighlightsSomething() {
        rows.filter { it.classes.isNotEmpty() }.forEach { row ->
            val verb = SPANISH.lexicon.byInfinitive(row.infinitive)!!
            val segments = SPANISH.engine.conjugate(verb, row.tense, row.person)!!.segments
            assertTrue(
                "${row.infinitive} ${row.tense.slug}.${row.person.slug} highlighted nothing",
                segments.any { it.role == SegmentRole.IRREGULAR && it.text.isNotEmpty() },
            )
        }
    }

    @Test
    fun theLexiconIsWellFormed() {
        val verbs = SPANISH.lexicon.verbs
        assertEquals(EXPECTED_VERBS, verbs.size)
        assertEquals("ranks must be 1..n and unique",
            (1..EXPECTED_VERBS).toList(), verbs.map { it.rank }.sorted())
        assertEquals("infinitives must be unique", verbs.size, verbs.map { it.infinitive }.toSet().size)
        verbs.forEach {
            assertTrue("${it.infinitive} has no gloss", it.gloss.isNotBlank())
            assertTrue("${it.infinitive} has no frequency", it.frequency > 0)
            assertEquals("${it.infinitive} verb class", SPANISH.conjugator.classOf(it.infinitive), it.verbClass)
        }
        // Ranking must follow frequency, since the selector's weighting reads one and the reference
        // list is ordered by the other.
        assertEquals(verbs.sortedBy { it.rank }.map { it.infinitive },
            verbs.sortedWith(compareByDescending<VerbEntry> { it.frequency }.thenBy { it.infinitive })
                .map { it.infinitive })
    }

    @Test
    fun theLexiconCarriesEnoughIrregularityToDrill() {
        val irregular = SPANISH.lexicon.verbs.filter { !it.isRegular }
        assertTrue("only ${irregular.size} irregular verbs", irregular.size >= MIN_IRREGULAR_VERBS)
        // Every family except "regular" must actually be represented, or a proficiency bucket and a
        // reference table would ship permanently empty.
        val families = rows.flatMap { it.classes }.map { it.family }.toSet()
        (IrregularityFamily.entries - IrregularityFamily.REGULAR).forEach {
            assertTrue("no verb exhibits $it", it in families)
        }
        assertTrue("too few generalizable classes", SPANISH.lexicon.generalizableClasses.size >= 8)
    }

    @Test
    fun theImperfectSubjunctiveIsNeverStored() {
        // It is derived from the third-plural preterite at runtime; storing it would let the two
        // drift apart.
        SPANISH.lexicon.verbs.forEach {
            assertTrue(
                "${it.infinitive} stores an imperfect subjunctive override",
                Tense.IMPERFECT_SUBJUNCTIVE !in it.overrides,
            )
        }
        // ...and it still comes out irregular wherever the preterite is.
        val saber = SPANISH.lexicon.byInfinitive("saber")!!
        val supiera = SPANISH.engine.conjugate(saber, Tense.IMPERFECT_SUBJUNCTIVE, Person.YO)!!
        assertEquals("supiera", supiera.form)
        assertTrue(supiera.isIrregular)
    }

    private companion object {
        const val GOLDEN = "conjugation_golden.csv"
        const val EXPECTED_VERBS = 200
        const val MIN_IRREGULAR_VERBS = 40
    }
}

package com.copiosa.domain.conjugation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Portuguese half of the anti-drift contract, mirroring `ConjugationGoldenTest`.
 *
 * `tools/conj/pt.py` reimplements [Conjugator] and the Portuguese ending tables in Python to work
 * out which cells deviate; if the two implementations ever disagree — an ending typo, a different
 * accent rule, a changed suppression heuristic — the lexicon would silently encode the wrong thing
 * and the drill would teach it. Replaying all 10,800 cells makes that impossible to miss.
 *
 * Portuguese has nine paradigms to Spanish's seven, which is where the extra 2,400 cells come from.
 */
class ConjugationGoldenPtTest {

    private val PT = ConjugationSpecs.PORTUGUESE

    private data class Row(
        val infinitive: String, val tense: Tense, val person: Person,
        val form: String, val naive: String, val showsNaive: Boolean,
        val classes: List<IrregularityClass>,
    )

    private val rows: List<Row> by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(GOLDEN)) {
            "$GOLDEN missing — regenerate with: python tools/build_conjugations.py --lang pt"
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
            PT.lexicon.verbs.size * PT.tenses.size * PT.persons.size, rows.size)

        rows.forEach { row ->
            val verb = checkNotNull(PT.lexicon.byInfinitive(row.infinitive)) { "missing ${row.infinitive}" }
            val where = "${row.infinitive} ${row.tense.slug}.${row.person.slug}"
            val actual = PT.engine.conjugate(verb, row.tense, row.person)
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
            val verb = PT.lexicon.byInfinitive(row.infinitive)!!
            val segments = PT.engine.conjugate(verb, row.tense, row.person)!!.segments
            assertTrue(
                "${row.infinitive} ${row.tense.slug}.${row.person.slug} highlighted nothing",
                segments.any { it.role == SegmentRole.IRREGULAR && it.text.isNotEmpty() },
            )
        }
    }

    @Test
    fun theLexiconIsWellFormed() {
        val verbs = PT.lexicon.verbs
        assertEquals(EXPECTED_VERBS, verbs.size)
        assertEquals("ranks must be 1..n and unique",
            (1..EXPECTED_VERBS).toList(), verbs.map { it.rank }.sorted())
        assertEquals("infinitives must be unique", verbs.size, verbs.map { it.infinitive }.toSet().size)
        verbs.forEach {
            assertTrue("${it.infinitive} has no gloss", it.gloss.isNotBlank())
            assertTrue("${it.infinitive} has no frequency", it.frequency > 0)
            assertEquals("${it.infinitive} verb class", PT.conjugator.classOf(it.infinitive), it.verbClass)
        }
        assertEquals(verbs.sortedBy { it.rank }.map { it.infinitive },
            verbs.sortedWith(compareByDescending<VerbEntry> { it.frequency }.thenBy { it.infinitive })
                .map { it.infinitive })
    }

    @Test
    fun theLexiconCarriesEnoughIrregularityToDrill() {
        val irregular = PT.lexicon.verbs.filter { !it.isRegular }
        assertTrue("only ${irregular.size} irregular verbs", irregular.size >= MIN_IRREGULAR_VERBS)
        assertTrue("too few generalizable classes", PT.lexicon.generalizableClasses.size >= 4)
        // Every class the lexicon uses must be a Portuguese one; a Spanish slug leaking in would put
        // a "-go yo" label on a Portuguese card.
        rows.flatMap { it.classes }.toSet().forEach {
            assertTrue("${it.slug} is not a Portuguese class", it.slug.startsWith("pt_"))
        }
    }

    @Test
    fun theFutureSubjunctiveAndPersonalInfinitivePartCompanyOnlyOnIrregularVerbs() {
        // The distinction the profile exists to teach. For a regular verb the two paradigms are
        // spelled identically; for fazer they are fizer and fazer.
        val fazer = PT.lexicon.byInfinitive("fazer")!!
        assertEquals("fizer", PT.engine.conjugate(fazer, Tense.FUTURE_SUBJUNCTIVE, Person.YO)!!.form)
        assertEquals("fazer", PT.engine.conjugate(fazer, Tense.PERSONAL_INFINITIVE, Person.YO)!!.form)

        val falar = PT.lexicon.byInfinitive("falar")!!
        PT.persons.forEach { person ->
            assertEquals(
                "falar $person",
                PT.engine.conjugate(falar, Tense.FUTURE_SUBJUNCTIVE, person)!!.form,
                PT.engine.conjugate(falar, Tense.PERSONAL_INFINITIVE, person)!!.form,
            )
        }
    }

    @Test
    fun theDerivedParadigmsAreStoredOnlyWhereTheRuleCannotReachThem() {
        // A derived cell normally costs nothing: it is built from its source and inherits that
        // cell's irregularity. Portuguese does need a handful of exceptions the orthography can't
        // predict — tivéssemos takes an acute where the regular comêssemos takes a circumflex, and
        // sair's personal infinitive is saíres, not *saires. What must never happen is a derived
        // cell being stored that the rule would have produced anyway: that is the derivation
        // quietly ceasing to be trusted, and it would let the two drift apart unnoticed.
        var stored = 0
        PT.lexicon.verbs.forEach { verb ->
            verb.overrides.filterKeys { it in PT.derived.keys }.forEach { (tense, byPerson) ->
                val derived = PT.derived.getValue(tense)
                byPerson.forEach { (person, override) ->
                    val source = derived.source
                        ?.let { verb.overrideAt(it.tense, it.person)?.form ?: PT.conjugator.regular(verb.infinitive, it.tense, it.person) }
                        ?: verb.infinitive
                    val ruleWouldGive = source?.let { PT.conjugator.derive(it, derived, person) }
                    assertTrue(
                        "${verb.infinitive} stores ${tense.slug}.${person.slug} = ${override.form}, " +
                            "which the rule already derives",
                        ruleWouldGive != override.form,
                    )
                    stored++
                }
            }
        }
        // A sanity floor: if this hits zero the exceptions have been silently dropped, and the
        // accent rule is now wrong for every strong preterite rather than right for most.
        assertTrue("expected some stored derived cells, found none", stored > 0)

        // ...and the derivation still carries irregularity through where nothing is stored.
        val ter = PT.lexicon.byInfinitive("ter")!!
        assertEquals("tivesse", PT.engine.conjugate(ter, Tense.IMPERFECT_SUBJUNCTIVE, Person.YO)!!.form)
        assertEquals("tiver", PT.engine.conjugate(ter, Tense.FUTURE_SUBJUNCTIVE, Person.YO)!!.form)
    }

    @Test
    fun theEuropeanPreteriteKeepsItsAccent() {
        // falámos, not falamos: without the acute the cell is homographic with the present, which is
        // exactly the pt-BR spelling this profile is not.
        listOf("falar", "voar", "ficar").forEach { infinitive ->
            val verb = PT.lexicon.byInfinitive(infinitive) ?: return@forEach
            val form = PT.engine.conjugate(verb, Tense.PRETERITE, Person.NOSOTROS)!!.form
            assertTrue("$infinitive preterite 1p is $form", form.endsWith("ámos"))
        }
    }

    private companion object {
        const val GOLDEN = "conjugation_golden_pt.csv"
        const val EXPECTED_VERBS = 200
        const val MIN_IRREGULAR_VERBS = 40
    }
}

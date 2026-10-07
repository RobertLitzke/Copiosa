package com.copiosa.domain.conjugation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The European Portuguese ending tables, checked against forms written out by hand.
 *
 * `ConjugationGoldenPtTest` proves the Kotlin engine and the Python generator agree; it cannot prove
 * they are both *right*, because they mirror each other. This test is the independent side: every
 * expectation is a full regular paradigm for one of the three model verbs, spelled out rather than
 * derived, so a wrong ending fails on the exact cell instead of being reproduced faithfully at both
 * ends.
 */
class PortugueseConjugationTest {

    private val PT = ConjugationSpecs.PORTUGUESE

    private fun row(infinitive: String, tense: Tense): List<String> =
        PT.persons.map { PT.conjugator.regular(infinitive, tense, it)!! }

    @Test
    fun `falar is regular across every paradigm`() {
        assertEquals(
            listOf("falo", "falas", "fala", "falamos", "falais", "falam"),
            row("falar", Tense.PRESENT),
        )
        // The acute on -ámos is what separates the preterite from the present in pt-PT.
        assertEquals(
            listOf("falei", "falaste", "falou", "falámos", "falastes", "falaram"),
            row("falar", Tense.PRETERITE),
        )
        assertEquals(
            listOf("falava", "falavas", "falava", "falávamos", "faláveis", "falavam"),
            row("falar", Tense.IMPERFECT),
        )
        assertEquals(
            listOf("falarei", "falarás", "falará", "falaremos", "falareis", "falarão"),
            row("falar", Tense.FUTURE),
        )
        assertEquals(
            listOf("falaria", "falarias", "falaria", "falaríamos", "falaríeis", "falariam"),
            row("falar", Tense.CONDITIONAL),
        )
        assertEquals(
            listOf("fale", "fales", "fale", "falemos", "faleis", "falem"),
            row("falar", Tense.PRESENT_SUBJUNCTIVE),
        )
        assertEquals(
            listOf("falasse", "falasses", "falasse", "falássemos", "falásseis", "falassem"),
            row("falar", Tense.IMPERFECT_SUBJUNCTIVE),
        )
        assertEquals(
            listOf("falar", "falares", "falar", "falarmos", "falardes", "falarem"),
            row("falar", Tense.FUTURE_SUBJUNCTIVE),
        )
        assertEquals(
            listOf("falar", "falares", "falar", "falarmos", "falardes", "falarem"),
            row("falar", Tense.PERSONAL_INFINITIVE),
        )
    }

    @Test
    fun `comer is regular across every paradigm`() {
        assertEquals(listOf("como", "comes", "come", "comemos", "comeis", "comem"), row("comer", Tense.PRESENT))
        assertEquals(
            listOf("comi", "comeste", "comeu", "comemos", "comestes", "comeram"),
            row("comer", Tense.PRETERITE),
        )
        assertEquals(
            listOf("comia", "comias", "comia", "comíamos", "comíeis", "comiam"),
            row("comer", Tense.IMPERFECT),
        )
        assertEquals(
            listOf("comerei", "comerás", "comerá", "comeremos", "comereis", "comerão"),
            row("comer", Tense.FUTURE),
        )
        // The subjunctive flips the theme vowel, as in Spanish.
        assertEquals(listOf("coma", "comas", "coma", "comamos", "comais", "comam"), row("comer", Tense.PRESENT_SUBJUNCTIVE))
        // A regular -er verb takes the circumflex, not the acute: comêssemos, never *coméssemos.
        assertEquals(
            listOf("comesse", "comesses", "comesse", "comêssemos", "comêsseis", "comessem"),
            row("comer", Tense.IMPERFECT_SUBJUNCTIVE),
        )
        assertEquals(
            listOf("comer", "comeres", "comer", "comermos", "comerdes", "comerem"),
            row("comer", Tense.FUTURE_SUBJUNCTIVE),
        )
    }

    @Test
    fun `partir is regular across every paradigm`() {
        assertEquals(listOf("parto", "partes", "parte", "partimos", "partis", "partem"), row("partir", Tense.PRESENT))
        assertEquals(
            listOf("parti", "partiste", "partiu", "partimos", "partistes", "partiram"),
            row("partir", Tense.PRETERITE),
        )
        assertEquals(
            listOf("partia", "partias", "partia", "partíamos", "partíeis", "partiam"),
            row("partir", Tense.IMPERFECT),
        )
        assertEquals(listOf("parta", "partas", "parta", "partamos", "partais", "partam"), row("partir", Tense.PRESENT_SUBJUNCTIVE))
        assertEquals(
            listOf("partisse", "partisses", "partisse", "partíssemos", "partísseis", "partissem"),
            row("partir", Tense.IMPERFECT_SUBJUNCTIVE),
        )
    }

    @Test
    fun `for a regular verb the future subjunctive and the personal infinitive coincide`() {
        // This is the whole reason the pair is worth drilling: they part company only on the
        // irregular verbs, and a learner who hasn't noticed writes the infinitive for both.
        listOf("falar", "comer", "partir").forEach { verb ->
            assertEquals(verb, row(verb, Tense.FUTURE_SUBJUNCTIVE), row(verb, Tense.PERSONAL_INFINITIVE))
        }
    }

    @Test
    fun `an irregular preterite carries into the future subjunctive but not the personal infinitive`() {
        // fazer's third-plural preterite is "fizeram", so the future subjunctive is "fizer" while the
        // personal infinitive stays "fazer". Built by hand rather than read from the lexicon, so the
        // rule is tested rather than the data.
        val fazer = VerbEntry(
            infinitive = "fazer", rank = 1, frequency = 1.0, gloss = "to do", verbClass = VerbClass.ER,
            overrides = mapOf(
                Tense.PRETERITE to mapOf(
                    Person.ELLOS to FormOverride("fizeram", listOf(IrregularityClass.PT_STRONG_PRET)),
                ),
            ),
        )
        val engine = PT.engine
        assertEquals("fizer", engine.conjugate(fazer, Tense.FUTURE_SUBJUNCTIVE, Person.YO)!!.form)
        assertEquals("fizermos", engine.conjugate(fazer, Tense.FUTURE_SUBJUNCTIVE, Person.NOSOTROS)!!.form)
        assertEquals("fazer", engine.conjugate(fazer, Tense.PERSONAL_INFINITIVE, Person.YO)!!.form)
        assertEquals("fazermos", engine.conjugate(fazer, Tense.PERSONAL_INFINITIVE, Person.NOSOTROS)!!.form)
        // The imperfect subjunctive rides the same stem.
        assertEquals("fizesse", engine.conjugate(fazer, Tense.IMPERFECT_SUBJUNCTIVE, Person.YO)!!.form)
    }

    @Test
    fun `a stored override on a derived cell beats the general accent rule`() {
        // tivéssemos takes an acute where the regular comêssemos takes a circumflex, and no
        // orthographic rule predicts which. The lexicon settles it; the engine must prefer it.
        val ter = VerbEntry(
            infinitive = "ter", rank = 1, frequency = 1.0, gloss = "to have", verbClass = VerbClass.ER,
            overrides = mapOf(
                Tense.PRETERITE to mapOf(
                    Person.ELLOS to FormOverride("tiveram", listOf(IrregularityClass.PT_STRONG_PRET)),
                ),
                Tense.IMPERFECT_SUBJUNCTIVE to mapOf(
                    Person.NOSOTROS to FormOverride("tivéssemos", listOf(IrregularityClass.PT_STRONG_PRET)),
                ),
            ),
        )
        val engine = PT.engine
        assertEquals("tivéssemos", engine.conjugate(ter, Tense.IMPERFECT_SUBJUNCTIVE, Person.NOSOTROS)!!.form)
        // Everything else still derives, so only the exception needs storing.
        assertEquals("tivesse", engine.conjugate(ter, Tense.IMPERFECT_SUBJUNCTIVE, Person.YO)!!.form)
        assertEquals("tiver", engine.conjugate(ter, Tense.FUTURE_SUBJUNCTIVE, Person.YO)!!.form)
    }

    @Test
    fun `classOf covers the three classes and por's compounds`() {
        assertEquals(VerbClass.AR, PT.conjugator.classOf("falar"))
        assertEquals(VerbClass.ER, PT.conjugator.classOf("comer"))
        assertEquals(VerbClass.IR, PT.conjugator.classOf("partir"))
        // pôr behaves like an -er verb with a one-letter stem, so its naive forms are non-words and
        // the engine suppresses them rather than showing "peram" struck through.
        assertEquals(VerbClass.ER, PT.conjugator.classOf("pôr"))
        assertEquals("p", PT.conjugator.stem("pôr"))
        assertEquals(VerbClass.ER, PT.conjugator.classOf("compor"))
        assertNull(PT.conjugator.classOf("casa"))
    }

    @Test
    fun `every cell of every paradigm is producible for the model verbs`() {
        listOf("falar", "comer", "partir", "pôr").forEach { infinitive ->
            PT.tenses.forEach { tense ->
                PT.persons.forEach { person ->
                    assertNotNull(
                        "$infinitive ${tense.slug}.${person.slug}",
                        PT.conjugator.regular(infinitive, tense, person),
                    )
                }
            }
        }
    }

    @Test
    fun `the CONJUGATION feature tracks whether the lexicon has anything to drill`() {
        // An empty lexicon is a supported state, not a broken one — it is how a language with a
        // paradigm but no generated verbs keeps the drill out of routine planning instead of
        // offering an empty session. Now that Portuguese has verbs, the feature must report as met.
        val profile = com.copiosa.domain.language.LanguageProfiles.PORTUGUESE
        if (PT.lexicon.verbs.isEmpty()) {
            assertNull(profile.conjugation)
        } else {
            assertNotNull(profile.conjugation)
            assertTrue(profile.supports(com.copiosa.domain.language.LanguageFeature.CONJUGATION))
        }
    }
}

package com.copiosa.domain.conjugation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConjugatorTest {

    private val SPANISH = ConjugationSpecs.SPANISH

    private fun row(infinitive: String, tense: Tense): List<String> =
        SPANISH.persons.map { SPANISH.conjugator.regular(infinitive, tense, it)!! }

    /** The imperfect subjunctive applied to whatever third-plural preterite it is handed. */
    private fun subImp(preterite3p: String, person: Person): String? =
        SPANISH.conjugator.derive(preterite3p, SPANISH.derived.getValue(Tense.IMPERFECT_SUBJUNCTIVE), person)

    @Test
    fun hablar_isFullyRegularAcrossEveryParadigm() {
        assertEquals(listOf("hablo", "hablas", "habla", "hablamos", "habláis", "hablan"), row("hablar", Tense.PRESENT))
        assertEquals(listOf("hablé", "hablaste", "habló", "hablamos", "hablasteis", "hablaron"), row("hablar", Tense.PRETERITE))
        assertEquals(listOf("hablaba", "hablabas", "hablaba", "hablábamos", "hablabais", "hablaban"), row("hablar", Tense.IMPERFECT))
        assertEquals(listOf("hablaré", "hablarás", "hablará", "hablaremos", "hablaréis", "hablarán"), row("hablar", Tense.FUTURE))
        assertEquals(listOf("hablaría", "hablarías", "hablaría", "hablaríamos", "hablaríais", "hablarían"), row("hablar", Tense.CONDITIONAL))
        assertEquals(listOf("hable", "hables", "hable", "hablemos", "habléis", "hablen"), row("hablar", Tense.PRESENT_SUBJUNCTIVE))
        assertEquals(listOf("hablara", "hablaras", "hablara", "habláramos", "hablarais", "hablaran"), row("hablar", Tense.IMPERFECT_SUBJUNCTIVE))
    }

    @Test
    fun comer_isFullyRegularAcrossEveryParadigm() {
        assertEquals(listOf("como", "comes", "come", "comemos", "coméis", "comen"), row("comer", Tense.PRESENT))
        assertEquals(listOf("comí", "comiste", "comió", "comimos", "comisteis", "comieron"), row("comer", Tense.PRETERITE))
        assertEquals(listOf("comía", "comías", "comía", "comíamos", "comíais", "comían"), row("comer", Tense.IMPERFECT))
        assertEquals(listOf("comeré", "comerás", "comerá", "comeremos", "comeréis", "comerán"), row("comer", Tense.FUTURE))
        assertEquals(listOf("comería", "comerías", "comería", "comeríamos", "comeríais", "comerían"), row("comer", Tense.CONDITIONAL))
        assertEquals(listOf("coma", "comas", "coma", "comamos", "comáis", "coman"), row("comer", Tense.PRESENT_SUBJUNCTIVE))
        assertEquals(listOf("comiera", "comieras", "comiera", "comiéramos", "comierais", "comieran"), row("comer", Tense.IMPERFECT_SUBJUNCTIVE))
    }

    @Test
    fun vivir_isFullyRegularAcrossEveryParadigm() {
        assertEquals(listOf("vivo", "vives", "vive", "vivimos", "vivís", "viven"), row("vivir", Tense.PRESENT))
        assertEquals(listOf("viví", "viviste", "vivió", "vivimos", "vivisteis", "vivieron"), row("vivir", Tense.PRETERITE))
        assertEquals(listOf("vivía", "vivías", "vivía", "vivíamos", "vivíais", "vivían"), row("vivir", Tense.IMPERFECT))
        assertEquals(listOf("viviré", "vivirás", "vivirá", "viviremos", "viviréis", "vivirán"), row("vivir", Tense.FUTURE))
        assertEquals(listOf("viviría", "vivirías", "viviría", "viviríamos", "viviríais", "vivirían"), row("vivir", Tense.CONDITIONAL))
        assertEquals(listOf("viva", "vivas", "viva", "vivamos", "viváis", "vivan"), row("vivir", Tense.PRESENT_SUBJUNCTIVE))
        assertEquals(listOf("viviera", "vivieras", "viviera", "viviéramos", "vivierais", "vivieran"), row("vivir", Tense.IMPERFECT_SUBJUNCTIVE))
    }

    @Test
    fun imperfectSubjunctive_derivesFromWhicheverPreteriteItIsGiven() {
        // The whole point of the tense: hand it an irregular third-plural and the irregularity carries.
        assertEquals("supiera", subImp("supieron", Person.YO))
        assertEquals("dijeran", subImp("dijeron", Person.ELLOS))
        assertEquals("fuera", subImp("fueron", Person.EL))
        assertEquals("tuvieras", subImp("tuvieron", Person.TU))
    }

    @Test
    fun imperfectSubjunctive_nosotrosTakesAWrittenAccent() {
        assertEquals("habláramos", subImp("hablaron", Person.NOSOTROS))
        assertEquals("comiéramos", subImp("comieron", Person.NOSOTROS))
        assertEquals("supiéramos", subImp("supieron", Person.NOSOTROS))
        assertEquals("dijéramos", subImp("dijeron", Person.NOSOTROS))
        assertEquals("fuéramos", subImp("fueron", Person.NOSOTROS))
        // ...and only nosotros.
        SPANISH.persons.filter { it != Person.NOSOTROS }.forEach {
            val form = subImp("hablaron", it)!!
            assertEquals("$form should carry no accent", form, form.replace("á", "a"))
        }
    }

    @Test
    fun imperfectSubjunctive_rejectsAnythingThatIsNotAThirdPluralPreterite() {
        assertNull(subImp("hablo", Person.YO))
        assertNull(subImp("ron", Person.YO))
    }

    @Test
    fun classOf_handlesAccentedInfinitives() {
        assertEquals(VerbClass.AR, SPANISH.conjugator.classOf("hablar"))
        assertEquals(VerbClass.ER, SPANISH.conjugator.classOf("comer"))
        assertEquals(VerbClass.IR, SPANISH.conjugator.classOf("vivir"))
        assertEquals(VerbClass.IR, SPANISH.conjugator.classOf("oír"))
        assertEquals(VerbClass.IR, SPANISH.conjugator.classOf("reír"))
        // Two letters is a verb: "ir" has an empty stem, which is the whole reason its naive forms
        // come out as non-words.
        assertEquals(VerbClass.IR, SPANISH.conjugator.classOf("ir"))
        assertEquals("", SPANISH.conjugator.stem("ir"))
        assertNull(SPANISH.conjugator.classOf("casa"))
        assertNull(SPANISH.conjugator.classOf("i"))
    }

    @Test
    fun everyCellOfEveryParadigmIsProducible() {
        listOf("hablar", "comer", "vivir", "ir", "ser").forEach { infinitive ->
            SPANISH.tenses.forEach { tense ->
                SPANISH.persons.forEach { person ->
                    assertNotNull(
                        "$infinitive $tense $person",
                        SPANISH.conjugator.regular(infinitive, tense, person),
                    )
                }
            }
        }
    }
}

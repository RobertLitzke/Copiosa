package com.copiosa.domain.conjugation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RegularConjugatorTest {

    private fun row(infinitive: String, tense: Tense): List<String> =
        Person.entries.map { RegularConjugator.regular(infinitive, tense, it)!! }

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
        assertEquals("supiera", RegularConjugator.imperfectSubjunctive("supieron", Person.YO))
        assertEquals("dijeran", RegularConjugator.imperfectSubjunctive("dijeron", Person.ELLOS))
        assertEquals("fuera", RegularConjugator.imperfectSubjunctive("fueron", Person.EL))
        assertEquals("tuvieras", RegularConjugator.imperfectSubjunctive("tuvieron", Person.TU))
    }

    @Test
    fun imperfectSubjunctive_nosotrosTakesAWrittenAccent() {
        assertEquals("habláramos", RegularConjugator.imperfectSubjunctive("hablaron", Person.NOSOTROS))
        assertEquals("comiéramos", RegularConjugator.imperfectSubjunctive("comieron", Person.NOSOTROS))
        assertEquals("supiéramos", RegularConjugator.imperfectSubjunctive("supieron", Person.NOSOTROS))
        assertEquals("dijéramos", RegularConjugator.imperfectSubjunctive("dijeron", Person.NOSOTROS))
        assertEquals("fuéramos", RegularConjugator.imperfectSubjunctive("fueron", Person.NOSOTROS))
        // ...and only nosotros.
        Person.entries.filter { it != Person.NOSOTROS }.forEach {
            val form = RegularConjugator.imperfectSubjunctive("hablaron", it)!!
            assertEquals("$form should carry no accent", form, form.replace("á", "a"))
        }
    }

    @Test
    fun imperfectSubjunctive_rejectsAnythingThatIsNotAThirdPluralPreterite() {
        assertNull(RegularConjugator.imperfectSubjunctive("hablo", Person.YO))
        assertNull(RegularConjugator.imperfectSubjunctive("ron", Person.YO))
    }

    @Test
    fun classOf_handlesAccentedInfinitives() {
        assertEquals(VerbClass.AR, RegularConjugator.classOf("hablar"))
        assertEquals(VerbClass.ER, RegularConjugator.classOf("comer"))
        assertEquals(VerbClass.IR, RegularConjugator.classOf("vivir"))
        assertEquals(VerbClass.IR, RegularConjugator.classOf("oír"))
        assertEquals(VerbClass.IR, RegularConjugator.classOf("reír"))
        // Two letters is a verb: "ir" has an empty stem, which is the whole reason its naive forms
        // come out as non-words.
        assertEquals(VerbClass.IR, RegularConjugator.classOf("ir"))
        assertEquals("", RegularConjugator.stem("ir"))
        assertNull(RegularConjugator.classOf("casa"))
        assertNull(RegularConjugator.classOf("i"))
    }

    @Test
    fun everyCellOfEveryParadigmIsProducible() {
        listOf("hablar", "comer", "vivir", "ir", "ser").forEach { infinitive ->
            Tense.entries.forEach { tense ->
                Person.entries.forEach { person ->
                    assertNotNull(
                        "$infinitive $tense $person",
                        RegularConjugator.regular(infinitive, tense, person),
                    )
                }
            }
        }
    }
}

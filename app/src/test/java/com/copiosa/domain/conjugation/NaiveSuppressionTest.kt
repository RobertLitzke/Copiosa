package com.copiosa.domain.conjugation

import com.copiosa.domain.conjugation.ConjugationFixtures.DAR
import com.copiosa.domain.conjugation.ConjugationFixtures.ESTAR
import com.copiosa.domain.conjugation.ConjugationFixtures.HABER
import com.copiosa.domain.conjugation.ConjugationFixtures.HABLAR
import com.copiosa.domain.conjugation.ConjugationFixtures.IR
import com.copiosa.domain.conjugation.ConjugationFixtures.LEER
import com.copiosa.domain.conjugation.ConjugationFixtures.PODER
import com.copiosa.domain.conjugation.ConjugationFixtures.SABER
import com.copiosa.domain.conjugation.ConjugationFixtures.SER
import com.copiosa.domain.conjugation.ConjugationFixtures.TENER
import com.copiosa.domain.conjugation.ConjugationFixtures.VER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The struck-through naive form is only worth showing when it is the mistake a learner would
 * actually make. "sabimos" for "supimos" pre-empts a real error; "o" for "voy" is not a word anyone
 * would produce and reads as a rendering bug.
 */
class NaiveSuppressionTest {

    private fun shows(verb: VerbEntry, tense: Tense, person: Person): Boolean =
        ConjugationEngine.conjugate(verb, tense, person)!!.showsNaive

    @Test
    fun plausibleErrors_areShown() {
        // The canonical case: the regular form is a well-formed word and the obvious wrong guess.
        val supimos = ConjugationEngine.conjugate(SABER, Tense.PRETERITE, Person.NOSOTROS)!!
        assertEquals("supimos", supimos.form)
        assertEquals("sabimos", supimos.naiveForm)
        assertTrue(supimos.showsNaive)

        assertTrue(shows(TENER, Tense.PRESENT, Person.YO))       // teno
        assertTrue(shows(TENER, Tense.PRETERITE, Person.YO))     // tení
        assertTrue(shows(PODER, Tense.PRESENT, Person.YO))       // podo
        assertTrue(shows(ESTAR, Tense.PRESENT, Person.TU))       // estas
        assertTrue(shows(LEER, Tense.PRETERITE, Person.EL))      // leió
        // haber's stem is long enough that "habré" vs "haberé" is a real slip.
        assertTrue(shows(HABER, Tense.FUTURE, Person.YO))
    }

    @Test
    fun suppletiveForms_areSuppressed() {
        // Nobody has ever produced "o" for "voy".
        val voy = ConjugationEngine.conjugate(IR, Tense.PRESENT, Person.YO)!!
        assertEquals("voy", voy.form)
        assertEquals("o", voy.naiveForm)
        assertFalse(voy.showsNaive)

        assertFalse(shows(SER, Tense.PRESENT, Person.YO))        // so
        assertFalse(shows(SER, Tense.IMPERFECT, Person.YO))      // sía
        assertFalse(shows(IR, Tense.IMPERFECT, Person.YO))       // ía
        assertFalse(shows(HABER, Tense.PRESENT, Person.YO))      // habo, but suppletive
    }

    @Test
    fun shortStemVerbs_areSuppressedEvenWhenTheClassIsNotSuppletive() {
        // ver and dar aren't suppletive, but a one-letter stem can't yield a word: "vo", "dé".
        assertFalse(shows(VER, Tense.PRESENT, Person.YO))
        assertFalse(shows(DAR, Tense.PRESENT, Person.YO))
        assertFalse(shows(DAR, Tense.PRETERITE, Person.YO))
    }

    @Test
    fun regularCells_neverShowANaiveForm() {
        assertFalse(shows(HABLAR, Tense.PRESENT, Person.YO))
        assertFalse(shows(TENER, Tense.PRESENT, Person.NOSOTROS))
        assertFalse(shows(SABER, Tense.IMPERFECT, Person.YO))
    }

    @Test
    fun theExplicitOverrideWins() {
        val verb = ConjugationFixtures.verb(
            "probar",
            overrides = listOf(
                Triple(
                    Tense.PRESENT, Person.YO,
                    FormOverride("pruebo", listOf(IrregularityClass.STEM_O_UE), suppressNaive = true),
                ),
            ),
        )
        val form = ConjugationEngine.conjugate(verb, Tense.PRESENT, Person.YO)!!
        assertEquals("probo", form.naiveForm)
        assertFalse("suppressNaive must override the heuristics", form.showsNaive)
    }

    @Test
    fun aShownNaiveFormAlwaysDiffersFromTheRealOne() {
        ConjugationFixtures.ALL.forEach { verb ->
            Tense.entries.forEach { tense ->
                Person.entries.forEach { person ->
                    val form = ConjugationEngine.conjugate(verb, tense, person)!!
                    if (form.showsNaive) {
                        assertTrue(
                            "${verb.infinitive} $tense $person shows an identical naive form",
                            form.naiveForm != form.form,
                        )
                    }
                }
            }
        }
    }
}

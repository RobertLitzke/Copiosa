package com.copiosa.domain.conjugation

import com.copiosa.domain.conjugation.ConjugationFixtures.BUSCAR
import com.copiosa.domain.conjugation.ConjugationFixtures.CABER
import com.copiosa.domain.conjugation.ConjugationFixtures.CONOCER
import com.copiosa.domain.conjugation.ConjugationFixtures.DECIR
import com.copiosa.domain.conjugation.ConjugationFixtures.DORMIR
import com.copiosa.domain.conjugation.ConjugationFixtures.ESTAR
import com.copiosa.domain.conjugation.ConjugationFixtures.HABLAR
import com.copiosa.domain.conjugation.ConjugationFixtures.HACER
import com.copiosa.domain.conjugation.ConjugationFixtures.IR
import com.copiosa.domain.conjugation.ConjugationFixtures.JUGAR
import com.copiosa.domain.conjugation.ConjugationFixtures.LEER
import com.copiosa.domain.conjugation.ConjugationFixtures.PEDIR
import com.copiosa.domain.conjugation.ConjugationFixtures.PENSAR
import com.copiosa.domain.conjugation.ConjugationFixtures.PODER
import com.copiosa.domain.conjugation.ConjugationFixtures.SABER
import com.copiosa.domain.conjugation.ConjugationFixtures.TENER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConjugationSegmentTest {

    /** The highlighted slice(s), pipe-joined: "ten|g|o" reads as stem / irregular / ending. */
    private fun layout(verb: VerbEntry, tense: Tense, person: Person): String =
        ConjugationEngine.conjugate(verb, tense, person)!!.segments.joinToString("|") { it.text }

    private fun irregularText(verb: VerbEntry, tense: Tense, person: Person): String =
        ConjugationEngine.conjugate(verb, tense, person)!!
            .segments.filter { it.role == SegmentRole.IRREGULAR }.joinToString("") { it.text }

    @Test
    fun stemChanges_highlightTheWholeDiphthong_notJustTheChangedLetter() {
        // The raw diff finds only the inserted "i", but "pienso" teaches "ie".
        assertEquals("p|ie|nso", layout(PENSAR, Tense.PRESENT, Person.YO))
        assertEquals("p|ue|do", layout(PODER, Tense.PRESENT, Person.YO))
        // jugar's diff lands on the "e"; the "u" beside it belongs to the same nucleus.
        assertEquals("j|ue|go", layout(JUGAR, Tense.PRESENT, Person.YO))
    }

    @Test
    fun theSilentUOfQuAndGuIsNotPartOfTheNucleus() {
        // querer changes e -> ie. Treating the u of "qu" as a vowel would widen the highlight to
        // "uie" and imply the u changed too.
        val querer = ConjugationFixtures.verb(
            "querer",
            overrides = listOf(
                ConjugationFixtures.cell(Tense.PRESENT, Person.YO, "quiero", IrregularityClass.STEM_E_IE),
            ),
        )
        assertEquals("qu|ie|ro", layout(querer, Tense.PRESENT, Person.YO))
    }

    @Test
    fun singleVowelChanges_stayMinimal() {
        assertEquals("p|i|do", layout(PEDIR, Tense.PRESENT, Person.YO))
        assertEquals("d|u|rmió", layout(DORMIR, Tense.PRETERITE, Person.EL))
    }

    @Test
    fun consonantInsertions_highlightOnlyTheChangedLetters() {
        assertEquals("ten|g|o", layout(TENER, Tense.PRESENT, Person.YO))
        assertEquals("cono|z|co", layout(CONOCER, Tense.PRESENT, Person.YO))
        // decir changes two adjacent letters at once (dec- -> dig-), so the span covers both.
        assertEquals("d|ig|o", layout(DECIR, Tense.PRESENT, Person.YO))
    }

    @Test
    fun strongPreterites_splitTheStemFromTheBorrowedEnding() {
        // Two deviations in one cell: the u-stem, and the unstressed -e where -í belongs.
        assertEquals("t|uv|e", layout(TENER, Tense.PRETERITE, Person.YO))
        assertEquals("t|uv|o", layout(TENER, Tense.PRETERITE, Person.EL))
        assertEquals("d|ij|e", layout(DECIR, Tense.PRETERITE, Person.YO))
        assertEquals("h|ic|e", layout(HACER, Tense.PRETERITE, Person.YO))

        val tuve = ConjugationEngine.conjugate(TENER, Tense.PRETERITE, Person.YO)!!
        val irregulars = tuve.segments.filter { it.role == SegmentRole.IRREGULAR }
        assertEquals(2, irregulars.size)
        assertEquals(IrregularityClass.STRONG_PRET_U, irregulars[0].irregularity)
        assertEquals(IrregularityClass.STRONG_PRET_ENDING, irregulars[1].irregularity)
    }

    @Test
    fun strongPreterites_doNotSplitWhenTheEndingIsAlreadyRegular() {
        // "tuviste" borrows nothing: -iste is the regular ending, so only the stem is irregular.
        assertEquals("t|uv|iste", layout(TENER, Tense.PRETERITE, Person.TU))
        assertEquals(
            1,
            ConjugationEngine.conjugate(TENER, Tense.PRETERITE, Person.TU)!!
                .segments.count { it.role == SegmentRole.IRREGULAR },
        )
    }

    @Test
    fun spellingChanges_highlightTheSubstitutedLetters() {
        assertEquals("le|y|ó", layout(LEER, Tense.PRETERITE, Person.EL))
        assertEquals("est|á|s", layout(ESTAR, Tense.PRESENT, Person.TU))
        assertEquals("bus|qu|é", layout(BUSCAR, Tense.PRETERITE, Person.YO))
    }

    @Test
    fun suppletiveForms_takeTheWholeWord() {
        val voy = ConjugationEngine.conjugate(IR, Tense.PRESENT, Person.YO)!!
        assertEquals(listOf("voy"), voy.segments.map { it.text })
        assertEquals(SegmentRole.IRREGULAR, voy.segments.single().role)
    }

    @Test
    fun mixedVowelAndConsonantSpans_doNotSwallowTheEnding() {
        // "quepo" vs "cabo": the span is "quep", which must not extend right into the -o ending
        // just because -o happens to be a vowel.
        assertEquals("quep|o", layout(CABER, Tense.PRESENT, Person.YO))
    }

    @Test
    fun regularCells_splitOnTheTrueStemEndingBoundary() {
        assertEquals("habl|o", layout(HABLAR, Tense.PRESENT, Person.YO))
        assertEquals("habl|amos", layout(HABLAR, Tense.PRESENT, Person.NOSOTROS))
        // Future and conditional attach to the whole infinitive.
        assertEquals("hablar|é", layout(HABLAR, Tense.FUTURE, Person.YO))
        assertEquals("hablar|ían", layout(HABLAR, Tense.CONDITIONAL, Person.ELLOS))
        assertEquals("habla|ra", layout(HABLAR, Tense.IMPERFECT_SUBJUNCTIVE, Person.YO))
        assertEquals("hablá|ramos", layout(HABLAR, Tense.IMPERFECT_SUBJUNCTIVE, Person.NOSOTROS))
        // A regular cell has nothing highlighted at all.
        assertTrue(
            ConjugationEngine.conjugate(HABLAR, Tense.PRESENT, Person.YO)!!
                .segments.none { it.role == SegmentRole.IRREGULAR },
        )
    }

    @Test
    fun imperfectSubjunctive_inheritsPreteriteIrregularityForFree() {
        val supiera = ConjugationEngine.conjugate(SABER, Tense.IMPERFECT_SUBJUNCTIVE, Person.YO)!!
        assertEquals("supiera", supiera.form)
        assertEquals("sabiera", supiera.naiveForm)
        assertEquals(listOf(IrregularityClass.STRONG_PRET_U), supiera.classes)
        assertTrue("sabiera is exactly the mistake a learner makes", supiera.showsNaive)

        val supieramos = ConjugationEngine.conjugate(SABER, Tense.IMPERFECT_SUBJUNCTIVE, Person.NOSOTROS)!!
        assertEquals("supiéramos", supieramos.form)

        // A verb with a regular third-plural preterite has a regular imperfect subjunctive.
        val hablara = ConjugationEngine.conjugate(HABLAR, Tense.IMPERFECT_SUBJUNCTIVE, Person.YO)!!
        assertEquals("hablara", hablara.form)
        assertFalse(hablara.isIrregular)
        assertFalse(hablara.showsNaive)
    }

    @Test
    fun segmentsAlwaysReconstructTheForm_andNeverHighlightNothing() {
        ConjugationFixtures.ALL.forEach { verb ->
            Tense.entries.forEach { tense ->
                Person.entries.forEach { person ->
                    val conjugated = ConjugationEngine.conjugate(verb, tense, person)
                    assertNotNull("${verb.infinitive} $tense $person", conjugated)
                    val form = conjugated!!
                    assertEquals(
                        "${verb.infinitive} $tense $person segments must rebuild the form",
                        form.form,
                        form.segments.joinToString("") { it.text },
                    )
                    if (form.isIrregular) {
                        assertTrue(
                            "${verb.infinitive} $tense $person highlighted nothing",
                            irregularText(verb, tense, person).isNotEmpty(),
                        )
                    } else {
                        assertEquals(
                            "${verb.infinitive} $tense $person should be regular",
                            form.naiveForm,
                            form.form,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theFamilyOfACellIsItsMostStructuralDeviation() {
        // tener's yo is a -go form; its preterite is a strong preterite; its future is a stem.
        assertEquals(IrregularityFamily.YO_FORM, ConjugationEngine.conjugate(TENER, Tense.PRESENT, Person.YO)!!.family)
        assertEquals(IrregularityFamily.STRONG_PRETERITE, ConjugationEngine.conjugate(TENER, Tense.PRETERITE, Person.YO)!!.family)
        assertEquals(IrregularityFamily.FUTURE_STEM, ConjugationEngine.conjugate(TENER, Tense.FUTURE, Person.YO)!!.family)
        assertEquals(IrregularityFamily.REGULAR, ConjugationEngine.conjugate(HABLAR, Tense.PRESENT, Person.YO)!!.family)
    }
}

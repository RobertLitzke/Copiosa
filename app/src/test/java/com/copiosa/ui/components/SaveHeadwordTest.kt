package com.copiosa.ui.components

import com.copiosa.data.translate.DictionaryEntry
import com.copiosa.data.translate.Sense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry lists below are what the Spanish pack returns for these words (2026-10 build), trimmed
 * to the fields the choice reads. The pack carries a row for every inflected form as well as for
 * every headword, which is why a lookup of one token comes back as several entries.
 */
class SaveHeadwordTest {

    private fun entry(lemma: String, pos: String, english: String? = null, inflectionOf: String? = null) =
        DictionaryEntry(
            lemma = lemma,
            pos = pos,
            definitions = listOf(Sense("…", null, null)),
            englishDefinitions = english?.split("; ").orEmpty(),
            inflectionOf = inflectionOf,
        )

    private val madrigueras = listOf(
        entry("madrigueras", "noun"),
        entry("madriguera", "noun", "burrow, den; hideout", "plural of madriguera"),
    )

    @Test
    fun aPluralIsSavedAsItsSingular_withTheSingularsMeaning() {
        val (lemma, pos) = SaveHeadword.suggest("madrigueras", madrigueras)
        assertEquals("madriguera", lemma)
        assertEquals("noun", pos)
        assertEquals("burrow, den; hideout", SaveHeadword.englishBack(madrigueras, "madriguera", pos))
    }

    /** The form's own row has no English, so saving it as written falls through to its translation. */
    @Test
    fun aFormRowHasNoBackOfItsOwn() {
        assertNull(SaveHeadword.englishBack(madrigueras, "madrigueras", "noun"))
    }

    /** *entorno* is a noun that happens to be a form of *entornar*; the noun is what was pointed at. */
    @Test
    fun aWordThatIsAHeadwordIsSavedAsWritten() {
        val entorno = listOf(
            entry("entorno", "noun", "surroundings, environment"),
            entry("entornar", "verb", "to leave ajar", "first-person singular indicative present of entornar"),
        )
        val (lemma, pos) = SaveHeadword.suggest("Entorno", entorno)
        assertNull(lemma)
        assertEquals("noun", pos)
        assertEquals("surroundings, environment", SaveHeadword.englishBack(entorno, "Entorno", pos))
    }

    /** Plural in both languages: *gafas* is "eyeglasses", not a form of *gafar* or of *gafa*. */
    @Test
    fun aPluralHeadwordStaysPlural() {
        val gafas = listOf(
            entry("gafas", "noun", "eyeglasses"),
            entry("gafar", "verb", "to jinx", "second-person singular indicative present of gafar"),
            entry("gafa", "noun", "grapple; clamp", "plural of gafa"),
        )
        assertNull(SaveHeadword.suggest("gafas", gafas).first)
        assertEquals("eyeglasses", SaveHeadword.englishBack(gafas, "gafas", "noun"))
    }

    /** A conjugation still goes to its infinitive: the form's own row is not a headword. */
    @Test
    fun aConjugationIsSavedAsItsInfinitive() {
        val catapulto = listOf(
            entry("catapultó", "verb"),
            entry("catapultar", "verb", "to catapult", "third-person singular preterite of catapultar"),
        )
        assertEquals("catapultar" to "verb", SaveHeadword.suggest("catapultó", catapulto))
    }

    /**
     * *emboscado* and *alcachofar* are rows the pack cannot gloss. Chosen, they leave the back to be
     * the translation of the plural in the sentence — the mismatch this exists to prevent.
     */
    @Test
    fun aHeadwordThePackCanGlossBeatsOneItCannot() {
        val emboscadas = listOf(
            entry("emboscadas", "participle"),
            entry("emboscadas", "noun"),
            entry("emboscado", "verb", inflectionOf = "feminine plural of emboscado"),
            entry("emboscar", "verb", "to ambush", "feminine participle past plural of emboscar"),
        )
        assertEquals("emboscar" to "verb", SaveHeadword.suggest("emboscadas", emboscadas))

        val alcachofas = listOf(
            entry("alcachofas", "noun"),
            entry("alcachofa", "noun", "artichoke; shower head", "plural of alcachofa"),
            entry("alcachofar", "verb", inflectionOf = "second-person singular indicative present of alcachofar"),
        )
        assertEquals("alcachofa" to "noun", SaveHeadword.suggest("alcachofas", alcachofas))
    }

    /**
     * *matices* is the plural of *matiz* and the tú subjunctive of *matizar*, and the pack glosses
     * both. A second-person reading is rare in a text; the plural is what was being read.
     */
    @Test
    fun aNounPluralBeatsASecondPersonVerbForm() {
        val matices = listOf(
            entry("matices", "noun"),
            entry("matices", "verb"),
            entry("matizar", "verb", "to tint; to qualify", "second-person singular subjunctive present of matizar"),
            entry("matiz", "noun", "shade, hue; nuance", "plural of matiz"),
        )
        assertEquals("matiz" to "noun", SaveHeadword.suggest("matices", matices))
        assertEquals("shade, hue; nuance", SaveHeadword.englishBack(matices, "matiz", "noun"))

        val luces = listOf(
            entry("luces", "noun"),
            entry("luz", "noun", "light", "plural of luz"),
            entry("lucir", "verb", "to shine; to show off", "second-person singular indicative present of lucir"),
        )
        assertEquals("luz" to "noun", SaveHeadword.suggest("luces", luces))
    }

    /** *las emboscadas* are ambushes: a plural participle loses to the noun it is spelled like. */
    @Test
    fun aNounPluralBeatsAParticiple() {
        val emboscadas = listOf(
            entry("emboscadas", "participle"),
            entry("emboscada", "noun", "ambush", "plural of emboscada"),
            entry("emboscar", "verb", "to ambush", "feminine participle past plural of emboscar"),
        )
        assertEquals("emboscada" to "noun", SaveHeadword.suggest("emboscadas", emboscadas))
    }

    /** Any other verb reading still wins: a third-person form is the commonest thing in a text. */
    @Test
    fun aFiniteVerbFormStillBeatsANoun() {
        val entries = listOf(
            entry("pesa", "noun", "weight", "form-of of pesa"),
            entry("pesar", "verb", "to weigh", "third-person singular indicative present of pesar"),
        )
        assertEquals("pesar" to "verb", SaveHeadword.suggest("pesa", entries))
    }

    /** "adverb" and "proverb" contain "verb"; neither is one, so neither jumps the queue. */
    @Test
    fun onlyAVerbIsAVerb() {
        val entries = listOf(
            entry("alfa", "noun", "alpha", "plural of alfa"),
            entry("alfo", "adv", "beta", "form-of of alfo"),
            entry("alfe", "proverb", "gamma", "form-of of alfe"),
        )
        assertEquals("alfa" to "noun", SaveHeadword.suggest("alfas", entries))
    }

    /** With no English anywhere (an older pack, a rare word) the same order applies to every reading. */
    @Test
    fun withoutEnglishTheSameOrderApplies() {
        val placas = listOf(
            entry("placas", "noun"),
            entry("placa", "noun", inflectionOf = "plural of placa"),
            entry("placar", "verb", inflectionOf = "second-person singular indicative present of placar"),
        )
        assertEquals("placa" to "noun", SaveHeadword.suggest("placas", placas))
        assertNull(SaveHeadword.englishBack(placas, "placa", "noun"))

        val catapulto = listOf(
            entry("catapultó", "verb"),
            entry("catapulta", "noun", inflectionOf = "form-of of catapulta"),
            entry("catapultar", "verb", inflectionOf = "third-person singular preterite of catapultar"),
        )
        assertEquals("catapultar" to "verb", SaveHeadword.suggest("catapultó", catapulto))
    }

    /** *tratados* is treaties: a saved *tratar*, whose forms include it, is not what was selected. */
    @Test
    fun aFormsMatchGivesWayToTheWordTheDictionaryMeans() {
        val tratados = listOf(
            entry("tratados", "noun"),
            entry("tratado", "noun", "treaty", "plural of tratado"),
            entry("tratar", "verb", "to treat; to try", "masculine participle past plural of tratar"),
        )
        val (lemma, _) = SaveHeadword.suggest("tratados", tratados)
        assertFalse(SaveHeadword.formMatchHolds("tratar", "tratados", lemma, tratados))
    }

    /** A selection that is a headword in its own right (suggestion null) overrules a verb card too. */
    @Test
    fun aHeadwordAsWrittenOverrulesAFormsMatch() {
        val entorno = listOf(
            entry("entorno", "noun", "surroundings"),
            entry("entornar", "verb", "to leave ajar", "first-person singular indicative present of entornar"),
        )
        assertFalse(SaveHeadword.formMatchHolds("entornar", "entorno", null, entorno))
    }

    @Test
    fun aFormsMatchOnTheSuggestedHeadwordHolds() {
        val pateo = listOf(
            entry("pateó", "verb"),
            entry("patear", "verb", "to kick", "third-person singular preterite of patear"),
        )
        val (lemma, _) = SaveHeadword.suggest("pateó", pateo)
        assertTrue(SaveHeadword.formMatchHolds("patear", "pateó", lemma, pateo))
    }

    /** With no English for the dictionary's pick, it has no better answer than the saved card. */
    @Test
    fun anUnglossedPickDoesNotOverruleAFormsMatch() {
        assertTrue(SaveHeadword.formMatchHolds("gato", "gatitos", null, emptyList()))
        val placas = listOf(
            entry("placas", "noun"),
            entry("placa", "noun", inflectionOf = "plural of placa"),
        )
        assertTrue(SaveHeadword.formMatchHolds("placar", "placas", "placa", placas))
    }

    @Test
    fun nothingFoundSuggestsNothing() {
        assertEquals(null to null, SaveHeadword.suggest("xyzzy", emptyList()))
        assertNull(SaveHeadword.englishBack(emptyList(), "xyzzy", null))
    }

    /** The back follows the part of speech picked, and is capped at three meanings. */
    @Test
    fun theBackIsThePickedEntrys_andCapped() {
        val entries = listOf(
            entry("enano", "adj", "dwarf; pygmy", "feminine plural of enano"),
            entry("enano", "noun", "dwarf, midget; pygmy; gnome; runt", "feminine plural of enano"),
        )
        assertEquals("dwarf; pygmy", SaveHeadword.englishBack(entries, "enano", "adj"))
        assertEquals("dwarf, midget; pygmy; gnome", SaveHeadword.englishBack(entries, "enano", "noun"))
    }
}

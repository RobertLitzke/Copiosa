package com.copiosa.domain.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The language-specific spell-out shapes the quiz depends on, against real CLDR RBNF rules. */
class NumberSpellerTest {

    private val speller = Icu4jNumberSpeller()

    @Test
    fun `english compounds`() {
        assertEquals("seventy-three", speller.spell(73, "en"))
        assertEquals("thirteen", speller.spell(13, "en"))
        assertEquals("thirty", speller.spell(30, "en"))
    }

    @Test
    fun `french vigesimal range`() {
        assertEquals("soixante-dix", speller.spell(70, "fr"))
        assertEquals("soixante-et-onze", speller.spell(71, "fr"))
        assertEquals("quatre-vingts", speller.spell(80, "fr"))
        assertEquals("quatre-vingt-onze", speller.spell(91, "fr"))
    }

    @Test
    fun `spanish fused twenties`() {
        assertEquals("veintiuno", speller.spell(21, "es"))
        assertEquals("sesenta", speller.spell(60, "es"))
        assertEquals("setenta", speller.spell(70, "es"))
    }

    @Test
    fun `german inverted compounds`() {
        assertEquals("einundzwanzig", speller.spell(21, "de"))
        assertEquals("zwölf", speller.spell(12, "de"))
        assertEquals("zwanzig", speller.spell(20, "de"))
    }

    @Test
    fun `italian elision`() {
        assertEquals("ventuno", speller.spell(21, "it"))
        assertEquals("ventotto", speller.spell(28, "it"))
    }

    @Test
    fun `portuguese fourteen`() {
        // CLDR uses one of the two accepted variants; either is fine for the quiz.
        assertTrue(speller.spell(14, "pt") in setOf("catorze", "quatorze"))
    }

    @Test
    fun `output never contains digits`() {
        for (lang in listOf("en", "es", "fr", "de", "it", "pt")) {
            for (n in listOf(1, 13, 30, 71, 80, 99, 100, 555, 1000)) {
                val spelled = speller.spell(n, lang)
                assertTrue("$lang/$n -> $spelled", spelled.none { it.isDigit() })
            }
        }
    }
}

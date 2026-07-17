package com.copiosa.domain.quiz

import org.junit.Assert.fail
import org.junit.Test

/**
 * Holds [WordNumberSpeller] to CLDR: every value 0..1000 in all six languages must match icu4j's
 * RBNF spell-out exactly (soft hyphens stripped). Android's public ICU API doesn't expose RBNF, so
 * the app ships the hand-rolled speller and this test is what keeps it honest. This is the whole range
 * the pool phrases locally; larger numbers are phrased by the LLM at seed time (see QuizPoolSeeder),
 * so the speller is never asked for anything beyond 1000.
 */
class WordNumberSpellerParityTest {

    private val ours = WordNumberSpeller()
    private val icu = Icu4jNumberSpeller()

    @Test
    fun `matches icu spell-out for 0 to 1000 in all six languages`() {
        val mismatches = mutableListOf<String>()
        for (lang in listOf("en", "es", "fr", "de", "it", "pt")) {
            for (n in 0..1000) {
                val expected = icu.spell(n, lang)
                val actual = ours.spell(n, lang)
                if (expected != actual) {
                    mismatches += "$lang $n: icu='$expected' ours='$actual'"
                }
            }
        }
        if (mismatches.isNotEmpty()) {
            fail("${mismatches.size} mismatches; first 30:\n" + mismatches.take(30).joinToString("\n"))
        }
    }
}

package com.copiosa.ui.components

import androidx.compose.ui.graphics.Color
import com.copiosa.domain.highlight.HighlightTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordColorizerTest {

    private fun colorizer(vararg targets: HighlightTarget) = WordColorizer(targets.toList(), Color.Red)

    /** The exact substrings the colorizer would tint in [text], in order. */
    private fun WordColorizer.matched(text: String): List<String> =
        spansFor(text).map { text.substring(it.start, it.end) }

    @Test
    fun `matches an inflected form expanded from the lemma`() {
        // forms already carry the plural, as HighlightTargetProvider would expand it.
        val c = colorizer(HighlightTarget(setOf("gato", "gatos"), gender = null, lang = "es"))
        assertEquals(listOf("gatos"), c.matched("Tengo dos gatos negros"))
        assertEquals(listOf("gato"), c.matched("El gato duerme"))
    }

    @Test
    fun `is case and punctuation insensitive`() {
        val c = colorizer(HighlightTarget(setOf("gato"), gender = null, lang = "es"))
        assertEquals(listOf("Gato", "gato"), c.matched("¡Gato! y otro gato."))
    }

    @Test
    fun `highlights a preceding agreeing article for a gendered noun`() {
        val c = colorizer(HighlightTarget(setOf("fiesta", "fiestas"), gender = "F", lang = "es"))
        assertEquals(listOf("la fiesta"), c.matched("Él fue a la fiesta anoche"))
        // Number is ignored: a plural noun still pulls in its plural article (original casing kept).
        assertEquals(listOf("Las fiestas"), c.matched("Las fiestas fueron geniales"))
        assertEquals(listOf("una fiesta"), c.matched("Hubo una fiesta"))
    }

    @Test
    fun `does not highlight a non-agreeing article`() {
        val c = colorizer(HighlightTarget(setOf("libro"), gender = "M", lang = "es"))
        assertEquals(listOf("el libro"), c.matched("Compré el libro"))
        // "la" is feminine; the masculine noun must not absorb it.
        assertEquals(listOf("libro"), c.matched("la libro"))
    }

    @Test
    fun `does not highlight an article for a non-gendered target`() {
        val c = colorizer(HighlightTarget(setOf("gato"), gender = null, lang = "es"))
        assertEquals(listOf("gato"), c.matched("el gato"))
    }

    @Test
    fun `only reaches back over a single space, not punctuation`() {
        val c = colorizer(HighlightTarget(setOf("fiesta"), gender = "F", lang = "es"))
        // A comma between the article and noun breaks the article highlight.
        assertEquals(listOf("fiesta"), c.matched("la, fiesta"))
    }

    @Test
    fun `does not highlight articles for languages without a table`() {
        val c = colorizer(HighlightTarget(setOf("fiesta"), gender = "F", lang = "de"))
        assertEquals(listOf("fiesta"), c.matched("die fiesta"))
    }

    @Test
    fun `empty colorizer matches nothing`() {
        assertTrue(colorizer().matched("cualquier texto aquí").isEmpty())
        assertTrue(colorizer().isEmpty)
    }
}

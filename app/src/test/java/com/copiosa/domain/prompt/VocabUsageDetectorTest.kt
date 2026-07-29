package com.copiosa.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabUsageDetectorTest {

    /** A hint whose surface forms are already normalized, as HighlightTargetProvider.decode produces. */
    private fun hint(front: String, vararg forms: String) =
        VocabHint(front, back = "", forms = (forms.toSet() + front.lowercase()))

    @Test
    fun `no supplied words yields empty result`() {
        val result = VocabUsageDetector.detect("cualquier texto", emptyList())
        assertEquals(emptyList<String>(), result.used)
        assertEquals(emptyList<String>(), result.unused)
    }

    @Test
    fun `matches the exact headword regardless of case and surrounding punctuation`() {
        val result = VocabUsageDetector.detect("¡Gato! El gato duerme.", listOf(hint("gato")))
        assertEquals(listOf("gato"), result.used)
        assertEquals(emptyList<String>(), result.unused)
    }

    @Test
    fun `matches an inflected form carried on the hint`() {
        val result = VocabUsageDetector.detect("Tengo dos gatos negros", listOf(hint("gato", "gatos")))
        assertEquals(listOf("gato"), result.used)
    }

    @Test
    fun `a form is not matched inside a larger word`() {
        // "gato" must not register just because "gatos" appears — matching is per-token.
        val result = VocabUsageDetector.detect("Tengo dos gatos negros", listOf(hint("gato")))
        assertEquals(emptyList<String>(), result.used)
        assertEquals(listOf("gato"), result.unused)
    }

    @Test
    fun `splits supplied words into used and unused`() {
        val supplied = listOf(hint("perro"), hint("casa"), hint("libro"))
        val result = VocabUsageDetector.detect("El perro entró en la casa.", supplied)
        assertEquals(listOf("perro", "casa"), result.used)
        assertEquals(listOf("libro"), result.unused)
    }

    @Test
    fun `multi-word phrase form matches across punctuation and whitespace`() {
        val result = VocabUsageDetector.detect(
            "Le dije: ¡buenos   días! a todos.",
            listOf(hint("buenos días", "buenos días")),
        )
        assertEquals(listOf("buenos días"), result.used)
    }

    @Test
    fun `matching is accent-sensitive`() {
        // A reply that drops the accent does not count — the safe under-counting direction.
        val result = VocabUsageDetector.detect("el paso siguiente", listOf(hint("pasó")))
        assertEquals(emptyList<String>(), result.used)
        assertEquals(listOf("pasó"), result.unused)
    }
}

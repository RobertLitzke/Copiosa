package com.copiosa.ui.flashcards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenderArticleTest {

    @Test fun `spanish articles`() {
        assertEquals("el", genderArticle("M", "es"))
        assertEquals("la", genderArticle("F", "es"))
        assertEquals("el/la", genderArticle("MF", "es"))
        assertEquals("los", genderArticle("MPL", "es"))
        assertEquals("las", genderArticle("FPL", "es"))
        assertNull(genderArticle("N", "es")) // Spanish has no neuter noun article
    }

    @Test fun `german articles include neuter`() {
        assertEquals("der", genderArticle("M", "de"))
        assertEquals("die", genderArticle("F", "de"))
        assertEquals("das", genderArticle("N", "de"))
    }

    @Test fun `portuguese and french articles`() {
        assertEquals("o", genderArticle("M", "pt"))
        assertEquals("a", genderArticle("F", "pt"))
        assertEquals("le", genderArticle("M", "fr"))
        assertEquals("la", genderArticle("F", "fr"))
    }

    @Test fun `region-tagged language tag still resolves`() {
        assertEquals("el", genderArticle("m", "es-MX"))
    }

    @Test fun `null or unknown yields no article`() {
        assertNull(genderArticle(null, "es"))
        assertNull(genderArticle("M", "en"))
    }

    @Test fun `gender options include neuter only for german`() {
        assertEquals(listOf(null, "M", "F", "MF"), genderOptions("es"))
        assertEquals(listOf(null, "M", "F", "N"), genderOptions("de"))
    }
}

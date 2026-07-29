package com.copiosa.domain.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackNoteSplitterTest {

    @Test fun `plain single-line translation is left untouched`() {
        val (translation, note) = BackNoteSplitter.split("crucible; melting pot")
        assertEquals("crucible; melting pot", translation)
        assertNull(note)
    }

    @Test fun `a multi-word sentence translation is not split`() {
        val (translation, note) = BackNoteSplitter.split("it does not matter to me at all")
        assertEquals("it does not matter to me at all", translation)
        assertNull(note)
    }

    @Test fun `labelled translation with a usage-note section splits at the header`() {
        val blob = """
            **Translation:**
            * **Crucible** (literal)
            * **Melting pot** (figurative)

            **Usage Note:**
            * **Literal:** a container for melting metal.
        """.trimIndent()
        val (translation, note) = BackNoteSplitter.split(blob)
        assertEquals("* **Crucible** (literal)\n* **Melting pot** (figurative)", translation)
        assertEquals("**Usage Note:**\n* **Literal:** a container for melting metal.", note)
    }

    @Test fun `leading translation label is stripped even without a note`() {
        val (translation, note) = BackNoteSplitter.split("**Translation:** \nDawn, beginnings, early stages.")
        assertEquals("Dawn, beginnings, early stages.", translation)
        assertNull(note)
    }

    @Test fun `italic note-on header also starts the note`() {
        val (translation, note) = BackNoteSplitter.split("savanna; bedsheet\n*Note on pronunciation:* stress varies.")
        assertEquals("savanna; bedsheet", translation)
        assertEquals("*Note on pronunciation:* stress varies.", note)
    }

    @Test fun `a translation bullet naming 'note' without a colon does not split`() {
        val text = "melody\n* **Note** (music)"
        val (translation, note) = BackNoteSplitter.split(text)
        assertEquals(text, translation)
        assertNull(note)
    }

    @Test fun `re-splitting a recombined result is idempotent`() {
        val blob = "**Translation:**\n* **Crucible**\n\n**Usage Note:**\nA severe test."
        val (b1, n1) = BackNoteSplitter.split(blob)
        val (b2, n2) = BackNoteSplitter.split(b1 + "\n\n" + n1)
        assertEquals(b1, b2)
        assertEquals(n1, n2)
    }

    @Test fun `empty input stays empty`() {
        val (translation, note) = BackNoteSplitter.split("   ")
        assertEquals("   ", translation)
        assertNull(note)
    }
}

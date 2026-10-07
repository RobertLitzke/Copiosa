package com.copiosa.data.epub

import com.copiosa.domain.prompt.BookSectionAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule under test is "structure from the original, content from whichever text is shown", and
 * the reason it exists is [`a translated list item comes back with its indent trimmed off`] — a
 * code path, not a prompt-following problem.
 */
class BlockMarkupTest {

    @Test
    fun `list depth comes from the indent`() {
        assertEquals(0, listMarkupOf("- Top")!!.depth)
        assertEquals(1, listMarkupOf("  - Nested")!!.depth)
        assertEquals(2, listMarkupOf("    - Deeper")!!.depth)
    }

    @Test
    fun `ordered items carry their ordinal, bullets do not`() {
        val ordered = listMarkupOf("  3. Third")!!
        assertEquals(1, ordered.depth)
        assertEquals(true, ordered.ordered)
        assertEquals(3, ordered.ordinal)
        assertEquals(false, listMarkupOf("- Bullet")!!.ordered)
    }

    @Test
    fun `ordinary prose is not a list item`() {
        assertNull(listMarkupOf("It was cold and dark."))
        assertNull(listMarkupOf("1980. A year to remember."))
        assertNull(listMarkupOf(""))
    }

    @Test
    fun `list bodies drop the marker whichever text they came from`() {
        assertEquals("Top", listItemBody("- Top"))
        assertEquals("Third", listItemBody("  3. Third"))
        // The translated copy has already lost its indent; the body is still the body.
        assertEquals("Tercero", listItemBody("3. Tercero"))
        assertEquals("Sin marcador", listItemBody("Sin marcador"))
    }

    @Test
    fun `a translated list item comes back with its indent trimmed off`() {
        // This is the whole reason structure is read from originalText. BookSectionAligner trims
        // every body it parses, so the indent that encodes nesting depth cannot survive in
        // translatedText — read depth off the translation and every nested list flattens.
        val original = "  - Un elemento anidado"
        val reply = "[[P4]]\n  - A nested item"
        val translated = BookSectionAligner.parse(reply, listOf(4)).byOrd.getValue(4)

        assertEquals("A nested item", listItemBody(translated))
        assertNull(listMarkupOf(translated).takeIf { it != null && it.depth == 1 })
        // …but the original still knows, which is what every renderer and the exporter read.
        assertEquals(1, listMarkupOf(original)!!.depth)
    }

    @Test
    fun `note labels and bodies survive a lost prefix`() {
        assertEquals("3", noteLabelOf("[^3]: The chief of police."))
        assertEquals("The chief of police.", noteBodyOf("[^3]: The chief of police."))
        assertNull(noteLabelOf("Just a paragraph."))
        // The model dropped the prefix on the way back; the body is still readable.
        assertEquals("El jefe de policía.", noteBodyOf("El jefe de policía."))
    }

    @Test
    fun `image keys are read out of the markdown`() {
        assertEquals("2.jpg", imageKeyOf("![](2.jpg)"))
        assertEquals("2.jpg", imageKeyOf("![some alt](2.jpg)"))
        assertNull(imageKeyOf("An ordinary paragraph."))
        assertNull(imageKeyOf("![]()"))
    }
}

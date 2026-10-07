package com.copiosa.data.epub

import com.copiosa.ui.components.stripInlineMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [inlineXhtml] is the third mirror of the reader's markdown tokenizer, so most of these assert the
 * two properties that keep it honest: it renders what the reader renders, and it escapes each
 * literal run exactly once without ever escaping its own tags.
 */
class MarkdownXhtmlTest {

    @Test
    fun `escapes xml specials exactly once`() {
        assertEquals("&lt;b&gt; &amp; done", inlineXhtml("<b> & done"))
        // The classic double-escape regression would give &amp;amp; here.
        assertEquals("A &amp; B", inlineXhtml("A & B"))
    }

    @Test
    fun `renders the five emphasis markers`() {
        assertEquals("<strong>bold</strong>", inlineXhtml("**bold**"))
        assertEquals("<strong>bold</strong>", inlineXhtml("__bold__"))
        assertEquals("<em>it</em>", inlineXhtml("*it*"))
        assertEquals("<em>it</em>", inlineXhtml("_it_"))
        assertEquals("<code>x</code>", inlineXhtml("`x`"))
    }

    @Test
    fun `underscores inside a word are literal`() {
        assertEquals("snake_case_name", inlineXhtml("snake_case_name"))
    }

    @Test
    fun `emphasis does not nest, exactly as the reader renders it`() {
        // The reader appends a bold run's content verbatim, so the inner markers show through.
        assertEquals("<strong>a *b* c</strong>", inlineXhtml("**a *b* c**"))
    }

    @Test
    fun `an unterminated marker is literal and the rest of the line survives`() {
        assertEquals("a * b", inlineXhtml("a * b"))
        assertEquals("half **way", inlineXhtml("half **way"))
    }

    @Test
    fun `specials inside emphasis are escaped but the tags are not`() {
        assertEquals("<strong>a &amp; &lt;b&gt;</strong>", inlineXhtml("**a & <b>**"))
    }

    @Test
    fun `backslash escapes the next character`() {
        assertEquals("*not italic*", inlineXhtml("\\*not italic\\*"))
        assertEquals("&amp;", inlineXhtml("\\&"))
    }

    @Test
    fun `control characters are dropped but tab, newline and return survive`() {
        // XML 1.0 forbids these outright, and "&#0;" is illegal too, so dropping is the only
        // option — one of them anywhere would otherwise make the whole file unparseable.
        val bell = 7.toChar()
        val nul = 0.toChar()
        assertEquals("ab", xmlText("a${bell}b"))
        assertEquals("ab", xmlText("a${nul}b"))
        assertEquals("a\tb\nc\rd", xmlText("a\tb\nc\rd"))
        // And they are dropped on the markdown path too, not just the bare escaper.
        assertEquals("<strong>ab</strong>", inlineXhtml("**a${bell}b**"))
    }

    @Test
    fun `attribute escaping covers quotes`() {
        assertEquals("a &quot;b&quot; &apos;c&apos; &amp;", xmlAttr("a \"b\" 'c' &"))
    }

    @Test
    fun `heading level comes from the hash count`() {
        assertEquals("<h2>X</h2>", headingXhtml("## X"))
        assertEquals("<h1>X</h1>", headingXhtml("# X"))
        assertEquals("<h6>X</h6>", headingXhtml("####### X"))
        // EpubParser writes chapter titles as "## …"; a row without hashes still gets a heading.
        assertEquals("<h2>X</h2>", headingXhtml("X"))
        assertEquals("<h2><strong>X</strong></h2>", headingXhtml("## **X**"))
    }

    @Test
    fun `heading attributes are placed on the open tag only`() {
        assertEquals("<h2 class=\"untranslated\">X</h2>", headingXhtml("## X", " class=\"untranslated\""))
    }

    /**
     * The property that matters most: whatever markup [inlineXhtml] produces, the words a reader
     * ends up seeing are the same words the app's own renderer would have shown.
     */
    @Test
    fun `rendered text matches what the reader strips to`() {
        val corpus = listOf(
            "plain text",
            "**bold** and *italic*",
            "__bold__ and _italic_",
            "snake_case survives",
            "`code` span",
            "**a *b* c**",
            "half **way",
            "trailing *",
            "\\*escaped\\*",
            "ampersand & angle < bracket >",
            "**a & b**",
            "el/la_x boundary",
            "*",
            "**",
            "",
            "mixed **bold** `code` *it* text",
            "quote \"double\" and 'single'",
            "ends with **",
            "_leading underscore",
            "multi  spaces   kept",
        )
        for (source in corpus) {
            assertEquals(
                "text content diverged for: <$source>",
                stripInlineMarkdown(source),
                textOf(inlineXhtml(source)),
            )
        }
    }

    @Test
    fun `a footnote marker becomes a noteref when its body is being written`() {
        assertEquals(
            "A remark<a class=\"noteref\" epub:type=\"noteref\" href=\"#note-1\"><sup>1</sup></a> here.",
            inlineXhtml("A remark[^1] here.", notes = setOf("1")),
        )
    }

    @Test
    fun `a marker with no matching note stays literal text`() {
        // An <a> pointing at a fragment the file does not contain is an epubcheck error, and a
        // marker the model invented is exactly how one would arrive here.
        assertEquals("A remark[^9] here.", inlineXhtml("A remark[^9] here.", notes = setOf("1")))
        assertEquals("A remark[^1] here.", inlineXhtml("A remark[^1] here."))
    }

    @Test
    fun `a bare bracket is not a footnote marker`() {
        assertEquals("He said [sic] plainly.", inlineXhtml("He said [sic] plainly.", notes = setOf("1")))
        assertEquals("Caret ^ alone.", inlineXhtml("Caret ^ alone."))
    }

    @Test
    fun `a marker is stripped rather than left as a bare label`() {
        // stripInlineMarkdown feeds vocabulary card fields, which must read as plain words.
        assertEquals("A remark here.", stripInlineMarkdown("A remark[^1] here."))
    }
}

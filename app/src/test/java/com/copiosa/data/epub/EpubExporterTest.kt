package com.copiosa.data.epub

import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.BookDownloadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers what the exported book *says*: which language, which chapters, which metadata. */
class EpubExporterTest {

    private val models = listOf("openai/gpt-4o-mini", "google/gemini-2.5-flash")

    private fun build(
        book: com.copiosa.data.local.BookEntity = book(),
        paragraphs: List<com.copiosa.data.local.BookParagraphEntity>,
        variant: EpubExportVariant = EpubExportVariant.TARGET_ONLY,
        models: List<String> = listOf("openai/gpt-4o-mini"),
        cover: EpubImage? = null,
        now: Long = 1_770_000_000_000,
        images: List<EpubImage> = emptyList(),
    ) = EpubExporter.build(book, paragraphs, variant, models, strings(), cover, now, images)

    // --- direction ----------------------------------------------------------

    @Test
    fun `a forward book exports its translation`() {
        val doc = build(
            paragraphs = listOf(
                para(0, 0, "## Chapter One", "## Capitulo Uno", heading = true),
                para(1, 0, "It was cold.", "Hacia frio."),
            ),
        )
        val body = doc.chapters.single().xhtml
        assertTrue(body.contains("Hacia frio."))
        assertFalse(body.contains("It was cold."))
        assertEquals("Capitulo Uno", doc.chapters.single().navLabel)
    }

    /**
     * The test that proves the direction helpers are actually used: a reverse book's target text is
     * its *original* column, so it exports completely with every translation still null.
     */
    @Test
    fun `a reverse book exports its own text with no translation at all`() {
        val doc = build(
            book = book(direction = BookDirection.REVERSE, downloadMode = BookDownloadMode.OFF),
            paragraphs = listOf(
                para(0, 0, "## Capitulo Uno", heading = true),
                para(1, 0, "Hacia frio."),
            ),
            models = emptyList(),
        )
        val body = doc.chapters.single().xhtml
        assertTrue(body.contains("Hacia frio."))
        assertEquals(0, doc.summary.untranslated)
        assertFalse(body.contains("untranslated"))
    }

    // --- variants -----------------------------------------------------------

    @Test
    fun `bilingual pairs each paragraph target-first with a native gloss`() {
        val doc = build(
            paragraphs = listOf(para(0, 0, "It was cold.", "Hacia frio.")),
            variant = EpubExportVariant.BILINGUAL,
        )
        val body = doc.chapters.single().xhtml
        assertTrue(body.contains("<p>Hacia frio.</p>"))
        assertTrue(body.contains("<p class=\"gloss\" xml:lang=\"en\" lang=\"en\">It was cold.</p>"))
        assertTrue(body.indexOf("Hacia frio.") < body.indexOf("It was cold."))
        assertEquals(listOf("es", "en"), doc.languages)
    }

    @Test
    fun `target-only carries a single language`() {
        val doc = build(paragraphs = listOf(para(0, 0, "It was cold.", "Hacia frio.")))
        assertEquals(listOf("es"), doc.languages)
    }

    // --- alignment misses ---------------------------------------------------

    @Test
    fun `an untranslated paragraph falls back to the other language rather than vanishing`() {
        val doc = build(
            paragraphs = listOf(
                para(0, 0, "It was cold.", "Hacia frio."),
                para(1, 0, "The wind rose."),
            ),
        )
        val body = doc.chapters.single().xhtml
        assertTrue(body.contains("<p class=\"untranslated\" xml:lang=\"en\" lang=\"en\">The wind rose.</p>"))
        assertEquals(2, doc.summary.paragraphs)
        assertEquals(1, doc.summary.untranslated)
        assertEquals("en", doc.summary.nativeLanguage)
    }

    @Test
    fun `a row with nothing on either side is skipped rather than emitted empty`() {
        val doc = build(
            paragraphs = listOf(
                para(0, 0, "Hacia frio.", "Hacia frio."),
                para(1, 0, "   ", "  "),
            ),
        )
        assertEquals(1, doc.summary.paragraphs)
        assertFalse(doc.chapters.single().xhtml.contains("<p></p>"))
    }

    // --- chapters -----------------------------------------------------------

    @Test
    fun `chapters group by chapterIndex in order with dense 1-based hrefs`() {
        val doc = build(
            paragraphs = listOf(
                para(2, 1, "b", "b"),
                para(0, 0, "a", "a"),
                para(3, 5, "c", "c"),
            ),
        )
        assertEquals(3, doc.chapters.size)
        assertEquals(
            listOf("text/chapter-001.xhtml", "text/chapter-002.xhtml", "text/chapter-003.xhtml"),
            doc.chapters.map { it.href },
        )
        assertEquals(listOf("chap-001", "chap-002", "chap-003"), doc.chapters.map { it.id })
    }

    @Test
    fun `a chapter with no heading falls back to a numbered label and emits no heading`() {
        val doc = build(paragraphs = listOf(para(0, 0, "It was cold.", "Hacia frio.")))
        assertEquals("Chapter 1", doc.chapters.single().navLabel)
        assertFalse(doc.chapters.single().xhtml.contains("<h2"))
    }

    @Test
    fun `a heading made only of punctuation still gets a usable nav label`() {
        // chapterTitleOf strips the hashes and emphasis and is left with nothing; an <a> with no
        // text would be invalid nav.
        val doc = build(
            paragraphs = listOf(
                para(0, 0, "## **", "## **", heading = true),
                para(1, 0, "It was cold.", "Hacia frio."),
            ),
        )
        assertEquals("Chapter 1", doc.chapters.single().navLabel)
    }

    @Test
    fun `the stored heading row is rendered once, in place`() {
        val doc = build(
            paragraphs = listOf(
                para(0, 0, "## Chapter One", "## Capitulo Uno", heading = true),
                para(1, 0, "It was cold.", "Hacia frio."),
            ),
        )
        // Emitting a second, title-derived heading would break EpubParser's dedupe on re-import.
        assertEquals(1, Regex("<h2").findAll(doc.chapters.single().xhtml).count())
    }

    @Test
    fun `an empty book has nothing to write`() {
        val thrown = runCatching { build(paragraphs = emptyList()) }.exceptionOrNull()
        assertTrue(thrown is EpubExporter.NothingToExportException)
    }

    // --- metadata -----------------------------------------------------------

    @Test
    fun `the identifier is a stable uuid, per variant`() {
        val rows = listOf(para(0, 0, "a", "a"))
        val first = build(paragraphs = rows, now = 1).identifier
        val later = build(paragraphs = rows, now = 999_999_999).identifier
        val bilingual = build(paragraphs = rows, variant = EpubExportVariant.BILINGUAL).identifier

        assertEquals(first, later)
        assertTrue(first.startsWith("urn:uuid:"))
        assertTrue(first != bilingual)
    }

    @Test
    fun `dcterms modified has no fractional seconds`() {
        val doc = build(paragraphs = listOf(para(0, 0, "a", "a")), now = 1_770_000_000_123)
        assertTrue(
            doc.modifiedUtc,
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$").matches(doc.modifiedUtc),
        )
    }

    @Test
    fun `every model is named as a translator, most-used first`() {
        val doc = build(paragraphs = listOf(para(0, 0, "a", "a")), models = models)
        assertEquals(models, doc.translators)
        assertEquals(
            models,
            doc.customMeta.filter { it.first == "copiosa:translation-model" }.map { it.second },
        )
        assertEquals("true", doc.customMeta.single { it.first == "copiosa:machine-translated" }.second)
    }

    @Test
    fun `no models means no translator is claimed`() {
        val doc = build(paragraphs = listOf(para(0, 0, "a", "a")), models = emptyList())
        assertTrue(doc.translators.isEmpty())
        assertEquals("false", doc.customMeta.single { it.first == "copiosa:machine-translated" }.second)
        assertTrue(doc.customMeta.none { it.first == "copiosa:translation-provider" })
        assertTrue(doc.customMeta.none { it.first == "copiosa:translated-from" })
    }

    @Test
    fun `the translation direction follows which column the model wrote`() {
        val forward = build(paragraphs = listOf(para(0, 0, "a", "a")))
        assertEquals("en", forward.customMeta.single { it.first == "copiosa:translated-from" }.second)
        assertEquals("es", forward.customMeta.single { it.first == "copiosa:translated-into" }.second)

        // On a reverse book the LLM writes the native gloss, so the arrow points the other way.
        val reverse = build(
            book = book(direction = BookDirection.REVERSE),
            paragraphs = listOf(para(0, 0, "a", "a")),
        )
        assertEquals("es", reverse.customMeta.single { it.first == "copiosa:translated-from" }.second)
        assertEquals("en", reverse.customMeta.single { it.first == "copiosa:translated-into" }.second)
    }

    @Test
    fun `a book that was never graded claims no level`() {
        val doc = build(
            book = book(direction = BookDirection.REVERSE, downloadMode = BookDownloadMode.OFF),
            paragraphs = listOf(para(0, 0, "a", "a")),
        )
        assertTrue(doc.customMeta.none { it.first == "copiosa:cefr-level" })
    }

    @Test
    fun `an anonymous book has no author`() {
        val doc = build(book = book(author = null), paragraphs = listOf(para(0, 0, "a", "a")))
        assertNull(doc.author)
    }

    @Test
    fun `a nested list is emitted inside its parent item, not beside it`() {
        val xhtml = build(
            paragraphs = listOf(
                para(0, 0, "- Top", "- Top", kind = BookParagraphEntity.KIND_LIST),
                para(1, 0, "  - Child", "  - Child", kind = BookParagraphEntity.KIND_LIST),
                para(2, 0, "- Second", "- Second", kind = BookParagraphEntity.KIND_LIST),
            ),
        ).chapters[0].xhtml.replace("\n", "")

        // <ul> as a sibling of <li> would be invalid XHTML, and epubcheck rejects the whole file.
        assertTrue(xhtml.contains("<ul><li><p>Top</p><ul><li><p>Child</p></li></ul></li><li><p>Second</p></li></ul>"))
    }

    @Test
    fun `an ordered list keeps its element type`() {
        val xhtml = build(
            paragraphs = listOf(
                para(0, 0, "1. First", "1. Primero", kind = BookParagraphEntity.KIND_LIST),
                para(1, 0, "2. Second", "2. Segundo", kind = BookParagraphEntity.KIND_LIST),
            ),
        ).chapters[0].xhtml.replace("\n", "")

        assertTrue(xhtml.contains("<ol><li><p>Primero</p></li><li><p>Segundo</p></li></ol>"))
        // The marker itself must not survive into the text, or it compounds on every export cycle.
        assertFalse(xhtml.contains("1. Primero"))
    }

    @Test
    fun `a list run is closed before the paragraph that follows it`() {
        val xhtml = build(
            paragraphs = listOf(
                para(0, 0, "- Item", "- Item", kind = BookParagraphEntity.KIND_LIST),
                para(1, 0, "After the list.", "After the list."),
            ),
        ).chapters[0].xhtml

        assertTrue(xhtml.contains("</li></ul>"))
        assertTrue(xhtml.indexOf("</ul>") < xhtml.indexOf("<p>After the list.</p>"))
    }

    @Test
    fun `a table that no longer parses degrades to a paragraph`() {
        val xhtml = build(
            paragraphs = listOf(
                para(
                    0, 0,
                    "| a | b |\n| --- | --- |\n| 1 | 2 |",
                    "The model turned this back into prose.",
                    kind = BookParagraphEntity.KIND_TABLE,
                ),
            ),
        ).chapters[0].xhtml

        assertFalse(xhtml.contains("<table"))
        assertTrue(xhtml.contains("<p>The model turned this back into prose.</p>"))
    }

    @Test
    fun `a table's first row becomes header cells`() {
        val xhtml = build(
            paragraphs = listOf(
                para(
                    0, 0,
                    "| Person | Verb |\n| --- | --- |\n| yo | hablo |",
                    "| Persona | Verbo |\n| --- | --- |\n| yo | hablo |",
                    kind = BookParagraphEntity.KIND_TABLE,
                ),
            ),
        ).chapters[0].xhtml

        assertTrue(xhtml.contains("<tr><th>Persona</th><th>Verbo</th></tr>"))
        assertTrue(xhtml.contains("<tr><td>yo</td><td>hablo</td></tr>"))
    }

    @Test
    fun `an image with no stored bytes is left out rather than pointing at nothing`() {
        val doc = build(
            paragraphs = listOf(
                para(0, 0, "Text.", "Texto."),
                para(1, 0, "![](9.jpg)", "![](9.jpg)", kind = BookParagraphEntity.KIND_IMAGE),
            ),
            images = emptyList(),
        )

        assertFalse(doc.chapters[0].xhtml.contains("<img"))
        // …and it does not inflate the paragraph count the result dialog reports.
        assertEquals(1, doc.summary.paragraphs)
    }

    @Test
    fun `only referenced images reach the manifest`() {
        val doc = build(
            paragraphs = listOf(para(0, 0, "![](1.jpg)", "![](1.jpg)", kind = BookParagraphEntity.KIND_IMAGE)),
            images = listOf(bodyImage("1.jpg"), bodyImage("2.jpg")),
        )

        assertEquals(listOf("images/1.jpg"), doc.images.map { it.href })
    }

    @Test
    fun `the original title is recorded only when it differs`() {
        assertEquals("The Hobbit", build(paragraphs = listOf(para(0, 0, "a", "a"))).source)
        val same = build(
            book = book(title = "The Hobbit", originalTitle = "The Hobbit"),
            paragraphs = listOf(para(0, 0, "a", "a")),
        )
        assertNull(same.source)
    }
}

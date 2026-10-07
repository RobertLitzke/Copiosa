package com.copiosa.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exercises [EpubParser] against fixture epubs built in-test: a Standard-Ebooks-shaped one
 * (epub:type front/backmatter, hgroup titles, inline em/strong, cover-image manifest property)
 * and degenerate/generic shapes for the fallback paths.
 */
class EpubParserTest {

    @Test
    fun `parses a standard-ebooks-shaped epub`() {
        val book = parse(standardEbook())

        assertEquals("The Tower Treasure", book.title)
        assertEquals("Franklin W. Dixon", book.author)
        // Titlepage (frontmatter) and colophon (backmatter) are skipped; 2 chapters remain.
        assertEquals(2, book.chapters.size)
        assertNotNull(book.coverBytes)
        assertEquals(FAKE_JPEG.size, book.coverBytes!!.size)
    }

    @Test
    fun `chapter titles come from the hgroup and lead as markdown headings`() {
        val book = parse(standardEbook())

        val first = book.chapters[0]
        assertEquals("I: The Speed Demon", first.title)
        assertEquals(BlockKind.HEADING, first.blocks[0].kind)
        assertEquals("## I: The Speed Demon", first.blocks[0].text)
        // The hgroup's own h2/p never re-emit as body blocks.
        assertEquals(1, first.blocks.count { it.kind == BlockKind.HEADING })
    }

    @Test
    fun `inline emphasis survives as markdown`() {
        val book = parse(standardEbook())

        val paragraphs = book.chapters[0].blocks.filter { it.kind == BlockKind.PARA }.map { it.text }
        assertTrue(paragraphs.any { it.contains("we *could* be detectives") })
        assertTrue(paragraphs.any { it.contains("**Bayport** high school") })
    }

    @Test
    fun `spine order is preserved`() {
        val book = parse(standardEbook())
        assertEquals("I: The Speed Demon", book.chapters[0].title)
        assertEquals("II: The Stolen Roadster", book.chapters[1].title)
    }

    @Test
    fun `a generic epub without epub-type falls back to filename skipping`() {
        val book = parse(
            epub(
                spine = listOf(
                    "cover.xhtml" to "<html><body><p>Cover page</p></body></html>",
                    "toc.xhtml" to "<html><body><p>Contents</p></body></html>",
                    "ch1.xhtml" to "<html><head><title>One</title></head><body><p>First chapter text.</p></body></html>",
                ),
            ),
        )

        assertEquals(1, book.chapters.size)
        assertEquals("One", book.chapters[0].title)
        assertNull(book.coverBytes)
    }

    @Test
    fun `if the skip rules would discard everything, every spine doc is kept`() {
        val book = parse(
            epub(
                spine = listOf(
                    "cover.xhtml" to "<html><body><p>Actually the whole book lives here.</p></body></html>",
                ),
            ),
        )

        assertEquals(1, book.chapters.size)
        assertTrue(book.chapters[0].blocks.any { it.text.contains("whole book") })
    }

    @Test
    fun `a footnote marker with no body anywhere is removed rather than left as a digit`() {
        val book = parse(
            epub(
                spine = listOf(
                    "ch1.xhtml" to """
                        <html><body>
                        <p>Kept text<sup><a href="#note-1">1</a></sup> continues.</p>
                        </body></html>
                    """.trimIndent(),
                ),
            ),
        )

        val texts = book.chapters[0].blocks.map { it.text }
        assertTrue(texts.any { it.contains("Kept text continues.") })
        assertFalse(texts.any { it.contains("[^") })
        assertTrue(book.chapters[0].blocks.none { it.kind == BlockKind.NOTE })
    }

    @Test
    fun `nested lists keep their depth, markers and ordinals`() {
        val book = parse(
            epub(
                spine = listOf(
                    "ch1.xhtml" to """
                        <html><body>
                        <ul>
                          <li>Top item
                            <ol><li>First nested</li><li>Second nested</li></ol>
                          </li>
                          <li>Second top</li>
                        </ul>
                        </body></html>
                    """.trimIndent(),
                ),
            ),
        )

        val items = book.chapters[0].blocks.filter { it.kind == BlockKind.LIST }.map { it.text }
        assertEquals(
            listOf("- Top item", "  1. First nested", "  2. Second nested", "- Second top"),
            items,
        )
    }

    @Test
    fun `a nested list is not swallowed into its parent item's text`() {
        val book = parse(
            epub(
                spine = listOf(
                    "ch1.xhtml" to
                        """<html><body><ul><li>Parent<ul><li>Child</li></ul></li></ul></body></html>""",
                ),
            ),
        )

        val items = book.chapters[0].blocks.filter { it.kind == BlockKind.LIST }.map { it.text }
        // The regression this guards: renderInline used to descend into the nested <ul>, so the
        // parent item read "Parent Child" and the child never became a row of its own.
        assertEquals(listOf("- Parent", "  - Child"), items)
    }

    @Test
    fun `a table becomes one pipe-table block`() {
        val book = parse(
            epub(
                spine = listOf(
                    "ch1.xhtml" to """
                        <html><body><table>
                        <thead><tr><th>Person</th><th>Verb</th></tr></thead>
                        <tbody><tr><td>yo</td><td>hablo</td></tr><tr><td>tú</td><td>hablas</td></tr></tbody>
                        </table></body></html>
                    """.trimIndent(),
                ),
            ),
        )

        val tables = book.chapters[0].blocks.filter { it.kind == BlockKind.TABLE }
        assertEquals(1, tables.size)
        assertEquals(
            """
                | Person | Verb |
                | --- | --- |
                | yo | hablo |
                | tú | hablas |
            """.trimIndent(),
            tables[0].text,
        )
    }

    @Test
    fun `a figure yields an image block and a caption paragraph`() {
        val seen = mutableListOf<String>()
        val book = EpubParser().parse(
            epub(
                spine = listOf(
                    "ch1.xhtml" to
                        """<html><body><figure><img src="../images/plate.jpg"/>
                           <figcaption>A picture</figcaption></figure></body></html>""",
                ),
                extraImage = "plate.jpg",
            ),
        ) { name, bytes -> seen.add("$name:${bytes.size}") }

        val blocks = book.chapters[0].blocks.filterNot { it.kind == BlockKind.HEADING }
        assertEquals(BlockKind.IMAGE, blocks[0].kind)
        assertEquals("![](1.jpg)", blocks[0].text)
        // The caption is a normal paragraph, so it goes through translation like any other prose.
        assertEquals(BlockKind.PARA, blocks[1].kind)
        assertEquals("A picture", blocks[1].text)
        assertEquals(listOf("1.jpg:${FAKE_JPEG.size}"), seen)
    }

    @Test
    fun `an image whose bytes are missing produces no image block`() {
        val book = parse(
            epub(
                spine = listOf(
                    "ch1.xhtml" to """<html><body><p>Text.</p><figure><img src="gone.png"/></figure></body></html>""",
                ),
            ),
        )
        assertTrue(book.chapters[0].blocks.none { it.kind == BlockKind.IMAGE })
    }

    @Test
    fun `an inline aside footnote becomes a marker plus a note row at the end of the chapter`() {
        val book = parse(
            epub(
                spine = listOf(
                    "ch1.xhtml" to """
                        <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                        <p>Chief Collig<a epub:type="noteref" href="#fn1">a</a> arrived.</p>
                        <p>And then he left.</p>
                        <aside epub:type="footnote" id="fn1"><p>The chief of police.</p></aside>
                        </body></html>
                    """.trimIndent(),
                ),
            ),
        )

        val blocks = book.chapters[0].blocks
        assertTrue(blocks.any { it.kind == BlockKind.PARA && it.text == "Chief Collig[^1] arrived." })
        // The body is parked last, after every paragraph — not where the <aside> sat.
        assertEquals(BlockKind.NOTE, blocks.last().kind)
        assertEquals("[^1]: The chief of police.", blocks.last().text)
    }

    @Test
    fun `endnotes in a backmatter document attach to the chapter that referenced them`() {
        val book = parse(
            epub(
                spine = listOf(
                    "chapter-1.xhtml" to """
                        <html xmlns:epub="http://www.idpf.org/2007/ops">
                        <head><title>One</title></head>
                        <body epub:type="bodymatter"><section epub:type="chapter">
                        <p>A remark<a epub:type="noteref" href="endnotes.xhtml#note-7">7</a> here.</p>
                        </section></body></html>
                    """.trimIndent(),
                    "chapter-2.xhtml" to """
                        <html xmlns:epub="http://www.idpf.org/2007/ops">
                        <head><title>Two</title></head>
                        <body epub:type="bodymatter"><section epub:type="chapter">
                        <p>Another<a epub:type="noteref" href="endnotes.xhtml#note-9">9</a> one.</p>
                        </section></body></html>
                    """.trimIndent(),
                    "endnotes.xhtml" to """
                        <html xmlns:epub="http://www.idpf.org/2007/ops">
                        <body epub:type="backmatter"><section epub:type="endnotes"><ol>
                        <li id="note-7" epub:type="endnote"><p>Note seven's body.
                          <a href="chapter-1.xhtml#noteref-7" epub:type="backlink">↩</a></p></li>
                        <li id="note-9" epub:type="endnote"><p>Note nine's body.
                          <a href="chapter-2.xhtml#noteref-9" epub:type="backlink">↩</a></p></li>
                        </ol></section></body></html>
                    """.trimIndent(),
                ),
            ),
        )

        // The endnotes document is mined, not read as a third chapter.
        assertEquals(2, book.chapters.size)
        // Labels restart at 1 per chapter, which is what makes (chapter, label) identify one note.
        assertTrue(book.chapters[0].blocks.any { it.text == "A remark[^1] here." })
        assertTrue(book.chapters[1].blocks.any { it.text == "Another[^1] one." })
        assertEquals("[^1]: Note seven's body.", book.chapters[0].blocks.last().text)
        assertEquals("[^1]: Note nine's body.", book.chapters[1].blocks.last().text)
        // The "back to the text" link is plumbing and must not survive into the body.
        assertTrue(book.chapters.none { c -> c.blocks.any { it.text.contains("↩") } })
    }

    @Test
    fun `a non-epub zip fails with a friendly message`() {
        val file = File(tempDir(), "not.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("random.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        val failure = runCatching { EpubParser().parse(file) }
        assertTrue(failure.exceptionOrNull() is EpubParseException)
    }

    @Test
    fun `a non-zip file fails with a friendly message`() {
        val file = File(tempDir(), "not.epub").apply { writeText("plain text") }
        val failure = runCatching { EpubParser().parse(file) }
        assertTrue(failure.exceptionOrNull() is EpubParseException)
    }

    // --- fixture builders ---------------------------------------------------

    private fun parse(file: File): EpubBook = EpubParser().parse(file)

    /** Standard Ebooks shape: epub:type semantics, hgroup titles, cover-image property. */
    private fun standardEbook(): File = epub(
        spine = listOf(
            "titlepage.xhtml" to
                """<html><body epub:type="frontmatter titlepage"><p>Title page art</p></body></html>""",
            "chapter-1.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>I: The Speed Demon</title></head>
                <body epub:type="bodymatter z3998:fiction">
                <section id="chapter-1" epub:type="chapter">
                <hgroup><h2 epub:type="ordinal z3998:roman">I</h2><p epub:type="title">The Speed Demon</p></hgroup>
                <p>I guess he'll begin to think we <em>could</em> be detectives.</p>
                <p>They were enjoying a holiday from the <strong>Bayport</strong> high school.</p>
                </section>
                </body></html>
            """.trimIndent(),
            "chapter-2.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>II: The Stolen Roadster</title></head>
                <body epub:type="bodymatter z3998:fiction">
                <section id="chapter-2" epub:type="chapter">
                <hgroup><h2>II</h2><p epub:type="title">The Stolen Roadster</p></hgroup>
                <p>The roadster was gone.</p>
                </section>
                </body></html>
            """.trimIndent(),
            "colophon.xhtml" to
                """<html><body epub:type="backmatter colophon"><p>Colophon</p></body></html>""",
        ),
        coverImage = true,
    )

    /**
     * Builds a minimal valid epub in a temp dir. [spine] is (filename -> xhtml) in reading order;
     * [coverImage] adds images/cover.jpg with `properties="cover-image"`.
     */
    private fun epub(
        spine: List<Pair<String, String>>,
        coverImage: Boolean = false,
        /** A body image written to epub/images/<name>, for the figure/img paths. */
        extraImage: String? = null,
    ): File {
        val file = File(tempDir(), "fixture.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                    <rootfiles><rootfile full-path="epub/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                """.trimIndent().toByteArray(),
            )
            val manifest = buildString {
                spine.forEachIndexed { i, (name, _) ->
                    append("""<item id="item$i" href="text/$name" media-type="application/xhtml+xml"/>""")
                }
                if (coverImage) {
                    append("""<item id="cover-img" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""")
                }
                if (extraImage != null) {
                    append("""<item id="body-img" href="images/$extraImage" media-type="image/jpeg"/>""")
                }
            }
            val spineRefs = spine.indices.joinToString("") { """<itemref idref="item$it"/>""" }
            entry(
                "epub/content.opf",
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">test</dc:identifier>
                    <dc:title>The Tower Treasure</dc:title>
                    <dc:creator>Franklin W. Dixon</dc:creator>
                    </metadata>
                    <manifest>$manifest</manifest>
                    <spine>$spineRefs</spine>
                    </package>
                """.trimIndent().toByteArray(),
            )
            spine.forEach { (name, xhtml) -> entry("epub/text/$name", xhtml.toByteArray()) }
            if (coverImage) entry("epub/images/cover.jpg", FAKE_JPEG)
            if (extraImage != null) entry("epub/images/$extraImage", FAKE_JPEG)
        }
        return file
    }

    private fun tempDir(): File = Files.createTempDirectory("epub_parser_test").toFile().apply { deleteOnExit() }

    private companion object {
        val FAKE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    }
}

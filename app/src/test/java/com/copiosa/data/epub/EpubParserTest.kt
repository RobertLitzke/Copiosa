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
    fun `images figures and footnote markers are dropped`() {
        val book = parse(
            epub(
                spine = listOf(
                    "ch1.xhtml" to """
                        <html><body>
                        <figure><img src="pic.png"/><figcaption>A picture</figcaption></figure>
                        <p>Kept text<sup><a href="#note-1">1</a></sup> continues.</p>
                        </body></html>
                    """.trimIndent(),
                ),
            ),
        )

        val texts = book.chapters[0].blocks.map { it.text }
        assertTrue(texts.any { it.contains("Kept text continues.") })
        assertFalse(texts.any { it.contains("A picture") })
        assertFalse(texts.any { it.contains("1") && it.length < 4 })
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
    private fun epub(spine: List<Pair<String, String>>, coverImage: Boolean = false): File {
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
        }
        return file
    }

    private fun tempDir(): File = Files.createTempDirectory("epub_parser_test").toFile().apply { deleteOnExit() }

    private companion object {
        val FAKE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    }
}

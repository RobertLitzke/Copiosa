package com.copiosa.data.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Covers what a *valid EPUB* looks like, independently of what the book says. Most of this is the
 * container's fine print — the parts that fail epubcheck while some readers still open the file, so
 * nothing here is caught by eyeballing the output.
 */
class EpubPackagerTest {

    private fun doc(cover: EpubImage? = coverImage()) = EpubExporter.build(
        book = book(),
        paragraphs = listOf(
            para(0, 0, "## Chapter One", "## Capitulo Uno", heading = true),
            para(1, 0, "It was cold.", "Hacia frio."),
            para(2, 1, "## Chapter Two", "## Capitulo Dos", heading = true),
            para(3, 1, "The wind rose.", "Se levanto el viento."),
        ),
        variant = EpubExportVariant.TARGET_ONLY,
        models = listOf("openai/gpt-4o-mini"),
        strings = strings(),
        cover = cover,
        now = 1_770_000_000_000,
    )

    private fun pack(document: EpubDocument = doc()): ByteArray =
        ByteArrayOutputStream().also { EpubPackager.pack(it, document) }.toByteArray()

    private fun entries(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                out[entry.name] = stream.readBytes()
            }
        }
        return out
    }

    @Test
    fun `mimetype is the first entry in the archive`() {
        assertEquals("mimetype", entries(pack()).keys.first())
    }

    @Test
    fun `mimetype entry satisfies every clause of the spec`() {
        ZipInputStream(ByteArrayInputStream(pack())).use { stream ->
            val entry = stream.nextEntry ?: error("empty zip")
            val bytes = stream.readBytes()

            assertEquals("mimetype", entry.name)
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals("application/epub+zip", String(bytes, Charsets.US_ASCII))
            assertEquals(20, bytes.size)
            // An extra field (an extended timestamp, say) makes the container non-conforming.
            assertNull(entry.extra)
            assertEquals(CRC32().apply { update(bytes) }.value, entry.crc)
            assertEquals(bytes.size.toLong(), entry.size)
        }
    }

    @Test
    fun `entry names use forward slashes only`() {
        assertTrue(entries(pack()).keys.none { it.contains('\\') })
    }

    @Test
    fun `container points at a package document that exists`() {
        val zip = entries(pack())
        val container = parse(String(zip.getValue("META-INF/container.xml"), Charsets.UTF_8))
        val fullPath = container.getElementsByTagName("rootfile").item(0)
            .attributes.getNamedItem("full-path").nodeValue
        assertTrue("rootfile points at $fullPath", zip.containsKey(fullPath))
    }

    @Test
    fun `every manifest href resolves to a real entry and every spine idref to a manifest item`() {
        val zip = entries(pack())
        val opf = parse(String(zip.getValue("EPUB/package.opf"), Charsets.UTF_8))

        val items = opf.getElementsByTagName("item")
        val ids = mutableSetOf<String>()
        for (i in 0 until items.length) {
            val attrs = items.item(i).attributes
            ids += attrs.getNamedItem("id").nodeValue
            val href = attrs.getNamedItem("href").nodeValue
            assertTrue("manifest href $href has no entry", zip.containsKey("EPUB/$href"))
        }

        val refs = opf.getElementsByTagName("itemref")
        for (i in 0 until refs.length) {
            val idref = refs.item(i).attributes.getNamedItem("idref").nodeValue
            assertTrue("spine idref $idref is not in the manifest", idref in ids)
        }
    }

    @Test
    fun `unique-identifier resolves to the dc identifier`() {
        val zip = entries(pack())
        val opf = parse(String(zip.getValue("EPUB/package.opf"), Charsets.UTF_8))
        val unique = opf.documentElement.getAttribute("unique-identifier")
        val identifier = opf.getElementsByTagName("dc:identifier").item(0)
        // A mismatch here is the classic "Adobe Digital Editions refuses to open it" failure.
        assertEquals(unique, identifier.attributes.getNamedItem("id").nodeValue)
        assertEquals(doc().identifier, identifier.textContent)
    }

    @Test
    fun `the ncx uid matches the package identifier`() {
        val zip = entries(pack())
        val ncx = parse(String(zip.getValue("EPUB/toc.ncx"), Charsets.UTF_8))
        val metas = ncx.getElementsByTagName("meta")
        val uid = (0 until metas.length)
            .map { metas.item(it).attributes }
            .single { it.getNamedItem("name").nodeValue == "dtb:uid" }
            .getNamedItem("content").nodeValue
        assertEquals(doc().identifier, uid)
    }

    @Test
    fun `the cover image round-trips byte for byte and is flagged both ways`() {
        val zip = entries(pack())
        assertArrayEquals(FAKE_JPEG, zip.getValue("EPUB/images/cover.jpg"))

        val opf = String(zip.getValue("EPUB/package.opf"), Charsets.UTF_8)
        assertTrue(opf.contains("properties=\"cover-image\""))
        // The EPUB 2 hint, for older readers and EpubParser's own fallback.
        assertTrue(opf.contains("<meta name=\"cover\" content=\"cover-image\"/>"))
    }

    @Test
    fun `a book with no cover writes neither the page nor the image`() {
        val zip = entries(pack(doc(cover = null)))
        assertTrue(zip.keys.none { it.startsWith("EPUB/images/") })
        assertTrue("EPUB/cover.xhtml" !in zip)
        assertTrue(!String(zip.getValue("EPUB/package.opf"), Charsets.UTF_8).contains("cover-image"))
    }

    /**
     * The broadest safety net in the suite: a missing `xmlns:epub`, an unescaped `&` or a stray
     * control character all show up here as a parse failure rather than as a reader saying nothing.
     * jsoup's XML parser is far too forgiving to serve the purpose.
     */
    @Test
    fun `every xml document in the container is well-formed`() {
        val zip = entries(pack())
        val xml = zip.filterKeys {
            it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".ncx") || it.endsWith(".xml")
        }
        assertTrue("no xml entries found", xml.isNotEmpty())
        xml.forEach { (name, bytes) ->
            runCatching { parse(String(bytes, Charsets.UTF_8)) }
                .onFailure { throw AssertionError("$name is not well-formed XML: ${it.message}", it) }
        }
    }

    @Test
    fun `paragraph text with xml specials survives into a well-formed file`() {
        val document = EpubExporter.build(
            book = book(),
            paragraphs = listOf(para(0, 0, "raw", "Tom & Jerry <not a tag> \"quoted\"")),
            variant = EpubExportVariant.TARGET_ONLY,
            models = emptyList(),
            strings = strings(),
            cover = null,
            now = 0,
        )
        val zip = entries(pack(document))
        val chapter = String(zip.getValue("EPUB/text/chapter-001.xhtml"), Charsets.UTF_8)
        assertEquals(
            "Tom & Jerry <not a tag> \"quoted\"",
            parse(chapter).getElementsByTagName("p").item(0).textContent,
        )
    }

    @Test
    fun `image media types are sniffed from the bytes, not the name`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        assertEquals("image/png", EpubPackager.sniffImageMediaType(png))
        assertEquals("images/cover.png", EpubPackager.coverHref("image/png"))
        assertEquals("image/jpeg", EpubPackager.sniffImageMediaType(FAKE_JPEG))
        assertEquals("images/cover.jpg", EpubPackager.coverHref("image/jpeg"))
    }

    /** Namespace-aware and refusing to fetch a DTD, so malformed input actually throws. */
    private fun parse(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(InputSource(StringReader(xml)))
    }
}

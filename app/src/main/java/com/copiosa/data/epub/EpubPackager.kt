package com.copiosa.data.epub

import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an [EpubDocument] out as an EPUB 3 container — the half of the export that knows what a
 * valid EPUB looks like and nothing about books.
 *
 * `java.util.zip` rather than commons-compress (which the backup export uses): it is what
 * [EpubParser] reads with, so a round-trip test needs no extra machinery, and it is what
 * `ApkgPackager` already writes with. Neither library can avoid setting size and CRC by hand for the
 * stored `mimetype` entry on a non-seekable stream, so there is nothing to choose between them there.
 */
object EpubPackager {

    const val CSS_NAME = "style.css"

    private const val MIMETYPE = "application/epub+zip"
    private const val ROOT = "EPUB/"
    private const val OPF_NAME = "package.opf"
    private const val NAV_NAME = "nav.xhtml"
    private const val NCX_NAME = "toc.ncx"
    private const val COVER_PAGE_NAME = "cover.xhtml"

    private const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
    private const val XHTML_MEDIA_TYPE = "application/xhtml+xml"

    fun pack(out: OutputStream, doc: EpubDocument) {
        ZipOutputStream(out).use { zip ->
            writeMimetype(zip)
            zip.text("META-INF/container.xml", containerXml())
            zip.text(ROOT + OPF_NAME, packageOpf(doc))
            zip.text(ROOT + NAV_NAME, navXhtml(doc))
            zip.text(ROOT + NCX_NAME, tocNcx(doc))
            zip.text(ROOT + CSS_NAME, STYLESHEET)
            doc.cover?.let { cover ->
                zip.text(ROOT + COVER_PAGE_NAME, coverXhtml(doc, cover))
                zip.bytes(ROOT + cover.href, cover.bytes)
            }
            doc.images.forEach { zip.bytes(ROOT + it.href, it.bytes) }
            doc.chapters.forEach { zip.text(ROOT + it.href, it.xhtml) }
        }
    }

    /**
     * The one entry that must be first and uncompressed. Getting any part of this wrong produces a
     * file that some readers still open and epubcheck rejects, so it is not something eyeballing
     * catches — [EpubPackagerTest] asserts every clause of it.
     *
     * A STORED entry on a non-seekable stream carries no data descriptor, so size and CRC have to be
     * known up front; `putNextEntry` throws if either is missing. No timestamp is set, because
     * setting one is what would add an extra field.
     */
    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = MIMETYPE.toByteArray(Charsets.US_ASCII)
        zip.putNextEntry(
            ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            },
        )
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun containerXml() = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        |  <rootfiles>
        |    <rootfile full-path="$ROOT$OPF_NAME" media-type="application/oebps-package+xml"/>
        |  </rootfiles>
        |</container>
        |
    """.trimMargin()

    private fun packageOpf(doc: EpubDocument): String {
        val meta = StringBuilder()
        // unique-identifier must resolve to this element's id. A mismatch is the classic
        // "Adobe Digital Editions refuses to open it" failure.
        meta.append("    <dc:identifier id=\"pub-id\">${xmlText(doc.identifier)}</dc:identifier>\n")
        meta.append("    <dc:title>${xmlText(doc.title)}</dc:title>\n")
        doc.languages.forEach { meta.append("    <dc:language>${xmlText(it)}</dc:language>\n") }
        // An empty dc:creator shows as a blank author row, so omit it rather than emit one.
        doc.author?.let {
            meta.append("    <dc:creator id=\"creator\">${xmlText(it)}</dc:creator>\n")
            meta.append("    <meta refines=\"#creator\" property=\"role\" scheme=\"marc:relators\">aut</meta>\n")
        }
        // dc:contributor + the MARC "trl" relator is what Calibre, Apple Books and Kobo actually
        // surface as a translator — the part of the provenance a human ever sees.
        doc.translators.forEachIndexed { i, model ->
            meta.append("    <dc:contributor id=\"translator-$i\">${xmlText(model)}</dc:contributor>\n")
            meta.append("    <meta refines=\"#translator-$i\" property=\"role\" scheme=\"marc:relators\">trl</meta>\n")
            meta.append("    <meta refines=\"#translator-$i\" property=\"display-seq\">${i + 1}</meta>\n")
        }
        doc.source?.let { meta.append("    <dc:source>${xmlText(it)}</dc:source>\n") }
        if (doc.description.isNotBlank()) {
            meta.append("    <dc:description>${xmlText(doc.description)}</dc:description>\n")
        }
        meta.append("    <meta property=\"dcterms:modified\">${xmlText(doc.modifiedUtc)}</meta>\n")
        doc.customMeta.forEach { (property, value) ->
            meta.append("    <meta property=\"${xmlAttr(property)}\">${xmlText(value)}</meta>\n")
        }
        // The EPUB 2 form. Newer epubcheck warns, but it is what EpubParser.findCover falls back to
        // and what older readers and conversion pipelines look for.
        if (doc.cover != null) meta.append("    <meta name=\"cover\" content=\"cover-image\"/>\n")

        val manifest = StringBuilder()
        manifest.append("    <item id=\"nav\" href=\"$NAV_NAME\" media-type=\"$XHTML_MEDIA_TYPE\" properties=\"nav\"/>\n")
        manifest.append("    <item id=\"ncx\" href=\"$NCX_NAME\" media-type=\"$NCX_MEDIA_TYPE\"/>\n")
        manifest.append("    <item id=\"css\" href=\"$CSS_NAME\" media-type=\"text/css\"/>\n")
        doc.cover?.let {
            manifest.append(
                "    <item id=\"cover-image\" href=\"${xmlAttr(it.href)}\" " +
                    "media-type=\"${xmlAttr(it.mediaType)}\" properties=\"cover-image\"/>\n",
            )
            manifest.append("    <item id=\"cover\" href=\"$COVER_PAGE_NAME\" media-type=\"$XHTML_MEDIA_TYPE\"/>\n")
        }
        // Body images. An image in the zip but not the manifest is an epubcheck error, and an image
        // in the manifest but not the zip is a worse one, so these two loops move together.
        doc.images.forEachIndexed { index, image ->
            manifest.append(
                "    <item id=\"img-${index + 1}\" href=\"${xmlAttr(image.href)}\" " +
                    "media-type=\"${xmlAttr(image.mediaType)}\"/>\n",
            )
        }
        doc.chapters.forEach {
            manifest.append(
                "    <item id=\"${xmlAttr(it.id)}\" href=\"${xmlAttr(it.href)}\" media-type=\"$XHTML_MEDIA_TYPE\"/>\n",
            )
        }

        val spine = StringBuilder()
        if (doc.cover != null) spine.append("    <itemref idref=\"cover\"/>\n")
        doc.chapters.forEach { spine.append("    <itemref idref=\"${xmlAttr(it.id)}\"/>\n") }

        val lang = xmlAttr(doc.languages.first())
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id"
            |         xml:lang="$lang" prefix="copiosa: https://copiosa.app/epub#">
            |  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            |$meta  </metadata>
            |  <manifest>
            |$manifest  </manifest>
            |  <spine toc="ncx">
            |$spine  </spine>
            |</package>
            |
        """.trimMargin()
    }

    /**
     * The nav document is in the manifest with `properties="nav"` but deliberately *not* in the
     * spine: legal, keeps readers from showing a duplicate contents page, and means [EpubParser]
     * never has to decide whether it is a chapter.
     */
    private fun navXhtml(doc: EpubDocument): String {
        val lang = xmlAttr(doc.languages.first())
        val items = doc.chapters.joinToString("") {
            "      <li><a href=\"${xmlAttr(it.href)}\">${xmlText(it.navLabel)}</a></li>\n"
        }
        val landmarks = StringBuilder()
        if (doc.cover != null) {
            landmarks.append(
                "      <li><a epub:type=\"cover\" href=\"$COVER_PAGE_NAME\">${xmlText(doc.coverLabel)}</a></li>\n",
            )
        }
        landmarks.append(
            "      <li><a epub:type=\"bodymatter\" href=\"${xmlAttr(doc.chapters.first().href)}\">" +
                "${xmlText(doc.chapters.first().navLabel)}</a></li>\n",
        )
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!DOCTYPE html>
            |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang" lang="$lang">
            |<head>
            |<meta charset="utf-8"/>
            |<title>${xmlText(doc.navTitle)}</title>
            |<link rel="stylesheet" type="text/css" href="$CSS_NAME"/>
            |</head>
            |<body>
            |  <nav epub:type="toc" id="toc">
            |    <h1>${xmlText(doc.navTitle)}</h1>
            |    <ol>
            |$items    </ol>
            |  </nav>
            |  <nav epub:type="landmarks" hidden="hidden">
            |    <ol>
            |$landmarks    </ol>
            |  </nav>
            |</body>
            |</html>
            |
        """.trimMargin()
    }

    /**
     * Superseded by the nav document, but ~1 KB and still what older Kobo firmware, ADE 2.x and
     * several conversion pipelines read. `dtb:uid` must match the package identifier.
     */
    private fun tocNcx(doc: EpubDocument): String {
        val points = doc.chapters.mapIndexed { i, chapter ->
            """
            |    <navPoint id="navpoint-${i + 1}" playOrder="${i + 1}">
            |      <navLabel><text>${xmlText(chapter.navLabel)}</text></navLabel>
            |      <content src="${xmlAttr(chapter.href)}"/>
            |    </navPoint>
            """.trimMargin()
        }.joinToString("\n")
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="${xmlAttr(doc.languages.first())}">
            |  <head>
            |    <meta name="dtb:uid" content="${xmlAttr(doc.identifier)}"/>
            |    <meta name="dtb:depth" content="1"/>
            |    <meta name="dtb:totalPageCount" content="0"/>
            |    <meta name="dtb:maxPageNumber" content="0"/>
            |  </head>
            |  <docTitle><text>${xmlText(doc.title)}</text></docTitle>
            |  <navMap>
            |$points
            |  </navMap>
            |</ncx>
            |
        """.trimMargin()
    }

    /**
     * Two separate mechanisms, both needed: `properties="cover-image"` on the *image* drives a
     * library thumbnail, this page is what renders as page one. `epub:type="cover"` also means
     * [EpubParser] drops it on re-import instead of counting it as a chapter.
     */
    private fun coverXhtml(doc: EpubDocument, cover: EpubImage): String {
        val lang = xmlAttr(doc.languages.first())
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!DOCTYPE html>
            |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang" lang="$lang">
            |<head>
            |<meta charset="utf-8"/>
            |<title>${xmlText(doc.coverLabel)}</title>
            |<link rel="stylesheet" type="text/css" href="$CSS_NAME"/>
            |</head>
            |<body epub:type="cover">
            |<section epub:type="cover" class="cover">
            |<img src="${xmlAttr(cover.href)}" alt="${xmlAttr(doc.coverLabel)}"/>
            |</section>
            |</body>
            |</html>
            |
        """.trimMargin()
    }

    /**
     * The gloss must not rely on colour alone: several e-ink readers force their own text colour and
     * ignore `opacity`, so italic, a smaller size and the left rule are what actually carry the
     * distinction on hardware.
     */
    private val STYLESHEET = """
        |html { font-size: 100%; }
        |body { margin: 0 5%; line-height: 1.5; }
        |h1, h2, h3, h4, h5, h6 { line-height: 1.25; margin: 1.6em 0 0.7em; }
        |p { margin: 0 0 0.85em; text-align: justify; }
        |p.gloss {
        |  font-style: italic;
        |  font-size: 0.92em;
        |  margin: -0.35em 0 1.1em 0;
        |  padding-left: 0.7em;
        |  border-left: 1px solid #999999;
        |  color: #555555;
        |}
        |p.untranslated, h1.untranslated, h2.untranslated, h3.untranslated,
        |h4.untranslated, h5.untranslated, h6.untranslated { font-style: italic; color: #555555; }
        |section.cover { margin: 0; padding: 0; text-align: center; }
        |section.cover img { max-width: 100%; max-height: 100%; }
        |ul, ol { margin: 0 0 0.85em; padding-left: 1.4em; }
        |li { margin: 0 0 0.3em; text-align: left; }
        |li > p { margin: 0; text-align: left; }
        |table { border-collapse: collapse; margin: 0 0 1em; width: 100%; }
        |th, td { border: 1px solid #999999; padding: 0.3em 0.5em; text-align: left; vertical-align: top; }
        |th { font-weight: bold; }
        |figure { margin: 1em 0; text-align: center; }
        |figure img { max-width: 100%; }
        |aside.footnote { font-size: 0.9em; color: #555555; margin: 0 0 0.6em; }
        |a.noteref { text-decoration: none; }
        |
    """.trimMargin()

    /** Entry names are built from `/` literals only — a `File`-derived name would emit `\` on Windows. */
    private fun ZipOutputStream.text(name: String, content: String) =
        bytes(name, content.toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.bytes(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    /** Sniffs the container format rather than trusting the filename, which is always `.jpg` today. */
    fun sniffImageMediaType(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }

    /** `images/cover.jpg` or `.png` — the extension must agree with the sniffed media type. */
    fun coverHref(mediaType: String): String = String.format(
        Locale.ROOT,
        "images/cover.%s",
        when (mediaType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        },
    )
}

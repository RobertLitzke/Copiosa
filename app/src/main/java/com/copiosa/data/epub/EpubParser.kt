package com.copiosa.data.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A parsed EPUB, reduced to what the book reader stores: ordered chapters of markdown blocks. */
data class EpubBook(
    val title: String,
    val author: String?,
    /** Content chapters only (front/backmatter stripped), in spine order. */
    val chapters: List<EpubChapter>,
    /** Raw cover image bytes (jpeg/png), null when the epub has none we could find. */
    val coverBytes: ByteArray?,
)

data class EpubChapter(val title: String?, val blocks: List<EpubBlock>)

/** One block of chapter content. [text] is markdown: `## Title` headings, `*italic*`, `**bold**`. */
data class EpubBlock(val kind: BlockKind, val text: String)

enum class BlockKind { HEADING, PARA }

/** The file isn't a readable EPUB; [message] is user-facing (import-failure dialog). */
class EpubParseException(message: String) : Exception(message)

/**
 * Minimal EPUB reader: container.xml → OPF (metadata/manifest/spine) → per-chapter XHTML flattened
 * to markdown blocks. Deliberately not a full renderer — plain text plus bold/italic/headings is
 * the contract (images, tables, footnotes and links are dropped). Pure JVM (jsoup + java.util.zip),
 * so it is unit-testable against a fixture epub; the Android-side work (content URIs, bitmap
 * downscaling, persistence) lives in [EpubImportService].
 *
 * Canonical input is a Standard Ebooks file, whose `epub:type` semantics drive the front/backmatter
 * skipping; generic epubs fall back to filename heuristics, and if the skip rules would discard
 * everything, every spine document is kept instead — better an imprint page than an empty book.
 */
class EpubParser {

    fun parse(file: File): EpubBook {
        try {
            ZipFile(file).use { zip -> return parseZip(zip) }
        } catch (e: EpubParseException) {
            throw e
        } catch (e: Exception) {
            throw EpubParseException("This file isn't a readable EPUB.")
        }
    }

    private fun parseZip(zip: ZipFile): EpubBook {
        val containerXml = zip.text("META-INF/container.xml")
            ?: throw EpubParseException("This file isn't an EPUB (no META-INF/container.xml).")
        val container = Jsoup.parse(containerXml, "", Parser.xmlParser())
        val opfPath = container.getElementsByTag("rootfile")
            .firstNotNullOfOrNull { it.attr("full-path").takeIf(String::isNotBlank) }
            ?: throw EpubParseException("This EPUB has no content manifest.")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val opfXml = zip.text(opfPath) ?: throw EpubParseException("This EPUB's manifest is missing.")
        val opf = Jsoup.parse(opfXml, "", Parser.xmlParser())

        val title = opf.firstTagText("dc:title") ?: "Untitled"
        val author = opf.firstTagText("dc:creator")

        // Manifest: id -> (href, media-type, properties).
        val manifest = opf.getElementsByTag("item").associateBy(
            { it.attr("id") },
            { ManifestItem(it.attr("href"), it.attr("media-type"), it.attr("properties")) },
        )
        val spineIds = opf.getElementsByTag("itemref").map { it.attr("idref") }
        if (spineIds.isEmpty()) throw EpubParseException("This EPUB has no reading order.")

        val coverBytes = findCover(zip, opf, manifest, opfDir)

        val spineDocs = spineIds.mapNotNull { id ->
            val item = manifest[id] ?: return@mapNotNull null
            if (!item.mediaType.contains("html", ignoreCase = true)) return@mapNotNull null
            if (item.properties.tokens().contains("nav")) return@mapNotNull null
            val html = zip.text(resolve(opfDir, item.href)) ?: return@mapNotNull null
            SpineDoc(fileName = item.href.substringAfterLast('/'), document = Jsoup.parse(html))
        }
        if (spineDocs.isEmpty()) throw EpubParseException("This EPUB has no readable chapters.")

        // Content selection with a safety net: if the skip rules leave nothing, keep everything.
        val content = spineDocs.filterNot { isNonContent(it) }.ifEmpty { spineDocs }

        val chapters = content.mapNotNull { doc ->
            val blocks = chapterBlocks(doc.document)
            if (blocks.isEmpty()) null else EpubChapter(title = chapterTitle(doc.document), blocks = blocks)
        }
        if (chapters.isEmpty()) throw EpubParseException("This EPUB has no readable chapters.")

        return EpubBook(title = title, author = author, chapters = chapters, coverBytes = coverBytes)
    }

    // --- content selection --------------------------------------------------

    /** True for titlepage/imprint/colophon/toc-style documents that shouldn't be translated. */
    private fun isNonContent(doc: SpineDoc): Boolean {
        val body = doc.document.body()
        val types = body.attr(EPUB_TYPE).tokens() +
            body.children().flatMap { it.attr(EPUB_TYPE).tokens() }
        if (types.any { it.substringAfterLast(':') in SKIP_TYPES }) return true
        // Generic epubs rarely carry epub:type; fall back to conventional filenames.
        val name = doc.fileName.substringBeforeLast('.').lowercase()
        return SKIP_FILENAMES.any { name == it || name.startsWith("$it-") || name.startsWith("${it}_") }
    }

    // --- chapter extraction ------------------------------------------------

    /** "I: The Speed Demon" from an hgroup, else the first h1-h3, else the head title. */
    private fun chapterTitle(doc: org.jsoup.nodes.Document): String? {
        val hgroup = doc.body().getElementsByTag("hgroup").first()
        if (hgroup != null) {
            val parts = hgroup.children()
                .filter { it.tagName().matches(Regex("h[1-6]")) || it.tagName() == "p" }
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) return parts.joinToString(": ")
        }
        for (tag in listOf("h1", "h2", "h3")) {
            doc.body().getElementsByTag(tag).first()?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return doc.head().getElementsByTag("title").first()?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun chapterBlocks(doc: org.jsoup.nodes.Document): List<EpubBlock> {
        val blocks = mutableListOf<EpubBlock>()
        val title = chapterTitle(doc)
        if (title != null) blocks.add(EpubBlock(BlockKind.HEADING, "## $title"))
        collectBlocks(doc.body(), blocks)
        return blocks
    }

    private fun collectBlocks(node: Element, out: MutableList<EpubBlock>) {
        for (child in node.children()) {
            val tag = child.tagName().lowercase()
            when {
                tag in DROP_TAGS -> Unit
                tag == "p" || tag == "li" -> {
                    val text = inlineMarkdown(child)
                    if (text.isNotEmpty()) out.add(EpubBlock(BlockKind.PARA, text))
                }
                tag.matches(Regex("h[1-6]")) -> {
                    // The chapter title is already emitted from the hgroup/first-heading pass; a
                    // heading whose text repeats it would double up.
                    val text = inlineMarkdown(child)
                    val already = out.firstOrNull { it.kind == BlockKind.HEADING }?.text?.removePrefix("## ")
                    if (text.isNotEmpty() && !(already != null && already.contains(child.text().trim()))) {
                        out.add(EpubBlock(BlockKind.HEADING, "## $text"))
                    }
                }
                // Containers (section/div/blockquote/ul/ol/…): descend.
                else -> collectBlocks(child, out)
            }
        }
    }

    /**
     * Renders one block element's children to markdown text, preserving inline emphasis — the
     * fidelity `Element.text()` (used by ArticleExtractor) throws away.
     */
    private fun inlineMarkdown(element: Element): String {
        val sb = StringBuilder()
        renderInline(element, sb)
        return sb.toString().replace(WHITESPACE, " ").trim()
    }

    private fun renderInline(node: Node, out: StringBuilder) {
        for (child in node.childNodes()) {
            when (child) {
                is TextNode -> out.append(child.wholeText)
                is Element -> {
                    val tag = child.tagName().lowercase()
                    when {
                        tag in DROP_TAGS || tag == "sup" || tag == "sub" -> Unit
                        tag == "br" -> out.append(' ')
                        tag in ITALIC_TAGS -> out.appendWrapped(child, "*")
                        tag in BOLD_TAGS -> out.appendWrapped(child, "**")
                        // Links, spans, abbr, …: keep the text, drop the semantics.
                        else -> renderInline(child, out)
                    }
                }
            }
        }
    }

    private fun StringBuilder.appendWrapped(element: Element, marker: String) {
        val inner = StringBuilder().also { renderInline(element, it) }
            .toString().replace(WHITESPACE, " ").trim()
        if (inner.isEmpty()) return
        append(marker).append(inner).append(marker)
    }

    // --- cover ---------------------------------------------------------------

    private fun findCover(
        zip: ZipFile,
        opf: org.jsoup.nodes.Document,
        manifest: Map<String, ManifestItem>,
        opfDir: String,
    ): ByteArray? {
        // EPUB 3: manifest item flagged cover-image.
        manifest.values.firstOrNull { it.properties.tokens().contains("cover-image") }
            ?.let { zip.bytes(resolve(opfDir, it.href)) }?.let { return it }
        // EPUB 2: <meta name="cover" content="item-id">.
        opf.getElementsByTag("meta")
            .firstOrNull { it.attr("name") == "cover" }
            ?.let { manifest[it.attr("content")] }
            ?.let { zip.bytes(resolve(opfDir, it.href)) }?.let { return it }
        // Last resort: a manifest image conventionally named cover.*.
        manifest.values
            .firstOrNull { it.mediaType.startsWith("image/") && COVER_NAME.matches(it.href.substringAfterLast('/')) }
            ?.let { zip.bytes(resolve(opfDir, it.href)) }?.let { return it }
        return null
    }

    // --- zip/path helpers ----------------------------------------------------

    private fun ZipFile.entryFor(path: String): ZipEntry? = getEntry(path)

    private fun ZipFile.text(path: String): String? =
        entryFor(path)?.let { getInputStream(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } }

    private fun ZipFile.bytes(path: String): ByteArray? =
        entryFor(path)?.let { getInputStream(it).use { s -> s.readBytes() } }

    /** Resolves [href] (possibly `../` or percent-encoded) against the OPF's directory. */
    private fun resolve(opfDir: String, href: String): String {
        val decoded = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayDeque<String>()
        if (opfDir.isNotEmpty()) opfDir.split('/').forEach { parts.addLast(it) }
        for (segment in decoded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun org.jsoup.nodes.Document.firstTagText(tag: String): String? =
        getElementsByTag(tag).first()?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.tokens(): List<String> = split(WHITESPACE).filter { it.isNotEmpty() }

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)

    private data class SpineDoc(val fileName: String, val document: org.jsoup.nodes.Document)

    private companion object {
        const val EPUB_TYPE = "epub:type"
        val WHITESPACE = Regex("\\s+")
        val COVER_NAME = Regex("cover\\.(jpe?g|png)", RegexOption.IGNORE_CASE)

        /** `epub:type` tokens (namespace-stripped) that mark a document as non-content. */
        val SKIP_TYPES = setOf(
            "frontmatter", "backmatter", "titlepage", "halftitlepage", "imprint", "colophon",
            "copyright-page", "copyright", "dedication", "acknowledgments", "acknowledgements",
            "toc", "landmarks", "cover", "loi", "lot",
        )

        /** Conventional non-content filenames (sans extension) for epubs without `epub:type`. */
        val SKIP_FILENAMES = setOf(
            "cover", "title", "titlepage", "toc", "nav", "contents", "copyright", "colophon",
            "imprint", "dedication", "acknowledgments", "acknowledgements", "halftitle",
        )

        /** Elements whose content never reaches the reader (images, footnotes, chrome). */
        val DROP_TAGS = setOf("img", "figure", "figcaption", "nav", "hgroup", "header", "footer", "aside", "table", "svg", "script", "style")

        val ITALIC_TAGS = setOf("em", "i", "cite", "dfn", "var")
        val BOLD_TAGS = setOf("strong", "b")
    }
}

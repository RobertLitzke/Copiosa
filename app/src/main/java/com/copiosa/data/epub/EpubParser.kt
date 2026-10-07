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

/**
 * Where body images go as they are read.
 *
 * A callback rather than a list on [EpubBook] because an illustrated EPUB carries tens of megabytes
 * of them: accumulating every image in memory alongside the parsed chapters, on a phone, is how an
 * import OOMs. With a sink, peak cost is one image, and the Android half gets to downscale and
 * write each one to disk as it arrives without [EpubParser] ever seeing `android.*`.
 */
fun interface EpubImageSink {
    /** [name] is the slug the matching [BlockKind.IMAGE] block references. */
    fun accept(name: String, bytes: ByteArray)

    companion object {
        /** Drops every image — the default, and what the pure-JVM tests use. */
        val None = EpubImageSink { _, _ -> }
    }
}

data class EpubChapter(val title: String?, val blocks: List<EpubBlock>)

/** One block of chapter content. [text] is markdown: `## Title` headings, `*italic*`, `**bold**`. */
data class EpubBlock(val kind: BlockKind, val text: String)

/**
 * What a stored block is. Everything but [HEADING] is body text as far as sectioning goes; the
 * distinction matters to the renderer and the exporter, which turn each back into its own markup.
 *
 * Structure lives in the block's markdown, not in a tree — see [BlockMarkup] for the rules on
 * reading it back, and why it may only ever be read off the *original* text.
 */
enum class BlockKind {
    HEADING,
    PARA,

    /** One list item: `  ` of indent per nesting level, then `- ` or `N. `. */
    LIST,

    /** A whole table as one GFM pipe table, so it reaches the translator as a unit. */
    TABLE,

    /** A footnote body, `[^3]: …`, parked at the end of the chapter that references it. */
    NOTE,

    /** `![](2.jpg)` — an image reference. Never sent to the translator. */
    IMAGE,
}

/** The file isn't a readable EPUB; [message] is user-facing (import-failure dialog). */
class EpubParseException(message: String) : Exception(message)

/** Splits a space-separated attribute (`epub:type`, manifest `properties`) into its tokens. */
private fun String.tokens(): List<String> = split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * Minimal EPUB reader: container.xml → OPF (metadata/manifest/spine) → per-chapter XHTML flattened
 * to markdown blocks. Not a full renderer, but it covers the structure a reader actually misses:
 * headings, paragraphs, bold/italic, nested lists, tables, footnotes and images. Pure JVM (jsoup +
 * java.util.zip), so it is unit-testable against a fixture epub; the Android-side work (content
 * URIs, bitmap downscaling, persistence) lives in [EpubImportService].
 *
 * Everything flattens to *markdown in a string*, one block per stored row — no tree. That is what
 * lets new structure inherit sectioning, the `[[P<ord>]]` translation contract, export and backup
 * unchanged, and it is why a table is one block rather than one block per row.
 *
 * Canonical input is a Standard Ebooks file, whose `epub:type` semantics drive the front/backmatter
 * skipping; generic epubs fall back to filename heuristics, and if the skip rules would discard
 * everything, every spine document is kept instead — better an imprint page than an empty book.
 */
class EpubParser {

    fun parse(file: File, images: EpubImageSink = EpubImageSink.None): EpubBook {
        try {
            ZipFile(file).use { zip -> return parseZip(zip, images) }
        } catch (e: EpubParseException) {
            throw e
        } catch (e: Exception) {
            throw EpubParseException("This file isn't a readable EPUB.")
        }
    }

    private fun parseZip(zip: ZipFile, sink: EpubImageSink): EpubBook {
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
            val path = resolve(opfDir, item.href)
            val html = zip.text(path) ?: return@mapNotNull null
            SpineDoc(
                fileName = item.href.substringAfterLast('/'),
                dir = path.substringBeforeLast('/', ""),
                document = Jsoup.parse(html),
            )
        }
        if (spineDocs.isEmpty()) throw EpubParseException("This EPUB has no readable chapters.")

        // Pass A. Footnote bodies, harvested from every spine document before any chapter is walked
        // — a chapter cannot both emit a `[^n]` marker and know whether a body for it exists unless
        // the bodies were gathered first, and a marker pointing at nothing is worse than no marker.
        val noteBodies = harvestNotes(spineDocs)

        // Endnote documents are backmatter, so this has to come *before* isNonContent would drop
        // them: they hold bodies the chapters reference, and are mined rather than read as chapters.
        val (noteDocs, rest) = spineDocs.partition { isEndnotes(it) }

        // Content selection with a safety net: if the skip rules leave nothing, keep everything —
        // but never the endnote documents, or their bodies would be emitted twice.
        val content = rest.filterNot { isNonContent(it) }.ifEmpty { rest }.ifEmpty { spineDocs - noteDocs.toSet() }

        // Passes B and C, per chapter: walk the body allocating a marker per referenced note, then
        // append that chapter's note bodies after it.
        val assets = Assets(zip, sink)
        val chapters = content.mapNotNull { Chapter(it, assets, noteBodies).build() }
        if (chapters.isEmpty()) throw EpubParseException("This EPUB has no readable chapters.")

        return EpubBook(title = title, author = author, chapters = chapters, coverBytes = coverBytes)
    }

    // --- content selection --------------------------------------------------

    /** True for titlepage/imprint/colophon/toc-style documents that shouldn't be translated. */
    private fun isNonContent(doc: SpineDoc): Boolean {
        if (doc.types().any { it in SKIP_TYPES }) return true
        // Generic epubs rarely carry epub:type; fall back to conventional filenames.
        val name = doc.baseName()
        return SKIP_FILENAMES.any { name == it || name.startsWith("$it-") || name.startsWith("${it}_") }
    }

    /**
     * True for the document holding footnote/endnote *bodies*.
     *
     * Matched on `endnotes`/`rearnotes` specifically and never on `backmatter`, which Standard
     * Ebooks also puts on the colophon, the uncopyright and any appendix — routing those to note
     * extraction would quietly delete real chapters.
     */
    private fun isEndnotes(doc: SpineDoc): Boolean =
        doc.types().any { it in NOTE_TYPES } || doc.baseName().trimEnd('s') in NOTE_FILENAMES

    // --- pass A: footnote bodies ---------------------------------------------

    /**
     * Every footnote body in the book, keyed by the element id a marker links to: EPUB 3 popup
     * footnotes (`<aside epub:type="footnote" id="…">`, which sit inline in the chapter itself) and
     * Standard Ebooks endnotes (`<li id="note-3">` inside a `<section epub:type="endnotes">`).
     */
    private fun harvestNotes(docs: List<SpineDoc>): Map<String, Element> {
        val out = mutableMapOf<String, Element>()
        for (doc in docs) {
            val endnotes = isEndnotes(doc)
            for (element in doc.document.body().getElementsByAttribute("id")) {
                val id = element.id().takeIf { it.isNotBlank() } ?: continue
                if (id in out) continue
                val typed = element.attr(EPUB_TYPE).tokens().any { it.substringAfterLast(':') in NOTE_ITEM_TYPES }
                val tag = element.tagName().lowercase()
                val looksLikeNote = typed ||
                    (tag == "aside" && endnotesIdiom(element)) ||
                    (endnotes && tag == "li")
                if (looksLikeNote) out[id] = element
            }
        }
        return out
    }

    /** An untyped `<aside>` is a footnote if it is one — a short block parked beside the prose. */
    private fun endnotesIdiom(aside: Element): Boolean = aside.getElementsByTag("p").isNotEmpty()

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

    private fun org.jsoup.nodes.Document.firstTagText(tag: String): String? =
        getElementsByTag(tag).first()?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)

    private class SpineDoc(
        val fileName: String,
        /** The document's own directory inside the zip — image `src`s resolve against it. */
        val dir: String,
        val document: org.jsoup.nodes.Document,
    ) {
        /** `epub:type` tokens on the body and its immediate children, namespace prefixes stripped. */
        fun types(): List<String> {
            val body = document.body()
            return (body.attr(EPUB_TYPE).tokens() + body.children().flatMap { it.attr(EPUB_TYPE).tokens() })
                .map { it.substringAfterLast(':') }
        }

        fun baseName(): String = fileName.substringBeforeLast('.').lowercase()
    }

    /**
     * Body images, handed to the sink as they are first seen and deduplicated by their path in the
     * zip, so an image used on twenty pages is stored once.
     */
    private class Assets(private val zip: ZipFile, private val sink: EpubImageSink) {
        private val byPath = mutableMapOf<String, String>()

        /** The slug an IMAGE block should name the image at [path] by, or null if unreadable. */
        fun keyFor(path: String): String? {
            byPath[path]?.let { return it }
            val bytes = zip.getEntry(path)?.let { zip.getInputStream(it).use { s -> s.readBytes() } }
            if (bytes == null || bytes.isEmpty()) return null
            // The extension is the source's, but the name is an opaque key: the import service may
            // re-encode while keeping it, and the exporter sniffs the bytes for the real media type.
            val extension = path.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "img"
            val key = "${byPath.size + 1}.$extension"
            byPath[path] = key
            sink.accept(key, bytes)
            return key
        }
    }

    // --- per-chapter block extraction ----------------------------------------

    /**
     * Flattens one spine document to blocks. Split out from [EpubParser] because the walk carries
     * per-document state — which directory images resolve against, which notes this chapter has
     * claimed, the running note counter — that would otherwise be threaded through every call.
     */
    private inner class Chapter(
        private val doc: SpineDoc,
        private val assets: Assets,
        private val noteBodies: Map<String, Element>,
    ) {
        /** Note ids this chapter marked, in marker order, mapped to the label it gave each. */
        private val claimed = LinkedHashMap<String, String>()

        fun build(): EpubChapter? {
            val blocks = mutableListOf<EpubBlock>()
            val title = chapterTitle()
            if (title != null) blocks.add(EpubBlock(BlockKind.HEADING, "## $title"))
            collectBlocks(doc.document.body(), blocks, depth = 0)
            // Pass C: the bodies of the notes this chapter marked, in the order they were marked.
            for ((id, label) in claimed) {
                val body = noteText(noteBodies.getValue(id))
                if (body.isNotEmpty()) blocks.add(EpubBlock(BlockKind.NOTE, "[^$label]: $body"))
            }
            return if (blocks.isEmpty()) null else EpubChapter(title, blocks)
        }

        /** "I: The Speed Demon" from an hgroup, else the first h1-h3, else the head title. */
        private fun chapterTitle(): String? {
            val body = doc.document.body()
            val hgroup = body.getElementsByTag("hgroup").first()
            if (hgroup != null) {
                val parts = hgroup.children()
                    .filter { it.tagName().matches(HEADING_TAG) || it.tagName() == "p" }
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) return parts.joinToString(": ")
            }
            for (tag in listOf("h1", "h2", "h3")) {
                body.getElementsByTag(tag).first()?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return doc.document.head().getElementsByTag("title").first()?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        private fun collectBlocks(node: Element, out: MutableList<EpubBlock>, depth: Int) {
            for (child in node.children()) {
                val tag = child.tagName().lowercase()
                when {
                    tag in BLOCK_DROP_TAGS -> Unit
                    tag == "img" -> imageBlock(child, out)
                    tag == "figure" -> figureBlocks(child, out)
                    tag == "table" -> tableBlock(child, out)
                    tag == "ul" || tag == "ol" -> listBlocks(child, out, depth)
                    tag == "p" || tag == "li" -> {
                        val text = inlineMarkdown(child)
                        if (text.isNotEmpty()) {
                            out.add(EpubBlock(BlockKind.PARA, text))
                        } else {
                            // A paragraph whose whole content is an image (a common way to place a
                            // plate) is that image, not an empty paragraph.
                            child.getElementsByTag("img").firstOrNull()?.let { imageBlock(it, out) }
                        }
                    }
                    tag.matches(HEADING_TAG) -> {
                        // The chapter title is already emitted from the hgroup/first-heading pass; a
                        // heading whose text repeats it would double up.
                        val text = inlineMarkdown(child)
                        val already = out.firstOrNull { it.kind == BlockKind.HEADING }?.text?.removePrefix("## ")
                        if (text.isNotEmpty() && !(already != null && already.contains(child.text().trim()))) {
                            out.add(EpubBlock(BlockKind.HEADING, "## $text"))
                        }
                    }
                    // Containers (section/div/blockquote/dl/…): descend.
                    else -> collectBlocks(child, out, depth)
                }
            }
        }

        // --- lists ----------------------------------------------------------

        /**
         * One [BlockKind.LIST] block per `<li>`, nesting carried as two spaces of indent per level
         * and the marker as `- ` or `N. `. Flat rows rather than a tree: the reader shows one item
         * per stored row, so a tree would only have to be flattened again there.
         *
         * The indent is prepended *outside* [inlineMarkdown], whose trailing `.trim()` would
         * otherwise eat the very thing that encodes the depth.
         */
        private fun listBlocks(list: Element, out: MutableList<EpubBlock>, depth: Int) {
            val ordered = list.tagName().equals("ol", ignoreCase = true)
            var number = list.attr("start").toIntOrNull() ?: 1
            val indent = " ".repeat(depth * INDENT_WIDTH)
            for (item in list.children()) {
                if (!item.tagName().equals("li", ignoreCase = true)) {
                    collectBlocks(item, out, depth)
                    continue
                }
                val marker = if (ordered) "${number++}. " else "- "
                val text = inlineMarkdown(item)
                if (text.isNotEmpty()) out.add(EpubBlock(BlockKind.LIST, "$indent$marker$text"))
                // Block-level content inside the item — a nested list, a table, a figure — after it.
                // renderInline skips these, so this is the only place their text is emitted.
                for (nested in item.children()) {
                    when (nested.tagName().lowercase()) {
                        "ul", "ol" -> listBlocks(nested, out, depth + 1)
                        "table" -> tableBlock(nested, out)
                        "figure" -> figureBlocks(nested, out)
                    }
                }
            }
        }

        // --- tables ---------------------------------------------------------

        /**
         * A whole `<table>` as one [BlockKind.TABLE] block. The first row becomes the header — GFM
         * cannot express a headerless table, and a reader showing the first data row emphasised
         * beats showing pipe characters as prose.
         *
         * Lossy on purpose: `colspan` is flattened by repeating the cell, `rowspan` is ignored, and
         * short rows are padded to the widest. One block rather than one per row is what gets the
         * table to the translator as a unit — row-by-row, every cell would be an isolated
         * `[[P<ord>]]` with no context, which is how a column of one-word headers gets mangled.
         */
        private fun tableBlock(table: Element, out: MutableList<EpubBlock>) {
            val rows = mutableListOf<List<String>>()
            for (row in table.getElementsByTag("tr")) {
                val cells = mutableListOf<String>()
                for (cell in row.children()) {
                    val tag = cell.tagName().lowercase()
                    if (tag != "td" && tag != "th") continue
                    val text = escapeCell(inlineMarkdown(cell))
                    repeat(cell.attr("colspan").toIntOrNull()?.coerceIn(1, MAX_COLSPAN) ?: 1) { cells.add(text) }
                }
                if (cells.isNotEmpty()) rows.add(cells)
            }
            if (rows.isEmpty()) return
            val width = rows.maxOf { it.size }
            fun pad(row: List<String>) = List(width) { row.getOrElse(it) { "" } }
            val markdown = renderPipeTable(PipeTable(pad(rows.first()), rows.drop(1).map(::pad)))
            out.add(EpubBlock(BlockKind.TABLE, markdown))
        }

        // --- images ---------------------------------------------------------

        /** `<figure>`: the image, then its caption as an ordinary paragraph so it gets translated. */
        private fun figureBlocks(figure: Element, out: MutableList<EpubBlock>) {
            figure.getElementsByTag("img").firstOrNull()?.let { imageBlock(it, out, withAltCaption = false) }
            val caption = figure.getElementsByTag("figcaption").firstOrNull()?.let { inlineMarkdown(it) }
            if (!caption.isNullOrEmpty()) out.add(EpubBlock(BlockKind.PARA, caption))
        }

        /**
         * An `![](key)` block for [img], plus its `alt` as a caption paragraph when there is one.
         *
         * The alt text deliberately does not stay inside the image markdown: an IMAGE block never
         * goes to the translator, so any text left in it would be the one thing on the page still
         * in the source language — and a filename sitting inside a translated paragraph is a
         * filename waiting to be helpfully rewritten by the model.
         */
        private fun imageBlock(img: Element, out: MutableList<EpubBlock>, withAltCaption: Boolean = true) {
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: return
            val key = assets.keyFor(resolve(doc.dir, src)) ?: return
            out.add(EpubBlock(BlockKind.IMAGE, "![]($key)"))
            if (!withAltCaption) return
            val alt = img.attr("alt").replace(WHITESPACE, " ").trim()
            if (alt.isNotEmpty()) out.add(EpubBlock(BlockKind.PARA, alt))
        }

        // --- footnotes ------------------------------------------------------

        /** One note body as a single markdown line, its "back to the text" link stripped. */
        private fun noteText(element: Element): String {
            val parts = element.children()
                .filter { it.tagName().lowercase() !in BLOCK_DROP_TAGS }
                .map { inlineMarkdown(it) }
                .filter { it.isNotEmpty() }
            return parts.ifEmpty { listOf(inlineMarkdown(element)) }.joinToString(" ").trim()
        }

        /**
         * True for an anchor *shaped* like a footnote marker: `epub:type="noteref"` in a
         * well-marked-up book, or — for the many that are not — a linked fragment sitting inside a
         * `<sup>`, which is what a marker looks like when nobody declared one. Shape alone, so that
         * a marker whose body went missing can be recognised and removed rather than left behind as
         * a bare digit in the middle of a sentence.
         */
        private fun isNoteRefShape(a: Element): Boolean {
            if (a.attr(EPUB_TYPE).tokens().any { it.substringAfterLast(':') == "noteref" }) return true
            return a.parent()?.tagName().equals("sup", ignoreCase = true) && a.attr("href").contains('#')
        }

        /** A marker only becomes `[^n]` when there is a body for it to open. */
        private fun hasNote(a: Element): Boolean = targetId(a) in noteBodies

        /** True for the "↩" link inside a note body pointing back at the marker. */
        private fun isBackLink(a: Element): Boolean =
            a.attr(EPUB_TYPE).tokens().any { it.substringAfterLast(':') == "backlink" }

        private fun targetId(a: Element): String = a.attr("href").substringAfterLast('#')

        /**
         * The label for [a]'s note, allocating one if this is its first mention.
         *
         * Numbered from 1 per chapter rather than reusing the source's label: it makes the
         * (chapter, label) pair unique by construction — which is what the reader's lookup keys on
         * — and it survives books whose notes restart per chapter or use `*` and `†`.
         */
        private fun noteLabel(a: Element): String {
            val id = targetId(a)
            return claimed.getOrPut(id) { "${claimed.size + 1}" }
        }

        // --- inline ---------------------------------------------------------

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
                            tag in INLINE_DROP_TAGS -> Unit
                            tag == "br" -> out.append(' ')
                            tag == "a" && isBackLink(child) -> Unit
                            // Shape but no body: the reference is unusable, so it goes entirely.
                            // Keeping its text would leave a stray "1" glued to the last word.
                            tag == "a" && isNoteRefShape(child) ->
                                if (hasNote(child)) out.append("[^").append(noteLabel(child)).append(']') else Unit
                            tag in ITALIC_TAGS -> out.appendWrapped(child, "*")
                            tag in BOLD_TAGS -> out.appendWrapped(child, "**")
                            // A paragraph boundary is a word boundary. Reached when a block element
                            // is being flattened into one line — a list item built from several
                            // <p>s, say — where running the two together would join two words.
                            tag in PARAGRAPH_TAGS -> {
                                renderInline(child, out)
                                out.append(' ')
                            }
                            // Links, spans, abbr, sup, sub…: keep the text, drop the semantics.
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
    }

    /** Resolves [href] (possibly `../` or percent-encoded) against [dir]. */
    private fun resolve(dir: String, href: String): String {
        val decoded = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayDeque<String>()
        if (dir.isNotEmpty()) dir.split('/').forEach { parts.addLast(it) }
        for (segment in decoded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private companion object {
        const val EPUB_TYPE = "epub:type"
        val WHITESPACE = Regex("\\s+")
        val COVER_NAME = Regex("cover\\.(jpe?g|png)", RegexOption.IGNORE_CASE)
        val HEADING_TAG = Regex("h[1-6]")

        /** Spaces of indent per nesting level. Must agree with [BlockMarkup]'s reader. */
        const val INDENT_WIDTH = 2

        /** A pathological `colspan` would otherwise blow a table up to that many columns. */
        const val MAX_COLSPAN = 16

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

        /** Documents holding footnote bodies — mined for their notes, never read as chapters. */
        val NOTE_TYPES = setOf("endnotes", "footnotes", "rearnotes")
        val NOTE_FILENAMES = setOf("endnote", "footnote", "note")

        /** `epub:type` tokens marking one element as a single note body. */
        val NOTE_ITEM_TYPES = setOf("footnote", "endnote", "rearnote", "note")

        /**
         * Elements the block walk never descends into: navigation and chrome, plus `<aside>`.
         *
         * The aside is there because a footnote body is emitted from the note pass, which finds it
         * by id in its own scan of the document. Letting the block walk descend as well would emit
         * every note twice — once as a paragraph where the aside happens to sit, once as its
         * [BlockKind.NOTE] row.
         */
        val BLOCK_DROP_TAGS = setOf("nav", "hgroup", "header", "footer", "svg", "script", "style", "aside")

        /**
         * Elements [Chapter.renderInline] must not descend into: the chrome above, plus everything
         * that becomes a block of its own. Kept separate from [BLOCK_DROP_TAGS] because the two
         * walks want opposite answers — a `<table>` is content to the block walk and must be
         * invisible to the inline one, or its cells would also be inlined into the enclosing
         * paragraph. `<ul>`/`<ol>`/`<li>` is the pairing that used to silently merge nested lists.
         */
        val INLINE_DROP_TAGS = BLOCK_DROP_TAGS +
            setOf("ul", "ol", "li", "table", "figure", "figcaption", "aside", "img")

        /** Block containers that, when flattened into one line, still separate their words. */
        val PARAGRAPH_TAGS = setOf("p", "div", "blockquote")

        val ITALIC_TAGS = setOf("em", "i", "cite", "dfn", "var")
        val BOLD_TAGS = setOf("strong", "b")
    }
}

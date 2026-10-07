package com.copiosa.data.epub

import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.book.chapterTitleOf
import com.copiosa.domain.book.nativeText
import com.copiosa.domain.book.targetText
import com.copiosa.domain.book.wantsTranslation
import com.copiosa.domain.model.BookDirection
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * Builds an [EpubDocument] from a book's stored rows.
 *
 * The export regenerates rather than repackages: the source EPUB is deleted at import and no anchor
 * back into its XHTML is stored, so there is nothing to substitute into. What comes out therefore
 * has exactly the fidelity the app's own reader has — headings, paragraphs, bold/italic, nested
 * lists, tables, footnotes, images and the cover — and no more, since anything [EpubParser] did not
 * read was never stored to write back out.
 *
 * Structure is read from each row's *original* text and its content from whichever language is
 * being written; see [BlockMarkup] for why the translation is not to be trusted with a marker.
 *
 * Which stored column is which language is never decided here: every read goes through
 * `targetText`/`nativeText`, so FORWARD and REVERSE books take the same path.
 */
object EpubExporter {

    /** The book has no renderable paragraphs, so there is no book to write. */
    class NothingToExportException : Exception("This book has no readable text to export.")

    fun build(
        book: BookEntity,
        /** Every paragraph of the book, ordered by `ord`. */
        paragraphs: List<BookParagraphEntity>,
        variant: EpubExportVariant,
        /** Translation models, most-used first; empty when nothing was machine-translated. */
        models: List<String>,
        strings: EpubStrings,
        cover: EpubImage?,
        now: Long,
        /** Body images, keyed by the filename an IMAGE row's markdown names. */
        images: List<EpubImage> = emptyList(),
    ): EpubDocument {
        val direction = book.direction
        val nativeLang = book.nativeLanguage
        // The gloss and any untranslated fallback are always in the native language, in both
        // directions — that is what going through nativeText() buys.
        val glossAttrs = " xml:lang=\"${xmlAttr(nativeLang)}\" lang=\"${xmlAttr(nativeLang)}\""

        var paragraphCount = 0
        var untranslated = 0
        val chapters = mutableListOf<EpubDocChapter>()

        val imageHrefs = images.associateBy { it.href.substringAfterLast('/') }

        for (chapterIndex in paragraphs.map { it.chapterIndex }.distinct().sorted()) {
            val rows = paragraphs.filter { it.chapterIndex == chapterIndex }
            // Only notes whose body this chapter actually writes may be linked to. Read off the
            // original text, because the `[^3]: ` prefix survives translation only by good luck.
            val notes = rows.filter { it.kind == BookParagraphEntity.KIND_NOTE }
                .mapNotNull { noteLabelOf(it.originalText) }
                .toSet()

            val body = StringBuilder()
            val lists = ListWriter(body)
            var navLabel: String? = null
            var emitted = 0

            for (row in rows) {
                val target = row.targetText(direction)?.takeIf { it.isNotBlank() }
                val native = row.nativeText(direction)?.takeIf { it.isNotBlank() }
                // Both sides blank is a row with nothing to say; skip it rather than emit <p></p>.
                val primary = target ?: native ?: continue
                // Any list run ends at the first row that isn't a list item, and the open <ul>
                // has to be closed before that row's own markup opens.
                if (row.kind != BookParagraphEntity.KIND_LIST) lists.closeAll()
                if (target == null) untranslated++
                emitted++

                val fallbackAttrs = if (target == null) " class=\"untranslated\"$glossAttrs" else ""
                val bilingual = variant == EpubExportVariant.BILINGUAL && target != null && native != null
                fun gloss(text: String) =
                    "<p class=\"gloss\"$glossAttrs>${inlineXhtml(text, notes)}</p>"

                // A `when` over the slug, with a paragraph fallback rather than an exhaustive match:
                // the kind column is deliberately plain text so that a row written by a future build
                // degrades instead of crashing, and matching every case would throw that away.
                when (row.kind) {
                    BookParagraphEntity.KIND_HEADING -> {
                        // The chapter title is a real stored row at the chapter's lowest ord, not
                        // something synthesised here — rendering it in place is what lets EpubParser's
                        // duplicate-heading check recognise it again on re-import. Emitting a second,
                        // title-derived heading would defeat that and grow a phantom block per cycle.
                        if (navLabel == null) navLabel = chapterTitleOf(primary)
                        body.append(headingXhtml(primary, fallbackAttrs, notes))
                        // A paragraph rather than a second <hN>: readers that build their own TOC
                        // from headings would otherwise show every chapter twice. The `##` has to
                        // come off first — outside an <hN> it is not a marker, just two characters.
                        if (bilingual) body.append(gloss(headingBody(native!!)))
                    }

                    BookParagraphEntity.KIND_LIST -> {
                        // Depth and marker come from the *original* text: the translated copy has
                        // been trimmed of its leading indent before it ever reached the database.
                        val markup = listMarkupOf(row.originalText)
                        val content = StringBuilder()
                            .append("<p$fallbackAttrs>")
                            .append(inlineXhtml(listItemBody(primary), notes))
                            .append("</p>")
                        // The gloss goes *inside* the <li>: as a sibling of one it would not be
                        // valid XHTML, and epubcheck rejects the file rather than the paragraph.
                        if (bilingual) content.append(gloss(listItemBody(native!!)))
                        lists.item(markup?.depth ?: 0, markup?.ordered ?: false, content.toString())
                    }

                    BookParagraphEntity.KIND_TABLE -> {
                        val table = parsePipeTable(primary)
                        if (table == null) {
                            // No longer a table — the model reflowed it into prose, and prose is
                            // what it now is. Better a readable paragraph than a broken <table>.
                            body.append("<p$fallbackAttrs>").append(inlineXhtml(primary, notes)).append("</p>")
                        } else {
                            body.append(tableXhtml(table, fallbackAttrs, notes))
                            if (bilingual) {
                                parsePipeTable(native!!)?.let {
                                    body.append(tableXhtml(it, " class=\"gloss\"$glossAttrs", notes))
                                }
                            }
                        }
                    }

                    BookParagraphEntity.KIND_NOTE -> {
                        val label = noteLabelOf(row.originalText)
                        val id = label?.let { " id=\"${xmlAttr(noteId(it))}\"" }.orEmpty()
                        body.append("<aside class=\"footnote\" epub:type=\"footnote\"$id>")
                            .append("<p$fallbackAttrs>")
                            .append(inlineXhtml(noteBodyOf(primary), notes))
                            .append("</p>")
                        if (bilingual) body.append(gloss(noteBodyOf(native!!)))
                        body.append("</aside>")
                    }

                    BookParagraphEntity.KIND_IMAGE -> {
                        // Silently absent when the import budget skipped it: the row still exists
                        // (dropping it would leave a hole in the dense ord sequence) but there are
                        // no bytes to point at, and a src to nothing is an epubcheck error.
                        val image = imageKeyOf(row.originalText)?.let { imageHrefs[it] }
                        if (image == null) {
                            emitted--
                            if (target == null) untranslated--
                        } else {
                            body.append("<figure><img src=\"../${xmlAttr(image.href)}\" alt=\"\"/></figure>")
                        }
                    }

                    else -> {
                        body.append("<p$fallbackAttrs>").append(inlineXhtml(primary, notes)).append("</p>")
                        if (bilingual) body.append(gloss(native!!))
                    }
                }
                body.append('\n')
            }
            lists.closeAll()

            if (emitted == 0) continue
            paragraphCount += emitted
            val number = chapters.size + 1
            // chapterTitleOf returns null for a heading made only of punctuation, so this covers
            // more than the no-heading case — an <a> with no text is invalid nav.
            val label = navLabel ?: String.format(Locale.getDefault(), strings.chapterFallback, number)
            val href = "text/${chapterFileName(number)}"
            chapters += EpubDocChapter(
                id = String.format(Locale.ROOT, "chap-%03d", number),
                href = href,
                navLabel = label,
                xhtml = chapterDocument(book.targetLanguage, label, body.toString()),
            )
        }

        if (chapters.isEmpty()) throw NothingToExportException()

        return EpubDocument(
            // Deterministic, so re-exporting the same book replaces it in a reader's library
            // instead of piling up duplicates, and so the packager's output is testable.
            identifier = "urn:uuid:" + UUID.nameUUIDFromBytes(
                "copiosa:book:${book.id}:${variant.name}".toByteArray(Charsets.UTF_8),
            ),
            title = book.title,
            author = book.author?.takeIf { it.isNotBlank() },
            languages = when (variant) {
                EpubExportVariant.TARGET_ONLY -> listOf(book.targetLanguage)
                EpubExportVariant.BILINGUAL -> listOf(book.targetLanguage, nativeLang)
            },
            description = strings.description,
            source = book.originalTitle.takeIf { it.isNotBlank() && it != book.title },
            modifiedUtc = DateTimeFormatter.ISO_INSTANT.format(
                // EPUB 3 demands exactly CCYY-MM-DDThh:mm:ssZ; Instant.now() carries fractional
                // seconds and would make the package invalid.
                Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS),
            ),
            translators = models,
            customMeta = customMeta(book, variant, models),
            cover = cover,
            // Only the images some chapter actually referenced: an unreferenced manifest item is an
            // epubcheck warning, and shipping bytes nothing points at just makes the file bigger.
            images = images.filter { image ->
                paragraphs.any {
                    it.kind == BookParagraphEntity.KIND_IMAGE &&
                        imageKeyOf(it.originalText) == image.href.substringAfterLast('/')
                }
            },
            coverLabel = strings.cover,
            navTitle = strings.contents,
            chapters = chapters,
            summary = EpubExportSummary(
                title = book.title,
                variant = variant,
                chapters = chapters.size,
                paragraphs = paragraphCount,
                untranslated = untranslated,
                nativeLanguage = nativeLang,
                models = models,
            ),
        )
    }

    /**
     * Rebuilds nested `<ul>`/`<ol>` markup from a run of flat [BookParagraphEntity.KIND_LIST] rows.
     *
     * The rows carry their nesting as a depth number, so the tree has to be reconstructed as the run
     * is walked: a deeper item opens a new list *inside* the item above it — not as its sibling,
     * which would be invalid XHTML — and a shallower one closes every list in between.
     *
     * The one shape it refuses is a jump of more than one level at a time, which would need a list
     * whose parent has no item to live in. Such a depth cannot come from [EpubParser], only from a
     * hand-edited row, and clamping it costs one level of indent rather than a malformed file.
     */
    private class ListWriter(private val out: StringBuilder) {
        /** One entry per open list, innermost last; true where that list is ordered. Each has an
         *  open `<li>` — the deepest holds the item just written, the rest hold their sublists. */
        private val open = ArrayDeque<Boolean>()

        fun item(depth: Int, ordered: Boolean, content: String) {
            val target = depth.coerceAtMost(open.size)
            while (open.size > target + 1) closeDeepest()
            if (open.size == target + 1) out.append("</li>")
            if (open.size < target + 1) {
                out.append(if (ordered) "<ol>" else "<ul>")
                open.addLast(ordered)
            }
            out.append("<li>").append(content)
        }

        fun closeAll() {
            while (open.isNotEmpty()) closeDeepest()
        }

        private fun closeDeepest() {
            out.append("</li>").append(if (open.removeLast()) "</ol>" else "</ul>")
        }
    }

    /** A [PipeTable] as XHTML, the first row as `<th>` header cells. */
    private fun tableXhtml(table: PipeTable, attributes: String, notes: Set<String>): String {
        val rows = table.padded()
        return buildString {
            append("<table$attributes>")
            rows.forEachIndexed { index, row ->
                val tag = if (index == 0) "th" else "td"
                append("<tr>")
                for (cell in row) append("<$tag>").append(inlineXhtml(cell, notes)).append("</$tag>")
                append("</tr>")
            }
            append("</table>")
        }
    }

    /**
     * Must not collide with [EpubParser]'s non-content filename heuristics, or the app could not
     * re-import its own output: `cover`, `title*`, `toc`, `nav`, `contents`, `part`… are all out.
     */
    private fun chapterFileName(number: Int) = String.format(Locale.ROOT, "chapter-%03d.xhtml", number)

    private fun customMeta(
        book: BookEntity,
        variant: EpubExportVariant,
        models: List<String>,
    ): List<Pair<String, String>> = buildList {
        // [models] is empty unless this particular export actually carries LLM output — see
        // BookEpubExportService, which decides that from the direction and the variant. Naming a
        // translator for a file that contains none of its work would be a lie in the metadata.
        add("copiosa:machine-translated" to models.isNotEmpty().toString())
        models.forEach { add("copiosa:translation-model" to it) }
        if (models.isNotEmpty()) {
            add("copiosa:translation-provider" to book.provider.name)
            // The LLM writes translatedText, which is the target text on a forward book and the
            // native gloss on a reverse one — so the direction of the translation flips with it.
            val forward = book.direction == BookDirection.FORWARD
            add("copiosa:translated-from" to if (forward) book.nativeLanguage else book.targetLanguage)
            add("copiosa:translated-into" to if (forward) book.targetLanguage else book.nativeLanguage)
        }
        add("copiosa:native-language" to book.nativeLanguage)
        add("copiosa:target-language" to book.targetLanguage)
        // Nothing levels a native-language gloss, so a reverse book's stored level claims nothing.
        if (book.wantsTranslation) add("copiosa:cefr-level" to book.level.name)
        add("copiosa:direction" to book.direction.name)
        add(
            "copiosa:variant" to when (variant) {
                EpubExportVariant.TARGET_ONLY -> "target-only"
                EpubExportVariant.BILINGUAL -> "bilingual"
            },
        )
        add("copiosa:section-count" to book.totalSections.toString())
    }

    /**
     * `xmlns:epub` is declared even though only `epub:type` uses it — an undeclared prefix makes the
     * file not well-formed, and a reader shows a parse error rather than the chapter.
     */
    private fun chapterDocument(lang: String, title: String, body: String): String {
        val l = xmlAttr(lang)
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!DOCTYPE html>
            |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$l" lang="$l">
            |<head>
            |<meta charset="utf-8"/>
            |<title>${xmlText(title)}</title>
            |<link rel="stylesheet" type="text/css" href="../${EpubPackager.CSS_NAME}"/>
            |</head>
            |<body epub:type="bodymatter">
            |<section epub:type="chapter">
            |$body</section>
            |</body>
            |</html>
            |
        """.trimMargin()
    }
}

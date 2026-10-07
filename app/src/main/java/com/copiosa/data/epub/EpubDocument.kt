package com.copiosa.data.epub

/** Which languages an exported book carries, and in what arrangement. */
enum class EpubExportVariant {
    /** The language being learned, alone — a clean reading copy. */
    TARGET_ONLY,

    /** Each paragraph followed by its counterpart in the other language, styled as a gloss. */
    BILINGUAL,
}

/** An image to embed, with the media type sniffed from the bytes rather than assumed from the name. */
class EpubImage(val bytes: ByteArray, val mediaType: String, val href: String)

/** One spine document: a complete, well-formed XHTML file plus what the nav needs to link to it. */
data class EpubDocChapter(
    /** Manifest id, e.g. `chap-001`. */
    val id: String,
    /** Path inside the EPUB directory, e.g. `text/chapter-001.xhtml`. */
    val href: String,
    /** Plain text (unescaped) for the nav and NCX labels. */
    val navLabel: String,
    val xhtml: String,
)

/** What the export produced, for the result dialog. */
data class EpubExportSummary(
    val title: String,
    val variant: EpubExportVariant,
    val chapters: Int,
    val paragraphs: Int,
    /** Paragraphs with no translation, emitted in the other language instead. */
    val untranslated: Int,
    /** The language those fall back to, so the result dialog can name it. */
    val nativeLanguage: String,
    val models: List<String>,
)

/**
 * Localised strings that end up *inside* the exported file. Passed in so [EpubExporter] stays free
 * of `android.*` and remains unit-testable.
 */
data class EpubStrings(
    /** Heading of the navigation document, e.g. "Contents". */
    val contents: String,
    /** Alt text for the cover image. */
    val cover: String,
    /** Format string with a single `%d`, for a chapter with no heading of its own. */
    val chapterFallback: String,
    /** Already-formatted `dc:description` prose. */
    val description: String,
)

/**
 * A whole book, rendered and ready to zip. [EpubExporter] builds it from Room rows; [EpubPackager]
 * turns it into the container. Splitting there keeps every decision about *what the book says* away
 * from every decision about *what a valid EPUB looks like*, and both halves testable on their own.
 */
data class EpubDocument(
    /** `urn:uuid:…`, deterministic per (book, variant) so re-exporting replaces rather than duplicates. */
    val identifier: String,
    val title: String,
    val author: String?,
    /** Target language first — it is the book's primary language. */
    val languages: List<String>,
    val description: String,
    /** The original title, when it differs from [title]. */
    val source: String?,
    /** `CCYY-MM-DDThh:mm:ssZ` exactly — EPUB 3 rejects fractional seconds. */
    val modifiedUtc: String,
    /** Translation models, most-used first; empty when nothing was machine-translated. */
    val translators: List<String>,
    /** `copiosa:`-prefixed metadata, as (property, value) pairs. */
    val customMeta: List<Pair<String, String>>,
    val cover: EpubImage?,
    /** Body images, in the order the chapters reference them. Empty for a book without any. */
    val images: List<EpubImage> = emptyList(),
    val coverLabel: String,
    val navTitle: String,
    val chapters: List<EpubDocChapter>,
    val summary: EpubExportSummary,
)

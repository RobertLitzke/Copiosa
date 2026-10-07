package com.copiosa.data.epub

import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.ProviderId

/** Shared builders for the export tests, so each one states only what it is actually about. */

internal fun book(
    id: Long = 7,
    title: String = "El Hobbit",
    originalTitle: String = "The Hobbit",
    author: String? = "J. R. R. Tolkien",
    direction: BookDirection = BookDirection.FORWARD,
    downloadMode: BookDownloadMode = BookDownloadMode.WHILE_READING,
    totalSections: Int = 2,
    translatedSections: Int = 2,
    coverFile: String? = null,
) = BookEntity(
    id = id,
    title = title,
    originalTitle = originalTitle,
    author = author,
    coverFile = coverFile,
    level = Cefr.B1,
    direction = direction,
    country = "ES",
    targetLanguage = "es",
    nativeLanguage = "en",
    provider = ProviderId.OPENROUTER,
    model = "openai/gpt-4o-mini",
    totalParagraphs = 0,
    wordCount = 0,
    totalSections = totalSections,
    translatedSections = translatedSections,
    downloadMode = downloadMode,
    createdAt = 0,
    updatedAt = 0,
)

internal fun para(
    ord: Int,
    chapterIndex: Int,
    original: String,
    translated: String? = null,
    heading: Boolean = false,
    /** Overrides [heading]; pass a `BookParagraphEntity.KIND_*` slug for the newer block types. */
    kind: String? = null,
) = BookParagraphEntity(
    id = ord.toLong() + 1,
    bookId = 7,
    ord = ord,
    chapterIndex = chapterIndex,
    sectionIndex = chapterIndex,
    kind = kind
        ?: if (heading) BookParagraphEntity.KIND_HEADING else BookParagraphEntity.KIND_PARA,
    originalText = original,
    translatedText = translated,
)

/** A body image as the exporter takes them: named exactly as an IMAGE row's markdown says. */
internal fun bodyImage(name: String, bytes: ByteArray = FAKE_JPEG) =
    EpubImage(bytes, EpubPackager.sniffImageMediaType(bytes), "images/$name")

internal fun strings() = EpubStrings(
    contents = "Contents",
    cover = "Cover",
    chapterFallback = "Chapter %d",
    description = "Machine-translated into Spanish with openai/gpt-4o-mini by Copiosa.",
)

/** A two-byte JPEG SOI marker is enough for the media-type sniffer and for byte-identity checks. */
internal val FAKE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01, 0x02, 0x03)

internal fun coverImage(bytes: ByteArray = FAKE_JPEG): EpubImage {
    val mediaType = EpubPackager.sniffImageMediaType(bytes)
    return EpubImage(bytes, mediaType, EpubPackager.coverHref(mediaType))
}

/** Strips tags and unescapes, so a test can assert on what a reader would actually show. */
internal fun textOf(xhtml: String): String = xhtml
    .replace(Regex("<[^>]*>"), "")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    // Last, or an escaped "&amp;lt;" would decode twice.
    .replace("&amp;", "&")

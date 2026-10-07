package com.copiosa.data.epub

import android.content.Context
import android.net.Uri
import com.copiosa.R
import com.copiosa.data.exportTo
import com.copiosa.data.local.BookDao
import com.copiosa.data.local.BookEntity
import com.copiosa.domain.book.fullyBilingual
import com.copiosa.domain.book.fullyReadable
import com.copiosa.domain.book.wantsTranslation
import com.copiosa.domain.model.BookDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What the export picker needs to draw itself, read once when the menu item is tapped. */
data class BookExportState(
    val bookId: Long,
    val title: String,
    val targetLanguage: String,
    val nativeLanguage: String,
    /** False when one of the two languages isn't present end to end, so only a single option shows. */
    val bilingualAvailable: Boolean,
) {
    /** Seeds the system file picker. Sanitised, because a book title is not a filename. */
    fun suggestedFileName(variant: EpubExportVariant): String {
        val tag = when (variant) {
            EpubExportVariant.TARGET_ONLY -> targetLanguage
            EpubExportVariant.BILINGUAL -> "$targetLanguage-$nativeLanguage"
        }
        val base = title
            .replace(ILLEGAL_FILENAME_CHARS, "-")
            .replace(WHITESPACE, " ")
            .trim()
            .take(60)
            .trim()
            .ifBlank { "book" }
        return "$base ($tag).epub"
    }

    private companion object {
        val ILLEGAL_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
        val WHITESPACE = Regex("\\s+")
    }
}

/** In flight, finished (with a [summary]), or failed — the shape `VocabViewModel` uses for Anki. */
data class BookExportRun(
    val running: Boolean = false,
    val summary: EpubExportSummary? = null,
    val error: String? = null,
)

/**
 * Writes a translated book back out as a standalone EPUB the user can read anywhere.
 *
 * The Android half of the export: DAO reads, the cover file, localised strings and the destination
 * URI. Everything about what an EPUB *is* lives in [EpubExporter] and [EpubPackager], which are pure
 * JVM and unit-tested — the same split `AnkiExporter`/`ApkgPackager`/`AnkiExportService` uses.
 *
 * Injected into both the reader's and the library row's ViewModel, so the gating rule, the filename
 * and the variant handling exist once rather than twice (the `BookDownloadController` precedent).
 */
@Singleton
class BookEpubExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
) {
    /** The book was deleted between opening the menu and picking a destination. */
    class BookGoneException : Exception("This book is no longer available.")

    /** Gating is re-checked at write time; the frontier can move while the picker is open. */
    class NotFullyTranslatedException : Exception("This book isn't fully translated yet.")

    suspend fun state(bookId: Long): BookExportState? = withContext(Dispatchers.IO) {
        val book = bookDao.getById(bookId) ?: return@withContext null
        BookExportState(
            bookId = book.id,
            title = book.title,
            targetLanguage = book.targetLanguage,
            nativeLanguage = book.nativeLanguage,
            bilingualAvailable = book.fullyBilingual,
        )
    }

    suspend fun export(
        uri: Uri,
        bookId: Long,
        variant: EpubExportVariant,
        now: Long = System.currentTimeMillis(),
    ): EpubExportSummary = context.contentResolver.exportTo(uri) { write(uri, bookId, variant, now) }

    private suspend fun write(
        uri: Uri,
        bookId: Long,
        variant: EpubExportVariant,
        now: Long,
    ): EpubExportSummary = withContext(Dispatchers.IO) {
        val book = bookDao.getById(bookId) ?: throw BookGoneException()
        if (!book.fullyReadable) throw NotFullyTranslatedException()
        // A bilingual export of a book that lost its second language mid-picker degrades rather than
        // failing: the target-language copy is still exactly what was asked for, minus the gloss.
        val effective = if (variant == EpubExportVariant.BILINGUAL && !book.fullyBilingual) {
            EpubExportVariant.TARGET_ONLY
        } else {
            variant
        }

        val paragraphs = bookDao.allParagraphs(bookId)
        val models = modelsPresentIn(book, effective)
        val cover = book.coverFile
            ?.let { BookMedia.file(context, it) }
            ?.takeIf { it.exists() }
            ?.readBytes()
            ?.takeIf { it.isNotEmpty() }
            ?.let { bytes ->
                val mediaType = EpubPackager.sniffImageMediaType(bytes)
                EpubImage(bytes, mediaType, EpubPackager.coverHref(mediaType))
            }

        val doc = EpubExporter.build(
            book = book,
            paragraphs = paragraphs,
            variant = effective,
            models = models,
            strings = strings(book, effective, models),
            cover = cover,
            now = now,
            images = bodyImages(bookId),
        )

        // The last point a cancel is honoured: a destination once opened is written through to the
        // end rather than left as half a book.
        ensureActive()
        context.contentResolver.openOutputStream(uri)?.buffered()?.use { EpubPackager.pack(it, doc) }
            ?: throw IllegalStateException("Could not open the destination file.")

        doc.summary
    }

    /**
     * This book's stored body images, named exactly as the IMAGE rows reference them.
     *
     * The media type is sniffed from the bytes rather than taken from the extension: the import may
     * have re-encoded an image while keeping the source's name, so the name is an opaque key and
     * only the bytes know what they are. [EpubExporter] drops any of these no row points at.
     */
    private fun bodyImages(bookId: Long): List<EpubImage> =
        BookMedia.imagesDir(context, bookId).listFiles().orEmpty()
            .filter { it.isFile }
            .sortedBy { it.name }
            .mapNotNull { file ->
                val bytes = file.readBytes().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                EpubImage(bytes, EpubPackager.sniffImageMediaType(bytes), "images/${file.name}")
            }

    /**
     * The models whose work this particular file actually contains.
     *
     * The LLM writes `translatedText`, which is the *target* text on a forward book — always
     * exported — but the *native gloss* on a reverse one, which only the bilingual variant carries.
     * A reverse book exported target-only therefore contains no machine translation at all, however
     * many sections have been glossed, and must not name a translator.
     */
    private suspend fun modelsPresentIn(book: BookEntity, variant: EpubExportVariant): List<String> {
        val carriesLlmOutput =
            book.direction == BookDirection.FORWARD || variant == EpubExportVariant.BILINGUAL
        if (!carriesLlmOutput) return emptyList()
        return bookDao.sectionsByModel(book.id).map { it.model }.filter { it.isNotBlank() }
    }

    private fun strings(
        book: BookEntity,
        variant: EpubExportVariant,
        models: List<String>,
    ): EpubStrings {
        val targetName = displayLanguage(book.targetLanguage)
        val nativeName = displayLanguage(book.nativeLanguage)
        val modelList = models.joinToString(", ")
        val description = buildList {
            when {
                book.direction == BookDirection.FORWARD && models.isNotEmpty() ->
                    add(context.getString(R.string.epub_description_forward, targetName, nativeName, modelList))
                else -> {
                    add(context.getString(R.string.epub_description_reverse, targetName))
                    if (models.isNotEmpty()) {
                        add(context.getString(R.string.epub_description_gloss, nativeName, modelList))
                    }
                }
            }
            // Nothing levels a native-language gloss, so a reverse book's stored level claims nothing.
            if (book.wantsTranslation && book.direction == BookDirection.FORWARD) {
                add(context.getString(R.string.epub_description_level, book.level.name))
            }
            if (variant == EpubExportVariant.BILINGUAL) {
                add(context.getString(R.string.epub_description_bilingual, nativeName))
            }
        }.joinToString(" ")

        return EpubStrings(
            contents = context.getString(R.string.epub_nav_contents),
            cover = context.getString(R.string.epub_cover),
            chapterFallback = context.getString(R.string.epub_chapter_fallback),
            description = description,
        )
    }

    private fun displayLanguage(tag: String): String =
        Locale.forLanguageTag(tag).displayLanguage.replaceFirstChar { it.uppercase() }.ifBlank { tag }
}

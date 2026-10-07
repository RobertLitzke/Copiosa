package com.copiosa.data.epub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import com.copiosa.data.local.AppDatabase
import com.copiosa.data.local.BookDao
import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.Cefr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the import dialog asked the user, and the only part of a book's settings snapshot they get a
 * say in. Everything else (languages, country, provider, model) still comes from the active profile.
 */
data class ImportOptions(
    val direction: BookDirection,
    /** The grading target. Carried for both directions but only meaningful for FORWARD. */
    val level: Cefr,
    /**
     * How eagerly the book translates from here. On a REVERSE book this doubles as the gloss opt-in:
     * anything above [BookDownloadMode.OFF] means "also produce a native translation".
     */
    val downloadMode: BookDownloadMode = BookDownloadMode.WHILE_READING,
)

/**
 * Orchestrates importing an EPUB: copies the picked file out of the content URI (ZipFile needs a
 * real file), runs the pure [EpubParser] + [BookSectioner], persists the book and its paragraph
 * rows in one transaction, and stores a downscaled cover in files/book_covers. The AnkiImportService
 * idiom throughout: cacheDir workdir deleted in `finally`, [EpubParseException] propagates for the
 * caller's failure dialog.
 */
@Singleton
class EpubImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val bookDao: BookDao,
    private val settingsRepo: SettingsRepository,
) {
    private val parser = EpubParser()

    /** Imports the epub at [uri] under [options] and returns the new book's id. */
    suspend fun import(uri: Uri, options: ImportOptions): Long = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "epub_import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val epubFile = File(work, "import.epub")
            context.contentResolver.openInputStream(uri)?.use { input ->
                epubFile.outputStream().use { input.copyTo(it) }
            } ?: throw EpubParseException("Could not open the selected file.")

            // Images are written to the workdir as they are parsed, under the same slugs the IMAGE
            // blocks name, and moved under the book's id once the insert has assigned one. Doing it
            // here rather than after means peak memory is one image, and the `finally` below cleans
            // up for free when the findMatching early-return makes the work moot.
            val imageWork = File(work, "images").apply { mkdirs() }
            val parsed = parser.parse(epubFile, imageSink(imageWork))

            val blocks = BookSectioner.assign(parsed.chapters)
            if (blocks.isEmpty()) throw EpubParseException("This EPUB has no readable chapters.")

            val settings = settingsRepo.settings.first()

            // Already have this book for this profile? Hand back the existing row: its translated
            // sections, reading position and cover are all still good, and re-importing would
            // otherwise re-pay for every section already translated.
            bookDao.findMatching(
                originalTitle = parsed.title,
                author = parsed.author,
                lang = settings.targetLanguage,
                totalParagraphs = blocks.size,
                direction = options.direction,
            )?.let { return@withContext it.id }

            val now = System.currentTimeMillis()
            val book = BookEntity(
                title = parsed.title,
                originalTitle = parsed.title,
                author = parsed.author,
                sourceFileName = uri.lastPathSegment?.substringAfterLast('/'),
                level = options.level,
                direction = options.direction,
                downloadMode = options.downloadMode,
                country = settings.defaultCountry,
                targetLanguage = settings.targetLanguage,
                nativeLanguage = settings.nativeLanguage,
                provider = settings.provider,
                model = settings.activeModel,
                totalParagraphs = blocks.size,
                // Image rows are a filename, not prose; counting them would inflate the reading
                // estimate the library shows by a word or two per plate.
                wordCount = blocks.filterNot { it.kind == BlockKind.IMAGE }
                    .sumOf { it.text.split(WHITESPACE).size },
                totalSections = blocks.maxOf { it.sectionIndex } + 1,
                createdAt = now,
                updatedAt = now,
            )

            val bookId = db.withTransaction {
                val id = bookDao.insert(book)
                blocks.asSequence().chunked(INSERT_CHUNK).forEach { chunk ->
                    bookDao.insertParagraphs(
                        chunk.map {
                            BookParagraphEntity(
                                bookId = id,
                                ord = it.ord,
                                chapterIndex = it.chapterIndex,
                                sectionIndex = it.sectionIndex,
                                kind = when (it.kind) {
                                    BlockKind.HEADING -> BookParagraphEntity.KIND_HEADING
                                    BlockKind.PARA -> BookParagraphEntity.KIND_PARA
                                    BlockKind.LIST -> BookParagraphEntity.KIND_LIST
                                    BlockKind.TABLE -> BookParagraphEntity.KIND_TABLE
                                    BlockKind.NOTE -> BookParagraphEntity.KIND_NOTE
                                    BlockKind.IMAGE -> BookParagraphEntity.KIND_IMAGE
                                },
                                originalText = it.text,
                                // An image is the same in every language and never goes to the
                                // translator, so it is born translated. Leaving it null would
                                // instead park a permanent "translation pending" notice mid-page.
                                translatedText = it.text.takeIf { _ -> it.kind == BlockKind.IMAGE },
                            )
                        },
                    )
                }
                id
            }

            // The book is committed, so a cancel arriving now must not strand it without the cover
            // and plates still sitting in the workdir the `finally` is about to delete.
            withContext(NonCancellable) {
                parsed.coverBytes?.let { bytes ->
                    writeCover(bookId, bytes)?.let { name ->
                        bookDao.update(bookDao.getById(bookId)!!.copy(coverFile = name))
                    }
                }
                adoptImages(imageWork, bookId)
            }
            bookId
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Writes each body image into [dir], downscaled, until [IMAGE_BUDGET_BYTES] of them have been
     * stored. Past that ceiling images are simply not written: the IMAGE block stays in the text —
     * dropping it would leave a hole in the dense `ord` sequence the reader's list index depends on
     * — and renders as nothing, which is what a book with four hundred plates costs on a phone.
     *
     * Bytes that are already small enough are stored verbatim rather than re-encoded. Round-tripping
     * PNG line art or a diagram through JPEG is exactly where ringing artefacts come from, and the
     * export sniffs the stored bytes for their media type, so the format need not be uniform.
     */
    private fun imageSink(dir: File): EpubImageSink {
        var written = 0L
        return EpubImageSink { name, bytes ->
            if (written >= IMAGE_BUDGET_BYTES) return@EpubImageSink
            val data = if (bytes.size <= IMAGE_VERBATIM_BYTES) bytes else downscaleToJpeg(bytes, IMAGE_MAX_PX)
            if (data == null || data.isEmpty()) return@EpubImageSink
            File(dir, name).writeBytes(data)
            written += data.size
        }
    }

    /** Moves the staged images under the book's own id, now that the insert has assigned one. */
    private fun adoptImages(staged: File, bookId: Long) {
        val files = staged.listFiles()?.takeIf { it.isNotEmpty() } ?: return
        val target = BookMedia.imagesDir(context, bookId).apply { mkdirs() }
        for (file in files) {
            val destination = File(target, file.name)
            // renameTo fails across filesystems (cacheDir and filesDir need not share one).
            if (!file.renameTo(destination)) file.copyTo(destination, overwrite = true)
        }
    }

    /** Decodes + downscales the cover to ~[COVER_MAX_PX] and stores it; null if undecodable. */
    private fun writeCover(bookId: Long, bytes: ByteArray): String? {
        val jpeg = downscaleToJpeg(bytes, COVER_MAX_PX) ?: return null
        val name = BookMedia.coverFileName(bookId)
        BookMedia.dir(context).apply { mkdirs() }
        BookMedia.file(context, name).writeBytes(jpeg)
        return name
    }

    /**
     * [bytes] decoded, sampled down so its longest side is around [maxPx], and re-encoded as JPEG.
     * Null when the bytes are not a decodable image. Shared by the cover and by body images, which
     * differ only in how large "around" is.
     */
    private fun downscaleToJpeg(bytes: ByteArray, maxPx: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val INSERT_CHUNK = 500
        const val COVER_MAX_PX = 600
        const val JPEG_QUALITY = 85

        /** Body images are read at arm's length on a phone; a plate does not need to be a poster. */
        const val IMAGE_MAX_PX = 800

        /** Small enough that re-encoding would cost more fidelity than it saves bytes. */
        const val IMAGE_VERBATIM_BYTES = 64 * 1024

        /** Ceiling on a single book's stored images. An illustrated folio can carry hundreds. */
        const val IMAGE_BUDGET_BYTES = 24L * 1024 * 1024
    }
}

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
import com.copiosa.domain.model.Cefr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    /** REVERSE only: also produce a whole-book native gloss, rather than reading the original bare. */
    val translateAll: Boolean = false,
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

            val parsed = parser.parse(epubFile)
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
                // Forward books lean on the reader's read-ahead, so they start with this off; a
                // reverse book has no read-ahead (it is already readable), so the whole-book gloss
                // is the only thing that would ever translate it.
                autoTranslate = options.direction == BookDirection.REVERSE && options.translateAll,
                country = settings.defaultCountry,
                targetLanguage = settings.targetLanguage,
                nativeLanguage = settings.nativeLanguage,
                provider = settings.provider,
                model = settings.activeModel,
                totalParagraphs = blocks.size,
                wordCount = blocks.sumOf { it.text.split(WHITESPACE).size },
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
                                },
                                originalText = it.text,
                            )
                        },
                    )
                }
                id
            }

            parsed.coverBytes?.let { bytes ->
                writeCover(bookId, bytes)?.let { name ->
                    bookDao.update(bookDao.getById(bookId)!!.copy(coverFile = name))
                }
            }
            bookId
        } finally {
            work.deleteRecursively()
        }
    }

    /** Decodes + downscales the cover to ~[COVER_MAX_PX] and stores it; null if undecodable. */
    private fun writeCover(bookId: Long, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_MAX_PX || bounds.outHeight / (sample * 2) >= COVER_MAX_PX) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        return try {
            val name = BookMedia.coverFileName(bookId)
            BookMedia.dir(context).apply { mkdirs() }
            BookMedia.file(context, name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, out)
            }
            name
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val INSERT_CHUNK = 500
        const val COVER_MAX_PX = 600
        const val COVER_JPEG_QUALITY = 85
    }
}

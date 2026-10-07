package com.copiosa.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.reading.ReadMode
import kotlinx.coroutines.flow.Flow

/** Where one content chapter lives: its first paragraph, and the sections it covers. */
data class ChapterSpan(
    val chapterIndex: Int,
    val firstOrd: Int,
    val firstSection: Int,
    val lastSection: Int,
)

/**
 * Translated sections that came from some other model. [firstSection]/[lastSection] are null when
 * [count] is 0 (SQLite's MIN/MAX over no rows), which is the "nothing to redo" case.
 */
data class StaleSections(
    val count: Int,
    val firstSection: Int?,
    val lastSection: Int?,
)

/** How many of a book's translated sections came from one model. */
data class ModelSectionCount(val model: String, val sections: Int)

/**
 * How much text one section holds, for sizing audio tracks without loading the book.
 *
 * Both sides are returned so the caller picks the one matching the book's direction — a forward
 * book is spoken from its translation, a reverse book from its own text — rather than baking the
 * direction into the SQL.
 */
data class SectionSize(
    val sectionIndex: Int,
    val originalChars: Int,
    val translatedChars: Int,
)

/** A heading paragraph; the first one of a chapter supplies that chapter's title. */
data class ChapterHeading(
    val chapterIndex: Int,
    val ord: Int,
    val originalText: String,
    val translatedText: String?,
)

@Dao
interface BookDao {

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Insert
    suspend fun insertParagraphs(paragraphs: List<BookParagraphEntity>)

    @Update
    suspend fun update(book: BookEntity)

    @Query("SELECT * FROM books WHERE targetLanguage = :lang ORDER BY updatedAt DESC")
    fun observeBooks(lang: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    /** Every book in [lang], most recently opened first — the cloze miner's scan order. */
    @Query("SELECT * FROM books WHERE targetLanguage = :lang ORDER BY updatedAt DESC")
    suspend fun booksFor(lang: String): List<BookEntity>

    /**
     * Prose paragraphs the learner has actually read, most recent first.
     *
     * Bounded by [maxOrd] (the book's reading position) rather than the whole book, because the cloze
     * bank is meant to be built from material already met — and because it keeps a scan of a 5,000
     * paragraph novel proportional to how far in they are.
     */
    @Query(
        "SELECT * FROM book_paragraphs WHERE bookId = :bookId AND kind = 'PARA' AND ord < :maxOrd " +
            "ORDER BY ord DESC LIMIT :limit",
    )
    suspend fun readParagraphs(bookId: Long, maxOrd: Int, limit: Int): List<BookParagraphEntity>

    /**
     * An already-imported copy of the same book for this profile: same source title, author,
     * paragraph count (the count guards against a different edition sharing a title) and direction.
     * Re-importing returns this row instead of a duplicate, so its translated sections and reading
     * position are kept rather than paid for again. Direction is part of the identity because the
     * same file imported the other way round is a genuinely different book to read.
     */
    @Query(
        "SELECT * FROM books WHERE originalTitle = :originalTitle AND targetLanguage = :lang " +
            "AND totalParagraphs = :totalParagraphs AND direction = :direction " +
            "AND ((:author IS NULL AND author IS NULL) OR author = :author) LIMIT 1",
    )
    suspend fun findMatching(
        originalTitle: String,
        author: String?,
        lang: String,
        totalParagraphs: Int,
        direction: BookDirection,
    ): BookEntity?

    /** Dense [BookParagraphEntity.ord] means the paging position *is* the ord. */
    @Query("SELECT * FROM book_paragraphs WHERE bookId = :bookId ORDER BY ord")
    fun paragraphs(bookId: Long): PagingSource<Int, BookParagraphEntity>

    @Query("SELECT * FROM book_paragraphs WHERE bookId = :bookId AND sectionIndex = :section ORDER BY ord")
    suspend fun sectionParagraphs(bookId: Long, section: Int): List<BookParagraphEntity>

    /**
     * The whole book at once, for the EPUB export — the one caller that legitimately needs every
     * paragraph in memory, since it is writing a single file out of them. Everything on the reading
     * path uses [paragraphs]' PagingSource or [sectionParagraphs] instead.
     */
    @Query("SELECT * FROM book_paragraphs WHERE bookId = :bookId ORDER BY ord")
    suspend fun allParagraphs(bookId: Long): List<BookParagraphEntity>

    @Query("SELECT sectionIndex FROM book_paragraphs WHERE bookId = :bookId AND ord = :ord")
    suspend fun sectionOf(bookId: Long, ord: Int): Int?

    /**
     * One row per content chapter: where it starts in the reader, and which sections it spans so its
     * translation state can be read off the frontier. Grouping scans the book's paragraph rows, so
     * this is a table-of-contents query, not something to run per scroll event.
     */
    @Query(
        "SELECT chapterIndex AS chapterIndex, MIN(ord) AS firstOrd, " +
            "MIN(sectionIndex) AS firstSection, MAX(sectionIndex) AS lastSection " +
            "FROM book_paragraphs WHERE bookId = :bookId GROUP BY chapterIndex ORDER BY chapterIndex",
    )
    suspend fun chapterSpans(bookId: Long): List<ChapterSpan>

    /**
     * Per-section text volume, used to split an oversized chapter into several audio tracks. Images
     * are excluded because they contribute a filename, not speech.
     */
    @Query(
        "SELECT sectionIndex AS sectionIndex, SUM(LENGTH(originalText)) AS originalChars, " +
            "SUM(LENGTH(COALESCE(translatedText, ''))) AS translatedChars FROM book_paragraphs " +
            "WHERE bookId = :bookId AND kind != 'IMAGE' GROUP BY sectionIndex ORDER BY sectionIndex",
    )
    suspend fun sectionSizes(bookId: Long): List<SectionSize>

    /**
     * Every heading row, in reading order. The chapter title is not a column: EpubParser emits the
     * chapter's title as its first HEADING block, so the first row per chapterIndex here *is* the
     * title (and carries its translation once the section has been through the LLM).
     */
    @Query(
        "SELECT chapterIndex, ord, originalText, translatedText FROM book_paragraphs " +
            "WHERE bookId = :bookId AND kind = 'HEADING' ORDER BY ord",
    )
    suspend fun headingRows(bookId: Long): List<ChapterHeading>

    /**
     * The footnote body a `[^3]` marker in [chapterIndex] points at, for the reader's note sheet.
     * Labels restart at 1 in every chapter, so the pair identifies one note.
     *
     * Matched on `originalText`, never on the translation: the `[^3]: ` prefix is the key, and the
     * translated copy is exactly where a model is liable to move, renumber or drop it.
     */
    @Query(
        "SELECT * FROM book_paragraphs WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "AND kind = 'NOTE' AND originalText LIKE :prefix || '%' ORDER BY ord LIMIT 1",
    )
    suspend fun noteRow(bookId: Long, chapterIndex: Int, prefix: String): BookParagraphEntity?

    @Query("UPDATE book_paragraphs SET translatedText = :text WHERE id = :id")
    suspend fun setTranslated(id: Long, text: String?)

    /**
     * Advance the frontier — only after every paragraph of the section is stored.
     *
     * `MAX` because the frontier is a high-water mark, not a cursor: re-translating an earlier
     * section (a model change, via BookTranslationManager.retranslate) runs the very same code path
     * and must not walk the frontier backwards, which would re-lock chapters the reader can already
     * read end to end.
     */
    @Query(
        "UPDATE books SET translatedSections = MAX(translatedSections, :n), autoRetryAttempts = 0, " +
            "lastAutoRetryAt = NULL, lastError = NULL, updatedAt = :now WHERE id = :id",
    )
    suspend fun setTranslatedSections(id: Long, n: Int, now: Long)

    /** Why the last attempt failed, for a stall to explain itself after a restart; see [BookEntity.lastError]. */
    @Query("UPDATE books SET lastError = :code WHERE id = :id")
    suspend fun setLastError(id: Long, code: String?)

    @Query("UPDATE books SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    /**
     * Stamps [BookEntity.lastReadAt] along with the position: a moved position is the one reliable
     * sign the book was being read. `updatedAt` is left alone — it orders the translation queue.
     */
    @Query("UPDATE books SET posParagraph = :ord, posOffset = :offset, lastReadAt = :now WHERE id = :id")
    suspend fun saveScroll(id: Long, ord: Int, offset: Int, now: Long)

    /** The language version last shown; restored on open beside the reading position. */
    @Query("UPDATE books SET readMode = :mode WHERE id = :id")
    suspend fun setReadMode(id: Long, mode: ReadMode)

    @Query("UPDATE books SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE books SET finishedReading = :finished WHERE id = :id")
    suspend fun setFinishedReading(id: Long, finished: Boolean)

    @Query("UPDATE books SET downloadMode = :mode WHERE id = :id")
    suspend fun setDownloadMode(id: Long, mode: BookDownloadMode)

    /** The model a later section's translation will be sent to; changed from the download dialog. */
    @Query("UPDATE books SET provider = :provider, model = :model WHERE id = :id")
    suspend fun setModel(id: Long, provider: ProviderId, model: String)

    @Query("UPDATE books SET highlightWords = :on WHERE id = :id")
    suspend fun setHighlightWords(id: Long, on: Boolean?)

    @Query("UPDATE books SET autoRetryAttempts = autoRetryAttempts + 1, lastAutoRetryAt = :now WHERE id = :id")
    suspend fun recordAutoRetry(id: Long, now: Long)

    /**
     * Books the background slow-translate task should still be pushing toward the end.
     *
     * Deliberately *not* filtered on [BookEntity.autoRetryAttempts]: a book set to BACKGROUND is a
     * standing instruction, and dropping it out of this query after a few failures made the stall
     * permanent and silent. The pacing is RetryBackoff.readyCapped's job instead, which keeps the
     * ladder but flattens it at a day. Ordering by `updatedAt` is what keeps a broken book from
     * squatting on the run's [limit] slots — only a stored section moves a book up, so one that
     * keeps failing sinks below any book still making progress.
     */
    @Query(
        "SELECT * FROM books WHERE downloadMode = 'BACKGROUND' AND translatedSections < totalSections " +
            "AND targetLanguage = :lang ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun pendingSlowTranslate(lang: String, limit: Int): List<BookEntity>

    /** Records which model translated a section; re-translating a section replaces its row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSection(section: BookSectionEntity)

    /**
     * The span of already-translated sections that did *not* come from [model], for the download
     * dialog's re-translate offer. Null [StaleSections.count] of 0 means everything matches; the
     * range is inclusive and may cover matching sections in between, which is the price of redoing
     * a contiguous run rather than tracking a set.
     */
    @Query(
        "SELECT COUNT(*) AS count, MIN(sectionIndex) AS firstSection, MAX(sectionIndex) AS lastSection " +
            "FROM book_sections WHERE bookId = :bookId AND model <> :model",
    )
    suspend fun staleSections(bookId: Long, model: String): StaleSections

    /** How many translated sections each model accounts for, most-used first — the dialog's tally. */
    @Query(
        "SELECT model AS model, COUNT(*) AS sections FROM book_sections WHERE bookId = :bookId " +
            "GROUP BY model ORDER BY sections DESC",
    )
    suspend fun sectionsByModel(bookId: Long): List<ModelSectionCount>

    /** Paragraph rows and section-provenance rows cascade. */
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

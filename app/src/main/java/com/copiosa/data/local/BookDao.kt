package com.copiosa.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.copiosa.domain.model.BookDirection
import kotlinx.coroutines.flow.Flow

/** Where one content chapter lives: its first paragraph, and the sections it covers. */
data class ChapterSpan(
    val chapterIndex: Int,
    val firstOrd: Int,
    val firstSection: Int,
    val lastSection: Int,
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
     * Every heading row, in reading order. The chapter title is not a column: EpubParser emits the
     * chapter's title as its first HEADING block, so the first row per chapterIndex here *is* the
     * title (and carries its translation once the section has been through the LLM).
     */
    @Query(
        "SELECT chapterIndex, ord, originalText, translatedText FROM book_paragraphs " +
            "WHERE bookId = :bookId AND kind = 'HEADING' ORDER BY ord",
    )
    suspend fun headingRows(bookId: Long): List<ChapterHeading>

    @Query("UPDATE book_paragraphs SET translatedText = :text WHERE id = :id")
    suspend fun setTranslated(id: Long, text: String?)

    /** Advance the frontier — only after every paragraph of the section is stored. */
    @Query(
        "UPDATE books SET translatedSections = :n, autoRetryAttempts = 0, lastAutoRetryAt = NULL, " +
            "updatedAt = :now WHERE id = :id",
    )
    suspend fun setTranslatedSections(id: Long, n: Int, now: Long)

    @Query("UPDATE books SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    @Query("UPDATE books SET posParagraph = :ord, posOffset = :offset WHERE id = :id")
    suspend fun saveScroll(id: Long, ord: Int, offset: Int)

    @Query("UPDATE books SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE books SET finishedReading = :finished WHERE id = :id")
    suspend fun setFinishedReading(id: Long, finished: Boolean)

    @Query("UPDATE books SET autoTranslate = :on WHERE id = :id")
    suspend fun setAutoTranslate(id: Long, on: Boolean)

    @Query("UPDATE books SET autoRetryAttempts = autoRetryAttempts + 1, lastAutoRetryAt = :now WHERE id = :id")
    suspend fun recordAutoRetry(id: Long, now: Long)

    /** Books the background slow-translate task should still be pushing toward the end. */
    @Query(
        "SELECT * FROM books WHERE autoTranslate = 1 AND translatedSections < totalSections " +
            "AND targetLanguage = :lang AND autoRetryAttempts < :maxAttempts " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun pendingSlowTranslate(lang: String, maxAttempts: Int, limit: Int): List<BookEntity>

    /** Paragraph rows cascade. */
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

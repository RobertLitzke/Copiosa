package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_29_30_SQL] against real SQLite, following the [Migration28To29Test] pattern.
 * The migration is purely additive (the imported-books tables), so the checks are shape and
 * constraints.
 *
 * This proves the statements *run*, not that Room accepts the resulting schema — the identity-hash
 * check only happens on a real device upgrade, so that still needs one manual pass.
 */
class Migration29To30Test {

    @Test
    fun `creates both book tables ready for writes`() {
        withDb { conn ->
            MIGRATION_29_30_SQL.forEach { conn.runSql(it) }

            conn.insertBook()
            conn.runSql(
                "INSERT INTO book_paragraphs (bookId, ord, chapterIndex, sectionIndex, kind, originalText) " +
                    "VALUES (1, 0, 0, 0, 'HEADING', '## I: The Speed Demon')",
            )

            assertEquals(1L, conn.long("SELECT COUNT(*) FROM books"))
            assertEquals(0L, conn.long("SELECT translatedSections FROM books"))
            assertEquals(1L, conn.long("SELECT COUNT(*) FROM book_paragraphs"))
            // The translation column starts null and stays nullable.
            assertEquals(null, conn.long("SELECT translatedText FROM book_paragraphs"))
        }
    }

    @Test
    fun `a paragraph's ord is unique within its book`() {
        withDb { conn ->
            MIGRATION_29_30_SQL.forEach { conn.runSql(it) }
            conn.insertBook()
            conn.insertBook()
            fun insert(bookId: Long, ord: Int) = conn.runSql(
                "INSERT INTO book_paragraphs (bookId, ord, chapterIndex, sectionIndex, kind, originalText) " +
                    "VALUES ($bookId, $ord, 0, 0, 'PARA', 'text')",
            )

            insert(1, 0)
            // The same ord in another book is a different row...
            insert(2, 0)
            // ...and so is another ord in the same book.
            insert(1, 1)
            val duplicated = runCatching { insert(1, 0) }

            assertTrue("expected the unique index to reject the duplicate", duplicated.isFailure)
            assertEquals(3L, conn.long("SELECT COUNT(*) FROM book_paragraphs"))
        }
    }

    @Test
    fun `deleting a book cascades to its paragraphs`() {
        withDb { conn ->
            conn.runSql("PRAGMA foreign_keys = ON")
            MIGRATION_29_30_SQL.forEach { conn.runSql(it) }
            conn.insertBook()
            conn.runSql(
                "INSERT INTO book_paragraphs (bookId, ord, chapterIndex, sectionIndex, kind, originalText) " +
                    "VALUES (1, 0, 0, 0, 'PARA', 'text')",
            )

            conn.runSql("DELETE FROM books WHERE id = 1")

            assertEquals(0L, conn.long("SELECT COUNT(*) FROM book_paragraphs"))
        }
    }

    @Test
    fun `creates the indices the reader and translator rely on`() {
        withDb { conn ->
            MIGRATION_29_30_SQL.forEach { conn.runSql(it) }
            listOf(
                "index_books_targetLanguage",
                "index_book_paragraphs_bookId_ord",
                "index_book_paragraphs_bookId_sectionIndex",
            ).forEach { name ->
                assertEquals(
                    "missing index $name",
                    1L,
                    conn.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '$name'"),
                )
            }
        }
    }

    @Test
    fun `running the migration twice is harmless`() {
        withDb { conn ->
            MIGRATION_29_30_SQL.forEach { conn.runSql(it) }
            MIGRATION_29_30_SQL.forEach { conn.runSql(it) }
            assertEquals(0L, conn.long("SELECT COUNT(*) FROM books"))
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun Connection.insertBook() = runSql(
        "INSERT INTO books (title, originalTitle, level, country, targetLanguage, nativeLanguage, " +
            "provider, model, totalParagraphs, wordCount, totalSections, translatedSections, " +
            "autoTranslate, posParagraph, posOffset, finishedReading, starred, createdAt, updatedAt, " +
            "autoRetryAttempts) " +
            "VALUES ('The Tower Treasure', 'The Tower Treasure', 'B1', 'MX', 'es', 'en', " +
            "'GEMINI', 'gemini-2.5-flash', 1200, 60000, 45, 0, 0, 0, 0, 0, 0, " +
            "1700000000000, 1700000000000, 0)",
    )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_29_30_test").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").absolutePath}").use(block)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.long(sql: String): Long? = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            if (!rs.next()) return null
            val value = rs.getLong(1)
            if (rs.wasNull()) null else value
        }
    }
}

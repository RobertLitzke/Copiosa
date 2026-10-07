package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_34_35_SQL] against real SQLite, following the [Migration31To32Test] pattern.
 *
 * This one rebuilds `books` rather than altering it, so the things worth pinning are the ones a
 * rebuild can silently get wrong: that every book's old two-flag state maps onto the right
 * download mode, that the child paragraph rows survive their parent table being dropped, that the
 * index comes back, and that the new provenance table only claims sections that really are
 * translated.
 */
class Migration34To35Test {

    @Test
    fun `the old autoTranslate flag becomes a download mode`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "Cien años de soledad", autoTranslate = 1)
            conn.insertBook(id = 2, title = "El Aleph", autoTranslate = 0)
            // A reverse book that never opted into its gloss costs nothing today and must keep
            // costing nothing — WHILE_READING would quietly start translating it.
            conn.insertBook(id = 3, title = "Rayuela", autoTranslate = 0, direction = "REVERSE")

            MIGRATION_34_35_SQL.forEach { conn.runSql(it) }

            assertEquals("BACKGROUND", conn.text("SELECT downloadMode FROM books WHERE id = 1"))
            assertEquals("WHILE_READING", conn.text("SELECT downloadMode FROM books WHERE id = 2"))
            assertEquals("OFF", conn.text("SELECT downloadMode FROM books WHERE id = 3"))
        }
    }

    @Test
    fun `the retired column is gone and the index is back`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "Pedro Páramo", autoTranslate = 1)

            MIGRATION_34_35_SQL.forEach { conn.runSql(it) }

            assertFalse("autoTranslate" in conn.columnsOf("books"))
            assertTrue("downloadMode" in conn.columnsOf("books"))
            assertEquals(
                1,
                conn.int(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_books_targetLanguage'",
                ),
            )
        }
    }

    @Test
    fun `paragraphs survive the parent table being rebuilt`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "La tregua", autoTranslate = 0)
            conn.insertParagraph(bookId = 1, ord = 0, section = 0, translated = "Traducido")
            conn.insertParagraph(bookId = 1, ord = 1, section = 1, translated = null)

            MIGRATION_34_35_SQL.forEach { conn.runSql(it) }

            assertEquals(2, conn.int("SELECT COUNT(*) FROM book_paragraphs WHERE bookId = 1"))
        }
    }

    @Test
    fun `only translated sections get a provenance row, stamped with the book's model`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "Ficciones", autoTranslate = 0, model = "gemini-3.5-flash")
            // Two paragraphs of section 0 are translated (one row expected, not two); section 1 is
            // past the frontier and must not be claimed.
            conn.insertParagraph(bookId = 1, ord = 0, section = 0, translated = "Uno")
            conn.insertParagraph(bookId = 1, ord = 1, section = 0, translated = "Dos")
            conn.insertParagraph(bookId = 1, ord = 2, section = 1, translated = null)

            MIGRATION_34_35_SQL.forEach { conn.runSql(it) }

            assertEquals(1, conn.int("SELECT COUNT(*) FROM book_sections WHERE bookId = 1"))
            assertEquals(0, conn.int("SELECT sectionIndex FROM book_sections WHERE bookId = 1"))
            assertEquals(
                "gemini-3.5-flash",
                conn.text("SELECT model FROM book_sections WHERE bookId = 1"),
            )
        }
    }

    // --- helpers -----------------------------------------------------------

    /** The v34 shape of the two tables the migration touches. */
    private fun Connection.createSchema() {
        runSql(
            "CREATE TABLE `books` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`originalTitle` TEXT NOT NULL, " +
                "`author` TEXT, " +
                "`coverFile` TEXT, " +
                "`sourceFileName` TEXT, " +
                "`level` TEXT NOT NULL, " +
                "`direction` TEXT NOT NULL DEFAULT 'FORWARD', " +
                "`country` TEXT NOT NULL, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`nativeLanguage` TEXT NOT NULL, " +
                "`provider` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`totalParagraphs` INTEGER NOT NULL, " +
                "`wordCount` INTEGER NOT NULL, " +
                "`totalSections` INTEGER NOT NULL, " +
                "`translatedSections` INTEGER NOT NULL, " +
                "`autoTranslate` INTEGER NOT NULL, " +
                "`posParagraph` INTEGER NOT NULL, " +
                "`posOffset` INTEGER NOT NULL, " +
                "`finishedReading` INTEGER NOT NULL, " +
                "`starred` INTEGER NOT NULL, " +
                "`highlightWords` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`autoRetryAttempts` INTEGER NOT NULL, " +
                "`lastAutoRetryAt` INTEGER)",
        )
        runSql("CREATE INDEX `index_books_targetLanguage` ON `books` (`targetLanguage`)")
        runSql(
            "CREATE TABLE `book_paragraphs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, " +
                "`ord` INTEGER NOT NULL, " +
                "`chapterIndex` INTEGER NOT NULL, " +
                "`sectionIndex` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`originalText` TEXT NOT NULL, " +
                "`translatedText` TEXT, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }

    private fun Connection.insertBook(
        id: Long,
        title: String,
        autoTranslate: Int,
        direction: String = "FORWARD",
        model: String = "gemini-3.5-flash",
    ) = runSql(
        "INSERT INTO books (id, title, originalTitle, level, direction, country, targetLanguage, " +
            "nativeLanguage, provider, model, totalParagraphs, wordCount, totalSections, " +
            "translatedSections, autoTranslate, posParagraph, posOffset, finishedReading, starred, " +
            "createdAt, updatedAt, autoRetryAttempts) " +
            "VALUES ($id, '$title', '$title', 'B2', '$direction', 'Mexico', 'es', 'en', 'GEMINI', " +
            "'$model', 10, 100, 2, 1, $autoTranslate, 0, 0, 0, 0, 1, 1, 0)",
    )

    private fun Connection.insertParagraph(bookId: Long, ord: Int, section: Int, translated: String?) =
        runSql(
            "INSERT INTO book_paragraphs (bookId, ord, chapterIndex, sectionIndex, kind, " +
                "originalText, translatedText) VALUES ($bookId, $ord, 0, $section, 'PARA', 'x', " +
                (translated?.let { "'$it'" } ?: "NULL") + ")",
        )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_34_35_test").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").absolutePath}").use(block)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.columnsOf(table: String): List<String> = createStatement().use { st ->
        st.executeQuery("PRAGMA table_info($table)").use { rs ->
            buildList { while (rs.next()) add(rs.getString("name")) }
        }
    }

    private fun Connection.text(sql: String): String? = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            if (!rs.next()) return null
            rs.getString(1)
        }
    }

    private fun Connection.int(sql: String): Int = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }
}

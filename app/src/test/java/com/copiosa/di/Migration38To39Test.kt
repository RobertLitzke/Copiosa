package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_38_39_SQL] against real SQLite, following the [Migration36To37Test] pattern.
 *
 * One nullable ALTER on `books`, so what is worth pinning is that an imported novel comes through
 * untouched — its paragraph counts, its translation frontier and, most easily broken, its reading
 * position — and that every existing book starts out with a NULL `lastError`. NULL is what "nothing
 * has failed" means, and both the reader's status bar and the library row key off exactly that, so
 * a migration that defaulted it to a string would put a red error line on every shelf.
 */
class Migration38To39Test {

    @Test
    fun `existing books keep their progress and reading position`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "Redwall", translated = 81, total = 153, posParagraph = 1_790)

            MIGRATION_38_39_SQL.forEach { conn.runSql(it) }

            assertEquals("Redwall", conn.text("SELECT title FROM books WHERE id = 1"))
            assertEquals(81, conn.int("SELECT translatedSections FROM books WHERE id = 1"))
            assertEquals(153, conn.int("SELECT totalSections FROM books WHERE id = 1"))
            assertEquals(1_790, conn.int("SELECT posParagraph FROM books WHERE id = 1"))
        }
    }

    @Test
    fun `every pre-existing book starts with no recorded failure`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "Redwall", translated = 81, total = 153)
            conn.insertBook(id = 2, title = "Winnie Pooh", translated = 36, total = 36)

            MIGRATION_38_39_SQL.forEach { conn.runSql(it) }

            assertTrue("lastError" in conn.columnsOf("books"))
            assertNull(conn.text("SELECT lastError FROM books WHERE id = 1"))
            assertEquals(0, conn.int("SELECT COUNT(*) FROM books WHERE lastError IS NOT NULL"))
        }
    }

    @Test
    fun `a stall can be recorded and then cleared by a stored section`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "Redwall", translated = 81, total = 153)
            MIGRATION_38_39_SQL.forEach { conn.runSql(it) }

            conn.runSql("UPDATE books SET lastError = 'REFUSED' WHERE id = 1")
            assertEquals("REFUSED", conn.text("SELECT lastError FROM books WHERE id = 1"))

            // BookDao.setTranslatedSections' shape: the successful section is what clears the stall.
            conn.runSql(
                "UPDATE books SET translatedSections = MAX(translatedSections, 82), " +
                    "autoRetryAttempts = 0, lastAutoRetryAt = NULL, lastError = NULL WHERE id = 1",
            )
            assertNull(conn.text("SELECT lastError FROM books WHERE id = 1"))
            assertEquals(82, conn.int("SELECT translatedSections FROM books WHERE id = 1"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** The v38 shape of `books`, trimmed to the columns these assertions touch. */
    private fun Connection.createSchema() {
        runSql(
            "CREATE TABLE `books` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`originalTitle` TEXT NOT NULL, " +
                "`author` TEXT, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`totalSections` INTEGER NOT NULL, " +
                "`translatedSections` INTEGER NOT NULL, " +
                "`downloadMode` TEXT NOT NULL, " +
                "`posParagraph` INTEGER NOT NULL, " +
                "`posOffset` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`autoRetryAttempts` INTEGER NOT NULL, " +
                "`lastAutoRetryAt` INTEGER)",
        )
    }

    private fun Connection.insertBook(
        id: Long,
        title: String,
        translated: Int,
        total: Int,
        posParagraph: Int = 0,
    ) = runSql(
        "INSERT INTO books (id, title, originalTitle, targetLanguage, totalSections, " +
            "translatedSections, downloadMode, posParagraph, posOffset, createdAt, updatedAt, " +
            "autoRetryAttempts) " +
            "VALUES ($id, '$title', '$title', 'es', $total, $translated, 'BACKGROUND', " +
            "$posParagraph, 0, 1, 1, 3)",
    )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_38_39_test").toFile()
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

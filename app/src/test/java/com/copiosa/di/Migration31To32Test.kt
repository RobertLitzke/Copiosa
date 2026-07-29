package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_31_32_SQL] against real SQLite, following the [Migration30To31Test] pattern.
 * The migration adds the book translation direction, and the whole point of its default is that
 * every book that already existed was translated *into* the target language — so an untouched row
 * must come out FORWARD rather than needing a backfill.
 */
class Migration31To32Test {

    @Test
    fun `existing books default to FORWARD`() {
        withDb { conn ->
            conn.createBooks()
            conn.insertBook(id = 1, title = "Cien años de soledad")
            conn.insertBook(id = 2, title = "El Aleph")

            MIGRATION_31_32_SQL.forEach { conn.runSql(it) }

            assertEquals("FORWARD", conn.text("SELECT direction FROM books WHERE id = 1"))
            assertEquals("FORWARD", conn.text("SELECT direction FROM books WHERE id = 2"))
        }
    }

    @Test
    fun `a book written after the migration keeps its own direction`() {
        withDb { conn ->
            conn.createBooks()
            MIGRATION_31_32_SQL.forEach { conn.runSql(it) }

            conn.runSql(
                "INSERT INTO books (id, title, originalTitle, level, direction) " +
                    "VALUES (3, 'Rayuela', 'Rayuela', 'B2', 'REVERSE')",
            )

            assertEquals("REVERSE", conn.text("SELECT direction FROM books WHERE id = 3"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** Just the columns this migration touches; the real table is much wider. */
    private fun Connection.createBooks() = runSql(
        "CREATE TABLE books (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`originalTitle` TEXT NOT NULL, " +
            "`level` TEXT NOT NULL)",
    )

    private fun Connection.insertBook(id: Long, title: String) = runSql(
        "INSERT INTO books (id, title, originalTitle, level) VALUES ($id, '$title', '$title', 'B2')",
    )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_31_32_test").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").absolutePath}").use(block)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.text(sql: String): String? = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            if (!rs.next()) return null
            rs.getString(1)
        }
    }
}

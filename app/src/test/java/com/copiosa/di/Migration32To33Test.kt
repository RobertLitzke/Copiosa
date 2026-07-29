package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_32_33_SQL] against real SQLite, following the [Migration30To31Test] pattern.
 * Unlike 30→31, which spared forward cards, this one clears the highlight-form cache on *every* row:
 * the part-of-speech filter changes what any homograph headword expands to, and the bad forms are
 * already stored, so the whole deck has to be recomputed from the dictionary.
 */
class Migration32To33Test {

    @Test
    fun `clears the cache on every card`() {
        withDb { conn ->
            conn.createFlashcards()
            conn.insertCard(id = 1, front = "gato", back = "cat", forms = "gato;gatos")
            // The reported case: a noun that had collected the verb "ser"'s paradigm.
            conn.insertCard(id = 2, front = "son", back = "son (music)", forms = "son;soy;eres;es")
            conn.insertCard(id = 3, front = "vivir", back = "to live", forms = null)

            MIGRATION_32_33_SQL.forEach { conn.runSql(it) }

            assertEquals(null, conn.text("SELECT inflectedForms FROM flashcards WHERE id = 1"))
            assertEquals(null, conn.text("SELECT inflectedForms FROM flashcards WHERE id = 2"))
            assertEquals(null, conn.text("SELECT inflectedForms FROM flashcards WHERE id = 3"))
        }
    }

    @Test
    fun `running the migration twice is harmless`() {
        withDb { conn ->
            conn.createFlashcards()
            conn.insertCard(id = 1, front = "gato", back = "cat", forms = "gato;gatos")

            MIGRATION_32_33_SQL.forEach { conn.runSql(it) }
            MIGRATION_32_33_SQL.forEach { conn.runSql(it) }

            assertEquals(null, conn.text("SELECT inflectedForms FROM flashcards WHERE id = 1"))
            assertEquals(1, conn.count("SELECT COUNT(*) FROM flashcards"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** Just the columns this migration reads and writes; the real table is much wider. */
    private fun Connection.createFlashcards() = runSql(
        "CREATE TABLE flashcards (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`front` TEXT NOT NULL, " +
            "`back` TEXT NOT NULL, " +
            "`inflectedForms` TEXT)",
    )

    private fun Connection.insertCard(id: Long, front: String, back: String, forms: String?) = runSql(
        "INSERT INTO flashcards (id, front, back, inflectedForms) " +
            "VALUES ($id, '$front', '$back', ${forms?.let { "'$it'" } ?: "NULL"})",
    )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_32_33_test").toFile()
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

    private fun Connection.count(sql: String): Int = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }
}

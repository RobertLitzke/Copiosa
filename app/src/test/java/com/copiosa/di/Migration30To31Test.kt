package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_30_31_SQL] against real SQLite, following the [Migration29To30Test] pattern.
 * The migration clears the stale highlight-form cache on reverse cards, so the checks are which rows
 * it touches: a reverse card's cache was built from its native front and must be recomputed, while a
 * forward card's is already correct and must survive (recomputing every row would re-run the
 * dictionary over the whole deck).
 */
class Migration30To31Test {

    @Test
    fun `clears the cache on reverse cards only`() {
        withDb { conn ->
            conn.createFlashcards()
            conn.insertCard(id = 1, front = "gato", back = "cat", frontIsTarget = 1, forms = "gato;gatos")
            conn.insertCard(id = 2, front = "cat", back = "gato", frontIsTarget = 0, forms = "cat;cats")

            MIGRATION_30_31_SQL.forEach { conn.runSql(it) }

            assertEquals("gato;gatos", conn.text("SELECT inflectedForms FROM flashcards WHERE id = 1"))
            assertEquals(null, conn.text("SELECT inflectedForms FROM flashcards WHERE id = 2"))
        }
    }

    @Test
    fun `running the migration twice is harmless`() {
        withDb { conn ->
            conn.createFlashcards()
            conn.insertCard(id = 1, front = "gato", back = "cat", frontIsTarget = 1, forms = "gato;gatos")
            conn.insertCard(id = 2, front = "cat", back = "gato", frontIsTarget = 0, forms = "cat;cats")

            MIGRATION_30_31_SQL.forEach { conn.runSql(it) }
            MIGRATION_30_31_SQL.forEach { conn.runSql(it) }

            assertEquals("gato;gatos", conn.text("SELECT inflectedForms FROM flashcards WHERE id = 1"))
            assertEquals(null, conn.text("SELECT inflectedForms FROM flashcards WHERE id = 2"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** Just the columns this migration reads and writes; the real table is much wider. */
    private fun Connection.createFlashcards() = runSql(
        "CREATE TABLE flashcards (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`front` TEXT NOT NULL, " +
            "`back` TEXT NOT NULL, " +
            "`inflectedForms` TEXT, " +
            "`frontIsTarget` INTEGER NOT NULL DEFAULT 1)",
    )

    private fun Connection.insertCard(id: Long, front: String, back: String, frontIsTarget: Int, forms: String) =
        runSql(
            "INSERT INTO flashcards (id, front, back, inflectedForms, frontIsTarget) " +
                "VALUES ($id, '$front', '$back', '$forms', $frontIsTarget)",
        )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_30_31_test").toFile()
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

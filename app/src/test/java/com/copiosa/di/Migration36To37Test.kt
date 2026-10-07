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
 * Replays [MIGRATION_36_37_SQL] against real SQLite, following the [Migration35To36Test] pattern.
 *
 * One nullable ALTER, so what is worth pinning is that it is genuinely non-destructive: a deck the
 * user has spent months building must come through with its text and — more easily broken — its FSRS
 * schedule intact, and every existing card must end up with a NULL `autoSource`, since NULL is what
 * "a person made this" means and the seeder's cleanup query keys off exactly that.
 */
class Migration36To37Test {

    @Test
    fun `existing cards keep their content and schedule`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertCard(id = 1, front = "gato", back = "cat", dueAt = 1_700_000_000_000L)

            MIGRATION_36_37_SQL.forEach { conn.runSql(it) }

            assertEquals("gato", conn.text("SELECT front FROM flashcards WHERE id = 1"))
            assertEquals("cat", conn.text("SELECT back FROM flashcards WHERE id = 1"))
            assertEquals(
                1_700_000_000_000L,
                conn.long("SELECT dueAt FROM flashcards WHERE id = 1"),
            )
            assertEquals(3, conn.int("SELECT repetitions FROM flashcards WHERE id = 1"))
        }
    }

    @Test
    fun `every pre-existing card is unattributed, which marks it as user-authored`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertCard(id = 1, front = "casa", back = "house", dueAt = 1L)
            conn.insertCard(id = 2, front = "perro", back = "dog", dueAt = 2L)

            MIGRATION_36_37_SQL.forEach { conn.runSql(it) }

            assertTrue("autoSource" in conn.columnsOf("flashcards"))
            assertNull(conn.text("SELECT autoSource FROM flashcards WHERE id = 1"))
            assertNull(conn.text("SELECT autoSource FROM flashcards WHERE id = 2"))
            assertEquals(0, conn.int("SELECT COUNT(*) FROM flashcards WHERE autoSource IS NOT NULL"))
        }
    }

    @Test
    fun `seeded cards can be counted and removed without touching the rest`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertCard(id = 1, front = "libro", back = "book", dueAt = 1L)
            MIGRATION_36_37_SQL.forEach { conn.runSql(it) }
            conn.insertCard(id = 2, front = "ser", back = "to be", dueAt = 2L, autoSource = "freq")
            conn.insertCard(id = 3, front = "estar", back = "to be", dueAt = 3L, autoSource = "freq")

            assertEquals(
                2,
                conn.int("SELECT COUNT(*) FROM flashcards WHERE targetLanguage = 'es' AND autoSource = 'freq'"),
            )
            conn.runSql("DELETE FROM flashcards WHERE targetLanguage = 'es' AND autoSource = 'freq'")
            // The word the user saved themselves is the one that survives.
            assertEquals(1, conn.int("SELECT COUNT(*) FROM flashcards"))
            assertEquals("libro", conn.text("SELECT front FROM flashcards WHERE id = 1"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** The v36 shape of `flashcards`, trimmed to the columns these assertions touch. */
    private fun Connection.createSchema() {
        runSql(
            "CREATE TABLE `flashcards` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`front` TEXT NOT NULL, " +
                "`back` TEXT NOT NULL, " +
                "`contextSentence` TEXT, " +
                "`explanation` TEXT, " +
                "`partOfSpeech` TEXT, " +
                "`cardKind` TEXT NOT NULL, " +
                "`inflectedForms` TEXT, " +
                "`frontIsTarget` INTEGER NOT NULL, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`nativeLanguage` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`dueAt` INTEGER NOT NULL, " +
                "`intervalDays` REAL NOT NULL, " +
                "`easeFactor` REAL NOT NULL, " +
                "`repetitions` INTEGER NOT NULL, " +
                "`lapses` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`stability` REAL, " +
                "`suspended` INTEGER NOT NULL, " +
                "`marked` INTEGER NOT NULL)",
        )
        runSql("CREATE INDEX `index_flashcards_targetLanguage` ON `flashcards` (`targetLanguage`)")
    }

    private fun Connection.insertCard(
        id: Long,
        front: String,
        back: String,
        dueAt: Long,
        autoSource: String? = null,
    ) {
        val extraCol = if (autoSource == null) "" else ", autoSource"
        val extraVal = if (autoSource == null) "" else ", '$autoSource'"
        runSql(
            "INSERT INTO flashcards (id, front, back, cardKind, frontIsTarget, targetLanguage, " +
                "nativeLanguage, createdAt, dueAt, intervalDays, easeFactor, repetitions, lapses, " +
                "state, suspended, marked$extraCol) " +
                "VALUES ($id, '$front', '$back', 'VOCAB', 1, 'es', 'en', 1, $dueAt, 12.5, 2.5, 3, 0, " +
                "'REVIEW', 0, 0$extraVal)",
        )
    }

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_36_37_test").toFile()
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

    private fun Connection.long(sql: String): Long = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }
}

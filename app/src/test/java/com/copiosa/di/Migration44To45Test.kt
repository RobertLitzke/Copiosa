package com.copiosa.di

import com.copiosa.data.local.FLASHCARDS_VIEW_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_44_45_SQL] against real SQLite, on the v43/v44-shaped `notes`, `cards` and
 * `flashcards` view the 42 -> 43 migration creates.
 *
 * The risk is the view: its column list is fixed when it is made, so the new source columns only
 * reach readers if it is dropped and recreated, and Room compares the stored text with
 * [FLASHCARDS_VIEW_SQL] character for character.
 */
class Migration44To45Test {

    @Test
    fun `notes gain both source columns, empty, and keep the source they had`() {
        withDb { conn ->
            conn.createV44Schema()
            conn.insertNote(id = 1, term = "gato", sourceConversationId = 9)

            MIGRATION_44_45_SQL.forEach { conn.runSql(it) }

            val columns = conn.columnsOf("notes")
            assertTrue("sourceBookId" in columns)
            assertTrue("sourceRoutineRunId" in columns)
            assertEquals(9, conn.int("SELECT sourceConversationId FROM notes WHERE id = 1"))
            assertEquals(1, conn.int("SELECT COUNT(*) FROM notes WHERE sourceBookId IS NULL AND sourceRoutineRunId IS NULL"))
        }
    }

    @Test
    fun `the view is recreated with the text Room expects and exposes the new columns`() {
        withDb { conn ->
            conn.createV44Schema()
            conn.insertNote(id = 1, term = "gato")
            // The view is a join, so a note shows up in it only through a card.
            conn.insertCard(id = 11, noteId = 1, direction = "TARGET_FIRST")

            MIGRATION_44_45_SQL.forEach { conn.runSql(it) }

            assertEquals(
                "CREATE VIEW `flashcards` AS $FLASHCARDS_VIEW_SQL",
                conn.text("SELECT sql FROM sqlite_master WHERE type = 'view' AND name = 'flashcards'"),
            )
            conn.runSql("UPDATE notes SET sourceBookId = 4 WHERE id = 1")
            assertEquals(4, conn.int("SELECT sourceBookId FROM flashcards WHERE noteId = 1"))
        }
    }

    @Test
    fun `saved-from picks one card per word from the one source asked about`() {
        withDb { conn ->
            conn.createV44Schema()
            // A word learned both ways: two cards, one note. Another from a different book, and one from a run.
            conn.insertNote(id = 1, term = "gato", createdAt = 100)
            conn.insertNote(id = 2, term = "perro", createdAt = 200)
            conn.insertNote(id = 3, term = "casa", createdAt = 300)
            conn.insertNote(id = 4, term = "luz", createdAt = 400)
            conn.insertCard(id = 11, noteId = 1, direction = "TARGET_FIRST")
            conn.insertCard(id = 12, noteId = 1, direction = "NATIVE_FIRST")
            conn.insertCard(id = 21, noteId = 2, direction = "TARGET_FIRST")
            conn.insertCard(id = 31, noteId = 3, direction = "TARGET_FIRST")
            conn.insertCard(id = 41, noteId = 4, direction = "TARGET_FIRST")

            MIGRATION_44_45_SQL.forEach { conn.runSql(it) }
            conn.runSql("UPDATE notes SET sourceBookId = 7 WHERE id IN (1, 3)")
            conn.runSql("UPDATE notes SET sourceBookId = 8 WHERE id = 2")
            conn.runSql("UPDATE notes SET sourceRoutineRunId = 7 WHERE id = 4")

            // Mirrors FlashcardDao.observeSavedFrom.
            fun savedFrom(conversation: String, book: String, run: String) = conn.ids(
                "SELECT id FROM flashcards WHERE id IN (SELECT MIN(id) FROM flashcards WHERE " +
                    "sourceConversationId = $conversation OR sourceBookId = $book OR " +
                    "sourceRoutineRunId = $run GROUP BY noteId) ORDER BY createdAt DESC, id DESC",
            )

            // Book 7 and routine run 7 share an id; the column, not the number, says which was asked.
            assertEquals(listOf(31L, 11L), savedFrom("NULL", "7", "NULL"))
            assertEquals(listOf(41L), savedFrom("NULL", "NULL", "7"))
            assertEquals(emptyList<Long>(), savedFrom("NULL", "NULL", "NULL"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** `notes`, `cards` and the view exactly as 42 -> 43 left them (the first five statements are the tables and indexes). */
    private fun Connection.createV44Schema() {
        MIGRATION_42_43_SQL.take(5).forEach { runSql(it) }
        runSql("CREATE VIEW `flashcards` AS $FLASHCARDS_VIEW_SQL_V43")
    }

    private fun Connection.insertNote(id: Long, term: String, sourceConversationId: Long? = null, createdAt: Long = 1) = runSql(
        "INSERT INTO notes (id, term, translation, sourceConversationId, cardKind, targetLanguage, nativeLanguage, " +
            "createdAt, marked) VALUES ($id, '$term', 't', ${sourceConversationId ?: "NULL"}, 'VOCAB', 'es', 'en', $createdAt, 0)",
    )

    private fun Connection.insertCard(id: Long, noteId: Long, direction: String) = runSql(
        "INSERT INTO cards (id, noteId, direction, dueAt, intervalDays, easeFactor, repetitions, lapses, state, suspended) " +
            "VALUES ($id, $noteId, '$direction', 0, 0.0, 2.5, 0, 0, 'NEW', 0)",
    )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_44_45_test").toFile()
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

    private fun Connection.int(sql: String): Int = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

    private fun Connection.text(sql: String): String = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    private fun Connection.ids(sql: String): List<Long> = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
    }
}

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
 * Replays [MIGRATION_35_36_SQL] against real SQLite, following the [Migration34To35Test] pattern.
 *
 * Plain ALTERs, so the things worth pinning are the ones a plain ALTER can still get wrong: that an
 * existing playlist row survives untouched (this is the table the user's whole listening queue lives
 * in), that `positionMs` lands as 0 rather than NULL on those old rows — it is declared NOT NULL, so
 * a missing default would make every existing row unreadable — and that the tag index exists, since
 * Room validates the schema against the entity on open and a missing index fails the whole app at
 * startup rather than at the query.
 */
class Migration35To36Test {

    @Test
    fun `existing rows keep their data and gain null grouping columns`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertItem(id = 1, title = "Una respuesta", played = 1)

            MIGRATION_35_36_SQL.forEach { conn.runSql(it) }

            assertEquals("Una respuesta", conn.text("SELECT title FROM audio_items WHERE id = 1"))
            assertEquals(1, conn.int("SELECT played FROM audio_items WHERE id = 1"))
            // A pre-existing item is loose: it belongs to no folder and came from no book.
            assertNull(conn.text("SELECT tag FROM audio_items WHERE id = 1"))
            assertNull(conn.text("SELECT sourceBookId FROM audio_items WHERE id = 1"))
            assertNull(conn.text("SELECT sourceChapterIndex FROM audio_items WHERE id = 1"))
        }
    }

    @Test
    fun `positionMs backfills to zero, which is the old start-from-the-top behaviour`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertItem(id = 1, title = "Capítulo uno", played = 0)

            MIGRATION_35_36_SQL.forEach { conn.runSql(it) }

            assertEquals(0, conn.int("SELECT positionMs FROM audio_items WHERE id = 1"))
        }
    }

    @Test
    fun `the tag index is created`() {
        withDb { conn ->
            conn.createSchema()

            MIGRATION_35_36_SQL.forEach { conn.runSql(it) }

            assertTrue("tag" in conn.columnsOf("audio_items"))
            assertEquals(
                1,
                conn.int(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_audio_items_tag'",
                ),
            )
        }
    }

    @Test
    fun `grouped rows round-trip once the columns exist`() {
        withDb { conn ->
            conn.createSchema()
            MIGRATION_35_36_SQL.forEach { conn.runSql(it) }

            conn.runSql(
                "INSERT INTO audio_items (id, title, text, langCode, sid, status, sortOrder, " +
                    "createdAt, played, tag, sourceBookId, sourceChapterIndex, positionMs) " +
                    "VALUES (2, 'El regreso', 'x', 'es', 0, 'READY', 300.0, 1, 0, " +
                    "'Cien años de soledad', 7, 3, 45000)",
            )

            assertEquals("Cien años de soledad", conn.text("SELECT tag FROM audio_items WHERE id = 2"))
            assertEquals(7, conn.int("SELECT sourceBookId FROM audio_items WHERE id = 2"))
            assertEquals(3, conn.int("SELECT sourceChapterIndex FROM audio_items WHERE id = 2"))
            assertEquals(45000, conn.int("SELECT positionMs FROM audio_items WHERE id = 2"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** The v35 shape of `audio_items`. */
    private fun Connection.createSchema() {
        runSql(
            "CREATE TABLE `audio_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`langCode` TEXT NOT NULL, " +
                "`sid` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`filePath` TEXT, " +
                "`durationMs` INTEGER, " +
                "`played` INTEGER NOT NULL, " +
                "`playedAt` INTEGER, " +
                "`sortOrder` REAL NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`sourceConversationId` INTEGER, " +
                "`sourceMessageId` INTEGER, " +
                "`error` TEXT)",
        )
        runSql("CREATE INDEX `index_audio_items_langCode` ON `audio_items` (`langCode`)")
    }

    private fun Connection.insertItem(id: Long, title: String, played: Int) = runSql(
        "INSERT INTO audio_items (id, title, text, langCode, sid, status, played, sortOrder, createdAt) " +
            "VALUES ($id, '$title', 'cuerpo', 'es', 0, 'READY', $played, 1.0, 1)",
    )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_35_36_test").toFile()
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

package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_40_41_SQL] against real SQLite, following the [Migration38To39Test] pattern.
 *
 * Two ALTERs adding the reader's language version. What matters is that every row that existed
 * before the upgrade comes out reading TARGET — that is exactly what those readers were showing, so
 * a wrong default would silently move a novel into Both mode on the first open after an update —
 * and that the reading position stored beside it, the thing this column now travels with, is
 * untouched by the migration.
 */
class Migration40To41Test {

    @Test
    fun `existing texts and books keep their reading position and start in target`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertConversation(id = 1, title = "El Pais", scrollIndex = 12, scrollOffset = 47)
            conn.insertBook(id = 1, title = "Redwall", posParagraph = 1_790, posOffset = 33)

            MIGRATION_40_41_SQL.forEach { conn.runSql(it) }

            assertTrue("readMode" in conn.columnsOf("conversations"))
            assertTrue("readMode" in conn.columnsOf("books"))
            assertEquals("TARGET", conn.text("SELECT readMode FROM conversations WHERE id = 1"))
            assertEquals("TARGET", conn.text("SELECT readMode FROM books WHERE id = 1"))
            assertEquals(12, conn.int("SELECT scrollIndex FROM conversations WHERE id = 1"))
            assertEquals(47, conn.int("SELECT scrollOffset FROM conversations WHERE id = 1"))
            assertEquals(1_790, conn.int("SELECT posParagraph FROM books WHERE id = 1"))
            assertEquals(33, conn.int("SELECT posOffset FROM books WHERE id = 1"))
        }
    }

    @Test
    fun `no pre-existing row is left in a mode other than target`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertConversation(id = 1, title = "El Pais")
            conn.insertConversation(id = 2, title = "Chat")
            conn.insertBook(id = 1, title = "Redwall")
            conn.insertBook(id = 2, title = "Winnie Pooh")

            MIGRATION_40_41_SQL.forEach { conn.runSql(it) }

            assertEquals(0, conn.int("SELECT COUNT(*) FROM conversations WHERE readMode <> 'TARGET'"))
            assertEquals(0, conn.int("SELECT COUNT(*) FROM books WHERE readMode <> 'TARGET'"))
        }
    }

    @Test
    fun `a chosen mode is stored per item and leaves the others alone`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "Redwall")
            conn.insertBook(id = 2, title = "Winnie Pooh")
            MIGRATION_40_41_SQL.forEach { conn.runSql(it) }

            // BookDao.setReadMode's shape: the enum name, one book at a time.
            conn.runSql("UPDATE books SET readMode = 'BOTH' WHERE id = 1")

            assertEquals("BOTH", conn.text("SELECT readMode FROM books WHERE id = 1"))
            assertEquals("TARGET", conn.text("SELECT readMode FROM books WHERE id = 2"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** The v40 shape of the two tables, trimmed to the columns these assertions touch. */
    private fun Connection.createSchema() {
        runSql(
            "CREATE TABLE `conversations` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`scrollIndex` INTEGER NOT NULL, " +
                "`scrollOffset` INTEGER NOT NULL, " +
                "`finishedReading` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        runSql(
            "CREATE TABLE `books` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`originalTitle` TEXT NOT NULL, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`downloadMode` TEXT NOT NULL, " +
                "`posParagraph` INTEGER NOT NULL, " +
                "`posOffset` INTEGER NOT NULL, " +
                "`finishedReading` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
    }

    private fun Connection.insertConversation(
        id: Long,
        title: String,
        scrollIndex: Int = 0,
        scrollOffset: Int = 0,
    ) = runSql(
        "INSERT INTO conversations (id, title, targetLanguage, scrollIndex, scrollOffset, " +
            "finishedReading, createdAt, updatedAt) " +
            "VALUES ($id, '$title', 'es', $scrollIndex, $scrollOffset, 0, 1, 1)",
    )

    private fun Connection.insertBook(
        id: Long,
        title: String,
        posParagraph: Int = 0,
        posOffset: Int = 0,
    ) = runSql(
        "INSERT INTO books (id, title, originalTitle, targetLanguage, downloadMode, posParagraph, " +
            "posOffset, finishedReading, createdAt, updatedAt) " +
            "VALUES ($id, '$title', '$title', 'es', 'BACKGROUND', $posParagraph, $posOffset, 0, 1, 1)",
    )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_40_41_test").toFile()
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

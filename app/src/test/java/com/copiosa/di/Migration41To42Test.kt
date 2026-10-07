package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_41_42_SQL] against real SQLite, following the [Migration40To41Test] pattern.
 *
 * One ALTER adding `books.lastReadAt`. What matters is that existing books come out with *no* read
 * time rather than a guessed one — a backfill from `updatedAt` would carry over the very ordering
 * this column replaces — and that the reading position it is stamped beside survives untouched.
 */
class Migration41To42Test {

    @Test
    fun `existing books keep their position and have no read time yet`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "Redwall", posParagraph = 1_790, posOffset = 33, updatedAt = 500)

            MIGRATION_41_42_SQL.forEach { conn.runSql(it) }

            assertTrue("lastReadAt" in conn.columnsOf("books"))
            assertEquals(1, conn.int("SELECT COUNT(*) FROM books WHERE lastReadAt IS NULL"))
            assertEquals(1_790, conn.int("SELECT posParagraph FROM books WHERE id = 1"))
            assertEquals(33, conn.int("SELECT posOffset FROM books WHERE id = 1"))
            assertEquals(500, conn.int("SELECT updatedAt FROM books WHERE id = 1"))
        }
    }

    @Test
    fun `saving a position stamps the read time and leaves the translation clock alone`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertBook(id = 1, title = "Redwall", updatedAt = 500)
            conn.insertBook(id = 2, title = "Winnie Pooh", updatedAt = 900)
            MIGRATION_41_42_SQL.forEach { conn.runSql(it) }

            // BookDao.saveScroll's shape.
            conn.runSql("UPDATE books SET posParagraph = 12, posOffset = 4, lastReadAt = 7000 WHERE id = 1")

            assertEquals(7_000, conn.int("SELECT lastReadAt FROM books WHERE id = 1"))
            assertEquals(500, conn.int("SELECT updatedAt FROM books WHERE id = 1"))
            assertEquals(1, conn.int("SELECT COUNT(*) FROM books WHERE id = 2 AND lastReadAt IS NULL"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** The v41 shape of `books`, trimmed to the columns these assertions touch. */
    private fun Connection.createSchema() {
        runSql(
            "CREATE TABLE `books` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`originalTitle` TEXT NOT NULL, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`posParagraph` INTEGER NOT NULL, " +
                "`posOffset` INTEGER NOT NULL, " +
                "`finishedReading` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
    }

    private fun Connection.insertBook(
        id: Long,
        title: String,
        posParagraph: Int = 0,
        posOffset: Int = 0,
        updatedAt: Long = 1,
    ) = runSql(
        "INSERT INTO books (id, title, originalTitle, targetLanguage, posParagraph, posOffset, " +
            "finishedReading, createdAt, updatedAt) " +
            "VALUES ($id, '$title', '$title', 'es', $posParagraph, $posOffset, 0, 1, $updatedAt)",
    )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_41_42_test").toFile()
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
}

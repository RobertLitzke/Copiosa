package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Replays [MIGRATION_45_46_SQL] against real SQLite, following the [Migration43To44Test] pattern.
 *
 * The migration only creates `llm_usage`. What is worth pinning is the key: the meter keeps one
 * running row per (day, provider, model, feature, book, costReported), and `LlmUsageDao.add` relies
 * on the table refusing a second.
 */
class Migration45To46Test {

    @Test
    fun `the table is created empty and existing data is untouched`() {
        withDb { conn ->
            conn.runSql("CREATE TABLE `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL)")
            conn.runSql("INSERT INTO books (title) VALUES ('Rayuela')")

            MIGRATION_45_46_SQL.forEach { conn.runSql(it) }

            assertEquals(0, conn.int("SELECT COUNT(*) FROM llm_usage"))
            assertEquals(1, conn.int("SELECT COUNT(*) FROM books"))
        }
    }

    @Test
    fun `one row per key, and the running-total update finds it`() {
        withDb { conn ->
            MIGRATION_45_46_SQL.forEach { conn.runSql(it) }
            conn.runSql(row(day = 20731, feature = "BOOK", bookId = 7, costReported = 0))

            // The same key again is a conflict rather than a second row.
            val duplicate = runCatching { conn.runSql(row(day = 20731, feature = "BOOK", bookId = 7, costReported = 0)) }
            assertTrue(duplicate.exceptionOrNull() is SQLException)

            // Any one part of the key differing is a different row.
            conn.runSql(row(day = 20732, feature = "BOOK", bookId = 7, costReported = 0))
            conn.runSql(row(day = 20731, feature = "CHAT", bookId = 0, costReported = 0))
            conn.runSql(row(day = 20731, feature = "BOOK", bookId = 8, costReported = 0))
            conn.runSql(row(day = 20731, feature = "BOOK", bookId = 7, costReported = 1))
            assertEquals(5, conn.int("SELECT COUNT(*) FROM llm_usage"))

            // The DAO's update, by key.
            conn.runSql(
                "UPDATE llm_usage SET calls = calls + 1, promptTokens = promptTokens + 50, costUsd = costUsd + 0.25 " +
                    "WHERE day = 20731 AND provider = 'GEMINI' AND model = 'gemini-2.5-flash' AND feature = 'BOOK' " +
                    "AND bookId = 7 AND costReported = 0",
            )
            assertEquals(2, conn.int("SELECT calls FROM llm_usage WHERE day = 20731 AND bookId = 7 AND costReported = 0"))
            assertEquals(150, conn.int("SELECT promptTokens FROM llm_usage WHERE day = 20731 AND bookId = 7 AND costReported = 0"))
            assertEquals(1, conn.int("SELECT calls FROM llm_usage WHERE day = 20731 AND bookId = 7 AND costReported = 1"))
        }
    }

    @Test
    fun `running it twice is harmless`() {
        withDb { conn ->
            MIGRATION_45_46_SQL.forEach { conn.runSql(it) }
            conn.runSql(row(day = 1, feature = "CHAT", bookId = 0, costReported = 0))
            MIGRATION_45_46_SQL.forEach { conn.runSql(it) }
            assertEquals(1, conn.int("SELECT COUNT(*) FROM llm_usage"))
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun row(day: Int, feature: String, bookId: Int, costReported: Int) =
        "INSERT INTO llm_usage (day, provider, model, feature, bookId, costReported, calls, promptTokens, " +
            "cachedTokens, outputTokens, costUsd) VALUES ($day, 'GEMINI', 'gemini-2.5-flash', '$feature', " +
            "$bookId, $costReported, 1, 100, 0, 20, 0.0)"

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_45_46_test").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").absolutePath}").use(block)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.int(sql: String): Int = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }
}

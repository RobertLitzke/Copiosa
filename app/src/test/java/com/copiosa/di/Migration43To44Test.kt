package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_43_44_SQL] against real SQLite, following the [Migration41To42Test] pattern.
 *
 * The migration only drops `suggested_topics`; what matters is that it goes (index included, so a
 * later table of the same name could be created cleanly) and that its neighbours are untouched.
 */
class Migration43To44Test {

    @Test
    fun `suggested_topics and its index are gone, other tables survive`() {
        withDb { conn ->
            conn.createSchema()
            conn.runSql("INSERT INTO suggested_topics (phrase, targetLanguage, score, generatedAt) VALUES ('food', 'es', 1.0, 1)")
            conn.runSql("INSERT INTO topics (phrase) VALUES ('travel')")

            MIGRATION_43_44_SQL.forEach { conn.runSql(it) }

            assertEquals(0, conn.int("SELECT COUNT(*) FROM sqlite_master WHERE name = 'suggested_topics'"))
            assertEquals(
                0,
                conn.int("SELECT COUNT(*) FROM sqlite_master WHERE name = 'index_suggested_topics_phrase_targetLanguage'"),
            )
            assertEquals(1, conn.int("SELECT COUNT(*) FROM topics"))
        }
    }

    @Test
    fun `a database that never had the table migrates cleanly`() {
        withDb { conn ->
            MIGRATION_43_44_SQL.forEach { conn.runSql(it) }

            assertEquals(0, conn.int("SELECT COUNT(*) FROM sqlite_master WHERE name = 'suggested_topics'"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** The v43 shape of `suggested_topics` (see [MIGRATION_23_24_SQL]) beside a table that must survive. */
    private fun Connection.createSchema() {
        runSql(
            "CREATE TABLE `suggested_topics` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`phrase` TEXT NOT NULL, " +
                "`rationale` TEXT, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`score` REAL NOT NULL, " +
                "`generatedAt` INTEGER NOT NULL, " +
                "`dismissedAt` INTEGER)",
        )
        runSql(
            "CREATE UNIQUE INDEX `index_suggested_topics_phrase_targetLanguage` " +
                "ON `suggested_topics` (`phrase`, `targetLanguage`)",
        )
        runSql("CREATE TABLE `topics` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `phrase` TEXT NOT NULL)")
    }

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_43_44_test").toFile()
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

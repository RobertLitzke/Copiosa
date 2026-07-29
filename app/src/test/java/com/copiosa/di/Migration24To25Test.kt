package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_24_25_SQL] against real SQLite, following the [Migration23To24Test] pattern.
 * The migration is purely additive (two new tables), so the checks are shape and constraints.
 */
class Migration24To25Test {

    @Test
    fun `creates both focus tables ready for writes`() {
        withDb { conn ->
            MIGRATION_24_25_SQL.forEach { conn.runSql(it) }

            conn.runSql(
                "INSERT INTO focus_sessions (langTag, activityType, startedAt, durationMs, epochDay, rolledUp) " +
                    "VALUES ('es', 'READING', 1000, 60000, 19700, 0)",
            )
            conn.runSql(
                "INSERT INTO focus_daily_stats (langTag, epochDay, activityType, totalDurationMs, sessionCount) " +
                    "VALUES ('es', 19699, 'READING', 120000, 3)",
            )

            assertEquals(1L, conn.long("SELECT COUNT(*) FROM focus_sessions"))
            assertEquals(1L, conn.long("SELECT id FROM focus_sessions"))
            assertEquals(1L, conn.long("SELECT COUNT(*) FROM focus_daily_stats"))
        }
    }

    @Test
    fun `daily stats are unique per profile, day, and activity`() {
        withDb { conn ->
            MIGRATION_24_25_SQL.forEach { conn.runSql(it) }

            conn.runSql(
                "INSERT INTO focus_daily_stats (langTag, epochDay, activityType, totalDurationMs, sessionCount) " +
                    "VALUES ('es', 19700, 'READING', 1, 1)",
            )
            // Another activity and another profile on the same day are fine.
            conn.runSql(
                "INSERT INTO focus_daily_stats (langTag, epochDay, activityType, totalDurationMs, sessionCount) " +
                    "VALUES ('es', 19700, 'QUIZ', 1, 1)",
            )
            conn.runSql(
                "INSERT INTO focus_daily_stats (langTag, epochDay, activityType, totalDurationMs, sessionCount) " +
                    "VALUES ('fr', 19700, 'READING', 1, 1)",
            )
            val duplicated = runCatching {
                conn.runSql(
                    "INSERT INTO focus_daily_stats (langTag, epochDay, activityType, totalDurationMs, sessionCount) " +
                        "VALUES ('es', 19700, 'READING', 2, 2)",
                )
            }

            assertTrue("expected the composite primary key to reject the duplicate", duplicated.isFailure)
            assertEquals(3L, conn.long("SELECT COUNT(*) FROM focus_daily_stats"))
        }
    }

    @Test
    fun `running the migration twice is harmless`() {
        withDb { conn ->
            MIGRATION_24_25_SQL.forEach { conn.runSql(it) }
            MIGRATION_24_25_SQL.forEach { conn.runSql(it) }
            assertEquals(0L, conn.long("SELECT COUNT(*) FROM focus_sessions"))
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_24_25_test").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").absolutePath}").use(block)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.long(sql: String): Long? = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            if (!rs.next()) return null
            val value = rs.getLong(1)
            if (rs.wasNull()) null else value
        }
    }
}

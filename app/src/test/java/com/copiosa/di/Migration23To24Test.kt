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
 * Replays [MIGRATION_23_24_SQL] against real SQLite, on v23-shaped tables.
 *
 * There is no instrumented test harness in this project, but the migration is the riskiest part of
 * the change — a 23-migration chain with no destructive fallback — so it's driven here through the
 * sqlite-jdbc driver the Anki tests already use.
 */
class Migration23To24Test {

    @Test
    fun `migration adds columns, backfills playedAt, and creates suggested_topics`() {
        withDb { conn ->
            createV23Schema(conn)

            // Two played rows (one of which the sweep should later see) and one never played.
            conn.runSql("INSERT INTO audio_items (id, title, langCode, played, sortOrder, createdAt) VALUES (1, 'a', 'es', 1, 1.0, 1000)")
            conn.runSql("INSERT INTO audio_items (id, title, langCode, played, sortOrder, createdAt) VALUES (2, 'b', 'es', 1, 2.0, 2000)")
            conn.runSql("INSERT INTO audio_items (id, title, langCode, played, sortOrder, createdAt) VALUES (3, 'c', 'es', 0, 3.0, 3000)")
            conn.runSql("INSERT INTO conversations (id, title, targetLanguage, createdAt, updatedAt) VALUES (1, 'chat', 'es', 5, 5)")

            MIGRATION_23_24_SQL.forEach { conn.runSql(it) }

            // Played rows adopt createdAt so no legacy row is immortal; unplayed rows stay null.
            assertEquals(1000L, conn.long("SELECT playedAt FROM audio_items WHERE id = 1"))
            assertEquals(2000L, conn.long("SELECT playedAt FROM audio_items WHERE id = 2"))
            assertNull(conn.long("SELECT playedAt FROM audio_items WHERE id = 3"))

            // Retry bookkeeping defaults so existing chats start with a full budget.
            assertEquals(0L, conn.long("SELECT autoRetryAttempts FROM conversations WHERE id = 1"))
            assertNull(conn.long("SELECT lastAutoRetryAt FROM conversations WHERE id = 1"))

            assertEquals(0L, conn.long("SELECT COUNT(*) FROM suggested_topics"))
        }
    }

    @Test
    fun `suggested_topics deduplicates by phrase and language`() {
        withDb { conn ->
            createV23Schema(conn)
            MIGRATION_23_24_SQL.forEach { conn.runSql(it) }

            conn.runSql("INSERT INTO suggested_topics (phrase, targetLanguage, score, generatedAt) VALUES ('food', 'es', 1.0, 1)")
            // Same phrase in another language is fine; a repeat within one language is not.
            conn.runSql("INSERT INTO suggested_topics (phrase, targetLanguage, score, generatedAt) VALUES ('food', 'fr', 1.0, 1)")
            val duplicated = runCatching {
                conn.runSql("INSERT INTO suggested_topics (phrase, targetLanguage, score, generatedAt) VALUES ('food', 'es', 2.0, 2)")
            }

            assertTrue("expected the unique index to reject the duplicate", duplicated.isFailure)
            assertEquals(2L, conn.long("SELECT COUNT(*) FROM suggested_topics"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** Only the columns the migration reads or writes; the rest of v23 is irrelevant here. */
    private fun createV23Schema(conn: Connection) {
        conn.runSql(
            "CREATE TABLE audio_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, " +
                "langCode TEXT NOT NULL, played INTEGER NOT NULL, sortOrder REAL NOT NULL, " +
                "createdAt INTEGER NOT NULL)",
        )
        conn.runSql(
            "CREATE TABLE conversations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, " +
                "targetLanguage TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
        )
    }

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_23_24_test").toFile()
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


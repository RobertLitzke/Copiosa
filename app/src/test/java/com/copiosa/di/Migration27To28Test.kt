package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_27_28_SQL] against real SQLite, following the [Migration23To24Test] pattern. The
 * migration is a single additive column on `messages`, so the checks are that the column lands
 * nullable-defaulting-null and accepts writes without disturbing existing rows.
 */
class Migration27To28Test {

    /** The pre-migration `messages` shape (enough columns to exercise the new one). */
    private fun Connection.createLegacyMessages() = runSql(
        "CREATE TABLE messages (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "conversationId INTEGER NOT NULL, " +
            "role TEXT NOT NULL, " +
            "content TEXT NOT NULL, " +
            "createdAt INTEGER NOT NULL, " +
            "metadata TEXT)",
    )

    @Test
    fun `existing rows default the new column to null and new rows accept raw output`() {
        withDb { conn ->
            conn.createLegacyMessages()
            conn.runSql(
                "INSERT INTO messages (conversationId, role, content, createdAt) " +
                    "VALUES (1, 'ASSISTANT', 'hola', 1000)",
            )

            MIGRATION_27_28_SQL.forEach { conn.runSql(it) }

            // The pre-existing row's rawResponse is null.
            assertNull(conn.string("SELECT rawResponse FROM messages WHERE id = 1"))

            // New rows can carry raw output.
            conn.runSql(
                "INSERT INTO messages (conversationId, role, content, createdAt, rawResponse) " +
                    "VALUES (1, 'ASSISTANT', 'body', 2000, 'body<<<COPIOSA_META>>>{}')",
            )
            assertEquals("body<<<COPIOSA_META>>>{}", conn.string("SELECT rawResponse FROM messages WHERE id = 2"))
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_27_28_test").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").absolutePath}").use(block)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.string(sql: String): String? = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            if (!rs.next()) return null
            rs.getString(1)
        }
    }
}

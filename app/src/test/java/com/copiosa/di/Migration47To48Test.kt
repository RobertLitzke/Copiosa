package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_47_48_SQL] against real SQLite, following the [Migration32To33Test] pattern.
 * Only notes whose cached highlight forms hold a half-bracketed hint are cleared; every other cache
 * is left alone so the repair doesn't re-run the dictionary over the whole deck.
 */
class Migration47To48Test {

    @Test
    fun `clears forms that carry a bracketed hint and keeps the rest`() {
        withDb { conn ->
            conn.runSql("CREATE TABLE notes (`id` INTEGER PRIMARY KEY NOT NULL, `inflectedForms` TEXT)")
            conn.runSql("INSERT INTO notes VALUES (1, 'gato;gatos')")
            conn.runSql("INSERT INTO notes VALUES (2, 'pasa (fruta')")
            conn.runSql("INSERT INTO notes VALUES (3, NULL)")

            MIGRATION_47_48_SQL.forEach { conn.runSql(it) }

            assertEquals("gato;gatos", conn.text("SELECT inflectedForms FROM notes WHERE id = 1"))
            assertEquals(null, conn.text("SELECT inflectedForms FROM notes WHERE id = 2"))
            assertEquals(null, conn.text("SELECT inflectedForms FROM notes WHERE id = 3"))
        }
    }

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_47_48_test").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").absolutePath}").use(block)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.text(sql: String): String? = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            if (!rs.next()) return null
            rs.getString(1)
        }
    }
}

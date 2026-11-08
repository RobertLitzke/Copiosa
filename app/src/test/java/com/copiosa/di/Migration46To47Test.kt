package com.copiosa.di

import com.copiosa.domain.listening.RadioScript
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_46_47_SQL] against real SQLite, following the [Migration45To46Test] pattern.
 *
 * The migration only adds columns. What is worth pinning is their defaults: every row already in the
 * playlist is prose, and the render queue reads anything that is not a radio script as prose — so a
 * default that drifted from the entity's would silently change how old tracks re-render. Likewise no
 * banked sentence was pinned or machine-translated before the flags existed.
 */
class Migration46To47Test {

    @Test
    fun `existing playlist rows become articles and keep everything else`() {
        withDb { conn ->
            conn.createV46Tables()
            conn.runSql("INSERT INTO audio_items (title, text, langCode, tag) VALUES ('Capítulo 1', 'Era de noche.', 'es', 'Rayuela')")

            MIGRATION_46_47_SQL.forEach { conn.runSql(it) }

            assertEquals(RadioScript.KIND_ARTICLE, conn.text("SELECT kind FROM audio_items WHERE id = 1"))
            assertEquals("Rayuela", conn.text("SELECT tag FROM audio_items WHERE id = 1"))
        }
    }

    @Test
    fun `a radio row keeps the kind it was written with`() {
        withDb { conn ->
            conn.createV46Tables()
            MIGRATION_46_47_SQL.forEach { conn.runSql(it) }

            conn.runSql(
                "INSERT INTO audio_items (title, text, langCode, kind) " +
                    "VALUES ('Radio', 'Hola.\tHello.', 'es', '${RadioScript.KIND}')",
            )
            conn.runSql("INSERT INTO audio_items (title, text, langCode) VALUES ('Nota', 'Hola.', 'es')")

            assertEquals(RadioScript.KIND, conn.text("SELECT kind FROM audio_items WHERE title = 'Radio'"))
            assertEquals(RadioScript.KIND_ARTICLE, conn.text("SELECT kind FROM audio_items WHERE title = 'Nota'"))
        }
    }

    @Test
    fun `banked sentences start unpinned and with their translations taken as given`() {
        withDb { conn ->
            conn.createV46Tables()
            conn.runSql("INSERT INTO cloze_sentences (text, translation) VALUES ('El {{c1::gato}} duerme.', 'The cat sleeps.')")

            MIGRATION_46_47_SQL.forEach { conn.runSql(it) }

            assertEquals("0", conn.text("SELECT pinned FROM cloze_sentences WHERE id = 1"))
            assertEquals("0", conn.text("SELECT machineTranslated FROM cloze_sentences WHERE id = 1"))
            assertEquals("The cat sleeps.", conn.text("SELECT translation FROM cloze_sentences WHERE id = 1"))
        }
    }

    // --- helpers -----------------------------------------------------------

    /** The two tables the migration touches, cut down to the columns the assertions read. */
    private fun Connection.createV46Tables() {
        runSql(
            "CREATE TABLE `audio_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `text` TEXT NOT NULL, `langCode` TEXT NOT NULL, `tag` TEXT)",
        )
        runSql(
            "CREATE TABLE `cloze_sentences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, `translation` TEXT)",
        )
    }

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_46_47_test").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").absolutePath}").use(block)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.text(sql: String): String = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getString(1)
        }
    }
}

package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_28_29_SQL] against real SQLite, following the [Migration24To25Test] pattern.
 * The migration is purely additive (the drill's two tables), so the checks are shape and constraints.
 *
 * This proves the statements *run*, not that Room accepts the resulting schema — the identity-hash
 * check only happens on a real device upgrade, so that still needs one manual pass.
 */
class Migration28To29Test {

    @Test
    fun `creates both conjugation tables ready for writes`() {
        withDb { conn ->
            MIGRATION_28_29_SQL.forEach { conn.runSql(it) }

            conn.runSql(
                "INSERT INTO conjugation_item_stats " +
                    "(itemKey, targetLanguage, attempts, correct, mastery, avgLatencyMs) " +
                    "VALUES ('1|produce|tener||pres.1s', 'es', 3, 2, 0.42, 1800)",
            )
            conn.runSql(
                "INSERT INTO conjugation_attempts " +
                    "(itemKey, infinitive, tense, person, direction, family, targetLanguage, " +
                    " grade, correct, latencyMs, createdAt, epochDay) " +
                    "VALUES ('1|produce|tener||pres.1s', 'tener', 'pres', '1s', 'produce', 'cj_yo', " +
                    " 'es', 'got_it', 1, 1740, 1700000000000, 19700)",
            )

            assertEquals(1L, conn.long("SELECT COUNT(*) FROM conjugation_item_stats"))
            assertEquals(1L, conn.long("SELECT id FROM conjugation_item_stats"))
            assertEquals(1L, conn.long("SELECT COUNT(*) FROM conjugation_attempts"))
            // Nullable columns stay nullable.
            assertEquals(null, conn.long("SELECT lastSeenAt FROM conjugation_item_stats"))
        }
    }

    @Test
    fun `an item's stats are unique per profile`() {
        withDb { conn ->
            MIGRATION_28_29_SQL.forEach { conn.runSql(it) }
            fun insert(key: String, lang: String) = conn.runSql(
                "INSERT INTO conjugation_item_stats " +
                    "(itemKey, targetLanguage, attempts, correct, mastery, avgLatencyMs) " +
                    "VALUES ('$key', '$lang', 1, 1, 0.3, 0)",
            )

            insert("1|produce|tener||pres.1s", "es")
            // The same item under another profile is a different row...
            insert("1|produce|tener||pres.1s", "fr")
            // ...and so is a different item under the same one.
            insert("1|decode|tener||pres.1s", "es")
            val duplicated = runCatching { insert("1|produce|tener||pres.1s", "es") }

            assertTrue("expected the unique index to reject the duplicate", duplicated.isFailure)
            assertEquals(3L, conn.long("SELECT COUNT(*) FROM conjugation_item_stats"))
        }
    }

    @Test
    fun `creates the indices the stats and selection queries rely on`() {
        withDb { conn ->
            MIGRATION_28_29_SQL.forEach { conn.runSql(it) }
            listOf(
                "index_conjugation_item_stats_itemKey_targetLanguage",
                "index_conjugation_attempts_targetLanguage_epochDay",
                "index_conjugation_attempts_itemKey",
            ).forEach { name ->
                assertEquals(
                    "missing index $name",
                    1L,
                    conn.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '$name'"),
                )
            }
        }
    }

    @Test
    fun `running the migration twice is harmless`() {
        withDb { conn ->
            MIGRATION_28_29_SQL.forEach { conn.runSql(it) }
            MIGRATION_28_29_SQL.forEach { conn.runSql(it) }
            assertEquals(0L, conn.long("SELECT COUNT(*) FROM conjugation_attempts"))
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_28_29_test").toFile()
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

package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * Replays [MIGRATION_37_38_SQL] against real SQLite, following the [Migration36To37Test] pattern.
 *
 * Three brand-new tables, so what is worth pinning is the shape the code will rely on: the columns
 * exist under their frozen names, the two unique indexes actually enforce their uniqueness, and the
 * migration is idempotent-by-construction (`IF NOT EXISTS` throughout) so a partially-applied run
 * can be replayed.
 */
class Migration37To38Test {

    @Test
    fun `creates the three course tables with the expected columns`() {
        withDb { conn ->
            MIGRATION_37_38_SQL.forEach { conn.runSql(it) }

            assertEquals(
                listOf(
                    "id", "courseId", "targetLanguage", "status", "startedAt", "graduatedAt",
                    "checkupStage", "nextCheckupAt", "lastCheckupAt",
                ),
                conn.columnsOf("course_progress"),
            )
            assertEquals(
                listOf(
                    "id", "courseId", "lessonId", "targetLanguage", "completedAt", "passes",
                    "lastAccuracy", "needsReview", "generatedItemsJson",
                ),
                conn.columnsOf("course_lesson_state"),
            )
            assertEquals(
                listOf(
                    "id", "courseId", "lessonId", "kind", "targetLanguage", "status", "planJson",
                    "currentIndex", "createdAt", "completedAt", "error",
                ),
                conn.columnsOf("course_sessions"),
            )
        }
    }

    @Test
    fun `one progress row per course and language is enforced`() {
        withDb { conn ->
            MIGRATION_37_38_SQL.forEach { conn.runSql(it) }
            conn.insertProgress("es_preterite", "es")
            conn.insertProgress("es_preterite", "pt") // same course, other profile: fine
            try {
                conn.insertProgress("es_preterite", "es")
                fail("duplicate (courseId, targetLanguage) must be rejected")
            } catch (_: java.sql.SQLException) {
                // expected
            }
            assertEquals(2, conn.int("SELECT COUNT(*) FROM course_progress"))
        }
    }

    @Test
    fun `one lesson-state row per lesson is enforced`() {
        withDb { conn ->
            MIGRATION_37_38_SQL.forEach { conn.runSql(it) }
            conn.insertLessonState("es_preterite", "regular_ar", "es")
            conn.insertLessonState("es_preterite", "regular_er_ir", "es")
            try {
                conn.insertLessonState("es_preterite", "regular_ar", "es")
                fail("duplicate (courseId, lessonId, targetLanguage) must be rejected")
            } catch (_: java.sql.SQLException) {
                // expected
            }
            assertEquals(2, conn.int("SELECT COUNT(*) FROM course_lesson_state"))
        }
    }

    @Test
    fun `sessions accept a null lessonId for checkups and replaying the migration is harmless`() {
        withDb { conn ->
            MIGRATION_37_38_SQL.forEach { conn.runSql(it) }
            // A partially-applied migration gets replayed on the next open.
            MIGRATION_37_38_SQL.forEach { conn.runSql(it) }

            conn.runSql(
                "INSERT INTO course_sessions (courseId, lessonId, kind, targetLanguage, status, " +
                    "currentIndex, createdAt) " +
                    "VALUES ('es_preterite', NULL, 'CHECKUP', 'es', 'READY', 0, 5)",
            )
            assertEquals(
                1,
                conn.int("SELECT COUNT(*) FROM course_sessions WHERE lessonId IS NULL AND kind = 'CHECKUP'"),
            )
            assertTrue("planJson" in conn.columnsOf("course_sessions"))
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun Connection.insertProgress(courseId: String, lang: String) {
        runSql(
            "INSERT INTO course_progress (courseId, targetLanguage, status, startedAt, checkupStage) " +
                "VALUES ('$courseId', '$lang', 'ACTIVE', 1, 0)",
        )
    }

    private fun Connection.insertLessonState(courseId: String, lessonId: String, lang: String) {
        runSql(
            "INSERT INTO course_lesson_state (courseId, lessonId, targetLanguage, passes, needsReview) " +
                "VALUES ('$courseId', '$lessonId', '$lang', 0, 0)",
        )
    }

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_37_38_test").toFile()
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

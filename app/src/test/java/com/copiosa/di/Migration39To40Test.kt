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
 * Replays [MIGRATION_39_40_SQL] against real SQLite, following the [Migration38To39Test] pattern.
 *
 * Two new tables, so what is worth pinning is what the feature silently depends on: the unique index
 * that makes every background miner idempotent (and makes a thrown-out sentence stay thrown out),
 * the cascade that stops orphan answer rows accumulating, and — most important on an upgrade — that
 * a live deck and its review history come through untouched.
 */
class Migration39To40Test {

    @Test
    fun `an existing deck and its review history are untouched`() {
        withDb { conn ->
            conn.createSchema()
            conn.insertCard(id = 1, front = "gato", interval = 21.0, reps = 4)
            conn.runSql(
                "INSERT INTO reviews (id, cardId, reviewedAt, rating, lastIntervalDays, intervalDays, " +
                    "easeFactor, state) VALUES (1, 1, 150, 'GOOD', 10.0, 21.0, 2.5, 'REVIEW')",
            )

            MIGRATION_39_40_SQL.forEach { conn.runSql(it) }

            assertEquals("gato", conn.text("SELECT front FROM flashcards WHERE id = 1"))
            assertEquals(21, conn.int("SELECT intervalDays FROM flashcards WHERE id = 1"))
            assertEquals(4, conn.int("SELECT repetitions FROM flashcards WHERE id = 1"))
            assertEquals(1, conn.int("SELECT COUNT(*) FROM reviews WHERE cardId = 1"))
        }
    }

    @Test
    fun `creates both cloze tables with the expected columns`() {
        withDb { conn ->
            conn.createSchema()
            MIGRATION_39_40_SQL.forEach { conn.runSql(it) }

            val sentences = conn.columnsOf("cloze_sentences")
            assertTrue(
                "missing columns in $sentences",
                sentences.containsAll(
                    listOf(
                        "id", "targetLanguage", "text", "answer", "lemma", "textKey", "cardId",
                        "translation", "distractors", "source", "suspended", "flaggedReason",
                        "dueAt", "intervalDays", "stability", "difficulty", "fsrsStep",
                        "repetitions", "lapses", "lastReviewedAt", "state",
                        "ankiNoteGuid", "ankiOrd", "createdAt",
                    ),
                ),
            )
            val reviews = conn.columnsOf("cloze_reviews")
            assertTrue(
                "missing columns in $reviews",
                reviews.containsAll(
                    listOf(
                        "id", "clozeId", "reviewedAt", "rating", "lastIntervalDays", "intervalDays",
                        "stabilityAfter", "difficultyAfter", "state", "latencyMs",
                    ),
                ),
            )
        }
    }

    /**
     * The miners insert with IGNORE against this index, which is the whole of their idempotency: a
     * background pass may rescan the same book every few hours and must add nothing. It is also what
     * makes a suspended row a tombstone — without the conflict, a sentence the learner threw out
     * would come straight back on the next pass.
     */
    @Test
    fun `the same sentence cannot be banked twice for one lemma`() {
        withDb { conn ->
            conn.createSchema()
            MIGRATION_39_40_SQL.forEach { conn.runSql(it) }
            conn.insertCloze(id = 1, lemma = "gato", textKey = "el gato duerme en la casa")

            var rejected = false
            try {
                conn.insertCloze(id = 2, lemma = "gato", textKey = "el gato duerme en la casa")
            } catch (e: SQLException) {
                rejected = true
            }
            assertTrue("a duplicate sentence should violate the unique index", rejected)

            // A different sentence, lemma or language is a different row and must still be allowed.
            conn.insertCloze(id = 3, lemma = "gato", textKey = "el gato come pescado")
            conn.insertCloze(id = 4, lemma = "perro", textKey = "el gato duerme en la casa")
            conn.insertCloze(id = 5, lemma = "gato", textKey = "el gato duerme en la casa", lang = "pt")
            assertEquals(4, conn.int("SELECT COUNT(*) FROM cloze_sentences"))
        }
    }

    @Test
    fun `deleting a sentence takes its answers with it`() {
        withDb { conn ->
            conn.createSchema()
            MIGRATION_39_40_SQL.forEach { conn.runSql(it) }
            conn.runSql("PRAGMA foreign_keys = ON")
            conn.insertCloze(id = 1, lemma = "gato", textKey = "el gato duerme en la casa")
            conn.runSql(
                "INSERT INTO cloze_reviews (clozeId, reviewedAt, rating, lastIntervalDays, intervalDays, " +
                    "state) VALUES (1, 500, 'GOOD', 0.0, 3.0, 'LEARNING')",
            )
            assertEquals(1, conn.int("SELECT COUNT(*) FROM cloze_reviews"))

            conn.runSql("DELETE FROM cloze_sentences WHERE id = 1")

            assertEquals(0, conn.int("SELECT COUNT(*) FROM cloze_reviews"))
        }
    }

    /**
     * A sentence outlives the card it practises. `cardId` carries no foreign key precisely so that
     * deleting a vocab card leaves the sentence drillable — it only stops nudging.
     */
    @Test
    fun `a sentence survives its vocab card being deleted`() {
        withDb { conn ->
            conn.createSchema()
            MIGRATION_39_40_SQL.forEach { conn.runSql(it) }
            conn.runSql("PRAGMA foreign_keys = ON")
            conn.insertCard(id = 9, front = "gato", interval = 0.0, reps = 0)
            conn.insertCloze(id = 1, lemma = "gato", textKey = "el gato duerme en la casa", cardId = 9)

            conn.runSql("DELETE FROM flashcards WHERE id = 9")

            assertEquals(1, conn.int("SELECT COUNT(*) FROM cloze_sentences WHERE id = 1"))
            assertEquals(9, conn.int("SELECT cardId FROM cloze_sentences WHERE id = 1"))
        }
    }

    // --- fixtures -----------------------------------------------------------

    /** Only the tables this migration touches or must leave alone. */
    private fun Connection.createSchema() {
        runSql(
            "CREATE TABLE flashcards (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, front TEXT NOT NULL, back TEXT NOT NULL, " +
                "targetLanguage TEXT NOT NULL, nativeLanguage TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                "dueAt INTEGER NOT NULL, intervalDays REAL NOT NULL, easeFactor REAL NOT NULL, " +
                "repetitions INTEGER NOT NULL, lapses INTEGER NOT NULL, state TEXT NOT NULL, " +
                "suspended INTEGER NOT NULL, marked INTEGER NOT NULL, cardKind TEXT NOT NULL, " +
                "frontIsTarget INTEGER NOT NULL, reviewDirection TEXT NOT NULL, autoSource TEXT, " +
                "imageFile TEXT, audioFile TEXT, ankiNoteGuid TEXT, ankiOrd INTEGER)",
        )
        runSql(
            "CREATE TABLE reviews (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardId INTEGER NOT NULL, " +
                "reviewedAt INTEGER NOT NULL, rating TEXT NOT NULL, lastIntervalDays REAL NOT NULL, " +
                "intervalDays REAL NOT NULL, stabilityAfter REAL, difficultyAfter REAL, " +
                "easeFactor REAL NOT NULL, state TEXT NOT NULL, reviewDurationMs INTEGER, " +
                "FOREIGN KEY(cardId) REFERENCES flashcards(id) ON DELETE CASCADE)",
        )
    }

    private fun Connection.insertCard(id: Long, front: String, interval: Double, reps: Int) = runSql(
        "INSERT INTO flashcards (id, front, back, targetLanguage, nativeLanguage, createdAt, dueAt, " +
            "intervalDays, easeFactor, repetitions, lapses, state, suspended, marked, cardKind, " +
            "frontIsTarget, reviewDirection) VALUES ($id, '$front', 'cat', 'es', 'en', 100, 200, " +
            "$interval, 2.5, $reps, 0, 'REVIEW', 0, 0, 'VOCAB', 1, 'BOTH')",
    )

    private fun Connection.insertCloze(
        id: Long,
        lemma: String,
        textKey: String,
        lang: String = "es",
        cardId: Long? = null,
    ) = runSql(
        // Every NOT NULL column is supplied: the migration declares no SQL defaults (Room compares
        // them when it validates the schema), so Room's own inserts name every column too.
        "INSERT INTO cloze_sentences (id, targetLanguage, text, answer, lemma, textKey, cardId, " +
            "source, suspended, dueAt, intervalDays, repetitions, lapses, state, createdAt) " +
            "VALUES ($id, '$lang', 'el ...', '$lemma', '$lemma', '$textKey', " +
            "${cardId?.toString() ?: "NULL"}, 'context', 0, 0, 0.0, 0, 0, 'NEW', 0)",
    )

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_39_40_test").toFile()
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

    private fun Connection.text(sql: String): String? = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            if (!rs.next()) return null
            rs.getString(1)
        }
    }

    private fun Connection.int(sql: String): Int = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }
}

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
 * Replays [MIGRATION_42_43_SQL] against real SQLite, following the [Migration41To42Test] pattern.
 *
 * This is the migration that splits `flashcards` into `notes` and `cards`, and unlike the ALTERs
 * around it, it rewrites every vocabulary row. So the tests are about what must come out the other
 * side unchanged — card ids, schedules, every review — and about each merge decision being the one
 * the migration's comment promises. Foreign keys are enforced throughout, as they are on device.
 */
class Migration42To43Test {

    private val day = 86_400_000L

    @Test
    fun `a forward and reverse pair becomes one note with two cards, ids and schedules intact`() {
        withDb { conn ->
            conn.insertCard(id = 10, front = "mantel", back = "tablecloth", guid = "g1", ord = 0, dueAt = 111, lapses = 1, stability = 9.5)
            conn.insertCard(
                id = 11, front = "tablecloth", back = "mantel", frontIsTarget = 0, direction = "NATIVE_FIRST",
                guid = "g1", ord = 1, dueAt = 222, lapses = 4, stability = 2.5,
            )
            conn.insertReview(id = 1, cardId = 10)
            conn.insertReview(id = 2, cardId = 11)
            conn.insertReview(id = 3, cardId = 11)

            conn.migrate()

            assertEquals(1, conn.int("SELECT COUNT(*) FROM notes"))
            assertEquals("mantel", conn.text("SELECT term FROM notes WHERE id = 10"))
            assertEquals("tablecloth", conn.text("SELECT translation FROM notes WHERE id = 10"))
            assertEquals(2, conn.int("SELECT COUNT(*) FROM cards WHERE noteId = 10"))
            assertEquals("TARGET_FIRST", conn.text("SELECT direction FROM cards WHERE id = 10"))
            assertEquals("NATIVE_FIRST", conn.text("SELECT direction FROM cards WHERE id = 11"))
            assertEquals(111, conn.int("SELECT dueAt FROM cards WHERE id = 10"))
            assertEquals(222, conn.int("SELECT dueAt FROM cards WHERE id = 11"))
            assertEquals(4, conn.int("SELECT lapses FROM cards WHERE id = 11"))
            assertEquals("2.5", conn.text("SELECT stability FROM cards WHERE id = 11"))
            // Every review still hangs off the card it was a review of.
            assertEquals(1, conn.int("SELECT COUNT(*) FROM reviews WHERE cardId = 10"))
            assertEquals(2, conn.int("SELECT COUNT(*) FROM reviews WHERE cardId = 11"))
        }
    }

    @Test
    fun `a merged note takes the term-first row's content, falling back where it is blank`() {
        withDb { conn ->
            conn.insertCard(
                id = 10, front = "fruta", back = "", guid = "g1", ord = 0,
                example = "Como fruta.", pos = "unknown", explanation = null, marked = 0,
            )
            conn.insertCard(
                id = 11, front = "fruit", back = "fruta", frontIsTarget = 0, direction = "NATIVE_FIRST", guid = "g1", ord = 1,
                example = "La fruta madura.", pos = "noun", explanation = "A sweet thing.", marked = 1, image = "fruta.jpg",
            )

            conn.migrate()

            // A row that only ever held a picture takes its text from its twin.
            assertEquals("fruit", conn.text("SELECT translation FROM notes WHERE id = 10"))
            assertEquals("Como fruta.", conn.text("SELECT contextSentence FROM notes WHERE id = 10"))
            assertEquals("noun", conn.text("SELECT partOfSpeech FROM notes WHERE id = 10"))
            assertEquals("A sweet thing.", conn.text("SELECT explanation FROM notes WHERE id = 10"))
            assertEquals(1, conn.int("SELECT marked FROM notes WHERE id = 10"))
            assertEquals("fruta.jpg", conn.text("SELECT imageFile FROM notes WHERE id = 10"))
        }
    }

    @Test
    fun `a lone reverse row is stored the right way round and gains its other direction`() {
        withDb { conn ->
            conn.insertCard(
                id = 20, front = "ladle", back = "cucharón", frontIsTarget = 0, direction = "NATIVE_FIRST",
                guid = "g2", ord = 1, dueAt = 500, state = "REVIEW", suspended = 1,
            )

            conn.migrate()

            assertEquals("cucharón", conn.text("SELECT term FROM notes WHERE id = 20"))
            assertEquals("ladle", conn.text("SELECT translation FROM notes WHERE id = 20"))
            assertEquals("NATIVE_FIRST", conn.text("SELECT direction FROM cards WHERE id = 20"))
            assertEquals(500, conn.int("SELECT dueAt FROM cards WHERE id = 20"))
            // The new card: never studied, frozen like its sibling, and on the template the old one left free.
            assertEquals(1, conn.int("SELECT COUNT(*) FROM cards WHERE noteId = 20 AND id <> 20"))
            assertEquals("TARGET_FIRST", conn.text("SELECT direction FROM cards WHERE noteId = 20 AND id <> 20"))
            assertEquals("NEW", conn.text("SELECT state FROM cards WHERE noteId = 20 AND id <> 20"))
            assertEquals(1, conn.int("SELECT suspended FROM cards WHERE noteId = 20 AND id <> 20"))
            assertEquals(0, conn.int("SELECT ankiOrd FROM cards WHERE noteId = 20 AND id <> 20"))
            assertNull(conn.text("SELECT stability FROM cards WHERE noteId = 20 AND id <> 20"))
        }
    }

    @Test
    fun `a BOTH card splits in two on a copy of its schedule`() {
        withDb { conn ->
            conn.insertCard(
                id = 30, front = "prestar", back = "to lend", direction = "BOTH",
                dueAt = 900, state = "REVIEW", lapses = 2, stability = 12.0, repetitions = 7,
            )
            conn.insertReview(id = 1, cardId = 30)

            conn.migrate()

            assertEquals(2, conn.int("SELECT COUNT(*) FROM cards WHERE noteId = 30"))
            assertEquals("TARGET_FIRST", conn.text("SELECT direction FROM cards WHERE id = 30"))
            val copy = "FROM cards WHERE noteId = 30 AND id <> 30"
            assertEquals("NATIVE_FIRST", conn.text("SELECT direction $copy"))
            assertEquals(900, conn.int("SELECT dueAt $copy"))
            assertEquals("REVIEW", conn.text("SELECT state $copy"))
            assertEquals(2, conn.int("SELECT lapses $copy"))
            assertEquals(7, conn.int("SELECT repetitions $copy"))
            assertEquals("12.0", conn.text("SELECT stability $copy"))
            // The history is one card's: it stays with the row it was recorded against.
            assertEquals(1, conn.int("SELECT COUNT(*) FROM reviews WHERE cardId = 30"))
            assertEquals(1, conn.int("SELECT COUNT(*) FROM reviews"))
        }
    }

    @Test
    fun `cards with one direction by nature stay single`() {
        withDb { conn ->
            conn.insertCard(id = 40, front = "Ayer {{c1::fui}} al cine", back = "", kind = "GRAMMAR", direction = "BOTH")
            conn.insertCard(
                id = 41, front = "hablar — yo, pretérito", back = "hablé", kind = "CONJUGATION",
                frontIsTarget = 0, direction = "NATIVE_FIRST", guid = "g4", ord = 0,
            )
            conn.insertCard(id = 42, front = "Voy {{c1::a}} casa", back = "", kind = "CLOZE")

            conn.migrate()

            assertEquals(3, conn.int("SELECT COUNT(*) FROM notes"))
            assertEquals(3, conn.int("SELECT COUNT(*) FROM cards"))
            assertEquals("TARGET_FIRST", conn.text("SELECT direction FROM cards WHERE id = 40"))
            assertEquals("NATIVE_FIRST", conn.text("SELECT direction FROM cards WHERE id = 41"))
            assertEquals("hablé", conn.text("SELECT term FROM notes WHERE id = 41"))
        }
    }

    @Test
    fun `new directions are released ten a day rather than all at once`() {
        withDb { conn ->
            for (i in 1..25) conn.insertCard(id = 100L + i, front = "w$i", back = "t$i")

            val before = System.currentTimeMillis()
            conn.migrate()

            val offsets = conn.longs("SELECT dueAt FROM cards WHERE id > 125 ORDER BY noteId").map { (it - before + 2_000) / day }
            assertEquals(25, offsets.size)
            assertEquals(List(10) { 0L } + List(10) { 1L } + List(5) { 2L }, offsets)
        }
    }

    @Test
    fun `a guid that is not a clean pair is left as a note per row`() {
        withDb { conn ->
            // Three cards off one guid, and two facing the same way off another: neither is guessed at.
            conn.insertCard(id = 50, front = "a", back = "b", guid = "g5", ord = 0, kind = "CONJUGATION")
            conn.insertCard(id = 51, front = "b", back = "a", frontIsTarget = 0, direction = "NATIVE_FIRST", guid = "g5", ord = 1, kind = "CONJUGATION")
            conn.insertCard(id = 52, front = "a", back = "c", guid = "g5", ord = 2, kind = "CONJUGATION")
            conn.insertCard(id = 60, front = "x", back = "y", guid = "g6", ord = 0, kind = "CONJUGATION")
            conn.insertCard(id = 61, front = "x", back = "z", guid = "g6", ord = 1, kind = "CONJUGATION")
            // The same guid in another language profile is another note entirely.
            conn.insertCard(id = 70, front = "casa", back = "house", guid = "g7", ord = 0, kind = "CONJUGATION")
            conn.insertCard(id = 71, front = "house", back = "casa", frontIsTarget = 0, direction = "NATIVE_FIRST", guid = "g7", ord = 1, lang = "pt", kind = "CONJUGATION")

            conn.migrate()

            assertEquals(7, conn.int("SELECT COUNT(*) FROM notes"))
            assertEquals(7, conn.int("SELECT COUNT(*) FROM cards"))
            assertEquals(7, conn.int("SELECT COUNT(*) FROM cards WHERE noteId = id"))
        }
    }

    @Test
    fun `a paired row that had been set to BOTH does not collide with its twin`() {
        withDb { conn ->
            conn.insertCard(id = 80, front = "gato", back = "cat", guid = "g8", ord = 0)
            conn.insertCard(id = 81, front = "cat", back = "gato", frontIsTarget = 0, direction = "BOTH", guid = "g8", ord = 1)

            conn.migrate()

            assertEquals(2, conn.int("SELECT COUNT(*) FROM cards WHERE noteId = 80"))
            assertEquals("NATIVE_FIRST", conn.text("SELECT direction FROM cards WHERE id = 81"))
        }
    }

    @Test
    fun `the flashcards view reads back what the table used to hold`() {
        withDb { conn ->
            conn.insertCard(id = 10, front = "mantel", back = "tablecloth", guid = "g1", ord = 0, dueAt = 111)
            conn.insertCard(id = 11, front = "tablecloth", back = "mantel", frontIsTarget = 0, direction = "NATIVE_FIRST", guid = "g1", ord = 1, dueAt = 222)

            conn.migrate()

            // The stored definition is what Room compares against, character for character.
            assertEquals(
                "CREATE VIEW `flashcards` AS $FLASHCARDS_VIEW_SQL_V43",
                conn.text("SELECT sql FROM sqlite_master WHERE type = 'view' AND name = 'flashcards'"),
            )
            assertEquals(2, conn.int("SELECT COUNT(*) FROM flashcards"))
            // Both cards now read the term on the front; the direction is what tells them apart.
            assertEquals("mantel", conn.text("SELECT front FROM flashcards WHERE id = 11"))
            assertEquals("tablecloth", conn.text("SELECT back FROM flashcards WHERE id = 11"))
            assertEquals(1, conn.int("SELECT frontIsTarget FROM flashcards WHERE id = 11"))
            assertEquals("NATIVE_FIRST", conn.text("SELECT reviewDirection FROM flashcards WHERE id = 11"))
            assertEquals(10, conn.int("SELECT noteId FROM flashcards WHERE id = 11"))
            assertEquals(222, conn.int("SELECT dueAt FROM flashcards WHERE id = 11"))
            assertEquals("g1", conn.text("SELECT ankiNoteGuid FROM flashcards WHERE id = 11"))
            assertEquals(1, conn.int("SELECT ankiOrd FROM flashcards WHERE id = 11"))
        }
    }

    @Test
    fun `the old table is kept aside and the scaffolding is gone`() {
        withDb { conn ->
            conn.insertCard(id = 10, front = "mantel", back = "tablecloth")
            conn.insertReview(id = 1, cardId = 10)

            conn.migrate()

            val objects = conn.texts("SELECT name FROM sqlite_master ORDER BY name")
            assertTrue("flashcards_v42" in objects)
            assertEquals(1, conn.int("SELECT COUNT(*) FROM flashcards_v42"))
            assertTrue(objects.none { it != null && it.startsWith("_") })
            assertTrue("reviews_new" !in objects)
            assertTrue("index_reviews_cardId" in objects)
            assertTrue("index_cards_noteId_direction" in objects)
        }
    }

    @Test
    fun `deleting a note takes its cards and their reviews with it`() {
        withDb { conn ->
            conn.insertCard(id = 10, front = "mantel", back = "tablecloth", guid = "g1", ord = 0)
            conn.insertCard(id = 11, front = "tablecloth", back = "mantel", frontIsTarget = 0, direction = "NATIVE_FIRST", guid = "g1", ord = 1)
            conn.insertCard(id = 12, front = "prestar", back = "to lend", kind = "CONJUGATION")
            conn.insertReview(id = 1, cardId = 10)
            conn.insertReview(id = 2, cardId = 11)
            conn.insertReview(id = 3, cardId = 12)
            conn.migrate()

            // FlashcardDao.delete's shape.
            conn.runSql("DELETE FROM notes WHERE id = (SELECT noteId FROM cards WHERE id = 11)")

            assertEquals(1, conn.int("SELECT COUNT(*) FROM cards"))
            assertEquals(1, conn.int("SELECT COUNT(*) FROM reviews"))
            assertEquals(12, conn.int("SELECT cardId FROM reviews"))
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun Connection.migrate() = MIGRATION_42_43_SQL.forEach { runSql(it) }

    /** The v42 shape of `flashcards` and `reviews`, in full: the migration reads every column. */
    private fun Connection.createSchema() {
        runSql("PRAGMA foreign_keys = ON")
        runSql(
            "CREATE TABLE `flashcards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `front` TEXT NOT NULL, " +
                "`back` TEXT NOT NULL, `contextSentence` TEXT, `sourceConversationId` INTEGER, `explanation` TEXT, " +
                "`usageNote` TEXT, `partOfSpeech` TEXT, `cefrLevel` TEXT, `cardKind` TEXT NOT NULL, `gender` TEXT, " +
                "`region` TEXT, `country` TEXT, `altForms` TEXT, `inflectedForms` TEXT, `frontIsTarget` INTEGER NOT NULL, " +
                "`reviewDirection` TEXT NOT NULL, `targetLanguage` TEXT NOT NULL, `nativeLanguage` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `dueAt` INTEGER NOT NULL, `intervalDays` REAL NOT NULL, " +
                "`easeFactor` REAL NOT NULL, `repetitions` INTEGER NOT NULL, `lapses` INTEGER NOT NULL, " +
                "`lastReviewedAt` INTEGER, `state` TEXT NOT NULL, `stability` REAL, `difficulty` REAL, `fsrsStep` INTEGER, " +
                "`suspended` INTEGER NOT NULL, `marked` INTEGER NOT NULL, `autoSource` TEXT, `ankiNoteGuid` TEXT, " +
                "`ankiOrd` INTEGER, `ankiNoteTypeId` INTEGER, `ankiFieldsJson` TEXT, `ankiTags` TEXT, `ankiDeckName` TEXT, " +
                "`imageFile` TEXT, `audioFile` TEXT)",
        )
        runSql("CREATE INDEX `index_flashcards_targetLanguage` ON `flashcards` (`targetLanguage`)")
        runSql(
            "CREATE TABLE `reviews` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardId` INTEGER NOT NULL, " +
                "`reviewedAt` INTEGER NOT NULL, `rating` TEXT NOT NULL, `lastIntervalDays` REAL NOT NULL, " +
                "`intervalDays` REAL NOT NULL, `stabilityAfter` REAL, `difficultyAfter` REAL, `easeFactor` REAL NOT NULL, " +
                "`state` TEXT NOT NULL, `reviewDurationMs` INTEGER, " +
                "FOREIGN KEY(`cardId`) REFERENCES `flashcards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        runSql("CREATE INDEX `index_reviews_cardId` ON `reviews` (`cardId`)")
    }

    private fun Connection.insertCard(
        id: Long,
        front: String,
        back: String,
        frontIsTarget: Int = 1,
        direction: String = "TARGET_FIRST",
        kind: String = "VOCAB",
        guid: String? = null,
        ord: Int? = null,
        lang: String = "es",
        dueAt: Long = 1,
        state: String = "NEW",
        lapses: Int = 0,
        repetitions: Int = 0,
        stability: Double? = null,
        suspended: Int = 0,
        marked: Int = 0,
        example: String? = null,
        pos: String? = null,
        explanation: String? = null,
        image: String? = null,
    ) = runSql(
        "INSERT INTO flashcards (id, front, back, contextSentence, explanation, partOfSpeech, cardKind, frontIsTarget, " +
            "reviewDirection, targetLanguage, nativeLanguage, createdAt, dueAt, intervalDays, easeFactor, repetitions, " +
            "lapses, state, stability, suspended, marked, ankiNoteGuid, ankiOrd, imageFile) VALUES ($id, ${q(front)}, " +
            "${q(back)}, ${q(example)}, ${q(explanation)}, ${q(pos)}, '$kind', $frontIsTarget, '$direction', '$lang', 'en', " +
            "1000, $dueAt, 0.0, 2.5, $repetitions, $lapses, '$state', ${stability ?: "NULL"}, $suspended, $marked, " +
            "${q(guid)}, ${ord ?: "NULL"}, ${q(image)})",
    )

    private fun Connection.insertReview(id: Long, cardId: Long) = runSql(
        "INSERT INTO reviews (id, cardId, reviewedAt, rating, lastIntervalDays, intervalDays, easeFactor, state) " +
            "VALUES ($id, $cardId, ${id * 1000}, 'GOOD', 1.0, 2.0, 2.5, 'REVIEW')",
    )

    private fun q(value: String?): String = value?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("migration_42_43_test").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "test.db").absolutePath}").use { conn ->
                conn.createSchema()
                block(conn)
            }
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

    private fun Connection.text(sql: String): String? = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
    }

    private fun Connection.texts(sql: String): List<String?> = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun Connection.longs(sql: String): List<Long> = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
    }
}

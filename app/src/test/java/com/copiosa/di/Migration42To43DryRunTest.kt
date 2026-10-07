package com.copiosa.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * A utility rather than a test: replays [MIGRATION_42_43_SQL] over a *copy* of a real database and
 * checks what the unit tests can only check on a handful of invented rows — that on a deck of
 * thousands, no card, schedule or review went missing.
 *
 * ```
 * gradlew :app:testDebugUnitTest --tests "*Migration42To43DryRunTest*" "-DmigrationDb=../dbpull/copiosa.db"
 * ```
 *
 * `migrationDb` resolves against `app/`, the forked test JVM's working directory. The file is copied
 * to a temp directory first (with any `-wal`/`-shm` sidecars) and the copy is what gets migrated; the
 * original is never opened for writing. With `migrationDb` unset this does nothing and passes.
 *
 * It needs a database at schema 40-42 — any version whose `flashcards` table already has every
 * column this migration reads. The report goes to standard out and to `<migrationDb>.dryrun.txt`.
 */
class Migration42To43DryRunTest {

    @Test
    fun `replay the notes and cards split over a real database`() {
        val path = System.getProperty("migrationDb").orEmpty()
        if (path.isBlank()) return
        val source = File(path)
        assertTrue("No database at ${source.absolutePath}", source.exists())

        val dir = Files.createTempDirectory("migration_42_43_dryrun").toFile()
        try {
            for (suffix in listOf("", "-wal", "-shm")) {
                val file = File(source.path + suffix)
                if (file.exists()) file.copyTo(File(dir, "copy.db$suffix"))
            }
            DriverManager.getConnection("jdbc:sqlite:${File(dir, "copy.db").absolutePath}").use { conn ->
                val report = replay(conn)
                println(report)
                File(source.path + ".dryrun.txt").writeText(report)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun replay(conn: Connection): String {
        conn.runSql("PRAGMA foreign_keys = ON")
        val cardsBefore = conn.long("SELECT COUNT(*) FROM flashcards")
        val reviewsBefore = conn.long("SELECT COUNT(*) FROM reviews")
        val reviewSumBefore = conn.long("SELECT COALESCE(SUM(cardId * 31 + reviewedAt), 0) FROM reviews")
        // Singles only: a BOTH row that is half of a forward/reverse pair is not split, it is paired.
        val bothBefore = conn.long(
            "SELECT COUNT(*) FROM flashcards c WHERE c.reviewDirection = 'BOTH' AND c.cardKind IN ('VOCAB','PHRASE','SENTENCE') " +
                "AND NOT (c.ankiNoteGuid IS NOT NULL AND (SELECT COUNT(*) FROM flashcards x WHERE x.ankiNoteGuid = c.ankiNoteGuid " +
                "AND x.targetLanguage = c.targetLanguage) = 2 AND (SELECT COUNT(DISTINCT x.frontIsTarget) FROM flashcards x " +
                "WHERE x.ankiNoteGuid = c.ankiNoteGuid AND x.targetLanguage = c.targetLanguage) = 2)",
        )

        val started = System.nanoTime()
        conn.autoCommit = false
        MIGRATION_42_43_SQL.forEach { conn.runSql(it) }
        conn.commit()
        conn.autoCommit = true
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // --- nothing lost -----------------------------------------------------
        assertEquals("every old row is still a card, under its old id", 0L,
            conn.long("SELECT COUNT(*) FROM flashcards_v42 f WHERE f.id NOT IN (SELECT id FROM cards)"))
        assertEquals("every carried-over card kept its schedule and frozen state", 0L,
            conn.long(
                "SELECT COUNT(*) FROM flashcards_v42 f JOIN cards c ON c.id = f.id WHERE " +
                    "c.dueAt <> f.dueAt OR c.intervalDays <> f.intervalDays OR c.easeFactor <> f.easeFactor OR " +
                    "c.repetitions <> f.repetitions OR c.lapses <> f.lapses OR c.state <> f.state OR " +
                    "c.suspended <> f.suspended OR c.lastReviewedAt IS NOT f.lastReviewedAt OR " +
                    "c.stability IS NOT f.stability OR c.difficulty IS NOT f.difficulty OR c.fsrsStep IS NOT f.fsrsStep",
            ))
        assertEquals("review rows", reviewsBefore, conn.long("SELECT COUNT(*) FROM reviews"))
        assertEquals("reviews still point at the same cards", reviewSumBefore,
            conn.long("SELECT COALESCE(SUM(cardId * 31 + reviewedAt), 0) FROM reviews"))
        assertEquals("no review is orphaned", 0L,
            conn.long("SELECT COUNT(*) FROM reviews WHERE cardId NOT IN (SELECT id FROM cards)"))
        assertEquals("no card is without its note", 0L,
            conn.long("SELECT COUNT(*) FROM cards WHERE noteId NOT IN (SELECT id FROM notes)"))
        assertEquals("no note is without a card", 0L,
            conn.long("SELECT COUNT(*) FROM notes WHERE id NOT IN (SELECT noteId FROM cards)"))
        assertEquals("the view reads one row per card", conn.long("SELECT COUNT(*) FROM cards"),
            conn.long("SELECT COUNT(*) FROM flashcards"))
        assertEquals("no note has two cards facing the same way", 0L,
            conn.long("SELECT COUNT(*) FROM (SELECT noteId FROM cards GROUP BY noteId, direction HAVING COUNT(*) > 1)"))

        // --- the text every old row showed is still what its card shows ----------
        // A carried-over card must read the same target and native text as before, except where the
        // merge deliberately let a note's term-first row speak for a twin that disagreed with it.
        val targetChanged = conn.long(
            "SELECT COUNT(*) FROM flashcards_v42 f JOIN flashcards v ON v.id = f.id WHERE " +
                "TRIM(v.front) <> TRIM(CASE WHEN f.frontIsTarget = 1 THEN f.front ELSE f.back END)",
        )
        val nativeChanged = conn.long(
            "SELECT COUNT(*) FROM flashcards_v42 f JOIN flashcards v ON v.id = f.id WHERE " +
                "TRIM(v.back) <> TRIM(CASE WHEN f.frontIsTarget = 1 THEN f.back ELSE f.front END)",
        )
        val changedOutsidePairs = conn.long(
            "SELECT COUNT(*) FROM flashcards_v42 f JOIN flashcards v ON v.id = f.id WHERE v.noteId = v.id AND (" +
                "v.front <> (CASE WHEN f.frontIsTarget = 1 THEN f.front ELSE f.back END) OR " +
                "v.back <> (CASE WHEN f.frontIsTarget = 1 THEN f.back ELSE f.front END))" +
                " AND TRIM(f.front) <> '' AND TRIM(f.back) <> ''",
        )
        assertEquals("a note's own term-first row never changes its text", 0L, changedOutsidePairs)

        // --- every word is now both ways ------------------------------------------
        assertEquals("every word, phrase and sentence note has a card each way", 0L,
            conn.long(
                "SELECT COUNT(*) FROM notes n WHERE n.cardKind IN ('VOCAB','PHRASE','SENTENCE') AND " +
                    "(SELECT COUNT(*) FROM cards c WHERE c.noteId = n.id) <> 2",
            ))

        val notes = conn.long("SELECT COUNT(*) FROM notes")
        val cards = conn.long("SELECT COUNT(*) FROM cards")
        val twoCard = conn.long("SELECT COUNT(*) FROM (SELECT noteId FROM cards GROUP BY noteId HAVING COUNT(*) = 2)")
        val created = cards - cardsBefore
        val newCards = created - bothBefore
        val lastNewDue = conn.long("SELECT COALESCE(MAX(dueAt), 0) FROM cards WHERE id NOT IN (SELECT id FROM flashcards_v42) AND state = 'NEW' AND stability IS NULL")
        val spreadDays = if (lastNewDue == 0L) 0 else (lastNewDue - System.currentTimeMillis()) / 86_400_000L + 1
        val kinds = conn.rows(
            "SELECT n.cardKind, COUNT(DISTINCT n.id), COUNT(c.id) FROM notes n JOIN cards c ON c.noteId = n.id GROUP BY n.cardKind ORDER BY 2 DESC",
        )

        return buildString {
            appendLine("Migration 42 -> 43 dry run: all checks passed in $elapsedMs ms")
            appendLine("  before: $cardsBefore cards, $reviewsBefore reviews")
            appendLine("  after:  $notes notes, $cards cards ($twoCard notes with a card each way), $reviewsBefore reviews")
            appendLine("  cards created: $created")
            appendLine("    $bothBefore from splitting a BOTH card (schedule copied)")
            appendLine("    $newCards brand new, released over $spreadDays days")
            appendLine("  twins whose text now follows the note's term-first card: $targetChanged target side, $nativeChanged native side")
            appendLine("  by kind (notes, cards):")
            kinds.forEach { appendLine("    ${it[0]}: ${it[1]} notes, ${it[2]} cards") }
        }
    }

    private fun Connection.runSql(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.long(sql: String): Long = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    private fun Connection.rows(sql: String): List<List<String>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val width = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..width).map { rs.getString(it).orEmpty() }) }
        }
    }
}

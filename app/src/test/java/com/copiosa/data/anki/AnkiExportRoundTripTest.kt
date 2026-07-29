package com.copiosa.data.anki

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.ReviewLogEntity
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/**
 * Round-trips cards through the real `.apkg` pipeline: [AnkiExporter] → [AnkiCollectionWriter] (JDBC) →
 * zip → [AnkiImporter]. Proves that scheduling and the full review history (the "times") survive a
 * Copiosa → Anki-file → Copiosa trip.
 */
class AnkiExportRoundTripTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L
    private val crtMs = now - (now % day) // matches the exporter's day-aligned crt for the same `now`

    /** Exports [cards], writes a real apkg, then re-imports it through the production importer. */
    private fun roundTrip(cards: List<AnkiExporter.Input>): ImportResult {
        val collection = AnkiExporter().export(cards, now)
        val tmp = Files.createTempDirectory("apkg_export_test").toFile()
        try {
            val collFile = File(tmp, "collection.anki21")
            DriverManager.getConnection("jdbc:sqlite:${collFile.absolutePath}").use { conn ->
                AnkiCollectionWriter.write(JdbcSqlExec(conn), collection)
            }
            val apkg = File(tmp, "out.apkg")
            apkg.outputStream().use { ApkgPackager.pack(it, collFile, emptyList()) }
            return JdbcRawApkg(apkg).use { AnkiImporter("es", "en").import(it, now) }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun ImportResult.card(guid: String, ord: Int): ImportedCard =
        cards.first { it.card.ankiNoteGuid == guid && it.card.ankiOrd == ord }

    // An imported review card with two graded reviews and media — the strict-identity case.
    private fun gato(): List<AnkiExporter.Input> {
        val forward = FlashcardEntity(
            id = 1, front = "el gato", back = "the cat",
            targetLanguage = "es", nativeLanguage = "en", createdAt = now,
            dueAt = crtMs + 100 * day, intervalDays = 30.0, easeFactor = 2.5, repetitions = 5, lapses = 1,
            state = FlashcardState.REVIEW, stability = 30.0, difficulty = 5.0,
            ankiNoteGuid = "g-gato", ankiOrd = 0, ankiNoteTypeId = 1000L,
            ankiFieldsJson = """["el gato<img src=\"pic1.png\">","the cat[sound:audio1.mp3]"]""",
            ankiDeckName = "Spanish", imageFile = "pic1.png", audioFile = "audio1.mp3",
            cardKind = CardKind.VOCAB, frontIsTarget = true,
        )
        val reverse = forward.copy(
            id = 2, front = "the cat", back = "el gato", ankiOrd = 1, easeFactor = 2.3, frontIsTarget = false,
        )
        val reviews = listOf(
            ReviewLogEntity(
                cardId = 1, reviewedAt = now - 40 * day, rating = ReviewRating.GOOD,
                lastIntervalDays = 7.0, intervalDays = 15.0, easeFactor = 2.5,
                state = FlashcardState.REVIEW, reviewDurationMs = 4200,
            ),
            ReviewLogEntity(
                cardId = 1, reviewedAt = now - 10 * day, rating = ReviewRating.GOOD,
                lastIntervalDays = 15.0, intervalDays = 30.0, easeFactor = 2.5,
                state = FlashcardState.REVIEW, reviewDurationMs = 3100,
            ),
        )
        return listOf(AnkiExporter.Input(forward, reviews), AnkiExporter.Input(reverse, emptyList()))
    }

    @Test
    fun reviewCardScheduleSurvivesRoundTrip() {
        val c = roundTrip(gato()).card("g-gato", 0).card
        assertEquals("el gato", c.front)
        assertEquals("the cat", c.back)
        assertEquals(FlashcardState.REVIEW, c.state)
        assertEquals(30.0, c.intervalDays, 1e-9)
        assertEquals(2.5, c.easeFactor, 1e-9)
        assertEquals(5, c.repetitions)
        assertEquals(1, c.lapses)
        assertEquals(crtMs + 100 * day, c.dueAt)
        assertEquals("Spanish", c.ankiDeckName)
        assertEquals(1000L, c.ankiNoteTypeId)
        assertNotNull("review card should be FSRS-seeded on re-import", c.stability)
    }

    @Test
    fun reverseCardCollapsesIntoSameNoteAndSwapsSides() {
        val res = roundTrip(gato())
        // Two cards, one note (shared guid) — exactly what a forward/reverse pair should produce.
        assertEquals(2, res.cards.size)
        val reverse = res.card("g-gato", 1).card
        assertEquals("the cat", reverse.front)
        assertEquals("el gato", reverse.back)
        assertFalse(reverse.frontIsTarget)
        assertEquals(2.3, reverse.easeFactor, 1e-9)
    }

    @Test
    fun reviewHistoryIncludingTimesSurvivesRoundTrip() {
        val gato = roundTrip(gato()).card("g-gato", 0)
        assertEquals(2, gato.reviews.size)
        assertEquals(listOf(ReviewRating.GOOD, ReviewRating.GOOD), gato.reviews.map { it.rating })
        assertEquals(listOf(15.0, 30.0), gato.reviews.map { it.intervalDays })
        // The "times": each review's timestamp and its duration must come back unchanged.
        assertEquals(listOf(now - 40 * day, now - 10 * day), gato.reviews.map { it.reviewedAt })
        assertEquals(listOf(4200, 3100), gato.reviews.map { it.reviewDurationMs })
        assertEquals(gato.reviews.last().reviewedAt, gato.card.lastReviewedAt)
    }

    @Test
    fun mediaIsReferencedAfterRoundTrip() {
        val c = roundTrip(gato()).card("g-gato", 0).card
        assertEquals("pic1.png", c.imageFile)
        assertEquals("audio1.mp3", c.audioFile)
    }

    @Test
    fun appCreatedCardRebuildsStructuredFields() {
        val card = FlashcardEntity(
            id = 7, front = "perro", back = "dog", altForms = "can",
            gender = "M", country = "Costa_Rica", partOfSpeech = "noun", cardKind = CardKind.VOCAB,
            frontIsTarget = true, targetLanguage = "es", nativeLanguage = "en",
            createdAt = now, dueAt = now, state = FlashcardState.NEW,
        )
        val res = roundTrip(listOf(AnkiExporter.Input(card, emptyList())))
        val c = res.card("la-7", 0).card
        assertEquals("perro", c.front)
        assertEquals("dog", c.back)
        assertEquals("can", c.altForms)
        assertEquals("M", c.gender)
        assertEquals("Costa_Rica", c.country)
        assertEquals("noun", c.partOfSpeech)
        assertEquals(CardKind.VOCAB, c.cardKind)
        assertTrue(c.frontIsTarget)
        assertEquals(FlashcardState.NEW, c.state)
        assertNull("a new card carries no FSRS memory state", c.stability)
    }

    @Test
    fun markedFlagSurvivesRoundTripAsMarkedTag() {
        val marked = FlashcardEntity(
            id = 8, front = "gato", back = "cat", cardKind = CardKind.VOCAB, marked = true,
            frontIsTarget = true, targetLanguage = "es", nativeLanguage = "en",
            createdAt = now, dueAt = now, state = FlashcardState.NEW,
        )
        val plain = marked.copy(id = 9, front = "perro", back = "dog", marked = false)
        val res = roundTrip(listOf(AnkiExporter.Input(marked, emptyList()), AnkiExporter.Input(plain, emptyList())))
        assertTrue("marked card returns marked", res.card("la-8", 0).card.marked)
        assertFalse("unmarked card stays unmarked", res.card("la-9", 0).card.marked)
        // The flag lives in the `marked` column, not the passthrough tags.
        assertNull(res.card("la-8", 0).card.ankiTags)
    }

    @Test
    fun exportCollectsReferencedMedia() {
        val collection = AnkiExporter().export(gato(), now)
        assertEquals(listOf("pic1.png", "audio1.mp3"), collection.mediaFiles)
        // One note (forward+reverse collapsed), two cards, two revlog rows.
        assertEquals(1, collection.notes.size)
        assertEquals(2, collection.cards.size)
        assertEquals(2, collection.revlog.size)
    }
}

package com.copiosa.data.anki

import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Drives [AnkiImporter] against the committed synthetic fixture (see scripts that built sample.apkg). */
class AnkiImporterTest {

    private val now = 1_700_000_000_000L
    private val crtMs = 1_600_000_000_000L // fixture crt (1600000000 s) * 1000
    private val day = 86_400_000L

    private fun load(): ImportResult {
        val apkg = File("src/test/resources/anki/sample.apkg")
        assertTrue("fixture missing: ${apkg.absolutePath}", apkg.exists())
        return JdbcRawApkg(apkg).use { AnkiImporter("es", "en").import(it, now) }
    }

    private fun ImportResult.card(guid: String, ord: Int): ImportedCard =
        cards.first { it.card.ankiNoteGuid == guid && it.card.ankiOrd == ord }

    @Test
    fun importsOneCopiosaCardPerAnkiCard() {
        assertEquals(5, load().cards.size)
    }

    @Test
    fun reviewCardSchedulingIsConverted() {
        val c = load().card("g-gato", 0).card
        assertEquals(FlashcardState.REVIEW, c.state)
        assertEquals("el gato", c.front)
        assertEquals("the cat", c.back)
        assertEquals(2.5, c.easeFactor, 1e-9)        // factor 2500 / 1000
        assertEquals(30.0, c.intervalDays, 1e-9)
        assertEquals(crtMs + 100 * day, c.dueAt)     // due 100 days since crt
        assertEquals(5, c.repetitions)
        assertNotNull("review card should be FSRS-seeded", c.stability)
        assertEquals("pic1.png", c.imageFile)
        assertEquals("audio1.mp3", c.audioFile)
        assertEquals(1000L, c.ankiNoteTypeId)
        assertEquals("Spanish", c.ankiDeckName)
    }

    @Test
    fun reverseTemplateSwapsFrontAndBack() {
        val c = load().card("g-gato", 1).card
        assertEquals("the cat", c.front)
        assertEquals("el gato", c.back)
        assertEquals(2.3, c.easeFactor, 1e-9)        // factor 2300
    }

    @Test
    fun newCardHasNoMemoryState() {
        val c = load().card("g-perro", 1).card
        assertEquals(FlashcardState.NEW, c.state)
        assertNull(c.stability)
        assertEquals(0.0, c.intervalDays, 1e-9)
    }

    @Test
    fun basicNoteMapsAndKeepsItsDeck() {
        val c = load().card("g-hola", 0).card
        assertEquals("hola", c.front)
        assertEquals("hello", c.back)
        assertNull(c.imageFile)
        assertNull(c.audioFile)
        assertEquals("Default", c.ankiDeckName)
        assertEquals(2, c.lapses)
    }

    @Test
    fun revlogBecomesReviewHistory() {
        val res = load()
        val gato = res.card("g-gato", 0)
        assertEquals(2, gato.reviews.size)
        assertEquals(listOf(ReviewRating.GOOD, ReviewRating.GOOD), gato.reviews.map { it.rating })
        assertEquals(listOf(15.0, 30.0), gato.reviews.map { it.intervalDays })
        assertEquals(gato.reviews.last().reviewedAt, gato.card.lastReviewedAt)

        val perro = res.card("g-perro", 0)
        assertEquals(1, perro.reviews.size)
        assertEquals(ReviewRating.HARD, perro.reviews.first().rating)
    }

    @Test
    fun collectsAllReferencedMedia() {
        assertEquals(
            setOf("pic1.png", "pic2.png", "audio1.mp3", "audio2.mp3"),
            load().mediaFiles,
        )
    }

    @Test
    fun inlineTagsDoNotSplitWords() {
        // The "t a par" bug: an inline styling span inside a word must not insert spaces.
        assertEquals("tapar", AnkiImporter.htmlToPlainText("t<span style=\"color:red\">a</span>par"))
        assertEquals("tapar", AnkiImporter.htmlToPlainText("<b>t</b><i>a</i>par"))
    }

    @Test
    fun blockTagsSeparateWordsAndEntitiesDecode() {
        assertEquals("line one line two", AnkiImporter.htmlToPlainText("<div>line one</div><div>line two</div>"))
        assertEquals("a b", AnkiImporter.htmlToPlainText("a<br>b"))
        assertEquals("salt & pepper", AnkiImporter.htmlToPlainText("salt &amp; pepper"))
        assertEquals("hola", AnkiImporter.htmlToPlainText("[sound:greeting.mp3]hola"))
    }
}

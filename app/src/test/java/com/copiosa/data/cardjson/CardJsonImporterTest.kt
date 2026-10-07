package com.copiosa.data.cardjson

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * The import is a merge, and what matters about a merge is what it leaves alone: a key the file
 * doesn't carry must not move, an untouched export must change nothing, and an id must never land on
 * a note it wasn't exported from.
 */
class CardJsonImporterTest {

    private val utc = ZoneOffset.UTC
    private val day = 86_400_000L

    /** 2026-10-02T00:00:00Z. */
    private val now = 1_790_899_200_000L

    /** A card as the `flashcards` view returns it: term on the front, a direction of its own. */
    private fun card(
        id: Long,
        note: Long,
        term: String,
        translation: String,
        direction: ReviewDirection = ReviewDirection.TARGET_FIRST,
    ) = FlashcardEntity(
        id = id,
        noteId = note,
        front = term,
        back = translation,
        reviewDirection = direction,
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = now - 10 * day,
        dueAt = now + 3 * day,
    )

    /** Note 7, studied both ways, carrying everything the JSON never mentions — to prove none of it moves. */
    private val recognise = card(70, 7, "mantel", "tablecloth").copy(
        partOfSpeech = "noun",
        gender = "M",
        cefrLevel = "A2",
        altForms = "tapete",
        country = "Costa_Rica",
        usageNote = "everyday word",
        contextSentence = "Pon el mantel en la mesa.",
        explanation = "A long generated explanation.",
        inflectedForms = "mantel;manteles",
        state = FlashcardState.REVIEW,
        intervalDays = 12.4,
        easeFactor = 2.3,
        repetitions = 9,
        lapses = 2,
        stability = 14.2,
        difficulty = 5.5,
        lastReviewedAt = now - day,
        marked = true,
        imageFile = "mantel.jpg",
        audioFile = "mantel.mp3",
        ankiNoteGuid = "abc123",
        ankiFieldsJson = "{\"Front\":\"mantel\"}",
    )
    private val produce = recognise.copy(
        id = 71,
        reviewDirection = ReviewDirection.NATIVE_FIRST,
        state = FlashcardState.RELEARNING,
        lapses = 4,
        stability = 3.1,
        suspended = true,
    )
    private val note7 = listOf(recognise, produce)

    private fun plan(text: String, existing: List<FlashcardEntity>) =
        CardJsonImporter.plan(text, existing, targetLanguage = "es", zone = utc)

    @Test
    fun `an untouched export changes nothing`() {
        val deck = note7 + listOf(
            card(80, 8, "prestar", "to lend"),
            card(81, 8, "prestar", "to lend", ReviewDirection.NATIVE_FIRST),
            // Stored untidy: the export trims it, and the import must not count that as an edit.
            card(90, 9, " adrede ", "on purpose").copy(usageNote = "formal  ", partOfSpeech = "Adverb"),
            card(100, 10, "hablé", "hablar — yo, pretérito", ReviewDirection.NATIVE_FIRST).copy(cardKind = CardKind.CONJUGATION),
        )
        val text = CardJsonExporter.encode(deck, mapOf(70L to 812), "es", "en", now, utc)
        val plan = plan(text, deck)
        assertTrue(plan.isEmpty)
        assertEquals(4, plan.unchanged)
        assertEquals(0, plan.unmatched + plan.duplicates + plan.invalid)
    }

    @Test
    fun `an edited field moves and nothing else on the note does`() {
        val text = """{"targetLanguage":"es","notes":[{"id":7,"translation":"tablecloth (for a dining table)"}]}"""
        val update = plan(text, note7).updates.single()
        assertEquals(7L, update.noteId)
        val merged = update.patch.applyTo(recognise)
        assertEquals(recognise.copy(back = "tablecloth (for a dining table)"), merged)
        // Spelled out, since these are the fields the format exists to protect.
        assertEquals("mantel.jpg", merged.imageFile)
        assertEquals("mantel.mp3", merged.audioFile)
        assertEquals("A long generated explanation.", merged.explanation)
        assertEquals(14.2, merged.stability!!, 0.0)
        assertEquals(recognise.dueAt, merged.dueAt)
        assertEquals("{\"Front\":\"mantel\"}", merged.ankiFieldsJson)
        // The edit is the note's: it has no direction to change.
        assertTrue(update.patch.directionChanges(note7).isEmpty())
    }

    @Test
    fun `a key left out is kept, an explicit null clears, a value sets`() {
        val text = """[{"id":7,"note":null,"example":"","cefr":"b1","altForms":"tapete;carpeta","marked":false}]"""
        val merged = plan(text, note7).updates.single().patch.applyTo(recognise)
        assertNull(merged.usageNote)
        assertNull(merged.contextSentence)
        assertEquals("B1", merged.cefrLevel)
        assertEquals("tapete; carpeta", merged.altForms)
        assertEquals(false, merged.marked)
        // Not in the file, so untouched.
        assertEquals("M", merged.gender)
        assertEquals("noun", merged.partOfSpeech)
        assertEquals("Costa_Rica", merged.country)
        assertEquals("mantel", merged.front)
    }

    @Test
    fun `read-only fields in the file are ignored`() {
        val text = """[{"id":7,"freqRank":1,"source":"freq","created":"2026-09-22","cards":{
            "target-first":{"id":70,"state":"NEW","due":"2030-01-01","intervalDays":999,"lapses":0,"reviews":0},
            "native-first":{"id":71,"state":"NEW","lapses":0,"frozen":true}}}]"""
        val plan = plan(text, note7)
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun `the two sides cannot be blanked`() {
        assertEquals(1, plan("""[{"id":7,"term":"","translation":null}]""", note7).unchanged)
    }

    @Test
    fun `values the card editor does not know are ignored rather than stored`() {
        assertEquals(1, plan("""[{"id":7,"gender":"masculine","cefr":"fluent","kind":"FLASHCARD"}]""", note7).unchanged)
    }

    @Test
    fun `kind and country are stored in the note's own forms`() {
        val plain = card(80, 8, "pedir prestado", "to borrow")
        val text = """[{"id":8,"kind":"phrase","country":"Costa  Rica","pos":"Phrase"}]"""
        val merged = plan(text, listOf(plain)).updates.single().patch.applyTo(plain)
        assertEquals(CardKind.PHRASE, merged.cardKind)
        assertEquals("Costa_Rica", merged.country)
        assertEquals("phrase", merged.partOfSpeech)
    }

    @Test
    fun `a patch with nothing to say hands back the very same row`() {
        // Identity, not just equality, is how the service tells "nothing to write" from an edit.
        val patch = plan("""[{"id":7,"note":"changed"}]""", note7).updates.single().patch
        val already = recognise.copy(usageNote = "changed")
        assertSame(already, patch.applyTo(already))
    }

    // ---- directions ----

    @Test
    fun `frozen sets one direction aside and leaves the other alone`() {
        val text = """[{"id":7,"cards":{"target-first":{"frozen":true},"native-first":{"frozen":false}}}]"""
        val update = plan(text, note7).updates.single()
        assertEquals(
            mapOf(ReviewDirection.TARGET_FIRST to true, ReviewDirection.NATIVE_FIRST to false),
            update.patch.directionChanges(note7),
        )
        // No content moved.
        assertSame(recognise, update.patch.applyTo(recognise))
    }

    @Test
    fun `a direction mentioned without a frozen flag is left as it is`() {
        val text = """[{"id":7,"cards":{"target-first":{"id":70},"native-first":{}}}]"""
        assertEquals(1, plan(text, note7).unchanged)
    }

    @Test
    fun `naming a direction the note has no card for starts studying it`() {
        val oneWay = card(80, 8, "cucharón", "ladle", ReviewDirection.NATIVE_FIRST)
        val update = plan("""[{"id":8,"cards":{"target-first":{}}}]""", listOf(oneWay)).updates.single()
        assertEquals(mapOf(ReviewDirection.TARGET_FIRST to false), update.patch.directionChanges(listOf(oneWay)))
    }

    @Test
    fun `an unrecognised direction is ignored`() {
        assertEquals(1, plan("""[{"id":7,"cards":{"sideways":{"frozen":true},"both":{"frozen":true}}}]""", note7).unchanged)
    }

    // ---- new notes ----

    @Test
    fun `an entry with no id is a new note, studied both ways unless it says otherwise`() {
        val text = """[
            {"term":"cucharón","translation":"ladle","pos":"noun","gender":"M"},
            {"term":"ojalá","translation":"hopefully","direction":"target-first"}
        ]"""
        val plan = plan(text, note7)
        assertEquals(listOf("cucharón", "ojalá"), plan.additions.map { it.term })
        assertEquals("ladle", plan.additions[0].translation)
        assertNull("no direction means the kind's default", plan.additions[0].direction)
        assertEquals(ReviewDirection.TARGET_FIRST, plan.additions[1].direction)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun `a new note the deck already teaches is skipped, as is a repeat within the file`() {
        val text = """[
            {"term":"Mantel","translation":"tablecloth"},
            {"term":"cucharón","translation":"ladle"},
            {"term":"cucharón","translation":"big spoon"}
        ]"""
        val plan = plan(text, note7)
        assertEquals(listOf("cucharón"), plan.additions.map { it.term })
        assertEquals(2, plan.duplicates)
    }

    @Test
    fun `a new note needs both sides`() {
        val plan = plan("""[{"term":"cucharón"},{"translation":"ladle"},"not a note",42]""", emptyList())
        assertTrue(plan.isEmpty)
        assertEquals(4, plan.invalid)
    }

    // ---- matching ----

    @Test
    fun `an id that matches no note is skipped, never added`() {
        val text = """[{"id":999,"term":"cucharón","translation":"ladle"},{"id":"abc","term":"x","translation":"y"}]"""
        val plan = plan(text, note7)
        assertTrue(plan.isEmpty)
        assertEquals(2, plan.unmatched)
    }

    @Test
    fun `a card id is not a note id`() {
        // 70 and 71 are note 7's cards. In a current file an id means a note, and there is no note 70.
        assertEquals(1, plan("""[{"id":70,"note":"x"}]""", note7).unmatched)
    }

    @Test
    fun `an id whose created date disagrees is not trusted`() {
        // Same row number, different note: a file from another install must not overwrite this one.
        val text = """[{"id":7,"term":"zanahoria","translation":"carrot","created":"2025-01-15"}]"""
        val plan = plan(text, note7)
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unmatched)
    }

    @Test
    fun `a created date a day off still matches`() {
        // The export writes local dates, so a timezone change can move one across midnight.
        assertEquals(1, plan("""[{"id":7,"note":"changed","created":"2026-09-23"}]""", note7).updates.size)
    }

    @Test
    fun `a file from before notes names cards, and each id finds its note`() {
        // Versions 1 and 2 listed cards. Either card of a note is a way in to it, and what the
        // entry edits is the note — where a card's wording has lived since the split.
        val text = """{"version":2,"targetLanguage":"es","cards":[
            {"id":71,"term":"el mantel","direction":"both","reverse":{"id":70,"term":"ignored"}}
        ]}"""
        val update = plan(text, note7).updates.single()
        assertEquals(7L, update.noteId)
        assertEquals("el mantel", update.patch.applyTo(recognise).front)
        // `direction` and `reverse` meant something only while a direction was a whole row.
        assertTrue(update.patch.directionChanges(note7).isEmpty())
        assertEquals(1, plan("""{"version":2,"cards":[{"id":7,"note":"x"}]}""", note7).unmatched)
    }

    @Test
    fun `a file for another language is refused`() {
        val text = """{"targetLanguage":"pt","notes":[{"id":7,"note":"x"}]}"""
        val e = assertThrows(CardJsonImporter.InvalidFileException::class.java) { plan(text, note7) }
        assertTrue(e.message!!.contains("pt"))
    }

    @Test
    fun `a file that is not a note list is refused`() {
        assertThrows(CardJsonImporter.InvalidFileException::class.java) { plan("not json", emptyList()) }
        assertThrows(CardJsonImporter.InvalidFileException::class.java) { plan("""{"format":"copiosa-cards"}""", emptyList()) }
        assertThrows(CardJsonImporter.InvalidFileException::class.java) { plan("\"just a string\"", emptyList()) }
    }
}

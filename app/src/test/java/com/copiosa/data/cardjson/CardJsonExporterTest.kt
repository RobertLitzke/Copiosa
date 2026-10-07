package com.copiosa.data.cardjson

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewDirection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * The export is for reading, so what is checked here is what a reader depends on: that it is one
 * valid JSON document, that a note sits on one line however many directions it is studied in, that
 * sides are named by language rather than by face, and that fields with nothing to say are absent
 * rather than null.
 */
class CardJsonExporterTest {

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

    private fun notesOf(text: String): List<JsonObject> =
        Json.parseToJsonElement(text).jsonObject.getValue("notes").jsonArray.map { it.jsonObject }

    private fun encode(vararg cards: FlashcardEntity, ranks: Map<Long, Int> = emptyMap()) =
        CardJsonExporter.encode(cards.toList(), ranks, "es", "en", now, utc)

    @Test
    fun `the output is one valid document with a header and every note`() {
        val text = encode(card(1, 1, "mantel", "tablecloth"), card(2, 2, "prestar", "to lend"))
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("copiosa-cards", root.getValue("format").jsonPrimitive.content)
        assertEquals(3, root.getValue("version").jsonPrimitive.int)
        assertEquals("2026-10-02", root.getValue("exported").jsonPrimitive.content)
        assertEquals("es", root.getValue("targetLanguage").jsonPrimitive.content)
        assertEquals("en", root.getValue("nativeLanguage").jsonPrimitive.content)
        assertEquals(2, root.getValue("count").jsonPrimitive.int)
        // The file explains its own round trip to whatever edits it next.
        assertTrue(root.getValue("about").jsonPrimitive.content.contains("matched by id"))
        assertEquals(2, root.getValue("notes").jsonArray.size)
    }

    @Test
    fun `a note studied both ways is one entry holding both schedules`() {
        val recognise = card(10, 7, "mantel", "tablecloth").copy(lapses = 1, repetitions = 9, intervalDays = 30.0)
        val produce = card(11, 7, "mantel", "tablecloth", ReviewDirection.NATIVE_FIRST).copy(
            state = FlashcardState.RELEARNING,
            dueAt = now + day,
            intervalDays = 2.0,
            repetitions = 8,
            lapses = 4,
            suspended = true,
        )
        // Listed native-first card first, as a deck in arbitrary order may well have them.
        val text = encode(produce, recognise, card(12, 8, "prestar", "to lend"))

        val notes = notesOf(text)
        assertEquals(listOf(7, 8), notes.map { it.getValue("id").jsonPrimitive.int })
        assertEquals(2, Json.parseToJsonElement(text).jsonObject.getValue("count").jsonPrimitive.int)

        val cards = notes.first().getValue("cards").jsonObject
        assertEquals("term-first leads, whatever order the rows came in", listOf("target-first", "native-first"), cards.keys.toList())
        val forward = cards.getValue("target-first").jsonObject
        assertEquals(10, forward.getValue("id").jsonPrimitive.int)
        assertEquals(1, forward.getValue("lapses").jsonPrimitive.int)
        assertEquals(30, forward.getValue("intervalDays").jsonPrimitive.int)
        assertFalse("an unfrozen direction says nothing about it", "frozen" in forward)
        val reverse = cards.getValue("native-first").jsonObject
        assertEquals(setOf("id", "state", "due", "intervalDays", "reviews", "lapses", "frozen"), reverse.keys)
        assertEquals(11, reverse.getValue("id").jsonPrimitive.int)
        assertEquals("RELEARNING", reverse.getValue("state").jsonPrimitive.content)
        assertEquals("2026-10-03", reverse.getValue("due").jsonPrimitive.content)
        assertEquals(4, reverse.getValue("lapses").jsonPrimitive.int)
    }

    @Test
    fun `each note is on a line of its own`() {
        val text = encode(
            card(1, 1, "mantel", "tablecloth"),
            card(2, 1, "mantel", "tablecloth", ReviewDirection.NATIVE_FIRST),
            card(3, 2, "prestar", "to lend"),
            card(4, 3, "adrede", "on purpose"),
        )
        val noteLines = text.lines().filter { it.startsWith("{\"id\":") }
        assertEquals(3, noteLines.size)
        assertTrue(noteLines[0].contains("\"term\":\"mantel\""))
        assertTrue(noteLines[2].contains("\"term\":\"adrede\""))
    }

    @Test
    fun `a line break inside a field does not split the note across lines`() {
        val text = encode(card(1, 1, "mantel", "tablecloth").copy(usageNote = "first line\nsecond line"))
        assertEquals(1, text.lines().count { it.startsWith("{\"id\":") })
        assertEquals("first line\nsecond line", notesOf(text).single().getValue("note").jsonPrimitive.content)
    }

    @Test
    fun `an empty deck is still valid json`() {
        assertTrue(notesOf(encode()).isEmpty())
    }

    @Test
    fun `a bare new note carries only what it has`() {
        val text = encode(card(1, 1, "mantel", "tablecloth").copy(partOfSpeech = "unknown"))
        val note = notesOf(text).single()
        assertEquals(setOf("id", "term", "translation", "kind", "cards", "created"), note.keys)
        assertEquals(setOf("id", "state", "due"), note.getValue("cards").jsonObject.getValue("target-first").jsonObject.keys)
        val line = text.lines().single { it.startsWith("{\"id\":") }
        assertFalse("nothing should be written as null", line.contains("null"))
    }

    @Test
    fun `a note with a single direction shows which one`() {
        val conjugation = card(1, 1, "hablé", "hablar — yo, pretérito", ReviewDirection.NATIVE_FIRST)
            .copy(cardKind = CardKind.CONJUGATION)
        val cards = notesOf(encode(conjugation)).single().getValue("cards").jsonObject
        assertEquals(setOf("native-first"), cards.keys)
    }

    @Test
    fun `a studied, annotated note carries its details`() {
        val studied = card(70, 7, "mantel", "tablecloth").copy(
            partOfSpeech = "noun",
            gender = "M",
            cefrLevel = "A2",
            altForms = "tapete",
            country = "Costa_Rica",
            usageNote = "everyday word",
            contextSentence = "Pon el mantel en la mesa.",
            explanation = "A long generated explanation that the export leaves out.",
            cardKind = CardKind.VOCAB,
            state = FlashcardState.REVIEW,
            intervalDays = 12.4,
            repetitions = 9,
            lapses = 2,
            marked = true,
            autoSource = "bulk",
        )
        val exported = CardJsonExporter.toJson(listOf(studied), rank = 812, zone = utc)
        assertEquals(7L, exported.id)
        assertEquals("noun", exported.pos)
        assertEquals("M", exported.gender)
        assertEquals("A2", exported.cefr)
        assertEquals("tapete", exported.altForms)
        assertEquals("Costa Rica", exported.country)
        assertEquals("everyday word", exported.note)
        assertEquals("Pon el mantel en la mesa.", exported.example)
        assertEquals(812, exported.freqRank)
        assertEquals(true, exported.marked)
        assertEquals("bulk", exported.source)
        assertEquals("2026-09-22", exported.created)
        val schedule = exported.cards.getValue("target-first")
        assertEquals(70L, schedule.id)
        assertEquals(FlashcardState.REVIEW, schedule.state)
        assertEquals("2026-10-05", schedule.due)
        assertEquals(12, schedule.intervalDays)
        assertEquals(9, schedule.reviews)
        assertEquals(2, schedule.lapses)

        val text = encode(studied, ranks = mapOf(70L to 812))
        assertFalse("the explanation is deliberately not exported", text.contains("generated explanation"))
    }

    @Test
    fun `a note takes its frequency rank from whichever card was matched`() {
        val text = encode(
            card(10, 7, "mantel", "tablecloth"),
            card(11, 7, "mantel", "tablecloth", ReviewDirection.NATIVE_FIRST),
            ranks = mapOf(11L to 640),
        )
        assertEquals(640, notesOf(text).single().getValue("freqRank").jsonPrimitive.int)
    }

    @Test
    fun `rows not stored yet are each a note of their own`() {
        // No note id means no siblings; the export must not fold unrelated rows together.
        val text = encode(card(1, 0, "mantel", "tablecloth"), card(2, 0, "prestar", "to lend"))
        assertEquals(2, notesOf(text).size)
    }
}

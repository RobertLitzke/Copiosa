package com.copiosa.domain.highlight

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.srs.ReviewWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure encode/decode of [FlashcardEntity.inflectedForms] used by the highlight render path. */
class HighlightFormsTest {
    @Test fun `encode then decode round-trips the inflected forms`() {
        val forms = setOf("gato", "gatos")
        val decoded = HighlightTargetProvider.decode(HighlightTargetProvider.encode(forms), "gato")
        assertEquals(forms, decoded)
    }

    @Test fun `decode always includes the normalized headword`() {
        // A card saved before backfill has no stored forms; it must still match its own headword.
        assertEquals(setOf("gato"), HighlightTargetProvider.decode(null, "Gato,"))
    }

    @Test fun `decode normalizes and dedupes stored forms`() {
        val decoded = HighlightTargetProvider.decode("Gatos;gatos;", "gato")
        assertEquals(setOf("gato", "gatos"), decoded)
    }

    @Test fun `decode drops a trailing bracketed hint from the headword`() {
        // "pasa (fruta)" used to cache the literal "pasa (fruta", which no text token can equal.
        assertEquals(setOf("pasa"), HighlightTargetProvider.decode(null, "pasa (fruta)"))
        assertEquals(setOf("callejero"), HighlightTargetProvider.decode(null, " callejero (papel) "))
    }

    @Test fun `stripTrailingHint trims whitespace and keeps a hint-only term`() {
        assertEquals("pasa", stripTrailingHint("  pasa  (fruta)  "))
        assertEquals("dar la lata", stripTrailingHint("dar la lata (coloq.)"))
        assertEquals("gato", stripTrailingHint(" gato "))
        assertEquals("(papel)", stripTrailingHint("(papel)"))
    }

    @Test fun `encode skips blank forms`() {
        assertTrue(!HighlightTargetProvider.encode(setOf("vivir", "")).contains(";;"))
    }

    @Test fun `targetTerm is the front of a forward card`() {
        assertEquals("gato", card(front = "gato", back = "cat", frontIsTarget = true).targetTerm)
    }

    @Test fun `targetTerm is the back of a reverse card`() {
        // A reverse Anki import prompts from the native side, so its target headword sits on the back.
        // Keying highlights off the front here would tint English words in the Spanish reading text.
        assertEquals("gato", card(front = "cat", back = "gato", frontIsTarget = false).targetTerm)
    }

    @Test fun `a small deck highlights everything due within the window and nothing past it`() {
        val now = 100 * ReviewWindows.DAY_MS
        val cards = listOf(
            card("vencido", dueAt = now - ReviewWindows.DAY_MS, id = 1),
            card("pronto", dueAt = now + ReviewWindows.HIGHLIGHT_DUE_MS, id = 2),
            card("lejano", dueAt = now + ReviewWindows.HIGHLIGHT_DUE_MS + 1, id = 3),
        )
        assertEquals(listOf("vencido", "pronto"), HighlightTargetProvider.eligible(cards, now).map { it.front })
    }

    @Test fun `a backlog larger than the cap keeps only the words due soonest`() {
        val now = 100 * ReviewWindows.DAY_MS
        val extra = 50
        // Handed over latest-first, so the cut has to come from the due date and not from list order.
        val cards = (0 until ReviewWindows.HIGHLIGHT_MAX_WORDS + extra)
            .map { card("w$it", dueAt = now - it, id = it + 1L) }
        val kept = HighlightTargetProvider.eligible(cards, now)
        assertEquals(ReviewWindows.HIGHLIGHT_MAX_WORDS, kept.size)
        assertEquals("w${cards.size - 1}", kept.first().front)
        assertTrue(kept.none { it.front == "w0" })
    }

    @Test fun `both directions of one word take a single place under the cap`() {
        val now = 100 * ReviewWindows.DAY_MS
        val cards = listOf(
            card("gato", dueAt = now + 2, id = 1, noteId = 7),
            card("gato", dueAt = now + 1, id = 2, noteId = 7),
            card("perro", dueAt = now + 3, id = 3, noteId = 8),
        )
        assertEquals(listOf(2L, 3L), HighlightTargetProvider.eligible(cards, now).map { it.id })
    }

    private fun card(front: String, back: String, frontIsTarget: Boolean) = FlashcardEntity(
        front = front,
        back = back,
        frontIsTarget = frontIsTarget,
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = 0L,
        dueAt = 0L,
    )

    private fun card(front: String, dueAt: Long, id: Long, noteId: Long = id) = FlashcardEntity(
        id = id,
        front = front,
        back = "",
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = 0L,
        dueAt = dueAt,
        noteId = noteId,
    )
}

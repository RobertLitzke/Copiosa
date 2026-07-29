package com.copiosa.domain.highlight

import com.copiosa.data.local.FlashcardEntity
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

    private fun card(front: String, back: String, frontIsTarget: Boolean) = FlashcardEntity(
        front = front,
        back = back,
        frontIsTarget = frontIsTarget,
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = 0L,
        dueAt = 0L,
    )
}

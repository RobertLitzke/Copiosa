package com.copiosa.ui.vocab

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.ReviewDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vocabulary list shows a note as one row, and [leadCard] picks which of its cards that row is.
 * It has to answer "when do I see this word again", and it must not call a word frozen because one
 * of its directions is.
 */
class LeadCardTest {

    private fun card(id: Long, dueAt: Long, direction: ReviewDirection, suspended: Boolean = false) =
        FlashcardEntity(
            id = id, noteId = 7, front = "mantel", back = "tablecloth", reviewDirection = direction,
            targetLanguage = "es", nativeLanguage = "en", createdAt = 0, dueAt = dueAt, suspended = suspended,
        )

    @Test
    fun `the soonest-due direction speaks for the word`() {
        val lead = leadCard(
            listOf(card(1, dueAt = 900, ReviewDirection.TARGET_FIRST), card(2, dueAt = 300, ReviewDirection.NATIVE_FIRST)),
        )
        assertEquals(2L, lead.id)
        assertFalse(lead.suspended)
    }

    @Test
    fun `a frozen direction does not speak for the word, however soon it was due`() {
        val lead = leadCard(
            listOf(
                card(1, dueAt = 900, ReviewDirection.TARGET_FIRST),
                card(2, dueAt = 300, ReviewDirection.NATIVE_FIRST, suspended = true),
            ),
        )
        assertEquals(1L, lead.id)
        assertFalse("one parked direction does not make the word frozen", lead.suspended)
    }

    @Test
    fun `a word reads as frozen only when every direction is`() {
        val lead = leadCard(
            listOf(
                card(1, dueAt = 900, ReviewDirection.TARGET_FIRST, suspended = true),
                card(2, dueAt = 300, ReviewDirection.NATIVE_FIRST, suspended = true),
            ),
        )
        assertTrue(lead.suspended)
    }

    @Test
    fun `a single-direction note is its own lead`() {
        val only = card(1, dueAt = 500, ReviewDirection.NATIVE_FIRST)
        assertEquals(only, leadCard(listOf(only)))
    }
}

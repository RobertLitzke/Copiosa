package com.copiosa.data.generation

import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dialog prices two things off this state before anything is spent: how many calls a rapid
 * download would cost, and how many sections a model change would redo. Both are read straight off
 * the per-section provenance tally, so they are worth pinning without a database.
 */
class BookDownloadStateTest {

    @Test
    fun `sections remaining is what a rapid download would cost`() {
        assertEquals(50, state(translated = 12, total = 62).sectionsRemaining)
    }

    @Test
    fun `a fully translated book has nothing left to download`() {
        assertEquals(0, state(translated = 62, total = 62).sectionsRemaining)
    }

    /** The frontier can only overshoot through a bug, but a negative "cost" would be nonsense. */
    @Test
    fun `remaining never goes negative`() {
        assertEquals(0, state(translated = 70, total = 62).sectionsRemaining)
    }

    @Test
    fun `the current model's own sections are not stale`() {
        val s = state(
            translated = 20,
            total = 62,
            sectionsByModel = mapOf("gemini-3.5-flash" to 12, "openai/gpt-4o-mini" to 8),
        )
        assertEquals(8, s.staleSections("gemini-3.5-flash"))
        assertEquals(12, s.staleSections("openai/gpt-4o-mini"))
    }

    @Test
    fun `a model with no sections of its own would redo everything`() {
        val s = state(translated = 20, total = 62, sectionsByModel = mapOf("gemini-3.5-flash" to 20))
        assertEquals(20, s.staleSections("gemini-2.5-pro"))
    }

    @Test
    fun `an untranslated book has nothing to redo`() {
        assertEquals(0, state(translated = 0, total = 62).staleSections("gemini-3.5-flash"))
    }

    private fun state(
        translated: Int,
        total: Int,
        sectionsByModel: Map<String, Int> = emptyMap(),
    ) = BookDownloadState(
        bookId = 1,
        title = "Cien años de soledad",
        mode = BookDownloadMode.WHILE_READING,
        selected = ModelOption(ProviderId.GEMINI, "gemini-3.5-flash"),
        options = listOf(ModelOption(ProviderId.GEMINI, "gemini-3.5-flash")),
        modelNames = emptyMap(),
        translatedSections = translated,
        totalSections = total,
        sectionsByModel = sectionsByModel,
    )
}

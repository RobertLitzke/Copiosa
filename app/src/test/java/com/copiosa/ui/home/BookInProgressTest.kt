package com.copiosa.ui.home

import com.copiosa.data.local.BookEntity
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which book Home offers to continue. The regression this pins: `updatedAt` moves whenever a section
 * is translated, so a book downloading in the background used to take the slot from the one being
 * read.
 */
class BookInProgressTest {

    @Test
    fun `the book read last wins over one that is only downloading`() {
        val reading = book(id = 1, posParagraph = 400, lastReadAt = 1_000, updatedAt = 500)
        val downloading = book(id = 2, posParagraph = 3, lastReadAt = 200, updatedAt = 9_000)

        // observeBooks hands them over newest-`updatedAt` first, which is what used to decide it.
        assertEquals(1L, bookInProgress(listOf(downloading, reading))?.id)
    }

    @Test
    fun `a book with a recorded read time outranks one without`() {
        val legacy = book(id = 1, posParagraph = 400, lastReadAt = null, updatedAt = 9_000)
        val read = book(id = 2, posParagraph = 10, lastReadAt = 100, updatedAt = 50)

        assertEquals(2L, bookInProgress(listOf(legacy, read))?.id)
    }

    @Test
    fun `books read before the column existed keep the old order`() {
        val older = book(id = 1, posParagraph = 400, lastReadAt = null, updatedAt = 100)
        val newer = book(id = 2, posParagraph = 10, lastReadAt = null, updatedAt = 900)

        assertEquals(2L, bookInProgress(listOf(older, newer))?.id)
    }

    @Test
    fun `finished and never-opened books are not continued`() {
        val finished = book(id = 1, posParagraph = 400, lastReadAt = 9_000, finishedReading = true)
        val unopened = book(id = 2, posParagraph = 0, lastReadAt = null, updatedAt = 9_000)

        assertNull(bookInProgress(listOf(finished, unopened)))
    }

    private fun book(
        id: Long,
        posParagraph: Int,
        lastReadAt: Long?,
        updatedAt: Long = 0,
        finishedReading: Boolean = false,
    ) = BookEntity(
        id = id,
        title = "Book $id",
        originalTitle = "Book $id",
        level = Cefr.B1,
        country = "ES",
        targetLanguage = "es",
        nativeLanguage = "en",
        provider = ProviderId.OPENROUTER,
        model = "openai/gpt-4o-mini",
        totalParagraphs = 1_000,
        wordCount = 50_000,
        totalSections = 100,
        posParagraph = posParagraph,
        finishedReading = finishedReading,
        createdAt = 0,
        updatedAt = updatedAt,
        lastReadAt = lastReadAt,
    )
}

package com.copiosa.ui.audio

import com.copiosa.data.local.AudioItemEntity
import com.copiosa.domain.model.AudioStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fold that turns the flat playlist query into folders without a second query. */
class AudioGroupingTest {

    @Test
    fun `untagged items pass through in order`() {
        val entries = groupByTag(listOf(item(1), item(2), item(3)))

        assertEquals(3, entries.size)
        assertTrue(entries.all { it is AudioListEntry.Loose })
        assertEquals(listOf(1L, 2L, 3L), entries.map { (it as AudioListEntry.Loose).item.id })
    }

    @Test
    fun `a folder takes the position of its first track and swallows the rest`() {
        val entries = groupByTag(
            listOf(
                item(1),
                item(2, tag = "Cien años", sortOrder = 100.0),
                item(3),
                item(4, tag = "Cien años", sortOrder = 200.0),
                item(5),
            ),
        )

        // Five rows become four: loose, folder, loose, loose.
        assertEquals(4, entries.size)
        val group = entries[1] as AudioListEntry.Group
        assertEquals("Cien años", group.tag)
        assertEquals(listOf(2L, 4L), group.tracks.map { it.id })
        assertEquals(listOf(1L, 3L, 5L), entries.filterIsInstance<AudioListEntry.Loose>().map { it.item.id })
    }

    @Test
    fun `tracks sort by their own key, not by whether they were played`() {
        // The DAO sinks played items, so a heard chapter 1 arrives after an unheard chapter 2.
        val entries = groupByTag(
            listOf(
                item(2, tag = "Libro", sortOrder = 200.0),
                item(1, tag = "Libro", sortOrder = 100.0, played = true),
            ),
        )

        val group = entries.single() as AudioListEntry.Group
        assertEquals(listOf(1L, 2L), group.tracks.map { it.id })
    }

    @Test
    fun `two folders stay separate and keep their first-seen order`() {
        val entries = groupByTag(
            listOf(
                item(1, tag = "B"),
                item(2, tag = "A"),
                item(3, tag = "B"),
            ),
        )

        assertEquals(listOf("B", "A"), entries.filterIsInstance<AudioListEntry.Group>().map { it.tag })
    }

    @Test
    fun `a folder counts what is unheard, ready and still rendering`() {
        val entries = groupByTag(
            listOf(
                item(1, tag = "Libro", sortOrder = 1.0, played = true, durationMs = 60_000),
                item(2, tag = "Libro", sortOrder = 2.0, durationMs = 120_000),
                item(3, tag = "Libro", sortOrder = 3.0, status = AudioStatus.PENDING),
                item(4, tag = "Libro", sortOrder = 4.0, status = AudioStatus.FAILED),
            ),
        )

        val group = entries.single() as AudioListEntry.Group
        assertEquals(3, group.unheard)
        assertEquals(2, group.ready)
        assertEquals(1, group.pending)
        assertEquals(1, group.failed)
        assertEquals(180_000L, group.totalMs)
    }

    private fun item(
        id: Long,
        tag: String? = null,
        sortOrder: Double = id.toDouble(),
        played: Boolean = false,
        status: AudioStatus = AudioStatus.READY,
        durationMs: Long? = null,
    ) = AudioItemEntity(
        id = id,
        title = "t$id",
        text = "x",
        langCode = "es",
        status = status,
        played = played,
        sortOrder = sortOrder,
        createdAt = 0,
        durationMs = durationMs,
        tag = tag,
    )
}

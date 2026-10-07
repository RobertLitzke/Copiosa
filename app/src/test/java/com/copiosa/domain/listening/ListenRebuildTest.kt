package com.copiosa.domain.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ListenRebuildTest {

    @Test
    fun `tiles drop punctuation and the opening capital`() {
        assertEquals(
            listOf("dónde", "está", "la", "estación", "de", "tren"),
            ListenRebuild.tilesOf("¿Dónde está la estación de tren?"),
        )
    }

    @Test
    fun `a proper noun past the first word keeps its capital`() {
        assertEquals(
            listOf("ayer", "fuimos", "a", "Madrid", "en", "coche"),
            ListenRebuild.tilesOf("Ayer fuimos a Madrid en coche."),
        )
    }

    @Test
    fun `inner apostrophes and hyphens survive`() {
        assertEquals(
            listOf("je", "crois", "qu'il", "est", "peut-être", "parti"),
            ListenRebuild.tilesOf("Je crois qu'il est peut-être parti."),
        )
    }

    @Test
    fun `stray punctuation tokens are not tiles`() {
        assertEquals(
            listOf("vino", "tarde", "como", "siempre"),
            ListenRebuild.tilesOf("Vino tarde — como siempre."),
        )
    }

    @Test
    fun `too short or too long is refused`() {
        assertNull(ListenRebuild.tilesOf("Hola, ¿qué tal?"))
        assertNull(ListenRebuild.tilesOf((1..13).joinToString(" ") { "palabra$it" }))
        assertNotNull(ListenRebuild.tilesOf((1..12).joinToString(" ") { "palabra$it" }))
    }

    @Test
    fun `a sentence of one repeated word is refused`() {
        assertNull(ListenRebuild.tilesOf("No, no, no, no."))
    }

    @Test
    fun `the shuffle never arrives solved`() {
        val tiles = listOf("el", "gato", "come", "pescado")
        repeat(500) { seed ->
            val order = ListenRebuild.shuffle(tiles, Random(seed))
            assertEquals(tiles.indices.toSet(), order.toSet())
            assertNotEquals(tiles, order.map { tiles[it] })
        }
    }

    @Test
    fun `the shuffle is unsolved even with repeated words`() {
        // Five of six tiles are the same word: most permutations read as the answer.
        val tiles = listOf("la", "la", "la", "la", "la", "casa")
        repeat(200) { seed ->
            val order = ListenRebuild.shuffle(tiles, Random(seed))
            assertNotEquals(tiles, order.map { tiles[it] })
        }
    }

    @Test
    fun `prefix counts only the leading run of right words`() {
        val tiles = listOf("el", "gato", "come", "pescado")
        assertEquals(4, ListenRebuild.correctPrefix(tiles, listOf(0, 1, 2, 3)))
        assertEquals(2, ListenRebuild.correctPrefix(tiles, listOf(0, 1, 3, 2)))
        assertEquals(0, ListenRebuild.correctPrefix(tiles, listOf(1, 0, 2, 3)))
        assertEquals(0, ListenRebuild.correctPrefix(tiles, emptyList()))
    }

    @Test
    fun `duplicate words are interchangeable`() {
        // "la" appears twice; tapping the second copy first is still the right word in that slot.
        val tiles = listOf("la", "niña", "ve", "la", "luna")
        assertEquals(5, ListenRebuild.correctPrefix(tiles, listOf(3, 1, 2, 0, 4)))
    }

    @Test
    fun `item keeps the written sentence and a valid order`() {
        val item = ListenRebuild.itemFor(9L, "El tren sale a las ocho.", "The train leaves at eight.", Random(1))!!
        assertEquals("El tren sale a las ocho.", item.sentence)
        assertEquals(listOf("el", "tren", "sale", "a", "las", "ocho"), item.tiles)
        assertEquals(item.tiles.indices.toSet(), item.order.toSet())
        assertNull(item.audioPath)
    }
}

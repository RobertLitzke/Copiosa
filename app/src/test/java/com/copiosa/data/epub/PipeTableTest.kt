package com.copiosa.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [parsePipeTable] is the one place a stored table is read, by both the reader and the exporter, and
 * half of what it reads is LLM output. These cases are mostly about what it must *refuse*: anything
 * that no longer parses falls back to plain-paragraph rendering, which is far better than a reader
 * showing pipe characters or an exporter emitting a malformed `<table>`.
 */
class PipeTableTest {

    @Test
    fun `a table round-trips through render and parse`() {
        val table = PipeTable(
            header = listOf("Person", "Verb"),
            rows = listOf(listOf("yo", "hablo"), listOf("tú", "hablas")),
        )
        assertEquals(table, parsePipeTable(renderPipeTable(table)))
    }

    @Test
    fun `the separator row is structure, not content`() {
        val table = parsePipeTable("| a | b |\n| --- | :-: |\n| 1 | 2 |")!!
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `a missing separator row still parses`() {
        // A model asked to keep the |---| row will sometimes drop it anyway; the table is still
        // perfectly readable without one, so refusing it would lose a table for no reason.
        val table = parsePipeTable("| a | b |\n| 1 | 2 |")!!
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `ragged rows are padded to the widest`() {
        val table = parsePipeTable("| a | b | c |\n| 1 |")!!
        assertEquals(3, table.width)
        assertEquals(listOf("1", "", ""), table.rows[0])
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "", "")), table.padded())
    }

    @Test
    fun `an escaped pipe is content rather than a cell boundary`() {
        val table = parsePipeTable("| ${escapeCell("a|b")} | c |\n| --- | --- |\n| 1 | 2 |")!!
        assertEquals(listOf("a|b", "c"), table.header)
    }

    @Test
    fun `prose is not a table however many pipes it contains`() {
        assertNull(parsePipeTable("He said | she said | they said"))
        assertNull(parsePipeTable("| a | b |"))
        assertNull(parsePipeTable("Ordinary prose.\nOn two lines."))
        assertNull(parsePipeTable(""))
    }

    @Test
    fun `a table the model reflowed into prose stops being a table`() {
        // The failure mode the reader and exporter fall back for: the row structure is gone, so
        // there is nothing left to lay out as a grid.
        assertNull(parsePipeTable("Person: yo, verb: hablo. Person: tú, verb: hablas."))
    }
}

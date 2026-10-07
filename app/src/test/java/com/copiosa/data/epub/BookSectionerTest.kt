package com.copiosa.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSectionerTest {

    @Test
    fun `ords are dense and ordered across chapters`() {
        val blocks = BookSectioner.assign(
            listOf(
                chapter("One", para(100), para(100)),
                chapter("Two", para(100)),
            ),
        )

        assertEquals((0 until blocks.size).toList(), blocks.map { it.ord })
        assertEquals(listOf(0, 0, 0, 1, 1), blocks.map { it.chapterIndex })
    }

    @Test
    fun `section indices are dense from zero`() {
        val blocks = BookSectioner.assign(
            listOf(
                chapter("One", para(3000), para(3000), para(3000)),
                chapter("Two", para(100)),
            ),
        )
        val sections = blocks.map { it.sectionIndex }.distinct()
        assertEquals((0..sections.max()).toList(), sections)
    }

    @Test
    fun `a long chapter splits at the char budget`() {
        val blocks = BookSectioner.assign(
            listOf(chapter("One", para(3000), para(3000), para(3000))),
        )

        // 3000+3000 > 4000 already, so each paragraph (after the first pair breaches) splits.
        val bySection = blocks.filter { it.kind == BlockKind.PARA }.groupBy { it.sectionIndex }
        assertTrue("expected multiple sections, got ${bySection.keys}", bySection.size > 1)
        bySection.values.forEach { section ->
            assertTrue(section.sumOf { it.text.length } <= BookSectioner.SECTION_CHARS + 3000)
        }
    }

    @Test
    fun `a section never spans a chapter boundary`() {
        val blocks = BookSectioner.assign(
            listOf(
                chapter("One", para(50)),
                chapter("Two", para(50)),
            ),
        )

        val chapterSections = blocks.groupBy { it.chapterIndex }.mapValues { (_, v) -> v.map { it.sectionIndex }.toSet() }
        assertTrue((chapterSections[0]!! intersect chapterSections[1]!!).isEmpty())
    }

    @Test
    fun `a heading joins the section that follows it`() {
        // Fill a section almost to the brim, then a heading + one more paragraph: the heading must
        // move with the paragraph into the new section rather than dangle at the end of the old one.
        val blocks = BookSectioner.assign(
            listOf(
                EpubChapter(
                    title = null,
                    blocks = listOf(
                        EpubBlock(BlockKind.PARA, "x".repeat(3900)),
                        EpubBlock(BlockKind.HEADING, "## Scene Two"),
                        EpubBlock(BlockKind.PARA, "y".repeat(500)),
                    ),
                ),
            ),
        )

        val heading = blocks.first { it.kind == BlockKind.HEADING }
        val follower = blocks.first { it.text.startsWith("y") }
        val opener = blocks.first { it.text.startsWith("x") }
        assertEquals(heading.sectionIndex, follower.sectionIndex)
        assertTrue(heading.sectionIndex != opener.sectionIndex)
    }

    @Test
    fun `the chapter heading opens the chapter's first section`() {
        val blocks = BookSectioner.assign(listOf(chapter("One", para(100))))
        assertEquals(BlockKind.HEADING, blocks[0].kind)
        assertEquals(blocks[0].sectionIndex, blocks[1].sectionIndex)
    }

    // --- helpers ------------------------------------------------------------

    private fun chapter(title: String, vararg paras: EpubBlock) = EpubChapter(
        title = title,
        blocks = listOf(EpubBlock(BlockKind.HEADING, "## $title")) + paras,
    )

    @Test
    fun `an image costs nothing against the section budget`() {
        // The budget bounds one LLM call, and images are filtered out of the request, so charging
        // them would shrink the real amount of prose each call carries.
        val withImages = BookSectioner.assign(
            listOf(chapter("One", para(2000), image(), image(), image(), para(2000))),
        )
        val withoutImages = BookSectioner.assign(listOf(chapter("One", para(2000), para(2000))))

        assertEquals(
            withoutImages.map { it.sectionIndex }.distinct().size,
            withImages.map { it.sectionIndex }.distinct().size,
        )
    }

    @Test
    fun `an image keeps its position rather than being deferred like a heading`() {
        val blocks = BookSectioner.assign(listOf(chapter("One", image(), para(100))))
        // Ords are the reader's list indices, so a plate has to stay where the author put it.
        assertEquals(
            listOf(BlockKind.HEADING, BlockKind.IMAGE, BlockKind.PARA),
            blocks.map { it.kind },
        )
        assertEquals((0 until blocks.size).toList(), blocks.map { it.ord })
    }

    @Test
    fun `lists, tables and notes are ordinary body text`() {
        val blocks = BookSectioner.assign(
            listOf(
                chapter(
                    "One",
                    EpubBlock(BlockKind.LIST, "- ${"w".repeat(2500)}"),
                    EpubBlock(BlockKind.TABLE, "| ${"w".repeat(2500)} |"),
                    EpubBlock(BlockKind.NOTE, "[^1]: ${"w".repeat(100)}"),
                ),
            ),
        )

        assertEquals((0 until blocks.size).toList(), blocks.map { it.ord })
        // They fill the budget like prose, so the oversized pair splits rather than riding along.
        assertTrue(blocks.map { it.sectionIndex }.distinct().size > 1)
        // And a note is absorbed by the running section rather than opening one of its own —
        // a section per chapter of notes would be an extra LLM call per chapter.
        assertEquals(blocks[blocks.lastIndex - 1].sectionIndex, blocks.last().sectionIndex)
    }

    private fun para(chars: Int) = EpubBlock(BlockKind.PARA, "w".repeat(chars))

    private fun image() = EpubBlock(BlockKind.IMAGE, "![](1.jpg)")
}

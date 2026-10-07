package com.copiosa.data.epub

import com.copiosa.data.local.BookParagraphEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The payoff test: write a book out and read it back in with [EpubParser], the same code path a
 * real e-reader's parser exercises. If the container is malformed, the cover page leaks in as a
 * chapter, or the emphasis markers do not survive, this is what says so.
 */
class EpubExportRoundTripTest {

    private val paragraphs = listOf(
        para(0, 0, "## Chapter One", "## Capitulo Uno", heading = true),
        para(1, 0, "It was *cold* and **dark**.", "Hacia *frio* y estaba **oscuro**."),
        para(2, 0, "Tom & Jerry <not a tag>.", "Tom y Jerry <no es una etiqueta>."),
        para(3, 1, "## Chapter Two", "## Capitulo Dos", heading = true),
        para(4, 1, "The wind rose.", "Se levanto el viento."),
    )

    /** The structural block types, as a chapter of their own for the two-cycle stability test. */
    private val structured = listOf(
        para(0, 0, "## Chapter One", "## Capitulo Uno", heading = true),
        para(1, 0, "Before the list.", "Antes de la lista."),
        para(2, 0, "- Top item", "- Elemento superior", kind = BookParagraphEntity.KIND_LIST),
        para(3, 0, "  1. First nested", "1. Primero anidado", kind = BookParagraphEntity.KIND_LIST),
        para(4, 0, "  2. Second nested", "2. Segundo anidado", kind = BookParagraphEntity.KIND_LIST),
        para(5, 0, "- Second top", "- Segundo superior", kind = BookParagraphEntity.KIND_LIST),
        para(
            6, 0,
            "| Person | Verb |\n| --- | --- |\n| I | speak |",
            "| Persona | Verbo |\n| --- | --- |\n| yo | hablo |",
            kind = BookParagraphEntity.KIND_TABLE,
        ),
        para(7, 0, "A remark[^1] here.", "Un comentario[^1] aqui."),
        para(8, 0, "![](1.jpg)", "![](1.jpg)", kind = BookParagraphEntity.KIND_IMAGE),
        para(9, 0, "[^1]: The note body.", "[^1]: El cuerpo de la nota.", kind = BookParagraphEntity.KIND_NOTE),
    )

    private fun export(
        variant: EpubExportVariant,
        rows: List<BookParagraphEntity> = paragraphs,
        images: List<EpubImage> = emptyList(),
    ): EpubBook {
        val doc = EpubExporter.build(
            book = book(),
            paragraphs = rows,
            variant = variant,
            models = listOf("openai/gpt-4o-mini"),
            strings = strings(),
            cover = coverImage(),
            now = 1_770_000_000_000,
            images = images,
        )
        val file = File(Files.createTempDirectory("epub_export").toFile(), "out.epub")
        file.outputStream().use { EpubPackager.pack(it, doc) }
        return EpubParser().parse(file)
    }

    @Test
    fun `a target-only export re-imports as the same book`() {
        val book = export(EpubExportVariant.TARGET_ONLY)

        assertEquals("El Hobbit", book.title)
        assertEquals("J. R. R. Tolkien", book.author)
        // The cover page and the nav document must not come back as chapters.
        assertEquals(2, book.chapters.size)
        assertEquals("Capitulo Uno", book.chapters[0].title)
        assertEquals("Capitulo Dos", book.chapters[1].title)
        assertNotNull(book.coverBytes)
        assertArrayEquals(FAKE_JPEG, book.coverBytes)
    }

    @Test
    fun `every block survives with its markdown intact`() {
        val chapter = export(EpubExportVariant.TARGET_ONLY).chapters[0]

        assertEquals(BlockKind.HEADING, chapter.blocks[0].kind)
        assertEquals("## Capitulo Uno", chapter.blocks[0].text)
        assertEquals(
            listOf("Hacia *frio* y estaba **oscuro**.", "Tom y Jerry <no es una etiqueta>."),
            chapter.blocks.filter { it.kind == BlockKind.PARA }.map { it.text },
        )
    }

    @Test
    fun `the chapter heading is not duplicated across a round trip`() {
        // EpubParser synthesises a "## Title" block from the first heading it sees and then dedupes
        // the element itself. Emitting our own extra heading would grow a phantom block per cycle.
        val chapter = export(EpubExportVariant.TARGET_ONLY).chapters[0]
        assertEquals(1, chapter.blocks.count { it.kind == BlockKind.HEADING })
    }

    /**
     * Documents a known, accepted asymmetry rather than pretending it round-trips: [EpubParser]
     * drops `class`, so a bilingual file's gloss paragraphs come back as ordinary siblings.
     */
    @Test
    fun `a bilingual export re-imports with both languages as separate paragraphs`() {
        val chapter = export(EpubExportVariant.BILINGUAL).chapters[0]
        val paras = chapter.blocks.filter { it.kind == BlockKind.PARA }.map { it.text }

        // Two source paragraphs, each paired, plus the chapter title's own counterpart.
        assertEquals(5, paras.size)
        // The title's gloss carries no "##" — outside an <hN> those are just two characters.
        assertEquals("Chapter One", paras[0])
        assertEquals("Hacia *frio* y estaba **oscuro**.", paras[1])
        assertEquals("It was *cold* and **dark**.", paras[2])
    }

    @Test
    fun `lists, tables, notes and images survive a round trip`() {
        val chapter = export(
            EpubExportVariant.TARGET_ONLY,
            rows = structured,
            images = listOf(bodyImage("1.jpg")),
        ).chapters[0]

        assertEquals(
            listOf("- Elemento superior", "  1. Primero anidado", "  2. Segundo anidado", "- Segundo superior"),
            chapter.blocks.filter { it.kind == BlockKind.LIST }.map { it.text },
        )
        assertEquals(
            "| Persona | Verbo |\n| --- | --- |\n| yo | hablo |",
            chapter.blocks.single { it.kind == BlockKind.TABLE }.text,
        )
        assertEquals("![](1.jpg)", chapter.blocks.single { it.kind == BlockKind.IMAGE }.text)
        assertEquals("[^1]: El cuerpo de la nota.", chapter.blocks.single { it.kind == BlockKind.NOTE }.text)
        assertTrue(chapter.blocks.any { it.text == "Un comentario[^1] aqui." })
    }

    /**
     * The test that catches what nothing else does. Every marker-compounding bug — a list item
     * re-exported as `- - item`, a note body emitted once per cycle, the phantom heading the dedupe
     * exists to prevent — is invisible in a single round trip and unmistakable across two.
     */
    @Test
    fun `a second export-import cycle produces exactly the same blocks`() {
        val images = listOf(bodyImage("1.jpg"))
        val first = export(EpubExportVariant.TARGET_ONLY, rows = structured, images = images)

        // Feed cycle one's output back in as stored rows, then export and re-import again.
        val rows = first.chapters.flatMapIndexed { chapterIndex, chapter ->
            chapter.blocks.map { it.kind to it.text }.mapIndexed { i, (kind, text) ->
                para(
                    ord = chapterIndex * 100 + i,
                    chapterIndex = chapterIndex,
                    original = text,
                    translated = text,
                    kind = when (kind) {
                        BlockKind.HEADING -> BookParagraphEntity.KIND_HEADING
                        BlockKind.LIST -> BookParagraphEntity.KIND_LIST
                        BlockKind.TABLE -> BookParagraphEntity.KIND_TABLE
                        BlockKind.NOTE -> BookParagraphEntity.KIND_NOTE
                        BlockKind.IMAGE -> BookParagraphEntity.KIND_IMAGE
                        BlockKind.PARA -> BookParagraphEntity.KIND_PARA
                    },
                )
            }
        }
        val second = export(EpubExportVariant.TARGET_ONLY, rows = rows, images = images)

        assertEquals(
            first.chapters.map { c -> c.blocks.map { it.kind to it.text } },
            second.chapters.map { c -> c.blocks.map { it.kind to it.text } },
        )
    }

    /**
     * Documents the accepted asymmetry for lists, alongside the paragraph one above: the bilingual
     * gloss has to live *inside* the `<li>` (as a sibling it would be invalid XHTML), so re-import
     * reads both languages as one run-on item rather than as a pair.
     */
    @Test
    fun `a bilingual list item re-imports as one run-on item`() {
        val chapter = export(EpubExportVariant.BILINGUAL, rows = structured, images = emptyList()).chapters[0]
        val items = chapter.blocks.filter { it.kind == BlockKind.LIST }.map { it.text }

        assertEquals(4, items.size)
        assertEquals("- Elemento superior Top item", items[0])
    }

    /**
     * A book imported before the structural kinds existed has PARA rows whose text the LLM wrote,
     * and an LLM writes bullets. Keying the exporter's list handling on the text rather than the
     * stored kind would silently turn those into a `<ul>`.
     */
    @Test
    fun `an old paragraph row that merely starts with a dash stays a paragraph`() {
        val chapter = export(
            EpubExportVariant.TARGET_ONLY,
            rows = listOf(
                para(0, 0, "## Chapter One", "## Capitulo Uno", heading = true),
                para(1, 0, "- Not a list row", "- No es una fila de lista"),
            ),
        ).chapters[0]

        assertTrue(chapter.blocks.none { it.kind == BlockKind.LIST })
        assertEquals("- No es una fila de lista", chapter.blocks.last { it.kind == BlockKind.PARA }.text)
    }
}

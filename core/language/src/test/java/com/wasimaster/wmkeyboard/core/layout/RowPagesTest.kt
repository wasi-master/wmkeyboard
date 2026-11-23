package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.language.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rows with more pages, swapped in by a swipe across the row. */
class RowPagesTest {

    private val letters = listOf(
        "qwertyuiop".map { Key(it.toString()) },
        "asdfghjkl".map { Key(it.toString()) },
        listOf(Key(" ", action = KeyAction.Space, width = 10f)),
    )

    private fun layer(vararg pages: RowPages) = LayerSpec(rows = letters, rowPages = pages.toList())

    private fun compiled(spec: LayerSpec) = KeyboardLayout(name = "t", rows = spec.rows, rowPages = spec.pagesByRow())

    private val emoji = RowPage(fill = RowPageFill.EMOJI)
    private val typed = RowPage(keys = listOf(Key("+"), Key("−"), Key("×"), Key("÷")))

    @Test
    fun `page 0 is the row itself and leaves the grid alone`() {
        val grid = compiled(layer(RowPages(0, listOf(emoji))))
        assertSame(grid, grid.withRowPages(emptyMap()) { listOf("😀") })
        assertSame(grid, grid.withRowPages(mapOf(0 to 0)) { listOf("😀") })
    }

    @Test
    fun `a filled page takes the row's width in as many keys as it is wide`() {
        val grid = compiled(layer(RowPages(0, listOf(emoji))))
        val paged = grid.withRowPages(mapOf(0 to 1)) { List(20) { i -> "e$i" } }
        val top = paged.rows[0]
        assertEquals(10, top.size)
        assertEquals(10f, top.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
        assertEquals("e0", top[0].output)
        // The other rows keep their own keys.
        assertSame(grid.rows[1], paged.rows[1])
    }

    @Test
    fun `a fill with fewer entries draws just those`() {
        val grid = compiled(layer(RowPages(1, listOf(RowPage(fill = RowPageFill.DIGITS)))))
        val paged = grid.withRowPages(mapOf(1 to 1)) { listOf("1", "2", "2", "") }
        assertEquals(listOf("1", "2"), paged.rows[1].map { it.label })
    }

    @Test
    fun `typed keys come as they are, one row tall`() {
        val tall = RowPage(keys = listOf(Key("x", rowSpan = 3)))
        val grid = compiled(layer(RowPages(0, listOf(typed, tall))))
        assertEquals(listOf("+", "−", "×", "÷"), grid.withRowPages(mapOf(0 to 1)) { emptyList() }.rows[0].map { it.label })
        assertEquals(1, grid.withRowPages(mapOf(0 to 2)) { emptyList() }.rows[0].single().rowSpan)
    }

    @Test
    fun `a row a tall key stands in has no pages`() {
        val rows = listOf(
            letters[0].dropLast(1) + Key("⏎", action = KeyAction.Enter, rowSpan = 2),
            letters[1],
            letters[2],
        )
        val spec = LayerSpec(rows = rows, rowPages = listOf(RowPages(0, listOf(emoji)), RowPages(1, listOf(emoji)), RowPages(2, listOf(emoji))))
        assertEquals(setOf(2), spec.pagesByRow().keys)
    }

    @Test
    fun `pages wrap round in both directions`() {
        assertEquals(1, nextRowPage(0, 1, 3))
        assertEquals(0, nextRowPage(2, 1, 3))
        assertEquals(2, nextRowPage(0, -1, 3))
        assertEquals(0, nextRowPage(0, 1, 1))
    }

    @Test
    fun `pages follow their rows through a reorder and a delete`() {
        val pages = listOf(RowPages(0, listOf(emoji)), RowPages(2, listOf(typed)))
        assertEquals(listOf(RowPages(1, listOf(typed)), RowPages(2, listOf(emoji))), pages.reindexed(listOf(1, 2, 0)))
        assertEquals(listOf(RowPages(1, listOf(typed))), pages.reindexed(listOf(1, 2)))
        assertNull(pages.reindexed(listOf(1)))
    }

    @Test
    fun `setting a row's pages replaces them and an empty list removes them`() {
        val spec = layer(RowPages(0, listOf(emoji)))
        assertEquals(listOf(RowPages(0, listOf(typed))), spec.withRowPagesAt(0, listOf(typed)).rowPages)
        assertNull(spec.withRowPagesAt(0, emptyList()).rowPages)
    }

    @Test
    fun `repair drops pages for a missing row, an empty page and a repeat`() {
        val spec = LayoutSpec(
            id = "t",
            name = "t",
            langId = "en",
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(
                    rows = letters.dropLast(1) + listOf(letters[2] + Key("⌫", action = KeyAction.Delete) + Key("⏎", action = KeyAction.Enter)),
                    rowPages = listOf(
                        RowPages(0, listOf(emoji, RowPage())),
                        RowPages(0, listOf(typed)),
                        RowPages(7, listOf(typed)),
                    ),
                ),
            ),
        )
        val repaired = spec.repair()
        assertEquals(listOf(RowPages(0, listOf(emoji))), repaired.spec.layers.getValue("letters").rowPages)
        assertTrue(repaired.repairNotes.any { it.stringRes == R.string.core_lang_repair_row_pages_dropped })
    }

    @Test
    fun `row pages survive the layout file and an unknown fill reads as none`() {
        val spec = layer(RowPages(0, listOf(emoji, RowPage(fill = RowPageFill.SYMBOLS, symbolSet = "builtin_coding"), typed)))
        val json = layoutJson.encodeToString(LayerSpec.serializer(), spec)
        assertEquals(spec, layoutJson.decodeFromString(LayerSpec.serializer(), json))
        val newer = """{"rows":[[{"label":"a"}]],"rowPages":[{"row":0,"pages":[{"fill":"weather"}]}]}"""
        assertNull(layoutJson.decodeFromString(LayerSpec.serializer(), newer).rowPages!!.single().pages.single().fill)
    }
}

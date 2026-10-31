package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The further pages of a paginated alphabet, as the layout editor's tabs reach them. */
class LayoutPagesTest {

    private fun grid(label: String) = LayerSpec(rows = listOf(listOf(Key(label))))

    private val letters = LayoutLayer.LETTERS.key
    private val board = LayoutSpec(
        id = "pages",
        name = "pages",
        layers = mapOf(
            letters to grid("क"),
            "page10" to grid("ज"),
            "page2" to grid("ख"),
            LayoutLayer.SYMBOLS.key to grid("!"),
            PanelKind.entries.first().layerKey to grid("p"),
        ),
    )

    @Test
    fun onlyThePagesAreListedInReadingOrder() {
        assertEquals(listOf("page2", "page10"), board.pageLayerKeys())
    }

    @Test
    fun theSwapShowsThePageAsTheLettersAndUndoesItself() {
        val onPage = board.withLettersSwappedFor("page2")
        assertEquals(grid("ख"), onPage.layers[letters])
        assertEquals(grid("क"), onPage.layers["page2"])
        assertEquals(board.layers, onPage.withLettersSwappedFor("page2").layers)
    }

    @Test
    fun noPageLeavesTheLayoutAlone() {
        assertSame(board, board.withLettersSwappedFor(null))
    }

    @Test
    fun anEditOnAPageLandsOnThatPage() {
        val onPage = board.withLettersSwappedFor("page2")
        val edited = onPage.copy(layers = onPage.layers + (letters to grid("ग")))
        val stored = edited.withLettersSwappedFor("page2")
        assertEquals(grid("ग"), stored.layers["page2"])
        assertEquals(grid("क"), stored.layers[letters])
    }

    @Test
    fun resettingAPageRemovesItAndKeepsTheLetters() {
        val onPage = board.withLettersSwappedFor("page2")
        val stored = onPage.copy(layers = onPage.layers - letters).withLettersSwappedFor("page2")
        assertEquals(grid("क"), stored.layers[letters])
        assertEquals(listOf("page10"), stored.pageLayerKeys())
    }
}

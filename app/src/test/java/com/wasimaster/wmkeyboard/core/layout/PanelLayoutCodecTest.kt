package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLayoutCodecTest {

    @Test
    fun `round trips every shipped panel`() {
        val all = BuiltInPanelLayouts.byKind.values.toList()
        assertEquals(all, PanelLayoutCodec.decodeList(PanelLayoutCodec.encodeList(all)))
        for (spec in all) {
            assertEquals(spec, PanelLayoutCodec.decode(PanelLayoutCodec.encode(spec)))
        }
    }

    @Test
    fun `round trips field and edit keys through the typing layout codec too`() {
        val original = LayoutSpec(
            id = "custom_1",
            name = "Test",
            langId = "en",
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(
                    listOf(
                        listOf(
                            Key("", action = KeyAction.Field(PanelFieldKind.CLIPBOARD_LIST), width = 6f, rowSpan = 2),
                            Key(
                                "",
                                action = KeyAction.Edit(TextEditAction.HOME),
                                actionAlternates = listOf(KeyAlternate(KeyAction.Edit(TextEditAction.PAGE_UP))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(original, LayoutCodec.decode(LayoutCodec.encode(original)))
    }

    @Test
    fun `the file spells a component by its lowercase kind`() {
        val json = PanelLayoutCodec.encode(BuiltInPanelLayouts.EMOJI)
        assertTrue(json.contains("\"type\":\"field\""))
        assertTrue(json.contains("\"kind\":\"emoji_grid\""))
        assertTrue(json.contains("\"panel\":\"emoji\""))
    }

    @Test
    fun `a component kind from a newer build coerces to UNKNOWN rather than a real kind`() {
        val json = """
            {"panel":"emoji","grid":{"rows":[[{"label":"","action":{"type":"field","kind":"hologram"}}]]}}
        """.trimIndent()
        val decoded = PanelLayoutCodec.decode(json)
        assertNotNull(decoded)
        val action = decoded!!.grid.rows.single().single().action as KeyAction.Field
        assertEquals(PanelFieldKind.UNKNOWN, action.kind)
        assertFalse(action.kind.isReal)
    }

    @Test
    fun `an element naming a panel this build lacks is dropped without losing the others`() {
        val good = PanelLayoutCodec.encode(BuiltInPanelLayouts.TEXT_EDIT)
        val json = """[{"panel":"hologram","grid":{"rows":[]}},$good]"""
        assertEquals(listOf(BuiltInPanelLayouts.TEXT_EDIT), PanelLayoutCodec.decodeList(json))
    }

    @Test
    fun `blank and malformed stores decode to nothing`() {
        assertEquals(emptyList<PanelLayoutSpec>(), PanelLayoutCodec.decodeList(""))
        assertEquals(emptyList<PanelLayoutSpec>(), PanelLayoutCodec.decodeList("{not json"))
        assertEquals(null, PanelLayoutCodec.decode(""))
    }

    @Test
    fun `the editing encoding drops defaults and still round-trips`() {
        val spec = BuiltInPanelLayouts.CLIPBOARD
        val text = PanelLayoutCodec.encodeForEditing(spec)
        assertFalse(text.contains("\"output\""))
        assertFalse(text.contains("\"appearance\""))
        assertEquals(spec, PanelLayoutCodec.decode(text))
    }

    @Test
    fun `a component's hold is spoken for and a repeating edit key's is too`() {
        assertTrue(KeyAction.Field(PanelFieldKind.EMOJI_GRID).holdIsSpokenFor())
        assertTrue(KeyAction.Edit(TextEditAction.LEFT).holdIsSpokenFor())
        assertTrue(KeyAction.Edit(TextEditAction.BACKSPACE).holdIsSpokenFor())
        assertFalse(KeyAction.Edit(TextEditAction.HOME).holdIsSpokenFor())
        assertFalse(KeyAction.Edit(TextEditAction.SELECT).holdIsSpokenFor())
    }

    @Test
    fun `resolution prefers the user's layout and falls back to the shipped one`() {
        val mine = BuiltInPanelLayouts.clipboard(bottomRow = true)
        val resolved = resolvePanelLayouts(listOf(mine))
        assertEquals(mine, resolved.getValue(PanelKind.CLIPBOARD))
        assertEquals(BuiltInPanelLayouts.EMOJI, resolved.getValue(PanelKind.EMOJI))
        assertEquals(PanelKind.entries.toSet(), resolved.keys)
    }

    @Test
    fun `the shipped text-editing pad is the old cluster written down`() {
        val rows = BuiltInPanelLayouts.TEXT_EDIT.grid.rows
        assertEquals(4, rows.size)
        val ops = rows.map { row -> row.map { (it.action as KeyAction.Edit).op } }
        assertEquals(
            listOf(TextEditAction.LEFT, TextEditAction.UP, TextEditAction.RIGHT, TextEditAction.SELECT_ALL),
            ops[0],
        )
        assertEquals(listOf(TextEditAction.HOME, TextEditAction.END, TextEditAction.BACKSPACE), ops[3])
        assertEquals(3, rows[0][0].rowSpan)
        // Home's hold is Page Up; Left repeats so it carries no alternate.
        assertEquals(KeyAction.Edit(TextEditAction.PAGE_UP), rows[3][0].actionAlternates.single().action)
        assertTrue(rows[0][0].actionAlternates.isEmpty())
        // Rows flow around the tall arrows: the middle rows have only two keys.
        assertEquals(2, rows[1].size)
        assertEquals(2, rows[2].size)
        // Widths add to 4.4 on every row, spans counted.
        for (w in spanRowWidths(rows)) assertEquals(4.4f, w, 0.01f)
    }
}

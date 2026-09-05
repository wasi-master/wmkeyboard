package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.language.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLayoutRepairTest {

    private fun field(kind: PanelFieldKind, width: Float = 10f) =
        Key("", action = KeyAction.Field(kind), width = width)

    private fun emoji(vararg rows: List<Key>) =
        PanelLayoutSpec(PanelKind.EMOJI, LayerSpec(rows.toList()))

    private fun List<LayoutFinding>.blocking() = filter { it.severity == LayoutSeverity.BLOCKING }

    private fun List<LayoutMessage>.names(vararg res: Int) =
        any { it.stringRes in res || it.pluralsRes in res }

    @Test
    fun `every shipped panel validates clean and survives repair unchanged`() {
        val shipped = BuiltInPanelLayouts.byKind.values +
            BuiltInPanelLayouts.clipboard(bottomRow = true)
        for (spec in shipped) {
            val blocking = validatePanelLayout(spec).blocking()
            assertTrue("${spec.panel} should have no blocking findings but had $blocking", blocking.isEmpty())
            assertTrue(spec.canBeEnabled())
            val repaired = spec.repair()
            assertEquals("${spec.panel} should need no repairs", emptyList<LayoutMessage>(), repaired.repairNotes)
            assertEquals(spec, repaired.spec)
        }
    }

    @Test
    fun `the shipped panels with a bottom row carry no warnings either`() {
        assertEquals(emptyList<LayoutFinding>(), validatePanelLayout(BuiltInPanelLayouts.EMOJI))
        assertEquals(emptyList<LayoutFinding>(), validatePanelLayout(BuiltInPanelLayouts.TRACKPAD))
    }

    @Test
    fun `an emoji panel without its grid blocks and repair adds one in a new row`() {
        val spec = emoji(listOf(field(PanelFieldKind.EMOJI_TABS)), BuiltInPanelLayouts.bottomRow)
        assertFalse(spec.canBeEnabled())
        assertTrue(
            validatePanelLayout(spec).blocking()
                .any { it.text.stringRes == R.string.core_lang_panel_required_field_error },
        )
        val repaired = spec.repair()
        assertTrue(repaired.repairNotes.names(R.string.core_lang_panel_repair_field_added))
        assertEquals(3, repaired.spec.grid.rows.size)
        assertEquals(
            KeyAction.Field(PanelFieldKind.EMOJI_GRID),
            repaired.spec.grid.rows.last().single().action,
        )
        assertTrue(repaired.spec.canBeEnabled())
    }

    @Test
    fun `a duplicate component blocks and repair keeps the first`() {
        val spec = emoji(
            listOf(field(PanelFieldKind.EMOJI_GRID, 5f), field(PanelFieldKind.EMOJI_GRID, 5f)),
        )
        assertTrue(
            validatePanelLayout(spec).blocking()
                .any { it.text.pluralsRes == R.plurals.core_lang_panel_duplicate_field_error },
        )
        val repaired = spec.repair()
        assertTrue(repaired.repairNotes.names(R.string.core_lang_panel_repair_duplicate_dropped))
        assertEquals(1, repaired.spec.grid.rows.flatten().size)
        assertTrue(repaired.spec.canBeEnabled())
    }

    @Test
    fun `a component from another panel is dropped`() {
        val spec = emoji(listOf(field(PanelFieldKind.EMOJI_GRID, 8f), field(PanelFieldKind.CLIPBOARD_LIST, 2f)))
        assertTrue(
            validatePanelLayout(spec).blocking()
                .any { it.text.pluralsRes == R.plurals.core_lang_panel_wrong_field_error },
        )
        val repaired = spec.repair()
        assertTrue(repaired.repairNotes.names(R.string.core_lang_panel_repair_field_dropped))
        assertEquals(1, repaired.spec.grid.rows.flatten().size)
    }

    @Test
    fun `an unknown component kind is dropped like a foreign one`() {
        val spec = emoji(listOf(field(PanelFieldKind.EMOJI_GRID, 8f), field(PanelFieldKind.UNKNOWN, 2f)))
        assertFalse(spec.canBeEnabled())
        assertEquals(1, spec.repair().spec.grid.rows.flatten().size)
    }

    @Test
    fun `the text-editing panel accepts no component`() {
        val spec = PanelLayoutSpec(
            PanelKind.TEXT_EDIT,
            LayerSpec(listOf(listOf(Key("", action = KeyAction.Edit(TextEditAction.LEFT)), field(PanelFieldKind.EMOJI_GRID)))),
        )
        assertFalse(spec.canBeEnabled())
        val repaired = spec.repair()
        assertEquals(1, repaired.spec.grid.rows.flatten().size)
        assertTrue(repaired.spec.canBeEnabled())
    }

    @Test
    fun `a shift key on a panel blocks and is dropped`() {
        val spec = emoji(listOf(field(PanelFieldKind.EMOJI_GRID, 9f), Key("⇧", action = KeyAction.Shift)))
        assertTrue(
            validatePanelLayout(spec).blocking()
                .any { it.text.pluralsRes == R.plurals.core_lang_panel_key_not_allowed_error },
        )
        val repaired = spec.repair()
        assertTrue(repaired.repairNotes.names(R.string.core_lang_panel_repair_key_dropped))
        assertTrue(repaired.spec.grid.rows.flatten().none { it.action == KeyAction.Shift })
    }

    @Test
    fun `an empty grid is replaced by the shipped panel`() {
        val spec = emoji(emptyList())
        assertFalse(spec.canBeEnabled())
        val repaired = spec.repair()
        assertTrue(repaired.repairNotes.names(R.string.core_lang_panel_repair_reset))
        assertEquals(BuiltInPanelLayouts.EMOJI, repaired.spec)
    }

    @Test
    fun `no ABC key warns but does not block`() {
        val spec = emoji(listOf(field(PanelFieldKind.EMOJI_GRID)))
        val findings = validatePanelLayout(spec)
        assertTrue(spec.canBeEnabled())
        assertTrue(findings.any { it.text.stringRes == R.string.core_lang_panel_no_letters_warning })
        // ...and the panels that never had one do not warn.
        for (spec in listOf(BuiltInPanelLayouts.TEXT_EDIT, BuiltInPanelLayouts.CLIPBOARD)) {
            assertEquals(emptyList<LayoutFinding>(), validatePanelLayout(spec))
        }
    }

    @Test
    fun `every blocking finding is one repair clears`() {
        val broken = listOf(
            emoji(listOf(field(PanelFieldKind.EMOJI_TABS))),
            emoji(listOf(field(PanelFieldKind.EMOJI_GRID, 5f), field(PanelFieldKind.EMOJI_GRID, 5f))),
            emoji(listOf(field(PanelFieldKind.EMOJI_GRID), Key("x", action = KeyAction.Fn))),
            emoji(listOf(field(PanelFieldKind.EMOJI_GRID), Key("", action = KeyAction.Unknown("zap")))),
            emoji(listOf(field(PanelFieldKind.EMOJI_GRID), Key("w", width = -1f))),
            emoji(listOf(field(PanelFieldKind.EMOJI_GRID), Key("s", rowSpan = 99))),
            emoji(*Array(MaxRowsPerLayer + 2) { listOf(Key("r")) }, listOf(field(PanelFieldKind.EMOJI_GRID))),
            emoji(emptyList()),
        )
        for (spec in broken) {
            assertFalse("$spec should block", spec.canBeEnabled())
            val repaired = spec.repair()
            assertTrue("$repaired should enable", repaired.spec.canBeEnabled())
            assertTrue(repaired.repairNotes.isNotEmpty())
        }
    }

    @Test
    fun `row heights are dropped when repair changes the row count`() {
        val spec = PanelLayoutSpec(
            PanelKind.EMOJI,
            LayerSpec(
                rows = listOf(listOf(field(PanelFieldKind.EMOJI_TABS)), BuiltInPanelLayouts.bottomRow),
                rowHeights = listOf(0.8f, 1f),
            ),
        )
        assertEquals(null, spec.repair().spec.grid.rowHeights)
        assertEquals(BuiltInPanelLayouts.EMOJI.grid.rowHeights, BuiltInPanelLayouts.EMOJI.repair().spec.grid.rowHeights)
    }

    @Test
    fun `validate never mutates`() {
        val spec = emoji(listOf(field(PanelFieldKind.EMOJI_GRID, 5f), field(PanelFieldKind.EMOJI_GRID, 5f)))
        val copy = spec.copy()
        validatePanelLayout(spec)
        assertEquals(copy, spec)
    }

    @Test
    fun `a component key in a typing layout blocks and is dropped by repair`() {
        val spec = LayoutSpec(
            id = "custom_1",
            name = "Test",
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(
                    listOf(
                        listOf(Key("a"), field(PanelFieldKind.EMOJI_GRID, 3f)),
                        listOf(
                            Key(" ", action = KeyAction.Space),
                            Key("⌫", action = KeyAction.Delete),
                            Key("⏎", action = KeyAction.Enter),
                        ),
                    ),
                ),
            ),
        )
        assertFalse(spec.canBeEnabled())
        assertTrue(
            validateLayout(spec).blocking()
                .any { it.text.pluralsRes == R.plurals.core_lang_layout_field_key_error },
        )
        val repaired = spec.repair()
        assertTrue(repaired.repairNotes.names(R.string.core_lang_repair_field_key_deleted))
        assertTrue(repaired.spec.canBeEnabled())
        assertTrue(repaired.spec.layer(LayoutLayer.LETTERS)!!.rows.flatten().none { it.action is KeyAction.Field })
    }

    @Test
    fun `an edit key is allowed on a typing layout`() {
        val spec = LayoutSpec(
            id = "custom_1",
            name = "Test",
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(
                    listOf(
                        listOf(Key("a"), Key("", action = KeyAction.Edit(TextEditAction.LEFT))),
                        listOf(
                            Key(" ", action = KeyAction.Space),
                            Key("⌫", action = KeyAction.Delete),
                            Key("⏎", action = KeyAction.Enter),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(spec.canBeEnabled())
        assertEquals(emptyList<LayoutMessage>(), spec.repair().repairNotes)
    }
}

package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold actions (competitor gap list item 14): their stored form, which key
 * carries them, and where they land in that key's popup.
 */
class HoldActionsTest {

    private val undo = KeyAlternate(KeyAction.Tool(ToolbarTool.UNDO))
    private val clipboard = KeyAlternate(KeyAction.Tool(ToolbarTool.CLIPBOARD))
    private val selectAll = KeyAlternate(KeyAction.Edit(TextEditAction.SELECT_ALL))
    private val thanks = KeyAlternate(KeyAction.Text, label = "Thanks!")

    @Test
    fun `the list survives the round trip in order`() {
        val list = listOf(clipboard, thanks, undo, selectAll)
        assertEquals(list, HoldActionsCodec.decode(HoldActionsCodec.encode(list)))
    }

    @Test
    fun `nothing stored reads as no actions`() {
        assertEquals(emptyList<KeyAlternate>(), HoldActionsCodec.decode(null))
        assertEquals(emptyList<KeyAlternate>(), HoldActionsCodec.decode(""))
        assertEquals(emptyList<KeyAlternate>(), HoldActionsCodec.decode("not json"))
        assertEquals(emptyList<KeyAlternate>(), HoldActionsCodec.decode("""{"enter":[]}"""))
    }

    @Test
    fun `a bad entry costs that entry alone`() {
        val json = """[{"action":{"type":"teleport"}},"junk",{"action":{"type":"space"}},""" +
            """{"action":{"type":"none"}},{"action":{"type":"emoji"}}]"""
        assertEquals(
            listOf(KeyAlternate(KeyAction.Space), KeyAlternate(KeyAction.Emoji)),
            HoldActionsCodec.decode(json),
        )
    }

    @Test
    fun `a key name from a newer build reads as enter`() {
        assertEquals(HoldActionsKey.ENTER, HoldActionsKey.parse("SPACE"))
        assertEquals(HoldActionsKey.ENTER, HoldActionsKey.parse(null))
        assertEquals(HoldActionsKey.GLOBE, HoldActionsKey.parse("GLOBE"))
    }

    @Test
    fun `the actions go after what the key already offers`() {
        val enter = Key("⏎", action = KeyAction.Enter, actionAlternates = listOf(KeyAlternate(KeyAction.Newline)))
        val held = enter.withHoldActions(listOf(undo, clipboard))
        assertEquals(
            listOf(KeyAction.Newline, undo.action, clipboard.action),
            held.actionAlternates.map { it.action },
        )
        assertTrue(held.opensAlternatesPopup())
    }

    @Test
    fun `the comma keeps its marks first`() {
        val comma = Key(",", role = KeyRole.Comma, longPress = listOf("!", "?"))
        val entries = comma.withHoldActions(listOf(undo)).alternateEntries()
        assertEquals(AlternateEntry.Character("!"), entries.first())
        assertEquals(AlternateEntry.Action(undo), entries.last())
    }

    @Test
    fun `an action the popup already has is not offered twice`() {
        val key = Key("⏎", action = KeyAction.Enter, actionAlternates = listOf(undo))
        assertSame(key, key.withHoldActions(listOf(undo)))
        assertEquals(listOf(undo, clipboard), Key("⏎", action = KeyAction.Enter)
            .withHoldActions(listOf(undo, clipboard, undo)).actionAlternates)
    }

    @Test
    fun `nothing to add leaves the key as it is`() {
        val enter = Key("⏎", action = KeyAction.Enter)
        assertSame(enter, enter.withHoldActions(emptyList()))
        assertSame(enter, enter.withHoldActions(listOf(KeyAlternate(KeyAction.None))))
    }

    @Test
    fun `a key whose hold is spoken for is left alone`() {
        val delete = Key("⌫", action = KeyAction.Delete)
        assertSame(delete, delete.withHoldActions(listOf(undo)))
        val held = Key(",", holdAction = selectAll)
        assertSame(held, held.withHoldActions(listOf(undo)))
    }

    @Test
    fun `each choice names its own key`() {
        val comma = Key(",", role = KeyRole.Comma)
        val globe = Key("🌐", action = KeyAction.LanguageSwitch)
        val enter = Key("⏎", action = KeyAction.Enter)
        assertTrue(HoldActionsKey.ENTER.owns(enter, rowIndex = 2, lastRow = 3))
        assertTrue(HoldActionsKey.COMMA.owns(comma, rowIndex = 3, lastRow = 3))
        assertTrue(HoldActionsKey.COMMA.owns(Key(","), rowIndex = 3, lastRow = 3))
        assertFalse(HoldActionsKey.COMMA.owns(Key(","), rowIndex = 1, lastRow = 3))
        assertTrue(HoldActionsKey.GLOBE.owns(globe, rowIndex = 3, lastRow = 3))
        assertFalse(HoldActionsKey.GLOBE.owns(globe, rowIndex = 0, lastRow = 3))
        assertFalse(HoldActionsKey.ENTER.owns(comma, rowIndex = 3, lastRow = 3))
    }
}

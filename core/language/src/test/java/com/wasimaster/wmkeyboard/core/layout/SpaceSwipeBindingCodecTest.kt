package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stored form of the spacebar swipe bindings (competitor gap list item 3). */
class SpaceSwipeBindingCodecTest {

    @Test
    fun `bindings survive the round trip`() {
        val map = mapOf(
            SpaceSwipeSlot.UP to KeyAlternate(KeyAction.Tool(ToolbarTool.TEXT_EDIT)),
            SpaceSwipeSlot.SHORT to KeyAlternate(KeyAction.Text, label = "Thanks!"),
            SpaceSwipeSlot.LONG to KeyAlternate(KeyAction.Edit(TextEditAction.SELECT_ALL)),
        )
        assertEquals(map, SpaceSwipeBindingCodec.decode(SpaceSwipeBindingCodec.encode(map)))
    }

    @Test
    fun `nothing stored reads as no bindings`() {
        assertEquals(emptyMap<SpaceSwipeSlot, KeyAlternate>(), SpaceSwipeBindingCodec.decode(null))
        assertEquals(emptyMap<SpaceSwipeSlot, KeyAlternate>(), SpaceSwipeBindingCodec.decode(""))
        assertEquals(emptyMap<SpaceSwipeSlot, KeyAlternate>(), SpaceSwipeBindingCodec.decode("not json"))
    }

    @Test
    fun `a bad entry costs that entry alone`() {
        val json = """{"short":{"action":{"type":"teleport"}},"sideways":{"action":{"type":"space"}},""" +
            """"long":{"action":{"type":"space"}},"up":"junk"}"""
        assertEquals(
            mapOf(SpaceSwipeSlot.LONG to KeyAlternate(KeyAction.Space)),
            SpaceSwipeBindingCodec.decode(json),
        )
    }

    @Test
    fun `a cleared slot binds nothing`() {
        val map = mapOf(SpaceSwipeSlot.UP to KeyAlternate(KeyAction.None))
        assertNull(map.bindingOf(SpaceSwipeSlot.UP))
        assertNull(map.bindingOf(SpaceSwipeSlot.SHORT))
    }

    @Test
    fun `slots are named by their tokens`() {
        assertEquals(listOf("short", "long", "up"), SpaceSwipeSlot.entries.map { it.token })
        for (slot in SpaceSwipeSlot.entries) assertEquals(slot, SpaceSwipeSlot.parse(slot.token))
        assertNull(SpaceSwipeSlot.parse("down"))
    }
}

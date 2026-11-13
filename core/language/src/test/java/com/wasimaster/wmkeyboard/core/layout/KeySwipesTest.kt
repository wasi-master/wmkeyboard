package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipes put on keys from Settings: how they land on a key, merge, and are stored. */
class KeySwipesTest {

    private val one = KeyAlternate(KeyAction.Text, label = "1")
    private val copy = KeyAlternate(KeyAction.Edit(TextEditAction.COPY))

    @Test
    fun `a text arm becomes a flick and an action arm a flick action`() {
        val key = Key(label = "q").withSwipeArms(mapOf(FlickDirection.UP_RIGHT to one, FlickDirection.DOWN_LEFT to copy))
        assertEquals(mapOf(FlickDirection.UP_RIGHT to "1"), key.flick)
        assertEquals(mapOf(FlickDirection.DOWN_LEFT to copy), key.flickActions)
        assertTrue(key.hasFlicks())
    }

    @Test
    fun `the layout keeps a direction it already has an arm for`() {
        val layout = Key(label = "q", flick = mapOf(FlickDirection.UP_RIGHT to "%"))
        val key = layout.withSwipeArms(mapOf(FlickDirection.UP_RIGHT to one, FlickDirection.UP to copy))
        assertEquals(mapOf(FlickDirection.UP_RIGHT to "%"), key.flick)
        assertEquals(mapOf(FlickDirection.UP to copy), key.flickActions)
    }

    @Test
    fun `a layout action arm beats a user text arm the same way`() {
        val layout = Key(label = "q", flickActions = mapOf(FlickDirection.UP_RIGHT to copy))
        assertSame(layout, layout.withSwipeArms(mapOf(FlickDirection.UP_RIGHT to one)))
    }

    @Test
    fun `keys whose hold is spoken for, and action keys, are left alone`() {
        val arms = mapOf(FlickDirection.UP to one)
        val held = Key(label = "q", holdAction = copy)
        val repeats = Key(label = "q", repeatOnHold = true)
        val shift = Key(label = "", action = KeyAction.Shift)
        assertSame(held, held.withSwipeArms(arms))
        assertSame(repeats, repeats.withSwipeArms(arms))
        assertSame(shift, shift.withSwipeArms(arms))
    }

    @Test
    fun `nothing to add returns the same key`() {
        val key = Key(label = "q")
        assertSame(key, key.withSwipeArms(null))
        assertSame(key, key.withSwipeArms(emptyMap()))
        assertSame(key, key.withSwipeArms(mapOf(FlickDirection.UP to KeyAlternate(KeyAction.None))))
    }

    @Test
    fun `a preset fills its slots and keeps the user's others`() {
        val mine = KeyAlternate(KeyAction.Text, label = "q!")
        val merged = mapOf("q" to mapOf(FlickDirection.UP_RIGHT to mine, FlickDirection.DOWN to copy))
            .plusPreset(KeySwipePreset.NUMBERS)
        assertEquals(one, merged.getValue("q").getValue(FlickDirection.UP_RIGHT))
        assertEquals(copy, merged.getValue("q").getValue(FlickDirection.DOWN))
        assertEquals(10, merged.size)
        assertEquals("0", merged.getValue("p").getValue(FlickDirection.UP_RIGHT).label)
    }

    @Test
    fun `the editing preset puts undo and redo on z and y`() {
        val arms = KeySwipePreset.EDITING.arms
        assertEquals(KeyAction.Tool(ToolbarTool.UNDO), arms.getValue("z").getValue(FlickDirection.DOWN_LEFT).action)
        assertEquals(KeyAction.Tool(ToolbarTool.REDO), arms.getValue("y").getValue(FlickDirection.DOWN_LEFT).action)
    }

    @Test
    fun `the punctuation preset puts a comma, a question mark and an exclamation mark on the period`() {
        val arms = KeySwipePreset.PUNCTUATION.arms.getValue(".")
        assertEquals(",", arms.getValue(FlickDirection.LEFT).label)
        assertEquals("?", arms.getValue(FlickDirection.UP).label)
        assertEquals("!", arms.getValue(FlickDirection.RIGHT).label)
        assertEquals(KeyAction.Text, arms.getValue(FlickDirection.UP).action)
    }

    @Test
    fun `clearing a key's last arm drops the key`() {
        val map = emptyMap<String, KeySwipeArms>().withKeySwipe("q", FlickDirection.UP, one)
        assertEquals(mapOf("q" to mapOf(FlickDirection.UP to one)), map)
        assertTrue(map.withKeySwipe("q", FlickDirection.UP, KeyAlternate(KeyAction.None)).isEmpty())
        assertTrue(map.withKeySwipe("q", FlickDirection.UP, null).isEmpty())
    }

    @Test
    fun `arms survive the round trip under their layout names`() {
        val map = mapOf(
            "q" to mapOf(FlickDirection.UP_RIGHT to one, FlickDirection.DOWN_LEFT to copy),
            "z" to mapOf(FlickDirection.LEFT to KeyAlternate(KeyAction.Tool(ToolbarTool.UNDO))),
        )
        val json = KeySwipeCodec.encode(map)
        assertTrue(json, "\"up_right\"" in json && "\"down_left\"" in json)
        assertEquals(map, KeySwipeCodec.decode(json))
    }

    @Test
    fun `a bad entry costs that entry alone`() {
        val json = """{"q":{"up_right":{"action":{"type":"teleport"}},"sideways":{"action":{"type":"space"}},""" +
            """"up":{"action":{"type":"space"}}},"Q":{"up":{"action":{"type":"space"}}},"w":"junk","e":{}}"""
        assertEquals(mapOf("q" to mapOf(FlickDirection.UP to KeyAlternate(KeyAction.Space))), KeySwipeCodec.decode(json))
    }

    @Test
    fun `garbage decodes to nothing`() {
        assertTrue(KeySwipeCodec.decode(null).isEmpty())
        assertTrue(KeySwipeCodec.decode("").isEmpty())
        assertTrue(KeySwipeCodec.decode("[1,2]").isEmpty())
        assertTrue(KeySwipeCodec.decode("{not json").isEmpty())
    }
}

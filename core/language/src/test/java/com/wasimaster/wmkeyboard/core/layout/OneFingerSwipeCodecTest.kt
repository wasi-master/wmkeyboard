package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored form of the one-finger swipe bindings (gap list item 44). */
class OneFingerSwipeCodecTest {

    @Test
    fun `bindings survive the round trip`() {
        val map = mapOf(
            SwipeDirection.UP to KeyAlternate(KeyAction.Tool(ToolbarTool.ONE_HANDED)),
            SwipeDirection.LEFT to KeyAlternate(KeyAction.None),
            SwipeDirection.RIGHT to KeyAlternate(KeyAction.Text, label = "→"),
        )
        assertEquals(map, OneFingerSwipeCodec.decode(OneFingerSwipeCodec.encode(map)))
    }

    @Test
    fun `the defaults shift, hide, delete a word and change language, and None clears one`() {
        val none = emptyMap<SwipeDirection, KeyAlternate>()
        assertEquals(KeyAction.Shift, none.oneFingerActionOf(SwipeDirection.UP)?.action)
        assertEquals(KeyAction.Tool(ToolbarTool.HIDE_KEYBOARD), none.oneFingerActionOf(SwipeDirection.DOWN)?.action)
        assertEquals(KeyAction.Edit(TextEditAction.DELETE_WORD), none.oneFingerActionOf(SwipeDirection.LEFT)?.action)
        assertEquals(KeyAction.LanguageSwitch, none.oneFingerActionOf(SwipeDirection.RIGHT)?.action)
        assertNull(mapOf(SwipeDirection.UP to KeyAlternate(KeyAction.None)).oneFingerActionOf(SwipeDirection.UP))
    }

    @Test
    fun `a bad entry costs that entry alone`() {
        val json = """{"U":{"action":{"type":"teleport"}},"X":{"action":{"type":"space"}},""" +
            """"UD":{"action":{"type":"space"}},"D":{"action":{"type":"space"}}}"""
        assertEquals(mapOf(SwipeDirection.DOWN to KeyAlternate(KeyAction.Space)), OneFingerSwipeCodec.decode(json))
    }

    @Test
    fun `garbage decodes to nothing`() {
        assertTrue(OneFingerSwipeCodec.decode("not json").isEmpty())
        assertTrue(OneFingerSwipeCodec.decode(null).isEmpty())
    }
}

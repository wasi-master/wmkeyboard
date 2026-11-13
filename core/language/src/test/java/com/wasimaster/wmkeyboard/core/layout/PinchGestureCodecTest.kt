package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored form of the pinch and stretch bindings (gap list item 18). */
class PinchGestureCodecTest {

    @Test
    fun `bindings survive the round trip`() {
        val map = mapOf(
            PinchGesture.STRETCH to KeyAlternate(KeyAction.Tool(ToolbarTool.ONE_HANDED)),
            PinchGesture.PINCH to KeyAlternate(KeyAction.None),
        )
        assertEquals(map, PinchGestureCodec.decode(PinchGestureCodec.encode(map)))
    }

    @Test
    fun `both default to the split toggle, and None clears one`() {
        val none = emptyMap<PinchGesture, KeyAlternate>()
        assertEquals(KeyAction.Tool(ToolbarTool.SPLIT), none.actionOf(PinchGesture.STRETCH)?.action)
        assertEquals(KeyAction.Tool(ToolbarTool.SPLIT), none.actionOf(PinchGesture.PINCH)?.action)
        assertNull(mapOf(PinchGesture.PINCH to KeyAlternate(KeyAction.None)).actionOf(PinchGesture.PINCH))
    }

    @Test
    fun `a bad entry costs that entry alone`() {
        val json = """{"pinch":{"action":{"type":"teleport"}},"twist":{"action":{"type":"space"}},""" +
            """"stretch":{"action":{"type":"space"}}}"""
        assertEquals(mapOf(PinchGesture.STRETCH to KeyAlternate(KeyAction.Space)), PinchGestureCodec.decode(json))
    }

    @Test
    fun `garbage decodes to nothing`() {
        assertTrue(PinchGestureCodec.decode("not json").isEmpty())
        assertTrue(PinchGestureCodec.decode(null).isEmpty())
    }
}

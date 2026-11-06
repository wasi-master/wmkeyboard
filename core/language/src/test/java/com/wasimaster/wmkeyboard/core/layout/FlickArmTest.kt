package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a flick off a key does: the text arms of a kana pad, their shift forms
 * (issue #550), and the arms that run an action instead (issue #549).
 */
class FlickArmTest {

    private val left = KeyAlternate(KeyAction.Edit(TextEditAction.LEFT))

    @Test
    fun `a text arm types its own shift form, not the tap's`() {
        val key = Key(
            "あ",
            shiftLabel = "ア",
            flick = mapOf(FlickDirection.LEFT to "い", FlickDirection.UP to "う"),
            flickShift = mapOf(FlickDirection.LEFT to "イ"),
        )
        val flicked = key.flickKey(FlickDirection.LEFT)!!
        assertEquals("い", flicked.output)
        assertEquals("イ", flicked.shiftLabel)
        // An arm with no shift form of its own has none, so Shift does to it
        // what it does to any key without a shift label.
        assertNull(key.flickKey(FlickDirection.UP)!!.shiftLabel)
    }

    @Test
    fun `an action arm beats the text arm the same way`() {
        val key = Key(
            "a",
            flick = mapOf(FlickDirection.LEFT to "b"),
            flickActions = mapOf(FlickDirection.LEFT to left),
        )
        assertTrue(key.flickArm(FlickDirection.LEFT) is FlickArm.Action)
        assertEquals(KeyAction.Edit(TextEditAction.LEFT), key.flickKey(FlickDirection.LEFT)!!.action)
        assertNull(key.flickArm(FlickDirection.RIGHT))
    }

    @Test
    fun `an action key flicks both its action arms and its text arms`() {
        // #789: a special key takes text arms too, typed through a plain text key.
        val key = Key(
            "",
            action = KeyAction.Edit(TextEditAction.SELECT),
            flick = mapOf(FlickDirection.UP to "x"),
            flickActions = mapOf(FlickDirection.LEFT to left),
        )
        assertTrue(key.hasFlicks())
        assertNotNull(key.flickArm(FlickDirection.UP))
        assertTrue(key.flickKey(FlickDirection.LEFT)!!.flickArmRepeats())
    }

    @Test
    fun `keys whose drag or hold is already the gesture take no action arms`() {
        val arms = mapOf(FlickDirection.LEFT to left)
        assertFalse(Key("", action = KeyAction.BrailleDot(1), flickActions = arms).hasFlicks())
        assertTrue(Key("", action = KeyAction.Shift, flickActions = arms).hasFlicks())
    }

    @Test
    fun `the delete keys take arms although their hold repeats`() {
        // Issue #769: forward delete on a flick of backspace.
        val arms = mapOf(FlickDirection.UP to KeyAlternate(KeyAction.ForwardDelete))
        assertTrue(Key("", action = KeyAction.Delete, flickActions = arms).hasFlicks())
        assertTrue(Key("", action = KeyAction.ForwardDelete, flickActions = arms).hasFlicks())
        assertTrue(Key("", action = KeyAction.Delete, flick = mapOf(FlickDirection.UP to "x")).hasFlicks())
    }

    @Test
    fun `the space bar takes arms beside its swipes`() {
        // Issue #863: No space on a flick of the space bar.
        val arms = mapOf(FlickDirection.UP to KeyAlternate(KeyAction.Edit(TextEditAction.NO_SPACE)))
        val space = Key(" ", action = KeyAction.Space, flickActions = arms)
        assertTrue(space.hasFlicks())
        assertTrue(space.flickArm(FlickDirection.UP) is FlickArm.Action)
        assertNull(space.flickArm(FlickDirection.LEFT))
    }

    @Test
    fun `the arrow keys take arms although their hold repeats`() {
        // Issue #865: word left on a flick of ←.
        val arms = mapOf(FlickDirection.LEFT to KeyAlternate(KeyAction.Edit(TextEditAction.WORD_LEFT)))
        val arrow = Key("", action = KeyAction.Edit(TextEditAction.LEFT), flickActions = arms)
        assertTrue(arrow.hasFlicks())
        assertTrue(arrow.flickArm(FlickDirection.LEFT) is FlickArm.Action)
    }

    @Test
    fun `a key told to repeat keeps its repeat and takes arms`() {
        // Issue #865: the arrow row's ← is a raw arrow key press told to repeat.
        val arms = mapOf(FlickDirection.LEFT to KeyAlternate(KeyAction.Edit(TextEditAction.WORD_LEFT)))
        val arrow = Key("←", action = KeyAction.SendKey(21), repeatOnHold = true, flickActions = arms)
        assertTrue(arrow.hasFlicks())
        assertTrue(arrow.flickArm(FlickDirection.LEFT) is FlickArm.Action)
        assertTrue(arrow.canRepeatOnHold())
        // A kana pad key still owns its whole gesture.
        assertFalse(Key("か", flick = mapOf(FlickDirection.LEFT to "き"), repeatOnHold = true).canRepeatOnHold())
    }

    @Test
    fun `a corner arm is an arm like any other`() {
        // Issue #410: MessagEase's o carries all eight.
        val key = Key(
            "o",
            flick = mapOf(
                FlickDirection.UP_LEFT to "q", FlickDirection.UP to "u", FlickDirection.UP_RIGHT to "p",
                FlickDirection.LEFT to "c", FlickDirection.RIGHT to "b",
                FlickDirection.DOWN_LEFT to "g", FlickDirection.DOWN to "d", FlickDirection.DOWN_RIGHT to "j",
            ),
        )
        assertTrue(key.hasFlicks())
        assertEquals("q", (key.flickArm(FlickDirection.UP_LEFT) as FlickArm.Text).text)
        assertEquals("j", key.flickKey(FlickDirection.DOWN_RIGHT)!!.output)
        assertTrue(FlickDirection.UP_LEFT.isDiagonal)
        assertFalse(FlickDirection.UP.isDiagonal)
        // The pad order puts the centre in the middle and every arm once.
        assertEquals(9, FlickDirection.gridOrder.size)
        assertEquals(null, FlickDirection.gridOrder[4])
        assertEquals(FlickDirection.entries.toSet(), FlickDirection.gridOrder.filterNotNull().toSet())
    }

    @Test
    fun `repair drops an action arm that only types or cannot run`() {
        val key = Key(
            "a",
            flickActions = mapOf(
                FlickDirection.LEFT to left,
                FlickDirection.UP to KeyAlternate(KeyAction.Text, label = "z"),
                FlickDirection.DOWN to KeyAlternate(KeyAction.Unknown("future")),
            ),
        )
        val repaired = key.repairKey("a", mutableListOf())!!
        assertEquals(mapOf(FlickDirection.LEFT to left), repaired.flickActions)
    }

    @Test
    fun `a caret move repeats on its arm however it was picked`() {
        // #865: word left as a cursor tool or as Ctrl+arrow kept going on the
        // toolbar and ran once, on the lift, from an arm.
        val arms = mapOf(
            FlickDirection.LEFT to KeyAlternate(KeyAction.Tool(ToolbarTool.CURSOR_WORD_LEFT)),
            FlickDirection.RIGHT to KeyAlternate(KeyAction.SendKey(22, meta = 0x1000)),
            FlickDirection.UP to KeyAlternate(KeyAction.Edit(TextEditAction.PAGE_UP)),
            FlickDirection.DOWN to KeyAlternate(KeyAction.Tool(ToolbarTool.EMOJI)),
        )
        val key = Key("←", action = KeyAction.SendKey(21), repeatOnHold = true, flickActions = arms)
        assertTrue(key.flickKey(FlickDirection.LEFT)!!.flickArmRepeats())
        assertTrue(key.flickKey(FlickDirection.RIGHT)!!.flickArmRepeats())
        assertTrue(key.flickKey(FlickDirection.UP)!!.flickArmRepeats())
        assertFalse(key.flickKey(FlickDirection.DOWN)!!.flickArmRepeats())
    }
}

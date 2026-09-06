package com.wasimaster.wmkeyboard.ime.ui

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The modifier drag of issue #67: dragging from a Ctrl/Alt/Meta key onto
 * another key fires that chord. Two pieces are testable without a device — the
 * rect table that says which key the finger lifted on, and the key press the
 * chord turns into.
 */
class ModifierChordTest {

    private val ctrl = Key("Ctrl", action = KeyAction.Mod(ModifierKey.CTRL))

    private fun ctrlMeta(key: Key): Int = (key.action as KeyAction.SendKey).meta

    private fun code(key: Key): Int = (key.action as KeyAction.SendKey).keyCode

    // ---- chordKey -----------------------------------------------------------

    @Test
    fun `a letter key names itself by its label`() {
        val chord = chordKey(Key("c"), ModifierKey.CTRL)!!
        // The character map lives in the service, so the code is left for it to
        // work out and the label is what carries the answer.
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, code(chord))
        assertEquals("c", chord.label)
        assertTrue(ctrlMeta(chord) and KeyEvent.META_CTRL_ON != 0)
        assertTrue(ctrlMeta(chord) and KeyEvent.META_CTRL_LEFT_ON != 0)
    }

    @Test
    fun `each modifier sends its own mask`() {
        assertTrue(
            ctrlMeta(chordKey(Key("c"), ModifierKey.ALT)!!) and KeyEvent.META_ALT_ON != 0,
        )
        assertTrue(
            ctrlMeta(chordKey(Key("c"), ModifierKey.META)!!) and KeyEvent.META_META_ON != 0,
        )
        // And only its own: Alt must not arrive carrying Ctrl.
        assertEquals(
            0,
            ctrlMeta(chordKey(Key("c"), ModifierKey.ALT)!!) and KeyEvent.META_CTRL_ON,
        )
    }

    @Test
    fun `keys that type nothing still have a keycode`() {
        assertEquals(KeyEvent.KEYCODE_SPACE, code(chordKey(Key(" ", action = KeyAction.Space), ModifierKey.CTRL)!!))
        assertEquals(KeyEvent.KEYCODE_ENTER, code(chordKey(Key("", action = KeyAction.Enter), ModifierKey.CTRL)!!))
        assertEquals(KeyEvent.KEYCODE_DEL, code(chordKey(Key("", action = KeyAction.Delete), ModifierKey.CTRL)!!))
        assertEquals(
            KeyEvent.KEYCODE_FORWARD_DEL,
            code(chordKey(Key("", action = KeyAction.ForwardDelete), ModifierKey.CTRL)!!),
        )
    }

    @Test
    fun `a key that already carries modifiers keeps them`() {
        val target = Key("F4", action = KeyAction.SendKey(KeyEvent.KEYCODE_F4, KeyEvent.META_ALT_ON))
        val chord = chordKey(target, ModifierKey.CTRL)!!
        assertEquals(KeyEvent.KEYCODE_F4, code(chord))
        assertTrue(ctrlMeta(chord) and KeyEvent.META_ALT_ON != 0)
        assertTrue(ctrlMeta(chord) and KeyEvent.META_CTRL_ON != 0)
    }

    @Test
    fun `keys that only change what the keyboard shows have no chord`() {
        assertNull(chordKey(Key("?123", action = KeyAction.Symbols), ModifierKey.CTRL))
        assertNull(chordKey(Key("ABC", action = KeyAction.Letters), ModifierKey.CTRL))
        assertNull(chordKey(Key("", action = KeyAction.Emoji), ModifierKey.CTRL))
        assertNull(chordKey(Key("", action = KeyAction.Shift), ModifierKey.CTRL))
        assertNull(chordKey(Key("", action = KeyAction.Tool(ToolbarTool.EMOJI)), ModifierKey.CTRL))
        // Including another modifier: two latches are a tap each, not a drag.
        assertNull(chordKey(ctrl, ModifierKey.ALT))
    }

    // ---- shiftChordKey ------------------------------------------------------

    @Test
    fun `shift onto a letter commits the capital, not a key event`() {
        val chord = shiftChordKey(Key("a"))!!
        // The text route, so the composing buffer, the suggestions and the
        // learning all see the letter. A raw KEYCODE_A would skip every one.
        assertEquals(KeyAction.Text, chord.action)
        assertEquals("A", chord.output)
        assertEquals("a", chord.label)
    }

    @Test
    fun `shift onto a key with a shift label takes that label`() {
        assertEquals("\"", shiftChordKey(Key("'", shiftLabel = "\""))!!.output)
        // Bengali: the shifted twin, not an uppercase that would do nothing.
        assertEquals("খ", shiftChordKey(Key("ক", shiftLabel = "খ"))!!.output)
    }

    @Test
    fun `shift onto a letter is idempotent under a shift already armed`() {
        // The service takes the shifted branch when shift is up, so the output
        // it hands back has to survive being shifted a second time.
        val once = shiftChordKey(Key("a"))!!
        assertEquals(once.output, once.output!!.uppercase())
    }

    @Test
    fun `shift onto anything but a letter is an ordinary chord`() {
        val enter = shiftChordKey(Key("", action = KeyAction.Enter))!!
        assertEquals(KeyEvent.KEYCODE_ENTER, code(enter))
        assertTrue(ctrlMeta(enter) and KeyEvent.META_SHIFT_ON != 0)
        val tab = shiftChordKey(Key("⇥", action = KeyAction.SendKey(KeyEvent.KEYCODE_TAB)))!!
        assertEquals(KeyEvent.KEYCODE_TAB, code(tab))
        assertTrue(ctrlMeta(tab) and KeyEvent.META_SHIFT_ON != 0)
        // And a key with nothing to send still has nothing to send.
        assertNull(shiftChordKey(Key("", action = KeyAction.Shift)))
        assertNull(shiftChordKey(Key("?123", action = KeyAction.Symbols)))
    }

    // ---- modifierKey and startsChordDrag ------------------------------------

    @Test
    fun `only a modifier key names a modifier`() {
        assertEquals(ModifierKey.CTRL, ctrl.modifierKey())
        assertNull(Key("c").modifierKey())
        assertNull(Key("", action = KeyAction.Shift).modifierKey())
        assertNull(null.modifierKey())
    }

    @Test
    fun `the latches and shift start a chord drag, nothing else does`() {
        assertTrue(ctrl.startsChordDrag())
        assertTrue(Key("Alt", action = KeyAction.Mod(ModifierKey.ALT)).startsChordDrag())
        assertTrue(Key("", action = KeyAction.Shift).startsChordDrag())
        // Caps lock is a state you leave on, so holding it for one key means
        // nothing; a letter and an empty cell hold nothing either.
        assertFalse(Key("", action = KeyAction.CapsLock).startsChordDrag())
        assertFalse(Key("c").startsChordDrag())
        assertFalse(null.startsChordDrag())
    }

    // ---- KeyRects -----------------------------------------------------------

    @Test
    fun `a point finds the key whose cell holds it`() {
        val rects = KeyRects()
        val gen = Any()
        val c = Key("c")
        rects.record(gen, ctrl, Rect(0f, 0f, 50f, 50f))
        rects.record(gen, c, Rect(50f, 0f, 100f, 50f))
        assertSame(ctrl, rects.keyAt(Offset(10f, 10f)))
        assertSame(c, rects.keyAt(Offset(60f, 10f)))
        assertNull(rects.keyAt(Offset(10f, 200f)))
    }

    @Test
    fun `a new grid wipes the rows the old one left behind`() {
        val rects = KeyRects()
        val old = Any()
        rects.record(old, ctrl, Rect(0f, 0f, 50f, 50f))
        rects.record(old, Key("c"), Rect(50f, 0f, 100f, 50f))
        // The grid was rebuilt narrower: the first key to report under the new
        // token clears the table, so the cell the old "c" held is empty rather
        // than answering for a key that is no longer on screen.
        val fresh = Any()
        rects.record(fresh, ctrl, Rect(0f, 0f, 40f, 50f))
        assertSame(ctrl, rects.keyAt(Offset(10f, 10f)))
        assertNull(rects.keyAt(Offset(60f, 10f)))
    }

    @Test
    fun `a key that reports twice in one pass is filed once`() {
        val rects = KeyRects()
        val gen = Any()
        val cell = Rect(0f, 0f, 50f, 50f)
        rects.record(gen, ctrl, cell)
        rects.record(gen, ctrl, cell)
        assertSame(ctrl, rects.keyAt(Offset(10f, 10f)))
    }
}

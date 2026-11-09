package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LayoutBehaviorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shift+Enter's newline override. The decision itself is on the ui state, so it
 * can be checked without an InputConnection — `onEnter` does nothing more than
 * skip `performEditorAction` when this says so.
 */
class ShiftEnterNewlineTest {

    private fun state(
        shift: ShiftState,
        byUser: Boolean,
        enabled: Boolean = true,
        action: EnterAction = EnterAction.SEND,
    ) = KeyboardUiState(
        settings = KeyboardSettings(
            layoutBehavior = LayoutBehaviorSettings(shiftEnterNewline = enabled),
        ),
        shiftState = shift,
        shiftPressedByUser = byUser,
        keyFaces = KeyFaces(enterAction = action),
    )

    @Test
    fun `a shift the user armed forces a newline`() {
        assertTrue(state(ShiftState.ON, byUser = true).softShiftForcesNewline)
    }

    @Test
    fun `auto-capitalize's shift does not`() {
        // The case that would otherwise stop the first line of every chat
        // message from sending: an empty box arms auto-cap by itself.
        assertFalse(state(ShiftState.ON, byUser = false).softShiftForcesNewline)
    }

    @Test
    fun `caps lock does not`() {
        assertFalse(state(ShiftState.CAPS_LOCK, byUser = true).softShiftForcesNewline)
    }

    @Test
    fun `no shift does not`() {
        assertFalse(state(ShiftState.OFF, byUser = false).softShiftForcesNewline)
    }

    @Test
    fun `the key draws the newline it is about to type`() {
        assertEquals(
            EnterAction.DEFAULT,
            state(ShiftState.ON, byUser = true).effectiveEnterAction,
        )
    }

    @Test
    fun `the key still draws Send when the setting is off`() {
        val off = state(ShiftState.ON, byUser = true, enabled = false)
        assertEquals(EnterAction.SEND, off.effectiveEnterAction)
        // The state still knows a user shift is armed — it just goes unused.
        assertTrue(off.softShiftForcesNewline)
    }

    @Test
    fun `a field with no action is unaffected either way`() {
        val plain = state(ShiftState.ON, byUser = true, action = EnterAction.DEFAULT)
        assertEquals(EnterAction.DEFAULT, plain.effectiveEnterAction)
    }
}

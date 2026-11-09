package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.TwoThumbBackspace
import com.wasimaster.wmkeyboard.ime.TwoThumbBackspaceStep.CANCEL_OWED_SPACE
import com.wasimaster.wmkeyboard.ime.TwoThumbBackspaceStep.DELETE_AUTO_SPACE
import com.wasimaster.wmkeyboard.ime.TwoThumbBackspaceStep.DELETE_CHARACTER
import com.wasimaster.wmkeyboard.ime.TwoThumbBackspaceStep.LEGACY
import com.wasimaster.wmkeyboard.ime.TwoThumbBackspaceStep.ORDINARY
import com.wasimaster.wmkeyboard.ime.TwoThumbBackspaceStep.RETYPE_WORD
import com.wasimaster.wmkeyboard.ime.TwoThumbBackspaceStep.UNDO_CORRECTION
import com.wasimaster.wmkeyboard.ime.TwoThumbBackspaceStep.UNDO_JOIN
import com.wasimaster.wmkeyboard.ime.TwoThumbBackspaceStep.UNDO_STEP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1188: which one step a Backspace takes on a two-thumb word under an
 * explicit choice, in the order the settings page shows. The service half,
 * with real key dispatch, is [TwoThumbBackspacePolicyTest].
 */
class TwoThumbBackspacePlanTest {

    private fun gesture(
        action: TwoThumbBackspace,
        spaceFirst: Boolean = false,
        joinFirst: Boolean = false,
        twoThumb: Boolean = true,
        undoesGlide: Boolean = true,
    ) = GestureSettings(
        twoThumbGlide = twoThumb,
        backspaceUndoesGlide = undoesGlide,
        twoThumbBackspace = action,
        twoThumbBackspaceSpaceFirst = spaceFirst,
        twoThumbBackspaceJoinFirst = joinFirst,
    )

    /** A two-thumb word just typed with the keyboard's space behind it, and nothing else. */
    private val wordAndAutoSpace = TwoThumbBackspaceFacts(autoSpaceBehind = true, wordBehind = true)

    @Test
    fun `current behavior is the old rules whatever else is on`() {
        val everything = TwoThumbBackspaceFacts(
            joinUndoable = true, autoSpaceBehind = true, spaceOwed = true,
            correctionUndoable = true, wordOpen = true, wordBehind = true,
        )
        val step = twoThumbBackspaceStep(gesture(TwoThumbBackspace.CURRENT, spaceFirst = true, joinFirst = true), everything)
        assertEquals(LEGACY, step)
    }

    @Test
    fun `ordinary glide is untouched by the two-thumb choice`() {
        for (action in TwoThumbBackspace.entries) {
            val step = twoThumbBackspaceStep(gesture(action, spaceFirst = true, twoThumb = false), wordAndAutoSpace)
            assertEquals("$action", LEGACY, step)
        }
    }

    @Test
    fun `legacy keeps reading the shared switch, an explicit choice does not`() {
        assertTrue(LEGACY.undoesGestures(gesture(TwoThumbBackspace.CURRENT)))
        assertFalse(LEGACY.undoesGestures(gesture(TwoThumbBackspace.CURRENT, undoesGlide = false)))
        // Undo input step undoes gestures even with the ordinary-glide switch off.
        assertTrue(UNDO_STEP.undoesGestures(gesture(TwoThumbBackspace.UNDO_STEP, undoesGlide = false)))
        for (step in listOf(RETYPE_WORD, DELETE_CHARACTER, ORDINARY, UNDO_CORRECTION, DELETE_AUTO_SPACE)) {
            assertFalse("$step", step.undoesGestures(gesture(TwoThumbBackspace.UNDO_STEP)))
        }
    }

    @Test
    fun `the action applies straight away when no first-tap stage is on`() {
        val expected = mapOf(
            TwoThumbBackspace.UNDO_STEP to UNDO_STEP,
            TwoThumbBackspace.RETYPE_WORD to RETYPE_WORD,
            TwoThumbBackspace.DELETE_CHARACTER to DELETE_CHARACTER,
        )
        for ((action, step) in expected) {
            assertEquals("$action, word behind", step, twoThumbBackspaceStep(gesture(action), wordAndAutoSpace))
            assertEquals(
                "$action, word open",
                step,
                twoThumbBackspaceStep(gesture(action), TwoThumbBackspaceFacts(wordOpen = true)),
            )
        }
    }

    @Test
    fun `space first takes the auto-space, then the next press reaches the word`() {
        val settings = gesture(TwoThumbBackspace.RETYPE_WORD, spaceFirst = true)
        assertEquals(DELETE_AUTO_SPACE, twoThumbBackspaceStep(settings, wordAndAutoSpace))
        // The space is gone; the word is still the one just typed.
        assertEquals(RETYPE_WORD, twoThumbBackspaceStep(settings, TwoThumbBackspaceFacts(wordBehind = true)))
    }

    @Test
    fun `a space the user typed is never an auto-space`() {
        // The space key's space reports no autoSpaceBehind; the action runs.
        val settings = gesture(TwoThumbBackspace.RETYPE_WORD, spaceFirst = true)
        assertEquals(RETYPE_WORD, twoThumbBackspaceStep(settings, TwoThumbBackspaceFacts(wordBehind = true)))
    }

    @Test
    fun `an owed space is called off, not deleted`() {
        val settings = gesture(TwoThumbBackspace.UNDO_STEP, spaceFirst = true)
        val owed = TwoThumbBackspaceFacts(spaceOwed = true, wordBehind = true)
        assertEquals(CANCEL_OWED_SPACE, twoThumbBackspaceStep(settings, owed))
        assertEquals(
            "with the stage off the action takes it",
            UNDO_STEP,
            twoThumbBackspaceStep(gesture(TwoThumbBackspace.UNDO_STEP), owed),
        )
    }

    @Test
    fun `space, then correction, then the action - one step each`() {
        val settings = gesture(TwoThumbBackspace.RETYPE_WORD, spaceFirst = true)
        // the⌴ corrected from teh: the space, then teh, then Retype.
        val first = TwoThumbBackspaceFacts(autoSpaceBehind = true, correctionUndoable = true, wordBehind = true)
        assertEquals(DELETE_AUTO_SPACE, twoThumbBackspaceStep(settings, first))
        val second = first.copy(autoSpaceBehind = false)
        assertEquals(UNDO_CORRECTION, twoThumbBackspaceStep(settings, second))
        val third = second.copy(correctionUndoable = false)
        assertEquals(RETYPE_WORD, twoThumbBackspaceStep(settings, third))
    }

    @Test
    fun `with space first off, a correction comes before the action and the space goes with it`() {
        val settings = gesture(TwoThumbBackspace.RETYPE_WORD)
        val facts = TwoThumbBackspaceFacts(autoSpaceBehind = true, correctionUndoable = true, wordBehind = true)
        assertEquals(UNDO_CORRECTION, twoThumbBackspaceStep(settings, facts))
    }

    @Test
    fun `join first restores the boundary before anything else`() {
        val settings = gesture(TwoThumbBackspace.RETYPE_WORD, spaceFirst = true, joinFirst = true)
        // carp⌶ after car⌴ + p: the word is open again, no space behind it.
        val joined = TwoThumbBackspaceFacts(joinUndoable = true, wordOpen = true)
        assertEquals(UNDO_JOIN, twoThumbBackspaceStep(settings, joined))
        // car p⌶: the continuation is its own open word, and the action takes it.
        assertEquals(RETYPE_WORD, twoThumbBackspaceStep(settings, TwoThumbBackspaceFacts(wordOpen = true)))
    }

    @Test
    fun `with join first off the joined word gets the action`() {
        val settings = gesture(TwoThumbBackspace.UNDO_STEP)
        val joined = TwoThumbBackspaceFacts(joinUndoable = true, wordOpen = true)
        assertEquals(UNDO_STEP, twoThumbBackspaceStep(settings, joined))
    }

    @Test
    fun `a join that can no longer be proved is not undone`() {
        val settings = gesture(TwoThumbBackspace.DELETE_CHARACTER, joinFirst = true)
        assertEquals(DELETE_CHARACTER, twoThumbBackspaceStep(settings, TwoThumbBackspaceFacts(wordOpen = true)))
    }

    @Test
    fun `an open word ignores the space and correction stages`() {
        // Neither can be behind a word still being typed; the action takes the word.
        val settings = gesture(TwoThumbBackspace.UNDO_STEP, spaceFirst = true)
        val facts = TwoThumbBackspaceFacts(autoSpaceBehind = true, correctionUndoable = true, wordOpen = true)
        assertEquals(UNDO_STEP, twoThumbBackspaceStep(settings, facts))
    }

    @Test
    fun `away from a two-thumb word Backspace is ordinary, so Retype never walks back`() {
        for (action in listOf(TwoThumbBackspace.UNDO_STEP, TwoThumbBackspace.RETYPE_WORD, TwoThumbBackspace.DELETE_CHARACTER)) {
            assertEquals("$action", ORDINARY, twoThumbBackspaceStep(gesture(action, spaceFirst = true), TwoThumbBackspaceFacts()))
        }
    }
}

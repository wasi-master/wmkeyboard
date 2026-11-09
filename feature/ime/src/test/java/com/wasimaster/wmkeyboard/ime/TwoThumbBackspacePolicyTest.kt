package com.wasimaster.wmkeyboard.ime

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.wasimaster.wmkeyboard.core.settings.TwoThumbBackspace
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.allUpPause
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.delete
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantDictionary
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.strip
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1188, the service half: Backspace on a two-thumb word under an explicit
 * Two-thumb Backspace choice, through the real key dispatch (which spends
 * the keyboard's space flag before the delete runs). The order itself is
 * pure, in [TwoThumbBackspacePlanTest]. "Current behavior" is covered by
 * [TwoThumbBackspaceTest] and [TwoThumbCommitUndoTest], unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbBackspacePolicyTest {

    /** An editor that says where its caret is: Retype of a typed word wants proof before it deletes (#844). */
    private class ExtractingEditor(initial: String) : RecordingEditor(initial = initial) {
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().also {
                it.text = text.toString()
                it.startOffset = 0
                it.selectionStart = text.length
                it.selectionEnd = text.length
            }
    }

    /** The editor's echo of where its caret now is, as a real one sends after each edit. */
    private fun echoCaret(service: WMKeyboardService, editor: RecordingEditor) {
        val end = editor.text.length
        service.onUpdateSelection(end, end, end, end, -1, -1)
    }

    private fun press(service: WMKeyboardService) {
        service.onKey(delete)
        drain(service)
    }

    /** Two swipes typed as one word by the all-thumbs-up pause: `qyqy `. */
    private fun twoSwipeWord(service: WMKeyboardService) {
        swipe(service)
        drain(service)
        swipe(service)
        drain(service)
        allUpPause(service)
    }

    @Test
    fun `current behavior with both stages switched on changes nothing`() {
        val (service, editor) = keyboard(
            settings(allUp = true, backspace = TwoThumbBackspace.CURRENT, spaceFirst = true, joinFirst = true),
        )
        twoSwipeWord(service)
        assertEquals("fixture: the pause typed both swipes as one word", "qyqy ", editor.text.toString())
        press(service)
        assertEquals("the stages are not used: the last swipe and the space go, as before", "qy", editor.text.toString())
    }

    @Test
    fun `undo input step undoes a swipe even with the ordinary glide switch off`() {
        val (service, editor) = keyboard(settings(allUp = true, undo = false, backspace = TwoThumbBackspace.UNDO_STEP))
        twoSwipeWord(service)
        press(service)
        assertEquals("qy", editor.text.toString())
    }

    @Test
    fun `undo input step with space first takes the space, then the swipe`() {
        val (service, editor) = keyboard(
            settings(allUp = true, backspace = TwoThumbBackspace.UNDO_STEP, spaceFirst = true),
        )
        twoSwipeWord(service)
        press(service)
        assertEquals("only the keyboard's space", "qyqy", editor.text.toString())
        press(service)
        assertEquals("the separator-only press left the word's undo armed", "qy", editor.text.toString())
    }

    @Test
    fun `delete character takes the space, then one letter, whichever way space first is set`() {
        for (spaceFirst in listOf(false, true)) {
            val (service, editor) = keyboard(
                settings(allUp = true, backspace = TwoThumbBackspace.DELETE_CHARACTER, spaceFirst = spaceFirst),
            )
            twoSwipeWord(service)
            press(service)
            assertEquals("space first $spaceFirst", "qyqy", editor.text.toString())
            press(service)
            assertEquals("space first $spaceFirst: a letter, never a swipe", "qyq", editor.text.toString())
        }
    }

    @Test
    fun `delete character on an open word takes one letter, not a swipe`() {
        val (service, editor) = keyboard(settings(backspace = TwoThumbBackspace.DELETE_CHARACTER))
        twoSwipeWord(service)
        assertEquals("fixture: the space word end keeps the word open", "qyqy", editor.text.toString())
        press(service)
        assertEquals("qyq", editor.text.toString())
    }

    @Test
    fun `retype clears the whole open word, and the next press deletes a character`() {
        val (service, editor) = keyboard(settings(backspace = TwoThumbBackspace.RETYPE_WORD), initial = "hi ")
        twoSwipeWord(service)
        assertEquals("fixture: the word is still open", "hi qyqy", editor.text.toString())
        press(service)
        assertEquals("hi ", editor.text.toString())
        assertEquals("the old spelling is offered first", "qyqy", strip(service).firstOrNull())
        press(service)
        assertEquals("no Retype chain into the word before", "hi", editor.text.toString())
    }

    @Test
    fun `retype takes a typed word and the keyboard's space in one press`() {
        val editor = ExtractingEditor("hi ")
        val (service, _) = keyboard(
            settings(allUp = true, backspace = TwoThumbBackspace.RETYPE_WORD),
            editor = editor,
        )
        echoCaret(service, editor)
        twoSwipeWord(service)
        assertEquals("hi qyqy ", editor.text.toString())
        echoCaret(service, editor)
        press(service)
        assertEquals("hi ", editor.text.toString())
        echoCaret(service, editor)
        press(service)
        assertEquals("the next press is ordinary", "hi", editor.text.toString())
    }

    @Test
    fun `retype with space first takes the space, then the word`() {
        val editor = ExtractingEditor("hi ")
        val (service, _) = keyboard(
            settings(allUp = true, backspace = TwoThumbBackspace.RETYPE_WORD, spaceFirst = true),
            editor = editor,
        )
        echoCaret(service, editor)
        twoSwipeWord(service)
        echoCaret(service, editor)
        press(service)
        assertEquals("hi qyqy", editor.text.toString())
        echoCaret(service, editor)
        press(service)
        assertEquals("hi ", editor.text.toString())
    }

    @Test
    fun `a two-thumb word ended by the space key keeps that space on Retype`() {
        val editor = ExtractingEditor("")
        val (service, _) = keyboard(
            settings(backspace = TwoThumbBackspace.RETYPE_WORD, spaceFirst = true),
            editor = editor,
        )
        swipe(service)
        drain(service)
        swipe(service)
        drain(service)
        service.onKey(TwoThumbKit.space)
        drain(service)
        assertEquals("fixture", "qyqy ", editor.text.toString())
        echoCaret(service, editor)
        press(service)
        assertEquals("the user's space is not an auto-space: the word goes, the space stays", " ", editor.text.toString())
    }

    @Test
    fun `join first puts the space back and keeps the letter as its own word`() {
        val (service, editor) = keyboard(
            settings(allUp = true, retractMs = RETRACT_MS, backspace = TwoThumbBackspace.UNDO_STEP, joinFirst = true),
        )
        plantDictionary(service, "car" to 200, "carpet" to 100)
        "car".forEach { tap(service, it.toString()) }
        allUpPause(service)
        assertEquals("fixture", "car ", editor.text.toString())
        tap(service, "p")
        drain(service)
        assertEquals("fixture: the letter took the space back", "carp", editor.text.toString())
        press(service)
        assertEquals("car p", editor.text.toString())
        // Lifting off Backspace does not type the letter as a word.
        allUpPause(service)
        assertEquals("car p", editor.text.toString())
    }

    @Test
    fun `control - with join first off the letter is deleted as before`() {
        val (service, editor) = keyboard(
            settings(allUp = true, retractMs = RETRACT_MS, backspace = TwoThumbBackspace.UNDO_STEP),
        )
        plantDictionary(service, "car" to 200, "carpet" to 100)
        "car".forEach { tap(service, it.toString()) }
        allUpPause(service)
        tap(service, "p")
        drain(service)
        press(service)
        assertEquals("car", editor.text.toString())
    }

    @Test
    fun `control - an ordinary glide ignores the two-thumb choice`() {
        val (service, editor) = keyboard(
            settings(twoThumb = false, backspace = TwoThumbBackspace.DELETE_CHARACTER, spaceFirst = true),
        )
        swipe(service, GlideVerdict.Word("hello"))
        drain(service)
        assertEquals("fixture", "hello ", editor.text.toString())
        press(service)
        assertEquals("", editor.text.toString())
    }

    private companion object {
        const val RETRACT_MS = 2000
    }
}

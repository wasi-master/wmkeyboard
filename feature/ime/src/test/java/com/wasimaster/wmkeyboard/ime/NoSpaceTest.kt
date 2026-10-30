package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.AutoTextSettings
import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * No space (#851): the keyboard's own space after a word comes back out, and
 * the next glided word joins on. So does a space the user typed after a word
 * (#939).
 */
@RunWith(RobolectricTestRunner::class)
class NoSpaceTest {

    private val space = Key(" ", action = KeyAction.Space)

    private fun keyboard(initial: String = ""): Pair<GlideKeyboard, RecordingEditor> {
        val editor = RecordingEditor(initial = initial)
        val (service, _, _) = glideKeyboard(
            state = glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    gesture = GestureSettings(autoSpaceAfterGlide = true),
                ),
            ),
            editor = editor,
        )
        return service to editor
    }

    private fun GlideKeyboard.glide(word: String) {
        onGesture(straightStroke(), glideKeyGrid(), GLIDE_KEY_WIDTH, GlideVerdict.Word(word))
        // The strip is already full from an earlier glide: wait for this one.
        TwoThumbKit.drain(this)
    }

    private fun GlideKeyboard.noSpace() = onTextEdit(TextEditAction.NO_SPACE, haptic = false)

    @Test
    fun `the glide's space comes back out, and the next glide joins on`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        assertEquals("foot ", editor.text.toString())
        service.noSpace()
        assertEquals("foot", editor.text.toString())
        service.glide("ball")
        assertEquals("football ", editor.text.toString())
    }

    @Test
    fun `a space the user typed after a word comes back out too`() {
        val (service, editor) = keyboard()
        "foo".forEach { service.onText(it.toString()) }
        service.onKey(space)
        assertEquals("foo ", editor.text.toString())
        service.noSpace()
        assertEquals("foo", editor.text.toString())
        service.glide("ball")
        assertEquals("fooball ", editor.text.toString())
    }

    @Test
    fun `a space after a mark stays`() {
        val (service, editor) = keyboard(initial = "foo. ")
        service.noSpace()
        assertEquals("foo. ", editor.text.toString())
    }

    // ---- A word still open (#1023) ----

    @Test
    fun `a tap with a word open leaves it open, marked`() {
        val (service, editor) = keyboard()
        "foo".forEach { service.onText(it.toString()) }
        service.noSpace()
        assertTrue(service.uiState.value.noSpaceWordEnd)
        assertEquals("foo", editor.text.toString())
        // Still the word being written: it can go on.
        service.onText("d")
        assertEquals("food", editor.text.toString())
        assertTrue(service.uiState.value.noSpaceWordEnd)
    }

    @Test
    fun `a space pressed on a marked word still types its space`() {
        val (service, editor) = keyboard()
        "foo".forEach { service.onText(it.toString()) }
        service.noSpace()
        service.onKey(space)
        assertEquals("foo ", editor.text.toString())
        assertFalse(service.uiState.value.noSpaceWordEnd)
    }

    @Test
    fun `a marked word picked off the strip takes no space`() {
        val (service, editor) = keyboard()
        "foo".forEach { service.onText(it.toString()) }
        service.noSpace()
        service.onSuggestionTapped("food")
        assertEquals("food", editor.text.toString())
        assertFalse(service.uiState.value.noSpaceWordEnd)
    }

    @Test
    fun `backspacing the marked word away takes the mark with it`() {
        val (service, _) = keyboard()
        service.onText("f")
        service.noSpace()
        service.onKey(Key("", action = KeyAction.Delete))
        assertFalse(service.uiState.value.noSpaceWordEnd)
    }

    // ---- Pressed as a key, through the same path a tap or flick takes (#939) ----

    private fun GlideKeyboard.noSpaceKey() = onKey(Key("", action = KeyAction.Edit(TextEditAction.NO_SPACE)))

    private fun GlideKeyboard.noSpaceNextKey() = onKey(Key("", action = KeyAction.Edit(TextEditAction.NO_SPACE_NEXT)))

    @Test
    fun `the key takes the glide's space back out, and the next glide joins on`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        service.noSpaceKey()
        assertEquals("foot", editor.text.toString())
        service.glide("ball")
        assertEquals("football ", editor.text.toString())
    }

    @Test
    fun `the hold's key joins the next glide on`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        service.noSpaceNextKey()
        service.glide("ball")
        assertEquals("football ", editor.text.toString())
    }

    @Test
    fun `after it a backspace takes one letter`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        service.noSpace()
        service.onKey(Key("", action = KeyAction.Delete))
        assertEquals("foo", editor.text.toString())
    }

    @Test
    fun `a full stop after it hugs the word`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        service.noSpace()
        service.onText(".")
        assertEquals("foot.", editor.text.toString())
    }

    /** The editor reports a caret move, and its echo is handled. */
    private fun GlideKeyboard.caret(from: Int, to: Int) {
        onUpdateSelection(from, from, to, to, -1, -1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the editor reporting No space's own edit keeps the next glide joined`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        service.caret(0, 5)
        service.noSpace()
        assertEquals("foot", editor.text.toString())
        // #867: the echo of the space coming out is not the user moving the caret.
        service.caret(5, 4)
        service.glide("ball")
        assertEquals("football ", editor.text.toString())
    }

    @Test
    fun `a caret the user moves after it still calls the join off`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        service.caret(0, 5)
        service.noSpace()
        service.caret(5, 4)
        // The user taps elsewhere: any caret but the one No space left.
        service.caret(4, 2)
        service.glide("ball")
        assertEquals("foot ball ", editor.text.toString())
    }

    // ---- No space for the next key (#863) ----

    /** A keyboard that puts a space after a full stop, as the issue's does. */
    private fun spacingKeyboard(): Pair<GlideKeyboard, RecordingEditor> {
        val editor = RecordingEditor()
        val (service, _, _) = glideKeyboard(
            state = glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    autoText = AutoTextSettings(spaceAfterPunctuation = true),
                ),
            ),
            editor = editor,
        )
        return service to editor
    }

    /** Types [text] a key at a time, answering each key with the editor's caret. */
    private fun GlideKeyboard.type(editor: RecordingEditor, text: String) {
        for (ch in text) {
            val old = editor.text.length
            onText(ch.toString())
            val now = editor.text.length
            onUpdateSelection(old, old, now, now, -1, -1)
            settle { false }
        }
    }

    private fun GlideKeyboard.noSpaceNext() = onTextEdit(TextEditAction.NO_SPACE_NEXT, haptic = false)

    @Test
    fun `a double tap arms it, and the full stop after it takes no space`() {
        val (service, editor) = spacingKeyboard()
        service.type(editor, "github")
        service.noSpace()
        service.noSpace()
        assertTrue(service.uiState.value.noSpaceArmed)
        service.type(editor, ".com")
        assertEquals("github.com", editor.text.toString())
        assertFalse(service.uiState.value.noSpaceArmed)
    }

    @Test
    fun `it is spent by the one key, and the next full stop is spaced again`() {
        val (service, editor) = spacingKeyboard()
        service.type(editor, "foo")
        service.noSpaceNext()
        service.type(editor, ".bar.")
        assertEquals("foo.bar. ", editor.text.toString())
    }

    @Test
    fun `the hold's action arms it, and pressed again calls it off`() {
        val (service, _) = spacingKeyboard()
        service.noSpaceNext()
        assertTrue(service.uiState.value.noSpaceArmed)
        service.noSpaceNext()
        assertFalse(service.uiState.value.noSpaceArmed)
    }

    @Test
    fun `a tap with it armed calls it off`() {
        val (service, editor) = spacingKeyboard()
        service.type(editor, "foo")
        service.noSpaceNext()
        service.noSpace()
        assertFalse(service.uiState.value.noSpaceArmed)
        service.type(editor, ".")
        assertEquals("foo. ", editor.text.toString())
    }

    @Test
    fun `space calls it off`() {
        val (service, _) = spacingKeyboard()
        service.noSpaceNext()
        service.onKey(space)
        assertFalse(service.uiState.value.noSpaceArmed)
    }

    @Test
    fun `a single tap does not arm it`() {
        val (service, editor) = spacingKeyboard()
        service.type(editor, "foo")
        service.noSpace()
        assertFalse(service.uiState.value.noSpaceArmed)
    }

    @Test
    fun `a glided word is the next key too`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        service.noSpaceNext()
        service.glide("ball")
        assertEquals("football ", editor.text.toString())
        assertFalse(service.uiState.value.noSpaceArmed)
    }
}

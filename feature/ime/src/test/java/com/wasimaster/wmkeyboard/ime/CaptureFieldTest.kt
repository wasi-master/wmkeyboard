package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard's own text fields have a caret, and one ladder decides which of
 * them has the keys (issue #161).
 *
 * Plain JVM tests, no Robolectric: [CaretText] is arithmetic and
 * [KeyboardUiState.captureTarget] is a `when`, and both are worth pinning
 * because everything else in #161 is built on them — a glide inserts at the
 * caret, a suggestion replaces the word around it, and a keystroke that picked
 * the wrong target types into the app behind the keyboard.
 */
class CaptureFieldTest {

    // ---- CaretText ----

    @Test
    fun `typing lands at the caret, not at the end`() {
        val text = CaretText("helo", caret = 3)
        assertEquals(CaretText("hello", 4), text.typed("l"))
    }

    @Test
    fun `backspace takes the character before the caret`() {
        val text = CaretText("hello", caret = 3)
        assertEquals(CaretText("helo", 2), text.deletedBackward())
    }

    @Test
    fun `backspace at the start changes nothing`() {
        val text = CaretText("hello", caret = 0)
        assertEquals(CaretText("hello", 0), text.deletedBackward())
    }

    @Test
    fun `backspace can take a whole emoji`() {
        // The length comes from the caller (the service asks
        // `charDeleteLength`); what this pins is that the span removed ends at
        // the caret rather than at the end of the text.
        val text = CaretText("hi 👍 there", caret = 5)
        assertEquals(CaretText("hi  there", 3), text.deletedBackward(length = 2))
    }

    @Test
    fun `forward delete takes the character after the caret`() {
        val text = CaretText("hello", caret = 2)
        assertEquals(CaretText("helo", 2), text.deletedForward())
    }

    @Test
    fun `forward delete at the end changes nothing`() {
        val text = CaretText("hello", caret = 5)
        assertEquals(CaretText("hello", 5), text.deletedForward())
    }

    @Test
    fun `the caret steps over a surrogate pair whole`() {
        val text = CaretText("a👍b", caret = 1)
        assertEquals(3, text.caretMoved(1).at)
        assertEquals(1, CaretText("a👍b", caret = 3).caretMoved(-1).at)
    }

    @Test
    fun `a caret asked for inside a surrogate pair lands in front of it`() {
        // What a tap can hand back: the x of a touch inside one glyph.
        assertEquals(1, CaretText("a👍b").caretAt(2).at)
    }

    @Test
    fun `a caret past the end clamps to it`() {
        assertEquals(5, CaretText("hello").caretAt(99).at)
        assertEquals(0, CaretText("hello").caretAt(-3).at)
    }

    @Test
    fun `the word at the caret is the one being typed, tail included`() {
        val word = CaretText("say hello there", caret = 6).wordAtCaret()
        assertEquals(4, word.start)
        assertEquals(9, word.end)
        // Only what is in front of the caret counts as typed so far.
        assertEquals("he", word.typed)
    }

    @Test
    fun `a caret on a space has no word`() {
        assertEquals("", CaretText("say hello", caret = 4).wordAtCaret().typed)
    }

    @Test
    fun `apostrophes and hyphens are inside a word`() {
        assertEquals("c'est", CaretText("c'est").wordAtCaret().typed)
        assertEquals("well-known", CaretText("well-known").wordAtCaret().typed)
    }

    @Test
    fun `the word before the caret is the prediction context`() {
        assertEquals("say", CaretText("say hel", caret = 7).wordBeforeCaret())
        assertNull(CaretText("say", caret = 3).wordBeforeCaret())
    }

    @Test
    fun `a pick replaces the whole word around the caret`() {
        val text = CaretText("say hel there", caret = 7)
        assertEquals(
            CaretText("say hello there", 10),
            text.replacedWordAtCaret("hello", spaceAfter = true),
        )
    }

    @Test
    fun `a pick that finishes a word being edited in the middle takes its tail`() {
        assertEquals(
            CaretText("say hello there", 10),
            CaretText("say hello there", caret = 6).replacedWordAtCaret("hello", spaceAfter = true),
        )
    }

    @Test
    fun `a pick typed in front of an existing word keeps that word`() {
        // "ab" typed at the start of "out of here" (#612).
        val text = CaretText("about of here", caret = 2)
        assertEquals(
            CaretText("absolutely out of here", 11),
            text.replacedWordAtCaret("absolutely", spaceAfter = true),
        )
    }

    @Test
    fun `a pick does not double a space that is already there`() {
        val text = CaretText("hel world", caret = 3)
        val next = text.replacedWordAtCaret("hello", spaceAfter = true)
        assertEquals("hello world", next.text)
        assertEquals(6, next.at)
    }

    @Test
    fun `a pick at the end of a query can leave the space off`() {
        val text = CaretText("hel", caret = 3)
        assertEquals(CaretText("hello", 5), text.replacedWordAtCaret("hello", spaceAfter = false))
    }

    @Test
    fun `a second glide at the end of a query adds a word instead of replacing the first`() {
        val first = CaretText("").glided("hello")
        assertEquals(CaretText("hello", 5), first)
        assertEquals(CaretText("hello world", 11), first.glided("world"))
    }

    @Test
    fun `a glide after a space does not add another`() {
        assertEquals(CaretText("hello world", 11), CaretText("hello ").glided("world"))
    }

    @Test
    fun `a glide in front of a word spaces itself off it`() {
        val next = CaretText("hello there", caret = 6).glided("big")
        assertEquals("hello big there", next.text)
        assertEquals(10, next.at)
        val onGap = CaretText("hello there", caret = 5).glided("big")
        assertEquals("hello big there", onGap.text)
        assertEquals(10, onGap.at)
    }

    @Test
    fun `a glide after a mark is spaced off it (#880)`() {
        assertEquals(CaretText("hello, world", 12), CaretText("hello,").glided("world"))
        assertEquals(CaretText("(see) there", 11), CaretText("(see)").glided("there"))
    }

    @Test
    fun `a glide after an opener stays against it`() {
        assertEquals(CaretText("(world", 6), CaretText("(").glided("world"))
    }

    @Test
    fun `a glide before punctuation leaves it attached`() {
        assertEquals(CaretText("hello world?", 11), CaretText("hello?", caret = 5).glided("world"))
    }

    // ---- the one ladder ----

    @Test
    fun `no keyboard field means the keys belong to the app`() {
        val state = KeyboardUiState()
        assertNull(state.captureTarget())
        assertFalse(state.keysTakenByKeyboard)
        assertNull(state.captureCaretText())
    }

    @Test
    fun `a search query is a capture target and carries its own caret`() {
        val state = KeyboardUiState(emojiQuery = "cat", emojiSearchActive = true)
        assertEquals(CaptureTarget.EMOJI_SEARCH, state.captureTarget())
        assertTrue(state.keysTakenByKeyboard)
        // With no caret stored, the caret is the end of the text.
        assertEquals(CaretText("cat", 3), state.captureCaretText())
    }

    @Test
    fun `a caret left behind by another field does not follow this one`() {
        val state = KeyboardUiState(
            emojiQuery = "cat",
            emojiSearchActive = true,
            captureCaret = CaptureCaret(CaptureTarget.DICTIONARY_SEARCH.name, at = 0, text = "cat"),
        )
        // The stored caret is about the dictionary, so this field starts at
        // its own end rather than inheriting a stranger's index.
        assertEquals(3, state.captureCaretIndex())
    }

    @Test
    fun `a caret for this field is the one used`() {
        val state = KeyboardUiState(
            emojiQuery = "cat",
            emojiSearchActive = true,
            captureCaret = CaptureCaret(CaptureTarget.EMOJI_SEARCH.name, at = 1, text = "cat"),
        )
        assertEquals(1, state.captureCaretIndex())
    }

    @Test
    fun `a caret taken against other text does not survive`() {
        // A query prefilled from the user's field, or written by a tool, does
        // not go through the caret's own write path — so a caret that is about
        // text the buffer no longer holds is dropped rather than pointing into
        // the middle of something it never described.
        val state = KeyboardUiState(
            emojiQuery = "cat",
            emojiSearchActive = true,
            captureCaret = CaptureCaret(CaptureTarget.EMOJI_SEARCH.name, at = 1, text = "dog house"),
        )
        assertEquals(3, state.captureCaretIndex())
    }

    @Test
    fun `the clipboard search is in the ladder`() {
        // It was missing from the space and Enter branches, so both leaked
        // into the app behind the panel.
        val state = KeyboardUiState(clipboardQuery = "x", clipboardSearchActive = true)
        assertEquals(CaptureTarget.CLIPBOARD_SEARCH, state.captureTarget())
        assertTrue(state.keysTakenByKeyboard)
    }

    @Test
    fun `the clip editor has the keys only with its panel open`() {
        val editing = KeyboardUiState(
            panel = PanelMode.CLIPBOARD,
            clipEdit = ClipEdit(id = 7, original = "teh", draft = "the"),
        )
        assertEquals(CaptureTarget.CLIP_EDIT, editing.captureTarget())
        assertEquals("the", editing.captureBuffer())
        assertTrue(editing.clipboardTakesKeys)
        // An edit left behind by a panel that closed some other way must not
        // keep typing into a buffer nothing is drawing.
        val closed = editing.copy(panel = PanelMode.NONE)
        assertNull(closed.captureTarget())
        assertFalse(closed.clipboardTakesKeys)
    }

    @Test
    fun `the clip editor outranks the clipboard search`() {
        val state = KeyboardUiState(
            panel = PanelMode.CLIPBOARD,
            clipboardSearchActive = true,
            clipboardQuery = "x",
            clipEdit = ClipEdit(id = 1, original = "a"),
        )
        assertEquals(CaptureTarget.CLIP_EDIT, state.captureTarget())
        assertTrue(CaptureTarget.CLIP_EDIT.takesWords)
        assertTrue(CaptureTarget.CLIP_EDIT.movableCaret)
    }

    @Test
    fun `a clip edit saves only a real change`() {
        assertFalse(ClipEdit(1, "same").canSave)
        assertFalse(ClipEdit(1, "same", draft = " same\n").canSave)
        assertFalse(ClipEdit(1, "text", draft = "   ").canSave)
        assertTrue(ClipEdit(1, "text", draft = "text!").canSave)
    }

    @Test
    fun `a media search only counts on a panel that has one`() {
        val searching = KeyboardUiState(
            panel = PanelMode.TRANSLATE,
            mediaQuery = "hello",
            mediaSearchActive = true,
        )
        assertEquals(CaptureTarget.MEDIA_SEARCH, searching.captureTarget())
        val elsewhere = searching.copy(panel = PanelMode.COMPASS)
        assertNull(elsewhere.captureTarget())
    }

    @Test
    fun `the chat composer has the keys only while it is up and focused`() {
        val composing = KeyboardUiState(
            panel = PanelMode.AI,
            aiChat = AiChatUi(open = true, composing = true, draft = "hello"),
        )
        assertEquals(CaptureTarget.AI_CHAT, composing.captureTarget())
        assertEquals("hello", composing.captureBuffer())
        assertTrue(CaptureTarget.AI_CHAT.takesWords)
        // Every one of its conditions alone gives the keys back: the panel
        // closed, the actions showing, the composer not focused, the list of
        // conversations up over it, and a chat that may not be offered at all.
        assertNull(composing.copy(panel = PanelMode.NONE).captureTarget())
        assertNull(composing.copy(aiChat = composing.aiChat.copy(open = false)).captureTarget())
        assertNull(composing.copy(aiChat = composing.aiChat.copy(composing = false)).captureTarget())
        assertNull(composing.copy(aiChat = composing.aiChat.copy(showSessions = true)).captureTarget())
        assertNull(composing.copy(aiChat = composing.aiChat.copy(available = false)).captureTarget())
    }

    @Test
    fun `the numeric fields take no words`() {
        assertFalse(CaptureTarget.CALC.takesWords)
        assertFalse(CaptureTarget.CONVERTER.takesWords)
        // …and neither does the test, which decodes against its own prompt.
        assertFalse(CaptureTarget.TYPING_TEST.takesWords)
        assertTrue(CaptureTarget.MEDIA_SEARCH.takesWords)
    }

    @Test
    fun `the typing test's caret does not move`() {
        // Its score counts keystrokes in the order they were made, so a
        // character inserted behind the ones already scored would be counted
        // against a word it was never typed into.
        assertFalse(CaptureTarget.TYPING_TEST.movableCaret)
        assertTrue(CaptureTarget.EMOJI_SEARCH.movableCaret)
    }

    @Test
    fun `a plugin's caret is keyed on the box it is in`() {
        val state = KeyboardUiState(
            panel = PanelMode.PLUGINS,
            pluginFocusedInput = "box-a",
            pluginInputs = mapOf("box-a" to "hi", "box-b" to "there"),
        )
        assertEquals(CaptureTarget.PLUGIN, state.captureTarget())
        assertEquals("PLUGIN:box-a", state.captureKey())
        assertEquals("hi", state.captureBuffer())
        // A caret left in the other box does not follow the focus over.
        val moved = state.copy(
            pluginFocusedInput = "box-b",
            captureCaret = CaptureCaret("PLUGIN:box-a", at = 0, text = "hi"),
        )
        assertEquals("there".length, moved.captureCaretIndex())
    }

    @Test
    fun `up and down step a line at a time in a note of several lines`() {
        // #1241: they jumped to the note's ends, which looked like nothing.
        val text = "first line\nab\nthird line"
        // Column 5 on line one: line two is shorter, so its end.
        assertEquals(13, CaretText(text, 5).lineStep(up = false))
        // Column 1 on line two goes to column 1 of line three.
        assertEquals(15, CaretText(text, 12).lineStep(up = false))
        // Up from line three, column 7, cut to line two's end.
        assertEquals(13, CaretText(text, 21).lineStep(up = true))
        // From the first line up is the start; from the last, down is the end.
        assertEquals(0, CaretText(text, 3).lineStep(up = true))
        assertEquals(text.length, CaretText(text, 16).lineStep(up = false))
    }

    @Test
    fun `home and end are the line's ends`() {
        val text = "first line\nab\nthird line"
        assertEquals(11, CaretText(text, 12).lineEdge(end = false))
        assertEquals(13, CaretText(text, 12).lineEdge(end = true))
        assertEquals(text.length, CaretText(text, 16).lineEdge(end = true))
    }
}

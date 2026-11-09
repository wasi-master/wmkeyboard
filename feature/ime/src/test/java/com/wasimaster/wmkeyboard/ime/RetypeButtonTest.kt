package com.wasimaster.wmkeyboard.ime

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.RecentWordPlacement
import com.wasimaster.wmkeyboard.core.settings.RetypeButtonPlace
import com.wasimaster.wmkeyboard.core.settings.RowSettings
import com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.ui.recentRowShown
import com.wasimaster.wmkeyboard.ime.ui.retypeButtonOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1189: the Retype button. Which bar holds it and when that bar is up, and
 * the button running the same Retype as the word menu, on the word at the
 * cursor and nothing else. The Retype rules themselves are [RetypeWordTest].
 */
@RunWith(RobolectricTestRunner::class)
class RetypeButtonTest {

    private fun state(
        place: RetypeButtonPlace,
        recentOn: Boolean = true,
        placement: RecentWordPlacement = RecentWordPlacement.OWN_ROW,
        secure: Boolean = false,
    ) = KeyboardUiState(
        settings = KeyboardSettings(
            suggestionStrip = SuggestionStripSettings(recentWordBar = recentOn, retypeButton = place),
            rows = RowSettings(recentWordPlacement = placement),
        ),
        secureField = secure,
    )

    @Test
    fun `the button sits on the one bar chosen, and never in a password field`() {
        for (place in RetypeButtonPlace.entries) {
            for (where in listOf(RetypeButtonPlace.SUGGESTION_BAR, RetypeButtonPlace.RECENT_WORDS_BAR)) {
                assertEquals("$place on $where", place == where, retypeButtonOn(state(place), where))
            }
            assertFalse("$place in a password field", retypeButtonOn(state(place, secure = true), place))
        }
    }

    @Test
    fun `on the previous-words bar the empty row stays up for the button`() {
        assertTrue(recentRowShown(state(RetypeButtonPlace.RECENT_WORDS_BAR)))
        assertFalse("control: no button, no words, no steady row", recentRowShown(state(RetypeButtonPlace.OFF)))
        assertFalse(
            "the strip's button does not hold the row up",
            recentRowShown(state(RetypeButtonPlace.SUGGESTION_BAR)),
        )
    }

    @Test
    fun `the button never turns Recent words on or moves them into a row`() {
        assertFalse(recentRowShown(state(RetypeButtonPlace.RECENT_WORDS_BAR, recentOn = false)))
        assertFalse(
            recentRowShown(state(RetypeButtonPlace.RECENT_WORDS_BAR, placement = RecentWordPlacement.INLINE)),
        )
    }

    /** An editor that says where its caret is, with [after] behind it. */
    private class ExtractingEditor(initial: String, after: String = "") :
        RecordingEditor(initial = initial, after = after) {
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().also {
                it.text = text.toString() + textAfter
                it.startOffset = 0
                it.selectionStart = text.length
                it.selectionEnd = text.length
            }
    }

    private fun keyboard(initial: String, after: String = ""): Pair<GlideKeyboard, RecordingEditor> {
        val editor = ExtractingEditor(initial, after)
        val (service, _) = glideKeyboard(editor = editor)
        val end = initial.length
        service.onUpdateSelection(end, end, end, end, -1, -1)
        return service to editor
    }

    private fun GlideKeyboard.press() =
        WMKeyboardService::class.java.getDeclaredMethod("onRetypeButton")
            .apply { isAccessible = true }
            .invoke(this)

    private fun GlideKeyboard.spaceIsOurs() {
        WMKeyboardService::class.java.getDeclaredField("pendingWordSpace")
            .apply { isAccessible = true }
            .setBoolean(this, true)
    }

    @Test
    fun `the button takes the word at the cursor, as the menu's Retype does`() {
        val viaButton = keyboard("hello form ").also { (service, _) -> service.spaceIsOurs() }
        viaButton.first.press()
        val viaAction = keyboard("hello form ").also { (service, _) -> service.spaceIsOurs() }
        viaAction.first.onTextEdit(TextEditAction.RETYPE_WORD, haptic = false)
        assertEquals("hello ", viaButton.second.text.toString())
        assertEquals(viaAction.second.text.toString(), viaButton.second.text.toString())
        assertEquals("form", viaButton.first.uiState.value.suggestions.first())
    }

    @Test
    fun `a second press does not walk back to the word before`() {
        val (service, editor) = keyboard("hello form ")
        service.spaceIsOurs()
        service.press()
        assertEquals("hello ", editor.text.toString())
        // The field's echo of the cleared word: the offer stays at that caret.
        service.onUpdateSelection(11, 11, 6, 6, -1, -1)
        service.press()
        assertEquals("one press clears one word", "hello ", editor.text.toString())
        service.onTextEdit(TextEditAction.RETYPE_WORD, haptic = false)
        assertEquals("the text editing action keeps the same rule", "hello ", editor.text.toString())
    }

    @Test
    fun `a cursor inside a word changes nothing`() {
        val (service, editor) = keyboard("hello wo", after = "rd")
        service.press()
        assertEquals("hello wo", editor.text.toString())
        assertEquals("rd", editor.textAfter)
    }

    @Test
    fun `a mark behind the cursor changes nothing`() {
        val (service, editor) = keyboard("hello form.")
        service.press()
        assertEquals("hello form.", editor.text.toString())
    }
}

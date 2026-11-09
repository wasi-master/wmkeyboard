package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.script.FancyAssembly
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Fancy Text with suggestions and autocorrect (#950): the keyboard composes
 * and corrects plain letters, and only what reaches the field is styled.
 *
 * Before this, every style committed each key as its glyph and nothing ever
 * composed, so "teh" stayed "𝐭𝐞𝐡" and the strip had nothing to offer. The
 * cases below are the paths a styled word can reach the field by, and the one
 * that has to read a styled word back out of it (the undo).
 *
 * The keyboard here routes its field through the service's own
 * [WMKeyboardService.fieldConnection], unlike [GlideKeyboard], because the
 * styling connection is the thing under test.
 */
@RunWith(RobolectricTestRunner::class)
class FancyComposingServiceTest {

    private class FancyKeyboard(private val editor: InputConnection) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = fieldConnection(editor)
        private val field = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        override fun getCurrentInputEditorInfo(): EditorInfo = field
    }

    /** A stand-in for the English list Fancy Text borrows. */
    private fun engine() = SuggestionEngine(
        Trie().apply {
            insert("the", 9000)
            insert("hello", 5000)
            insert("sunshine", 3000)
        },
        BengaliPhoneticIndex(emptyList()),
        UserLexicon(null),
    )

    private fun keyboard(editor: RecordingEditor, style: String = "bold"): FancyKeyboard {
        val service = FancyKeyboard(editor)
        plantPersonalStores(service)
        plant(service, "suggestionEngine", engine())
        seedState(
            service,
            glideReadyState(fieldNoSuggestions = false).copy(
                language = LanguageRegistry.byId(FancyStyles.LANG_ID),
                activeFancyStyleId = style,
            ),
        )
        return service
    }

    private fun plant(service: WMKeyboardService, name: String, value: Any) {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(service, value)
    }

    private fun styled(text: String, style: String = "bold") =
        FancyStyles.transform(text, FancyStyles.byId(style)!!)

    private fun type(service: WMKeyboardService, keys: String) {
        for (key in keys) {
            if (key == ' ') space(service) else service.onKey(Key(label = key.toString()))
        }
    }

    private fun space(service: WMKeyboardService) = service.onKey(Key(label = "space", action = KeyAction.Space))

    private fun backspace(service: WMKeyboardService) = service.onKey(Key(label = "⌫", action = KeyAction.Delete))

    @Test
    fun `the word being typed shows styled while the buffer stays plain`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        type(service, "hel")

        assertEquals(styled("hel"), editor.text.toString())
        assertEquals(styled("hel"), service.uiState.value.composingPreview)
        assertEquals("nothing committed yet", emptyList<String>(), editor.commits)
    }

    @Test
    fun `a space corrects the plain word and commits it styled`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        type(service, "teh ")

        assertEquals(styled("the "), editor.text.toString())
    }

    @Test
    fun `a backspace after the correction puts the typed word back, styled`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        type(service, "teh ")
        backspace(service)

        assertEquals(styled("teh "), editor.text.toString())
    }

    @Test
    fun `a backspace inside the word takes one styled letter off`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        type(service, "helx")
        backspace(service)

        assertEquals(styled("hel"), editor.text.toString())
    }

    @Test
    fun `a pick from the strip commits the plain suggestion styled`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        type(service, "sun")
        service.onSuggestionTapped("sunshine")

        assertEquals(styled("sunshine "), editor.text.toString())
    }

    @Test
    fun `an astral-free style composes and styles too`() {
        val editor = RecordingEditor()
        val service = keyboard(editor, style = "small_caps")

        type(service, "teh hello ")

        assertEquals(styled("the hello ", "small_caps"), editor.text.toString())
    }

    @Test
    fun `Normal corrects and commits plain letters`() {
        val editor = RecordingEditor()
        val service = keyboard(editor, style = FancyStyles.NORMAL_ID)

        type(service, "teh ")

        assertEquals("the ", editor.text.toString())
    }

    @Test
    fun `upside down still styles each key and composes nothing`() {
        val editor = RecordingEditor()
        val service = keyboard(editor, style = "upside_down")

        type(service, "teh")

        assertEquals(styled("t", "upside_down") + styled("e", "upside_down") + styled("h", "upside_down"), editor.text.toString())
        assertEquals("", service.uiState.value.composingPreview)
    }

    @Test
    fun `a styled field reads back as plain words`() {
        val text = "hello world 123"
        for (style in FancyStyles.all) {
            // unstyle leaves reversed text (upside down, mirror) in written order.
            if (style.assembly == FancyAssembly.REVERSED) continue
            val plain = FancyStyles.unstyle(FancyStyles.transform(text, style))
            // Small caps and superscript have no capitals, and a style with
            // no digits leaves them plain, so lowercase is all that is asked.
            assertEquals(style.id, text, plain.lowercase())
        }
    }
}

package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.VietnameseTelexComposer
import com.wasimaster.wmkeyboard.core.input.composer.VietnameseVniComposer
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.VietnameseSettings
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Sentence capitals on the Vietnamese boards (#1221): the first word of a
 * field, and the first word after a full stop, start with a capital when
 * "Capitalize the first letter of a sentence" is on, as they do in English.
 */
@RunWith(RobolectricTestRunner::class)
class VietnameseAutoCapsServiceTest {

    private val sentenceField = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    /** A field that answers caps mode from its own text, as a real editor does. */
    private class CapsEditor : RecordingEditor() {
        override fun getCursorCapsMode(reqModes: Int): Int = TextUtils.getCapsMode(text, text.length, reqModes)
    }

    private class ViKeyboard(
        private val editor: InputConnection,
        private val info: EditorInfo,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = info
    }

    private fun keyboardOn(editor: RecordingEditor, composer: Composer?, restoreMarks: Boolean): ViKeyboard {
        val service = ViKeyboard(editor, sentenceField)
        plantPersonalStores(service)
        val engine = SuggestionEngine(
            Trie().apply {
                insert("xin", 900)
                insert("chào", 800)
                insert("tôi", 700)
                insert("hello", 100)
            },
            BengaliPhoneticIndex(emptyList()),
            UserLexicon(null),
        )
        val field = WMKeyboardService::class.java.getDeclaredField("suggestionEngine")
        field.isAccessible = true
        field.set(service, engine)
        val base = glideReadyState(
            settings = KeyboardSettings(
                learnFromTyping = false,
                haptics = HapticSettings(enabled = false),
                vietnamese = VietnameseSettings(restoreMarks = restoreMarks),
            ),
            fieldNoSuggestions = false,
        )
        seedState(
            service,
            if (composer == null) base else base.copy(composer = composer, language = LanguageRegistry.byId("vi")),
        )
        return service
    }

    /** One key, then the caret report a real editor sends after it. */
    private fun type(service: ViKeyboard, editor: RecordingEditor, text: String) {
        for (ch in text) {
            val before = editor.text.length
            service.onKey(
                if (ch == ' ') Key(label = " ", action = KeyAction.Space)
                else Key(label = ch.toString(), action = KeyAction.Text),
            )
            ShadowSystemClock.advanceBy(Duration.ofMillis(300))
            val at = editor.text.length
            service.onUpdateSelection(before, before, at, at, -1, -1)
        }
    }

    private fun fieldOpens(service: ViKeyboard) = service.onUpdateSelection(0, 0, 0, 0, -1, -1)

    private fun sentences(composer: Composer?, keys: String, restoreMarks: Boolean = false): String {
        val editor = CapsEditor()
        val service = keyboardOn(editor, composer, restoreMarks)
        fieldOpens(service)
        type(service, editor, keys)
        return editor.text.toString()
    }

    @Test
    fun `english control capitalizes both sentences`() {
        assertEquals("Hello. Hello ", sentences(null, "hello. hello "))
    }

    @Test
    fun `telex capitalizes the first word and the one after a full stop`() {
        assertEquals("Xin chào. Tôi ", sentences(VietnameseTelexComposer, "xin chaof. tooi "))
    }

    @Test
    fun `telex keeps the capitals with marks restored from the word list`() {
        assertEquals("Xin chào. Tôi ", sentences(VietnameseTelexComposer, "xin chaof. tooi ", restoreMarks = true))
    }

    @Test
    fun `vni capitalizes the first word and the one after a full stop`() {
        assertEquals("Xin chào. Tôi ", sentences(VietnameseVniComposer, "xin chao2. to6i "))
    }
}

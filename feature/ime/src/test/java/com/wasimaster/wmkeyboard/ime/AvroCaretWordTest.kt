package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.prediction.SeedBigrams
import com.wasimaster.wmkeyboard.core.prediction.SpellingMap
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A caret put back at the end of a Bengali word on Avro takes the word up
 * again (#1181): its roman spelling goes in the buffer, the word is the
 * composing region, and the next key goes on the end of it, as an English
 * word's does.
 */
@RunWith(RobolectricTestRunner::class)
class AvroCaretWordTest {

    private class AvroKeyboard(
        private val editor: InputConnection,
        private val info: EditorInfo,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = info
    }

    private val plainField = EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }

    private fun engine() = SuggestionEngine(
        Trie().apply { insert("the", 10000) },
        BengaliPhoneticIndex(listOf("কেমন" to 5000, "কেমনে" to 900)),
        UserLexicon(null),
        SpellingMap.load("kemon\tকেমন\n".byteInputStream(Charsets.UTF_8)),
        SeedBigrams.load("".byteInputStream(Charsets.UTF_8)),
    ).apply {
        primaryLanguageId = "bn"
        englishSources = false
    }

    private fun keyboardOn(editor: RecordingEditor): AvroKeyboard {
        val service = AvroKeyboard(editor, plainField)
        plantPersonalStores(service)
        val field = WMKeyboardService::class.java.getDeclaredField("suggestionEngine")
        field.isAccessible = true
        field.set(service, engine())
        val avro = BuiltInLayouts.AVRO
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false)),
                fieldNoSuggestions = false,
            ).copy(
                language = avro.language(),
                script = avro.script(),
                composer = composerFor(avro.script(), avro.composerType()),
                layoutId = avro.id,
            ),
        )
        return service
    }

    private fun composing(service: WMKeyboardService): String {
        val field = WMKeyboardService::class.java.getDeclaredField("composing")
        field.isAccessible = true
        return field.get(service).toString()
    }

    @Test
    fun `a caret after a bengali word takes it up in its roman spelling`() {
        val editor = RecordingEditor(initial = "কেমন")
        val service = keyboardOn(editor)

        // The caret lands at the end of the word.
        service.onUpdateSelection(0, 0, 4, 4, -1, -1)

        assertEquals("kemon", composing(service))
        assertEquals("কেমন", service.uiState.value.composingPreview)

        // The next key goes on the end of the word, not in front of a new one.
        service.onKey(Key(label = "e"))
        assertEquals("kemone", composing(service))
        service.onKey(Key(label = "space", action = KeyAction.Space))
        assertEquals("কেমনে ", editor.text.toString())
    }
}

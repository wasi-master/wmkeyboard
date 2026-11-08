package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A word typed on Avro over a selection, or over one the keyboard still
 * thinks is there. Google's search box selects its whole text on a tap and
 * then drops the caret at the start; a key typed between the two reports took
 * the typing-over-a-selection path, which commits the key's own letter, so a
 * stray Latin `j` or `k` landed in front of the Bengali typed after it.
 */
@RunWith(RobolectricTestRunner::class)
class AvroSelectionTypingTest {

    private fun avroKeyboard(editor: RecordingEditor): GlideKeyboard {
        val avro = BuiltInLayouts.AVRO
        val (service, _, _) = glideKeyboard(
            glideReadyState(
                settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false)),
            ).copy(
                language = avro.language(),
                script = avro.script(),
                composer = composerFor(avro.script(), avro.composerType()),
                layoutId = avro.id,
            ),
            editor,
        )
        return service
    }

    /** What the keyboard last heard the selection was, as an echo would have left it. */
    private fun mirrorSelection(service: WMKeyboardService, start: Int, end: Int) {
        for ((name, value) in listOf("expectedSelStart" to start, "expectedSelEnd" to end)) {
            val field = WMKeyboardService::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.setInt(service, value)
        }
    }

    private fun type(service: GlideKeyboard, word: String) {
        for (letter in word) service.onKey(Key(label = letter.toString()))
    }

    @Test
    fun `a word typed before a collapsed selection's echo is transliterated whole`() {
        val editor = RecordingEditor(initial = "তুমি ")
        val service = avroKeyboard(editor)
        // The field reported "তুমি" selected and has since collapsed its
        // caret; the report saying so has not arrived yet.
        mirrorSelection(service, 0, 4)

        type(service, "ami")

        assertEquals("তুমি আমি", editor.text.toString())
        assertEquals("আমি", service.uiState.value.composingPreview)
    }

    @Test
    fun `a letter typed over a selection deletes it and opens a transliterated word`() {
        val editor = RecordingEditor()
        val service = avroKeyboard(editor)
        mirrorSelection(service, 0, 4)

        type(service, "a")

        // The selection is replaced by nothing, and the key composes.
        assertEquals(listOf(""), editor.commits)
        assertEquals("আ", editor.text.toString())
        assertEquals("আ", service.uiState.value.composingPreview)
    }
}

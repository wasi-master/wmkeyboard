package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
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
 * Translate's box takes what is typed as the text to translate, so a phonetic
 * layout spells there as it does in the app's field. Roman letters reached the
 * translator as the word `ami`, not as আমি.
 */
@RunWith(RobolectricTestRunner::class)
class AvroTranslateBoxTest {

    private fun avroIn(panel: PanelMode, editor: RecordingEditor): GlideKeyboard {
        val avro = BuiltInLayouts.AVRO
        val (service, _, _) = glideKeyboard(
            glideReadyState(
                settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false)),
            ).copy(
                language = avro.language(),
                script = avro.script(),
                composer = composerFor(avro.script(), avro.composerType()),
                layoutId = avro.id,
                panel = panel,
                mediaSearchActive = true,
            ),
            editor,
        )
        return service
    }

    private fun type(service: GlideKeyboard, word: String) {
        for (letter in word) service.onKey(Key(label = letter.toString()))
    }

    private fun query(service: GlideKeyboard) = service.uiState.value.mediaQuery

    @Test
    fun `a word typed in the box stands in its script and stays out of the app's field`() {
        val editor = RecordingEditor()
        val service = avroIn(PanelMode.TRANSLATE, editor)

        type(service, "ami")

        assertEquals("আমি", query(service))
        assertEquals("", editor.text.toString())
    }

    @Test
    fun `a space ends the word and the next one spells afresh`() {
        val service = avroIn(PanelMode.TRANSLATE, RecordingEditor())

        type(service, "ami")
        service.onKey(Key(label = " ", action = KeyAction.Space))
        type(service, "ami")

        assertEquals("আমি আমি", query(service))
    }

    @Test
    fun `a pick replaces the whole word, marks and all`() {
        val service = avroIn(PanelMode.TRANSLATE, RecordingEditor())

        type(service, "ami")
        service.onCaptureSuggestion("আমরা")

        assertEquals("আমরা ", query(service))
    }

    @Test
    fun `other search boxes keep the roman letters`() {
        val service = avroIn(PanelMode.WIKIPEDIA, RecordingEditor())

        type(service, "ami")

        assertEquals("ami", query(service))
    }
}

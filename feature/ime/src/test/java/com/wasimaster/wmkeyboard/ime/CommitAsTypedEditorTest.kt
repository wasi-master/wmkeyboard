package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.applyMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A mode's commit-as-typed reaches the editor as one commit a key, with no composing region (#1024). */
@RunWith(RobolectricTestRunner::class)
class CommitAsTypedEditorTest {

    private fun mode(commitAsTyped: Boolean?) =
        KeyboardMode(id = "m", name = "M", commitAsTyped = commitAsTyped)

    private fun typed(settings: KeyboardSettings): RecordingEditor {
        val (service, editor, _) = glideKeyboard(
            state = glideReadyState(settings = settings, fieldNoSuggestions = false),
        )
        "foo".forEach { service.onText(it.toString()) }
        return editor
    }

    private val base = KeyboardSettings(learnFromTyping = false)

    @Test
    fun `with the mode's override on, every key is its own commit`() {
        val editor = typed(base.applyMode(mode(true)))
        assertEquals("foo", editor.text.toString())
        assertEquals(listOf("f", "o", "o"), editor.commits)
    }

    @Test
    fun `with it off the word composes`() {
        val editor = typed(base.applyMode(mode(false)))
        assertEquals("foo", editor.text.toString())
        assertEquals(emptyList<String>(), editor.commits)
    }

    @Test
    fun `with no override the word composes`() {
        val editor = typed(base.applyMode(mode(null)))
        assertEquals("foo", editor.text.toString())
        assertEquals(emptyList<String>(), editor.commits)
    }
}

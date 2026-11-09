package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The undo chip as a chain: several corrections in a sentence, each one
 * recoverable, newest first, one tap at a time.
 *
 * The corrections are real ones, made by a small engine over a word list that
 * holds the words each typo is one swap away from, with the chip's gate opened
 * all the way so that every correction earns an entry. Each test checks the
 * field shows the corrections before it taps anything: no correction, no
 * chain, and an undo test that passes over an uncorrected field proves
 * nothing.
 *
 * What every test here guards is the one failure the chip must never have —
 * rewriting a word it was not about. So the stale and ambiguous cases assert
 * the whole field, not only that the chip went away.
 */
@RunWith(RobolectricTestRunner::class)
class UndoChipChainTest {

    private fun engine() = SuggestionEngine(
        Trie().apply {
            insert("the", 9000)
            insert("and", 8000)
            insert("cat", 6000)
            insert("sat", 5000)
            insert("on", 7000)
            insert("a", 7500)
            insert("mat", 4000)
        },
        BengaliPhoneticIndex(emptyList()),
        UserLexicon(null),
    )

    private fun keyboard(editor: RecordingEditor): GlideKeyboard {
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        val field = WMKeyboardService::class.java.getDeclaredField("suggestionEngine")
        field.isAccessible = true
        field.set(service, engine())
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    // Every correction is under the bar, so every one is chained.
                    suggestionStrip = SuggestionStripSettings(undoChipObviousness = 1f),
                ),
                fieldNoSuggestions = false,
            ),
        )
        return service
    }

    /** Each word followed by a space, the way the space bar commits it. */
    private fun typeWords(service: GlideKeyboard, words: String) {
        for (word in words.split(' ')) {
            for (key in word) service.onKey(Key(label = key.toString()))
            service.onKey(Key(label = "space", action = KeyAction.Space))
        }
    }

    /** The typed words waiting in the chain, oldest first. */
    private fun chain(service: GlideKeyboard): List<String> {
        val field = WMKeyboardService::class.java.getDeclaredField("undoChain")
        field.isAccessible = true
        return (field.get(service) as Collection<*>).map { entry ->
            val typed = entry!!.javaClass.getDeclaredField("typed")
            typed.isAccessible = true
            typed.get(entry) as String
        }
    }

    /**
     * Presses the chip.
     *
     * The strip publishes it from a debounced job these tests never run, so
     * this is that publish: the chain's newest entry, put where the strip
     * would put it, exactly as the refresh does between words. The tap then
     * goes through the real callback, which is what picks the chip out of the
     * slot it shares.
     */
    private fun tapUndo(service: GlideKeyboard) {
        seedState(service, service.uiState.value.copy(undoChips = UndoChips(correctionUndo = chain(service).lastOrNull())))
        service.onRevisionSuggestionTapped()
    }

    @Test
    fun `two corrections come back one tap at a time, newest first`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        typeWords(service, "teh adn")
        assertEquals("the and ", editor.text.toString())
        assertEquals(listOf("teh", "adn"), chain(service))

        tapUndo(service)
        assertEquals("the adn ", editor.text.toString())
        // The chip moved on to the older correction without waiting for a refresh.
        assertEquals("teh", service.uiState.value.correctionUndo)

        tapUndo(service)
        assertEquals("teh adn ", editor.text.toString())
        assertNull(service.uiState.value.correctionUndo)
        assertEquals(emptyList<String>(), chain(service))
    }

    @Test
    fun `three corrections with words between them all come back`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        typeWords(service, "teh cta sat on a mta")
        assertEquals("the cat sat on a mat ", editor.text.toString())
        assertEquals(listOf("teh", "cta", "mta"), chain(service))

        tapUndo(service)
        assertEquals("the cat sat on a mta ", editor.text.toString())
        tapUndo(service)
        assertEquals("the cta sat on a mta ", editor.text.toString())
        tapUndo(service)
        assertEquals("teh cta sat on a mta ", editor.text.toString())
        assertNull(service.uiState.value.correctionUndo)
    }

    /**
     * The newest correction was changed by something other than the keyboard
     * — the word is no longer the one it wrote. Its entry is dropped without a
     * keystroke reaching the field, and the same tap goes on to the older
     * correction, which is still exactly where it was.
     */
    @Test
    fun `an edited correction is skipped and the tap undoes the one before it`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        typeWords(service, "teh adn")
        assertEquals("the and ", editor.text.toString())
        editor.text.replace(4, 7, "end")

        tapUndo(service)

        assertEquals("teh end ", editor.text.toString())
        assertNull(service.uiState.value.correctionUndo)
        assertEquals(emptyList<String>(), chain(service))
    }

    /**
     * The same word twice, both corrected: the old single chip refused this
     * outright, since a word standing twice gave it no way to say which one it
     * meant. Each entry remembers what stood in front of its word, and that
     * tells the two apart.
     */
    @Test
    fun `the same correction twice comes back occurrence by occurrence`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        typeWords(service, "teh cat sat on teh mat")
        assertEquals("the cat sat on the mat ", editor.text.toString())

        tapUndo(service)
        assertEquals("the cat sat on teh mat ", editor.text.toString())
        tapUndo(service)
        assertEquals("teh cat sat on teh mat ", editor.text.toString())
    }

    /**
     * The same word twice behind the same words: "the" typed by hand after a
     * repeat of exactly the text that stood in front of the corrected one.
     * Nothing in the field says which of the two the entry meant, so neither
     * is touched and the entry goes.
     */
    @Test
    fun `a duplicate the entry cannot tell apart is left alone`() {
        val editor = RecordingEditor()
        val service = keyboard(editor)

        typeWords(service, "cat sat on a mat teh cat sat on a mat the")
        val field = "cat sat on a mat the cat sat on a mat the "
        assertEquals(field, editor.text.toString())
        assertEquals(listOf("teh"), chain(service))

        tapUndo(service)

        assertEquals(field, editor.text.toString())
        assertNull(service.uiState.value.correctionUndo)
        assertEquals(emptyList<String>(), chain(service))
    }
}

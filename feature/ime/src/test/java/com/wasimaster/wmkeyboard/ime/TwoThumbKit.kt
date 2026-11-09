package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.TwoThumbBackspace
import com.wasimaster.wmkeyboard.core.settings.TwoThumbWordEnd
import com.wasimaster.wmkeyboard.core.settings.applyMode
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import java.time.Duration
import kotlinx.coroutines.Job
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * What a two-thumb service test needs on top of [GlideServiceHarness]: two-thumb
 * settings, a field that is ordinary text (so a delete reaches
 * `deleteSurroundingText` rather than a raw key event the recording editor
 * ignores), a swipe, a tap, and the waits for a decode and the all-thumbs-up
 * pause.
 *
 * With no dictionary planted, a decode finds nothing and a word shows the keys
 * under its gestures: the straight stroke below reads `qy`.
 */
internal object TwoThumbKit {
    const val PAUSE_MS = 200L
    val grid = glideKeyGrid()
    val delete = Key("⌫", action = KeyAction.Delete)
    val space = Key(" ", action = KeyAction.Space)

    fun settings(
        twoThumb: Boolean = true,
        allUp: Boolean = false,
        undo: Boolean = true,
        noAutoSpaceMode: Boolean = false,
        retractMs: Int = 0,
        band: Boolean = true,
        backspace: TwoThumbBackspace = TwoThumbBackspace.CURRENT,
        spaceFirst: Boolean = false,
        joinFirst: Boolean = false,
    ): KeyboardSettings {
        val base = KeyboardSettings(
            learnFromTyping = false,
            gesture = GestureSettings(
                twoThumbGlide = twoThumb,
                backspaceUndoesGlide = undo,
                twoThumbWordEnd = if (allUp) TwoThumbWordEnd.ALL_UP else TwoThumbWordEnd.SPACE,
                twoThumbPauseMs = PAUSE_MS.toInt(),
                // One pause for every word, as before #747 split it.
                twoThumbTapPauseMs = PAUSE_MS.toInt(),
                twoThumbOneLetterPauseMs = PAUSE_MS.toInt(),
                // Off unless a test is about it, so a letter typed after a
                // pause starts a new word whatever the planted dictionary holds.
                twoThumbRetractWindowMs = retractMs,
                twoThumbPauseBand = band,
                twoThumbBackspace = backspace,
                twoThumbBackspaceSpaceFirst = spaceFirst,
                twoThumbBackspaceJoinFirst = joinFirst,
            ),
        )
        if (!noAutoSpaceMode) return base
        return base.applyMode(KeyboardMode(id = "spaces-off", name = "Spaces off", autoSpace = false))
    }

    private class TextKeyboard(private val editor: InputConnection) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo =
            EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }
    }

    fun keyboard(
        settings: KeyboardSettings,
        initial: String = "",
        editor: RecordingEditor = RecordingEditor(initial = initial),
    ): Pair<WMKeyboardService, RecordingEditor> {
        val service = TextKeyboard(editor)
        plantPersonalStores(service)
        seedState(service, glideReadyState(settings = settings))
        return service to editor
    }

    /** A word list the decoder reads strokes against, so a word has readings and alternates. */
    fun plantDictionary(service: WMKeyboardService, vararg words: Pair<String, Int>) = plantEngine(
        service,
        SuggestionEngine(
            Trie().apply { words.forEach { (word, frequency) -> insert(word, frequency) } },
            BengaliPhoneticIndex(emptyList()),
            UserLexicon(null),
        ),
    )

    /** [engine] as the one the service reads strokes and taps against. */
    fun plantEngine(service: WMKeyboardService, engine: SuggestionEngine) {
        WMKeyboardService::class.java.getDeclaredField("suggestionEngine").apply {
            isAccessible = true
            set(service, engine)
        }
    }

    private fun job(service: WMKeyboardService, name: String): Job? =
        WMKeyboardService::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(service) as Job?
        }

    private fun busy(service: WMKeyboardService): Boolean =
        job(service, "duetJob")?.isActive == true || job(service, "gestureJob")?.isActive == true

    /** Waits out every decode in flight and whatever it posted to the main thread. */
    fun drain(service: WMKeyboardService) {
        settle { !busy(service) }
        repeat(20) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** One lifted stroke along the top row, from Q to Y. */
    fun swipe(service: WMKeyboardService, verdict: GlideVerdict = GlideVerdict.Leader()) =
        service.onGesture(straightStroke(0), grid, GLIDE_KEY_WIDTH, verdict)

    fun tap(service: WMKeyboardService, text: String) = service.onKey(Key(text))

    /** Every thumb up, and the pause after it run out. */
    fun allUpPause(service: WMKeyboardService) {
        service.onGesturePreview(emptyList(), emptyList(), DUET_PING_ALL_UP, GlideCase.None)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PAUSE_MS + 50))
        drain(service)
    }

    fun strip(service: WMKeyboardService): List<String> = service.uiState.value.suggestions
}

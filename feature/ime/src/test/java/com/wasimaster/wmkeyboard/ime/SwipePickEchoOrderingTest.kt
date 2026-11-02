package com.wasimaster.wmkeyboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.grid
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantEngine
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.space
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.strip
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * A two-thumb word picked over from the strip, with the editor answering every
 * edit the way a real app does: a selection update per edit (one per batch),
 * in order, delivered to the service later than the edit that caused it.
 *
 * [RecordingEditor] never calls `onUpdateSelection`, so every path that runs
 * off an echo — the revert anchor that disarms `lastGestureWord`, a composing
 * buffer dropped or re-armed, a word resumed at the caret — was out of reach of
 * the earlier pick tests. This editor reaches them. The matrix below prints
 * every ordering it ran and fails on any where the pick left the swiped word
 * in the field and added the picked one after it.
 */
@RunWith(RobolectricTestRunner::class)
class SwipePickEchoOrderingTest {

    /** A text field whose caret, selection and composing span are its own, and which echoes them. */
    internal class EchoingEditor(target: View = View(RuntimeEnvironment.getApplication())) :
        BaseInputConnection(target, true) {
        var service: WMKeyboardService? = null

        /** How late each echo reaches the service. */
        var echoDelayMs = 0L

        val text = StringBuilder()
        var caret = 0
            private set
        private var compStart = -1
        private var compEnd = -1
        private var batch = 0
        private var dirty = false
        private var reportedSel = 0
        private var reportedCompStart = -1
        private var reportedCompEnd = -1
        private val handler = Handler(Looper.getMainLooper())
        val log = mutableListOf<String>()

        private fun changed() {
            if (batch > 0) {
                dirty = true
                return
            }
            dirty = false
            if (caret == reportedSel && compStart == reportedCompStart && compEnd == reportedCompEnd) return
            val old = reportedSel
            val sel = caret
            val cs = compStart
            val ce = compEnd
            reportedSel = sel
            reportedCompStart = cs
            reportedCompEnd = ce
            log += "edit→echo($old→$sel,$cs..$ce)"
            handler.postDelayed({
                log += "deliver($old→$sel,$cs..$ce)"
                service?.onUpdateSelection(old, old, sel, sel, cs, ce)
            }, echoDelayMs)
        }

        private fun replace(start: Int, end: Int, with: String) {
            text.replace(start, end, with)
            val delta = with.length - (end - start)
            if (compStart >= 0) {
                if (compStart >= end) {
                    compStart += delta
                    compEnd += delta
                } else if (compEnd > start) {
                    compStart = -1
                    compEnd = -1
                }
            }
            caret = start + with.length
        }

        override fun beginBatchEdit(): Boolean {
            batch++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batch > 0) batch--
            if (batch == 0 && dirty) changed()
            return batch > 0
        }

        override fun commitText(t: CharSequence?, newCursorPosition: Int): Boolean {
            val s = t?.toString().orEmpty()
            log += "commitText('$s')"
            val (a, b) = if (compStart >= 0) compStart to compEnd else caret to caret
            compStart = -1
            compEnd = -1
            replace(a, b, s)
            if (newCursorPosition > 1) caret = (caret + newCursorPosition - 1).coerceAtMost(text.length)
            changed()
            return true
        }

        override fun setComposingText(t: CharSequence?, newCursorPosition: Int): Boolean {
            val s = t?.toString().orEmpty()
            log += "setComposingText('$s')"
            val (a, b) = if (compStart >= 0) compStart to compEnd else caret to caret
            compStart = -1
            compEnd = -1
            replace(a, b, s)
            if (s.isNotEmpty()) {
                compStart = a
                compEnd = a + s.length
            }
            changed()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            log += "setComposingRegion($start,$end)"
            val a = minOf(start, end).coerceIn(0, text.length)
            val b = maxOf(start, end).coerceIn(0, text.length)
            if (a == b) {
                compStart = -1
                compEnd = -1
            } else {
                compStart = a
                compEnd = b
            }
            changed()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (compStart >= 0) log += "finishComposingText"
            compStart = -1
            compEnd = -1
            changed()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            log += "deleteSurroundingText($beforeLength,$afterLength)"
            val a = (caret - beforeLength).coerceAtLeast(0)
            val b = (caret + afterLength).coerceAtMost(text.length)
            text.delete(caret, b)
            text.delete(a, caret)
            if (compStart >= 0 && (compEnd > a && compStart < b)) {
                compStart = -1
                compEnd = -1
            } else if (compStart >= caret) {
                compStart -= (caret - a) + (b - caret)
                compEnd -= (caret - a) + (b - caret)
            } else if (compStart >= 0 && compStart >= a) {
                compStart -= caret - a
                compEnd -= caret - a
            }
            caret = a
            changed()
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            caret = start.coerceIn(0, text.length)
            changed()
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            text.substring((caret - n).coerceAtLeast(0), caret)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
            text.substring(caret, (caret + n).coerceAtMost(text.length))

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().also {
                it.text = text.toString()
                it.startOffset = 0
                it.selectionStart = caret
                it.selectionEnd = caret
            }

        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean = true
    }

    private class EchoKeyboard(private val editor: InputConnection) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo =
            EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }
    }

    private fun keyboard(settings: KeyboardSettings): Pair<WMKeyboardService, EchoingEditor> {
        val editor = EchoingEditor()
        val service = EchoKeyboard(editor)
        editor.service = service
        plantPersonalStores(service)
        seedState(service, glideReadyState(settings = settings, fieldNoSuggestions = false))
        val engine = SuggestionEngine(PackedTrie.of(english), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        engine.primaryLanguageId = "en"
        engine.englishSources = true
        engine.apostropheFixes = settings.autoText.apostrophe
        plantEngine(service, engine)
        return service to editor
    }

    private fun strokeThrough(letters: String, pauseAt: Char? = null): List<GesturePoint> {
        val centers = glideKeyCenters()
        val stops = letters.map { centers.getValue(it.code) }
        val points = ArrayList<GesturePoint>()
        stops.zipWithNext().forEachIndexed { index, (from, to) ->
            if (letters[index] == pauseAt) {
                repeat(STEPS * 2) { points += GesturePoint(from.first, from.second, points.size * STEP_MS) }
            }
            for (step in 0 until STEPS) {
                val f = step / STEPS.toFloat()
                points += GesturePoint(
                    from.first + (to.first - from.first) * f,
                    from.second + (to.second - from.second) * f,
                    points.size * STEP_MS,
                )
            }
        }
        val (x, y) = stops.last()
        points += GesturePoint(x, y, points.size * STEP_MS)
        return points
    }

    private fun looper() = shadowOf(Looper.getMainLooper())

    /** Lets [ms] of main-thread time pass, waiting out decodes as it goes. */
    private fun pass(service: WMKeyboardService, ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 10L)
            looper().idleFor(Duration.ofMillis(step))
            Thread.sleep(2)
            left -= step
        }
        drain(service)
    }

    enum class Input { LONE_SWIPE, TAP_THEN_SWIPE, SWIPE_THEN_TAP }
    enum class End { ALL_UP, SPACE }
    enum class PickAt {
        /** Before the word ends. */
        COMPOSING,
        /** Right after the word end, its echoes still in flight. */
        AFTER_END_ECHOES_PENDING,
        /** After the word end, once its echoes have arrived. */
        AFTER_END_ECHOES_DELIVERED,
    }

    private data class Scenario(
        val input: Input,
        val end: End,
        val pickAt: PickAt,
        val echoDelayMs: Long,
        val barTouch: Boolean,
        val retractMs: Int,
    )

    private data class Outcome(val scenario: Scenario, val field: String, val stripAtPick: List<String>, val log: List<String>)

    private fun enter(service: WMKeyboardService, input: Input) {
        when (input) {
            Input.LONE_SWIPE ->
                service.onGesture(strokeThrough("its", pauseAt = 't'), grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())
            Input.TAP_THEN_SWIPE -> {
                service.onKey(Key("i"))
                pass(service, 30)
                service.onGesture(strokeThrough("ts"), grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())
            }
            Input.SWIPE_THEN_TAP -> {
                service.onGesture(strokeThrough("it"), grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())
                pass(service, 30)
                service.onKey(Key("s"))
            }
        }
    }

    private fun run(s: Scenario): Outcome {
        val (service, editor) = keyboard(settings(allUp = s.end == End.ALL_UP, retractMs = s.retractMs))
        editor.echoDelayMs = s.echoDelayMs
        enter(service, s.input)
        // Long enough for the decode, short of the all-up pause.
        pass(service, 60)
        val pick = { picked: () -> Unit ->
            if (s.barTouch) service.onGesturePreview(emptyList(), emptyList(), DUET_PING_BAR_DOWN, GlideCase.None)
            if (s.barTouch) service.onGesturePreview(emptyList(), emptyList(), DUET_PING_BAR_UP, GlideCase.None)
            picked()
        }
        var stripAtPick = emptyList<String>()
        val tapIt = {
            stripAtPick = strip(service)
            if ("it's" in stripAtPick) service.onSuggestionTapped("it's")
        }
        when (s.pickAt) {
            PickAt.COMPOSING -> {
                pass(service, s.echoDelayMs + 20)
                pick(tapIt)
            }
            PickAt.AFTER_END_ECHOES_PENDING, PickAt.AFTER_END_ECHOES_DELIVERED -> {
                when (s.end) {
                    End.ALL_UP -> {
                        service.onGesturePreview(emptyList(), emptyList(), DUET_PING_ALL_UP, GlideCase.None)
                        // The pause, and the commit at its end.
                        val until = if (s.pickAt == PickAt.AFTER_END_ECHOES_PENDING) TwoThumbKit.PAUSE_MS + 5 else TwoThumbKit.PAUSE_MS + s.echoDelayMs + 60
                        var left = until
                        while (left > 0) {
                            looper().idleFor(Duration.ofMillis(5))
                            left -= 5
                            if (left % 50 == 0L) drainQuiet(service)
                        }
                    }
                    End.SPACE -> {
                        pass(service, s.echoDelayMs + 20)
                        service.onKey(space)
                        if (s.pickAt == PickAt.AFTER_END_ECHOES_DELIVERED) pass(service, s.echoDelayMs + 40)
                    }
                }
                pick(tapIt)
            }
        }
        pass(service, s.echoDelayMs + TwoThumbKit.PAUSE_MS + 400)
        return Outcome(s, editor.text.toString(), stripAtPick, editor.log.toList())
    }

    /** Waits out the decoders without letting main-thread time pass. */
    private fun drainQuiet(service: WMKeyboardService) {
        settle {
            listOf("duetJob", "gestureJob").none { name ->
                WMKeyboardService::class.java.getDeclaredField(name).let {
                    it.isAccessible = true
                    (it.get(service) as kotlinx.coroutines.Job?)?.isActive == true
                }
            }
        }
        looper().idle()
    }

    @Test
    fun `a pick over a two-thumb word replaces it under every echo ordering`() {
        val results = mutableListOf<Outcome>()
        for (input in Input.entries) for (end in End.entries) for (pickAt in PickAt.entries)
            for (delay in listOf(0L, 40L, 120L, 260L)) for (bar in listOf(false, true)) for (retract in listOf(0, 700)) {
                if (bar && end == End.SPACE) continue
                if (retract != 0 && end == End.SPACE) continue
                results += run(Scenario(input, end, pickAt, delay, bar, retract))
            }
        val picked = results.filter { "it's" in it.stripAtPick }
        val appended = picked.filter { it.field.trimEnd() != "it's" }
        println("SCENARIOS RUN: ${results.size}, picked: ${picked.size}, not replaced: ${appended.size}")
        results.forEach { println("RESULT ${it.scenario} strip=${it.stripAtPick.take(3)} field='${it.field}'") }
        appended.forEach { o -> println("FAIL ${o.scenario}\n  " + o.log.joinToString("\n  ")) }
        assertEquals(appended.joinToString("\n") { "${it.scenario} -> '${it.field}'" }, 0, appended.size)
    }

    /**
     * The all-thumbs-up pause types the word and then its space as two edits,
     * outside a batch, so the editor echoes twice: the caret after "its", then
     * after "its ". When those echoes reach the service more than
     * REVERT_SETTLE_MS after the commit armed its revert guard (a slow app, or
     * the keyboard's main thread busy), the first records the anchor and the
     * second reads as the caret moving off it: `lastGestureWord` is disarmed.
     * The strip is left showing the word's alternates until the asynchronous
     * refresh that echo started lands, and a pick in between has no gesture
     * word to replace, so it is committed at the caret after the word.
     */
    @Test
    fun `a pick between a late echo's disarm and the strip refresh replaces the word`() {
        val failures = mutableListOf<String>()
        for (input in listOf(Input.LONE_SWIPE, Input.TAP_THEN_SWIPE)) {
            val (service, editor) = keyboard(settings(allUp = true, retractMs = 700))
            editor.echoDelayMs = LATE_ECHO_MS
            enter(service, input)
            pass(service, 60)
            looper().idleFor(Duration.ofMillis(LATE_ECHO_MS))
            drain(service)
            assertEquals(input.name, "its", editor.text.toString())
            assertEquals(input.name, listOf("its", "it's"), strip(service).take(2))

            service.onGesturePreview(emptyList(), emptyList(), DUET_PING_ALL_UP, GlideCase.None)
            // Main-thread time passes a millisecond at a time until the
            // editor's echo of the space has been delivered.
            var waited = 0L
            while (editor.log.none { it.startsWith("deliver(3→4") } && waited < 2_000) {
                looper().idleFor(Duration.ofMillis(1))
                waited++
                if (waited % 25 == 0L) drainQuiet(service)
            }
            println("$input after ${waited}ms field='${editor.text}' strip=${strip(service)}")
            assertEquals(input.name, "its ", editor.text.toString())
            assertEquals(input.name, listOf("its", "it's"), strip(service).take(2))

            service.onSuggestionTapped("it's")
            pass(service, LATE_ECHO_MS + 100)

            println("$input log:\n  " + editor.log.joinToString("\n  "))
            val field = editor.text.toString()
            println("RESULT $input field='$field'")
            if (field != "it's ") failures += "$input: field '$field'"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    /** How long the strip keeps offering the alternates after the disarming echo. */
    @Test
    fun `measure the window a late echo leaves the alternates on a disarmed strip`() {
        val (service, editor) = keyboard(settings(allUp = true, retractMs = 700))
        editor.echoDelayMs = LATE_ECHO_MS
        enter(service, Input.LONE_SWIPE)
        pass(service, 60)
        looper().idleFor(Duration.ofMillis(LATE_ECHO_MS))
        drain(service)
        service.onGesturePreview(emptyList(), emptyList(), DUET_PING_ALL_UP, GlideCase.None)
        var waited = 0L
        while (editor.log.none { it.startsWith("deliver(3→4") } && waited < 2_000) {
            looper().idleFor(Duration.ofMillis(1)); waited++
        }
        var open = 0L
        while (strip(service).take(2) == listOf("its", "it's") && open < 5_000) {
            looper().idleFor(Duration.ofMillis(1)); open++
            if (open % 25 == 0L) drainQuiet(service)
        }
        println("WINDOW alternates stayed ${open}ms after the disarming echo; strip now ${strip(service)}")
    }

    private companion object {
        /** Past REVERT_SETTLE_MS (200), counted from the commit that armed the guard. */
        const val LATE_ECHO_MS = 230L
        const val STEPS = 5
        const val STEP_MS = 20L

        val english by lazy {
            File("../../app/dictionaries-src/en.txt").inputStream().use { DictionaryLoader.loadEntries(it) }
        }
    }
}

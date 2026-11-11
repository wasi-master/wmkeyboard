package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.TwoThumbBackspace
import android.os.Looper
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.TwoThumbWordEnd
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import java.time.Duration
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Issue #640: the service side of a two-thumb word (#638), gesture by gesture.
 *
 * This module has no dictionary, so every decode here finds no word at all —
 * which is exactly the state #640 is about: a word still being built whose
 * gestures finish nothing yet. With no prefix to show either, the field shows
 * the letters under the gestures ([DuetWord.literal]); [literalOf] works the
 * same thing out from the grid, so the tests do not depend on which key sits
 * where.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbWordLifecycleTest {

    private val grid = glideKeyGrid()
    private val space = Key(label = " ", action = KeyAction.Space)

    private fun keyboard(allUp: Boolean = false): Pair<WMKeyboardService, RecordingEditor> {
        val settings = KeyboardSettings(
            learnFromTyping = false,
            gesture = GestureSettings(
                twoThumbGlide = true,
                twoThumbWordEnd = if (allUp) TwoThumbWordEnd.ALL_UP else TwoThumbWordEnd.SPACE,
                twoThumbPauseMs = PAUSE_MS.toInt(),
                twoThumbTapPauseMs = PAUSE_MS.toInt(),
                twoThumbOneLetterPauseMs = PAUSE_MS.toInt(),
                twoThumbRetractWindowMs = 0,
                twoThumbBackspace = TwoThumbBackspace.CURRENT,
            ),
        )
        val (service, editor, _) = glideKeyboard(glideReadyState(settings = settings))
        return service to editor
    }

    private fun swipe(service: WMKeyboardService, row: Int) =
        service.onGesture(straightStroke(row), grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())

    private fun field(service: WMKeyboardService, name: String): Any? {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(service)
    }

    /** The two-thumb loop's pings: the last finger lifted, and the first one landed. */
    private fun thumbsUp(service: WMKeyboardService) =
        service.onGesturePreview(emptyList(), emptyList(), DUET_PING_ALL_UP, GlideCase.None)

    private fun thumbDown(service: WMKeyboardService) =
        service.onGesturePreview(emptyList(), emptyList(), DUET_PING_DOWN, GlideCase.None)

    /** Lets [ms] of the main looper's clock go by, running whatever falls due. */
    private fun letTimePass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun gestures(service: WMKeyboardService): Int = (field(service, "duetWord") as DuetWord).snapshot().size

    private fun decoding(service: WMKeyboardService): Boolean = (field(service, "duetJob") as Job?)?.isActive == true

    /** The keys a straight stroke starts and ends on, the way the field spells an unread swipe. */
    private fun literalOf(stroke: List<GesturePoint>): String {
        fun nearest(p: GesturePoint): KeyCenter =
            grid.minBy { (it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y) }
        val first = Character.toLowerCase(nearest(stroke.first()).codePoint)
        val last = Character.toLowerCase(nearest(stroke.last()).codePoint)
        return buildString {
            appendCodePoint(first)
            if (last != first) appendCodePoint(last)
        }
    }

    @Test
    fun `gestures that finish no word yet are all kept, and nothing is typed`() {
        val (service, editor) = keyboard()
        val first = literalOf(straightStroke(0))
        val both = first + literalOf(straightStroke(1))

        swipe(service, 0)
        settle { editor.text.toString() == first }
        assertEquals("the first swipe never reached the field", first, editor.text.toString())

        swipe(service, 1)
        settle { editor.text.toString() == both }

        assertEquals("a gesture was dropped from the word in progress", both, editor.text.toString())
        assertEquals("the word lost a gesture from its buffer", 2, gestures(service))
        assertTrue("something was typed before the word ended: ${editor.commits}", editor.commits.isEmpty())
    }

    @Test
    fun `space while the last gesture is still being read types the whole word, once`() {
        val (service, editor) = keyboard()
        val first = literalOf(straightStroke(0))
        val both = first + literalOf(straightStroke(1))

        swipe(service, 0)
        settle { editor.text.toString() == first }
        // The second gesture's decode hops to another thread and has not come
        // back yet: the looper is not pumped before the space.
        swipe(service, 1)
        service.onKey(space)
        settle { !decoding(service) }
        // Then give the cancelled decode every chance to come home and write
        // over the field anyway: its thread finishes, and its resumption is a
        // post to this looper.
        repeat(LATE_ROUNDS) {
            Thread.sleep(LATE_STEP_MS)
            settle { true }
        }

        assertEquals("the space typed a stale reading, or the late one landed after it", "$both ", editor.text.toString())
        assertEquals("the word did not end with its space", 0, gestures(service))
    }

    @Test
    fun `under all thumbs up, thumbs taking turns keep building one word`() {
        // Issue #674: one thumb lifts before the other lands. The gap between
        // them used to end the word, so it came out in two pieces.
        val (service, editor) = keyboard(allUp = true)
        val first = literalOf(straightStroke(0))
        val both = first + literalOf(straightStroke(1))

        swipe(service, 0)
        settle { editor.text.toString() == first }
        thumbsUp(service)
        letTimePass(PAUSE_MS / 2)
        thumbDown(service)
        swipe(service, 1)
        settle { editor.text.toString() == both }
        assertTrue("the gap between the thumbs ended the word: ${editor.commits}", editor.commits.isEmpty())

        thumbsUp(service)
        letTimePass(PAUSE_MS + PAUSE_SLACK_MS)
        settle { editor.text.toString() == "$both " }
        assertEquals("the pause did not type the word with its space", "$both ", editor.text.toString())
    }

    @Test
    fun `under all thumbs up, a tapped word ends after the pause, and a lift inside it does not split it`() {
        // Issue #676: T, lift, o is still one word, and it gets its space.
        val (service, editor) = keyboard(allUp = true)

        service.onKey(Key(label = "t"))
        thumbsUp(service)
        letTimePass(PAUSE_MS / 2)
        thumbDown(service)
        service.onKey(Key(label = "o"))
        thumbsUp(service)
        settle { true }
        assertEquals("the lift between the taps ended the word", "to", editor.text.toString().lowercase())

        letTimePass(PAUSE_MS + PAUSE_SLACK_MS)
        settle { editor.text.endsWith(" ") }
        assertEquals("the tapped word did not get its space", "to ", editor.text.toString().lowercase())
    }

    private companion object {
        const val PAUSE_MS = 200L
        const val PAUSE_SLACK_MS = 50L
        const val LATE_ROUNDS = 20
        const val LATE_STEP_MS = 10L
    }
}

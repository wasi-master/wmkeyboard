package com.wasimaster.wmkeyboard.core.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [WmSlider] inside a scrolling column, driven with real touch events.
 *
 * Every movement is measured in touch slops, so an input means the same thing
 * at any density, and the flick that matters is checked to be one a slider
 * without this guard takes: nothing here passes because the input was too
 * gentle to trip the bug in the first place.
 */
@RunWith(RobolectricTestRunner::class)
class WmSliderTest {

    private val compose = createComposeRule()

    // The rule launches a ComponentActivity, which this library module's test
    // manifest does not declare, so it is registered before the launch.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            object : ExternalResource() {
                override fun before() {
                    val app = RuntimeEnvironment.getApplication()
                    shadowOf(app.packageManager)
                        .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                }
            },
        )
        .around(compose)

    private var value by mutableFloatStateOf(0f)
    private var finishes = 0
    private var slop = 0f
    private lateinit var scroll: ScrollState
    private lateinit var scope: CoroutineScope
    private lateinit var focusSlider: () -> Unit

    @Test
    fun `a mostly vertical flick that starts on the slider scrolls the page and leaves the value alone`() {
        page { WmSlider(value = value, onValueChange = { value = it }, onValueChangeFinished = { finishes++ }) }
        flickUp()
        assertEquals(0f, value, 0f)
        assertEquals(0, finishes)
        assertTrue("the page did not scroll", scroll.value > 0)
    }

    @Test
    fun `a scroll that swings sideways afterwards stays the page's`() {
        page { WmSlider(value = value, onValueChange = { value = it }, onValueChangeFinished = { finishes++ }) }
        compose.onNodeWithTag(SLIDER).performTouchInput {
            down(center)
            // Straight up past the slop: the page takes the gesture here...
            moveBy(Offset(0f, -slop * 2f))
            // ...and a long swing sideways after that does not hand it over.
            repeat(3) { moveBy(Offset(slop * 4f, 0f)) }
        }
        compose.waitForIdle()
        // How far the page came while the finger was still down. Lifting it after
        // a sideways plateau flings the page back to the top, with a slider under
        // the finger or without one: foundation fits a curve to the last samples,
        // which by then is heading back down.
        val scrolled = scroll.value
        compose.onNodeWithTag(SLIDER).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(0f, value, 0f)
        assertEquals(0, finishes)
        assertTrue("the page did not scroll", scrolled > 0)
    }

    @Test
    fun `the flick's first frame is the one a slider without the guard takes`() {
        // Issue #153 in one frame: a slider that starts a drag on a slop of
        // sideways travel alone takes this, because it sees the event before the
        // page does. Material's own slider did until material3 1.4.0, which
        // guards it the way this one does, so the frame is measured here rather
        // than played against it.
        assertTrue("sideways past the slop", abs(FIRST_FRAME.x) >= 1f)
        assertTrue("vertically past the slop", abs(FIRST_FRAME.y) >= 1f)
        assertFalse("and further up than sideways", isSliderDrag(FIRST_FRAME, slop = 1f))
    }

    @Test
    fun `a sideways drag slides the thumb to the finger`() {
        page { WmSlider(value = value, onValueChange = { value = it }, onValueChangeFinished = { finishes++ }) }
        compose.onNodeWithTag(SLIDER).performTouchInput {
            down(Offset(width * 0.25f, centerY))
            // Sideways by more than it drifts down: the slider's, not the page's.
            moveBy(Offset(slop * 2f, slop / 2f))
            moveTo(Offset(width * 0.75f, centerY + slop))
            up()
        }
        compose.waitForIdle()
        assertEquals(0.75f, value, TOLERANCE)
        assertEquals(1, finishes)
        assertEquals(0, scroll.value)
    }

    @Test
    fun `a tap sets the value where it lands`() {
        page { WmSlider(value = value, onValueChange = { value = it }, onValueChangeFinished = { finishes++ }) }
        compose.onNodeWithTag(SLIDER).performTouchInput { click(Offset(width * 0.25f, centerY)) }
        compose.waitForIdle()
        assertEquals(0.25f, value, TOLERANCE)
        assertEquals(1, finishes)
    }

    @Test
    fun `the touch that stops the page scrolling is not a tap on the slider`() {
        page { WmSlider(value = value, onValueChange = { value = it }, onValueChangeFinished = { finishes++ }) }
        compose.mainClock.autoAdvance = false
        scope.launch { scroll.animateScrollBy(SCROLL_PX, tween(durationMillis = 10_000, easing = LinearEasing)) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertTrue("the page is not scrolling yet", scroll.isScrollInProgress)
        compose.onNodeWithTag(SLIDER).performTouchInput { click(Offset(width * 0.75f, centerY)) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0f, value, 0f)
        assertEquals(0, finishes)
    }

    @Test
    fun `a drag is the slider's once it goes a slop sideways and further sideways than vertically`() {
        assertTrue(isSliderDrag(Offset(10f, 9f), slop = 8f))
        assertTrue(isSliderDrag(Offset(-10f, -9f), slop = 8f))
        assertFalse("short of the slop", isSliderDrag(Offset(7f, 0f), slop = 8f))
        assertFalse("a tie goes to the page", isSliderDrag(Offset(10f, 10f), slop = 8f))
        assertFalse("mostly vertical", isSliderDrag(Offset(12f, -24f), slop = 8f))
    }

    @Test
    fun `the value under a finger follows Material's track, which stops half a thumb from each edge`() {
        // 104 px wide with a 4 px thumb: the thumb's centre runs from 2 to 102.
        assertEquals(0f, sliderValueAt(2f, 104, 4, rtl = false, range = 0f..1f), 0f)
        assertEquals(0.5f, sliderValueAt(52f, 104, 4, rtl = false, range = 0f..1f), EXACT)
        assertEquals(1f, sliderValueAt(102f, 104, 4, rtl = false, range = 0f..1f), 0f)
        assertEquals("held at the start", 0f, sliderValueAt(-30f, 104, 4, rtl = false, range = 0f..1f), 0f)
        assertEquals("held at the end", 1f, sliderValueAt(300f, 104, 4, rtl = false, range = 0f..1f), 0f)
        assertEquals("right to left", 0.25f, sliderValueAt(77f, 104, 4, rtl = true, range = 0f..1f), EXACT)
        assertEquals("scaled to the range", 30f, sliderValueAt(52f, 104, 4, rtl = false, range = 10f..50f), EXACT)
        assertEquals("no room for a track", 10f, sliderValueAt(1f, 2, 4, rtl = false, range = 10f..50f), 0f)
        // 2..6 with three stops between: 2, 3, 4, 5, 6 at quarters of the track.
        // A finger just past the halfway mark between two stops takes the next.
        assertEquals("nearest stop below", 3f, sliderValueAt(30f, 104, 4, rtl = false, range = 2f..6f, steps = 3), 0f)
        assertEquals("nearest stop above", 4f, sliderValueAt(42f, 104, 4, rtl = false, range = 2f..6f, steps = 3), 0f)
    }

    @Test
    fun `d-pad down off a focused slider reaches the row below instead of moving the thumb`() {
        // Issue #259: Material answers the vertical arrows as well as the
        // horizontal ones and consumes them, so on a television the first
        // slider the remote reaches keeps the focus for good.
        remoteControlPage()
        compose.onNodeWithTag(SLIDER).performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(0f, value, 0f)
        assertEquals(0, finishes)
        compose.onNodeWithTag(BELOW).assertIsFocused()
    }

    @Test
    fun `d-pad up off a focused slider reaches the row above`() {
        remoteControlPage()
        compose.onNodeWithTag(SLIDER).performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertEquals(0f, value, 0f)
        compose.onNodeWithTag(ABOVE).assertIsFocused()
    }

    @Test
    fun `d-pad right still moves the value and keeps the focus`() {
        // The pair the remote has to spare: sideways is how a slider is set.
        remoteControlPage()
        compose.onNodeWithTag(SLIDER).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertTrue("the value did not move: was $value", value > 0f)
        compose.onNodeWithTag(ABOVE).assertIsNotFocused()
        compose.onNodeWithTag(BELOW).assertIsNotFocused()
    }

    /** A focusable row, the slider, another focusable row, with the slider focused. */
    @OptIn(ExperimentalTestApi::class)
    private fun remoteControlPage() {
        compose.setContent {
            slop = LocalViewConfiguration.current.touchSlop
            scroll = rememberScrollState()
            scope = rememberCoroutineScope()
            val requester = remember { FocusRequester() }
            focusSlider = { requester.requestFocus() }
            Column(Modifier.height(PAGE_DP.dp).verticalScroll(scroll)) {
                Box(Modifier.fillMaxWidth().height(ROW_DP.dp).testTag(ABOVE).focusable())
                Box(Modifier.fillMaxWidth().focusRequester(requester).testTag(SLIDER)) {
                    WmSlider(value = value, onValueChange = { value = it }, onValueChangeFinished = { finishes++ })
                }
                Box(Modifier.fillMaxWidth().height(ROW_DP.dp).testTag(BELOW).focusable())
            }
        }
        compose.runOnIdle { focusSlider() }
        compose.waitForIdle()
    }

    /** A column taller than its window, at the top, with the slider in view near the top. */
    private fun page(slider: @Composable () -> Unit) {
        compose.setContent {
            slop = LocalViewConfiguration.current.touchSlop
            scroll = rememberScrollState()
            scope = rememberCoroutineScope()
            Column(Modifier.height(PAGE_DP.dp).verticalScroll(scroll)) {
                Spacer(Modifier.height(ABOVE_DP.dp))
                Box(Modifier.fillMaxWidth().testTag(SLIDER)) { slider() }
                Spacer(Modifier.height(BELOW_DP.dp))
            }
        }
    }

    /**
     * A quick flick up the page from the slider's middle. Its first frame takes
     * the finger past the slop on both axes at once, twice as far up as
     * sideways — the frame a slider without the guard claims.
     */
    private fun flickUp() {
        compose.onNodeWithTag(SLIDER).performTouchInput {
            down(center)
            moveBy(FIRST_FRAME * slop)
            repeat(4) { moveBy(Offset(slop / 4f, -slop * 3f)) }
            up()
        }
        compose.waitForIdle()
    }
}

private const val SLIDER = "slider"
private const val ABOVE = "above"
private const val BELOW = "below"
private const val ROW_DP = 48
private const val PAGE_DP = 300
private const val ABOVE_DP = 100
private const val BELOW_DP = 600
private const val SCROLL_PX = 200f

/** The flick's first frame, in touch slops: past the slop on both axes at once. */
private val FIRST_FRAME = Offset(1.5f, -3f)

/** Thumb widths shift the answer by under a hundredth on the test's slider. */
private const val TOLERANCE = 0.01f
private const val EXACT = 1e-5f

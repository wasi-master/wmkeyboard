package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.GlideLookAhead
import com.wasimaster.wmkeyboard.core.settings.GlideLoopStrengthRange
import com.wasimaster.wmkeyboard.core.settings.GlidePauseStrengthRange
import com.wasimaster.wmkeyboard.core.settings.glideTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a pause and a loop are worth, and that a setting reaches each (issue
 * #1250). The decoder-level half, a stop on the u lifting `four` over `for`,
 * is `native/wm-engine/tests/glide_marks.rs`.
 */
class GlideMarkStrengthTest {

    @Test
    fun `the settings ship at the decoder's own charges`() {
        val shipped = GestureSettings().glideTuning()
        assertEquals(GlideBeam.Tuning.DEFAULT.unclaimedDwell, shipped.unclaimedDwell, 0f)
        assertEquals(GlideBeam.Tuning.DEFAULT.unclaimedLoop, shipped.unclaimedLoop, 0f)
        assertTrue(GestureSettings().pauseStrength in GlidePauseStrengthRange)
        assertTrue(GestureSettings().loopStrength in GlideLoopStrengthRange)
    }

    @Test
    fun `a strength the user set is the charge the decoder runs with`() {
        val tuned = GestureSettings(pauseStrength = 7f, loopStrength = 6f).glideTuning()
        assertEquals(7f, tuned.unclaimedDwell, 0f)
        assertEquals(6f, tuned.unclaimedLoop, 0f)
        // Nothing else moves with them.
        assertEquals(
            GestureSettings().glideTuning(),
            tuned.copy(
                unclaimedDwell = GlideBeam.Tuning.DEFAULT.unclaimedDwell,
                unclaimedLoop = GlideBeam.Tuning.DEFAULT.unclaimedLoop,
            ),
        )
    }

    @Test
    fun `a pause outweighs the half nat between two words drawn alike`() {
        // `for` and `four` are one stroke and sit about half a nat apart in
        // the bundled list. The charge is in alignment units, so it reaches
        // the score through the shape weight: the fitted 0.9 came to 0.45
        // nats, under the gap, and a stop on the u changed nothing.
        val tuning = GlideBeam.Tuning.DEFAULT
        assertTrue(tuning.unclaimedDwell * tuning.shapeWeight > 0.6)
        assertTrue(tuning.unclaimedLoop * tuning.shapeWeight > 1.0)
    }

    @Test
    fun `the guess's charge and the tiers follow the shape weight`() {
        // Both are nats against gaps the shape weight scales. Left at their
        // old values after it was cut to a fifth, "When sure" showed a guess
        // on a real stroke 80 % drawn 8 times in a hundred.
        val tuning = GlideBeam.Tuning.DEFAULT
        assertTrue(tuning.lookAheadCost <= tuning.shapeWeight)
        assertTrue(GlideLookAhead.CONFIDENT.margin > GlideLookAhead.EAGER.margin)
        assertTrue(GlideLookAhead.CONFIDENT.margin <= 2.0)
    }
}

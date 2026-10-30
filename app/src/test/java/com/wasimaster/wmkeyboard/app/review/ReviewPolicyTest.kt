package com.wasimaster.wmkeyboard.app.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {

    private val now = 1_000_000_000_000L
    private val installedLongAgo = now - ReviewPolicy.MIN_INSTALLED_MILLIS

    @Test
    fun `asks once there is enough use and it never has`() {
        assertTrue(ReviewPolicy.shouldAsk(ReviewPolicy.MIN_WORDS, installedLongAgo, 0L, now))
    }

    @Test
    fun `too few words does not ask`() {
        assertFalse(ReviewPolicy.shouldAsk(ReviewPolicy.MIN_WORDS - 1, installedLongAgo, 0L, now))
    }

    @Test
    fun `a new install does not ask however much was typed`() {
        assertFalse(ReviewPolicy.shouldAsk(100_000L, installedLongAgo + 1, 0L, now))
    }

    @Test
    fun `an earlier ask holds until the window ends`() {
        val justInside = now - ReviewPolicy.REPEAT_MILLIS + 1
        assertFalse(ReviewPolicy.shouldAsk(100_000L, installedLongAgo, justInside, now))
        assertTrue(ReviewPolicy.shouldAsk(100_000L, installedLongAgo, now - ReviewPolicy.REPEAT_MILLIS, now))
    }

    @Test
    fun `an ask dated in the future counts as expired`() {
        assertTrue(ReviewPolicy.shouldAsk(100_000L, installedLongAgo, now + 1, now))
    }
}

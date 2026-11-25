package com.wasimaster.wmkeyboard.core.otp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A copied bare code stays out of the clipboard history only when a
 * notification brought that code recently; every other number is a clip.
 */
class NotificationOtpSeenTest {

    private fun arrived(code: String, at: Long) = NotificationOtpBus.publish(
        NotificationOtp(
            code = code,
            sourcePackage = "com.example.bank",
            sourceApp = "Bank",
            message = "Your code is $code",
            notificationKey = null,
            postedAt = at,
        ),
    )

    @Test fun aCodeFromANotificationMatchesWithinTheWindow() {
        val at = 1_000_000L
        arrived("731946", at)
        assertTrue(NotificationOtpBus.seenWithin("731946", at + 60_000L))
        assertTrue(NotificationOtpBus.seenWithin(" 731946 ", at + NotificationOtpBus.COPY_MATCH_WINDOW_MS))
    }

    @Test fun anOldCodeNoLongerMatches() {
        val at = 2_000_000L
        arrived("552803", at)
        assertFalse(NotificationOtpBus.seenWithin("552803", at + NotificationOtpBus.COPY_MATCH_WINDOW_MS + 1))
    }

    @Test fun aNumberNoNotificationBroughtIsNotACode() {
        assertFalse(NotificationOtpBus.seenWithin("90817263", 3_000_000L))
        assertFalse(NotificationOtpBus.seenWithin("", 3_000_000L))
    }

    @Test fun aUsedCodeStillCounts() {
        val at = 4_000_000L
        arrived("KX4Q9Z", at)
        NotificationOtpBus.clear()
        assertTrue(NotificationOtpBus.seenWithin("kx4q9z", at + 5_000L))
    }
}

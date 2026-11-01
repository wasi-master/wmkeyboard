package com.wasimaster.wmkeyboard.ime

import android.app.Notification
import android.app.Person
import com.wasimaster.wmkeyboard.core.otp.NotificationOtp
import com.wasimaster.wmkeyboard.core.otp.NotificationOtpBus
import com.wasimaster.wmkeyboard.core.otp.NotificationOtpMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What a notification hands the code detector, and which code the bus keeps
 * (#1049): an SMS app may carry its text only in the message list, and the
 * shade can hand over an older code after a newer one.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationOtpHarvestTest {

    @After fun clearBus() = NotificationOtpBus.clear()

    private fun builder() = Notification.Builder(RuntimeEnvironment.getApplication(), "sms")

    @Test fun readsTheNewestMessageOfAConversation() {
        val sender = Person.Builder().setName("Bank").build()
        val style = Notification.MessagingStyle(Person.Builder().setName("Me").build())
            .addMessage("Your OTP is 111111", 1_000L, sender)
            .addMessage("Your OTP is 222222", 2_000L, sender)
        val text = NotificationOtpMonitor.harvest(builder().setStyle(style).build())
        assertTrue(text, "222222" in text)
        assertFalse(text, "111111" in text)
    }

    @Test fun readsTheTickerText() {
        val text = NotificationOtpMonitor.harvest(builder().setTicker("Your OTP is 333333").build())
        assertTrue(text, "333333" in text)
    }

    private fun otp(code: String, at: Long) = NotificationOtp(
        code = code,
        sourcePackage = "sms",
        sourceApp = "SMS",
        message = "",
        notificationKey = null,
        postedAt = at,
    )

    @Test fun anOlderCodeReadLaterDoesNotReplaceANewerOne() {
        NotificationOtpBus.publish(otp("444444", 2_000L))
        NotificationOtpBus.publish(otp("555555", 1_000L))
        assertEquals("444444", NotificationOtpBus.latest.value?.code)
    }

    @Test fun aNewerCodeStillReplacesAnOlderOne() {
        NotificationOtpBus.publish(otp("666666", 1_000L))
        NotificationOtpBus.publish(otp("777777", 2_000L))
        assertEquals("777777", NotificationOtpBus.latest.value?.code)
    }
}

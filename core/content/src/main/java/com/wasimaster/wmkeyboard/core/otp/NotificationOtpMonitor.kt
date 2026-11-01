package com.wasimaster.wmkeyboard.core.otp

import android.app.Notification
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.wasimaster.wmkeyboard.core.debug.DebugLog

/**
 * Turns a posted notification into a [NotificationOtp] on the bus, or ignores
 * it. Called by the notification listener for every notification while the
 * code chip is on, so everything here is a cheap synchronous check — the
 * expensive part (the regex pass) only runs once the notification has survived
 * the filters.
 */
object NotificationOtpMonitor {

    private const val TAG = "NotificationOtp"

    /** Notification text scanned per notification; codes arrive early. */
    private const val MAX_SCANNED_CHARS = 1000

    /** How much of the message rides along for context display. */
    private const val MAX_MESSAGE_CHARS = 300

    /** The top of the "Offer a code for" slider; anything older can't be offered. */
    private const val MAX_SCAN_AGE_MS = 10 * 60_000L

    /**
     * Recently processed notifications, so an app updating the same
     * notification (progress ticks, group re-sorts) does not re-raise a chip
     * the user already dismissed. Insertion-ordered, oldest evicted.
     */
    private val processed = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) =
            size > 64
    }

    /**
     * The notifications already in the shade, newest first, for a code the
     * listener never saw posted: it was not bound yet, or the process had died
     * (#1049). Stops at the first code, so nothing older is read, and skips
     * what is too old to be offered at all.
     */
    fun scan(context: Context, active: Array<StatusBarNotification>) {
        val oldest = System.currentTimeMillis() - MAX_SCAN_AGE_MS
        for (sbn in active.filter { it.postTime >= oldest }.sortedByDescending { it.postTime }) {
            if (process(context, sbn)) return
        }
    }

    /** True when [sbn] held a code. */
    fun process(context: Context, sbn: StatusBarNotification): Boolean {
        // Our own notifications, and anything pinned: a media session, a
        // download, a foreground service — none of them ever carries a code,
        // and media is the one poster guaranteed to be chatty here. A group
        // summary is read: an app may post nothing else, and the bus already
        // keeps one code from one app to one chip.
        if (sbn.packageName == context.packageName) return false
        if (sbn.isOngoing) return false
        val notification = sbn.notification ?: return false

        val dedupeKey = "${sbn.key}:${sbn.postTime}"
        synchronized(processed) {
            if (processed.containsKey(dedupeKey)) return false
            processed[dedupeKey] = Unit
        }

        val text = harvest(notification).take(MAX_SCANNED_CHARS)
        // Android 15 and up give an untrusted listener this line in place of a
        // code's text (#1049). Nothing can read the code then, but the settings
        // row can say why no chip came.
        val redacted = redactedLine().let { it != null && it in text }
        if (redacted) NotificationOtpCapture.noteRedacted(context, sbn.postTime)
        NotificationOtpCapture.loadKeywords(context)
        // Why no chip came, for About > Diagnostics. Never the text itself.
        val code = NotificationOtpExtractor.extract(text)
        if (code == null) {
            DebugLog.d(TAG, "${sbn.packageName}: ${if (redacted) "text hidden by Android" else "no code"}, ${text.length} chars")
            return false
        }
        DebugLog.d(TAG, "${sbn.packageName}: code found")

        NotificationOtpBus.publish(
            NotificationOtp(
                code = code,
                sourcePackage = sbn.packageName,
                sourceApp = appLabel(context, sbn.packageName),
                message = text.take(MAX_MESSAGE_CHARS),
                notificationKey = sbn.key,
                postedAt = sbn.postTime,
            ),
        )
        return true
    }

    /** The framework's own wording, in the phone's language; null where it has none. */
    private fun redactedLine(): String? = runCatching {
        val system = Resources.getSystem()
        val id = system.getIdentifier("redacted_notification_message", "string", "android")
        if (id == 0) null else system.getString(id)
    }.getOrNull()

    /**
     * Every text surface a notification has, joined so a keyword in the title
     * can anchor a code in the body. Ordered visible-first: the extractor
     * returns the first anchored match, and the surfaces the user can see
     * should out-rank the summary line.
     *
     * A messaging app may put its text only in the message list (#1049). Only
     * the newest message is read: the list is the whole thread, and a code from
     * an earlier message would arrive looking as fresh as this notification.
     */
    @Suppress("DEPRECATION") // The typed getParcelableArray is API 33.
    fun harvest(notification: Notification): String {
        val parts = ArrayList<CharSequence>(8)
        notification.extras?.let { extras ->
            extras.getCharSequence(Notification.EXTRA_TITLE)?.let(parts::add)
            extras.getCharSequence(Notification.EXTRA_TEXT)?.let(parts::add)
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let(parts::add)
            (extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle)
                ?.getCharSequence("text")?.let(parts::add)
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let(parts::addAll)
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let(parts::add)
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.let(parts::add)
        }
        notification.tickerText?.let(parts::add)
        return parts.joinToString(" ")
    }

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))
}

package com.wasimaster.wmkeyboard.core.otp

import android.app.Notification
import android.content.Context
import android.content.res.Resources
import android.service.notification.StatusBarNotification

/**
 * Turns a posted notification into a [NotificationOtp] on the bus, or ignores
 * it. Called by the notification listener for every notification while the
 * code chip is on, so everything here is a cheap synchronous check — the
 * expensive part (the regex pass) only runs once the notification has survived
 * the filters.
 */
object NotificationOtpMonitor {

    /** Notification text scanned per notification; codes arrive early. */
    private const val MAX_SCANNED_CHARS = 1000

    /** How much of the message rides along for context display. */
    private const val MAX_MESSAGE_CHARS = 300

    /**
     * Recently processed notifications, so an app updating the same
     * notification (progress ticks, group re-sorts) does not re-raise a chip
     * the user already dismissed. Insertion-ordered, oldest evicted.
     */
    private val processed = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) =
            size > 64
    }

    fun process(context: Context, sbn: StatusBarNotification) {
        // Our own notifications, and anything pinned: a media session, a
        // download, a foreground service — none of them ever carries a code,
        // and media is the one poster guaranteed to be chatty here.
        if (sbn.packageName == context.packageName) return
        if (sbn.isOngoing) return
        val notification = sbn.notification ?: return
        // The summary repeats its children's text; scanning both would raise
        // the same code twice with different keys.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val dedupeKey = "${sbn.key}:${sbn.postTime}"
        synchronized(processed) {
            if (processed.containsKey(dedupeKey)) return
            processed[dedupeKey] = Unit
        }

        val text = harvest(notification).take(MAX_SCANNED_CHARS)
        // Android 15 and up give an untrusted listener this line in place of a
        // code's text (#1049). Nothing can read the code then, but the settings
        // row can say why no chip came.
        if (redactedLine().let { it != null && it in text }) NotificationOtpCapture.noteRedacted(context, sbn.postTime)
        NotificationOtpCapture.loadKeywords(context)
        val code = NotificationOtpExtractor.extract(text) ?: return

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
     */
    private fun harvest(notification: Notification): String {
        val extras = notification.extras ?: return ""
        val parts = ArrayList<CharSequence>(6)
        extras.getCharSequence(Notification.EXTRA_TITLE)?.let(parts::add)
        extras.getCharSequence(Notification.EXTRA_TEXT)?.let(parts::add)
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let(parts::add)
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let(parts::addAll)
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let(parts::add)
        extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.let(parts::add)
        return parts.joinToString(" ")
    }

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))
}

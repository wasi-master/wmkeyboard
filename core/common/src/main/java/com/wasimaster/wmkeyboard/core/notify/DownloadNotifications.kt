package com.wasimaster.wmkeyboard.core.notify

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.wasimaster.wmkeyboard.common.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * How far along one download is, in the only terms the shade cares about.
 *
 * Every download in this app has its own status type, with its own stages —
 * verifying a checksum, packing a trie, unzipping a pack. None of that belongs
 * in a notification, so each caller flattens its own status into this before
 * handing it over.
 */
sealed interface DownloadProgress {

    /**
     * Bytes so far, and the total when the source reported one. [text] stands
     * in for the byte count where the work is not a transfer (words spelled,
     * say), and the bar still runs from [bytes] to [total].
     */
    data class Running(val bytes: Long, val total: Long, val text: String? = null) : DownloadProgress

    /** Finished, and whatever the file was for is ready to use. */
    data object Done : DownloadProgress

    /** Stopped on an error. [reason] is a sentence the caller already resolved. */
    data class Failed(val reason: String?) : DownloadProgress

    /**
     * Not downloading, and nothing to say: cancelled, deleted, or never
     * started. Clears the notification rather than replacing it.
     */
    data object Gone : DownloadProgress
}

/**
 * Notification keys for the downloads that have no manager to key them.
 *
 * Most of what this app fetches lives in a manager with a state map, and that
 * map's own key is what the shade uses. The three below are suspend calls with
 * a progress lambda instead — ML Kit's ink models, the system's offline voice
 * model, Keyman's rule files — so their keys are agreed here rather than at
 * each call site, because the settings app and the keyboard both start them
 * and must not open two rows for one download.
 */
object DownloadKeys {
    fun handwriting(languageTag: String): String = "handwriting/$languageTag"
    fun voiceModel(languageTag: String): String = "voice-model/$languageTag"
    fun keymanRules(keyboardId: String): String = "keyman/$keyboardId"

    /** ML Kit's on-device translation model for one language, by model code. */
    fun translateModel(code: String): String = "translate-model/$code"
}

/**
 * The shade's view of a download.
 *
 * A download in this app belongs to a process-wide manager, not to the screen
 * that started it, and the screen is usually gone long before the download is.
 * That is the whole case for notifying: without it, a 400 MB voice model
 * finishes with nobody told, and a failure halfway is discovered days later by
 * someone wondering why their dictionary never appeared.
 *
 * Callers hand over a flow rather than calling progress in themselves, because
 * the managers already publish one and the alternative is a posting site inside
 * every one of them. [watch] is idempotent per [key]: starting the same
 * download twice replaces the collector instead of stacking two.
 *
 * Nothing here keeps the process alive — this app does no background work — so
 * a download whose process dies takes its notification with it, via the timeout
 * every progress post carries. See [WmNotifications.PROGRESS_TIMEOUT_MS].
 */
object DownloadNotifications {

    /**
     * How often a running download may redraw its notification.
     *
     * The managers report bytes about four times a second, which is right for
     * a progress bar on screen and far too often for a row in the shade.
     */
    private const val POST_INTERVAL_MS = 900L

    /**
     * How long a watch waits for its download to actually start.
     *
     * A manager sets its status from a coroutine, so the first thing the flow
     * hands back is almost always the state *before* the press: no entry at
     * all, or the finished state of a file being re-fetched. Treating either as
     * the end would stop the watch a moment before the download began, or
     * announce "ready to use" for something that has not been fetched yet — so
     * nothing counts as an ending until a running state has been seen, and a
     * watch that never sees one gives up quietly after this.
     */
    private const val START_WINDOW_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val watching = mutableMapOf<String, Job>()

    /**
     * Follows one download to its end and keeps the shade in step.
     *
     * [key] identifies the download (the same string the manager keys its own
     * state map by is ideal). [title] is what the user calls the thing being
     * downloaded — "Bangla dictionary", "Whisper small" — resolved by the
     * caller, since the words live in the caller's module.
     */
    fun watch(context: Context, key: String, title: String, flow: Flow<DownloadProgress>) {
        val app = context.applicationContext
        val id = NotificationIds.forDownload(key)
        synchronized(watching) {
            watching.remove(key)?.cancel()
            val job = scope.launch {
                // A holder rather than captured `var`s: the collector runs once
                // per emission, and the compiler reads a write in it that is
                // only ever read by the *next* run as a write to nothing.
                val watch = Watch(giveUpAt = SystemClock.elapsedRealtime() + START_WINDOW_MS)
                flow.collect { progress ->
                    if (progress is DownloadProgress.Running) {
                        watch.running = true
                        val now = SystemClock.elapsedRealtime()
                        if (now - watch.lastPostAt < POST_INTERVAL_MS) return@collect
                        watch.lastPostAt = now
                        postRunning(app, id, title, progress)
                        return@collect
                    }
                    // Everything below is an ending, and an ending only counts
                    // once there was a beginning. See [START_WINDOW_MS].
                    if (!watch.running) {
                        if (SystemClock.elapsedRealtime() > watch.giveUpAt) stop(key)
                        return@collect
                    }
                    when (progress) {
                        DownloadProgress.Done -> postDone(app, id, title)
                        is DownloadProgress.Failed -> postFailed(app, id, title, progress.reason)
                        DownloadProgress.Gone -> WmNotifications.cancel(app, id)
                        is DownloadProgress.Running -> Unit
                    }
                    stop(key)
                }
            }
            watching[key] = job
        }
    }

    /**
     * Follows a download that reports through callbacks instead of a flow.
     *
     * Some of what this app fetches has no state map behind it: ML Kit's ink
     * models, the system's own offline voice model and the Keyman rule files
     * are suspend calls with a progress lambda, owned by whoever called them.
     * The returned handle is the flow those callers do not have — they push
     * into it and the shade follows, with the same throttling, the same
     * timeout and the same switch as everything else.
     *
     * Starts as running with an unknown size, so the caller has nothing to do
     * beyond reporting the ending it already knows about.
     */
    /**
     * The text of a download that brings a feature's engine library before
     * its model: which of the two is coming in, and its own byte count, so
     * the bar starting again at the second file reads as the next step.
     */
    fun stepText(context: Context, library: Boolean, bytes: Long, total: Long): String {
        val readout = if (total > 0L) {
            context.getString(
                R.string.common_notify_download_progress,
                Formatter.formatShortFileSize(context, bytes),
                Formatter.formatShortFileSize(context, total),
            )
        } else {
            Formatter.formatShortFileSize(context, bytes)
        }
        return context.getString(
            if (library) R.string.common_notify_download_step_library else R.string.common_notify_download_step_model,
            readout,
        )
    }

    fun start(context: Context, key: String, title: String): Handle {
        val progress = MutableStateFlow<DownloadProgress>(DownloadProgress.Running(0, 0))
        watch(context, key, title, progress)
        return Handle(progress)
    }

    /**
     * One callback-driven download, from the caller's side.
     *
     * Every method is safe to call after the download has ended, and after the
     * shade has stopped listening: this is handed to code with cancellation
     * paths and error paths of its own, and a notification handle is not worth
     * a single `if` at any of them.
     */
    class Handle internal constructor(private val progress: MutableStateFlow<DownloadProgress>) {

        /** [total] is 0 while the size is unknown, which draws an endless bar. */
        fun progress(bytes: Long, total: Long) {
            progress.value = DownloadProgress.Running(bytes, total)
        }

        /** Work that is not a transfer: [done] of [total], described by [text]. */
        fun progress(done: Long, total: Long, text: String) {
            progress.value = DownloadProgress.Running(done, total, text)
        }

        /** Finished, and whatever was fetched is usable. */
        fun done() {
            progress.value = DownloadProgress.Done
        }

        /** [reason] is a sentence the caller has already resolved, or null. */
        fun failed(reason: String? = null) {
            progress.value = DownloadProgress.Failed(reason)
        }

        /** Cancelled, or finished with nothing to say. Takes the row down. */
        fun gone() {
            progress.value = DownloadProgress.Gone
        }
    }

    /** Drops a download's notification and stops following it. */
    fun clear(context: Context, key: String) {
        stop(key)
        WmNotifications.cancel(context, NotificationIds.forDownload(key))
    }

    private fun stop(key: String) {
        synchronized(watching) { watching.remove(key) }?.cancel()
    }

    private fun postRunning(
        context: Context,
        id: Int,
        title: String,
        progress: DownloadProgress.Running,
    ) {
        val known = progress.total > 0L
        val percent = if (known) {
            (progress.bytes * PERCENT / progress.total).toInt().coerceIn(0, PERCENT.toInt())
        } else {
            0
        }
        WmNotifications.post(context, NotificationKind.DOWNLOADS, id) {
            setContentTitle(title)
            setContentText(
                if (progress.text != null) {
                    progress.text
                } else if (known) {
                    context.getString(
                        R.string.common_notify_download_progress,
                        Formatter.formatShortFileSize(context, progress.bytes),
                        Formatter.formatShortFileSize(context, progress.total),
                    )
                } else {
                    Formatter.formatShortFileSize(context, progress.bytes)
                },
            )
            setProgress(PERCENT.toInt(), percent, !known)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setTimeoutAfter(WmNotifications.PROGRESS_TIMEOUT_MS)
            openApp(context)
        }
    }

    private fun postDone(context: Context, id: Int, title: String) {
        WmNotifications.post(context, NotificationKind.DOWNLOADS, id) {
            setContentTitle(title)
            setContentText(context.getString(R.string.common_notify_download_done))
            setAutoCancel(true)
            setSilent(true)
            openApp(context)
        }
    }

    private fun postFailed(context: Context, id: Int, title: String, reason: String?) {
        WmNotifications.post(context, NotificationKind.DOWNLOADS, id) {
            setContentTitle(context.getString(R.string.common_notify_download_failed, title))
            reason?.let {
                setContentText(it)
                setStyle(NotificationCompat.BigTextStyle().bigText(it))
            }
            setAutoCancel(true)
            openApp(context)
        }
    }

    /** Tapping any of these opens the app, which is where the download lives. */
    private fun NotificationCompat.Builder.openApp(context: Context) {
        val intent = WmNotifications.launchIntent(context) ?: return
        setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    /** One watch's own bookkeeping; see the note where it is built. */
    private class Watch(val giveUpAt: Long) {
        var running = false
        var lastPostAt = 0L
    }

    /** The scale a notification's progress bar is drawn on. */
    private const val PERCENT = 100L
}

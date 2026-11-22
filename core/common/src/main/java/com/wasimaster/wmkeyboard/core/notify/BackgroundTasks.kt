package com.wasimaster.wmkeyboard.core.notify

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One long job's state, as both the screen that started it and the shade see it.
 */
sealed interface TaskState {

    /**
     * Under way: [done] of [total] (0 while the size is not known yet, which
     * draws an endless bar), and the line to show for it, already in words.
     */
    data class Running(val done: Long, val total: Long, val text: String?) : TaskState

    /** Finished. [message] is the sentence the screen would have shown; [ok] is false for a failure. */
    data class Ended(val message: String, val ok: Boolean) : TaskState
}

/**
 * Long jobs the user starts from a screen and should not have to sit and watch
 * (#1522): saving or restoring the downloaded files, importing a sticker set,
 * writing a zip of packs.
 *
 * Before this each of them ran on the coroutine scope of the screen that
 * started it, which had two results. Nothing told the user how far along it
 * was, or that it was still going at all, and leaving the screen cancelled it
 * halfway, with nothing said. Here a job runs on a scope of the process's own,
 * so going back or to another screen leaves it running. Its progress is a
 * [StateFlow] the screen reads whenever it is open again, and the shade carries
 * the same progress while it runs and a line when it ends.
 *
 * This keeps the app's rule of no background work: there is no foreground
 * service, so a process Android kills takes the job with it. The progress post
 * carries [WmNotifications.PROGRESS_TIMEOUT_MS], so the shade then clears
 * itself instead of sitting at 43%.
 *
 * Posts go under [NotificationKind.DOWNLOADS] and its switch.
 */
object BackgroundTasks {

    /** How often a running job may redraw its notification; see [DownloadNotifications]. */
    private const val POST_INTERVAL_MS = 900L

    /** How often a running job may change what its screen shows. */
    private const val STATE_INTERVAL_MS = 120L

    private const val PERCENT = 100

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val states = MutableStateFlow<Map<String, TaskState>>(emptyMap())

    /** Every job running or ended and not yet seen, by key. */
    val all: StateFlow<Map<String, TaskState>> = states.asStateFlow()

    /** Whether the job under [key] is running now. */
    fun isRunning(key: String): Boolean = states.value[key] is TaskState.Running

    /** What a job hands back when it ends: the sentence to show, and whether it worked. */
    class Outcome(val message: String, val ok: Boolean = true)

    /**
     * What a running job reports through. Safe from any thread, and cheap to
     * call per file: the shade is redrawn at most every [POST_INTERVAL_MS].
     */
    class Progress internal constructor(
        private val context: Context,
        private val key: String,
        private val title: String,
    ) {
        private var lastStateAt = 0L
        private var lastPostAt = 0L

        fun update(done: Long, total: Long, text: String? = null) {
            val now = SystemClock.elapsedRealtime()
            // A zip reports every 64 KB; the screen needs a few frames a second.
            val last = total > 0L && done >= total
            if (!last && now - lastStateAt < STATE_INTERVAL_MS) return
            lastStateAt = now
            val running = TaskState.Running(done, total, text)
            states.update { it + (key to running) }
            if (now - lastPostAt < POST_INTERVAL_MS) return
            lastPostAt = now
            postRunning(context, key, title, running)
        }
    }

    /**
     * Runs [work] under [key], unless a job is already running there, and
     * returns whether it started. [title] names the job in the shade ("Saving
     * downloaded files"). An exception [work] throws ends the job as a failure
     * with [failure] as its message.
     */
    fun start(
        context: Context,
        key: String,
        title: String,
        failure: String,
        work: suspend (Progress) -> Outcome,
    ): Boolean {
        val app = context.applicationContext
        val first = TaskState.Running(0, 0, null)
        var started = false
        states.update { current ->
            // Set on every pass: update() may run this more than once.
            started = current[key] !is TaskState.Running
            if (started) current + (key to first) else current
        }
        if (!started) return false
        postRunning(app, key, title, first)
        scope.launch {
            val outcome = try {
                work(Progress(app, key, title))
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Outcome(failure, ok = false)
            }
            states.update { it + (key to TaskState.Ended(outcome.message, outcome.ok)) }
            postEnded(app, key, title, outcome)
        }
        return true
    }

    /**
     * Lets go of an ended job once its screen has shown the message, so the
     * next visit does not show it again. Leaves a running job alone.
     */
    fun dismiss(key: String) {
        states.update { current -> if (current[key] is TaskState.Ended) current - key else current }
    }

    private fun idFor(key: String): Int = NotificationIds.forDownload("task/$key")

    private fun postRunning(context: Context, key: String, title: String, running: TaskState.Running) {
        val known = running.total > 0L
        val percent = if (known) (running.done * PERCENT / running.total).toInt().coerceIn(0, PERCENT) else 0
        WmNotifications.post(context, NotificationKind.DOWNLOADS, idFor(key)) {
            setContentTitle(title)
            running.text?.let { setContentText(it) }
            setProgress(PERCENT, percent, !known)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setTimeoutAfter(WmNotifications.PROGRESS_TIMEOUT_MS)
            openApp(context)
        }
    }

    private fun postEnded(context: Context, key: String, title: String, outcome: Outcome) {
        WmNotifications.post(context, NotificationKind.DOWNLOADS, idFor(key)) {
            setContentTitle(title)
            setContentText(outcome.message)
            setStyle(NotificationCompat.BigTextStyle().bigText(outcome.message))
            setAutoCancel(true)
            setSilent(outcome.ok)
            openApp(context)
        }
    }

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
}

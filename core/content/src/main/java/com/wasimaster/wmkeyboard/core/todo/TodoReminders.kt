package com.wasimaster.wmkeyboard.core.todo

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wasimaster.wmkeyboard.app.MainActivityContract
import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import java.io.File
import java.time.ZoneId
import kotlin.concurrent.thread
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Reminds of a task when it falls due (#995): a notification with the task's
 * words and a Done button that ticks it.
 *
 * Built the way the plugins' reminders are ([com.wasimaster.wmkeyboard.core.plugins.PluginReminderAlarms]):
 * one wake-up, registered for the soonest reminder still to come, and
 * registered again from the list after every change, so re-arming is safe at
 * any moment. What has been shown is not written down task by task: the last
 * wake-up's time is, and a wake-up shows every reminder that fell after it.
 * A date moved later reminds again; a date already past when it was set (a
 * task app's old one) never does.
 *
 * No boot receiver, for the reason the clock gives: the keyboard starts at
 * boot, and [rearmOnStart] puts the wake-up back. The list lives in
 * credential-encrypted storage, so nothing is read before the first unlock.
 */
object TodoReminders {

    private const val TAG = "TodoReminders"
    private const val PREFS = "todo_reminders"
    private const val KEY_LAST = "last_wake"
    private const val CHANNEL_ID = "todo_reminders"
    private const val REQUEST_WAKE = 1

    /** Registers the wake-up for the soonest reminder to come, or clears it. */
    fun rearm(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val tasks = read(app) ?: return
        val last = lastWake(app, System.currentTimeMillis())
        val zone = ZoneId.systemDefault()
        val next = tasks.mapNotNull { TodoDue.remindAfter(it, last, zone) }.minOrNull()
        val wake = wakeIntent(app)
        if (next == null) {
            am.cancel(wake)
            return
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        val set = exact && runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, wake) }.isSuccess
        // Without the exact-alarm grant Doze may hold the wake-up back some
        // minutes. A reminder a little late beats none.
        if (!set) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, wake) }
    }

    /** [rearm] on a thread of its own, for the panel and the settings after a change. */
    fun requestRearm(context: Context) {
        val app = context.applicationContext
        thread(name = "todo-reminders", isDaemon = true) {
            runCatching { rearm(app) }.onFailure { DebugLog.e(TAG, "re-arm failed", it) }
        }
    }

    /**
     * Shows what came due while nothing was running, then re-arms. From a cold
     * process start and from the unlock. Free for anyone with no list.
     */
    fun rearmOnStart(context: Context) {
        val app = context.applicationContext
        if (!DirectBoot.isUserUnlocked(app) || !file(app).isFile) return
        thread(name = "todo-reminders", isDaemon = true) {
            runCatching { onWake(app, System.currentTimeMillis()) }
                .onFailure { DebugLog.e(TAG, "re-arm at start failed", it) }
        }
    }

    /** The wake-up fired: show every reminder since the last one, then register the next. */
    fun onWake(context: Context, now: Long) {
        val app = context.applicationContext
        val tasks = read(app) ?: return
        val last = lastWake(app, now)
        val zone = ZoneId.systemDefault()
        for (task in tasks) {
            val at = TodoDue.remindAfter(task, last, zone) ?: continue
            if (at <= now) post(app, task, at)
        }
        prefs(app).edit().putLong(KEY_LAST, now).apply()
        rearm(app)
    }

    /** The notification's Done: the task is ticked, as a tap in the panel would. */
    fun onDone(context: Context, id: Long) {
        val app = context.applicationContext
        NotificationManagerCompat.from(app).cancel(NotificationIds.forTodoReminder(id))
        if (!DirectBoot.isUserUnlocked(app)) return
        val store = TodoStore(file(app))
        if (store.item(id)?.done == false) {
            store.setDone(id, true, System.currentTimeMillis())
            store.save()
        }
        rearm(app)
    }

    /** Holds [id]'s reminders off until [until], and takes the one on show away. */
    fun onSnooze(context: Context, id: Long, until: Long) {
        val app = context.applicationContext
        NotificationManagerCompat.from(app).cancel(NotificationIds.forTodoReminder(id))
        if (!DirectBoot.isUserUnlocked(app)) return
        val store = TodoStore(file(app))
        store.snooze(id, until)
        store.save()
        rearm(app)
    }

    /** The first open task whose time has come, for the suggestion strip; null when none has. */
    fun firstDue(context: Context, now: Long, skip: (TodoTask) -> Boolean): TodoTask? {
        val app = context.applicationContext
        val zone = ZoneId.systemDefault()
        return read(app).orEmpty().firstOrNull { !skip(it) && TodoDue.isDue(it, now, zone) }
    }

    // ---- the list ------------------------------------------------------------

    private fun file(context: Context) = File(context.filesDir, TodoStore.FILE_PATH)

    private fun read(context: Context): List<TodoTask>? {
        if (!DirectBoot.isUserUnlocked(context)) return null
        val file = file(context)
        if (!file.isFile) return emptyList()
        return TodoStore(file).all()
    }

    /** The last wake-up's time; the first ever is now, so a fresh list starts with no backlog. */
    private fun lastWake(context: Context, now: Long): Long {
        val prefs = prefs(context)
        val last = prefs.getLong(KEY_LAST, 0L)
        if (last > 0L) return last
        prefs.edit().putLong(KEY_LAST, now).apply()
        return now
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wakeIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_WAKE,
            Intent(context, TodoReminderReceiver::class.java).setAction(TodoReminderReceiver.ACTION_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ---- the notification ----------------------------------------------------

    private fun post(context: Context, task: TodoTask, at: Long) {
        if (!WmNotifications.canPost(context)) return
        ensureChannel(context)
        val id = NotificationIds.forTodoReminder(task.id)
        val open = PendingIntent.getActivity(
            context, id,
            MainActivityContract.intent(context, ROUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val done = PendingIntent.getBroadcast(
            context, id,
            Intent(context, TodoReminderReceiver::class.java)
                .setAction(TodoReminderReceiver.ACTION_DONE)
                .putExtra(TodoReminderReceiver.EXTRA_ID, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(CommonR.drawable.common_ic_notification)
            .setContentTitle(context.getString(R.string.core_content_todo_reminder_title))
            .setContentText(task.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.text))
            .setWhen(at)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.core_content_todo_reminder_done), done)
            .build()
        // canPost has ruled the refusal out; some platforms still throw, and
        // nothing here may crash over it.
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.core_content_todo_reminders_channel_title),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = context.getString(R.string.core_content_todo_reminders_channel_body)
        manager.createNotificationChannel(channel)
    }

    /** The To-do tool's settings page, which a tap on the reminder opens. */
    private const val ROUTE = "tool/TODO"
}

/**
 * Where the to-do reminder's wake-up and its Done button land (#995). In the
 * app manifest, not exported: only the app's own alarm and notification send
 * it. Not direct-boot aware: the list is unreadable before the first unlock.
 */
class TodoReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "todo-reminders", isDaemon = true) {
            try {
                when (intent.action) {
                    ACTION_WAKE -> TodoReminders.onWake(app, System.currentTimeMillis())
                    ACTION_DONE -> TodoReminders.onDone(app, intent.getLongExtra(EXTRA_ID, -1L))
                }
            } catch (e: Exception) {
                DebugLog.e("TodoReminders", "receiver failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WAKE = "com.wasimaster.wmkeyboard.todo.REMIND"
        const val ACTION_DONE = "com.wasimaster.wmkeyboard.todo.DONE"
        const val EXTRA_ID = "id"
    }
}

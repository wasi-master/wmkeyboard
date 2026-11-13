package com.wasimaster.wmkeyboard.core.plugins

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
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.plugins.R
import kotlin.concurrent.thread
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Shows the reminders plugins set, when their time comes.
 *
 * One wake-up for every plugin together, registered for the soonest pending
 * reminder. When it fires, [onWake] posts whatever is due, takes it off each
 * plugin's list, and registers the next. The registration is a pure function of
 * the files, so re-arming is safe at any moment, and the plugin never runs:
 * the text it handed over is all the app needs.
 *
 * A reminder is shown only while the plugin may run: plugins on, this plugin
 * on, and its manifest still declaring `reminders`. One that comes due while
 * any of that is false is dropped rather than held, so turning a plugin back on
 * a week later does not empty a week of reminders into the shade at once.
 *
 * No boot receiver, for the reason the clock gives: the keyboard starts at
 * boot anyway, and [rearmOnStart] puts the wake-up back then, showing anything
 * that came due while the phone was off. Plugins live in credential-encrypted
 * storage, so before the first unlock there is nothing to read; the unlock
 * re-attaches the store and re-arms.
 */
object PluginReminderAlarms {

    /** Registers the wake-up for the soonest reminder of any plugin, or clears it. */
    fun rearm(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val store = PluginStore.get(app)
        val next = store.plugins()
            .mapNotNull { plugin -> store.remindersFile(plugin.id)?.takeIf { it.isFile } }
            .mapNotNull { PluginReminders(it).next() }
            .minOrNull()
        val wake = pending(app)
        if (next == null) {
            am.cancel(wake)
            return
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        val set = exact && runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, wake) }.isSuccess
        // Without the exact-alarm grant the best Android allows is a wake-up
        // Doze may hold back some minutes. A reminder a little late beats none.
        if (!set) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, wake) }
    }

    /**
     * [rearm] on a thread of its own. What a running plugin's `wm.reminders`
     * calls: the plugin thread must not wait on file reads and AlarmManager.
     */
    fun requestRearm(context: Context) {
        val app = context.applicationContext
        thread(name = "plugin-reminders", isDaemon = true) {
            runCatching { rearm(app) }.onFailure { DebugLog.e(TAG, "re-arm failed", it) }
        }
    }

    /**
     * Shows what came due while nothing was running, then re-arms. From a cold
     * process start and from the unlock. Off the main thread, and free for
     * anyone with no plugin that declares reminders.
     */
    fun rearmOnStart(context: Context) {
        val app = context.applicationContext
        val store = PluginStore.get(app)
        if (store.plugins().none { it.grants(PluginPermission.Reminders) }) return
        thread(name = "plugin-reminders", isDaemon = true) {
            runCatching { onWake(app, System.currentTimeMillis()) }
                .onFailure { DebugLog.e(TAG, "re-arm at start failed", it) }
        }
    }

    /** The wake-up fired: show what is due, then register the next. */
    fun onWake(context: Context, now: Long) {
        val app = context.applicationContext
        val store = PluginStore.get(app)
        val on = store.subsystemEnabled()
        for (plugin in store.plugins()) {
            val file = store.remindersFile(plugin.id)?.takeIf { it.isFile } ?: continue
            val due = PluginReminders(file).takeDue(now)
            if (due.isEmpty()) continue
            if (!on || !plugin.enabled || !plugin.grants(PluginPermission.Reminders)) continue
            for (reminder in due) post(app, plugin, reminder)
        }
        rearm(app)
    }

    // ---- the notification -------------------------------------------------

    /**
     * The plugin's name is the title, always, and the reminder's text is the
     * body. A plugin chooses the words under its name, never the name: a
     * reminder that could title itself "Google" or "Your bank" would be a
     * phishing tool with a to-do list attached.
     */
    private fun post(context: Context, plugin: InstalledPlugin, reminder: PluginReminder) {
        if (!WmNotifications.canPost(context)) return
        ensureChannel(context)
        val id = NotificationIds.forPluginReminder(plugin.id, reminder.id)
        val open = PendingIntent.getActivity(
            context,
            id,
            MainActivityContract.intent(context, "plugin/${plugin.id}"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(CommonR.drawable.common_ic_notification)
            .setContentTitle(plugin.name)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setWhen(reminder.at)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // canPost has ruled the refusal out; this still throws on the devices
        // where the platform disagrees, and nothing here may crash over it.
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /**
     * `notify.show` from a running API 5 plugin: a notification now, in the
     * reminders' channel, under the same rule as a reminder. The plugin's name
     * is the title; its [title], if any, goes in the header beside the app's
     * name, and [text] is the body. Showing the same [noticeId] again replaces it.
     */
    fun postNotice(context: Context, plugin: InstalledPlugin, noticeId: String, title: String?, text: String): Boolean {
        val app = context.applicationContext
        if (!WmNotifications.canPost(app)) return false
        ensureChannel(app)
        val id = NotificationIds.forPluginReminder(plugin.id, NOTICE_PREFIX + noticeId)
        val open = PendingIntent.getActivity(
            app,
            id,
            MainActivityContract.intent(app, "plugin/${plugin.id}"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(CommonR.drawable.common_ic_notification)
            .setContentTitle(plugin.displayName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .apply { if (!title.isNullOrBlank()) setSubText(title) }
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return runCatching { NotificationManagerCompat.from(app).notify(id, notification) }.isSuccess
    }

    /** `notify.cancel`: takes one of [postNotice]'s back down. Nothing happens for one never shown. */
    fun cancelNotice(context: Context, pluginId: String, noticeId: String) {
        WmNotifications.cancel(context, NotificationIds.forPluginReminder(pluginId, NOTICE_PREFIX + noticeId))
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.core_plugins_reminders_channel_title),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = context.getString(R.string.core_plugins_reminders_channel_body)
        runCatching { manager.createNotificationChannel(channel) }
    }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_WAKE,
            Intent(context, PluginReminderReceiver::class.java).setAction(ACTION_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Not a [com.wasimaster.wmkeyboard.core.notify.NotificationKind]: a kind is
     * a switch in the app's notification settings, and this one already has
     * two of its own, the plugin's permission and its on switch. Android's own
     * channel settings turn all of them off at once.
     */
    const val CHANNEL_ID = "plugin_reminders"

    /** Keeps a notice's id apart from a reminder's of the same name. */
    private const val NOTICE_PREFIX = "notify:"

    internal const val ACTION_WAKE = "com.wasimaster.wmkeyboard.plugins.REMINDER_WAKE"
    private const val REQUEST_WAKE = 1
    private const val TAG = "PluginReminders"
}

/**
 * Where the reminders' wake-up lands. Declared in the app manifest, not
 * exported, and not direct-boot aware: plugins and their reminders live in
 * storage that is locked until the first unlock.
 */
class PluginReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PluginReminderAlarms.ACTION_WAKE) return
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "plugin-reminders", isDaemon = true) {
            try {
                PluginReminderAlarms.onWake(app, System.currentTimeMillis())
            } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                DebugLog.e("PluginReminders", "wake-up failed", failure)
            } finally {
                pending.finish()
            }
        }
    }
}

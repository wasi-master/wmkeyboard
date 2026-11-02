package com.wasimaster.wmkeyboard.core.settings

import android.app.PendingIntent
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.NotificationKind
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.settings.R

/**
 * The automatic backup that did not happen.
 *
 * It is the one failure in this app with no natural place to appear. The
 * backup runs from a job, with no screen: the outcome is recorded in the
 * settings, and read back only by someone who opens the Backup screen — which
 * is to say, by someone who already suspects something is wrong. A revoked
 * folder grant or an expired cloud token silently stops every backup from that
 * day on, and the user finds out when they need the backup.
 *
 * Only the failures a person can do something about are worth a notification,
 * and each of them gets its own sentence: reconnect the destination, choose
 * another folder, free some space. See [AutoBackupRunner.fail] for what is
 * deliberately not announced.
 */
internal object BackupNotification {

    fun post(context: Context, reason: SinkError, destination: BackupDestination = BackupDestination.FOLDER) {
        WmNotifications.post(context, NotificationKind.BACKUP, NotificationIds.BACKUP) {
            setContentTitle(context.getString(R.string.core_settings_notify_backup_title))
            val body = context.getString(reason.bodyRes(destination))
            setContentText(body)
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setAutoCancel(true)
            val launch = WmNotifications.launchIntent(context) ?: return@post
            setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    /**
     * One sentence per failure, saying what to do rather than what happened.
     * The same four the Backup screen shows, in this module because this is
     * where they are read without a screen.
     */
    @StringRes
    private fun SinkError.bodyRes(destination: BackupDestination): Int = when (this) {
        SinkError.PERMISSION_LOST -> when {
            destination == BackupDestination.FOLDER -> R.string.core_settings_notify_backup_permission
            destination.signsIn -> R.string.core_settings_notify_backup_permission_account
            else -> R.string.core_settings_notify_backup_permission_server
        }
        SinkError.TARGET_MISSING -> if (destination == BackupDestination.FOLDER) {
            R.string.core_settings_notify_backup_target
        } else {
            R.string.core_settings_notify_backup_target_remote
        }
        SinkError.OUT_OF_SPACE -> R.string.core_settings_notify_backup_space
        SinkError.UNSAFE -> R.string.core_settings_notify_backup_unsafe
        SinkError.OUT_OF_MEMORY -> R.string.core_settings_notify_backup_memory
        SinkError.NOT_CONFIGURED, SinkError.IO -> R.string.core_settings_notify_backup_io
    }
}

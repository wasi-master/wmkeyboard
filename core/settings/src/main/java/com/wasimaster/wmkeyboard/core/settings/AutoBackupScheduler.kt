package com.wasimaster.wmkeyboard.core.settings

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * Keeps the platform's idea of when to back up in step with the user's.
 *
 * `JobScheduler` rather than WorkManager: this is one periodic job with no
 * chaining, no observers and no pre-API-23 story to tell, so WorkManager's
 * whole offer is unused, and it would cost a dependency that drags in Room and
 * an `androidx.startup` provider running at every process start. In a keyboard
 * that is cold-start latency on the path the user feels.
 *
 * Not `setPersisted`, which would need `RECEIVE_BOOT_COMPLETED`. The job is
 * instead re-established by whoever starts first after a reboot, and in a
 * keyboard that is the keyboard — within seconds of anyone typing.
 */
object AutoBackupScheduler {

    /** Arbitrary and permanent. Changing it orphans whatever is scheduled. */
    private const val JOB_ID = 20260807

    /** The one-off run [runNow] asks for; its own id so it never replaces the periodic job. */
    private const val JOB_ID_NOW = 20260923

    /** The frequent schedule's periodic job (#970), beside the main one. */
    private const val JOB_ID_FREQUENT = 20261027

    /** The one-off run [runExport] asks for. */
    private const val JOB_ID_EXPORT = 20261102

    /** How long an export may wait for the platform: nobody asked for it to be put off. */
    private const val EXPORT_DEADLINE_MS = 60_000L

    /** The job extra that makes a run the frequent schedule's. Read by the job service. */
    const val EXTRA_FREQUENT = "frequent"

    /**
     * The job extra that makes a run ignore the interval, as the Back up now
     * button does. Read by the job service in `:app`.
     */
    const val EXTRA_FORCE = "force"

    /** The job extra that makes a run an [AutomationExport] of these section ids. Read by the job service. */
    const val EXTRA_EXPORT_SECTIONS = "export_sections"

    /** The package that asked for the export, or empty. Read by the job service. */
    const val EXTRA_EXPORT_SENDER = "export_sender"

    /**
     * The job runs in `:app`, which a library module cannot name in code. Same
     * string-component approach `AppCatalog` uses for launching activities.
     * `AutoBackupSchedulerTest` reads the manifest and checks this still names
     * a service that exists, since the compiler cannot.
     */
    const val SERVICE_CLASS = "com.wasimaster.wmkeyboard.app.AutoBackupJobService"

    private const val HOUR_MS = 60L * 60L * 1000L

    /** Retry delay after a failure the job asked to have retried. */
    private const val BACKOFF_MS = 30L * 60L * 1000L

    /**
     * Only the destinations that go over the wire wait for a network; a SAF
     * folder is usually local storage, and an offline phone should still be
     * able to back up to its own card.
     *
     * A network destination with "Wi-Fi only" off still waits for *some*
     * network. Without that the job ran offline, the token refresh failed, and
     * the run was recorded against the destination.
     */
    fun networkTypeFor(settings: AutoBackupSettings): Int = when {
        // Mixed locations wait for the network: a run is one pass over all of
        // them, and the folder copy costs nothing to delay.
        settings.backupTargets.none { it.type.needsNetwork } -> JobInfo.NETWORK_TYPE_NONE
        settings.requireUnmetered -> JobInfo.NETWORK_TYPE_UNMETERED
        else -> JobInfo.NETWORK_TYPE_ANY
    }

    /**
     * Backs up once, as soon as the ticked locations' network rule allows, whether
     * or not the automatic backup is on. A job rather than a coroutine because
     * the caller (an automation intent) has seconds to live, and a backup to a
     * cloud destination can take longer. False when there is nowhere to back
     * up to.
     */
    fun runNow(context: Context, settings: AutoBackupSettings): Boolean {
        if (settings.backupTargets.isEmpty()) return false
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(JOB_ID_NOW, ComponentName(context.packageName, SERVICE_CLASS))
            .setRequiredNetworkType(networkTypeFor(settings))
            .setExtras(android.os.PersistableBundle().apply { putBoolean(EXTRA_FORCE, true) })
            .build()
        return runCatching { scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
    }

    /** An [AutomationExport] of [sections], a job for the reason [runNow] is. False when it could not be scheduled. */
    fun runExport(context: Context, sections: Set<ConfigBackup.Section>, sender: String): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(JOB_ID_EXPORT, ComponentName(context.packageName, SERVICE_CLASS))
            .setOverrideDeadline(EXPORT_DEADLINE_MS)
            .setExtras(
                android.os.PersistableBundle().apply {
                    putString(EXTRA_EXPORT_SECTIONS, sections.joinToString(",") { it.id })
                    putString(EXTRA_EXPORT_SENDER, sender)
                },
            )
            .build()
        return runCatching { scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
    }

    /**
     * Schedules, reschedules or cancels to match [settings].
     *
     * Safe to call as often as you like, and it is called from process start,
     * which matters more than it looks: handing `JobScheduler` a periodic job
     * restarts its period. Rescheduling unconditionally from the keyboard's
     * `onCreate` would mean a keyboard used more often than once a day never
     * reaches the end of a single period, and the backup never runs at all.
     * So an already-correct job is left exactly where it is.
     *
     * A pause is deliberately not part of this. Cancelling the job for a pause
     * and scheduling it again at the end would restart the period both times;
     * the job keeps its place and the run skips itself instead (see
     * [BackupPause]).
     */
    fun sync(context: Context, settings: AutoBackupSettings) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val on = settings.enabled && settings.backupTargets.isNotEmpty()
        syncJob(context, scheduler, settings, JOB_ID, on, settings.intervalHours, frequent = false)
        syncJob(
            context, scheduler, settings, JOB_ID_FREQUENT,
            on && settings.frequent.enabled, settings.frequent.intervalHours, frequent = true,
        )
    }

    /** One schedule's job, kept as [sync] describes. */
    private fun syncJob(
        context: Context,
        scheduler: JobScheduler,
        settings: AutoBackupSettings,
        id: Int,
        wanted: Boolean,
        intervalHours: Int,
        frequent: Boolean,
    ) {
        val pending = runCatching { scheduler.getPendingJob(id) }.getOrNull()

        if (!wanted) {
            if (pending != null) runCatching { scheduler.cancel(id) }
            return
        }

        val intervalMs = intervalHours.coerceAtLeast(1) * HOUR_MS
        val networkType = networkTypeFor(settings)
        // The constraints belong in this comparison as much as the period does.
        // Left out, turning the charging requirement off would write the
        // setting, leave the old job in place, and change nothing the user can
        // see — the worst shape a settings bug takes.
        if (pending != null &&
            pending.intervalMillis == intervalMs &&
            pending.isRequireCharging == settings.requireCharging &&
            pending.networkType == networkType
        ) {
            return
        }

        val job = JobInfo.Builder(id, ComponentName(context.packageName, SERVICE_CLASS))
            .setPeriodic(intervalMs)
            // Deliberately without `setRequiresDeviceIdle`. Idle plus charging
            // is what makes the platform's own backups so hard to predict, and
            // the work here is a few seconds of file copying.
            .setRequiresCharging(settings.requireCharging)
            .setRequiredNetworkType(networkType)
            .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_LINEAR)
            .apply {
                if (frequent) setExtras(android.os.PersistableBundle().apply { putBoolean(EXTRA_FREQUENT, true) })
            }
            .build()
        runCatching { scheduler.schedule(job) }
    }
}

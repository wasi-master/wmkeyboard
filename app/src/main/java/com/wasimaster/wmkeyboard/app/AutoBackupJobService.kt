package com.wasimaster.wmkeyboard.app

import android.app.job.JobParameters
import android.app.job.JobService
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.AutomationExport
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where an automatic backup actually runs.
 *
 * The work is small but it must not be interrupted halfway, and the obvious
 * alternative — doing it from the keyboard when the keyboard is hidden — puts it
 * in the worst possible place: a hidden IME has just left the visible process
 * band, so it is the most likely thing on the device to be killed, and the
 * keyboard's own scope is cancelled the moment the user switches input method.
 * A job gets a wakelock and a process the system intends to keep for the
 * duration.
 *
 * All the ordering and every safety check lives in [AutoBackupRunner]; this is
 * only the thing the platform can call.
 */
class AutoBackupJobService : JobService() {

    /**
     * Not the service's lifecycle scope, because there is no such thing here —
     * [onStopJob] cancels this deliberately and nothing else should.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var running: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        running = scope.launch {
            val exportSections = params?.extras?.getString(AutoBackupScheduler.EXTRA_EXPORT_SECTIONS)
            if (exportSections != null) {
                AutomationExport.run(
                    applicationContext,
                    SettingsRepository(applicationContext),
                    AutomationExport.parseSections(exportSections).orEmpty(),
                    params?.extras?.getString(AutoBackupScheduler.EXTRA_EXPORT_SENDER).orEmpty(),
                )
                jobFinished(params, false)
                return@launch
            }
            // A one-off run from an automation intent forces, as the Back up
            // now button does; the periodic job keeps to its interval.
            val force = params?.extras?.getBoolean(AutoBackupScheduler.EXTRA_FORCE, false) == true
            // The frequent schedule's job says so (#970); everything else is the main one.
            val frequent = params?.extras?.getBoolean(AutoBackupScheduler.EXTRA_FREQUENT, false) == true
            AutoBackupRunner.run(applicationContext, SettingsRepository(applicationContext), force = force, frequent = frequent)
            // Always false: a periodic job comes round again on its own, and
            // asking for a reschedule on one is ignored. A failure worth showing
            // the user is already recorded in the settings by the runner.
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        running?.cancel()
        // Let the next period have a go rather than waiting a whole cycle. A run
        // cut off partway leaves nothing behind: the destination is only written
        // after a staged file has been read back and parsed.
        return true
    }
}

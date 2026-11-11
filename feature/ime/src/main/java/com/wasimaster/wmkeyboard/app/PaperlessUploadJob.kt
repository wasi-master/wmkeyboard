package com.wasimaster.wmkeyboard.app

import android.app.job.JobParameters
import android.app.job.JobService
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.paperless.PaperlessQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Uploads the scans waiting for the user's Paperless server (#1198). The
 * system runs it once the network the queue asked for is there, the keyboard
 * open or not; a run that could not reach the server asks to be run again,
 * and the system backs off between tries. See [PaperlessQueue].
 */
class PaperlessUploadJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        running = scope.launch {
            val settings = SettingsRepository(applicationContext).settings.first().scanner.paperless
            val again = runCatching { PaperlessQueue.drain(applicationContext, settings) }.getOrDefault(true)
            jobFinished(params, again)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        running?.cancel()
        // The network went: whatever is still waiting goes on the next run.
        return true
    }
}

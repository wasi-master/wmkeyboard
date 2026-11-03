package com.wasimaster.wmkeyboard.core.settings.sync

import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sink.ProfileCode
import com.wasimaster.wmkeyboard.core.settings.sink.ProfileLink
import com.wasimaster.wmkeyboard.core.settings.targets
import com.wasimaster.wmkeyboard.core.settings.keepLocalGroups
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The process-wide half of "soon after a change": keeps the jobs in line with
 * the settings, pushes a minute after a synced setting changes, and pulls
 * when the process starts if the last pass is old.
 *
 * Started once from the application, so it covers the keyboard and the
 * settings app alike: they share one process. What it watches is the settings
 * store only. Stores kept in files (the dictionary, snippets) are caught by
 * the hourly pull and by the pull at start.
 */
object SyncWatcher {

    /** A process start this long after the last pass pulls straight away. */
    private const val STALE_MS = 5L * 60L * 1000L

    /**
     * A handler, because this starts in `Application.onCreate`, and that runs
     * before the first unlock too: the keyboard is direct-boot aware. Anything
     * that throws in here must cost a log line, never the keyboard's process.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> BackupLog.w("sync watcher", failure) },
    )

    @Volatile
    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(context: Context, repository: SettingsRepository) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            repository.settings
                .map { it.autoBackup }
                .distinctUntilChanged { a, b -> a.sync == b.sync && a.locations == b.locations }
                .collect { SyncScheduler.sync(app, it) }
        }
        scope.launch {
            // The fingerprint reads the credential-protected store, which is not
            // there before the first unlock. The settings flow re-emits when the
            // repository moves to the real store, which is when this starts.
            repository.settings
                .map {
                    val sync = it.autoBackup.sync
                    Triple(sync.includeSecrets, sync.keepLocalGroups, DirectBoot.isUserUnlocked(app))
                }
                .distinctUntilChanged()
                .flatMapLatest { (secrets, keepLocal, unlocked) ->
                    if (unlocked) repository.syncFingerprint(secrets, keepLocal).drop(1) else emptyFlow()
                }
                .catch { BackupLog.w("sync fingerprint", it) }
                .collect { SyncScheduler.requestSoon(app, repository.settings.first().autoBackup) }
        }
        scope.launch {
            val auto = repository.settings.first().autoBackup
            if (System.currentTimeMillis() - auto.sync.lastRunAtMs > STALE_MS) {
                SyncScheduler.requestSoon(app, auto, delayMs = 0L)
            }
        }
        scope.launch {
            // The link to the copy in another profile (#1017), while sync goes
            // through one. After the unlock only: its mailbox is in
            // credential-protected storage, as the settings it carries are.
            repository.settings
                .map { if (DirectBoot.isUserUnlocked(app)) it.autoBackup.profileCode() else null }
                .distinctUntilChanged()
                .collect { code -> ProfileLink.update(app, code) { SyncRunner.run(app, repository) } }
        }
    }

    /** The code of the Other profile location sync goes through, or null when it goes through none. */
    private fun AutoBackupSettings.profileCode(): String? =
        if (!sync.enabled) {
            null
        } else {
            sync.targets(locations).firstOrNull { it.type == BackupDestination.PROFILE }
                ?.let { ProfileCode.normalize(it.profileCode) }
        }
}

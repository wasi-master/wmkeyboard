package com.wasimaster.wmkeyboard.core.settings

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSink
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.DriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.DriveAuth
import com.wasimaster.wmkeyboard.core.settings.sink.DropboxSink
import com.wasimaster.wmkeyboard.core.settings.sink.FtpSink
import com.wasimaster.wmkeyboard.core.settings.sink.GitSink
import com.wasimaster.wmkeyboard.core.settings.sink.ImapSink
import com.wasimaster.wmkeyboard.core.settings.sink.OneDriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.SafFolderSink
import com.wasimaster.wmkeyboard.core.settings.sink.SftpSink
import com.wasimaster.wmkeyboard.core.settings.sink.SmbSink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.WebDavSink
import com.wasimaster.wmkeyboard.core.settings.sink.sinkErrorOf
import com.wasimaster.wmkeyboard.core.settings.sink.BoxSink
import com.wasimaster.wmkeyboard.core.settings.sink.ICloudSink
import com.wasimaster.wmkeyboard.core.settings.sink.MegaSink
import com.wasimaster.wmkeyboard.core.settings.sink.NfsSink
import com.wasimaster.wmkeyboard.core.settings.sink.PCloudSink
import com.wasimaster.wmkeyboard.core.settings.sink.QnapSink
import com.wasimaster.wmkeyboard.core.settings.sink.SynologySink
import com.wasimaster.wmkeyboard.core.settings.sink.proton.ProtonDriveSink
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Takes one automatic backup, in the order that cannot lose data.
 *
 * The ordering is the whole point, so it is worth stating plainly. A backup is
 * staged locally, renamed into place locally, **read back and parsed**, and
 * only then copied to the destination — and only after *that* does anything
 * older get deleted. Every one of those steps exists because of a way the
 * obvious version goes wrong:
 *
 * - Writing straight to the destination cannot be atomic. A folder behind a
 *   `DocumentsProvider` may not support rename, and a process killed mid-write
 *   leaves a file that is the right shape and the wrong length.
 * - A half-written bundle that nobody reads back looks exactly like a good one
 *   in a folder listing. Rotation would then count it as a generation and
 *   delete a real one to make room.
 * - A backup taken while the device is locked is not a small backup, it is an
 *   almost empty one: the file-backed sections live in credential-encrypted
 *   storage and simply are not there. Written and rotated, a few lock-screen
 *   sessions would evict every good generation with nothing.
 *
 * One run at a time, process-wide, because "Back up now" and the scheduled job
 * can land together and they would otherwise fight over the staging file.
 */
object AutoBackupRunner {

    /**
     * The least the embedded files may take before sections are dropped.
     *
     * Not a tuning knob so much as a floor under [maxEmbeddedBytes], the
     * ceiling on how much of the heap one export may ask for. See
     * [SettingsRepository.embeddedByteEstimate].
     */
    private const val MIN_EMBEDDED_BYTES = 16L * 1024 * 1024

    /**
     * How much embedded file data one export may carry: an eighth of the heap
     * this process may grow to, never under [MIN_EMBEDDED_BYTES]. The bundle is
     * built as one string and, when encrypted, copied again, so the eighth
     * leaves room for those copies and for the keyboard itself.
     */
    private fun maxEmbeddedBytes(): Long = maxOf(MIN_EMBEDDED_BYTES, Runtime.getRuntime().maxMemory() / 8)

    /**
     * The heavy sections to leave out of a bundle of [requested] so the rest
     * fits under [limit]: the largest first, one at a time, until it does.
     *
     * Issue #692: every heavy section went at once when their sum was over the
     * line, so a big sticker collection also took the word lists and the
     * vocabulary with it, though those were a few hundred kilobytes.
     */
    fun sectionsToDrop(
        requested: Set<ConfigBackup.Section>,
        limit: Long,
        estimate: (ConfigBackup.Section) -> Long,
    ): Set<ConfigBackup.Section> {
        val sizes = (requested intersect HEAVY_SECTIONS).associateWith(estimate)
        var total = sizes.values.sum()
        val dropped = LinkedHashSet<ConfigBackup.Section>()
        for ((section, size) in sizes.entries.sortedByDescending { it.value }) {
            if (total <= limit) break
            dropped += section
            total -= size
        }
        return dropped
    }

    /**
     * The heavy sections a file export from the settings screen leaves out.
     *
     * The same rule as the automatic backup's with twice the room: the export
     * is written as it is encoded rather than built as a string first, and it
     * runs while the user waits for it rather than inside a keyboard that is
     * typing. Without any ceiling a large sticker collection made the export
     * run out of memory and report only that the file could not be written
     * (#693).
     */
    fun exportSectionsToDrop(
        repository: SettingsRepository,
        requested: Set<ConfigBackup.Section>,
    ): Set<ConfigBackup.Section> =
        sectionsToDrop(requested, maxEmbeddedBytes() * 2) { repository.embeddedByteEstimate(setOf(it)) }

    /** Sections that carry whole files, and so are the ones worth dropping. */
    private val HEAVY_SECTIONS = setOf(
        ConfigBackup.Section.STICKERS,
        ConfigBackup.Section.ICONS,
        ConfigBackup.Section.WORDLISTS,
        ConfigBackup.Section.VOCAB,
    )

    private const val STAGING_DIR = "backup"


    /** What one run did, for the settings screen and for the job's retry choice. */
    sealed interface Outcome {

        /**
         * At least one location has the backup. [name] is what the file ended
         * up called at the first of them, which is not always what we asked.
         * [failed] names the locations that did not get it, by id.
         */
        data class Done(
            val name: String,
            val skipped: Set<ConfigBackup.Section>,
            val failed: Map<String, SinkError> = emptyMap(),
        ) : Outcome

        /** Nothing to do: turned off, paused, no folder, or the interval has not elapsed. */
        data object Skipped : Outcome

        /**
         * The device was locked, so the bundle would have been nearly empty.
         * Not recorded as an error: nothing is wrong, this was the wrong moment.
         */
        data object Locked : Outcome

        /** [reason] is worth retrying only when it is [SinkError.IO]. */
        data class Failed(val reason: SinkError) : Outcome
    }

    /**
     * Runs a backup if one is due.
     *
     * [force] is the "Back up now" button: it skips the interval check, the
     * pause and the enabled switch, but not the lock check or the destination
     * check, because
     * those are about whether a backup can be any good rather than whether one
     * was asked for.
     */
    suspend fun run(
        context: Context,
        repository: SettingsRepository,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        /** The frequent schedule's run (#970) rather than the main one's. */
        frequent: Boolean = false,
    ): Outcome = BackupGate.mutex.withLock {
        // Files this run's traffic as scheduled in the network activity log,
        // unless the user pressed "Back up now". The gate holds one run at a time.
        BackupTraffic.unattended = !force
        try {
            runLocked(context, repository, force, nowMs, frequent)
        } finally {
            BackupTraffic.unattended = false
        }
    }

    private suspend fun runLocked(
        context: Context,
        repository: SettingsRepository,
        force: Boolean,
        nowMs: Long,
        frequent: Boolean,
    ): Outcome {
        val appContext = context.applicationContext
        val settings = repository.settings.first().autoBackup
        // First, and before anything is listed or built: while paused, the
        // periodic job still fires (see BackupPause), and each of those
        // firings should cost one settings read.
        if (!force && settings.paused(nowMs)) return Outcome.Skipped
        val targets = settings.backupTargets

        if (!force && !settings.enabled) return Outcome.Skipped
        if (frequent && !settings.frequent.enabled) return Outcome.Skipped
        if (targets.isEmpty()) return Outcome.Skipped
        val due = if (frequent) isDue(settings.frequent.lastRunAtMs, settings.frequent.intervalHours, nowMs) else isDue(settings, nowMs)
        if (!force && !due) return Outcome.Skipped
        // Before anything else. A locked device cannot produce a real bundle,
        // and an unreal one is worse than none.
        if (!DirectBoot.isUserUnlocked(appContext)) return Outcome.Locked

        BackupLog.d("run force=$force frequent=$frequent locations=${targets.map { "${it.type.id}:${it.id}" }}")
        val plan = if (frequent) Plan.frequent(settings) else Plan.main(settings)
        val outcome = runCancellable {
            backUp(appContext, repository, targets, settings, plan, nowMs, announce = !force)
        }
        return outcome.getOrElse { failure ->
            fail(appContext, repository, failure, nowMs, announce = !force, location = null, frequent = frequent)
        }
    }

    /**
     * What one run writes: the main schedule's choices, or the frequent
     * one's (#970). The two share the locations, the passphrase and the
     * staging; everything here is what differs.
     */
    private data class Plan(
        val sections: Set<ConfigBackup.Section>,
        val encrypt: Boolean,
        val includeSecrets: Boolean,
        val keep: Int,
        val staggered: Boolean,
        val frequent: Boolean,
    ) {
        companion object {
            fun main(settings: AutoBackupSettings) = Plan(
                sections = settings.sectionSet,
                encrypt = settings.encrypt && settings.passphrase.isNotEmpty(),
                includeSecrets = settings.backupIncludeSecrets,
                keep = settings.keep,
                staggered = settings.staggered,
                frequent = false,
            )

            // Never the keys: see [FrequentBackup].
            fun frequent(settings: AutoBackupSettings) = Plan(
                sections = settings.frequent.sectionSet,
                encrypt = settings.frequent.encrypt && settings.passphrase.isNotEmpty(),
                includeSecrets = false,
                keep = settings.frequent.keep,
                staggered = false,
                frequent = true,
            )
        }
    }

    /**
     * The sink for [location], or null when this build cannot reach it.
     *
     * Only Drive, Dropbox, OneDrive and Box can be missing: Drive on a build
     * with no Play services compiled in ([DriveAuth.provider] is what `:app`
     * fills in behind that seam), the other three on a build with no client id. A null
     * is that build, not a failure.
     */
    fun sinkFor(context: Context, location: BackupLocation): BackupSink? =
        when (location.type) {
            BackupDestination.FOLDER ->
                SafFolderSink(context, Uri.parse(location.folderUri))
            BackupDestination.WEBDAV -> WebDavSink(
                baseUrl = location.webDavUrl,
                user = location.webDavUser,
                password = location.webDavPassword,
            )
            BackupDestination.DRIVE -> DriveAuth.provider?.let {
                DriveSink(it, location.driveSpace, location.driveFolder)
            }
            BackupDestination.S3 -> S3Sink(location.s3)
            BackupDestination.FTP -> FtpSink(location.ftp)
            BackupDestination.DROPBOX -> BackupClients.dropbox()?.let {
                DropboxSink(location.refreshToken, it)
            }
            BackupDestination.ONEDRIVE -> BackupClients.oneDrive()?.let {
                OneDriveSink(location.refreshToken, it) { rotated ->
                    storeRefreshToken(context, location.id, rotated)
                }
            }
            BackupDestination.SFTP -> SftpSink(location.sftp) { key ->
                storeHostKey(context, location.id, key)
            }
            BackupDestination.SMB -> SmbSink(location.smb)
            BackupDestination.GIT -> GitSink(location.git, BackupInstall.deviceLabel(context))
            BackupDestination.IMAP -> ImapSink(location.imap)
            BackupDestination.NEARBY -> com.wasimaster.wmkeyboard.core.settings.sink.NearbySync
                .dir(context, location.nearbyDeviceId)
                ?.let { com.wasimaster.wmkeyboard.core.settings.sink.NearbySink(it, location.nearbyDeviceId) }
            BackupDestination.BOX -> BackupClients.box()?.let {
                BoxSink(location.refreshToken, it, location.box) { rotated ->
                    storeRefreshToken(context, location.id, rotated)
                }
            }
            BackupDestination.PCLOUD -> PCloudSink(location.pcloud)
            BackupDestination.SYNOLOGY -> SynologySink(location.nas) { pin -> storeCertPin(context, location.id, pin) }
            BackupDestination.QNAP -> QnapSink(location.nas) { pin -> storeCertPin(context, location.id, pin) }
            BackupDestination.NFS -> NfsSink(location.nfs)
            BackupDestination.MEGA -> MegaSink(location.mega)
            BackupDestination.ICLOUD -> ICloudSink(location.icloud) { cookies, trust ->
                storeLocation(context, location.id, "iCloud session") {
                    it.copy(icloud = it.icloud.copy(cookies = cookies, trustToken = trust.ifEmpty { it.icloud.trustToken }))
                }
            }
            BackupDestination.PROTON -> ProtonDriveSink(location.proton) { access, refresh ->
                storeLocation(context, location.id, "Proton tokens") {
                    it.copy(proton = it.proton.copy(accessToken = access, refreshToken = refresh))
                }
            }
        }

    /**
     * Saves the certificate the first connection to a NAS saw, so every later
     * one is held to it, as [storeHostKey] does for SFTP. Only fills an empty
     * slot.
     */
    private fun storeCertPin(context: Context, id: String, pin: String) {
        storeLocation(context, id, "NAS certificate") {
            if (it.nas.certPin.isEmpty()) it.copy(nas = it.nas.copy(certPin = pin)) else it
        }
    }

    /**
     * Saves what a sink learned mid-run: a rotated token, a new session.
     * Blocking for the reason [storeRefreshToken] gives.
     */
    private fun storeLocation(context: Context, id: String, what: String, change: (BackupLocation) -> BackupLocation) {
        runCatching {
            runBlocking { SettingsRepository(context.applicationContext).updateBackupLocation(id, change) }
        }.onFailure { BackupLog.w("could not store the $what for $id", it) }
    }

    /**
     * Saves the host key the first SFTP connection saw, so every later one is
     * held to it. Blocking for the same reason as [storeRefreshToken]. Only
     * fills an empty slot: a key already there is never replaced from here.
     */
    private fun storeHostKey(context: Context, id: String, key: String) {
        runCatching {
            runBlocking {
                SettingsRepository(context.applicationContext).updateBackupLocation(id) {
                    if (it.sftp.hostKey.isEmpty()) it.copy(sftp = it.sftp.copy(hostKey = key)) else it
                }
            }
        }.onFailure { BackupLog.w("could not store the SFTP host key for $id", it) }
    }

    /**
     * Saves the refresh token a service sent in place of [BackupLocation.refreshToken].
     *
     * Blocking, and before the call that needed the token goes on: a run the
     * system stops right after would otherwise leave only the old token saved.
     * The old token keeps working until its own expiry, so a lost write costs
     * nothing at once, but three months later it is a location that has to be
     * signed in to again.
     */
    private fun storeRefreshToken(context: Context, id: String, token: String) {
        runCatching {
            runBlocking {
                SettingsRepository(context.applicationContext).updateBackupLocation(id) {
                    it.copy(refreshToken = token)
                }
            }
        }.onFailure { BackupLog.w("could not store the new refresh token for $id", it) }
    }

    /**
     * Whether enough wall-clock time has passed since the last good run.
     *
     * Pure, and public so the tests in `:app` can reach it — the arithmetic has
     * two edge cases that are easy to get wrong and impossible to observe from
     * outside a run.
     */
    fun isDue(settings: AutoBackupSettings, nowMs: Long): Boolean =
        isDue(settings.lastRunAtMs, settings.intervalHours, nowMs)

    /** [isDue] for any schedule: its last good run and its interval. */
    fun isDue(last: Long, intervalHours: Int, nowMs: Long): Boolean {
        // A last-run time in the future is a clock that moved, or a bundle from
        // another device that got through. Either way, waiting for it to come
        // round again could mean waiting for weeks; take one now instead.
        if (last <= 0L || last > nowMs) return true
        return nowMs - last >= intervalHours.coerceAtLeast(1) * 60L * 60L * 1000L
    }

    private suspend fun backUp(
        appContext: Context,
        repository: SettingsRepository,
        targets: List<BackupLocation>,
        settings: AutoBackupSettings,
        plan: Plan,
        nowMs: Long,
        announce: Boolean,
    ): Outcome {
        val encrypt = plan.encrypt
        val requested = plan.sections
        if (requested.isEmpty()) return Outcome.Skipped

        // Drop the bulky sections before building anything, not after: the
        // failure being avoided is an allocation, so noticing it in the
        // finished string would be noticing it too late.
        val skipped = sectionsToDrop(requested, maxEmbeddedBytes()) { section ->
            repository.embeddedByteEstimate(setOf(section))
        }
        val sections = requested - skipped
        if (sections.isEmpty()) return Outcome.Skipped

        val version = appVersion(appContext)

        // Staged and verified once, then copied to every location: the
        // expensive part and the safety check do not scale with how many
        // places the user wants a copy in. Written straight to the file, never
        // held whole as a string, because this runs inside the keyboard's own
        // process and whatever heap the keyboard has left (#1080).
        val staged = stage(appContext, settings, encrypt) { out ->
            repository.exportConfigTo(
                out = out,
                sections = sections,
                // The user's call, on the automatic backup's own switch. The screen
                // strongly suggests a passphrase before it goes on, and warns when
                // it is on without one; it does not quietly drop keys the user
                // asked to keep.
                includeSecrets = plan.includeSecrets,
                appVersion = version.first,
                appVersionName = version.second,
                // Never the locations themselves. A backup at one location holding
                // the passwords and sign-ins for all of them would hand every one
                // of them to whoever can open that one.
                excludeKeys = setOf(SettingsBackup.AUTO_BACKUP_LOCATIONS),
            )
        }
        try {
            if (!verify(staged, settings, encrypt)) {
                return Outcome.Failed(SinkError.IO)
            }
            val installId = BackupInstall.id(appContext)
            val name = AutoBackupNaming.name(
                stampMs = nowMs,
                encrypted = encrypt,
                installId = installId,
                device = BackupInstall.deviceLabel(appContext),
                group = settings.sync.group,
                frequent = plan.frequent,
            )
            val mime =
                if (encrypt) ConfigBackup.ENCRYPTED_MIME_TYPE else ConfigBackup.MIME_TYPE

            var firstName: String? = null
            val failed = LinkedHashMap<String, SinkError>()
            for (location in targets) {
                val written = copyTo(appContext, location, name, mime, staged, plan.keep, plan.staggered, installId, plan.frequent)
                written.onSuccess { entry ->
                    if (firstName == null) firstName = entry.name
                    // A location's status is the main schedule's: what the
                    // screen calls its last backup is the whole one.
                    if (!plan.frequent) {
                        repository.setLocationStatus(location.id) { it.copy(backupAtMs = nowMs, backupError = "") }
                    }
                }.onFailure { failure ->
                    val reason = (failure as? BackupSinkException)?.reason ?: SinkError.IO
                    BackupLog.w("location ${location.id} failed: $reason", failure)
                    failed[location.id] = reason
                    if (!plan.frequent) repository.setLocationStatus(location.id) { it.copy(backupError = reason.name) }
                    if (announce && reason != SinkError.NOT_CONFIGURED) {
                        BackupNotification.post(appContext, reason, location.type)
                    }
                }
            }

            val landed = firstName
                ?: return fail(
                    appContext,
                    repository,
                    BackupSinkException(failed.values.firstOrNull() ?: SinkError.IO),
                    nowMs,
                    announce = false,
                    location = null,
                    frequent = plan.frequent,
                )
            // The run counts as done when any location has it: the schedule is
            // about whether a recent copy exists somewhere, and the failures are
            // recorded per location for the screen to show.
            if (plan.frequent) {
                repository.setFrequentBackupOutcome(ranAtMs = nowMs, error = "")
            } else {
                repository.setAutoBackupOutcome(ranAtMs = nowMs, error = "")
            }
            BackupLog.d("done $landed (${staged.length()} B) skipped=$skipped failed=$failed")
            return Outcome.Done(landed, skipped, failed)
        } finally {
            staged.delete()
        }
    }

    /**
     * One location's share of a run: readiness, the copy, then rotation.
     * Rotation only after the copy is confirmed, as ever: it is the one step
     * that destroys a generation.
     */
    private suspend fun copyTo(
        appContext: Context,
        location: BackupLocation,
        name: String,
        mime: String,
        staged: File,
        keep: Int,
        staggered: Boolean,
        installId: String,
        frequent: Boolean,
    ): Result<com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry> {
        val sink = sinkFor(appContext, location)
            ?: return Result.failure(BackupSinkException(SinkError.NOT_CONFIGURED))
        sink.readiness().exceptionOrNull()?.let { return Result.failure(it) }
        val written = sink.write(name, mime) { out -> staged.inputStream().use { it.copyTo(out) } }
        if (written.isSuccess) rotate(sink, keep, staggered, installId, frequent)
        return written
    }

    /**
     * Writes the bundle to local storage and renames it into place.
     *
     * The rename is the atomic step the rest of the app already relies on, and
     * it is available here because this is an ordinary file. It is what makes
     * [verify] meaningful: without it, a reader could see a file still being
     * written and call the short read corruption.
     */
    private suspend fun stage(
        appContext: Context,
        settings: AutoBackupSettings,
        encrypt: Boolean,
        write: suspend (OutputStream) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, STAGING_DIR).apply { mkdirs() }
        val part = File(dir, "staging.part")
        val staged = File(dir, "staging")
        part.delete()
        staged.delete()

        try {
            part.outputStream().buffered().use { out ->
                if (encrypt) {
                    // The cipher's doFinal runs at the close, after [write] has
                    // returned and let go of what it built.
                    BackupCrypto.encrypting(
                        out = out,
                        passphrase = settings.passphrase.toCharArray(),
                        salt = Base64.decode(settings.kdfSalt, Base64.NO_WRAP),
                    ).buffered().use { write(it) }
                } else {
                    write(out)
                }
            }
        } catch (failure: Throwable) {
            part.delete()
            throw failure
        }
        if (!part.renameTo(staged)) throw BackupSinkException(SinkError.IO)
        staged
    }

    /**
     * Reads the staged file back to its last byte, and for an encrypted one
     * checks its tag.
     *
     * Not paranoia about the disk: this is the check that a truncated or
     * garbled generation never reaches the destination, and so never gets
     * counted as the reason an older one can be deleted. It does not build the
     * bundle again, which is what used to run a busy keyboard out of heap at
     * the very end of a run (#1080).
     */
    private suspend fun verify(
        staged: File,
        settings: AutoBackupSettings,
        encrypt: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        if (encrypt) {
            val bytes = staged.inputStream().use {
                BackupCrypto.decryptBytes(it, settings.passphrase.toCharArray())
            } ?: return@withContext false
            ConfigBackup.isComplete(bytes.inputStream())
        } else {
            staged.inputStream().use { ConfigBackup.isComplete(it.buffered()) }
        }
    }

    /**
     * The running app's version, read from the package rather than taken from
     * a `BuildConfig` this module cannot see. It is stamped into the bundle so
     * a restore can tell it came from a newer app than the one reading it.
     */
    private fun appVersion(appContext: Context): Pair<Int, String> = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode to info.versionName.orEmpty()
    }.getOrDefault(0 to "")

    private suspend fun rotate(sink: BackupSink, keep: Int, staggered: Boolean, installId: String, frequent: Boolean) {
        // Backups only, and only this schedule's (#970). A sink lists sync
        // files too, and a sync file or the other schedule's backup counted
        // here would be a generation to delete.
        val entries = sink.list().getOrNull()?.filter { AutoBackupNaming.isOurs(it.name, frequent) } ?: return
        val doomed = AutoBackupNaming.rotation(entries, keep, installId, staggered)
        BackupLog.d("rotate: ${entries.size} listed, keep $keep, deleting ${doomed.map { it.name }}")
        for (entry in doomed) {
            sink.delete(entry)
        }
    }

    /**
     * Records a failed run, and says so in the shade when nobody was watching.
     *
     * [announce] is false for the "Back up now" button: the user is looking at
     * the screen that is about to show the same sentence, and a notification
     * for something they just watched fail is the kind that teaches people to
     * turn the channel off. [SinkError.NOT_CONFIGURED] is never announced
     * either — no destination is a setting, not a fault.
     */
    private suspend fun fail(
        context: Context,
        repository: SettingsRepository,
        failure: Throwable,
        nowMs: Long,
        announce: Boolean,
        location: BackupLocation?,
        frequent: Boolean = false,
    ): Outcome {
        val reason = sinkErrorOf(failure)
        BackupLog.w("failed: $reason", failure)
        if (frequent) {
            repository.setFrequentBackupOutcome(ranAtMs = nowMs, error = reason.name)
        } else {
            repository.setAutoBackupOutcome(ranAtMs = nowMs, error = reason.name)
        }
        if (announce && reason != SinkError.NOT_CONFIGURED) {
            BackupNotification.post(context, reason, location?.type ?: BackupDestination.FOLDER)
        }
        return Outcome.Failed(reason)
    }
}

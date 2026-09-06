package com.wasimaster.wmkeyboard.core.settings

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSink
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.DriveAppDataSink
import com.wasimaster.wmkeyboard.core.settings.sink.DriveAuth
import com.wasimaster.wmkeyboard.core.settings.sink.DropboxSink
import com.wasimaster.wmkeyboard.core.settings.sink.FtpSink
import com.wasimaster.wmkeyboard.core.settings.sink.OneDriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.SafFolderSink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.WebDavSink
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
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
     * The point past which the image sections are dropped rather than embedded.
     *
     * Not a tuning knob so much as a ceiling on how much of the heap one export
     * may ask for. See [SettingsRepository.embeddedByteEstimate].
     */
    private const val MAX_EMBEDDED_BYTES = 16L * 1024 * 1024

    /** Sections that carry whole files, and so are the ones worth dropping. */
    private val HEAVY_SECTIONS = setOf(
        ConfigBackup.Section.STICKERS,
        ConfigBackup.Section.ICONS,
        ConfigBackup.Section.WORDLISTS,
        ConfigBackup.Section.VOCAB,
    )

    private const val STAGING_DIR = "backup"

    private val gate = Mutex()

    /** What one run did, for the settings screen and for the job's retry choice. */
    sealed interface Outcome {

        /** [name] is what the file ended up called, which is not always what we asked. */
        data class Done(val name: String, val skipped: Set<ConfigBackup.Section>) : Outcome

        /** Nothing to do: turned off, no folder, or the interval has not elapsed. */
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
     * [force] is the "Back up now" button: it skips the interval check and the
     * enabled switch, but not the lock check or the destination check, because
     * those are about whether a backup can be any good rather than whether one
     * was asked for.
     */
    suspend fun run(
        context: Context,
        repository: SettingsRepository,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome = gate.withLock {
        val appContext = context.applicationContext
        val settings = repository.settings.first().autoBackup

        if (!force && !settings.enabled) return@withLock Outcome.Skipped
        if (!settings.destinationConfigured) return@withLock Outcome.Skipped
        if (!force && !isDue(settings, nowMs)) return@withLock Outcome.Skipped
        // Before anything else. A locked device cannot produce a real bundle,
        // and an unreal one is worse than none.
        if (!DirectBoot.isUserUnlocked(appContext)) return@withLock Outcome.Locked

        val sink = sinkFor(appContext, settings)
            ?: return@withLock fail(
                repository,
                BackupSinkException(SinkError.NOT_CONFIGURED),
                nowMs,
            )
        sink.readiness().exceptionOrNull()?.let { failure ->
            return@withLock fail(repository, failure, nowMs)
        }

        val outcome = runCancellable {
            backUp(appContext, repository, sink, settings, nowMs)
        }
        outcome.getOrElse { failure -> fail(repository, failure, nowMs) }
    }

    /**
     * The sink for the chosen destination, or null when this build cannot
     * reach it.
     *
     * Only Drive can be missing, and only on a build with no Play services
     * compiled in: [DriveAuth.provider] is what `:app` fills in behind that
     * seam, so a null there is the F-Droid case rather than a failure. The
     * other two are ordinary code that every build has.
     */
    fun sinkFor(context: Context, settings: AutoBackupSettings): BackupSink? =
        when (settings.destination) {
            BackupDestination.FOLDER ->
                SafFolderSink(context, Uri.parse(settings.folderUri))
            BackupDestination.WEBDAV -> WebDavSink(
                baseUrl = settings.webDavUrl,
                user = settings.webDavUser,
                password = settings.webDavPassword,
            )
            BackupDestination.DRIVE -> DriveAuth.provider?.let(::DriveAppDataSink)
            BackupDestination.S3 -> S3Sink(settings.s3)
            BackupDestination.FTP -> FtpSink(settings.ftp)
            BackupDestination.DROPBOX -> BackupClients.dropbox()?.let {
                DropboxSink(settings.dropboxRefreshToken, it)
            }
            BackupDestination.ONEDRIVE -> BackupClients.oneDrive()?.let {
                OneDriveSink(settings.oneDriveRefreshToken, it)
            }
        }

    /**
     * Whether enough wall-clock time has passed since the last good run.
     *
     * Pure, and public so the tests in `:app` can reach it — the arithmetic has
     * two edge cases that are easy to get wrong and impossible to observe from
     * outside a run.
     */
    fun isDue(settings: AutoBackupSettings, nowMs: Long): Boolean {
        val last = settings.lastRunAtMs
        // A last-run time in the future is a clock that moved, or a bundle from
        // another device that got through. Either way, waiting for it to come
        // round again could mean waiting for weeks; take one now instead.
        if (last <= 0L || last > nowMs) return true
        return nowMs - last >= settings.intervalHours.coerceAtLeast(1) * 60L * 60L * 1000L
    }

    private suspend fun backUp(
        appContext: Context,
        repository: SettingsRepository,
        sink: BackupSink,
        settings: AutoBackupSettings,
        nowMs: Long,
    ): Outcome {
        val encrypt = settings.encrypt && settings.passphrase.isNotEmpty()
        val requested = settings.sectionSet
        if (requested.isEmpty()) return Outcome.Skipped

        // Drop the bulky sections before building anything, not after: the
        // failure being avoided is an allocation, so noticing it in the
        // finished string would be noticing it too late.
        val skipped = if (repository.embeddedByteEstimate(requested) > MAX_EMBEDDED_BYTES) {
            requested intersect HEAVY_SECTIONS
        } else {
            emptySet()
        }
        val sections = requested - skipped
        if (sections.isEmpty()) return Outcome.Skipped

        val version = appVersion(appContext)
        val bundle = repository.exportConfig(
            sections = sections,
            // A file nobody can read is not protected by withholding the keys,
            // and a file anybody can read should not carry them.
            includeSecrets = settings.includeSecrets && encrypt,
            appVersion = version.first,
            appVersionName = version.second,
        )

        val staged = stage(appContext, bundle, settings, encrypt)
        try {
            if (!verify(staged, settings, encrypt)) {
                return Outcome.Failed(SinkError.IO)
            }
            val name = AutoBackupNaming.name(nowMs, encrypt)
            val mime =
                if (encrypt) ConfigBackup.ENCRYPTED_MIME_TYPE else ConfigBackup.MIME_TYPE
            val written = sink.write(name, mime) { out -> staged.inputStream().use { it.copyTo(out) } }
                .getOrElse { return fail(repository, it, nowMs) }

            // Last, and only now. Everything above can fail without costing the
            // user a generation; this is the only step that destroys one.
            rotate(sink, settings.keep)

            repository.setAutoBackupOutcome(ranAtMs = nowMs, error = "")
            return Outcome.Done(written.name, skipped)
        } finally {
            staged.delete()
        }
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
        bundle: String,
        settings: AutoBackupSettings,
        encrypt: Boolean,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, STAGING_DIR).apply { mkdirs() }
        val part = File(dir, "staging.part")
        val staged = File(dir, "staging")
        part.delete()
        staged.delete()

        part.outputStream().use { out ->
            if (encrypt) {
                BackupCrypto.encrypt(
                    out = out,
                    passphrase = settings.passphrase.toCharArray(),
                    salt = Base64.decode(settings.kdfSalt, Base64.NO_WRAP),
                    plaintext = bundle,
                )
            } else {
                OutputStreamWriter(out, Charsets.UTF_8).use { it.write(bundle) }
            }
        }
        if (!part.renameTo(staged)) throw BackupSinkException(SinkError.IO)
        staged
    }

    /**
     * Reads the staged file back and parses it.
     *
     * Not paranoia about the disk: this is the check that a truncated or
     * garbled generation never reaches the destination, and so never gets
     * counted as the reason an older one can be deleted.
     */
    private suspend fun verify(
        staged: File,
        settings: AutoBackupSettings,
        encrypt: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val text = if (encrypt) {
            val result = staged.inputStream().use {
                BackupCrypto.decrypt(it, settings.passphrase.toCharArray())
            }
            (result as? BackupCrypto.DecryptResult.Ok)?.text ?: return@withContext false
        } else {
            staged.readText()
        }
        ConfigBackup.decode(text) != null
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

    private suspend fun rotate(sink: BackupSink, keep: Int) {
        val entries = sink.list().getOrNull() ?: return
        for (entry in AutoBackupNaming.rotation(entries, keep)) {
            sink.delete(entry)
        }
    }

    private suspend fun fail(
        repository: SettingsRepository,
        failure: Throwable,
        nowMs: Long,
    ): Outcome {
        val reason = (failure as? BackupSinkException)?.reason ?: SinkError.IO
        repository.setAutoBackupOutcome(ranAtMs = nowMs, error = reason.name)
        return Outcome.Failed(reason)
    }
}

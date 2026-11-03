package com.wasimaster.wmkeyboard.core.settings

import android.app.job.JobInfo
import com.wasimaster.wmkeyboard.core.settings.sink.DriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.DropboxSink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which sentence a cloud failure turns into.
 *
 * The one that matters is PERMISSION_LOST: it tells the user to sign in again,
 * and it is not retried. Anything transient reported as that sends the user to
 * fix a grant that is fine.
 */
class CloudSinkErrorsTest {

    @Test
    fun `Drive names a full account as out of space, not as a lost grant`() {
        assertEquals(SinkError.OUT_OF_SPACE, DriveSink.statusError(403, "storageQuotaExceeded"))
    }

    @Test
    fun `Drive rate limits are transient`() {
        assertEquals(SinkError.IO, DriveSink.statusError(403, "userRateLimitExceeded"))
        assertEquals(SinkError.IO, DriveSink.statusError(403, "rateLimitExceeded"))
        assertEquals(SinkError.IO, DriveSink.statusError(429))
        assertEquals(SinkError.IO, DriveSink.statusError(503))
    }

    @Test
    fun `Drive refusals are still a lost grant`() {
        assertEquals(SinkError.PERMISSION_LOST, DriveSink.statusError(401))
        assertEquals(SinkError.PERMISSION_LOST, DriveSink.statusError(403, "insufficientPermissions"))
        assertEquals(SinkError.PERMISSION_LOST, DriveSink.statusError(403, null))
    }

    @Test
    fun `Dropbox 409 is read from the error summary`() {
        assertEquals(
            SinkError.OUT_OF_SPACE,
            DropboxSink.statusError(409, "path/insufficient_space/..."),
        )
        assertEquals(
            SinkError.TARGET_MISSING,
            DropboxSink.statusError(409, "path/not_found/.."),
        )
        assertEquals(
            SinkError.IO,
            DropboxSink.statusError(409, "path/too_many_write_operations/.."),
        )
    }

    @Test
    fun `Dropbox refusals and limits`() {
        assertEquals(SinkError.PERMISSION_LOST, DropboxSink.statusError(401))
        assertEquals(SinkError.IO, DropboxSink.statusError(429))
        assertEquals(SinkError.IO, DropboxSink.statusError(500))
    }

    private fun with(vararg types: BackupDestination, unmetered: Boolean) = AutoBackupSettings(
        requireUnmetered = unmetered,
        locations = types.mapIndexed { i, type ->
            BackupLocation(
                id = "0000000$i",
                type = type,
                folderUri = "content://x",
                webDavUrl = "https://x/",
                webDavUser = "u",
                s3 = S3Config(bucket = "b", accessKeyId = "k", secretAccessKey = "s"),
                ftp = FtpConfig(host = "h", user = "u"),
                sftp = SftpConfig(host = "h", user = "u", password = "p"),
                smb = SmbConfig(host = "h", share = "s", user = "u"),
                git = GitConfig(repository = "o/r", token = "t"),
                imap = ImapConfig(host = "h", user = "u", password = "p"),
                refreshToken = "t",
                pcloud = PCloudConfig(auth = "a"),
                nas = NasConfig(server = "s", user = "u", password = "p"),
                nfs = NfsConfig(host = "h", export = "/e"),
                mega = MegaConfig(sessionId = "s", masterKey = "k"),
                icloud = ICloudConfig(appleId = "a", password = "p", trustToken = "t"),
                proton = ProtonConfig(uid = "u", refreshToken = "t", saltedKeyPass = "s"),
            )
        },
    )

    @Test
    fun `a network location always waits for a network`() {
        // A paired computer (NEARBY) is reached on the local network and waits
        // for nothing, like a folder; the copy in another profile (PROFILE)
        // is on this phone, and backups never go to either.
        for (destination in BackupDestination.entries - BackupDestination.FOLDER - BackupDestination.NEARBY - BackupDestination.PROFILE) {
            assertEquals(
                JobInfo.NETWORK_TYPE_ANY,
                AutoBackupScheduler.networkTypeFor(with(destination, unmetered = false)),
            )
            assertEquals(
                JobInfo.NETWORK_TYPE_UNMETERED,
                AutoBackupScheduler.networkTypeFor(with(destination, unmetered = true)),
            )
        }
    }

    @Test
    fun `folders only need no network either way`() {
        for (unmetered in listOf(true, false)) {
            assertEquals(
                JobInfo.NETWORK_TYPE_NONE,
                AutoBackupScheduler.networkTypeFor(with(BackupDestination.FOLDER, unmetered = unmetered)),
            )
        }
    }

    @Test
    fun `a folder beside a cloud location waits for the network`() {
        assertEquals(
            JobInfo.NETWORK_TYPE_ANY,
            AutoBackupScheduler.networkTypeFor(
                with(BackupDestination.FOLDER, BackupDestination.DROPBOX, unmetered = false),
            ),
        )
    }
}

package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sink.DriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDestinationTest {

    @Test
    fun `a folder needs a folder`() {
        val settings = AutoBackupSettings(destination = BackupDestination.FOLDER)
        assertFalse(settings.destinationConfigured)
        assertTrue(settings.copy(folderUri = "content://tree/1").destinationConfigured)
    }

    @Test
    fun `webdav needs an address and a user`() {
        val webdav = AutoBackupSettings(destination = BackupDestination.WEBDAV)
        assertFalse(webdav.destinationConfigured)
        assertFalse(webdav.copy(webDavUrl = "https://h/dav/").destinationConfigured)
        assertFalse(webdav.copy(webDavUser = "me").destinationConfigured)
        assertTrue(webdav.copy(webDavUrl = "https://h/dav/", webDavUser = "me").destinationConfigured)
    }

    @Test
    fun `drive needs nothing stored`() {
        // What it needs is an authorization Play services holds, not a setting.
        assertTrue(AutoBackupSettings(destination = BackupDestination.DRIVE).destinationConfigured)
    }

    @Test
    fun `a folder is what a fresh install backs up to`() {
        // It is the only one that needs no account and no network, and the only
        // one that works on a device with no Google Play services.
        assertEquals(BackupDestination.FOLDER, AutoBackupSettings().destination)
    }

    @Test
    fun `destination ids are stable on disk`() {
        // Stored as ids, so renaming an enum constant must not silently move
        // every user's backups back to the default.
        assertEquals(
            listOf("folder", "webdav", "drive", "s3", "dropbox", "onedrive", "ftp", "sftp", "smb", "git", "imap", "box", "pcloud", "synology", "qnap", "nfs", "mega", "icloud", "proton", "nearby"),
            BackupDestination.entries.map { it.id },
        )
        assertEquals(
            BackupDestination.entries.size,
            BackupDestination.entries.map { it.id }.toSet().size,
        )
    }

    @Test
    fun `s3 needs a bucket and a key pair`() {
        val s3 = AutoBackupSettings(destination = BackupDestination.S3)
        assertFalse(s3.destinationConfigured)
        assertFalse(s3.copy(s3 = S3Config(bucket = "b")).destinationConfigured)
        assertTrue(
            s3.copy(s3 = S3Config(bucket = "b", accessKeyId = "k", secretAccessKey = "s"))
                .destinationConfigured,
        )
    }

    @Test
    fun `ftp needs a host and a user`() {
        val ftp = AutoBackupSettings(destination = BackupDestination.FTP)
        assertFalse(ftp.destinationConfigured)
        assertTrue(ftp.copy(ftp = FtpConfig(host = "h", user = "u")).destinationConfigured)
    }

    @Test
    fun `the two oauth destinations need their token`() {
        val dropbox = AutoBackupSettings(destination = BackupDestination.DROPBOX)
        assertFalse(dropbox.destinationConfigured)
        assertTrue(dropbox.copy(dropboxRefreshToken = "t").destinationConfigured)
        val oneDrive = AutoBackupSettings(destination = BackupDestination.ONEDRIVE)
        assertFalse(oneDrive.destinationConfigured)
        assertTrue(oneDrive.copy(oneDriveRefreshToken = "t").destinationConfigured)
    }

    @Test
    fun `ftp uses tls unless it is turned off`() {
        assertTrue(FtpConfig().secure)
    }

    @Test
    fun `every stored credential is on the secret list`() {
        for (key in listOf(
            SettingsBackup.AUTO_BACKUP_WEBDAV_PASSWORD,
            SettingsBackup.AUTO_BACKUP_S3_SECRET,
            SettingsBackup.AUTO_BACKUP_FTP_PASSWORD,
            // A refresh token is a standing grant on the account, so it is a
            // credential in every sense that matters.
            SettingsBackup.AUTO_BACKUP_DROPBOX_TOKEN,
            SettingsBackup.AUTO_BACKUP_ONEDRIVE_TOKEN,
        )) {
            assertTrue(key, key in SettingsBackup.SECRET_KEYS)
        }
    }

    @Test
    fun `a cleartext endpoint is recognised`() {
        assertTrue(S3Sink.isCleartext("http://192.168.1.5:9000"))
        assertFalse(S3Sink.isCleartext("https://s3.amazonaws.com"))
        assertFalse(S3Sink.isCleartext(""))
    }

    @Test
    fun `an empty region falls back rather than signing with nothing`() {
        assertEquals("us-east-1", S3Sink.normalizeRegion(""))
        assertEquals("eu-west-1", S3Sink.normalizeRegion("  EU-West-1 "))
    }

    @Test
    fun `the drive scope is the narrow one`() {
        // drive.appdata sees only this app's own hidden folder. Anything wider
        // would be asking for sight of the user's documents to store a backup.
        assertEquals("https://www.googleapis.com/auth/drive.appdata", DriveSink.SCOPE)
    }

    @Test
    fun `drive timestamps parse`() {
        assertEquals(
            1_754_575_353_000L,
            DriveSink.parseRfc3339("2025-08-07T14:02:33.000Z"),
        )
        // Fractional seconds vary, and some responses have none at all.
        assertEquals(
            1_754_575_353_000L,
            DriveSink.parseRfc3339("2025-08-07T14:02:33Z"),
        )
        assertEquals(
            1_754_575_353_000L,
            DriveSink.parseRfc3339("2025-08-07T14:02:33.123456Z"),
        )
    }

    @Test
    fun `an unreadable drive timestamp sorts by name instead of throwing`() {
        assertEquals(0L, DriveSink.parseRfc3339(""))
        assertEquals(0L, DriveSink.parseRfc3339("yesterday"))
    }
}

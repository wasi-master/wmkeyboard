package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.BackupLocation

/**
 * One [RemoteFs] per kind of backup location that holds a tree of files,
 * built from the same saved details the backup's sink uses.
 *
 * Every adapter is built the same way, `XFs(context, location)`, touches no
 * network until its first call, and throws [java.io.IOException] from that
 * call when it cannot connect. One that this build cannot reach at all (no
 * Play services for Drive, no client id for Dropbox, OneDrive or Box) throws
 * [Unavailable] from its constructor, which is a null here.
 */
object RemoteAdapters {

    /** Thrown by an adapter's constructor when this build has no way to the service. */
    class Unavailable(what: String) : java.io.IOException(what)

    val BROWSABLE: Set<BackupDestination> = setOf(
        BackupDestination.WEBDAV,
        BackupDestination.SFTP,
        BackupDestination.SMB,
        BackupDestination.FTP,
        BackupDestination.S3,
        BackupDestination.NFS,
        BackupDestination.DRIVE,
        BackupDestination.DROPBOX,
        BackupDestination.ONEDRIVE,
        BackupDestination.BOX,
        BackupDestination.PCLOUD,
        BackupDestination.SYNOLOGY,
        BackupDestination.QNAP,
        BackupDestination.MEGA,
        BackupDestination.ICLOUD,
        BackupDestination.PROTON,
    )

    fun open(context: Context, location: BackupLocation): RemoteFs? = try {
        when (location.type) {
            BackupDestination.WEBDAV -> WebDavFs(context, location)
            BackupDestination.SFTP -> SftpFs(context, location)
            BackupDestination.SMB -> SmbFs(context, location)
            BackupDestination.FTP -> FtpFs(context, location)
            BackupDestination.S3 -> S3Fs(context, location)
            BackupDestination.NFS -> NfsFs(context, location)
            BackupDestination.DRIVE -> DriveFs(context, location)
            BackupDestination.DROPBOX -> DropboxFs(context, location)
            BackupDestination.ONEDRIVE -> OneDriveFs(context, location)
            BackupDestination.BOX -> BoxFs(context, location)
            BackupDestination.PCLOUD -> PCloudFs(context, location)
            BackupDestination.SYNOLOGY -> SynologyFs(context, location)
            BackupDestination.QNAP -> QnapFs(context, location)
            BackupDestination.MEGA -> MegaFs(context, location)
            BackupDestination.ICLOUD -> ICloudFs(context, location)
            BackupDestination.PROTON -> ProtonFs(context, location)
            else -> null
        }
    } catch (_: Unavailable) {
        null
    }

    /** The service's own name, which no language translates. */
    fun serviceName(type: BackupDestination): String = when (type) {
        BackupDestination.WEBDAV -> "WebDAV"
        BackupDestination.SFTP -> "SFTP"
        BackupDestination.SMB -> "SMB"
        BackupDestination.FTP -> "FTP"
        BackupDestination.S3 -> "S3"
        BackupDestination.NFS -> "NFS"
        BackupDestination.DRIVE -> "Google Drive"
        BackupDestination.DROPBOX -> "Dropbox"
        BackupDestination.ONEDRIVE -> "OneDrive"
        BackupDestination.BOX -> "Box"
        BackupDestination.PCLOUD -> "pCloud"
        BackupDestination.SYNOLOGY -> "Synology"
        BackupDestination.QNAP -> "QNAP"
        BackupDestination.MEGA -> "MEGA"
        BackupDestination.ICLOUD -> "iCloud Drive"
        BackupDestination.PROTON -> "Proton Drive"
        else -> type.id
    }
}

package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteFiles
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.files.RemotePlace
import com.wasimaster.wmkeyboard.core.files.RemotePlaces
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The backup locations, as places the Files tool can browse: every
 * configured one of a type that holds folders and files. Git, IMAP, a
 * nearby device and the other profile are left out (they hold backups, not a
 * tree of files), and so is a picked folder, which the Files tool reaches
 * through Android's picker already.
 *
 * Changing a location (a new password, another server) closes its open
 * session, so the next look goes in with what is saved now.
 */
object BackupRemotePlaces : RemotePlaces {

    @Volatile
    private var latest: List<BackupLocation>? = null

    /** Hooks the Files tool up to the backup locations. Once, at start. */
    fun start(context: Context, repository: SettingsRepository) {
        RemoteFiles.source = this
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repository.settings
                .map { it.autoBackup.locations }
                .distinctUntilChanged()
                .collect { locations ->
                    val before = latest?.associateBy { it.id }
                    latest = locations
                    if (before == null) return@collect
                    // Only a location whose way in changed loses its session. A
                    // token, cookie, host key or certificate saved by a session
                    // itself (they rotate hourly on some services) must not
                    // drop the connection that saved it.
                    val now = locations.associateBy { it.id }
                    for (id in before.keys + now.keys) {
                        val old = before[id]?.let(::wayIn)
                        val new = now[id]?.let(::wayIn)
                        if (old != new) RemoteFiles.close(id)
                    }
                }
        }
    }

    /** What a session was opened with, less what sessions save for themselves. */
    private fun wayIn(location: BackupLocation): BackupLocation = location.copy(
        refreshToken = "",
        sftp = location.sftp.copy(hostKey = ""),
        nas = location.nas.copy(certPin = ""),
        icloud = location.icloud.copy(cookies = "", trustToken = ""),
        proton = location.proton.copy(accessToken = "", refreshToken = ""),
        name = "",
        backup = false,
        schedule = null,
        contents = null,
    )

    private fun locations(context: Context): List<BackupLocation> =
        latest ?: runBlocking { SettingsRepository(context.applicationContext).settings.first().autoBackup.locations }

    override fun places(context: Context): List<RemotePlace> =
        locations(context)
            .filter { it.configured && it.type in RemoteAdapters.BROWSABLE }
            .map { RemotePlace(it.id, labelOf(it), it.type.id) }

    override fun open(context: Context, id: String): RemoteFs? {
        val location = locations(context).firstOrNull { it.id == id && it.configured } ?: return null
        return RemoteAdapters.open(context, location)
    }

    /** What the user called it, else the service and, for a server, its address. */
    fun labelOf(location: BackupLocation): String {
        location.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val host = when (location.type) {
            BackupDestination.WEBDAV -> runCatching { java.net.URI(location.webDavUrl.trim()).host }.getOrNull()
            BackupDestination.SFTP -> location.sftp.host
            BackupDestination.SMB -> location.smb.host
            BackupDestination.FTP -> location.ftp.host
            BackupDestination.S3 -> location.s3.bucket
            BackupDestination.NFS -> location.nfs.host
            else -> null
        }?.trim().orEmpty()
        val service = RemoteAdapters.serviceName(location.type)
        return if (host.isEmpty()) service else "$service · $host"
    }
}

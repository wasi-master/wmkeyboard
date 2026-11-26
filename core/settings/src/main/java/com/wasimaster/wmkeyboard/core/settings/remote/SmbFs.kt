package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.smb.Smb2Client
import com.wasimaster.wmkeyboard.core.settings.sink.smb.SmbStatusException
import java.io.File
import java.io.OutputStream

/**
 * A Windows or Samba share's files, from the backup location's server,
 * share, user and password, with the same encryption rule (on, a server
 * that cannot encrypt is refused).
 *
 * The top is the share's root, not the backup's folder inside it. One
 * signed-in connection stays open between calls until [close]. A new file
 * and a new folder are created with `FILE_CREATE` and a rename is asked not
 * to replace, so the server refuses rather than writing over anything.
 * Windows names ignore case, and so does this.
 */
class SmbFs(context: Context, location: BackupLocation) : RemoteFs {

    private val config = location.smb
    private var client: Smb2Client? = null

    override val caseInsensitive: Boolean get() = true

    private fun smb(): Smb2Client {
        client?.let { return it }
        val made = Smb2Client.connect(config.host.trim(), config.port, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS) { _, _ -> }
        try {
            made.negotiate()
            made.login(config.user, config.password, config.domain.trim(), requireEncryption = config.encrypt)
            made.treeConnect(config.share.trim().trim('\\', '/'))
        } catch (failure: Throwable) {
            runCatching { made.close() }
            throw failure
        }
        client = made
        return made
    }

    /** [names] as a path from the share root, with the backslashes SMB wants. */
    private fun at(names: List<String>): String {
        names.forEach { require(it.isNotEmpty() && '\\' !in it && '/' !in it && it != "." && it != "..") { "bad name" } }
        return names.joinToString("\\")
    }

    private fun isMissing(e: SmbStatusException): Boolean =
        e.status == Smb2Client.STATUS_OBJECT_NAME_NOT_FOUND || e.status == Smb2Client.STATUS_OBJECT_PATH_NOT_FOUND

    override fun list(dir: List<String>): List<RemoteEntry> {
        val smb = smb()
        val handle = smb.create(
            at(dir),
            Smb2Client.ACCESS_READ_DATA or Smb2Client.ACCESS_READ_ATTRIBUTES or Smb2Client.ACCESS_SYNCHRONIZE,
            Smb2Client.DISPOSITION_OPEN,
            Smb2Client.OPTION_DIRECTORY,
        )
        try {
            return smb.list(handle).map { RemoteEntry(it.name, it.directory, if (it.directory) -1 else it.size, it.modifiedMs) }
        } finally {
            runCatching { smb.close(handle) }
        }
    }

    override fun stat(path: List<String>): RemoteEntry? {
        if (path.isEmpty()) return RemoteEntry("", true)
        val smb = smb()
        val handle = try {
            smb.create(at(path), Smb2Client.ACCESS_READ_ATTRIBUTES or Smb2Client.ACCESS_SYNCHRONIZE, Smb2Client.DISPOSITION_OPEN, 0)
        } catch (e: SmbStatusException) {
            if (isMissing(e)) return null
            throw e
        }
        runCatching { smb.close(handle) }
        return RemoteEntry(path.last(), handle.directory, if (handle.directory) -1 else handle.size, handle.modifiedMs)
    }

    override fun download(path: List<String>, to: OutputStream) {
        val smb = smb()
        val handle = smb.create(
            at(path),
            Smb2Client.ACCESS_READ_DATA or Smb2Client.ACCESS_READ_ATTRIBUTES or Smb2Client.ACCESS_SYNCHRONIZE,
            Smb2Client.DISPOSITION_OPEN,
            Smb2Client.OPTION_NON_DIRECTORY,
        )
        try {
            smb.readTo(handle, to)
        } finally {
            runCatching { smb.close(handle) }
        }
    }

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        val smb = smb()
        val handle = smb.create(
            at(dir + name),
            Smb2Client.ACCESS_WRITE_DATA or Smb2Client.ACCESS_READ_ATTRIBUTES or Smb2Client.ACCESS_SYNCHRONIZE,
            // Refused with a name collision when something has the name.
            Smb2Client.DISPOSITION_CREATE,
            Smb2Client.OPTION_NON_DIRECTORY,
            share = 0,
        )
        try {
            from.inputStream().buffered().use { smb.writeFrom(handle, it, onSent) }
        } finally {
            smb.close(handle)
        }
    }

    override fun mkdir(dir: List<String>, name: String) {
        val smb = smb()
        val handle = smb.create(
            at(dir + name),
            Smb2Client.ACCESS_READ_ATTRIBUTES or Smb2Client.ACCESS_SYNCHRONIZE,
            Smb2Client.DISPOSITION_CREATE,
            Smb2Client.OPTION_DIRECTORY,
        )
        runCatching { smb.close(handle) }
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        val smb = smb()
        val handle = try {
            smb.create(
                at(path),
                Smb2Client.ACCESS_DELETE or Smb2Client.ACCESS_READ_ATTRIBUTES,
                Smb2Client.DISPOSITION_OPEN,
                (if (isDirectory) Smb2Client.OPTION_DIRECTORY else Smb2Client.OPTION_NON_DIRECTORY) or Smb2Client.OPTION_DELETE_ON_CLOSE,
            )
        } catch (e: SmbStatusException) {
            if (isMissing(e)) return
            throw e
        }
        smb.close(handle)
    }

    override fun rename(path: List<String>, newName: String) = moveTo(path, path.dropLast(1) + newName)

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        moveTo(path, toDir + path.last())
        return true
    }

    private fun moveTo(from: List<String>, to: List<String>) {
        val smb = smb()
        val handle = smb.create(
            at(from),
            Smb2Client.ACCESS_DELETE or Smb2Client.ACCESS_READ_ATTRIBUTES or Smb2Client.ACCESS_SYNCHRONIZE,
            Smb2Client.DISPOSITION_OPEN,
            0,
        )
        try {
            smb.rename(handle, at(to), replace = false)
        } finally {
            runCatching { smb.close(handle) }
        }
    }

    override fun close() {
        runCatching { client?.close() }
        client = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

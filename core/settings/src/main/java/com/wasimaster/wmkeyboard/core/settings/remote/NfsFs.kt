package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.netlog.NetCall
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.Nfs3Client
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.NfsStatusException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * An NFS export's files, through the backup's [Nfs3Client], as the same
 * user and group ids the backup writes as.
 *
 * The top is the export's root. A path is found by looking each name up in
 * turn from there; the mount and the NFS connection stay open between calls,
 * and one network log row covers the session, as the backup's covers a run.
 *
 * NFS has no way to say "rename, but not over something": RENAME replaces a
 * target that exists, and an unchecked CREATE empties one. So a rename or a
 * move looks the target up first and refuses when it is there, and an upload
 * creates its file GUARDED, which the server refuses for a taken name.
 */
class NfsFs(
    @Suppress("UNUSED_PARAMETER") context: Context,
    location: BackupLocation,
) : RemoteFs {

    private val config = location.nfs

    private var client: Nfs3Client? = null
    private var netCall: NetCall? = null

    /**
     * The path of an upload this refused because the name was taken.
     * [com.wasimaster.wmkeyboard.core.files.RemoteFiles] removes a failed
     * upload's file next, and that file is someone else's: skipped once.
     */
    private var refusedUpload: List<String>? = null

    /** The open client, or a new one mounted now. */
    private fun nfs(): Nfs3Client {
        client?.let { return it }
        if (!config.configured) throw IOException("NFS is not set up")
        val host = config.host.trim()
        val call = NetLog.callTo(
            source = NetSource.BACKUP,
            method = "NFS",
            scheme = "nfs",
            host = host,
            port = config.port.takeIf { it > 0 } ?: Nfs3Client.NFS_DEFAULT_PORT,
            route = config.export.trim(),
            background = false,
        )
        val opened = try {
            Nfs3Client.open(
                host, config.export.trim(), config.uid, config.gid, config.port, config.mountPort,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
            ) { sent, received ->
                if (sent > 0) call.sent(sent)
                if (received > 0) call.received(received)
            }
        } catch (failure: Throwable) {
            call.fail(failure)
            call.end()
            throw failure
        }
        client = opened
        netCall = call
        return opened
    }

    /**
     * The handle at [path], or null when some name on the way is not there
     * (or is a file where a folder was needed). Throws when the server
     * cannot be asked.
     */
    private fun resolve(path: List<String>): ByteArray? {
        val nfs = nfs()
        var at = nfs.root
        for (name in path) {
            checkName(name)
            val next = try {
                nfs.lookup(at, name)
            } catch (notFolder: NfsStatusException) {
                if (notFolder.status == Nfs3Client.NFS3ERR_NOTDIR) null else throw notFolder
            }
            at = next ?: return null
        }
        return at
    }

    private fun folder(dir: List<String>): ByteArray =
        resolve(dir) ?: throw FileNotFoundException("no folder ${dir.joinToString("/")}")

    override fun list(dir: List<String>): List<RemoteEntry> {
        refusedUpload = null
        return nfs().list(folder(dir))
            .filter { it.name.isNotEmpty() && '/' !in it.name }
            .map { RemoteEntry(it.name, it.directory, if (it.directory) -1L else it.size, it.modifiedMs) }
    }

    override fun stat(path: List<String>): RemoteEntry? {
        refusedUpload = null
        if (path.isEmpty()) {
            // GETATTR on the root asks the server, so a dead connection shows here.
            nfs().getattr(nfs().root)
            return RemoteEntry("", isDirectory = true)
        }
        val handle = resolve(path) ?: return null
        val attributes = try {
            nfs().getattr(handle)
        } catch (gone: NfsStatusException) {
            // Removed between the lookup and now.
            if (gone.status == Nfs3Client.NFS3ERR_STALE || gone.status == Nfs3Client.NFS3ERR_NOENT) return null
            throw gone
        }
        return RemoteEntry(
            name = path.last(),
            isDirectory = attributes.directory,
            size = if (attributes.directory) -1L else attributes.size,
            modifiedMs = attributes.modifiedMs,
        )
    }

    override fun download(path: List<String>, to: OutputStream) {
        refusedUpload = null
        if (path.isEmpty()) throw IOException("the top is a folder")
        val handle = resolve(path) ?: throw FileNotFoundException(path.joinToString("/"))
        nfs().readTo(handle, to)
    }

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        refusedUpload = null
        checkName(name)
        val parent = folder(dir)
        val handle = try {
            nfs().createNew(parent, name)
        } catch (taken: NfsStatusException) {
            if (taken.status == Nfs3Client.NFS3ERR_EXIST) refusedUpload = dir + name
            throw taken
        }
        from.inputStream().buffered().use { nfs().writeFrom(handle, it, onSent) }
    }

    override fun mkdir(dir: List<String>, name: String) {
        refusedUpload = null
        checkName(name)
        nfs().mkdir(folder(dir), name)
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (path.isEmpty()) throw IOException("the top cannot be removed")
        if (refusedUpload == path) {
            refusedUpload = null
            return
        }
        refusedUpload = null
        checkName(path.last())
        // A folder on the way that is gone takes the file with it.
        val parent = resolve(path.dropLast(1)) ?: return
        if (isDirectory) nfs().rmdir(parent, path.last()) else nfs().remove(parent, path.last())
    }

    override fun rename(path: List<String>, newName: String) {
        refusedUpload = null
        if (path.isEmpty()) throw IOException("the top cannot be renamed")
        checkName(newName)
        val parent = folder(path.dropLast(1))
        val source = resolve(path) ?: throw FileNotFoundException(path.joinToString("/"))
        val nfs = nfs()
        // RENAME replaces a target that exists. The same handle is the same
        // file, which a server whose disk folds case finds for a change of case alone.
        val there = nfs.lookup(parent, newName)
        if (there != null && !there.contentEquals(source)) throw IOException("$newName is already there")
        nfs.rename(parent, path.last(), newName)
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        refusedUpload = null
        if (path.isEmpty()) return false
        val name = path.last()
        val from = folder(path.dropLast(1))
        val to = resolve(toDir) ?: return false
        val nfs = nfs()
        if (nfs.lookup(to, name) != null) return false
        return try {
            nfs.renameTo(from, name, to, name)
            true
        } catch (apart: NfsStatusException) {
            // Another file system inside the export: the store copies instead.
            if (apart.status == Nfs3Client.NFS3ERR_XDEV) false else throw apart
        }
    }

    override fun close() {
        refusedUpload = null
        val open = client ?: return
        client = null
        runCatching { open.close() }
        Nfs3Client.unmount(
            config.host.trim(), config.export.trim(), config.uid, config.gid, config.mountPort,
            CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
        )
        netCall?.end()
        netCall = null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Refuses a name that is not one entry: a slash is a path, `.` and `..` are other folders. */
        private fun checkName(name: String) {
            if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) {
                throw IOException("not a name NFS can carry: $name")
            }
        }
    }
}

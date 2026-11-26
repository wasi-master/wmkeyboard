package com.wasimaster.wmkeyboard.core.settings.sink.nfs

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** An NFS or MOUNT call that came back with a status other than OK. */
class NfsStatusException(val status: Int, what: String) : IOException("$what: nfs status $status")

/**
 * NFS version 3 (RFC 1813), the procedures a backup needs, over [RpcClient].
 *
 * A session is three short connections: the portmapper (port 111) to find
 * the other two, MOUNT to turn the export path into a file handle, and NFS
 * itself. A server with fixed ports can skip the first.
 *
 * Android does not let an app open a socket from a port below 1024, and many
 * servers want exactly that unless the export allows otherwise (`insecure`
 * on Linux, "Allow connections from non-privileged ports" on a Synology).
 * Such a server answers the MOUNT call with an auth error, which the sink
 * reports as a permission problem and the screen explains.
 */
class Nfs3Client private constructor(
    private val nfs: RpcClient,
    private val credential: ByteArray,
    val root: ByteArray,
) : AutoCloseable {

    /** One directory entry from [list]. */
    data class Entry(val name: String, val directory: Boolean, val size: Long, val modifiedMs: Long)

    /** What [getattr] reports of one file or folder. */
    data class Attributes(val directory: Boolean, val size: Long, val modifiedMs: Long)

    private var writeChunk = DEFAULT_CHUNK
    private var readChunk = DEFAULT_CHUNK

    private fun call(procedure: Int, args: XdrWriter): XdrReader =
        nfs.call(NFS_PROGRAM, NFS_VERSION, procedure, credential, args.bytes())

    /** Reads FSINFO for the transfer sizes the server prefers. Best effort. */
    private fun fsinfo() {
        runCatching {
            val r = call(PROC_FSINFO, XdrWriter().opaque(root))
            val status = r.int()
            skipPostOpAttr(r)
            if (status != NFS_OK) return
            val rtmax = r.uint()
            val rtpref = r.uint()
            r.uint() // rtmult
            val wtmax = r.uint()
            val wtpref = r.uint()
            readChunk = pick(rtpref, rtmax)
            writeChunk = pick(wtpref, wtmax)
        }
    }

    private fun pick(preferred: Long, max: Long): Int {
        val v = if (preferred > 0) preferred else max
        return v.coerceIn(MIN_CHUNK.toLong(), MAX_CHUNK.toLong()).toInt()
    }

    /** The handle of [name] in [dir], or null when there is nothing by that name. */
    fun lookup(dir: ByteArray, name: String): ByteArray? {
        val r = call(PROC_LOOKUP, XdrWriter().opaque(dir).string(name))
        val status = r.int()
        if (status == NFS3ERR_NOENT) return null
        if (status != NFS_OK) throw NfsStatusException(status, "lookup")
        return r.opaque()
    }

    /** Makes [name] in [dir], or finds it when it is already there. */
    fun mkdir(dir: ByteArray, name: String, mode: Int = DIR_MODE): ByteArray {
        lookup(dir, name)?.let { return it }
        val r = call(PROC_MKDIR, XdrWriter().opaque(dir).string(name).also { sattr(it, mode, null) })
        val status = r.int()
        if (status == NFS3ERR_EXIST) return lookup(dir, name) ?: throw NfsStatusException(status, "mkdir")
        if (status != NFS_OK) throw NfsStatusException(status, "mkdir")
        return handleOrLookup(r, dir, name)
    }

    /** Makes [name] in [dir] empty, replacing what is there (UNCHECKED, size 0). */
    fun create(dir: ByteArray, name: String, mode: Int = FILE_MODE): ByteArray {
        val args = XdrWriter().opaque(dir).string(name).int(CREATE_UNCHECKED).also { sattr(it, mode, 0L) }
        val r = call(PROC_CREATE, args)
        val status = r.int()
        if (status != NFS_OK) throw NfsStatusException(status, "create")
        return handleOrLookup(r, dir, name)
    }

    /** CREATE and MKDIR may answer without the handle (post_op_fh3 with none). */
    private fun handleOrLookup(r: XdrReader, dir: ByteArray, name: String): ByteArray =
        if (r.bool()) r.opaque() else lookup(dir, name) ?: throw NfsStatusException(NFS3ERR_NOENT, "lookup")

    /** Writes [bytes] from the start of [file], each piece committed to disk before the next. */
    fun write(file: ByteArray, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(writeChunk, bytes.size - offset)
            val r = call(
                PROC_WRITE,
                XdrWriter().opaque(file).long(offset.toLong()).int(count).int(FILE_SYNC)
                    .opaque(bytes.copyOfRange(offset, offset + count)),
            )
            val status = r.int()
            skipWcc(r)
            if (status != NFS_OK) throw NfsStatusException(status, "write")
            val written = r.uint().toInt()
            if (written <= 0) throw IOException("nfs: wrote nothing")
            offset += written
        }
    }

    fun read(file: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val r = call(PROC_READ, XdrWriter().opaque(file).long(offset).int(readChunk))
            val status = r.int()
            skipPostOpAttr(r)
            if (status != NFS_OK) throw NfsStatusException(status, "read")
            r.uint() // count
            val eof = r.bool()
            val data = r.opaque()
            out.write(data)
            offset += data.size
            if (eof || data.isEmpty()) return out.toByteArray()
        }
    }

    /** Removes [name] from [dir]; already gone is fine. */
    fun remove(dir: ByteArray, name: String) {
        val r = call(PROC_REMOVE, XdrWriter().opaque(dir).string(name))
        val status = r.int()
        if (status != NFS_OK && status != NFS3ERR_NOENT) throw NfsStatusException(status, "remove")
    }

    /** Renames within [dir], replacing [to] when it exists, which NFS does atomically. */
    fun rename(dir: ByteArray, from: String, to: String) {
        val r = call(PROC_RENAME, XdrWriter().opaque(dir).string(from).opaque(dir).string(to))
        val status = r.int()
        if (status != NFS_OK) throw NfsStatusException(status, "rename")
    }

    /** Everything in [dir], through READDIRPLUS so sizes and times come with the names. */
    fun list(dir: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        var cookie = 0L
        var verifier = ByteArray(VERIFIER_BYTES)
        while (true) {
            val r = call(
                PROC_READDIRPLUS,
                XdrWriter().opaque(dir).long(cookie).fixed(verifier).int(DIR_COUNT).int(DIR_MAX),
            )
            val status = r.int()
            if (status != NFS_OK) {
                throw NfsStatusException(status, "readdirplus")
            }
            skipPostOpAttr(r)
            verifier = r.fixed(VERIFIER_BYTES)
            var any = false
            while (r.bool()) {
                any = true
                r.long() // fileid
                val name = r.string()
                cookie = r.long()
                var directory = false
                var size = -1L
                var modified = 0L
                if (r.bool()) {
                    val type = r.int()
                    directory = type == TYPE_DIR
                    r.skip(FATTR_AFTER_TYPE_TO_SIZE)
                    size = r.long()
                    r.skip(FATTR_SIZE_TO_MTIME)
                    modified = r.uint() * 1000L + r.uint() / 1_000_000L
                    r.skip(NFSTIME_BYTES) // ctime
                }
                if (r.bool()) r.opaque() // the entry's handle, unused
                if (name != "." && name != "..") out += Entry(name, directory, size, modified)
            }
            val eof = r.bool()
            if (eof || !any) return out
        }
    }

    /** GETATTR: whether [handle] is a folder, its size and when it last changed. */
    fun getattr(handle: ByteArray): Attributes {
        val r = call(PROC_GETATTR, XdrWriter().opaque(handle))
        val status = r.int()
        if (status != NFS_OK) throw NfsStatusException(status, "getattr")
        val type = r.int()
        r.skip(FATTR_AFTER_TYPE_TO_SIZE)
        val size = r.long()
        r.skip(FATTR_SIZE_TO_MTIME)
        val modified = r.uint() * 1000L + r.uint() / 1_000_000L
        return Attributes(directory = type == TYPE_DIR, size = size, modifiedMs = modified)
    }

    /**
     * Makes [name] in [dir] as a new empty file, GUARDED: where something
     * already has the name the server answers NFS3ERR_EXIST and nothing is
     * touched, unlike [create], which empties what is there.
     */
    fun createNew(dir: ByteArray, name: String, mode: Int = FILE_MODE): ByteArray {
        val args = XdrWriter().opaque(dir).string(name).int(CREATE_GUARDED).also { sattr(it, mode, 0L) }
        val r = call(PROC_CREATE, args)
        val status = r.int()
        if (status != NFS_OK) throw NfsStatusException(status, "create")
        return handleOrLookup(r, dir, name)
    }

    /**
     * Writes all of [input] from the start of [file], a chunk at a time and
     * each committed before the next, as [write] does, without holding more
     * than a chunk. [onSent] hears each chunk's byte count and may throw to
     * stop. Returns the bytes written.
     */
    fun writeFrom(file: ByteArray, input: InputStream, onSent: (Long) -> Unit = {}): Long {
        val buffer = ByteArray(writeChunk)
        var offset = 0L
        while (true) {
            // Fill the chunk, since a stream may hand over less than asked.
            var filled = 0
            while (filled < buffer.size) {
                val n = input.read(buffer, filled, buffer.size - filled)
                if (n < 0) break
                filled += n
            }
            if (filled == 0) return offset
            var at = 0
            while (at < filled) {
                val count = filled - at
                val r = call(
                    PROC_WRITE,
                    XdrWriter().opaque(file).long(offset).int(count).int(FILE_SYNC)
                        .opaque(buffer.copyOfRange(at, filled)),
                )
                val status = r.int()
                skipWcc(r)
                if (status != NFS_OK) throw NfsStatusException(status, "write")
                val written = r.uint().toInt()
                if (written <= 0 || written > count) throw IOException("nfs: wrote $written of $count")
                at += written
                offset += written
                onSent(written.toLong())
            }
            if (filled < buffer.size) return offset
        }
    }

    /** Reads all of [file] into [out], a chunk at a time. [out] may throw to stop. Returns the bytes read. */
    fun readTo(file: ByteArray, out: OutputStream): Long {
        var offset = 0L
        while (true) {
            val r = call(PROC_READ, XdrWriter().opaque(file).long(offset).int(readChunk))
            val status = r.int()
            skipPostOpAttr(r)
            if (status != NFS_OK) throw NfsStatusException(status, "read")
            r.uint() // count
            val eof = r.bool()
            val data = r.opaque()
            out.write(data)
            offset += data.size
            if (eof || data.isEmpty()) return offset
        }
    }

    /** Removes the empty folder [name] from [dir]; already gone is fine, one with something in it is refused. */
    fun rmdir(dir: ByteArray, name: String) {
        val r = call(PROC_RMDIR, XdrWriter().opaque(dir).string(name))
        val status = r.int()
        if (status != NFS_OK && status != NFS3ERR_NOENT) throw NfsStatusException(status, "rmdir")
    }

    /**
     * Renames [from] in [fromDir] to [to] in [toDir]. Like [rename], NFS
     * replaces a [to] that exists, so the caller makes sure it does not.
     */
    fun renameTo(fromDir: ByteArray, from: String, toDir: ByteArray, to: String) {
        val r = call(PROC_RENAME, XdrWriter().opaque(fromDir).string(from).opaque(toDir).string(to))
        val status = r.int()
        if (status != NFS_OK) throw NfsStatusException(status, "rename")
    }

    override fun close() = nfs.close()

    companion object {
        const val NFS_OK = 0
        const val NFS3ERR_PERM = 1
        const val NFS3ERR_NOENT = 2
        const val NFS3ERR_ACCES = 13
        const val NFS3ERR_EXIST = 17
        const val NFS3ERR_XDEV = 18
        const val NFS3ERR_NOTDIR = 20
        const val NFS3ERR_FBIG = 27
        const val NFS3ERR_NOSPC = 28
        const val NFS3ERR_ROFS = 30
        const val NFS3ERR_DQUOT = 69
        const val NFS3ERR_STALE = 70

        const val PORTMAP_PORT = 111
        const val NFS_DEFAULT_PORT = 2049

        private const val PORTMAP_PROGRAM = 100000
        private const val PORTMAP_VERSION = 2
        private const val PORTMAP_GETPORT = 3
        private const val IPPROTO_TCP = 6

        private const val MOUNT_PROGRAM = 100005
        private const val MOUNT_VERSION = 3
        private const val MOUNT_MNT = 1
        private const val MOUNT_UMNT = 3

        private const val NFS_PROGRAM = 100003
        private const val NFS_VERSION = 3
        private const val PROC_GETATTR = 1
        private const val PROC_LOOKUP = 3
        private const val PROC_READ = 6
        private const val PROC_WRITE = 7
        private const val PROC_CREATE = 8
        private const val PROC_MKDIR = 9
        private const val PROC_REMOVE = 12
        private const val PROC_RMDIR = 13
        private const val PROC_RENAME = 14
        private const val PROC_READDIRPLUS = 17
        private const val PROC_FSINFO = 19

        private const val CREATE_UNCHECKED = 0
        private const val CREATE_GUARDED = 1
        private const val FILE_SYNC = 2
        private const val TYPE_DIR = 2
        private const val FILE_MODE = 0x1a4 // 0644
        private const val DIR_MODE = 0x1ed // 0755

        private const val VERIFIER_BYTES = 8
        private const val DIR_COUNT = 8192
        private const val DIR_MAX = 32768
        private const val DEFAULT_CHUNK = 32 * 1024
        private const val MIN_CHUNK = 4 * 1024
        private const val MAX_CHUNK = 1024 * 1024

        // fattr3 after its type: mode, nlink, uid, gid (4 x 4); then size.
        private const val FATTR_AFTER_TYPE_TO_SIZE = 16
        // After size: used (8), rdev (8), fsid (8), fileid (8), atime (8).
        private const val FATTR_SIZE_TO_MTIME = 40
        private const val NFSTIME_BYTES = 8
        // fattr3 is 84 bytes: five 4-byte fields, five 8-byte ones, three times.
        private const val FATTR_BYTES = 84
        // wcc_attr: size (8), mtime (8), ctime (8).
        private const val WCC_ATTR_BYTES = 24

        private fun skipPostOpAttr(r: XdrReader) {
            if (r.bool()) r.skip(FATTR_BYTES)
        }

        private fun skipWcc(r: XdrReader) {
            if (r.bool()) r.skip(WCC_ATTR_BYTES)
            skipPostOpAttr(r)
        }

        /** sattr3: [mode], and a size of [size] when given; owner and times left to the server. */
        private fun sattr(w: XdrWriter, mode: Int, size: Long?) {
            w.bool(true).int(mode)
            w.bool(false) // uid
            w.bool(false) // gid
            if (size != null) w.bool(true).long(size) else w.bool(false)
            w.int(0) // atime: don't change
            w.int(0) // mtime: don't change
        }

        /** The TCP port the portmapper on [host] has for [program], or 0 for none. */
        private fun portOf(host: String, program: Int, version: Int, timeouts: Pair<Int, Int>, traffic: (Long, Long) -> Unit): Int =
            RpcClient.connect(host, PORTMAP_PORT, timeouts.first, timeouts.second, traffic).use { pm ->
                pm.call(
                    PORTMAP_PROGRAM, PORTMAP_VERSION, PORTMAP_GETPORT, RpcClient.NONE,
                    XdrWriter().int(program).int(version).int(IPPROTO_TCP).int(0).bytes(),
                ).int()
            }

        /**
         * Mounts [export] on [host] and opens the NFS connection. [nfsPort]
         * and [mountPort] at 0 are asked of the portmapper; NFS falls back to
         * 2049 when the portmapper cannot be reached.
         */
        fun open(
            host: String,
            export: String,
            uid: Int,
            gid: Int,
            nfsPort: Int,
            mountPort: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            traffic: (Long, Long) -> Unit,
        ): Nfs3Client {
            val credential = RpcClient.authSys(uid, gid)
            val timeouts = connectTimeoutMs to readTimeoutMs
            val mountAt = mountPort.takeIf { it > 0 } ?: portOf(host, MOUNT_PROGRAM, MOUNT_VERSION, timeouts, traffic)
                .takeIf { it > 0 } ?: throw RpcUnavailableException("no mount service")
            val handle = RpcClient.connect(host, mountAt, connectTimeoutMs, readTimeoutMs, traffic).use { mount ->
                val r = mount.call(MOUNT_PROGRAM, MOUNT_VERSION, MOUNT_MNT, credential, XdrWriter().string(export).bytes())
                val status = r.int()
                if (status != NFS_OK) throw NfsStatusException(status, "mount")
                r.opaque()
            }
            val nfsAt = nfsPort.takeIf { it > 0 }
                ?: runCatching { portOf(host, NFS_PROGRAM, NFS_VERSION, timeouts, traffic) }.getOrNull()?.takeIf { it > 0 }
                ?: NFS_DEFAULT_PORT
            val client = Nfs3Client(RpcClient.connect(host, nfsAt, connectTimeoutMs, readTimeoutMs, traffic), credential, handle)
            client.fsinfo()
            return client
        }

        /** Tells the server the mount is done with. Best effort: servers keep no lasting state for it. */
        fun unmount(host: String, export: String, uid: Int, gid: Int, mountPort: Int, connectTimeoutMs: Int, readTimeoutMs: Int) {
            runCatching {
                val at = mountPort.takeIf { it > 0 }
                    ?: portOf(host, MOUNT_PROGRAM, MOUNT_VERSION, connectTimeoutMs to readTimeoutMs) { _, _ -> }
                if (at <= 0) return
                RpcClient.connect(host, at, connectTimeoutMs, readTimeoutMs) { _, _ -> }.use {
                    it.call(MOUNT_PROGRAM, MOUNT_VERSION, MOUNT_UMNT, RpcClient.authSys(uid, gid), XdrWriter().string(export).bytes())
                }
            }
        }
    }
}

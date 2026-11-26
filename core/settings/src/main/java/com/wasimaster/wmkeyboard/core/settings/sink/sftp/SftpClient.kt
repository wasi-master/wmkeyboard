package com.wasimaster.wmkeyboard.core.settings.sink.sftp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** An SFTP `STATUS` reply that was not `OK`. */
class SftpStatusException(val code: Int, what: String) : IOException("$what: SFTP status $code")

/**
 * SFTP version 3 (draft-ietf-secsh-filexfer-02), the version every server
 * speaks, over the two streams of an SSH `sftp` subsystem channel.
 *
 * Ours rather than JSch's `ChannelSftp`: that class builds a `java.time`
 * formatter in a static initializer, and `java.time` is API 26. On Android 7
 * the first directory listing would throw. The protocol a backup needs is
 * seven requests, so it is here instead, with no Android in it and a test
 * that drives it against a byte-level fake server.
 *
 * Writes and reads are pipelined, [WINDOW] requests in flight, so a large
 * backup is not one network round trip per 32 KiB.
 */
class SftpClient(input: InputStream, output: OutputStream) {

    private val input = DataInputStream(input.buffered())
    private val output = DataOutputStream(output.buffered())
    private var nextId = 1

    /** Extensions from the server's VERSION reply, `posix-rename@openssh.com` among them. */
    var extensions: Map<String, String> = emptyMap()
        private set

    class Attrs(val size: Long, val mtimeMs: Long, val directory: Boolean)

    class Entry(val name: String, val attrs: Attrs)

    fun init() {
        send(FXP_INIT) { it.writeInt(VERSION) }
        val (type, body) = receive()
        if (type != FXP_VERSION) throw IOException("SFTP: no VERSION reply")
        val version = body.readInt()
        if (version < VERSION) throw IOException("SFTP: server version $version")
        val ext = LinkedHashMap<String, String>()
        while (body.available() > 0) ext[body.string()] = body.string()
        extensions = ext
    }

    /** Attributes of [path], or null when nothing is there. */
    fun stat(path: String): Attrs? {
        val id = request(FXP_STAT) { it.string(path) }
        val (type, body) = reply(id)
        return when (type) {
            FXP_ATTRS -> body.attrs()
            FXP_STATUS -> body.status().let { if (it == FX_NO_SUCH_FILE) null else throw SftpStatusException(it, "stat") }
            else -> throw IOException("SFTP: unexpected reply $type")
        }
    }

    fun mkdir(path: String) {
        val id = request(FXP_MKDIR) {
            it.string(path)
            it.writeInt(0)
        }
        expectOk(id, "mkdir")
    }

    fun remove(path: String) {
        val id = request(FXP_REMOVE) { it.string(path) }
        expectOk(id, "remove")
    }

    /**
     * Moves [from] over [to]. The OpenSSH extension replaces atomically; plain
     * v3 refuses an existing target, so there the target goes first.
     */
    fun renameReplacing(from: String, to: String) {
        if (POSIX_RENAME in extensions) {
            val id = request(FXP_EXTENDED) {
                it.string(POSIX_RENAME)
                it.string(from)
                it.string(to)
            }
            expectOk(id, "posix-rename")
            return
        }
        runCatching { remove(to) }
        val id = request(FXP_RENAME) {
            it.string(from)
            it.string(to)
        }
        expectOk(id, "rename")
    }

    fun list(path: String): List<Entry> {
        val handle = handleOf(request(FXP_OPENDIR) { it.string(path) }, "opendir")
        val out = ArrayList<Entry>()
        try {
            while (true) {
                val id = request(FXP_READDIR) { it.bytes(handle) }
                val (type, body) = reply(id)
                if (type == FXP_STATUS) {
                    val code = body.status()
                    if (code == FX_EOF) break
                    throw SftpStatusException(code, "readdir")
                }
                if (type != FXP_NAME) throw IOException("SFTP: unexpected reply $type")
                repeat(body.readInt()) {
                    val name = body.string()
                    body.string() // longname, for humans
                    val attrs = body.attrs()
                    if (name != "." && name != "..") out += Entry(name, attrs)
                }
            }
        } finally {
            runCatching { close(handle) }
        }
        return out
    }

    /** Creates or truncates [path] and writes [data] to it. */
    fun put(path: String, data: ByteArray, onSent: (Long) -> Unit = {}) {
        val handle = handleOf(
            request(FXP_OPEN) {
                it.string(path)
                it.writeInt(FXF_WRITE or FXF_CREAT or FXF_TRUNC)
                it.writeInt(0)
            },
            "open",
        )
        var failure: Throwable? = null
        try {
            val pending = ArrayDeque<Int>()
            var offset = 0
            while (offset < data.size || pending.isNotEmpty()) {
                while (offset < data.size && pending.size < WINDOW) {
                    val n = minOf(CHUNK, data.size - offset)
                    val at = offset
                    pending += request(FXP_WRITE) {
                        it.bytes(handle)
                        it.writeLong(at.toLong())
                        it.writeInt(n)
                        it.write(data, at, n)
                    }
                    onSent(n.toLong())
                    offset += n
                }
                expectOk(pending.removeFirst(), "write")
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            // A close that fails after a failed write would hide the write's error.
            val closed = runCatching { close(handle) }
            if (failure == null) closed.getOrThrow()
        }
    }

    fun get(path: String, onReceived: (Long) -> Unit = {}): ByteArray =
        ByteArrayOutputStream().also { download(path, it, onReceived) }.toByteArray()

    /**
     * Streams [path] into [to], a window of reads in flight, for files too
     * big to hold. [to] may throw to stop; the replies still on their way
     * are then left unread, so the session must not be used again.
     */
    fun download(path: String, to: OutputStream, onReceived: (Long) -> Unit = {}) {
        val handle = handleOf(
            request(FXP_OPEN) {
                it.string(path)
                it.writeInt(FXF_READ)
                it.writeInt(0)
            },
            "open",
        )
        val out = to
        try {
            var offset = 0L
            var eof = false
            while (!eof) {
                // A window of reads at consecutive offsets. A server may answer
                // any of them short, so after a short one the rest of the window
                // is thrown away and the next window starts where it ended; at
                // the real end that costs one extra request, which says EOF.
                val window = (0 until WINDOW).map { k ->
                    val at = offset + k.toLong() * CHUNK
                    at to request(FXP_READ) {
                        it.bytes(handle)
                        it.writeLong(at)
                        it.writeInt(CHUNK)
                    }
                }
                var stop = false
                for ((at, id) in window) {
                    val (type, body) = reply(id)
                    if (stop) continue
                    when (type) {
                        FXP_DATA -> {
                            val chunk = body.bytes()
                            out.write(chunk)
                            onReceived(chunk.size.toLong())
                            offset = at + chunk.size
                            if (chunk.size < CHUNK) stop = true
                        }
                        FXP_STATUS -> {
                            val code = body.status()
                            if (code != FX_EOF) throw SftpStatusException(code, "read")
                            eof = true
                            stop = true
                        }
                        else -> throw IOException("SFTP: unexpected reply $type")
                    }
                }
            }
        } finally {
            runCatching { close(handle) }
        }
    }

    /**
     * Creates [path] and writes everything [from] holds to it, a window of
     * writes in flight. Refused when anything is already called [path]
     * (`EXCL`): this never writes over a file.
     */
    fun upload(path: String, from: InputStream, onSent: (Long) -> Unit = {}) {
        val handle = handleOf(
            request(FXP_OPEN) {
                it.string(path)
                it.writeInt(FXF_WRITE or FXF_CREAT or FXF_EXCL)
                it.writeInt(0)
            },
            "open",
        )
        var failure: Throwable? = null
        try {
            val pending = ArrayDeque<Int>()
            var offset = 0L
            var done = false
            val chunk = ByteArray(CHUNK)
            while (!done || pending.isNotEmpty()) {
                while (!done && pending.size < WINDOW) {
                    var n = 0
                    while (n < CHUNK) {
                        val r = from.read(chunk, n, CHUNK - n)
                        if (r < 0) break
                        n += r
                    }
                    if (n < CHUNK) done = true
                    if (n == 0) break
                    val at = offset
                    val size = n
                    pending += request(FXP_WRITE) {
                        it.bytes(handle)
                        it.writeLong(at)
                        it.writeInt(size)
                        it.write(chunk, 0, size)
                    }
                    onSent(n.toLong())
                    offset += n
                }
                if (pending.isNotEmpty()) expectOk(pending.removeFirst(), "write")
            }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            val closed = runCatching { close(handle) }
            if (failure == null) closed.getOrThrow()
        }
    }

    /** Removes the empty folder [path]. */
    fun rmdir(path: String) {
        expectOk(request(FXP_RMDIR) { it.string(path) }, "rmdir")
    }

    /**
     * Renames [from] to [to] with plain version 3 `RENAME`, which refuses a
     * [to] that exists (OpenSSH links then unlinks), never the replacing
     * `posix-rename` extension.
     */
    fun renameStrict(from: String, to: String) {
        val id = request(FXP_RENAME) {
            it.string(from)
            it.string(to)
        }
        expectOk(id, "rename")
    }

    /** The server's absolute form of [path]; `.` is where the login lands. */
    fun realpath(path: String): String {
        val id = request(FXP_REALPATH) { it.string(path) }
        val (type, body) = reply(id)
        return when (type) {
            FXP_NAME -> {
                body.readInt()
                body.string()
            }
            FXP_STATUS -> throw SftpStatusException(body.status(), "realpath")
            else -> throw IOException("SFTP: realpath got reply $type")
        }
    }

    private fun close(handle: ByteArray) {
        expectOk(request(FXP_CLOSE) { it.bytes(handle) }, "close")
    }

    // ---- Wire format ----

    private fun request(type: Int, write: (DataOutputStream) -> Unit): Int {
        val id = nextId++
        send(type) {
            it.writeInt(id)
            write(it)
        }
        return id
    }

    private fun send(type: Int, write: (DataOutputStream) -> Unit) {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).also { it.writeByte(type) }.also(write).flush()
        output.writeInt(payload.size())
        payload.writeTo(output)
        output.flush()
    }

    private fun receive(): Pair<Int, DataInputStream> {
        val length = input.readInt()
        if (length < 1 || length > MAX_PACKET) throw IOException("SFTP: bad packet length $length")
        val packet = ByteArray(length)
        input.readFully(packet)
        val body = DataInputStream(packet.inputStream(1, length - 1))
        return (packet[0].toInt() and 0xFF) to body
    }

    /** The reply to [id]. Replies arrive in request order on every real server. */
    private fun reply(id: Int): Pair<Int, DataInputStream> {
        val (type, body) = receive()
        val got = body.readInt()
        if (got != id) throw IOException("SFTP: reply $got out of order, wanted $id")
        return type to body
    }

    private fun expectOk(id: Int, what: String) {
        val (type, body) = reply(id)
        if (type != FXP_STATUS) throw IOException("SFTP: $what got reply $type")
        val code = body.status()
        if (code != FX_OK) throw SftpStatusException(code, what)
    }

    private fun handleOf(id: Int, what: String): ByteArray {
        val (type, body) = reply(id)
        return when (type) {
            FXP_HANDLE -> body.bytes()
            FXP_STATUS -> throw SftpStatusException(body.status(), what)
            else -> throw IOException("SFTP: $what got reply $type")
        }
    }

    private fun DataOutputStream.string(s: String) = bytes(s.toByteArray(Charsets.UTF_8))

    private fun DataOutputStream.bytes(b: ByteArray) {
        writeInt(b.size)
        write(b)
    }

    private fun DataInputStream.bytes(): ByteArray {
        val n = readInt()
        if (n < 0 || n > MAX_PACKET) throw IOException("SFTP: bad string length $n")
        return ByteArray(n).also { readFully(it) }
    }

    private fun DataInputStream.string(): String = String(bytes(), Charsets.UTF_8)

    private fun DataInputStream.status(): Int = readInt()

    private fun DataInputStream.attrs(): Attrs {
        val flags = readInt()
        var size = -1L
        var mtime = 0L
        var directory = false
        if (flags and ATTR_SIZE != 0) size = readLong()
        if (flags and ATTR_UIDGID != 0) {
            readInt()
            readInt()
        }
        if (flags and ATTR_PERMISSIONS != 0) directory = readInt() and S_IFMT == S_IFDIR
        if (flags and ATTR_ACMODTIME != 0) {
            readInt()
            mtime = (readInt().toLong() and 0xFFFFFFFFL) * 1000L
        }
        if (flags and ATTR_EXTENDED != 0) {
            repeat(readInt()) {
                string()
                string()
            }
        }
        return Attrs(size, mtime, directory)
    }

    companion object {
        private const val VERSION = 3
        const val CHUNK = 32 * 1024
        private const val WINDOW = 16
        private const val MAX_PACKET = 256 * 1024
        private const val POSIX_RENAME = "posix-rename@openssh.com"

        private const val FXP_INIT = 1
        private const val FXP_VERSION = 2
        private const val FXP_OPEN = 3
        private const val FXP_CLOSE = 4
        private const val FXP_READ = 5
        private const val FXP_WRITE = 6
        private const val FXP_OPENDIR = 11
        private const val FXP_READDIR = 12
        private const val FXP_REMOVE = 13
        private const val FXP_MKDIR = 14
        private const val FXP_RMDIR = 15
        private const val FXP_REALPATH = 16
        private const val FXP_STAT = 17
        private const val FXP_RENAME = 18
        private const val FXP_STATUS = 101
        private const val FXP_HANDLE = 102
        private const val FXP_DATA = 103
        private const val FXP_NAME = 104
        private const val FXP_ATTRS = 105
        private const val FXP_EXTENDED = 200

        private const val FXF_READ = 0x1
        private const val FXF_WRITE = 0x2
        private const val FXF_CREAT = 0x8
        private const val FXF_TRUNC = 0x10
        private const val FXF_EXCL = 0x20

        private const val ATTR_SIZE = 0x1
        private const val ATTR_UIDGID = 0x2
        private const val ATTR_PERMISSIONS = 0x4
        private const val ATTR_ACMODTIME = 0x8
        private const val ATTR_EXTENDED = 0x80000000.toInt()
        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000

        const val FX_OK = 0
        const val FX_EOF = 1
        const val FX_NO_SUCH_FILE = 2
        const val FX_PERMISSION_DENIED = 3
        const val FX_FAILURE = 4
    }
}

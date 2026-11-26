package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.FtpListing
import com.wasimaster.wmkeyboard.core.settings.sink.FtpSink
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * An FTP server's files, through the backup's own FTP session
 * ([FtpSink.Session]): the same TLS (`AUTH TLS` and a protected data channel
 * unless the location turned it off), the same passive mode, binary type.
 *
 * The top is wherever the login lands, as `PWD` reports it, and every
 * command after that names an absolute path built from it, so nothing
 * depends on the working directory. One control connection stays open
 * between calls and opens on the first.
 *
 * FTP has two ways to lose a file that the rest of the Files tool does not
 * expect, and both are closed here: `STOR` replaces whatever has the name,
 * and on most servers `RNTO` does too. So both look first, and a name that
 * is taken, in any case, is refused. A listing is not enough to look with:
 * `LIST` leaves out dot files on many servers (vsftpd unless asked with
 * `-a`), and vsftpd's `hide_file` keeps names out of every listing while
 * they stay reachable, so the exact path is asked too ([probe]).
 *
 * A login whose `PWD` is not a `/` path (VMS, some mainframes) is refused
 * at connection rather than guessed at.
 */
class FtpFs(
    @Suppress("UNUSED_PARAMETER") context: Context,
    location: BackupLocation,
) : RemoteFs {

    private val config = location.ftp

    private var session: FtpSink.Session? = null

    /** The login directory, absolute, as `PWD` said it. */
    private var home = "/"

    /** Whether `FEAT` offered MLST, so `MLSD` and `MLST` can be relied on rather than tried. */
    private var machineListings = false

    /** Whether `SYST` named Windows, whose file systems fold case. */
    private var windows = false

    /**
     * The name of an upload this refused because something already had it.
     * [com.wasimaster.wmkeyboard.core.files.RemoteFiles] removes a failed
     * upload's file next, and here that file is not ours: the delete is
     * skipped once.
     */
    private var refusedUpload: List<String>? = null

    override val caseInsensitive: Boolean
        get() = runCatching { connected(); windows }.getOrDefault(false)

    /** The open session, or a new one: logged in, its home and abilities learned. */
    private fun connected(): FtpSink.Session {
        session?.let { return it }
        if (config.host.isEmpty() || config.user.isEmpty()) throw IOException("FTP is not set up")
        val opened = FtpSink.Session(config)
        try {
            home = parsePwd(opened.command("PWD")) ?: throw IOException("the server did not say where the login lands")
            val features = opened.command("FEAT")
            val offered = if (codeOf(features) == 211) features.lines().drop(1).map { it.trim().uppercase(Locale.US) } else emptyList()
            machineListings = offered.any { it.startsWith("MLST") }
            // Names cross as UTF-8 either way; some servers (IIS, FileZilla)
            // only read them as UTF-8 once asked to.
            if (offered.any { it == "UTF8" }) opened.command("OPTS UTF8 ON")
            windows = opened.command("SYST").contains("windows", ignoreCase = true)
        } catch (failure: Throwable) {
            runCatching { opened.close() }
            throw failure
        }
        session = opened
        return opened
    }

    /** The absolute path of [path], every name in it checked first. */
    private fun abs(path: List<String>): String {
        path.forEach(::checkName)
        if (path.isEmpty()) return home
        return home.trimEnd('/') + "/" + path.joinToString("/")
    }

    override fun list(dir: List<String>): List<RemoteEntry> {
        refusedUpload = null
        return listing(dir)
    }

    private fun listing(dir: List<String>): List<RemoteEntry> {
        val ftp = connected()
        val target = abs(dir)
        val found = if (machineListings) {
            val lines = ftp.dataCommand("MLSD $target").toString(Charsets.UTF_8).lines()
            // The folder itself and its parent, which some servers list under their full paths.
            FtpListing.parseMlsd(lines.filterNot(::isSelfOrParent))
        } else {
            // LIST takes options as well as a path on many servers, and spaces
            // confuse some, so it lists the working directory instead. "-a"
            // for dot files; a server that takes it for a name answers with
            // an error or nothing, and is asked plainly.
            expectOk(ftp.command("CWD $target"))
            val now = System.currentTimeMillis()
            runCatching { FtpListing.parseList(ftp.dataCommand("LIST -a").toString(Charsets.UTF_8).lines(), now) }
                .getOrNull()?.takeIf { all -> all.any { it.name != "." && it.name != ".." } }
                ?: FtpListing.parseList(ftp.dataCommand("LIST").toString(Charsets.UTF_8).lines(), now)
        }
        return found
            .filter { it.name.isNotEmpty() && '/' !in it.name && it.name != "." && it.name != ".." }
            .map { RemoteEntry(it.name, it.isDirectory, if (it.isDirectory) -1L else it.sizeBytes, it.modifiedAtMs) }
    }

    override fun stat(path: List<String>): RemoteEntry? {
        refusedUpload = null
        return lookup(path)
    }

    private fun lookup(path: List<String>): RemoteEntry? {
        val ftp = connected()
        if (path.isEmpty()) {
            // Asked of the server, so a stale connection is found out here.
            expectOk(ftp.command("NOOP"))
            return RemoteEntry("", isDirectory = true)
        }
        val name = path.last()
        if (machineListings) {
            val reply = ftp.command("MLST ${abs(path)}")
            if (codeOf(reply) in 200..299) mlstEntry(reply, name)?.let { return it }
            // 550 is both "not there" and "not allowed": the folder's listing tells which.
        }
        val siblings = listing(path.dropLast(1))
        return siblings.firstOrNull { it.name == name }
            ?: (if (windows) siblings.firstOrNull { it.name.equals(name, ignoreCase = true) } else null)
            // Not listed is not absent: a dot file or a hidden name, such as the store's own upload in progress.
            ?: probe(path)
    }

    /**
     * What the exact [path] is, asked of it rather than of a listing: a
     * folder if `CWD` goes in (every command names an absolute path, so
     * where it leaves the session does not matter), a file if `SIZE` or
     * `MDTM` answers. Null when none does.
     */
    private fun probe(path: List<String>): RemoteEntry? {
        val ftp = connected()
        val target = abs(path)
        if (codeOf(ftp.command("CWD $target")) in 200..299) return RemoteEntry(path.last(), isDirectory = true)
        val modified = ftp.command("MDTM $target").takeIf { codeOf(it) == 213 }?.substring(3)?.trim()?.let(FtpListing::parseMlsdTime)
        val size = ftp.command("SIZE $target").takeIf { codeOf(it) == 213 }?.substring(3)?.trim()?.toLongOrNull()
        if (size == null && modified == null) return null
        return RemoteEntry(path.last(), isDirectory = false, size = size ?: -1L, modifiedMs = modified ?: 0L)
    }

    /**
     * Whether anything in [dir] has [name], in any case, leaving out
     * [except] (the thing being renamed): the listing, then the exact path.
     */
    private fun taken(dir: List<String>, name: String, except: String? = null): Boolean {
        if (listing(dir).any { it.name != except && it.name.equals(name, ignoreCase = true) }) return true
        // A change of case alone: on a server that folds case the exact path is the source itself.
        if (except != null && except.equals(name, ignoreCase = true)) return false
        return probe(dir + name) != null
    }

    override fun download(path: List<String>, to: OutputStream) {
        refusedUpload = null
        if (path.isEmpty()) throw IOException("the top is a folder")
        connected().retrieveTo(abs(path), to)
    }

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        refusedUpload = null
        checkName(name)
        val ftp = connected()
        // STOR replaces a file of the same name. The store chose a free one;
        // this makes sure, in any case, since some servers fold it.
        if (taken(dir, name)) {
            refusedUpload = dir + name
            throw IOException("$name is already there")
        }
        from.inputStream().use { ftp.storeFrom(abs(dir + name), it, onSent) }
    }

    override fun mkdir(dir: List<String>, name: String) {
        refusedUpload = null
        checkName(name)
        expectOk(connected().command("MKD ${abs(dir + name)}"))
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (path.isEmpty()) throw IOException("the top cannot be removed")
        if (refusedUpload == path) {
            refusedUpload = null
            return
        }
        refusedUpload = null
        val reply = connected().command((if (isDirectory) "RMD " else "DELE ") + abs(path))
        if (codeOf(reply) in 200..299) return
        // A refusal is often "no such file"; only one that is gone counts as done.
        if (lookup(path) == null) return
        throw IOException("the server would not remove it: ${reply.take(REPLY_SHOWN)}")
    }

    override fun rename(path: List<String>, newName: String) {
        refusedUpload = null
        if (path.isEmpty()) throw IOException("the top cannot be renamed")
        checkName(newName)
        val dir = path.dropLast(1)
        // RNTO replaces the target on most servers (it is rename(2) on Unix).
        // A name that differs only in case is refused too: on a server whose
        // disk folds case it is the same file, and SYST does not say which.
        if (taken(dir, newName, except = path.last())) throw IOException("$newName is already there")
        val ftp = connected()
        val from = ftp.command("RNFR ${abs(path)}")
        if (codeOf(from) != 350) throw IOException("the server would not rename it: ${from.take(REPLY_SHOWN)}")
        expectOk(ftp.command("RNTO ${abs(dir + newName)}"))
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        refusedUpload = null
        if (path.isEmpty()) return false
        val name = path.last()
        // Same look as rename: RNTO into a folder that has the name would replace it.
        if (taken(toDir, name)) return false
        val ftp = connected()
        if (codeOf(ftp.command("RNFR ${abs(path)}")) != 350) return false
        // A server that cannot move across folders says so here, and the
        // store copies instead; the source is untouched either way.
        return codeOf(ftp.command("RNTO ${abs(toDir + name)}")) in 200..299
    }

    override fun close() {
        refusedUpload = null
        val open = session ?: return
        session = null
        runCatching { open.close() }
    }

    private fun expectOk(reply: String) {
        if (codeOf(reply) !in 200..299) throw IOException("the server said ${reply.take(REPLY_SHOWN)}")
    }

    companion object {
        private const val REPLY_SHOWN = 200

        private fun codeOf(reply: String): Int = reply.take(3).toIntOrNull() ?: 0

        /**
         * Refuses a name that would address something else: a slash is
         * another folder, a line break would end the command and start
         * another, and spaces at either end are trimmed by some servers into
         * a different file's name.
         */
        private fun checkName(name: String) {
            if (name.isEmpty() || name == "." || name == ".." || name.trim() != name ||
                name.any { it == '/' || it == '\r' || it == '\n' || it == '\u0000' }
            ) {
                throw IOException("not a name FTP can carry: $name")
            }
        }

        /**
         * The directory out of `257 "/home/me" is the current directory`,
         * where a quote inside the name is doubled. Null when there is none.
         */
        internal fun parsePwd(reply: String): String? {
            if (codeOf(reply) != 257) return null
            val open = reply.indexOf('"')
            if (open < 0) return null
            val out = StringBuilder()
            var i = open + 1
            while (i < reply.length) {
                val c = reply[i]
                if (c == '"') {
                    if (i + 1 < reply.length && reply[i + 1] == '"') {
                        out.append('"')
                        i += 2
                        continue
                    }
                    return out.toString().takeIf { it.startsWith("/") }
                }
                out.append(c)
                i++
            }
            return null
        }

        /** An MLSD line for the listed folder itself or its parent (`type=cdir`, `type=pdir`). */
        private fun isSelfOrParent(line: String): Boolean =
            line.substringBefore(' ').split(';').any {
                it.equals("type=cdir", ignoreCase = true) || it.equals("type=pdir", ignoreCase = true)
            }

        /**
         * The facts line of an `MLST` reply (`250-…`, then ` type=file;size=3; /path`,
         * then `250 End`) as an entry called [name]. Null when there is no such line.
         */
        private fun mlstEntry(reply: String, name: String): RemoteEntry? {
            val line = reply.lines().drop(1).firstOrNull { it.startsWith(" ") }?.substring(1) ?: return null
            val facts = line.substringBefore(' ').split(';').mapNotNull { fact ->
                val eq = fact.indexOf('=')
                if (eq <= 0) null else fact.substring(0, eq).lowercase(Locale.US) to fact.substring(eq + 1)
            }.toMap()
            val type = facts["type"]?.lowercase(Locale.US) ?: return null
            val directory = type == "dir" || type == "cdir" || type == "pdir"
            return RemoteEntry(
                name = name,
                isDirectory = directory,
                size = if (directory) -1L else facts["size"]?.toLongOrNull() ?: -1L,
                modifiedMs = facts["modify"]?.let(FtpListing::parseMlsdTime) ?: 0L,
            )
        }
    }
}

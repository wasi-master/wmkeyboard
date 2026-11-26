package com.wasimaster.wmkeyboard.core.files

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A file or folder in a [RemoteFs], as its service reports it. */
data class RemoteEntry(
    val name: String,
    val isDirectory: Boolean,
    /** -1 when the service does not say. */
    val size: Long = -1,
    /** 0 when the service does not say. */
    val modifiedMs: Long = 0,
    /**
     * The service's own id for it, where one folder can hold two things of
     * the same name (Drive, MEGA): what tells them apart. Null elsewhere.
     */
    val key: String? = null,
)

/**
 * One server's or cloud's files, for the Files tool: the same places the
 * automatic backup writes to (WebDAV, SFTP, SMB, S3, Dropbox and the rest),
 * browsed and changed like a folder on the phone.
 *
 * A path is the list of names from the place's top folder down; the empty
 * list is the top. Every call blocks, and [RemoteFiles] makes them one at a
 * time per place, on a background thread, so an adapter needs no locking of
 * its own and may keep one connection open between calls. Any exception
 * means the call did not happen as asked; [RemoteFiles] then closes this
 * session and opens a new one for the next call.
 *
 * What keeps files safe is in [RemoteFiles], not here: a new file arrives
 * under a temporary name and takes its real name only once its size checks
 * out, a name is never written over, and a move deletes its source only once
 * the copy is whole. Adapters keep to the contract below and leave the policy
 * to it.
 */
interface RemoteFs : Closeable {

    /** What the folder [dir] holds. Throws when it cannot be read; an empty folder is an empty list. */
    fun list(dir: List<String>): List<RemoteEntry>

    /** What is at [path], or null when nothing is. Throws only when the service cannot be asked. */
    fun stat(path: List<String>): RemoteEntry?

    /** Writes the whole file at [path] to [to]. [to] may throw to stop early; let it through. */
    fun download(path: List<String>, to: OutputStream)

    /**
     * Stores [from], all [File.length] bytes of it, as [name] in [dir].
     * [name] is free: nothing in [dir] has it. Throws on any failure. Calls
     * [onSent] with byte counts as they go; it may throw to stop the upload.
     */
    fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit)

    /** Makes the folder [name] in [dir]. [name] is free. */
    fun mkdir(dir: List<String>, name: String)

    /**
     * Removes the file at [path], or the folder at [path], which [RemoteFiles]
     * has emptied first. Something already gone counts as removed.
     */
    fun delete(path: List<String>, isDirectory: Boolean)

    /**
     * Renames [path] in its folder to [newName], which is free. Must never
     * replace something already called [newName]: where the service would,
     * it refuses instead.
     */
    fun rename(path: List<String>, newName: String)

    /**
     * Moves [path] into the folder [toDir] of this same place, keeping its
     * name, which is free there, when the service can do that itself. False
     * (the default) has [RemoteFiles] copy it and delete the original.
     */
    fun move(path: List<String>, toDir: List<String>): Boolean = false

    /**
     * Whether a finished [upload] appears whole or not at all (an S3 PUT, a
     * Dropbox or Drive upload), so it can go straight to its name. False has
     * [RemoteFiles] upload under a temporary name and [rename] it.
     */
    val atomicUpload: Boolean get() = false

    /** Whether the service treats names that differ only in case as one (SMB, iCloud). */
    val caseInsensitive: Boolean get() = false

    /**
     * Which one each path in [picks] means, by its [RemoteEntry.key], where
     * its folder holds more than one thing of that name. Set before every
     * call and cleared after, from the path the call is about. A service
     * that allows such twins resolves a picked path to that key only, and
     * to nothing when it is gone, never to its namesake; a twin's path with
     * no pick is refused rather than guessed. Others ignore it.
     */
    fun pick(picks: Map<List<String>, String>) {}

    override fun close() {}
}

/** A place offered in the Files tool: one backup location that can be browsed. */
data class RemotePlace(
    /** The backup location's id: stable, and the first part of every path in it. */
    val id: String,
    val label: String,
    /** The location's type id ("webdav", "sftp", "dropbox"), for its icon. */
    val type: String,
)

/** Where the places come from; set at start by the app, which knows the backup locations. */
interface RemotePlaces {
    fun places(context: Context): List<RemotePlace>

    /** A connected session for the place [id], or null when it is gone or this build cannot reach it. */
    fun open(context: Context, id: String): RemoteFs?
}

/**
 * The Files tool's servers and clouds: every path in one is a `content://`
 * URI of [RemoteFilesProvider], `content://<package>.remotefiles/<place>/<a>/<b>`,
 * so the file manager carries it as it carries a document's, the trail of
 * folders it came down and all, and hands it to previews, Send and Share as
 * a URI that can be read. Reading through that URI fetches the file into
 * the cache; writing never goes through it.
 *
 * Writes go through here, and here is where nothing gets lost:
 *
 * - A file is read whole into a local copy before the server is touched, so
 *   a source that fails halfway changes nothing there.
 * - It is uploaded under a hidden temporary name, its size is checked, and
 *   only then does it take its real name. A failure or a stop removes the
 *   temporary file; the real name never points at half a file.
 * - A name already taken is never written over: a copy gets `name (1)`, and
 *   Replace in the file manager removes the old one only once the new one
 *   is whole ([DocFiles.transfer]).
 * - A move deletes its source only after its copy has arrived and checked out.
 * - Folders are removed one entry at a time, stopping at the first refusal.
 * - Where a folder holds two things of one name (Drive and MEGA allow it),
 *   each one's path carries its service id after the name ([TWIN]), so
 *   whatever is done to a row is done to that row's file, and never to
 *   the other one of its name.
 */
object RemoteFiles {

    @Volatile
    var source: RemotePlaces? = null

    private const val AUTHORITY_SUFFIX = ".remotefiles"
    private const val PART_MARK = ".wmpart-"
    private const val IDLE_MS = 90_000L

    /**
     * Between a twin's name and its key in a path segment. No service allows
     * it in a name, so a segment with one is never a real name.
     */
    private const val TWIN = '\u0000'

    /** Whether [path] is in a server or a cloud rather than on the phone or behind a document provider. */
    fun isRemote(path: String): Boolean =
        path.startsWith("content://") && Uri.parse(path).authority?.endsWith(AUTHORITY_SUFFIX) == true

    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    /** The path of [segments] in the place [placeId]. */
    fun pathOf(context: Context, placeId: String, segments: List<String>): String {
        val builder = Uri.Builder().scheme("content").authority(authority(context)).appendPath(placeId)
        segments.forEach { builder.appendPath(it) }
        return builder.build().toString()
    }

    /**
     * The place and the segments under it, or null when [path] is not a
     * remote one. A twin's segment still carries its key; [plain] takes it off.
     */
    fun parse(path: String): Pair<String, List<String>>? {
        if (!isRemote(path)) return null
        val parts = Uri.parse(path).pathSegments
        val place = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return place to parts.drop(1)
    }

    /** The names of [segments], each without a twin's key. */
    private fun plain(segments: List<String>): List<String> = segments.map { it.substringBefore(TWIN) }

    /** The file or folder name [path] ends in, as it shows. */
    fun nameOf(path: String): String = parse(path)?.second?.lastOrNull()?.substringBefore(TWIN).orEmpty()

    /**
     * The twins on the way down each of [paths], by their names, and the key
     * each was picked by. Null when two of them pick one path differently,
     * which one call cannot say to a service.
     */
    private fun picksOf(vararg paths: List<String>): Map<List<String>, String>? {
        val out = HashMap<List<String>, String>()
        for (segments in paths) {
            segments.forEachIndexed { i, segment ->
                if (TWIN !in segment) return@forEachIndexed
                val at = plain(segments.subList(0, i + 1))
                val key = segment.substringAfter(TWIN)
                if (out.put(at, key)?.let { it != key } == true) return null
            }
        }
        return out
    }

    fun places(context: Context): List<RemotePlace> =
        runCatching { source?.places(context.applicationContext).orEmpty() }.getOrDefault(emptyList())

    /** The top folder of [place], as the file manager opens it. */
    fun rootOf(context: Context, place: RemotePlace): String = pathOf(context, place.id, emptyList())

    // ---- Sessions ------------------------------------------------------------

    private class Slot {
        val lock = ReentrantLock()
        var fs: RemoteFs? = null
        var lastUse = 0L
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val closer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "remote-files-idle").apply { isDaemon = true } }

    init {
        // A server keeps a connection open for us only so long; one left idle is let go.
        closer.scheduleWithFixedDelay({ closeIdle() }, IDLE_MS, IDLE_MS / 2, TimeUnit.MILLISECONDS)
    }

    private fun closeIdle() {
        val now = System.currentTimeMillis()
        for (slot in slots.values) {
            if (!slot.lock.tryLock()) continue
            try {
                if (slot.fs != null && now - slot.lastUse > IDLE_MS) drop(slot)
            } finally {
                slot.lock.unlock()
            }
        }
    }

    private fun drop(slot: Slot) {
        runCatching { slot.fs?.close() }
        slot.fs = null
    }

    /** Lets every session go: a changed backup location must not keep its old connection. */
    fun closeAll() {
        for (slot in slots.values) slot.lock.withLock { drop(slot) }
    }

    /** Lets the session of [placeId] go, once any call on it now has finished. */
    fun close(placeId: String) {
        val slot = slots[placeId] ?: return
        slot.lock.withLock { drop(slot) }
    }

    /**
     * Runs [block] on the place's session, alone. A connection that was idle
     * is checked first and opened again when the server dropped it; any
     * failure inside [block] closes the session, so the next call starts
     * clean rather than mid-way through a broken exchange. [retry] reruns a
     * read once on a fresh connection when the first attempt failed.
     */
    private fun <T> use(
        context: Context,
        placeId: String,
        retry: Boolean,
        picks: Map<List<String>, String> = emptyMap(),
        block: (RemoteFs) -> T,
    ): T {
        val slot = slots.getOrPut(placeId) { Slot() }
        slot.lock.withLock {
            // A connection quiet for a while is asked first, and opened again
            // when the server hung up on it: a write must not be the call
            // that finds that out.
            val idle = slot.fs
            if (idle != null && System.currentTimeMillis() - slot.lastUse > STALE_MS) {
                if (runCatching { idle.stat(emptyList()) }.isFailure) drop(slot)
            }
            var attempt = 0
            while (true) {
                attempt++
                val fresh = slot.fs == null
                val fs = slot.fs ?: (source?.open(context.applicationContext, placeId) ?: throw IOException("no such place: $placeId"))
                    .also { slot.fs = it }
                try {
                    fs.pick(picks)
                    val result = try {
                        block(fs)
                    } finally {
                        fs.pick(emptyMap())
                    }
                    slot.lastUse = System.currentTimeMillis()
                    return result
                } catch (failure: Throwable) {
                    drop(slot)
                    if (failure is InterruptedException || failure is kotlinx.coroutines.CancellationException) throw failure
                    if (!retry || attempt >= 2 || fresh) throw failure
                }
            }
        }
    }

    private const val STALE_MS = 20_000L

    // ---- Reading -------------------------------------------------------------

    /** What [path] is; the place's top folder is always a folder. Null when nothing is there or it cannot be asked. */
    fun entry(context: Context, path: String): FileEntry? {
        val (place, segments) = parse(path) ?: return null
        if (segments.isEmpty()) {
            val label = places(context).firstOrNull { it.id == place }?.label ?: place
            return FileEntry(path, label, true, 0, 0, "")
        }
        val picks = picksOf(segments) ?: return null
        val found = runCatching { use(context, place, retry = true, picks) { it.stat(plain(segments)) } }.getOrNull() ?: return null
        // The path asked about, a twin's key and all, rather than one made again from the name.
        return entryOf(context, place, segments.dropLast(1), found).copy(path = path)
    }

    /** What the folder [path] holds, unsorted; null when it cannot be read (not the same as empty). */
    fun list(context: Context, path: String): List<FileEntry>? {
        val (place, segments) = parse(path) ?: return null
        val picks = picksOf(segments) ?: return null
        val found = runCatching { use(context, place, retry = true, picks) { it.list(plain(segments)) } }
            .onFailure { android.util.Log.w("RemoteFiles", "list failed", it) }
            .getOrNull() ?: return null
        // Our own unfinished uploads stay out of sight: they are not files yet.
        val shown = found.filterNot { it.name.contains(PART_MARK) || TWIN in it.name }
        // Two of one name each get their key in their path, so each row is its own file.
        val twins = shown.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        return shown.map { e ->
            val twin = e.name in twins && e.key != null
            entryOf(context, place, segments, e, if (twin) "${e.name}$TWIN${e.key}" else e.name)
        }
    }

    private fun entryOf(context: Context, place: String, dir: List<String>, e: RemoteEntry, segment: String = e.name): FileEntry = FileEntry(
        path = pathOf(context, place, dir + segment),
        name = e.name,
        isDirectory = e.isDirectory,
        size = if (e.isDirectory) 0 else e.size.coerceAtLeast(0),
        modifiedMs = e.modifiedMs,
        mime = if (e.isDirectory) "" else FilePlaces.mimeOf(e.name).orEmpty(),
    )

    /**
     * The file at [path], fetched into a private copy that goes when the
     * stream is closed. [limit] stops after that many bytes, for a text
     * preview of a long file. Null when it cannot be read.
     */
    fun input(context: Context, path: String, limit: Long = -1): InputStream? {
        val (place, segments) = parse(path) ?: return null
        if (segments.isEmpty()) return null
        // A whole read shares the provider's copy, so a preview and its facts fetch it once.
        if (limit < 0) return cached(context, path)?.let { FileInputStream(it) }
        val picks = picksOf(segments) ?: return null
        val temp = File.createTempFile("read", ".tmp", ioDir(context))
        val ok = runCatching {
            temp.outputStream().buffered().use { out ->
                val capped = if (limit >= 0) Capped(out, limit) else out
                try {
                    use(context, place, retry = true, picks) { it.download(plain(segments), capped) }
                } catch (enough: Capped.Enough) {
                    // As much as was asked for; the session was closed under it, and opens again next time.
                }
            }
        }.isSuccess
        if (!ok) {
            temp.delete()
            return null
        }
        return object : FileInputStream(temp) {
            override fun close() {
                super.close()
                temp.delete()
            }
        }
    }

    /** Stops a download once [limit] bytes are in, by throwing [Enough]. */
    private class Capped(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
        class Enough : IOException("enough")

        private var written = 0L

        override fun write(b: Int) {
            if (written >= limit) throw Enough()
            out.write(b)
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written >= limit) throw Enough()
            val n = minOf(len.toLong(), limit - written).toInt()
            out.write(b, off, n)
            written += n
            if (n < len) throw Enough()
        }
    }

    /**
     * The file at [path] in the cache, whole, for the provider: the copy made
     * before when the server still reports the same size and time. Null when
     * it cannot be fetched.
     */
    fun cached(context: Context, path: String): File? {
        val (place, segments) = parse(path) ?: return null
        if (segments.isEmpty()) return null
        val picks = picksOf(segments) ?: return null
        val stat = runCatching { use(context, place, retry = true, picks) { it.stat(plain(segments)) } }.getOrNull() ?: return null
        if (stat.isDirectory) return null
        val dir = File(context.cacheDir, "remote-files").apply { mkdirs() }
        val key = sha1("$path|${stat.size}|${stat.modifiedMs}")
        val name = plain(segments).last().replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
        val file = File(dir, "$key-$name")
        // One fetch per file at a time: a thumbnail and the preview asking together share it.
        val gate = fetching.getOrPut(key) { Any() }
        synchronized(gate) {
            try {
                return fetch(context, place, segments, picks, stat, dir, key, file)
            } finally {
                fetching.remove(key, gate)
            }
        }
    }

    private val fetching = ConcurrentHashMap<String, Any>()

    private fun fetch(
        context: Context,
        place: String,
        segments: List<String>,
        picks: Map<List<String>, String>,
        stat: RemoteEntry,
        dir: File,
        key: String,
        file: File,
    ): File? {
        if (file.isFile && (stat.size < 0 || file.length() == stat.size)) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val temp = File(dir, "$key.part")
        val ok = runCatching {
            temp.outputStream().buffered().use { out -> use(context, place, retry = true, picks) { it.download(plain(segments), out) } }
        }.isSuccess && (stat.size < 0 || temp.length() == stat.size)
        if (!ok || !temp.renameTo(file)) {
            temp.delete()
            return null
        }
        prune(dir, keep = file)
        return file
    }

    /** Oldest first until the folder fits its share of the cache; never [keep], the copy just asked for. */
    private fun prune(dir: File, keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it != keep }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() } + keep.length()
        for (f in files) {
            if (total <= CACHE_BUDGET) break
            total -= f.length()
            f.delete()
        }
    }

    private const val CACHE_BUDGET = 512L * 1024 * 1024

    // ---- Changing --------------------------------------------------------------

    /**
     * Writes a new file called [name] (or `name (1)` when taken) into the
     * folder [dir], from what [body] writes, and returns its path. [body]
     * runs first, into a local copy; the server sees nothing until it has
     * finished. Null, with nothing left behind on the server, when any part
     * fails. [onSent] hears bytes uploaded; [check] may throw to stop.
     */
    fun write(
        context: Context,
        dir: String,
        name: String,
        body: (OutputStream) -> Unit,
        onSent: (Long) -> Unit = {},
        check: () -> Unit = {},
    ): String? {
        val (place, raw) = parse(dir) ?: return null
        val picks = picksOf(raw) ?: return null
        val segments = plain(raw)
        val temp = File.createTempFile("write", ".tmp", ioDir(context))
        // The hidden name it goes up under, known out here so a broken
        // connection's leftover can be cleared on a fresh one.
        var staged: String? = null
        try {
            temp.outputStream().buffered().use(body)
            check()
            return use(context, place, retry = false, picks) { fs ->
                val final = freeName(fs, segments, name)
                val part = if (fs.atomicUpload) null else ".$final$PART_MARK${token()}"
                staged = part
                upload(fs, segments, final, part, temp, onSent, check)
                pathOf(context, place, raw + final)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            clearStaged(context, place, segments, picks, staged)
            throw e
        } catch (e: Exception) {
            android.util.Log.w("RemoteFiles", "write failed", e)
            clearStaged(context, place, segments, picks, staged)
            return null
        } finally {
            temp.delete()
        }
    }

    /** A temporary upload left by a failed write, removed over a fresh connection; never a real name. */
    private fun clearStaged(context: Context, place: String, dir: List<String>, picks: Map<List<String>, String>, staged: String?) {
        if (staged == null || !staged.contains(PART_MARK)) return
        runCatching { use(context, place, retry = true, picks) { fs -> if (fs.stat(dir + staged) != null) fs.delete(dir + staged, false) } }
    }

    /**
     * The upload itself, under the rules above: a temporary name unless the
     * service's uploads are all-or-nothing, the size checked, then the real
     * name. Whatever fails takes its temporary file with it.
     */
    private fun upload(fs: RemoteFs, dir: List<String>, name: String, part: String?, from: File, onSent: (Long) -> Unit, check: () -> Unit) {
        val staged = part ?: name
        val counting: (Long) -> Unit = { n ->
            check()
            onSent(n)
        }
        var uploaded = false
        try {
            fs.upload(dir, staged, from, counting)
            uploaded = true
            val landed = fs.stat(dir + staged) ?: throw IOException("the upload is not there")
            if (landed.isDirectory || (landed.size >= 0 && landed.size != from.length())) {
                throw IOException("the upload is ${landed.size} bytes, not ${from.length()}")
            }
            if (staged != name) {
                fs.rename(dir + staged, name)
                fs.stat(dir + name) ?: throw IOException("the renamed upload is not there")
            }
        } catch (failure: Throwable) {
            // A temporary name is ours to clear. The real name only when our
            // own upload put a wrong file there; a failed all-or-nothing
            // upload left nothing, and whatever has the name now is not ours.
            if (part != null || uploaded) runCatching { fs.delete(dir + staged, false) }
            throw failure
        }
    }

    /** Makes a folder [name] in [dir]; [unique] picks `name (1)` when taken, else a taken name is refused. Its path, or null. */
    fun makeFolder(context: Context, dir: String, name: String, unique: Boolean): String? {
        val (place, raw) = parse(dir) ?: return null
        val picks = picksOf(raw) ?: return null
        val segments = plain(raw)
        return runCatching {
            use(context, place, retry = false, picks) { fs ->
                val taken = names(fs, segments)
                val final = when {
                    !taken.contains(fs, name) -> name
                    unique -> uniqueName(fs, name, taken)
                    else -> return@use null
                }
                fs.mkdir(segments, final)
                fs.stat(segments + final)?.takeIf { it.isDirectory } ?: throw IOException("the folder is not there")
                pathOf(context, place, raw + final)
            }
        }.onFailure { android.util.Log.w("RemoteFiles", "mkdir failed", it) }.getOrNull()
    }

    /** Renames [path] to [name] where it is; refused when [name] is taken. Its new path, or null. */
    fun rename(context: Context, path: String, name: String): String? {
        val (place, raw) = parse(path) ?: return null
        if (raw.isEmpty()) return null
        val picks = picksOf(raw) ?: return null
        val segments = plain(raw)
        val dir = segments.dropLast(1)
        return runCatching {
            use(context, place, retry = false, picks) { fs ->
                if (segments.last() == name) return@use path
                val taken = names(fs, dir)
                // A change of case alone is the same name to a server that ignores case.
                val sameFile = fs.caseInsensitive && segments.last().equals(name, ignoreCase = true)
                if (!sameFile && taken.contains(fs, name)) return@use null
                fs.rename(segments, name)
                // The new name is free, so the renamed file has it alone and needs no key.
                fs.pick(picks.filterKeys { it != segments })
                fs.stat(dir + name) ?: throw IOException("the renamed file is not there")
                pathOf(context, place, raw.dropLast(1) + name)
            }
        }.onFailure { android.util.Log.w("RemoteFiles", "rename failed", it) }.getOrNull()
    }

    /**
     * Removes [path], a folder with everything in it. False when anything
     * refused, in which case what is left stays where it was.
     */
    fun remove(context: Context, path: String): Boolean {
        val (place, raw) = parse(path) ?: return false
        if (raw.isEmpty()) return false
        val picks = picksOf(raw) ?: return false
        val segments = plain(raw)
        return runCatching {
            use(context, place, retry = false, picks) { fs ->
                val what = fs.stat(segments) ?: return@use true
                removeTree(fs, segments, what.isDirectory, picks)
                fs.pick(picks)
                fs.stat(segments) == null
            }
        }.onFailure { android.util.Log.w("RemoteFiles", "remove failed", it) }.getOrDefault(false)
    }

    /**
     * Removes [path] and, for a folder, everything in it first. Twins inside
     * go by their own keys, one at a time: one of two of a name is never
     * mistaken for the other, even when both go.
     */
    private fun removeTree(fs: RemoteFs, path: List<String>, isDirectory: Boolean, picks: Map<List<String>, String>) {
        fs.pick(picks)
        if (isDirectory) {
            val children = fs.list(path)
            val twins = children.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            for (child in children) {
                val at = path + child.name
                val key = child.key
                val inner = if (child.name in twins && key != null) picks + (at to key) else picks
                removeTree(fs, at, child.isDirectory, inner)
            }
        }
        fs.pick(picks)
        fs.delete(path, isDirectory)
    }

    /**
     * Moves [path] into the folder [dir] of the same place on the server, when
     * the service can and the name is free there. Its new path, or null to
     * copy and delete instead.
     */
    fun moveWithin(context: Context, path: String, dir: String): String? {
        val (place, raw) = parse(path) ?: return null
        val (toPlace, toRaw) = parse(dir) ?: return null
        if (place != toPlace || raw.isEmpty()) return null
        // Not into itself, nor where it already is.
        if (toRaw.size >= raw.size && toRaw.subList(0, raw.size) == raw) return null
        if (toRaw == raw.dropLast(1)) return null
        // Two paths that pick one folder differently go the long way, a call each.
        val picks = picksOf(raw, toRaw) ?: return null
        val segments = plain(raw)
        val toSegments = plain(toRaw)
        return runCatching {
            use(context, place, retry = false, picks) { fs ->
                if (names(fs, toSegments).contains(fs, segments.last())) return@use null
                if (!fs.move(segments, toSegments)) return@use null
                // Its name is free where it went, so it needs no key there.
                fs.pick(picks.filterKeys { it != segments })
                fs.stat(toSegments + segments.last()) ?: throw IOException("the moved file is not there")
                pathOf(context, place, toRaw + segments.last())
            }
        }.getOrNull()
    }

    // ---- Names -------------------------------------------------------------------

    private fun names(fs: RemoteFs, dir: List<String>): Set<String> = fs.list(dir).map { it.name }.toSet()

    private fun Set<String>.contains(fs: RemoteFs, name: String): Boolean =
        if (fs.caseInsensitive) any { it.equals(name, ignoreCase = true) } else name in this

    private fun freeName(fs: RemoteFs, dir: List<String>, name: String): String {
        val taken = names(fs, dir)
        return if (taken.contains(fs, name)) uniqueName(fs, name, taken) else name
    }

    /** `name (1).ext`, `name (2).ext`, … the first one free, as [FileOps.uniqueTarget] names a copy on the phone. */
    private fun uniqueName(fs: RemoteFs, name: String, taken: Set<String>): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (!taken.contains(fs, candidate)) return candidate
            n++
        }
    }

    private val random = SecureRandom()

    private fun token(): String = (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")

    private fun ioDir(context: Context): File = File(context.cacheDir, "remote-io").apply { mkdirs() }

    private fun sha1(text: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

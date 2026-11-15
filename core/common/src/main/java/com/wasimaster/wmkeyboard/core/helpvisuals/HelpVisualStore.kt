package com.wasimaster.wmkeyboard.core.helpvisuals

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The help pictures on this phone: where each one is kept, whether it is here,
 * and the two ways it gets here.
 *
 * A picture is one small WebP named by the SHA-256 of its bytes, so the same
 * file serves every version of the app that still shows it, and a file that
 * does not hash to its own name is never kept. It arrives either alone, the
 * moment its "?" is opened ([fetch]), or with every other picture of one look
 * in a pack ([fetchPack], or [installPack] for a pack brought over as a file).
 * The two mix: once a look's pack is on the phone, a picture a later version
 * added is fetched alone rather than by downloading the pack again.
 *
 * [root] is the store's own directory; the app puts it under
 * `noBackupFilesDir`, since everything in it can be downloaded again.
 */
class HelpVisualStore(
    private val root: File,
    private val index: HelpVisualIndex,
    private val urlOf: (String) -> String = { ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(it) },
) {
    private val filesDir = File(root, FILES)
    private val packLock = Mutex()
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    /** Where [file] is kept once it is here. */
    fun fileOf(file: HelpFile): File = File(filesDir, file.fileName)

    fun has(file: HelpFile): Boolean = fileOf(file).isFile

    /**
     * The file to draw for [frame] while the app is in [theme]: that look's
     * own picture when it is here, otherwise the other look's when that one
     * is, otherwise null. The second answer keeps a dialog from going blank
     * the first time the app is opened in the other look.
     */
    fun resolve(frame: HelpFrame, theme: HelpTheme): Resolved? {
        frame.file(theme)?.let { if (has(it)) return Resolved(fileOf(it), theme) }
        frame.file(theme.other)?.let { if (has(it)) return Resolved(fileOf(it), theme.other) }
        return null
    }

    class Resolved(val file: File, val theme: HelpTheme)

    /** Whether every frame of [visual] can be drawn in [theme]'s own pictures. */
    fun complete(visual: HelpVisual, theme: HelpTheme): Boolean =
        visual.frames.all { frame -> frame.file(theme)?.let(::has) ?: (resolve(frame, theme) != null) }

    /** Whether [theme]'s pack was ever installed here, in any version. */
    fun packInstalled(theme: HelpTheme): Boolean = marker(theme).isFile

    /**
     * Whether the user turned [theme]'s pack down while holding the other
     * look's, which is then what the dialog keeps showing without asking again.
     */
    fun packDeclined(theme: HelpTheme): Boolean = declinedMarker(theme).isFile

    fun declinePack(theme: HelpTheme) {
        root.mkdirs()
        runCatching { declinedMarker(theme).writeText("") }
    }

    /** Fetches every frame of [visual] in [theme] that is not here yet, one file each. */
    suspend fun fetch(visual: HelpVisual, theme: HelpTheme) {
        for (frame in visual.frames) {
            val file = frame.file(theme) ?: frame.file(theme.other) ?: continue
            fetch(file)
        }
    }

    /** Fetches [file] alone and checks it. Returns at once when it is here. */
    suspend fun fetch(file: HelpFile) {
        if (has(file)) return
        fileLocks.getOrPut(file.sha256) { Mutex() }.withLock {
            if (has(file)) return
            withContext(Dispatchers.IO) { filesDir.mkdirs() }
            FileDownloader.fetchVerified(
                url = urlOf(file.repoPath),
                target = fileOf(file),
                size = file.bytes,
                sha256 = file.sha256,
                source = NetSource.DOWNLOAD_HELP,
                // One small file: a second connection would only cost a handshake.
                connections = 1,
            )
        }
    }

    /**
     * Downloads [theme]'s pack and unpacks it. [onProgress] hears 0 to 1 a few
     * times a second, from a worker thread. Throws what [FileDownloader] throws,
     * and an [IOException] when the index names no pack for this look.
     */
    suspend fun fetchPack(theme: HelpTheme, onProgress: (Float) -> Unit = {}) {
        val pack = index.pack(theme) ?: throw IOException("no ${theme.id} pack in this version")
        packLock.withLock {
            withContext(Dispatchers.IO) { root.mkdirs() }
            val archive = File(root, pack.fileName)
            try {
                FileDownloader.fetchVerified(
                    url = urlOf(pack.repoPath),
                    target = archive,
                    size = pack.bytes,
                    sha256 = pack.sha256,
                    source = NetSource.DOWNLOAD_HELP,
                ) { written -> onProgress((written.toFloat() / pack.bytes).coerceIn(0f, 1f)) }
                withContext(Dispatchers.IO) { unpack(archive, theme, pack.sha256) }
            } finally {
                archive.delete()
            }
        }
    }

    /**
     * Installs a pack brought over as a file, for the build with no internet
     * permission. Any version's pack is taken: each picture inside is kept only
     * if it hashes to its own name, so a pack can add pictures and never
     * replace one with something else. Returns the pictures added, or null when
     * [name] is not a pack's name.
     */
    suspend fun installPack(archive: File, name: String = archive.name): Int? {
        val theme = HelpPack.themeOf(name) ?: return null
        return packLock.withLock {
            withContext(Dispatchers.IO) {
                root.mkdirs()
                unpack(archive, theme, name.substringAfterLast('-').substringBefore('.'))
            }
        }
    }

    /** The bytes the store holds, for the storage screen. */
    fun bytesOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes every picture and forgets which packs were here. The user's choice of mode is not the store's to forget. */
    fun clear() {
        root.deleteRecursively()
    }

    private fun marker(theme: HelpTheme) = File(root, "pack-${theme.id}")
    private fun declinedMarker(theme: HelpTheme) = File(root, "declined-${theme.id}")

    private fun unpack(archive: File, theme: HelpTheme, version: String): Int {
        filesDir.mkdirs()
        var added = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                val sha = ENTRY.matchEntire(name)?.groupValues?.get(1)
                if (entry.isDirectory || sha == null) continue
                val bytes = readCapped(zip) ?: continue
                if (sha256(bytes) != sha) continue
                val target = File(filesDir, name)
                if (target.isFile) continue
                val staged = File(filesDir, "$name.tmp")
                staged.writeBytes(bytes)
                if (staged.renameTo(target)) added++ else staged.delete()
            }
        }
        marker(theme).writeText(version)
        declinedMarker(theme).delete()
        return added
    }

    /** One entry's bytes, or null when it is larger than any help picture is. */
    private fun readCapped(zip: ZipInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_ENTRY_BYTES) return null
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** The store's directory name under `noBackupFilesDir`. */
        const val DIR = "help-visuals"
        private const val FILES = "files"
        private const val BUFFER = 64 * 1024

        /** A clip is a few hundred kilobytes at most; anything past this is not one of ours. */
        private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
        private val ENTRY = Regex("""^([0-9a-f]{64})\.webp$""")
    }
}

package com.wasimaster.wmkeyboard.core.layout

import android.content.Context
import android.content.res.AssetManager
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The converted Keyman grids, as one file a release build fetches instead of
 * carrying. They are 862 of the shipped layouts and most of the bytes of
 * `assets/layouts`, and hardly anyone turns one on. One pack rather than one
 * file per keyboard because the More layouts page draws every grid of a language
 * as a card, so choosing one already needs the rest.
 *
 * Only a build with the `keyman-layouts-pack.tsv` asset works this way (see
 * `generateKeymanLayoutPack` in the app's build script). Everywhere else the
 * grids are ordinary assets and this object answers nothing. The layout index
 * lists them either way, so their names and languages never wait for a download.
 *
 * In device-protected storage, because a layout that is on has to draw on a
 * locked phone, and in the no-backup directory because the app can fetch it again.
 *
 * The pin is a digest of the entries rather than of the zip, so what is checked
 * does not depend on which deflate wrote the file. The Gradle task computes the
 * same digest; change [digestOf] and it together.
 */
object KeymanLayoutPack {

    /** Every layout in the pack has this id prefix. */
    const val LAYOUT_ID_PREFIX = "asset_kmn_"

    /** The data repository's release holding the packs, named by digest and never replaced. */
    const val RELEASE_TAG = "layouts"

    private const val ENTRY_PREFIX = "kmn_"
    private const val PIN_ASSET = "keyman-layouts-pack.tsv"
    private const val DIR_NAME = "keyman-layouts"
    private const val PART_NAME = "download.part"
    private const val PACK_SUFFIX = ".zip"

    /** What this build expects: the entries' digest, how many there are and their total size. */
    class Pin(val digest: String, val entries: Int, val rawBytes: Long) {
        /** The name the release publishes this pack under. */
        val fileName: String get() = "keyman-layouts-$digest$PACK_SUFFIX"
    }

    @Volatile private var dir: File? = null
    @Volatile private var pinned: Pin? = null

    /**
     * The pack on disk, and it may be an older one than [pinned]: after an app
     * update whose grids changed, the last version keeps a layout drawing until
     * the new one arrives, rather than dropping it to QWERTY for the wait.
     */
    @Volatile private var current: File? = null

    private val _revision = MutableStateFlow(0)

    /** Moves when a pack lands, which is when a grid that could not resolve now can. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Where the pack lives. A path only; called from the application, once per process. */
    fun attach(context: Context) {
        dir = File(DirectBoot.deviceContext(context.applicationContext).noBackupFilesDir, DIR_NAME)
    }

    /** Reads this build's pin and finds the pack. Called with the layout index, off the main thread. */
    internal fun load(assets: AssetManager) {
        val pin = runCatching {
            assets.open(PIN_ASSET).bufferedReader().use { it.readLine() }?.split('\t')
                ?.takeIf { it.size == 3 }
                ?.let { (digest, entries, rawBytes) -> Pin(digest, entries.toInt(), rawBytes.toLong()) }
        }.getOrNull()
        configure(pin)
    }

    /** The test seam under [load]: no [AssetManager] and no [Context]. */
    internal fun configure(pin: Pin?, directory: File? = dir) {
        dir = directory
        pinned = pin
        if (pin == null) {
            current = null
            return
        }
        val packs = directory?.listFiles { file -> file.name.endsWith(PACK_SUFFIX) }.orEmpty()
        current = packs.firstOrNull { it.name == pin.fileName } ?: packs.firstOrNull()
    }

    /** This build's pin, or null when it carries the grids as assets. */
    val pin: Pin? get() = pinned

    /** Whether [layoutId] is a grid this build has to fetch and has nothing to draw it from. */
    fun isMissing(layoutId: String): Boolean =
        layoutId.startsWith(LAYOUT_ID_PREFIX) && pinned != null && current == null

    /** Whether the pack this build pinned has yet to arrive. */
    val needsDownload: Boolean
        get() {
            val pin = pinned ?: return false
            return current?.name != pin.fileName
        }

    /** The file a download writes to, on the pack's own volume so [install] can rename it. */
    fun partFile(): File? = dir?.let { File(it, PART_NAME) }

    /** [fileStem]'s grid from the pack, or null when it is not in one. */
    internal fun read(fileStem: String, suffix: String): String? {
        if (!fileStem.startsWith(ENTRY_PREFIX)) return null
        val file = current ?: return null
        return runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry(fileStem + suffix)?.let { entry ->
                    zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                }
            }
        }.getOrNull()
    }

    /**
     * Installs [candidate] if it is the pack this build pinned, and removes any
     * other. Answers false, leaving what was there alone, if it is not.
     */
    fun install(candidate: File): Boolean {
        val pin = pinned ?: return false
        val directory = dir ?: return false
        if (digestOf(candidate, pin) != pin.digest) return false
        val target = File(directory, pin.fileName)
        if (!candidate.renameTo(target)) return false
        directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
        current = target
        AssetLayouts.forgetPacked()
        _revision.update { it + 1 }
        return true
    }

    /**
     * The digest of [zip]'s entries in name order, or null when they are not what
     * [pin] describes: a wrong count, a name that is not a pack entry, or more
     * bytes than it allows, which stops at the limit instead of filling the disk.
     *
     * Each entry contributes its name, a NUL, its size in decimal, a NUL and its
     * bytes.
     */
    internal fun digestOf(zip: File, pin: Pin): String? = runCatching {
        ZipFile(zip).use { archive ->
            val entries = archive.entries().asSequence().sortedBy { it.name }.toList()
            if (entries.size != pin.entries) return@use null
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            for (entry in entries) {
                if (!entry.name.startsWith(ENTRY_PREFIX) || '/' in entry.name || '\\' in entry.name) return@use null
                val bytes = archive.getInputStream(entry).use { it.readCapped(pin.rawBytes - total) }
                    ?: return@use null
                total += bytes.size
                digest.update(entry.name.encodeToByteArray())
                digest.update(0)
                digest.update(bytes.size.toString().encodeToByteArray())
                digest.update(0)
                digest.update(bytes)
            }
            digest.digest().joinToString("") { "%02x".format(it) }.takeIf { total == pin.rawBytes }
        }
    }.getOrNull()

    /** The whole stream, or null when it holds more than [limit] bytes. */
    private fun InputStream.readCapped(limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
    }

    private const val BUFFER_BYTES = 16 * 1024
}

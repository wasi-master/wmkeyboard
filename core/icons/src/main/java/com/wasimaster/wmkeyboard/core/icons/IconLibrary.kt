package com.wasimaster.wmkeyboard.core.icons

import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * One picture the user imported, kept whether or not anything wears it.
 *
 * [keepColours] overrules the guess [IconArt.monochrome] makes: null leaves the
 * picture to that guess (a one-colour glyph takes the theme's tint, artwork is
 * drawn as painted), true always draws it as painted, false always tints it.
 */
@Serializable
data class LibraryIcon(
    val id: String,
    val name: String,
    val extension: String,
    val addedAt: Long = 0L,
    val usedAt: Long = 0L,
    val keepColours: Boolean? = null,
)

/** What [IconLibrary.add] made of a file. */
sealed interface LibraryAddResult {
    /** [existed] is true when the same bytes were already in the library. */
    data class Added(val icon: LibraryIcon, val existed: Boolean) : LibraryAddResult

    /** Not a picture the keyboard can draw, or past the size caps. */
    data object NotAnIcon : LibraryAddResult

    /** [IconLibrary.MAX_ICONS] already reached. */
    data object Full : LibraryAddResult
}

/**
 * The user's own pictures, under `filesDir/iconlibrary/`:
 *
 * ```
 * iconlibrary/
 * ├── library.json        manifest
 * └── <id>.<svg|png|webp|jpg|gif>
 * ```
 *
 * Before this, a picture imported for a slot was written into the "My icons"
 * pack *under that slot's id*. Picking anything else for the slot left the file
 * on disk with nothing that listed it, a second import overwrote the first, and
 * one picture could not go on two slots. Here a picture is stored once, apart
 * from every slot, and a slot (or a layout's key, or a theme) points at it by
 * id: `u:<id>` in the icon overrides, see [IconOverrides.LIBRARY_PREFIX].
 *
 * The id is a hash of the bytes. Importing the same file twice keeps one entry,
 * and a layout or a backup carrying the picture to another phone lands on the
 * same id there, so every `u:` that named it still finds it.
 *
 * One store per process, for the reason [IconPackStore] gives: the keyboard and
 * the settings app share it, and [revision] is how the keyboard notices.
 */
class IconLibrary(private var baseDir: File?) {

    @Serializable
    private data class Snapshot(val version: Int = FORMAT_VERSION, val icons: List<LibraryIcon> = emptyList())

    private val icons = ArrayList<LibraryIcon>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** id → parsed picture; a null value is a file that would not read. */
    private val artCache = HashMap<String, IconArt?>()

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change; see [IconPackStore.revision] for why it is in memory. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        reload()
    }

    companion object {
        @Volatile
        private var shared: IconLibrary? = null

        /** The one library for this process; see [IconPackStore.get] for direct boot. */
        fun get(context: android.content.Context): IconLibrary =
            shared ?: synchronized(this) {
                shared ?: IconLibrary(libraryDir(context)).also { shared = it }
            }

        /** Re-points the shared library after a direct-boot unlock. */
        fun attach(context: android.content.Context) {
            get(context).attach(libraryDir(context))
        }

        private fun libraryDir(context: android.content.Context): File? =
            if (DirectBoot.isUserUnlocked(context)) {
                File(context.applicationContext.filesDir, DIR_NAME)
            } else {
                null
            }

        private const val FORMAT_VERSION = 1

        const val DIR_NAME = "iconlibrary"
        const val MANIFEST = "library.json"

        /**
         * Generous for icons a person imports one at a time, and a bound on
         * what a layout file or a backup can make the phone store.
         */
        const val MAX_ICONS = 200

        /** Longest name kept; a file name is all it ever starts from. */
        const val MAX_NAME_LENGTH = 60

        /** What an id looks like: hex only, so it is always a safe file name. */
        private val ID_PATTERN = Regex("[0-9a-f]{16}")

        fun isId(value: String): Boolean = ID_PATTERN.matches(value)

        /** The id [bytes] are stored under: the first 64 bits of their SHA-256. */
        fun idFor(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .take(8)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /**
         * The extension [bytes] are stored as, or null when they are no icon.
         * Rasters are sniffed before the SVG parse, for the reason
         * [IconPackStore] gives in its own `identify`.
         */
        fun extensionOf(bytes: ByteArray): String? {
            RasterIcons.read(bytes)?.let { return it.format.extension }
            if (bytes.size > SvgParser.MAX_SOURCE_BYTES) return null
            val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return null
            return if (runCatching { SvgParser.parse(text) }.getOrNull() != null) "svg" else null
        }

        /** [art] with the entry's own colour choice applied over the guess. */
        fun monochromeOf(icon: LibraryIcon, art: IconArt): Boolean =
            when (icon.keepColours) {
                true -> false
                false -> true
                null -> art.monochrome
            }

        /** A display name out of whatever the file was called. */
        fun nameFrom(fileName: String?): String =
            fileName.orEmpty()
                .substringBeforeLast('.')
                .replace('_', ' ')
                .trim()
                .take(MAX_NAME_LENGTH)
    }

    // ---- reading -------------------------------------------------------

    /** Every picture, the most recently used first, then the newest. */
    @Synchronized
    fun icons(): List<LibraryIcon> =
        icons.sortedWith(compareByDescending<LibraryIcon> { it.usedAt }.thenByDescending { it.addedAt })

    @Synchronized
    fun icon(id: String?): LibraryIcon? = icons.firstOrNull { it.id == id }

    /** The file behind [id], or null when there is none. */
    @Synchronized
    fun fileFor(id: String): File? {
        val icon = icon(id) ?: return null
        val dir = baseDir ?: return null
        return File(dir, "${icon.id}.${icon.extension}").takeIf { it.isFile }
    }

    /**
     * The picture behind [id], parsed. Blocking on the first read of each one,
     * so the keyboard asks off the main thread; cached after that.
     */
    @Synchronized
    fun art(id: String): IconArt? {
        if (artCache.containsKey(id)) return artCache[id]
        val file = fileFor(id)
        val parsed = file?.let(::readArt)
        artCache[id] = parsed
        return parsed
    }

    private fun readArt(file: File): IconArt? {
        if (file.length() > RasterIcons.MAX_SOURCE_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        RasterIcons.read(bytes)?.let { return it }
        if (bytes.size > SvgParser.MAX_SOURCE_BYTES) return null
        return runCatching { SvgParser.parse(bytes.decodeToString()) }.getOrNull()?.let(IconArt::Vector)
    }

    /** The bytes behind [id], for an export that has to carry them. */
    fun bytesOf(id: String): ByteArray? = fileFor(id)?.let { runCatching { it.readBytes() }.getOrNull() }

    // ---- writing -------------------------------------------------------

    /**
     * Stores [bytes] as a picture named [name]. The same bytes added twice keep
     * their first entry, which only has its use time refreshed.
     */
    @Synchronized
    fun add(bytes: ByteArray, name: String, now: Long = System.currentTimeMillis()): LibraryAddResult {
        val id = idFor(bytes)
        icon(id)?.let { existing ->
            val touched = existing.copy(usedAt = now)
            replace(touched)
            save()
            return LibraryAddResult.Added(touched, existed = true)
        }
        val extension = extensionOf(bytes) ?: return LibraryAddResult.NotAnIcon
        if (icons.size >= MAX_ICONS) return LibraryAddResult.Full
        val dir = baseDir ?: return LibraryAddResult.NotAnIcon
        dir.mkdirs()
        val target = File(dir, "$id.$extension")
        val written = runCatching {
            val part = File(dir, "$id.$extension.part")
            part.writeBytes(bytes)
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    return@runCatching false
                }
            }
            true
        }.getOrDefault(false)
        if (!written) return LibraryAddResult.NotAnIcon
        val icon = LibraryIcon(
            id = id,
            name = name.trim().take(MAX_NAME_LENGTH).ifBlank { id },
            extension = extension,
            addedAt = now,
            usedAt = now,
        )
        icons.add(icon)
        artCache.remove(id)
        save()
        return LibraryAddResult.Added(icon, existed = false)
    }

    /** Marks [id] as just used, so it leads the picker's list. */
    @Synchronized
    fun touch(id: String, now: Long = System.currentTimeMillis()) {
        val icon = icon(id) ?: return
        replace(icon.copy(usedAt = now))
        save()
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val icon = icon(id) ?: return
        val trimmed = name.trim().take(MAX_NAME_LENGTH)
        if (trimmed.isEmpty() || trimmed == icon.name) return
        replace(icon.copy(name = trimmed))
        save()
    }

    @Synchronized
    fun setKeepColours(id: String, keep: Boolean?) {
        val icon = icon(id) ?: return
        if (icon.keepColours == keep) return
        replace(icon.copy(keepColours = keep))
        save()
    }

    @Synchronized
    fun delete(id: String) {
        val icon = icon(id) ?: return
        icons.remove(icon)
        baseDir?.let { File(it, "${icon.id}.${icon.extension}").delete() }
        artCache.remove(id)
        save()
    }

    @Synchronized
    fun clear() {
        icons.clear()
        artCache.clear()
        baseDir?.listFiles()?.forEach { if (it.name != MANIFEST) it.delete() }
        save()
    }

    private fun replace(icon: LibraryIcon) {
        val index = icons.indexOfFirst { it.id == icon.id }
        if (index >= 0) icons[index] = icon
    }

    // ---- persistence ---------------------------------------------------

    private fun manifestFile(): File? = baseDir?.let { File(it, MANIFEST) }

    @Synchronized
    fun save() {
        val file = manifestFile()
        if (file != null) {
            runCatching {
                file.parentFile?.mkdirs()
                val part = File(file.parentFile, "$MANIFEST.part")
                part.writeText(json.encodeToString(Snapshot(icons = icons.toList())))
                if (!part.renameTo(file)) {
                    file.delete()
                    part.renameTo(file)
                }
            }
        }
        _revision.value++
    }

    @Synchronized
    fun attach(dir: File?) {
        if (dir?.absolutePath == baseDir?.absolutePath) return
        baseDir = dir
        reload()
    }

    @Synchronized
    fun reload() {
        icons.clear()
        artCache.clear()
        val dir = baseDir ?: return
        val file = manifestFile() ?: return
        var readable = true
        if (file.exists()) {
            readable = runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                // Ids are file names, so anything not shaped like one is dropped
                // before it can name a path: the manifest may have come back
                // from a backup someone edited.
                snapshot.icons
                    .filter { isId(it.id) && it.extension in RasterIcons.FILE_EXTENSIONS }
                    .distinctBy { it.id }
                    .take(MAX_ICONS)
                    .forEach { icons.add(it) }
            }.isSuccess
        }
        reconcile(dir, sweepUnknown = readable)
        _revision.value++
    }

    /**
     * Entries without a file are dropped; files no entry names are deleted,
     * unless the manifest could not be read, when nothing is known well enough
     * to sweep.
     */
    private fun reconcile(dir: File, sweepUnknown: Boolean) {
        val before = icons.size
        icons.removeAll { !File(dir, "${it.id}.${it.extension}").isFile }
        val referenced = icons.mapTo(HashSet()) { "${it.id}.${it.extension}" }
        if (sweepUnknown) dir.listFiles()?.forEach { child ->
            if (child.name == MANIFEST || child.name in referenced) return@forEach
            child.deleteRecursively()
        }
        if (icons.size != before) save()
    }
}

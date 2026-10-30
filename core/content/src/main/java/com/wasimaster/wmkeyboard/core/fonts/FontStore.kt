package com.wasimaster.wmkeyboard.core.fonts

import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A font file the user installed, from a repository or their own storage. */
@Serializable
data class InstalledFont(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val version: String = "",
    /** Always derived from [id], never from anything the file claimed. */
    val fileName: String = "",
    val addedAt: Long = 0L,
    /**
     * Language ids this face actually covers, when the publisher said so.
     *
     * Empty means no claim, and is treated as "offer it everywhere" — which is
     * both the honest reading and what every font imported before this field
     * existed gets. A non-empty list is what keeps a Latin-only display face
     * out of the Bengali picker, where it would draw every key blank.
     */
    val langIds: List<String> = emptyList(),
    /**
     * An emoji face rather than a text one. Kept in the same library — it is
     * the same kind of file with the same lifecycle — but the pickers are
     * separate, because a colour emoji font on the key labels is not a choice
     * anyone means to make.
     */
    val emoji: Boolean = false,
)

/**
 * Fonts the user has installed, under `filesDir/fonts/installed/`:
 *
 * ```
 * fonts/
 * ├── custom_font.ttf         the pre-existing single-slot picks, untouched
 * ├── custom_font_bn.ttf
 * ├── custom_emoji.ttf
 * └── installed/
 *     ├── fonts.json          manifest
 *     └── <fontId>.ttf
 * ```
 *
 * The app already had three fixed custom-font slots — one for Latin, one for
 * Bengali, one for emoji — each overwritten on import. That is fine for "use
 * this one font I picked", but it has nowhere to put a *library*: installing a
 * second font from a repository would silently evict the first. This store is
 * that library, sitting alongside the old slots rather than replacing them, so
 * nothing anyone already set up changes.
 *
 * Same contract as the pack stores: one per process, mutators write the
 * manifest immediately, [revision] is how the keyboard notices, and the
 * directory is null before the first unlock.
 */
class FontStore(private var baseDir: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = FORMAT_VERSION,
        val fonts: List<InstalledFont> = emptyList(),
    )

    private val fonts = ArrayList<InstalledFont>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _revision = MutableStateFlow(0)

    /** Bumped whenever the installed set changes. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        reload()
    }

    companion object {
        @Volatile
        private var shared: FontStore? = null

        fun get(context: android.content.Context): FontStore =
            shared ?: synchronized(this) {
                shared ?: FontStore(fontsDir(context)).also { shared = it }
            }

        /** Re-points the shared store after a direct-boot unlock. */
        fun attach(context: android.content.Context) {
            get(context).attach(fontsDir(context))
        }

        private fun fontsDir(context: android.content.Context): File? =
            if (DirectBoot.isUserUnlocked(context)) {
                File(context.applicationContext.filesDir, DIR_NAME)
            } else {
                null
            }

        private const val FORMAT_VERSION = 1

        const val DIR_NAME = "fonts/installed"

        /** A picker listing more than this is unusable anyway. */
        const val MAX_FONTS = 50

        /** Big enough for a CJK face or a phone's colour emoji font, small enough to refuse a mistake. */
        const val MAX_BYTES = 64L * 1024 * 1024

        /**
         * Prefix marking a font id in the settings' font-id string, alongside
         * `google:` and the fixed `custom` ids. Resolved by `KeyboardFonts`.
         */
        const val ID_PREFIX = "installed:"

        private const val STAGING_PREFIX = ".staging_"

        /** File name for a font. Derived, never taken from untrusted input. */
        fun fileNameFor(id: String): String = "$id.ttf"

        /** The settings font-id for an installed font. */
        fun fontIdFor(id: String): String = ID_PREFIX + id

        /** The store id inside a settings font-id, or null if it isn't one. */
        fun storeIdOf(fontId: String): String? =
            fontId.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)
    }

    // ---- reading -------------------------------------------------------

    @Synchronized
    fun fonts(): List<InstalledFont> = fonts.toList()

    /** The text faces — everything the key-label pickers should offer. */
    @Synchronized
    fun textFonts(): List<InstalledFont> = fonts.filter { !it.emoji }

    /** The emoji faces, for the emoji-font picker. */
    @Synchronized
    fun emojiFonts(): List<InstalledFont> = fonts.filter { it.emoji }

    @Synchronized
    fun font(id: String?): InstalledFont? = fonts.firstOrNull { it.id == id }

    /** The file backing [id], whether or not it exists. */
    fun fileFor(id: String): File? = baseDir?.let { File(it, fileNameFor(id)) }

    /** The file backing [id] only if it is actually there and non-empty. */
    fun existingFileFor(id: String): File? = fileFor(id)?.takeIf { it.isFile && it.length() > 0 }

    // ---- mutating ------------------------------------------------------

    /** An id whose file doesn't exist yet. */
    @Synchronized
    fun freeId(now: Long = System.currentTimeMillis()): String {
        var candidate = "font_$now"
        var suffix = 1
        while (fonts.any { it.id == candidate } || fileFor(candidate)?.exists() == true) {
            candidate = "font_${now}_${suffix++}"
        }
        return candidate
    }

    /**
     * Registers a font whose file has already been staged into place, or
     * returns null when [MAX_FONTS] is reached. The import path; see
     * [FontFile.import].
     */
    @Synchronized
    fun adopt(font: InstalledFont): InstalledFont? {
        if (fonts.size >= MAX_FONTS) return null
        val adopted = font.copy(name = uniqueName(font.name), fileName = fileNameFor(font.id))
        fonts.add(adopted)
        save()
        return adopted
    }

    @Synchronized
    fun delete(id: String) {
        if (!fonts.removeAll { it.id == id }) return
        fileFor(id)?.delete()
        save()
    }

    /** A directory an import can fill before the font is registered. */
    fun stagingDir(): File? =
        baseDir?.let {
            File(it, STAGING_PREFIX + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        }

    /** Moves a staged file into place under [id]. */
    @Synchronized
    fun adoptFile(id: String, staged: File): Boolean {
        val target = fileFor(id) ?: return false
        target.parentFile?.mkdirs()
        if (staged.renameTo(target)) return true
        // Rename can fail across filesystems; copying is the fallback, and a
        // half-copied font is deleted rather than registered.
        return runCatching {
            staged.copyTo(target, overwrite = true)
            staged.delete()
            true
        }.getOrElse {
            target.delete()
            false
        }
    }

    // ---- persistence ---------------------------------------------------

    private fun manifestFile(): File? = baseDir?.let { File(it, "fonts.json") }

    @Synchronized
    fun save() {
        val file = manifestFile()
        if (file != null) {
            runCatching {
                file.parentFile?.mkdirs()
                val part = File(file.parentFile, "fonts.json.part")
                part.writeText(json.encodeToString(Snapshot(fonts = fonts.toList())))
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
        fonts.clear()
        val dir = baseDir ?: run { _revision.value++; return }
        val file = manifestFile() ?: return
        var readable = true
        if (file.exists()) {
            readable = runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                fonts.addAll(snapshot.fonts.take(MAX_FONTS))
            }.isSuccess
        }
        reconcile(dir, sweepUnknown = readable)
        _revision.value++
    }

    /**
     * Makes the manifest and the directory agree: an entry whose file has gone
     * is dropped, a file nothing references is deleted. A manifest that exists
     * but won't parse means we don't know what the files are, so sweeping is
     * skipped — deleting the user's fonts on the strength of a bad read would
     * be the worse failure.
     */
    private fun reconcile(dir: File, sweepUnknown: Boolean) {
        val alive = fonts.filter { File(dir, fileNameFor(it.id)).isFile }
        val changed = alive.size != fonts.size
        if (changed) {
            fonts.clear()
            fonts.addAll(alive)
        }
        if (sweepUnknown) {
            val referenced = alive.mapTo(HashSet()) { fileNameFor(it.id) }
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    if (!child.name.startsWith(STAGING_PREFIX)) child.deleteRecursively()
                    return@forEach
                }
                if (child.name == "fonts.json" || child.name in referenced) return@forEach
                child.delete()
            }
        }
        if (changed) save()
    }

    private fun uniqueName(name: String, exceptId: String? = null): String {
        val cleaned = name.trim().ifBlank { "Font" }
        val taken = fonts.filter { it.id != exceptId }.mapTo(HashSet()) { it.name }
        if (cleaned !in taken) return cleaned
        var n = 2
        while ("$cleaned ($n)" in taken) n++
        return "$cleaned ($n)"
    }
}

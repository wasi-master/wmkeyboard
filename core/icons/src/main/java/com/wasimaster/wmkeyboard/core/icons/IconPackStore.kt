package com.wasimaster.wmkeyboard.core.icons

import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Installed icon packs, persisted under `filesDir/iconpacks/`:
 *
 * ```
 * iconpacks/
 * ├── packs.json          manifest
 * └── <packId>/<slotId>.<svg|png|webp|jpg|gif>
 * ```
 *
 * Modelled on [com.wasimaster.wmkeyboard.core.stickers.StickerPackStore] and
 * for the same reason: the IME and the settings app run in one process and both
 * mutate packs, so there is one store per process and every mutator writes the
 * manifest immediately. [revision] is how the keyboard notices.
 *
 * A slot's extension is not recorded anywhere: the file is found by trying each
 * of [RasterIcons.FILE_EXTENSIONS] against the slot's id, and the extension a
 * file is *written* with comes from sniffing its bytes (see [setIcon]). So the
 * manifest still holds nothing but slot ids, and nothing a pack says can name a
 * path.
 *
 * The store also owns the *parsed* form. [art] caches each slot's [IconArt] for
 * a pack so the keyboard's render path is a map lookup — parsing an SVG per
 * frame would be visible as jank on every keystroke.
 */
class IconPackStore(private var baseDir: File?) {

    @Serializable
    private data class Snapshot(val version: Int = FORMAT_VERSION, val packs: List<IconPack> = emptyList())

    private val packs = ArrayList<IconPack>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * packId → parsed icons, dropped whenever that pack changes, and the
     * least recently used dropped past [MAX_PARSED_PACKS]. The keyboard's
     * themes panel parses every pack it shows a card for, and with no bound
     * a scroll through the installed packs kept each of them parsed for the
     * life of the process. A pack dropped is parsed again on its next use,
     * which every caller already does off the main thread.
     */
    private val artCache = object : LinkedHashMap<String, Map<String, IconArt>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, IconArt>>?): Boolean =
            size > MAX_PARSED_PACKS
    }

    private val _revision = MutableStateFlow(0)

    /**
     * Bumped on every change to the installed packs.
     *
     * The keyboard collects this to know when to rebuild its icon set. It has
     * to be an in-memory signal rather than the manifest's mtime, because a
     * composable reads it on every recomposition — and the keyboard recomposes
     * on every keystroke, so a `stat` here would be a syscall per key. One
     * store per process (see [get]) is what makes in-memory sufficient.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        reload()
    }

    companion object {
        @Volatile
        private var shared: IconPackStore? = null

        /**
         * The one store for this process. Created with no directory at all
         * during direct boot — `filesDir` does not exist yet — and pointed at
         * the real one by [attach] when the user unlocks, so the keyboard that
         * drew stock icons on the lock screen picks the user's packs up
         * through [revision] rather than waiting for a restart.
         */
        fun get(context: android.content.Context): IconPackStore =
            shared ?: synchronized(this) {
                shared ?: IconPackStore(packsDir(context)).also { shared = it }
            }

        /** Re-points the shared store after a direct-boot unlock. */
        fun attach(context: android.content.Context) {
            get(context).attach(packsDir(context))
        }

        private fun packsDir(context: android.content.Context): File? =
            if (DirectBoot.isUserUnlocked(context)) {
                File(context.applicationContext.filesDir, DIR_NAME)
            } else {
                null
            }

        private const val FORMAT_VERSION = 1

        const val DIR_NAME = "iconpacks"

        /**
         * Installed packs at most. Packs are small (a few hundred icons, an
         * archive of 24 MB at the very most) and only the ones in use are
         * parsed, [MAX_PARSED_PACKS] of them at a time, so this is about the
         * list staying a list. It was 20, which people collecting packs met.
         */
        const val MAX_PACKS = 100

        /** Packs kept parsed at once; see [artCache]. */
        private const val MAX_PARSED_PACKS = 12

        /**
         * The pack a single-slot SVG import lands in. Reserved so "import one
         * icon" and "install a pack" are the same mechanism underneath: the
         * user is building a pack whether they think of it that way or not,
         * and Export writes exactly what they built.
         */
        const val MINE_ID = "mine"

        /** Staging directories an import is still filling; skipped by reconcile. */
        private const val STAGING_PREFIX = ".staging_"

        /**
         * File name for a slot's vector icon. Derived, never taken from
         * untrusted input.
         *
         * Still the plain `.svg` name, because that is what a pack held before
         * raster icons existed and what [IconPackFile.write] names an entry
         * after when the file on disk is one.
         */
        fun fileNameFor(slot: String): String = "$slot.svg"

        /** File name for a slot in a given extension; see [fileNameFor]. */
        fun fileNameFor(slot: String, extension: String): String = "$slot.$extension"

        /**
         * Every file name a slot's icon could be stored under, vectors first.
         *
         * Vectors first because a slot can only have one file and the writer
         * deletes the others, so the order only decides which wins if a pack
         * directory is hand-assembled with two — and a vector is the better of
         * the two to honour.
         */
        fun fileNamesFor(slot: String): List<String> =
            RasterIcons.FILE_EXTENSIONS.map { fileNameFor(slot, it) }
    }

    // ---- reading -------------------------------------------------------

    @Synchronized
    fun packs(): List<IconPack> = packs.toList()

    @Synchronized
    fun pack(packId: String?): IconPack? = packs.firstOrNull { it.id == packId }

    /**
     * Absolute file backing [slot] in [packId]: whichever extension it is
     * actually stored in, or the `.svg` name when it is stored in none.
     *
     * The fallback keeps the old contract — "whether or not it exists" — which
     * [IconPackFile.write] relies on to skip a slot whose file has gone.
     */
    fun fileFor(packId: String, slot: String): File? {
        val dir = baseDir?.let { File(it, packId) } ?: return null
        return fileNamesFor(slot).map { File(dir, it) }.firstOrNull { it.isFile }
            ?: File(dir, fileNameFor(slot))
    }

    /**
     * Parsed icons for [packId], slot id → vector document or raster bytes.
     * Blocking on the first call for a pack (it reads and parses every file);
     * cached after that, so callers should warm it off the main thread the way
     * the dictionaries are warmed.
     */
    @Synchronized
    fun art(packId: String): Map<String, IconArt> {
        artCache[packId]?.let { return it }
        val pack = pack(packId)
        val dir = baseDir?.let { File(it, packId) }
        val parsed = if (pack == null || dir == null) {
            emptyMap()
        } else {
            buildMap {
                for (slot in pack.slots) {
                    readArt(dir, slot)?.let { put(slot, it) }
                }
            }
        }
        artCache[packId] = parsed
        return parsed
    }

    /**
     * One slot's file, read and identified.
     *
     * A raster is recognised from its bytes and an SVG from parsing it, in that
     * order: the sniff is a dozen byte comparisons and cannot be fooled into
     * reading megabytes, while handing a PNG to an XML parser is wasted work on
     * every keyboard start.
     */
    private fun readArt(dir: File, slot: String): IconArt? {
        for (name in fileNamesFor(slot)) {
            val file = File(dir, name)
            if (!file.isFile || file.length() > RasterIcons.MAX_SOURCE_BYTES) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            RasterIcons.read(bytes)?.let { return it }
            if (bytes.size > SvgParser.MAX_SOURCE_BYTES) continue
            runCatching { SvgParser.parse(bytes.decodeToString()) }.getOrNull()
                ?.let { return IconArt.Vector(it) }
        }
        return null
    }

    /**
     * The alternative looks [packId] draws for [slot], by variant name, in the
     * order the pack lists them. Empty for a pack with none, which is most.
     */
    @Synchronized
    fun variantsOf(packId: String, slot: String): List<String> =
        pack(packId)?.slots.orEmpty().mapNotNull { key ->
            if (IconSlots.slotOf(key) == slot) IconSlots.variantOf(key) else null
        }

    // ---- packs ---------------------------------------------------------

    /** Creates a pack, or returns null when [MAX_PACKS] is already reached. */
    @Synchronized
    fun createPack(name: String, id: String? = null, now: Long = System.currentTimeMillis()): IconPack? {
        if (packs.size >= MAX_PACKS) return null
        val packId = id ?: freePackId(now)
        if (packs.any { it.id == packId }) return null
        val pack = IconPack(
            id = packId,
            // The fallback only fires when a caller passes a blank name, which
            // the app never does: it always has a name to give.
            name = uniqueName(name.trim().ifBlank { "My icons" }),
            createdAt = now,
        )
        baseDir?.let { File(it, pack.id).mkdirs() }
        packs.add(pack)
        save()
        return pack
    }

    /**
     * The "My icons" pack, created on first use.
     *
     * [name] is what a new one is called. The caller passes it because the
     * store has no context of its own to read a resource with: the wording is
     * `R.string.core_icons_pack_mine_label`, and the screen that starts the
     * pack resolves it.
     */
    @Synchronized
    fun mine(name: String, now: Long = System.currentTimeMillis()): IconPack? =
        pack(MINE_ID) ?: createPack(name, id = MINE_ID, now = now)

    /** Registers a pack whose directory was already filled (the import path). */
    @Synchronized
    fun adoptPack(pack: IconPack): IconPack? {
        if (packs.size >= MAX_PACKS) return null
        val adopted = pack.copy(name = uniqueName(pack.name))
        packs.add(adopted)
        artCache.remove(adopted.id)
        save()
        return adopted
    }

    /** A pack id whose directory doesn't exist yet. */
    @Synchronized
    fun freePackId(now: Long = System.currentTimeMillis()): String {
        var candidate = "pack_$now"
        var suffix = 1
        while (packs.any { it.id == candidate } || baseDir?.let { File(it, candidate).exists() } == true) {
            candidate = "pack_${now}_${suffix++}"
        }
        return candidate
    }

    /** Directory a pack's files live in, created if needed. */
    fun packDir(packId: String): File? =
        baseDir?.let { File(it, packId).apply { mkdirs() } }

    @Synchronized
    fun deletePack(packId: String) {
        if (!packs.removeAll { it.id == packId }) return
        baseDir?.let { File(it, packId).deleteRecursively() }
        artCache.remove(packId)
        save()
    }

    // ---- icons ---------------------------------------------------------

    /** Writes [svg] as [slot]'s icon in [packId]; see [setIcon]. */
    @Synchronized
    fun setIcon(packId: String, slot: String, svg: String): Boolean =
        setIcon(packId, slot, svg.toByteArray())

    /**
     * Writes [bytes] as [slot]'s icon in [packId]. Returns false when the slot
     * is unknown, the file is not an icon this can draw, or the write failed —
     * the caller reports that rather than silently registering a slot with no
     * file behind it. [slot] may be a variant key (`tool.gif@text`) as well as
     * a plain slot id.
     *
     * The extension comes from the bytes, not from whatever the file was called
     * where it came from: a raster is sniffed, and anything else is tried as
     * SVG. A slot that already had an icon in a *different* format has that
     * file removed, so a slot never has two.
     */
    @Synchronized
    fun setIcon(packId: String, slot: String, bytes: ByteArray): Boolean {
        val index = packs.indexOfFirst { it.id == packId }
        if (index < 0) return false
        if (!IconSlots.isKnownKey(slot)) return false
        // Identify before writing: a file the renderer can't use is not worth
        // storing, and finding out now lets the picker say so.
        val extension = identify(bytes) ?: return false
        val dir = packDir(packId) ?: return false
        val fileName = fileNameFor(slot, extension)
        val written = runCatching {
            val part = File(dir, "$fileName.part")
            part.writeBytes(bytes)
            val target = File(dir, fileName)
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    return@runCatching false
                }
            }
            true
        }.getOrDefault(false)
        if (!written) return false
        // The slot's other formats, if the user is replacing an SVG with a PNG
        // or the other way round. Left behind they would shadow the new file
        // through [readArt] and come back in the next export.
        for (name in fileNamesFor(slot)) {
            if (name != fileName) File(dir, name).delete()
        }

        if (slot !in packs[index].slots) {
            packs[index] = packs[index].copy(slots = packs[index].slots + slot)
        }
        artCache.remove(packId)
        save()
        return true
    }

    /**
     * The extension [bytes] should be stored under, or null when they are not a
     * usable icon.
     *
     * Rasters are sniffed first for the reason given in [readArt], and the SVG
     * attempt is skipped for bytes that are plainly not text.
     */
    private fun identify(bytes: ByteArray): String? {
        RasterIcons.read(bytes)?.let { return it.format.extension }
        if (bytes.size > SvgParser.MAX_SOURCE_BYTES) return null
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return null
        return if (SvgParser.parse(text) != null) "svg" else null
    }

    @Synchronized
    fun removeIcon(packId: String, slot: String) {
        val index = packs.indexOfFirst { it.id == packId }
        if (index < 0 || slot !in packs[index].slots) return
        packs[index] = packs[index].copy(slots = packs[index].slots - slot)
        val dir = baseDir?.let { File(it, packId) }
        if (dir != null) for (name in fileNamesFor(slot)) File(dir, name).delete()
        artCache.remove(packId)
        save()
    }

    // ---- persistence ---------------------------------------------------

    private fun manifestFile(): File? = baseDir?.let { File(it, "packs.json") }

    @Synchronized
    fun save() {
        val file = manifestFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, "packs.json.part")
            part.writeText(json.encodeToString(Snapshot(packs = packs.toList())))
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        }
        _revision.value++
    }

    /**
     * Points the store at [dir] and re-reads it, bumping [revision] so the
     * keyboard rebuilds its icon set. Only ever called with a real directory
     * after a null one — the direct-boot unlock.
     */
    @Synchronized
    fun attach(dir: File?) {
        if (dir?.absolutePath == baseDir?.absolutePath) return
        baseDir = dir
        reload()
    }

    @Synchronized
    fun reload() {
        packs.clear()
        artCache.clear()
        val dir = baseDir ?: return
        val file = manifestFile() ?: return
        var readable = true
        if (file.exists()) {
            readable = runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                packs.addAll(snapshot.packs.take(MAX_PACKS))
            }.isSuccess
        }
        // A manifest that exists but won't parse means we don't know what the
        // directories hold — sweeping them would destroy the user's icons on
        // the strength of a bad read.
        reconcile(dir, sweepUnknown = readable)
        _revision.value++
    }

    /**
     * Makes the manifest and the files on disk agree: slots without a file are
     * dropped, files and directories nothing references are deleted. Staging
     * directories belong to an import in flight and are left alone.
     */
    private fun reconcile(dir: File, sweepUnknown: Boolean) {
        var changed = false
        for (i in packs.indices) {
            val pack = packs[i]
            val packDir = File(dir, pack.id)
            val alive = pack.slots.filter { slot ->
                fileNamesFor(slot).any { File(packDir, it).isFile }
            }
            if (alive.size != pack.slots.size) {
                packs[i] = pack.copy(slots = alive)
                changed = true
            }
            // Every name a live slot could be stored under, not just the one it
            // is: the sweep below deletes whatever is left, and a slot the user
            // is mid-way through replacing must not lose its old file.
            val referenced = alive.flatMapTo(HashSet()) { fileNamesFor(it) }
            packDir.listFiles()?.forEach { if (it.name !in referenced) it.deleteRecursively() }
        }
        val known = packs.mapTo(HashSet()) { it.id }
        if (sweepUnknown) dir.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            if (child.name in known || child.name.startsWith(STAGING_PREFIX)) return@forEach
            child.deleteRecursively()
        }
        if (changed) save()
    }

    /** A directory an import can fill before the pack is registered. */
    fun stagingDir(): File? =
        baseDir?.let { File(it, STAGING_PREFIX + UUID.randomUUID().toString().take(8)).apply { mkdirs() } }

    private fun uniqueName(name: String, exceptId: String? = null): String {
        val taken = packs.filter { it.id != exceptId }.mapTo(HashSet()) { it.name }
        if (name !in taken) return name
        var n = 2
        while ("$name ($n)" in taken) n++
        return "$name ($n)"
    }
}

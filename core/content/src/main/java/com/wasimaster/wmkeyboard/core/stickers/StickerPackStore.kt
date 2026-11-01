package com.wasimaster.wmkeyboard.core.stickers

import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.media.MediaMime
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.tools.GifSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * User sticker packs, persisted under `filesDir/stickers/`:
 *
 * ```
 * stickers/
 * ├── packs.json                        manifest
 * ├── .originals/<stickerId>.webp       what the edit started from
 * ├── .originals/<stickerId>.mask.png   what it erased
 * ├── .originals/<stickerId>.json       the crop, the border, the brush
 * └── <packId>/<stickerId>.webp|.gif
 * ```
 *
 * `.originals` holds the picture each sticker was made from and the edit that
 * turned it into the sticker, so the editor can re-open the photo with the
 * crop, the erasing and the border still on it instead of the flattened result
 * it produced. It is private to this device: exporting a pack writes the
 * manifest and the files the manifest names ([StickerPackFile.write]), and the
 * config backup's sticker section walks the same list, so neither carries any
 * of it out. It is keyed by sticker id rather than by pack so moving a sticker
 * between packs does not have to move it too.
 *
 * Unlike [com.wasimaster.wmkeyboard.core.snippets.SnippetStore] every mutator
 * writes the manifest immediately: the settings app and the IME each hold their
 * own instance, and an unsaved edit in one would be silently lost when the
 * other reloaded. [stateToken] lets the IME notice a change cheaply (the
 * DictionaryStore pattern) and call [reloadIfChanged] when its panel opens.
 *
 * The store owns the image files. Loading reconciles both directions: manifest
 * entries whose file vanished are dropped, and files or directories nothing
 * references are deleted — that's the recovery path for a crash between
 * writing bytes and writing the manifest.
 */
class StickerPackStore(private var baseDir: File?) {

    @Serializable
    private data class Snapshot(val version: Int = FORMAT_VERSION, val packs: List<StickerPack> = emptyList())

    private val packs = ArrayList<StickerPack>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loadedToken = 0L

    init {
        reload()
    }

    companion object {
        @Volatile
        private var shared: StickerPackStore? = null

        /**
         * The one store for this process. The IME and the settings screens run
         * in the same process and both mutate packs, so they must not hold
         * separate in-memory copies of the same file.
         */
        fun get(context: android.content.Context): StickerPackStore =
            shared ?: synchronized(this) {
                shared ?: StickerPackStore(packsDir(context)).also { shared = it }
            }

        /**
         * Re-points the shared store after a direct-boot unlock. Until then it
         * has no directory at all — `filesDir` does not exist that early — and
         * reads as a user with no packs.
         */
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
        const val MAX_PACKS = 50
        const val MAX_STICKERS_PER_PACK = 200

        /** Prefix that marks a [GifItem] as coming from a local pack. */
        const val ITEM_PREFIX = "local_"

        /** Directory name under filesDir. */
        const val DIR_NAME = "stickers"

        /** Staging directories an import is still filling; skipped by reconcile. */
        private const val STAGING_PREFIX = ".staging_"

        /**
         * How old a staging directory is before reconcile takes it for one a
         * dead process left. An import stages a pack and waits on the user to
         * pick from it (#965), which can take minutes, never a day.
         */
        private const val STALE_STAGING_MS = 24L * 60 * 60 * 1000

        /** Where the picture behind each sticker is kept, for a later edit. */
        const val ORIGINALS_DIR = ".originals"

        fun newStickerId(): String = "s" + UUID.randomUUID().toString().replace("-", "").take(8)

        /** File name for a sticker; derived, never taken from untrusted input. */
        fun fileNameFor(id: String, mime: String): String = "$id.${MediaMime.extension(mime)}"

        /**
         * The name an edited sticker's file takes: `s1a2b3c4.webp`, then
         * `s1a2b3c4_1.webp`, `_2`, and so on.
         *
         * An edit must not reuse the name. Both the settings grid and the
         * keyboard's sticker panel load these files through one shared image
         * loader with a memory cache and a disk cache, both keyed on the path
         * — so writing new bytes to the old path leaves the old picture on
         * screen in two places until something evicts it. A new name is
         * self-invalidating everywhere at once, and it still derives from the
         * id and the MIME rather than from anything a user supplied.
         */
        fun nextFileName(current: String, id: String, mime: String): String {
            val extension = MediaMime.extension(mime)
            val stem = current.substringBeforeLast('.', current)
            val generation = stem.removePrefix(id).let { suffix ->
                if (suffix.startsWith("_")) suffix.drop(1).toIntOrNull() ?: 0 else 0
            }
            return "${id}_${generation + 1}.$extension"
        }
    }

    // ---- reading -------------------------------------------------------

    @Synchronized
    fun packs(): List<StickerPack> = packs.toList()

    @Synchronized
    fun pack(packId: String?): StickerPack? = packs.firstOrNull { it.id == packId }

    @Synchronized
    fun isEmpty(): Boolean = packs.isEmpty()

    @Synchronized
    fun totalStickers(): Int = packs.sumOf { it.stickers.size }

    /** Absolute file backing [sticker] in [packId]. */
    fun fileFor(packId: String, sticker: CustomSticker): File? =
        baseDir?.let { File(File(it, packId), sticker.fileName) }

    // ---- originals -----------------------------------------------------

    /** Where kept originals live, whether or not anything is in there yet. */
    fun originalsDir(): File? = baseDir?.let { File(it, ORIGINALS_DIR) }

    /**
     * The picture [stickerId] was made from, or null when none was kept — a
     * sticker imported from a pack file, saved from a provider, or added
     * before this app version.
     */
    fun originalFor(stickerId: String): File? =
        originalsDir()?.let { File(it, "$stickerId.webp") }?.takeIf { it.isFile }

    /**
     * The mask a kept edit erased with, or null when that edit erased nothing.
     */
    fun maskFor(stickerId: String): File? =
        originalsDir()?.let { File(it, "$stickerId.mask.png") }?.takeIf { it.isFile }

    /**
     * The edit [stickerId] was last saved with, or null when none was kept.
     * A file that no longer parses is treated as none: a stale edit is worth
     * less than the picture, and the picture is a separate file.
     */
    fun editStateFor(stickerId: String): StickerEditState? {
        val file = originalsDir()?.let { File(it, "$stickerId.json") } ?: return null
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<StickerEditState>(file.readText()) }.getOrNull()
    }

    /**
     * Records how [stickerId] was edited, so opening it again resumes that
     * edit. [maskPng] is the erased-parts mask, or null when nothing was
     * erased; passing null deletes any mask kept before.
     */
    fun writeEditState(stickerId: String, state: StickerEditState, maskPng: ByteArray?) {
        val dir = originalsDir() ?: return
        runCatching {
            dir.mkdirs()
            writeAtomically(File(dir, "$stickerId.json"), json.encodeToString(state).toByteArray())
            val mask = File(dir, "$stickerId.mask.png")
            if (maskPng == null) mask.delete() else writeAtomically(mask, maskPng)
        }
    }

    /**
     * Keeps [bytes] as the original behind [stickerId]. Failing to write one
     * costs a later edit its clean starting point and nothing else, so every
     * caller ignores the result.
     */
    private fun writeOriginal(stickerId: String, bytes: ByteArray) {
        val dir = originalsDir() ?: return
        runCatching {
            dir.mkdirs()
            writeAtomically(File(dir, "$stickerId.webp"), bytes)
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val part = File(target.parentFile, target.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) part.delete()
        }
    }

    /** Everything kept for a later edit of [stickerId]: picture, mask, state. */
    private fun deleteKept(stickerId: String) {
        val dir = originalsDir() ?: return
        listOf("$stickerId.webp", "$stickerId.mask.png", "$stickerId.json")
            .forEach { File(dir, it).delete() }
    }

    /**
     * Drops every kept original, mask and edit. The stickers themselves are
     * untouched; they simply re-open as themselves from then on.
     */
    fun clearOriginals() {
        originalsDir()?.listFiles()?.forEach { it.delete() }
    }

    /** Resolves a `local_…` [GifItem.id] back to its pack and sticker. */
    @Synchronized
    fun findByItemId(itemId: String): Pair<StickerPack, CustomSticker>? {
        if (!itemId.startsWith(ITEM_PREFIX)) return null
        for (pack in packs) {
            val prefix = ITEM_PREFIX + pack.id + "_"
            if (!itemId.startsWith(prefix)) continue
            val stickerId = itemId.removePrefix(prefix)
            val sticker = pack.stickers.firstOrNull { it.id == stickerId } ?: continue
            return pack to sticker
        }
        return null
    }

    /**
     * Local stickers as grid items. [query] matches the sticker's title, its
     * keywords and the pack name case-insensitively; blank shows everything.
     * [packId] limits the result to one pack (null = all packs, in pack order).
     *
     * Title and keywords are matched as one line rather than field by field,
     * so a query can span both: a sticker saved by an older build as "grumpy"
     * with the tag "cat" has to keep matching "grumpy cat".
     */
    @Synchronized
    fun searchAsGifItems(query: String, packId: String? = null): List<GifItem> {
        val dir = baseDir ?: return emptyList()
        val needle = query.trim()
        val scope = if (packId == null) packs else packs.filter { it.id == packId }
        val items = ArrayList<GifItem>()
        for (pack in scope) {
            val packMatches = needle.isEmpty() || pack.name.contains(needle, ignoreCase = true)
            for (sticker in pack.stickers) {
                val matches = packMatches ||
                    StickerKeywords.haystack(sticker).contains(needle, ignoreCase = true)
                if (!matches) continue
                items += gifItem(dir, pack, sticker)
            }
        }
        return items
    }

    /**
     * [sticker] as a grid item, the way [searchAsGifItems] would list it, or
     * null when the pack is gone or the store has no directory yet. For the
     * stickers offered while typing (#329), which are found by
     * [StickerTriggerIndex] rather than by a search.
     */
    @Synchronized
    fun asGifItem(packId: String, sticker: CustomSticker): GifItem? {
        val dir = baseDir ?: return null
        val pack = packs.firstOrNull { it.id == packId } ?: return null
        return gifItem(dir, pack, sticker)
    }

    private fun gifItem(dir: File, pack: StickerPack, sticker: CustomSticker): GifItem {
        // Paths are ours — hex ids under a pack_<millis> directory — so a
        // plain file:// URL needs no escaping.
        val url = "file://" + File(File(dir, pack.id), sticker.fileName).absolutePath
        return GifItem(
            id = itemId(pack.id, sticker.id),
            previewUrl = url,
            fullUrl = url,
            mime = sticker.mime,
            aspectRatio = sticker.aspectRatio,
            source = GifSource.LOCAL,
            title = sticker.name.ifBlank { pack.name },
        )
    }

    private fun itemId(packId: String, stickerId: String) = "$ITEM_PREFIX${packId}_$stickerId"

    // ---- packs ---------------------------------------------------------

    /** Creates a pack, or returns null when [MAX_PACKS] is already reached. */
    @Synchronized
    fun createPack(name: String, now: Long = System.currentTimeMillis()): StickerPack? {
        if (packs.size >= MAX_PACKS) return null
        val pack = StickerPack(id = freePackId(now), name = uniqueName(name.trim().ifBlank { "My stickers" }), createdAt = now)
        baseDir?.let { File(it, pack.id).mkdirs() }
        packs.add(pack)
        save()
        return pack
    }

    /** Registers a pack whose directory was already filled (import path). */
    @Synchronized
    fun adoptPack(pack: StickerPack): StickerPack? {
        if (packs.size >= MAX_PACKS) return null
        val adopted = pack.copy(name = uniqueName(pack.name))
        packs.add(adopted)
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
    fun renamePack(packId: String, name: String) {
        val index = packs.indexOfFirst { it.id == packId }
        if (index < 0) return
        val trimmed = name.trim().ifBlank { return }
        if (trimmed == packs[index].name) return
        packs[index] = packs[index].copy(name = uniqueName(trimmed, exceptId = packId))
        save()
    }

    @Synchronized
    fun deletePack(packId: String) {
        val gone = packs.filter { it.id == packId }
        if (!packs.removeAll { it.id == packId }) return
        baseDir?.let { File(it, packId).deleteRecursively() }
        gone.forEach { pack -> pack.stickers.forEach { deleteKept(it.id) } }
        save()
    }

    @Synchronized
    fun reorderPacks(order: List<String>) {
        val byId = packs.associateBy { it.id }
        val reordered = order.mapNotNull { byId[it] } + packs.filter { it.id !in order }
        if (reordered.size != packs.size) return
        packs.clear()
        packs.addAll(reordered)
        save()
    }

    // ---- stickers ------------------------------------------------------

    /**
     * Adds a sticker to [packId]. [original] is the picture it was made from,
     * kept for a later edit; pass null when the sticker is its own original
     * (a provider sticker, an imported pack) or when there is nothing to keep.
     */
    @Synchronized
    fun addSticker(
        packId: String,
        processed: ProcessedSticker,
        name: String = "",
        keywords: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
        original: ByteArray? = null,
    ): StickerAddResult {
        val index = packs.indexOfFirst { it.id == packId }
        if (index < 0) return StickerAddResult.PackMissing
        if (packs[index].stickers.size >= MAX_STICKERS_PER_PACK) return StickerAddResult.PackFull
        val dir = packDir(packId) ?: return StickerAddResult.WriteFailed
        val id = newStickerId()
        val fileName = fileNameFor(id, processed.mime)
        val written = runCatching {
            val part = File(dir, "$fileName.part")
            part.writeBytes(processed.bytes)
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
        if (!written) return StickerAddResult.WriteFailed
        original?.let { writeOriginal(id, it) }

        val sticker = CustomSticker(
            id = id,
            fileName = fileName,
            mime = processed.mime,
            name = name.trim(),
            keywords = keywords,
            animated = processed.animated,
            aspectRatio = processed.aspectRatio,
            addedAt = now,
        )
        packs[index] = packs[index].copy(stickers = packs[index].stickers + sticker)
        save()
        return StickerAddResult.Added(sticker)
    }

    /**
     * Replaces the image behind [stickerId] with an edited one, keeping the
     * sticker's id, its search words, when it was added and where it sits in
     * the pack. Its shape comes from [processed] — the editor always emits a
     * square still, and the manifest has to say so.
     *
     * The file is written under a new name and the old one deleted; see
     * [nextFileName] for why. A delete that fails leaves a file the manifest
     * does not name, which [reconcile] sweeps on the next load.
     *
     * The first edit of a sticker with no kept original promotes the picture
     * it is about to replace: an edit is destructive, and the state before it
     * is the best starting point still available. Later edits leave that first
     * original alone, so erasing a background twice cannot eat the photo.
     *
     * [original], when given, replaces the kept original outright: the editor
     * passes the picture it upscaled, which is the sticker's source from then on.
     */
    @Synchronized
    fun replaceStickerImage(
        packId: String,
        stickerId: String,
        processed: ProcessedSticker,
        original: ByteArray? = null,
    ): StickerAddResult {
        val index = packs.indexOfFirst { it.id == packId }
        if (index < 0) return StickerAddResult.PackMissing
        val stickers = packs[index].stickers.toMutableList()
        val at = stickers.indexOfFirst { it.id == stickerId }
        if (at < 0) return StickerAddResult.PackMissing
        val dir = packDir(packId) ?: return StickerAddResult.WriteFailed
        val previous = stickers[at]
        if (original != null) {
            writeOriginal(stickerId, original)
        } else if (!previous.animated && originalFor(stickerId) == null) {
            runCatching { File(dir, previous.fileName).readBytes() }
                .getOrNull()
                ?.let { writeOriginal(stickerId, it) }
        }
        val fileName = nextFileName(previous.fileName, previous.id, processed.mime)
        val written = runCatching {
            val part = File(dir, "$fileName.part")
            part.writeBytes(processed.bytes)
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
        if (!written) return StickerAddResult.WriteFailed

        if (previous.fileName != fileName) File(dir, previous.fileName).delete()
        val sticker = previous.copy(
            fileName = fileName,
            mime = processed.mime,
            animated = processed.animated,
            aspectRatio = processed.aspectRatio,
        )
        stickers[at] = sticker
        packs[index] = packs[index].copy(stickers = stickers)
        save()
        return StickerAddResult.Added(sticker)
    }

    @Synchronized
    fun removeSticker(packId: String, stickerId: String) {
        val index = packs.indexOfFirst { it.id == packId }
        if (index < 0) return
        val sticker = packs[index].stickers.firstOrNull { it.id == stickerId } ?: return
        packs[index] = packs[index].copy(stickers = packs[index].stickers - sticker)
        fileFor(packId, sticker)?.delete()
        deleteKept(stickerId)
        save()
    }

    /**
     * Removes every sticker of [stickerIds] from [packId] in one go (#965),
     * with one manifest write rather than one per sticker. Returns how many
     * were removed.
     */
    @Synchronized
    fun removeStickers(packId: String, stickerIds: Set<String>): Int {
        val index = packs.indexOfFirst { it.id == packId }
        if (index < 0 || stickerIds.isEmpty()) return 0
        val (gone, kept) = packs[index].stickers.partition { it.id in stickerIds }
        if (gone.isEmpty()) return 0
        packs[index] = packs[index].copy(stickers = kept)
        for (sticker in gone) {
            fileFor(packId, sticker)?.delete()
            deleteKept(sticker.id)
        }
        save()
        return gone.size
    }

    @Synchronized
    fun updateSticker(packId: String, stickerId: String, name: String, keywords: List<String>) {
        val index = packs.indexOfFirst { it.id == packId }
        if (index < 0) return
        val stickers = packs[index].stickers.toMutableList()
        val at = stickers.indexOfFirst { it.id == stickerId }
        if (at < 0) return
        stickers[at] = stickers[at].copy(name = name.trim(), keywords = keywords)
        packs[index] = packs[index].copy(stickers = stickers)
        save()
    }

    /** Moves [stickerId] to the end of [toPackId], carrying its file across. */
    @Synchronized
    fun moveSticker(fromPackId: String, stickerId: String, toPackId: String): Boolean {
        if (fromPackId == toPackId) return false
        val fromIndex = packs.indexOfFirst { it.id == fromPackId }
        val toIndex = packs.indexOfFirst { it.id == toPackId }
        if (fromIndex < 0 || toIndex < 0) return false
        if (packs[toIndex].stickers.size >= MAX_STICKERS_PER_PACK) return false
        val sticker = packs[fromIndex].stickers.firstOrNull { it.id == stickerId } ?: return false
        val source = fileFor(fromPackId, sticker) ?: return false
        val targetDir = packDir(toPackId) ?: return false
        val target = File(targetDir, sticker.fileName)
        val moved = runCatching {
            source.renameTo(target) || (source.copyTo(target, overwrite = true).exists() && source.delete())
        }.getOrDefault(false)
        if (!moved) return false
        packs[fromIndex] = packs[fromIndex].copy(stickers = packs[fromIndex].stickers - sticker)
        // Re-read the destination: it may be the same index we just rewrote.
        val destIndex = packs.indexOfFirst { it.id == toPackId }
        packs[destIndex] = packs[destIndex].copy(stickers = packs[destIndex].stickers + sticker)
        save()
        return true
    }

    /**
     * Moves [stickerIds] to the end of [toPackId], in the order they sit in now.
     * Returns how many went, which is short only when that pack fills (#1061).
     */
    @Synchronized
    fun moveStickers(fromPackId: String, stickerIds: Set<String>, toPackId: String): Int =
        pack(fromPackId)?.stickers.orEmpty()
            .filter { it.id in stickerIds }
            .count { moveSticker(fromPackId, it.id, toPackId) }

    /**
     * Copies [stickerIds] to the end of [toPackId], each with an id and a file of
     * its own and whatever is kept for editing it, so changing or deleting one
     * leaves the other alone. Returns how many went (#1061).
     */
    @Synchronized
    fun copyStickers(
        fromPackId: String,
        stickerIds: Set<String>,
        toPackId: String,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (fromPackId == toPackId || packs.none { it.id == toPackId }) return 0
        val copied = pack(fromPackId)?.stickers.orEmpty()
            .filter { it.id in stickerIds }
            .count { copySticker(fromPackId, it, toPackId, now) }
        if (copied > 0) save()
        return copied
    }

    private fun copySticker(fromPackId: String, sticker: CustomSticker, toPackId: String, now: Long): Boolean {
        val index = packs.indexOfFirst { it.id == toPackId }
        if (packs[index].stickers.size >= MAX_STICKERS_PER_PACK) return false
        val source = fileFor(fromPackId, sticker) ?: return false
        val dir = packDir(toPackId) ?: return false
        val id = newStickerId()
        val fileName = fileNameFor(id, sticker.mime)
        if (runCatching { source.copyTo(File(dir, fileName), overwrite = true) }.isFailure) return false
        originalsDir()?.let { kept ->
            for (suffix in listOf(".webp", ".mask.png", ".json")) {
                val file = File(kept, sticker.id + suffix)
                if (file.isFile) runCatching { file.copyTo(File(kept, id + suffix), overwrite = true) }
            }
        }
        val copy = sticker.copy(id = id, fileName = fileName, addedAt = now)
        packs[index] = packs[index].copy(stickers = packs[index].stickers + copy)
        return true
    }

    /** Moves a sticker within its pack by [delta] positions. */
    @Synchronized
    fun reorderSticker(packId: String, stickerId: String, delta: Int) {
        val index = packs.indexOfFirst { it.id == packId }
        if (index < 0 || delta == 0) return
        val stickers = packs[index].stickers.toMutableList()
        val at = stickers.indexOfFirst { it.id == stickerId }
        if (at < 0) return
        val to = (at + delta).coerceIn(0, stickers.size - 1)
        if (to == at) return
        stickers.add(to, stickers.removeAt(at))
        packs[index] = packs[index].copy(stickers = stickers)
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
        loadedToken = currentToken()
    }

    /**
     * Cheap change signal: the manifest's size and mtime. The IME compares it
     * on panel open instead of re-parsing JSON on every keystroke.
     */
    fun stateToken(): Long = currentToken()

    private fun currentToken(): Long {
        val file = manifestFile() ?: return 0L
        if (!file.exists()) return 0L
        return file.length() * 31 + file.lastModified()
    }

    /** Reloads only when the manifest changed on disk. Returns true if it did. */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        if (currentToken() == loadedToken) return false
        reload()
        return true
    }

    @Synchronized
    /**
     * Points the store at [dir] and re-reads it. Only ever called with a real
     * directory after a null one — the direct-boot unlock.
     */
    fun attach(dir: File?) {
        if (dir?.absolutePath == baseDir?.absolutePath) return
        baseDir = dir
        reload()
    }

    fun reload() {
        packs.clear()
        loadedToken = currentToken()
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
        // directories hold — sweeping them would destroy the user's images on
        // the strength of a bad read.
        reconcile(dir, sweepUnknown = readable)
    }

    /**
     * Makes the manifest and the files on disk agree: entries without a file
     * are dropped, files and directories nothing references are deleted.
     * Staging directories belong to an import in flight and are left alone
     * until they are a day old, and so is the originals directory — its files answer to sticker ids
     * rather than to file names, and are swept by id below.
     */
    private fun reconcile(dir: File, sweepUnknown: Boolean) {
        var changed = false
        for (i in packs.indices) {
            val pack = packs[i]
            val packDir = File(dir, pack.id)
            val alive = pack.stickers.filter { File(packDir, it.fileName).isFile }
                .take(MAX_STICKERS_PER_PACK)
            if (alive.size != pack.stickers.size) {
                packs[i] = pack.copy(stickers = alive)
                changed = true
            }
            val referenced = alive.mapTo(HashSet()) { it.fileName }
            packDir.listFiles()?.forEach { if (it.name !in referenced) it.deleteRecursively() }
        }
        val known = packs.mapTo(HashSet()) { it.id }
        if (sweepUnknown) dir.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            if (child.name in known) return@forEach
            val staleBefore = System.currentTimeMillis() - STALE_STAGING_MS
            if (child.name.startsWith(STAGING_PREFIX) && child.lastModified() >= staleBefore) return@forEach
            if (child.name == ORIGINALS_DIR) return@forEach
            child.deleteRecursively()
        }
        if (sweepUnknown) {
            val live = HashSet<String>()
            packs.forEach { pack -> pack.stickers.forEach { live += it.id } }
            File(dir, ORIGINALS_DIR).listFiles()?.forEach { file ->
                // Before the first dot, not the last: one sticker keeps up to
                // three files and two of them have a compound extension
                // (`.mask.png`, `.json.part`). Ids never contain a dot.
                if (file.name.substringBefore('.') !in live) file.delete()
            }
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

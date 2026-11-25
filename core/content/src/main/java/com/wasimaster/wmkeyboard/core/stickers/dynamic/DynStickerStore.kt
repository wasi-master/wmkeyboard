package com.wasimaster.wmkeyboard.core.stickers.dynamic

import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.stickers.StickerArchive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Every dynamic sticker pack on the device.
 *
 * ```
 * filesDir/dynstickers/
 * ├── state.json             favourites, recents, what is switched off
 * ├── packs/<packId>/        one per pack, laid out as a .wmdynstickers zip
 * │   ├── pack.json
 * │   ├── templates/  scenes/  fonts/  images/
 * └── .staging_<id>/         imports in progress
 * ```
 *
 * The built-in pack is read straight from the app's assets
 * (`assets/dynstickers/builtin/`) and never copied: it is read-only, and the
 * builder duplicates a template into a pack of the user's to change it.
 *
 * Same contract as the other stores: one per process (the keyboard and the
 * settings screens share it), mutators write through at once, [revision] is
 * how readers notice, and the directory is null before the first unlock.
 */
class DynStickerStore(
    private var baseDir: File?,
    private val assets: AssetReader,
) {

    /** Reads a file under `assets/dynstickers/`. */
    fun interface AssetReader {
        fun open(path: String): InputStream?
    }

    @Serializable
    data class State(
        val disabledPacks: List<String> = emptyList(),
        val disabledTemplates: List<String> = emptyList(),
        val favourites: List<String> = emptyList(),
        val recents: List<String> = emptyList(),
        /** User pack order; packs not listed follow in the order they were added. */
        val order: List<String> = emptyList(),
    )

    /** A pack as loaded: its manifest and where its files are (null for the built-in one, which is in assets). */
    class Loaded(val meta: DynPackMeta, val dir: File?)

    sealed interface ImportResult {
        data class Imported(val pack: DynPackMeta, val templates: Int, val skipped: Int) : ImportResult

        /** Nothing in the file is a template: no Lottie document with text in it. */
        data object NoTemplates : ImportResult

        data object TooMany : ImportResult

        /** The store is not ready (before the first unlock), or writing failed. */
        data object Failed : ImportResult
    }

    private val packs = LinkedHashMap<String, Loaded>()
    private var state = State()
    private var resolved: List<DynTemplate>? = null

    /**
     * Parsed documents, least recently used first, bounded by their estimated
     * heap ([heapWeight]) rather than by count: a compiled built-in scene is
     * some 400 KB of JSON tree and an imported document can be fifty times
     * that, so the 64 entries this used to keep were 27 MB on a phone that
     * had only scrolled the panel once.
     */
    private val documents = LinkedHashMap<String, CachedDocument>(DOC_INITIAL, LOAD_FACTOR, true)
    private var documentBytes = 0L

    private class CachedDocument(val doc: JsonObject, val weight: Long)

    /** The art library, read when a scene first draws from it; see [ArtLibrary]. */
    private var art: ArtLibrary? = null
    private val artLock = Any()

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change of packs, templates or state. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        reload()
    }

    companion object {
        @Volatile
        private var shared: DynStickerStore? = null

        fun get(context: android.content.Context): DynStickerStore =
            shared ?: synchronized(this) {
                val app = context.applicationContext
                shared ?: DynStickerStore(dirFor(app)) { path ->
                    runCatching { app.assets.open("$ASSET_ROOT/$path") }.getOrNull()
                }.also { shared = it }
            }

        /**
         * Lets the shared store's parsed documents and art go, if the process
         * has one; see [release]. Never builds the store just to empty it.
         */
        fun releaseShared() {
            shared?.release()
        }

        /** Re-points the shared store after a direct-boot unlock. */
        fun attach(context: android.content.Context) {
            get(context).attach(dirFor(context.applicationContext))
        }

        private fun dirFor(context: android.content.Context): File? =
            if (DirectBoot.isUserUnlocked(context)) File(context.filesDir, DIR_NAME) else null

        const val DIR_NAME = "dynstickers"
        const val ASSET_ROOT = "dynstickers"
        const val BUILTIN_ID = "builtin"
        private const val BUILTIN_DIR = "builtin"
        private const val ART_DIR = "art"
        private const val PACKS_DIR = "packs"
        private const val STATE_FILE = "state.json"
        private const val STAGING_PREFIX = ".staging_"
        private const val DOC_INITIAL = 16
        private const val LOAD_FACTOR = 0.75f

        /**
         * What the parsed documents may take of the heap together, as
         * [heapWeight] estimates it (some 15% under the real thing, measured
         * over the built-in pack). Small on purpose: a built-in scene compiles
         * again in about a millisecond, a fraction of drawing it.
         */
        private const val DOC_BUDGET_BYTES = 4L * 1024 * 1024

        // Rough heap costs of a kotlinx JSON tree on ART, for [heapWeight]:
        // a JsonObject is its LinkedHashMap and table, each entry a node plus
        // its key string, a JsonArray its ArrayList, a primitive its literal
        // plus the content string (compact, a byte per Latin-1 character).
        private const val OBJECT_BYTES = 96L
        private const val ENTRY_BYTES = 64L
        private const val ARRAY_BYTES = 48L
        private const val SLOT_BYTES = 4L
        private const val PRIMITIVE_BYTES = 48L
        private const val RECENTS = 24
        const val MAX_PACKS = 200
        const val MAX_TEMPLATES = 600
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private const val FONTS_XML = "fonts.xml"
        private const val DOTLOTTIE_MANIFEST = "manifest.json"
        private const val GZIP_0: Byte = 0x1F
        private const val GZIP_1: Byte = -0x75
        private const val MAX_ID = 48
        private const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024

        private val SAFE_ID = Regex("[^a-z0-9_-]")
        private val VERSION_SUFFIX = Regex("(_still)?_v?\\d+$")
    }

    // ---- reading -------------------------------------------------------

    @Synchronized
    fun packs(): List<DynPackMeta> = packs.values.map { it.meta }

    @Synchronized
    fun pack(id: String): DynPackMeta? = packs[id]?.meta

    @Synchronized
    fun state(): State = state

    /** The pack's directory, or null for the built-in pack (whose files are assets). */
    @Synchronized
    fun packDir(id: String): File? = packs[id]?.dir

    /** Every template, disabled ones included, in pack order. */
    @Synchronized
    fun allTemplates(): List<DynTemplate> {
        resolved?.let { return it }
        val out = ArrayList<DynTemplate>()
        for (loaded in orderedPacks()) {
            for (meta in loaded.meta.templates) {
                val slots = meta.slots.ifEmpty { document(loaded, meta)?.let { TemplateProps.slots(it, TemplateProps.read(it)) }.orEmpty() }
                if (slots.isEmpty()) continue
                out += DynTemplate(loaded.meta.id, meta, slots, loaded.meta.source, meta.match.phrases.map(DynText::normalize).distinct())
            }
        }
        resolved = out
        return out
    }

    /** The templates switched on, in packs switched on. */
    @Synchronized
    fun templates(): List<DynTemplate> {
        val off = state.disabledTemplates.toHashSet()
        val offPacks = state.disabledPacks.toHashSet()
        return allTemplates().filter { it.packId !in offPacks && it.key !in off }
    }

    @Synchronized
    fun template(key: String): DynTemplate? = allTemplates().firstOrNull { it.key == key }

    /** [template]'s document, parsed (compiled from its scene when it has no file). Cached. */
    @Synchronized
    fun document(template: DynTemplate): JsonObject? {
        val loaded = packs[template.packId] ?: return null
        return document(loaded, template.meta)
    }

    private fun document(loaded: Loaded, meta: TemplateMeta): JsonObject? {
        val key = loaded.meta.id + "/" + meta.id
        documents[key]?.let { return it.doc }
        val doc = if (meta.file.isNotBlank()) {
            readText(loaded, meta.file)?.let(LottieDoc::parse)
        } else {
            meta.scene?.let { readText(loaded, it) }?.let { compileScene(it, meta)?.document }
        } ?: return null
        rememberDocument(key, doc)
        return doc
    }

    /** Keeps [doc] under [key], letting the least recently used go past the budget. One bigger than all of it is not kept. */
    private fun rememberDocument(key: String, doc: JsonObject) {
        val weight = heapWeight(doc)
        if (weight > DOC_BUDGET_BYTES) return
        documents.put(key, CachedDocument(doc, weight))?.let { documentBytes -= it.weight }
        documentBytes += weight
        // Entries, not keys and a lookup: a get on an access-ordered map is a
        // modification, and would end the iteration.
        val it = documents.entries.iterator()
        while (documentBytes > DOC_BUDGET_BYTES && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            documentBytes -= eldest.value.weight
            it.remove()
        }
    }

    /** Roughly what [element] takes on the heap as a parsed tree; shared subtrees count once per use. */
    private fun heapWeight(element: JsonElement): Long = when (element) {
        is JsonObject -> element.entries.fold(OBJECT_BYTES) { sum, (k, v) -> sum + ENTRY_BYTES + k.length + heapWeight(v) }
        is JsonArray -> element.fold(ARRAY_BYTES) { sum, v -> sum + SLOT_BYTES + heapWeight(v) }
        is JsonPrimitive -> PRIMITIVE_BYTES + element.content.length
    }

    /**
     * Lets go of everything parsed: the documents and the art library. All of
     * it is read again on demand, at the cost of a parse. The keyboard calls
     * this once nothing on screen draws a dynamic sticker.
     */
    fun release() {
        synchronized(this) {
            documents.clear()
            documentBytes = 0L
        }
        synchronized(artLock) { art = null }
    }

    /** The scene behind [template], for the builder, or null when it has none. */
    @Synchronized
    fun scene(template: DynTemplate): DynScene? {
        val loaded = packs[template.packId] ?: return null
        val path = template.meta.scene ?: return null
        val text = readText(loaded, path) ?: return null
        return runCatching { DynPackFile.json.decodeFromString(DynScene.serializer(), text) }.getOrNull()
    }

    /** Compiles a scene's JSON with the art library, for [document] and the builder's preview. */
    fun compileScene(text: String, meta: TemplateMeta): SceneToLottie.Compiled? {
        val scene = runCatching { DynPackFile.json.decodeFromString(DynScene.serializer(), text) }.getOrNull() ?: return null
        return SceneToLottie.compile(scene, meta.name, meta.match, meta.altText, ::artFor)
    }

    /** A drawing from the built-in art library. */
    fun artFor(id: String): SceneToLottie.Art? = synchronized(artLock) { artLibrary()?.get(id) }

    /** Every art id, for the builder's picker. */
    fun artIds(): List<String> = synchronized(artLock) { artLibrary()?.ids?.sorted().orEmpty() }

    /** Under [artLock]. */
    private fun artLibrary(): ArtLibrary? {
        art?.let { return it }
        val bytes = assets.open("$ART_DIR/art.json")?.use { it.readBytes() } ?: return null
        return ArtLibrary(bytes).also { art = it }
    }

    /** A font file a pack carries, by family or by file name; null when it carries none. */
    @Synchronized
    fun fontFile(packId: String, family: String): File? {
        val loaded = packs[packId] ?: return null
        val dir = loaded.dir ?: return null
        val font = loaded.meta.fonts.firstOrNull { it.family.equals(family, ignoreCase = true) }
            ?: loaded.meta.fonts.firstOrNull { it.file.substringAfterLast('/').substringBeforeLast('.').equals(family, ignoreCase = true) }
            ?: return null
        return DynPackFile.resolve(dir, font.file)?.takeIf { it.isFile }
    }

    /** The bytes of a picture a template uses, from its pack's `images/` (or the built-in assets). */
    @Synchronized
    fun imageBytes(packId: String, name: String): ByteArray? {
        val loaded = packs[packId] ?: return null
        val clean = name.substringAfterLast('/')
        if (clean.isBlank()) return null
        return readBytes(loaded, "${DynPackFile.IMAGES_DIR}/$clean")
    }

    private fun readText(loaded: Loaded, path: String): String? = readBytes(loaded, path)?.decodeToString()

    private fun readBytes(loaded: Loaded, path: String): ByteArray? {
        val dir = loaded.dir
        if (dir == null) {
            if (path.contains("..")) return null
            return assets.open("$BUILTIN_DIR/$path")?.use { it.readBytes() }
        }
        return DynPackFile.resolve(dir, path)?.takeIf { it.isFile }?.readBytes()
    }

    private fun orderedPacks(): List<Loaded> {
        val order = state.order
        val builtin = packs[BUILTIN_ID]
        val others = packs.values.filter { it.meta.id != BUILTIN_ID }
            .sortedBy { order.indexOf(it.meta.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return listOfNotNull(builtin) + others
    }

    // ---- state -----------------------------------------------------------

    @Synchronized
    fun setPackEnabled(packId: String, enabled: Boolean) =
        updateState { it.copy(disabledPacks = toggle(it.disabledPacks, packId, !enabled)) }

    @Synchronized
    fun setTemplateEnabled(key: String, enabled: Boolean) =
        updateState { it.copy(disabledTemplates = toggle(it.disabledTemplates, key, !enabled)) }

    @Synchronized
    fun setFavourite(key: String, favourite: Boolean) =
        updateState { it.copy(favourites = toggle(it.favourites, key, favourite)) }

    /** Moves [key] to the front of the recently sent list. */
    @Synchronized
    fun noteSent(key: String) = updateState { it.copy(recents = (listOf(key) + it.recents.filter { r -> r != key }).take(RECENTS)) }

    @Synchronized
    fun setPackOrder(ids: List<String>) = updateState { it.copy(order = ids) }

    private fun toggle(list: List<String>, key: String, present: Boolean): List<String> =
        if (present) (list.filter { it != key } + key) else list.filter { it != key }

    private fun updateState(change: (State) -> State) {
        state = change(state)
        saveState()
        bump()
    }

    // ---- importing -------------------------------------------------------

    /**
     * Imports a zip: our own `.wmdynstickers`, a template set's document or
     * font zip, or a dotLottie file. [replaceId] overwrites that pack (a feed
     * or addon update); otherwise a pack of the same id from the same source
     * is replaced and anything else gets a fresh id.
     */
    @Synchronized
    fun importArchive(
        input: InputStream,
        source: PackSource,
        fallbackName: String,
        feedId: String? = null,
        sourceUrl: String? = null,
        replaceId: String? = null,
    ): ImportResult = importArchives(listOf(input), source, fallbackName, feedId, sourceUrl, replaceId)

    /**
     * Imports several zips as one pack: what a feed whose documents and fonts
     * come in separate zips downloads.
     */
    @Synchronized
    fun importArchives(
        inputs: List<InputStream>,
        source: PackSource,
        fallbackName: String,
        feedId: String? = null,
        sourceUrl: String? = null,
        replaceId: String? = null,
    ): ImportResult {
        val base = baseDir ?: return ImportResult.Failed
        val staging = File(base, STAGING_PREFIX + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        try {
            val files = LinkedHashMap<String, File>()
            val texts = HashMap<String, String>()
            inputs.forEachIndexed { i, input ->
                val part = File(staging, "z$i").apply { mkdirs() }
                val unpacked = StickerArchive.unpack(input, part, setOf(DynPackFile.MANIFEST, FONTS_XML, DOTLOTTIE_MANIFEST))
                files.putAll(unpacked.files)
                unpacked.texts.forEach { (k, v) -> texts.putIfAbsent(k, v) }
            }
            val ours = texts[DynPackFile.MANIFEST]?.let(DynPackFile::readManifest)
            return if (ours != null) {
                adoptOurs(ours, files, source, feedId, sourceUrl, replaceId)
            } else {
                adoptLoose(files, texts[FONTS_XML], fallbackName, source, feedId, sourceUrl, replaceId)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Imports one document: a Lottie `.json`, or a gzipped one (`.tgs`).
     * Into [intoPackId] when given, else into a new pack named after the file.
     */
    @Synchronized
    fun importDocument(bytes: ByteArray, fileName: String, intoPackId: String? = null): ImportResult {
        val text = (if (isGzip(bytes)) gunzip(bytes) else bytes)?.decodeToString() ?: return ImportResult.NoTemplates
        val doc = LottieDoc.parse(text) ?: return ImportResult.NoTemplates
        val id = templateId(fileName.substringBeforeLast('.'))
        val meta = metaFor(doc, id, "${DynPackFile.TEMPLATES_DIR}/$id.json") ?: return ImportResult.NoTemplates
        val pack = intoPackId?.let { packs[it] }?.takeIf { it.meta.source == PackSource.USER }
            ?: createPackInternal(fileName.substringBeforeLast('.').ifBlank { "Templates" }) ?: return ImportResult.Failed
        val saved = saveTemplateInternal(pack.meta.id, meta, LottieDoc.encode(doc), null) ?: return ImportResult.Failed
        return ImportResult.Imported(packs.getValue(pack.meta.id).meta.takeIf { saved.id.isNotEmpty() } ?: pack.meta, 1, 0)
    }

    private fun adoptOurs(
        manifest: DynPackMeta,
        files: Map<String, File>,
        source: PackSource,
        feedId: String?,
        sourceUrl: String?,
        replaceId: String?,
    ): ImportResult {
        val id = targetId(manifest.id, source, replaceId) ?: return ImportResult.TooMany
        val dir = freshDir(id) ?: return ImportResult.Failed
        fun bring(path: String): Boolean {
            val from = lookup(files, path) ?: return false
            val to = DynPackFile.resolve(dir, path) ?: return false
            to.parentFile?.mkdirs()
            from.copyTo(to, overwrite = true)
            return true
        }
        var skipped = 0
        val templates = ArrayList<TemplateMeta>()
        for (template in manifest.templates.take(MAX_TEMPLATES)) {
            val hasDoc = template.file.isNotBlank() && bring(template.file)
            val hasScene = template.scene?.let(::bring) == true
            if (!hasDoc && !hasScene) {
                skipped++
                continue
            }
            var meta = template.copy(scene = template.scene.takeIf { hasScene }, file = template.file.takeIf { hasDoc }.orEmpty())
            if (meta.slots.isEmpty() && hasDoc) {
                val doc = DynPackFile.resolve(dir, meta.file)?.readText()?.let(LottieDoc::parse)
                if (doc != null) metaFor(doc, meta.id, meta.file)?.let { derived -> meta = mergeDerived(meta, derived) }
            }
            templates += meta
        }
        val fonts = manifest.fonts.filter { bring(it.file) }
        files.keys.filter { it.startsWith(DynPackFile.IMAGES_DIR + "/") }.forEach { bring(it) }
        if (templates.isEmpty()) {
            dir.deleteRecursively()
            return ImportResult.NoTemplates
        }
        val meta = manifest.copy(
            id = id,
            name = manifest.name.ifBlank { id },
            templates = templates,
            fonts = fonts,
            source = source,
            feedId = feedId,
            sourceUrl = sourceUrl,
            updatedAt = System.currentTimeMillis(),
        )
        return commit(id, dir, meta, skipped)
    }

    private fun adoptLoose(
        files: Map<String, File>,
        fontsXml: String?,
        name: String,
        source: PackSource,
        feedId: String?,
        sourceUrl: String?,
        replaceId: String?,
    ): ImportResult {
        val id = targetId(templateId(name).ifBlank { "pack" }, source, replaceId) ?: return ImportResult.TooMany
        val dir = freshDir(id) ?: return ImportResult.Failed
        val templates = ArrayList<TemplateMeta>()
        var skipped = 0
        val families = HashMap<String, String>()
        fontsXml?.let { FontsXml.parse(it) }?.forEach { (family, list) -> list.forEach { families.putIfAbsent(it.lowercase(), family) } }
        val fonts = ArrayList<PackFont>()
        val takenIds = HashSet<String>()
        for ((entry, file) in files) {
            val tail = StickerArchive.tailOf(entry)
            val lower = tail.lowercase()
            when {
                lower.endsWith(".json") && lower != DOTLOTTIE_MANIFEST -> {
                    if (templates.size >= MAX_TEMPLATES) {
                        skipped++
                        continue
                    }
                    val bytes = file.readBytes()
                    val text = (if (isGzip(bytes)) gunzip(bytes) else bytes)?.decodeToString()
                    val doc = text?.let(LottieDoc::parse)
                    var templateId = templateId(tail.substringBeforeLast('.'))
                    var n = 2
                    while (!takenIds.add(templateId)) templateId = "${templateId(tail.substringBeforeLast('.'))}_${n++}"
                    val path = "${DynPackFile.TEMPLATES_DIR}/$templateId.json"
                    val meta = doc?.let { metaFor(it, templateId, path) }
                    if (meta == null) {
                        skipped++
                        continue
                    }
                    DynPackFile.resolve(dir, path)?.let { target ->
                        target.parentFile?.mkdirs()
                        target.writeText(LottieDoc.encode(doc))
                        templates += meta
                    }
                }
                lower.endsWith(".ttf") || lower.endsWith(".otf") -> {
                    val clean = safeFileName(tail)
                    val path = "${DynPackFile.FONTS_DIR}/$clean"
                    DynPackFile.resolve(dir, path)?.let { target ->
                        target.parentFile?.mkdirs()
                        file.copyTo(target, overwrite = true)
                        val family = families[lower] ?: tail.substringBeforeLast('.').substringBefore('-')
                        fonts += PackFont(family = family, file = path, style = tail.substringBeforeLast('.').substringAfter('-', ""))
                        // Also under the file's own name, which is what a document's fName often is.
                        val own = tail.substringBeforeLast('.')
                        if (!own.equals(family, ignoreCase = true)) fonts += PackFont(family = own, file = path)
                    }
                }
                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") -> {
                    DynPackFile.resolve(dir, "${DynPackFile.IMAGES_DIR}/${safeFileName(tail)}")?.let { target ->
                        target.parentFile?.mkdirs()
                        file.copyTo(target, overwrite = true)
                    }
                }
            }
        }
        if (templates.isEmpty()) {
            // A fonts-only zip on its own is not a pack.
            dir.deleteRecursively()
            return ImportResult.NoTemplates
        }
        val meta = DynPackMeta(
            id = id,
            name = name.ifBlank { id },
            templates = templates.sortedBy { it.name.lowercase() },
            fonts = fonts,
            source = source,
            feedId = feedId,
            sourceUrl = sourceUrl,
            updatedAt = System.currentTimeMillis(),
        )
        return commit(id, dir, meta, skipped)
    }

    /**
     * A manifest entry for a document: its rules from `customProps`, or read
     * off its text layers. Null when it has no text layer at all (an
     * animation, not a template).
     */
    fun metaFor(doc: JsonObject, id: String, path: String): TemplateMeta? {
        val props = TemplateProps.read(doc)
        val slots = TemplateProps.slots(doc, props)
        if (slots.isEmpty()) return null
        val photos = TemplateProps.photos(doc)
        val firstPhrase = props?.match?.phrases?.firstOrNull()
        val name = firstPhrase?.replaceFirstChar { it.titlecase() }
            ?: id.replace(VERSION_SUFFIX, "").replace('_', ' ').replaceFirstChar { it.titlecase() }
        return TemplateMeta(
            id = id,
            name = name,
            file = path,
            kind = if (photos.isNotEmpty()) DynKind.MEME else DynKind.STICKER,
            match = props?.match ?: DynMatch(),
            slots = slots,
            photos = photos,
            altText = props?.altText.orEmpty(),
        )
    }

    private fun mergeDerived(meta: TemplateMeta, derived: TemplateMeta): TemplateMeta = meta.copy(
        slots = derived.slots,
        photos = meta.photos.ifEmpty { derived.photos },
        match = if (meta.match == DynMatch()) derived.match else meta.match,
        altText = meta.altText.ifBlank { derived.altText },
    )

    private fun commit(id: String, dir: File, meta: DynPackMeta, skipped: Int): ImportResult {
        val manifest = File(dir, DynPackFile.MANIFEST)
        if (!writeAtomically(manifest, DynPackFile.writeManifest(meta))) {
            dir.deleteRecursively()
            return ImportResult.Failed
        }
        // Swap the staged directory in for any old copy of the pack.
        val packsDir = packsDir() ?: return ImportResult.Failed
        val target = File(packsDir, id)
        val old = if (target.exists()) File(packsDir, ".old_$id").also { target.renameTo(it) } else null
        if (!dir.renameTo(target)) {
            dir.copyRecursively(target, overwrite = true)
            dir.deleteRecursively()
        }
        old?.deleteRecursively()
        packs[id] = Loaded(meta, target)
        if (id !in state.order) state = state.copy(order = state.order + id)
        saveState()
        invalidate(id)
        return ImportResult.Imported(meta, meta.templates.size, skipped)
    }

    /** The id an import lands under, or null when the pack limit is reached. */
    private fun targetId(wanted: String, source: PackSource, replaceId: String?): String? {
        if (replaceId != null) return replaceId
        val clean = templateId(wanted).ifBlank { "pack" }
        val existing = packs[clean]
        if (existing != null && existing.meta.source == source && existing.meta.id != BUILTIN_ID) return clean
        if (packs.size >= MAX_PACKS) return null
        var candidate = if (existing == null && clean != BUILTIN_ID) clean else "${clean}_2"
        var n = 3
        while (packs.containsKey(candidate)) candidate = "${clean}_${n++}"
        return candidate
    }

    /** A new empty staging directory a pack is assembled in before it replaces the old one. */
    private fun freshDir(id: String): File? {
        val base = baseDir ?: return null
        return File(base, "$STAGING_PREFIX$id-${UUID.randomUUID().toString().take(6)}").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun lookup(files: Map<String, File>, path: String): File? =
        files[path] ?: files.entries.firstOrNull { it.key.endsWith("/$path") }?.value
            ?: files.entries.firstOrNull { StickerArchive.tailOf(it.key) == path.substringAfterLast('/') }?.value

    // ---- editing (the builder) ------------------------------------------

    /** A new empty pack of the user's own. */
    @Synchronized
    fun createPack(name: String): DynPackMeta? = createPackInternal(name)?.meta

    private fun createPackInternal(name: String): Loaded? {
        val id = targetId("my_${templateId(name).ifBlank { "pack" }}", PackSource.USER, null) ?: return null
        val packsDir = packsDir() ?: return null
        val dir = File(packsDir, id).apply { mkdirs() }
        val meta = DynPackMeta(id = id, name = name.ifBlank { "My templates" }, source = PackSource.USER, updatedAt = System.currentTimeMillis())
        if (!writeAtomically(File(dir, DynPackFile.MANIFEST), DynPackFile.writeManifest(meta))) return null
        val loaded = Loaded(meta, dir)
        packs[id] = loaded
        state = state.copy(order = state.order + id)
        saveState()
        invalidate(id)
        return loaded
    }

    /**
     * Writes a template into a user pack: its document (compiled from the
     * scene when there is one) and its scene. Replaces a template with the
     * same id. Returns the entry as saved.
     */
    @Synchronized
    fun saveTemplate(packId: String, meta: TemplateMeta, document: String?, scene: String?): TemplateMeta? =
        saveTemplateInternal(packId, meta, document, scene)

    private fun saveTemplateInternal(packId: String, meta: TemplateMeta, document: String?, scene: String?): TemplateMeta? {
        val loaded = packs[packId] ?: return null
        val dir = loaded.dir ?: return null
        if (loaded.meta.source != PackSource.USER) return null
        val id = templateId(meta.id).ifBlank { "t_${UUID.randomUUID().toString().take(8)}" }
        var saved = meta.copy(id = id)
        if (document != null) {
            val path = "${DynPackFile.TEMPLATES_DIR}/$id.json"
            val file = DynPackFile.resolve(dir, path) ?: return null
            file.parentFile?.mkdirs()
            if (!writeAtomically(file, document)) return null
            saved = saved.copy(file = path)
        }
        if (scene != null) {
            val path = "${DynPackFile.SCENES_DIR}/$id.json"
            val file = DynPackFile.resolve(dir, path) ?: return null
            file.parentFile?.mkdirs()
            if (!writeAtomically(file, scene)) return null
            saved = saved.copy(scene = path)
        }
        val list = loaded.meta.templates
        val next = if (list.any { it.id == id }) list.map { if (it.id == id) saved else it } else list + saved
        if (next.size > MAX_TEMPLATES) return null
        return if (writePack(loaded, loaded.meta.copy(templates = next))) saved else null
    }

    /** Copies a picture into a user pack's `images/`, returning the name a scene refers to it by. */
    @Synchronized
    fun addImage(packId: String, bytes: ByteArray, extension: String): String? {
        val loaded = packs[packId] ?: return null
        val dir = loaded.dir ?: return null
        val name = "img_${UUID.randomUUID().toString().take(8)}.${extension.lowercase().ifBlank { "png" }}"
        val file = DynPackFile.resolve(dir, "${DynPackFile.IMAGES_DIR}/$name") ?: return null
        file.parentFile?.mkdirs()
        return runCatching { file.writeBytes(bytes); name }.getOrNull()
    }

    @Synchronized
    fun deleteTemplate(packId: String, templateId: String): Boolean {
        val loaded = packs[packId] ?: return false
        val dir = loaded.dir ?: return false
        val meta = loaded.meta.templates.firstOrNull { it.id == templateId } ?: return false
        meta.file.takeIf { it.isNotBlank() }?.let { DynPackFile.resolve(dir, it)?.delete() }
        meta.scene?.let { DynPackFile.resolve(dir, it)?.delete() }
        val key = "$packId/$templateId"
        state = state.copy(
            favourites = state.favourites - key,
            disabledTemplates = state.disabledTemplates - key,
            recents = state.recents - key,
        )
        saveState()
        return writePack(loaded, loaded.meta.copy(templates = loaded.meta.templates.filter { it.id != templateId }))
    }

    /**
     * Copies a template (from any pack, the built-in one included) into a
     * user pack, document and scene both, under a new id.
     */
    @Synchronized
    fun duplicateTemplate(key: String, intoPackId: String): TemplateMeta? {
        val template = allTemplates().firstOrNull { it.key == key } ?: return null
        val from = packs[template.packId] ?: return null
        val sceneText = template.meta.scene?.let { readText(from, it) }
        val docText = if (template.meta.file.isNotBlank()) readText(from, template.meta.file) else null
        val into = packs[intoPackId] ?: return null
        var id = "${template.meta.id}_copy"
        var n = 2
        while (into.meta.templates.any { it.id == id }) id = "${template.meta.id}_copy$n".also { n++ }
        // Pictures the copy refers to come along.
        from.meta.fonts.forEach { font ->
            val bytes = readBytes(from, font.file) ?: return@forEach
            val dir = into.dir ?: return@forEach
            val target = DynPackFile.resolve(dir, font.file) ?: return@forEach
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            if (into.meta.fonts.none { it.file == font.file }) {
                writePack(into, into.meta.copy(fonts = into.meta.fonts + font))
            }
        }
        // And the pictures it draws: the scene's own, and the document's.
        val pictures = LinkedHashSet<String>()
        sceneText?.let { text ->
            runCatching { DynPackFile.json.decodeFromString(DynScene.serializer(), text) }.getOrNull()
                ?.elements?.mapNotNullTo(pictures) { it.image }
        }
        docText?.let(LottieDoc::parse)?.let { doc -> LottieDoc.imageAssets(doc).mapNotNullTo(pictures) { it.file } }
        val intoDir = into.dir
        if (intoDir != null) {
            for (name in pictures) {
                val clean = name.substringAfterLast('/')
                val target = DynPackFile.resolve(intoDir, "${DynPackFile.IMAGES_DIR}/$clean") ?: continue
                if (target.exists()) continue
                val bytes = readBytes(from, "${DynPackFile.IMAGES_DIR}/$clean") ?: continue
                target.parentFile?.mkdirs()
                runCatching { target.writeBytes(bytes) }
            }
        }
        val copy = template.meta.copy(id = id, name = template.name + " (copy)", file = "", scene = null)
        return saveTemplateInternal(intoPackId, copy, docText, sceneText)
    }

    @Synchronized
    fun renamePack(packId: String, name: String): Boolean {
        val loaded = packs[packId] ?: return false
        if (loaded.dir == null) return false
        return writePack(loaded, loaded.meta.copy(name = name.ifBlank { loaded.meta.name }))
    }

    @Synchronized
    fun reorderTemplates(packId: String, ids: List<String>): Boolean {
        val loaded = packs[packId] ?: return false
        if (loaded.dir == null) return false
        val byId = loaded.meta.templates.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] } + loaded.meta.templates.filter { it.id !in ids }
        return writePack(loaded, loaded.meta.copy(templates = ordered))
    }

    @Synchronized
    fun deletePack(packId: String): Boolean {
        if (packId == BUILTIN_ID) return false
        val loaded = packs.remove(packId) ?: return false
        loaded.dir?.deleteRecursively()
        val prefix = "$packId/"
        state = state.copy(
            order = state.order - packId,
            disabledPacks = state.disabledPacks - packId,
            favourites = state.favourites.filterNot { it.startsWith(prefix) },
            disabledTemplates = state.disabledTemplates.filterNot { it.startsWith(prefix) },
            recents = state.recents.filterNot { it.startsWith(prefix) },
        )
        saveState()
        invalidate(packId)
        return true
    }

    /** The packs a feed downloaded, for refreshing or removing it. */
    @Synchronized
    fun packsOfFeed(feedId: String): List<DynPackMeta> = packs.values.map { it.meta }.filter { it.feedId == feedId }

    /** Writes a pack as a `.wmdynstickers` zip. The built-in pack cannot be exported (its scenes ship in the app). */
    @Synchronized
    fun export(packId: String, out: OutputStream): Boolean {
        val loaded = packs[packId] ?: return false
        val dir = loaded.dir ?: return false
        return runCatching { DynPackFile.write(dir, loaded.meta, out); true }.getOrDefault(false)
    }

    private fun writePack(loaded: Loaded, meta: DynPackMeta): Boolean {
        val dir = loaded.dir ?: return false
        val stamped = meta.copy(updatedAt = System.currentTimeMillis())
        if (!writeAtomically(File(dir, DynPackFile.MANIFEST), DynPackFile.writeManifest(stamped))) return false
        packs[meta.id] = Loaded(stamped, dir)
        invalidate(meta.id)
        return true
    }

    private fun invalidate(packId: String) {
        resolved = null
        val prefix = "$packId/"
        val it = documents.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key.startsWith(prefix)) {
                documentBytes -= entry.value.weight
                it.remove()
            }
        }
        bump()
    }

    private fun bump() {
        _revision.value++
    }

    // ---- persistence -------------------------------------------------------

    private fun packsDir(): File? = baseDir?.let { File(it, PACKS_DIR).apply { mkdirs() } }

    @Synchronized
    fun attach(dir: File?) {
        if (dir?.absolutePath == baseDir?.absolutePath) return
        baseDir = dir
        reload()
    }

    @Synchronized
    fun reload() {
        packs.clear()
        documents.clear()
        documentBytes = 0L
        resolved = null
        loadBuiltin()?.let { packs[BUILTIN_ID] = Loaded(it, null) }
        val base = baseDir
        if (base != null) {
            state = File(base, STATE_FILE).takeIf { it.isFile }?.let { file ->
                runCatching { DynPackFile.json.decodeFromString(State.serializer(), file.readText()) }.getOrNull()
            } ?: State()
            File(base, PACKS_DIR).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name }
                .forEach { dir ->
                    val meta = File(dir, DynPackFile.MANIFEST).takeIf { it.isFile }
                        ?.let { runCatching { DynPackFile.readManifest(it.readText()) }.getOrNull() }
                        ?: return@forEach
                    // The directory name is the id; a hand-edited manifest cannot move a pack.
                    packs[dir.name] = Loaded(meta.copy(id = dir.name), dir)
                }
            sweepStaging(base)
        }
        bump()
    }

    private fun loadBuiltin(): DynPackMeta? {
        val text = assets.open("$BUILTIN_DIR/${DynPackFile.MANIFEST}")?.use { it.readBytes().decodeToString() } ?: return null
        return DynPackFile.readManifest(text)?.copy(id = BUILTIN_ID, source = PackSource.BUILTIN)
    }

    /** Staging directories an interrupted import left behind, once they are a day old. */
    private fun sweepStaging(base: File) {
        val now = System.currentTimeMillis()
        val dirs = base.listFiles().orEmpty().toList() + File(base, PACKS_DIR).listFiles().orEmpty().toList()
        dirs.filter { it.isDirectory && (it.name.startsWith(STAGING_PREFIX) || it.name.startsWith(".old_")) }
            .filter { now - it.lastModified() > DAY_MS }
            .forEach { it.deleteRecursively() }
    }

    private fun saveState() {
        val base = baseDir ?: return
        writeAtomically(File(base, STATE_FILE), DynPackFile.json.encodeToString(State.serializer(), state))
    }

    private fun writeAtomically(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(text)
        if (!part.renameTo(file)) {
            file.delete()
            part.renameTo(file)
        }
        true
    }.getOrDefault(false)

    private fun templateId(raw: String): String =
        raw.trim().lowercase().replace(' ', '_').replace(SAFE_ID, "").take(MAX_ID)

    private fun safeFileName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "file" }

    private fun isGzip(bytes: ByteArray) = bytes.size > 2 && bytes[0] == GZIP_0 && bytes[1] == GZIP_1

    private fun gunzip(bytes: ByteArray): ByteArray? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_DOCUMENT_BYTES) return@runCatching null
            }
            out.toByteArray()
        }
    }.getOrNull()

}

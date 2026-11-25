package com.wasimaster.wmkeyboard.ime.dynstickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import com.wasimaster.wmkeyboard.core.media.MediaMime
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.settings.DynExactMode
import com.wasimaster.wmkeyboard.core.settings.DynGenericMode
import com.wasimaster.wmkeyboard.core.settings.DynamicStickerSettings
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynEncoder
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynFill
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynFonts
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynKind
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynMatcher
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynRenderer
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynStickerStore
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynTemplate
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynText
import com.wasimaster.wmkeyboard.core.stickers.dynamic.SlotRole
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.tools.GifSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Dynamic stickers in the keyboard: the templates the message suits, offered
 * in the sticker row while typing; every template filled with the text under
 * the stickers panel's Text chip; the fill sheet for templates with several
 * texts or a photo; and the render that is sent.
 *
 * The keyboard shows a template as a [GifItem] of [GifSource.DYNAMIC] whose
 * picture is a `wmdyn:` address: [DynThumbFetcher] draws it when a cell asks,
 * so a grid of a hundred templates renders only the ones on screen. What each
 * address stands for (a template and its fill) is kept here, the last few
 * hundred of them.
 */
internal class DynStickerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    /** What the controller needs from the keyboard. */
    interface Host {
        val settings: DynamicStickerSettings

        /** A face for a font-picker id (`google:…`, `installed:…`), or null. */
        fun typeface(id: String): Typeface?

        /** Emoji whose names match [word], best first, for the meaning of a word. */
        fun emojiFor(word: String): List<String>

        fun haptic()

        /** Sends a finished render the way the sticker panel sends (its own send mode). */
        fun send(output: DynEncoder.Output)

        /** Spins the cell [id] while it renders, and stops. */
        fun startRender(id: String)

        /** How far the render [startRender] named has got: [done] frames of [total]. */
        fun renderProgress(done: Int, total: Int)
        fun endRender()
        fun toast(message: Int)
        fun copy(file: File)
        fun addToStickers(file: File, keywords: List<String>)
        fun openRoute(route: String)
        fun pickPhoto()

        /** Asks the grid to draw again: a filter, a favourite or the store changed. */
        fun refreshGrid()
    }

    val store: DynStickerStore = DynStickerStore.get(context)
    private val fonts = DynFonts(context, store) { id -> host.typeface(id) }
    private val renderer = DynRenderer(store, fonts)

    private val _ui = MutableStateFlow(DynPanelUi())
    val ui: StateFlow<DynPanelUi> = _ui.asStateFlow()

    val callbacks = DynStickerCallbacks(state = ui, onAction = ::onAction, isFavourite = ::isFavourite)

    /** Two renders at a time: a grid scrolled fast asks for a dozen at once. */
    private val renders = Semaphore(MAX_PARALLEL_RENDERS)

    private var matcher: DynMatcher? = null
    private var matcherRevision = -1

    private val entries = object : LinkedHashMap<String, Entry>(REGISTRY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > REGISTRY
    }

    /** A template and what fills it, behind one `wmdyn:` id. */
    class Entry(val templateKey: String, val fill: DynFill)

    /** Photos picked in the fill sheet, by slot id. */
    private val photos = HashMap<String, Uri>()
    private var sheetJob: Job? = null

    init {
        DynThumbs.source = this
        scope.launch {
            // A pack installed, a template edited or switched off: new faces,
            // new chips, and a grid that may show something else now.
            store.revision.collect {
                fonts.clear()
                publishPacks()
                host.refreshGrid()
            }
        }
    }

    // ---- matching ------------------------------------------------------

    private fun matcher(): DynMatcher = synchronized(this) {
        val revision = store.revision.value
        matcher?.takeIf { revision == matcherRevision } ?: DynMatcher(store.templates()).also {
            matcher = it
            matcherRevision = revision
        }
    }

    private fun conceptsOf(word: String): Collection<String> =
        if (word.length < MIN_CONCEPT_WORD) {
            emptyList()
        } else {
            host.emojiFor(word).take(CONCEPT_EMOJI).flatMap { com.wasimaster.wmkeyboard.core.stickers.dynamic.EmojiConcepts.of(it) }
        }

    /**
     * The tray's offer for [message]: what the settings allow, at most their
     * count. Blocking; call off the main thread.
     */
    fun offerFor(message: String): List<GifItem> {
        val settings = host.settings
        if (!settings.enabled || !settings.inTray) return emptyList()
        val text = message.trim()
        if (text.isEmpty() || DynText.wordCount(text) > settings.maxWords) return emptyList()
        val tiers = buildSet {
            if (settings.matchPhrase) add(DynMatcher.Tier.PHRASE)
            if (settings.matchInside) add(DynMatcher.Tier.INSIDE)
            if (settings.matchFeelings) add(DynMatcher.Tier.CONCEPT)
            if (settings.generic != DynGenericMode.OFF) add(DynMatcher.Tier.GENERIC)
        }
        if (tiers.isEmpty()) return emptyList()
        val state = store.state()
        val hits = matcher().match(
            text,
            DynMatcher.Options(
                tiers = tiers,
                maxWords = settings.maxWords,
                limit = settings.maxInTray,
                memes = settings.memesInTray,
                photos = false,
                favourites = state.favourites.toSet(),
            ),
            conceptsOf = if (settings.matchFeelings) ::conceptsOf else { _: String -> emptyList<String>() },
        )
        if (settings.generic == DynGenericMode.AFTER_MATCH && hits.none { it.tier != DynMatcher.Tier.GENERIC }) return emptyList()
        return hits.map { item(it.template, it.fill, animated = settings.animateInTray) }
    }

    /**
     * Every template switched on, filled with [text], for the panel's grid:
     * the ones [text] matches first, then the rest that it fits. A blank
     * [text] shows each template with its own words. Blocking.
     */
    fun panelItems(text: String): List<GifItem> {
        val clean = text.trim()
        val filter = _ui.value.filter
        val state = store.state()
        val favourites = state.favourites.toSet()
        val templates = store.templates().filter { template ->
            when {
                filter == null -> true
                filter == FILTER_STICKERS -> template.kind == DynKind.STICKER
                filter == FILTER_MEMES -> template.kind == DynKind.MEME
                filter == FILTER_FAVOURITES -> template.key in favourites
                filter.startsWith(FILTER_PACK) -> template.packId == filter.removePrefix(FILTER_PACK)
                else -> true
            }
        }
        _ui.update { it.copy(text = clean) }
        if (clean.isEmpty()) {
            val ordered = templates.sortedByDescending { it.key in favourites }
            return ordered.map { item(it, DynFill(emptyMap()), animated = false) }
        }
        val keys = templates.mapTo(HashSet()) { it.key }
        val matched = matcher().match(
            clean,
            DynMatcher.Options(
                tiers = setOf(DynMatcher.Tier.PHRASE, DynMatcher.Tier.INSIDE, DynMatcher.Tier.CONCEPT),
                maxWords = PANEL_MAX_WORDS,
                limit = PANEL_LIMIT,
                memes = true,
                photos = true,
                favourites = favourites,
            ),
            conceptsOf = ::conceptsOf,
        ).filter { it.template.key in keys }
        val seen = matched.mapTo(HashSet()) { it.template.key }
        val rest = templates.filter { it.key !in seen }
            .sortedByDescending { it.key in favourites }
            .mapNotNull { template -> DynMatcher.fillFor(template, clean)?.let { template to it } }
        return matched.map { item(it.template, it.fill, animated = false) } + rest.map { (t, f) -> item(t, f, animated = false) }
    }

    /** The cell for [template] filled with [fill]. */
    private fun item(template: DynTemplate, fill: DynFill, animated: Boolean): GifItem {
        val id = register(template.key, fill)
        val meme = template.kind == DynKind.MEME
        return GifItem(
            id = ITEM_PREFIX + id,
            previewUrl = DynThumbs.uri(id, animated),
            fullUrl = DynThumbs.uri(id, animated = false),
            mime = if (meme && template.meta.photos.isNotEmpty()) MediaMime.PNG else MediaMime.WEBP,
            aspectRatio = 1f,
            source = GifSource.DYNAMIC,
            title = template.name,
        )
    }

    private fun register(templateKey: String, fill: DynFill): String {
        val crc = CRC32().apply { update((templateKey + "\u0000" + fill.cacheKey).toByteArray()) }.value
        val id = java.lang.Long.toHexString(crc) + Integer.toHexString((templateKey + fill.cacheKey).hashCode())
        synchronized(entries) { entries[id] = Entry(templateKey, fill) }
        return id
    }

    private fun entry(id: String): Entry? = synchronized(entries) { entries[id] }

    fun entryOf(item: GifItem): Entry? = if (item.source == GifSource.DYNAMIC) entry(item.id.removePrefix(ITEM_PREFIX)) else null

    /** Whether tapping [item] needs the fill sheet first: a photo to pick. */
    fun needsSheet(item: GifItem): Boolean {
        val entry = entryOf(item) ?: return false
        return store.template(entry.templateKey)?.meta?.photos?.isNotEmpty() == true
    }

    // ---- rendering -----------------------------------------------------

    private fun policy(): DynRenderer.ExactPolicy = when (host.settings.exactEngine) {
        DynExactMode.NEVER -> DynRenderer.ExactPolicy.NEVER
        DynExactMode.IMPORTED -> DynRenderer.ExactPolicy.IMPORTED
        DynExactMode.ALWAYS -> DynRenderer.ExactPolicy.ALWAYS
    }

    /** [entry]'s fill with the user's font override, when one is set and the fill names none. */
    private fun withOverride(fill: DynFill): DynFill {
        val override = host.settings.fontOverride
        return if (override.isBlank() || fill.fontOverride != null) fill else fill.copy(fontOverride = override)
    }

    private fun photoBitmaps(fill: DynFill): Map<String, Bitmap> {
        if (fill.photos.isEmpty()) return emptyMap()
        val out = HashMap<String, Bitmap>()
        for ((slot, uri) in fill.photos) {
            MediaPrep.decode(context, Uri.parse(uri), PHOTO_EDGE)?.let { out[slot] = it }
        }
        return out
    }

    /** A still of [id] at [px], for the grid and the tray. Suspends while two others render. */
    suspend fun thumb(id: String, px: Int): Bitmap? {
        val entry = entry(id) ?: return null
        val template = store.template(entry.templateKey) ?: return null
        return renders.withPermit {
            withContext(Dispatchers.Default) {
                val fill = withOverride(entry.fill)
                val photoMaps = photoBitmaps(fill)
                try {
                    renderer.prepare(template, fill, policy(), photoMaps)?.use { prepared ->
                        prepared.render(prepared.posterProgress, px.coerceIn(MIN_THUMB, MAX_THUMB))
                    }
                } finally {
                    photoMaps.values.forEach(Bitmap::recycle)
                }
            }
        }
    }

    /** A small animated WebP of [id], for "animate in the row". Cached on disk. */
    suspend fun animatedThumb(id: String, px: Int): ByteArray? {
        val entry = entry(id) ?: return null
        val template = store.template(entry.templateKey) ?: return null
        val size = px.coerceIn(MIN_THUMB, ANIMATED_THUMB)
        val cached = File(thumbDir(), "$id-$size.webp")
        if (cached.isFile) return runCatching { cached.readBytes() }.getOrNull()
        return renders.withPermit {
            withContext(Dispatchers.Default) {
                val fill = withOverride(entry.fill)
                renderer.prepare(template, fill, policy())?.use { prepared ->
                    if (!prepared.animated) return@use null
                    val sink = DynEncoder.animatedEncoder.create(size, THUMB_QUALITY)
                    var ok = true
                    prepared.forEachFrame(size, THUMB_FPS, THUMB_FRAMES) { frame, ms -> if (ok && !sink.add(frame, ms)) ok = false }
                    sink.finish()?.takeIf { ok }
                }?.also { bytes -> runCatching { cached.writeBytes(bytes) } }
            }
        }
    }

    /**
     * [item] rendered for sending: a sticker (animated when it moves, the
     * setting allows and [still] does not say otherwise), or for a meme with a
     * photo, a picture. Blocking; call on an IO dispatcher. [onProgress]
     * hears each frame of an animated one as it is drawn.
     */
    fun render(
        item: GifItem,
        still: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): DynEncoder.Output? {
        val entry = entryOf(item) ?: return null
        val template = store.template(entry.templateKey) ?: return null
        val fill = withOverride(entry.fill)
        val photoMaps = photoBitmaps(fill)
        try {
            val prepared = renderer.prepare(template, fill, policy(), photoMaps) ?: return null
            return prepared.use {
                sweepSent()
                val target = File(sendDir(), "${template.meta.id.take(NAME_CHARS)}-${item.id.takeLast(ID_CHARS)}")
                val out = if (template.kind == DynKind.MEME && template.meta.photos.isNotEmpty()) {
                    DynEncoder.image(it, target)
                } else {
                    DynEncoder.sticker(it, target, animated = host.settings.sendAnimated && !still, onProgress = onProgress)
                }
                if (out != null) store.noteSent(template.key)
                out
            }
        } finally {
            photoMaps.values.forEach(Bitmap::recycle)
        }
    }

    private fun thumbDir(): File = File(context.cacheDir, "$CACHE_DIR/thumbs").apply { mkdirs() }

    /** Under the FileProvider's `dynstickers/` cache root, which the field reads the sent file from. */
    private fun sendDir(): File = File(context.cacheDir, "$CACHE_DIR/send").apply { mkdirs() }

    private fun sweepSent() {
        val files = sendDir().listFiles().orEmpty().sortedByDescending { it.lastModified() }
        files.drop(KEEP_SENT).forEach { it.delete() }
        val thumbs = thumbDir().listFiles().orEmpty()
        if (thumbs.size > KEEP_THUMBS) thumbs.sortedBy { it.lastModified() }.take(thumbs.size - KEEP_THUMBS).forEach { it.delete() }
    }

    // ---- the panel -----------------------------------------------------

    /** The filter chips' packs, kept current with the store. */
    private fun publishPacks() {
        val disabled = store.state().disabledPacks.toSet()
        val packs = store.packs().filter { it.id !in disabled && it.templates.isNotEmpty() }.map { DynPackChip(it.id, it.name) }
        val hasMemes = store.templates().any { it.kind == DynKind.MEME }
        val favourites = store.state().favourites.isNotEmpty()
        _ui.update { it.copy(packs = packs, hasMemes = hasMemes, hasFavourites = favourites) }
    }

    fun isFavourite(item: GifItem): Boolean = entryOf(item)?.templateKey?.let { it in store.state().favourites } == true

    fun onAction(action: DynAction) {
        when (action) {
            is DynAction.Filter -> {
                host.haptic()
                if (_ui.value.filter == action.id) return
                _ui.update { it.copy(filter = action.id) }
                host.refreshGrid()
            }
            is DynAction.Favourite -> {
                host.haptic()
                val entry = entryOf(action.item) ?: return
                val on = entry.templateKey !in store.state().favourites
                store.setFavourite(entry.templateKey, on)
                _ui.update { ui -> ui.copy(sheet = ui.sheet?.copy(favourite = on)) }
            }
            is DynAction.Customize -> openSheet(action.item)
            DynAction.CloseSheet -> closeSheet()
            is DynAction.Send -> sendFromSheet(action.still)
            DynAction.PickPhoto -> host.pickPhoto()
            is DynAction.PhotoPicked -> photoPicked(action.uri)
            is DynAction.Font -> updateSheet { it.copy(fontOverride = action.family.takeIf(String::isNotBlank)) }
            is DynAction.TextColor -> updateSheet { it.copy(textColor = action.argb) }
            is DynAction.SaveToStickers -> saveToStickers(action.item)
            is DynAction.Edit -> {
                val entry = entryOf(action.item) ?: return
                _ui.update { it.copy(sheet = null) }
                host.openRoute(DynRoutes.builder(entry.templateKey))
            }
            DynAction.OpenSettings -> host.openRoute(DynRoutes.SETTINGS)
            DynAction.Create -> {
                _ui.update { it.copy(sheet = null) }
                host.openRoute(DynRoutes.NEW)
            }
        }
    }

    private fun openSheet(item: GifItem) {
        val entry = entryOf(item) ?: return
        val template = store.template(entry.templateKey) ?: return
        host.haptic()
        photos.clear()
        val slots = template.slots.map { slot ->
            DynSheetSlot(
                id = slot.id,
                label = slot.label,
                role = slot.role,
                text = entry.fill.texts[slot.id].orEmpty().ifBlank { slot.sample },
            )
        }
        val sheet = DynSheet(
            item = item,
            templateKey = template.key,
            name = template.name,
            slots = slots,
            photos = template.meta.photos.map { DynSheetPhoto(it.id, it.label, picked = false) },
            moving = !template.meta.stillOnly,
            favourite = template.key in store.state().favourites,
            fontOverride = entry.fill.fontOverride,
            textColor = entry.fill.textColor,
            preview = item,
        )
        _ui.update { it.copy(sheet = sheet) }
    }

    private fun closeSheet() {
        sheetJob?.cancel()
        photos.clear()
        _ui.update { it.copy(sheet = null) }
    }

    private fun photoPicked(uri: Uri) {
        val sheet = _ui.value.sheet ?: return
        val slot = sheet.photos.firstOrNull { !it.picked } ?: sheet.photos.firstOrNull() ?: return
        photos[slot.id] = uri
        updateSheet { s -> s.copy(photos = s.photos.map { if (it.id == slot.id) it.copy(picked = true) else it }) }
    }

    /** Applies [change] to the sheet and points its preview at the fill it now describes. */
    private fun updateSheet(change: (DynSheet) -> DynSheet) {
        val sheet = _ui.value.sheet ?: return
        val next = change(sheet)
        val fill = fillOf(next)
        val id = register(next.templateKey, fill)
        val preview = next.item.copy(id = ITEM_PREFIX + id, previewUrl = DynThumbs.uri(id, false), fullUrl = DynThumbs.uri(id, false))
        _ui.update { it.copy(sheet = next.copy(preview = preview)) }
    }

    private fun fillOf(sheet: DynSheet): DynFill = DynFill(
        texts = sheet.slots.associate { it.id to it.text },
        photos = photos.mapValues { it.value.toString() },
        fontOverride = sheet.fontOverride,
        textColor = sheet.textColor,
    )

    private fun sendFromSheet(still: Boolean) {
        val sheet = _ui.value.sheet ?: return
        if (sheet.photos.any { !it.picked }) {
            host.pickPhoto()
            return
        }
        val item = sheet.preview
        _ui.update { it.copy(sheet = it.sheet?.copy(busy = true, progress = null)) }
        sheetJob?.cancel()
        sheetJob = scope.launch {
            host.startRender(item.id)
            val output = withContext(Dispatchers.IO) {
                runCatching {
                    render(item, still) { done, total ->
                        host.renderProgress(done, total)
                        val fraction = done.toFloat() / total.coerceAtLeast(1)
                        _ui.update { it.copy(sheet = it.sheet?.copy(progress = fraction)) }
                    }
                }.getOrNull()
            }
            host.endRender()
            if (output == null) {
                _ui.update { it.copy(sheet = it.sheet?.copy(busy = false, progress = null)) }
                host.toast(com.wasimaster.wmkeyboard.ime.R.string.ime_dynsticker_render_failed)
                return@launch
            }
            closeSheet()
            host.send(output)
        }
    }

    private fun saveToStickers(item: GifItem) {
        val entry = entryOf(item) ?: return
        host.haptic()
        _ui.update { it.copy(sheet = null) }
        scope.launch {
            val output = withContext(Dispatchers.IO) { runCatching { render(item) }.getOrNull() }
            if (output == null) {
                host.toast(com.wasimaster.wmkeyboard.ime.R.string.ime_dynsticker_render_failed)
                return@launch
            }
            val words = entry.fill.texts.values.joinToString(" ").let(DynText::display)
            host.addToStickers(output.file, listOfNotNull(words.takeIf { it.isNotBlank() }))
        }
    }

    /** Copies [item]'s render to the clipboard. */
    fun copy(item: GifItem) {
        scope.launch {
            host.startRender(item.id)
            val output = withContext(Dispatchers.IO) { runCatching { render(item) }.getOrNull() }
            host.endRender()
            if (output == null) {
                host.toast(com.wasimaster.wmkeyboard.ime.R.string.ime_dynsticker_render_failed)
            } else {
                host.copy(output.file)
            }
        }
    }

    /** Encodes [bitmap] as PNG bytes, for a fetch result Coil decodes again. */
    internal fun png(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }.toByteArray()

    companion object {
        /** The stickers panel's Text chip: its key in the source row. */
        const val CHIP_KEY = "dynamic"

        const val ITEM_PREFIX = "dyn_"
        const val FILTER_STICKERS = "\u0000stickers"
        const val FILTER_MEMES = "\u0000memes"
        const val FILTER_FAVOURITES = "\u0000favourites"
        const val FILTER_PACK = "pack:"

        /** Where the sent files and the cached animated thumbnails live: a FileProvider root. */
        const val CACHE_DIR = "dynstickers"

        /** How far back the tray reads for the message. */
        const val MESSAGE_LOOKBEHIND = 400

        private const val REGISTRY = 800
        private const val LOAD_FACTOR = 0.75f
        private const val MAX_PARALLEL_RENDERS = 2
        private const val PANEL_MAX_WORDS = 40
        private const val PANEL_LIMIT = 200
        private const val MIN_THUMB = 96
        private const val MAX_THUMB = 512
        private const val ANIMATED_THUMB = 256
        private const val THUMB_QUALITY = 60
        private const val THUMB_FPS = 12f
        private const val THUMB_FRAMES = 48
        private const val PHOTO_EDGE = 1024
        private const val KEEP_SENT = 12
        private const val KEEP_THUMBS = 120
        private const val NAME_CHARS = 24
        private const val ID_CHARS = 10
        private const val PNG_QUALITY = 100
        private const val MIN_CONCEPT_WORD = 3
        private const val CONCEPT_EMOJI = 2
    }
}

/** Settings routes the keyboard opens for dynamic stickers; the app's nav graph declares them. */
object DynRoutes {
    const val SETTINGS = "dynamic_stickers"

    /** What the builder's argument says for a template not made yet. */
    const val NEW_TEMPLATE = "new"

    /** The builder on a new template of the user's own. */
    const val NEW = "dynamic_sticker_builder/$NEW_TEMPLATE"

    fun pack(packId: String): String = "dynamic_sticker_pack/${Uri.encode(packId)}"

    /**
     * The builder on [templateKey] (`pack/template`, one path segment once
     * encoded); one from a pack the user cannot change opens as a copy.
     */
    fun builder(templateKey: String): String = "dynamic_sticker_builder/${Uri.encode(templateKey)}"
}

/** One pack's chip in the Text grid's filter row. */
data class DynPackChip(val id: String, val name: String)

/** A text slot in the fill sheet. */
data class DynSheetSlot(val id: String, val label: String, val role: SlotRole, val text: String)

/** A photo slot in the fill sheet. */
data class DynSheetPhoto(val id: String, val label: String, val picked: Boolean)

/** The fill sheet over the Text grid: one template, its texts, its photos and its look. */
data class DynSheet(
    /** The cell it was opened from. */
    val item: GifItem,
    val templateKey: String,
    val name: String,
    val slots: List<DynSheetSlot>,
    val photos: List<DynSheetPhoto>,
    /** It moves, so "Send still" is worth offering. */
    val moving: Boolean,
    val favourite: Boolean,
    val fontOverride: String?,
    val textColor: Long?,
    /** The cell as it is now filled: what the preview draws and Send sends. */
    val preview: GifItem,
    val busy: Boolean = false,
    /** How far an animated send has drawn, 0..1; null before the first frame or for a still. */
    val progress: Float? = null,
)

/** The Text grid's own state beside the panel's: its filter, its packs, and the sheet. */
data class DynPanelUi(
    /** Null for all, else one of the controller's `FILTER_` values. */
    val filter: String? = null,
    val packs: List<DynPackChip> = emptyList(),
    val hasMemes: Boolean = false,
    val hasFavourites: Boolean = false,
    /** The text the grid was last filled with. */
    val text: String = "",
    val sheet: DynSheet? = null,
)

sealed interface DynAction {
    data class Filter(val id: String?) : DynAction
    data class Favourite(val item: GifItem) : DynAction
    data class Customize(val item: GifItem) : DynAction
    data object CloseSheet : DynAction
    data class Send(val still: Boolean) : DynAction
    data object PickPhoto : DynAction
    data class PhotoPicked(val uri: Uri) : DynAction
    data class Font(val family: String) : DynAction
    data class TextColor(val argb: Long?) : DynAction
    data class SaveToStickers(val item: GifItem) : DynAction
    data class Edit(val item: GifItem) : DynAction
    data object OpenSettings : DynAction

    /** The builder, on a new template. */
    data object Create : DynAction
}

/** The Text grid's state and actions, riding the tool callbacks like the clock's. */
data class DynStickerCallbacks(
    val state: StateFlow<DynPanelUi> = MutableStateFlow(DynPanelUi()),
    val onAction: (DynAction) -> Unit = {},
    /** Whether a Text cell's template is a favourite, for its long-press menu. */
    val isFavourite: (GifItem) -> Boolean = { false },
)

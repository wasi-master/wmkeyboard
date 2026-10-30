package com.wasimaster.wmkeyboard.ime.emojimix

import android.content.Context
import com.wasimaster.wmkeyboard.core.emoji.EmojiCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiEntry
import com.wasimaster.wmkeyboard.core.emoji.mix.EmojiMixer
import com.wasimaster.wmkeyboard.core.emoji.mix.MixSeq
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.media.MediaMime
import com.wasimaster.wmkeyboard.core.settings.EmojiMixOrder
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSettings
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSource
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.tools.GifSource
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.StickerOffer
import com.wasimaster.wmkeyboard.ime.StickerOfferItem
import com.wasimaster.wmkeyboard.ime.ui.RECENT_TAB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Emoji mixes on the keyboard: the offers raised by emoji in the text or
 * picked in the emoji panel, the Mixes panel, and sending a mix. The sources
 * are the user's; this only reads what [EmojiMixStore] holds and fetches the
 * images their addresses name.
 */
class EmojiMixController(
    context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    /** What the controller needs of the keyboard. */
    interface Host {
        val state: KeyboardUiState
        fun publish(change: (KeyboardUiState) -> KeyboardUiState)
        fun haptic()

        /** Whether previews may be fetched on this connection, data saving considered. */
        fun previewsAllowed(): Boolean

        /** Sends [file] (a PNG) into the field, as a sticker where [mode] asks and the app takes one. */
        fun send(file: File, mode: MediaSendMode)

        /** The cell [id] spins while its image comes down, and stops. */
        fun startFetch(id: String)
        fun endFetch()
        fun toast(message: Int)
        fun setFavourite(pairKey: String, starred: Boolean)
        fun setShuffleCategories(slot: Int, categories: Set<String>)
        fun addToStickers(file: File, keywords: List<String>)
        fun saveToGallery(file: File)
        fun openPanel()

        /** The emoji whose names and keywords match [query], best first. */
        fun searchEmoji(query: String): List<String>

        /** Puts the search box away and empties it. */
        fun endSearch()
    }

    val store = EmojiMixStore(context)

    @Volatile
    private var mixer: EmojiMixer = EmojiMixer.EMPTY
    private var sources: List<EmojiMixSource> = emptyList()
    private var loadedKey: Any? = null
    private var verifyJob: Job? = null

    /** The catalog [places] was built from, so a new one rebuilds it. */
    private var placesFor: List<EmojiEntry>? = null
    private var placesCache: Map<String, CatalogPlace> = emptyMap()

    /** Where an emoji sits in the emoji catalog: its place in the panel's order, and its category. */
    private class CatalogPlace(val order: Int, val category: String)

    /** Whether a mix can be offered at all right now. */
    val usable: Boolean get() = mixer.usable

    /**
     * Picks up a change to the sources or their files. Blocking (an index is
     * read from disk), so the keyboard calls it off the main thread.
     */
    fun reload(settings: EmojiMixSettings) {
        val key = settings.sources to settings.indexVersion
        if (key == loadedKey) return
        loadedKey = key
        sources = settings.sources
        mixer = runCatching { store.load(settings.sources) }.getOrDefault(EmojiMixer.EMPTY)
        host.publish { it.copy(emojiMix = it.emojiMix.copy(usable = mixer.usable, canList = mixer.canList)) }
        refreshFavourites(settings)
    }

    // ---- offers ----

    /**
     * Mixes for the emoji that end [text]: the last two together first, when
     * there are two and they mix, then the last one's mixes with the others.
     * Null when the text does not end in an emoji with a mix.
     */
    fun offerFor(text: String, inEmojiPanel: Boolean): StickerOffer? {
        val m = mixer
        if (!m.usable) return null
        val tail = MixSeq.trailingEmoji(text, 2)
        val last = tail.lastOrNull() ?: return null
        if (!m.supports(last)) return null
        val pair = tail.takeIf { it.size == 2 }?.let { m.mix(it[0], it[1]) }
        val previewsAllowed = host.previewsAllowed()
        val partners = if (m.canList) ordered(m.partners(last), last) else emptyList()
        val all = (listOfNotNull(pair) + partners)
            .distinctBy { it.id }
            .filter { it.verified }
            .filter { previewsAllowed || it.url.startsWith("file:") }
            .take(OFFER_LIMIT)
        if (all.isEmpty()) return null
        // With a pair the trigger is both emoji, so sending takes both back out.
        val trigger = if (pair != null && pair.verified) tail.joinToString("") else last
        return StickerOffer(
            trigger = trigger,
            stickers = all.map { StickerOfferItem(it.asGifItem(), trigger.length) },
            inEmojiPanel = inEmojiPanel,
            mix = true,
        )
    }

    /** The emoji used lately first, when the settings ask for it; the source's order otherwise. */
    private fun ordered(mixes: List<EmojiMixer.Mix>, emoji: String): List<EmojiMixer.Mix> {
        val settings = host.state.settings.emoji.mix
        if (settings.order != EmojiMixOrder.YOURS_FIRST) return mixes
        val state = host.state
        val mine = (state.emojiRecents + state.emojiFrequents).map(MixSeq::canonicalOf).distinct()
        if (mine.isEmpty()) return mixes
        val rank = mine.withIndex().associate { it.value to it.index }
        val self = MixSeq.canonicalOf(emoji)
        return mixes.sortedBy { mix ->
            val other = MixSeq.canonicalOf(mix.left).takeIf { it != self } ?: MixSeq.canonicalOf(mix.right)
            rank[other] ?: Int.MAX_VALUE
        }
    }

    /** A mix offered on the strip or the panel's row was picked: fetch and send it. */
    fun sendOffered(item: GifItem) {
        val (left, right) = pairOf(item) ?: return
        val mix = mixer.mix(left, right) ?: return
        send(mix)
    }

    // ---- the panel ----

    /** The Mixes panel opened: start from [left], or from the emoji last used. */
    fun onOpen(left: String? = null) {
        val m = mixer
        val state = host.state
        val emoji = pickable(m, state)
        val byTab = pickerTabs(emoji, state)
        val tab = state.emojiMix.tab.takeIf { it in byTab } ?: byTab.keys.firstOrNull().orEmpty()
        val start = left?.takeIf { m.supports(it) }
            ?: state.emojiMix.left.takeIf { it.isNotEmpty() && m.supports(it) }
            ?: state.emojiRecents.firstOrNull { m.supports(it) }
            ?: emoji.firstOrNull().orEmpty()
        host.publish {
            it.copy(
                emojiMix = it.emojiMix.copy(
                    usable = m.usable,
                    canList = m.canList,
                    emoji = emoji,
                    tabs = byTab.keys.toList(),
                    tab = tab,
                    byTab = byTab,
                    previewsHeld = !host.previewsAllowed(),
                ),
            )
        }
        setPair(start, if (left != null) "" else state.emojiMix.right, slot = if (start.isEmpty()) 0 else 1)
        refreshFavourites(state.settings.emoji.mix)
    }

    /**
     * The emoji for the picker: what the indexes mix, or with only a mixer,
     * the catalogue's. In the emoji panel's order, not the source's: a source
     * may lead with an emoji that pairs with everything (the magic wand, whose
     * mix redraws the other emoji in the old blob style), and at the front of
     * the picker it looked like a button.
     */
    private fun pickable(m: EmojiMixer, state: KeyboardUiState): List<String> {
        val places = places(state)
        val raw = if (m.canList) m.supported() else state.emojiCatalog.filter { it.parent.isNullOrEmpty() }.map { it.emoji }
        // One the catalog lacks goes last.
        return raw.distinct().sortedBy { places[EmojiCatalog.matchKey(it)]?.order ?: Int.MAX_VALUE }
    }

    /** [emoji] by the picker's tabs: the recent ones first, then the categories in the emoji panel's order. */
    private fun pickerTabs(emoji: List<String>, state: KeyboardUiState): Map<String, List<String>> {
        val byKey = emoji.associateBy(EmojiCatalog::matchKey)
        val tabs = LinkedHashMap<String, List<String>>()
        val recent = state.emojiRecents.mapNotNull { byKey[EmojiCatalog.matchKey(it)] }.distinct()
        if (recent.isNotEmpty()) tabs[RECENT_TAB] = recent
        val grouped = emoji.groupBy { categoryOf(it, state) }
        for (category in IconSlots.EMOJI_CATEGORIES + grouped.keys) {
            grouped[category]?.let { tabs.putIfAbsent(category, it) }
        }
        return tabs
    }

    private fun categoryOf(emoji: String, state: KeyboardUiState): String =
        places(state)[EmojiCatalog.matchKey(emoji)]?.category ?: OTHER_CATEGORY

    private fun places(state: KeyboardUiState): Map<String, CatalogPlace> {
        val catalog = state.emojiCatalog
        if (catalog !== placesFor) {
            placesFor = catalog
            placesCache = HashMap<String, CatalogPlace>(catalog.size * 2).also { map ->
                catalog.forEachIndexed { i, entry ->
                    map.putIfAbsent(EmojiCatalog.matchKey(entry.emoji), CatalogPlace(i, entry.category))
                }
            }
        }
        return placesCache
    }

    /** A tab of the picker chosen. */
    fun onTab(tab: String) {
        host.publish { it.copy(emojiMix = it.emojiMix.copy(tab = tab)) }
    }

    /** What the search box holds now: the picker shows the emoji it finds, those that mix. */
    fun onQuery(query: String) {
        val offered = host.state.emojiMix.emoji.toHashSet()
        val found = if (query.isBlank()) emptyList() else host.searchEmoji(query).filter { it in offered }
        host.publish { it.copy(emojiMix = it.emojiMix.copy(searched = found)) }
    }

    /** The first of what the box found, for Enter. */
    fun onSearchEnter() {
        host.state.emojiMix.searched.firstOrNull()?.let(::onPickEmoji)
    }

    /** An emoji picked in the panel's picker: it fills the slot the panel is on. */
    fun onPickEmoji(emoji: String) {
        host.haptic()
        // Found what it was looking for: back to the mixes of it.
        if (host.state.mediaSearchActive) host.endSearch()
        val ui = host.state.emojiMix
        if (ui.slot == 0) setPair(emoji, ui.right, slot = 1) else setPair(ui.left, emoji, slot = 1)
    }

    /** A slot's emoji taken out; the next pick fills that slot. */
    fun onClearSlot(slot: Int) {
        host.haptic()
        val ui = host.state.emojiMix
        if (slot == 0) setPair("", ui.right, slot = 0) else setPair(ui.left, "", slot = 1)
    }

    /** One of the two slots tapped: the next pick goes there. */
    fun onSlot(slot: Int) {
        host.haptic()
        host.publish { it.copy(emojiMix = it.emojiMix.copy(slot = slot.coerceIn(0, 1))) }
    }

    fun onSwap() {
        host.haptic()
        val ui = host.state.emojiMix
        if (ui.right.isEmpty()) return
        setPair(ui.right, ui.left, ui.slot)
    }

    /**
     * A random emoji and one of its mixes, the way a shuffle button in such a
     * panel works, each drawn from the categories the user ticked for its slot.
     */
    fun onShuffle() {
        host.haptic()
        val m = mixer
        val state = host.state
        val all = state.emojiMix.emoji
        if (all.isEmpty()) return
        val settings = state.settings.emoji.mix
        fun allowed(emoji: String, categories: Set<String>) = categories.isEmpty() || categoryOf(emoji, state) in categories
        val firsts = all.filter { allowed(it, settings.shuffleFirst) }.ifEmpty { all }
        val seconds = all.filter { allowed(it, settings.shuffleSecond) }.ifEmpty { all }
        // A few draws: a first emoji may mix with nothing in the second's categories.
        repeat(SHUFFLE_TRIES) {
            val left = firsts.random()
            val partner = if (m.canList) {
                val self = MixSeq.canonicalOf(left)
                m.partners(left)
                    .map { if (MixSeq.canonicalOf(it.left) == self) it.right else it.left }
                    .filter { allowed(it, settings.shuffleSecond) }
                    .randomOrNull()
            } else {
                seconds.random()
            }
            if (partner != null) {
                setPair(left, partner, slot = 1)
                return
            }
        }
        setPair(firsts.random(), seconds.random(), slot = 1)
    }

    /**
     * The categories the shuffle draws emoji [slot] from. Every category, or
     * none, is stored as no choice at all, so a category a later source adds
     * is in the draw.
     */
    fun onShuffleCategories(slot: Int, categories: Set<String>) {
        host.haptic()
        val available = host.state.emojiMix.tabs.filter { it != RECENT_TAB }.toSet()
        val chosen = categories intersect available
        host.setShuffleCategories(slot, if (chosen.containsAll(available)) emptySet() else chosen)
    }

    /** A mix in the panel tapped: send it. */
    fun onSendCell(cell: EmojiMixCell) {
        val mix = mixer.mix(cell.left, cell.right) ?: EmojiMixer.Mix(cell.left, cell.right, cell.url, cell.verified, "")
        send(mix)
    }

    fun onFavourite(cell: EmojiMixCell, starred: Boolean) {
        host.haptic()
        host.setFavourite(cell.pairKey, starred)
    }

    fun onAddToStickers(cell: EmojiMixCell) = withFile(cell) { file ->
        host.addToStickers(file, listOf(cell.left, cell.right, cell.left + cell.right))
    }

    fun onSaveToGallery(cell: EmojiMixCell) = withFile(cell) { file -> host.saveToGallery(file) }

    /** The favourites as cells, from the stored pairs; a pair no source has any more is skipped. */
    fun refreshFavourites(settings: EmojiMixSettings) {
        val m = mixer
        val cells = settings.favourites.mapNotNull { key ->
            val (l, r) = key.split('_').takeIf { it.size == 2 } ?: return@mapNotNull null
            val left = MixSeq.emoji(l) ?: return@mapNotNull null
            val right = MixSeq.emoji(r) ?: return@mapNotNull null
            m.mix(left, right)?.asCell()
        }
        host.publish { it.copy(emojiMix = it.emojiMix.copy(favourites = cells)) }
    }

    private fun setPair(left: String, right: String, slot: Int) {
        val m = mixer
        val result = if (left.isNotEmpty() && right.isNotEmpty()) m.mix(left, right) else null
        // The grid lists the first emoji's mixes, or the second's while the first slot is empty.
        val anchor = left.ifEmpty { right }
        val mixes = if (anchor.isNotEmpty() && m.canList) ordered(m.partners(anchor), anchor) else emptyList()
        host.publish {
            it.copy(
                emojiMix = it.emojiMix.copy(
                    left = left,
                    right = right,
                    slot = slot,
                    result = result?.takeIf { r -> r.verified }?.asCell(),
                    noMix = left.isNotEmpty() && right.isNotEmpty() && result == null,
                    mixes = mixes.map { mix -> mix.asCell() },
                    loading = result != null && !result.verified,
                ),
            )
        }
        // A mixer no index backs: the pair is only real if its image is not the placeholder.
        verifyJob?.cancel()
        val unverified = listOfNotNull(result?.takeIf { !it.verified }) +
            if (!m.canList && anchor.isNotEmpty()) mixerGuesses(anchor) else emptyList()
        if (unverified.isEmpty() || !host.previewsAllowed()) {
            if (result != null && !result.verified) host.publish { it.copy(emojiMix = it.emojiMix.copy(loading = false)) }
            return
        }
        verifyJob = scope.launch {
            val checked = withContext(Dispatchers.IO) {
                unverified.map { mix -> async { mix to store.fetch(mix, sourceOf(mix)) } }.awaitAll()
            }
            val real = checked.mapNotNull { (mix, file) -> file?.let { mix.asCell().copy(url = it.toURI().toString(), verified = true) } }
            host.publish { state ->
                val ui = state.emojiMix
                if (ui.left != left || ui.right != right) return@publish state
                val pairCell = result?.let { r -> real.firstOrNull { it.id == r.id } }
                state.copy(
                    emojiMix = ui.copy(
                        result = pairCell ?: ui.result,
                        noMix = result != null && pairCell == null,
                        mixes = if (m.canList) ui.mixes else real.filter { it.id != result?.id },
                        loading = false,
                    ),
                )
            }
        }
    }

    /** With a mixer and no index: [left] with the emoji used lately, each to be checked. */
    private fun mixerGuesses(left: String): List<EmojiMixer.Mix> {
        val state = host.state
        return (state.emojiRecents + state.emojiFrequents)
            .distinct()
            .filter { MixSeq.canonicalOf(it) != MixSeq.canonicalOf(left) }
            .take(MIXER_GUESSES)
            .mapNotNull { mixer.mix(left, it) }
    }

    // ---- sending ----

    private fun send(mix: EmojiMixer.Mix) {
        val state = host.state
        if (state.mediaDownloadingId != null) return
        if (!state.acceptsRichMedia) {
            host.toast(com.wasimaster.wmkeyboard.ime.R.string.ime_emoji_mix_field_refuses_toast)
            return
        }
        host.haptic()
        host.startFetch(mix.id)
        scope.launch {
            val file = withContext(Dispatchers.IO) { store.fetch(mix, sourceOf(mix)) }
            host.endFetch()
            if (file == null) {
                host.toast(com.wasimaster.wmkeyboard.ime.R.string.ime_emoji_mix_fetch_failed_toast)
                return@launch
            }
            host.send(file, host.state.settings.emoji.mix.sendMode)
        }
    }

    private fun withFile(cell: EmojiMixCell, then: (File) -> Unit) {
        val mix = mixer.mix(cell.left, cell.right) ?: return
        if (host.state.mediaDownloadingId != null) return
        host.haptic()
        host.startFetch(mix.id)
        scope.launch {
            val file = withContext(Dispatchers.IO) { store.fetch(mix, sourceOf(mix)) }
            host.endFetch()
            if (file == null) {
                host.toast(com.wasimaster.wmkeyboard.ime.R.string.ime_emoji_mix_fetch_failed_toast)
            } else {
                then(file)
            }
        }
    }

    private fun sourceOf(mix: EmojiMixer.Mix): EmojiMixSource? = sources.firstOrNull { it.id == mix.sourceId }

    private fun EmojiMixer.Mix.asCell() = EmojiMixCell(id, left, right, url, verified)

    private fun EmojiMixer.Mix.asGifItem() = GifItem(
        id = id,
        previewUrl = url,
        fullUrl = url,
        mime = MediaMime.PNG,
        aspectRatio = 1f,
        source = GifSource.LOCAL,
        title = left + right,
    )

    /** The two emoji an offered item is the mix of, from its title. */
    private fun pairOf(item: GifItem): Pair<String, String>? {
        val tail = MixSeq.trailingEmoji(item.title, 2)
        return if (tail.size == 2) tail[0] to tail[1] else null
    }

    private companion object {
        const val OFFER_LIMIT = 40
        const val SHUFFLE_TRIES = 8

        /** The picker's tab for emoji the catalog has no category for. */
        const val OTHER_CATEGORY = "other"
        const val MIXER_GUESSES = 12
    }
}

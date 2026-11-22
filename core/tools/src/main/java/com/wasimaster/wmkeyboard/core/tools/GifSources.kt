package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.tools.R

/**
 * Where a GIF/sticker result came from. [LOCAL] is the user's own sticker
 * packs on device — sticker-only, always available, and never mixed into a
 * provider grid. [OFFLINE] is its GIF-panel twin: packs of GIFs imported from
 * a file ([com.wasimaster.wmkeyboard.core.tools.offlinegif.OfflineGifPacks]),
 * offered once one is installed and needing no network at all.
 *
 * [COMMONS] is the F-Droid build's only remote source and appears in no other
 * channel: KLIPY and GIPHY both want an API key that build cannot carry. Its
 * corpus is educational rather than reactive, which the panel says out loud
 * rather than leaving the user to conclude the search is broken.
 *
 * [GIFCITIES] is the Internet Archive's search over old GeoCities GIFs. It
 * needs no key, so any build can offer it, but only once the user turns it
 * on: the corpus is a curiosity rather than a reaction-GIF catalogue, and it
 * carries no content rating.
 */
enum class GifSource {
    KLIPY, GIPHY, LOCAL, COMMONS, OFFLINE, GIFCITIES,

    /** A plugin's provider ([PluginProviders]); which one is the target's key. */
    PLUGIN;

    /** Files on the device rather than a provider: its own grid, never interleaved, never metered. */
    val onDevice: Boolean get() = this == LOCAL || this == OFFLINE

    /**
     * Always a grid of its own, never interleaved in mixed mode. True of the
     * packs on the device, and of GIPHY: its API terms (section 1) forbid
     * mixing its search results with another provider's without its written
     * approval.
     */
    val ownGrid: Boolean get() = onDevice || this == GIPHY
}

/**
 * One GIF or sticker result: a small preview for the panel grid and the
 * full-size file that actually gets committed to the editor. Produced by
 * [KlipyClient] and [GiphyClient].
 */
data class GifItem(
    val id: String,
    /** Compact preview shown in the picker grid (animated for KLIPY/GIPHY). */
    val previewUrl: String,
    /** Full-size file downloaded and inserted on tap. */
    val fullUrl: String,
    /** MIME of [fullUrl] — image/gif, image/webp or image/png. */
    val mime: String,
    /** width/height of the preview, for grid cell sizing. */
    val aspectRatio: Float,
    val source: GifSource,
    /** Provider's name for the item, or a local sticker's — may be blank. */
    val title: String = "",
    /**
     * Who made it, as the provider names them: a GIPHY username. Blank when
     * the provider gives none. GIPHY's API terms (section 5) ask for it to be
     * shown wherever it exists.
     */
    val creator: String = "",
)

/**
 * One provider row of the GIF or sticker list, ready to ask: which client
 * ([source]) and what it was set up with. [key] is the row's
 * `ProviderEntry.key`, which is what tells two Commons wikis apart and what
 * the panel's selected chip remembers.
 */
data class MediaTarget(
    val key: String,
    val source: GifSource,
    val apiKey: String = "",
    val url: String = "",
    /** The name the user gave this server, shown on its chip instead of the kind's. */
    val name: String = "",
    /** Rows of the same kind asked in turn when this one fails: a second key kept as a backup. */
    val backups: List<MediaTarget> = emptyList(),
) {
    constructor(source: GifSource) : this(source.name, source)

    /** This target, then its [backups]: the order one fetch walks. */
    val withBackups: List<MediaTarget> get() = if (backups.isEmpty()) listOf(this) else listOf(copy(backups = emptyList())) + backups

    val onDevice: Boolean get() = source.onDevice
}

/**
 * One chip in the source row, with the label it shows: [name] when the user
 * named the server, else [labelRes].
 */
data class SourceChip(
    @StringRes val labelRes: Int,
    val source: GifSource,
    val key: String = source.name,
    val name: String = "",
)

/** Helpers shared by the multi-provider GIF/sticker pipeline. */
object GifSources {

    /** The name a source goes by on screen. The UI resolves the id. */
    @StringRes
    fun displayNameRes(source: GifSource): Int = when (source) {
        GifSource.KLIPY -> R.string.core_tools_gif_source_klipy
        GifSource.GIPHY -> R.string.core_tools_gif_source_giphy
        GifSource.LOCAL -> R.string.core_tools_gif_source_local
        GifSource.COMMONS -> R.string.core_tools_gif_source_commons
        GifSource.OFFLINE -> R.string.core_tools_gif_source_offline
        GifSource.GIFCITIES -> R.string.core_tools_gif_source_gifcities
        GifSource.PLUGIN -> R.string.core_tools_provider_plugin
    }

    /** The client a provider type is asked through, or null for a type that is not a GIF or sticker source. */
    fun sourceOf(typeId: String): GifSource? = when (typeId) {
        ProviderTypes.KLIPY.id -> GifSource.KLIPY
        ProviderTypes.GIPHY.id -> GifSource.GIPHY
        ProviderTypes.COMMONS.id -> GifSource.COMMONS
        ProviderTypes.GIFCITIES.id -> GifSource.GIFCITIES
        ProviderTypes.OFFLINE_GIFS.id -> GifSource.OFFLINE
        ProviderTypes.STICKER_PACKS.id -> GifSource.LOCAL
        ProviderTypes.PLUGIN.id -> GifSource.PLUGIN
        else -> null
    }

    /**
     * A chip of its own, never part of the lead grid: the packs on the device
     * always, and GIPHY when the grid would mix (its terms). One provider at
     * a time never mixes, so there GIPHY can take its turn like the rest.
     */
    private fun ownChip(target: MediaTarget, mode: ChainMode): Boolean =
        target.onDevice || (mode == ChainMode.MIX && target.source.ownGrid)

    /**
     * Which providers one fetch should hit, given the chip the user is on.
     * In tabs mode that is the chip's provider. Otherwise it is the lead
     * group, mixed or asked in turn by [mode], unless the chip is one with a
     * grid of its own ([ownChip]).
     */
    fun targets(providers: List<MediaTarget>, selected: String, mode: ChainMode): List<MediaTarget> {
        if (providers.isEmpty()) return emptyList()
        val pick = providers.firstOrNull { it.key == selected }
        if (mode == ChainMode.TABS) return listOf(pick ?: providers.first())
        if (pick != null && ownChip(pick, mode)) return listOf(pick)
        val lead = providers.filterNot { ownChip(it, mode) }
        return lead.ifEmpty { listOf(providers.first()) }
    }

    /**
     * Chips for the source row, or an empty list when there is nothing to
     * switch between. Outside tabs mode the lead group collapses into one
     * "Online" chip (or the provider's own name, when it is alone), and the
     * providers with a grid of their own still get a chip each.
     */
    fun chips(providers: List<MediaTarget>, mode: ChainMode): List<SourceChip> {
        fun chip(target: MediaTarget) = SourceChip(displayNameRes(target.source), target.source, target.key, target.name)
        if (mode == ChainMode.TABS) {
            return if (providers.size > 1) providers.map(::chip) else emptyList()
        }
        val lead = providers.filterNot { ownChip(it, mode) }
        val leadChip = when (lead.size) {
            0 -> emptyList()
            1 -> listOf(chip(lead[0]))
            else -> listOf(SourceChip(R.string.core_tools_gif_source_online, lead[0].source, lead[0].key))
        }
        val chips = leadChip + providers.filter { ownChip(it, mode) }.map(::chip)
        return if (chips.size > 1) chips else emptyList()
    }

    /** Index of the active chip in [chips], falling back to the lead group's, then the first. */
    fun selectedChip(chips: List<SourceChip>, selected: String, providers: List<MediaTarget>, mode: ChainMode): Int {
        val exact = chips.indexOfFirst { it.key == selected }
        if (exact >= 0) return exact
        val own = providers.filter { ownChip(it, mode) }.mapTo(HashSet()) { it.key }
        return chips.indexOfFirst { it.key !in own }.coerceAtLeast(0)
    }

    /** [targets] for a plain list of sources, one provider each: tabs, or mixed. */
    fun targets(sources: List<GifSource>, selected: GifSource, tabs: Boolean): List<GifSource> =
        targets(sources.map(::MediaTarget), selected.name, if (tabs) ChainMode.TABS else ChainMode.MIX).map { it.source }

    /** [chips] for a plain list of sources: tabs, or mixed. */
    fun chips(sources: List<GifSource>, tabs: Boolean): List<SourceChip> =
        chips(sources.map(::MediaTarget), if (tabs) ChainMode.TABS else ChainMode.MIX)

    /** [selectedChip] for a plain list of sources in mixed mode. */
    fun selectedChip(chips: List<SourceChip>, selected: GifSource): Int =
        selectedChip(chips, selected.name, chips.map { MediaTarget(it.source) }, ChainMode.MIX)

    /**
     * A cell's shape in the picker grid. Clamped so one degenerate banner or
     * filmstrip cannot flatten its whole row (or stretch it off the panel);
     * the preview letterboxes inside the clamped cell instead of cropping.
     */
    fun cellRatio(item: GifItem): Float = item.aspectRatio.coerceIn(MIN_CELL_RATIO, MAX_CELL_RATIO)

    /**
     * Splits picker results into justified rows: every item in a row shares
     * one height, widths follow each item's own aspect ratio, and nothing is
     * cropped. Rows aim for [perRow] items and close early once they carry
     * [targetRowRatio] worth of width, so a pair of wide GIFs makes a row of
     * two rather than squeezing a cropped third in.
     *
     * A row never takes an item that would push it past [MAX_ROW_STRETCH]
     * times its target: heights are `width / ratio-sum`, and an unbounded sum
     * is a row of thumbnails too short to read.
     */
    fun rows(items: List<GifItem>, perRow: Int = DEFAULT_PER_ROW): List<List<GifItem>> {
        val most = perRow.coerceAtLeast(1)
        val target = targetRowRatio(most)
        val rows = ArrayList<List<GifItem>>()
        val row = ArrayList<GifItem>()
        var sum = 0f
        for (item in items) {
            val ratio = cellRatio(item)
            if (row.isNotEmpty() && (row.size == most || sum + ratio > target * MAX_ROW_STRETCH)) {
                rows += row.toList()
                row.clear()
                sum = 0f
            }
            row += item
            sum += ratio
            if (sum >= target) {
                rows += row.toList()
                row.clear()
                sum = 0f
            }
        }
        if (row.isNotEmpty()) rows += row.toList()
        return rows
    }

    /**
     * How much ratio-width a row of at most [perRow] items wants; also the
     * floor rows are padded to, so a short last row keeps its neighbours'
     * height. A little over one square per item, so [perRow] squares close a
     * row and fewer leave room.
     */
    fun targetRowRatio(perRow: Int): Float = perRow.coerceAtLeast(1) + 0.2f

    /** The ratio-width a row wants at the shipped three to a row. */
    val TARGET_ROW_RATIO: Float get() = targetRowRatio(DEFAULT_PER_ROW)

    /** Items to a row unless the setting says otherwise. */
    const val DEFAULT_PER_ROW = 3

    /** How far past its target a row may run before an item starts the next one: 4.6 at three to a row. */
    private const val MAX_ROW_STRETCH = 1.4375f
    private const val MIN_CELL_RATIO = 0.5f
    private const val MAX_CELL_RATIO = 2.6f

    /**
     * Round-robin merge for "mixed" mode: one item from each provider in
     * turn, so no source dominates the top of the grid.
     */
    fun interleave(lists: List<List<GifItem>>): List<GifItem> =
        com.wasimaster.wmkeyboard.core.providers.ProviderRunner.interleave(lists)
}

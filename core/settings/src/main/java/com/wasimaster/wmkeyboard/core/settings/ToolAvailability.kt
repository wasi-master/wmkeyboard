package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.providers.ProviderTool

/**
 * Whether the two search tools have somewhere to search: a row of the search
 * provider list that is on and set up (a SearXNG instance, a Tavily key, or a
 * Brave key, theirs or the one baked into this build).
 *
 * Any one will do, and none excludes the others. The F-Droid build ships no
 * Brave key, so in practice its answer comes from the instance; but a user of
 * that build who has their own key is not stopped from using it. The tools
 * module resolves the same pair for the request itself
 * (`ToolApiKeys.hasSearchProvider`); this lives down here because the toolbar,
 * the toolbox and the settings screens all have to ask the question long before
 * anything reaches a network client.
 */
fun hasSearchKey(settings: KeyboardSettings): Boolean =
    settings.resolveProviders(ProviderTool.SEARCH).isNotEmpty()

/**
 * Whether a tool can do its job right now — as opposed to [isSupportedTool],
 * which asks whether this build ships it at all.
 *
 * The difference is that this one can change while the app is running: paste a
 * key and the tool appears, clear it and it goes. Everything the keyboard draws
 * or dispatches filters on both, so a tool that cannot work is never on the
 * bar, in the toolbox, or behind a hardware shortcut.
 */
fun isUsableTool(tool: ToolbarTool, settings: KeyboardSettings): Boolean =
    toolBlocker(tool, settings) == null

/**
 * What stands between a tool and doing its job. The settings app draws the
 * reason on the tool's row and beside its switch, so the reason is typed
 * rather than implied: for a long time the search tools were the only
 * unusable ones, every screen spelled `!isUsableTool` as "needs an API key",
 * and the first tool gated on something else was offered a key field for a
 * problem a key could not fix. A new blocker added here fails to compile at
 * every screen until it has words of its own.
 */
enum class ToolBlocker {
    /** Web and image search: no search API key is configured. */
    NEEDS_SEARCH_KEY,

    /**
     * Web and image search on the F-Droid build: no SearXNG instance is named.
     *
     * Separate from [NEEDS_SEARCH_KEY] because the remedy is different and the
     * screens say so: that one wants a key pasted, this one wants the address
     * of a server. Offering a key field for a problem no key can fix is the
     * exact mistake this enum was introduced to make impossible.
     */
    NEEDS_SEARCH_INSTANCE,

    /** The Secondary layout tool: the user has not made a secondary layout yet. */
    NEEDS_SECONDARY_LAYOUT,

    /** The Voice tool: voice typing is switched off on the Voice typing screen (#578). */
    VOICE_OFF,

    /** The emoji mixes panel: no source is added and switched on under Emoji › Emoji mixes. */
    NEEDS_MIX_SOURCE,

    /** The Music ID tool: no recogniser on its list is on and set up with its key. */
    NEEDS_MUSIC_PROVIDER,
}

/** Why [tool] cannot run right now, or null when it can; see [ToolBlocker]. */
fun toolBlocker(tool: ToolbarTool, settings: KeyboardSettings): ToolBlocker? = when (tool) {
    ToolbarTool.WEB_SEARCH, ToolbarTool.IMAGE_SEARCH ->
        when {
            hasSearchKey(settings) -> null
            BuildConfig.ENABLE_FDROID -> ToolBlocker.NEEDS_SEARCH_INSTANCE
            else -> ToolBlocker.NEEDS_SEARCH_KEY
        }
    // Nothing to show until the user has built a secondary layout or pinned
    // an enabled layout on the tool (#1230); a button that does nothing is
    // worse than no button.
    ToolbarTool.CUSTOM_LAYOUT ->
        if (secondaryLayouts(settings.customLayouts).isNotEmpty() || settings.pinnedBoardId() != null) {
            null
        } else {
            ToolBlocker.NEEDS_SECONDARY_LAYOUT
        }
    ToolbarTool.VOICE -> if (settings.voiceBar.enabled) null else ToolBlocker.VOICE_OFF
    ToolbarTool.EMOJI_MIX -> if (settings.emoji.mix.active) null else ToolBlocker.NEEDS_MIX_SOURCE
    ToolbarTool.MUSIC_ID -> if (settings.resolveProviders(ProviderTool.MUSIC).isNotEmpty()) null else ToolBlocker.NEEDS_MUSIC_PROVIDER
    else -> null
}

/**
 * The tools the user has switched on that can actually run, for the callers
 * that take a whole set (the hardware shortcuts, the smart suggestions, the
 * tool picker) rather than filtering tool by tool.
 */
fun usableTools(settings: KeyboardSettings): Set<ToolbarTool> {
    usableToolsMemo?.takeIf { it.settings === settings }?.let { return it.tools }
    val tools: Set<ToolbarTool> = settings.enabledTools.filterTo(mutableSetOf()) { isUsableTool(it, settings) }
    usableToolsMemo = UsableToolsMemo(settings, tools)
    return tools
}

/**
 * The last answer of [usableTools], for the settings it was worked out from.
 *
 * The keyboard asks on every keystroke — the composition does, for the
 * selection bar's Ask AI button, and so does the smart-suggestion pass — and
 * the answer is a pure function of one [KeyboardSettings] instance, which only
 * changes when a preference is written. Keyed on identity: an equal copy is
 * not worth the comparison, and a stale hit is impossible.
 */
private class UsableToolsMemo(val settings: KeyboardSettings, val tools: Set<ToolbarTool>)

@Volatile
private var usableToolsMemo: UsableToolsMemo? = null

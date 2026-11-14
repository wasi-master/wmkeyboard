package com.wasimaster.wmkeyboard.app

import android.content.res.Resources

/*
 * The pages beside a step of the path strip (#1317): a long press on a step
 * lists the screens that share its parent, so moving from Typing › Gestures to
 * Typing › Suggestions is one press and a pick instead of back and down again.
 *
 * Read off the search index rather than a table of parents, for the reason the
 * strip itself gives: the index already names every destination with the path
 * above it, and a second table would drift. A step whose name the index does
 * not know (a language's page, a mode the user made) has no siblings, and its
 * long press does nothing.
 */

/**
 * The screens that share a parent with the last step of [path].
 *
 * [path] is the strip's titles, outermost first, ending with the step pressed:
 * `["Home", "Typing", "Gestures"]`. The home step is [homeTitle] on the strip
 * and [rootTitle] in the index ("Settings"), which is how the index files the
 * rows of the home list. A screen is a destination entry whose path is the
 * parent's; a tool's page is the tool entry under the Tools screen, whose own
 * name ends its path. In index order, which is the order the hub draws them
 * in, with one entry a route.
 */
internal fun crumbSiblings(
    path: List<String>,
    index: List<SettingsSearchEntry>,
    homeTitle: String,
    rootTitle: String,
): List<SettingsSearchEntry> {
    val below = if (path.firstOrNull() == homeTitle) path.drop(1) else path
    if (below.isEmpty()) return emptyList()
    val parent = below.dropLast(1).ifEmpty { listOf(rootTitle) }
    return index.filter { entry ->
        when (entry.weight) {
            EntryWeight.SECTION -> entry.screenPath == parent
            EntryWeight.TOOL ->
                entry.screenPath.dropLast(1) == parent && entry.screenPath.lastOrNull() == entry.title
            else -> false
        }
    }
        // Not a route with an argument the index cannot fill in; and one
        // entry a page, since a button the index files as a destination of
        // its own ("Import from a file") opens a page that is already listed.
        .filter { '{' !in it.route }
        .distinctBy { it.route }
}

/**
 * The index the strip reads siblings from, built once per language on the
 * first long press and kept for the process, like the search screen's.
 */
internal object CrumbSiblingIndex {
    @Volatile
    private var held: Pair<String, List<SettingsSearchEntry>>? = null

    /** The index in the language [res] is set to. Slow the first time: call it off the main thread. */
    fun of(res: Resources): List<SettingsSearchEntry> {
        val locales = res.configuration.locales.toLanguageTags()
        held?.takeIf { it.first == locales }?.let { return it.second }
        return settingsSearchIndex(res).also { held = locales to it }
    }
}

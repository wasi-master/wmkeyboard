package com.wasimaster.wmkeyboard.app

/**
 * Every settings screen a link may open, written exactly as the NavHost
 * declares it.
 *
 * The nav graph is the only place that knows what a route means, and it is a
 * list of `composable("…")` calls a hundred entries long inside a composable
 * that nothing outside the file can call. A link arriving from another app —
 * or from `adb`, or from a page on the documentation site — has to be checked
 * against that list *before* it reaches `navigate()`, because an unmatched
 * route is not a no-op there: `NavController.navigate` throws
 * `IllegalArgumentException`, and the throw lands in the activity that has
 * just been resumed. So this is the allowlist, and [SettingsDeepLinkTest]
 * reads both this file and `MainActivity.kt` and fails when they drift.
 *
 * Patterns are the NavHost's own, `{arg}` segments included. A link fills
 * those segments in: `language/{langId}` is addressed as
 * `wmkeyboard://settings/language/en_US`, and the segment travels into the
 * route untouched, still percent-encoded, because that is the form
 * `navigate()` decodes from.
 *
 * ### What is deliberately not addressable
 *
 * [excluded] holds the handful of destinations a link must not open, each with
 * the reason. They are listed rather than simply left out so that a screen
 * added later cannot be forgotten: the test fails on a destination that is in
 * neither table.
 */
internal object SettingsRoutes {

    /**
     * Destinations no link may open, and why.
     *
     * None of these are private — the fingerprint lock is what guards private
     * screens, and it guards them by route, so it covers a deep link exactly
     * as it covers a tap. These three simply have nowhere to land.
     */
    val excluded: Map<String, String> = mapOf(
        // The activity already refuses every pending navigation until
        // onboarding is done, so a link here could only ever restart a wizard
        // the user has finished. Replaying it is a row on About.
        "onboarding" to "the first-run wizard; replayed from About, never from a link",
        // Draws from StickerEditHandoff, an in-memory handover set one line
        // before the navigation that opens it. Reached cold it pops straight
        // back out.
        "sticker_editor" to "opens only on an in-memory handoff from the import flow",
        // Same shape: PhotoSelection.current is set by the browse screen as it
        // navigates, and the screen pops itself when it is null.
        "photo_detail" to "opens only on the photo the browse screen just handed over",
    )

    /**
     * Every addressable destination, as the NavHost declares it.
     *
     * Order is the nav graph's, so the two files can be read side by side.
     * Matching does not depend on it — [byDepth] sorts patterns so that a
     * literal segment always beats an `{arg}` that would also accept it.
     */
    val all: List<String> = listOf(
        "home",
        "search",
        "typing",
        "typing/corrections",
        "typing/suggestions",
        "typing/autopilot",
        "typing/chips",
        "typing/codes",
        "typing/gestures",
        "typing/hardware",
        "keypress",
        "keypress/haptics",
        "keypress/popup",
        "keypress/shortcuts",
        "dictionary",
        "backup",
        "backup/auto",
        "backup/contents",
        "customdictionaries",
        "emojikeywords",
        "blacklist",
        "musicapps",
        "phoneformats",
        "hwshortcuts",
        "appearance",
        "appearance/toolbar",
        "appearance/toolbox",
        "layout",
        "layout/size",
        "layout/onehanded",
        "fonts",
        "fonts/{script}",
        "icons",
        "themes",
        "theme_edit/{themeId}",
        "photos",
        "photo_browse",
        "photo_library",
        "photo_rotation",
        "keymaps",
        "sticker_packs",
        "plugins",
        "plugin/{pluginId}",
        "addons",
        "addon_repo/{repoUrl}",
        "addon/{repoUrl}/{addonId}",
        "sticker_pack/{packId}",
        "keymap_edit/{layoutId}",
        "keymap_json/{layoutId}",
        "panel_edit/{panel}",
        "panel_json/{panel}",
        "languages",
        "add_language",
        "language/{langId}",
        "language/{langId}/more",
        "emoji",
        "emoji/panel",
        "voice",
        "clipboard",
        "expander",
        "expander/edit/{snippetId}",
        "tools",
        "tool/{toolName}",
        "accessibility",
        "privacy",
        "permissions",
        "applock",
        "datasaver",
        "advanced",
        "rows",
        "ai_actions",
        "ai_history",
        "ai_chat",
        "ai_chat/{conversationId}",
        "ai_action_edit/{actionId}",
        "symbol_set_edit/{setId}",
        "modes",
        "mode_edit/{modeId}",
        "about",
        "egg_game",
        "storage",
        "storage/{category}",
        "statistics",
        "debug_log",
        "licenses",
        "license_text/{asset}",
    )

    /** The patterns that take no argument: the plain screens, in graph order. */
    val plain: List<String> = all.filterNot { it.contains('{') }

    /**
     * Matching order: fewest `{arg}` segments first.
     *
     * Nothing in the graph is ambiguous today — no screen has both a literal
     * and an argument at the same depth under the same parent — but a pattern
     * added later could be, and swallowing `storage/photos` into
     * `storage/{category}` when a literal `storage/photos` exists would be a
     * bug nobody sees until a link goes to the wrong page.
     */
    private val byDepth: List<List<String>> =
        all.sortedBy { pattern -> pattern.count { it == '{' } }
            .map { it.split('/') }

    /**
     * The route a link path names, or null when it names no screen.
     *
     * [path] is the raw, still-encoded path of a `wmkeyboard://settings/…`
     * link with its leading and trailing slashes trimmed. The returned route
     * carries the graph's own spelling for every literal segment — so a link
     * may shout `THEMES` and still land on `themes` — and the link's own text
     * for every argument, whose case and encoding are the caller's to decide.
     */
    fun resolve(path: String?): String? {
        val segments = path.orEmpty().trim('/').split('/')
        if (segments.isEmpty() || segments.any { it.isEmpty() }) return null
        // "." and ".." mean something to a URI resolver and nothing to the nav
        // graph, so a link carrying either is refused rather than flattened.
        if (segments.any { it == "." || it == ".." }) return null
        for (pattern in byDepth) {
            if (pattern.size != segments.size) continue
            val filled = ArrayList<String>(pattern.size)
            var matched = true
            for (i in pattern.indices) {
                val part = pattern[i]
                if (part.startsWith("{")) {
                    filled += segments[i]
                } else if (part.equals(segments[i], ignoreCase = true)) {
                    filled += part
                } else {
                    matched = false
                    break
                }
            }
            if (matched) return filled.joinToString("/")
        }
        return null
    }
}

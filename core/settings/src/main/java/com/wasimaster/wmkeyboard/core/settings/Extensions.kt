package com.wasimaster.wmkeyboard.core.settings

/**
 * A feature most people would never expect a keyboard to have, switched on
 * or off as one thing (SETTINGS_REDESIGN.md §3).
 *
 * Not the necessities. Voice typing, the clipboard, snippets, translate and
 * AI are what a keyboard is for today, and each has a switch of its own
 * where it lives. An extension is a bubble level, a music recogniser, a link
 * to the desktop, a notebook, a speed test: a welcome surprise to one user
 * and clutter to the next. So each is one row on the Extensions screen and
 * shows up nowhere else until it is switched on.
 *
 * Off means gone from everywhere the bundle shows: its rows under Advanced,
 * its entries on the Tools list and in every tool picker, its search
 * results, its buttons on the keyboard's toolbar and in its toolbox, its
 * panels, and the background work it owns. On brings all of it back with the
 * settings it had.
 *
 * Each entry names what it owns: the [tools], and the settings [routes] as
 * the nav graph declares them (`{arg}` segments included). A route or a tool
 * belongs to at most one bundle; [ExtensionsTest] holds that.
 *
 * The enabled set is stored in [AppUiSettings.extensions]. Nothing stored
 * means an install from before the screen existed, and that install keeps
 * everything on; onboarding writes [defaultsFor] the depth the user picked.
 */
enum class Extension(
    val tools: Set<ToolbarTool> = emptySet(),
    val routes: Set<String> = emptySet(),
) {
    /** GIFs, stickers and emoji mixes: the three picture panels and the pack screens. */
    STICKERS(
        tools = setOf(ToolbarTool.GIF, ToolbarTool.STICKER, ToolbarTool.EMOJI_MIX),
        routes = setOf(
            "sticker_packs", "signal_stickers", "whatsapp_stickers", "telegram_stickers",
            "sticker_generator", "sticker_pack/{packId}", "sticker_pack/{packId}/add", "sticker_pack/{packId}/edit/{stickerId}", "emoji/mixes",
        ),
    ),

    /** Text scan, the QR and barcode reader and the document scanner. */
    SCANNERS(
        tools = setOf(ToolbarTool.OCR, ToolbarTool.QR_SCAN, ToolbarTool.DOC_SCAN),
    ),

    /** The handwriting panel and its models. */
    HANDWRITING(
        tools = setOf(ToolbarTool.HANDWRITING),
    ),

    /** The notebook in the keyboard and the quick note action. */
    NOTES(
        tools = setOf(ToolbarTool.NOTES, ToolbarTool.QUICK_NOTE),
        routes = setOf("notes", "notes/edit/{noteId}", "notes/tags", "notes/trash"),
    ),

    /** Vocabulary packs, the word of the day and the nudges. */
    VOCABULARY(
        tools = setOf(ToolbarTool.VOCABULARY),
        routes = setOf(
            "vocab/packs", "vocab/lists", "vocab/list/{packId}", "vocab/review", "vocab/browse",
            "vocab/word/{packId}/{word}",
        ),
    ),

    /** The calculator and the unit and currency converters. */
    CALCULATORS(
        tools = setOf(ToolbarTool.CALCULATOR, ToolbarTool.UNIT_CONVERT, ToolbarTool.CURRENCY),
    ),

    /** Web search, image search and Wikipedia, inside the keyboard. */
    WEB_SEARCH(
        tools = setOf(ToolbarTool.WEB_SEARCH, ToolbarTool.IMAGE_SEARCH, ToolbarTool.WIKIPEDIA),
    ),

    /** The camera and the gallery panels. */
    CAMERA_AND_GALLERY(
        tools = setOf(ToolbarTool.CAMERA, ToolbarTool.GALLERY),
    ),

    /** The calendar and the world clock, timers and countdowns. */
    CALENDAR_AND_CLOCK(
        tools = setOf(ToolbarTool.CALENDAR, ToolbarTool.CLOCK),
        routes = setOf("clock"),
    ),

    /** Passwords, QR codes and repeated text, made on the keyboard. */
    GENERATORS(
        tools = setOf(ToolbarTool.PASSWORD_GEN, ToolbarTool.QR_GEN, ToolbarTool.TEXT_REPEAT),
    ),

    /** The typing speed test and its history. */
    TYPING_TEST(
        tools = setOf(ToolbarTool.TYPING_TEST),
    ),

    /** The field read aloud. */
    READ_BACK(
        tools = setOf(ToolbarTool.READ_BACK),
    ),

    /** Teaching the dictionary from a block of text. */
    LEARN_FROM_TEXT(
        tools = setOf(ToolbarTool.LEARN_FROM_TEXT),
    ),

    /** Find and replace inside the field. */
    FIND_REPLACE(
        tools = setOf(ToolbarTool.FIND_REPLACE),
    ),

    /** Keyboard modes: per-app and per-field bundles of overrides. */
    MODES(
        tools = setOf(ToolbarTool.MODES),
        routes = setOf("modes", "mode_edit/{modeId}"),
    ),

    /** Play, pause and skip from the toolbar, and the apps it follows. */
    MEDIA_CONTROLS(
        tools = setOf(ToolbarTool.MEDIA_CONTROL),
        routes = setOf("musicapps"),
    ),

    /** Opening other apps and split-screen pairs from the keyboard. */
    APP_LAUNCHER(
        tools = setOf(ToolbarTool.APP_LAUNCHER),
        routes = setOf("launchercombos"),
    ),

    /** The weather and the location panels. */
    WEATHER_AND_LOCATION(
        tools = setOf(ToolbarTool.WEATHER, ToolbarTool.LOCATION),
    ),

    /** The flashlight, the compass, the bubble level and the moon phase. */
    INSTRUMENTS(
        tools = setOf(ToolbarTool.FLASHLIGHT, ToolbarTool.COMPASS, ToolbarTool.LEVEL, ToolbarTool.MOON_PHASE),
    ),

    /** Naming the song that is playing. */
    MUSIC_ID(
        tools = setOf(ToolbarTool.MUSIC_ID),
        routes = setOf("music/history"),
    ),

    /** The internet speed test. */
    SPEED_TEST(
        tools = setOf(ToolbarTool.SPEED_TEST),
    ),

    /** The link to a desktop through KDE Connect. */
    KDE_CONNECT(
        tools = setOf(ToolbarTool.KDE_CONNECT),
        routes = setOf("kdeconnect/devices"),
    ),

    /** Sending files to other devices. */
    SEND_FILES(
        tools = setOf(ToolbarTool.SEND_FILES),
        routes = setOf("send/instances", "send/history"),
    ),

    /** Lights, switches and scenes from the keyboard. */
    SMART_HOME(
        tools = setOf(ToolbarTool.HOME_ASSISTANT),
        routes = setOf("homeassistant/entities"),
    ),
    ;

    companion object {
        /** Every bundle, as a set. The answer for an install that never chose. */
        val All: Set<Extension> = entries.toSet()

        /** What "keep it simple" starts with: nothing. Every extension is a switch away. */
        val SimpleDefaults: Set<Extension> = emptySet()

        /**
         * What "a good middle" starts with: the bundles that work the moment
         * they are on, with no pairing, account, sensor or grant behind them.
         * Left off: the ones that reach another device, a server or the
         * phone's hardware, and modes, which are a system of their own.
         */
        val StandardDefaults: Set<Extension> = setOf(
            STICKERS, SCANNERS, HANDWRITING, NOTES, VOCABULARY, CALCULATORS, WEB_SEARCH, CAMERA_AND_GALLERY,
            CALENDAR_AND_CLOCK, GENERATORS, TYPING_TEST, READ_BACK, LEARN_FROM_TEXT, FIND_REPLACE,
        )

        /** The bundles on at [depth] before the user touches the screen. */
        fun defaultsFor(depth: SettingsDepth): Set<Extension> = when (depth) {
            SettingsDepth.SIMPLE -> SimpleDefaults
            SettingsDepth.STANDARD, SettingsDepth.AUTO -> StandardDefaults
            SettingsDepth.EVERYTHING -> All
        }

        private val byTool: Map<ToolbarTool, Extension> =
            entries.flatMap { ext -> ext.tools.map { it to ext } }.toMap()

        private val byRoute: Map<String, Extension> =
            entries.flatMap { ext -> ext.routes.map { it to ext } }.toMap()

        /** The bundle [tool] belongs to, or null for a tool that is its own. */
        fun owning(tool: ToolbarTool): Extension? = byTool[tool]

        /**
         * The bundle that owns the screen [pattern] names, or null. [pattern]
         * is the nav graph's own spelling, `{arg}` segments included; a filled
         * route is matched by the caller through its allowlist first.
         */
        fun owningRoute(pattern: String): Extension? = byRoute[pattern]

        /**
         * The stored names back as bundles. A name this build does not know
         * (one removed later, or a typo in a synced store) is dropped rather
         * than failing the whole read.
         */
        fun decode(names: Set<String>): Set<Extension> =
            names.mapNotNullTo(LinkedHashSet()) { name -> entries.firstOrNull { it.name == name } }
    }
}

/**
 * The bundles switched on, for every reader: the stored set, or everything
 * for an install that never stored one. See [AppUiSettings.extensions].
 */
fun KeyboardSettings.extensionsResolved(): Set<Extension> = appUi.extensions ?: Extension.All

/** Whether [extension] is on; see [extensionsResolved]. */
fun KeyboardSettings.extensionOn(extension: Extension): Boolean = extension in extensionsResolved()

/** Whether [tool] is free to show: its bundle is on, or it belongs to none. */
fun Set<Extension>.allows(tool: ToolbarTool): Boolean =
    Extension.owning(tool)?.let { it in this } ?: true

/** The bundle's tools this build has: what its switch and the Tools page's switches share. */
fun Extension.liveTools(): List<ToolbarTool> = tools.filter(::isSupportedTool)

/**
 * The bundles on after [tool] is switched [enabled] on its own, with
 * [disabledAfter] the switched-off tools once that is written (#1067). The
 * Tools page and a bundle switch the same things, so they have to agree:
 * switching a tool on switches its bundle on, and switching off the last of
 * a bundle's tools switches the bundle off, rather than leave it reading on
 * with nothing in it. A tool no bundle owns changes nothing here.
 */
fun Set<Extension>.afterToolSwitch(
    tool: ToolbarTool,
    enabled: Boolean,
    disabledAfter: Collection<ToolbarTool>,
): Set<Extension> {
    val owner = Extension.owning(tool) ?: return this
    return when {
        enabled -> this + owner
        owner.liveTools().all { it in disabledAfter } -> this - owner
        else -> this
    }
}

/**
 * The tools to switch back on with [this] bundle, out of [disabled]: all of
 * its tools when every one of them was switched off one by one (#1067), so the
 * bundle's switch never turns on a bundle with nothing in it, and none
 * otherwise, so a choice made among its tools comes back as it was.
 */
fun Extension.toolsToRestore(disabled: Collection<ToolbarTool>): Set<ToolbarTool> {
    val live = liveTools()
    return if (live.isNotEmpty() && live.all { it in disabled }) live.toSet() else emptySet()
}

/** Whether the screen [pattern] names is free to open; see [allows]. */
fun Set<Extension>.allowsRoute(pattern: String): Boolean =
    Extension.owningRoute(pattern)?.let { it in this } ?: true

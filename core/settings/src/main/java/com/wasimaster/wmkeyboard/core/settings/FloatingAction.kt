package com.wasimaster.wmkeyboard.core.settings

/**
 * What one gesture on the floating button does (#755), or what one slot of its
 * quick menu runs.
 *
 * Stored as a short token (`keyboard`, `ask_ai`, `tool:VOICE`), so a tool the
 * enum later drops reads back as nothing rather than as a crash, and a menu is
 * one comma-joined string.
 */
sealed interface FloatingAction {
    val token: String

    /** The gesture is not bound. */
    data object None : FloatingAction {
        override val token = "none"
    }

    /** Brings the keyboard up, field or no field; puts it away while it is up (#374, #697). */
    data object Keyboard : FloatingAction {
        override val token = "keyboard"
    }

    /** Opens the Ask AI sheet about what is on screen (#697). */
    data object AskAi : FloatingAction {
        override val token = "ask_ai"
    }

    /**
     * Waits for a tap on a word anywhere on screen, then opens the Look up
     * card for it (#765). Reads the screen, so it needs screen reading.
     */
    data object LookUp : FloatingAction {
        override val token = "look_up"
    }

    /** Fans the quick menu out around the button. Never a menu slot itself. */
    data object Menu : FloatingAction {
        override val token = "menu"
    }

    /** Puts the button away until the keyboard next opens or closes. */
    data object Hide : FloatingAction {
        override val token = "hide"
    }

    /** Runs a toolbar tool, bringing the keyboard up first when the tool needs it. */
    data class Tool(val tool: ToolbarTool) : FloatingAction {
        override val token get() = "$TOOL_PREFIX${tool.name}"
    }

    /**
     * Opens an app (#922), by its package: whatever its launcher entry is
     * when pressed, so an update that renames the entry keeps working.
     *
     * [profile] is the serial of another profile the app lives in, the work
     * profile above all (#1138), or null for the keyboard's own. Stored as
     * `app:package@serial`, the key the app launcher keeps such an app under.
     */
    data class App(val packageName: String, val profile: Long? = null) : FloatingAction {
        override val token get() = APP_PREFIX + packageName + (profile?.let { "@$it" } ?: "")

        /** From another profile, so opened through it and drawn with its badge. */
        val otherProfile: Boolean get() = profile != null
    }

    /**
     * Opens two apps together (#1140), as the app launcher's split pairs do:
     * [first] full screen, then [second] beside it in split screen where the
     * phone has one. By package, as [LauncherSplitCombo] keeps them, so a
     * pair picked from the launcher's list is the same pair.
     */
    data class AppPair(val first: String, val second: String) : FloatingAction {
        override val token get() = "$PAIR_PREFIX$first$PAIR_JOIN$second"
    }

    /**
     * Opens [tool] in a floating box over the app (#1155), straight from the
     * button, without the keyboard coming up first. Only an [AppletTools] tool.
     */
    data class Applet(val tool: ToolbarTool) : FloatingAction {
        override val token get() = "$APPLET_PREFIX${tool.name}"
    }

    /**
     * Presses one of Android's own system buttons (#1170): Back, recent apps,
     * the app drawer, the notification shade or quick settings, through the
     * system buttons accessibility service. With the service off, a press
     * explains it and opens Android's accessibility settings.
     */
    data class System(val button: SystemButton) : FloatingAction {
        override val token get() = "$SYSTEM_PREFIX${button.name}"
    }

    /**
     * Opens one of the button's categories (#923): a ring of its own, in
     * place of the one it sits in, that a hold can slide on into. Its name
     * and slots live in [SummonButtonSettings.categories], under [id].
     */
    data class Category(val id: String) : FloatingAction {
        override val token get() = "$CATEGORY_PREFIX$id"
    }

    /**
     * Runs one of the user's own buttons (#1213, #1236): an app's shortcut, a
     * setting switch or a settings page, by its [CustomButton.id] in
     * [ToolboxSettings.customButtons]. A button since deleted does nothing.
     */
    data class Custom(val id: String) : FloatingAction {
        override val token get() = "$CUSTOM_PREFIX$id"
    }

    companion object {
        private const val TOOL_PREFIX = "tool:"
        private const val APP_PREFIX = "app:"
        private const val CATEGORY_PREFIX = "category:"
        private const val PAIR_PREFIX = "pair:"
        private const val APPLET_PREFIX = "applet:"
        private const val SYSTEM_PREFIX = "system:"
        private const val CUSTOM_PREFIX = "custom:"

        /** Between a pair's two packages; no package name has one. */
        private const val PAIR_JOIN = '+'

        /** The token's action, or null for one this build does not know. */
        fun parse(token: String): FloatingAction? = when (token) {
            None.token -> None
            Keyboard.token -> Keyboard
            AskAi.token -> AskAi
            LookUp.token -> LookUp
            Menu.token -> Menu
            Hide.token -> Hide
            else -> when {
                token.startsWith(TOOL_PREFIX) -> token.removePrefix(TOOL_PREFIX)
                    .let { name -> ToolbarTool.entries.firstOrNull { it.name == name } }
                    ?.let(::Tool)
                token.startsWith(APP_PREFIX) -> parseApp(token.removePrefix(APP_PREFIX))
                token.startsWith(SYSTEM_PREFIX) -> token.removePrefix(SYSTEM_PREFIX)
                    .let { name -> SystemButton.entries.firstOrNull { it.name == name } }
                    ?.let(::System)
                token.startsWith(APPLET_PREFIX) -> token.removePrefix(APPLET_PREFIX)
                    .let { name -> AppletTools.firstOrNull { it.name == name } }
                    ?.let(::Applet)
                token.startsWith(PAIR_PREFIX) -> token.removePrefix(PAIR_PREFIX).split(PAIR_JOIN)
                    .takeIf { it.size == 2 && it.all { name -> name.isPackageName() } }
                    ?.let { AppPair(it[0], it[1]) }
                token.startsWith(CUSTOM_PREFIX) -> token.removePrefix(CUSTOM_PREFIX)
                    .takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
                    ?.let(::Custom)
                token.startsWith(CATEGORY_PREFIX) -> token.removePrefix(CATEGORY_PREFIX)
                    .takeIf { it.isNotEmpty() && it.none { c -> c == ',' || c.isWhitespace() } }
                    ?.let(::Category)
                else -> null
            }
        }

        /** `package` or `package@serial` (#1138), or null for anything else. */
        private fun parseApp(rest: String): App? {
            val packageName = rest.substringBefore('@')
            if (!packageName.isPackageName()) return null
            if ('@' !in rest) return App(packageName)
            val serial = rest.substringAfter('@').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.toLongOrNull() ?: return null
            return App(packageName, serial)
        }

        /** Letters, digits, `_` and dots, as Android allows a package's name; nothing a menu token splits on. */
        private fun String.isPackageName(): Boolean =
            isNotEmpty() && '.' in this && all { it.isLetterOrDigit() || it == '_' || it == '.' }

        /** A stored quick menu: the slots this build still knows, in order, each once. */
        fun parseMenu(stored: String): List<FloatingAction> =
            stored.split(',')
                .mapNotNull { parse(it.trim()) }
                .filter { it.fitsMenu() }
                .distinct()
                .take(MAX_FLOATING_MENU_ITEMS)

        fun menuToken(items: List<FloatingAction>): String =
            items.filter { it.fitsMenu() }.distinct().take(MAX_FLOATING_MENU_ITEMS).joinToString(",") { it.token }
    }
}

/**
 * The tools that open in a floating box over other apps (#1155): the ones
 * that work with no text field behind them, by their own buttons, or by a
 * text box of their own the keyboard types into. In the order the pickers
 * list them.
 *
 * The clipboard, calendar, media controls, dictionary, Wikipedia and web
 * search joined them in #1265, and plugins with API 5. Left out on purpose: the panels that are keys
 * themselves (emoji, the number pad, handwriting, the trackpad), the AI chat,
 * whose composer, model and replies live and die with its panel, and find and
 * replace, which edits the app's field through the keyboard's own connection.
 */
val AppletTools: List<ToolbarTool> = listOf(
    ToolbarTool.CALCULATOR,
    ToolbarTool.UNIT_CONVERT,
    ToolbarTool.CURRENCY,
    ToolbarTool.CLOCK,
    ToolbarTool.TODO,
    ToolbarTool.NOTES,
    ToolbarTool.TRANSLATE,
    ToolbarTool.CLIPBOARD,
    ToolbarTool.CALENDAR,
    ToolbarTool.MEDIA_CONTROL,
    ToolbarTool.DICTIONARY,
    ToolbarTool.WIKIPEDIA,
    ToolbarTool.WEB_SEARCH,
    ToolbarTool.PLUGINS,
)

/** Whether [this] opens in a floating box (#1155). */
val ToolbarTool.floats: Boolean get() = this in AppletTools

/**
 * Whether a tap outside [tool]'s floating box closes it: the setting for every
 * box, unless that tool is set apart from it (#1262). Null is a box whose tool
 * is not known, which follows the setting.
 */
fun KeyboardSettings.boxClosesOutside(tool: ToolbarTool?): Boolean =
    summonButton.boxClosesOutside != (tool != null && tool in toolbarBehavior.boxOutsideFlipped)

/** A quick menu slot cannot open the menu it sits in, nor do nothing. */
fun FloatingAction.fitsMenu(): Boolean = this != FloatingAction.None && this != FloatingAction.Menu

/** A category's slot is anything a menu slot can be but another category: one level deep (#923). */
fun FloatingAction.fitsCategory(): Boolean = fitsMenu() && this !is FloatingAction.Category

/**
 * How many slots the quick menu, or a category, fans out (#1172): eight on
 * the ring round the button, and up to eight more on a second ring outside
 * it. Past that the rings stop fitting on a phone's screen.
 */
const val MAX_FLOATING_MENU_ITEMS = 16

/** The quick menu a fresh install opens with: the reasons people asked for the button. */
val DefaultFloatingMenu: List<FloatingAction> = listOf(
    FloatingAction.Tool(ToolbarTool.VOICE),
    FloatingAction.AskAi,
    FloatingAction.Tool(ToolbarTool.CLIPBOARD),
    FloatingAction.Tool(ToolbarTool.EMOJI),
    FloatingAction.Keyboard,
)

/** The button's colours. */
enum class FloatingButtonStyle {
    /** Dark on a light system theme's apps and light on a dark one's, like the shade. */
    AUTO,
    DARK,
    LIGHT,

    /** The system's accent (Material You on Android 12 and up). */
    ACCENT,
}

/**
 * The button's outline (#933). Its quick menu's slots take the same one, so
 * the ring reads as the button's. [CIRCLE] is the button as it always was.
 */
enum class FloatingButtonShape {
    CIRCLE,
    SQUIRCLE,
    ROUNDED_SQUARE,
    OVAL,
    DIAMOND,
    PENTAGON,
    HEXAGON,
    OCTAGON,
    STAR,
    HEART,
}

/** How the quick menu's slots, and a category's, come out of the button (#923). */
enum class FloatingMenuAnimation {
    /** Each slot springs out from the button a moment after the one before: the menu as it always was. */
    SPRING,

    /** Each slot grows where it sits, one after another. */
    POP,

    /** The ring swings round into place from the button's side. */
    SWEEP,

    /** The whole ring fades in where it sits. */
    FADE,

    /** There at once. */
    NONE,
}

/** The size slider's range, in dp. */
val FloatingButtonSizeRange = 40..72

/** How far a ring's slots stand off the button's edge, in dp (#923). */
val FloatingMenuGapRange = 8..96

/** How long the button waits before fading, in seconds; 0 never fades. */
val FloatingButtonIdleRange = 0..10

/** How fast the button's motion may run, as a multiple of its own pace (#1138). */
val FloatingAnimationSpeedRange = 0.5f..3f

/** The edge pill's thickness, in dp (#1137). */
val FloatingPillThicknessRange = 2..16

/** The edge pill's height, in dp (#1137). */
val FloatingPillLengthRange = 24..200

/** How far in from the edge the pill takes a touch, in dp (#1137). */
val FloatingPillTouchRange = 4..48

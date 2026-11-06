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
     * Opens one of the button's categories (#923): a ring of its own, in
     * place of the one it sits in, that a hold can slide on into. Its name
     * and slots live in [SummonButtonSettings.categories], under [id].
     */
    data class Category(val id: String) : FloatingAction {
        override val token get() = "$CATEGORY_PREFIX$id"
    }

    companion object {
        private const val TOOL_PREFIX = "tool:"
        private const val APP_PREFIX = "app:"
        private const val CATEGORY_PREFIX = "category:"

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

/** A quick menu slot cannot open the menu it sits in, nor do nothing. */
fun FloatingAction.fitsMenu(): Boolean = this != FloatingAction.None && this != FloatingAction.Menu

/** A category's slot is anything a menu slot can be but another category: one level deep (#923). */
fun FloatingAction.fitsCategory(): Boolean = fitsMenu() && this !is FloatingAction.Category

/** How many slots the quick menu fans out; past this the ring stops reading as a ring. */
const val MAX_FLOATING_MENU_ITEMS = 8

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

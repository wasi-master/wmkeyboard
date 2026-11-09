package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardReturn
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardTab
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AlternateEmail
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CalendarMonth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DarkMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FavoriteBorder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Highlight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardHide
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LightMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Menu
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Numbers
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Phone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SpaceBar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import android.view.KeyEvent
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.icons.SymbolIcons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardCapslock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Layers
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune

/**
 * Named-icon registry that lets any [com.wasimaster.wmkeyboard.core.layout.Key]
 * draw a vector glyph — as the key's main label ([Key.icon]) or as its corner
 * hint ([Key.iconHint]) — instead of a character.
 *
 * The [Key] type is a plain `@Serializable` value with no Compose dependency,
 * so it stores the icon as a *name*; this object turns that name into the
 * actual [ImageVector] at render time. Keeping the mapping here (in the ime/ui
 * layer) is what keeps `core/layout` free of Compose types.
 *
 * Names are matched case-insensitively; a handful of [aliases] cover the common
 * synonyms. An unknown name resolves to null, and the renderer falls back to
 * drawing the text label — so a typo degrades gracefully rather than crashing.
 */
object KeyIcons {

    /**
     * Canonical name → vector. The iteration order is the order an icon picker
     * would present them in, so the most generally useful glyphs lead.
     */
    val catalog: Map<String, ImageVector> by lazy {
        linkedMapOf(
        // Editing / navigation.
        "backspace" to Icons.AutoMirrored.Outlined.Backspace,
        "forward_delete" to KeyboardIcons.ForwardDelete,
        "enter" to Icons.AutoMirrored.Outlined.KeyboardReturn,
        "tab" to Icons.AutoMirrored.Outlined.KeyboardTab,
        "space" to Icons.Outlined.SpaceBar,
        "shift" to SymbolIcons.Shift,
        "shift_on" to SymbolIcons.ShiftFilled,
        "shift_lock" to SymbolIcons.ShiftLockFilled,
        "caps_lock" to Icons.Outlined.KeyboardCapslock,
        "undo" to Icons.AutoMirrored.Outlined.Undo,
        "redo" to Icons.AutoMirrored.Outlined.Redo,
        // Arrows.
        "arrow_up" to Icons.Outlined.ArrowUpward,
        "arrow_down" to Icons.Outlined.ArrowDownward,
        "arrow_left" to Icons.AutoMirrored.Outlined.ArrowBack,
        "arrow_right" to Icons.AutoMirrored.Outlined.ArrowForward,
        "chevron_up" to Icons.Outlined.KeyboardArrowUp,
        "chevron_down" to Icons.Outlined.KeyboardArrowDown,
        "chevron_left" to Icons.Outlined.ChevronLeft,
        "chevron_right" to Icons.Outlined.ChevronRight,
        // Actions.
        "search" to Icons.Outlined.Search,
        "send" to Icons.AutoMirrored.Outlined.Send,
        "check" to Icons.Outlined.Check,
        "close" to Icons.Outlined.Close,
        "add" to Icons.Outlined.Add,
        "remove" to Icons.Outlined.Remove,
        "copy" to Icons.Outlined.ContentCopy,
        "cut" to Icons.Outlined.ContentCut,
        "paste" to Icons.Outlined.ContentPaste,
        // Its bundled name, Delete, is taken: `delete` has always meant backspace.
        "trash" to Icons.Outlined.Delete,
        // Content / tools.
        "emoji" to Icons.Outlined.EmojiEmotions,
        "language" to Icons.Outlined.Language,
        "translate" to Icons.Outlined.Translate,
        "mic" to Icons.Outlined.Mic,
        "image" to Icons.Outlined.Image,
        "link" to Icons.Outlined.Link,
        "at" to Icons.Outlined.AlternateEmail,
        "phone" to Icons.Outlined.Phone,
        "numbers" to Icons.Outlined.Numbers,
        "calendar" to Icons.Outlined.CalendarMonth,
        "star" to Icons.Outlined.Star,
        "heart" to Icons.Outlined.FavoriteBorder,
        // Keyboard / app chrome.
        "keyboard" to Icons.Outlined.Keyboard,
        "keyboard_hide" to Icons.Outlined.KeyboardHide,
        "settings" to Icons.Outlined.Settings,
        "menu" to Icons.Outlined.Menu,
        "more" to Icons.Outlined.MoreHoriz,
        "light_mode" to Icons.Outlined.LightMode,
        "dark_mode" to Icons.Outlined.DarkMode,
        "highlight" to Icons.Outlined.Highlight,
        "incognito" to KeyboardIcons.Incognito,
        )
    }

    /** Common synonyms mapped onto their canonical [catalog] entry. */
    private val aliases: Map<String, String> by lazy {
        mapOf(
        "delete" to "backspace",
        "return" to "enter",
        "caps" to "caps_lock",
        "capslock" to "caps_lock",
        "shift_filled" to "shift_on",
        "up" to "arrow_up",
        "down" to "arrow_down",
        "left" to "arrow_left",
        "right" to "arrow_right",
        "done" to "check",
        "clear" to "close",
        "plus" to "add",
        "minus" to "remove",
        "clipboard" to "paste",
        "globe" to "language",
        "voice" to "mic",
        "photo" to "image",
        "picture" to "image",
        "email" to "at",
        "mail" to "at",
        "favorite" to "heart",
        "hide" to "keyboard_hide",
        "gear" to "settings",
        "bulb" to "highlight",
        )
    }

    /**
     * Canonical name → the [aliases] that resolve to it, so the icon picker can
     * match a search against what a drawing is usually called and not only
     * against the short name a layout file stores: "clipboard" finds `paste`,
     * "globe" finds `language` (issue #223).
     */
    private val aliasesByCanonical: Map<String, List<String>> by lazy {
        aliases.entries.groupBy({ it.value }, { it.key })
    }

    /**
     * The picture in the user's icon library that [name] points at, as
     * `u:<id>`, or null for every other name. The board, the editor's preview
     * and the picker ask here, so the spelling lives in one place.
     */
    fun libraryIdOf(name: String?): String? =
        com.wasimaster.wmkeyboard.core.icons.IconOverrides.libraryIdOf(name?.trim())
            ?.takeIf { com.wasimaster.wmkeyboard.core.icons.IconLibrary.isId(it) }

    /** The name a key stores to wear library picture [id]. */
    fun libraryName(id: String): String = com.wasimaster.wmkeyboard.core.icons.IconOverrides.librarySource(id)

    /** The alias names that draw [name], for the picker's search. */
    fun aliasesFor(name: String): List<String> =
        aliasesByCanonical[name.trim().lowercase()].orEmpty()

    /**
     * The app's own bundled glyphs ([BuiltinIcons]) by lowercased name, so a key
     * can wear any icon the settings already draw — `EmojiEmotions`, `Translate`
     * — at no size cost (issue #187). Looked up after [catalog] and [aliases],
     * which keep their short names.
     */
    private val builtinByLowerName: Map<String, () -> ImageVector> by lazy {
        // mapKeys, not mapValues: the builders stay unbuilt, so resolving one
        // bundled icon name costs that one icon rather than all of them.
        BuiltinIcons.catalog.mapKeys { it.key.lowercase() }
    }

    /**
     * Every icon name a layout can use: canonical names, aliases, then the
     * bundled app icons that are not already one of those under the same name.
     */
    val names: List<String> by lazy {
        catalog.keys.toList() + aliases.keys +
            BuiltinIcons.names.filter { it.lowercase() !in catalog && it.lowercase() !in aliases }
    }

    /**
     * What the layout editor's icon picker offers, in order: the key glyphs under
     * their short names first, then each bundled app icon that is not the same
     * drawing under another name. One entry per drawing, so the grid never shows
     * the same glyph twice.
     *
     * A bundled name that is also an alias is left out: `Delete` already means
     * backspace and `Done` already means check to every layout written so far,
     * so offering them would store a name that draws something else. The trash
     * glyph stays reachable as `trash`.
     */
    val pickerEntries: List<Pair<String, ImageVector>> by lazy {
        val drawn = catalog.values.toHashSet()
        // The picker is the one place that wants every bundled icon, so this
        // is where the builders are invoked. Names are checked first so an icon
        // already offered under a short name is skipped without building it.
        catalog.toList() + BuiltinIcons.catalog.mapNotNull { (name, build) ->
            val key = name.lowercase()
            if (key in catalog || key in aliases) return@mapNotNull null
            val vector = build()
            if (vector in drawn) null else name to vector
        }
    }

    /**
     * The glyph an unlabelled key or popup entry of [action] draws in place of
     * the stand-in character [KeyAction.fallbackLabel] spells for it, or null
     * for an action with no glyph: the ones that read as a word (Ctrl, Fn, esc,
     * ?123) and the ones that draw from an icon slot of their own.
     *
     * The board, the alternates popup and the layout editor's preview all ask
     * here, so the three cannot drift apart. Tab and the arrows are answered
     * with their slot's built-in glyph for the two that have no icon pack to
     * honour; the board draws those through the slot itself.
     */
    fun forAction(action: KeyAction): ImageVector? = when (action) {
        // A named layout: the keyboard with a switch arrow, apart from the
        // input-method picker's plain keyboard and the globe.
        is KeyAction.Layout -> SymbolIcons.KeyboardPreviousLanguage
        is KeyAction.LayerSwitch -> Icons.Outlined.Layers
        // The Modes tool's own icon, so a mode key reads as that panel's pick.
        is KeyAction.Mode -> Icons.Outlined.Tune
        is KeyAction.Broadcast -> Icons.Outlined.Bolt
        is KeyAction.SendKey -> arrowKeySlot(action.keyCode)?.let(IconDefaults::forSlot)
            // Escape is a word on every physical keyboard, so it keeps "esc".
            ?: SymbolIcons.KeyboardKeys.takeIf { action.keyCode != KeyEvent.KEYCODE_ESCAPE }
        else -> null
    }

    /**
     * The vector for [name], or null if the name is blank or unrecognised (the
     * caller then draws the text label). Case- and whitespace-insensitive.
     */
    fun byName(name: String?): ImageVector? {
        // Before [catalog] is touched, which is the point of the check being
        // here rather than at the call site: every key on the board asks this
        // on every grid build, almost always with no icon name at all, and
        // building the catalogue means constructing seventy ImageVectors —
        // path data parsed and node trees built — on whichever thread got
        // here first. That was the keyboard's first frame. Lazy plus this
        // early return means a board of ordinary keys never builds it.
        if (name.isNullOrBlank()) return null
        val key = name.trim().lowercase()
        catalog[key]?.let { return it }
        aliases[key]?.let { alias -> return catalog[alias] }
        return builtinByLowerName[key]?.invoke()
    }
}

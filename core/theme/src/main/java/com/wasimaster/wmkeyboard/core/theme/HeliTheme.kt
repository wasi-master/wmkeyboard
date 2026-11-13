package com.wasimaster.wmkeyboard.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.math.BigInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading a HeliBoard (or LeanType) colour theme.
 *
 * Those keyboards do not have a theme *file*. A theme is shared as a line of
 * JSON, copied out of the colour screen's menu and pasted into a forum post,
 * which is why two whole theme collections and a discussion category exist as
 * text rather than as downloads. So this takes text, from a file or from the
 * clipboard, and neither route needs an extension to be claimed.
 *
 * **Written from the published format, not from either project's source.**
 * HeliBoard and LeanType are GPL-3.0 and this app is MIT; what is transcribed
 * here is the shape of a JSON document, the same footing as the layout
 * converters in `:core:language`. LeanType is a HeliBoard fork and its colour
 * screen is unchanged, so one reader serves both.
 *
 * ### Two shapes, because the other keyboard has two
 *
 * With its own colour screen set to *few* or *more* colours, a theme exports as
 * an object: a name, which of the three modes it was in, and ten named colours,
 * each with a flag saying whether the user picked it or let the app derive it.
 *
 * ```json
 * {"name":"Midnight","moreColors":0,"colors":{"background":[-16777216,false], …}}
 * ```
 *
 * Set to *all* colours it exports as a flat map of the forty-odd internal
 * colour roles instead, with the theme's own name folded in as a base-36 key
 * whose value is 0.
 *
 * ```json
 * {"KEY_BACKGROUND":-16777216,"KEY_TEXT":-1, …,"1c8k2f4":0}
 * ```
 *
 * Both are read. The second carries far more than the first, and is the one a
 * carefully built theme is usually in.
 *
 * ### A derived colour stays derived
 *
 * `[null,true]` or `[-16777216,true]` both mean *the app worked this one out*.
 * Writing such a value in here would freeze another keyboard's derivation into
 * a literal colour, and then the user could not tell it from a colour they
 * chose. Those fields are left null, which is this app's own way of saying the
 * same thing.
 */
object HeliTheme {

    /** What the import picker accepts: a theme travels as plain text. */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/json",
        "text/plain",
        "application/octet-stream",
    )

    /** The longest pasted text worth parsing. A real theme is under 4 KB. */
    const val MAX_LENGTH = 256 * 1024

    /**
     * Converts [text], from a file or from the clipboard.
     *
     * Never throws: a pasted sentence, a `.flex`, and this app's own theme file
     * are all ordinary outcomes with their own answer.
     */
    fun read(text: String): HeliResult {
        if (text.length > MAX_LENGTH) return HeliResult.NotATheme
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return HeliResult.NotATheme
        return namedShape(root) ?: allColoursShape(root) ?: HeliResult.NotATheme
    }

    // ---- the named shape ----

    /**
     * `{"name":…,"moreColors":…,"colors":{…}}`.
     *
     * Recognised by `colors` holding two-element arrays, which is how a
     * `Pair<Int?, Boolean>` is written. Null when the object is some other
     * JSON entirely, so the caller can try the flat shape next.
     */
    private fun namedShape(root: JsonObject): HeliResult? {
        val colors = root["colors"] as? JsonObject ?: return null
        val picked = LinkedHashMap<String, Long>()
        var read = 0
        var derived = 0
        for ((key, value) in colors) {
            val pair = value as? JsonArray ?: return null
            if (pair.size != PAIR_SIZE) return null
            read++
            val auto = (pair[1] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            val argb = (pair[0] as? JsonPrimitive)?.content?.toIntOrNull()
            // A colour the other keyboard derived is left for this one to
            // derive: see the class comment.
            if (auto || argb == null) {
                derived++
                continue
            }
            picked[key] = argb.toArgbLong()
        }
        if (read == 0) return null
        val name = (root["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return convert(
            name = name ?: DEFAULT_NAME,
            colours = picked.mapKeys { (key, _) -> key.lowercase() },
            read = read,
            shape = HeliShape.NAMED,
            derived = derived,
        )
    }

    // ---- the all-colours shape ----

    /**
     * A flat `{"KEY_BACKGROUND":-16777216, …}` map.
     *
     * One entry is not a colour: the theme's name, base-36 encoded, with the
     * value 0. It is told apart by not being a role this reader knows, which is
     * also what makes the decode safe — a key that is neither a role nor
     * base-36 text simply goes nowhere.
     */
    private fun allColoursShape(root: JsonObject): HeliResult? {
        val picked = LinkedHashMap<String, Long>()
        var name: String? = null
        var read = 0
        for ((key, value) in root) {
            val argb = (value as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            val role = key.uppercase()
            if (role in AllColours) {
                read++
                picked[role] = argb.toArgbLong()
            } else if (name == null) {
                name = decodeBase36(key)
            }
        }
        if (read == 0) return null
        return convert(
            name = name?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME,
            colours = picked,
            read = read,
            shape = HeliShape.ALL,
        )
    }

    /**
     * HeliBoard folds the theme's name into the map as base-36 bytes.
     *
     * Anything that is not a valid encoding is simply not the name: an unknown
     * role from a newer build lands here too, and must not become a theme
     * called "?????".
     */
    private fun decodeBase36(key: String): String? = runCatching {
        BigInteger(key, BASE36).toByteArray().decodeToString().takeIf { text ->
            text.isNotBlank() && text.all { it.code in PRINTABLE }
        }
    }.getOrNull()

    // ---- the mapping ----

    /**
     * The colours as a [ThemeSpec].
     *
     * The same rule the `.flex` mapper works by: a field is written only when
     * the file says something unambiguous about that surface, and everything
     * else is left null for this app to derive. What is different here is that
     * the source is a fixed, named set rather than a stylesheet of arbitrary
     * selectors, so nothing has to be guessed at from a rule that meant
     * something else — either the colour is in the file or it is not.
     */
    private fun convert(
        name: String,
        colours: Map<String, Long>,
        read: Int,
        shape: HeliShape,
        derived: Int = 0,
    ): HeliResult {
        val dropped = linkedSetOf<HeliUnsupported>()
        if (derived > 0) dropped += HeliUnsupported.DERIVED_COLOURS
        // Which roles actually reached a field, rather than which ones *could*.
        // The count used to be "how many of the file's roles are named in any
        // of the lists below", which over-reported: `KEY_TEXT` and `KEY_ICON`
        // are both named by [KeyTextRoles] and only the first present one is
        // read, so a theme stating both was told it used both.
        val landed = linkedSetOf<String>()
        fun of(roles: List<String>): Long? {
            for (role in roles) {
                val colour = colours[role] ?: continue
                landed += role
                return colour
            }
            return null
        }

        val board = of(BoardRoles) ?: return HeliResult.NotATheme
        val key = of(KeyRoles) ?: board
        // Neither shape says whether it is a day or a night theme, so the
        // board decides, the same test `onColorFor` makes about text.
        val night = Color(board).luminance() < DARK_THRESHOLD
        val keySurface = composite(key, board)
        val accent = of(AccentRoles)
        val enterBackground = of(EnterRoles) ?: accent
        val spaceBackground = of(SpaceRoles)
        val spaceText = of(SpaceTextRoles)
        val shiftIcon = of(ShiftRoles)

        val theme = ThemeSpec(
            id = "",
            name = name,
            dark = night,
            boardBackground = board,
            keyBackground = key,
            keyText = of(KeyTextRoles)?.legibleOn(keySurface, dropped) ?: onColorFor(keySurface),
            modifierKeyBackground = of(ModifierRoles) ?: key,
            modifierKeyText = of(ModifierTextRoles),
            enterKeyBackground = enterBackground ?: key,
            // Not nullable, unlike its neighbours: a theme that names no enter
            // colour still has to have a legible one on whatever the key is.
            enterKeyText = of(EnterTextRoles)
                ?: onColorFor(composite(enterBackground ?: key, board)),
            hintText = of(HintRoles)?.takeIf { it.isVisible() },
            popupBackground = of(PopupRoles),
            popupText = of(PopupTextRoles),
            suggestionBarBackground = of(StripRoles),
            suggestionText = of(SuggestionRoles),
            secondaryText = of(SecondaryRoles)?.takeIf { it.isVisible() },
            toolbarIcon = of(ToolRoles),
            toolCircleBackground = of(ToolBackgroundRoles),
            toolCircleActiveBackground = of(ToolActiveRoles),
            chipBackground = of(ChipRoles),
            chipText = of(ChipTextRoles),
            chipActiveBackground = of(ChipActiveRoles),
            // Read after the shared chip lists, which name the same roles, so
            // a theme stating only these still has a card colour too.
            emojiSearchBackground = of(EmojiSearchRoles),
            emojiSearchText = of(EmojiSearchTextRoles),
            clipboardChipBackground = of(ClipChipRoles),
            clipboardChipText = of(ClipChipTextRoles),
            navigationBarBackground = of(NavBarRoles),
            oneHandedPanelIcon = of(OneHandedRoles),
            accent = accent ?: enterBackground ?: onColorFor(board),
            gestureTrailColor = of(GestureRoles),
            keyOverrides = buildMap {
                if (spaceBackground != null || spaceText != null) {
                    put(KEY_SPACE, KeyOverride(background = spaceBackground, text = spaceText))
                }
                if (shiftIcon != null) put(KEY_SHIFT, KeyOverride(text = shiftIcon))
            },
        )
        // A colour the file states that no field here read. Said plainly
        // rather than left to the user to work out from "N of M": the two
        // numbers never explained *which* colours.
        if (landed.size < colours.size) dropped += HeliUnsupported.UNUSED_COLOURS
        return HeliResult.Converted(
            theme = theme,
            shape = shape,
            coloursRead = read,
            coloursUsed = landed.size,
            dropped = dropped.toList(),
        )
    }

    /** A scraped text colour, unless it is unreadable where it landed. */
    private fun Long.legibleOn(background: Long, dropped: MutableSet<HeliUnsupported>): Long? {
        if (!isVisible()) return null
        val seen = composite(this, background)
        if (contrastRatio(seen, background) >= Readability.POOR_CONTRAST) return this
        dropped += HeliUnsupported.LOW_CONTRAST_FALLBACK
        return null
    }

    private fun Int.toArgbLong(): Long = toLong() and ARGB_MASK

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    // The ten names the colour screen writes, lowercase as the file has them.
    private const val C_BACKGROUND = "background"
    private const val C_KEYS = "keys"
    private const val C_FUNCTIONAL = "functional_keys"
    private const val C_SPACEBAR = "spacebar"
    private const val C_TEXT = "text"
    private const val C_HINT = "hint_text"
    private const val C_SUGGESTION = "suggestion_text"
    private const val C_SPACEBAR_TEXT = "spacebar_text"
    private const val C_ACCENT = "accent"
    private const val C_GESTURE = "gesture"

    internal val NamedColours = setOf(
        C_BACKGROUND, C_KEYS, C_FUNCTIONAL, C_SPACEBAR, C_TEXT, C_HINT,
        C_SUGGESTION, C_SPACEBAR_TEXT, C_ACCENT, C_GESTURE,
    )

    // The internal roles of the all-colours mode. Transcribed from the
    // published enum; a name this build does not know is read as the theme's
    // own name candidate and then discarded, which costs nothing.
    private const val R_MAIN_BACKGROUND = "MAIN_BACKGROUND"
    private const val R_KEY_BACKGROUND = "KEY_BACKGROUND"
    private const val R_KEY_TEXT = "KEY_TEXT"
    private const val R_KEY_HINT_TEXT = "KEY_HINT_TEXT"
    private const val R_KEY_ICON = "KEY_ICON"
    private const val R_FUNCTIONAL_KEY_BACKGROUND = "FUNCTIONAL_KEY_BACKGROUND"
    private const val R_FUNCTIONAL_KEY_TEXT = "FUNCTIONAL_KEY_TEXT"
    private const val R_ACTION_KEY_BACKGROUND = "ACTION_KEY_BACKGROUND"
    private const val R_ACTION_KEY_ICON = "ACTION_KEY_ICON"
    private const val R_ACTION_KEY_POPUP_KEYS_BACKGROUND = "ACTION_KEY_POPUP_KEYS_BACKGROUND"
    private const val R_SPACE_BAR_BACKGROUND = "SPACE_BAR_BACKGROUND"
    private const val R_SPACE_BAR_TEXT = "SPACE_BAR_TEXT"
    private const val R_SHIFT_KEY_ICON = "SHIFT_KEY_ICON"
    private const val R_POPUP_KEYS_BACKGROUND = "POPUP_KEYS_BACKGROUND"
    private const val R_POPUP_KEY_TEXT = "POPUP_KEY_TEXT"
    private const val R_POPUP_KEY_ICON = "POPUP_KEY_ICON"
    private const val R_KEY_PREVIEW_BACKGROUND = "KEY_PREVIEW_BACKGROUND"
    private const val R_KEY_PREVIEW_TEXT = "KEY_PREVIEW_TEXT"
    private const val R_MORE_SUGGESTIONS_BACKGROUND = "MORE_SUGGESTIONS_BACKGROUND"
    private const val R_MORE_SUGGESTIONS_WORD_BACKGROUND = "MORE_SUGGESTIONS_WORD_BACKGROUND"
    private const val R_MORE_SUGGESTIONS_HINT = "MORE_SUGGESTIONS_HINT"
    private const val R_STRIP_BACKGROUND = "STRIP_BACKGROUND"
    private const val R_SUGGESTED_WORD = "SUGGESTED_WORD"
    private const val R_SUGGESTION_TYPED_WORD = "SUGGESTION_TYPED_WORD"
    private const val R_SUGGESTION_VALID_WORD = "SUGGESTION_VALID_WORD"
    private const val R_SUGGESTION_AUTO_CORRECT = "SUGGESTION_AUTO_CORRECT"
    private const val R_REMOVE_SUGGESTION_ICON = "REMOVE_SUGGESTION_ICON"
    private const val R_TOOL_BAR_KEY = "TOOL_BAR_KEY"
    private const val R_TOOL_BAR_KEY_ENABLED_BACKGROUND = "TOOL_BAR_KEY_ENABLED_BACKGROUND"
    private const val R_TOOL_BAR_EXPAND_KEY = "TOOL_BAR_EXPAND_KEY"
    private const val R_TOOL_BAR_EXPAND_KEY_BACKGROUND = "TOOL_BAR_EXPAND_KEY_BACKGROUND"
    private const val R_GESTURE_TRAIL = "GESTURE_TRAIL"
    private const val R_GESTURE_PREVIEW = "GESTURE_PREVIEW"
    private const val R_NAVIGATION_BAR = "NAVIGATION_BAR"
    private const val R_ONE_HANDED_MODE_BUTTON = "ONE_HANDED_MODE_BUTTON"
    private const val R_CLIPBOARD_PIN = "CLIPBOARD_PIN"
    private const val R_CLIPBOARD_SUGGESTION_BACKGROUND = "CLIPBOARD_SUGGESTION_BACKGROUND"
    private const val R_CLIPBOARD_SUGGESTION_ICON = "CLIPBOARD_SUGGESTION_ICON"
    private const val R_AUTOFILL_BACKGROUND_CHIP = "AUTOFILL_BACKGROUND_CHIP"
    private const val R_EMOJI_CATEGORY = "EMOJI_CATEGORY"
    private const val R_EMOJI_CATEGORY_SELECTED = "EMOJI_CATEGORY_SELECTED"
    private const val R_EMOJI_KEY_TEXT = "EMOJI_KEY_TEXT"
    private const val R_EMOJI_SEARCH_TEXT = "EMOJI_SEARCH_TEXT"
    private const val R_EMOJI_SEARCH_BACKGROUND = "EMOJI_SEARCH_BACKGROUND"

    internal val AllColours = setOf(
        R_MAIN_BACKGROUND, R_KEY_BACKGROUND, R_KEY_TEXT, R_KEY_HINT_TEXT, R_KEY_ICON,
        R_FUNCTIONAL_KEY_BACKGROUND, R_FUNCTIONAL_KEY_TEXT, R_ACTION_KEY_BACKGROUND,
        R_ACTION_KEY_ICON, R_ACTION_KEY_POPUP_KEYS_BACKGROUND, R_SPACE_BAR_BACKGROUND,
        R_SPACE_BAR_TEXT, R_SHIFT_KEY_ICON, R_POPUP_KEYS_BACKGROUND, R_POPUP_KEY_TEXT,
        R_POPUP_KEY_ICON, R_KEY_PREVIEW_BACKGROUND, R_KEY_PREVIEW_TEXT,
        R_MORE_SUGGESTIONS_BACKGROUND, R_MORE_SUGGESTIONS_WORD_BACKGROUND,
        R_MORE_SUGGESTIONS_HINT, R_STRIP_BACKGROUND, R_SUGGESTED_WORD,
        R_SUGGESTION_TYPED_WORD, R_SUGGESTION_VALID_WORD, R_SUGGESTION_AUTO_CORRECT,
        R_REMOVE_SUGGESTION_ICON, R_TOOL_BAR_KEY, R_TOOL_BAR_KEY_ENABLED_BACKGROUND,
        R_TOOL_BAR_EXPAND_KEY, R_TOOL_BAR_EXPAND_KEY_BACKGROUND, R_GESTURE_TRAIL,
        R_GESTURE_PREVIEW, R_NAVIGATION_BAR, R_ONE_HANDED_MODE_BUTTON, R_CLIPBOARD_PIN,
        R_CLIPBOARD_SUGGESTION_BACKGROUND, R_CLIPBOARD_SUGGESTION_ICON,
        R_AUTOFILL_BACKGROUND_CHIP, R_EMOJI_CATEGORY, R_EMOJI_CATEGORY_SELECTED,
        R_EMOJI_KEY_TEXT, R_EMOJI_SEARCH_TEXT, R_EMOJI_SEARCH_BACKGROUND,
    )

    // Which role fills which field, best first. A named-shape key and an
    // all-shape role sit in the same list because only one of the two is ever
    // present, and one table is one thing to keep true.
    private val BoardRoles = listOf(R_MAIN_BACKGROUND, C_BACKGROUND)
    private val KeyRoles = listOf(R_KEY_BACKGROUND, C_KEYS)
    private val KeyTextRoles = listOf(R_KEY_TEXT, R_KEY_ICON, C_TEXT)
    private val ModifierRoles = listOf(R_FUNCTIONAL_KEY_BACKGROUND, C_FUNCTIONAL)
    private val ModifierTextRoles = listOf(R_FUNCTIONAL_KEY_TEXT)
    private val EnterRoles = listOf(R_ACTION_KEY_BACKGROUND)
    private val EnterTextRoles = listOf(R_ACTION_KEY_ICON)
    private val HintRoles = listOf(R_KEY_HINT_TEXT, C_HINT)
    private val PopupRoles = listOf(
        R_POPUP_KEYS_BACKGROUND, R_KEY_PREVIEW_BACKGROUND, R_MORE_SUGGESTIONS_BACKGROUND,
        // The enter key's own long-press sheet. The same bubble here, and the
        // only role a theme that styles nothing but the action key will state.
        R_ACTION_KEY_POPUP_KEYS_BACKGROUND,
    )
    private val PopupTextRoles = listOf(R_POPUP_KEY_TEXT, R_POPUP_KEY_ICON, R_KEY_PREVIEW_TEXT)
    private val StripRoles = listOf(R_STRIP_BACKGROUND)
    private val SuggestionRoles = listOf(
        R_SUGGESTION_TYPED_WORD, R_SUGGESTED_WORD, C_SUGGESTION, R_EMOJI_KEY_TEXT,
    )
    private val SecondaryRoles = listOf(
        R_MORE_SUGGESTIONS_HINT, R_SUGGESTION_VALID_WORD, R_EMOJI_CATEGORY,
    )
    private val ToolRoles = listOf(R_TOOL_BAR_KEY, R_TOOL_BAR_EXPAND_KEY, R_REMOVE_SUGGESTION_ICON)
    private val ToolBackgroundRoles = listOf(R_TOOL_BAR_EXPAND_KEY_BACKGROUND)
    private val ToolActiveRoles = listOf(R_TOOL_BAR_KEY_ENABLED_BACKGROUND)
    // The emoji board's own roles answer here too. This keyboard draws the
    // emoji search field as a chip and its glyphs as suggestion text, so a
    // theme that states them has stated those surfaces — and HeliBoard's
    // all-colours export always does, which is why seven of its roles used to
    // be read and then thrown away.
    private val ChipRoles = listOf(
        R_CLIPBOARD_SUGGESTION_BACKGROUND, R_AUTOFILL_BACKGROUND_CHIP,
        R_EMOJI_SEARCH_BACKGROUND, R_MORE_SUGGESTIONS_WORD_BACKGROUND,
    )
    private val ChipTextRoles = listOf(
        R_CLIPBOARD_SUGGESTION_ICON, R_CLIPBOARD_PIN, R_EMOJI_SEARCH_TEXT,
    )
    private val ChipActiveRoles = listOf(R_EMOJI_CATEGORY_SELECTED)
    // The two surfaces with roles of their own (HeliBoard v4.0 and v4.1), and
    // fields of their own here (gap list item 28): a theme that colours them
    // apart from the other chips keeps the difference on import.
    private val EmojiSearchRoles = listOf(R_EMOJI_SEARCH_BACKGROUND)
    private val EmojiSearchTextRoles = listOf(R_EMOJI_SEARCH_TEXT)
    private val ClipChipRoles = listOf(R_CLIPBOARD_SUGGESTION_BACKGROUND)
    private val ClipChipTextRoles = listOf(R_CLIPBOARD_SUGGESTION_ICON)
    private val NavBarRoles = listOf(R_NAVIGATION_BAR)
    private val OneHandedRoles = listOf(R_ONE_HANDED_MODE_BUTTON)
    private val AccentRoles = listOf(C_ACCENT, R_SUGGESTION_AUTO_CORRECT, R_ACTION_KEY_BACKGROUND)
    private val GestureRoles = listOf(R_GESTURE_TRAIL, R_GESTURE_PREVIEW, C_GESTURE)
    private val SpaceRoles = listOf(R_SPACE_BAR_BACKGROUND, C_SPACEBAR)
    private val SpaceTextRoles = listOf(R_SPACE_BAR_TEXT, C_SPACEBAR_TEXT)
    private val ShiftRoles = listOf(R_SHIFT_KEY_ICON)

    /**
     * Every role any list above names.
     *
     * Not the count any more — that is now the roles a conversion actually
     * read, which is a different and smaller number (see `convert`). Kept as
     * the thing `HeliRoleCoverageTest` holds the tables to: a role in
     * [AllColours] that appears in none of the lists is one the import silently
     * throws away, which is how seven of them went unnoticed.
     */
    internal val Landing: Set<String> = listOf(
        BoardRoles, KeyRoles, KeyTextRoles, ModifierRoles, ModifierTextRoles, EnterRoles,
        EnterTextRoles, HintRoles, PopupRoles, PopupTextRoles, StripRoles, SuggestionRoles,
        SecondaryRoles, ToolRoles, ToolBackgroundRoles, ToolActiveRoles, ChipRoles,
        ChipTextRoles, ChipActiveRoles, NavBarRoles, OneHandedRoles, AccentRoles,
        GestureRoles, SpaceRoles, SpaceTextRoles, ShiftRoles, EmojiSearchRoles,
        EmojiSearchTextRoles, ClipChipRoles, ClipChipTextRoles,
    ).flatten().toSet()

    /** The [KeyOverride] map keys for the two keys with colours of their own. */
    private const val KEY_SPACE = "SPACE"
    private const val KEY_SHIFT = "SHIFT"

    private const val DEFAULT_NAME = "Imported theme"
    private const val PAIR_SIZE = 2
    private const val BASE36 = 36
    private const val ARGB_MASK = 0xFFFFFFFFL
    private const val DARK_THRESHOLD = 0.5f

    private val PRINTABLE = 0x20..0x7E
}

/** What reading a HeliBoard theme produced. */
sealed interface HeliResult {

    data class Converted(
        val theme: ThemeSpec,
        val shape: HeliShape,
        /** Colours the file states. */
        val coloursRead: Int,
        /** How many of them had somewhere to go here. */
        val coloursUsed: Int,
        /** What the file asked for that did not come across whole. */
        val dropped: List<HeliUnsupported> = emptyList(),
    ) : HeliResult

    /** Not one of the two shapes, or a colour object with nothing in it. */
    data object NotATheme : HeliResult
}

/**
 * Something a HeliBoard theme asked for that did not come across whole.
 *
 * The `.flex` and Gboard imports have said this from the start and this one
 * never did — it showed "N of M colours" and left the user to work out which
 * M − N, which for the all-colours export is nearly always the emoji board and
 * for the named export is nearly always nothing at all.
 */
enum class HeliUnsupported {

    /**
     * The file left colours for the keyboard to work out, which is what the
     * other keyboard's "auto" flag means, and what this one does with them.
     *
     * Not a loss so much as a thing worth saying: the user sees a theme with
     * colours they did not choose, and the honest explanation is that their
     * file did not choose them either.
     */
    DERIVED_COLOURS,

    /** The file states colours for parts of that keyboard this one has not. */
    UNUSED_COLOURS,

    /** A scraped text colour was unreadable on its background and was dropped. */
    LOW_CONTRAST_FALLBACK,
}

/** Which of the other keyboard's two export shapes a theme arrived in. */
enum class HeliShape {
    /** The ten named colours, plus whichever the other keyboard derived. */
    NAMED,

    /** Every internal colour role, which is the richer export. */
    ALL,
}

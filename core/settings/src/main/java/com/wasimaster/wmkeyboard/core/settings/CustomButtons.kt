package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A button the user made for the toolbar, the toolbox and the floating button:
 * another app's shortcut (#1213), a switch over one of the keyboard's own
 * settings, or a jump to a settings page (#1236).
 *
 * Fixed tools are [ToolbarTool] values; these cannot be, since there is one
 * per shortcut the user picks, so what a button is lives in a list of its
 * own. Where it is lives in the tool lists, as a [ToolKey.Custom] entry
 * (#1345): pinned to the toolbar, at a cell of the toolbox grid, in a folder,
 * hidden, ranked by use, exactly as a built-in tool is, and dragged between
 * them the same way. The floating button names one by [id] (`custom:<id>`).
 */
data class CustomButton(
    /** Stable for the button's life: `c1`, `c2`, …; see [nextCustomButtonId]. */
    val id: String,
    val kind: CustomButtonKind,
    /**
     * What the button runs. For [CustomButtonKind.SHORTCUT], an `intent:` URI
     * (Android's own `Intent.toUri` form), a launcher shortcut
     * `ls:<profile serial>:<package>/<shortcut id>`, or an app screen only
     * Shizuku or root can open, `am:<package>/<activity class>` (#1405); for
     * [CustomButtonKind.SETTING], a [CustomToggle] name; for
     * [CustomButtonKind.PAGE], a [CustomPage] name; for
     * [CustomButtonKind.PLUGIN] (read only to migrate it), a plugin's id.
     */
    val target: String,
    /** The name it shows. Blank: the catalogue's own name for a switch or a page. */
    val label: String = "",
    /** The app a shortcut belongs to: its icon stands in when the shortcut's own is missing. */
    val packageName: String = "",
    /**
     * Where the button goes when it is first placed: pinned to the toolbar, or
     * (false) into the toolbox. Read only for a button no tool list holds yet
     * ([placedCustomButtons]): a new one, or one made before buttons were
     * tools (#1345). After that the lists decide, and a drag moves it.
     */
    val onToolbar: Boolean = true,
    /**
     * The icon it wears instead of its own (#1375): one out of a launcher
     * icon pack installed on the phone, `s:<package>/<drawable>`, on the
     * toolbar, in the toolbox and on the floating button alike. Blank wears
     * the shortcut's picture, or the switch's or page's glyph.
     */
    val icon: String = "",
) {
    /** The switch this button flips, or null for another kind (or one this build dropped). */
    val toggle: CustomToggle?
        get() = if (kind == CustomButtonKind.SETTING) CustomToggle.entries.firstOrNull { it.name == target } else null

    /** The page this button opens, or null. */
    val page: CustomPage?
        get() = if (kind == CustomButtonKind.PAGE) CustomPage.entries.firstOrNull { it.name == target } else null

    /** Whether this build can still run the button: a switch or a page it no longer has cannot. */
    val usable: Boolean
        get() = when (kind) {
            CustomButtonKind.SHORTCUT -> target.isNotBlank()
            CustomButtonKind.SETTING -> toggle != null
            CustomButtonKind.PAGE -> page != null
            // A tool of its own now ([ToolKey.Plugin]); the button is only
            // read to be moved there, by SettingsRepository.migratePluginTiles.
            CustomButtonKind.PLUGIN -> false
        }
}

/**
 * [PLUGIN] was a plugin's tile, before plugins became tools of their own
 * ([ToolKey.Plugin]). Kept so a stored one still decodes and can be migrated;
 * nothing makes one any more.
 */
enum class CustomButtonKind { SHORTCUT, SETTING, PAGE, PLUGIN }

/**
 * The settings a custom button can flip (#1236): on/off ones the keyboard
 * reads live, so a tap shows at once. The button is lit while [isOn].
 * Each entry is one getter and one setter, so the list is a chosen one.
 *
 * A hold on the button opens the switch's own row in Settings (#1356):
 * [settingRow] is that row's title resource name, which the settings app
 * finds, scrolls to and flashes; [route] is the screen to open when there is
 * no single row to point at.
 */
enum class CustomToggle(val settingRow: String?, val route: String) {
    NUMBER_ROW("layout_number_row_title", "layout/size"),
    KEY_HINTS("keypress_long_press_hints_title", "keypress/shortcuts"),
    SYMBOL_ROW("rows_symbol_row_title", "rows"),
    SUGGESTIONS("typing_suggestions_title", "typing/suggestions"),
    GLIDE_TYPING("typing_glide_typing_title", "typing/gestures"),
    AUTO_CAPITALIZE("typing_auto_capitalize_title", "typing/writing"),
    KEY_SOUND("hardware_sound_key_title", "keypress/haptics"),
    VIBRATION("keypress_haptics_title", "keypress/haptics"),
    KEY_POPUP("keypress_popup_title", "keypress/popup"),
    TOOLBAR_LABELS("appearance_toolbar_labels_title", "appearance/toolbar"),
    REDUCE_MOTION("accessibility_reduce_motion_title", "accessibility"),
    SECOND_TOOLBAR("appearance_second_toolbar_title", "appearance/toolbar"),
    // #1356.
    AUTOCORRECT("typing_autocorrect_title", "typing/corrections"),
    DOUBLE_SPACE_PERIOD("typing_double_space_title", "typing/writing"),
    SPACE_AFTER_PUNCTUATION("typing_auto_space_punctuation_title", "typing/writing"),
    ARROW_ROW("layout_arrow_row_title", "layout"),
    SPLIT_KEYBOARD("layout_split_title", "layout/onehanded"),
    INCOGNITO("privacy_incognito_title", "privacy"),
    /**
     * Dark keys rather than light: on for Dark or AMOLED. Off turns the mode
     * to Light, on to Dark, so a press always changes what the board looks
     * like, even from Follow the system. An automatic day and night theme
     * pair still decides over it.
     */
    DARK_THEME(null, "themes"),
    ;

    fun isOn(s: KeyboardSettings): Boolean = when (this) {
        NUMBER_ROW -> s.numberRow
        KEY_HINTS -> s.longPressHints
        SYMBOL_ROW -> s.symbolRowEnabled
        SUGGESTIONS -> s.suggestions
        GLIDE_TYPING -> s.gestureTyping
        AUTO_CAPITALIZE -> s.autoText.capitalize
        KEY_SOUND -> s.sound.enabled
        VIBRATION -> s.haptics.enabled
        KEY_POPUP -> s.popup.enabled
        TOOLBAR_LABELS -> s.toolbarLabels
        REDUCE_MOTION -> s.reduceMotion
        SECOND_TOOLBAR -> s.toolbarBehavior.secondRow
        AUTOCORRECT -> s.correction.enabled
        DOUBLE_SPACE_PERIOD -> s.autoText.doubleSpacePeriod
        SPACE_AFTER_PUNCTUATION -> s.autoText.spaceAfterPunctuation
        ARROW_ROW -> s.layoutBehavior.arrowRow
        SPLIT_KEYBOARD -> s.splitKeyboard
        INCOGNITO -> s.incognito
        DARK_THEME -> s.themeMode == ThemeMode.DARK || s.themeMode == ThemeMode.AMOLED
    }
}

/** Writes [toggle] as [on], through the same setter its settings row uses. */
suspend fun SettingsRepository.setCustomToggle(toggle: CustomToggle, on: Boolean) {
    when (toggle) {
        CustomToggle.NUMBER_ROW -> setNumberRow(on)
        CustomToggle.KEY_HINTS -> setLongPressHints(on)
        CustomToggle.SYMBOL_ROW -> setSymbolRowEnabled(on)
        CustomToggle.SUGGESTIONS -> setSuggestions(on)
        CustomToggle.GLIDE_TYPING -> setGestureTyping(on)
        CustomToggle.AUTO_CAPITALIZE -> setAutoCapitalize(on)
        CustomToggle.KEY_SOUND -> setKeySound(on)
        CustomToggle.VIBRATION -> setHapticFeedback(on)
        CustomToggle.KEY_POPUP -> setKeyPopup(on)
        CustomToggle.TOOLBAR_LABELS -> setToolbarLabels(on)
        CustomToggle.REDUCE_MOTION -> setReduceMotion(on)
        CustomToggle.SECOND_TOOLBAR -> setSecondToolbar(on)
        CustomToggle.AUTOCORRECT -> setAutocorrect(on)
        CustomToggle.DOUBLE_SPACE_PERIOD -> setDoubleSpacePeriod(on)
        CustomToggle.SPACE_AFTER_PUNCTUATION -> setAutoSpaceAfterPunctuation(on)
        CustomToggle.ARROW_ROW -> setArrowRow(on)
        CustomToggle.SPLIT_KEYBOARD -> setSplitKeyboard(on)
        CustomToggle.INCOGNITO -> setIncognito(on)
        CustomToggle.DARK_THEME -> setThemeMode(if (on) ThemeMode.DARK else ThemeMode.LIGHT)
    }
}

/**
 * The settings pages a custom button can open (#1236). [route] is a settings
 * route from the app's allowlist; [FAVOURITES] has none, being the favourites
 * list (#898) that opens over whichever screen is showing.
 */
enum class CustomPage(val route: String?) {
    FAVOURITES(null),
    HOME("home"),
    SEARCH("search"),
    THEMES("themes"),
    LANGUAGES("languages"),
    TYPING("typing"),
    DICTIONARY("dictionary"),
    CLIPBOARD("clipboard"),
    TOOLS("tools"),
    TOOLBAR("appearance/toolbar"),
    KEYPRESS("keypress"),
    CUSTOM_BUTTONS("custom_buttons"),
    // #1356.
    APPEARANCE("appearance"),
    LAYOUT("layout"),
    LAYOUT_SIZE("layout/size"),
    CORRECTIONS("typing/corrections"),
    SUGGESTIONS("typing/suggestions"),
    GESTURES("typing/gestures"),
    HAPTICS("keypress/haptics"),
    EMOJI("emoji"),
    VOICE("voice"),
    AI("ai"),
    PRIVACY("privacy"),
    BACKUP("backup"),
    ACCESSIBILITY("accessibility"),
    STATISTICS("statistics"),
    RESOURCES("resources"),
}

/** The most buttons the list keeps: past this the toolbar has no room left for its tools. */
const val MAX_CUSTOM_BUTTONS = 24

/** The longest name a button keeps. */
const val CUSTOM_BUTTON_LABEL_MAX = 40

/** What a [CustomButton.icon] out of an installed icon pack starts with; the icon library's `s:` source. */
const val CUSTOM_ICON_PACK_PREFIX = "s:"

/** Where launcher-shortcut targets start; see [CustomButton.target]. */
const val CUSTOM_LAUNCHER_SHORTCUT_PREFIX = "ls:"

/**
 * Where an app screen opened through the shell starts (#1405): one another
 * app may not start itself, because it asks for a permission or is not
 * exported. See [shellActivityOf].
 */
const val CUSTOM_SHELL_ACTIVITY_PREFIX = "am:"

private val shellPackagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
private val shellClassPattern = Regex("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*")

/**
 * The package and full class name an `am:` target names, or null when it is
 * not one or names anything but a plain component. A target can come from a
 * restored backup and ends up on a shell's command line, so nothing outside
 * Java's identifier characters gets through: no space, quote, semicolon or
 * dollar sign at the start.
 */
fun shellActivityOf(target: String): Pair<String, String>? {
    if (!target.startsWith(CUSTOM_SHELL_ACTIVITY_PREFIX)) return null
    val rest = target.removePrefix(CUSTOM_SHELL_ACTIVITY_PREFIX)
    val pkg = rest.substringBefore('/', "")
    var cls = rest.substringAfter('/', "")
    if (cls.startsWith('.')) cls = pkg + cls
    if (!shellPackagePattern.matches(pkg) || !shellClassPattern.matches(cls)) return null
    return pkg to cls
}

/** The `am:` target for [pkg]'s screen [cls]. */
fun shellActivityTarget(pkg: String, cls: String): String = "$CUSTOM_SHELL_ACTIVITY_PREFIX$pkg/$cls"

/** An id no button in this list uses yet. */
fun List<CustomButton>.nextCustomButtonId(): String {
    val taken = mapTo(HashSet()) { it.id }
    var n = 1
    while ("c$n" in taken) n++
    return "c$n"
}

fun List<CustomButton>.customButtonById(id: String?): CustomButton? =
    if (id == null) null else firstOrNull { it.id == id }

/** The ids of the buttons that can be drawn as tools: those this build can still run. */
fun List<CustomButton>.liveCustomToolIds(): Set<String> = mapNotNullTo(HashSet()) { button -> button.id.takeIf { button.usable } }

private fun cleanLabel(label: String): String =
    label.replace('\n', ' ').replace('\t', ' ').trim().take(CUSTOM_BUTTON_LABEL_MAX)

/** One id each, at most [MAX_CUSTOM_BUTTONS], names cleaned. */
fun List<CustomButton>.normalizedCustomButtons(): List<CustomButton> {
    val ids = HashSet<String>()
    return mapNotNull { button ->
        if (button.id.isBlank() || !ids.add(button.id) || button.target.isBlank()) null
        else button.copy(label = cleanLabel(button.label))
    }.take(MAX_CUSTOM_BUTTONS)
}

/**
 * The stored form: a JSON array of `{"id", "kind", "target", "label", "pkg", "bar", "icon"}`.
 * Empty encodes as null, so the preference is removed rather than kept empty.
 */
fun encodeCustomButtons(buttons: List<CustomButton>): String? {
    if (buttons.isEmpty()) return null
    return buildJsonArray {
        for (button in buttons) {
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(button.id))
                    put("kind", JsonPrimitive(button.kind.name))
                    put("target", JsonPrimitive(button.target))
                    if (button.label.isNotEmpty()) put("label", JsonPrimitive(button.label))
                    if (button.packageName.isNotEmpty()) put("pkg", JsonPrimitive(button.packageName))
                    if (!button.onToolbar) put("bar", JsonPrimitive(false))
                    if (button.icon.isNotEmpty()) put("icon", JsonPrimitive(button.icon))
                },
            )
        }
    }.toString()
}

/** The inverse of [encodeCustomButtons]; malformed entries and unknown kinds are dropped. */
fun decodeCustomButtons(raw: String?): List<CustomButton> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        runCatching {
            val obj = element.jsonObject
            val kindName = obj["kind"]?.jsonPrimitive?.contentOrNull
            CustomButton(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null,
                kind = CustomButtonKind.entries.firstOrNull { it.name == kindName } ?: return@runCatching null,
                target = obj["target"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null,
                label = obj["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                packageName = obj["pkg"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                onToolbar = obj["bar"]?.jsonPrimitive?.booleanOrNull ?: true,
                // Only an installed pack's icon: nothing else is drawn from here.
                icon = obj["icon"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith(CUSTOM_ICON_PACK_PREFIX) }.orEmpty(),
            )
        }.getOrNull()
    }.normalizedCustomButtons()
}

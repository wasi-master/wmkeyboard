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
 * per shortcut the user picks, so they live in a list of their own, the way
 * the apps on the toolbar (#1159) and the toolbox folders do. The toolbar
 * draws the ones [onToolbar] after its tools, folders and apps; the toolbox
 * draws the rest in a row above its grid. The floating button names one by
 * [id] (`custom:<id>`).
 */
data class CustomButton(
    /** Stable for the button's life: `c1`, `c2`, …; see [nextCustomButtonId]. */
    val id: String,
    val kind: CustomButtonKind,
    /**
     * What the button runs. For [CustomButtonKind.SHORTCUT], an `intent:` URI
     * (Android's own `Intent.toUri` form) or a launcher shortcut
     * `ls:<profile serial>:<package>/<shortcut id>`; for
     * [CustomButtonKind.SETTING], a [CustomToggle] name; for
     * [CustomButtonKind.PAGE], a [CustomPage] name.
     */
    val target: String,
    /** The name it shows. Blank: the catalogue's own name for a switch or a page. */
    val label: String = "",
    /** The app a shortcut belongs to: its icon stands in when the shortcut's own is missing. */
    val packageName: String = "",
    /** On the toolbar, or (false) in the toolbox. */
    val onToolbar: Boolean = true,
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
        }
}

enum class CustomButtonKind { SHORTCUT, SETTING, PAGE }

/**
 * The settings a custom button can flip (#1236): on/off ones the keyboard
 * reads live, so a tap shows at once. The button is lit while [isOn].
 * A small fixed list on purpose; each entry is one getter and one setter.
 */
enum class CustomToggle {
    NUMBER_ROW,
    KEY_HINTS,
    SYMBOL_ROW,
    SUGGESTIONS,
    GLIDE_TYPING,
    AUTO_CAPITALIZE,
    KEY_SOUND,
    VIBRATION,
    KEY_POPUP,
    TOOLBAR_LABELS,
    REDUCE_MOTION,
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
}

/** The most buttons the list keeps: past this the toolbar has no room left for its tools. */
const val MAX_CUSTOM_BUTTONS = 24

/** The longest name a button keeps. */
const val CUSTOM_BUTTON_LABEL_MAX = 40

/** Where launcher-shortcut targets start; see [CustomButton.target]. */
const val CUSTOM_LAUNCHER_SHORTCUT_PREFIX = "ls:"

/** An id no button in this list uses yet. */
fun List<CustomButton>.nextCustomButtonId(): String {
    val taken = mapTo(HashSet()) { it.id }
    var n = 1
    while ("c$n" in taken) n++
    return "c$n"
}

fun List<CustomButton>.customButtonById(id: String?): CustomButton? =
    if (id == null) null else firstOrNull { it.id == id }

/** The buttons the toolbar shows, in the user's order. */
fun List<CustomButton>.onToolbar(): List<CustomButton> = filter { it.onToolbar && it.usable }

/** The buttons the toolbox shows, in the user's order. */
fun List<CustomButton>.inToolbox(): List<CustomButton> = filter { !it.onToolbar && it.usable }

/** [this] with [button] moved [by] places (negative is up), clamped to the ends. */
fun List<CustomButton>.moved(button: String, by: Int): List<CustomButton> {
    val from = indexOfFirst { it.id == button }
    if (from < 0) return this
    val to = (from + by).coerceIn(0, size - 1)
    if (to == from) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

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
 * The stored form: a JSON array of `{"id", "kind", "target", "label", "pkg", "bar"}`.
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
            )
        }.getOrNull()
    }.normalizedCustomButtons()
}

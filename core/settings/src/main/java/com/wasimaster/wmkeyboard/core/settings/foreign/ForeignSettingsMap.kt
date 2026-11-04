package com.wasimaster.wmkeyboard.core.settings.foreign

import com.wasimaster.wmkeyboard.core.settings.SettingsBackup
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import com.wasimaster.wmkeyboard.core.settings.DefaultToolHoldActions
import com.wasimaster.wmkeyboard.core.settings.ToolHoldAction
import com.wasimaster.wmkeyboard.core.settings.ToolHoldActions
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/** The keyboards whose backups this app can read. */
enum class ForeignKeyboard {
    HELIBOARD,
    LEANTYPE,
    FUTO,
    FLORISBOARD,
    CLEVERKEYS,
    ANYSOFTKEYBOARD,
    KINETICA,
}

/**
 * The settings of ours another keyboard's backup can set. Each is one
 * preference here, so the picker can offer it on its own line and the user can
 * leave any of them behind.
 */
enum class ForeignSettingKind(
    /** The preference it writes. */
    val key: String,
    /** The [SettingsBackup.Entry.type] of that preference. */
    val type: String,
) {
    NUMBER_ROW("number_row", "boolean"),
    KEY_POPUP("key_popup", "boolean"),
    HAPTICS("haptic", "boolean"),
    HAPTIC_STRENGTH("haptic_strength", "int"),
    KEY_SOUND("key_sound", "boolean"),
    SOUND_VOLUME("key_sound_volume", "float"),
    AUTOCORRECT("autocorrect", "boolean"),
    AUTO_CAPITALIZE("auto_capitalize", "boolean"),
    DOUBLE_SPACE_PERIOD("double_space_period", "boolean"),
    SUGGESTIONS("suggestions", "boolean"),
    EMOJI_SUGGESTIONS("emoji_prediction", "boolean"),
    GESTURE_TYPING("gesture_typing", "boolean"),
    LONG_PRESS_DELAY("long_press_delay", "int"),
    KEY_HEIGHT("key_height", "int"),
    SPLIT_KEYBOARD("split_keyboard", "boolean"),
    THEME_MODE("theme_mode", "string"),
    /** What a press and hold on each toolbar tool runs, as the [ToolHoldActions] CSV. */
    TOOL_HOLD_ACTIONS("toolbar_hold_actions", "string"),
}

/**
 * One setting the backup states, translated: what it will write here, and the
 * value in our terms. [value] is what the picker shows, so it is already on our
 * scale (milliseconds, dp, a [ThemeMode] name).
 */
data class MappedSetting(
    val kind: ForeignSettingKind,
    val value: Any,
    /** The other keyboard's own key, for the record. */
    val foreignKey: String,
) {
    val entry: SettingsBackup.Entry get() = SettingsBackup.Entry(kind.key, kind.type, value)
}

/**
 * Which of another keyboard's settings mean the same thing as one of ours, and
 * how its value converts.
 *
 * Only the settings that mean *exactly* the same thing are here. A keyboard's
 * "autocorrect confidence" is a threshold on its own scorer, and copying the
 * number across would set ours to something nobody chose; those are left out
 * rather than guessed at. A value that only means "follow the system" (HeliBoard
 * writes −1 for the system vibration length) is left out too, since ours
 * already does that when nothing is set.
 */
object ForeignSettingsMap {

    // Our own ranges, the setters' limits, so a converted value never lands
    // somewhere a slider cannot reach. Declared first: the rule tables below
    // are built when the object is, in file order.
    private const val DEFAULT_KEY_HEIGHT_DP = 48
    private const val MIN_KEY_HEIGHT_DP = SettingsRepository.KEY_HEIGHT_MIN_DP
    private const val MAX_KEY_HEIGHT_DP = SettingsRepository.KEY_HEIGHT_MAX_DP
    private val HAPTIC_RANGE = 5..60
    private val LONG_PRESS_RANGE = 100..3000
    private const val MIN_VOLUME = 0.05f
    private const val SCALE_EPSILON = 0.01

    /** A rule: the setting it fills, the keys that may hold it, and the conversion. */
    private class Rule(
        val kind: ForeignSettingKind,
        val keys: List<String>,
        val convert: (Any) -> Any?,
    )

    private fun bool(kind: ForeignSettingKind, vararg keys: String) =
        Rule(kind, keys.toList()) { it as? Boolean ?: (it as? String)?.toBooleanStrictOrNull() }

    private fun intIn(kind: ForeignSettingKind, range: IntRange, vararg keys: String) =
        Rule(kind, keys.toList()) { raw ->
            val n = number(raw)?.roundToInt() ?: return@Rule null
            if (n < 0) null else n.coerceIn(range)
        }

    private fun volume(vararg keys: String) =
        Rule(ForeignSettingKind.SOUND_VOLUME, keys.toList()) { raw ->
            val v = number(raw)?.toFloat() ?: return@Rule null
            // HeliBoard writes −1 for "the system volume"; FUTO and ASK write
            // 0..1, ASK sometimes 0..100.
            when {
                v < 0f -> null
                v > 1f -> (v / 100f).coerceIn(MIN_VOLUME, 1f)
                else -> v.coerceIn(MIN_VOLUME, 1f)
            }
        }

    /** A height multiplier on the other keyboard, as a key height here. */
    private fun heightScale(vararg keys: String) =
        Rule(ForeignSettingKind.KEY_HEIGHT, keys.toList()) { raw ->
            val scale = number(raw) ?: return@Rule null
            if (scale <= 0.0) null else heightFromScale(scale)
        }

    private fun heightFromScale(scale: Double): Int? {
        // A multiplier of exactly 1 says nothing the default does not.
        if (kotlin.math.abs(scale - 1.0) < SCALE_EPSILON) return null
        return (DEFAULT_KEY_HEIGHT_DP * scale).roundToInt().coerceIn(MIN_KEY_HEIGHT_DP, MAX_KEY_HEIGHT_DP)
    }

    private fun number(raw: Any): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull()
        else -> null
    }

    private val heli = listOf(
        bool(ForeignSettingKind.NUMBER_ROW, "show_number_row"),
        bool(ForeignSettingKind.KEY_POPUP, "popup_on"),
        bool(ForeignSettingKind.HAPTICS, "vibrate_on"),
        intIn(ForeignSettingKind.HAPTIC_STRENGTH, HAPTIC_RANGE, "vibration_duration_settings"),
        bool(ForeignSettingKind.KEY_SOUND, "sound_on"),
        volume("keypress_sound_volume"),
        bool(ForeignSettingKind.AUTOCORRECT, "auto_correction"),
        bool(ForeignSettingKind.AUTO_CAPITALIZE, "auto_cap"),
        bool(ForeignSettingKind.DOUBLE_SPACE_PERIOD, "use_double_space_period"),
        bool(ForeignSettingKind.SUGGESTIONS, "show_suggestions"),
        bool(ForeignSettingKind.EMOJI_SUGGESTIONS, "show_emoji_suggestions", "emoji_suggestions"),
        bool(ForeignSettingKind.GESTURE_TYPING, "gesture_input"),
        intIn(ForeignSettingKind.LONG_PRESS_DELAY, LONG_PRESS_RANGE, "key_longpress_timeout"),
        // HeliBoard suffixes sizing keys with `_<landscape>_<folded>`, LeanType
        // with `_<landscape>` only. Portrait, unfolded, either way.
        heightScale("keyboard_height_scale_false_false", "keyboard_height_scale_false", "keyboard_height_scale"),
        bool(ForeignSettingKind.SPLIT_KEYBOARD, "split_keyboard"),
    )

    private val futo = listOf(
        bool(ForeignSettingKind.NUMBER_ROW, "pref_enable_number_row"),
        bool(ForeignSettingKind.KEY_POPUP, "popup_on"),
        bool(ForeignSettingKind.HAPTICS, "vibrate_on"),
        intIn(ForeignSettingKind.HAPTIC_STRENGTH, HAPTIC_RANGE, "vibration_duration"),
        bool(ForeignSettingKind.KEY_SOUND, "sound_on"),
        volume("key_sound_volume", "pref_keypress_sound_volume"),
        bool(ForeignSettingKind.AUTOCORRECT, "pref_key_auto_correction"),
        bool(ForeignSettingKind.AUTO_CAPITALIZE, "auto_cap"),
        bool(ForeignSettingKind.DOUBLE_SPACE_PERIOD, "pref_key_use_double_space_period"),
        bool(ForeignSettingKind.SUGGESTIONS, "show_suggestions"),
        bool(ForeignSettingKind.EMOJI_SUGGESTIONS, "suggestEmojis"),
        bool(ForeignSettingKind.GESTURE_TYPING, "gesture_input"),
        intIn(ForeignSettingKind.LONG_PRESS_DELAY, LONG_PRESS_RANGE, "pref_key_longpress_timeout"),
    )

    private val floris = listOf(
        bool(ForeignSettingKind.NUMBER_ROW, "keyboard__number_row"),
        bool(ForeignSettingKind.KEY_POPUP, "keyboard__popup_enabled"),
        bool(ForeignSettingKind.HAPTICS, "input_feedback__haptic_enabled"),
        bool(ForeignSettingKind.KEY_SOUND, "input_feedback__audio_enabled"),
        bool(ForeignSettingKind.AUTO_CAPITALIZE, "correction__auto_capitalization", "typing__auto_capitalization"),
        bool(ForeignSettingKind.DOUBLE_SPACE_PERIOD, "correction__double_space_period"),
        bool(ForeignSettingKind.SUGGESTIONS, "suggestion__enabled"),
        bool(ForeignSettingKind.GESTURE_TYPING, "glide__enabled"),
        intIn(ForeignSettingKind.LONG_PRESS_DELAY, LONG_PRESS_RANGE, "keyboard__long_press_delay"),
    )

    private val cleverKeys = listOf(
        bool(ForeignSettingKind.NUMBER_ROW, "number_row"),
        bool(ForeignSettingKind.HAPTICS, "vibration_enabled"),
        bool(ForeignSettingKind.AUTOCORRECT, "autocorrect_enabled"),
        bool(ForeignSettingKind.AUTO_CAPITALIZE, "autocapitalisation"),
        bool(ForeignSettingKind.DOUBLE_SPACE_PERIOD, "double_space_to_period"),
        bool(ForeignSettingKind.SUGGESTIONS, "word_prediction_enabled"),
        bool(ForeignSettingKind.GESTURE_TYPING, "swipe_typing_enabled"),
    )

    private val anySoftKeyboard = listOf(
        bool(ForeignSettingKind.KEY_POPUP, "key_press_preview_popup"),
        intIn(ForeignSettingKind.HAPTIC_STRENGTH, HAPTIC_RANGE, "settings_key_vibrate_on_key_press_duration_int"),
        bool(ForeignSettingKind.KEY_SOUND, "sound_on"),
        volume("custom_sound_volume"),
        bool(ForeignSettingKind.AUTO_CAPITALIZE, "auto_caps"),
        bool(ForeignSettingKind.SUGGESTIONS, "candidates_on"),
        bool(ForeignSettingKind.GESTURE_TYPING, "settings_key_gesture_typing"),
    )

    /**
     * Kinetica's settings, as its backup's `pref` records name them. Its
     * autocorrect is a level (`off`, `normal`, `aggressive`), of which only
     * off and on mean the same thing here; its layout mode places the board
     * (`full`, `left`, `right`, `split`, `one_handed`…), of which only split
     * and full width say whether the board is split.
     */
    private val kinetica = listOf(
        bool(ForeignSettingKind.NUMBER_ROW, "pref_number_row"),
        bool(ForeignSettingKind.HAPTICS, "pref_vibration"),
        Rule(ForeignSettingKind.AUTOCORRECT, listOf("pref_autocorrect_level")) { raw ->
            when (raw as? String) {
                "off" -> false
                "normal", "aggressive" -> true
                else -> null
            }
        },
        bool(ForeignSettingKind.AUTO_CAPITALIZE, "pref_auto_capitalize"),
        bool(ForeignSettingKind.DOUBLE_SPACE_PERIOD, "pref_double_space_period"),
        intIn(ForeignSettingKind.LONG_PRESS_DELAY, LONG_PRESS_RANGE, "pref_long_press_ms"),
        Rule(ForeignSettingKind.SPLIT_KEYBOARD, listOf("pref_layout_mode")) { raw ->
            when (raw as? String) {
                "split" -> true
                "full" -> false
                else -> null
            }
        },
    )

    /**
     * Every setting in [prefs] that has a counterpart here, in the order the
     * picker lists them. A kind appears at most once.
     */
    fun map(keyboard: ForeignKeyboard, prefs: Map<String, Any>): List<MappedSetting> {
        val rules = when (keyboard) {
            ForeignKeyboard.HELIBOARD, ForeignKeyboard.LEANTYPE -> heli
            ForeignKeyboard.FUTO -> futo
            ForeignKeyboard.FLORISBOARD -> floris
            ForeignKeyboard.CLEVERKEYS -> cleverKeys
            ForeignKeyboard.ANYSOFTKEYBOARD -> anySoftKeyboard
            ForeignKeyboard.KINETICA -> kinetica
        }
        val out = LinkedHashMap<ForeignSettingKind, MappedSetting>()
        for (rule in rules) {
            if (rule.kind in out) continue
            for (key in rule.keys) {
                val raw = prefs[key] ?: continue
                val value = rule.convert(raw) ?: continue
                out[rule.kind] = MappedSetting(rule.kind, value, key)
                break
            }
        }
        // The extras no single rule can express.
        if (ForeignSettingKind.KEY_HEIGHT !in out && keyboard == ForeignKeyboard.FUTO) {
            futoHeight(prefs)?.let { out[ForeignSettingKind.KEY_HEIGHT] = it }
        }
        if (ForeignSettingKind.SPLIT_KEYBOARD !in out && keyboard == ForeignKeyboard.FUTO) {
            futoSplit(prefs)?.let { out[ForeignSettingKind.SPLIT_KEYBOARD] = it }
        }
        themeMode(keyboard, prefs)?.let { out[ForeignSettingKind.THEME_MODE] = it }
        if (keyboard == ForeignKeyboard.HELIBOARD || keyboard == ForeignKeyboard.LEANTYPE) {
            heliToolHolds(prefs)?.let { out[ForeignSettingKind.TOOL_HOLD_ACTIONS] = it }
        }
        return ForeignSettingKind.entries.mapNotNull { out[it] }
    }

    /**
     * FUTO's sizing is one JSON object per orientation, `keyboard_settings_portrait`,
     * holding a `heightMultiplier` among much else.
     */
    private fun futoHeight(prefs: Map<String, Any>): MappedSetting? {
        val obj = futoSizing(prefs) ?: return null
        val scale = (obj["heightMultiplier"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val height = heightFromScale(scale) ?: return null
        return MappedSetting(ForeignSettingKind.KEY_HEIGHT, height, FUTO_SIZING)
    }

    private fun futoSplit(prefs: Map<String, Any>): MappedSetting? {
        val obj = futoSizing(prefs) ?: return null
        val prefersSplit = (obj["prefersSplit"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: return null
        return MappedSetting(ForeignSettingKind.SPLIT_KEYBOARD, prefersSplit, FUTO_SIZING)
    }

    private fun futoSizing(prefs: Map<String, Any>): JsonObject? {
        val text = prefs[FUTO_SIZING] as? String ?: return null
        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    }

    /**
     * Light, dark or following the system, where the backup says so plainly.
     *
     * HeliBoard names its colour set: `light`, `dark` and `black` say which
     * mode outright, and `theme_auto_day_night` means it followed the system.
     * Any other name is a colour theme of its own, which comes across as a
     * theme rather than as a mode.
     */
    private fun themeMode(keyboard: ForeignKeyboard, prefs: Map<String, Any>): MappedSetting? {
        val mode: Pair<ThemeMode, String>? = when (keyboard) {
            ForeignKeyboard.HELIBOARD, ForeignKeyboard.LEANTYPE -> when {
                prefs["theme_auto_day_night"] == true -> ThemeMode.SYSTEM to "theme_auto_day_night"
                else -> when (prefs["theme_colors"] as? String) {
                    "light", "holo_white" -> ThemeMode.LIGHT to "theme_colors"
                    "dark", "darker" -> ThemeMode.DARK to "theme_colors"
                    "black" -> ThemeMode.AMOLED to "theme_colors"
                    "dynamic" -> ThemeMode.SYSTEM to "theme_colors"
                    else -> null
                }
            }
            ForeignKeyboard.FLORISBOARD -> when (prefs["theme__mode"] as? String) {
                "ALWAYS_DAY" -> ThemeMode.LIGHT to "theme__mode"
                "ALWAYS_NIGHT" -> ThemeMode.DARK to "theme__mode"
                "FOLLOW_SYSTEM", "FOLLOW_TIME" -> ThemeMode.SYSTEM to "theme__mode"
                else -> null
            }
            // Kinetica's brightness, which its theme comes across with too.
            ForeignKeyboard.KINETICA -> when (prefs["pref_theme_brightness"] as? String) {
                "light" -> ThemeMode.LIGHT to "pref_theme_brightness"
                "dark" -> ThemeMode.DARK to "pref_theme_brightness"
                "system" -> ThemeMode.SYSTEM to "pref_theme_brightness"
                else -> null
            }
            else -> null
        }
        return mode?.let { (value, key) -> MappedSetting(ForeignSettingKind.THEME_MODE, value.name, key) }
    }

    /**
     * The toolbar long presses a HeliBoard user changed (issue #639), as hold
     * bindings here.
     *
     * HeliBoard keeps them in `toolbar_custom_key_codes`, one
     * `KEY,tapCode,longPressCode` entry per customised key, `;`-separated, with
     * `null` for a code left alone (see its `readCustomKeyCodes`). Only the
     * long-press half has a home here: a tool's tap is the tool. A key nobody
     * customised holds to HeliBoard's built-in pairing, and those are the
     * pairings [DefaultToolHoldActions] already gives, so they need nothing
     * written.
     *
     * A code with no tool here (clear clipboard history, page start, the
     * gesture-data toggle) is left out, and so is HeliBoard's "repeat the key",
     * which here is the caret tools' own repeat setting rather than a binding.
     */
    internal fun heliToolHolds(prefs: Map<String, Any>): MappedSetting? {
        val text = prefs[HELI_TOOLBAR_CODES] as? String ?: return null
        val holds = LinkedHashMap<ToolbarTool, ToolHoldAction>()
        for (entry in text.split(';')) {
            val parts = entry.split(',')
            if (parts.size < 3) continue
            val tool = HELI_TOOLBAR_KEYS[parts[0].trim()] ?: continue
            val code = parts[2].trim().toIntOrNull() ?: continue
            val action = when (code) {
                HELI_UNSPECIFIED -> ToolHoldAction.None
                else -> HELI_KEY_CODES[code]?.let(ToolHoldAction::Run) ?: continue
            }
            // A tool holding to itself is a slow tap, and the pairing it
            // already has here needs no entry.
            if (action is ToolHoldAction.Run && (action.tool == tool || DefaultToolHoldActions[tool] == action.tool)) {
                continue
            }
            holds[tool] = action
        }
        if (holds.isEmpty()) return null
        return MappedSetting(ForeignSettingKind.TOOL_HOLD_ACTIONS, ToolHoldActions.encode(holds), HELI_TOOLBAR_CODES)
    }

    private const val FUTO_SIZING = "keyboard_settings_portrait"

    private const val HELI_TOOLBAR_CODES = "toolbar_custom_key_codes"

    /** HeliBoard's `KeyCode.UNSPECIFIED`: a long press that does nothing. */
    private const val HELI_UNSPECIFIED = 0

    /** HeliBoard's `ToolbarKey` names that have a tool here. */
    private val HELI_TOOLBAR_KEYS: Map<String, ToolbarTool> = mapOf(
        "VOICE" to ToolbarTool.VOICE,
        "CLIPBOARD" to ToolbarTool.CLIPBOARD,
        "NUMPAD" to ToolbarTool.NUMPAD,
        "DPAD" to ToolbarTool.TEXT_EDIT,
        "UNDO" to ToolbarTool.UNDO,
        "REDO" to ToolbarTool.REDO,
        "SETTINGS" to ToolbarTool.SETTINGS,
        "SELECT_ALL" to ToolbarTool.SELECT_ALL,
        "SELECT_WORD" to ToolbarTool.SELECT_WORD,
        "COPY" to ToolbarTool.COPY,
        "CUT" to ToolbarTool.CUT,
        "PASTE" to ToolbarTool.PASTE,
        "ONE_HANDED" to ToolbarTool.ONE_HANDED,
        "FLOATING" to ToolbarTool.FLOATING,
        "SPLIT" to ToolbarTool.SPLIT,
        "INCOGNITO" to ToolbarTool.INCOGNITO,
        "AUTOCORRECT" to ToolbarTool.AUTOCORRECT,
        "EMOJI" to ToolbarTool.EMOJI,
        "LEFT" to ToolbarTool.CURSOR_LEFT,
        "RIGHT" to ToolbarTool.CURSOR_RIGHT,
        "UP" to ToolbarTool.CURSOR_UP,
        "DOWN" to ToolbarTool.CURSOR_DOWN,
        "WORD_LEFT" to ToolbarTool.CURSOR_WORD_LEFT,
        "WORD_RIGHT" to ToolbarTool.CURSOR_WORD_RIGHT,
        "PAGE_UP" to ToolbarTool.PAGE_UP,
        "PAGE_DOWN" to ToolbarTool.PAGE_DOWN,
        "FULL_LEFT" to ToolbarTool.CURSOR_HOME,
        "FULL_RIGHT" to ToolbarTool.CURSOR_END,
    )

    /** HeliBoard's `KeyCode` values a hold can name that have a tool here. */
    private val HELI_KEY_CODES: Map<Int, ToolbarTool> = mapOf(
        -233 to ToolbarTool.VOICE,
        -213 to ToolbarTool.CLIPBOARD,
        -205 to ToolbarTool.NUMPAD,
        -10054 to ToolbarTool.TEXT_EDIT,
        -131 to ToolbarTool.UNDO,
        -132 to ToolbarTool.REDO,
        -301 to ToolbarTool.SETTINGS,
        -35 to ToolbarTool.SELECT_ALL,
        -34 to ToolbarTool.SELECT_WORD,
        -31 to ToolbarTool.COPY,
        -32 to ToolbarTool.CUT,
        -33 to ToolbarTool.PASTE,
        -10002 to ToolbarTool.ONE_HANDED,
        -109 to ToolbarTool.FLOATING,
        -113 to ToolbarTool.SPLIT,
        -244 to ToolbarTool.INCOGNITO,
        -245 to ToolbarTool.AUTOCORRECT,
        -212 to ToolbarTool.EMOJI,
        -232 to ToolbarTool.HIDE_KEYBOARD,
        -21 to ToolbarTool.CURSOR_LEFT,
        -22 to ToolbarTool.CURSOR_RIGHT,
        -23 to ToolbarTool.CURSOR_UP,
        -24 to ToolbarTool.CURSOR_DOWN,
        -10015 to ToolbarTool.CURSOR_WORD_LEFT,
        -10016 to ToolbarTool.CURSOR_WORD_RIGHT,
        -10010 to ToolbarTool.PAGE_UP,
        -10011 to ToolbarTool.PAGE_DOWN,
        -27 to ToolbarTool.CURSOR_HOME,
        -28 to ToolbarTool.CURSOR_END,
    )
}

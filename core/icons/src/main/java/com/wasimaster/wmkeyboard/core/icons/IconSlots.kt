package com.wasimaster.wmkeyboard.core.icons

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.icons.R

/** Which surface a slot belongs to, and the order the settings screen groups them in. */
enum class IconSlotGroup(@StringRes val titleRes: Int) {
    TOOL(R.string.core_icons_group_tools_title),
    KEY(R.string.core_icons_group_keys_title),
    CHROME(R.string.core_icons_group_toolbar_title),
    EMOJI_TAB(R.string.core_icons_group_emoji_tabs_title),
}

/**
 * One icon the user can replace.
 *
 * [id] is the stable name that travels in an icon pack and in the saved
 * overrides, so it must never change once shipped — renaming one silently
 * drops every user's customisation of it. Ids are lowercase `group.name`, and
 * deliberately contain no `,` or `=`: the overrides are persisted as a CSV of
 * `slot=source` pairs, the same shape as the per-tool colour overrides.
 *
 * [labelRes] is the wording of the row, resolved by the screen that draws it
 * rather than here, so the app follows a language change.
 *
 * [tool] is set for the [IconSlotGroup.TOOL] slots, and those carry no
 * [labelRes] of their own. The settings UI shows `toolTitle(tool)` for them, so
 * a tool's icon row and its own settings screen agree on what it is called.
 */
data class IconSlot(
    val id: String,
    val group: IconSlotGroup,
    @StringRes val labelRes: Int?,
    val tool: ToolbarTool? = null,
)

/**
 * Every icon a user can swap, as stable ids.
 *
 * This is the `core` half of the icon system: it names the slots but knows
 * nothing about vectors, so it stays Compose-free and JVM-testable. The
 * built-in glyph for each id lives in `ime/ui/IconDefaults.kt`, matching how
 * `Key.icon` stores a name that `KeyIcons` resolves at render time.
 *
 * The tool slots are *derived* from [ToolbarTool] rather than listed, so a new
 * tool becomes customisable the moment it is added to the enum — one less
 * exhaustive `when` to forget. The remaining groups are enumerated by hand
 * because they have no enum to hang off.
 *
 * Deliberately absent: the named layout glyphs in `KeyIcons`. Those are picked
 * per key by whoever authored the layout, so a pack that redefined them would
 * be changing that author's design rather than the app's chrome.
 */
object IconSlots {

    /** `tool.<enum name lowercased>` — the id for [tool]'s toolbar/toolbox icon. */
    fun forTool(tool: ToolbarTool): String = "tool.${tool.name.lowercase()}"

    // ---- key action slots ----

    const val KEY_SHIFT = "key.shift"
    const val KEY_SHIFT_ON = "key.shift_on"
    const val KEY_SHIFT_LOCK = "key.shift_lock"

    /**
     * The dedicated Caps Lock key a tablet board adds. Its own slot rather than
     * [KEY_SHIFT_LOCK]: that one is the shift key's face while caps lock is on,
     * and the two keys sit on the same board.
     */
    const val KEY_CAPS_LOCK = "key.caps_lock"
    const val KEY_BACKSPACE = "key.backspace"
    const val KEY_FORWARD_DELETE = "key.forward_delete"
    const val KEY_GLOBE = "key.globe"
    const val KEY_INPUT_METHOD_PICKER = "key.input_method_picker"
    const val KEY_EMOJI = "key.emoji"

    /** The four keys of the arrow row under the keyboard (#369, slots per #504). */
    const val KEY_ARROW_LEFT = "key.arrow_left"
    const val KEY_ARROW_UP = "key.arrow_up"
    const val KEY_ARROW_DOWN = "key.arrow_down"
    const val KEY_ARROW_RIGHT = "key.arrow_right"

    /** A key sending Tab: the tablet board's, or one built in the layout editor. */
    const val KEY_TAB = "key.tab"

    /**
     * One per enter action the field can ask for. `EnterAction.CUSTOM` has no
     * slot: the app supplied its own wording and the key draws that as text,
     * so there is no icon to replace.
     */
    const val KEY_ENTER = "key.enter"
    const val KEY_ENTER_SEARCH = "key.enter_search"
    const val KEY_ENTER_SEND = "key.enter_send"
    const val KEY_ENTER_GO = "key.enter_go"
    const val KEY_ENTER_NEXT = "key.enter_next"
    const val KEY_ENTER_PREVIOUS = "key.enter_previous"
    const val KEY_ENTER_DONE = "key.enter_done"

    // ---- toolbar chrome slots ----

    const val CHROME_TOOLBOX = "chrome.toolbox"
    const val CHROME_PANEL_BACK = "chrome.panel_back"
    const val CHROME_SUGGESTIONS_EXPAND = "chrome.suggestions_expand"
    const val CHROME_EMOJI_SHORTCUT = "chrome.emoji_shortcut"
    const val CHROME_SEARCH_CLOSE = "chrome.search_close"
    const val CHROME_INCOGNITO = "chrome.incognito"
    const val CHROME_POWER_SAVING = "chrome.power_saving"

    /** The toolbar-only view's button for a physical keyboard (#642), each way. */
    const val CHROME_SHOW_KEYS = "chrome.show_keys"
    const val CHROME_HIDE_KEYS = "chrome.hide_keys"
    const val CHROME_TOOLBOX_SEARCH = "chrome.toolbox_search"

    // ---- emoji tab slots ----

    const val EMOJI_TAB_SEARCH = "emoji_tab.search"
    const val EMOJI_TAB_RECENT = "emoji_tab.recent"
    const val EMOJI_TAB_MOST_USED = "emoji_tab.most_used"

    /** The Suggested tab (#1554): emoji for the words before the caret. */
    const val EMOJI_TAB_SUGGESTED = "emoji_tab.suggested"

    /**
     * `emoji_tab.<category>` for a catalog category name. Unknown categories
     * get an id too — the resolver falls back to the smiley for them, exactly
     * as the built-in map already did.
     */
    fun forEmojiCategory(category: String): String = "emoji_tab.$category"

    /**
     * Category name → the wording of its tab, in the order the tab strip shows
     * them. The names are the catalog's own and never change; only the wording
     * is translated.
     */
    private val emojiCategoryLabels: Map<String, Int> = linkedMapOf(
        "smileys" to R.string.core_icons_slot_emoji_smileys_label,
        "people" to R.string.core_icons_slot_emoji_people_label,
        "animals" to R.string.core_icons_slot_emoji_animals_label,
        "nature" to R.string.core_icons_slot_emoji_nature_label,
        "food" to R.string.core_icons_slot_emoji_food_label,
        "travel" to R.string.core_icons_slot_emoji_travel_label,
        "activities" to R.string.core_icons_slot_emoji_activities_label,
        "objects" to R.string.core_icons_slot_emoji_objects_label,
        "symbols" to R.string.core_icons_slot_emoji_symbols_label,
        "flags" to R.string.core_icons_slot_emoji_flags_label,
    )

    /** The catalog's Unicode categories, in the order the tab strip shows them. */
    val EMOJI_CATEGORIES: List<String> = emojiCategoryLabels.keys.toList()

    private val keySlots: List<IconSlot> = listOf(
        IconSlot(KEY_SHIFT, IconSlotGroup.KEY, R.string.core_icons_slot_shift_label),
        IconSlot(KEY_SHIFT_ON, IconSlotGroup.KEY, R.string.core_icons_slot_shift_on_label),
        IconSlot(KEY_SHIFT_LOCK, IconSlotGroup.KEY, R.string.core_icons_slot_caps_lock_label),
        IconSlot(KEY_CAPS_LOCK, IconSlotGroup.KEY, R.string.core_icons_slot_caps_lock_key_label),
        IconSlot(KEY_BACKSPACE, IconSlotGroup.KEY, R.string.core_icons_slot_backspace_label),
        IconSlot(KEY_FORWARD_DELETE, IconSlotGroup.KEY, R.string.core_icons_slot_forward_delete_label),
        IconSlot(KEY_ENTER, IconSlotGroup.KEY, R.string.core_icons_slot_enter_label),
        IconSlot(KEY_ENTER_SEARCH, IconSlotGroup.KEY, R.string.core_icons_slot_enter_search_label),
        IconSlot(KEY_ENTER_SEND, IconSlotGroup.KEY, R.string.core_icons_slot_enter_send_label),
        IconSlot(KEY_ENTER_GO, IconSlotGroup.KEY, R.string.core_icons_slot_enter_go_label),
        IconSlot(KEY_ENTER_NEXT, IconSlotGroup.KEY, R.string.core_icons_slot_enter_next_label),
        IconSlot(KEY_ENTER_PREVIOUS, IconSlotGroup.KEY, R.string.core_icons_slot_enter_previous_label),
        IconSlot(KEY_ENTER_DONE, IconSlotGroup.KEY, R.string.core_icons_slot_enter_done_label),
        IconSlot(KEY_GLOBE, IconSlotGroup.KEY, R.string.core_icons_slot_globe_label),
        IconSlot(
            KEY_INPUT_METHOD_PICKER,
            IconSlotGroup.KEY,
            R.string.core_icons_slot_input_method_picker_label,
        ),
        IconSlot(KEY_EMOJI, IconSlotGroup.KEY, R.string.core_icons_slot_emoji_key_label),
        IconSlot(KEY_ARROW_LEFT, IconSlotGroup.KEY, R.string.core_icons_slot_arrow_left_label),
        IconSlot(KEY_ARROW_UP, IconSlotGroup.KEY, R.string.core_icons_slot_arrow_up_label),
        IconSlot(KEY_ARROW_DOWN, IconSlotGroup.KEY, R.string.core_icons_slot_arrow_down_label),
        IconSlot(KEY_ARROW_RIGHT, IconSlotGroup.KEY, R.string.core_icons_slot_arrow_right_label),
        IconSlot(KEY_TAB, IconSlotGroup.KEY, R.string.core_icons_slot_tab_label),
    )

    private val chromeSlots: List<IconSlot> = listOf(
        IconSlot(CHROME_TOOLBOX, IconSlotGroup.CHROME, R.string.core_icons_slot_toolbox_label),
        IconSlot(CHROME_PANEL_BACK, IconSlotGroup.CHROME, R.string.core_icons_slot_panel_back_label),
        IconSlot(CHROME_SUGGESTIONS_EXPAND, IconSlotGroup.CHROME, R.string.core_icons_slot_suggestions_expand_label),
        IconSlot(CHROME_EMOJI_SHORTCUT, IconSlotGroup.CHROME, R.string.core_icons_slot_emoji_shortcut_label),
        IconSlot(CHROME_SEARCH_CLOSE, IconSlotGroup.CHROME, R.string.core_icons_slot_search_close_label),
        IconSlot(CHROME_INCOGNITO, IconSlotGroup.CHROME, R.string.core_icons_slot_incognito_label),
        IconSlot(CHROME_POWER_SAVING, IconSlotGroup.CHROME, R.string.core_icons_slot_power_saving_label),
        IconSlot(CHROME_SHOW_KEYS, IconSlotGroup.CHROME, R.string.core_icons_slot_show_keys_label),
        IconSlot(CHROME_HIDE_KEYS, IconSlotGroup.CHROME, R.string.core_icons_slot_hide_keys_label),
        IconSlot(CHROME_TOOLBOX_SEARCH, IconSlotGroup.CHROME, R.string.core_icons_slot_toolbox_search_label),
    )

    private val emojiTabSlots: List<IconSlot> = buildList {
        add(IconSlot(EMOJI_TAB_SEARCH, IconSlotGroup.EMOJI_TAB, CommonR.string.common_search))
        add(IconSlot(EMOJI_TAB_RECENT, IconSlotGroup.EMOJI_TAB, R.string.core_icons_slot_emoji_recent_label))
        add(IconSlot(EMOJI_TAB_MOST_USED, IconSlotGroup.EMOJI_TAB, R.string.core_icons_slot_emoji_most_used_label))
        add(IconSlot(EMOJI_TAB_SUGGESTED, IconSlotGroup.EMOJI_TAB, R.string.core_icons_slot_emoji_suggested_label))
        for ((category, labelRes) in emojiCategoryLabels) {
            add(IconSlot(forEmojiCategory(category), IconSlotGroup.EMOJI_TAB, labelRes))
        }
    }

    /** Every slot, grouped in [IconSlotGroup] order. */
    val all: List<IconSlot> = buildList {
        for (tool in ToolbarTool.entries) {
            // A tool slot carries no label of its own: the settings UI shows
            // toolTitle(tool), which is the wording the rest of the app uses.
            add(IconSlot(forTool(tool), IconSlotGroup.TOOL, labelRes = null, tool = tool))
        }
        addAll(keySlots)
        addAll(chromeSlots)
        addAll(emojiTabSlots)
    }

    private val byId: Map<String, IconSlot> = all.associateBy { it.id }

    /** The slot named [id], or null — an unknown id is dropped, never fatal. */
    fun byId(id: String?): IconSlot? = id?.let { byId[it] }

    /** Slots in [group], in declaration order. */
    fun inGroup(group: IconSlotGroup): List<IconSlot> = all.filter { it.group == group }

    /**
     * Whether [id] is safe to use as a file name inside a pack.
     *
     * Slot ids are lowercase letters, digits, `.` and `_` by construction, and
     * always start with a letter. This is the belt-and-braces check on an id
     * arriving from a downloaded pack: it has to rule out `..` and anything
     * beginning with a dot as well as the obvious path separators, because the
     * id becomes a file name.
     */
    fun isWellFormed(id: String): Boolean =
        id.isNotEmpty() &&
            id.length <= 64 &&
            id.first() in 'a'..'z' &&
            ".." !in id &&
            id.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }

    // ---- variants ----

    /**
     * Joins a slot id to the name of one of its alternative looks: `tool.gif@text`
     * is the `text` look of `tool.gif`.
     *
     * A pack ships a variant as a file of that name (`icons/tool.gif@text.svg`)
     * and lists the same key in its `slots`, so the variant rides every bit of
     * machinery a slot's own icon does — the store, the export, the reconcile
     * sweep — with no second list to keep in step. Neither half can contain the
     * separator, so the split is never ambiguous.
     */
    const val VARIANT_SEPARATOR = '@'

    fun variantKey(slot: String, variant: String): String = "$slot$VARIANT_SEPARATOR$variant"

    /** The slot half of a pack key: `tool.gif@text` → `tool.gif`, and a plain slot id is itself. */
    fun slotOf(key: String): String = key.substringBefore(VARIANT_SEPARATOR)

    /** The variant half of a pack key, or null when the key is a slot's own icon. */
    fun variantOf(key: String): String? {
        val at = key.indexOf(VARIANT_SEPARATOR)
        return if (at < 0) null else key.substring(at + 1)
    }

    /**
     * Whether [name] may name a variant: lowercase letters, digits and `_`,
     * starting with a letter, at most 32 long. No `.` at all, so a variant can
     * never smuggle a path segment into the file name it becomes.
     */
    fun isVariantNameWellFormed(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= 32 &&
            name.first() in 'a'..'z' &&
            name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    /**
     * Whether a pack may hold [key]: a slot this version knows, alone or with a
     * well-formed variant name after [VARIANT_SEPARATOR]. The check every key
     * arriving from a pack goes through before it becomes a file name.
     */
    fun isKnownKey(key: String): Boolean {
        val slot = slotOf(key)
        if (!isWellFormed(slot) || byId(slot) == null) return false
        val variant = variantOf(key) ?: return true
        return isVariantNameWellFormed(variant)
    }
}

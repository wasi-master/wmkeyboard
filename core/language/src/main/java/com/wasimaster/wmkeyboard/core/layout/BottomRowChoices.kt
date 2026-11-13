package com.wasimaster.wmkeyboard.core.layout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A bottom row drawn on every letters layout in place of the one it was laid
 * out with (competitor gap list item 15, AnySoftKeyboard's common bottom row).
 *
 * Applied by [arrangedBy] before the 🌐 and comma settings, which keep working
 * on the row it draws. A layer laid out by hand in the editor
 * ([KeyboardLayout.bottomRowAsLaidOut]) and a row a preset cannot stand in for
 * (a 12-key pad, a kana or CJK board; see `seatsForPreset`) keep their own.
 *
 * Stored by [name]; a name this build does not know reads as [AS_LAID_OUT].
 */
enum class BottomRowPreset {
    /** Each layout's own row. The default: nothing changes. */
    AS_LAID_OUT,

    /** `?123 , ␣ . ⏎`: no 🌐 key, so languages change on the spacebar. */
    CLASSIC,

    /** `?123 🌐 ␣ ⏎`, the iPhone's row. "Emoji key instead of 🌐" makes it the emoji key. */
    IPHONE,

    /** `?123 ␣ ⏎`: the widest spacebar, punctuation on the symbols page. */
    MINIMAL,

    /**
     * `?123 ☺ , 🌐 ␣ . ⏎`: an emoji key beside the 🌐 key, so a multilingual
     * board has both. The 🌐 key stays the 🌐 key here whatever "Emoji key
     * instead of 🌐" says, since the row already has its emoji key.
     */
    WITH_EMOJI,
    ;

    companion object {
        fun parse(name: String?): BottomRowPreset = entries.firstOrNull { it.name == name } ?: AS_LAID_OUT
    }
}

/**
 * The bottom-row key whose long-press popup carries the user's hold actions
 * (competitor gap list item 14: FlorisBoard's Fn key, FUTO's Enter hold menu).
 *
 * Stored by [name]; a name this build does not know reads as [ENTER].
 */
enum class HoldActionsKey {
    /** The enter key, after its line break entry in a field with an action. */
    ENTER,

    /** The comma key, after its marks. */
    COMMA,

    /** The 🌐 key's seat, or the emoji or tool key "Emoji key instead of 🌐" put there. */
    GLOBE,
    ;

    companion object {
        fun parse(name: String?): HoldActionsKey = entries.firstOrNull { it.name == name } ?: ENTER
    }
}

/**
 * [this] key with [actions] added to its long-press popup, after every
 * alternate it already has, or [this] when there is nothing to add.
 *
 * After, never before: the first entry is what a plain hold-and-release
 * commits with hold-to-select on, and that belongs to what the key offered
 * before the user's list existed (the comma's marks, enter's line break). An
 * action the popup already offers is not offered twice. A key whose hold is
 * taken by something else ([canHoldAlternates]) is left alone.
 *
 * The actions join the key's action block, so a layout that put that block
 * ahead of the characters ([Key.actionAlternatesAt]) has them there too.
 */
fun Key.withHoldActions(actions: List<KeyAlternate>): Key {
    if (actions.isEmpty() || !canHoldAlternates()) return this
    val added = actions.filter { candidate ->
        candidate.action != KeyAction.None && candidate.action !is KeyAction.Unknown &&
            actionAlternates.none { it.action == candidate.action && it.label == candidate.label }
    }.distinct()
    if (added.isEmpty()) return this
    return copy(actionAlternates = actionAlternates + added)
}

/**
 * Whether [key], at [rowIndex] of a grid whose last row is [lastRow], is the
 * key [choice] names. Asked of the key as laid out, before the emoji and field
 * rewrites, so the comma slot an email field turned into `@` is still the
 * comma's, and the 🌐 seat the emoji preference took over is still the 🌐's.
 */
fun HoldActionsKey.owns(key: Key, rowIndex: Int, lastRow: Int): Boolean = when (this) {
    HoldActionsKey.ENTER -> key.action == KeyAction.Enter
    HoldActionsKey.COMMA -> rowIndex == lastRow && key.roleIn(rowIndex, lastRow) == KeyRole.Comma
    HoldActionsKey.GLOBE -> rowIndex == lastRow && key.action == KeyAction.LanguageSwitch
}

/**
 * The stored form of the hold actions: a JSON array of [KeyAlternate]s in the
 * layout format, in the user's order, the shape [SpaceSwipeBindingCodec]
 * writes each value in.
 *
 * An entry this build cannot read, or an action tag from a newer build
 * ([KeyAction.Unknown]), costs that entry and no more, and the rest keep
 * their order.
 */
object HoldActionsCodec {
    fun decode(json: String?): List<KeyAlternate> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { layoutJson.parseToJsonElement(json) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return root.mapNotNull { element ->
            runCatching { layoutJson.decodeFromJsonElement<KeyAlternate>(element) }.getOrNull()
                ?.takeUnless { it.action is KeyAction.Unknown || it.action == KeyAction.None }
        }
    }

    fun encode(actions: List<KeyAlternate>): String =
        JsonArray(actions.map { layoutJson.encodeToJsonElement(it) }).toString()
}

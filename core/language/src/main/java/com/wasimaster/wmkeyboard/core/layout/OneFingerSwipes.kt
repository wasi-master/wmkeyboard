package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * What a fresh install binds to the one-finger swipes across the letters
 * (gap list item 44, AnySoftKeyboard's gesture map, LeanType's board swipe):
 * up shifts, down hides the keyboard, left deletes a word and right moves to
 * the next language. Only ever live while glide typing is off.
 */
val DefaultOneFingerSwipes: Map<SwipeDirection, KeyAlternate> = mapOf(
    SwipeDirection.UP to KeyAlternate(KeyAction.Shift),
    SwipeDirection.DOWN to KeyAlternate(KeyAction.Tool(ToolbarTool.HIDE_KEYBOARD)),
    SwipeDirection.LEFT to KeyAlternate(KeyAction.Edit(TextEditAction.DELETE_WORD)),
    SwipeDirection.RIGHT to KeyAlternate(KeyAction.LanguageSwitch),
)

/**
 * What a one-finger swipe [direction] runs: the user's binding, else the
 * default, else nothing. A binding to [KeyAction.None] is a cleared default
 * and answers null, as [FingerSwipe]'s do.
 */
fun Map<SwipeDirection, KeyAlternate>.oneFingerActionOf(direction: SwipeDirection): KeyAlternate? =
    (this[direction] ?: DefaultOneFingerSwipes[direction])?.takeUnless { it.action == KeyAction.None }

/**
 * The stored form of the one-finger bindings: one JSON object keyed by
 * [SwipeDirection.token] (`U`, `D`, `L`, `R`), each value a [KeyAlternate] in
 * the layout format. Same tolerance as [FingerSwipeCodec]: an entry this build
 * cannot read costs that entry alone.
 */
object OneFingerSwipeCodec {
    fun decode(json: String?): Map<SwipeDirection, KeyAlternate> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { layoutJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return root.entries.mapNotNull { (token, element) ->
            val direction = SwipeDirection.entries.firstOrNull { token.length == 1 && it.token == token[0] }
                ?: return@mapNotNull null
            val binding = runCatching { layoutJson.decodeFromJsonElement<KeyAlternate>(element) }.getOrNull()
                ?: return@mapNotNull null
            if (binding.action is KeyAction.Unknown) null else direction to binding
        }.toMap()
    }

    fun encode(map: Map<SwipeDirection, KeyAlternate>): String =
        JsonObject(
            map.entries
                .sortedBy { it.key.ordinal }
                .associate { (direction, binding) -> direction.token.toString() to layoutJson.encodeToJsonElement(binding) },
        ).toString()
}

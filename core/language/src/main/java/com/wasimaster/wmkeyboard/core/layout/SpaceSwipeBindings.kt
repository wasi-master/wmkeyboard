package com.wasimaster.wmkeyboard.core.layout

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A spacebar swipe that can be bound to a picked action, the way a
 * multi-finger swipe is (competitor gap list item 3: HeliBoard's D-pad
 * shortcut, AnySoftKeyboard's spacebar gesture map). The quick and the
 * hold-then-swipe slots run their binding when set to "another action"; the
 * swipe up runs its own when set the same way.
 *
 * [token] is the key in the stored map and nothing else, so a slot this build
 * lacks drops that entry alone.
 */
enum class SpaceSwipeSlot(val token: String) {
    SHORT("short"),
    LONG("long"),
    UP("up"),
    ;

    companion object {
        fun parse(token: String): SpaceSwipeSlot? = entries.firstOrNull { it.token == token }
    }
}

/**
 * What [slot] runs, or null when nothing is bound. A binding to
 * [KeyAction.None] is the user having cleared the slot and also answers null.
 */
fun Map<SpaceSwipeSlot, KeyAlternate>.bindingOf(slot: SpaceSwipeSlot): KeyAlternate? =
    this[slot]?.takeUnless { it.action == KeyAction.None }

/**
 * The stored form of the spacebar swipe bindings: one JSON object keyed by
 * [SpaceSwipeSlot.token], each value a [KeyAlternate] in the layout format,
 * the same shape [FingerSwipeCodec] writes, so a swipe can run anything a key
 * can and reads back through the same tolerant decoder.
 *
 * An entry this build cannot read, or an action tag from a newer build
 * ([KeyAction.Unknown]), costs that entry and no more.
 */
object SpaceSwipeBindingCodec {
    fun decode(json: String?): Map<SpaceSwipeSlot, KeyAlternate> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { layoutJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return root.entries.mapNotNull { (token, element) ->
            val slot = SpaceSwipeSlot.parse(token) ?: return@mapNotNull null
            val binding = runCatching { layoutJson.decodeFromJsonElement<KeyAlternate>(element) }.getOrNull()
                ?: return@mapNotNull null
            if (binding.action is KeyAction.Unknown) null else slot to binding
        }.toMap()
    }

    fun encode(map: Map<SpaceSwipeSlot, KeyAlternate>): String =
        JsonObject(
            map.entries
                .sortedBy { it.key.ordinal }
                .associate { (slot, binding) -> slot.token to layoutJson.encodeToJsonElement(binding) },
        ).toString()
}

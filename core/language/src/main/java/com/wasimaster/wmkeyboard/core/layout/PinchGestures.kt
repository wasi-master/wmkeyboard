package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Two fingers on the keys moving together (pinch) or apart (stretch), each
 * with an action of its own (gap list item 18, AnySoftKeyboard K1).
 *
 * [token] names the slot in the stored map and nothing else, so a gesture
 * this build lacks drops that slot alone.
 */
enum class PinchGesture(val token: String) {
    PINCH("pinch"),
    STRETCH("stretch"),
    ;

    companion object {
        fun parse(token: String): PinchGesture? = entries.firstOrNull { it.token == token }
    }
}

/**
 * What a fresh install binds: both run the split toggle. The grid reads the
 * direction, so a stretch only ever splits the board and a pinch only ever
 * merges it; neither undoes what the other did by accident.
 */
val DefaultPinchGestures: Map<PinchGesture, KeyAlternate> = mapOf(
    PinchGesture.PINCH to KeyAlternate(KeyAction.Tool(ToolbarTool.SPLIT)),
    PinchGesture.STRETCH to KeyAlternate(KeyAction.Tool(ToolbarTool.SPLIT)),
)

/**
 * What [gesture] runs: the user's binding, else the default, else nothing. A
 * binding to [KeyAction.None] is the user having cleared a default, and also
 * answers null.
 */
fun Map<PinchGesture, KeyAlternate>.actionOf(gesture: PinchGesture): KeyAlternate? =
    (this[gesture] ?: DefaultPinchGestures[gesture])?.takeUnless { it.action == KeyAction.None }

/**
 * The stored form of the pinch bindings: one JSON object keyed by
 * [PinchGesture.token], each value a [KeyAlternate] in the layout format, the
 * same shape [FingerSwipeCodec] writes. An entry this build cannot read costs
 * that entry and no more; an action tag from a newer build is dropped rather
 * than kept as a gesture that claims to do something and does nothing.
 */
object PinchGestureCodec {
    fun decode(json: String?): Map<PinchGesture, KeyAlternate> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { layoutJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return root.entries.mapNotNull { (token, element) ->
            val gesture = PinchGesture.parse(token) ?: return@mapNotNull null
            val binding = runCatching { layoutJson.decodeFromJsonElement<KeyAlternate>(element) }.getOrNull()
                ?: return@mapNotNull null
            if (binding.action is KeyAction.Unknown) null else gesture to binding
        }.toMap()
    }

    fun encode(map: Map<PinchGesture, KeyAlternate>): String =
        JsonObject(
            map.entries
                .sortedBy { it.key.ordinal }
                .associate { (gesture, binding) -> gesture.token to layoutJson.encodeToJsonElement(binding) },
        ).toString()
}

package com.wasimaster.wmkeyboard.core.layout

/**
 * The stored form of the keyboard's shake action (#1429): one [KeyAlternate]
 * in the layout format, so a shake can run anything a key, a multi-finger
 * swipe or a pinch can, and reads back through the same tolerant decoder.
 *
 * Nothing decodes to null: an empty value, a value this build cannot read,
 * an action tag from a newer build ([KeyAction.Unknown]) and a binding to
 * [KeyAction.None] all mean the shake does nothing, which is also the default.
 */
object ShakeActionCodec {
    fun decode(json: String?): KeyAlternate? {
        if (json.isNullOrBlank()) return null
        val binding = runCatching { layoutJson.decodeFromString<KeyAlternate>(json) }.getOrNull() ?: return null
        return binding.takeUnless { it.action is KeyAction.Unknown || it.action == KeyAction.None }
    }

    fun encode(binding: KeyAlternate): String = layoutJson.encodeToString(binding)
}

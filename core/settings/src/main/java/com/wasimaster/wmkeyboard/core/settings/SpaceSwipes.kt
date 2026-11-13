package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.SpaceSwipeSlot
import com.wasimaster.wmkeyboard.core.layout.bindingOf

/**
 * What a swipe up from the spacebar does. One answer where there used to be
 * two switches (`space_swipe_up_touchpad`, #444, and `space_swipe_up_numpad`),
 * plus [ACTION], which runs the binding stored for [SpaceSwipeSlot.UP]
 * (competitor gap list item 3, AnySoftKeyboard's utility keyboard on swipe up).
 */
enum class SpaceSwipeUp {
    NONE,

    /** The 2-D cursor touchpad for that drag (#444). */
    TOUCHPAD,

    /** The number pad, the way SwiftKey's swipe up opens it. */
    NUMPAD,

    /** The action picked for [SpaceSwipeSlot.UP], once, when the finger lifts. */
    ACTION,
}

/**
 * The stored swipe-up answer, else the two switches it replaced. Read so
 * nobody's setting changes on the update: the touchpad won when both were on,
 * so it wins here too. A name this build does not know falls back the same way.
 */
fun spaceSwipeUpOf(stored: String?, legacyTouchpad: Boolean?, legacyNumpad: Boolean?): SpaceSwipeUp {
    stored?.let { name -> SpaceSwipeUp.entries.firstOrNull { it.name == name } }?.let { return it }
    return when {
        legacyTouchpad == true -> SpaceSwipeUp.TOUCHPAD
        legacyNumpad == true -> SpaceSwipeUp.NUMPAD
        else -> SpaceSwipeUp.NONE
    }
}

/**
 * The key a discrete spacebar swipe presses, once per gesture, or null for a
 * slot that drags (cursor, language) or does nothing. [binding] is what the
 * slot is bound to, read only for [SpaceSwipeAction.ACTION]; an action slot
 * with nothing bound does nothing.
 */
fun SpaceSwipeAction.pressedAction(binding: KeyAlternate?): KeyAlternate? = when (this) {
    SpaceSwipeAction.NUMPAD -> KeyAlternate(KeyAction.Numpad)
    SpaceSwipeAction.KEYBOARDS -> KeyAlternate(KeyAction.InputMethodPicker)
    SpaceSwipeAction.SECONDARY_LAYOUT -> KeyAlternate(KeyAction.Tool(ToolbarTool.CUSTOM_LAYOUT))
    SpaceSwipeAction.ACTION -> binding?.takeUnless { it.action == KeyAction.None }
    SpaceSwipeAction.NONE, SpaceSwipeAction.LANGUAGE, SpaceSwipeAction.CURSOR -> null
}

/** The same for the swipe up: the number pad, the bound action, or null. */
fun SpaceSwipeUp.pressedAction(binding: KeyAlternate?): KeyAlternate? = when (this) {
    SpaceSwipeUp.NUMPAD -> KeyAlternate(KeyAction.Numpad)
    SpaceSwipeUp.ACTION -> binding?.takeUnless { it.action == KeyAction.None }
    SpaceSwipeUp.NONE, SpaceSwipeUp.TOUCHPAD -> null
}

/** What [slot] is bound to on this keyboard, or null. */
fun TextEditingSettings.spaceSwipeBinding(slot: SpaceSwipeSlot): KeyAlternate? =
    spaceSwipeActions.bindingOf(slot)
